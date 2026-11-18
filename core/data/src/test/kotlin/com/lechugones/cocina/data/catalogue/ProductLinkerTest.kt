package com.lechugones.cocina.data.catalogue

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lechugones.cocina.data.db.CocinaDatabase
import com.lechugones.cocina.data.settings.DataStoreSettingsRepository
import com.lechugones.cocina.data.testing.InMemoryPreferencesDataStore
import com.lechugones.cocina.domain.money.Money
import com.lechugones.cocina.domain.product.BrandKind
import com.lechugones.cocina.domain.product.Product
import com.lechugones.cocina.domain.product.ProductKey
import com.lechugones.cocina.domain.product.SuggestionSource
import com.lechugones.cocina.domain.quantity.Amount
import com.lechugones.cocina.domain.quantity.PackageSize
import com.lechugones.cocina.domain.quantity.Quantity
import com.lechugones.cocina.domain.recipe.Ingredient
import com.lechugones.cocina.domain.recipe.IngredientLink
import com.lechugones.cocina.domain.recipe.MatchHints
import com.lechugones.cocina.domain.units.MeasureUnit
import com.lechugones.cocina.provider.ProviderProduct
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class ProductLinkerTest {
    private val database =
        Room
            .inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), CocinaDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    private val settings = DataStoreSettingsRepository(InMemoryPreferencesDataStore())
    private val linker =
        ProductLinker(
            database.productDao(),
            database.linkMemoryDao(),
            settings,
            Clock.fixed(Instant.parse("2026-11-19T10:00:00Z"), ZoneOffset.UTC),
        )

    @After
    fun close() = database.close()

    private suspend fun stock(vararg products: Pair<String, String>) {
        database.productDao().upsert(
            products.map { (id, name) ->
                ProviderProduct(
                    Product(
                        ProductKey("mercadona", id),
                        name,
                        Quantity(Amount.of(360), MeasureUnit.G),
                        Money(150),
                        brandKind = BrandKind.STORE,
                    ),
                    imageUrl = "https://prod-mercadona.imgix.net/images/$id.jpg",
                    categoryPath = listOf("Panadería"),
                ).toEntity(Instant.EPOCH)
            },
        )
    }

    private val tortillas =
        Ingredient(
            "i1",
            "Tortillas de trigo",
            Amount.of(4),
            MeasureUnit.UD,
            match = MatchHints("tortillas de trigo", PackageSize.parse("360 g")),
        )

    @Test
    fun `suggests and preselects the closest cached product, with its display data`() =
        runTest {
            stock("1" to "Tortillas de trigo Hacendado", "2" to "Maxi tortillas de trigo Hacendado", "3" to "Pan de molde")
            val match = linker.match(tortillas).first()
            assertEquals("mercadona", match.providerId)
            val chosen = assertNotNull(match.result.preselected)
            assertEquals("1", chosen.product.key.productId)
            assertTrue(match.result.suggestions.none { it.product.key.productId == "3" })
            assertEquals("https://prod-mercadona.imgix.net/images/1.jpg", match.products[chosen.product.key]?.imageUrl)
        }

    @Test
    fun `a confirmed link is remembered for the same ingredient name`() =
        runTest {
            stock("1" to "Tortillas de trigo Hacendado", "2" to "Maxi tortillas de trigo Hacendado")
            linker.remember(tortillas.nameKey, listOf(IngredientLink(ProductKey("mercadona", "2"), preferred = true)))
            val other = tortillas.copy(id = "i2", name = "tortillas  de TRIGO")
            val best =
                linker
                    .match(other)
                    .first()
                    .result.preselected
            assertEquals("2", best?.product?.key?.productId)
            assertEquals(SuggestionSource.MEMORY, best?.source)
        }

    @Test
    fun `file links win when the product is cached and are ignored when it is not`() =
        runTest {
            stock("1" to "Tortillas de trigo Hacendado", "2" to "Maxi tortillas de trigo Hacendado")
            val fromFile =
                linker
                    .match(tortillas, listOf(ProductKey("mercadona", "2")))
                    .first()
                    .result.preselected
            assertEquals(SuggestionSource.FILE, fromFile?.source)
            val missing =
                linker
                    .match(tortillas, listOf(ProductKey("mercadona", "999")))
                    .first()
                    .result.preselected
            assertEquals(SuggestionSource.SEARCH, missing?.source)
        }

    @Test
    fun `display data for links skips products that are not cached`() =
        runTest {
            stock("1" to "Tortillas de trigo Hacendado")
            val found =
                linker.productsFor(
                    listOf(IngredientLink(ProductKey("mercadona", "1"), true), IngredientLink(ProductKey("mercadona", "404"), false)),
                )
            assertEquals(setOf(ProductKey("mercadona", "1")), found.keys)
        }
}
