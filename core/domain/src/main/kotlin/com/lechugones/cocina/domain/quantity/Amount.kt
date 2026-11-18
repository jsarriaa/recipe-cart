package com.lechugones.cocina.domain.quantity

import com.lechugones.cocina.domain.units.MeasureUnit
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Fixed-point decimal with exactly three decimals, stored as thousandths ([milli]).
 * Used for every stored quantity (SPEC § 5.2).
 */
@JvmInline
value class Amount(
    val milli: Long,
) : Comparable<Amount> {
    fun toBigDecimal(): BigDecimal = BigDecimal.valueOf(milli, SCALE)

    override fun compareTo(other: Amount): Int = milli.compareTo(other.milli)

    /** Plain decimal with a dot and no trailing zeros: `0.5`, `300`, `1.25`. */
    override fun toString(): String = toBigDecimal().stripTrailingZeros().toPlainString()

    companion object {
        const val SCALE = 3

        /** Rounds half-up to three decimals. */
        fun of(value: BigDecimal): Amount =
            Amount(
                value
                    .setScale(SCALE, RoundingMode.HALF_UP)
                    .unscaledValue()
                    .longValueExact(),
            )

        fun of(value: Long): Amount = Amount(Math.multiplyExact(value, 1000L))

        fun of(value: Int): Amount = of(value.toLong())

        /** Parses a plain decimal with a dot, e.g. `"1.5"`. Throws on malformed input. */
        fun parse(plain: String): Amount = of(BigDecimal(plain))
    }
}

/** An [Amount] in a [MeasureUnit]. */
data class Quantity(
    val amount: Amount,
    val unit: MeasureUnit,
) {
    val value: BigDecimal get() = amount.toBigDecimal()

    override fun toString(): String = "$amount ${unit.code}"
}
