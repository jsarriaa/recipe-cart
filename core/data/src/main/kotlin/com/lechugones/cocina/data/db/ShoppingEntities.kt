package com.lechugones.cocina.data.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

// Schema v6 (phase 6): the shopping list (SPEC § 4.6). The list itself is computed from these
// inputs every time, so it is always up to date with recipes and the pantry.

/**
 * "Añadir a la lista" from a recipe. No foreign key: saving a recipe deletes and re-inserts its
 * row, which would cascade here. [com.lechugones.cocina.data.recipe.RecipeLibrary.delete]
 * removes the entries of a deleted recipe instead (SPEC § 4.2).
 */
@Entity(tableName = "shopping_recipe", indices = [Index(value = ["recipe_id"])])
data class ShoppingRecipeEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "recipe_id") val recipeId: String,
    val servings: Int,
    @ColumnInfo(name = "added_at") val addedAt: Long,
)

/** A free-text item or a catalogue product added by hand. */
@Entity(tableName = "shopping_manual")
data class ShoppingManualEntity(
    @PrimaryKey val id: String,
    val name: String,
    @ColumnInfo(name = "provider_id") val providerId: String?,
    @ColumnInfo(name = "product_id") val productId: String?,
    val packages: Int,
    @ColumnInfo(name = "added_at") val addedAt: Long,
)

/** A ticked item, by its stable item key. */
@Entity(tableName = "shopping_check")
data class ShoppingCheckEntity(
    @PrimaryKey @ColumnInfo(name = "item_key") val itemKey: String,
)

/** Supermarket chosen by hand for an ingredient name. */
@Entity(tableName = "shopping_override")
data class ShoppingOverrideEntity(
    @PrimaryKey @ColumnInfo(name = "name_key") val nameKey: String,
    @ColumnInfo(name = "provider_id") val providerId: String,
)

@Dao
abstract class ShoppingDao {
    @Query("SELECT * FROM shopping_recipe ORDER BY added_at")
    abstract fun observeRecipes(): Flow<List<ShoppingRecipeEntity>>

    @Query("SELECT * FROM shopping_manual ORDER BY added_at")
    abstract fun observeManual(): Flow<List<ShoppingManualEntity>>

    @Query("SELECT item_key FROM shopping_check")
    abstract fun observeChecks(): Flow<List<String>>

    @Query("SELECT * FROM shopping_override")
    abstract fun observeOverrides(): Flow<List<ShoppingOverrideEntity>>

    @Upsert
    abstract suspend fun upsertRecipe(entry: ShoppingRecipeEntity)

    @Upsert
    abstract suspend fun upsertManual(entry: ShoppingManualEntity)

    @Upsert
    abstract suspend fun check(entry: ShoppingCheckEntity)

    @Query("DELETE FROM shopping_check WHERE item_key = :itemKey")
    abstract suspend fun uncheck(itemKey: String)

    @Upsert
    abstract suspend fun setOverride(entry: ShoppingOverrideEntity)

    @Query("DELETE FROM shopping_override WHERE name_key = :nameKey")
    abstract suspend fun clearOverride(nameKey: String)

    @Query("DELETE FROM shopping_recipe WHERE id = :id")
    abstract suspend fun deleteRecipe(id: String)

    @Query("DELETE FROM shopping_recipe WHERE recipe_id = :recipeId")
    abstract suspend fun deleteForRecipe(recipeId: String)

    @Query("DELETE FROM shopping_manual WHERE id IN (:ids)")
    abstract suspend fun deleteManual(ids: List<String>)

    @Query("DELETE FROM shopping_check")
    abstract suspend fun clearChecks()

    @Query("DELETE FROM shopping_recipe")
    protected abstract suspend fun clearRecipes()

    @Query("DELETE FROM shopping_manual")
    protected abstract suspend fun clearManual()

    /** "Vaciar lista". Overrides are kept: they are preferences, not list content. */
    @Transaction
    open suspend fun clearAll() {
        clearRecipes()
        clearManual()
        clearChecks()
    }
}
