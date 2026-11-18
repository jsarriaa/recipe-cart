package com.lechugones.cocina.domain.units

import com.lechugones.cocina.domain.assertDecimal
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.math.BigDecimal
import kotlin.test.assertNull

class UnitConverterTest {
    @ParameterizedTest
    @CsvSource(
        "1.5, KG, G, 1500",
        "250, G, KG, 0.25",
        "1, L, ML, 1000",
        "33, CL, ML, 330",
        "1, CDA, ML, 15",
        "1, CDTA, ML, 5",
        "3, CDTA, CDA, 1",
        "1, TAZA, ML, 250",
        "500, ML, L, 0.5",
        "4, UD, UD, 4",
        "2, DIENTE, DIENTE, 2",
    )
    fun `converts within a dimension`(
        amount: String,
        from: MeasureUnit,
        to: MeasureUnit,
        expected: String,
    ) {
        assertDecimal(expected, UnitConverter.convert(BigDecimal(amount), from, to))
    }

    @Test
    fun `count to mass needs a unit weight`() {
        val avocado = UnitWeight(MeasureUnit.UD, BigDecimal(200))
        assertDecimal("300", UnitConverter.convert(BigDecimal("1.5"), MeasureUnit.UD, MeasureUnit.G, avocado))
        assertDecimal("0.2", UnitConverter.convert(BigDecimal.ONE, MeasureUnit.UD, MeasureUnit.KG, avocado))
        assertDecimal("2", UnitConverter.convert(BigDecimal(400), MeasureUnit.G, MeasureUnit.UD, avocado))
        assertNull(UnitConverter.convert(BigDecimal.ONE, MeasureUnit.UD, MeasureUnit.G))
    }

    @Test
    fun `spoon to mass uses the ingredient density`() {
        val cumin = UnitWeight(MeasureUnit.CDA, BigDecimal(6))
        assertDecimal("6", UnitConverter.convert(BigDecimal.ONE, MeasureUnit.CDA, MeasureUnit.G, cumin))
        // 1 cdta = 5 ml = 1/3 cda → 2 g with the same density.
        assertDecimal("2", UnitConverter.convert(BigDecimal.ONE, MeasureUnit.CDTA, MeasureUnit.G, cumin))
        assertDecimal("0.4", UnitConverter.convert(BigDecimal.ONE, MeasureUnit.ML, MeasureUnit.G, cumin))
    }

    @Test
    fun `incompatible dimensions return null`() {
        assertNull(UnitConverter.convert(BigDecimal.ONE, MeasureUnit.G, MeasureUnit.ML))
        assertNull(UnitConverter.convert(BigDecimal.ONE, MeasureUnit.UD, MeasureUnit.DIENTE))
        assertNull(UnitConverter.convert(BigDecimal.ONE, MeasureUnit.LONCHA, MeasureUnit.G))
        val clove = UnitWeight(MeasureUnit.DIENTE, BigDecimal(5))
        assertNull(UnitConverter.convert(BigDecimal.ONE, MeasureUnit.UD, MeasureUnit.G, clove))
    }

    @Test
    fun `negligible units never convert`() {
        assertNull(UnitConverter.convert(BigDecimal.ONE, MeasureUnit.PIZCA, MeasureUnit.G))
        assertNull(UnitConverter.convert(BigDecimal.ONE, MeasureUnit.G, MeasureUnit.AL_GUSTO))
        assertDecimal("1", UnitConverter.convert(BigDecimal.ONE, MeasureUnit.PIZCA, MeasureUnit.PIZCA))
    }
}
