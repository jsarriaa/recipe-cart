package com.lechugones.cocina.data.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Query
import androidx.room.Upsert

// Schema v4 (phase 3b): ingredient to product links and link memory (SPEC § 4.2, § 5.4).

/**
 * A confirmed link. No foreign key to `product`: a link must survive a product that is not in
 * the cache (a manual product from another phone, a provider not synced yet).
 */
@Entity(
    tableName = "ingredient_link",
    primaryKeys = ["ingredient_id", "provider_id", "product_id"],
    foreignKeys = [
        ForeignKey(
            entity = IngredientEntity::class,
            parentColumns = ["id"],
            childColumns = ["ingredient_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["provider_id", "product_id"])],
)
data class IngredientLinkEntity(
    @ColumnInfo(name = "ingredient_id") val ingredientId: String,
    @ColumnInfo(name = "provider_id") val providerId: String,
    @ColumnInfo(name = "product_id") val productId: String,
    val preferred: Boolean,
    val position: Int,
)

/** Products the user confirmed for an ingredient name, suggested first next time. */
@Entity(
    tableName = "link_memory",
    primaryKeys = ["name_key", "provider_id", "product_id"],
)
data class LinkMemoryEntity(
    @ColumnInfo(name = "name_key") val nameKey: String,
    @ColumnInfo(name = "provider_id") val providerId: String,
    @ColumnInfo(name = "product_id") val productId: String,
    @ColumnInfo(name = "last_used_at") val lastUsedAt: Long,
    @ColumnInfo(name = "use_count") val useCount: Int,
)

@Dao
interface LinkMemoryDao {
    @Query("SELECT * FROM link_memory WHERE name_key = :nameKey ORDER BY last_used_at DESC, use_count DESC")
    suspend fun forName(nameKey: String): List<LinkMemoryEntity>

    @Query("SELECT * FROM link_memory WHERE name_key = :nameKey AND provider_id = :providerId AND product_id = :productId")
    suspend fun get(
        nameKey: String,
        providerId: String,
        productId: String,
    ): LinkMemoryEntity?

    @Upsert
    suspend fun upsert(entry: LinkMemoryEntity)
}
