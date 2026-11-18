package com.lechugones.cocina.data.catalogue

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.lechugones.cocina.provider.ProviderLocation
import kotlinx.coroutines.flow.first
import javax.inject.Inject

/**
 * Remembers the store or warehouse each provider resolved for the chosen postal code, so it is
 * asked once per postal code and not on every sync (docs/providers/README.md § Location).
 */
class ProviderLocationStore
    @Inject
    constructor(
        private val dataStore: DataStore<Preferences>,
    ) {
        suspend fun get(
            providerId: String,
            postalCode: String,
        ): ProviderLocation? {
            val stored = dataStore.data.first()[key(providerId)] ?: return null
            val (code, token) = stored.split(SEPARATOR, limit = 2).takeIf { it.size == 2 } ?: return null
            return if (code == postalCode && token.isNotBlank()) ProviderLocation(code, token) else null
        }

        suspend fun put(
            providerId: String,
            location: ProviderLocation,
        ) {
            dataStore.edit { it[key(providerId)] = location.postalCode + SEPARATOR + location.token }
        }

        private fun key(providerId: String) = stringPreferencesKey("provider_location_$providerId")

        private companion object {
            const val SEPARATOR = "|"
        }
    }
