package com.lechugones.cocina.data.recipe

import com.lechugones.cocina.data.image.RecipeImageStore
import com.lechugones.cocina.domain.recipe.Recipe
import com.lechugones.cocina.domain.recipe.RecipeOrigin
import com.lechugones.cocina.domain.recipe.RecipeSummary
import com.lechugones.cocina.domain.recipepack.DuplicateChoice
import com.lechugones.cocina.domain.recipepack.ExportImage
import com.lechugones.cocina.domain.recipepack.ExportItem
import com.lechugones.cocina.domain.recipepack.ImportCandidate
import com.lechugones.cocina.domain.recipepack.ImportPlanner
import com.lechugones.cocina.domain.recipepack.PackReadResult
import com.lechugones.cocina.domain.recipepack.RecipeDrafts
import com.lechugones.cocina.domain.recipepack.RecipeRules
import kotlinx.coroutines.flow.Flow
import java.time.Clock
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** Result of importing the selected candidates of a pack. */
data class ImportOutcome(
    val imported: List<String>,
    val skipped: Int,
)

/**
 * Recipe operations that touch both the database and photo files, so neither is left behind:
 * editor saves, delete, duplicate, import and export (SPEC § 4.2, § 4.3).
 */
@Singleton
class RecipeLibrary
    @Inject
    constructor(
        private val repository: RecipeRepository,
        private val images: RecipeImageStore,
        private val clock: Clock,
    ) {
        fun observeSummaries(): Flow<List<RecipeSummary>> = repository.observeSummaries()

        suspend fun get(id: String): Recipe? = repository.get(id)

        /**
         * Saves a recipe coming from the editor. [original] is the stored version (null for a new
         * recipe). An imported recipe that changed is marked as edited after import, and a
         * replaced or removed photo is deleted from disk.
         */
        suspend fun saveFromEditor(
            edited: Recipe,
            original: Recipe?,
        ): Recipe {
            val now = Instant.now(clock)
            val changed = original == null || edited.copy(updatedAt = original.updatedAt) != original
            val toSave =
                edited.copy(
                    createdAt = original?.createdAt ?: now,
                    updatedAt = if (changed) now else edited.updatedAt,
                    editedAfterImport = edited.editedAfterImport || (original?.origin == RecipeOrigin.IMPORTED && changed),
                )
            repository.save(toSave)
            if (original?.imagePath != null && original.imagePath != toSave.imagePath) images.delete(original.imagePath)
            return toSave
        }

        suspend fun delete(id: String) {
            val recipe = repository.get(id) ?: return
            repository.delete(id)
            images.delete(recipe.imagePath)
        }

        /** Copies a recipe with new ids and its own copy of the photo. [title] builds the new title. */
        suspend fun duplicate(
            id: String,
            title: (String) -> String,
        ): Recipe? {
            val recipe = repository.get(id) ?: return null
            val now = Instant.now(clock)
            val copy =
                recipe.copy(
                    id = newId(),
                    uid = newId(),
                    title = title(recipe.title).take(RecipeRules.TITLE_MAX),
                    ingredients = recipe.ingredients.map { it.copy(id = newId()) },
                    imagePath = recipe.imagePath?.let { images.copy(it) },
                    createdAt = now,
                    updatedAt = now,
                    origin = RecipeOrigin.CREATED,
                    importedAt = null,
                    editedAfterImport = false,
                )
            repository.save(copy)
            return copy
        }

        /** Preview candidates for a read pack. Links resolve against the catalogue from phase 3. */
        suspend fun planImport(pack: PackReadResult): List<ImportCandidate> = ImportPlanner.plan(pack, repository.existing()) { false }

        /** Imports [choices] (candidate → duplicate choice) and stores their photos. */
        suspend fun import(
            choices: List<Pair<ImportCandidate, DuplicateChoice?>>,
            packImages: Map<String, ByteArray>,
        ): ImportOutcome {
            val now = Instant.now(clock)
            val imported = mutableListOf<String>()
            var skipped = 0
            for ((candidate, choice) in choices) {
                val effective = if (candidate.duplicateOf == null) null else (choice ?: candidate.defaultChoice)
                val draft = candidate.draft
                if (draft == null || effective == DuplicateChoice.SKIP) {
                    skipped++
                    continue
                }
                val imagePath = draft.image?.let { packImages[it] }?.let { images.importBytes(it) }
                val recipe = ImportPlanner.toRecipe(candidate, choice, now, ::newId, imagePath)
                if (recipe == null) {
                    images.delete(imagePath)
                    skipped++
                    continue
                }
                val previous = repository.get(recipe.id)
                repository.save(recipe)
                if (previous?.imagePath != null && previous.imagePath != recipe.imagePath) images.delete(previous.imagePath)
                imported += recipe.id
            }
            return ImportOutcome(imported, skipped)
        }

        /** Export items for [ids] (all recipes when null), with their photos. */
        suspend fun exportItems(ids: List<String>?): List<ExportItem> {
            val recipes = if (ids == null) repository.getAll() else ids.mapNotNull { repository.get(it) }
            return recipes.map { recipe ->
                val photo = recipe.imagePath?.let { images.readBytes(it) }
                ExportItem(RecipeDrafts.fromRecipe(recipe), photo?.let { ExportImage(it, "jpg") })
            }
        }

        private fun newId(): String = UUID.randomUUID().toString()
    }
