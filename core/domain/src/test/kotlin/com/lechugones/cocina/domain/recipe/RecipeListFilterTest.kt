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
    fun `recently cooked first, never cooked last by name`() {
        val cooked =
            recipes.mapIndexed { i, recipe ->
                when (i) {
                    1 -> recipe.copy(lastCookedAt = Instant.parse("2026-11-10T20:00:00Z"))
                    3 -> recipe.copy(lastCookedAt = Instant.parse("2026-11-18T20:00:00Z"))
                    else -> recipe
                }
            }
        assertEquals(
            listOf("arroz al horno", "Nachos", "Ñoquis con tomate", "Tacos de pollo"),
            RecipeListFilter.apply(cooked, "", null, RecipeSort.RECENTLY_COOKED).map { it.title },
        )
    }
}
