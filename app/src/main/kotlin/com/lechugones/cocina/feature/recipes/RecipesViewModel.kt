package com.lechugones.cocina.feature.recipes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lechugones.cocina.data.recipe.RecipeLibrary
import com.lechugones.cocina.domain.recipe.RecipeListFilter
import com.lechugones.cocina.domain.recipe.RecipeSort
import com.lechugones.cocina.domain.recipe.RecipeSummary
import com.lechugones.cocina.feature.export.ExportFile
import com.lechugones.cocina.feature.export.RecipeExporter
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class RecipesUiState(
    val loading: Boolean = true,
    /** Recipes in the library, before search and filters. */
    val totalCount: Int = 0,
    val recipes: List<RecipeSummary> = emptyList(),
    val tags: List<String> = emptyList(),
    val query: String = "",
    val tag: String? = null,
    val sort: RecipeSort = RecipeSort.NAME,
)

sealed interface RecipesEvent {
    data class Exported(
        val file: ExportFile,
    ) : RecipesEvent

    data object ExportFailed : RecipesEvent
}

@HiltViewModel
class RecipesViewModel
    @Inject
    constructor(
        private val library: RecipeLibrary,
        private val exporter: RecipeExporter,
    ) : ViewModel() {
        private val query = MutableStateFlow("")
        private val tag = MutableStateFlow<String?>(null)
        private val sort = MutableStateFlow(RecipeSort.NAME)
        private val eventChannel = Channel<RecipesEvent>(Channel.BUFFERED)
        val events: Flow<RecipesEvent> = eventChannel.receiveAsFlow()

        val uiState: StateFlow<RecipesUiState> =
            combine(library.observeSummaries(), query, tag, sort) { all, query, tag, sort ->
                RecipesUiState(
                    loading = false,
                    totalCount = all.size,
                    recipes = RecipeListFilter.apply(all, query, tag, sort),
                    tags = RecipeListFilter.tags(all),
                    query = query,
                    tag = tag,
                    sort = sort,
                )
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), RecipesUiState())

        init {
            // Photos left behind by an editor session that never saved (for example, the app was killed).
            viewModelScope.launch { library.cleanOrphanPhotos() }
        }

        fun setQuery(value: String) {
            query.value = value
        }

        fun toggleTag(value: String) {
            tag.value = if (tag.value == value) null else value
        }

        fun setSort(value: RecipeSort) {
            sort.value = value
        }

        fun exportAll(fileName: String) {
            viewModelScope.launch {
                val file = exporter.export(null, fileName)
                eventChannel.send(if (file == null) RecipesEvent.ExportFailed else RecipesEvent.Exported(file))
            }
        }

        private companion object {
            const val STOP_TIMEOUT_MILLIS = 5_000L
        }
    }
