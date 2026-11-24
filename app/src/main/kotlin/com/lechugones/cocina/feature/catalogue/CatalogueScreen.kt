package com.lechugones.cocina.feature.catalogue

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Storefront
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.lechugones.cocina.R
import com.lechugones.cocina.data.catalogue.SyncFailure
import com.lechugones.cocina.data.catalogue.SyncState
import com.lechugones.cocina.domain.format.SpanishNumberFormat
import com.lechugones.cocina.domain.product.BrandKind
import com.lechugones.cocina.domain.product.ManualProducts
import com.lechugones.cocina.domain.units.MeasureUnit
import com.lechugones.cocina.provider.ProviderProduct
import com.lechugones.cocina.ui.components.PostalCodeDialog
import com.lechugones.cocina.ui.components.ProductImage
import com.lechugones.cocina.ui.components.ProductListItem
import com.lechugones.cocina.ui.components.ScreenScaffold
import com.lechugones.cocina.ui.components.productReferenceText
import com.lechugones.cocina.ui.components.productSizeText
import com.lechugones.cocina.ui.components.supermarketLabel
import com.lechugones.cocina.ui.format.Dates
import com.lechugones.cocina.ui.format.providerErrorMessage
import com.lechugones.cocina.ui.format.quantityText
import java.math.BigDecimal

/** Search the cached supermarket catalogue and download it on demand (SPEC § 4.1). */
@Composable
fun CatalogueScreen(
    onOpenSettings: () -> Unit,
    viewModel: CatalogueViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val resources = LocalResources.current
    val latest by rememberUpdatedState(state)

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            val name = latest.providerName
            val message =
                when (event) {
                    // Refresh outcomes are shown inside the product sheet.
                    CatalogueEvent.PriceUpdated, CatalogueEvent.ProductGone, is CatalogueEvent.RefreshFailed -> {
                        null
                    }

                    is CatalogueEvent.SyncEnded -> {
                        when (val s = event.state) {
                            is SyncState.Finished -> {
                                if (s.complete) {
                                    resources.getQuantityString(R.plurals.catalogue_sync_done, s.products, count(s.products))
                                } else {
                                    resources.getString(R.string.catalogue_sync_partial)
                                }
                            }

                            is SyncState.Cancelled -> {
                                resources.getQuantityString(R.plurals.catalogue_sync_cancelled, s.products, count(s.products))
                            }

                            is SyncState.Failed -> {
                                when (val reason = s.reason) {
                                    is SyncFailure.Provider -> providerErrorMessage(resources, reason.error, name, latest.postalCode)
                                    else -> null
                                }
                            }

                            else -> {
                                null
                            }
                        }
                    }
                }
            message?.let { snackbar.showSnackbar(it) }
        }
    }

    ScreenScaffold(
        title = stringResource(R.string.nav_catalogue),
        topLevel = true,
        onOpenSettings = onOpenSettings,
        snackbarHostState = snackbar,
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (state.providers.size > 1) {
                Row(
                    Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    state.providers.forEach { (id, name) ->
                        FilterChip(selected = state.providerId == id, onClick = { viewModel.chooseProvider(id) }, label = { Text(name) })
                    }
                }
            }
            SyncBar(state, onStart = viewModel::startSync, onCancel = viewModel::cancelSync)
            if (state.status.products == 0 && state.sync !is SyncState.Running) {
                EmptyCatalogue(state.providerName, onDownload = viewModel::startSync)
            } else {
                SearchBar(state, viewModel::onQueryChange, viewModel::setStoreBrandOnly)
                Results(state, onSelect = viewModel::select)
            }
        }
    }

    if (state.askingPostalCode) {
        PostalCodeDialog(current = state.postalCode, onSave = viewModel::savePostalCode, onDismiss = viewModel::dismissPostalCode)
    }
    state.selected?.let { product ->
        ProductSheet(
            product = product,
            refreshing = state.refreshing,
            outcome =
                when (val outcome = state.refreshOutcome) {
                    CatalogueEvent.PriceUpdated -> stringResource(R.string.catalogue_price_updated)
                    CatalogueEvent.ProductGone -> stringResource(R.string.catalogue_product_gone, state.providerName)
                    is CatalogueEvent.RefreshFailed -> providerErrorMessage(resources, outcome.error, state.providerName, state.postalCode)
                    else -> null
                },
            onRefresh = viewModel::refreshSelected,
            onDismiss = { viewModel.select(null) },
        )
    }
}

private fun count(value: Int): String = SpanishNumberFormat.decimal(BigDecimal(value))

