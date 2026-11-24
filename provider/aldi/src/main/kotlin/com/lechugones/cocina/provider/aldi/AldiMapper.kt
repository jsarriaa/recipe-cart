package com.lechugones.cocina.provider.aldi

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
 * Maps Aldi index records to [ProviderProduct] (docs/providers/aldi.md § Mapping). The unit-price
 * rules (`g` and `100-g` scales are per kilogram) follow supermercapy's `parse_price`
 * (third_party/supermercapy, MIT).
 */
object AldiMapper {
    const val PROVIDER_ID = "aldi"
    const val SITE = "https://www.aldi.es"

    /** Aldi Spain's own brands (to be completed as the catalogue shows more). */
    val STORE_BRANDS =
        setOf(
            "Milsani",
            "Myvay",
            "Gutbio",
            "Bio",
            "Barissimo",
            "Choceur",
            "Cucina",
            "Cucina Nobile",
            "Special",
            "Gourmet",
            "Olearia del Olivar",
            "Olearia de Semillas",
            "La Villa",
            "Golden Seafood",
            "Almare",
            "Asia Green Garden",
            "Casa Morando",
            "Lyttos",
            "Snack Day",
            "Sweet Valley",
            "Belbake",
            "Rio d'Oro",
            "River",
            "Moser Roth",
            "Le Gusto",
            "Mamia",
            "Tandil",
            "Lacura",
        )
    private val STORE_UPPER = STORE_BRANDS.associateBy { it.uppercase(Locale.ROOT) }

    /** Aldi writes every brand in capitals followed by a registered sign. */
    private val REGISTERED = Char(0x00AE)

    private val bulk = Regex("""a granel""", RegexOption.IGNORE_CASE)
    private val pack = Regex("""(\d+)\s*[x×]\s*(\d+(?:[.,]\d+)?)\s*(kg|g|ml|cl|l)\b""", RegexOption.IGNORE_CASE)
    private val size = Regex("""(\d+(?:[.,]\d+)?)\s*(kg|g|ml|cl|l)\b""", RegexOption.IGNORE_CASE)
    private val pieces = Regex("""(\d+)\s*(?:unidades|uds?\.?)(?:\s|$)""", RegexOption.IGNORE_CASE)
    private val approx = Regex("""aprox""", RegexOption.IGNORE_CASE)

    /** Null when the record has no price (about 200 fresh items) or no usable size. */
    fun map(
        record: AldiRecord,
        fetchedAt: Instant,
        categoryPath: List<String> = emptyList(),
    ): ProviderProduct? {
        val euros = record.currentPrice?.priceValue?.takeIf { it > 0 } ?: return null
        val unitText = record.salesUnit.orEmpty()
        val looseByKilo = bulk.containsMatchIn(unitText)
        val parsed =
            if (looseByKilo) {
                Parsed(Quantity(Amount.of(1000), MeasureUnit.G), null, approx = true)
            } else {
                parse(unitText) ?: parse(record.name) ?: return null
            }
        val product =
            Product(
                key = ProductKey(PROVIDER_ID, record.objectID),
                name = record.name.trim(),
                net = parsed.net,
                price = Money.ofEuros(BigDecimal.valueOf(euros)),
                brand = brandName(record.brandName),
                brandKind = brandKind(record.brandName),
                unitCount = parsed.count,
                approx = parsed.approx || approx.containsMatchIn(unitText),
                fetchedAt = fetchedAt,
                available = record.isAvailable ?: true,
            )
        val reference = if (looseByKilo) Money.ofEuros(BigDecimal.valueOf(euros)) to "kg" else reference(record, parsed.net, euros)
        return ProviderProduct(
            product = product,
            imageUrl = record.assets.firstOrNull { it.type == "primary" }?.url ?: record.assets.firstOrNull()?.url,
            webUrl = record.productSlug?.let { "$SITE/producto/$it.html" },
            categoryPath = categoryPath,
            referencePrice = reference?.first,
            referenceFormat = reference?.second,
        )
    }

