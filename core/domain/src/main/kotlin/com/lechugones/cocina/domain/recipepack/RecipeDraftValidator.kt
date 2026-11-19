package com.lechugones.cocina.domain.recipepack

import com.lechugones.cocina.domain.recipe.NameKey
import java.math.BigDecimal

/**
 * Range and consistency rules of RECIPE_FORMAT § 4 and § 9 on an already typed [RecipeDraft].
 * Used by the document parser and by the recipe editor, so both apply the same limits.
 */
object RecipeDraftValidator {
    data class Result(
        /** The draft with automatic fixes applied (only the first preferred link per provider stays). */
        val draft: RecipeDraft,
        val issues: List<PackIssue>,
    ) {
        val hasErrors: Boolean get() = issues.any { it.isError }
    }

    private val HEADING = Regex("""^##\s+(.+?)\s*#*\s*$""")

    fun validate(draft: RecipeDraft): Result {
        val issues = mutableListOf<PackIssue>()

        fun add(
            code: IssueCode,
            path: String,
            value: String? = null,
        ) {
            issues += PackIssue(code, path, value)
        }

        draft.uid?.let { if (!RecipeRules.UID.matches(it)) add(IssueCode.E07, "uid", it) }
        if (draft.title.isEmpty() || draft.title.length > RecipeRules.TITLE_MAX) add(IssueCode.E07, "title")
        draft.description?.let { if (it.length > RecipeRules.DESCRIPTION_MAX) add(IssueCode.E07, "description") }
        if (draft.servings !in RecipeRules.SERVINGS) add(IssueCode.E07, "servings", draft.servings.toString())
        draft.prepMinutes?.let { if (it !in RecipeRules.MINUTES) add(IssueCode.E07, "prep_minutes", it.toString()) }
        draft.cookMinutes?.let { if (it !in RecipeRules.MINUTES) add(IssueCode.E07, "cook_minutes", it.toString()) }
        if (draft.tags.size > RecipeRules.TAGS_MAX) add(IssueCode.E07, "tags")
        draft.tags.forEachIndexed { i, tag ->
            if (tag.isEmpty() || tag.length > RecipeRules.TAG_MAX) add(IssueCode.E07, "tags[$i]", tag)
        }
        draft.source?.let { if (it.length > RecipeRules.SOURCE_MAX) add(IssueCode.E07, "source") }
        draft.author?.let { if (it.length > RecipeRules.AUTHOR_MAX) add(IssueCode.E07, "author") }
        if (draft.body.length > RecipeRules.BODY_MAX) add(IssueCode.E12, "body")
        if (draft.ingredients.size !in RecipeRules.INGREDIENTS) add(IssueCode.E07, "ingredients")

        val seen = mutableSetOf<Triple<String?, String, String>>()
        val ingredients =
            draft.ingredients.mapIndexed { i, ingredient ->
                val path = "ingredients[$i]"
                if (ingredient.name.isEmpty() || ingredient.name.length > RecipeRules.NAME_MAX) add(IssueCode.E07, "$path.name")
                if (ingredient.quantity == null && ingredient.unit.requiresQuantity) add(IssueCode.E09, "$path.quantity")
                ingredient.unitWeightG?.let { weight ->
                    val grams = weight.toBigDecimal()
                    if (grams <= BigDecimal.ZERO || grams > RecipeRules.UNIT_WEIGHT_MAX) {
                        add(IssueCode.E07, "$path.unit_weight_g", weight.toString())
                    }
                }
                ingredient.note?.let { if (it.length > RecipeRules.NOTE_MAX) add(IssueCode.E07, "$path.note") }
                ingredient.group?.let { if (it.length > RecipeRules.GROUP_MAX) add(IssueCode.E07, "$path.group") }
                ingredient.match?.let { match ->
                    if (match.query.length !in RecipeRules.QUERY_LENGTH) add(IssueCode.E07, "$path.match.query", match.query)
                }
                if (ingredient.match == null && !ingredient.pantry) add(IssueCode.W05, path)
                if (!seen.add(Triple(ingredient.group, NameKey.of(ingredient.name), ingredient.unit.code))) add(IssueCode.W11, path)

                ingredient.links.forEachIndexed { j, link ->
                    val linkPath = "$path.links[$j]"
                    if (!RecipeRules.PROVIDER.matches(link.provider)) add(IssueCode.E07, "$linkPath.provider", link.provider)
                    if (link.productId.length !in RecipeRules.PRODUCT_ID_LENGTH) add(IssueCode.E07, "$linkPath.product_id")
                    link.name?.let { if (it.length > RecipeRules.LINK_NAME_MAX) add(IssueCode.E07, "$linkPath.name") }
                }
                ingredient.copy(links = singlePreferredPerProvider(ingredient.links) { add(IssueCode.W08, "$path.links") })
            }

        if (!hasPreparationHeading(draft.body)) add(IssueCode.W09, "body")
        return Result(draft.copy(ingredients = ingredients), issues)
    }

    fun hasPreparationHeading(body: String): Boolean =
        body.lines().any { line ->
            HEADING
                .matchEntire(line.trimEnd())
                ?.groupValues
                ?.get(1)
                ?.let { NameKey.of(it) == "preparacion" } == true
        }

    private fun singlePreferredPerProvider(
        links: List<LinkDraft>,
        onExtra: () -> Unit,
    ): List<LinkDraft> {
        val providersWithPreferred = mutableSetOf<String>()
        var reported = false
        return links.map { link ->
            if (!link.preferred) return@map link
            if (providersWithPreferred.add(link.provider)) {
                link
            } else {
                if (!reported) onExtra().also { reported = true }
                link.copy(preferred = false)
            }
        }
    }
}
