package com.lechugones.cocina.domain.recipe

import com.lechugones.cocina.domain.assertDecimal
import com.lechugones.cocina.domain.quantity.Amount
import com.lechugones.cocina.domain.units.MeasureUnit
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RecipeHelpersTest {
    @ParameterizedTest
    @CsvSource(
        "'Contramuslo de Pollo  deshuesado', 'contramuslo de pollo deshuesado'",
        "Ñoquis, noquis",
        "'  Jamón ibérico ', 'jamon iberico'",
        "'Aceite (virgen extra)', 'aceite virgen extra'",
        "'pimentón-dulce', 'pimenton dulce'",
        "'Tomates cherry 250g', 'tomates cherry 250g'",
    )
    fun `name key normalises case, accents and punctuation`(
        name: String,
        expected: String,
    ) {
        assertEquals(expected, NameKey.of(name))
    }

    @Test
    fun `scaling multiplies by the servings ratio`() {
        assertDecimal("450", ServingsScaler.scale(Amount.of(300), MeasureUnit.G, 2, 3))
        assertDecimal("6", ServingsScaler.scale(Amount.of(4), MeasureUnit.UD, 2, 3))
        assertDecimal("0.5", ServingsScaler.scale(Amount.of(1), MeasureUnit.UD, 4, 2))
        assertDecimal("300", ServingsScaler.scale(Amount.of(300), MeasureUnit.G, 2, 2))
    }

    @Test
    fun `negligible units and missing quantities do not scale`() {
        assertDecimal("1", ServingsScaler.scale(Amount.of(1), MeasureUnit.PIZCA, 2, 6))
        assertNull(ServingsScaler.scale(null, MeasureUnit.AL_GUSTO, 2, 6))
    }

    @Test
    fun `scaling rejects non-positive servings`() {
        assertThrows<IllegalArgumentException> { ServingsScaler.scale(Amount.of(1), MeasureUnit.G, 0, 2) }
    }
}
