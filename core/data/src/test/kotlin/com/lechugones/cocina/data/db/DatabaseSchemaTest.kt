package com.lechugones.cocina.data.db

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals

/**
 * Migration harness. Every new schema version adds a test here that creates the previous
 * version from its exported JSON (`core/data/schemas/`), inserts data, migrates, and checks that
 * the data survived (CLAUDE.md, hard rule 2).
 */
@RunWith(AndroidJUnit4::class)
class DatabaseSchemaTest {
    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), CocinaDatabase::class.java)

    @Test
    fun `migrations cover every version step`() {
        val steps = CocinaMigrations.ALL.map { it.startVersion to it.endVersion }
        val expected = (1 until CocinaDatabase.VERSION).map { it to it + 1 }
        assertEquals(expected, steps)
    }

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
    fun `exported v1 schema opens with the current database and migrations`() =
        runTest {
            helper.createDatabase(testDb, 1).use { db ->
                db.execSQL(
                    "INSERT INTO recipe (id, uid, title, description, servings, prep_minutes, cook_minutes, " +
                        "image_path, source, body_md, created_at, updated_at) " +
                        "VALUES ('r1', 'u1', 'Gazpacho', NULL, 4, NULL, NULL, NULL, NULL, '', 0, 0)",
                )
            }
            helper.runMigrationsAndValidate(testDb, CocinaDatabase.VERSION, true, *CocinaMigrations.ALL).close()

            val database =
                Room
                    .databaseBuilder(ApplicationProvider.getApplicationContext(), CocinaDatabase::class.java, testDb)
                    .addMigrations(*CocinaMigrations.ALL)
                    .allowMainThreadQueries()
                    .build()
            try {
                assertEquals("Gazpacho", database.recipeDao().getRecipe("r1")?.title)
            } finally {
                database.close()
            }
        }
}
