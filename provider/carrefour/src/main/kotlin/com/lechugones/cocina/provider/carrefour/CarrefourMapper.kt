package com.lechugones.cocina.provider.carrefour

import com.lechugones.cocina.domain.money.Money
import com.lechugones.cocina.domain.product.BrandKind
import com.lechugones.cocina.domain.product.Product
import com.lechugones.cocina.domain.product.ProductKey
import com.lechugones.cocina.domain.quantity.Amount
import com.lechugones.cocina.domain.quantity.Quantity
import com.lechugones.cocina.domain.units.MeasureUnit
import com.lechugones.cocina.provider.ProviderProduct
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.util.Locale

/**
 * Maps Carrefour search documents to [ProviderProduct] (docs/providers/carrefour.md § Mapping).
 * The size and reference-price rules follow supermercapy's `parse_search_document`
 * (third_party/supermercapy, MIT): one selling unit holds [CarrefourDocument.conversionFactor]
 * of [CarrefourDocument.measureUnit], except for variable-weight food, whose factor is a
 * placeholder.
 */
object CarrefourMapper {
    const val PROVIDER_ID = "carrefour"
    const val SITE = "https://www.carrefour.es"
    private const val CATALOG = "food"

    /** Carrefour's own lines; any brand starting with "Carrefour" is one too. */
    val STORE_BRANDS =
        setOf(
            "Carrefour",
            "Carrefour Bio",
            "Carrefour Eco",
            "Carrefour Classic",
            "Carrefour Extra",
            "Carrefour Sensation",
            "Carrefour El Mercado",
            "Carrefour Original",
            "Carrefour Veggie",
            "Carrefour No Gluten",
            "Simpl",
            "De Nuestra Tierra",
        )
    private val STORE_UPPER = STORE_BRANDS.map { it.uppercase(Locale.ROOT) }.toSet()

    /** What the index writes when a product has no brand. */
    private val NO_BRAND = setOf("MARCA NACIONAL SIN MARCA", "PRIMER PRECIO SIN MARCA")

    private val packPattern = Regex("""(\d+)\s*[x×]\s*\d""", RegexOption.IGNORE_CASE)
    private val unitsInName = Regex("""pack de (\d+) unidades""", RegexOption.IGNORE_CASE)
    private val sizePattern = Regex("""(\d+(?:[.,]\d+)?)\s*(kg|g|ml|cl|l)\b""", RegexOption.IGNORE_CASE)

    /** Null when the document is not food, has no usable price, or no size can be found. */
    fun map(
        doc: CarrefourDocument,
        fetchedAt: Instant,
    ): ProviderProduct? {
        if (doc.activeFood == false) return null
        val euros = doc.price?.takeIf { it > 0 } ?: return null
        val price = Money.ofEuros(BigDecimal.valueOf(euros))
        val variable = doc.variableWeight == true
        val fromFactor = if (variable) null else sizeFromFactor(doc)
        val net: Quantity
        val approx: Boolean
        when {
            fromFactor != null -> {
                net = fromFactor
                approx = false
            }

            variable && (doc.averageWeight ?: 0.0) > 0 -> {
                net =
                    Quantity(
                        Amount.of(BigDecimal.valueOf(checkNotNull(doc.averageWeight)).setScale(0, RoundingMode.HALF_UP)),
                        MeasureUnit.G,
                    )
                approx = true
            }

            else -> {
                net = sizeFromText(doc.recipient) ?: sizeFromText(doc.name) ?: return null
                approx = variable
            }
        }
        val brand = brandName(doc.brand)
        val product =
            Product(
                key = ProductKey(PROVIDER_ID, doc.id),
                name = doc.name.trim(),
                net = net,
                price = price,
                brand = brand,
                brandKind = brandKind(doc.brand),
                unitCount = unitCount(doc),
                approx = approx,
                fetchedAt = fetchedAt,
                available = doc.available ?: true,
            )
        val reference = if (variable) null else referencePrice(euros, doc)
        return ProviderProduct(
            product = product,
            imageUrl = doc.images[CATALOG],
            webUrl = doc.urls[CATALOG]?.let { SITE + it },
            referencePrice = reference?.first,
            referenceFormat = reference?.second,
        )
    }

