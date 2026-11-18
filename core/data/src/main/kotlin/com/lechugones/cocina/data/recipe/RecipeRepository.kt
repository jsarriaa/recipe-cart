package com.lechugones.cocina.data.recipe

import com.lechugones.cocina.data.db.IngredientEntity
import com.lechugones.cocina.data.db.RecipeDao
import com.lechugones.cocina.data.db.RecipeEntity
import com.lechugones.cocina.data.db.RecipeTagEntity
import com.lechugones.cocina.domain.quantity.Amount
import com.lechugones.cocina.domain.quantity.PackageSize
import com.lechugones.cocina.domain.recipe.Ingredient
import com.lechugones.cocina.domain.recipe.MatchHints
import com.lechugones.cocina.domain.recipe.Recipe
import com.lechugones.cocina.domain.recipe.RecipeSummary
import com.lechugones.cocina.domain.units.MeasureUnit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Instant
import javax.inject.Inject

interface RecipeRepository {
    fun observeSummaries(): Flow<List<RecipeSummary>>

    suspend fun get(id: String): Recipe?

    /** Inserts or fully replaces [recipe]. */
    suspend fun save(recipe: Recipe)

    suspend fun delete(id: String)
}

/**
 * Room-backed [RecipeRepository]. Ingredient product links are not stored yet: they arrive with
 * schema v2 in phase 3, so [get] returns ingredients without links.
 */
class RoomRecipeRepository
    @Inject
    constructor(
        private val dao: RecipeDao,
    ) : RecipeRepository {
        override fun observeSummaries(): Flow<List<RecipeSummary>> =
            dao.observeSummaries().map { rows -> rows.map { RecipeSummary(it.id, it.title, it.servings) } }

        override suspend fun get(id: String): Recipe? {
            val recipe = dao.getRecipe(id) ?: return null
            return recipe.toDomain(
                tags = dao.getTags(id),
                ingredients = dao.getIngredients(id).map { it.toDomain() },
            )
        }

        override suspend fun save(recipe: Recipe) {
            dao.upsertFull(
                recipe = recipe.toEntity(),
                tags = recipe.tags.distinct().map { RecipeTagEntity(recipe.id, it) },
                ingredients = recipe.ingredients.mapIndexed { index, ingredient -> ingredient.toEntity(recipe.id, index) },
            )
        }

        override suspend fun delete(id: String) = dao.delete(id)
    }

internal fun Recipe.toEntity() =
    RecipeEntity(
        id = id,
        uid = uid,
        title = title,
        description = description,
        servings = servings,
        prepMinutes = prepMinutes,
        cookMinutes = cookMinutes,
        imagePath = imagePath,
        source = source,
        bodyMarkdown = bodyMarkdown,
        createdAt = createdAt.toEpochMilli(),
        updatedAt = updatedAt.toEpochMilli(),
    )

internal fun RecipeEntity.toDomain(
    tags: List<String>,
    ingredients: List<Ingredient>,
) = Recipe(
    id = id,
    uid = uid,
    title = title,
    servings = servings,
    ingredients = ingredients,
    createdAt = Instant.ofEpochMilli(createdAt),
    updatedAt = Instant.ofEpochMilli(updatedAt),
    description = description,
    prepMinutes = prepMinutes,
    cookMinutes = cookMinutes,
    tags = tags,
    imagePath = imagePath,
    source = source,
    bodyMarkdown = bodyMarkdown,
)

internal fun Ingredient.toEntity(
    recipeId: String,
    position: Int,
) = IngredientEntity(
    id = id,
    recipeId = recipeId,
    position = position,
    groupLabel = group,
    name = name,
    nameKey = nameKey,
    quantityMilli = quantity?.milli,
    unit = unit.code,
    note = note,
    optional = optional,
    pantryStaple = pantryStaple,
    unitWeightGMilli = unitWeightG?.milli,
    matchQuery = match?.query,
    matchPackage = match?.packageSize?.canonical(),
    matchStoreBrandOk = match?.storeBrandOk ?: true,
)

internal fun IngredientEntity.toDomain() =
    Ingredient(
        id = id,
        name = name,
        quantity = quantityMilli?.let(::Amount),
        unit = requireNotNull(MeasureUnit.fromCode(unit)) { "Unknown unit code '$unit' in ingredient $id" },
        unitWeightG = unitWeightGMilli?.let(::Amount),
        note = note,
        group = groupLabel,
        optional = optional,
        pantryStaple = pantryStaple,
        match =
            matchQuery?.let { query ->
                MatchHints(
                    query = query,
                    packageSize = matchPackage?.let(PackageSize::parse),
                    storeBrandOk = matchStoreBrandOk,
                )
            },
    )
