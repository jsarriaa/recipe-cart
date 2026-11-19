package com.lechugones.cocina.feature.cookable

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lechugones.cocina.R
import com.lechugones.cocina.domain.pantry.CoverageStatus
import com.lechugones.cocina.domain.pantry.RecipeCoverage
import com.lechugones.cocina.ui.components.RecipePhoto
import com.lechugones.cocina.ui.components.ScreenScaffold
import com.lechugones.cocina.ui.format.quantityText

/** "¿Qué puedo cocinar?" (SPEC § 4.5). */
@Composable
fun CookableScreen(
    onBack: () -> Unit,
    onOpenRecipe: (String) -> Unit,
    viewModel: CookableViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val added = stringResource(R.string.shopping_added)
    LaunchedEffect(viewModel) { viewModel.added.collect { snackbar.showSnackbar(added) } }
    ScreenScaffold(title = stringResource(R.string.cookable_title), onBack = onBack, snackbarHostState = snackbar) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (state.loading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                return@Column
            }
            if (state.pantryEmpty) {
                Text(
                    stringResource(R.string.cookable_pantry_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
            FilterChip(
                selected = state.onlyUseSoon,
                onClick = viewModel::toggleOnlyUseSoon,
                label = { Text(stringResource(R.string.cookable_only_use_soon)) },
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            if (state.coverage.isEmpty()) {
                Text(
                    stringResource(if (state.onlyUseSoon) R.string.cookable_none_use_soon else R.string.cookable_none),
                    modifier = Modifier.padding(16.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            LazyColumn(Modifier.fillMaxSize()) {
                items(state.coverage, key = { it.recipe.id }) { item ->
                    CoverageRow(item, onClick = { onOpenRecipe(item.recipe.id) }, onAddMissing = { viewModel.addMissing(item) })
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun CoverageRow(
    item: RecipeCoverage,
    onClick: () -> Unit,
    onAddMissing: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        RecipePhoto(
            item.recipe.imagePath,
            contentDescription = null,
            modifier = Modifier.size(56.dp).clip(RoundedCornerShape(8.dp)),
            placeholderIconSize = 24.dp,
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(item.recipe.title, style = MaterialTheme.typography.bodyLarge)
            val (text, color) =
                when (item.status) {
                    CoverageStatus.ALL -> {
                        stringResource(R.string.cookable_all) to MaterialTheme.colorScheme.primary
                    }

                    CoverageStatus.FEW_MISSING, CoverageStatus.MANY_MISSING -> {
                        pluralStringResource(R.plurals.cookable_missing, item.missing.size, item.missing.size) to
                            MaterialTheme.colorScheme.onSurfaceVariant
                    }
                }
            Text(text, style = MaterialTheme.typography.labelLarge, color = color)
            if (item.missing.isNotEmpty()) {
                Text(
                    stringResource(
                        R.string.cookable_missing_list,
                        item.missing
                            .map { ingredient ->
                                item.partlyMissing[ingredient.id]?.let {
                                    stringResource(R.string.cookable_partly_missing, ingredient.name, quantityText(it, ingredient.unit))
                                } ?: ingredient.name
                            }.joinToString(", "),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (item.usesSoon.isNotEmpty()) {
                AssistChip(
                    onClick = onClick,
                    label = { Text(stringResource(R.string.cookable_uses_soon, item.usesSoon.joinToString(", "))) },
                )
            }
            if (item.missing.isNotEmpty()) {
                TextButton(onClick = onAddMissing) { Text(stringResource(R.string.shopping_add_missing)) }
            }
        }
    }
}
