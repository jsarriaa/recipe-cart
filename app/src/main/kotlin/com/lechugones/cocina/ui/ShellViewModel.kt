package com.lechugones.cocina.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lechugones.cocina.BuildConfig
import com.lechugones.cocina.feature.settings.update.AppUpdates
import com.lechugones.cocina.feature.settings.update.UpdateCheck
import com.lechugones.cocina.provider.SupermarketProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** The pop-up shown when the app opens and GitHub has a newer release. */
data class LaunchUpdate(
    val update: UpdateCheck.Available,
    val canInstall: Boolean,
    val downloading: Boolean = false,
    val failed: Boolean = false,
)

/** State of the app shell. */
@HiltViewModel
class ShellViewModel
    @Inject
    constructor(
        providers: Set<@JvmSuppressWildcards SupermarketProvider>,
        private val updates: AppUpdates,
    ) : ViewModel() {
        /** Supermarket display names by provider id, for labels on product rows. */
        val supermarketNames: Map<String, String> = providers.associate { it.id to it.displayName }

        private val launchUpdateState = MutableStateFlow<LaunchUpdate?>(null)
        val launchUpdate: StateFlow<LaunchUpdate?> = launchUpdateState.asStateFlow()

        init {
            // Owner request (1.2.0): one quiet check each time the app opens. A failure (offline,
            // GitHub down) shows nothing.
            viewModelScope.launch {
                val result = updates.check(BuildConfig.VERSION_NAME)
                if (result is UpdateCheck.Available) launchUpdateState.value = LaunchUpdate(result, updates.canInstall)
            }
        }

        fun dismissUpdate() {
            launchUpdateState.value = null
        }

        fun installUpdate() {
            val current = launchUpdateState.value ?: return
            if (current.downloading) return
            launchUpdateState.update { it?.copy(downloading = true, failed = false) }
            viewModelScope.launch {
                val ok = updates.downloadAndInstall(current.update)
                launchUpdateState.value = if (ok) null else current.copy(downloading = false, failed = true)
            }
        }
    }
