package com.lechugones.cocina.domain.units

import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.EnumSource
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MeasureUnitTest {
    @ParameterizedTest
    @EnumSource(MeasureUnit::class)
    fun `every canonical code parses to itself without normalisation`(unit: MeasureUnit) {
        val parsed = MeasureUnit.parse(unit.code)
        assertEquals(unit, parsed?.unit)
        assertFalse(parsed!!.normalized)
        assertEquals(unit, MeasureUnit.fromCode(unit.code))
    }

    @ParameterizedTest
    @CsvSource(
        "gramos, G",
        "Kg, KG",
        "L, L",
        "litros, L",
        "' cucharada ', CDA",
        "cucharaditas, CDTA",
        "tbsp, CDA",
        "unidades, UD",
        "piezas, UD",
        "dientes, DIENTE",
        "'al gusto', AL_GUSTO",
        "'AL   GUSTO', AL_GUSTO",
        "c/s, AL_GUSTO",
        "pizcas, PIZCA",
    )
    fun `aliases and other spellings are normalised`(
        raw: String,
        expected: MeasureUnit,
    ) {
        val parsed = MeasureUnit.parse(raw)
        assertEquals(expected, parsed?.unit)
        assertTrue(parsed!!.normalized)
    }

    @ParameterizedTest
    @CsvSource("lata", "sobre", "paquete", "chorrito", "puñado", "''", "kgs")
    fun `package words and unknown units are rejected`(raw: String) {
        assertNull(MeasureUnit.parse(raw))
    }

    @Test
    fun `quantity rules per unit`() {
        assertTrue(MeasureUnit.G.requiresQuantity)
        assertFalse(MeasureUnit.PIZCA.requiresQuantity)
        assertTrue(MeasureUnit.PIZCA.allowsQuantity)
        assertFalse(MeasureUnit.AL_GUSTO.allowsQuantity)
        assertFalse(MeasureUnit.PIZCA.scalesWithServings)
        assertTrue(MeasureUnit.CDA.isKitchenVolume)
        assertFalse(MeasureUnit.ML.isKitchenVolume)
    }

    @Test
    fun `codes and aliases never collide`() {
        val all = MeasureUnit.entries.flatMap { listOf(it.code) + it.aliases }
        assertEquals(all.size, all.toSet().size, "duplicated code or alias")
    }
}
