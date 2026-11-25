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
import androidx.compose.foundation.layout.size
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
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
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
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lechugones.cocina.R
import com.lechugones.cocina.domain.cost.IngredientCostLine
import com.lechugones.cocina.domain.cost.LineStatus
import com.lechugones.cocina.domain.format.SpanishNumberFormat
import com.lechugones.cocina.domain.recipe.Ingredient
import com.lechugones.cocina.domain.recipe.Recipe
import com.lechugones.cocina.domain.recipe.RecipeOrigin
import com.lechugones.cocina.domain.recipe.ServingsScaler
import com.lechugones.cocina.feature.community.PublishDialogs
import com.lechugones.cocina.feature.community.PublishViewModel
import com.lechugones.cocina.feature.export.ExportDialog
import com.lechugones.cocina.feature.export.ExportFile
import com.lechugones.cocina.provider.ProviderProduct
import com.lechugones.cocina.ui.components.ManualProductDialog
import com.lechugones.cocina.ui.components.MarkdownText
import com.lechugones.cocina.ui.components.ProductImage
import com.lechugones.cocina.ui.components.RecipePhoto
import com.lechugones.cocina.ui.components.ScreenScaffold
import com.lechugones.cocina.ui.components.productPriceText
import com.lechugones.cocina.ui.components.supermarketLabel
import com.lechugones.cocina.ui.format.Dates
import com.lechugones.cocina.ui.format.providerErrorMessage
import com.lechugones.cocina.ui.format.quantityText
import com.lechugones.cocina.ui.format.usageText

@Composable
fun RecipeDetailScreen(
    onBack: () -> Unit,
    onEdit: (String) -> Unit,
    onOpenRecipe: (String) -> Unit,
    viewModel: RecipeDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val linking by viewModel.linking.collectAsStateWithLifecycle()
    val cookSheet by viewModel.cookSheet.collectAsStateWithLifecycle()
    val cookDone = stringResource(R.string.cook_done)
    val cookUndo = stringResource(R.string.cook_undo)
    val cookUndone = stringResource(R.string.cook_undone)
    val addedToList = stringResource(R.string.shopping_added)
    val snackbar = remember { SnackbarHostState() }
    var menuOpen by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var export by remember { mutableStateOf<ExportFile?>(null) }
    val publisher: PublishViewModel = hiltViewModel()
    val publishState by publisher.uiState.collectAsStateWithLifecycle()
    val exportError = stringResource(R.string.export_error)
    val copyTitle = stringResource(R.string.recipe_copy_title, "%s")

    // Runs again when the screen comes back from the editor, so edits show up.
    LaunchedEffect(Unit) { viewModel.load() }
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                RecipeDetailEvent.Deleted -> {
                    onBack()
                }

                is RecipeDetailEvent.Duplicated -> {
                    onOpenRecipe(event.id)
                }

                is RecipeDetailEvent.Exported -> {
                    export = event.file
                }

                RecipeDetailEvent.ExportFailed -> {
                    snackbar.showSnackbar(exportError)
                }

                is RecipeDetailEvent.Cooked -> {
                    val result = snackbar.showSnackbar(cookDone, actionLabel = cookUndo, duration = SnackbarDuration.Long)
                    if (result == SnackbarResult.ActionPerformed) viewModel.undoCook(event.eventId)
                }

                RecipeDetailEvent.CookUndone -> {
                    snackbar.showSnackbar(cookUndone)
                }

                RecipeDetailEvent.AddedToList -> {
                    snackbar.showSnackbar(addedToList)
                }
            }
        }
    }

    val loaded = state as? RecipeDetailUiState.Loaded
    val resources = LocalResources.current
    val refreshResult = loaded?.refreshResult
    LaunchedEffect(refreshResult) {
        val result = refreshResult ?: return@LaunchedEffect
        val name = loaded.primaryProvider.let { viewModel.providerDisplayName(it) ?: it }
        val error = result.error
        snackbar.showSnackbar(
            if (error != null) {
                providerErrorMessage(resources, error, name, null)
            } else {
                resources.getQuantityString(R.plurals.cost_refreshed, result.updated, result.updated)
            },
        )
        viewModel.dismissRefreshResult()
    }
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
                        DropdownMenuItem(text = { Text(stringResource(R.string.publish_menu)) }, onClick = {
                            menuOpen = false
                            publisher.start()
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
                RecipeDetailContent(current, viewModel, padding)
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
    PublishDialogs(
        publishState,
        onSaveName = publisher::saveName,
        onChangeName = publisher::changeName,
        onPublish = publisher::publish,
        onDismiss = publisher::dismiss,
    )

    cookSheet?.let { cook ->
        CookSheet(
            state = cook,
            onAmount = viewModel::setCookAmount,
            onSkip = viewModel::setCookSkip,
            onFinished = viewModel::setFinished,
            onConfirm = viewModel::confirmCook,
            onDismiss = viewModel::closeCook,
        )
    }

    val sheet = linking
    if (sheet != null && loaded != null) {
        LinkProductSheet(
            state = sheet,
            providers = loaded.providers,
            onProvider = viewModel::switchLinkProvider,
            current =
                sheet.ingredient.links
                    .filter { it.product.providerId == sheet.providerId }
                    .firstNotNullOfOrNull { loaded.products[it.product] },
            onQuery = viewModel::onLinkQuery,
            onPick = viewModel::link,
            onUnlink = viewModel::unlink,
            onAddManual = { viewModel.openManual(true) },
            onDismiss = viewModel::closeLinking,
        )
        if (sheet.manualOpen) {
            ManualProductDialog(
                initialName = sheet.ingredient.name,
                errors = sheet.manualErrors,
                onSave = viewModel::saveManual,
                onDismiss = { viewModel.openManual(false) },
            )
        }
    }
}

/** The cost card's choice for [ingredient] in the current mode, else its first cached link. */
private fun shownProduct(
    ingredient: Ingredient,
    loaded: RecipeDetailUiState.Loaded,
    line: IngredientCostLine?,
): ProviderProduct? =
    line?.product?.key?.let { loaded.products[it] }
        // Only the primary view falls back to another supermarket's product; a chosen one shows its own or none.
        ?: if (loaded.mode == CostMode.Primary) linkedProduct(ingredient, loaded) else null

/** "1/2 paquete, 250 g de 500 g · 1,87 €", or why there is no cost. Staples left out of the cost say so. */
@Composable
private fun CostLineText(line: IngredientCostLine) {
    val text =
        when (line.status) {
            LineStatus.PRICED -> {
                listOfNotNull(usageText(line), line.cost?.let { SpanishNumberFormat.money(it) }).joinToString(" · ")
            }

            LineStatus.NO_PRICE -> {
                listOfNotNull(usageText(line), stringResource(R.string.catalogue_no_price)).joinToString(" · ")
            }

            LineStatus.INCOMPATIBLE_UNITS -> {
                stringResource(R.string.cost_incompatible)
            }

            LineStatus.NO_LINK, LineStatus.NO_QUANTITY -> {
                null
            }
        } ?: return
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (line.stale) {
            Icon(
                Icons.Outlined.WarningAmber,
                contentDescription = stringResource(R.string.cost_stale),
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(14.dp),
            )
            Spacer(Modifier.width(4.dp))
        }
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = if (line.includedInTotals) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.outline,
        )
    }
}

