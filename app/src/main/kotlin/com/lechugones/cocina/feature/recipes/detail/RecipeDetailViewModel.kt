package com.lechugones.cocina.feature.recipes.detail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.lechugones.cocina.data.recipe.RecipeLibrary
import com.lechugones.cocina.domain.recipe.Recipe
import com.lechugones.cocina.feature.export.ExportFile
import com.lechugones.cocina.feature.export.RecipeExporter
import com.lechugones.cocina.ui.navigation.RecipeDetailRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface RecipeDetailUiState {
    data object Loading : RecipeDetailUiState

    data object NotFound : RecipeDetailUiState

    /** [servings] is the scaled view; it is not saved (SPEC § 4.2). */
    data class Loaded(
        val recipe: Recipe,
        val servings: Int,
    ) : RecipeDetailUiState
}

sealed interface RecipeDetailEvent {
    data object Deleted : RecipeDetailEvent

    data class Duplicated(
        val id: String,
    ) : RecipeDetailEvent

    data class Exported(
        val file: ExportFile,
    ) : RecipeDetailEvent

    data object ExportFailed : RecipeDetailEvent
}

@HiltViewModel
class RecipeDetailViewModel
    @Inject
    constructor(
        savedStateHandle: SavedStateHandle,
        private val library: RecipeLibrary,
        private val exporter: RecipeExporter,
    ) : ViewModel() {
        private val id = savedStateHandle.toRoute<RecipeDetailRoute>().id
        private val state = MutableStateFlow<RecipeDetailUiState>(RecipeDetailUiState.Loading)
        val uiState: StateFlow<RecipeDetailUiState> = state.asStateFlow()
        private val eventChannel = Channel<RecipeDetailEvent>(Channel.BUFFERED)
        val events: Flow<RecipeDetailEvent> = eventChannel.receiveAsFlow()

        /** (Re)loads the recipe, e.g. when coming back from the editor. Keeps the scaled servings if still valid. */
        fun load() {
            viewModelScope.launch {
                val recipe = library.get(id)
                val previous = state.value as? RecipeDetailUiState.Loaded
                state.value =
                    if (recipe == null) {
                        RecipeDetailUiState.NotFound
                    } else {
                        val keep = previous != null && previous.recipe.servings == recipe.servings
                        RecipeDetailUiState.Loaded(recipe, if (keep) previous.servings else recipe.servings)
                    }
            }
        }

        fun setServings(servings: Int) {
            val loaded = state.value as? RecipeDetailUiState.Loaded ?: return
            state.value = loaded.copy(servings = servings.coerceIn(MIN_SERVINGS, MAX_SERVINGS))
        }

        fun delete() {
            viewModelScope.launch {
                library.delete(id)
                eventChannel.send(RecipeDetailEvent.Deleted)
            }
        }

        fun duplicate(title: (String) -> String) {
            viewModelScope.launch {
                library.duplicate(id, title)?.let { eventChannel.send(RecipeDetailEvent.Duplicated(it.id)) }
            }
        }

        fun export() {
            viewModelScope.launch {
                val file = exporter.export(listOf(id), allFileName = "")
                eventChannel.send(if (file == null) RecipeDetailEvent.ExportFailed else RecipeDetailEvent.Exported(file))
            }
        }

        companion object {
            const val MIN_SERVINGS = 1
            const val MAX_SERVINGS = 99
        }
    }
