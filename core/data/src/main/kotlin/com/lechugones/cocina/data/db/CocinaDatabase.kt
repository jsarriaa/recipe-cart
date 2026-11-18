package com.lechugones.cocina.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration

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
        const val VERSION = 1
    }
}

/**
 * Every migration ever shipped, in order. Destructive fallback is forbidden: a missing
 * migration must crash in development, not wipe the owner's data (CLAUDE.md, hard rule 2).
 */
object CocinaMigrations {
    val ALL: Array<Migration> = emptyArray()
}
