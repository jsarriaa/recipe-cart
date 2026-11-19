package com.lechugones.cocina.feature.settings.update

import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AppVersionTest {
    @Test
    fun `newer versions are found, numerically`() {
        assertTrue(AppVersion.isNewer("1.1.0", "1.0.0"))
        assertTrue(AppVersion.isNewer("v1.10.0", "1.9.2"))
        assertTrue(AppVersion.isNewer("2", "1.9.9"))
        assertTrue(AppVersion.isNewer("1.0.1", "1.0"))
    }

    @Test
    fun `same or older versions are not updates, whatever the suffix`() {
        assertFalse(AppVersion.isNewer("1.1.0", "1.1.0"))
        assertFalse(AppVersion.isNewer("v1.1.0", "1.1.0-debug"))
        assertFalse(AppVersion.isNewer("1.0.9", "1.1.0"))
        assertFalse(AppVersion.isNewer("1.1", "1.1.0"))
    }
}
