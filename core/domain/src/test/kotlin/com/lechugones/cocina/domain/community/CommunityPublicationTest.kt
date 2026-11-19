package com.lechugones.cocina.domain.community

import com.lechugones.cocina.domain.product.ProductKey
import com.lechugones.cocina.domain.quantity.Amount
import com.lechugones.cocina.domain.recipe.Ingredient
import com.lechugones.cocina.domain.recipe.IngredientLink
import com.lechugones.cocina.domain.recipe.Recipe
import com.lechugones.cocina.domain.recipepack.RecipeDocumentParser
import com.lechugones.cocina.domain.units.MeasureUnit
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CommunityPublicationTest {
    private fun recipe(author: String? = null) =
        Recipe(
            id = "r1",
            uid = "receta-tacos-01",
            title = "Tacos tex mex",
            servings = 2,
            ingredients =
                listOf(
                    Ingredient(
                        id = "i1",
                        name = "tortilla de trigo",
                        quantity = Amount.of(4),
                        unit = MeasureUnit.UD,
                        links = listOf(IngredientLink(ProductKey("mercadona", "80870"), preferred = true)),
                    ),
                ),
            createdAt = Instant.EPOCH,
            updatedAt = Instant.EPOCH,
            tags = listOf("mexicana"),
            imagePath = "images/abc.jpg",
            author = author,
        )

    @Test
    fun `the published text carries the author and no links or photo, and parses back`() {
        val ready = assertIs<CommunityPublication.Prepared.Ready>(CommunityPublication.prepare(recipe(), "Joan"))
        assertTrue("author: Joan" in ready.text)
        assertFalse("links:" in ready.text)
        assertFalse("image:" in ready.text)
        val parsed = RecipeDocumentParser.parse(ready.text)
        val draft = assertNotNull(parsed.draft, parsed.issues.toString())
        assertEquals("Joan", draft.author)
        assertEquals("Tacos tex mex", draft.title)
        assertEquals(listOf("mexicana"), draft.tags)
        assertNull(draft.image)
    }

    @Test
    fun `a recipe already signed by the same name can be published again`() {
        assertIs<CommunityPublication.Prepared.Ready>(CommunityPublication.prepare(recipe(author = " joan "), "Joan"))
    }

    @Test
    fun `someone else's recipe is refused`() {
        assertEquals(
            CommunityPublication.Prepared.OtherAuthor("Marta"),
            CommunityPublication.prepare(recipe(author = "Marta"), "Joan"),
        )
    }

    @Test
    fun `names are trimmed, single spaced and at most 60 characters`() {
        assertEquals("Joan S", CommunityPublication.cleanName("  Joan   S "))
        assertNull(CommunityPublication.cleanName("   "))
        assertNull(CommunityPublication.cleanName("x".repeat(61)))
        assertEquals("x".repeat(60), CommunityPublication.cleanName("x".repeat(60)))
    }
}
