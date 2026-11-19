package com.lechugones.cocina.feature.community

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.lechugones.cocina.data.recipe.RecipeRepository
import com.lechugones.cocina.data.settings.SettingsRepository
import com.lechugones.cocina.domain.community.CommunityPublication
import com.lechugones.cocina.ui.navigation.RecipeDetailRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/** The "Publicar en la comunidad" dialogs on a recipe. */
sealed interface PublishUi {
    data object Hidden : PublishUi

    /** First time (or "Cambiar nombre"): the name shown as "por …". */
    data class AskName(
        val initial: String,
        val invalid: Boolean = false,
    ) : PublishUi

    data class Confirm(
        val title: String,
        val name: String,
    ) : PublishUi

    data object Publishing : PublishUi

    data class Done(
        val result: PublishResult,
    ) : PublishUi

    data class OtherAuthor(
        val author: String,
    ) : PublishUi
}

@HiltViewModel
class PublishViewModel
    @Inject
    constructor(
        savedStateHandle: SavedStateHandle,
        private val recipes: RecipeRepository,
        private val settings: SettingsRepository,
        private val book: CommunityBook,
    ) : ViewModel() {
        private val id = savedStateHandle.toRoute<RecipeDetailRoute>().id
        private val state = MutableStateFlow<PublishUi>(PublishUi.Hidden)
        val uiState: StateFlow<PublishUi> = state.asStateFlow()

        fun start() {
            viewModelScope.launch {
                val name = settings.settings.first().publisherName
                if (name == null) state.value = PublishUi.AskName("") else confirm(name)
            }
        }

        fun changeName() {
            viewModelScope.launch {
                state.value =
                    PublishUi.AskName(
                        settings.settings
                            .first()
                            .publisherName
                            .orEmpty(),
                    )
            }
        }

        fun saveName(input: String) {
            val name = CommunityPublication.cleanName(input)
            if (name == null) {
                state.value = PublishUi.AskName(input, invalid = true)
                return
            }
            viewModelScope.launch {
                settings.update { it.copy(publisherName = name) }
                confirm(name)
            }
        }

        private suspend fun confirm(name: String) {
            val recipe = recipes.get(id) ?: return dismiss()
            state.value =
                when (val prepared = CommunityPublication.prepare(recipe, name)) {
                    is CommunityPublication.Prepared.OtherAuthor -> PublishUi.OtherAuthor(prepared.author)
                    is CommunityPublication.Prepared.Ready -> PublishUi.Confirm(recipe.title, name)
                }
        }

        fun publish() {
            val confirm = state.value as? PublishUi.Confirm ?: return
            state.value = PublishUi.Publishing
            viewModelScope.launch {
                val recipe = recipes.get(id) ?: return@launch dismiss()
                val prepared = CommunityPublication.prepare(recipe, confirm.name)
                if (prepared !is CommunityPublication.Prepared.Ready) return@launch dismiss()
                val result = book.publish(prepared.text)
                // Signed recipes keep their author, so a later export or publication says "por …" too.
                if (result == PublishResult.Published && recipe.author == null) recipes.save(recipe.copy(author = confirm.name))
                state.value = PublishUi.Done(result)
            }
        }

        fun dismiss() {
            state.value = PublishUi.Hidden
        }
    }
