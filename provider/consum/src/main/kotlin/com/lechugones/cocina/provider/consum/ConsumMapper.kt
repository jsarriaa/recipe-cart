package com.lechugones.cocina.provider.consum

import com.lechugones.cocina.domain.money.Money
import com.lechugones.cocina.domain.product.BrandKind
import com.lechugones.cocina.domain.product.Product
import com.lechugones.cocina.domain.product.ProductKey
import com.lechugones.cocina.domain.quantity.Amount
import com.lechugones.cocina.domain.quantity.Quantity
import com.lechugones.cocina.domain.units.MeasureUnit
import com.lechugones.cocina.provider.ProviderProduct
import kotlinx.serialization.json.JsonPrimitive
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.util.Locale

/**
 * Maps Consum products to [ProviderProduct] (docs/providers/consum.md § Mapping). The offer rule,
 * the product types and the unit vocabulary (`Dc` is a dozen, `100 Gr` is per 100 g) follow
 * supermercapy's `consum/models.py` (third_party/supermercapy, MIT).
 */
object ConsumMapper {
    const val PROVIDER_ID = "consum"
    val STORE_BRANDS = setOf("Consum", "Consum Eco", "Consum Kids")

    private const val WEIGHED = 2
    private const val PIECE_APPROX = 3
    private const val DISTRIBUTOR_BRAND = "filter.id.brand.distributor"

    private val packOf = Regex("""(\d+)\s*[x×]\s*(\d+(?:[.,]\d+)?)\s*(kg|gr|g|ml|cl|l)\b""", RegexOption.IGNORE_CASE)
    private val size = Regex("""(\d+(?:[.,]\d+)?)\s*(kg|gr|g|ml|cl|l)\b""", RegexOption.IGNORE_CASE)
    private val dozens = Regex("""(\d+(?:[.,]\d+)?)\s*dc\b""", RegexOption.IGNORE_CASE)
    private val pieces = Regex("""(\d+)\s*(?:u|ud|uds|unidades)\b""", RegexOption.IGNORE_CASE)
    private val unitType = Regex("""^\s*(\d+(?:[.,]\d+)?)\s*([A-Za-z]+)\s*$""")
    private const val MAX_UNITS = 100

    /** Null without a price or a usable size. */
    fun map(
        source: ConsumProduct,
        fetchedAt: Instant,
        categoryPath: List<String> = source.categories.mapNotNull { it.name }.take(1),
    ): ProviderProduct? {
        val shelf =
            source.priceData.prices
                .firstOrNull { it.id == "PRICE" }
                ?.value
        val current =
            source.priceData.prices
                .firstOrNull { it.id == "OFFER_PRICE" }
                ?.value ?: shelf ?: return null
        val price = euros(current.centAmount) ?: return null
        val reference = reference(euros(current.unitAmount), source.priceData.unitPriceUnitType)
        val data = source.productData
        val net: Quantity
        val approx: Boolean
        var count: Int? = null
        when (source.productType) {
            WEIGHED -> {
                net = Quantity(Amount.of(1000), MeasureUnit.G)
                approx = true
            }

            PIECE_APPROX -> {
                // A piece or tray at an approximate price: its weight is price ÷ price per kg.
                net = reference?.let { sizeFromPrice(price, it) } ?: return null
                approx = true
            }

            else -> {
                val fromText = sizeFromText(sizeText(data))
                if (fromText != null) {
                    net = fromText.first
                    count = fromText.second
                    approx = false
                } else {
                    net = reference?.let { sizeFromPrice(price, it) } ?: return null
                    approx = true
                }
            }
        }
        val product =
            Product(
                key = ProductKey(PROVIDER_ID, source.code),
                name =
                    data.name
                        .trim()
                        .replace(Regex("""\s+"""), " ")
                        .removeSuffix(" ."),
                net = net,
                price = Money.ofEuros(price),
                brand = brandName(data.brand?.name),
                brandKind = brandKind(data),
                packaging = data.format?.trim()?.takeIf { it.isNotEmpty() && source.productType == PIECE_APPROX },
                unitCount = count,
                approx = approx,
                fetchedAt = fetchedAt,
                available = data.availability == "1" && !data.temporaryOutOfStock,
            )
        return ProviderProduct(
            product = product,
            imageUrl = photo(source),
            webUrl = data.url?.takeIf { it.startsWith("https://") },
            categoryPath = categoryPath,
            referencePrice = reference?.let { Money.ofEuros(it.euros) },
            referenceFormat = reference?.format,
        )
    }

    fun brandKind(data: ConsumProductData): BrandKind {
        val upper =
            data.brand
                ?.name
                ?.trim()
                ?.uppercase(Locale.ROOT)
        val distributor =
            data.attributes
                .firstOrNull { it.code == DISTRIBUTOR_BRAND }
                ?.languages
                ?.firstOrNull()
                ?.values
                ?.firstOrNull() == "1"
        return when {
            distributor || upper?.startsWith("CONSUM") == true -> BrandKind.STORE
            upper.isNullOrEmpty() -> BrandKind.UNBRANDED
            else -> BrandKind.NATIONAL
        }
    }

    /** "CONSUM ECO" → "Consum Eco"; empty means no brand. */
    fun brandName(raw: String?): String? {
        val words =
            raw
                ?.trim()
                ?.lowercase(Locale.ROOT)
                ?.split(' ')
                ?.filter { it.isNotEmpty() }
                .orEmpty()
        if (words.isEmpty()) return null
        return words.joinToString(" ") { word -> word.replaceFirstChar { it.titlecase(Locale.ROOT) } }
    }

