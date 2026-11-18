package com.lechugones.cocina.domain.quantity

import com.lechugones.cocina.domain.units.Dimension
import com.lechugones.cocina.domain.units.MeasureUnit
import com.lechugones.cocina.domain.units.UnitConverter
import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode

/** A whole number plus a proper fraction, e.g. 1 1/2. [numerator] is 0 for whole numbers. */
data class MixedFraction(
    val whole: Int,
    val numerator: Int,
    val denominator: Int,
) {
    init {
        require(whole >= 0 && numerator >= 0 && denominator > 0 && numerator < denominator)
    }
}

/** A quantity rounded for display. The UI turns it into text with es-ES formatting and unit names. */
sealed interface DisplayQuantity {
    val unit: MeasureUnit

    data class Decimal(
        val value: BigDecimal,
        override val unit: MeasureUnit,
    ) : DisplayQuantity

    data class Fraction(
        val value: MixedFraction,
        override val unit: MeasureUnit,
    ) : DisplayQuantity

    /** `al_gusto`: no number is shown. */
    data class NoQuantity(
        override val unit: MeasureUnit,
    ) : DisplayQuantity
}

/** Friendly rounding of (scaled) quantities, SPEC § 5.3. Computations never use these values. */
object QuantityDisplay {
    private val THOUSAND = BigDecimal(1000)
    private val TEN = BigDecimal.TEN
    private val HUNDRED = BigDecimal(100)
    private val FIVE = BigDecimal(5)
    private val MIN_SMALL = BigDecimal("0.1")

    fun friendly(
        value: BigDecimal?,
        unit: MeasureUnit,
    ): DisplayQuantity =
        when {
            unit == MeasureUnit.AL_GUSTO -> DisplayQuantity.NoQuantity(unit)
            unit == MeasureUnit.PIZCA -> pinch(value)
            value == null -> DisplayQuantity.NoQuantity(unit)
            unit.isKitchenVolume -> DisplayQuantity.Fraction(nearestFraction(value, denominator = 2), unit)
            unit.dimension == Dimension.COUNT -> DisplayQuantity.Fraction(nearestFraction(value, denominator = 4), unit)
            else -> metric(value, unit)
        }

    /**
     * Rounds [value] to the nearest 1/[denominator], never below 1/4 for positive values.
     */
    fun nearestFraction(
        value: BigDecimal,
        denominator: Int,
    ): MixedFraction {
        var steps = value.multiply(BigDecimal(denominator)).setScale(0, RoundingMode.HALF_UP).toInt()
        var den = denominator
        if (steps == 0 && value.signum() > 0) {
            // Never show 0 for something that is there: fall back to a quarter.
            steps = 1
            den = 4
        }
        val whole = steps / den
        val rest = steps % den
        if (rest == 0) return MixedFraction(whole, 0, 1)
        val gcd = BigInteger.valueOf(rest.toLong()).gcd(BigInteger.valueOf(den.toLong())).toInt()
        return MixedFraction(whole, rest / gcd, den / gcd)
    }

    private fun pinch(value: BigDecimal?): DisplayQuantity {
        val count = value?.setScale(0, RoundingMode.HALF_UP)?.max(BigDecimal.ONE) ?: BigDecimal.ONE
        return DisplayQuantity.Decimal(count, MeasureUnit.PIZCA)
    }

    private fun metric(
        value: BigDecimal,
        unit: MeasureUnit,
    ): DisplayQuantity {
        val small = if (unit.dimension == Dimension.MASS) MeasureUnit.G else MeasureUnit.ML
        val big = if (unit.dimension == Dimension.MASS) MeasureUnit.KG else MeasureUnit.L
        val base = UnitConverter.convert(value, unit, small) ?: value
        val rounded = roundSmall(base)
        if (rounded >= THOUSAND) {
            val bigValue = base.divide(THOUSAND, 2, RoundingMode.HALF_UP).stripTrailingZeros()
            return DisplayQuantity.Decimal(bigValue.plain(), big)
        }
        return DisplayQuantity.Decimal(rounded.plain(), small)
    }

    private fun roundSmall(base: BigDecimal): BigDecimal =
        when {
            base < TEN -> base.setScale(1, RoundingMode.HALF_UP).max(MIN_SMALL).stripTrailingZeros()
            base <= HUNDRED -> base.setScale(0, RoundingMode.HALF_UP)
            else -> base.divide(FIVE, 0, RoundingMode.HALF_UP).multiply(FIVE)
        }

    /** Avoids scientific notation scales such as 1E+3 after [BigDecimal.stripTrailingZeros]. */
    private fun BigDecimal.plain(): BigDecimal = if (scale() < 0) setScale(0) else this
}
