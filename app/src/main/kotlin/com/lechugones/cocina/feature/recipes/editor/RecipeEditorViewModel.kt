package com.lechugones.cocina.feature.recipes.editor

import android.content.Context
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.lechugones.cocina.R
import com.lechugones.cocina.data.image.RecipeImageStore
import com.lechugones.cocina.data.recipe.RecipeLibrary
import com.lechugones.cocina.domain.recipe.Recipe
import com.lechugones.cocina.domain.recipepack.PackIssue
import com.lechugones.cocina.ui.navigation.RecipeEditorRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Clock
import java.time.Instant
import javax.inject.Inject

/** The ingredient being edited in the bottom sheet; [index] is null for a new one. */
data class IngredientSheetState(
    val index: Int?,
    val form: IngredientForm,
    val errors: List<PackIssue> = emptyList(),
)

data class RecipeEditorUiState(
    val loading: Boolean = true,
    val isNew: Boolean = true,
    val form: RecipeForm = RecipeForm(),
    val errors: List<PackIssue> = emptyList(),
    val sheet: IngredientSheetState? = null,
    val saving: Boolean = false,
    val dirty: Boolean = false,
)

sealed interface RecipeEditorEvent {
    data class Saved(
        val id: String,
        val isNew: Boolean,
    ) : RecipeEditorEvent

    data object InvalidForm : RecipeEditorEvent

    data object PhotoFailed : RecipeEditorEvent

    data object SaveFailed : RecipeEditorEvent

    data object Discarded : RecipeEditorEvent
}

@HiltViewModel
class RecipeEditorViewModel
    @Inject
    constructor(
        savedStateHandle: SavedStateHandle,
        @ApplicationContext private val context: Context,
        private val library: RecipeLibrary,
        private val images: RecipeImageStore,
        private val clock: Clock,
    ) : ViewModel() {
        private val recipeId = savedStateHandle.toRoute<RecipeEditorRoute>().id
        private val state = MutableStateFlow(RecipeEditorUiState())
        val uiState: StateFlow<RecipeEditorUiState> = state.asStateFlow()
        private val eventChannel = Channel<RecipeEditorEvent>(Channel.BUFFERED)
        val events: Flow<RecipeEditorEvent> = eventChannel.receiveAsFlow()

        private var original: Recipe? = null
        private var initialForm = RecipeForm()

        /** Photos stored during this editing session; deleted unless the saved recipe uses them. */
        private val sessionPhotos = mutableSetOf<String>()

        init {
            viewModelScope.launch {
                val recipe = recipeId?.let { library.get(it) }
                original = recipe
                initialForm =
                    if (recipe != null) {
                        EditorForms.fromRecipe(recipe)
                    } else {
                        RecipeForm(body = context.getString(R.string.editor_body_template))
                    }
                state.value = RecipeEditorUiState(loading = false, isNew = recipe == null, form = initialForm)
            }
        }

        fun updateForm(transform: (RecipeForm) -> RecipeForm) {
            state.update { current ->
                val form = transform(current.form)
                current.copy(form = form, dirty = form != initialForm)
            }
        }

        // --- Photo ------------------------------------------------------------------------

        fun setPhoto(uri: Uri) {
            viewModelScope.launch {
                val stored = images.importUri(uri)
                if (stored == null) {
                    eventChannel.send(RecipeEditorEvent.PhotoFailed)
                    return@launch
                }
                sessionPhotos += stored
                val previous = state.value.form.photo
                updateForm { it.copy(photo = stored) }
                dropSessionPhoto(previous)
            }
        }

        fun removePhoto() {
            val previous = state.value.form.photo
            updateForm { it.copy(photo = null) }
            viewModelScope.launch { dropSessionPhoto(previous) }
        }

        // --- Ingredients ------------------------------------------------------------------

        fun openNewIngredient() {
            state.update { it.copy(sheet = IngredientSheetState(index = null, form = IngredientForm())) }
        }

        fun openIngredient(index: Int) {
            val form =
                state.value.form.ingredients
                    .getOrNull(index) ?: return
            state.update { it.copy(sheet = IngredientSheetState(index, form)) }
        }

        fun updateSheet(transform: (IngredientForm) -> IngredientForm) {
            state.update { current -> current.copy(sheet = current.sheet?.let { it.copy(form = transform(it.form)) }) }
        }

        fun closeSheet() {
            state.update { it.copy(sheet = null) }
        }

        /** Validates the sheet; on success puts the ingredient into the recipe and closes the sheet. */
        fun applySheet() {
            val sheet = state.value.sheet ?: return
            val errors = EditorForms.validateIngredient(sheet.form)
            if (errors.isNotEmpty()) {
                state.update { it.copy(sheet = sheet.copy(errors = errors)) }
                return
            }
            updateForm { form ->
                val ingredients = form.ingredients.toMutableList()
                if (sheet.index == null) ingredients += sheet.form else ingredients[sheet.index] = sheet.form
                form.copy(ingredients = ingredients)
            }
            state.update { it.copy(sheet = null, errors = emptyList()) }
        }

        fun deleteSheetIngredient() {
            val index = state.value.sheet?.index
            if (index != null) updateForm { it.copy(ingredients = it.ingredients.filterIndexed { i, _ -> i != index }) }
            closeSheet()
        }

        fun moveIngredient(
            index: Int,
            delta: Int,
        ) {
            updateForm { form ->
                val target = index + delta
                if (target !in form.ingredients.indices) return@updateForm form
                val ingredients = form.ingredients.toMutableList()
                ingredients.add(target, ingredients.removeAt(index))
                form.copy(ingredients = ingredients)
            }
        }

        // --- Save / discard ---------------------------------------------------------------

        fun save() {
            val current = state.value
            if (current.saving) return
            val validation = EditorForms.validate(current.form)
            if (!validation.isValid) {
                state.update { it.copy(errors = validation.errors) }
                viewModelScope.launch { eventChannel.send(RecipeEditorEvent.InvalidForm) }
                return
            }
            state.update { it.copy(saving = true, errors = emptyList()) }
            viewModelScope.launch {
                try {
                    val recipe = EditorForms.toRecipe(current.form, validation.draft, original, Instant.now(clock))
                    val saved = library.saveFromEditor(recipe, original)
                    sessionPhotos.filter { it != saved.imagePath }.forEach { images.delete(it) }
                    sessionPhotos.clear()
                    eventChannel.send(RecipeEditorEvent.Saved(saved.id, isNew = original == null))
                } catch (e: IllegalArgumentException) {
                    state.update { it.copy(saving = false) }
                    eventChannel.send(RecipeEditorEvent.SaveFailed)
                }
            }
        }

        fun discard() {
            viewModelScope.launch {
                withContext(NonCancellable) {
                    sessionPhotos.forEach { images.delete(it) }
                    sessionPhotos.clear()
                }
                eventChannel.send(RecipeEditorEvent.Discarded)
            }
        }

        /** Deletes [name] if it is a photo from this session that the form no longer uses. */
        private suspend fun dropSessionPhoto(name: String?) {
            if (name != null && name in sessionPhotos && name != state.value.form.photo) {
                sessionPhotos -= name
                images.delete(name)
            }
        }
    }
