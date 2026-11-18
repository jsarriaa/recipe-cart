package com.lechugones.cocina.domain.quantity

import com.lechugones.cocina.domain.assertDecimal
import com.lechugones.cocina.domain.units.MeasureUnit
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertIs

class QuantityDisplayTest {
    @ParameterizedTest(name = "{0} {1} → {2} {3}")
    @CsvSource(
        "3.25, G, 3.3, G",
        "0.04, G, 0.1, G",
        "7, G, 7, G",
        "45.5, G, 46, G",
        "100, G, 100, G",
        "101, G, 100, G",
        "333.333, G, 335, G",
        "450, G, 450, G",
        "998, G, 1, KG",
        "1250, G, 1.25, KG",
        "1.5, KG, 1.5, KG",
        "0.25, KG, 250, G",
        "33, CL, 330, ML",
        "1500, ML, 1.5, L",
        "0.2, L, 200, ML",
    )
    fun `metric quantities round to friendly values`(
        value: String,
        unit: MeasureUnit,
        expectedValue: String,
        expectedUnit: MeasureUnit,
    ) {
        val display = assertIs<DisplayQuantity.Decimal>(QuantityDisplay.friendly(BigDecimal(value), unit))
        assertDecimal(expectedValue, display.value)
        assertEquals(expectedUnit, display.unit)
    }

    @ParameterizedTest(name = "{0} {1} → {2} {3}/{4}")
    @CsvSource(
        "1.5, UD, 1, 1, 2",
        "1.333333, UD, 1, 1, 4",
        "0.6, UD, 0, 1, 2",
        "2, DIENTE, 2, 0, 1",
        "0.05, UD, 0, 1, 4",
        "1.2, CDA, 1, 0, 1",
        "1.3, CDA, 1, 1, 2",
        "0.1, CDTA, 0, 1, 4",
        "2.75, TAZA, 3, 0, 1",
    )
    fun `count and kitchen units round to simple fractions`(
        value: String,
        unit: MeasureUnit,
        whole: Int,
        numerator: Int,
        denominator: Int,
    ) {
        val display = assertIs<DisplayQuantity.Fraction>(QuantityDisplay.friendly(BigDecimal(value), unit))
        assertEquals(MixedFraction(whole, numerator, denominator), display.value)
        assertEquals(unit, display.unit)
    }

    @Test
    fun `negligible units`() {
        assertEquals(DisplayQuantity.NoQuantity(MeasureUnit.AL_GUSTO), QuantityDisplay.friendly(null, MeasureUnit.AL_GUSTO))
        val pinch = assertIs<DisplayQuantity.Decimal>(QuantityDisplay.friendly(null, MeasureUnit.PIZCA))
        assertDecimal("1", pinch.value)
        val twoPinches = assertIs<DisplayQuantity.Decimal>(QuantityDisplay.friendly(BigDecimal(2), MeasureUnit.PIZCA))
        assertDecimal("2", twoPinches.value)
    }
}
