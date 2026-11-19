package com.lechugones.cocina.domain.recipe

import com.lechugones.cocina.domain.money.Money
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
    val origin: RecipeOrigin = RecipeOrigin.CREATED,
    /** When the recipe was last imported from a pack (null for recipes created in the app). */
    val importedAt: Instant? = null,
    /** An imported recipe was changed in the app afterwards: re-imports must not overwrite it silently. */
    val editedAfterImport: Boolean = false,
) {
    init {
        require(servings > 0) { "servings must be positive: $servings" }
    }
}

/** Where a recipe came from (SPEC § 4.2, "Everything is editable"). */
enum class RecipeOrigin(
    val code: String,
) {
    CREATED("created"),
    IMPORTED("imported"),
    ;

    companion object {
        fun fromCode(code: String): RecipeOrigin = entries.firstOrNull { it.code == code } ?: CREATED
    }
}

/** The fields shown in recipe lists, plus what list search and filters need. */
data class RecipeSummary(
    val id: String,
    val title: String,
    val servings: Int,
    val imagePath: String? = null,
    val tags: List<String> = emptyList(),
    /** [NameKey]s of the ingredient names, for search. */
    val ingredientKeys: Set<String> = emptySet(),
    val createdAt: Instant = Instant.EPOCH,
    /** Cost per serving at the primary supermarket; null when no ingredient has a price. */
    val costPerServing: Money? = null,
    /** Some counted ingredient has no price: [costPerServing] is a lower bound ("≥"). */
    val costIsLowerBound: Boolean = false,
    /** Last "Lo he cocinado" that was not undone. */
    val lastCookedAt: Instant? = null,
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
