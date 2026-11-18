package com.lechugones.cocina.domain.cost

import com.lechugones.cocina.domain.assertDecimal
import com.lechugones.cocina.domain.assertDecimalNear
import com.lechugones.cocina.domain.money.Money
import com.lechugones.cocina.domain.packaging.PackageFraction
import com.lechugones.cocina.domain.packaging.SimpleFraction
import com.lechugones.cocina.domain.product.BrandKind
import com.lechugones.cocina.domain.product.Product
import com.lechugones.cocina.domain.product.ProductKey
import com.lechugones.cocina.domain.quantity.Amount
import com.lechugones.cocina.domain.quantity.Quantity
import com.lechugones.cocina.domain.recipe.Ingredient
import com.lechugones.cocina.domain.recipe.IngredientLink
import com.lechugones.cocina.domain.recipe.Recipe
import com.lechugones.cocina.domain.units.MeasureUnit
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Uses the sample taco recipe (examples/recipe-packs/tacos-de-pollo.md) with the Mercadona
 * products and prices recorded on 2026-11-18 (docs/providers/mercadona.md). The olive oil and
 * the Día products are made-up test data.
 */
class RecipeCostCalculatorTest {
    private val now = Instant.parse("2026-11-18T12:00:00Z")
    private val options = CostOptions(now = now)
    private val mercadona = ProductSelection.SingleProvider("mercadona")

    private fun mercadona(id: String) = ProductKey("mercadona", id)

    private fun product(
        key: ProductKey,
        name: String,
        grams: Long,
        cents: Long,
        unit: MeasureUnit = MeasureUnit.G,
        unitCount: Int? = null,
        drainedGrams: Long? = null,
        approx: Boolean = false,
        fetchedAt: Instant = now,
        brandKind: BrandKind = BrandKind.STORE,
    ) = Product(
        key = key,
        name = name,
        net = Quantity(Amount.of(grams), unit),
        price = Money(cents),
        brandKind = brandKind,
        unitCount = unitCount,
        drained = drainedGrams?.let { Quantity(Amount.of(it), unit) },
        approx = approx,
        fetchedAt = fetchedAt,
    )

    private val chicken = product(mercadona("2789"), "Contramuslos de pollo deshuesados y sin piel", 550, 374, approx = true)
    private val tortillas = product(mercadona("80870"), "Maxi tortillas de trigo Hacendado", 360, 150, unitCount = 6)
    private val avocado = product(mercadona("3830"), "Aguacate", 200, 112, unitCount = 1, approx = true)
    private val cherry = product(mercadona("69990"), "Tomates cherry", 500, 220, brandKind = BrandKind.UNBRANDED)
    private val mozzarella = product(mercadona("51110"), "Queso rallado mozzarella Hacendado", 200, 160)
    private val seasoning = product(mercadona("23353"), "Sazonador para fajitas Hacendado", 17, 90)
    private val oil = product(mercadona("test-oil"), "Aceite de oliva virgen extra (test)", 1000, 600, unit = MeasureUnit.ML)

    private val catalogue = listOf(chicken, tortillas, avocado, cherry, mozzarella, seasoning, oil).associateBy { it.key }

    private fun ingredient(
        id: String,
        name: String,
        quantity: Long?,
        unit: MeasureUnit,
        vararg links: ProductKey,
        unitWeightG: Long? = null,
        pantry: Boolean = false,
        optional: Boolean = false,
    ) = Ingredient(
        id = id,
        name = name,
        quantity = quantity?.let { Amount.of(it) },
        unit = unit,
        unitWeightG = unitWeightG?.let { Amount.of(it) },
        pantryStaple = pantry,
        optional = optional,
        links = links.map { IngredientLink(it) },
    )

    private fun recipe(vararg ingredients: Ingredient) =
        Recipe(
            id = "r1",
            uid = "ejemplo-tacos-de-pollo",
            title = "Tacos de pollo con aguacate y mozzarella",
            servings = 2,
            ingredients = ingredients.toList(),
            createdAt = now,
            updatedAt = now,
        )

    private val tacos =
        recipe(
            ingredient("chicken", "contramuslo de pollo deshuesado", 300, MeasureUnit.G, chicken.key),
            ingredient("tortillas", "tortilla de trigo maxi", 4, MeasureUnit.UD, tortillas.key, unitWeightG = 60),
            ingredient("avocado", "aguacate", 1, MeasureUnit.UD, avocado.key, unitWeightG = 200),
            ingredient("cherry", "tomate cherry", 150, MeasureUnit.G, cherry.key),
            ingredient("mozzarella", "mozzarella rallada", 60, MeasureUnit.G, mozzarella.key),
            ingredient("seasoning", "sazonador para fajitas", 10, MeasureUnit.G, seasoning.key),
            ingredient("oil", "aceite de oliva virgen extra", 1, MeasureUnit.CDA, oil.key, pantry = true),
            ingredient("salt", "sal", null, MeasureUnit.PIZCA, pantry = true),
        )

    private fun RecipeCost.line(id: String) = lines.single { it.ingredient.id == id }

