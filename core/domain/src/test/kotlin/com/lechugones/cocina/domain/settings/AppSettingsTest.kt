package com.lechugones.cocina.domain.settings

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AppSettingsTest {
    @Test
    fun `defaults are already sane`() {
        assertEquals(AppSettings(), AppSettings().sanitized())
    }

    @Test
    fun `invalid values fall back to safe ones`() {
        val sanitized =
            AppSettings(
                activeProviders = emptySet(),
                primaryProvider = "dia",
                postalCode = "4600",
                staleAfterDays = 0,
            ).sanitized()
        assertEquals(setOf("mercadona"), sanitized.activeProviders)
        assertEquals("mercadona", sanitized.primaryProvider)
        assertNull(sanitized.postalCode)
        assertEquals(AppSettings.DEFAULT_STALE_AFTER_DAYS, sanitized.staleAfterDays)
    }

    @Test
    fun `primary provider must be active`() {
        val sanitized = AppSettings(activeProviders = setOf("manual", "dia"), primaryProvider = "mercadona").sanitized()
        assertEquals("dia", sanitized.primaryProvider)
    }

    @Test
    fun `valid postal codes are trimmed and kept`() {
        assertEquals("46001", AppSettings(postalCode = " 46001 ").sanitized().postalCode)
    }
}
