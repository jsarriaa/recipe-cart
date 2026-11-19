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
        IngredientLinkEntity::class,
        LinkMemoryEntity::class,
        PantryLotEntity::class,
        ShelfLifeMemoryEntity::class,
        CookEventEntity::class,
        CookDeductionEntity::class,
        ShoppingRecipeEntity::class,
        ShoppingManualEntity::class,
        ShoppingCheckEntity::class,
        ShoppingOverrideEntity::class,
    ],
    version = CocinaDatabase.VERSION,
    exportSchema = true,
)
abstract class CocinaDatabase : RoomDatabase() {
    abstract fun recipeDao(): RecipeDao

    abstract fun productDao(): ProductDao

    abstract fun linkMemoryDao(): LinkMemoryDao

    abstract fun pantryDao(): PantryDao

    abstract fun shoppingDao(): ShoppingDao

    companion object {
        const val NAME = "cocina.db"
        const val VERSION = 7
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

    /** v4 (phase 3b): ingredient product links and link memory. Existing data is untouched. */
    val MIGRATION_3_4 =
        object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `ingredient_link` (`ingredient_id` TEXT NOT NULL, `provider_id` TEXT NOT NULL, " +
                        "`product_id` TEXT NOT NULL, `preferred` INTEGER NOT NULL, `position` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`ingredient_id`, `provider_id`, `product_id`), FOREIGN KEY(`ingredient_id`) " +
                        "REFERENCES `ingredient`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_ingredient_link_provider_id_product_id` " +
                        "ON `ingredient_link` (`provider_id`, `product_id`)",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `link_memory` (`name_key` TEXT NOT NULL, `provider_id` TEXT NOT NULL, " +
                        "`product_id` TEXT NOT NULL, `last_used_at` INTEGER NOT NULL, `use_count` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`name_key`, `provider_id`, `product_id`))",
                )
            }
        }

    /** v5 (phase 5): pantry lots, opened shelf-life memory and cook history. Existing data is untouched. */
    val MIGRATION_4_5 =
        object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `pantry_lot` (`id` TEXT NOT NULL, `provider_id` TEXT, `product_id` TEXT, " +
                        "`name` TEXT NOT NULL, `name_key` TEXT NOT NULL, `quantity_milli` INTEGER, `unit` TEXT, " +
                        "`package_quantity_milli` INTEGER, `opened` INTEGER NOT NULL, `opened_at` INTEGER, " +
                        "`added_at` INTEGER NOT NULL, `best_before` INTEGER, `location` TEXT, `presence_only` INTEGER NOT NULL, " +
                        "`use_soon_manual` INTEGER NOT NULL, `use_soon_dismissed_at` INTEGER, `opened_shelf_life_days` INTEGER, " +
                        "PRIMARY KEY(`id`))",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_pantry_lot_name_key` ON `pantry_lot` (`name_key`)")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_pantry_lot_provider_id_product_id` ON `pantry_lot` (`provider_id`, `product_id`)",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `shelf_life_memory` (`key` TEXT NOT NULL, " +
                        "`opened_shelf_life_days` INTEGER NOT NULL, PRIMARY KEY(`key`))",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `cook_event` (`id` TEXT NOT NULL, `recipe_id` TEXT, `recipe_title` TEXT NOT NULL, " +
                        "`servings` INTEGER NOT NULL, `cooked_at` INTEGER NOT NULL, `undone` INTEGER NOT NULL, PRIMARY KEY(`id`))",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_cook_event_recipe_id` ON `cook_event` (`recipe_id`)")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `cook_deduction` (`event_id` TEXT NOT NULL, `lot_id` TEXT NOT NULL, " +
                        "`lot_snapshot_json` TEXT NOT NULL, `deducted_milli` INTEGER NOT NULL, PRIMARY KEY(`event_id`, `lot_id`), " +
                        "FOREIGN KEY(`event_id`) REFERENCES `cook_event`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
                )
            }
        }

    /** v6 (phase 6): shopping list inputs (recipes added, manual items, ticks, overrides). */
    val MIGRATION_5_6 =
        object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `shopping_recipe` (`id` TEXT NOT NULL, `recipe_id` TEXT NOT NULL, " +
                        "`servings` INTEGER NOT NULL, `added_at` INTEGER NOT NULL, PRIMARY KEY(`id`))",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_shopping_recipe_recipe_id` ON `shopping_recipe` (`recipe_id`)")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `shopping_manual` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `provider_id` TEXT, " +
                        "`product_id` TEXT, `packages` INTEGER NOT NULL, `added_at` INTEGER NOT NULL, PRIMARY KEY(`id`))",
                )
                db.execSQL("CREATE TABLE IF NOT EXISTS `shopping_check` (`item_key` TEXT NOT NULL, PRIMARY KEY(`item_key`))")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `shopping_override` (`name_key` TEXT NOT NULL, `provider_id` TEXT NOT NULL, " +
                        "PRIMARY KEY(`name_key`))",
                )
            }
        }

    /** v7 (1.4.0): the recipe's author, for the community recipe book. */
    val MIGRATION_6_7 =
        object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `recipe` ADD COLUMN `author` TEXT DEFAULT NULL")
            }
        }

    val ALL: Array<Migration> = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7)
}
