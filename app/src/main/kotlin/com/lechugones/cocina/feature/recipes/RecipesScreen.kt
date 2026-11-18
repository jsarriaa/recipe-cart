package com.lechugones.cocina.feature.recipes

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.RestaurantMenu
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lechugones.cocina.R
import com.lechugones.cocina.domain.recipe.RecipeSort
import com.lechugones.cocina.domain.recipe.RecipeSummary
import com.lechugones.cocina.feature.export.ExportDialog
import com.lechugones.cocina.feature.export.ExportFile
import com.lechugones.cocina.ui.components.EmptyState
import com.lechugones.cocina.ui.components.RecipePhoto
import com.lechugones.cocina.ui.components.ScreenScaffold
import java.time.LocalDate
import java.time.format.DateTimeFormatter

@Composable
fun RecipesScreen(
    onOpenRecipe: (String) -> Unit,
    onNewRecipe: () -> Unit,
    onImport: () -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: RecipesViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var searching by rememberSaveable { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var sortOpen by remember { mutableStateOf(false) }
    var export by remember { mutableStateOf<ExportFile?>(null) }
    val exportError = stringResource(R.string.export_error)
    val allFileName = stringResource(R.string.export_all_file_name, LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE))

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is RecipesEvent.Exported -> export = event.file
                RecipesEvent.ExportFailed -> snackbar.showSnackbar(exportError)
            }
        }
    }

    ScreenScaffold(
        title = stringResource(R.string.nav_recipes),
        topLevel = true,
        onOpenSettings = onOpenSettings,
        snackbarHostState = snackbar,
        actions = {
            IconButton(onClick = {
                searching = !searching
                if (!searching) viewModel.setQuery("")
            }) {
                Icon(
                    if (searching) Icons.Filled.Close else Icons.Filled.Search,
                    contentDescription = stringResource(if (searching) R.string.action_close_search else R.string.action_search),
                )
            }
            Box {
                IconButton(onClick = { sortOpen = true }) {
                    Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = stringResource(R.string.action_sort))
                }
                DropdownMenu(expanded = sortOpen, onDismissRequest = { sortOpen = false }) {
                    SortItem(R.string.recipes_sort_name, RecipeSort.NAME, state.sort) {
                        viewModel.setSort(it)
                        sortOpen = false
                    }
                    SortItem(R.string.recipes_sort_newest, RecipeSort.NEWEST, state.sort) {
                        viewModel.setSort(it)
                        sortOpen = false
                    }
                }
            }
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.action_more))
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.recipes_import)) }, onClick = {
                        menuOpen = false
                        onImport()
                    })
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.recipes_export_all)) },
                        enabled = state.totalCount > 0,
                        onClick = {
                            menuOpen = false
                            viewModel.exportAll(allFileName)
                        },
                    )
                }
            }
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onNewRecipe,
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text(stringResource(R.string.recipes_new)) },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (searching) {
                OutlinedTextField(
                    value = state.query,
                    onValueChange = viewModel::setQuery,
                    placeholder = { Text(stringResource(R.string.recipes_search_hint)) },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            if (state.tags.isNotEmpty()) {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(state.tags, key = { it }) { tag ->
                        FilterChip(selected = tag == state.tag, onClick = { viewModel.toggleTag(tag) }, label = { Text(tag) })
                    }
                }
            }
            when {
                state.loading -> {
                    val loading = stringResource(R.string.recipes_loading)
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.semantics { contentDescription = loading })
                    }
                }

                state.totalCount == 0 -> {
                    EmptyState(Icons.Outlined.RestaurantMenu, R.string.recipes_empty_title, R.string.recipes_empty_body)
                }

                state.recipes.isEmpty() -> {
                    Text(
                        stringResource(R.string.recipes_no_results),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(24.dp),
                    )
                }

                else -> {
                    LazyColumn(contentPadding = PaddingValues(bottom = 88.dp)) {
                        items(state.recipes, key = { it.id }) { recipe ->
                            RecipeRow(recipe, onClick = { onOpenRecipe(recipe.id) })
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }

    export?.let { ExportDialog(it, snackbar, onDismiss = { export = null }) }
}

@Composable
private fun SortItem(
    label: Int,
    sort: RecipeSort,
    current: RecipeSort,
    onSelect: (RecipeSort) -> Unit,
) {
    DropdownMenuItem(
        text = { Text(stringResource(label)) },
        onClick = { onSelect(sort) },
        trailingIcon = { if (sort == current) Icon(Icons.Filled.Check, contentDescription = null) },
    )
}

@Composable
private fun RecipeRow(
    recipe: RecipeSummary,
    onClick: () -> Unit,
) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        leadingContent = {
            RecipePhoto(
                path = recipe.imagePath,
                contentDescription = null,
                modifier = Modifier.size(56.dp).clip(RoundedCornerShape(8.dp)),
                placeholderIconSize = 24.dp,
            )
        },
        headlineContent = { Text(recipe.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            val servings = pluralStringResource(R.plurals.recipe_servings, recipe.servings, recipe.servings)
            val tags = recipe.tags.joinToString(" · ")
            Text(if (tags.isEmpty()) servings else "$servings · $tags", maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
    )
}
