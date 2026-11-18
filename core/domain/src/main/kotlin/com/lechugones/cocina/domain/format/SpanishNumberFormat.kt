package com.lechugones.cocina.domain.format

import com.lechugones.cocina.domain.money.Money
import com.lechugones.cocina.domain.quantity.MixedFraction
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * es-ES number formatting, independent of the device locale and of the JVM's locale data
 * (CLAUDE.md language rules). Only digits and symbols: words come from string resources.
 *
 * - Decimal comma: `1,5`.
 * - Thousands are grouped with a dot only from five integer digits: `1234`, `12.345`
 *   (CLDR minimum grouping digits for Spanish).
 * - Money: `3,45 €`, with a no-break space before the euro sign.
 */
object SpanishNumberFormat {
    /** U+00A0, written as a code point so editors cannot turn it into a plain space. */
    val NO_BREAK_SPACE: Char = Char(0x00A0)

    fun decimal(
        value: BigDecimal,
        maxFractionDigits: Int = 2,
    ): String {
        val rounded = value.setScale(maxFractionDigits, RoundingMode.HALF_UP).stripTrailingZeros()
        val negative = rounded.signum() < 0
        val plain = rounded.abs().toPlainString()
        val integerPart = plain.substringBefore('.')
        val fractionPart = plain.substringAfter('.', missingDelimiterValue = "")
        val sign = if (negative) "-" else ""
        val fraction = if (fractionPart.isEmpty()) "" else ",$fractionPart"
        return sign + group(integerPart) + fraction
    }

    fun money(money: Money): String {
        val negative = money.cents < 0
        val abs = Math.absExact(money.cents)
        val euros = (abs / 100).toString()
        val cents = (abs % 100).toString().padStart(2, '0')
        val sign = if (negative) "-" else ""
        return "$sign${group(euros)},$cents$NO_BREAK_SPACE€"
    }

    /** `2`, `1/2`, `1 1/2`. */
    fun mixedFraction(fraction: MixedFraction): String =
        when {
            fraction.numerator == 0 -> fraction.whole.toString()
            fraction.whole == 0 -> "${fraction.numerator}/${fraction.denominator}"
            else -> "${fraction.whole} ${fraction.numerator}/${fraction.denominator}"
        }

    private fun group(integerDigits: String): String {
        if (integerDigits.length < 5) return integerDigits
        return integerDigits
            .reversed()
            .chunked(3)
            .joinToString(".")
            .reversed()
    }
}
