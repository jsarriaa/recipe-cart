package com.lechugones.cocina.domain.product

import com.lechugones.cocina.domain.money.Money
import com.lechugones.cocina.domain.quantity.PackageSize
import java.time.Instant

/** What the user typed for a product of a shop without an API (SPEC § 4.1, Manual provider). */
data class ManualProductForm(
    val name: String,
    val brand: String = "",
    val storeBrand: Boolean = false,
    /** Package size in the RECIPE_FORMAT § 7 grammar: `500 g`, `1 l`, `4 x 125 g`, `6 ud`. */
    val size: String,
    /** Euros, `1,25` or `1.25`; blank when unknown. */
    val price: String = "",
    val shop: String = "",
)

enum class ManualProductField { NAME, SIZE, PRICE }

sealed interface ManualProductResult {
    data class Valid(
        val product: Product,
        val shop: String?,
    ) : ManualProductResult

    data class Invalid(
        val fields: Set<ManualProductField>,
    ) : ManualProductResult
}

/** Builds the built-in "manual" provider's products. They behave like any cached product. */
object ManualProducts {
    const val PROVIDER_ID = "manual"
    const val NAME_MAX = 120

    fun build(
        productId: String,
        form: ManualProductForm,
        now: Instant,
    ): ManualProductResult {
        val name = form.name.trim()
        val size = PackageSize.parse(form.size)
        val priceText = form.price.trim()
        val price = if (priceText.isEmpty()) null else Money.parseEuros(priceText)
        val invalid =
            buildSet {
                if (name.isEmpty() || name.length > NAME_MAX) add(ManualProductField.NAME)
                if (size == null) add(ManualProductField.SIZE)
                if (priceText.isNotEmpty() && (price == null || price.cents < 0)) add(ManualProductField.PRICE)
            }
        if (invalid.isNotEmpty() || size == null) return ManualProductResult.Invalid(invalid)
        val brand = form.brand.trim().ifEmpty { null }
        val product =
            Product(
                key = ProductKey(PROVIDER_ID, productId),
                name = name,
                net = size.total(),
                price = price,
                brand = brand,
                brandKind =
                    when {
                        form.storeBrand -> BrandKind.STORE
                        brand == null -> BrandKind.UNBRANDED
                        else -> BrandKind.NATIONAL
                    },
                unitCount = size.count.takeIf { it > 1 },
                fetchedAt = now,
            )
        return ManualProductResult.Valid(product, form.shop.trim().ifEmpty { null })
    }
}
