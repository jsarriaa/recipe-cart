package com.lechugones.cocina.domain.recipepack

import com.lechugones.cocina.domain.product.ProductKey
import com.lechugones.cocina.domain.recipe.IngredientLink
import com.lechugones.cocina.domain.recipe.RecipeOrigin
import org.junit.jupiter.api.Test
import java.io.File
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ImportPlannerTest {
    private val now = Instant.parse("2026-11-18T12:00:00Z")
    private val earlier = Instant.parse("2026-10-01T09:00:00Z")
    private val tacosText = File(repoRoot, "examples/recipe-packs/tacos-de-pollo.md").readText()

    private fun pack(text: String = tacosText) = RecipePackReader.readText(text, "tacos.md")

    private var counter = 0

    private fun newId() = "id-${++counter}"

    private fun existing(
        uid: String = "otra-cosa-1",
        title: String = "Otra receta",
        edited: Boolean = false,
    ) = ExistingRecipe("existing", uid, title, earlier, edited)

    @Test
    fun `new recipes import with fresh ids and the file uid`() {
        val candidate = ImportPlanner.plan(pack(), emptyList()) { false }.single()
        assertEquals(emptyList(), candidate.issues)
        assertNull(candidate.duplicateOf)

        val recipe = assertNotNull(ImportPlanner.toRecipe(candidate, null, now, ::newId, imagePath = "foto.jpg"))
        assertEquals("ejemplo-tacos-de-pollo", recipe.uid)
        assertTrue(recipe.id.startsWith("id-"))
        assertEquals(RecipeOrigin.IMPORTED, recipe.origin)
        assertEquals(now, recipe.importedAt)
        assertFalse(recipe.editedAfterImport)
        assertEquals("foto.jpg", recipe.imagePath)
        assertEquals(
            8,
            recipe.ingredients
                .map { it.id }
                .toSet()
                .size,
            "every ingredient gets its own id",
        )
    }

    @Test
    fun `duplicates are found by uid, then by title ignoring accents and case`() {
        val byUid = ImportPlanner.plan(pack(), listOf(existing(uid = "ejemplo-tacos-de-pollo"))) { false }.single()
        assertNotNull(byUid.duplicateOf)
        val byTitle = ImportPlanner.plan(pack(), listOf(existing(title = "TACOS de pollo con aguacate y MOZZARELLA"))) { false }.single()
        assertNotNull(byTitle.duplicateOf)
        assertTrue(IssueCode.W10 in byTitle.issues.map { it.code })
        assertNull(ImportPlanner.plan(pack(), listOf(existing())) { false }.single().duplicateOf)
    }

    @Test
    fun `edited recipes default to keeping mine`() {
        val untouched = ImportPlanner.plan(pack(), listOf(existing(uid = "ejemplo-tacos-de-pollo"))) { false }.single()
        assertEquals(DuplicateChoice.REPLACE, untouched.defaultChoice)
        assertFalse(untouched.replaceNeedsConfirmation)

        val edited = ImportPlanner.plan(pack(), listOf(existing(uid = "ejemplo-tacos-de-pollo", edited = true))) { false }.single()
        assertEquals(DuplicateChoice.SKIP, edited.defaultChoice)
        assertTrue(edited.replaceNeedsConfirmation)
        assertNull(ImportPlanner.toRecipe(edited, null, now, ::newId, null), "default for an edited recipe is to skip")
    }

    @Test
    fun `replace keeps the id and creation date, keep both gets new ids`() {
        val candidate = ImportPlanner.plan(pack(), listOf(existing(uid = "ejemplo-tacos-de-pollo"))) { false }.single()

        val replaced = assertNotNull(ImportPlanner.toRecipe(candidate, DuplicateChoice.REPLACE, now, ::newId, null))
        assertEquals("existing", replaced.id)
        assertEquals(earlier, replaced.createdAt)
        assertEquals(now, replaced.updatedAt)

        val copy = assertNotNull(ImportPlanner.toRecipe(candidate, DuplicateChoice.KEEP_BOTH, now, ::newId, null))
        assertTrue(copy.id != "existing")
        assertTrue(copy.uid != "ejemplo-tacos-de-pollo")
        assertEquals(now, copy.createdAt)

        assertNull(ImportPlanner.toRecipe(candidate, DuplicateChoice.SKIP, now, ::newId, null))
    }

    @Test
    fun `unresolvable links are dropped with W07`() {
        val text =
            tacosText.replace(
                "      store_brand_ok: true\n  - name: tortilla",
                "      store_brand_ok: true\n    links:\n      - provider: mercadona\n        product_id: \"2789\"\n" +
                    "      - provider: dia\n        product_id: \"X1\"\n  - name: tortilla",
            )
        val candidate = ImportPlanner.plan(pack(text), emptyList()) { it.provider == "mercadona" }.single()
        assertEquals(listOf(IssueCode.W07), candidate.issues.map { it.code })
        val recipe = assertNotNull(ImportPlanner.toRecipe(candidate, null, now, ::newId, null))
        assertEquals(listOf(IngredientLink(ProductKey("mercadona", "2789"))), recipe.ingredients.first().links)
    }

    @Test
    fun `documents with errors cannot be imported`() {
        val candidate = ImportPlanner.plan(pack("no es una receta"), emptyList()) { false }.single()
        assertFalse(candidate.canImport)
        assertNull(ImportPlanner.toRecipe(candidate, null, now, ::newId, null))
    }

    /** SPEC AC3.1 at the domain level: recipe → export → import gives the same recipe. */
    @Test
    fun `recipes survive export and re-import`() {
        val imported =
            assertNotNull(ImportPlanner.toRecipe(ImportPlanner.plan(pack(), emptyList()) { true }.single(), null, earlier, ::newId, null))
        val exported = RecipePackWriter.writeDocument(RecipeDrafts.fromRecipe(imported))
        val again =
            assertNotNull(
                ImportPlanner.toRecipe(ImportPlanner.plan(pack(exported), emptyList()) { true }.single(), null, earlier, ::newId, null),
            )

        fun normalize(recipe: com.lechugones.cocina.domain.recipe.Recipe) =
            recipe.copy(id = "", ingredients = recipe.ingredients.map { it.copy(id = "") })
        assertEquals(normalize(imported), normalize(again))
    }
}
