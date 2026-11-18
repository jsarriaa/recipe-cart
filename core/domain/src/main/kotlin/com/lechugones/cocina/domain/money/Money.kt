package com.lechugones.cocina.domain.money

import java.math.BigDecimal
import java.math.RoundingMode

/** An amount of euros, stored as integer cents (SPEC § 5.2). */
@JvmInline
value class Money(
    val cents: Long,
) : Comparable<Money> {
    operator fun plus(other: Money): Money = Money(Math.addExact(cents, other.cents))

    operator fun times(factor: Int): Money = Money(Math.multiplyExact(cents, factor.toLong()))

    override fun compareTo(other: Money): Int = cents.compareTo(other.cents)

    /** Euros as an exact decimal with two places. */
    fun toEuros(): BigDecimal = BigDecimal.valueOf(cents, 2)

    override fun toString(): String = "${toEuros().toPlainString()} EUR"

    companion object {
        val ZERO = Money(0)
        private val euroPattern = Regex("""^-?\d+(?:[.,]\d+)?$""")

        /**
         * Parses a decimal euro string such as the `"1.50"` providers send (a comma is accepted
         * too). More than two decimals are rounded half-up. Returns null when malformed.
         */
        fun parseEuros(text: String): Money? {
            val trimmed = text.trim()
            if (!euroPattern.matches(trimmed)) return null
            return ofEuros(BigDecimal(trimmed.replace(',', '.')))
        }

        fun ofEuros(value: BigDecimal): Money = Money(value.setScale(2, RoundingMode.HALF_UP).unscaledValue().longValueExact())

        /** Rounds an exact amount of cents (e.g. a proportional cost) half-up to whole cents. */
        fun ofCents(value: BigDecimal): Money = Money(value.setScale(0, RoundingMode.HALF_UP).longValueExact())
    }
}