    /** The first photo in [ConsumProduct.media]; `imageURL` answers 404 for many products. */
    private fun photo(source: ConsumProduct): String? =
        source.media
            .filter { it.url?.startsWith("https://") == true }
            .minByOrNull { it.order ?: Int.MAX_VALUE }
            ?.url
            ?: source.productData.imageURL?.takeIf { it.startsWith("https://") }

    /** The size follows the name in the description: `Leche Semidesnatada Brik 1 L`. */
    private fun sizeText(data: ConsumProductData): String {
        val description = data.description?.trim().orEmpty()
        return if (description.startsWith(data.name.trim())) description.substring(data.name.trim().length) else description
    }

    private fun euros(value: JsonPrimitive?): BigDecimal? = value?.content?.toBigDecimalOrNull()?.takeIf { it.signum() > 0 }

    private class Reference(
        val euros: BigDecimal,
        val format: String,
        /** Base unit and how many base units one reference unit holds. */
        val unit: MeasureUnit,
        val perUnit: BigDecimal,
    )

    /** `1 L`, `1 Kg`, `100 Gr`, `100 ml`, `1 Dc`, `1 U`; doses, washes and metres are dropped. */
    private fun reference(
        euros: BigDecimal?,
        type: String?,
    ): Reference? {
        if (euros == null) return null
        val m = unitType.find(type ?: return null) ?: return null
        val per = BigDecimal(m.groupValues[1].replace(',', '.'))
        if (per.signum() <= 0) return null
        return when (m.groupValues[2].lowercase(Locale.ROOT)) {
            "l" -> Reference(perBase(euros, per, BigDecimal.ONE), "L", MeasureUnit.ML, BigDecimal(1000))
            "ml" -> Reference(perBase(euros, per, BigDecimal(1000)), "L", MeasureUnit.ML, BigDecimal(1000))
            "kg" -> Reference(perBase(euros, per, BigDecimal.ONE), "kg", MeasureUnit.G, BigDecimal(1000))
            "gr", "g" -> Reference(perBase(euros, per, BigDecimal(1000)), "kg", MeasureUnit.G, BigDecimal(1000))
            "dc" -> Reference(perBase(euros, per, BigDecimal.ONE), "docena", MeasureUnit.UD, BigDecimal(12))
            "u" -> Reference(perBase(euros, per, BigDecimal.ONE), "ud", MeasureUnit.UD, BigDecimal.ONE)
            else -> null
        }
    }

    /** [euros] for [per] units, restated for [whole] of them (per 100 g → per 1000 g). */
    private fun perBase(
        euros: BigDecimal,
        per: BigDecimal,
        whole: BigDecimal,
    ): BigDecimal = euros.multiply(whole).divide(per, 4, RoundingMode.HALF_UP).stripTrailingZeros()

    /** Content derived from price ÷ unit price (7,00 € ÷ 3,50 €/kg = 2 kg). */
    private fun sizeFromPrice(
        price: BigDecimal,
        reference: Reference,
    ): Quantity? {
        val amount =
            price
                .divide(reference.euros, 6, RoundingMode.HALF_UP)
                .multiply(reference.perUnit)
                .setScale(0, RoundingMode.HALF_UP)
        return if (amount.signum() > 0) Quantity(Amount.of(amount), reference.unit) else null
    }

    private fun sizeFromText(text: String): Pair<Quantity, Int?>? {
        packOf.find(text)?.let { m ->
            val count = m.groupValues[1].toInt()
            // Multiply before rounding: 2 x 112,5 g is 225 g, not 2 x 113 g.
            val total = if (count in 1..MAX_UNITS) base(m.groupValues[2], m.groupValues[3], BigDecimal(count)) else null
            if (total != null) return total to count.takeIf { it > 1 }
        }
        size.find(text)?.let { m -> base(m.groupValues[1], m.groupValues[2])?.let { return it to null } }
        dozens.find(text)?.let { m ->
            // 0,5 Dc is 6 eggs, 0,83 Dc is a box of 10, 1,5 Dc is 18.
            val eggs = BigDecimal(m.groupValues[1].replace(',', '.')).multiply(BigDecimal(12)).setScale(0, RoundingMode.HALF_UP).toInt()
            if (eggs in 1..MAX_UNITS) return Quantity(Amount.of(eggs), MeasureUnit.UD) to eggs.takeIf { it > 1 }
        }
        pieces.find(text)?.let { m ->
            val count = m.groupValues[1].toInt()
            if (count in 1..MAX_UNITS) return Quantity(Amount.of(count), MeasureUnit.UD) to count.takeIf { it > 1 }
        }
        return null
    }

    private fun base(
        value: String,
        unit: String,
        times: BigDecimal = BigDecimal.ONE,
    ): Quantity? {
        val (base, factor) =
            when (unit.lowercase(Locale.ROOT)) {
                "g", "gr" -> MeasureUnit.G to BigDecimal.ONE
                "kg" -> MeasureUnit.G to BigDecimal(1000)
                "ml" -> MeasureUnit.ML to BigDecimal.ONE
                "cl" -> MeasureUnit.ML to BigDecimal.TEN
                "l" -> MeasureUnit.ML to BigDecimal(1000)
                else -> return null
            }
        val amount = BigDecimal(value.replace(',', '.')).multiply(factor).multiply(times).setScale(0, RoundingMode.HALF_UP)
        return if (amount.signum() > 0) Quantity(Amount.of(amount), base) else null
    }
}
