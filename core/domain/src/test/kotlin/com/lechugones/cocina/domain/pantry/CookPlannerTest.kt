package com.lechugones.cocina.domain.pantry

import com.lechugones.cocina.domain.cost.CostOptions
import com.lechugones.cocina.domain.cost.ProductSelection
import com.lechugones.cocina.domain.cost.RecipeCostCalculator
import com.lechugones.cocina.domain.money.Money
import com.lechugones.cocina.domain.product.Product
import com.lechugones.cocina.domain.product.ProductKey
import com.lechugones.cocina.domain.quantity.Amount
import com.lechugones.cocina.domain.quantity.Quantity
import com.lechugones.cocina.domain.recipe.Ingredient
import com.lechugones.cocina.domain.recipe.IngredientLink
import com.lechugones.cocina.domain.recipe.Recipe
import com.lechugones.cocina.domain.units.MeasureUnit
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** AC5.1 and the ordering half of AC5.3. Today is 20/11/2026, default 3-day window. */
class CookPlannerTest {
    private val now = Instant.parse("2026-11-20T19:00:00Z")
    private val oil = ProductKey("mercadona", "oil")

    private fun day(n: Int) = Instant.parse("2026-11-01T00:00:00Z").plusSeconds(n * 86_400L)

    private fun lot(
        id: String,
        name: String,
        amount: Long,
        unit: MeasureUnit = MeasureUnit.G,
        added: Int = 1,
        opened: Boolean = false,
        product: ProductKey? = null,
        manual: Boolean = false,
    ) = PantryLot(
        id = id,
        name = name,
        addedAt = day(added),
        product = product,
        quantity = Amount.of(amount),
        unit = unit,
        packageQuantity = Amount.of(amount),
        opened = opened,
        openedAt = if (opened) day(added) else null,
        useSoonManual = manual,
    )

    private fun recipe(
        vararg ingredients: Ingredient,
        servings: Int = 2,
    ) = Recipe("r", "u", "Receta", servings, ingredients.toList(), Instant.EPOCH, Instant.EPOCH)

    private fun ingredient(
        id: String,
        name: String,
        amount: Long?,
        unit: MeasureUnit = MeasureUnit.G,
        links: List<ProductKey> = emptyList(),
        pantry: Boolean = false,
        optional: Boolean = false,
    ) = Ingredient(
        id = id,
        name = name,
        quantity = amount?.let { Amount.of(it) },
        unit = unit,
        pantryStaple = pantry,
        optional = optional,
        links = links.map { IngredientLink(it, true) },
    )

    private fun plan(
        recipe: Recipe,
        lots: List<PantryLot>,
        servings: Int = recipe.servings,
    ) = CookPlanner.plan(recipe, servings, lots)

    @Test
    fun `opened lots go first, then the oldest`() {
        val lots =
            listOf(
                lot("new-sealed", "nata", 200, MeasureUnit.ML, added = 7),
                lot("old-sealed", "nata", 200, MeasureUnit.ML, added = 1),
                lot("new-opened", "nata", 200, MeasureUnit.ML, added = 9, opened = true),
            )
        assertEquals(listOf("new-opened", "old-sealed", "new-sealed"), CookPlanner.consumptionOrder(lots).map { it.id })
        val takes = plan(recipe(ingredient("i", "nata", 300, MeasureUnit.ML)), lots).ingredients.single().takes
        assertEquals(listOf("new-opened" to 200L, "old-sealed" to 100L), takes.map { it.lotId to it.amount.milli / 1000 })
    }

    @Test
    fun `insufficient stock reports what is missing, scaled to the servings`() {
        val deduction = plan(recipe(ingredient("i", "arroz", 150)), listOf(lot("a", "arroz", 200)), servings = 4).ingredients.single()
        assertEquals(0, BigDecimal("300").compareTo(deduction.needed))
        assertEquals(0, BigDecimal("100").compareTo(deduction.missing))
        assertNull(plan(recipe(ingredient("i", "arroz", 150)), listOf(lot("a", "arroz", 200))).ingredients.single().missing)
    }

    @Test
    fun `units are converted, a tablespoon of oil from a 1 l bottle`() {
        val bottle = lot("b", "aceite de oliva", 1000, MeasureUnit.ML, product = oil)
        val deduction =
            plan(recipe(ingredient("i", "aceite de oliva virgen extra", 1, MeasureUnit.CDA, links = listOf(oil))), listOf(bottle))
        assertEquals(
            15_000L,
            deduction.ingredients
                .single()
                .takes
                .single()
                .amount.milli,
        )
        val change = CookPlanner.apply(listOf(bottle), deduction.ingredients.single().takes, emptySet(), now)
        assertEquals(
            985_000L,
            change.updated
                .single()
                .quantity
                ?.milli,
        )
        assertTrue(change.updated.single().opened, "a sealed lot that is partly used becomes opened")
        assertEquals(now, change.updated.single().openedAt)
    }

    @Test
    fun `count units and lots that cannot be converted`() {
        val eggs = lot("e", "huevos", 12, MeasureUnit.UD)
        assertEquals(
            3_000L,
            plan(recipe(ingredient("i", "huevos", 3, MeasureUnit.UD)), listOf(eggs))
                .ingredients
                .single()
                .takes
                .single()
                .amount.milli,
        )
        val flourInUnits = lot("f", "harina", 1, MeasureUnit.UD)
        val deduction = plan(recipe(ingredient("i", "harina", 200)), listOf(flourInUnits)).ingredients.single()
        assertEquals(listOf("f"), deduction.manualLots)
        assertTrue(deduction.takes.isEmpty())
    }

