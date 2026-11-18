package com.lechugones.cocina.domain.recipe

import com.lechugones.cocina.domain.product.ProductKey
import com.lechugones.cocina.domain.quantity.Amount
import com.lechugones.cocina.domain.quantity.PackageSize
import com.lechugones.cocina.domain.units.MeasureUnit
import com.lechugones.cocina.domain.units.UnitWeight
import java.time.Instant

/** A recipe as the app stores it. Field meanings follow docs/RECIPE_FORMAT.md § 4. */
data class Recipe(
    val id: String,
    val uid: String,
    val title: String,
    val servings: Int,
    val ingredients: List<Ingredient>,
    val createdAt: Instant,
    val updatedAt: Instant,
    val description: String? = null,
    val prepMinutes: Int? = null,
    val cookMinutes: Int? = null,
    val tags: List<String> = emptyList(),
    val imagePath: String? = null,
    val source: String? = null,
    val bodyMarkdown: String = "",
) {
    init {
        require(servings > 0) { "servings must be positive: $servings" }
    }
}

/** The fields shown in recipe lists. */
data class RecipeSummary(
    val id: String,
    val title: String,
    val servings: Int,
)

data class Ingredient(
    val id: String,
    val name: String,
    val quantity: Amount?,
    val unit: MeasureUnit,
    val unitWeightG: Amount? = null,
    val note: String? = null,
    val group: String? = null,
    val optional: Boolean = false,
    val pantryStaple: Boolean = false,
    val match: MatchHints? = null,
    val links: List<IngredientLink> = emptyList(),
) {
    /** Normalised name used for link memory and pantry matching. */
    val nameKey: String get() = NameKey.of(name)

    val unitWeight: UnitWeight? get() = unitWeightG?.let { UnitWeight(unit, it.toBigDecimal()) }
}

/** Product-matching hints carried by recipe packs. */
data class MatchHints(
    val query: String,
    val packageSize: PackageSize? = null,
    val storeBrandOk: Boolean = true,
)

/** A confirmed link from an ingredient to a product. At most one preferred link per provider. */
data class IngredientLink(
    val product: ProductKey,
    val preferred: Boolean = false,
)
