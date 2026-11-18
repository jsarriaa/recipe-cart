package com.lechugones.cocina.domain.packaging

import java.math.BigDecimal
import java.math.RoundingMode

/** The fractions a package share is snapped to (SPEC § 5.3). */
enum class SimpleFraction(
    val numerator: Int,
    val denominator: Int,
) {
    ZERO(0, 1),
    QUARTER(1, 4),
    THIRD(1, 3),
    HALF(1, 2),
    TWO_THIRDS(2, 3),
    THREE_QUARTERS(3, 4),
    ;

    val value: BigDecimal = BigDecimal(numerator).divide(BigDecimal(denominator), 9, RoundingMode.HALF_UP)
}

/**
 * How much of one package an amount uses, e.g. "1/2 paquete" or "≈ 5 % del envase".
 * Structured only: the UI builds the Spanish sentence with string resources.
 */
sealed interface PackageFraction {
    /** used ÷ usable package quantity. */
    val ratio: BigDecimal

    /** `whole` packages plus `rest`. [exact] is false when the UI must prefix "≈". */
    data class Fraction(
        override val ratio: BigDecimal,
        val whole: Int,
        val rest: SimpleFraction,
        val exact: Boolean,
    ) : PackageFraction {
        /** More than one package: the packaging noun is pluralised. */
        val isPlural: Boolean get() = whole > 1 || (whole == 1 && rest != SimpleFraction.ZERO)
    }

    /** Less than 1/8 of a package: shown as an approximate percentage. */
    data class Percent(
        override val ratio: BigDecimal,
        val percent: Int,
    ) : PackageFraction

    companion object {
        val TOLERANCE = BigDecimal("0.02")
        val PERCENT_BELOW = BigDecimal("0.125")
        private const val RATIO_SCALE = 9

        /** Fraction of a package of [usable] that [used] represents. Null if either is not positive. */
        fun of(
            used: BigDecimal,
            usable: BigDecimal,
        ): PackageFraction? {
            if (used.signum() <= 0 || usable.signum() <= 0) return null
            return ofRatio(used.divide(usable, RATIO_SCALE, RoundingMode.HALF_UP))
        }

        fun ofRatio(ratio: BigDecimal): PackageFraction? {
            if (ratio.signum() <= 0) return null
            if (ratio < PERCENT_BELOW) {
                val percent =
                    ratio
                        .movePointRight(2)
                        .setScale(0, RoundingMode.HALF_UP)
                        .toInt()
                        .coerceAtLeast(1)
                return Percent(ratio, percent)
            }
            var whole = ratio.add(TOLERANCE).setScale(0, RoundingMode.FLOOR).toInt()
            val rest = ratio.subtract(BigDecimal(whole)).max(BigDecimal.ZERO)

            // Below one package, "0 paquetes" is never an answer: snap to at least a quarter.
            val candidates = if (whole == 0) SimpleFraction.entries.drop(1) else SimpleFraction.entries
            val nearest = candidates.minBy { rest.subtract(it.value).abs() }
            val distanceToNearest = rest.subtract(nearest.value).abs()
            val distanceToOne = rest.subtract(BigDecimal.ONE).abs()

            return if (distanceToOne < distanceToNearest) {
                whole += 1
                Fraction(ratio, whole, SimpleFraction.ZERO, exact = distanceToOne <= TOLERANCE)
            } else {
                Fraction(ratio, whole, nearest, exact = distanceToNearest <= TOLERANCE)
            }
        }
    }
}
