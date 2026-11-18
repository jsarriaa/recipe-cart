package com.lechugones.cocina.domain.packaging

import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PackageFractionTest {
    /** The examples table in SPEC § 5.3, plus edge cases. */
    @ParameterizedTest(name = "{0} of {1} → {2} + {3}, exact={4}")
    @CsvSource(
        "250, 500, 0, HALF, true",
        "750, 500, 1, HALF, true",
        "150, 500, 0, THIRD, false",
        "4, 6, 0, TWO_THIRDS, true",
        "10, 17, 0, TWO_THIRDS, false",
        "300, 550, 0, HALF, false",
        "60, 200, 0, THIRD, false",
        "200, 200, 1, ZERO, true",
        "99, 100, 1, ZERO, true",
        "90, 100, 1, ZERO, false",
        "270, 100, 2, TWO_THIRDS, false",
        "13, 100, 0, QUARTER, false",
        "125, 1000, 0, QUARTER, false",
        "1000, 500, 2, ZERO, true",
        "760, 1000, 0, THREE_QUARTERS, true",
    )
    fun `snaps to simple fractions`(
        used: String,
        usable: String,
        whole: Int,
        rest: SimpleFraction,
        exact: Boolean,
    ) {
        val fraction = assertIs<PackageFraction.Fraction>(PackageFraction.of(BigDecimal(used), BigDecimal(usable)))
        assertEquals(whole, fraction.whole, "whole")
        assertEquals(rest, fraction.rest, "rest")
        assertEquals(exact, fraction.exact, "exact")
    }

    @ParameterizedTest(name = "{0} of {1} → ≈ {2} %")
    @CsvSource("15, 1000, 2", "5, 250, 2", "124, 1000, 12", "1, 1000, 1", "1, 100000, 1")
    fun `tiny shares become percentages`(
        used: String,
        usable: String,
        percent: Int,
    ) {
        val fraction = assertIs<PackageFraction.Percent>(PackageFraction.of(BigDecimal(used), BigDecimal(usable)))
        assertEquals(percent, fraction.percent)
    }

    @Test
    fun `plural only above one package`() {
        assertFalse(fractionOf("250", "500").isPlural)
        assertFalse(fractionOf("500", "500").isPlural)
        assertTrue(fractionOf("750", "500").isPlural)
        assertTrue(fractionOf("1000", "500").isPlural)
    }

    @Test
    fun `nothing used gives no fraction`() {
        assertNull(PackageFraction.of(BigDecimal.ZERO, BigDecimal(500)))
        assertNull(PackageFraction.of(BigDecimal.ONE, BigDecimal.ZERO))
    }

    private fun fractionOf(
        used: String,
        usable: String,
    ) = assertIs<PackageFraction.Fraction>(PackageFraction.of(BigDecimal(used), BigDecimal(usable)))
}
