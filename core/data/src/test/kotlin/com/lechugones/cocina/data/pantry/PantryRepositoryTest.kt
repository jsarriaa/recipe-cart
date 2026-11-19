package com.lechugones.cocina.data.pantry

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lechugones.cocina.data.db.CocinaDatabase
import com.lechugones.cocina.domain.money.Money
import com.lechugones.cocina.domain.pantry.CookPlanner
import com.lechugones.cocina.domain.pantry.PantryLot
import com.lechugones.cocina.domain.product.Product
import com.lechugones.cocina.domain.product.ProductKey
import com.lechugones.cocina.domain.quantity.Amount
import com.lechugones.cocina.domain.quantity.Quantity
import com.lechugones.cocina.domain.recipe.Ingredient
import com.lechugones.cocina.domain.recipe.Recipe
import com.lechugones.cocina.domain.units.MeasureUnit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class PantryRepositoryTest {
    private val now = Instant.parse("2026-11-20T19:00:00Z")
    private val database =
        Room
            .inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), CocinaDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    private val repository = PantryRepository(database.pantryDao(), Clock.fixed(now, ZoneOffset.UTC))

    @After
    fun close() = database.close()

    private val tortillas =
        Product(
            ProductKey("mercadona", "80870"),
            "Maxi tortillas de trigo Hacendado",
            Quantity(Amount.of(360), MeasureUnit.G),
            Money(150),
            packaging = "Paquete",
            unitCount = 6,
        )

    private val recipe =
        Recipe(
            "r1",
            "u1",
            "Tacos",
            2,
            listOf(Ingredient("i1", "tortilla de trigo maxi", Amount.of(4), MeasureUnit.UD)),
            Instant.EPOCH,
            Instant.EPOCH,
        )

    @Test
    fun `a product becomes a sealed lot measured in pieces, and lots round-trip`() =
        runTest {
            val lot = repository.save(repository.newLotFor(tortillas, bestBefore = LocalDate.parse("2026-12-01")))
            assertEquals(Amount.of(6), lot.quantity)
            assertEquals(MeasureUnit.UD, lot.unit)
            assertEquals(listOf(lot), repository.observeLots().first())
        }

    @Test
    fun `cooking deducts in one go and undo restores the exact lots`() =
        runTest {
            val lot = repository.save(repository.newLotFor(tortillas).copy(name = "tortilla de trigo maxi"))
            val plan = CookPlanner.plan(recipe, 2, repository.lots())
            val event = repository.cook(recipe, 2, plan.ingredients.flatMap { it.takes }, emptySet())

            val after = checkNotNull(repository.lot(lot.id))
            assertEquals(Amount.of(2), after.quantity)
            assertTrue(after.opened)
            assertEquals(now, after.openedAt)
            assertEquals(mapOf("r1" to now), repository.observeLastCooked().first())

            assertTrue(repository.undo(event))
            assertEquals(lot, repository.lot(lot.id))
            assertFalse(repository.undo(event), "undo runs once")
            assertTrue(repository.observeLastCooked().first().isEmpty())
        }

    @Test
    fun `a lot used up is removed and comes back on undo`() =
        runTest {
            val lot =
                repository.save(
                    PantryLot("l1", "tortilla de trigo maxi", now, quantity = Amount.of(4), unit = MeasureUnit.UD),
                )
            val plan = CookPlanner.plan(recipe, 2, repository.lots())
            val event = repository.cook(recipe, 2, plan.ingredients.flatMap { it.takes }, emptySet())
            assertNull(repository.lot("l1"))
            repository.undo(event)
            assertEquals(lot, repository.lot("l1"))
        }

    @Test
    fun `shelf life is remembered and a new best-before clears a dismissed tag`() =
        runTest {
            val lot =
                repository.save(
                    PantryLot("n", "nata", now, quantity = Amount.of(200), unit = MeasureUnit.ML, openedShelfLifeDays = 4),
                )
            assertEquals(4, repository.rememberedShelfLife(null, "Nata"))
            val dismissed = repository.save(lot.copy(useSoonDismissedAt = now, bestBefore = LocalDate.parse("2026-11-21")))
            assertNull(dismissed.useSoonDismissedAt, "the best-before changed")
            val kept = repository.save(dismissed.copy(useSoonDismissedAt = now))
            assertEquals(now, kept.useSoonDismissedAt)
        }
}
