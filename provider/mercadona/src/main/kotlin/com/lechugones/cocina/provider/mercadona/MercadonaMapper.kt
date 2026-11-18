package com.lechugones.cocina.provider.mercadona

import com.lechugones.cocina.domain.money.Money
import com.lechugones.cocina.domain.product.BrandKind
import com.lechugones.cocina.domain.product.Product
import com.lechugones.cocina.domain.product.ProductKey
import com.lechugones.cocina.domain.quantity.Amount
import com.lechugones.cocina.domain.quantity.Quantity
import com.lechugones.cocina.domain.recipe.NameKey
import com.lechugones.cocina.domain.units.MeasureUnit
import com.lechugones.cocina.provider.ProviderProduct
import java.math.BigDecimal
import java.time.Instant

/** Mercadona JSON → [ProviderProduct], following the field table in docs/providers/mercadona.md. */
object MercadonaMapper {
    const val PROVIDER_ID = "mercadona"
    val STORE_BRANDS = setOf("Hacendado", "Deliplus", "Bosque Verde", "Compy")

    /** Top-level categories of loose produce, meat and fish: no brand means "unbranded". */
    private val FRESH_TOP_CATEGORIES = setOf(1, 2, 3)
    private val THOUSAND = BigDecimal(1000)
    private val storeBrandKeys = STORE_BRANDS.map(NameKey::of)

    /** Null when the product cannot be priced or measured (no size, malformed price). */
    fun map(
        dto: ProductDto,
        fetchedAt: Instant,
        categoryPath: List<String> = dto.categories.map { it.name },
    ): ProviderProduct? {
        val price = Money.parseEuros(dto.price.unitPrice) ?: return null
        val net = netQuantity(dto.price) ?: return null
        val unitCount = dto.price.totalUnits?.takeIf { it > 0 } ?: if (dto.packaging == "Pieza") 1 else null
        val product =
            Product(
                key = ProductKey(PROVIDER_ID, dto.id),
                name = dto.displayName.trim(),
                net = net,
                price = price,
                brand = dto.brand?.trim()?.ifEmpty { null },
                brandKind = brandKind(dto),
                packaging = dto.packaging?.trim()?.ifEmpty { null },
                unitCount = unitCount,
                unitCountUnit = unitCount?.let { countUnit(dto.price.unitName) },
                drained = drained(dto.price, net),
                approx = dto.price.approxSize || dto.price.sellingMethod != 0,
                fetchedAt = fetchedAt,
                available = dto.published && dto.unavailableFrom == null,
            )
        return ProviderProduct(
            product = product,
            imageUrl = dto.thumbnail,
            webUrl = dto.shareUrl,
            categoryPath = categoryPath,
            referencePrice = dto.price.referencePrice?.let(Money::parseEuros),
            referenceFormat = dto.price.referenceFormat,
        )
    }

    fun brandKind(dto: ProductDto): BrandKind {
        val brand = dto.brand?.let(NameKey::of)
        val words = " ${NameKey.of(dto.displayName)} "
        return when {
            brand != null && brand in storeBrandKeys -> BrandKind.STORE
            storeBrandKeys.any { " $it " in words } -> BrandKind.STORE
            brand != null -> BrandKind.NATIONAL
            dto.categories.firstOrNull()?.id in FRESH_TOP_CATEGORIES -> BrandKind.UNBRANDED
            else -> BrandKind.NATIONAL
        }
    }

    private fun netQuantity(price: PriceInstructions): Quantity? {
        val size = price.unitSize?.takeIf { it > 0 }?.let { BigDecimal(it.toString()) } ?: return null
        return when (price.sizeFormat) {
            "kg" -> Quantity(Amount.of(size.multiply(THOUSAND)), MeasureUnit.G)
            "l" -> Quantity(Amount.of(size.multiply(THOUSAND)), MeasureUnit.ML)
            "ud" -> Quantity(Amount.of(size), MeasureUnit.UD)
            else -> null
        }
    }

    private fun drained(
        price: PriceInstructions,
        net: Quantity,
    ): Quantity? {
        if (price.sizeFormat != "kg") return null
        val kg = price.drainedWeight?.takeIf { it > 0 } ?: return null
        return Quantity(Amount.of(BigDecimal(kg.toString()).multiply(THOUSAND)), net.unit)
    }

    private fun countUnit(unitName: String?): MeasureUnit =
        when (unitName?.trim()?.lowercase()) {
            "lonchas" -> MeasureUnit.LONCHA
            "rebanadas" -> MeasureUnit.REBANADA
            else -> MeasureUnit.UD
        }
}
