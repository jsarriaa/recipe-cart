package com.lechugones.cocina.data.db

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Migration harness. Every new schema version adds a test here that creates the previous
 * version from its exported JSON (`core/data/schemas/`), inserts data, migrates, and checks that
 * the data survived (CLAUDE.md, hard rule 2).
 */
@RunWith(AndroidJUnit4::class)
class DatabaseSchemaTest {
    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), CocinaDatabase::class.java)

    /**
     * Absolute path: with a bare name, Room's test driver compares paths with "/" and fails
     * under Robolectric on Windows.
     */
    private val testDb: String =
        ApplicationProvider
            .getApplicationContext<android.content.Context>()
            .getDatabasePath("migration-test.db")
            .absolutePath

    @Test
    fun `migrations cover every version step`() {
        val steps = CocinaMigrations.ALL.map { it.startVersion to it.endVersion }
        val expected = (1 until CocinaDatabase.VERSION).map { it to it + 1 }
        assertEquals(expected, steps)
    }

    @Test
    fun `v1 data survives the migration to v2 with safe defaults`() =
        runTest {
            helper.createDatabase(testDb, 1).use { db ->
                db.execSQL(
                    "INSERT INTO recipe (id, uid, title, description, servings, prep_minutes, cook_minutes, " +
                        "image_path, source, body_md, created_at, updated_at) " +
                        "VALUES ('r1', 'u1', 'Gazpacho', NULL, 4, NULL, NULL, 'foto.jpg', NULL, '## Preparación', 10, 20)",
                )
                db.execSQL("INSERT INTO recipe_tag (recipe_id, tag) VALUES ('r1', 'verano')")
                db.execSQL(
                    "INSERT INTO ingredient (id, recipe_id, position, group_label, name, name_key, quantity_milli, unit, " +
                        "note, optional, pantry_staple, unit_weight_g_milli, match_query, match_package, match_store_brand_ok) " +
                        "VALUES ('i1', 'r1', 0, NULL, 'tomate', 'tomate', 1000000, 'g', NULL, 0, 0, NULL, 'tomate', '1 kg', 1)",
                )
            }
            helper.runMigrationsAndValidate(testDb, 2, true, CocinaMigrations.MIGRATION_1_2).close()

            val database =
                Room
                    .databaseBuilder(ApplicationProvider.getApplicationContext(), CocinaDatabase::class.java, testDb)
                    .addMigrations(*CocinaMigrations.ALL)
                    .allowMainThreadQueries()
                    .build()
            try {
                val dao = database.recipeDao()
                val recipe = dao.getRecipe("r1")
                assertEquals("Gazpacho", recipe?.title)
                assertEquals("foto.jpg", recipe?.imagePath)
                assertEquals("created", recipe?.origin)
                assertNull(recipe?.importedAt)
                assertEquals(false, recipe?.editedAfterImport)
                assertEquals(listOf("verano"), dao.getTags("r1"))
                assertEquals("tomate", dao.getIngredients("r1").single().name)
            } finally {
                database.close()
            }
        }

    @Test
    fun `v2 recipes survive the migration to v3, which adds an empty catalogue`() =
        runTest {
            helper.createDatabase(testDb, 2).use { db ->
                db.execSQL(
                    "INSERT INTO recipe (id, uid, title, description, servings, prep_minutes, cook_minutes, image_path, source, " +
                        "body_md, created_at, updated_at, origin, imported_at, edited_after_import) " +
                        "VALUES ('r1', 'u1', 'Tortilla', NULL, 2, 10, 20, NULL, NULL, '## Preparación', 10, 20, 'imported', 30, 1)",
                )
                db.execSQL("INSERT INTO recipe_tag (recipe_id, tag, position) VALUES ('r1', 'cena', 0)")
            }
            helper.runMigrationsAndValidate(testDb, 3, true, CocinaMigrations.MIGRATION_2_3).close()

            val database =
                Room
                    .databaseBuilder(ApplicationProvider.getApplicationContext(), CocinaDatabase::class.java, testDb)
                    .addMigrations(*CocinaMigrations.ALL)
                    .allowMainThreadQueries()
                    .build()
            try {
                val recipe = database.recipeDao().getRecipe("r1")
                assertEquals("Tortilla", recipe?.title)
                assertEquals("imported", recipe?.origin)
                assertEquals(true, recipe?.editedAfterImport)
                assertEquals(listOf("cena"), database.recipeDao().getTags("r1"))
                assertEquals(0, database.productDao().observeCount("mercadona").first())
            } finally {
                database.close()
            }
        }
}
