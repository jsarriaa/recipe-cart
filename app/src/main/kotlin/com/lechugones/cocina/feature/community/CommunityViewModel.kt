package com.lechugones.cocina.feature.community

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lechugones.cocina.domain.community.CommunityFilter
import com.lechugones.cocina.domain.community.CommunityRecipe
import com.lechugones.cocina.domain.community.CommunitySearch
import com.lechugones.cocina.ui.ImportSource
import com.lechugones.cocina.ui.IncomingImports
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class CommunityUiState(
    val loading: Boolean = true,
    val failed: Boolean = false,
    val all: List<CommunityRecipe> = emptyList(),
    val filter: CommunityFilter = CommunityFilter(),
    /** Path of the recipe being downloaded. */
    val downloading: String? = null,
) {
    val tags: List<String> get() = CommunitySearch.tags(all)
    val shown: List<CommunityRecipe> get() = CommunitySearch.filter(all, filter)
}

enum class CommunityEvent { DOWNLOAD_FAILED }

/** "Recetas de la comunidad": search the community recipe book and download a recipe. */
@HiltViewModel
class CommunityViewModel
    @Inject
    constructor(
        private val book: CommunityBook,
        private val incoming: IncomingImports,
    ) : ViewModel() {
        private val state = MutableStateFlow(CommunityUiState())
        val uiState: StateFlow<CommunityUiState> = state.asStateFlow()

        private val eventChannel = Channel<CommunityEvent>(Channel.BUFFERED)
        val events: Flow<CommunityEvent> = eventChannel.receiveAsFlow()

        init {
            load()
        }

        fun load() {
            state.update { it.copy(loading = true, failed = false) }
            viewModelScope.launch {
                val recipes = book.index()
                state.update { it.copy(loading = false, failed = recipes == null, all = recipes.orEmpty()) }
            }
        }

        fun setQuery(query: String) = state.update { it.copy(filter = it.filter.copy(query = query)) }

        fun toggleTag(tag: String) =
            state.update {
                val tags = it.filter.tags
                it.copy(filter = it.filter.copy(tags = if (tag in tags) tags - tag else tags + tag))
            }

        fun setMaxMinutes(minutes: Int?) = state.update { it.copy(filter = it.filter.copy(maxMinutes = minutes)) }

        /** Downloads the recipe and hands it to the normal import screen (preview, duplicates, products). */
        fun download(recipe: CommunityRecipe) {
            if (state.value.downloading != null) return
            state.update { it.copy(downloading = recipe.path) }
            viewModelScope.launch {
                val text = book.recipeText(recipe)
                state.update { it.copy(downloading = null) }
                if (text == null) {
                    eventChannel.send(CommunityEvent.DOWNLOAD_FAILED)
                } else {
                    incoming.submit(ImportSource.Text(text))
                }
            }
        }
    }
