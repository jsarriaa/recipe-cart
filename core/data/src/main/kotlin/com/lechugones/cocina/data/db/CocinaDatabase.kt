package com.lechugones.cocina.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        RecipeEntity::class,
        RecipeTagEntity::class,
        IngredientEntity::class,
        ProductEntity::class,
        PriceSnapshotEntity::class,
    ],
    version = CocinaDatabase.VERSION,
    exportSchema = true,
)
abstract class CocinaDatabase : RoomDatabase() {
    abstract fun recipeDao(): RecipeDao

    abstract fun productDao(): ProductDao

    companion object {
        const val NAME = "cocina.db"
        const val VERSION = 3
    }
}

/**
 * Every migration ever shipped, in order. Destructive fallback is forbidden: a missing
 * migration must crash in development, not wipe the owner's data (CLAUDE.md, hard rule 2).
 */
object CocinaMigrations {
    /** v2 (phase 2): recipe origin tracking and ordered tags. */
    val MIGRATION_1_2 =
        object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE recipe ADD COLUMN origin TEXT NOT NULL DEFAULT 'created'")
                db.execSQL("ALTER TABLE recipe ADD COLUMN imported_at INTEGER")
                db.execSQL("ALTER TABLE recipe ADD COLUMN edited_after_import INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE recipe_tag ADD COLUMN position INTEGER NOT NULL DEFAULT 0")
            }
        }

    /** v3 (phase 3a): cached supermarket catalogue and price history. Recipe data is untouched. */
    val MIGRATION_2_3 =
        object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `product` (`provider_id` TEXT NOT NULL, `product_id` TEXT NOT NULL, " +
                        "`name` TEXT NOT NULL, `search_key` TEXT NOT NULL, `brand` TEXT, `brand_kind` TEXT NOT NULL, " +
                        "`packaging` TEXT, `net_milli` INTEGER NOT NULL, `base_unit` TEXT NOT NULL, `unit_count` INTEGER, " +
                        "`unit_count_unit` TEXT, `drained_milli` INTEGER, `approx` INTEGER NOT NULL, `price_cents` INTEGER, " +
                        "`reference_price_cents` INTEGER, `reference_format` TEXT, `image_url` TEXT, `web_url` TEXT, " +
                        "`category_path` TEXT NOT NULL, `available` INTEGER NOT NULL, `fetched_at` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`provider_id`, `product_id`))",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_product_provider_id_available` ON `product` (`provider_id`, `available`)",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `price_snapshot` (`provider_id` TEXT NOT NULL, `product_id` TEXT NOT NULL, " +
                        "`fetched_at` INTEGER NOT NULL, `price_cents` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`provider_id`, `product_id`, `fetched_at`))",
                )
            }
        }

    val ALL: Array<Migration> = arrayOf(MIGRATION_1_2, MIGRATION_2_3)
}
