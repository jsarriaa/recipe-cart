package com.lechugones.cocina.feature.importer

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lechugones.cocina.data.recipe.RecipeLibrary
import com.lechugones.cocina.domain.recipepack.DuplicateChoice
import com.lechugones.cocina.domain.recipepack.ImportCandidate
import com.lechugones.cocina.domain.recipepack.PackIssue
import com.lechugones.cocina.domain.recipepack.PackReadResult
import com.lechugones.cocina.domain.recipepack.RecipePackReader
import com.lechugones.cocina.ui.ImportSource
import com.lechugones.cocina.ui.IncomingImports
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

    data object Saving : ImportUiState

    data class Done(
        val imported: Int,
        val skipped: Int,
    ) : ImportUiState
}

/** Import pipeline of SPEC § 4.3: read → validate → preview → duplicates → save → summary. */
@HiltViewModel
class ImportViewModel
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        incoming: IncomingImports,
        private val library: RecipeLibrary,
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

        fun import() {
            val preview = state.value as? ImportUiState.Preview ?: return
            state.value = ImportUiState.Saving
            viewModelScope.launch {
                val chosen = preview.rows.filter { it.selected && it.candidate.canImport }
                val outcome = library.import(chosen.map { it.candidate to it.choice }, preview.images)
                state.value = ImportUiState.Done(outcome.imported.size, outcome.skipped + (preview.rows.size - chosen.size))
            }
        }

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
    }
