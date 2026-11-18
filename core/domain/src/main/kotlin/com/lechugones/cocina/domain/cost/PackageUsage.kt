package com.lechugones.cocina.domain.cost

import com.lechugones.cocina.domain.product.PackageMeasure
import com.lechugones.cocina.domain.product.Product
import com.lechugones.cocina.domain.units.Dimension
import com.lechugones.cocina.domain.units.MeasureUnit
import com.lechugones.cocina.domain.units.UnitConverter
import com.lechugones.cocina.domain.units.UnitWeight
import java.math.BigDecimal
import java.math.RoundingMode

/** An ingredient amount expressed against one package: [used] of [measure], [ratio] packages. */
data class PackageUsage(
    val measure: PackageMeasure,
    val used: BigDecimal,
    val ratio: BigDecimal,
)

object PackageUsageResolver {
    private const val RATIO_SCALE = 9

    /**
     * Expresses [amount] of [unit] against [product]'s package, trying its measures in order
     * (piece count, then mass/volume). Returns null when no measure is compatible (SPEC § 5.1).
     */
    fun resolve(
        amount: BigDecimal,
        unit: MeasureUnit,
        unitWeight: UnitWeight?,
        product: Product,
    ): PackageUsage? {
        if (unit.dimension == Dimension.NEGLIGIBLE) return null
        for (measure in product.measures()) {
            if (measure.usable.signum() <= 0) continue
            val used = UnitConverter.convert(amount, unit, measure.unit, unitWeight) ?: continue
            return PackageUsage(measure, used, used.divide(measure.usable, RATIO_SCALE, RoundingMode.HALF_UP))
        }
        return null
    }
}
