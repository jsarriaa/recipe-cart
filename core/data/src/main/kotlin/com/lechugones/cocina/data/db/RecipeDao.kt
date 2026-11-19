package com.lechugones.cocina.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
abstract class RecipeDao {
    /** One consistent snapshot of every recipe with its tags and ingredient keys. */
    @Transaction
    @Query("SELECT id, title, servings, image_path AS imagePath, created_at AS createdAt FROM recipe")
    abstract fun observeSummaries(): Flow<List<RecipeSummaryWithDetails>>

    @Query("SELECT * FROM recipe WHERE id = :id")
    abstract suspend fun getRecipe(id: String): RecipeEntity?

    @Query("SELECT tag FROM recipe_tag WHERE recipe_id = :recipeId ORDER BY position, tag")
    abstract suspend fun getTags(recipeId: String): List<String>

    @Query("SELECT * FROM ingredient WHERE recipe_id = :recipeId ORDER BY position")
    abstract suspend fun getIngredients(recipeId: String): List<IngredientEntity>

    @Query(
        "SELECT l.* FROM ingredient_link l JOIN ingredient i ON i.id = l.ingredient_id " +
            "WHERE i.recipe_id = :recipeId ORDER BY l.ingredient_id, l.position",
    )
    abstract suspend fun getLinks(recipeId: String): List<IngredientLinkEntity>

    @Query("SELECT id FROM recipe ORDER BY title COLLATE NOCASE, id")
    abstract suspend fun allIds(): List<String>

    @Query(
        "SELECT id, uid, title, created_at AS createdAt, edited_after_import AS editedAfterImport FROM recipe",
    )
    abstract suspend fun existingRecipes(): List<ExistingRecipeRow>

    @Query("SELECT image_path FROM recipe WHERE image_path IS NOT NULL")
    abstract suspend fun imagePaths(): List<String>

    @Query("SELECT COUNT(*) FROM recipe")
    abstract suspend fun count(): Int

    @Query("DELETE FROM recipe WHERE id = :id")
    abstract suspend fun delete(id: String)

    @Query("DELETE FROM shopping_recipe WHERE recipe_id = :id")
    protected abstract suspend fun deleteShoppingEntries(id: String)

    /** Deletes a recipe for good: with its tags, ingredients and links, and off the shopping list. */
    @Transaction
    open suspend fun deleteRecipe(id: String) {
        deleteShoppingEntries(id)
        delete(id)
    }

    @Insert
    protected abstract suspend fun insertRecipe(recipe: RecipeEntity)

    @Insert
    protected abstract suspend fun insertTags(tags: List<RecipeTagEntity>)

    @Insert
    protected abstract suspend fun insertIngredients(ingredients: List<IngredientEntity>)

    @Insert
    protected abstract suspend fun insertLinks(links: List<IngredientLinkEntity>)

    /** Inserts or fully replaces a recipe with its tags and ingredients, atomically. */
    @Transaction
    open suspend fun upsertFull(
        recipe: RecipeEntity,
        tags: List<RecipeTagEntity>,
        ingredients: List<IngredientEntity>,
        links: List<IngredientLinkEntity> = emptyList(),
    ) {
        delete(recipe.id) // Cascades to tags, ingredients and their links.
        insertRecipe(recipe)
        if (tags.isNotEmpty()) insertTags(tags)
        if (ingredients.isNotEmpty()) insertIngredients(ingredients)
        if (links.isNotEmpty()) insertLinks(links)
    }
}
