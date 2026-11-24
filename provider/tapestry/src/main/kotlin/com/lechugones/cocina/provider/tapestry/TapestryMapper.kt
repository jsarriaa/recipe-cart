package com.lechugones.cocina.provider.tapestry

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

/** One shop on the Tapestry storefront. */
data class TapestryChain(
    val id: String,
    val displayName: String,
    /** `https://supermercado.eroski.es/` */
    val baseUrl: String,
) {
    companion object {
        val EROSKI = TapestryChain("eroski", "Eroski", "https://supermercado.eroski.es/")
        val CAPRABO = TapestryChain("caprabo", "Caprabo", "https://www.capraboacasa.com/")
    }
}

/**
 * Maps Tapestry tiles to [ProviderProduct] (docs/providers/eroski.md § Mapping). The visible-price
 * rule, the weighed-produce rule and the unit text (`1 KILO A 5,73 €`) follow supermercapy
 * (third_party/supermercapy, MIT).
 */
object TapestryMapper {
    /** The Eroski group's own lines, sold by both shops. */
    val STORE_BRANDS = setOf("Eroski", "Eroski Basic", "Eroski Natur", "Eroski Sannia", "Seleqtia", "Caprabo")
    private val STORE_UPPER = STORE_BRANDS.map { it.uppercase(Locale.ROOT) }.toSet()
    private val ALIASES = mapOf("E.BASIC" to "Eroski Basic")

    private const val SIZE_UNIT = """(kg|kilos?|gr|g|ml|cl|l|litros?)"""
    private val packOf = Regex("""(\d+)\s*[x×]\s*(\d+(?:[.,]\d+)?)\s*$SIZE_UNIT\b""", RegexOption.IGNORE_CASE)
    private val size = Regex("""(\d+(?:[.,]\d+)?)\s*$SIZE_UNIT\b""", RegexOption.IGNORE_CASE)
    private val pieces = Regex("""(\d+)\s*(?:uds?\.?|unidades|u)(?=\s|,|$)""", RegexOption.IGNORE_CASE)
    private val minimum = Regex("""compra m[íi]nima\s+(\d+(?:[.,]\d+)?)\s*$SIZE_UNIT\b""", RegexOption.IGNORE_CASE)
    private val unitPrice = Regex("""^\s*(\d+(?:[.,]\d+)?)\s*([A-ZÁÉÍÓÚa-záéíóú.]+)\s+A\s+(\d[\d.]*,\d+|\d+(?:\.\d+)?)""")
    private const val MAX_UNITS = 100

    /** Null for marketplace items, or without a price or a usable size. */
    fun map(
        chain: TapestryChain,
        tile: TapestryTile,
        fetchedAt: Instant,
    ): ProviderProduct? {
        if (tile.marketplace) return null
        val price = euros(tile.price) ?: return null
        val shown = reference(tile.unitPrice)
        val net: Quantity
        val approx: Boolean
        var count: Int? = null
        val fromName = sizeFromName(tile.name)
        when {
            tile.weighed -> {
                // Sold by weight: the price is for the minimum purchase ("compra mínima 1 kg").
                net = minimum.find(tile.name)?.let { base(it.groupValues[1], it.groupValues[2]) }
                    ?: shown?.let { sizeFromPrice(price, it) }
                    ?: Quantity(Amount.of(1000), MeasureUnit.G)
                approx = true
            }

            fromName != null -> {
                net = fromName.first
                count = fromName.second
                approx = false
            }

            else -> {
                net = shown?.let { sizeFromPrice(price, it) } ?: return null
                approx = true
            }
        }
        val reference = shown ?: computed(price, net)
        val product =
            Product(
                key = ProductKey(chain.id, tile.id),
                name = tile.name.replace(Regex("""\s+"""), " ").trim(),
                net = net,
                price = Money.ofEuros(price),
                brand = brandName(tile.brand),
                brandKind = brandKind(tile.brand),
                unitCount = count,
                approx = approx,
                fetchedAt = fetchedAt,
            )
        return ProviderProduct(
            product = product,
            imageUrl = tile.imageUrl,
            webUrl = tile.url?.replace(":443/", "/"),
            categoryPath = emptyList(),
            referencePrice = reference?.let { Money.ofEuros(it.euros) },
            referenceFormat = reference?.format,
        )
    }

    fun brandKind(raw: String?): BrandKind {
        val upper = raw?.trim()?.uppercase(Locale.ROOT)
        return when {
            upper.isNullOrEmpty() -> BrandKind.UNBRANDED
            upper in ALIASES || upper in STORE_UPPER || upper.startsWith("EROSKI") -> BrandKind.STORE
            else -> BrandKind.NATIONAL
        }
    }