    fun brandKind(raw: String?): BrandKind {
        val upper = raw?.trim()?.uppercase(Locale.ROOT)
        return when {
            upper.isNullOrEmpty() || upper in NO_BRAND -> BrandKind.UNBRANDED
            upper in STORE_UPPER || upper.startsWith("CARREFOUR") -> BrandKind.STORE
            else -> BrandKind.NATIONAL
        }
    }

    /** The index writes brands in capitals: "LA CANTARA" becomes "La Cantara". */
    fun brandName(raw: String?): String? {
        val upper = raw?.trim()?.uppercase(Locale.ROOT)
        if (upper.isNullOrEmpty() || upper in NO_BRAND) return null
        STORE_BRANDS.firstOrNull { it.uppercase(Locale.ROOT) == upper }?.let { return it }
        return upper
            .lowercase(Locale.ROOT)
            .split(' ')
            .joinToString(" ") { word -> word.replaceFirstChar { it.titlecase(Locale.ROOT) } }
    }

    private fun sizeFromFactor(doc: CarrefourDocument): Quantity? {
        val factor = doc.conversionFactor?.takeIf { it > 0 } ?: return null
        val (unit, multiplier) =
            when (doc.measureUnit?.lowercase(Locale.ROOT)) {
                "kg" -> MeasureUnit.G to BigDecimal(1000)
                "l" -> MeasureUnit.ML to BigDecimal(1000)
                "ud" -> MeasureUnit.UD to BigDecimal.ONE
                "docena" -> MeasureUnit.UD to BigDecimal(12)
                else -> return null
            }
        val amount = BigDecimal.valueOf(factor).multiply(multiplier).setScale(0, RoundingMode.HALF_UP)
        return if (amount.signum() > 0) Quantity(Amount.of(amount), unit) else null
    }

    private fun sizeFromText(text: String?): Quantity? {
        if (text == null) return null
        val count =
            packPattern
                .find(text)
                ?.groupValues
                ?.get(1)
                ?.toIntOrNull() ?: 1
        val match = sizePattern.findAll(text).lastOrNull() ?: return null
        val (unit, multiplier) =
            when (match.groupValues[2].lowercase(Locale.ROOT)) {
                "g" -> MeasureUnit.G to BigDecimal.ONE
                "kg" -> MeasureUnit.G to BigDecimal(1000)
                "ml" -> MeasureUnit.ML to BigDecimal.ONE
                "cl" -> MeasureUnit.ML to BigDecimal.TEN
                else -> MeasureUnit.ML to BigDecimal(1000)
            }
        val amount =
            BigDecimal(match.groupValues[1].replace(',', '.'))
                .multiply(multiplier)
                .multiply(BigDecimal(count))
                .setScale(0, RoundingMode.HALF_UP)
        return if (amount.signum() > 0) Quantity(Amount.of(amount), unit) else null
    }

    private fun unitCount(doc: CarrefourDocument): Int? {
        val fromRecipient =
            doc.recipient?.let {
                packPattern
                    .find(it)
                    ?.groupValues
                    ?.get(1)
                    ?.toIntOrNull()
            }
        val fromName =
            unitsInName
                .find(doc.name)
                ?.groupValues
                ?.get(1)
                ?.toIntOrNull()
        return (fromRecipient ?: fromName)?.takeIf { it in 2..MAX_UNITS }
    }

    /** Price per kg, litre, piece or dozen: price ÷ factor, as supermercapy computes it. */
    private fun referencePrice(
        euros: Double,
        doc: CarrefourDocument,
    ): Pair<Money, String>? {
        val factor = doc.conversionFactor?.takeIf { it > 0 } ?: return null
        val format =
            when (doc.measureUnit?.lowercase(Locale.ROOT)) {
                "kg" -> "kg"
                "l" -> "L"
                "ud" -> "ud"
                "docena" -> "docena"
                else -> return null
            }
        val perUnit = BigDecimal.valueOf(euros).divide(BigDecimal.valueOf(factor), 4, RoundingMode.HALF_UP)
        return Money.ofEuros(perUnit) to format
    }

    private const val MAX_UNITS = 100
}
