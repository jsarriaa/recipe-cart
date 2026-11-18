package com.lechugones.cocina.data.settings

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.lechugones.cocina.data.testing.InMemoryPreferencesDataStore
import com.lechugones.cocina.domain.settings.AppSettings
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Tests the mapping and sanitising done by [DataStoreSettingsRepository]. */
class DataStoreSettingsRepositoryTest {
    @Test
    fun `defaults when nothing is stored`() =
        runTest {
            assertEquals(AppSettings(), DataStoreSettingsRepository(InMemoryPreferencesDataStore()).settings.first())
        }

    @Test
    fun `updates are persisted`() =
        runTest {
            val repository = DataStoreSettingsRepository(InMemoryPreferencesDataStore())
            repository.update {
                it.copy(
                    activeProviders = setOf("mercadona", "manual"),
                    postalCode = "46001",
                    storeBrandOnly = true,
                    includeStaplesInCost = true,
                    staleAfterDays = 7,
                )
            }
            val stored = repository.settings.first()
            assertEquals(setOf("mercadona", "manual"), stored.activeProviders)
            assertEquals("46001", stored.postalCode)
            assertEquals(true, stored.storeBrandOnly)
            assertEquals(true, stored.includeStaplesInCost)
            assertEquals(7, stored.staleAfterDays)
        }

    @Test
    fun `clearing the postal code removes it`() =
        runTest {
            val store = InMemoryPreferencesDataStore()
            val repository = DataStoreSettingsRepository(store)
            repository.update { it.copy(postalCode = "28001") }
            repository.update { it.copy(postalCode = null) }
            assertNull(repository.settings.first().postalCode)
            assertNull(store.data.first()[stringPreferencesKey("postal_code")])
        }

    @Test
    fun `invalid values are sanitised on write and on read`() =
        runTest {
            val store = InMemoryPreferencesDataStore()
            val repository = DataStoreSettingsRepository(store)
            repository.update { it.copy(activeProviders = emptySet(), staleAfterDays = -3) }
            assertEquals(AppSettings(), repository.settings.first())

            store.edit {
                it[stringPreferencesKey("postal_code")] = "abc"
                it[intPreferencesKey("stale_after_days")] = 9999
            }
            val settings = repository.settings.first()
            assertNull(settings.postalCode)
            assertEquals(AppSettings.DEFAULT_STALE_AFTER_DAYS, settings.staleAfterDays)
        }
}
