package com.lechugones.cocina.data.catalogue

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lechugones.cocina.data.db.CocinaDatabase
import com.lechugones.cocina.data.settings.DataStoreSettingsRepository
import com.lechugones.cocina.data.testing.InMemoryPreferencesDataStore
import com.lechugones.cocina.domain.money.Money
import com.lechugones.cocina.domain.product.BrandKind
import com.lechugones.cocina.domain.product.ManualProductForm
import com.lechugones.cocina.domain.product.ManualProductResult
import com.lechugones.cocina.domain.product.Product
import com.lechugones.cocina.domain.product.ProductKey
import com.lechugones.cocina.domain.quantity.Amount
import com.lechugones.cocina.domain.quantity.Quantity
import com.lechugones.cocina.domain.units.MeasureUnit
import com.lechugones.cocina.provider.ProviderCategory
import com.lechugones.cocina.provider.ProviderError
import com.lechugones.cocina.provider.ProviderLocation
import com.lechugones.cocina.provider.ProviderProduct
import com.lechugones.cocina.provider.ProviderResult
import com.lechugones.cocina.provider.SupermarketProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class CatalogueRepositoryTest {
    private val database =
        Room
            .inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), CocinaDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    private val dao = database.productDao()
    private val dataStore = InMemoryPreferencesDataStore()
    private val settings = DataStoreSettingsRepository(dataStore)
    private val locations = ProviderLocationStore(dataStore)
    private val clock = MutableClock(Instant.parse("2026-11-18T10:00:00Z"))
    private val provider = FakeProvider()

    @After
    fun close() = database.close()

    private fun TestScope.repository(scope: CoroutineScope = backgroundScope) =
        CatalogueRepository(setOf(provider), dao, settings, locations, clock, scope)

    private suspend fun useFakeProvider() =
        settings.update { it.copy(postalCode = "08001", activeProviders = setOf(FakeProvider.ID), primaryProvider = FakeProvider.ID) }

    private suspend fun CatalogueRepository.syncAndWait(): SyncState {
        startSync(FakeProvider.ID)
        return syncState.first { it !is SyncState.Idle && it !is SyncState.Running }
    }

    @Test
    fun `sync needs a postal code first`() =
        runTest {
            val state = repository().syncAndWait()
            assertEquals(SyncState.Failed(FakeProvider.ID, SyncFailure.NoPostalCode, 0), state)
            assertEquals(0, provider.calls)
        }

    @Test
    fun `sync stores every category, resolves the location once and searches without accents`() =
        runTest {
            useFakeProvider()
            val repository = repository()
            assertEquals(SyncState.Finished(FakeProvider.ID, 3, complete = true), repository.syncAndWait())
            assertEquals(1, provider.locationCalls)

            val found = repository.search("platano", storeBrandOnly = false).first()
            assertEquals(listOf("Plátano de Canarias"), found.map { it.product.name })
            assertEquals(listOf("Frutas", "Plátanos"), found.single().categoryPath)
            assertEquals(Quantity(Amount.of(1000), MeasureUnit.G), found.single().product.net)

            repository.acknowledgeSync()
            repository.syncAndWait()
            assertEquals(1, provider.locationCalls, "the warehouse is remembered for the same postal code")
        }

    @Test
    fun `every word must match and store brand only hides national brands`() =
        runTest {
            useFakeProvider()
            val repository = repository()
            repository.syncAndWait()
            assertEquals(
                listOf("Tomate frito Orlando", "Tomate frito Hacendado"), // Shorter names first.
                repository.search("tomate frito", storeBrandOnly = false).first().map { it.product.name },
            )
            assertEquals(
                listOf("Tomate frito Hacendado"),
                repository.search("frito tomate", storeBrandOnly = true).first().map { it.product.name },
            )
            assertTrue(repository.search("  ", storeBrandOnly = false).first().isEmpty())
        }

    @Test
    fun `price snapshots are recorded only when the price changes`() =
        runTest {
            useFakeProvider()
            val repository = repository()
            repository.syncAndWait()
            clock.now = clock.now.plusSeconds(3_600)
            repository.acknowledgeSync()
            repository.syncAndWait()
            provider.bananaPrice = 239
            clock.now = clock.now.plusSeconds(3_600)
            repository.acknowledgeSync()
            repository.syncAndWait()

            val history = database.query("SELECT price_cents FROM price_snapshot WHERE product_id = 'banana' ORDER BY fetched_at", null)
            val prices = buildList { while (history.moveToNext()) add(history.getLong(0)) }
            assertEquals(listOf(199L, 239L), prices)
        }

    @Test
    fun `products gone from a complete sync are kept but unavailable`() =
        runTest {
            useFakeProvider()
            val repository = repository()
            repository.syncAndWait()
            provider.dropOrlando = true
            clock.now = clock.now.plusSeconds(60)
            repository.acknowledgeSync()
            repository.syncAndWait()

            assertEquals(2, repository.status(FakeProvider.ID).first().products)
            assertEquals(false, dao.get(FakeProvider.ID, "orlando")?.available)
        }

    @Test
    fun `a block stops the sync, keeps what was saved and pauses the provider`() =
        runTest {
            useFakeProvider()
            provider.blockSecondCategory = true
            val repository = repository()
            val state = repository.syncAndWait()
            assertEquals(SyncState.Failed(FakeProvider.ID, SyncFailure.Provider(ProviderError.Blocked), 1), state)
            assertEquals(1, repository.status(FakeProvider.ID).first().products)

            val callsBefore = provider.calls
            assertEquals(
                ProviderResult.Failure(ProviderError.Blocked),
                repository.refreshProduct(ProductKey(FakeProvider.ID, "banana")),
            )
            assertEquals(callsBefore, provider.calls, "no request during the pause")
        }

    @Test
    fun `refreshing a product updates its price and keeps its category path`() =
        runTest {
            useFakeProvider()
            val repository = repository()
            repository.syncAndWait()
            provider.bananaPrice = 205
            val refreshed =
                assertIs<ProviderResult.Success<ProviderProduct?>>(repository.refreshProduct(ProductKey(FakeProvider.ID, "banana")))
            assertEquals(Money(205), refreshed.value?.product?.price)
            val stored = repository.observeProduct(ProductKey(FakeProvider.ID, "banana")).first()
            assertEquals(Money(205), stored?.product?.price)
            assertEquals(listOf("Frutas", "Plátanos"), stored?.categoryPath)
        }

    @Test
    fun `a product the shop removed is marked unavailable on refresh`() =
        runTest {
            useFakeProvider()
            val repository = repository()
            repository.syncAndWait()
            assertEquals(ProviderResult.Success(null), repository.refreshProduct(ProductKey(FakeProvider.ID, "gone")))
            provider.dropOrlando = true
            assertEquals(ProviderResult.Success(null), repository.refreshProduct(ProductKey(FakeProvider.ID, "orlando")))
            assertEquals(false, dao.get(FakeProvider.ID, "orlando")?.available)
            assertNull(dao.get(FakeProvider.ID, "gone"))
        }

    @Test
    fun `manual products are saved, searchable and keep their shop`() =
        runTest {
            useFakeProvider()
            val repository = repository()
            val saved =
                assertIs<ManualProductResult.Valid>(
                    repository.saveManual(ManualProductForm("Queso de cabra artesano", size = "250 g", price = "4,90", shop = "Mercat")),
                )
            val found = repository.search("queso cabra", storeBrandOnly = false).first().single()
            assertEquals(saved.product.key, found.product.key)
            assertEquals(listOf("Mercat"), found.categoryPath)
            assertEquals(Money(490), found.product.price)
            assertIs<ManualProductResult.Invalid>(repository.saveManual(ManualProductForm("", size = "")))
        }

    @Test
    fun `refreshing a recipe's prices asks only for its products and skips manual ones`() =
        runTest {
            useFakeProvider()
            val repository = repository()
            repository.syncAndWait()
            provider.bananaPrice = 250
            val before = provider.calls
            val summary =
                repository.refreshProducts(
                    listOf(ProductKey(FakeProvider.ID, "banana"), ProductKey(FakeProvider.ID, "gone"), ProductKey("manual", "x")),
                )
            assertEquals(RefreshSummary(updated = 1, gone = 1, error = null), summary)
            assertEquals(before + 2, provider.calls)
            assertEquals(
                Money(250),
                repository
                    .observeProduct(ProductKey(FakeProvider.ID, "banana"))
                    .first()
                    ?.product
                    ?.price,
            )
        }

    private class MutableClock(
        var now: Instant,
    ) : Clock() {
        override fun getZone(): ZoneId = ZoneId.of("UTC")

        override fun withZone(zone: ZoneId?): Clock = this

        override fun instant(): Instant = now
    }

    private class FakeProvider : SupermarketProvider {
        override val id = ID
        override val displayName = "Fake"
        override val storeBrands = setOf("Hacendado")
        var calls = 0
        var locationCalls = 0
        var bananaPrice = 199L
        var dropOrlando = false
        var blockSecondCategory = false

        private val fruit = ProviderCategory("1", "Plátanos", "Frutas")
        private val sauces = ProviderCategory("2", "Tomate", "Salsas")

        override suspend fun resolveLocation(postalCode: String): ProviderResult<ProviderLocation> {
            calls++
            locationCalls++
            return ProviderResult.Success(ProviderLocation(postalCode, "bcn1"))
        }

        override suspend fun categories(location: ProviderLocation): ProviderResult<List<ProviderCategory>> {
            calls++
            return ProviderResult.Success(listOf(fruit, sauces))
        }

        override suspend fun productsIn(
            category: ProviderCategory,
            location: ProviderLocation,
        ): ProviderResult<List<ProviderProduct>> {
            calls++
            return when (category) {
                fruit -> {
                    ProviderResult.Success(listOf(banana(listOf("Frutas", "Plátanos"))))
                }

                else -> {
                    if (blockSecondCategory) {
                        ProviderResult.Failure(ProviderError.Blocked)
                    } else {
                        ProviderResult.Success(listOfNotNull(sauce("hacendado", "Tomate frito Hacendado", BrandKind.STORE), orlando()))
                    }
                }
            }
        }

        override suspend fun product(
            productId: String,
            location: ProviderLocation,
        ): ProviderResult<ProviderProduct?> {
            calls++
            return ProviderResult.Success(
                when (productId) {
                    "banana" -> banana(listOf("Frutas"))
                    "orlando" -> orlando()
                    else -> null
                },
            )
        }

        private fun orlando() = if (dropOrlando) null else sauce("orlando", "Tomate frito Orlando", BrandKind.NATIONAL, "Orlando")

        private fun banana(path: List<String>) =
            ProviderProduct(
                product =
                    Product(
                        key = ProductKey(ID, "banana"),
                        name = "Plátano de Canarias",
                        net = Quantity(Amount.of(1000), MeasureUnit.G),
                        price = Money(bananaPrice),
                        approx = true,
                    ),
                categoryPath = path,
            )

        private fun sauce(
            productId: String,
            name: String,
            kind: BrandKind,
            brand: String? = null,
        ) = ProviderProduct(
            product =
                Product(
                    key = ProductKey(ID, productId),
                    name = name,
                    net = Quantity(Amount.of(400), MeasureUnit.G),
                    price = Money(95),
                    brand = brand,
                    brandKind = kind,
                ),
            categoryPath = listOf("Salsas", "Tomate"),
        )

        companion object {
            const val ID = "fake"
        }
    }
}
