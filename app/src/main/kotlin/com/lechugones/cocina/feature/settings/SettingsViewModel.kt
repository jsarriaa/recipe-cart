package com.lechugones.cocina.feature.settings

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lechugones.cocina.BuildConfig
import com.lechugones.cocina.data.backup.AppInfo
import com.lechugones.cocina.data.backup.BackupInspection
import com.lechugones.cocina.data.backup.BackupManager
import com.lechugones.cocina.data.backup.StagedBackup
import com.lechugones.cocina.data.settings.SettingsRepository
import com.lechugones.cocina.domain.community.CommunityPublication
import com.lechugones.cocina.domain.settings.AppSettings
import com.lechugones.cocina.feature.settings.update.AppUpdates
import com.lechugones.cocina.feature.settings.update.UpdateCheck
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
import java.io.File
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
    val addMissingStaples: Boolean = true,
    /** Installed supermarkets: id to display name. */
    val providers: List<Pair<String, String>> = emptyList(),
    val activeProviders: Set<String> = emptySet(),
    val primaryProvider: String = AppSettings.DEFAULT_PROVIDER,
    val update: UpdateUi = UpdateUi.Idle,
    val publisherName: String? = null,
    val editingPostalCode: Boolean = false,
)

/** "Buscar actualizaciones" (owner request after 1.0.0). */
sealed interface UpdateUi {
    data object Idle : UpdateUi

    data object Checking : UpdateUi

    data object UpToDate : UpdateUi

    data class Available(
        val version: String,
        val sizeBytes: Long,
    ) : UpdateUi

    data class NoApk(
        val version: String,
    ) : UpdateUi

    data object Downloading : UpdateUi

    data object Failed : UpdateUi
}

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
        private val updates: AppUpdates,
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
                            addMissingStaples = s.addMissingStaplesToList,
                            activeProviders = s.activeProviders,
                            primaryProvider = s.primaryProvider,
                            publisherName = s.publisherName,
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

        private var available: UpdateCheck.Available? = null

        val canInstallUpdates: Boolean = updates.canInstall

        fun checkUpdates() {
            if (state.value.update == UpdateUi.Checking || state.value.update == UpdateUi.Downloading) return
            state.update { it.copy(update = UpdateUi.Checking) }
            viewModelScope.launch {
                val result = updates.check(BuildConfig.VERSION_NAME)
                available = result as? UpdateCheck.Available
                state.update {
                    it.copy(
                        update =
                            when (result) {
                                UpdateCheck.UpToDate -> UpdateUi.UpToDate
                                is UpdateCheck.Available -> UpdateUi.Available(result.version, result.apk.size)
                                is UpdateCheck.NoApk -> UpdateUi.NoApk(result.version)
                                UpdateCheck.Failed -> UpdateUi.Failed
                            },
                    )
                }
            }
        }

        /** Downloads the new APK and hands it to Android's installer, which asks the user to confirm. */
        fun downloadAndInstall() {
            val update = available ?: return
            state.update { it.copy(update = UpdateUi.Downloading) }
            viewModelScope.launch {
                val ok = updates.downloadAndInstall(update)
                state.update {
                    it.copy(update = if (ok) UpdateUi.Available(update.version, update.apk.size) else UpdateUi.Failed)
                }
            }
        }

        /** False when [input] is not a usable name (empty or too long). */
        fun setPublisherName(input: String): Boolean {
            val name = CommunityPublication.cleanName(input) ?: return false
            viewModelScope.launch { settings.update { it.copy(publisherName = name) } }
            return true
        }

        fun setAddMissingStaples(enabled: Boolean) {
            viewModelScope.launch { settings.update { it.copy(addMissingStaplesToList = enabled) } }
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
