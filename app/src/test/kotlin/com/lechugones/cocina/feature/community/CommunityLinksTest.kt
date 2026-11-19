package com.lechugones.cocina.feature.community

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CommunityLinksTest {
    @Test
    fun `recipe files inside the book become raw URLs`() {
        assertEquals(
            "https://raw.githubusercontent.com/jsarriaa/recetario/main/recipes/tacos-de-pollo.md",
            CommunityLinks.recipeUrl("recipes/tacos-de-pollo.md"),
        )
        assertEquals(CommunityLinks.RAW_BASE + "recipes/mexicana/tacos.md", CommunityLinks.recipeUrl("recipes/mexicana/tacos.md"))
    }

    @Test
    fun `paths that could leave the book are refused`() {
        assertNull(CommunityLinks.recipeUrl("../recipe-cart/main/x.md"))
        assertNull(CommunityLinks.recipeUrl("recipes/../../x.md"))
        assertNull(CommunityLinks.recipeUrl("https://example.com/x.md"))
        assertNull(CommunityLinks.recipeUrl("recipes/x.txt"))
        assertNull(CommunityLinks.recipeUrl("index.json"))
    }
}
