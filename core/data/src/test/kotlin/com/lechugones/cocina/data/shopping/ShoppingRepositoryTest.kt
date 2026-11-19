package com.lechugones.cocina.data.shopping

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lechugones.cocina.data.catalogue.ProductLinker
import com.lechugones.cocina.data.catalogue.toEntity
import com.lechugones.cocina.data.db.CocinaDatabase
import com.lechugones.cocina.data.pantry.PantryRepository
import com.lechugones.cocina.data.recipe.RoomRecipeRepository
import com.lechugones.cocina.data.settings.DataStoreSettingsRepository
import com.lechugones.cocina.data.testing.InMemoryPreferencesDataStore
import com.lechugones.cocina.domain.money.Money
import com.lechugones.cocina.domain.product.Product
import com.lechugones.cocina.domain.product.ProductKey
import com.lechugones.cocina.domain.quantity.Amount
import com.lechugones.cocina.domain.quantity.Quantity
import com.lechugones.cocina.domain.recipe.Ingredient
import com.lechugones.cocina.domain.recipe.IngredientLink
import com.lechugones.cocina.domain.recipe.Recipe
import com.lechugones.cocina.domain.shopping.ShoppingMode
import com.lechugones.cocina.domain.units.MeasureUnit
import com.lechugones.cocina.provider.ProviderProduct
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class ShoppingRepositoryTest {
    private val now = Instant.parse("2026-11-20T10:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val database =
        Room
            .inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), CocinaDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    private val settings = DataStoreSettingsRepository(InMemoryPreferencesDataStore())
    private val recipes = RoomRecipeRepository(database.recipeDao())
    private val pantry = PantryRepository(database.pantryDao(), clock)
    private val linker = ProductLinker(database.productDao(), database.linkMemoryDao(), settings, clock)
    private val shopping = ShoppingRepository(database.shoppingDao(), recipes, pantry, linker, settings, clock)

    private val tortillas =
        Product(
            ProductKey("mercadona", "80870"),
            "Maxi tortillas de trigo Hacendado",
            Quantity(Amount.of(360), MeasureUnit.G),
            Money(150),
            unitCount = 6,
            fetchedAt = now,
        )

    @After
    fun close() = database.close()

    private suspend fun seed() {
        database.productDao().upsert(listOf(ProviderProduct(tortillas).toEntity(now)))
        recipes.save(
            Recipe(
                "r1",
                "u1",
                "Tacos",
                2,
                listOf(
                    Ingredient(
                        "i1",
                        "tortilla de trigo maxi",
                        Amount.of(4),
                        MeasureUnit.UD,
                        links = listOf(IngredientLink(tortillas.key, true)),
                    ),
                    Ingredient("i2", "cilantro", null, MeasureUnit.AL_GUSTO),
                ),
                now,
                now,
            ),
        )
    }

    @Test
    fun `recipes added twice add up, and Terminar compra moves checked items to the pantry`() =
        runTest {
            seed()
            shopping.addRecipe("r1", 2)
            shopping.addRecipe("r1", 2)
            var state = shopping.observe().first()
            val item = state.list.items.single { it.product?.key == tortillas.key }
            assertEquals(2, item.packages, "8 tortillas, 6 per package")
            assertEquals(Money(300), state.list.total)
            assertEquals(2, state.contributions.size)

            shopping.setChecked(item.key, true)
            assertTrue(
                shopping
                    .observe()
                    .first()
                    .list.items
                    .single { it.key == item.key }
                    .checked,
            )
            assertEquals(2, shopping.finishShopping())

            state = shopping.observe().first()
            assertEquals(2, pantry.lots().count { it.product == tortillas.key })
            assertTrue(state.list.items.none { it.product?.key == tortillas.key }, "12 tortillas at home now")
            assertEquals(listOf("cilantro"), state.list.items.map { it.name }, "the unchecked item stays")
            assertEquals(2, state.contributions.size, "the recipes still need the cilantro")
        }

    @Test
    fun `manual items, mode and clearing`() =
        runTest {
            seed()
            shopping.addManual("Servilletas")
            shopping.addManual("", tortillas.key, packages = 3)
            var list = shopping.observe().first().list
            assertEquals(listOf("Maxi tortillas de trigo Hacendado", "Servilletas"), list.items.map { it.name })
            assertEquals(Money(450), list.total)
            shopping.setCheapest(true)
            assertEquals(ShoppingMode.CHEAPEST, shopping.observe().first().mode)

            val servilletas = list.items.single { it.name == "Servilletas" }
            shopping.setChecked(servilletas.key, true)
            shopping.finishShopping()
            list = shopping.observe().first().list
            assertTrue(list.items.none { it.name == "Servilletas" }, "checked manual items leave the list")
            assertTrue(pantry.lots().any { it.name == "Servilletas" && it.presenceOnly })

            shopping.addRecipe("r1", 2)
            shopping.clear()
            assertTrue(
                shopping
                    .observe()
                    .first()
                    .list.isEmpty,
            )
        }
}
