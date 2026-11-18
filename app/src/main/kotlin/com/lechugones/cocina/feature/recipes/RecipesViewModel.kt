package com.lechugones.cocina.feature.recipes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lechugones.cocina.data.recipe.RecipeRepository
import com.lechugones.cocina.domain.recipe.RecipeSummary
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

sealed interface RecipesUiState {
    data object Loading : RecipesUiState

    data class Loaded(
        val recipes: List<RecipeSummary>,
    ) : RecipesUiState
}

@HiltViewModel
class RecipesViewModel
    @Inject
    constructor(
        recipeRepository: RecipeRepository,
    ) : ViewModel() {
        val uiState: StateFlow<RecipesUiState> =
            recipeRepository
                .observeSummaries()
                .map<List<RecipeSummary>, RecipesUiState> { RecipesUiState.Loaded(it) }
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), RecipesUiState.Loading)

        private companion object {
            const val STOP_TIMEOUT_MILLIS = 5_000L
        }
    }