    @Test
    fun `taco recipe for 2 servings`() {
        val cost = RecipeCostCalculator.compute(tacos, 2, catalogue, mercadona, options)

        // 204 + 100 + 112 + 66 + 48 + 52.94 cents; oil and salt are staples, left out by default.
        assertEquals(Money(583), cost.dishCost)
        assertEquals(Money(291), cost.perServing)
        assertEquals(Money(374 + 150 + 112 + 220 + 160 + 90), cost.wholePackagesCost)
        assertEquals(0, cost.missingPriceCount)
        assertTrue(cost.approximate, "chicken and avocado are variable weight")
        assertEquals(6, cost.purchases.size)
    }

    @Test
    fun `taco lines carry package fractions`() {
        val cost = RecipeCostCalculator.compute(tacos, 2, catalogue, mercadona, options)

        val tortillaLine = cost.line("tortillas")
        assertEquals(MeasureUnit.UD, tortillaLine.usage?.measure?.unit, "counted in tortillas, not grams")
        assertEquals(
            PackageFraction.Fraction(tortillaLine.usage!!.ratio, 0, SimpleFraction.TWO_THIRDS, exact = true),
            tortillaLine.fraction,
        )

        val avocadoLine = cost.line("avocado")
        assertEquals(Money(112), avocadoLine.cost)
        assertIs<PackageFraction.Fraction>(avocadoLine.fraction).let { assertEquals(1, it.whole) }

        val seasoningLine = cost.line("seasoning")
        assertDecimalNear("52.941176", seasoningLine.costCents, tolerance = "0.0001")
        val seasoningFraction = assertIs<PackageFraction.Fraction>(seasoningLine.fraction)
        assertEquals(SimpleFraction.TWO_THIRDS, seasoningFraction.rest)
        assertFalse(seasoningFraction.exact)

        val oilLine = cost.line("oil")
        assertFalse(oilLine.includedInTotals)
        assertEquals(LineStatus.PRICED, oilLine.status)
        assertEquals(PackageFraction.Percent(oilLine.usage!!.ratio, 2), oilLine.fraction)

        val saltLine = cost.line("salt")
        assertEquals(LineStatus.NO_QUANTITY, saltLine.status)
        assertFalse(saltLine.includedInTotals)
    }

    @Test
    fun `leftovers per product`() {
        val cost = RecipeCostCalculator.compute(tacos, 2, catalogue, mercadona, options)
        val byProduct = cost.purchases.associateBy { it.product.key }

        assertDecimal("250", byProduct.getValue(chicken.key).leftover)
        assertDecimal("2", byProduct.getValue(tortillas.key).leftover)
        assertEquals(MeasureUnit.UD, byProduct.getValue(tortillas.key).leftoverUnit)
        assertDecimal("350", byProduct.getValue(cherry.key).leftover)
        assertDecimal("0", byProduct.getValue(avocado.key).leftover)
    }

    @Test
    fun `scaling to 4 servings buys more whole packages`() {
        val cost = RecipeCostCalculator.compute(tacos, 4, catalogue, mercadona, options)
        val packages = cost.purchases.associate { it.product.key to it.packages }

        assertEquals(2, packages[chicken.key], "600 g from 550 g trays")
        assertEquals(2, packages[tortillas.key], "8 of 6 tortillas")
        assertEquals(2, packages[avocado.key])
        assertEquals(1, packages[cherry.key])
        assertEquals(1, packages[mozzarella.key])
        assertEquals(2, packages[seasoning.key])
        assertEquals(Money(748 + 300 + 224 + 220 + 160 + 180), cost.wholePackagesCost)
        assertEquals(Money(1166), cost.dishCost)
    }

    @Test
    fun `staples count when the setting is on`() {
        val cost = RecipeCostCalculator.compute(tacos, 2, catalogue, mercadona, options.copy(includeStaples = true))
        // 1 cda = 15 ml of a 6,00 € litre = 9 cents.
        assertEquals(Money(583 + 9), cost.dishCost)
        assertEquals(Money(1106 + 600), cost.wholePackagesCost)
    }

    @Test
    fun `ingredients sharing a product are consolidated before rounding up`() {
        val oilTwice =
            recipe(
                ingredient("oil1", "aceite de oliva virgen extra", 600, MeasureUnit.ML, oil.key),
                ingredient("oil2", "aceite de oliva virgen extra", 600, MeasureUnit.ML, oil.key),
            )
        val cost = RecipeCostCalculator.compute(oilTwice, 2, catalogue, mercadona, options)
        val purchase = cost.purchases.single()
        assertEquals(2, purchase.packages, "1.2 l needs two bottles, not one per ingredient")
        assertDecimal("800", purchase.leftover)
    }

    @Test
    fun `one third of a package rounds to cents`() {
        val third = product(mercadona("third"), "Test", 300, 100)
        val cost =
            RecipeCostCalculator.compute(
                recipe(ingredient("x", "test", 100, MeasureUnit.G, third.key)),
                2,
                mapOf(third.key to third),
                mercadona,
                options,
            )
        assertEquals(Money(33), cost.dishCost)
        assertEquals(Money(100), cost.wholePackagesCost)
    }

