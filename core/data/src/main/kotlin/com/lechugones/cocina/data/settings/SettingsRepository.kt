package com.lechugones.cocina.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.lechugones.cocina.domain.settings.AppSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException
import javax.inject.Inject

interface SettingsRepository {
    val settings: Flow<AppSettings>

    suspend fun update(transform: (AppSettings) -> AppSettings)
}

/** Stores [AppSettings] in the `settings` Preferences DataStore. Values are sanitised both ways. */
class DataStoreSettingsRepository
    @Inject
    constructor(
        private val dataStore: DataStore<Preferences>,
    ) : SettingsRepository {
        override val settings: Flow<AppSettings> =
            dataStore.data
                .catch { error -> if (error is IOException) emit(emptyPreferences()) else throw error }
                .map { it.toSettings() }

        override suspend fun update(transform: (AppSettings) -> AppSettings) {
            dataStore.edit { prefs -> prefs.write(transform(prefs.toSettings()).sanitized()) }
        }

        private object Keys {
            val ACTIVE_PROVIDERS = stringSetPreferencesKey("active_providers")
            val PRIMARY_PROVIDER = stringPreferencesKey("primary_provider")
            val POSTAL_CODE = stringPreferencesKey("postal_code")
            val STORE_BRAND_ONLY = booleanPreferencesKey("store_brand_only")
            val CLUB_DIA_PRICES = booleanPreferencesKey("club_dia_prices")
            val INCLUDE_STAPLES = booleanPreferencesKey("include_staples_in_cost")
            val INCLUDE_OPTIONALS = booleanPreferencesKey("include_optionals_in_cost")
            val STALE_AFTER_DAYS = intPreferencesKey("stale_after_days")
            val ADD_MISSING_STAPLES = booleanPreferencesKey("add_missing_staples_to_list")
            val SHOPPING_CHEAPEST = booleanPreferencesKey("shopping_cheapest")
            val PUBLISHER_NAME = stringPreferencesKey("publisher_name")
        }

        private fun Preferences.toSettings(): AppSettings {
            val defaults = AppSettings()
            return AppSettings(
                activeProviders = this[Keys.ACTIVE_PROVIDERS] ?: defaults.activeProviders,
                primaryProvider = this[Keys.PRIMARY_PROVIDER] ?: defaults.primaryProvider,
                postalCode = this[Keys.POSTAL_CODE],
                storeBrandOnly = this[Keys.STORE_BRAND_ONLY] ?: defaults.storeBrandOnly,
                clubDiaPrices = this[Keys.CLUB_DIA_PRICES] ?: defaults.clubDiaPrices,
                includeStaplesInCost = this[Keys.INCLUDE_STAPLES] ?: defaults.includeStaplesInCost,
                includeOptionalsInCost = this[Keys.INCLUDE_OPTIONALS] ?: defaults.includeOptionalsInCost,
                staleAfterDays = this[Keys.STALE_AFTER_DAYS] ?: defaults.staleAfterDays,
                addMissingStaplesToList = this[Keys.ADD_MISSING_STAPLES] ?: defaults.addMissingStaplesToList,
                shoppingCheapest = this[Keys.SHOPPING_CHEAPEST] ?: defaults.shoppingCheapest,
                publisherName = this[Keys.PUBLISHER_NAME],
            ).sanitized()
        }

        private fun MutablePreferences.write(settings: AppSettings) {
            this[Keys.ACTIVE_PROVIDERS] = settings.activeProviders
            this[Keys.PRIMARY_PROVIDER] = settings.primaryProvider
            val postalCode = settings.postalCode
            if (postalCode == null) remove(Keys.POSTAL_CODE) else this[Keys.POSTAL_CODE] = postalCode
            this[Keys.STORE_BRAND_ONLY] = settings.storeBrandOnly
            this[Keys.CLUB_DIA_PRICES] = settings.clubDiaPrices
            this[Keys.INCLUDE_STAPLES] = settings.includeStaplesInCost
            this[Keys.INCLUDE_OPTIONALS] = settings.includeOptionalsInCost
            this[Keys.STALE_AFTER_DAYS] = settings.staleAfterDays
            this[Keys.ADD_MISSING_STAPLES] = settings.addMissingStaplesToList
            this[Keys.SHOPPING_CHEAPEST] = settings.shoppingCheapest
            val publisher = settings.publisherName
            if (publisher == null) remove(Keys.PUBLISHER_NAME) else this[Keys.PUBLISHER_NAME] = publisher
        }
    }
