package com.lechugones.cocina.provider.ahorramas

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
 * Maps Ahorramás grid tiles to [ProviderProduct] (docs/providers/ahorramas.md § Mapping). The
 * shown price, the per-kilogram rule for weighed food and the unit vocabulary follow
 * supermercapy (third_party/supermercapy, MIT).
 */
object AhorramasMapper {
    const val PROVIDER_ID = "ahorramas"
    const val SITE = "https://www.ahorramas.com"
    val STORE_BRANDS = setOf("Alipende", "Nuestro Pescado Congelado")
    private val STORE_UPPER = STORE_BRANDS.associateBy { it.uppercase(Locale.ROOT) }
    private val NO_BRAND = setOf("SIN MARCA")

    private val packOf = Regex("""(\d+)\s*[x×]\s*(\d+(?:[.,]\d+)?)\s*(kg|g|ml|cl|l)\b""", RegexOption.IGNORE_CASE)
    private val size = Regex("""(\d+(?:[.,]\d+)?)\s*(kg|gr|g|ml|cl|l)\b""", RegexOption.IGNORE_CASE)
    private val pieces = Regex("""(\d+)\s*(?:u\b|ud\b|uds\b|unid\b|unidades\b)""", RegexOption.IGNORE_CASE)
    private val packCount = Regex("""(?:\bpack\s*|\bP)(\d+)\b""", RegexOption.IGNORE_CASE)
    private val unitPrice = Regex("""(\d+(?:\.\d{3})*,\d+)\s*€\s*/\s*([A-Za-z.\s]+)""")
    private val TOLERANCE = BigDecimal("0.05")

    /** Null without a price or a usable size. */
    fun map(
        tile: AhorramasTile,
        fetchedAt: Instant,
    ): ProviderProduct? {
        val price = tile.price?.toBigDecimalOrNull()?.takeIf { it.signum() > 0 } ?: return null
        val reference = reference(tile.unitPrice)
        val fromName = sizeFromName(tile.name)
        val net: Quantity
        val approx: Boolean
        when {
            tile.pricePerKilo -> {
                // Fresh food by weight: the price is per kilogram.
                net = Quantity(Amount.of(1000), MeasureUnit.G)
                approx = true
            }

            fromName != null -> {
                val fromPrice = reference?.let { sizeFromPrice(price, it) }
                net = fromName.first
                // Only a disagreeing size in the same unit makes the name's size doubtful.
                approx = fromPrice != null && fromPrice.unit == fromName.first.unit && !close(fromName.first, fromPrice)
            }

            else -> {
                net = reference?.let { sizeFromPrice(price, it) } ?: return null
                approx = true
            }
        }
        val product =
            Product(
                key = ProductKey(PROVIDER_ID, tile.id),
                name = tile.name.trim(),
                net = net,
                price = Money.ofEuros(price),
                brand = brandName(tile.brand),
                brandKind = brandKind(tile.brand),
                unitCount = if (tile.pricePerKilo) null else (fromName?.second ?: packCount(tile.name)),
                approx = approx,
                fetchedAt = fetchedAt,
                available = tile.available,
            )
        return ProviderProduct(
            product = product,
            imageUrl = tile.imageUrl?.let(::smallImage),
            webUrl = tile.path?.let { SITE + it },
            categoryPath = listOfNotNull(tile.category1, tile.category2),
            referencePrice = reference?.let { Money.ofEuros(it.euros) },
            referenceFormat = reference?.format,
        )
    }

    fun brandKind(raw: String?): BrandKind {
        val upper = raw?.trim()?.uppercase(Locale.ROOT)
        return when {
            upper.isNullOrEmpty() || upper in NO_BRAND -> BrandKind.UNBRANDED
            upper in STORE_UPPER -> BrandKind.STORE
            else -> BrandKind.NATIONAL
        }
    }

    /** "PAZO DE VILANE" → "Pazo De Vilane"; the store brands keep their spelling. */
    fun brandName(raw: String?): String? {
        val upper = raw?.trim()?.uppercase(Locale.ROOT)
        if (upper.isNullOrEmpty() || upper in NO_BRAND) return null
        STORE_UPPER[upper]?.let { return it }
        return upper
            .lowercase(Locale.ROOT)
            .split(' ')
            .filter { it.isNotEmpty() }
            .joinToString(" ") { word -> word.replaceFirstChar { it.titlecase(Locale.ROOT) } }
    }

    /** The tile image at list size: the CDN takes `sw`, `sh` and `sm`. */
    private fun smallImage(url: String): String = url.substringBefore('?') + "?sw=260&sh=260&sm=fit"

    private class Reference(
        val euros: BigDecimal,
        /** Display format for [ProviderProduct.referenceFormat]. */
        val format: String,
        /** Base unit and how many base units one reference unit holds. */
        val unit: MeasureUnit,
        val perUnit: BigDecimal,
    )

    private fun reference(text: String?): Reference? {
        val m = unitPrice.find(text ?: return null) ?: return null
        val euros = BigDecimal(m.groupValues[1].replace(".", "").replace(',', '.'))
        if (euros.signum() <= 0) return null
        val unit = m.groupValues[2].trim().uppercase(Locale.ROOT)
        return when {
            unit.startsWith("LITRO") -> Reference(euros, "L", MeasureUnit.ML, BigDecimal(1000))

            // KG.PESO ESC is per kilogram of drained weight (tins and jars).
            unit.startsWith("KILO") || unit.startsWith("KG") -> Reference(euros, "kg", MeasureUnit.G, BigDecimal(1000))

            unit.startsWith("DOCENA") -> Reference(euros, "docena", MeasureUnit.UD, BigDecimal(12))

            unit.startsWith("UNIDAD") -> Reference(euros, "ud", MeasureUnit.UD, BigDecimal.ONE)

            else -> null // LAVADO (doses) and the like.
        }
    }

    /** Content derived from price ÷ unit price (0,83 € ÷ 0,83 €/l = 1 l). */
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

    private fun sizeFromName(name: String): Pair<Quantity, Int?>? {
        packOf.find(name)?.let { m ->
            val count = m.groupValues[1].toInt()
            val each = base(m.groupValues[2], m.groupValues[3])
            if (each != null && count in 1..MAX_UNITS) {
                return Quantity(Amount.of(each.amount.toBigDecimal().multiply(BigDecimal(count))), each.unit) to count.takeIf { it > 1 }
            }
        }
        size.find(name)?.let { m -> base(m.groupValues[1], m.groupValues[2])?.let { return it to null } }
        pieces.find(name)?.let { m ->
            val count = m.groupValues[1].toInt()
            if (count in 1..MAX_UNITS) return Quantity(Amount.of(count), MeasureUnit.UD) to count.takeIf { it > 1 }
        }
        return null
    }

    private fun packCount(name: String): Int? =
        packCount
            .find(name)
            ?.groupValues
            ?.get(1)
            ?.toIntOrNull()
            ?.takeIf { it in 2..MAX_UNITS }

    private fun base(
        value: String,
        unit: String,
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
        val amount = BigDecimal(value.replace(',', '.')).multiply(factor).setScale(0, RoundingMode.HALF_UP)
        return if (amount.signum() > 0) Quantity(Amount.of(amount), base) else null
    }

    private fun close(
        a: Quantity,
        b: Quantity,
    ): Boolean {
        val x = a.amount.toBigDecimal()
        val y = b.amount.toBigDecimal()
        return x.subtract(y).abs() <= x.max(y).multiply(TOLERANCE)
    }

    private const val MAX_UNITS = 100
}
