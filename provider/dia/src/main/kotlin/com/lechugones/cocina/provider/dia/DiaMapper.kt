package com.lechugones.cocina.provider.dia

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

/** Maps Día search items to [ProviderProduct] (docs/providers/dia.md § Mapping). */
object DiaMapper {
    const val PROVIDER_ID = "dia"
    const val SITE = "https://www.dia.es"
    val STORE_BRANDS = setOf("Dia", "Dia Láctea", "Dia Nature", "Dia Al Punto", "El Molino de Dia")

    /** First-level categories of loose fresh food: products there without a brand are unbranded. */
    private val FRESH = setOf("Frutas", "Verduras", "Carne", "Pescado y marisco", "Frutas y verduras")

    /** "6 x 1 L", "3 x 200 ml", "350 g", "1.5 L", "24 unidades". */
    private val packPattern = Regex("""(\d+)\s*[x×]\s*(\d+(?:[.,]\d+)?)\s*(kg|g|ml|cl|l)\b""", RegexOption.IGNORE_CASE)
    private val sizePattern = Regex("""(\d+(?:[.,]\d+)?)\s*(kg|g|ml|cl|l)\b""", RegexOption.IGNORE_CASE)
    private val unitsPattern = Regex("""(\d+)\s*(?:unidades|uds?\b)""", RegexOption.IGNORE_CASE)

    private val TOLERANCE = BigDecimal("0.05")

    /** Null when the item has no usable price or size. */
    fun map(
        item: DiaItem,
        fetchedAt: Instant,
    ): ProviderProduct? {
        val prices = item.prices ?: return null
        // The Club Dia price needs the loyalty card: use the regular price ("Precios Club Dia" is off).
        val euros = (if (prices.clubPrice) prices.strikethroughPrice else prices.price) ?: prices.price ?: return null
        if (euros <= 0) return null
        val fromName = sizeFromName(item.name)
        val fromPrice = sizeFromPrice(euros, prices)
        val (net, approx) =
            when {
                fromName != null && fromPrice != null -> fromName.quantity to !close(fromName.quantity, fromPrice)
                fromName != null -> fromName.quantity to false
                fromPrice != null -> fromPrice to true
                else -> return null
            }
        val product =
            Product(
                key = ProductKey(PROVIDER_ID, item.id),
                name = item.name,
                net = net,
                price = Money.ofEuros(BigDecimal.valueOf(euros)),
                brand = item.brand?.takeIf { it.isNotBlank() },
                brandKind = brandKind(item),
                unitCount = fromName?.count?.takeIf { it > 1 },
                approx = approx,
                fetchedAt = fetchedAt,
                available = (item.unitsInStock ?: 1) > 0,
            )
        return ProviderProduct(
            product = product,
            imageUrl = item.image?.let { SITE + it },
            webUrl = item.url?.let { SITE + it },
            categoryPath = listOfNotNull(item.category1, item.category2),
            referencePrice = prices.pricePerUnit?.let { Money.ofEuros(BigDecimal.valueOf(it)) },
            referenceFormat = referenceFormat(prices.measureUnit),
        )
    }

    fun brandKind(item: DiaItem): BrandKind =
        when {
            item.diaBrand == true -> BrandKind.STORE
            !item.brand.isNullOrBlank() -> BrandKind.NATIONAL
            item.category1 in FRESH -> BrandKind.UNBRANDED
            else -> BrandKind.NATIONAL
        }

    private data class NameSize(
        val quantity: Quantity,
        val count: Int,
    )

    private fun sizeFromName(name: String): NameSize? {
        packPattern.find(name)?.let { m ->
            val count = m.groupValues[1].toInt()
            val each = base(decimal(m.groupValues[2]), m.groupValues[3]) ?: return@let
            return NameSize(Quantity(Amount.of(each.amount.toBigDecimal().multiply(BigDecimal(count))), each.unit), count)
        }
        unitsPattern.find(name)?.let { m ->
            val count = m.groupValues[1].toInt()
            if (count > 0) return NameSize(Quantity(Amount.of(count), MeasureUnit.UD), count)
        }
        sizePattern.find(name)?.let { m ->
            val size = base(decimal(m.groupValues[1]), m.groupValues[2]) ?: return null
            return NameSize(size, 1)
        }
        return null
    }

    /** Package content derived from price ÷ price per unit (e.g. 0,83 € ÷ 0,83 €/l = 1 l). */
    private fun sizeFromPrice(
        euros: Double,
        prices: DiaPrices,
    ): Quantity? {
        val perUnit = prices.pricePerUnit?.takeIf { it > 0 } ?: return null
        val units = BigDecimal.valueOf(euros).divide(BigDecimal.valueOf(perUnit), 6, RoundingMode.HALF_UP)
        val (unit, factor) =
            when (prices.measureUnit?.uppercase()) {
                "LITRO" -> MeasureUnit.ML to BigDecimal(1000)
                "KILO" -> MeasureUnit.G to BigDecimal(1000)
                "DOCENA" -> MeasureUnit.UD to BigDecimal(12)
                "UNIDAD" -> MeasureUnit.UD to BigDecimal.ONE
                else -> return null
            }
        val amount = units.multiply(factor).setScale(0, RoundingMode.HALF_UP)
        return if (amount.signum() > 0) Quantity(Amount.of(amount), unit) else null
    }

    private fun close(
        a: Quantity,
        b: Quantity,
    ): Boolean {
        if (a.unit != b.unit) return false
        val x = a.amount.toBigDecimal()
        val y = b.amount.toBigDecimal()
        return x.subtract(y).abs() <= x.max(y).multiply(TOLERANCE)
    }

    private fun base(
        value: BigDecimal,
        unit: String,
    ): Quantity? {
        val (base, factor) =
            when (unit.lowercase()) {
                "g" -> MeasureUnit.G to BigDecimal.ONE
                "kg" -> MeasureUnit.G to BigDecimal(1000)
                "ml" -> MeasureUnit.ML to BigDecimal.ONE
                "cl" -> MeasureUnit.ML to BigDecimal.TEN
                "l" -> MeasureUnit.ML to BigDecimal(1000)
                else -> return null
            }
        val amount = value.multiply(factor)
        return if (amount.signum() > 0) Quantity(Amount.of(amount), base) else null
    }

    private fun decimal(text: String) = BigDecimal(text.replace(',', '.'))

    private fun referenceFormat(measureUnit: String?): String? =
        when (measureUnit?.uppercase()) {
            "LITRO" -> "L"
            "KILO" -> "kg"
            "DOCENA" -> "docena"
            "UNIDAD" -> "ud"
            else -> null
        }
}
