package com.lechugones.cocina.feature.recipes

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.RestaurantMenu
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lechugones.cocina.R
import com.lechugones.cocina.ui.components.EmptyState
import com.lechugones.cocina.ui.theme.CocinaTheme

@Composable
fun RecipesScreen(viewModel: RecipesViewModel = hiltViewModel()) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    RecipesContent(uiState)
}

@Composable
fun RecipesContent(
    uiState: RecipesUiState,
    modifier: Modifier = Modifier,
) {
    when (uiState) {
        RecipesUiState.Loading -> {
            val loading = stringResource(R.string.recipes_loading)
            Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.semantics { contentDescription = loading })
            }
        }

        is RecipesUiState.Loaded -> {
            if (uiState.recipes.isEmpty()) {
                EmptyState(
                    icon = Icons.Outlined.RestaurantMenu,
                    title = R.string.recipes_empty_title,
                    body = R.string.recipes_empty_body,
                    modifier = modifier,
                )
            } else {
                LazyColumn(modifier.fillMaxSize()) {
                    items(uiState.recipes, key = { it.id }) { recipe ->
                        ListItem(
                            headlineContent = { Text(recipe.title) },
                            supportingContent = {
                                Text(pluralStringResource(R.plurals.recipe_servings, recipe.servings, recipe.servings))
                            },
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun RecipesEmptyPreview() {
    CocinaTheme(dynamicColor = false) { RecipesContent(RecipesUiState.Loaded(emptyList())) }
}
