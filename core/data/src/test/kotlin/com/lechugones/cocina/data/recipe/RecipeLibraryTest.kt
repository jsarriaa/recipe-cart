package com.lechugones.cocina.data.recipe

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lechugones.cocina.data.catalogue.ProductLinker
import com.lechugones.cocina.data.catalogue.toEntity
import com.lechugones.cocina.data.db.CocinaDatabase
import com.lechugones.cocina.data.image.RecipeImageStore
import com.lechugones.cocina.data.settings.DataStoreSettingsRepository
import com.lechugones.cocina.data.testing.InMemoryPreferencesDataStore
import com.lechugones.cocina.domain.product.ProductKey
import com.lechugones.cocina.domain.recipe.IngredientLink
import com.lechugones.cocina.domain.recipe.RecipeOrigin
import com.lechugones.cocina.domain.recipepack.DuplicateChoice
import com.lechugones.cocina.domain.recipepack.RecipePackReader
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class RecipeLibraryTest {
    private val now = Instant.parse("2026-11-20T08:00:00Z")
    private lateinit var database: CocinaDatabase
    private lateinit var repository: RoomRecipeRepository
    private lateinit var images: RecipeImageStore
    private lateinit var library: RecipeLibrary
    private lateinit var linker: ProductLinker

    private val tacos = File("../../examples/recipe-packs/tacos-de-pollo.md").readText()

    @Before
    fun setUp() {
        database = inMemoryDatabase()
        repository = RoomRecipeRepository(database.recipeDao())
        images = RecipeImageStore(ApplicationProvider.getApplicationContext())
        images.directory.listFiles()?.forEach { it.delete() }
        val clock = Clock.fixed(now, ZoneOffset.UTC)
        linker =
            ProductLinker(
                database.productDao(),
                database.linkMemoryDao(),
                DataStoreSettingsRepository(InMemoryPreferencesDataStore()),
                clock,
            )
        library = RecipeLibrary(repository, images, clock, linker)
    }

    @After
    fun tearDown() = database.close()

    private fun photo(name: String) = images.file(name).apply { writeBytes(byteArrayOf(1, 2, 3)) }

    @Test
    fun `editing an imported recipe marks it as edited`() =
        runTest {
            val original = testRecipe()
            repository.save(original)

            val unchanged = library.saveFromEditor(original, original)
            assertFalse(unchanged.editedAfterImport, "saving without changes is not an edit")

            val edited = library.saveFromEditor(original.copy(title = "Tacos míos"), original)
            assertTrue(edited.editedAfterImport)
            assertEquals(now, edited.updatedAt)
            assertEquals(original.createdAt, edited.createdAt)
            assertEquals(edited, repository.get("r1"))
        }

    @Test
    fun `new recipes get creation dates`() =
        runTest {
            val saved = library.saveFromEditor(testRecipe("n").copy(origin = RecipeOrigin.CREATED, importedAt = null), null)
            assertEquals(now, saved.createdAt)
            assertFalse(saved.editedAfterImport)
        }

    @Test
    fun `replacing or removing a photo deletes the old file`() =
        runTest {
            val old = photo("old.jpg")
            val original = testRecipe(imagePath = "old.jpg")
            repository.save(original)
            library.saveFromEditor(original.copy(imagePath = "new.jpg"), original)
            assertFalse(old.exists())
        }

    @Test
    fun `delete removes the photo too`() =
        runTest {
            val file = photo("tacos.jpg")
            repository.save(testRecipe(imagePath = "tacos.jpg"))
            library.delete("r1")
            assertNull(repository.get("r1"))
            assertFalse(file.exists())
        }

    @Test
    fun `duplicate gets new ids, its own photo and created origin`() =
        runTest {
            photo("tacos.jpg")
            repository.save(testRecipe(imagePath = "tacos.jpg"))
            val copy = assertNotNull(library.duplicate("r1") { "$it (copia)" })
            assertEquals("Tacos de pollo con aguacate y mozzarella (copia)", copy.title)
            assertTrue(copy.id != "r1" && copy.uid != testRecipe().uid)
            assertTrue(copy.ingredients.none { it.id.startsWith("r1-") })
            assertEquals(RecipeOrigin.CREATED, copy.origin)
            val copyPhoto = assertNotNull(copy.imagePath)
            assertTrue(copyPhoto != "tacos.jpg" && images.file(copyPhoto).exists())

            library.delete(copy.id)
            assertTrue(images.file("tacos.jpg").exists(), "deleting the copy keeps the original photo")
        }

    @Test
    fun `importing a pack saves the recipe, re-importing respects edits`() =
        runTest {
            val pack = RecipePackReader.readText(tacos, "tacos.md")
            val first = library.planImport(pack)
            val outcome = library.import(first.map { it to null }, pack.images)
            val id = outcome.imported.single()
            val imported = assertNotNull(repository.get(id))
            assertEquals(RecipeOrigin.IMPORTED, imported.origin)
            assertEquals(now, imported.importedAt)

            library.saveFromEditor(imported.copy(servings = 4), imported)

            val second = library.planImport(pack).single()
            assertEquals(DuplicateChoice.SKIP, second.defaultChoice)
            assertTrue(second.replaceNeedsConfirmation)
            assertEquals(0, library.import(listOf(second to null), pack.images).imported.size)
            assertEquals(4, repository.get(id)?.servings, "my edit is kept by default")

            library.import(listOf(second to DuplicateChoice.REPLACE), pack.images)
            assertEquals(2, repository.get(id)?.servings, "explicit replace restores the pack version")
            assertEquals(1, repository.count())
        }

    @Test
    fun `export items carry drafts and photos`() =
        runTest {
            photo("tacos.jpg")
            repository.save(testRecipe(imagePath = "tacos.jpg"))
            repository.save(testRecipe("r2", "Arroz"))
            val items = library.exportItems(null)
            assertEquals(listOf("Arroz", "Tacos de pollo con aguacate y mozzarella"), items.map { it.draft.title })
            assertNull(items[0].image)
            assertEquals(3, items[1].image?.bytes?.size)
        }

    @Test
    fun `linking an ingredient saves it, marks the import as edited and remembers the choice`() =
        runTest {
            val pack = RecipePackReader.readText(tacos, "tacos.md")
            val id = library.import(library.planImport(pack).map { it to null }, emptyMap()).imported.single()
            val recipe = checkNotNull(library.get(id))
            val ingredient = recipe.ingredients.first()
            val link = IngredientLink(ProductKey("mercadona", "80870"), preferred = true)

            val saved = checkNotNull(library.setLinks(id, ingredient.id, listOf(link)))
            assertTrue(saved.editedAfterImport)
            assertEquals(
                listOf(link),
                library
                    .get(id)
                    ?.ingredients
                    ?.first()
                    ?.links,
            )
            assertEquals(
                "80870",
                database
                    .linkMemoryDao()
                    .forName(ingredient.nameKey)
                    .single()
                    .productId,
            )

            library.setLinks(id, ingredient.id, emptyList())
            assertTrue(
                library
                    .get(id)
                    ?.ingredients
                    ?.first()
                    ?.links
                    .orEmpty()
                    .isEmpty(),
            )
        }

    @Test
    fun `file links to products that are not cached are dropped with W07`() =
        runTest {
            val linked =
                tacos.replaceFirst(
                    "    match:",
                    "    links:\n      - provider: mercadona\n        product_id: \"999\"\n    match:",
                )
            val candidate = library.planImport(RecipePackReader.readText(linked, "tacos.md")).single()
            assertTrue(candidate.issues.any { it.code.name == "W07" })
            assertTrue(checkNotNull(candidate.draft).ingredients.all { it.links.isEmpty() })
        }

    @Test
    fun `cost per serving uses linked cached products, null when nothing is priced`() =
        runTest {
            val pack = RecipePackReader.readText(tacos, "tacos.md")
            val id = library.import(library.planImport(pack).map { it to null }, emptyMap()).imported.single()
            repository.save(testRecipe("r2", "Arroz"))
            val chicken = checkNotNull(library.get(id)).ingredients.first()
            database.productDao().upsert(
                listOf(
                    com.lechugones.cocina.provider
                        .ProviderProduct(
                            com.lechugones.cocina.domain.product.Product(
                                ProductKey("mercadona", "p1"),
                                "Contramuslos",
                                com.lechugones.cocina.domain.quantity.Quantity(
                                    com.lechugones.cocina.domain.quantity.Amount
                                        .of(1000),
                                    com.lechugones.cocina.domain.units.MeasureUnit.G,
                                ),
                                com.lechugones.cocina.domain.money
                                    .Money(1000),
                                fetchedAt = now,
                            ),
                        ).toEntity(now),
                ),
            )
            library.setLinks(id, chicken.id, listOf(IngredientLink(ProductKey("mercadona", "p1"), preferred = true)))

            val costs =
                RecipeCosts(
                    repository,
                    linker,
                    DataStoreSettingsRepository(InMemoryPreferencesDataStore()),
                    Clock.fixed(now, ZoneOffset.UTC),
                ).perServing()
            // 300 g of a 1 kg pack at 10,00 €, for 2 servings.
            assertEquals(
                com.lechugones.cocina.domain.money
                    .Money(150),
                costs[id],
            )
            assertNull(costs["r2"])
        }
}
