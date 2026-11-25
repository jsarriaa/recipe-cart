package com.lechugones.cocina.feature.export

import com.lechugones.cocina.domain.format.SpanishNumberFormat
import com.lechugones.cocina.domain.markdown.MdBlock
import com.lechugones.cocina.domain.money.Money
import com.lechugones.cocina.domain.product.Product
import com.lechugones.cocina.domain.product.ProductKey
import com.lechugones.cocina.domain.quantity.Amount
import com.lechugones.cocina.domain.quantity.Quantity
import com.lechugones.cocina.domain.recipe.Ingredient
import com.lechugones.cocina.domain.recipe.IngredientLink
import com.lechugones.cocina.domain.recipe.Recipe
import com.lechugones.cocina.domain.units.MeasureUnit
import com.lechugones.cocina.provider.ProviderProduct
import org.junit.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RecipePrintoutsTest {
    private val words =
        PrintWords(
            brand = "Cocina para Lechugones",
            optional = "opcional",
            ingredients = "Ingredientes",
            stepsEmpty = "Sin pasos",
            servings = { "$it raciones" },
            prep = { "Preparación: $it min" },
            cook = { "Cocción: $it min" },
            staples = { "Básicos de despensa ($it)" },
            footer = { "página $it" },
            quantity = { value, unit -> if (value == null) "al gusto" else "${SpanishNumberFormat.decimal(value)} ${unit.code}" },
        )

    private val eggs = ProductKey("mercadona", "1")
    private val manual = ProductKey("manual", "2")
    private val products =
        mapOf(
            eggs to ProviderProduct(Product(eggs, "Huevos grandes L", Quantity(Amount.of(12), MeasureUnit.UD), Money(305))),
            manual to ProviderProduct(Product(manual, "Aceite del pueblo", Quantity(Amount.of(1000), MeasureUnit.ML), Money(900))),
        )

    private val recipe =
        Recipe(
            id = "r",
            uid = "u",
            title = "Tortilla de patatas",
            servings = 4,
            createdAt = Instant.EPOCH,
            updatedAt = Instant.EPOCH,
            prepMinutes = 15,
            ingredients =
                listOf(
                    Ingredient("1", "huevo", Amount.of(6), MeasureUnit.UD, links = listOf(IngredientLink(eggs, preferred = true))),
                    Ingredient("2", "cebolla", Amount.of(1), MeasureUnit.UD, note = "en juliana", optional = true),
                    Ingredient(
                        "3",
                        "aceite de oliva",
                        null,
                        MeasureUnit.AL_GUSTO,
                        pantryStaple = true,
                        links = listOf(IngredientLink(manual)),
                    ),
                ),
            bodyMarkdown = "## Preparación\n\n1. Pocha la patata.\n2. Cuaja la tortilla.\n",
        )

    private fun build(servings: Int = 4) =
        RecipePrintouts.build(
            words = words,
            recipe = recipe,
            servings = servings,
            photo = null,
            productFor = { ingredient -> ingredient.links.firstNotNullOfOrNull { products[it.product] } },
            shopName = { id -> mapOf("mercadona" to "Mercadona", "manual" to "A mano")[id] },
        )

    @Test
    fun `ingredients name their supermarket and product, never a price`() {
        val printout = build()
        val egg = printout.groups.single().ingredients[0]
        assertEquals("6 ud", egg.quantity)
        assertEquals("Huevos grandes L", egg.product)
        assertEquals("Mercadona", egg.shop)
        val onion = printout.groups.single().ingredients[1]
        assertEquals("cebolla (opcional)", onion.name)
        assertEquals("en juliana", onion.note)
        assertNull(onion.product)
        val all = printout.groups.flatMap { it.ingredients } + printout.staples
        assertTrue(all.none { "€" in it.product.orEmpty() || "3,05" in it.product.orEmpty() })
    }

    @Test
    fun `staples get their own section, with manual products`() {
        val printout = build()
        assertEquals("Básicos de despensa (1)", printout.staplesTitle)
        assertEquals("al gusto", printout.staples.single().quantity)
        assertEquals("Aceite del pueblo", printout.staples.single().product)
        assertEquals("A mano", printout.staples.single().shop)
    }

    @Test
    fun `quantities follow the servings shown`() {
        val printout = build(servings = 2)
        assertEquals(
            "3 ud",
            printout.groups
                .single()
                .ingredients[0]
                .quantity,
        )
        assertEquals(listOf("2 raciones", "Preparación: 15 min"), printout.details)
        assertEquals("página 3", printout.footer(3))
    }

    @Test
    fun `steps are the recipe Markdown`() {
        val steps = build().steps
        assertIs<MdBlock.Heading>(steps[0])
        assertEquals(2, assertIs<MdBlock.OrderedList>(steps[1]).items.size)
    }
}
