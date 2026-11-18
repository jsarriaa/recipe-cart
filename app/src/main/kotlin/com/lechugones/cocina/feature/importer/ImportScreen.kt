package com.lechugones.cocina.feature.importer

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lechugones.cocina.R
import com.lechugones.cocina.domain.recipepack.DuplicateChoice
import com.lechugones.cocina.domain.recipepack.IssueCode
import com.lechugones.cocina.ui.ImportSource
import com.lechugones.cocina.ui.components.RecipePhoto
import com.lechugones.cocina.ui.components.ScreenScaffold
import com.lechugones.cocina.ui.format.issueText

/** MIME types offered in the file picker; many file managers label .md files as octet-stream. */
private val PICKER_TYPES =
    arrayOf("text/markdown", "text/x-markdown", "text/plain", "application/zip", "application/x-zip-compressed", "application/octet-stream")

@Composable
fun ImportScreen(
    onBack: () -> Unit,
    onDone: () -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: ImportViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val pick =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (uris.isNotEmpty()) viewModel.load(ImportSource.Files(uris))
        }

    val preview = state as? ImportUiState.Preview
    ScreenScaffold(
        title = stringResource(R.string.import_title),
        onBack = onBack,
        bottomBar = {
            if (preview != null) {
                val count = preview.rows.count { it.willImport }
                Button(
                    onClick = viewModel::import,
                    enabled = count > 0,
                    modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp),
                ) { Text(pluralStringResource(R.plurals.import_button, count, count)) }
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (val current = state) {
                ImportUiState.Idle -> {
                    Message(stringResource(R.string.import_idle_body)) { PickButton { pick.launch(PICKER_TYPES) } }
                }

                ImportUiState.Loading -> {
                    Progress(stringResource(R.string.import_loading))
                }

                ImportUiState.Saving -> {
                    Progress(stringResource(R.string.import_saving))
                }

                ImportUiState.ReadFailed -> {
                    Message(stringResource(R.string.import_read_error)) { PickButton { pick.launch(PICKER_TYPES) } }
                }

                ImportUiState.IsBackup -> {
                    Message(stringResource(R.string.import_backup_title), stringResource(R.string.import_backup_body)) {
                        Button(onClick = onOpenSettings) { Text(stringResource(R.string.import_go_settings)) }
                    }
                }

                is ImportUiState.Rejected -> {
                    Message(stringResource(R.string.import_rejected_title), current.issues.map { issueText(it) }.joinToString("\n")) {
                        PickButton { pick.launch(PICKER_TYPES) }
                    }
                }

                is ImportUiState.Done -> {
                    val imported = pluralStringResource(R.plurals.import_done_imported, current.imported, current.imported)
                    val skipped =
                        if (current.skipped >
                            0
                        ) {
                            ", " + pluralStringResource(R.plurals.import_done_skipped, current.skipped, current.skipped)
                        } else {
                            ""
                        }
                    Message(stringResource(R.string.import_done_title), imported + skipped) {
                        Button(onClick = onDone) { Text(stringResource(R.string.import_done_view)) }
                    }
                }

                is ImportUiState.Preview -> {
                    PreviewList(current, viewModel)
                }
            }
        }
    }

    preview?.confirmReplace?.let { index ->
        val title =
            preview.rows[index]
                .candidate.duplicateOf
                ?.title
                .orEmpty()
        AlertDialog(
            onDismissRequest = { viewModel.confirmReplace(false) },
            title = { Text(stringResource(R.string.import_replace_confirm_title)) },
            text = { Text(stringResource(R.string.import_replace_confirm_body, title)) },
            confirmButton = {
                TextButton(
                    onClick = { viewModel.confirmReplace(true) },
                ) { Text(stringResource(R.string.import_choice_replace)) }
            },
            dismissButton = { TextButton(onClick = { viewModel.confirmReplace(false) }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@Composable
private fun PreviewList(
    preview: ImportUiState.Preview,
    viewModel: ImportViewModel,
) {
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (preview.packIssues.isNotEmpty()) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(stringResource(R.string.import_pack_notes), style = MaterialTheme.typography.titleSmall)
                        preview.packIssues.forEach { Text(issueText(it), style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
        }
        itemsIndexed(preview.rows) { index, row ->
            CandidateCard(
                row = row,
                image =
                    row.candidate.draft
                        ?.image
                        ?.let { preview.images[it] },
                onToggle = { viewModel.toggle(index) },
                onChoose = { viewModel.choose(index, it) },
            )
        }
    }
}

@Composable
private fun CandidateCard(
    row: CandidateRow,
    image: ByteArray?,
    onToggle: () -> Unit,
    onChoose: (DuplicateChoice) -> Unit,
) {
    val candidate = row.candidate
    val draft = candidate.draft
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = row.selected && candidate.canImport, onCheckedChange = { onToggle() }, enabled = candidate.canImport)
                RecipePhoto(
                    path = null,
                    bytes = image,
                    contentDescription = null,
                    modifier = Modifier.size(56.dp).clip(RoundedCornerShape(8.dp)),
                    placeholderIconSize = 24.dp,
                )
                Column(Modifier.padding(start = 12.dp).weight(1f)) {
                    Text(draft?.title ?: candidate.sourceName, style = MaterialTheme.typography.titleMedium)
                    if (draft != null) {
                        val servings = pluralStringResource(R.plurals.recipe_servings, draft.servings, draft.servings)
                        val count = draft.ingredients.size
                        val ingredients = pluralStringResource(R.plurals.recipe_ingredient_count, count, count)
                        Text("$servings · $ingredients", style = MaterialTheme.typography.bodySmall)
                    } else {
                        Text(
                            stringResource(R.string.import_cannot),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
            candidate.issues.filterNot { it.code == IssueCode.W10 }.forEach { issue ->
                Text(
                    issueText(issue),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (issue.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            candidate.duplicateOf?.let { duplicate ->
                Text(stringResource(R.string.import_duplicate, duplicate.title), style = MaterialTheme.typography.bodyMedium)
                if (candidate.replaceNeedsConfirmation) {
                    Text(stringResource(R.string.import_edited_hint), style = MaterialTheme.typography.bodySmall)
                }
                DuplicateChoices(row.choice, onChoose)
            }
        }
    }
}

@Composable
private fun DuplicateChoices(
    selected: DuplicateChoice?,
    onChoose: (DuplicateChoice) -> Unit,
) {
    val options =
        listOf(
            DuplicateChoice.REPLACE to R.string.import_choice_replace,
            DuplicateChoice.KEEP_BOTH to R.string.import_choice_copy,
            DuplicateChoice.SKIP to R.string.import_choice_keep,
        )
    // A radio list rather than segmented buttons: long labels like "Mantener la mía" fit on one line.
    Column(Modifier.selectableGroup()) {
        options.forEach { (choice, label) ->
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .selectable(selected = selected == choice, role = Role.RadioButton, onClick = { onChoose(choice) })
                        .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = selected == choice, onClick = null)
                Text(stringResource(label), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 8.dp))
            }
        }
    }
}

@Composable
private fun PickButton(onClick: () -> Unit) {
    Button(onClick = onClick) { Text(stringResource(R.string.import_pick)) }
}

@Composable
private fun Progress(text: String) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        CircularProgressIndicator()
        Text(text, modifier = Modifier.padding(top = 16.dp))
    }
}

@Composable
private fun Message(
    title: String,
    body: String? = null,
    action: @Composable () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        body?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        action()
    }
}
