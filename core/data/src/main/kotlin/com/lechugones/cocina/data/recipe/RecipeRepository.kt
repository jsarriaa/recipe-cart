package com.lechugones.cocina.data.recipe

import com.lechugones.cocina.data.db.IngredientEntity
import com.lechugones.cocina.data.db.IngredientLinkEntity
import com.lechugones.cocina.data.db.RecipeDao
import com.lechugones.cocina.data.db.RecipeEntity
import com.lechugones.cocina.data.db.RecipeTagEntity
import com.lechugones.cocina.domain.product.ProductKey
import com.lechugones.cocina.domain.quantity.Amount
import com.lechugones.cocina.domain.quantity.PackageSize
import com.lechugones.cocina.domain.recipe.Ingredient
import com.lechugones.cocina.domain.recipe.IngredientLink
import com.lechugones.cocina.domain.recipe.MatchHints
import com.lechugones.cocina.domain.recipe.Recipe
import com.lechugones.cocina.domain.recipe.RecipeOrigin
import com.lechugones.cocina.domain.recipe.RecipeSummary
import com.lechugones.cocina.domain.recipepack.ExistingRecipe
import com.lechugones.cocina.domain.units.MeasureUnit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Instant
import javax.inject.Inject

interface RecipeRepository {
    /** Every recipe with what the list needs (photo, tags, ingredient keys), unsorted. */
    fun observeSummaries(): Flow<List<RecipeSummary>>

    suspend fun get(id: String): Recipe?

    /** All recipes, alphabetically (for "Exportar todas"). */
    suspend fun getAll(): List<Recipe>

    /** Inserts or fully replaces [recipe]. */
    suspend fun save(recipe: Recipe)

    suspend fun delete(id: String)

    suspend fun existing(): List<ExistingRecipe>

    /** Photo file names referenced by any recipe. */
    suspend fun imagePaths(): Set<String>

    suspend fun count(): Int
}

/** Room-backed [RecipeRepository], including ingredient product links (schema v4). */
class RoomRecipeRepository
    @Inject
    constructor(
        private val dao: RecipeDao,
    ) : RecipeRepository {
        override fun observeSummaries(): Flow<List<RecipeSummary>> =
            dao.observeSummaries().map { rows ->
                rows.map { (row, tags, keys) ->
                    RecipeSummary(
                        id = row.id,
                        title = row.title,
                        servings = row.servings,
                        imagePath = row.imagePath,
                        tags = tags.sortedBy { it.position }.map { it.tag },
                        ingredientKeys = keys.toSet(),
                        createdAt = Instant.ofEpochMilli(row.createdAt),
                    )
                }
            }

        override suspend fun get(id: String): Recipe? {
            val recipe = dao.getRecipe(id) ?: return null
            val links = dao.getLinks(id).groupBy { it.ingredientId }
            return recipe.toDomain(
                tags = dao.getTags(id),
                ingredients =
                    dao.getIngredients(id).map { entity ->
                        entity.toDomain().copy(
                            links =
                                links[entity.id].orEmpty().map {
                                    IngredientLink(
                                        ProductKey(it.providerId, it.productId),
                                        it.preferred,
                                    )
                                },
                        )
                    },
            )
        }

        override suspend fun getAll(): List<Recipe> = dao.allIds().mapNotNull { get(it) }

        override suspend fun save(recipe: Recipe) {
            dao.upsertFull(
                recipe = recipe.toEntity(),
                tags = recipe.tags.distinct().mapIndexed { index, tag -> RecipeTagEntity(recipe.id, tag, index) },
                ingredients = recipe.ingredients.mapIndexed { index, ingredient -> ingredient.toEntity(recipe.id, index) },
                links =
                    recipe.ingredients.flatMap { ingredient ->
                        ingredient.links
                            .distinctBy { it.product }
                            .mapIndexed { index, link ->
                                IngredientLinkEntity(ingredient.id, link.product.providerId, link.product.productId, link.preferred, index)
                            }
                    },
            )
        }

        override suspend fun delete(id: String) = dao.deleteRecipe(id)

        override suspend fun existing(): List<ExistingRecipe> =
            dao.existingRecipes().map {
                ExistingRecipe(it.id, it.uid, it.title, Instant.ofEpochMilli(it.createdAt), it.editedAfterImport)
            }

        override suspend fun imagePaths(): Set<String> = dao.imagePaths().toSet()

        override suspend fun count(): Int = dao.count()
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
        origin = origin.code,
        importedAt = importedAt?.toEpochMilli(),
        editedAfterImport = editedAfterImport,
        author = author,
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
    author = author,
    bodyMarkdown = bodyMarkdown,
    origin = RecipeOrigin.fromCode(origin),
    importedAt = importedAt?.let(Instant::ofEpochMilli),
    editedAfterImport = editedAfterImport,
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
