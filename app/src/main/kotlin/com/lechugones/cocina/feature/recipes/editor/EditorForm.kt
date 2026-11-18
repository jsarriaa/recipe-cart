package com.lechugones.cocina.feature.recipes.editor

import com.lechugones.cocina.domain.quantity.Amount
import com.lechugones.cocina.domain.quantity.PackageSize
import com.lechugones.cocina.domain.quantity.QuantityParseResult
import com.lechugones.cocina.domain.quantity.QuantityParser
import com.lechugones.cocina.domain.recipe.Ingredient
import com.lechugones.cocina.domain.recipe.IngredientLink
import com.lechugones.cocina.domain.recipe.MatchHints
import com.lechugones.cocina.domain.recipe.Recipe
import com.lechugones.cocina.domain.recipe.RecipeOrigin
import com.lechugones.cocina.domain.recipepack.FrontMatter
import com.lechugones.cocina.domain.recipepack.IngredientDraft
import com.lechugones.cocina.domain.recipepack.IssueCode
import com.lechugones.cocina.domain.recipepack.PackIssue
import com.lechugones.cocina.domain.recipepack.RecipeDraft
import com.lechugones.cocina.domain.recipepack.RecipeDraftValidator
import com.lechugones.cocina.domain.units.MeasureUnit
import java.time.Instant
import java.util.Locale
import java.util.UUID

/** One ingredient as typed in the editor. [key] identifies the row; [id] is the stored id. */
data class IngredientForm(
    val key: String = UUID.randomUUID().toString(),
    val id: String? = null,
    val name: String = "",
    val quantity: String = "",
    val unit: MeasureUnit = MeasureUnit.G,
    val unitWeight: String = "",
    val note: String = "",
    val group: String = "",
    val optional: Boolean = false,
    val pantry: Boolean = false,
    val matchQuery: String = "",
    val matchPackage: String = "",
    val storeBrandOk: Boolean = true,
    /** Product links are kept as they are; they are edited from phase 3. */
    val links: List<IngredientLink> = emptyList(),
)

/** The whole recipe as typed in the editor. */
data class RecipeForm(
    val title: String = "",
    val description: String = "",
    val servings: String = "2",
    val prep: String = "",
    val cook: String = "",
    val tags: String = "",
    val source: String = "",
    val body: String = "",
    /** Stored photo file name. */
    val photo: String? = null,
    val ingredients: List<IngredientForm> = emptyList(),
)

/**
 * Converts between [Recipe] and the editor form, and validates the form with the same rules as
 * recipe packs ([RecipeDraftValidator], RECIPE_FORMAT § 4), so both paths accept the same data.
 */
object EditorForms {
    private val WHITESPACE = Regex("\\s+")
    private val INTEGER = Regex("^\\d{1,6}$")

    fun fromRecipe(recipe: Recipe) =
        RecipeForm(
            title = recipe.title,
            description = recipe.description.orEmpty(),
            servings = recipe.servings.toString(),
            prep = recipe.prepMinutes?.toString().orEmpty(),
            cook = recipe.cookMinutes?.toString().orEmpty(),
            tags = recipe.tags.joinToString(", "),
            source = recipe.source.orEmpty(),
            body = recipe.bodyMarkdown,
            photo = recipe.imagePath,
            ingredients = recipe.ingredients.map(::fromIngredient),
        )

    fun fromIngredient(ingredient: Ingredient) =
        IngredientForm(
            id = ingredient.id,
            name = ingredient.name,
            quantity = ingredient.quantity?.let(::editable).orEmpty(),
            unit = ingredient.unit,
            unitWeight = ingredient.unitWeightG?.let(::editable).orEmpty(),
            note = ingredient.note.orEmpty(),
            group = ingredient.group.orEmpty(),
            optional = ingredient.optional,
            pantry = ingredient.pantryStaple,
            matchQuery = ingredient.match?.query.orEmpty(),
            matchPackage =
                ingredient.match
                    ?.packageSize
                    ?.canonical()
                    .orEmpty(),
            storeBrandOk = ingredient.match?.storeBrandOk ?: true,
            links = ingredient.links,
        )

    /** Parsed draft plus every error (warnings are not shown in the editor). */
    data class Validation(
        val draft: RecipeDraft,
        val errors: List<PackIssue>,
    ) {
        val isValid: Boolean get() = errors.isEmpty()
    }

    fun validate(form: RecipeForm): Validation {
        val issues = mutableListOf<PackIssue>()
        val servings =
            form.servings
                .trim()
                .takeIf { INTEGER.matches(it) }
                ?.toInt()
        if (servings == null) issues += PackIssue(IssueCode.E06, "servings", form.servings)
        val prep = optionalInt(form.prep, "prep_minutes", issues)
        val cook = optionalInt(form.cook, "cook_minutes", issues)
        val ingredients = form.ingredients.mapIndexed { i, ingredient -> parseIngredient(ingredient, "ingredients[$i]", issues) }

        val draft =
            RecipeDraft(
                title = form.title.trim(),
                servings = servings ?: 1,
                ingredients = ingredients,
                description = form.description.trim().ifEmpty { null },
                prepMinutes = prep,
                cookMinutes = cook,
                tags = parseTags(form.tags),
                source = form.source.trim().ifEmpty { null },
                body = normalizeBody(form.body),
            )
        val parsedPaths = issues.map { it.path }.toSet()
        val ruleErrors = RecipeDraftValidator.validate(draft).issues.filter { it.isError && it.path !in parsedPaths }
        return Validation(draft, issues + ruleErrors)
    }

