package com.lechugones.cocina.provider.carrefour

import com.lechugones.cocina.domain.money.Money
import com.lechugones.cocina.domain.product.BrandKind
import com.lechugones.cocina.domain.quantity.Amount
import com.lechugones.cocina.domain.quantity.Quantity
import com.lechugones.cocina.domain.units.MeasureUnit
import com.lechugones.cocina.provider.FoodSearches
import com.lechugones.cocina.provider.ProviderCategory
import com.lechugones.cocina.provider.ProviderError
import com.lechugones.cocina.provider.ProviderLocation
import com.lechugones.cocina.provider.ProviderProduct
import com.lechugones.cocina.provider.ProviderResult
import com.lechugones.cocina.provider.RateLimiter
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Fixtures `search-*.json` are real answers recorded on 2026-11-24 with the app's User-Agent,
 * trimmed; `supermercapy-*.json` are supermercapy's recordings (third_party/supermercapy, MIT).
 */
class CarrefourProviderTest {
    private val now = Instant.parse("2026-11-24T08:00:00Z")
    private val json = Json { ignoreUnknownKeys = true }

    private fun fixture(name: String): String =
        checkNotNull(javaClass.getResource("/fixtures/carrefour/$name")) { "missing fixture $name" }.readText()

    private fun docs(name: String) =
        json
            .decodeFromString(CarrefourSearchResponse.serializer(), fixture(name))
            .catalog
            ?.content
            .orEmpty()
            .associateBy { it.id }

    private fun mapped(
        file: String,
        id: String,
    ) = checkNotNull(CarrefourMapper.map(checkNotNull(docs(file)[id]) { "no $id in $file" }, now))

    // --- Mapping --------------------------------------------------------------------------

    @Test
    fun `a litre of store-brand milk, with picture, link and price per litre`() {
        val p = mapped("search-leche.json", "521007071")
        assertEquals(Quantity(Amount.of(1000), MeasureUnit.ML), p.product.net)
        assertEquals(Money(83), p.product.price)
        assertEquals("Carrefour", p.product.brand)
        assertEquals(BrandKind.STORE, p.product.brandKind)
        assertEquals(false, p.product.approx)
        assertEquals(Money(83), p.referencePrice)
        assertEquals("L", p.referenceFormat)
        assertEquals("https://static.carrefour.es/hd_350x_/img_pim_food/231394_00_1.jpg", p.imageUrl)
        assertEquals("https://www.carrefour.es/supermercado/leche-semidesnatada-carrefour-brik-1-l/R-521007071/p", p.webUrl)
    }

    @Test
    fun `national brands are title-cased`() {
        val p = mapped("search-leche.json", "VC4AECOMM-700248")
        assertEquals("La Cantara", p.product.brand)
        assertEquals(BrandKind.NATIONAL, p.product.brandKind)
    }

    @Test
    fun `a multipack is the total weight with its unit count`() {
        val p = mapped("search-yogur.json", "VC4AECOMM-155029")
        assertEquals(Quantity(Amount.of(440), MeasureUnit.G), p.product.net)
        assertEquals(4, p.product.unitCount)
        assertEquals(Money(162), p.product.price)
        assertEquals(Money(368), p.referencePrice) // 1,62 € / 0,44 kg
        assertEquals("kg", p.referenceFormat)
        assertEquals(6, mapped("search-yogur.json", "fprod1490097").product.unitCount)
    }

    @Test
    fun `fresh food by weight uses the average weight, is approximate and has no reference price`() {
        val bag = mapped("search-platano.json", "VC4AECOMM-779025")
        assertEquals(Quantity(Amount.of(900), MeasureUnit.G), bag.product.net)
        assertTrue(bag.product.approx)
        assertNull(bag.referencePrice)
        assertEquals(BrandKind.STORE, mapped("search-platano.json", "VC4AECOMM-545424").product.brandKind)
    }

    @Test
    fun `no-brand placeholders and a missing brand are unbranded`() {
        val loose = mapped("search-platano.json", "529921745")
        assertNull(loose.product.brand)
        assertEquals(BrandKind.UNBRANDED, loose.product.brandKind)
        assertEquals(BrandKind.UNBRANDED, mapped("search-platano.json", "547901617").product.brandKind)
    }

