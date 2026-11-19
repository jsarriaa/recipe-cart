package com.lechugones.cocina.feature.settings

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lechugones.cocina.data.backup.AppInfo
import com.lechugones.cocina.data.backup.BackupInspection
import com.lechugones.cocina.data.backup.BackupManager
import com.lechugones.cocina.data.backup.StagedBackup
import com.lechugones.cocina.data.settings.SettingsRepository
import com.lechugones.cocina.domain.settings.AppSettings
import com.lechugones.cocina.provider.SupermarketProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException
import java.io.InputStream
import java.time.Instant
import javax.inject.Inject

data class SettingsUiState(
    val versionName: String,
    val working: Boolean = false,
    /** A validated backup waiting for the user's confirmation. */
    val pendingRestore: StagedBackup? = null,
    /** When the newest automatic pre-restore backup was taken, if any. */
    val lastAutomaticBackup: Instant? = null,
    val postalCode: String? = null,
    val storeBrandOnly: Boolean = false,
    val useSoonDays: Int = AppSettings.DEFAULT_USE_SOON_DAYS,
    val addMissingStaples: Boolean = true,
    /** Installed supermarkets: id to display name. */
    val providers: List<Pair<String, String>> = emptyList(),
    val activeProviders: Set<String> = emptySet(),
    val primaryProvider: String = AppSettings.DEFAULT_PROVIDER,
    val editingPostalCode: Boolean = false,
)

sealed interface SettingsEvent {
    data class BackupCreated(
        val recipes: Int,
    ) : SettingsEvent

    data object BackupFailed : SettingsEvent

    data class RestoreRejected(
        val reason: BackupInspection.Reason,
    ) : SettingsEvent

    data object RestoreFailed : SettingsEvent

    /** Data was replaced: the app process must restart now. */
    data object RestartNow : SettingsEvent
}

@HiltViewModel
class SettingsViewModel
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val backups: BackupManager,
        private val settings: SettingsRepository,
        installed: Set<@JvmSuppressWildcards SupermarketProvider>,
        appInfo: AppInfo,
    ) : ViewModel() {
        private val state =
            MutableStateFlow(
                SettingsUiState(
                    versionName = appInfo.versionName,
                    providers = installed.sortedBy { it.displayName }.map { it.id to it.displayName },
                ),
            )
        val uiState: StateFlow<SettingsUiState> = state.asStateFlow()
        private val eventChannel = Channel<SettingsEvent>(Channel.BUFFERED)
        val events: Flow<SettingsEvent> = eventChannel.receiveAsFlow()

        init {
            refreshAutomatic()
            viewModelScope.launch {
                settings.settings.collect { s ->
                    state.update {
                        it.copy(
                            postalCode = s.postalCode,
                            storeBrandOnly = s.storeBrandOnly,
                            useSoonDays = s.useSoonWarningDays,
                            addMissingStaples = s.addMissingStaplesToList,
                            activeProviders = s.activeProviders,
                            primaryProvider = s.primaryProvider,
                        )
                    }
                }
            }
        }

        fun editPostalCode(open: Boolean) {
            state.update { it.copy(editingPostalCode = open) }
        }

        fun savePostalCode(code: String) {
            if (!AppSettings.isValidPostalCode(code)) return
            state.update { it.copy(editingPostalCode = false) }
            viewModelScope.launch { settings.update { it.copy(postalCode = code) } }
        }

        /** Switches a supermarket on or off; at least one stays on (AppSettings.sanitized). */
        fun setActive(
            id: String,
            active: Boolean,
        ) {
            viewModelScope.launch {
                settings.update {
                    val ids = if (active) it.activeProviders + id else it.activeProviders - id
                    if (ids.isEmpty()) it else it.copy(activeProviders = ids)
                }
            }
        }

        fun setPrimary(id: String) {
            viewModelScope.launch { settings.update { it.copy(primaryProvider = id) } }
        }

        fun setAddMissingStaples(enabled: Boolean) {
            viewModelScope.launch { settings.update { it.copy(addMissingStaplesToList = enabled) } }
        }

        fun setUseSoonDays(days: Int) {
            val value = days.coerceIn(AppSettings.USE_SOON_RANGE)
            viewModelScope.launch { settings.update { it.copy(useSoonWarningDays = value) } }
        }

        fun setStoreBrandOnly(enabled: Boolean) {
            viewModelScope.launch { settings.update { it.copy(storeBrandOnly = enabled) } }
        }

        fun createBackup(target: Uri) =
            work {
                val manifest =
                    try {
                        context.contentResolver.openOutputStream(target)?.use { backups.createBackup(it) }
                    } catch (e: IOException) {
                        null
                    } catch (e: SecurityException) {
                        null
                    }
                eventChannel.send(if (manifest == null) SettingsEvent.BackupFailed else SettingsEvent.BackupCreated(manifest.recipes))
            }

        /** Validates a backup the user picked; on success asks for confirmation. */
        fun inspect(source: Uri) =
            work {
                val stream =
                    try {
                        context.contentResolver.openInputStream(source)
                    } catch (e: IOException) {
                        null
                    } catch (e: SecurityException) {
                        null
                    }
                inspectStream(stream)
            }

        /** Offers to go back to the data that existed before the last restore. */
        fun undoLastRestore() =
            work {
                inspectStream(backups.automaticBackups().firstOrNull()?.inputStream())
            }

        fun cancelRestore() {
            state.update { it.copy(pendingRestore = null) }
        }

        fun confirmRestore() {
            val staged = state.value.pendingRestore ?: return
            state.update { it.copy(pendingRestore = null) }
            work {
                try {
                    backups.restore(staged)
                    eventChannel.send(SettingsEvent.RestartNow)
                } catch (e: IOException) {
                    eventChannel.send(SettingsEvent.RestoreFailed)
                }
            }
        }

        private suspend fun inspectStream(stream: InputStream?) {
            if (stream == null) {
                eventChannel.send(SettingsEvent.RestoreRejected(BackupInspection.Reason.CORRUPT))
                return
            }
            when (val inspection = stream.use { backups.inspect(it) }) {
                is BackupInspection.Ready -> state.update { it.copy(pendingRestore = inspection.staged) }
                is BackupInspection.Invalid -> eventChannel.send(SettingsEvent.RestoreRejected(inspection.reason))
            }
        }

        private fun refreshAutomatic() {
            val newest = backups.automaticBackups().firstOrNull()
            state.update { it.copy(lastAutomaticBackup = newest?.let { file -> Instant.ofEpochMilli(file.lastModified()) }) }
        }

        private fun work(block: suspend () -> Unit) {
            if (state.value.working) return
            state.update { it.copy(working = true) }
            viewModelScope.launch {
                try {
                    block()
                } finally {
                    state.update { it.copy(working = false) }
                    refreshAutomatic()
                }
            }
        }
    }
