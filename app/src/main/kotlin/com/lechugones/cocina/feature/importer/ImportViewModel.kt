package com.lechugones.cocina.feature.importer

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lechugones.cocina.data.catalogue.CatalogueRepository
import com.lechugones.cocina.data.catalogue.ProductLinker
import com.lechugones.cocina.data.recipe.RecipeLibrary
import com.lechugones.cocina.data.settings.SettingsRepository
import com.lechugones.cocina.domain.product.ProductKey
import com.lechugones.cocina.domain.recipe.Ingredient
import com.lechugones.cocina.domain.recipe.IngredientLink
import com.lechugones.cocina.domain.recipepack.DuplicateChoice
import com.lechugones.cocina.domain.recipepack.ImportCandidate
import com.lechugones.cocina.domain.recipepack.IngredientDraft
import com.lechugones.cocina.domain.recipepack.LinkDraft
import com.lechugones.cocina.domain.recipepack.PackIssue
import com.lechugones.cocina.domain.recipepack.PackReadResult
import com.lechugones.cocina.domain.recipepack.RecipePackReader
import com.lechugones.cocina.provider.ProviderProduct
import com.lechugones.cocina.ui.ImportSource
import com.lechugones.cocina.ui.IncomingImports
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import javax.inject.Inject

/** One recipe of the pack in the preview, with the user's selection and duplicate choice. */
data class CandidateRow(
    val candidate: ImportCandidate,
    val selected: Boolean,
    val choice: DuplicateChoice?,
) {
    /** Would actually be saved on "Importar". */
    val willImport: Boolean get() = selected && candidate.canImport && choice != DuplicateChoice.SKIP
}

sealed interface ImportUiState {
    data object Idle : ImportUiState

    data object Loading : ImportUiState

    data object ReadFailed : ImportUiState

    data object IsBackup : ImportUiState

    data class Rejected(
        val issues: List<PackIssue>,
    ) : ImportUiState

    data class Preview(
        val rows: List<CandidateRow>,
        /** Pack-level notes such as ignored files. */
        val packIssues: List<PackIssue>,
        val images: Map<String, ByteArray>,
        /** Row waiting for "¿Reemplazar tu versión?". */
        val confirmReplace: Int? = null,
    ) : ImportUiState

    /** Looking for product suggestions (SPEC § 4.3, step 5). */
    data object Matching : ImportUiState

    /** Product suggestions for the ingredients of the selected recipes, before saving. */
    data class Suggestions(
        val preview: Preview,
        val providerId: String,
        val providerName: String,
        val items: List<MatchItem>,
        /** No catalogue cached for the provider: only "Importar sin vincular" makes sense. */
        val catalogueEmpty: Boolean,
        /** Item whose "Ver más" list is open. */
        val choosing: Int? = null,
    ) : ImportUiState

    data object Saving : ImportUiState

    data class Done(
        val imported: Int,
        val skipped: Int,
    ) : ImportUiState
}

/** One ingredient in the suggestions step, with its candidates and the chosen product. */
data class MatchItem(
    val row: Int,
    val ingredient: Int,
    val recipeTitle: String,
    val ingredientName: String,
    val options: List<ProviderProduct>,
    val selected: ProviderProduct?,
)

