package com.lechugones.cocina.feature.shopping

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.ShoppingCart
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lechugones.cocina.R
import com.lechugones.cocina.domain.product.ManualProducts
import com.lechugones.cocina.domain.shopping.ShoppingGroup
import com.lechugones.cocina.domain.shopping.ShoppingItem
import com.lechugones.cocina.domain.shopping.ShoppingList
import com.lechugones.cocina.domain.shopping.ShoppingMode
import com.lechugones.cocina.provider.ProviderProduct
import com.lechugones.cocina.ui.components.EmptyState
import com.lechugones.cocina.ui.components.ProductImage
import com.lechugones.cocina.ui.components.ProductListItem
import com.lechugones.cocina.ui.components.ScreenScaffold
import com.lechugones.cocina.ui.components.productSizeText
import com.lechugones.cocina.ui.format.Dates
import com.lechugones.cocina.ui.format.costFigure
import com.lechugones.cocina.ui.format.quantityText
import java.time.LocalDate

/** The Compra tab (SPEC § 4.6). */
@Composable
fun ShoppingScreen(
    onOpenSettings: () -> Unit,
    viewModel: ShoppingViewModel = hiltViewModel(),
) {
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val resources = LocalResources.current
    val context = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    val state = ui.state
    val list = state?.list

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is ShoppingEvent.Finished -> {
                    snackbar.showSnackbar(
                        resources.getQuantityString(R.plurals.shopping_finished, event.lots, event.lots),
                    )
                }
            }
        }
    }

    val manualName = stringResource(R.string.cost_provider_manual)
    val name: (String) -> String = { id -> if (id == ManualProducts.PROVIDER_ID) manualName else viewModel.providerDisplayName(id) ?: id }
    val shareText = list?.let { shareText(it, name) }

    ScreenScaffold(
        title = stringResource(R.string.nav_shopping),
        topLevel = true,
        onOpenSettings = onOpenSettings,
        snackbarHostState = snackbar,
        actions = {
            if (list != null && !list.isEmpty) {
                IconButton(onClick = {
                    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, shareText)
                    try {
                        context.startActivity(Intent.createChooser(send, null))
                    } catch (e: ActivityNotFoundException) {
                        // Nothing can receive text: nothing to do.
                    }
                }) { Icon(Icons.Outlined.Share, contentDescription = stringResource(R.string.shopping_share)) }
            }
            Box {
                IconButton(
                    onClick = { menu = true },
                ) { Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.action_more)) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    val cheapest = state?.mode == ShoppingMode.CHEAPEST
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.shopping_cheapest)) },
                        leadingIcon = { Checkbox(checked = cheapest, onCheckedChange = null) },
                        onClick = {
                            menu = false
                            viewModel.setCheapest(!cheapest)
                        },
                    )
                    DropdownMenuItem(text = { Text(stringResource(R.string.shopping_clear)) }, onClick = {
                        menu = false
                        viewModel.clear()
                    })
                }
            }
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { viewModel.openAdd(true) }) {
                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.shopping_add))
            }
        },
        bottomBar = { if (list != null && !list.isEmpty) TotalBar(list, onFinish = viewModel::finish) },
    ) { padding ->
        when {
            ui.loading || state == null -> {
                Unit
            }

            list == null || (list.isEmpty && state.contributions.isEmpty()) -> {
                EmptyState(
                    Icons.Outlined.ShoppingCart,
                    R.string.shopping_empty_title,
                    R.string.shopping_empty_body,
                    modifier = Modifier.padding(padding),
                )
            }

            else -> {
                // Room below the last row so the + button never covers a price.
                LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(bottom = 88.dp)) {
                    list.groups.forEach { group ->
                        item(key = "g:" + group.providerId) { GroupHeader(group, name) }
                        items(group.items, key = { it.key }) { item ->
                            ItemRow(
                                item,
                                imageUrl = item.product?.key?.let { state.images[it] },
                                onToggle = { viewModel.toggle(item) },
                                onOptions = { viewModel.select(item) },
                            )
                            HorizontalDivider()
                        }
                    }
                    if (state.contributions.isNotEmpty()) {
                        item(key = "recipes") {
                            Text(
                                stringResource(R.string.shopping_recipes),
                                style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
                            )
                        }
                        items(state.contributions, key = { "c:" + it.id }) { contribution ->
                            Row(Modifier.fillMaxWidth().padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    pluralStringResource(
                                        R.plurals.shopping_recipe_line,
                                        contribution.servings,
                                        contribution.recipe.title,
                                        contribution.servings,
                                    ),
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.weight(1f),
                                )
                                IconButton(onClick = { viewModel.removeContribution(contribution.id) }) {
                                    Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.shopping_remove))
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (ui.adding) AddSheet(ui, viewModel::setAddQuery, viewModel::addText, viewModel::addProduct, onDismiss = { viewModel.openAdd(false) })
    ui.selected?.let { item ->
        ItemOptionsSheet(
            item,
            name,
            onProvider = viewModel::override,
            onRemove = viewModel::removeSelected,
            onDismiss = { viewModel.select(null) },
        )
    }
}

/** The shared text, with every word from string resources (AC6.2). */
@Composable
private fun shareText(
    list: ShoppingList,
    name: (String) -> String,
): String {
    val sizes = list.items.associate { it.key to itemSize(it) }
    val resources = LocalResources.current
    val labels =
        ShareLabels(
            title = stringResource(R.string.shopping_share_title, Dates.date(LocalDate.now())),
            noSupermarket = stringResource(R.string.shopping_no_supermarket_upper),
            providerName = { name(it).uppercase(java.util.Locale.forLanguageTag("es-ES")) },
            total = { money, missing ->
                if (missing > 0) {
                    resources.getQuantityString(R.plurals.shopping_total_missing, missing, money, missing)
                } else {
                    resources.getString(R.string.shopping_total, money)
                }
            },
            size = { sizes[it.key] },
        )
    return ShoppingShareText.format(list, labels)
}

/** "≈ 550 g", "6 ud" for a product; the amount needed for a generic item. */
@Composable
private fun itemSize(item: ShoppingItem): String? {
    val product = item.product
    if (product != null) {
        val size = productSizeText(ProviderProduct(product))
        return if (product.approx) "≈ $size" else size
    }
    val needed = item.needed ?: return null
    val unit = item.unit ?: return null
    return quantityText(needed, unit)
}

@Composable
private fun GroupHeader(
    group: ShoppingGroup,
    name: (String) -> String,
) {
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp)) {
        Text(
            group.providerId?.let(name) ?: stringResource(R.string.shopping_no_supermarket),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f),
        )
        if (group.providerId != null && group.items.any { it.cost != null }) {
            Text(costFigure(group.subtotal, missing = false, approximate = group.approximate), style = MaterialTheme.typography.titleSmall)
        }
    }
}

