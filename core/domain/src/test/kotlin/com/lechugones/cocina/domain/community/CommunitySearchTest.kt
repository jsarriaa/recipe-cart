package com.lechugones.cocina.domain.community

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class CommunitySearchTest {
    private val tacos =
        CommunityRecipe(
            path = "recetas/tacos.md",
            title = "Tacos tex mex",
            servings = 2,
            author = "Joan",
            totalMinutes = 30,
            tags = listOf("mexicana", "cena"),
            ingredients = listOf("tortilla de trigo", "contramuslo de pollo"),
        )
    private val lentils =
        CommunityRecipe(
            path = "recetas/lentejas.md",
            title = "Lentejas estofadas",
            servings = 4,
            author = "Marta",
            totalMinutes = 60,
            tags = listOf("cuchara", "vegana"),
            ingredients = listOf("lenteja pardina", "zanahoria"),
        )
    private val salad =
        CommunityRecipe(path = "recetas/ensalada.md", title = "Ensalada César", servings = 2, tags = listOf("cena", "vegetariana"))
    private val all = listOf(tacos, lentils, salad)

    private fun titles(filter: CommunityFilter) = CommunitySearch.filter(all, filter).map { it.title }

    @Test
    fun `no filter keeps everything in index order`() {
        assertEquals(listOf("Tacos tex mex", "Lentejas estofadas", "Ensalada César"), titles(CommunityFilter()))
    }

    @Test
    fun `every word must match, ignoring case and accents, across all fields`() {
        assertEquals(listOf("Ensalada César"), titles(CommunityFilter(query = "CESAR")))
        assertEquals(listOf("Tacos tex mex"), titles(CommunityFilter(query = "pollo joan")))
        assertEquals(listOf("Lentejas estofadas"), titles(CommunityFilter(query = "zanahoria")))
        assertEquals(emptyList(), titles(CommunityFilter(query = "pollo marta")))
    }

    @Test
    fun `all chosen tags must be present`() {
        assertEquals(listOf("Tacos tex mex", "Ensalada César"), titles(CommunityFilter(tags = setOf("cena"))))
        assertEquals(listOf("Ensalada César"), titles(CommunityFilter(tags = setOf("cena", "vegetariana"))))
    }

    @Test
    fun `time limit leaves out slower recipes and recipes without a time`() {
        assertEquals(listOf("Tacos tex mex"), titles(CommunityFilter(maxMinutes = 30)))
        assertEquals(listOf("Tacos tex mex", "Lentejas estofadas"), titles(CommunityFilter(maxMinutes = 60)))
    }

    @Test
    fun `tags are listed most used first, then alphabetically`() {
        assertEquals(listOf("cena", "cuchara", "mexicana", "vegana", "vegetariana"), CommunitySearch.tags(all))
    }
}
