package com.lechugones.cocina.domain.recipe

import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals

class RecipeListFilterTest {
    private fun summary(
        id: String,
        title: String,
        tags: List<String> = emptyList(),
        ingredients: List<String> = emptyList(),
        day: Int = 1,
    ) = RecipeSummary(
        id = id,
        title = title,
        servings = 2,
        tags = tags,
        ingredientKeys = ingredients.map(NameKey::of).toSet(),
        createdAt = Instant.parse("2026-11-%02dT00:00:00Z".format(day)),
    )

    private val recipes =
        listOf(
            summary("1", "Ñoquis con tomate", listOf("italiana", "rápida"), listOf("ñoquis de patata", "tomate frito"), day = 3),
            summary("2", "Nachos", listOf("mexicana"), listOf("totopos", "queso cheddar"), day = 1),
            summary("3", "Tacos de pollo", listOf("mexicana", "cena"), listOf("contramuslo de pollo", "aguacate"), day = 2),
            summary("4", "arroz al horno", emptyList(), listOf("arroz", "garbanzos"), day = 4),
        )

    private fun ids(
        query: String = "",
        tag: String? = null,
        sort: RecipeSort = RecipeSort.NAME,
    ) = RecipeListFilter.apply(recipes, query, tag, sort).map { it.id }

    @Test
    fun `name sort follows Spanish order, ignoring case and accents`() {
        assertEquals(listOf("4", "2", "1", "3"), ids())
    }

    @Test
    fun `newest first`() {
        assertEquals(listOf("4", "1", "3", "2"), ids(sort = RecipeSort.NEWEST))
    }

    @Test
    fun `search matches titles, tags and ingredients without accents`() {
        assertEquals(listOf("1"), ids("noquis"))
        assertEquals(listOf("3"), ids("AGUACATE"))
        assertEquals(listOf("2", "3"), ids("mexicana"))
        assertEquals(listOf("1"), ids("tomate rapida"), "every word must match somewhere")
        assertEquals(emptyList(), ids("lentejas"))
    }

    @Test
    fun `tag filter`() {
        assertEquals(listOf("2", "3"), ids(tag = "mexicana"))
        assertEquals(listOf("3"), ids("pollo", tag = "mexicana"))
    }

    @Test
    fun `distinct sorted tags`() {
        assertEquals(listOf("cena", "italiana", "mexicana", "rápida"), RecipeListFilter.tags(recipes))
    }

    @Test
    fun `cheapest per serving first, recipes without a price last by name`() {
        val priced =
            recipes.mapIndexed { i, recipe ->
                when (i) {
                    0 -> {
                        recipe.copy(
                            costPerServing =
                                com.lechugones.cocina.domain.money
                                    .Money(240),
                        )
                    }

                    2 -> {
                        recipe.copy(
                            costPerServing =
                                com.lechugones.cocina.domain.money
                                    .Money(180),
                        )
                    }

                    else -> {
                        recipe
                    }
                }
            }
        assertEquals(
            listOf("Tacos de pollo", "Ñoquis con tomate", "arroz al horno", "Nachos"),
            RecipeListFilter.apply(priced, "", null, RecipeSort.CHEAPEST).map { it.title },
        )
    }
}
