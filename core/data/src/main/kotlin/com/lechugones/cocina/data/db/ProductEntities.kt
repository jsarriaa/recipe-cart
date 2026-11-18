package com.lechugones.cocina.data.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

// Schema v3 (phase 3a): the cached supermarket catalogue (SPEC § 4.1, § 6).

@Entity(
    tableName = "product",
    primaryKeys = ["provider_id", "product_id"],
    indices = [Index(value = ["provider_id", "available"])],
)
data class ProductEntity(
    @ColumnInfo(name = "provider_id") val providerId: String,
    @ColumnInfo(name = "product_id") val productId: String,
    val name: String,
    /** NameKey of name, brand and category path: accent-free search with LIKE. */
    @ColumnInfo(name = "search_key") val searchKey: String,
    val brand: String?,
    /** [com.lechugones.cocina.domain.product.BrandKind] name. */
    @ColumnInfo(name = "brand_kind") val brandKind: String,
    val packaging: String?,
    /** Thousandths of [baseUnit]. */
    @ColumnInfo(name = "net_milli") val netMilli: Long,
    /** g, ml or ud. */
    @ColumnInfo(name = "base_unit") val baseUnit: String,
    @ColumnInfo(name = "unit_count") val unitCount: Int?,
    @ColumnInfo(name = "unit_count_unit") val unitCountUnit: String?,
    @ColumnInfo(name = "drained_milli") val drainedMilli: Long?,
    val approx: Boolean,
    @ColumnInfo(name = "price_cents") val priceCents: Long?,
    @ColumnInfo(name = "reference_price_cents") val referencePriceCents: Long?,
    @ColumnInfo(name = "reference_format") val referenceFormat: String?,
    @ColumnInfo(name = "image_url") val imageUrl: String?,
    @ColumnInfo(name = "web_url") val webUrl: String?,
    /** Category names joined with " › ". */
    @ColumnInfo(name = "category_path") val categoryPath: String,
    val available: Boolean,
    /** Epoch milliseconds of the last successful fetch. */
    @ColumnInfo(name = "fetched_at") val fetchedAt: Long,
)

/** One row per price change (SPEC § 4.1, price history). */
@Entity(
    tableName = "price_snapshot",
    primaryKeys = ["provider_id", "product_id", "fetched_at"],
)
data class PriceSnapshotEntity(
    @ColumnInfo(name = "provider_id") val providerId: String,
    @ColumnInfo(name = "product_id") val productId: String,
    @ColumnInfo(name = "fetched_at") val fetchedAt: Long,
    @ColumnInfo(name = "price_cents") val priceCents: Long,
)

data class ProductPriceRow(
    val productId: String,
    val priceCents: Long?,
)

@Dao
interface ProductDao {
    @Upsert
    suspend fun upsert(products: List<ProductEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSnapshots(snapshots: List<PriceSnapshotEntity>)

    @Query("SELECT product_id AS productId, price_cents AS priceCents FROM product WHERE provider_id = :providerId")
    suspend fun prices(providerId: String): List<ProductPriceRow>

    @Query("SELECT * FROM product WHERE provider_id = :providerId AND product_id = :productId")
    suspend fun get(
        providerId: String,
        productId: String,
    ): ProductEntity?

    @Query("SELECT * FROM product WHERE provider_id = :providerId AND product_id = :productId")
    fun observe(
        providerId: String,
        productId: String,
    ): Flow<ProductEntity?>

    /** Available products whose search key contains [word]; further words are filtered by the caller. */
    @Query(
        "SELECT * FROM product WHERE provider_id IN (:providers) AND available = 1 " +
            "AND search_key LIKE '%' || :word || '%' " +
            "AND (:storeBrandOnly = 0 OR brand_kind != 'NATIONAL') " +
            "ORDER BY name COLLATE NOCASE LIMIT :limit",
    )
    fun search(
        providers: Set<String>,
        word: String,
        storeBrandOnly: Boolean,
        limit: Int,
    ): Flow<List<ProductEntity>>

    @Query("SELECT COUNT(*) FROM product WHERE provider_id = :providerId AND available = 1")
    fun observeCount(providerId: String): Flow<Int>

    @Query("SELECT MAX(fetched_at) FROM product WHERE provider_id = :providerId")
    fun observeLastFetch(providerId: String): Flow<Long?>

    /** After a complete sync: products the shop no longer lists. Rows are kept so links survive. */
    @Query("UPDATE product SET available = 0 WHERE provider_id = :providerId AND fetched_at < :since")
    suspend fun markUnseenUnavailable(
        providerId: String,
        since: Long,
    )

    @Query("UPDATE product SET available = 0 WHERE provider_id = :providerId AND product_id = :productId")
    suspend fun markUnavailable(
        providerId: String,
        productId: String,
    )
}
