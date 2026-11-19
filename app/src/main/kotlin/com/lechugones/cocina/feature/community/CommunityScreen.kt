package com.lechugones.cocina.feature.community

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lechugones.cocina.R
import com.lechugones.cocina.domain.community.CommunityRecipe
import com.lechugones.cocina.ui.components.ScreenScaffold

private val TIME_LIMITS = listOf(15, 30, 60)

/** "Recetas de la comunidad" (SPEC § 4.10). */
@Composable
fun CommunityScreen(
    onBack: () -> Unit,
    viewModel: CommunityViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val failed = stringResource(R.string.community_download_failed)
    LaunchedEffect(viewModel) { viewModel.events.collect { snackbar.showSnackbar(failed) } }
    ScreenScaffold(title = stringResource(R.string.community_title), onBack = onBack, snackbarHostState = snackbar) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                state.loading -> {
                    CircularProgressIndicator(Modifier.align(Alignment.Center))
                }

                state.failed -> {
                    Column(
                        Modifier.align(Alignment.Center).padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(stringResource(R.string.community_load_failed), style = MaterialTheme.typography.bodyLarge)
                        OutlinedButton(onClick = viewModel::load) { Text(stringResource(R.string.community_retry)) }
                    }
                }

                else -> {
                    CommunityList(state, viewModel)
                }
            }
        }
    }
}

@Composable
private fun CommunityList(
    state: CommunityUiState,
    viewModel: CommunityViewModel,
) {
    val shown = state.shown
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 12.dp)) {
                OutlinedTextField(
                    value = state.filter.query,
                    onValueChange = viewModel::setQuery,
                    placeholder = { Text(stringResource(R.string.community_search_hint)) },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                )
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(TIME_LIMITS) { minutes ->
                        val selected = state.filter.maxMinutes == minutes
                        FilterChip(
                            selected = selected,
                            onClick = { viewModel.setMaxMinutes(if (selected) null else minutes) },
                            label = { Text(stringResource(R.string.community_max_minutes, minutes)) },
                        )
                    }
                }
                if (state.tags.isNotEmpty()) {
                    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(state.tags) { tag ->
                            FilterChip(
                                selected = tag in state.filter.tags,
                                onClick = { viewModel.toggleTag(tag) },
                                label = { Text(tag) },
                            )
                        }
                    }
                }
                Text(
                    pluralStringResource(R.plurals.community_count, shown.size, shown.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
        }
        if (shown.isEmpty()) {
            item {
                Text(
                    stringResource(if (state.all.isEmpty()) R.string.community_empty else R.string.community_none),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
        items(shown, key = { it.path }) { recipe ->
            CommunityCard(
                recipe,
                downloading = state.downloading == recipe.path,
                enabled = state.downloading == null,
                onDownload = { viewModel.download(recipe) },
            )
        }
    }
}

@Composable
private fun CommunityCard(
    recipe: CommunityRecipe,
    downloading: Boolean,
    enabled: Boolean,
    onDownload: () -> Unit,
) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(recipe.title, style = MaterialTheme.typography.titleMedium)
            recipe.author?.let {
                Text(
                    stringResource(R.string.community_by, it),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            val facts =
                buildList {
                    add(pluralStringResource(R.plurals.recipe_servings, recipe.servings, recipe.servings))
                    recipe.totalMinutes?.let { add(stringResource(R.string.community_minutes, it)) }
                    addAll(recipe.tags)
                }
            Text(
                facts.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            recipe.description?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Button(onClick = onDownload, enabled = enabled) {
                    if (downloading) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                    }
                    Text(stringResource(R.string.community_download), modifier = Modifier.padding(start = 8.dp))
                }
            }
        }
    }
}
