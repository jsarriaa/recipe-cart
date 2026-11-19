package com.lechugones.cocina.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lechugones.cocina.data.pantry.PantryRepository
import com.lechugones.cocina.data.settings.SettingsRepository
import com.lechugones.cocina.domain.pantry.UseSoon
import com.lechugones.cocina.provider.SupermarketProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

/** State of the app shell: the "Usar pronto" count badge on the Despensa tab (SPEC § 4.5). */
@HiltViewModel
class ShellViewModel
    @Inject
    constructor(
        pantry: PantryRepository,
        settings: SettingsRepository,
        clock: Clock,
        providers: Set<@JvmSuppressWildcards SupermarketProvider>,
    ) : ViewModel() {
        /** Supermarket display names by provider id, for labels on product rows. */
        val supermarketNames: Map<String, String> = providers.associate { it.id to it.displayName }

        private val zone = ZoneId.systemDefault()

        val useSoonCount: StateFlow<Int> =
            combine(pantry.observeLots(), settings.settings) { lots, s ->
                val today = LocalDate.now(clock.withZone(zone))
                lots.count { UseSoon.evaluate(it, today, zone, s.useSoonWarningDays).useSoon }
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), 0)

        private companion object {
            const val STOP_TIMEOUT_MILLIS = 5_000L
        }
    }
