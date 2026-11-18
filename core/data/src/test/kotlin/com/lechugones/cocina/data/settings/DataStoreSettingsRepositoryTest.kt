package com.lechugones.cocina.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.lechugones.cocina.domain.settings.AppSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Tests the mapping and sanitising done by [DataStoreSettingsRepository]. DataStore's own file
 * storage is replaced by an in-memory fake: its rename-over-existing-file step does not work on
 * Windows JVMs, where these tests also run (it does on Android).
 */
class DataStoreSettingsRepositoryTest {
    private class InMemoryPreferencesDataStore : DataStore<Preferences> {
        private val state = MutableStateFlow(emptyPreferences())
        private val mutex = Mutex()
        override val data: Flow<Preferences> = state

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
            mutex.withLock { transform(state.value).also { state.value = it } }
    }

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
