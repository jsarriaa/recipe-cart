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

    @Test
    fun `v3 recipes and catalogue survive the migration to v4, and links can then be stored`() =
        runTest {
            helper.createDatabase(testDb, 3).use { db ->
                db.execSQL(
                    "INSERT INTO recipe (id, uid, title, description, servings, prep_minutes, cook_minutes, image_path, source, " +
                        "body_md, created_at, updated_at, origin, imported_at, edited_after_import) " +
                        "VALUES ('r1', 'u1', 'Tacos', NULL, 2, NULL, NULL, NULL, NULL, '', 10, 20, 'created', NULL, 0)",
                )
                db.execSQL(
                    "INSERT INTO ingredient (id, recipe_id, position, group_label, name, name_key, quantity_milli, unit, " +
                        "note, optional, pantry_staple, unit_weight_g_milli, match_query, match_package, match_store_brand_ok) " +
                        "VALUES ('i1', 'r1', 0, NULL, 'tortillas', 'tortillas', 4000, 'ud', NULL, 0, 0, NULL, 'tortillas trigo', NULL, 1)",
                )
                db.execSQL(
                    "INSERT INTO product (provider_id, product_id, name, search_key, brand, brand_kind, packaging, net_milli, " +
                        "base_unit, unit_count, unit_count_unit, drained_milli, approx, price_cents, reference_price_cents, " +
                        "reference_format, image_url, web_url, category_path, available, fetched_at) VALUES ('mercadona', '80870', " +
                        "'Maxi tortillas', 'maxi tortillas', NULL, 'STORE', NULL, 360000, 'g', 6, 'ud', NULL, 0, 150, NULL, NULL, " +
                        "NULL, NULL, '', 1, 5)",
                )
            }
            helper.runMigrationsAndValidate(testDb, 4, true, CocinaMigrations.MIGRATION_3_4).close()

            val database =
                Room
                    .databaseBuilder(ApplicationProvider.getApplicationContext(), CocinaDatabase::class.java, testDb)
                    .addMigrations(*CocinaMigrations.ALL)
                    .allowMainThreadQueries()
                    .build()
            try {
                val repository =
                    com.lechugones.cocina.data.recipe
                        .RoomRecipeRepository(database.recipeDao())
                val recipe = checkNotNull(repository.get("r1"))
                assertEquals("tortillas", recipe.ingredients.single().name)
                assertEquals(1, database.productDao().observeCount("mercadona").first())

                val link =
                    com.lechugones.cocina.domain.recipe.IngredientLink(
                        com.lechugones.cocina.domain.product
                            .ProductKey("mercadona", "80870"),
                        preferred = true,
                    )
                repository.save(recipe.copy(ingredients = recipe.ingredients.map { it.copy(links = listOf(link)) }))
                assertEquals(
                    listOf(link),
                    repository
                        .get("r1")
                        ?.ingredients
                        ?.single()
                        ?.links,
                )
            } finally {
                database.close()
            }
        }

    @Test
    fun `v4 recipes, links and link memory survive the migration to v5, with an empty pantry`() =
        runTest {
            helper.createDatabase(testDb, 4).use { db ->
                db.execSQL(
                    "INSERT INTO recipe (id, uid, title, description, servings, prep_minutes, cook_minutes, image_path, source, " +
                        "body_md, created_at, updated_at, origin, imported_at, edited_after_import) " +
                        "VALUES ('r1', 'u1', 'Tacos', NULL, 2, NULL, NULL, NULL, NULL, '', 10, 20, 'created', NULL, 0)",
                )
                db.execSQL(
                    "INSERT INTO ingredient (id, recipe_id, position, group_label, name, name_key, quantity_milli, unit, " +
                        "note, optional, pantry_staple, unit_weight_g_milli, match_query, match_package, match_store_brand_ok) " +
                        "VALUES ('i1', 'r1', 0, NULL, 'tortillas', 'tortillas', 4000, 'ud', NULL, 0, 0, NULL, NULL, NULL, 1)",
                )
                db.execSQL("INSERT INTO ingredient_link VALUES ('i1', 'mercadona', '80870', 1, 0)")
                db.execSQL("INSERT INTO link_memory VALUES ('tortillas', 'mercadona', '80870', 5, 2)")
            }
            helper.runMigrationsAndValidate(testDb, 5, true, CocinaMigrations.MIGRATION_4_5).close()

            val database =
                Room
                    .databaseBuilder(ApplicationProvider.getApplicationContext(), CocinaDatabase::class.java, testDb)
                    .addMigrations(*CocinaMigrations.ALL)
                    .allowMainThreadQueries()
                    .build()
            try {
                val recipe =
                    checkNotNull(
                        com.lechugones.cocina.data.recipe
                            .RoomRecipeRepository(database.recipeDao())
                            .get("r1"),
                    )
                assertEquals(
                    "80870",
                    recipe.ingredients
                        .single()
                        .links
                        .single()
                        .product.productId,
                )
                assertEquals(
                    2,
                    database
                        .linkMemoryDao()
                        .forName("tortillas")
                        .single()
                        .useCount,
                )
                assertEquals(emptyList(), database.pantryDao().lots())
            } finally {
                database.close()
            }
        }

    @Test
    fun `v5 pantry and cook history survive the migration to v6, and editing a recipe keeps it on the list`() =
        runTest {
            helper.createDatabase(testDb, 5).use { db ->
                db.execSQL(
                    "INSERT INTO recipe (id, uid, title, description, servings, prep_minutes, cook_minutes, image_path, source, " +
                        "body_md, created_at, updated_at, origin, imported_at, edited_after_import) " +
                        "VALUES ('r1', 'u1', 'Tacos', NULL, 2, NULL, NULL, NULL, NULL, '', 10, 20, 'created', NULL, 0)",
                )
                db.execSQL(
                    "INSERT INTO pantry_lot (id, provider_id, product_id, name, name_key, quantity_milli, unit, package_quantity_milli, " +
                        "opened, opened_at, added_at, best_before, location, presence_only, use_soon_manual, use_soon_dismissed_at, " +
                        "opened_shelf_life_days) VALUES ('l1', NULL, NULL, 'nata', 'nata', 200000, 'ml', 200000, 1, 5, 1, NULL, " +
                        "'nevera', 0, 0, NULL, 4)",
                )
                db.execSQL("INSERT INTO cook_event VALUES ('e1', 'r1', 'Tacos', 2, 9, 0)")
            }
            helper.runMigrationsAndValidate(testDb, 6, true, CocinaMigrations.MIGRATION_5_6).close()

            val database =
                Room
                    .databaseBuilder(ApplicationProvider.getApplicationContext(), CocinaDatabase::class.java, testDb)
                    .addMigrations(*CocinaMigrations.ALL)
                    .allowMainThreadQueries()
                    .build()
            try {
                assertEquals(
                    "nata",
                    database
                        .pantryDao()
                        .lots()
                        .single()
                        .name,
                )
                assertEquals(
                    1,
                    database
                        .pantryDao()
                        .observeLastCooked()
                        .first()
                        .size,
                )

                val shopping = database.shoppingDao()
                shopping.upsertRecipe(ShoppingRecipeEntity("s1", "r1", 4, 1))
                val recipes =
                    com.lechugones.cocina.data.recipe
                        .RoomRecipeRepository(database.recipeDao())
                val recipe = checkNotNull(recipes.get("r1"))
                recipes.save(recipe.copy(title = "Tacos editados"))
                assertEquals(listOf("r1"), shopping.observeRecipes().first().map { it.recipeId }, "saving re-inserts the recipe row")
                recipes.delete("r1")
                assertEquals(emptyList(), shopping.observeRecipes().first(), "deleting the recipe takes it off the list")
            } finally {
                database.close()
            }
        }

    @Test
    fun `v6 recipes survive the migration to v7 without an author, and an author can then be stored`() =
        runTest {
            helper.createDatabase(testDb, 6).use { db ->
                db.execSQL(
                    "INSERT INTO recipe (id, uid, title, description, servings, prep_minutes, cook_minutes, image_path, source, " +
                        "body_md, created_at, updated_at, origin, imported_at, edited_after_import) " +
                        "VALUES ('r1', 'u1', 'Tacos', NULL, 2, NULL, NULL, NULL, 'casa', '', 10, 20, 'created', NULL, 0)",
                )
            }
            helper.runMigrationsAndValidate(testDb, 7, true, CocinaMigrations.MIGRATION_6_7).close()

            val database =
                Room
                    .databaseBuilder(ApplicationProvider.getApplicationContext(), CocinaDatabase::class.java, testDb)
                    .addMigrations(*CocinaMigrations.ALL)
                    .allowMainThreadQueries()
                    .build()
            try {
                val recipes =
                    com.lechugones.cocina.data.recipe
                        .RoomRecipeRepository(database.recipeDao())
                val recipe = checkNotNull(recipes.get("r1"))
                assertEquals("casa", recipe.source)
                assertEquals(null, recipe.author)
                recipes.save(recipe.copy(author = "Joan"))
                assertEquals("Joan", recipes.get("r1")?.author)
            } finally {
                database.close()
            }
        }
}
