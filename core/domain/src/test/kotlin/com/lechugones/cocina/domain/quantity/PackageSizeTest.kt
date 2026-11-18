package com.lechugones.cocina.domain.quantity

import com.lechugones.cocina.domain.units.MeasureUnit
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class PackageSizeTest {
    @ParameterizedTest(name = "\"{0}\" → \"{1}\"")
    @CsvSource(
        "500 g, 500 g",
        "1 kg, 1 kg",
        "'1,5 l', 1.5 l",
        "250ml, 250 ml",
        "6 ud, 6 ud",
        "4 x 125 g, 4 x 125 g",
        "2×1 l, 2 x 1 l",
        "1 x 500 g, 500 g",
        "' 33 CL ', 33 cl",
        "3X200ML, 3 x 200 ml",
    )
    fun `parses the grammar and prints the canonical form`(
        raw: String,
        canonical: String,
    ) {
        val size = assertNotNull(PackageSize.parse(raw))
        assertEquals(canonical, size.canonical())
        assertEquals(size, PackageSize.parse(size.canonical()), "canonical form must parse back")
    }

    @ParameterizedTest
    @CsvSource("medio kilo", "1 paquete", "500", "g 500", "0 g", "101 x 1 g", "4 x 0 g", "1 lata", "''", "-5 g")
    fun `rejects invalid sizes`(raw: String) {
        assertNull(PackageSize.parse(raw))
    }

    @Test
    fun `total content is expressed in the base unit`() {
        assertEquals(Quantity(Amount.of(500), MeasureUnit.G), PackageSize.parse("4 x 125 g")?.total())
        assertEquals(Quantity(Amount.of(1500), MeasureUnit.ML), PackageSize.parse("1.5 l")?.total())
        assertEquals(Quantity(Amount.of(600), MeasureUnit.ML), PackageSize.parse("3 x 200 ml")?.total())
        assertEquals(Quantity(Amount.of(12), MeasureUnit.UD), PackageSize.parse("2 x 6 ud")?.total())
    }
}