    @Test
    fun `drained weight is the usable quantity`() {
        val freshMozzarella = product(mercadona("51050"), "Mozzarella fresca de vaca Hacendado", 250, 90, drainedGrams = 125)
        val cost =
            RecipeCostCalculator.compute(
                recipe(ingredient("m", "mozzarella fresca", 125, MeasureUnit.G, freshMozzarella.key)),
                2,
                mapOf(freshMozzarella.key to freshMozzarella),
                mercadona,
                options,
            )
        assertEquals(Money(90), cost.dishCost, "125 g drained is the whole ball")
        assertEquals(1, cost.purchases.single().packages)
    }

    @Test
    fun `missing links, prices and incompatible units are reported`() {
        val eggsByWeight = product(mercadona("eggs"), "Huevos (test)", 500, 200)
        val noPrice = product(mercadona("np"), "Sin precio (test)", 500, 0).copy(price = null)
        val catalogue = listOf(eggsByWeight, noPrice).associateBy { it.key }
        val cost =
            RecipeCostCalculator.compute(
                recipe(
                    ingredient("unlinked", "lima", 1, MeasureUnit.UD),
                    ingredient("eggs", "huevo", 2, MeasureUnit.UD, eggsByWeight.key),
                    ingredient("np", "algo", 100, MeasureUnit.G, noPrice.key),
                    ingredient("opt", "cilantro", 5, MeasureUnit.G, optional = true),
                ),
                2,
                catalogue,
                mercadona,
                options,
            )
        assertEquals(LineStatus.NO_LINK, cost.line("unlinked").status)
        assertEquals(LineStatus.INCOMPATIBLE_UNITS, cost.line("eggs").status)
        assertEquals(LineStatus.NO_PRICE, cost.line("np").status)
        assertFalse(cost.line("opt").includedInTotals, "optional ingredients are excluded by default")
        assertEquals(3, cost.missingPriceCount)
        assertEquals(Money.ZERO, cost.dishCost)
    }

    @Test
    fun `optional ingredients count when the setting is on`() {
        val cost =
            RecipeCostCalculator.compute(
                recipe(ingredient("opt", "tomate cherry", 250, MeasureUnit.G, cherry.key, optional = true)),
                2,
                catalogue,
                mercadona,
                options.copy(includeOptionals = true),
            )
        assertEquals(Money(110), cost.dishCost)
    }

    @Test
    fun `stale prices make totals approximate`() {
        val stale = cherry.copy(fetchedAt = now.minus(Duration.ofDays(20)))
        val cost =
            RecipeCostCalculator.compute(
                recipe(ingredient("c", "tomate cherry", 150, MeasureUnit.G, stale.key)),
                2,
                mapOf(stale.key to stale),
                mercadona,
                options,
            )
        assertTrue(cost.approximate)
        assertFalse(
            RecipeCostCalculator
                .compute(
                    recipe(ingredient("c", "tomate cherry", 150, MeasureUnit.G, cherry.key)),
                    2,
                    catalogue,
                    mercadona,
                    options,
                ).approximate,
        )
    }

    @Test
    fun `preferred link wins within a provider`() {
        val other = product(mercadona("69930"), "Tomates cherry pera kumato", 300, 170)
        val ing =
            Ingredient(
                id = "c",
                name = "tomate cherry",
                quantity = Amount.of(150),
                unit = MeasureUnit.G,
                links = listOf(IngredientLink(cherry.key), IngredientLink(other.key, preferred = true)),
            )
        val cost = RecipeCostCalculator.compute(recipe(ing), 2, catalogue + (other.key to other), mercadona, options)
        assertEquals(
            other.key,
            cost.lines
                .single()
                .product
                ?.key,
        )
    }

    @Test
    fun `cheapest mode compares whole-package cost across providers`() {
        val mercadonaRice = product(mercadona("rice"), "Arroz (test)", 500, 200)
        val diaRice = product(ProductKey("dia", "rice"), "Arroz (test)", 1000, 300)
        val catalogue = listOf(mercadonaRice, diaRice).associateBy { it.key }
        val cheapest = ProductSelection.Cheapest(setOf("mercadona", "dia"))

        fun chosenFor(grams: Long) =
            RecipeCostCalculator
                .compute(
                    recipe(ingredient("rice", "arroz", grams, MeasureUnit.G, mercadonaRice.key, diaRice.key)),
                    2,
                    catalogue,
                    cheapest,
                    options,
                ).lines
                .single()
                .product
                ?.key

        assertEquals(diaRice.key, chosenFor(600), "2 × 2,00 € > 1 × 3,00 €")
        assertEquals(mercadonaRice.key, chosenFor(400), "1 × 2,00 € < 1 × 3,00 €")
    }

    @Test
    fun `whole packages tolerate rounding noise`() {
        assertEquals(1, RecipeCostCalculator.packagesFor(java.math.BigDecimal("1.0000001")))
        assertEquals(2, RecipeCostCalculator.packagesFor(java.math.BigDecimal("1.001")))
        assertEquals(1, RecipeCostCalculator.packagesFor(java.math.BigDecimal("0.2")))
    }
}
