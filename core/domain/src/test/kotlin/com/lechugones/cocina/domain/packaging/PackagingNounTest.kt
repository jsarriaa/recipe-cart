package com.lechugones.cocina.domain.packaging

import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PackagingNounTest {
    @ParameterizedTest
    @CsvSource(
        "paquete,paquetes",
        "bandeja,bandejas",
        "bote,botes",
        "sobre,sobres",
        "tarrina,tarrinas",
        "lata,latas",
        "malla,mallas",
        "pieza,piezas",
        "cruz,cruces",
    )
    fun `plural follows the spec rule`(
        singular: String,
        plural: String,
    ) {
        assertEquals(plural, PackagingNoun.pluralize(singular))
    }

    @Test
    fun `provider packaging is lowercased, missing packaging is left to the UI`() {
        assertEquals("paquete", PackagingNoun.of("Paquete", plural = false))
        assertEquals("bandejas", PackagingNoun.of(" Bandeja ", plural = true))
        assertNull(PackagingNoun.of(null, plural = false))
        assertNull(PackagingNoun.of("  ", plural = true))
    }
}
