package com.lechugones.cocina.domain.recipepack

import com.lechugones.cocina.domain.product.ProductKey
import com.lechugones.cocina.domain.recipe.Ingredient
import com.lechugones.cocina.domain.recipe.IngredientLink
import com.lechugones.cocina.domain.recipe.NameKey
import com.lechugones.cocina.domain.recipe.Recipe
import com.lechugones.cocina.domain.recipe.RecipeOrigin
import java.time.Instant

/** What the importer needs to know about recipes already in the library. */
data class ExistingRecipe(
    val id: String,
    val uid: String,
    val title: String,
    val createdAt: Instant,
    val editedAfterImport: Boolean,
)

/** How to resolve a duplicate (SPEC § 4.3, step 4). */
enum class DuplicateChoice {
    /** Overwrite the existing recipe (same id). */
    REPLACE,

    /** "Guardar como copia": a new recipe with a new uid. */
    KEEP_BOTH,

    /** "Omitir" / "Mantener la mía". */
    SKIP,
}

/** One recipe of a pack, ready for the preview screen. */
data class ImportCandidate(
    val sourceName: String,
    /** Null when the document has errors and cannot be imported. */
    val draft: RecipeDraft?,
    val issues: List<PackIssue>,
    val duplicateOf: ExistingRecipe?,
    val defaultChoice: DuplicateChoice?,
) {
    val canImport: Boolean get() = draft != null

    /** Replacing this duplicate would discard edits made in the app: ask first. */
    val replaceNeedsConfirmation: Boolean get() = duplicateOf?.editedAfterImport == true
}

object ImportPlanner {
    /**
     * Turns parsed documents into preview candidates: marks duplicates (`W10`) with a safe
     * default choice, and drops links that cannot be resolved (`W07`).
     */
    fun plan(
        result: PackReadResult,
        existing: List<ExistingRecipe>,
        isLinkResolvable: (LinkDraft) -> Boolean,
    ): List<ImportCandidate> =
        result.documents.map { document ->
            val draft = document.parsed.draft
            if (draft == null) {
                return@map ImportCandidate(document.name, null, document.parsed.issues, null, null)
            }
            val issues = document.parsed.issues.toMutableList()
            val ingredients =
                draft.ingredients.mapIndexed { i, ingredient ->
                    val (kept, dropped) = ingredient.links.partition(isLinkResolvable)
                    dropped.forEach { issues += PackIssue(IssueCode.W07, "ingredients[$i].links", "${it.provider}:${it.productId}") }
                    ingredient.copy(links = kept)
                }
            val duplicate = findDuplicate(draft, existing)
            if (duplicate != null) issues += PackIssue(IssueCode.W10, "title", duplicate.title)
            ImportCandidate(
                sourceName = document.name,
                draft = draft.copy(ingredients = ingredients),
                issues = issues,
                duplicateOf = duplicate,
                defaultChoice = duplicate?.let { if (it.editedAfterImport) DuplicateChoice.SKIP else DuplicateChoice.REPLACE },
            )
        }

    /** Same uid first, otherwise the same normalised title. */
    fun findDuplicate(
        draft: RecipeDraft,
        existing: List<ExistingRecipe>,
    ): ExistingRecipe? {
        draft.uid?.let { uid -> existing.firstOrNull { it.uid == uid }?.let { return it } }
        val titleKey = NameKey.of(draft.title)
        return existing.firstOrNull { NameKey.of(it.title) == titleKey }
    }

    /**
     * Builds the recipe to save for [candidate]. Returns null for [DuplicateChoice.SKIP] or when
     * the candidate has errors. [imagePath] is the stored photo file, if any.
     */
    fun toRecipe(
        candidate: ImportCandidate,
        choice: DuplicateChoice?,
        now: Instant,
        newId: () -> String,
        imagePath: String?,
    ): Recipe? {
        val draft = candidate.draft ?: return null
        val duplicate = candidate.duplicateOf
        val effective = if (duplicate == null) null else (choice ?: candidate.defaultChoice)
        if (effective == DuplicateChoice.SKIP) return null
        val replacing = effective == DuplicateChoice.REPLACE && duplicate != null
        return RecipeDrafts.toRecipe(
            draft = draft,
            id = if (replacing) duplicate.id else newId(),
            uid =
                when {
                    effective == DuplicateChoice.KEEP_BOTH -> newId()
                    else -> draft.uid ?: duplicate?.uid ?: newId()
                },
            createdAt = if (replacing) duplicate.createdAt else now,
            now = now,
            newId = newId,
            imagePath = imagePath,
        )
    }
}

/** Conversions between stored [Recipe]s and pack [RecipeDraft]s. */
object RecipeDrafts {
    fun toRecipe(
        draft: RecipeDraft,
        id: String,
        uid: String,
        createdAt: Instant,
        now: Instant,
        newId: () -> String,
        imagePath: String?,
    ) = Recipe(
        id = id,
        uid = uid,
        title = draft.title,
        servings = draft.servings,
        ingredients =
            draft.ingredients.map { ingredient ->
                Ingredient(
                    id = newId(),
                    name = ingredient.name,
                    quantity = ingredient.quantity,
                    unit = ingredient.unit,
                    unitWeightG = ingredient.unitWeightG,
                    note = ingredient.note,
                    group = ingredient.group,
                    optional = ingredient.optional,
                    pantryStaple = ingredient.pantry,
                    match = ingredient.match,
                    links = ingredient.links.map { IngredientLink(ProductKey(it.provider, it.productId), it.preferred) },
                )
            },
        createdAt = createdAt,
        updatedAt = now,
        description = draft.description,
        prepMinutes = draft.prepMinutes,
        cookMinutes = draft.cookMinutes,
        tags = draft.tags,
        imagePath = imagePath,
        source = draft.source,
        author = draft.author,
        bodyMarkdown = draft.body,
        origin = RecipeOrigin.IMPORTED,
        importedAt = now,
        editedAfterImport = false,
    )

    /**
     * The draft to export for [recipe]. [linkNames] are product name snapshots; [image] is the
     * pack path the exporter will give the photo.
     */
    fun fromRecipe(
        recipe: Recipe,
        image: String? = null,
        linkNames: Map<ProductKey, String> = emptyMap(),
    ) = RecipeDraft(
        title = recipe.title,
        servings = recipe.servings,
        ingredients =
            recipe.ingredients.map { ingredient ->
                IngredientDraft(
                    name = ingredient.name,
                    quantity = ingredient.quantity,
                    unit = ingredient.unit,
                    unitWeightG = ingredient.unitWeightG,
                    note = ingredient.note,
                    group = ingredient.group,
                    optional = ingredient.optional,
                    pantry = ingredient.pantryStaple,
                    match = ingredient.match,
                    links =
                        ingredient.links.map {
                            LinkDraft(it.product.providerId, it.product.productId, linkNames[it.product], it.preferred)
                        },
                )
            },
        uid = recipe.uid,
        description = recipe.description,
        prepMinutes = recipe.prepMinutes,
        cookMinutes = recipe.cookMinutes,
        tags = recipe.tags,
        image = image,
        source = recipe.source,
        author = recipe.author,
        body = recipe.bodyMarkdown,
    )
}
