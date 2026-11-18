package com.lechugones.cocina.feature.importer

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lechugones.cocina.R
import com.lechugones.cocina.ui.components.ProductImage
import com.lechugones.cocina.ui.components.ProductListItem
import com.lechugones.cocina.ui.components.productPriceText

/** Step 5 of the import (SPEC § 4.3): one row per ingredient with the suggested product. */
@Composable
fun SuggestionsList(
    state: ImportUiState.Suggestions,
    onChange: (Int) -> Unit,
    onClear: (Int) -> Unit,
) {
    if (state.catalogueEmpty) {
        Text(
            stringResource(R.string.import_match_no_catalogue, state.providerName),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(24.dp),
        )
        return
    }
    val linked = state.items.count { it.selected != null }
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Text(
                pluralStringResource(R.plurals.import_match_summary, state.items.size, linked, state.items.size, state.providerName),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
        }
        state.items.withIndex().groupBy { it.value.row }.forEach { (_, entries) ->
            item {
                Text(
                    entries.first().value.recipeTitle,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 4.dp),
                )
            }
            items(entries, key = { it.index }) { (index, item) ->
                SuggestionRow(item, onChange = { onChange(index) }, onClear = { onClear(index) })
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun SuggestionRow(
    item: MatchItem,
    onChange: () -> Unit,
    onClear: () -> Unit,
) {
    val product = item.selected
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onChange).padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ProductImage(product?.imageUrl, 48.dp)
        Column(Modifier.weight(1f)) {
            Text(item.ingredientName, style = MaterialTheme.typography.bodyLarge)
            Text(
                if (product == null) {
                    stringResource(if (item.options.isEmpty()) R.string.import_match_none else R.string.import_match_unlinked)
                } else {
                    stringResource(R.string.link_product_line, product.product.name, productPriceText(product))
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (product == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (product != null) {
            IconButton(onClick = onClear) { Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.link_remove)) }
        }
    }
}

/** "Aceptar todo" and "Importar sin vincular". */
@Composable
fun SuggestionsActions(
    state: ImportUiState.Suggestions,
    onAcceptAll: () -> Unit,
    onWithoutLinks: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!state.catalogueEmpty) {
            Button(onClick = onAcceptAll, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.import_match_accept_all)) }
            OutlinedButton(onClick = onWithoutLinks, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.import_match_without_links))
            }
        } else {
            Button(
                onClick = onWithoutLinks,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.import_match_without_links)) }
        }
    }
}

/** "Ver más": every candidate for one ingredient, plus leaving it unlinked. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SuggestionChooser(
    item: MatchItem,
    onPick: (com.lechugones.cocina.provider.ProviderProduct?) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
            Text(
                stringResource(R.string.link_title, item.ingredientName),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            if (item.options.isEmpty()) {
                Text(
                    stringResource(R.string.import_match_none_body),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(16.dp),
                )
            }
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 480.dp)) {
                items(item.options, key = { it.product.key.toString() }) { option ->
                    ProductListItem(
                        option,
                        onClick = { onPick(option) },
                        supporting = if (option.product.key == item.selected?.product?.key) stringResource(R.string.link_current) else null,
                    )
                    HorizontalDivider()
                }
            }
            TextButton(onClick = { onPick(null) }, modifier = Modifier.padding(horizontal = 8.dp)) {
                Text(stringResource(R.string.import_match_leave_unlinked))
            }
        }
    }
}
