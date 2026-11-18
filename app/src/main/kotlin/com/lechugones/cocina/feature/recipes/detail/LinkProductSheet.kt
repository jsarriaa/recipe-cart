package com.lechugones.cocina.feature.recipes.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.lechugones.cocina.R
import com.lechugones.cocina.domain.product.SuggestionSource
import com.lechugones.cocina.provider.ProviderProduct
import com.lechugones.cocina.ui.components.ProductListItem

/**
 * "Vincular producto" (SPEC § 4.2): suggestions from link memory and the cached catalogue, a
 * search box, and the current link with "Quitar vínculo".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LinkProductSheet(
    state: LinkingState,
    current: ProviderProduct?,
    onQuery: (String) -> Unit,
    onPick: (ProviderProduct) -> Unit,
    onUnlink: (String) -> Unit,
    onAddManual: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
            Text(
                stringResource(R.string.link_title, state.ingredient.name),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            if (current != null) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.link_current),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { onUnlink(current.product.key.providerId) }) { Text(stringResource(R.string.link_remove)) }
                }
                ProductListItem(current, onClick = onDismiss)
                HorizontalDivider()
            }
            OutlinedTextField(
                value = state.query,
                onValueChange = onQuery,
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                placeholder = { Text(stringResource(R.string.link_search_hint)) },
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                trailingIcon = {
                    if (state.query.isNotEmpty()) {
                        IconButton(onClick = { onQuery("") }) {
                            Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.catalogue_search_clear))
                        }
                    }
                },
                singleLine = true,
            )
            TextButton(onClick = onAddManual, modifier = Modifier.padding(horizontal = 8.dp)) {
                Text(stringResource(R.string.manual_add))
            }
            if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp))
            val message =
                when {
                    state.loading -> null
                    state.catalogueEmpty -> stringResource(R.string.link_no_catalogue)
                    state.options.isEmpty() && state.query.isNotBlank() -> stringResource(R.string.catalogue_no_results, state.query.trim())
                    state.options.isEmpty() -> stringResource(R.string.link_no_suggestions)
                    else -> null
                }
            message?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 480.dp), verticalArrangement = Arrangement.Top) {
                items(state.options, key = {
                    it.product.product.key
                        .toString()
                }) { option ->
                    ProductListItem(
                        option.product,
                        onClick = { onPick(option.product) },
                        supporting =
                            when (option.source) {
                                SuggestionSource.MEMORY -> stringResource(R.string.link_source_memory)
                                SuggestionSource.FILE -> stringResource(R.string.link_source_file)
                                else -> null
                            },
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}
