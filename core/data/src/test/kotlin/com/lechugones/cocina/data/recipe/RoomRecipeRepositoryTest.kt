package com.lechugones.cocina.data.recipe

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.cash.turbine.test
import com.lechugones.cocina.data.db.CocinaDatabase
import com.lechugones.cocina.domain.quantity.Amount
import com.lechugones.cocina.domain.quantity.PackageSize
import com.lechugones.cocina.domain.recipe.Ingredient
import com.lechugones.cocina.domain.recipe.MatchHints
import com.lechugones.cocina.domain.recipe.Recipe
import com.lechugones.cocina.domain.recipe.RecipeOrigin
import com.lechugones.cocina.domain.units.MeasureUnit
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Shared test recipe: an imported taco recipe with tags out of alphabetical order. */
fun testRecipe(
    id: String = "r1",
    title: String = "Tacos de pollo con aguacate y mozzarella",
    createdAt: Instant = Instant.parse("2026-11-18T10:15:30.123Z"),
    imagePath: String? = null,
) = Recipe(
    id = id,
    uid = "uid-$id-0000",
    title = title,
    servings = 2,
    createdAt = createdAt,
    updatedAt = createdAt,
    description = "Tacos rápidos.",
    prepMinutes = 15,
    cookMinutes = 15,
    tags = listOf("mexicana", "cena", "rápida"),
    imagePath = imagePath,
    source = "examples/recipe-packs/tacos-de-pollo.md",
    bodyMarkdown = "## Preparación\n\n1. Corta el pollo.",
    origin = RecipeOrigin.IMPORTED,
    importedAt = createdAt,
    ingredients =
        listOf(
            Ingredient(
                id = "$id-i1",
                name = "tortilla de trigo maxi",
                quantity = Amount.of(4),
                unit = MeasureUnit.UD,
                unitWeightG = Amount.of(60),
                note = "calientes",
                group = "Base",
                match = MatchHints("maxi tortillas trigo", PackageSize.parse("6 ud"), storeBrandOk = true),
            ),
            Ingredient(
                id = "$id-i2",
                name = "aceite de oliva virgen extra",
                quantity = Amount.parse("1.5"),
                unit = MeasureUnit.CDA,
                pantryStaple = true,
                optional = true,
                match = MatchHints("aceite oliva virgen extra", PackageSize.parse("1 l"), storeBrandOk = false),
            ),
            Ingredient(id = "$id-i3", name = "sal", quantity = null, unit = MeasureUnit.PIZCA, pantryStaple = true),
        ),
)

fun inMemoryDatabase(): CocinaDatabase =
    Room
        .inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), CocinaDatabase::class.java)
        .allowMainThreadQueries()
        .build()

@RunWith(AndroidJUnit4::class)
class RoomRecipeRepositoryTest {
    private lateinit var database: CocinaDatabase
    private lateinit var repository: RoomRecipeRepository

    @Before
    fun setUp() {
        database = inMemoryDatabase()
        repository = RoomRecipeRepository(database.recipeDao())
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun `saved recipes read back identically, including tag order and origin`() =
        runTest {
            val recipe = testRecipe()
            repository.save(recipe)
            assertEquals(recipe, repository.get("r1"))
        }

    @Test
    fun `saving again replaces ingredients and tags`() =
        runTest {
            repository.save(testRecipe())
            val edited =
                testRecipe().copy(
                    tags = listOf("rápida"),
                    ingredients = testRecipe().ingredients.take(1),
                    editedAfterImport = true,
                )
            repository.save(edited)
            assertEquals(edited, repository.get("r1"))
        }

    @Test
    fun `delete removes the recipe and its children`() =
        runTest {
            repository.save(testRecipe())
            repository.delete("r1")
            assertNull(repository.get("r1"))
            assertEquals(emptyList(), database.recipeDao().getIngredients("r1"))
            assertEquals(emptyList(), database.recipeDao().getTags("r1"))
        }

    @Test
    fun `summaries carry photo, tags and ingredient keys`() =
        runTest {
            repository.observeSummaries().test {
                assertEquals(emptyList(), awaitItem())
                repository.save(testRecipe(imagePath = "foto.jpg"))
                val summary = awaitItem().single()
                assertEquals("foto.jpg", summary.imagePath)
                assertEquals(listOf("mexicana", "cena", "rápida"), summary.tags)
                assertEquals(setOf("tortilla de trigo maxi", "aceite de oliva virgen extra", "sal"), summary.ingredientKeys)
                assertEquals(testRecipe().createdAt, summary.createdAt)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `existing recipes, photos and getAll`() =
        runTest {
            repository.save(testRecipe("b", "Ñoquis", imagePath = "b.jpg"))
            repository.save(testRecipe("a", "Arroz").copy(editedAfterImport = true))
            val existing = repository.existing().associateBy { it.id }
            assertEquals(true, existing.getValue("a").editedAfterImport)
            assertEquals("uid-b-0000", existing.getValue("b").uid)
            assertEquals(setOf("b.jpg"), repository.imagePaths())
            assertEquals(listOf("a", "b"), repository.getAll().map { it.id })
            assertEquals(2, repository.count())
        }
}
