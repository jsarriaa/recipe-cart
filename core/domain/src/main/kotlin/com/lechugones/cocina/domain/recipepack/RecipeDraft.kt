package com.lechugones.cocina.domain.recipepack

import com.lechugones.cocina.domain.quantity.Amount
import com.lechugones.cocina.domain.recipe.MatchHints
import com.lechugones.cocina.domain.units.MeasureUnit
import java.math.BigDecimal

/**
 * A recipe as a `cpl-recipe/1` document describes it, before it gets app ids
 * (docs/RECIPE_FORMAT.md § 4). Strings are already trimmed.
 */
data class RecipeDraft(
    val title: String,
    val servings: Int,
    val ingredients: List<IngredientDraft>,
    val uid: String? = null,
    val description: String? = null,
    val prepMinutes: Int? = null,
    val cookMinutes: Int? = null,
    val tags: List<String> = emptyList(),
    /** Pack-relative image path, `images/<file>`. */
    val image: String? = null,
    val source: String? = null,
    /** Who wrote it: the name shown in the community recipe book ("por Joan"). */
    val author: String? = null,
    val body: String = "",
)

data class IngredientDraft(
    val name: String,
    val quantity: Amount?,
    val unit: MeasureUnit,
    val unitWeightG: Amount? = null,
    val note: String? = null,
    val group: String? = null,
    val optional: Boolean = false,
    val pantry: Boolean = false,
    val match: MatchHints? = null,
    val links: List<LinkDraft> = emptyList(),
)

/** A confirmed product link as written in a pack. [name] is a human-readable snapshot. */
data class LinkDraft(
    val provider: String,
    val productId: String,
    val name: String? = null,
    val preferred: Boolean = false,
)

/** Limits and patterns of the format, shared by the parser, the validator and the editor. */
object RecipeRules {
    const val FORMAT = "cpl-recipe/1"
    val UID = Regex("^[A-Za-z0-9-]{8,64}$")
    const val TITLE_MAX = 120
    const val DESCRIPTION_MAX = 500
    val SERVINGS = 1..50
    val MINUTES = 0..1440
    const val TAGS_MAX = 15
    const val TAG_MAX = 30
    const val SOURCE_MAX = 300
    const val AUTHOR_MAX = 60
    val INGREDIENTS = 1..60
    const val NAME_MAX = 80
    const val NOTE_MAX = 120
    const val GROUP_MAX = 40
    val UNIT_WEIGHT_MAX = BigDecimal(10_000)
    val QUERY_LENGTH = 2..80
    val PROVIDER = Regex("^[a-z][a-z0-9_]{1,31}$")
    val PRODUCT_ID_LENGTH = 1..64
    const val LINK_NAME_MAX = 200
    const val BODY_MAX = 20_000
    const val DOCUMENT_MAX_BYTES = 256 * 1024
    val IMAGE = Regex("^images/[A-Za-z0-9._-]+\\.(jpg|jpeg|png|webp)$", RegexOption.IGNORE_CASE)
}
