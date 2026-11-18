package com.lechugones.cocina.domain.money

import com.lechugones.cocina.domain.format.SpanishNumberFormat
import com.lechugones.cocina.domain.quantity.MixedFraction
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MoneyAndFormatTest {
    private val nbsp = SpanishNumberFormat.NO_BREAK_SPACE

    @ParameterizedTest
    @CsvSource("1.50, 150", "0.9, 90", "52.94, 5294", "5.294, 529", "5.295, 530", "'1,15', 115", "3, 300", "-0.5, -50")
    fun `parses provider euro strings exactly`(
        text: String,
        cents: Long,
    ) {
        assertEquals(Money(cents), Money.parseEuros(text))
    }

    @ParameterizedTest
    @CsvSource("abc", "''", "1.2.3", "€1")
    fun `rejects malformed euro strings`(text: String) {
        assertNull(Money.parseEuros(text))
    }

    @Test
    fun `arithmetic and exact cent rounding`() {
        assertEquals(Money(450), Money(150) * 3)
        assertEquals(Money(240), Money(150) + Money(90))
        // One third of a 1,00 € package costs 0,33 € (SPEC AC4.2).
        assertEquals(Money(33), Money.ofCents(BigDecimal("33.333333333")))
        assertEquals(Money(67), Money.ofCents(BigDecimal("66.5")))
    }

    @ParameterizedTest
    @CsvSource(
        "345, '3,45'",
        "5, '0,05'",
        "100, '1,00'",
        "123456, '1234,56'",
        "1234567, '12.345,67'",
        "-250, '-2,50'",
    )
    fun `formats money in es-ES`(
        cents: Long,
        expected: String,
    ) {
        assertEquals("$expected$nbsp€", SpanishNumberFormat.money(Money(cents)))
    }

    @ParameterizedTest
    @CsvSource(
        "1.5, '1,5'",
        "1.25, '1,25'",
        "1.255, '1,26'",
        "250, '250'",
        "1000, '1000'",
        "12500, '12.500'",
        "1234567.891, '1.234.567,89'",
        "0.004, '0'",
        "-0.004, '0'",
        "-3.5, '-3,5'",
    )
    fun `formats decimals in es-ES`(
        value: String,
        expected: String,
    ) {
        assertEquals(expected, SpanishNumberFormat.decimal(BigDecimal(value)))
    }

    @Test
    fun `formats mixed fractions`() {
        assertEquals("2", SpanishNumberFormat.mixedFraction(MixedFraction(2, 0, 1)))
        assertEquals("1/2", SpanishNumberFormat.mixedFraction(MixedFraction(0, 1, 2)))
        assertEquals("1 3/4", SpanishNumberFormat.mixedFraction(MixedFraction(1, 3, 4)))
    }
}
