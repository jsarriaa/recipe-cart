package com.lechugones.cocina.feature.recipes.editor

import android.content.Context
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lechugones.cocina.R
import com.lechugones.cocina.domain.quantity.QuantityParseResult
import com.lechugones.cocina.domain.quantity.QuantityParser
import com.lechugones.cocina.domain.recipepack.PackIssue
import com.lechugones.cocina.ui.components.MarkdownText
import com.lechugones.cocina.ui.components.RecipePhoto
import com.lechugones.cocina.ui.components.ScreenScaffold
import com.lechugones.cocina.ui.format.issueMessage
import com.lechugones.cocina.ui.format.issueText
import com.lechugones.cocina.ui.format.quantityText
import com.lechugones.cocina.ui.format.unitLabel
import java.io.File

@Composable
fun RecipeEditorScreen(
    onClose: () -> Unit,
    onSavedNew: (String) -> Unit,
    viewModel: RecipeEditorViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    var confirmDiscard by remember { mutableStateOf(false) }
    var preview by rememberSaveable { mutableStateOf(false) }
    val fixErrors = stringResource(R.string.editor_fix_errors)
    val photoError = stringResource(R.string.editor_photo_error)
    val saveError = stringResource(R.string.editor_save_error)

    val pickPhoto =
        rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let(viewModel::setPhoto) }
    var cameraUri by rememberSaveable { mutableStateOf<Uri?>(null) }
    val takePhoto =
        rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { taken ->
            val uri = cameraUri
            if (taken && uri != null) viewModel.setPhoto(uri)
        }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is RecipeEditorEvent.Saved -> if (event.isNew) onSavedNew(event.id) else onClose()
                RecipeEditorEvent.Discarded -> onClose()
                RecipeEditorEvent.InvalidForm -> snackbar.showSnackbar(fixErrors)
                RecipeEditorEvent.PhotoFailed -> snackbar.showSnackbar(photoError)
                RecipeEditorEvent.SaveFailed -> snackbar.showSnackbar(saveError)
            }
        }
    }

    val requestClose = { if (state.dirty) confirmDiscard = true else viewModel.discard() }
    BackHandler(enabled = state.sheet == null) { requestClose() }

    ScreenScaffold(
        title = stringResource(if (state.isNew) R.string.editor_new_title else R.string.editor_edit_title),
        onBack = requestClose,
        snackbarHostState = snackbar,
        actions = {
            TextButton(onClick = viewModel::save, enabled = !state.loading && !state.saving) {
                Text(stringResource(R.string.action_save))
            }
        },
    ) { padding ->
        if (state.loading) {
            Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@ScreenScaffold
        }
        val form = state.form
        val errors = state.errors
        LazyColumn(
            modifier = Modifier.fillMaxSize().imePadding(),
            contentPadding =
                PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = padding.calculateTopPadding() + 8.dp,
                    bottom =
                        padding.calculateBottomPadding() + 24.dp,
                ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                RecipePhoto(
                    path = form.photo,
                    contentDescription = stringResource(R.string.recipe_photo_description),
                    modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                    OutlinedButton(onClick = {
                        pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    }) {
                        Icon(Icons.Outlined.PhotoLibrary, contentDescription = null)
                        Text(stringResource(R.string.editor_photo_gallery), modifier = Modifier.padding(start = 8.dp))
                    }
                    OutlinedButton(onClick = {
                        val uri = newCameraUri(context)
                        cameraUri = uri
                        takePhoto.launch(uri)
                    }) {
                        Icon(Icons.Outlined.CameraAlt, contentDescription = null)
                        Text(stringResource(R.string.editor_photo_camera), modifier = Modifier.padding(start = 8.dp))
                    }
                    if (form.photo != null) {
                        IconButton(onClick = viewModel::removePhoto) {
                            Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.editor_photo_remove))
                        }
                    }
                }
            }
            item {
                Field(R.string.editor_field_title, form.title, errors.at("title")) { v -> viewModel.updateForm { it.copy(title = v) } }
            }
            item {
                Field(R.string.editor_field_description, form.description, errors.at("description"), singleLine = false) { v ->
                    viewModel.updateForm { it.copy(description = v) }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Field(
                        R.string.editor_field_servings,
                        form.servings,
                        errors.at("servings"),
                        number = true,
                        modifier = Modifier.weight(1f),
                    ) { v ->
                        viewModel.updateForm { it.copy(servings = v) }
                    }
                    Field(
                        R.string.editor_field_prep,
                        form.prep,
                        errors.at("prep_minutes"),
                        number = true,
                        modifier = Modifier.weight(1f),
                    ) { v ->
                        viewModel.updateForm { it.copy(prep = v) }
                    }
                    Field(
                        R.string.editor_field_cook,
                        form.cook,
                        errors.at("cook_minutes"),
                        number = true,
                        modifier = Modifier.weight(1f),
                    ) { v ->
                        viewModel.updateForm { it.copy(cook = v) }
                    }
                }
            }
            item {
                Field(
                    R.string.editor_field_tags,
                    form.tags,
                    errors.firstOrNull { it.path.startsWith("tags") },
                    help = stringResource(R.string.editor_field_tags_help),
                ) { v -> viewModel.updateForm { it.copy(tags = v) } }
            }
            item {
                Field(R.string.editor_field_source, form.source, errors.at("source")) { v -> viewModel.updateForm { it.copy(source = v) } }
            }
            item { Text(stringResource(R.string.editor_ingredients), style = MaterialTheme.typography.titleMedium) }
            itemsIndexed(form.ingredients, key = { _, row -> row.key }) { index, row ->
                IngredientRowItem(
                    row = row,
                    hasError = errors.any { it.ingredientIndex == index },
                    canMoveUp = index > 0,
                    canMoveDown = index < form.ingredients.lastIndex,
                    onClick = { viewModel.openIngredient(index) },
                    onMove = { delta -> viewModel.moveIngredient(index, delta) },
                )
            }
            item {
                errors.at("ingredients")?.let { ErrorText(stringResource(R.string.editor_no_ingredients)) }
                errors.filter { it.ingredientIndex != null }.forEach { ErrorText(issueText(it)) }
                OutlinedButton(onClick = viewModel::openNewIngredient) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                    Text(stringResource(R.string.editor_add_ingredient), modifier = Modifier.padding(start = 8.dp))
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.editor_field_body),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    FilterChip(selected = !preview, onClick = { preview = false }, label = { Text(stringResource(R.string.editor_write)) })
                    FilterChip(selected = preview, onClick = { preview = true }, label = { Text(stringResource(R.string.editor_preview)) })
                }
            }
            item {
                if (preview) {
                    MarkdownText(form.body)
                } else {
                    OutlinedTextField(
                        value = form.body,
                        onValueChange = { v -> viewModel.updateForm { it.copy(body = v) } },
                        minLines = 8,
                        isError = errors.at("body") != null,
                        supportingText = errors.at("body")?.let { { Text(issueMessage(it)) } },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }

    state.sheet?.let { sheet ->
        IngredientSheet(
            sheet = sheet,
            onChange = viewModel::updateSheet,
            onApply = viewModel::applySheet,
            onDelete = viewModel::deleteSheetIngredient,
            onDismiss = viewModel::closeSheet,
        )
    }

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text(stringResource(R.string.editor_discard_title)) },
            text = { Text(stringResource(R.string.editor_discard_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDiscard = false
                    viewModel.discard()
                }) { Text(stringResource(R.string.action_discard)) }
            },
            dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text(stringResource(R.string.action_keep_editing)) } },
        )
    }
}

