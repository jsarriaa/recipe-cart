package com.lechugones.cocina.domain.quantity

import java.math.BigDecimal
import java.math.RoundingMode

/** Result of parsing a recipe quantity (RECIPE_FORMAT § 6). */
sealed interface QuantityParseResult {
    data class Valid(
        val amount: Amount,
        /** A decimal comma was used (`"1,5"`): normalised, warning `W02`. */
        val usedDecimalComma: Boolean = false,
        /** More than three decimals were given and rounded: warning `W12`. */
        val rounded: Boolean = false,
    ) : QuantityParseResult

    data class Invalid(
        val reason: Reason,
    ) : QuantityParseResult

    enum class Reason { MALFORMED, NOT_POSITIVE, TOO_LARGE, BAD_DENOMINATOR }
}

/**
 * Parses quantities written as decimals (`300`, `0.5`, `"1,5"`) or fractions (`"1/2"`,
 * `"1 1/2"`). Fractions are converted to three decimals without a warning.
 */
object QuantityParser {
    val MAX: BigDecimal = BigDecimal(100_000)
    private const val MAX_DENOMINATOR = 1000
    private val decimal = Regex("""^\d+(?:[.,]\d+)?$""")
    private val fraction = Regex("""^(?:(\d+)\s+)?(\d+)/(\d+)$""")

    fun parse(raw: String): QuantityParseResult {
        val text = raw.trim()
        fraction.matchEntire(text)?.let { match -> return parseFraction(match) }
        if (decimal.matches(text)) {
            return fromValue(BigDecimal(text.replace(',', '.')), usedDecimalComma = ',' in text)
        }
        return QuantityParseResult.Invalid(QuantityParseResult.Reason.MALFORMED)
    }

    /** Validates a numeric value (for YAML numbers) and converts it to an [Amount]. */
    fun fromValue(
        value: BigDecimal,
        usedDecimalComma: Boolean = false,
    ): QuantityParseResult {
        if (value.signum() <= 0) return QuantityParseResult.Invalid(QuantityParseResult.Reason.NOT_POSITIVE)
        if (value > MAX) return QuantityParseResult.Invalid(QuantityParseResult.Reason.TOO_LARGE)
        val amount = Amount.of(value)
        if (amount.milli == 0L) return QuantityParseResult.Invalid(QuantityParseResult.Reason.NOT_POSITIVE)
        val rounded = value.stripTrailingZeros().scale() > Amount.SCALE
        return QuantityParseResult.Valid(amount, usedDecimalComma, rounded)
    }

    private fun parseFraction(match: MatchResult): QuantityParseResult {
        val (wholeText, numeratorText, denominatorText) = match.destructured
        val denominator = denominatorText.toIntOrNull()
        if (denominator == null || denominator !in 1..MAX_DENOMINATOR) {
            return QuantityParseResult.Invalid(QuantityParseResult.Reason.BAD_DENOMINATOR)
        }
        val whole = if (wholeText.isEmpty()) BigDecimal.ZERO else BigDecimal(wholeText)
        val value =
            whole.add(
                BigDecimal(numeratorText).divide(BigDecimal(denominator), 9, RoundingMode.HALF_UP),
            )
        return when (val result = fromValue(value)) {
            is QuantityParseResult.Valid -> result.copy(rounded = false)
            is QuantityParseResult.Invalid -> result
        }
    }
}