    /** Errors of a single ingredient, as shown in the ingredient sheet. */
    fun validateIngredient(form: IngredientForm): List<PackIssue> {
        val issues = mutableListOf<PackIssue>()
        val draft = parseIngredient(form, "ingredients[0]", issues)
        val parsedPaths = issues.map { it.path }.toSet()
        val probe = RecipeDraft(title = "x", servings = 1, ingredients = listOf(draft), body = "")
        return issues +
            RecipeDraftValidator
                .validate(probe)
                .issues
                .filter { it.isError && it.path.startsWith("ingredients[0]") && it.path !in parsedPaths }
    }

    /** Builds the recipe to save. [original] keeps ids, origin and dates of an edited recipe. */
    fun toRecipe(
        form: RecipeForm,
        draft: RecipeDraft,
        original: Recipe?,
        now: Instant,
        newId: () -> String = { UUID.randomUUID().toString() },
    ): Recipe =
        Recipe(
            id = original?.id ?: newId(),
            uid = original?.uid ?: newId(),
            title = draft.title,
            servings = draft.servings,
            ingredients =
                form.ingredients.zip(draft.ingredients).map { (row, parsed) ->
                    Ingredient(
                        id = row.id ?: newId(),
                        name = parsed.name,
                        quantity = parsed.quantity,
                        unit = parsed.unit,
                        unitWeightG = parsed.unitWeightG,
                        note = parsed.note,
                        group = parsed.group,
                        optional = parsed.optional,
                        pantryStaple = parsed.pantry,
                        match = parsed.match,
                        links = row.links,
                    )
                },
            createdAt = original?.createdAt ?: now,
            updatedAt = original?.updatedAt ?: now,
            description = draft.description,
            prepMinutes = draft.prepMinutes,
            cookMinutes = draft.cookMinutes,
            tags = draft.tags,
            imagePath = form.photo,
            source = draft.source,
            bodyMarkdown = draft.body,
            origin = original?.origin ?: RecipeOrigin.CREATED,
            importedAt = original?.importedAt,
            editedAfterImport = original?.editedAfterImport ?: false,
        )

    /**
     * A number to edit: decimal comma, no thousands separator (`12500`, `1,5`), so that
     * [QuantityParser] reads it back unchanged. Display formatting would turn 12500 into `12.500`.
     */
    fun editable(amount: Amount): String = amount.toString().replace('.', ',')

    fun parseTags(text: String): List<String> =
        text
            .split(',')
            .map { it.trim().lowercase(Locale.ROOT) }
            .filter { it.isNotEmpty() }
            .distinct()

    private fun normalizeBody(body: String): String = FrontMatter.trimBlankLines(FrontMatter.normalize(body).split('\n'))

    private fun optionalInt(
        text: String,
        path: String,
        issues: MutableList<PackIssue>,
    ): Int? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        return trimmed.takeIf { INTEGER.matches(it) }?.toInt() ?: run {
            issues += PackIssue(IssueCode.E06, path, text)
            null
        }
    }

    private fun parseIngredient(
        form: IngredientForm,
        path: String,
        issues: MutableList<PackIssue>,
    ): IngredientDraft {
        val quantityText = form.quantity.trim()
        val quantity =
            if (quantityText.isEmpty() || form.unit == MeasureUnit.AL_GUSTO) {
                null
            } else {
                when (val result = QuantityParser.parse(quantityText)) {
                    is QuantityParseResult.Valid -> result.amount
                    is QuantityParseResult.Invalid -> null.also { issues += PackIssue(IssueCode.E10, "$path.quantity", quantityText) }
                }
            }
        val weightText = form.unitWeight.trim()
        val unitWeight =
            if (weightText.isEmpty()) {
                null
            } else {
                when (val result = QuantityParser.parse(weightText)) {
                    is QuantityParseResult.Valid -> result.amount
                    is QuantityParseResult.Invalid -> null.also { issues += PackIssue(IssueCode.E07, "$path.unit_weight_g", weightText) }
                }
            }
        val query = form.matchQuery.trim()
        val packageText = form.matchPackage.trim()
        val packageSize = packageText.takeIf { it.isNotEmpty() }?.let { PackageSize.parse(it) }
        if (packageText.isNotEmpty() && packageSize == null) issues += PackIssue(IssueCode.E11, "$path.match.package_size", packageText)
        if (query.isEmpty() && packageText.isNotEmpty()) issues += PackIssue(IssueCode.E05, "$path.match.query")
        val match = if (query.isEmpty()) null else MatchHints(query, packageSize, form.storeBrandOk)

        return IngredientDraft(
            name = form.name.trim().replace(WHITESPACE, " "),
            quantity = quantity,
            unit = form.unit,
            unitWeightG = unitWeight,
            note = form.note.trim().ifEmpty { null },
            group = form.group.trim().ifEmpty { null },
            optional = form.optional,
            pantry = form.pantry,
            match = match,
            links = emptyList(),
        )
    }
}
