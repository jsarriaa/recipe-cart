package com.lechugones.cocina.feature.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lechugones.cocina.R
import com.lechugones.cocina.data.backup.BackupInspection
import com.lechugones.cocina.domain.settings.AppSettings
import com.lechugones.cocina.ui.components.PostalCodeDialog
import com.lechugones.cocina.ui.components.ScreenScaffold
import com.lechugones.cocina.ui.format.Dates
import com.lechugones.cocina.util.ProcessRestarter
import java.time.Instant
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val backupError = stringResource(R.string.settings_backup_error)
    val notBackup = stringResource(R.string.settings_restore_not_backup)
    val newer = stringResource(R.string.settings_restore_newer)
    val corrupt = stringResource(R.string.settings_restore_corrupt)
    val restoreFailed = stringResource(R.string.settings_restore_failed)
    val resources = LocalResources.current

    val create =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
            uri?.let(viewModel::createBackup)
        }
    val open =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let(viewModel::inspect)
        }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is SettingsEvent.BackupCreated -> {
                    snackbar.showSnackbar(resources.getQuantityString(R.plurals.settings_backup_done, event.recipes, event.recipes))
                }

                SettingsEvent.BackupFailed -> {
                    snackbar.showSnackbar(backupError)
                }

                is SettingsEvent.RestoreRejected -> {
                    snackbar.showSnackbar(
                        when (event.reason) {
                            BackupInspection.Reason.NOT_A_BACKUP -> notBackup
                            BackupInspection.Reason.NEWER_VERSION -> newer
                            BackupInspection.Reason.CORRUPT -> corrupt
                        },
                    )
                }

                SettingsEvent.RestoreFailed -> {
                    snackbar.showSnackbar(restoreFailed)
                }

                SettingsEvent.RestartNow -> {
                    ProcessRestarter.restart(context)
                }
            }
        }
    }

    ScreenScaffold(title = stringResource(R.string.settings_title), onBack = onBack, snackbarHostState = snackbar) { padding ->
        Column(
            modifier =
                Modifier
                    .padding(padding)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (state.working) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(stringResource(R.string.settings_working), style = MaterialTheme.typography.bodySmall)
            }
            Text(stringResource(R.string.settings_supermarkets_title), style = MaterialTheme.typography.titleMedium)
            state.providers.forEach { (id, name) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(
                        selected = state.primaryProvider == id,
                        onClick = { viewModel.setPrimary(id) },
                        enabled = id in state.activeProviders,
                    )
                    Column(Modifier.weight(1f)) {
                        Text(name, style = MaterialTheme.typography.bodyLarge)
                        if (state.primaryProvider == id) {
                            Text(
                                stringResource(R.string.settings_provider_primary),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Switch(checked = id in state.activeProviders, onCheckedChange = { viewModel.setActive(id, it) })
                }
            }
            Text(stringResource(R.string.settings_providers_body), style = MaterialTheme.typography.bodySmall)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.settings_postal_code), style = MaterialTheme.typography.bodyLarge)
                    Text(
                        state.postalCode ?: stringResource(R.string.settings_postal_code_none),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = { viewModel.editPostalCode(true) }) { Text(stringResource(R.string.settings_postal_code_change)) }
            }
            Text(stringResource(R.string.postal_code_body), style = MaterialTheme.typography.bodySmall)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.catalogue_store_brand_only), style = MaterialTheme.typography.bodyLarge)
                    Text(
                        stringResource(R.string.settings_store_brand_only_body),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = state.storeBrandOnly, onCheckedChange = viewModel::setStoreBrandOnly)
            }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Text(stringResource(R.string.settings_pantry_title), style = MaterialTheme.typography.titleMedium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.settings_add_staples), style = MaterialTheme.typography.bodyLarge)
                    Text(
                        stringResource(R.string.settings_add_staples_body),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = state.addMissingStaples, onCheckedChange = viewModel::setAddMissingStaples)
            }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Text(stringResource(R.string.settings_backup_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.settings_backup_body), style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { create.launch(backupFileName()) }, enabled = !state.working) {
                    Text(stringResource(R.string.settings_backup_create))
                }
                OutlinedButton(
                    onClick = { open.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")) },
                    enabled = !state.working,
                ) { Text(stringResource(R.string.settings_backup_restore)) }
            }
            state.lastAutomaticBackup?.let { taken ->
                Text(stringResource(R.string.settings_backup_undo_body, Dates.dateTime(taken)), style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = viewModel::undoLastRestore, enabled = !state.working) {
                    Text(stringResource(R.string.settings_backup_undo))
                }
            }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Text(stringResource(R.string.settings_about_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.settings_privacy), style = MaterialTheme.typography.bodyMedium)
            Text(
                stringResource(R.string.settings_version, state.versionName),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (state.editingPostalCode) {
        PostalCodeDialog(current = state.postalCode, onSave = viewModel::savePostalCode, onDismiss = { viewModel.editPostalCode(false) })
    }

    state.pendingRestore?.let { staged ->
        val manifest = staged.manifest
        val created = runCatching { Dates.dateTime(Instant.parse(manifest.createdAt)) }.getOrDefault(manifest.createdAt)
        AlertDialog(
            onDismissRequest = viewModel::cancelRestore,
            title = { Text(stringResource(R.string.settings_restore_confirm_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.settings_restore_confirm_body,
                        created,
                        pluralStringResource(R.plurals.backup_recipes, manifest.recipes, manifest.recipes),
                        pluralStringResource(R.plurals.backup_photos, manifest.images, manifest.images),
                    ),
                )
            },
            confirmButton = { TextButton(onClick = viewModel::confirmRestore) { Text(stringResource(R.string.settings_restore_action)) } },
            dismissButton = { TextButton(onClick = viewModel::cancelRestore) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

/** `cocina-backup-YYYYMMDD-HHmm.zip` (SPEC § 4.7). */
private fun backupFileName(): String = "cocina-backup-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmm")) + ".zip"
