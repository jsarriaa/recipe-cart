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
import com.lechugones.cocina.domain.recipe.RecipeSummary
import com.lechugones.cocina.domain.units.MeasureUnit
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNull

@RunWith(AndroidJUnit4::class)
class RoomRecipeRepositoryTest {
    private lateinit var database: CocinaDatabase
    private lateinit var repository: RoomRecipeRepository

    private val createdAt = Instant.parse("2026-11-18T10:15:30.123Z")

    @Before
    fun setUp() {
        database =
            Room
                .inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), CocinaDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        repository = RoomRecipeRepository(database.recipeDao())
    }

    @After
    fun tearDown() = database.close()

    private fun tacos(
        id: String = "r1",
        title: String = "Tacos de pollo con aguacate y mozzarella",
    ) = Recipe(
        id = id,
        uid = "uid-$id",
        title = title,
        servings = 2,
        createdAt = createdAt,
        updatedAt = createdAt,
        description = "Tacos rápidos.",
        prepMinutes = 15,
        cookMinutes = 15,
        tags = listOf("cena", "mexicana"),
        source = "examples/recipe-packs/tacos-de-pollo.md",
        bodyMarkdown = "## Preparación\n\n1. Corta el pollo.",
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

    @Test
    fun `saved recipes read back identically`() =
        runTest {
            val recipe = tacos()
            repository.save(recipe)
            assertEquals(recipe, repository.get("r1"))
        }

    @Test
    fun `saving again replaces ingredients and tags`() =
        runTest {
            repository.save(tacos())
            val edited = tacos().copy(tags = listOf("rápida"), ingredients = tacos().ingredients.take(1))
            repository.save(edited)
            assertEquals(edited, repository.get("r1"))
        }

    @Test
    fun `delete removes the recipe and its children`() =
        runTest {
            repository.save(tacos())
            repository.delete("r1")
            assertNull(repository.get("r1"))
            assertEquals(emptyList(), database.recipeDao().getIngredients("r1"))
            assertEquals(emptyList(), database.recipeDao().getTags("r1"))
        }

    @Test
    fun `summaries are observed sorted by title`() =
        runTest {
            repository.observeSummaries().test {
                assertEquals(emptyList(), awaitItem())
                repository.save(tacos(id = "b", title = "ñoquis con tomate"))
                awaitItem()
                repository.save(tacos(id = "a", title = "Arroz al horno"))
                assertEquals(
                    listOf(RecipeSummary("a", "Arroz al horno", 2), RecipeSummary("b", "ñoquis con tomate", 2)),
                    awaitItem(),
                )
                cancelAndIgnoreRemainingEvents()
            }
        }
}