@Composable
private fun SyncBar(
    state: CatalogueUiState,
    onStart: () -> Unit,
    onCancel: () -> Unit,
) {
    val sync = state.sync
    when {
        sync is SyncState.Running -> {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (sync.categoriesTotal == 0) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(
                        stringResource(R.string.catalogue_sync_connecting, state.providerName),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else {
                    LinearProgressIndicator(
                        progress = { sync.categoriesDone.toFloat() / sync.categoriesTotal },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        stringResource(R.string.catalogue_sync_progress, sync.categoriesDone + 1, sync.categoriesTotal),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        pluralStringResource(R.plurals.catalogue_sync_saved, sync.products, count(sync.products)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onCancel) { Text(stringResource(R.string.action_cancel)) }
                }
            }
            HorizontalDivider()
        }

        state.status.products > 0 -> {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    pluralStringResource(
                        R.plurals.catalogue_status,
                        state.status.products,
                        state.providerName,
                        count(state.status.products),
                        state.status.lastFetch
                            ?.let(Dates::date)
                            .orEmpty(),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onStart) { Text(stringResource(R.string.catalogue_update)) }
            }
        }
    }
}

@Composable
private fun EmptyCatalogue(
    providerName: String,
    onDownload: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            Icons.Outlined.Storefront,
            contentDescription = null,
            modifier = Modifier.size(56.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.catalogue_empty_title), style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.catalogue_empty_body, providerName),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onDownload) { Text(stringResource(R.string.catalogue_download)) }
    }
}

@Composable
private fun SearchBar(
    state: CatalogueUiState,
    onQueryChange: (String) -> Unit,
    onStoreBrandOnly: (Boolean) -> Unit,
) {
    val focus = LocalFocusManager.current
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        OutlinedTextField(
            value = state.query,
            onValueChange = onQueryChange,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(stringResource(R.string.catalogue_search_hint)) },
            leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
            trailingIcon = {
                if (state.query.isNotEmpty()) {
                    IconButton(onClick = { onQueryChange("") }) {
                        Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.catalogue_search_clear))
                    }
                }
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
        )
        FilterChip(
            selected = state.storeBrandOnly,
            onClick = { onStoreBrandOnly(!state.storeBrandOnly) },
            label = { Text(stringResource(R.string.catalogue_store_brand_only)) },
        )
    }
}

@Composable
private fun Results(
    state: CatalogueUiState,
    onSelect: (ProviderProduct) -> Unit,
) {
    when {
        state.query.isBlank() -> {
            Hint(stringResource(R.string.catalogue_search_prompt))
        }

        state.results.isEmpty() -> {
            Hint(stringResource(R.string.catalogue_no_results, state.query.trim()))
        }

        else -> {
            LazyColumn(Modifier.fillMaxSize()) {
                items(state.results, key = { it.product.key.providerId + ":" + it.product.key.productId }) { product ->
                    ProductListItem(product, onClick = { onSelect(product) })
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun Hint(text: String) {
    Text(
        text,
        modifier = Modifier.fillMaxWidth().padding(24.dp),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProductSheet(
    product: ProviderProduct,
    refreshing: Boolean,
    outcome: String?,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val p = product.product
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                ProductImage(product.imageUrl, 112.dp)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(p.name, style = MaterialTheme.typography.titleMedium)
                    p.brand?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                    supermarketLabel(product)?.let {
                        Text(it, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.secondary)
                    }
                    if (p.brandKind == BrandKind.STORE) {
                        Text(
                            stringResource(R.string.catalogue_store_brand),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
            if (product.categoryPath.isNotEmpty()) {
                Text(
                    product.categoryPath.joinToString(" › "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(listOfNotNull(p.packaging, productSizeText(product)).joinToString(" · "), style = MaterialTheme.typography.bodyMedium)
            p.drained?.let {
                Text(
                    stringResource(R.string.catalogue_drained, quantityText(it.value, it.unit)),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (p.approx) Text(stringResource(R.string.catalogue_approx), style = MaterialTheme.typography.bodyMedium)
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    p.price?.let(SpanishNumberFormat::money) ?: stringResource(R.string.catalogue_no_price),
                    style = MaterialTheme.typography.headlineSmall,
                )
                productReferenceText(product)?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            }
            p.fetchedAt?.let {
                Text(
                    stringResource(R.string.catalogue_price_date, Dates.date(it)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (refreshing) LinearProgressIndicator(Modifier.fillMaxWidth())
            outcome?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // Manual products have no shop to ask.
                if (p.key.providerId != ManualProducts.PROVIDER_ID) {
                    Button(onClick = onRefresh, enabled = !refreshing) { Text(stringResource(R.string.catalogue_refresh_price)) }
                }
                product.webUrl?.let { url ->
                    OutlinedButton(onClick = {
                        try {
                            context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
                        } catch (e: ActivityNotFoundException) {
                            // No browser installed: nothing to open.
                        }
                    }) { Text(stringResource(R.string.catalogue_open_web)) }
                }
            }
        }
    }
}
