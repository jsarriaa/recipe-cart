package com.lechugones.cocina.domain.shopping

import com.lechugones.cocina.domain.money.Money
import com.lechugones.cocina.domain.pantry.PantryLot
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
import java.time.Duration
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** AC6.1. Prices as recorded on 2026-11-18; "manual" products are made up. */
class ShoppingListBuilderTest {
    private val now = Instant.parse("2026-11-20T10:00:00Z")
    private val options = ShoppingOptions(now = now, primaryProvider = "mercadona")

    private fun key(id: String) = ProductKey("mercadona", id)

    private fun product(
        key: ProductKey,
        name: String,
        amount: Long,
        cents: Long?,
        unit: MeasureUnit = MeasureUnit.G,
        unitCount: Int? = null,
        approx: Boolean = false,
        fetchedAt: Instant = now,
    ) = Product(
        key,
        name,
        Quantity(Amount.of(amount), unit),
        cents?.let(::Money),
        unitCount = unitCount,
        approx = approx,
        fetchedAt = fetchedAt,
    )

    private val chicken = product(key("2789"), "Contramuslos de pollo deshuesados y sin piel", 550, 374, approx = true)
    private val tortillas = product(key("80870"), "Maxi tortillas de trigo Hacendado", 360, 150, unitCount = 6)
    private val oil = product(key("oil"), "Aceite de oliva virgen extra", 1000, 600, MeasureUnit.ML)
    private val manualTortillas = product(ProductKey("manual", "t"), "Tortillas del mercado", 360, 90, unitCount = 6)
    private val products = listOf(chicken, tortillas, oil, manualTortillas).associateBy { it.key }

    private fun ingredient(
        id: String,
        name: String,
        amount: Long?,
        unit: MeasureUnit = MeasureUnit.G,
        vararg links: ProductKey,
        pantry: Boolean = false,
        optional: Boolean = false,
    ) = Ingredient(
        id,
        name,
        amount?.let { Amount.of(it) },
        unit,
        pantryStaple = pantry,
        optional = optional,
        links = links.mapIndexed { i, k -> IngredientLink(k, i == 0) },
    )

    private val tacos =
        Recipe(
            "tacos",
            "u1",
            "Tacos",
            2,
            listOf(
                ingredient("c", "contramuslo de pollo", 300, MeasureUnit.G, chicken.key),
                ingredient("t", "tortilla de trigo maxi", 4, MeasureUnit.UD, tortillas.key, manualTortillas.key),
                ingredient("o", "aceite de oliva", 1, MeasureUnit.CDA, oil.key, pantry = true),
                ingredient("s", "sal", null, MeasureUnit.AL_GUSTO, pantry = true),
                ingredient("k", "cilantro fresco", null, MeasureUnit.AL_GUSTO, optional = false),
                ingredient("q", "queso extra", 50, optional = true),
            ),
            Instant.EPOCH,
            Instant.EPOCH,
        )
    private val fajitas =
        Recipe(
            "fajitas",
            "u2",
            "Fajitas",
            4,
            listOf(
                ingredient("c", "contramuslo de pollo", 400, MeasureUnit.G, chicken.key),
                ingredient("t", "tortilla de trigo maxi", 8, MeasureUnit.UD, tortillas.key),
            ),
            Instant.EPOCH,
            Instant.EPOCH,
        )

    private fun lot(
        id: String,
        name: String,
        amount: Long,
        unit: MeasureUnit,
        product: ProductKey? = null,
        opened: Boolean = false,
    ) = PantryLot(id, name, now, product = product, quantity = Amount.of(amount), unit = unit, opened = opened)

    private fun build(
        contributions: List<ShoppingContribution>,
        lots: List<PantryLot> = emptyList(),
        manual: List<ManualShoppingItem> = emptyList(),
        opts: ShoppingOptions = options,
    ) = ShoppingListBuilder.build(contributions, manual, lots, products, opts)

    @Test
    fun `needs are consolidated across recipes before rounding up to packages`() {
        val list = build(listOf(ShoppingContribution("1", tacos, 2), ShoppingContribution("2", fajitas, 4)))
        val chickenItem = list.items.single { it.product == chicken }
        assertEquals(0, BigDecimal("700").compareTo(chickenItem.needed))
        assertEquals(2, chickenItem.packages, "700 g of 550 g packages")
        assertEquals(Money(748), chickenItem.cost)
        assertEquals(listOf(ItemSource("Tacos", 2, "1"), ItemSource("Fajitas", 4, "2")), chickenItem.sources)
        assertEquals(2, list.items.single { it.product == tortillas }.packages, "12 tortillas, 6 per package")
    }

    @Test
    fun `servings scale each contribution`() {
        val list = build(listOf(ShoppingContribution("1", tacos, 4)))
        assertEquals(0, BigDecimal("600").compareTo(list.items.single { it.product == chicken }.needed))
        assertEquals(2, list.items.single { it.product == chicken }.packages)
    }

