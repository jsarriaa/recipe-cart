package com.lechugones.cocina.data.db

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation

// Schema v2 (phase 2). Column names follow docs/SPEC.md § 6. Any change needs a new schema
// version and a tested migration (CLAUDE.md, hard rule 2). Columns added after v1 declare the
// same DEFAULT as their migration, so Room's schema validation matches.

@Entity(
    tableName = "recipe",
    indices = [Index(value = ["uid"], unique = true)],
)
data class RecipeEntity(
    @PrimaryKey val id: String,
    val uid: String,
    val title: String,
    val description: String?,
    val servings: Int,
    @ColumnInfo(name = "prep_minutes") val prepMinutes: Int?,
    @ColumnInfo(name = "cook_minutes") val cookMinutes: Int?,
    @ColumnInfo(name = "image_path") val imagePath: String?,
    val source: String?,
    @ColumnInfo(name = "body_md") val bodyMarkdown: String,
    /** Epoch milliseconds. */
    @ColumnInfo(name = "created_at") val createdAt: Long,
    /** Epoch milliseconds. */
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    /** [com.lechugones.cocina.domain.recipe.RecipeOrigin.code]. Added in v2. */
    @ColumnInfo(name = "origin", defaultValue = "'created'") val origin: String,
    /** Epoch milliseconds. Added in v2. */
    @ColumnInfo(name = "imported_at") val importedAt: Long?,
    /** Added in v2. */
    @ColumnInfo(name = "edited_after_import", defaultValue = "0") val editedAfterImport: Boolean,
)

@Entity(
    tableName = "recipe_tag",
    primaryKeys = ["recipe_id", "tag"],
    foreignKeys = [
        ForeignKey(
            entity = RecipeEntity::class,
            parentColumns = ["id"],
            childColumns = ["recipe_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["tag"])],
)
data class RecipeTagEntity(
    @ColumnInfo(name = "recipe_id") val recipeId: String,
    val tag: String,
    /** Order of the tag in the recipe. Added in v2. */
    @ColumnInfo(name = "position", defaultValue = "0") val position: Int,
)

@Entity(
    tableName = "ingredient",
    foreignKeys = [
        ForeignKey(
            entity = RecipeEntity::class,
            parentColumns = ["id"],
            childColumns = ["recipe_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["recipe_id"]), Index(value = ["name_key"])],
)
data class IngredientEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "recipe_id") val recipeId: String,
    val position: Int,
    @ColumnInfo(name = "group_label") val groupLabel: String?,
    val name: String,
    @ColumnInfo(name = "name_key") val nameKey: String,
    /** Thousandths of [unit]; null for `pizca` without a count and `al_gusto`. */
    @ColumnInfo(name = "quantity_milli") val quantityMilli: Long?,
    /** [com.lechugones.cocina.domain.units.MeasureUnit.code]. */
    val unit: String,
    val note: String?,
    val optional: Boolean,
    @ColumnInfo(name = "pantry_staple") val pantryStaple: Boolean,
    @ColumnInfo(name = "unit_weight_g_milli") val unitWeightGMilli: Long?,
    @ColumnInfo(name = "match_query") val matchQuery: String?,
    /** Canonical package size text, e.g. `4 x 125 g`. */
    @ColumnInfo(name = "match_package") val matchPackage: String?,
    @ColumnInfo(name = "match_store_brand_ok") val matchStoreBrandOk: Boolean,
)

/** Projection for recipe lists. */
data class RecipeSummaryRow(
    val id: String,
    val title: String,
    val servings: Int,
    val imagePath: String?,
    val createdAt: Long,
)

/** A recipe list row with its tags and ingredient name keys, loaded in one transaction. */
data class RecipeSummaryWithDetails(
    @Embedded val row: RecipeSummaryRow,
    @Relation(parentColumn = "id", entityColumn = "recipe_id", entity = RecipeTagEntity::class)
    val tags: List<RecipeTagEntity>,
    @Relation(parentColumn = "id", entityColumn = "recipe_id", entity = IngredientEntity::class, projection = ["name_key"])
    val ingredientKeys: List<String>,
)

/** What the importer needs to detect duplicates. */
data class ExistingRecipeRow(
    val id: String,
    val uid: String,
    val title: String,
    val createdAt: Long,
    val editedAfterImport: Boolean,
)
