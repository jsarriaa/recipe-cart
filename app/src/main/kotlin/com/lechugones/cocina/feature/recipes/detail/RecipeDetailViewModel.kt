package com.lechugones.cocina.feature.recipes.detail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.lechugones.cocina.data.catalogue.CatalogueRepository
import com.lechugones.cocina.data.catalogue.ProductLinker
import com.lechugones.cocina.data.recipe.RecipeLibrary
import com.lechugones.cocina.data.settings.SettingsRepository
import com.lechugones.cocina.domain.product.ManualProductField
import com.lechugones.cocina.domain.product.ManualProductForm
import com.lechugones.cocina.domain.product.ManualProductResult
import com.lechugones.cocina.domain.product.ProductKey
import com.lechugones.cocina.domain.product.SuggestionSource
import com.lechugones.cocina.domain.recipe.Ingredient
import com.lechugones.cocina.domain.recipe.IngredientLink
import com.lechugones.cocina.domain.recipe.Recipe
import com.lechugones.cocina.feature.export.ExportFile
import com.lechugones.cocina.feature.export.RecipeExporter
import com.lechugones.cocina.provider.ProviderProduct
import com.lechugones.cocina.ui.navigation.RecipeDetailRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface RecipeDetailUiState {
    data object Loading : RecipeDetailUiState

    data object NotFound : RecipeDetailUiState

    /** [servings] is the scaled view; it is not saved (SPEC § 4.2). */
    data class Loaded(
        val recipe: Recipe,
        val servings: Int,
        /** Cached display data of the linked products (picture, name, price). */
        val products: Map<ProductKey, ProviderProduct> = emptyMap(),
        val primaryProvider: String = "",
    ) : RecipeDetailUiState
}

/** A product offered in the "Vincular producto" sheet. [source] is null for typed searches. */
data class LinkOption(
    val product: ProviderProduct,
    val source: SuggestionSource?,
)

/** State of the "Vincular producto" sheet for one ingredient. */
data class LinkingState(
    val ingredient: Ingredient,
    val query: String,
    /** The manual product form is open; [manualErrors] are the fields the last save rejected. */
    val manualOpen: Boolean = false,
    val manualErrors: Set<ManualProductField> = emptySet(),
    val options: List<LinkOption> = emptyList(),
    val loading: Boolean = true,
    /** No product of the primary provider is cached yet: the catalogue must be downloaded first. */
    val catalogueEmpty: Boolean = false,
)

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
        private val linker: ProductLinker,
        private val catalogue: CatalogueRepository,
        private val settings: SettingsRepository,
    ) : ViewModel() {
        private val id = savedStateHandle.toRoute<RecipeDetailRoute>().id
        private val state = MutableStateFlow<RecipeDetailUiState>(RecipeDetailUiState.Loading)
        val uiState: StateFlow<RecipeDetailUiState> = state.asStateFlow()
        private val linkingState = MutableStateFlow<LinkingState?>(null)
        val linking: StateFlow<LinkingState?> = linkingState.asStateFlow()
        private val eventChannel = Channel<RecipeDetailEvent>(Channel.BUFFERED)
        val events: Flow<RecipeDetailEvent> = eventChannel.receiveAsFlow()
        private var searchJob: Job? = null

        /** (Re)loads the recipe, e.g. when coming back from the editor. Keeps the scaled servings if still valid. */
        fun load() {
            viewModelScope.launch { reload() }
        }

        private suspend fun reload() {
            val recipe = library.get(id)
            val previous = state.value as? RecipeDetailUiState.Loaded
            state.value =
                if (recipe == null) {
                    RecipeDetailUiState.NotFound
                } else {
                    val keep = previous != null && previous.recipe.servings == recipe.servings
                    RecipeDetailUiState.Loaded(
                        recipe = recipe,
                        servings = if (keep) previous.servings else recipe.servings,
                        products = linker.productsFor(recipe.ingredients.flatMap { it.links }),
                        primaryProvider = settings.settings.first().primaryProvider,
                    )
                }
        }

        fun setServings(servings: Int) {
            val loaded = state.value as? RecipeDetailUiState.Loaded ?: return
            state.value = loaded.copy(servings = servings.coerceIn(MIN_SERVINGS, MAX_SERVINGS))
        }

        // --- "Vincular producto" ------------------------------------------------------------

        /** Opens the sheet with suggestions for [ingredient] at the primary provider. */
        fun openLinking(ingredient: Ingredient) {
            searchJob?.cancel()
            val primary = (state.value as? RecipeDetailUiState.Loaded)?.primaryProvider.orEmpty()
            linkingState.value = LinkingState(ingredient, query = "")
            searchJob =
                viewModelScope.launch {
                    val match = linker.match(ingredient).firstOrNull { it.providerId == primary }
                    val options =
                        match?.result?.suggestions.orEmpty().mapNotNull { s ->
                            match?.products?.get(s.product.key)?.let { LinkOption(it, s.source) }
                        }
                    val empty = catalogue.status(primary).first().products == 0
                    linkingState.update { it?.copy(options = options, loading = false, catalogueEmpty = empty) }
                }
        }

        /** A typed search replaces the suggestions; clearing it brings them back. */
        fun onLinkQuery(query: String) {
            val current = linkingState.value ?: return
            linkingState.value = current.copy(query = query)
            if (query.isBlank()) {
                openLinking(current.ingredient)
                return
            }
            searchJob?.cancel()
            searchJob =
                viewModelScope.launch {
                    delay(SEARCH_DEBOUNCE_MILLIS)
                    linkingState.update { it?.copy(loading = true) }
                    val storeBrandOnly = settings.settings.first().storeBrandOnly
                    val found = catalogue.search(query, storeBrandOnly).first().map { LinkOption(it, null) }
                    linkingState.update { it?.copy(options = found, loading = false) }
                }
        }

        fun openManual(open: Boolean) {
            linkingState.update { it?.copy(manualOpen = open, manualErrors = emptySet()) }
        }

        /** Saves a typed product and links it, or shows which fields are wrong. */
        fun saveManual(form: ManualProductForm) {
            viewModelScope.launch {
                when (val result = catalogue.saveManual(form)) {
                    is ManualProductResult.Invalid -> {
                        linkingState.update { it?.copy(manualErrors = result.fields) }
                    }

                    is ManualProductResult.Valid -> {
                        link(ProviderProduct(result.product, categoryPath = listOfNotNull(result.shop)))
                    }
                }
            }
        }

        fun closeLinking() {
            searchJob?.cancel()
            linkingState.value = null
        }

        /** Links [product] as the preferred one of its provider, keeping links to other providers. */
        fun link(product: ProviderProduct) {
            val ingredient = linkingState.value?.ingredient ?: return
            val key = product.product.key
            // The newest pick goes first: it is the product the recipe shows.
            val links = listOf(IngredientLink(key, preferred = true)) + ingredient.links.filter { it.product.providerId != key.providerId }
            saveLinks(ingredient, links)
        }

        /** Removes the links of [providerId] from the ingredient being linked. */
        fun unlink(providerId: String) {
            val ingredient = linkingState.value?.ingredient ?: return
            saveLinks(ingredient, ingredient.links.filter { it.product.providerId != providerId })
        }

        private fun saveLinks(
            ingredient: Ingredient,
            links: List<IngredientLink>,
        ) {
            closeLinking()
            viewModelScope.launch {
                library.setLinks(id, ingredient.id, links)
                reload()
            }
        }

        // --- Other actions ------------------------------------------------------------------

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
            private const val SEARCH_DEBOUNCE_MILLIS = 250L
        }
    }
