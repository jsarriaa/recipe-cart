package com.lechugones.cocina.domain.quantity

import com.lechugones.cocina.domain.quantity.QuantityParseResult.Invalid
import com.lechugones.cocina.domain.quantity.QuantityParseResult.Reason
import com.lechugones.cocina.domain.quantity.QuantityParseResult.Valid
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertIs

class QuantityParserTest {
    @ParameterizedTest
    @CsvSource(
        "300, 300000",
        "0.5, 500",
        "1.25, 1250",
        "' 2 ', 2000",
        "1/2, 500",
        "3/4, 750",
        "1 1/2, 1500",
        "2  1/4, 2250",
        "1/3, 333",
        "2/3, 667",
        "100000, 100000000",
    )
    fun `parses decimals and fractions`(
        raw: String,
        expectedMilli: Long,
    ) {
        val result = assertIs<Valid>(QuantityParser.parse(raw))
        assertEquals(expectedMilli, result.amount.milli)
        assertEquals(false, result.usedDecimalComma)
        assertEquals(false, result.rounded, "fractions and short decimals are not 'rounded'")
    }

    @Test
    fun `decimal comma is accepted and flagged`() {
        val result = assertIs<Valid>(QuantityParser.parse("1,5"))
        assertEquals(1500, result.amount.milli)
        assertEquals(true, result.usedDecimalComma)
    }

    @Test
    fun `more than three decimals are rounded half up and flagged`() {
        val result = assertIs<Valid>(QuantityParser.parse("0.3335"))
        assertEquals(334, result.amount.milli)
        assertEquals(true, result.rounded)
    }

    @ParameterizedTest
    @CsvSource(
        "abc, MALFORMED",
        "'', MALFORMED",
        "-1, MALFORMED",
        ".5, MALFORMED",
        "1/2/3, MALFORMED",
        "0, NOT_POSITIVE",
        "0/4, NOT_POSITIVE",
        "0.0001, NOT_POSITIVE",
        "100000.001, TOO_LARGE",
        "1/0, BAD_DENOMINATOR",
        "1/1001, BAD_DENOMINATOR",
        "1/99999999999, BAD_DENOMINATOR",
    )
    fun `rejects invalid quantities`(
        raw: String,
        reason: Reason,
    ) {
        assertEquals(Invalid(reason), QuantityParser.parse(raw))
    }

    @Test
    fun `numeric values from YAML are validated the same way`() {
        assertIs<Valid>(QuantityParser.fromValue(BigDecimal("0.5")))
        assertEquals(Invalid(Reason.NOT_POSITIVE), QuantityParser.fromValue(BigDecimal("-2")))
    }
}