@Composable
private fun ItemRow(
    item: ShoppingItem,
    imageUrl: String?,
    onToggle: () -> Unit,
    onOptions: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onOptions).padding(end = 16.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Checkbox(checked = item.checked, onCheckedChange = { onToggle() })
        ProductImage(imageUrl, 40.dp)
        Column(Modifier.weight(1f)) {
            val count = item.packages?.let { "$it × " }.orEmpty()
            Text(
                count + item.name,
                style = MaterialTheme.typography.bodyLarge,
                color = if (item.checked) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            val details =
                listOfNotNull(
                    itemSize(item),
                    item.needed?.takeIf { item.product != null }?.let { needed ->
                        item.unit?.let { stringResource(R.string.shopping_needed, quantityText(needed, it)) }
                    },
                    item.sources.takeIf { it.isNotEmpty() }?.let { sources ->
                        stringResource(R.string.shopping_for, sources.map { it.recipeTitle }.distinct().joinToString(", "))
                    },
                )
            if (details.isNotEmpty()) {
                Text(
                    details.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Text(
            item.cost?.let {
                costFigure(
                    it,
                    missing = false,
                    approximate = item.approximate,
                )
            } ?: stringResource(R.string.shopping_no_price),
            style = MaterialTheme.typography.bodyMedium,
            color = if (item.cost == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun TotalBar(
    list: ShoppingList,
    onFinish: () -> Unit,
) {
    val checked = list.items.count { it.checked }
    Surface(tonalElevation = 3.dp) {
        Row(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.shopping_total, costFigure(list.total, missing = false, approximate = list.approximate)),
                    style = MaterialTheme.typography.titleMedium,
                )
                if (list.missingPrices > 0) {
                    Text(
                        pluralStringResource(R.plurals.shopping_without_price, list.missingPrices, list.missingPrices),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Button(onClick = onFinish, enabled = checked > 0) { Text(pluralStringResource(R.plurals.shopping_finish, checked, checked)) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddSheet(
    ui: ShoppingUiState,
    onQuery: (String) -> Unit,
    onAddText: () -> Unit,
    onAddProduct: (ProviderProduct) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
            Text(
                stringResource(R.string.shopping_add_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            OutlinedTextField(
                value = ui.addQuery,
                onValueChange = onQuery,
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                placeholder = { Text(stringResource(R.string.shopping_add_hint)) },
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                singleLine = true,
            )
            if (ui.addQuery.isNotBlank()) {
                TextButton(onClick = onAddText, modifier = Modifier.padding(horizontal = 8.dp)) {
                    Text(stringResource(R.string.shopping_add_text, ui.addQuery.trim()))
                }
            }
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                items(ui.addResults, key = { it.product.key.toString() }) { product ->
                    ProductListItem(product, onClick = { onAddProduct(product) })
                    HorizontalDivider()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ItemOptionsSheet(
    item: ShoppingItem,
    name: (String) -> String,
    onProvider: (String?) -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(item.name, style = MaterialTheme.typography.titleMedium)
            if (item.overrideKey != null && item.providerOptions.size > 1) {
                Text(
                    stringResource(R.string.shopping_buy_at),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(top = 8.dp),
                )
                TextButton(onClick = { onProvider(null) }) { Text(stringResource(R.string.shopping_buy_default)) }
                item.providerOptions.sorted().forEach { provider ->
                    TextButton(onClick = { onProvider(provider) }) { Text(name(provider)) }
                }
            }
            if (item.manualId != null) {
                TextButton(onClick = onRemove) { Text(stringResource(R.string.shopping_remove)) }
            }
            if (item.overrideKey == null && item.manualId == null) {
                Text(stringResource(R.string.shopping_no_options), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