/** The product shown for [ingredient]: the first cached link, preferred ones first (the newest pick is first). */
private fun linkedProduct(
    ingredient: Ingredient,
    loaded: RecipeDetailUiState.Loaded,
): ProviderProduct? {
    val links = ingredient.links.sortedBy { !it.preferred }
    return links.firstNotNullOfOrNull { loaded.products[it.product] }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RecipeDetailContent(
    loaded: RecipeDetailUiState.Loaded,
    viewModel: RecipeDetailViewModel,
    padding: PaddingValues,
) {
    val onServings: (Int) -> Unit = viewModel::setServings
    val onLink: (Ingredient) -> Unit = viewModel::openLinking
    val recipe = loaded.recipe
    val servings = loaded.servings
    val lines =
        loaded.cost
            ?.lines
            .orEmpty()
            .associateBy { it.ingredient.id }
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
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = viewModel::addToList, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.shopping_add_recipe))
                }
                OutlinedButton(onClick = viewModel::openCook, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.cook_action))
                }
            }
        }
        item {
            CostCard(
                loaded = loaded,
                providerNames = viewModel::providerDisplayName,
                onMode = viewModel::setMode,
                onIncludes = viewModel::setIncludes,
                onRefresh = viewModel::refreshPrices,
                onFixMissing = onLink,
            )
        }
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
            items(ingredients, key = { it.id }) {
                IngredientRow(it, recipe.servings, servings, shownProduct(it, loaded, lines[it.id]), lines[it.id]) { onLink(it) }
            }
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
            if (staplesOpen) {
                items(staples, key = { it.id }) {
                    IngredientRow(it, recipe.servings, servings, shownProduct(it, loaded, lines[it.id]), lines[it.id]) { onLink(it) }
                }
            }
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
    product: ProviderProduct?,
    line: IngredientCostLine?,
    onClick: () -> Unit,
) {
    val scaled = ServingsScaler.scale(ingredient.quantity, ingredient.unit, recipeServings, servings)
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(
                onClickLabel = stringResource(R.string.link_action),
                onClick = onClick,
            ).padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ProductImage(product?.imageUrl, 40.dp)
        Spacer(Modifier.width(12.dp))
        Text(
            quantityText(scaled, ingredient.unit),
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.width(96.dp),
        )
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            val optional = if (ingredient.optional) " (${stringResource(R.string.recipe_optional)})" else ""
            Text(ingredient.name + optional, style = MaterialTheme.typography.bodyLarge)
            ingredient.note?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (product != null) {
                Text(
                    listOfNotNull(
                        supermarketLabel(product),
                        stringResource(R.string.link_product_line, product.product.name, productPriceText(product)),
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            line?.let { CostLineText(it) }
        }
    }
}
