package com.lechugones.cocina.feature.pantry

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Kitchen
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lechugones.cocina.R
import com.lechugones.cocina.domain.pantry.PantryLocation
import com.lechugones.cocina.domain.pantry.UseSoonReason
import com.lechugones.cocina.ui.components.EmptyState
import com.lechugones.cocina.ui.components.ProductImage
import com.lechugones.cocina.ui.components.ProductListItem
import com.lechugones.cocina.ui.components.ScreenScaffold
import com.lechugones.cocina.ui.format.Dates
import com.lechugones.cocina.ui.format.quantityText

/** The Despensa tab (SPEC § 4.5): grouped lots, search, location filter and adding. */
@Composable
fun PantryScreen(
    onOpenSettings: () -> Unit,
    viewModel: PantryViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var addMenu by remember { mutableStateOf(false) }

    ScreenScaffold(
        title = stringResource(R.string.nav_pantry),
        topLevel = true,
        onOpenSettings = onOpenSettings,
        floatingActionButton = {
            Box {
                ExtendedFloatingActionButton(
                    onClick = { addMenu = true },
                    icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    text = { Text(stringResource(R.string.pantry_add)) },
                )
                DropdownMenu(expanded = addMenu, onDismissRequest = { addMenu = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.pantry_add_from_catalogue)) }, onClick = {
                        addMenu = false
                        viewModel.openProductPicker(true)
                    })
                    DropdownMenuItem(text = { Text(stringResource(R.string.pantry_add_manual)) }, onClick = {
                        addMenu = false
                        viewModel.addManual()
                    })
                }
            }
        },
    ) { padding ->
        if (!state.loading && state.total == 0) {
            EmptyState(
                Icons.Outlined.Kitchen,
                R.string.pantry_empty_title,
                R.string.pantry_empty_action,
                modifier = Modifier.padding(padding),
            )
        } else {
            Column(Modifier.padding(padding).fillMaxSize()) {
                Filters(state, viewModel::setQuery, viewModel::setLocation)
                if (state.groups.isEmpty() && !state.loading) {
                    Text(
                        stringResource(R.string.pantry_no_results),
                        modifier = Modifier.padding(24.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                LazyColumn(Modifier.fillMaxSize()) {
                    state.groups.forEach { (group, rows) ->
                        item(key = group.name) { GroupTitle(group, rows.size) }
                        items(rows, key = { it.lot.id }) { row ->
                            LotRowItem(
                                row = row,
                                onClick = { viewModel.edit(row.lot) },
                                onToggleUseSoon = { viewModel.toggleUseSoon(row) },
                                onFinished = { viewModel.finished(row.lot) },
                            )
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }

    state.editing?.let { form ->
        LotEditorSheet(
            form = form,
            onChange = viewModel::updateForm,
            onSave = viewModel::saveForm,
            onDelete = viewModel::deleteEditing,
            onDismiss = viewModel::closeEditor,
        )
    }
    if (state.choosingProduct) {
        ProductPickerSheet(state, viewModel::setProductQuery, viewModel::addProduct, onDismiss = { viewModel.openProductPicker(false) })
    }
}

@Composable
fun locationLabel(location: PantryLocation): String =
    stringResource(
        when (location) {
            PantryLocation.FRIDGE -> R.string.pantry_location_fridge
            PantryLocation.FREEZER -> R.string.pantry_location_freezer
            PantryLocation.CUPBOARD -> R.string.pantry_location_cupboard
        },
    )

@Composable
private fun Filters(
    state: PantryUiState,
    onQuery: (String) -> Unit,
    onLocation: (PantryLocation) -> Unit,
) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        OutlinedTextField(
            value = state.query,
            onValueChange = onQuery,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(stringResource(R.string.pantry_search_hint)) },
            leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
            singleLine = true,
        )
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PantryLocation.entries.forEach { location ->
                FilterChip(
                    selected = state.location == location,
                    onClick = { onLocation(location) },
                    label = { Text(locationLabel(location)) },
                )
            }
        }
    }
}

@Composable
private fun GroupTitle(
    group: PantryGroup,
    count: Int,
) {
    Text(
        stringResource(
            when (group) {
                PantryGroup.USE_SOON -> R.string.pantry_group_use_soon
                PantryGroup.OPENED -> R.string.pantry_group_opened
                PantryGroup.SEALED -> R.string.pantry_group_sealed
                PantryGroup.STAPLES -> R.string.pantry_group_staples
            },
        ) + " ($count)",
        style = MaterialTheme.typography.titleSmall,
        color = if (group == PantryGroup.USE_SOON) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
    )
}

@Composable
private fun LotRowItem(
    row: LotRow,
    onClick: () -> Unit,
    onToggleUseSoon: () -> Unit,
    onFinished: () -> Unit,
) {
    val lot = row.lot
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ProductImage(row.imageUrl, 48.dp)
        Column(Modifier.weight(1f)) {
            Text(lot.name, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val details =
                listOfNotNull(
                    if (lot.presenceOnly) {
                        stringResource(R.string.pantry_have_it)
                    } else {
                        lot.quantity?.let { q ->
                            val unit = checkNotNull(lot.unit)
                            val left = quantityText(q.toBigDecimal(), unit)
                            val full = lot.packageQuantity?.takeIf { it != q }
                            if (full ==
                                null
                            ) {
                                left
                            } else {
                                stringResource(R.string.pantry_quantity_of, left, quantityText(full.toBigDecimal(), unit))
                            }
                        }
                    },
                    lot.openedAt?.takeIf { lot.opened }?.let { stringResource(R.string.pantry_opened_on, Dates.date(it)) },
                    lot.location?.let { locationLabel(it) },
                )
            Text(
                details.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val status = row.status
            val deadline = status.deadline
            val statusText =
                when {
                    status.expired -> stringResource(R.string.pantry_expired)
                    status.reason == UseSoonReason.MANUAL -> stringResource(R.string.pantry_use_soon_manual)
                    status.useSoon && deadline != null -> stringResource(R.string.pantry_use_by, Dates.date(deadline))
                    else -> null
                }
            statusText?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error) }
        }
        if (lot.presenceOnly) {
            TextButton(onClick = onFinished) { Text(stringResource(R.string.pantry_finished)) }
        } else {
            IconButton(onClick = onToggleUseSoon) {
                Icon(
                    if (row.status.useSoon) Icons.Filled.Bolt else Icons.Outlined.Bolt,
                    contentDescription =
                        stringResource(if (row.status.useSoon) R.string.pantry_clear_use_soon else R.string.pantry_toggle_use_soon),
                    tint = if (row.status.useSoon) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProductPickerSheet(
    state: PantryUiState,
    onQuery: (String) -> Unit,
    onPick: (com.lechugones.cocina.provider.ProviderProduct) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
            Text(
                stringResource(R.string.pantry_pick_product_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            OutlinedTextField(
                value = state.productQuery,
                onValueChange = onQuery,
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                placeholder = { Text(stringResource(R.string.catalogue_search_hint)) },
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                singleLine = true,
            )
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 480.dp)) {
                items(state.productResults, key = { it.product.key.toString() }) { product ->
                    ProductListItem(product, onClick = { onPick(product) })
                    HorizontalDivider()
                }
            }
        }
    }
}
