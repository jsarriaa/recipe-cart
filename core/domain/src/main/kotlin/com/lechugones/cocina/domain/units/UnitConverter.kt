package com.lechugones.cocina.domain.units

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Weight in grams of one [unit] of a specific ingredient (`unit_weight_g` in recipe packs).
 * Example: one avocado (`ud`) weighs 200 g; one tablespoon (`cda`) of cumin weighs 6 g.
 */
data class UnitWeight(
    val unit: MeasureUnit,
    val grams: BigDecimal,
)

/** Unit conversions as defined in SPEC § 5.1. */
object UnitConverter {
    /** Scale used for intermediate results. */
    const val SCALE = 9

    /**
     * Converts [amount] from [from] to [to]. Returns null when no conversion path exists.
     *
     * - Same unit: unchanged.
     * - Mass ↔ mass, volume ↔ volume (including kitchen units): by fixed factors.
     * - Anything ↔ mass, or count/volume across dimensions: only through [unitWeight].
     * - Negligible units never convert.
     */
    fun convert(
        amount: BigDecimal,
        from: MeasureUnit,
        to: MeasureUnit,
        unitWeight: UnitWeight? = null,
    ): BigDecimal? {
        if (from == to) return amount
        if (from.dimension == Dimension.NEGLIGIBLE || to.dimension == Dimension.NEGLIGIBLE) return null
        val fromFactor = from.factorToBase
        val toFactor = to.factorToBase
        if (from.dimension == to.dimension && fromFactor != null && toFactor != null) {
            return amount.multiply(fromFactor).divide(toFactor, SCALE, RoundingMode.HALF_UP)
        }
        val gramsFrom = gramsPerUnit(from, unitWeight) ?: return null
        val gramsTo = gramsPerUnit(to, unitWeight) ?: return null
        return amount.multiply(gramsFrom).divide(gramsTo, SCALE, RoundingMode.HALF_UP)
    }

    /** Grams in one [unit], if known. Volume units derive a density from a volume [unitWeight]. */
    fun gramsPerUnit(
        unit: MeasureUnit,
        unitWeight: UnitWeight?,
    ): BigDecimal? {
        if (unit.dimension == Dimension.MASS) return unit.factorToBase
        if (unitWeight == null) return null
        if (unit == unitWeight.unit) return unitWeight.grams
        val unitFactor = unit.factorToBase
        val weightFactor = unitWeight.unit.factorToBase
        return if (
            unit.dimension == Dimension.VOLUME &&
            unitWeight.unit.dimension == Dimension.VOLUME &&
            unitFactor != null &&
            weightFactor != null
        ) {
            unitWeight.grams.multiply(unitFactor).divide(weightFactor, SCALE, RoundingMode.HALF_UP)
        } else {
            null
        }
    }

    /** Base unit of a dimension used for package contents: g, ml or ud. */
    fun packageBaseUnit(unit: MeasureUnit): MeasureUnit =
        when (unit.dimension) {
            Dimension.MASS -> MeasureUnit.G
            Dimension.VOLUME -> MeasureUnit.ML
            Dimension.COUNT, Dimension.NEGLIGIBLE -> MeasureUnit.UD
        }
}