private fun List<PackIssue>.at(path: String): PackIssue? = firstOrNull { it.path == path }

/** A content Uri for the camera app to write into (cache/camera, shared through FileProvider). */
private fun newCameraUri(context: Context): Uri {
    val file = File(File(context.cacheDir, "camera").apply { mkdirs() }, "capture-${System.currentTimeMillis()}.jpg")
    return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
}

@Composable
internal fun Field(
    label: Int,
    value: String,
    error: PackIssue?,
    modifier: Modifier = Modifier,
    help: String? = null,
    singleLine: Boolean = true,
    number: Boolean = false,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(stringResource(label)) },
        singleLine = singleLine,
        isError = error != null,
        supportingText =
            when {
                error != null -> {
                    { Text(issueMessage(error)) }
                }

                help != null -> {
                    { Text(help) }
                }

                else -> {
                    null
                }
            },
        keyboardOptions = if (number) KeyboardOptions(keyboardType = KeyboardType.Number) else KeyboardOptions.Default,
        modifier = modifier.fillMaxWidth(),
    )
}

@Composable
private fun ErrorText(text: String) {
    Text(
        text,
        color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(bottom = 4.dp),
    )
}

@Composable
private fun IngredientRowItem(
    row: IngredientForm,
    hasError: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onClick: () -> Unit,
    onMove: (Int) -> Unit,
) {
    val parsed = (QuantityParser.parse(row.quantity) as? QuantityParseResult.Valid)?.amount
    val quantity =
        when {
            parsed != null -> quantityText(parsed.toBigDecimal(), row.unit)
            row.quantity.isBlank() -> quantityText(null, row.unit)
            else -> "${row.quantity} ${unitLabel(row.unit, plural = true)}"
        }
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        headlineContent = {
            Text(
                row.name,
                color = if (hasError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            )
        },
        supportingContent = { Text(listOfNotNull(quantity, row.note.ifBlank { null }).joinToString(" · ")) },
        trailingContent = {
            Column {
                IconButton(onClick = { onMove(-1) }, enabled = canMoveUp) {
                    Icon(Icons.Filled.KeyboardArrowUp, contentDescription = stringResource(R.string.action_move_up))
                }
                IconButton(onClick = { onMove(1) }, enabled = canMoveDown) {
                    Icon(Icons.Filled.KeyboardArrowDown, contentDescription = stringResource(R.string.action_move_down))
                }
            }
        },
    )
}