    @Test
    fun `a small package below one kilo keeps its grams`() {
        val chips = mapped("search-platano.json", "VC4AECOMM-530709")
        assertEquals(Quantity(Amount.of(75), MeasureUnit.G), chips.product.net)
        assertEquals(false, chips.product.approx)
    }

    @Test
    fun `without a conversion factor the size comes from the pack text`() {
        val p = mapped("supermercapy-search.json", "521007071")
        assertEquals(Quantity(Amount.of(1000), MeasureUnit.ML), p.product.net)
        assertNull(p.referencePrice)
    }

    @Test
    fun `the active price is used when there is a discount`() {
        val p = mapped("supermercapy-search.json", "714713105")
        assertEquals(Money(94), p.product.price)
    }

    @Test
    fun `documents without a price or outside the food catalogue are skipped`() {
        val doc = checkNotNull(docs("search-leche.json")["521007071"])
        assertNull(CarrefourMapper.map(doc.copy(price = null), now))
        assertNull(CarrefourMapper.map(doc.copy(price = 0.0), now))
        assertNull(CarrefourMapper.map(doc.copy(activeFood = false), now))
    }

    // --- Requests -------------------------------------------------------------------------

    private lateinit var server: MockWebServer
    private lateinit var provider: CarrefourProvider
    private val location = ProviderLocation("08001", CarrefourProvider.NATIONAL)

    @BeforeEach
    fun startServer() {
        server = MockWebServer().apply { start() }
        provider =
            CarrefourProvider.create(
                client = OkHttpClient(),
                clock = Clock.fixed(now, ZoneOffset.UTC),
                baseUrl = server.url("/search/v1/query/carrefour/").toString(),
                limiter = RateLimiter(0, sleep = {}),
                sleep = {},
            )
    }

    @AfterEach
    fun stopServer() = server.close()

    private fun enqueue(
        body: String,
        code: Int = 200,
    ) = server.enqueue(
        MockResponse
            .Builder()
            .code(code)
            .addHeader("Content-Type", "application/json")
            .body(body)
            .build(),
    )

    @Test
    fun `the location needs no request and the sync runs the shared food searches`() =
        runTest {
            val resolved = provider.resolveLocation("08001")
            assertEquals(location, assertIs<ProviderResult.Success<ProviderLocation>>(resolved).value)
            val categories = assertIs<ProviderResult.Success<List<ProviderCategory>>>(provider.categories(location)).value
            assertEquals(FoodSearches.TERMS, categories.map { it.id })
            assertEquals(0, server.requestCount)
        }

    @Test
    fun `a category is one food search of 120 rows`() =
        runTest {
            enqueue(fixture("search-yogur.json"))
            val result = provider.productsIn(ProviderCategory("Yogur", "Yogur"), location)
            assertEquals(6, assertIs<List<*>>(assertIs<ProviderResult.Success<*>>(result).value).size)
            val url = checkNotNull(server.takeRequest().url)
            assertEquals("/search/v1/query/carrefour/search", url.encodedPath)
            assertEquals("yogur", url.queryParameter("query"))
            assertEquals("120", url.queryParameter("rows"))
            assertEquals("es", url.queryParameter("lang"))
            assertEquals("food", url.queryParameter("catalog"))
        }

    @Test
    fun `a product refresh picks the exact id, and a vanished product is null`() =
        runTest {
            enqueue(fixture("search-yogur.json"))
            val found = provider.product("prod630059", location)
            val product = assertIs<ProviderProduct>(assertIs<ProviderResult.Success<*>>(found).value)
            assertEquals("prod630059", product.product.key.productId)
            enqueue(fixture("supermercapy-search-empty.json"))
            assertNull(assertIs<ProviderResult.Success<*>>(provider.product("gone", location)).value)
        }

    @Test
    fun `403 is a block and is not retried`() =
        runTest {
            enqueue("{}", code = 403)
            val result = provider.productsIn(ProviderCategory("leche", "leche"), location)
            assertEquals(ProviderError.Blocked, assertIs<ProviderResult.Failure>(result).error)
            assertEquals(1, server.requestCount)
        }
}
