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

    @Insert
    protected abstract suspend fun insertRecipe(recipe: RecipeEntity)

    @Insert
    protected abstract suspend fun insertTags(tags: List<RecipeTagEntity>)

    @Insert
    protected abstract suspend fun insertIngredients(ingredients: List<IngredientEntity>)

    /** Inserts or fully replaces a recipe with its tags and ingredients, atomically. */
    @Transaction
    open suspend fun upsertFull(
        recipe: RecipeEntity,
        tags: List<RecipeTagEntity>,
        ingredients: List<IngredientEntity>,
    ) {
        delete(recipe.id) // Cascades to tags and ingredients.
        insertRecipe(recipe)
        if (tags.isNotEmpty()) insertTags(tags)
        if (ingredients.isNotEmpty()) insertIngredients(ingredients)
    }
}