    /** "LA FAGEDA" → "La Fageda", "E.BASIC" → "Eroski Basic". */
    fun brandName(raw: String?): String? {
        val upper = raw?.trim()?.uppercase(Locale.ROOT)
        if (upper.isNullOrEmpty()) return null
        ALIASES[upper]?.let { return it }
        return upper
            .lowercase(Locale.ROOT)
            .split(' ')
            .filter { it.isNotEmpty() }
            .joinToString(" ") { word -> word.replaceFirstChar { it.titlecase(Locale.ROOT) } }
    }

    private fun euros(text: String?): BigDecimal? {
        val clean = text?.replace("€", "")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        // "2,29" or "1.234,50" on the tile; "2.29" in the analytics event.
        val normal = if (',' in clean) clean.replace(".", "").replace(',', '.') else clean
        return normal.toBigDecimalOrNull()?.takeIf { it.signum() > 0 }
    }

    private class Reference(
        val euros: BigDecimal,
        val format: String,
        val unit: MeasureUnit,
        /** Base units in one reference unit. */
        val perUnit: BigDecimal,
    )

    /** `1 KILO A 5,73 €`, `100 ML. A 0,31 €`, `1 LITRO A …`, `1 DOCENA A …`, `1 UNIDAD A …`. */
    private fun reference(text: String?): Reference? {
        val m = unitPrice.find(text ?: return null) ?: return null
        val per = BigDecimal(m.groupValues[1].replace(',', '.'))
        val euros = euros(m.groupValues[3]) ?: return null
        if (per.signum() <= 0) return null
        val word = m.groupValues[2].trim('.').uppercase(Locale.ROOT)

        fun restated(whole: BigDecimal) = euros.multiply(whole).divide(per, 4, RoundingMode.HALF_UP).stripTrailingZeros()
        return when {
            word.startsWith("KILO") || word == "KG" -> Reference(restated(BigDecimal.ONE), "kg", MeasureUnit.G, BigDecimal(1000))

            word == "G" || word == "GR" ||
                word.startsWith(
                    "GRAMO",
                )
            -> Reference(restated(BigDecimal(1000)), "kg", MeasureUnit.G, BigDecimal(1000))

            word.startsWith("LITRO") || word == "L" -> Reference(restated(BigDecimal.ONE), "L", MeasureUnit.ML, BigDecimal(1000))

            word == "ML" -> Reference(restated(BigDecimal(1000)), "L", MeasureUnit.ML, BigDecimal(1000))

            word.startsWith("DOCENA") -> Reference(restated(BigDecimal.ONE), "docena", MeasureUnit.UD, BigDecimal(12))

            word.startsWith(
                "UNIDAD",
            ) || word.startsWith("ROLLO") -> Reference(restated(BigDecimal.ONE), "ud", MeasureUnit.UD, BigDecimal.ONE)

            else -> null // Doses, metres.
        }
    }

    /** Most single items print no unit price: per kg or L from the size. */
    private fun computed(
        price: BigDecimal,
        net: Quantity,
    ): Reference? {
        val amount = net.amount.toBigDecimal().takeIf { it.signum() > 0 } ?: return null
        val perBase = price.multiply(BigDecimal(1000)).divide(amount, 4, RoundingMode.HALF_UP).stripTrailingZeros()
        return when (net.unit) {
            MeasureUnit.G -> Reference(perBase, "kg", MeasureUnit.G, BigDecimal(1000))
            MeasureUnit.ML -> Reference(perBase, "L", MeasureUnit.ML, BigDecimal(1000))
            else -> null
        }
    }

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

    /** The size is at the end of the name: `…, brik 1 litro`, `…, pack 4x100 g`, `…, cartón 6 uds.`. */
    private fun sizeFromName(name: String): Pair<Quantity, Int?>? {
        packOf.findAll(name).lastOrNull()?.let { m ->
            val count = m.groupValues[1].toInt()
            // Multiply before rounding: 2 x 112,5 g is 225 g, not 2 x 113 g.
            val total = if (count in 1..MAX_UNITS) base(m.groupValues[2], m.groupValues[3], BigDecimal(count)) else null
            if (total != null) return total to count.takeIf { it > 1 }
        }
        size.findAll(name).lastOrNull()?.let { m -> base(m.groupValues[1], m.groupValues[2])?.let { return it to null } }
        pieces.findAll(name).lastOrNull()?.let { m ->
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
        val word = unit.lowercase(Locale.ROOT)
        val (base, factor) =
            when {
                word == "g" || word == "gr" -> MeasureUnit.G to BigDecimal.ONE
                word == "kg" || word.startsWith("kilo") -> MeasureUnit.G to BigDecimal(1000)
                word == "ml" -> MeasureUnit.ML to BigDecimal.ONE
                word == "cl" -> MeasureUnit.ML to BigDecimal.TEN
                word == "l" || word.startsWith("litro") -> MeasureUnit.ML to BigDecimal(1000)
                else -> return null
            }
        val amount = BigDecimal(value.replace(',', '.')).multiply(factor).multiply(times).setScale(0, RoundingMode.HALF_UP)
        return if (amount.signum() > 0) Quantity(Amount.of(amount), base) else null
    }
}