    @Test
    fun `two ingredients share what is left of one lot`() {
        val lots = listOf(lot("t", "tomate", 300))
        val plan = plan(recipe(ingredient("a", "tomate", 200), ingredient("b", "tomate", 200)), lots)
        assertEquals(
            200_000L,
            plan.ingredients[0]
                .takes
                .single()
                .amount.milli,
        )
        assertEquals(
            100_000L,
            plan.ingredients[1]
                .takes
                .single()
                .amount.milli,
        )
        assertEquals(0, BigDecimal("100").compareTo(plan.ingredients[1].missing))
    }

    @Test
    fun `presence-only staples are offered, never deducted, and lots that reach zero are removed`() {
        val salt = PantryLot(id = "s", name = "sal", addedAt = day(1), presenceOnly = true)
        val rice = lot("r", "arroz", 150)
        val plan =
            plan(recipe(ingredient("i", "sal", null, MeasureUnit.PIZCA, pantry = true), ingredient("j", "arroz", 150)), listOf(salt, rice))
        assertEquals(listOf("s"), plan.ingredients[0].presenceLots)
        val change = CookPlanner.apply(listOf(salt, rice), plan.ingredients.flatMap { it.takes }, finished = emptySet(), now = now)
        assertEquals(listOf("r"), change.removed)
        assertTrue(change.updated.isEmpty())
        assertEquals(
            listOf("s", "r"),
            CookPlanner.apply(listOf(salt, rice), plan.ingredients.flatMap { it.takes }, setOf("s"), now).removed,
        )
    }

    @Test
    fun `undo restores the exact previous state`() {
        val before = listOf(lot("a", "arroz", 500), lot("b", "nata", 200, MeasureUnit.ML, opened = true))
        val plan = plan(recipe(ingredient("i", "arroz", 100), ingredient("j", "nata", 200, MeasureUnit.ML)), before)
        val change = CookPlanner.apply(before, plan.ingredients.flatMap { it.takes }, emptySet(), now)
        val after = before.filter { it.id !in change.removed }.map { lot -> change.updated.firstOrNull { it.id == lot.id } ?: lot }
        // Undo puts back the snapshots of every lot the change touched.
        val touched = (change.updated.map { it.id } + change.removed).toSet()
        val restored = (after.filter { it.id !in touched } + before.filter { it.id in touched }).sortedBy { it.id }
        assertEquals(before, restored)
    }

    @Test
    fun `coverage ignores staples and optionals and sorts by what is missing`() {
        val cream = lot("c", "nata", 200, MeasureUnit.ML)
        val pasta = lot("p", "pasta", 500)
        val carbonara =
            Recipe(
                "carbonara",
                "u1",
                "Carbonara",
                2,
                listOf(
                    ingredient("1", "pasta", 200),
                    ingredient("2", "nata", 100, MeasureUnit.ML),
                    ingredient("3", "sal", 1, MeasureUnit.PIZCA, pantry = true),
                    ingredient("4", "perejil", 5, optional = true),
                ),
                Instant.EPOCH,
                Instant.EPOCH,
            )
        val plain = Recipe("plain", "u2", "Pasta sola", 2, listOf(ingredient("1", "pasta", 200)), Instant.EPOCH, Instant.EPOCH)
        val missingTwo =
            Recipe(
                "salad",
                "u3",
                "Ensalada",
                2,
                listOf(ingredient("1", "lechuga", 200), ingredient("2", "tomate", 100), ingredient("3", "pasta", 50)),
                Instant.EPOCH,
                Instant.EPOCH,
            )
        val result = CookCoverage.evaluate(listOf(missingTwo, plain, carbonara), listOf(pasta, cream))
        assertEquals(listOf("plain", "carbonara", "salad"), result.map { it.recipe.id }, "both complete first, in list order")
        assertEquals(CoverageStatus.ALL, result[0].status)
        assertEquals(CoverageStatus.FEW_MISSING, result[2].status)
        assertEquals(listOf("lechuga", "tomate"), result[2].missing.map { it.name })
        assertTrue(result[2].partlyMissing.isEmpty(), "pasta is fully covered, the others are not in the pantry at all")
        val short = CookCoverage.evaluate(listOf(plain), listOf(lot("p", "pasta", 150))).single()
        assertEquals(0, BigDecimal("50").compareTo(short.partlyMissing["1"]))
    }

    @Test
    fun `te falta comprar buys only what the pantry does not cover`() {
        val riceKey = ProductKey("mercadona", "rice")
        val rice = Product(riceKey, "Arroz", Quantity(Amount.of(1000), MeasureUnit.G), Money(120))
        val recipe = recipe(ingredient("i", "arroz", 1200, links = listOf(riceKey)))
        val plan = plan(recipe, listOf(lot("a", "arroz", 500, product = riceKey)))
        val remaining = CookCoverage.remainingToBuy(recipe, plan)
        val cost =
            RecipeCostCalculator.compute(
                remaining,
                plan.servings,
                mapOf(riceKey to rice),
                ProductSelection.SingleProvider("mercadona"),
                CostOptions(now),
            )
        assertEquals(Money(120), cost.wholePackagesCost, "700 g missing: one 1 kg package, not two")
        val covered = CookCoverage.remainingToBuy(recipe, plan(recipe, listOf(lot("a", "arroz", 2000, product = riceKey))))
        assertTrue(covered.ingredients.isEmpty())
    }
}
