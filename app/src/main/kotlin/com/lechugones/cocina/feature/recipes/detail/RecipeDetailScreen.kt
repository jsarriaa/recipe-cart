package com.lechugones.cocina.feature.recipes.detail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SuggestionChip
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lechugones.cocina.R
import com.lechugones.cocina.domain.recipe.Ingredient
import com.lechugones.cocina.domain.recipe.Recipe
import com.lechugones.cocina.domain.recipe.RecipeOrigin
import com.lechugones.cocina.domain.recipe.ServingsScaler
import com.lechugones.cocina.feature.export.ExportDialog
import com.lechugones.cocina.feature.export.ExportFile
import com.lechugones.cocina.ui.components.MarkdownText
import com.lechugones.cocina.ui.components.RecipePhoto
import com.lechugones.cocina.ui.components.ScreenScaffold
import com.lechugones.cocina.ui.format.Dates
import com.lechugones.cocina.ui.format.quantityText

@Composable
fun RecipeDetailScreen(
    onBack: () -> Unit,
    onEdit: (String) -> Unit,
    onOpenRecipe: (String) -> Unit,
    viewModel: RecipeDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var menuOpen by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var export by remember { mutableStateOf<ExportFile?>(null) }
    val exportError = stringResource(R.string.export_error)
    val copyTitle = stringResource(R.string.recipe_copy_title, "%s")

    // Runs again when the screen comes back from the editor, so edits show up.
    LaunchedEffect(Unit) { viewModel.load() }
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                RecipeDetailEvent.Deleted -> onBack()
                is RecipeDetailEvent.Duplicated -> onOpenRecipe(event.id)
                is RecipeDetailEvent.Exported -> export = event.file
                RecipeDetailEvent.ExportFailed -> snackbar.showSnackbar(exportError)
            }
        }
    }

    val loaded = state as? RecipeDetailUiState.Loaded
    ScreenScaffold(
        title = loaded?.recipe?.title.orEmpty(),
        onBack = onBack,
        snackbarHostState = snackbar,
        actions = {
            if (loaded != null) {
                IconButton(onClick = { onEdit(loaded.recipe.id) }) {
                    Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.action_edit))
                }
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.action_more))
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.action_duplicate)) }, onClick = {
                            menuOpen = false
                            viewModel.duplicate { copyTitle.replace("%s", it) }
                        })
                        DropdownMenuItem(text = { Text(stringResource(R.string.action_export)) }, onClick = {
                            menuOpen = false
                            viewModel.export()
                        })
                        DropdownMenuItem(text = { Text(stringResource(R.string.action_delete)) }, onClick = {
                            menuOpen = false
                            confirmDelete = true
                        })
                    }
                }
            }
        },
    ) { padding ->
        when (val current = state) {
            RecipeDetailUiState.Loading -> {
                Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            }

            RecipeDetailUiState.NotFound -> {
                Text(stringResource(R.string.recipe_not_found), modifier = Modifier.padding(padding).padding(24.dp))
            }

            is RecipeDetailUiState.Loaded -> {
                RecipeDetailContent(current.recipe, current.servings, viewModel::setServings, padding)
            }
        }
    }

    if (confirmDelete && loaded != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.recipe_delete_title)) },
            text = { Text(stringResource(R.string.recipe_delete_body, loaded.recipe.title)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    viewModel.delete()
                }) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
    export?.let { ExportDialog(it, snackbar, onDismiss = { export = null }) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RecipeDetailContent(
    recipe: Recipe,
    servings: Int,
    onServings: (Int) -> Unit,
    padding: PaddingValues,
) {
    var staplesOpen by rememberSaveable { mutableStateOf(false) }
    val (staples, main) = recipe.ingredients.partition { it.pantryStaple }
    val groups = main.groupBy { it.group }

    LazyColumn(contentPadding = padding, modifier = Modifier.fillMaxSize()) {
        if (recipe.imagePath != null) {
            item {
                RecipePhoto(
                    path = recipe.imagePath,
                    contentDescription = stringResource(R.string.recipe_photo_description),
                    modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f),
                )
            }
        }
        item {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(recipe.title, style = MaterialTheme.typography.headlineSmall)
                recipe.description?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }
                val times =
                    listOfNotNull(
                        recipe.prepMinutes?.let { stringResource(R.string.recipe_prep_minutes, it) },
                        recipe.cookMinutes?.let { stringResource(R.string.recipe_cook_minutes, it) },
                    )
                if (times.isNotEmpty()) {
                    Text(
                        times.joinToString(" · "),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                val importedAt = recipe.importedAt
                if (recipe.origin == RecipeOrigin.IMPORTED && importedAt != null) {
                    val date = Dates.date(importedAt)
                    Text(
                        stringResource(
                            if (recipe.editedAfterImport) R.string.recipe_imported_edited else R.string.recipe_imported_on,
                            date,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (recipe.tags.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        recipe.tags.forEach { SuggestionChip(onClick = {}, label = { Text(it) }) }
                    }
                }
            }
        }
        item { ServingsStepper(servings, recipe.servings, onServings) }
        item { SectionTitle(stringResource(R.string.recipe_ingredients)) }
        groups.forEach { (group, ingredients) ->
            if (group != null) {
                item {
                    Text(
                        group,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp),
                    )
                }
            }
            items(ingredients, key = { it.id }) { IngredientRow(it, recipe.servings, servings) }
        }
        if (staples.isNotEmpty()) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { staplesOpen = !staplesOpen }.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.recipe_staples, staples.size),
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f),
                    )
                    Icon(if (staplesOpen) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, contentDescription = null)
                }
            }
            if (staplesOpen) items(staples, key = { it.id }) { IngredientRow(it, recipe.servings, servings) }
        }
        item { Spacer(Modifier.height(12.dp)) }
        item {
            if (recipe.bodyMarkdown.isBlank()) {
                Text(stringResource(R.string.recipe_steps_empty), modifier = Modifier.padding(16.dp))
            } else {
                MarkdownText(recipe.bodyMarkdown, modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 24.dp))
            }
        }
    }
}

@Composable
private fun ServingsStepper(
    servings: Int,
    original: Int,
    onServings: (Int) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.recipe_servings_label), style = MaterialTheme.typography.titleSmall)
                if (servings != original) {
                    Text(
                        stringResource(R.string.recipe_servings_original, original),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (servings != original) {
                TextButton(onClick = { onServings(original) }) { Text(stringResource(R.string.recipe_servings_reset)) }
            }
            IconButton(onClick = { onServings(servings - 1) }, enabled = servings > RecipeDetailViewModel.MIN_SERVINGS) {
                Icon(Icons.Filled.Remove, contentDescription = stringResource(R.string.recipe_servings_decrease))
            }
            Text(servings.toString(), style = MaterialTheme.typography.titleLarge)
            IconButton(onClick = { onServings(servings + 1) }, enabled = servings < RecipeDetailViewModel.MAX_SERVINGS) {
                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.recipe_servings_increase))
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 4.dp),
    )
}

@Composable
private fun IngredientRow(
    ingredient: Ingredient,
    recipeServings: Int,
    servings: Int,
) {
    val scaled = ServingsScaler.scale(ingredient.quantity, ingredient.unit, recipeServings, servings)
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Text(
            quantityText(scaled, ingredient.unit),
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.width(120.dp),
        )
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            val optional = if (ingredient.optional) " (${stringResource(R.string.recipe_optional)})" else ""
            Text(ingredient.name + optional, style = MaterialTheme.typography.bodyLarge)
            ingredient.note?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
