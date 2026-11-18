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
    ],
    version = CocinaDatabase.VERSION,
    exportSchema = true,
)
abstract class CocinaDatabase : RoomDatabase() {
    abstract fun recipeDao(): RecipeDao

    companion object {
        const val NAME = "cocina.db"
        const val VERSION = 2
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

    val ALL: Array<Migration> = arrayOf(MIGRATION_1_2)
}