    @Test
    fun `pantry stock is subtracted, opened lots included, and covered items disappear`() {
        val lots =
            listOf(
                lot("a", "tortillas", 2, MeasureUnit.UD, tortillas.key, opened = true),
                lot("b", "pollo", 550, MeasureUnit.G, chicken.key),
            )
        val list = build(listOf(ShoppingContribution("1", tacos, 2)), lots)
        val tortillaItem = list.items.single { it.product == tortillas }
        assertEquals(0, BigDecimal("2").compareTo(tortillaItem.needed))
        assertEquals(1, tortillaItem.packages)
        assertTrue(list.items.none { it.product == chicken }, "550 g at home covers 300 g")
    }

    @Test
    fun `staples are added only when the pantry is in use and has none of them`() {
        assertTrue(build(listOf(ShoppingContribution("1", tacos, 2))).items.none { it.name == "sal" || it.product == oil }, "empty pantry")
        val withOther = build(listOf(ShoppingContribution("1", tacos, 2)), listOf(lot("x", "arroz", 1000, MeasureUnit.G)))
        assertTrue(withOther.items.any { it.product == oil })
        assertTrue(withOther.items.any { it.name == "sal" })
        val withOil = build(listOf(ShoppingContribution("1", tacos, 2)), listOf(lot("o", "aceite de oliva", 500, MeasureUnit.ML, oil.key)))
        assertTrue(withOil.items.none { it.product == oil })
        val off =
            build(
                listOf(ShoppingContribution("1", tacos, 2)),
                listOf(lot("x", "arroz", 1000, MeasureUnit.G)),
                opts = options.copy(addMissingStaples = false),
            )
        assertTrue(off.items.none { it.name == "sal" })
    }

    @Test
    fun `optional ingredients are left out, unlinked ones go to Sin supermercado`() {
        val list = build(listOf(ShoppingContribution("1", tacos, 2)))
        assertTrue(list.items.none { it.name == "queso extra" })
        val noShop = list.groups.last()
        assertNull(noShop.providerId)
        assertEquals(listOf("cilantro fresco"), noShop.items.map { it.name })
        assertNull(noShop.items.single().packages)
    }

    @Test
    fun `groups carry subtotals, the total is approximate when any price is, and unpriced items are counted`() {
        val unpriced = product(key("np"), "Cilantro", 50, null)
        val list =
            ShoppingListBuilder.build(
                listOf(ShoppingContribution("1", tacos, 2)),
                listOf(ManualShoppingItem("m1", "Cilantro", unpriced.key), ManualShoppingItem("m2", "Servilletas")),
                emptyList(),
                products + (unpriced.key to unpriced),
                options,
            )
        val mercadona = list.groups.first()
        assertEquals("mercadona", mercadona.providerId)
        assertEquals(Money(374 + 150), mercadona.subtotal)
        assertTrue(mercadona.approximate, "the chicken is sold by weight")
        assertEquals(1, mercadona.missingPrices)
        assertEquals(Money(524), list.total)
        assertTrue(list.approximate)
        assertEquals(3, list.missingPrices, "cilantro product, cilantro fresco and servilletas")
    }

    @Test
    fun `cheapest mode and manual overrides pick the supermarket per item`() {
        val cheapest = build(listOf(ShoppingContribution("1", tacos, 2)), opts = options.copy(mode = ShoppingMode.CHEAPEST))
        assertTrue(cheapest.items.any { it.product == manualTortillas })
        val overridden =
            build(
                listOf(ShoppingContribution("1", tacos, 2)),
                opts = options.copy(mode = ShoppingMode.CHEAPEST, overrides = mapOf("tortilla de trigo maxi" to "mercadona")),
            )
        val item = overridden.items.single { it.product == tortillas }
        assertEquals(setOf("mercadona", "manual"), item.providerOptions)
        assertEquals("tortilla de trigo maxi", item.overrideKey)
    }

    @Test
    fun `checks survive rebuilds through stable keys, stale prices are approximate`() {
        val first = build(listOf(ShoppingContribution("1", tacos, 2)))
        val tortillaKey = first.items.single { it.product == tortillas }.key
        val again = build(listOf(ShoppingContribution("1", tacos, 4)), opts = options.copy(checked = setOf(tortillaKey)))
        assertTrue(again.items.single { it.key == tortillaKey }.checked)
        assertFalse(again.items.single { it.product == chicken }.checked)

        val old = products + (tortillas.key to tortillas.copy(fetchedAt = now.minus(Duration.ofDays(30))))
        val stale = ShoppingListBuilder.build(listOf(ShoppingContribution("1", tacos, 2)), emptyList(), emptyList(), old, options)
        assertTrue(stale.items.single { it.product?.key == tortillas.key }.approximate)
    }
}
