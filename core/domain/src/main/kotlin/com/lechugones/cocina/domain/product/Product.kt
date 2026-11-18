package com.lechugones.cocina.domain.product

import com.lechugones.cocina.domain.money.Money
import com.lechugones.cocina.domain.quantity.Quantity
import com.lechugones.cocina.domain.units.MeasureUnit
import java.math.BigDecimal
import java.time.Instant

/** Identifies a product at one provider (`mercadona`, `dia`, `manual`). */
data class ProductKey(
    val providerId: String,
    val productId: String,
)

enum class BrandKind {
    /** The provider's own brand (Hacendado, Dia…). */
    STORE,

    /** Any other brand. Hidden by "Solo marca blanca". */
    NATIONAL,

    /** No brand: loose produce, meat trays. */
    UNBRANDED,
}

/**
 * A sellable package at a provider, normalised (docs/providers/README.md).
 *
 * [net] is in a package base unit: g, ml or ud. [drained] (same unit) replaces it as the usable
 * quantity when present. [unitCount] pieces of [unitCountUnit] (default ud) are inside, if known.
 */
data class Product(
    val key: ProductKey,
    val name: String,
    val net: Quantity,
    val price: Money?,
    val brand: String? = null,
    val brandKind: BrandKind = BrandKind.UNBRANDED,
    val packaging: String? = null,
    val unitCount: Int? = null,
    val unitCountUnit: MeasureUnit? = null,
    val drained: Quantity? = null,
    val approx: Boolean = false,
    val fetchedAt: Instant? = null,
    val available: Boolean = true,
) {
    init {
        require(net.unit in BASE_UNITS) { "net quantity must be in g, ml or ud: ${net.unit}" }
        require(net.amount.milli > 0) { "net quantity must be positive" }
        require(drained == null || drained.unit == net.unit) { "drained weight must use the net unit" }
        require(unitCount == null || unitCount > 0) { "unitCount must be positive" }
    }

    /**
     * The ways an ingredient amount can be measured against this package, in order of
     * preference: piece count first (exact for "4 tortillas of 6"), then mass or volume.
     */
    fun measures(): List<PackageMeasure> =
        buildList {
            if (unitCount != null) {
                add(PackageMeasure(unitCountUnit ?: MeasureUnit.UD, BigDecimal(unitCount)))
            }
            if (net.unit == MeasureUnit.UD) {
                if (unitCount == null) add(PackageMeasure(MeasureUnit.UD, net.value))
            } else {
                add(PackageMeasure(net.unit, (drained ?: net).value))
            }
        }

    companion object {
        val BASE_UNITS: Set<MeasureUnit> = setOf(MeasureUnit.G, MeasureUnit.ML, MeasureUnit.UD)
    }
}

/** One way to measure a package: [usable] amount of [unit] per package. */
data class PackageMeasure(
    val unit: MeasureUnit,
    val usable: BigDecimal,
)