/** Import pipeline of SPEC § 4.3: read → validate → preview → duplicates → match → save → summary. */
@HiltViewModel
class ImportViewModel
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        incoming: IncomingImports,
        private val library: RecipeLibrary,
        private val linker: ProductLinker,
        private val catalogue: CatalogueRepository,
        private val settings: SettingsRepository,
    ) : ViewModel() {
        private val state = MutableStateFlow<ImportUiState>(ImportUiState.Idle)
        val uiState: StateFlow<ImportUiState> = state.asStateFlow()

        init {
            incoming.consume()?.let(::load)
        }

        fun load(source: ImportSource) {
            state.value = ImportUiState.Loading
            viewModelScope.launch {
                val result =
                    try {
                        withContext(Dispatchers.IO) { read(source) }
                    } catch (e: IOException) {
                        null
                    } catch (e: SecurityException) {
                        null
                    }
                state.value =
                    when {
                        result == null -> {
                            ImportUiState.ReadFailed
                        }

                        result.isBackup -> {
                            ImportUiState.IsBackup
                        }

                        result.documents.isEmpty() -> {
                            ImportUiState.Rejected(result.issues)
                        }

                        else -> {
                            ImportUiState.Preview(
                                rows =
                                    library
                                        .planImport(
                                            result,
                                        ).map { CandidateRow(it, selected = it.canImport, choice = it.defaultChoice) },
                                packIssues = result.issues,
                                images = result.images,
                            )
                        }
                    }
            }
        }

        fun toggle(index: Int) = updateRow(index) { it.copy(selected = !it.selected) }

        fun choose(
            index: Int,
            choice: DuplicateChoice,
        ) {
            val preview = state.value as? ImportUiState.Preview ?: return
            val row = preview.rows.getOrNull(index) ?: return
            if (choice == DuplicateChoice.REPLACE && row.candidate.replaceNeedsConfirmation && row.choice != DuplicateChoice.REPLACE) {
                state.value = preview.copy(confirmReplace = index)
            } else {
                updateRow(index) { it.copy(choice = choice) }
            }
        }

        fun confirmReplace(confirmed: Boolean) {
            val preview = state.value as? ImportUiState.Preview ?: return
            val index = preview.confirmReplace ?: return
            state.value = preview.copy(confirmReplace = null)
            if (confirmed) updateRow(index) { it.copy(choice = DuplicateChoice.REPLACE) }
        }

        /** "Importar" on the preview: computes product suggestions for the recipes that will be saved. */
        fun import() {
            val preview = state.value as? ImportUiState.Preview ?: return
            state.value = ImportUiState.Matching
            viewModelScope.launch {
                val providerId = settings.settings.first().primaryProvider
                val empty = catalogue.status(providerId).first().products == 0
                val items =
                    if (empty) {
                        emptyList()
                    } else {
                        preview.rows.flatMapIndexed { r, row ->
                            val draft = row.candidate.draft
                            if (!row.willImport || draft == null) return@flatMapIndexed emptyList()
                            draft.ingredients.mapIndexed { i, ingredient ->
                                val match = linker.match(ingredient.toIngredient()).firstOrNull { it.providerId == providerId }
                                val options =
                                    match
                                        ?.result
                                        ?.suggestions
                                        .orEmpty()
                                        .take(
                                            MAX_OPTIONS,
                                        ).mapNotNull { match?.products?.get(it.product.key) }
                                val selected = match?.result?.preselected?.let { match.products[it.product.key] }
                                MatchItem(r, i, draft.title, ingredient.name, options, selected)
                            }
                        }
                    }
                state.value =
                    ImportUiState.Suggestions(
                        preview = preview,
                        providerId = providerId,
                        providerName = catalogue.provider(providerId)?.displayName.orEmpty(),
                        items = items,
                        catalogueEmpty = empty,
                    )
            }
        }

        /** "Aceptar todo": saves with every selected suggestion in one tap (AC3.5). */
        fun acceptAll() {
            val suggestions = state.value as? ImportUiState.Suggestions ?: return
            save(suggestions.preview, withLinks(suggestions))
        }

        /** "Importar sin vincular": saves without suggestions (links that came in the file stay). */
        fun importWithoutLinks() {
            val suggestions = state.value as? ImportUiState.Suggestions ?: return
            save(suggestions.preview, suggestions.preview.rows)
        }

        fun backToPreview() {
            val suggestions = state.value as? ImportUiState.Suggestions ?: return
            state.value = suggestions.preview
        }

        fun openChooser(item: Int?) {
            state.update { if (it is ImportUiState.Suggestions) it.copy(choosing = item) else it }
        }

        /** Picks [product] for an item; null leaves the ingredient unlinked. */
        fun select(
            item: Int,
            product: ProviderProduct?,
        ) {
            state.update { current ->
                if (current !is ImportUiState.Suggestions) return@update current
                current.copy(
                    items = current.items.mapIndexed { i, it -> if (i == item) it.copy(selected = product) else it },
                    choosing = null,
                )
            }
        }

        /** The preview rows with each chosen product written into the drafts as the provider's preferred link. */
        private fun withLinks(suggestions: ImportUiState.Suggestions): List<CandidateRow> {
            val byIngredient = suggestions.items.associateBy { it.row to it.ingredient }
            return suggestions.preview.rows.mapIndexed { r, row ->
                val draft = row.candidate.draft ?: return@mapIndexed row
                val ingredients =
                    draft.ingredients.mapIndexed { i, ingredient ->
                        val item = byIngredient[r to i] ?: return@mapIndexed ingredient
                        val others = ingredient.links.filter { it.provider != suggestions.providerId }
                        val chosen =
                            item.selected?.let {
                                LinkDraft(suggestions.providerId, it.product.key.productId, it.product.name, preferred = true)
                            }
                        ingredient.copy(links = others + listOfNotNull(chosen))
                    }
                row.copy(candidate = row.candidate.copy(draft = draft.copy(ingredients = ingredients)))
            }
        }

        private fun save(
            preview: ImportUiState.Preview,
            rows: List<CandidateRow>,
        ) {
            state.value = ImportUiState.Saving
            viewModelScope.launch {
                val chosen = rows.filter { it.selected && it.candidate.canImport }
                val outcome = library.import(chosen.map { it.candidate to it.choice }, preview.images)
                state.value = ImportUiState.Done(outcome.imported.size, outcome.skipped + (rows.size - chosen.size))
            }
        }

        private fun IngredientDraft.toIngredient() =
            Ingredient(
                id = "",
                name = name,
                quantity = quantity,
                unit = unit,
                match = match,
                links = links.map { IngredientLink(ProductKey(it.provider, it.productId), it.preferred) },
            )

        private fun updateRow(
            index: Int,
            transform: (CandidateRow) -> CandidateRow,
        ) {
            state.update { current ->
                if (current !is ImportUiState.Preview) return@update current
                current.copy(rows = current.rows.mapIndexed { i, row -> if (i == index) transform(row) else row })
            }
        }

        /** Reads every input and merges them into one result. */
        private fun read(source: ImportSource): PackReadResult =
            when (source) {
                is ImportSource.Text -> {
                    RecipePackReader.readText(source.text, "")
                }

                is ImportSource.Files -> {
                    val results =
                        source.uris.map { uri ->
                            val stream = context.contentResolver.openInputStream(uri) ?: throw IOException("Cannot open $uri")
                            stream.use { RecipePackReader.read(it, displayName(uri)) }
                        }
                    PackReadResult(
                        documents = results.flatMap { it.documents },
                        images = results.fold(emptyMap()) { all, result -> all + result.images },
                        issues = results.flatMap { it.issues },
                    )
                }
            }

        private fun displayName(uri: Uri): String =
            context.contentResolver
                .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
                ?: uri.lastPathSegment.orEmpty()

        private companion object {
            const val MAX_OPTIONS = 8
        }
    }