    fun brandKind(raw: String?): BrandKind {
        val clean = clean(raw) ?: return BrandKind.UNBRANDED
        return if (clean.uppercase(Locale.ROOT) in STORE_UPPER) BrandKind.STORE else BrandKind.NATIONAL
    }

    /** "MILSANI®" → "Milsani", "OLEARIA DEL OLIVAR®" → "Olearia del Olivar". */
    fun brandName(raw: String?): String? {
        val clean = clean(raw) ?: return null
        STORE_UPPER[clean.uppercase(Locale.ROOT)]?.let { return it }
        return clean
            .lowercase(Locale.ROOT)
            .split(' ')
            .joinToString(" ") { word -> word.replaceFirstChar { it.titlecase(Locale.ROOT) } }
    }

    private fun clean(raw: String?): String? =
        raw
            ?.replace(REGISTERED.toString(), "")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

    private data class Parsed(
        val net: Quantity,
        val count: Int?,
        val approx: Boolean = false,
    )

    private fun parse(text: String): Parsed? {
        pack.find(text)?.let { m ->
            val count = m.groupValues[1].toInt()
            val each = base(m.groupValues[2], m.groupValues[3]) ?: return@let
            if (count in 1..MAX_UNITS) {
                return Parsed(
                    checkNotNull(base(m.groupValues[2], m.groupValues[3], BigDecimal(count))) { "pack size" },
                    count.takeIf { it > 1 },
                )
            }
        }
        size.find(text)?.let { m -> base(m.groupValues[1], m.groupValues[2])?.let { return Parsed(it, null) } }
        pieces.find(text)?.let { m ->
            val count = m.groupValues[1].toInt()
            if (count in 1..MAX_UNITS) return Parsed(Quantity(Amount.of(count), MeasureUnit.UD), count.takeIf { it > 1 })
        }
        return null
    }

    /** [times] multiplies before rounding: 2 x 112,5 g is 225 g, not 2 x 113 g. */
    private fun base(
        value: String,
        unit: String,
        times: BigDecimal = BigDecimal.ONE,
    ): Quantity? {
        val (base, factor) =
            when (unit.lowercase(Locale.ROOT)) {
                "g" -> MeasureUnit.G to BigDecimal.ONE
                "kg" -> MeasureUnit.G to BigDecimal(1000)
                "ml" -> MeasureUnit.ML to BigDecimal.ONE
                "cl" -> MeasureUnit.ML to BigDecimal.TEN
                "l" -> MeasureUnit.ML to BigDecimal(1000)
                else -> return null
            }
        val amount = BigDecimal(value.replace(',', '.')).multiply(factor).multiply(times).setScale(0, RoundingMode.HALF_UP)
        return if (amount.signum() > 0) Quantity(Amount.of(amount), base) else null
    }

    /**
     * The shelf's price per unit. Aldi leaves it out for packs of exactly one kilo or litre (the
     * price is then the unit price itself), and sends `g` and `100-g` scales on per-kilo prices.
     */
    private fun reference(
        record: AldiRecord,
        net: Quantity,
        euros: Double,
    ): Pair<Money, String>? {
        val base = record.currentPrice?.basePrice?.firstOrNull()
        val value = base?.basePriceValue?.takeIf { it > 0 }
        if (value != null) {
            val format =
                when (base.basePriceScale?.lowercase(Locale.ROOT)) {
                    "kg", "g", "100-g" -> "kg"
                    "l" -> "L"
                    "100-ml" -> "100 ml"
                    "unidad" -> "ud"
                    else -> null
                }
            if (format != null) return Money.ofEuros(BigDecimal.valueOf(value)) to format
        }
        val oneUnit = net.amount == Amount.of(1000)
        return when {
            oneUnit && net.unit == MeasureUnit.G -> Money.ofEuros(BigDecimal.valueOf(euros)) to "kg"
            oneUnit && net.unit == MeasureUnit.ML -> Money.ofEuros(BigDecimal.valueOf(euros)) to "L"
            else -> null
        }
    }

    private const val MAX_UNITS = 100
}
