package com.lechugones.cocina.data.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

// Schema v5 (phase 5): pantry, opened shelf-life memory and cook history (SPEC § 4.5, § 6).

/** One pantry lot. Serializable so a cook event can keep an exact snapshot for "Deshacer". */
@Serializable
@Entity(
    tableName = "pantry_lot",
    indices = [Index(value = ["name_key"]), Index(value = ["provider_id", "product_id"])],
)
data class PantryLotEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "provider_id") val providerId: String?,
    @ColumnInfo(name = "product_id") val productId: String?,
    val name: String,
    @ColumnInfo(name = "name_key") val nameKey: String,
    @ColumnInfo(name = "quantity_milli") val quantityMilli: Long?,
    /** [com.lechugones.cocina.domain.units.MeasureUnit.code] of the quantities. */
    val unit: String?,
    @ColumnInfo(name = "package_quantity_milli") val packageQuantityMilli: Long?,
    val opened: Boolean,
    @ColumnInfo(name = "opened_at") val openedAt: Long?,
    @ColumnInfo(name = "added_at") val addedAt: Long,
    /** Epoch day. */
    @ColumnInfo(name = "best_before") val bestBefore: Long?,
    /** [com.lechugones.cocina.domain.pantry.PantryLocation.code]. */
    val location: String?,
    @ColumnInfo(name = "presence_only") val presenceOnly: Boolean,
    @ColumnInfo(name = "use_soon_manual") val useSoonManual: Boolean,
    @ColumnInfo(name = "use_soon_dismissed_at") val useSoonDismissedAt: Long?,
    @ColumnInfo(name = "opened_shelf_life_days") val openedShelfLifeDays: Int?,
)

/** "Una vez abierto, se conserva N días", remembered per product key or name key. */
@Entity(tableName = "shelf_life_memory")
data class ShelfLifeMemoryEntity(
    @PrimaryKey val key: String,
    @ColumnInfo(name = "opened_shelf_life_days") val openedShelfLifeDays: Int,
)

/** One "Lo he cocinado". No foreign key to the recipe: history keeps a title snapshot. */
@Entity(tableName = "cook_event", indices = [Index(value = ["recipe_id"])])
data class CookEventEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "recipe_id") val recipeId: String?,
    @ColumnInfo(name = "recipe_title") val recipeTitle: String,
    val servings: Int,
    @ColumnInfo(name = "cooked_at") val cookedAt: Long,
    val undone: Boolean,
)

/** A lot touched by a cook event, with its state before ([lotSnapshotJson]) for an exact undo. */
@Entity(
    tableName = "cook_deduction",
    primaryKeys = ["event_id", "lot_id"],
    foreignKeys = [
        ForeignKey(entity = CookEventEntity::class, parentColumns = ["id"], childColumns = ["event_id"], onDelete = ForeignKey.CASCADE),
    ],
)
data class CookDeductionEntity(
    @ColumnInfo(name = "event_id") val eventId: String,
    @ColumnInfo(name = "lot_id") val lotId: String,
    @ColumnInfo(name = "lot_snapshot_json") val lotSnapshotJson: String,
    @ColumnInfo(name = "deducted_milli") val deductedMilli: Long,
)

data class LastCookedRow(
    val recipeId: String,
    val cookedAt: Long,
)

@Dao
abstract class PantryDao {
    @Query("SELECT * FROM pantry_lot ORDER BY added_at")
    abstract fun observeLots(): Flow<List<PantryLotEntity>>

    @Query("SELECT * FROM pantry_lot ORDER BY added_at")
    abstract suspend fun lots(): List<PantryLotEntity>

    @Query("SELECT * FROM pantry_lot WHERE id = :id")
    abstract suspend fun lot(id: String): PantryLotEntity?

    @Upsert
    abstract suspend fun upsertLots(lots: List<PantryLotEntity>)

    @Query("DELETE FROM pantry_lot WHERE id IN (:ids)")
    abstract suspend fun deleteLots(ids: List<String>)

    @Query("SELECT opened_shelf_life_days FROM shelf_life_memory WHERE `key` = :key")
    abstract suspend fun shelfLife(key: String): Int?

    @Upsert
    abstract suspend fun rememberShelfLife(entry: ShelfLifeMemoryEntity)

    @Insert
    protected abstract suspend fun insertEvent(event: CookEventEntity)

    @Insert
    protected abstract suspend fun insertDeductions(deductions: List<CookDeductionEntity>)

    @Query("SELECT * FROM cook_event WHERE id = :id")
    abstract suspend fun event(id: String): CookEventEntity?

    @Query("SELECT * FROM cook_deduction WHERE event_id = :eventId")
    abstract suspend fun deductions(eventId: String): List<CookDeductionEntity>

    @Query("UPDATE cook_event SET undone = 1 WHERE id = :id")
    protected abstract suspend fun markUndone(id: String)

    @Query(
        "SELECT recipe_id AS recipeId, MAX(cooked_at) AS cookedAt FROM cook_event " +
            "WHERE undone = 0 AND recipe_id IS NOT NULL GROUP BY recipe_id",
    )
    abstract fun observeLastCooked(): Flow<List<LastCookedRow>>

    /** "Lo he cocinado": the lot changes and the event with its snapshots, atomically. */
    @Transaction
    open suspend fun cook(
        event: CookEventEntity,
        deductions: List<CookDeductionEntity>,
        updated: List<PantryLotEntity>,
        removed: List<String>,
    ) {
        insertEvent(event)
        if (deductions.isNotEmpty()) insertDeductions(deductions)
        if (updated.isNotEmpty()) upsertLots(updated)
        if (removed.isNotEmpty()) deleteLots(removed)
    }

    /** "Deshacer": puts every touched lot back exactly as it was. */
    @Transaction
    open suspend fun undo(
        eventId: String,
        snapshots: List<PantryLotEntity>,
    ) {
        if (snapshots.isNotEmpty()) upsertLots(snapshots)
        markUndone(eventId)
    }
}
