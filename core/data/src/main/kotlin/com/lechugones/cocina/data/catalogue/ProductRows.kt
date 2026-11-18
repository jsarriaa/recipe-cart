package com.lechugones.cocina.data.catalogue

import com.lechugones.cocina.data.db.ProductEntity
import com.lechugones.cocina.domain.money.Money
import com.lechugones.cocina.domain.product.BrandKind
import com.lechugones.cocina.domain.product.Product
import com.lechugones.cocina.domain.product.ProductKey
import com.lechugones.cocina.domain.quantity.Amount
import com.lechugones.cocina.domain.quantity.Quantity
import com.lechugones.cocina.domain.recipe.NameKey
import com.lechugones.cocina.domain.units.MeasureUnit
import com.lechugones.cocina.provider.ProviderProduct
import java.time.Instant

/** Separator of [ProductEntity.categoryPath]. */
internal const val PATH_SEPARATOR = " › "

internal fun ProviderProduct.toEntity(fetchedAt: Instant): ProductEntity =
    ProductEntity(
        providerId = product.key.providerId,
        productId = product.key.productId,
        name = product.name,
        searchKey = NameKey.of(listOfNotNull(product.name, product.brand, categoryPath.lastOrNull()).joinToString(" ")),
        brand = product.brand,
        brandKind = product.brandKind.name,
        packaging = product.packaging,
        netMilli = product.net.amount.milli,
        baseUnit = product.net.unit.code,
        unitCount = product.unitCount,
        unitCountUnit = product.unitCountUnit?.code,
        drainedMilli = product.drained?.amount?.milli,
        approx = product.approx,
        priceCents = product.price?.cents,
        referencePriceCents = referencePrice?.cents,
        referenceFormat = referenceFormat,
        imageUrl = imageUrl,
        webUrl = webUrl,
        categoryPath = categoryPath.joinToString(PATH_SEPARATOR),
        available = product.available,
        fetchedAt = fetchedAt.toEpochMilli(),
    )

/** Rows that no longer make a valid product (unknown unit) map to null and are ignored. */
internal fun ProductEntity.toProviderProduct(): ProviderProduct? {
    val unit = MeasureUnit.fromCode(baseUnit)?.takeIf { it in Product.BASE_UNITS } ?: return null
    if (netMilli <= 0) return null
    val product =
        Product(
            key = ProductKey(providerId, productId),
            name = name,
            net = Quantity(Amount(netMilli), unit),
            price = priceCents?.let(::Money),
            brand = brand,
            brandKind = BrandKind.entries.firstOrNull { it.name == brandKind } ?: BrandKind.NATIONAL,
            packaging = packaging,
            unitCount = unitCount?.takeIf { it > 0 },
            unitCountUnit = unitCountUnit?.let(MeasureUnit::fromCode),
            drained = drainedMilli?.let { Quantity(Amount(it), unit) },
            approx = approx,
            fetchedAt = Instant.ofEpochMilli(fetchedAt),
            available = available,
        )
    return ProviderProduct(
        product = product,
        imageUrl = imageUrl,
        webUrl = webUrl,
        categoryPath = categoryPath.split(PATH_SEPARATOR).filter { it.isNotBlank() },
        referencePrice = referencePriceCents?.let(::Money),
        referenceFormat = referenceFormat,
    )
}
