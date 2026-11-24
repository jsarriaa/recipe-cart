package com.lechugones.cocina.provider.aldi

import com.lechugones.cocina.domain.money.Money
import com.lechugones.cocina.domain.product.BrandKind
import com.lechugones.cocina.domain.quantity.Amount
import com.lechugones.cocina.domain.quantity.Quantity
import com.lechugones.cocina.domain.units.MeasureUnit
import com.lechugones.cocina.provider.ProviderCategory
import com.lechugones.cocina.provider.ProviderError
import com.lechugones.cocina.provider.ProviderLocation
import com.lechugones.cocina.provider.ProviderProduct
import com.lechugones.cocina.provider.ProviderResult
import com.lechugones.cocina.provider.RateLimiter
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
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
 * Fixtures `*.html` are real www.aldi.es pages recorded on 2026-11-24 with the app's User-Agent,
 * trimmed to their `__NEXT_DATA__`; `supermercapy-*.json` are supermercapy's recorded index
 * records (third_party/supermercapy, MIT).
 */
class AldiProviderTest {
    private val now = Instant.parse("2026-11-24T08:00:00Z")
    private val json = Json { ignoreUnknownKeys = true }

    private fun fixture(name: String): String =
        checkNotNull(javaClass.getResource("/fixtures/aldi/$name")) { "missing fixture $name" }.readText()

    private fun records(page: String) = AldiPages.records(fixture(page)).associateBy { it.objectID }

    private fun mapped(
        page: String,
        id: String,
    ): ProviderProduct = checkNotNull(AldiMapper.map(checkNotNull(records(page)[id]) { "no $id in $page" }, now))

    private fun record(name: String) = json.decodeFromString(AldiRecord.serializer(), fixture(name))

    // --- Pages ----------------------------------------------------------------------------

    @Test
    fun `the overview lists top-level categories with their marketing flag, hidden ones left out`() {
        val tops = AldiPages.children(fixture("productos.html"))
        assertTrue(tops.any { it.path == "despensa" && it.name == "Despensa" && !it.marketing })
        assertTrue(tops.any { it.path == "juguetes" && it.marketing })
        assertTrue(tops.none { it.path == "verano" })
    }

    @Test
    fun `a leaf page embeds its product records`() {
        assertEquals(7, AldiPages.records(fixture("leaf-leche-y-bebidas-vegetales.html")).size)
        assertEquals(emptyList(), AldiPages.records(fixture("lacteos-y-huevos.html")))
    }

    // --- Mapping --------------------------------------------------------------------------

    @Test
    fun `a litre of store-brand milk, its price per litre computed when Aldi leaves it out`() {
        val p = mapped("leaf-leche-y-bebidas-vegetales.html", "80500")
        assertEquals(Quantity(Amount.of(1000), MeasureUnit.ML), p.product.net)
        assertEquals(Money(83), p.product.price)
        assertEquals("Milsani", p.product.brand)
        assertEquals(BrandKind.STORE, p.product.brandKind)
        assertEquals(Money(83), p.referencePrice)
        assertEquals("L", p.referenceFormat)
        assertTrue(checkNotNull(p.imageUrl).startsWith("https://s7g10.scene7.com/"))
        assertTrue(checkNotNull(p.webUrl).startsWith("https://www.aldi.es/producto/"))
    }

    @Test
    fun `a pack of six is the total volume with its unit count and the shelf unit price`() {
        val p = mapped("leaf-leche-y-bebidas-vegetales.html", "601202000")
        assertEquals(Quantity(Amount.of(1200), MeasureUnit.ML), p.product.net)
        assertEquals(6, p.product.unitCount)
        assertEquals(Money(129), p.referencePrice)
        assertEquals("L", p.referenceFormat)
    }

    @Test
    fun `national brands lose the registered sign and are title-cased`() {
        val p = mapped("leaf-leche-y-bebidas-vegetales.html", "600718000")
        assertEquals("Puleva", p.product.brand)
        assertEquals(BrandKind.NATIONAL, p.product.brandKind)
        assertEquals("Coosur", mapped("leaf-aceites-y-vinagres.html", "602031000").product.brand)
    }

    @Test
    fun `multi-word store brands keep their spelling`() {
        val oil = mapped("leaf-aceites-y-vinagres.html", "662500")
        assertEquals("Olearia del Olivar", oil.product.brand)
        assertEquals(BrandKind.STORE, oil.product.brandKind)
        assertEquals(Quantity(Amount.of(3000), MeasureUnit.ML), oil.product.net)
        assertEquals(Money(375), oil.referencePrice)
    }

    @Test
    fun `loose fruit by the kilo is one approximate kilo at the price per kilo`() {
        val melon = mapped("leaf-fruta.html", "992600")
        assertEquals(Quantity(Amount.of(1000), MeasureUnit.G), melon.product.net)
        assertTrue(melon.product.approx)
        assertEquals(Money(149), melon.product.price)
        assertEquals(Money(149), melon.referencePrice)
        assertEquals("kg", melon.referenceFormat)
        assertEquals(BrandKind.UNBRANDED, melon.product.brandKind)
    }

    @Test
    fun `fruit in a pack keeps its count and weight`() {
        val mango = mapped("leaf-fruta.html", "647000")
        assertEquals(Quantity(Amount.of(600), MeasureUnit.G), mango.product.net)
        assertEquals(2, mango.product.unitCount)
        assertEquals(Money(482), mango.referencePrice)
    }

    @Test
    fun `records without a price are skipped`() {
        val fruit = records("leaf-fruta.html")
        assertNull(AldiMapper.map(checkNotNull(fruit["996300"]), now))
        assertNull(AldiMapper.map(checkNotNull(fruit["891800"]), now))
        assertNull(AldiMapper.map(record("supermercapy-product_loose.json"), now))
    }

    @Test
    fun `supermercapy's records map the same way`() {
        val grapes = checkNotNull(AldiMapper.map(record("supermercapy-product.json"), now))
        assertEquals(Quantity(Amount.of(500), MeasureUnit.G), grapes.product.net)
        assertEquals(Money(149), grapes.product.price)
        assertEquals(Money(298), grapes.referencePrice)
        val pasta = checkNotNull(AldiMapper.map(record("supermercapy-product_two_windows.json"), now))
        assertEquals("Cucina", pasta.product.brand)
        assertEquals(BrandKind.STORE, pasta.product.brandKind)
    }

    @Test
    fun `per-gram scales are per kilo`() {
        val coconut = mapped("leaf-aceites-y-vinagres.html", "600625100")
        assertEquals(Quantity(Amount.of(250), MeasureUnit.G), coconut.product.net)
        assertEquals("kg", coconut.referenceFormat)
    }

    // --- Requests -------------------------------------------------------------------------

    private lateinit var server: MockWebServer
    private lateinit var provider: AldiProvider
    private val location = ProviderLocation("08001", AldiProvider.PENINSULA)

    private fun html(
        body: String,
        code: Int = 200,
    ) = MockResponse
        .Builder()
        .code(code)
        .addHeader("Content-Type", "text/html; charset=utf-8")
        .body(body)
        .build()

    @BeforeEach
    fun startServer() {
        server = MockWebServer().apply { start() }
        provider =
            AldiProvider.create(
                client = OkHttpClient(),
                clock = Clock.fixed(now, ZoneOffset.UTC),
                baseUrl = server.url("/").toString(),
                limiter = RateLimiter(0, sleep = {}),
                sleep = {},
            )
    }

    @AfterEach
    fun stopServer() = server.close()

    @Test
    fun `the sync reads the overview, then each food top-level page for its subcategories`() =
        runTest {
            server.dispatcher =
                object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse =
                        when (request.url.encodedPath) {
                            "/productos.html" -> html(fixture("productos.html"))
                            else -> html(fixture("lacteos-y-huevos.html"))
                        }
                }
            val result = provider.categories(location)
            val leaves = assertIs<ProviderResult.Success<List<ProviderCategory>>>(result).value
            val tops = AldiPages.children(fixture("productos.html")).filter { !it.marketing && it.path !in AldiProvider.NOT_FOOD }
            assertEquals(1 + tops.size, server.requestCount)
            assertEquals(tops.size * 3, leaves.size)
            assertEquals(
                ProviderCategory("lacteos-y-huevos/leche-y-bebidas-vegetales", "Leche y bebidas vegetales", tops.first().name),
                leaves.first(),
            )
            val paths = (1..server.requestCount).map { checkNotNull(server.takeRequest().url).encodedPath }
            assertTrue(paths.none { it.contains("mascotas") || it.contains("juguetes") || it.contains("verano") })
        }

    @Test
    fun `a category is its page, with the path kept in the products`() =
        runTest {
            server.enqueue(html(fixture("leaf-leche-y-bebidas-vegetales.html")))
            val category = ProviderCategory("lacteos-y-huevos/leche-y-bebidas-vegetales", "Leche", "Lácteos y huevos")
            val products = assertIs<ProviderResult.Success<List<ProviderProduct>>>(provider.productsIn(category, location)).value
            assertEquals(7, products.size)
            assertEquals(listOf("Lácteos y huevos", "Leche"), products.first().categoryPath)
            assertEquals("/productos/lacteos-y-huevos/leche-y-bebidas-vegetales.html", checkNotNull(server.takeRequest().url).encodedPath)
        }

    @Test
    fun `a product refresh reads its page by id, and 404 means gone`() =
        runTest {
            server.enqueue(html(fixture("product-80500.html")))
            val fresh = assertIs<ProviderResult.Success<ProviderProduct?>>(provider.product("80500", location)).value
            assertEquals(Money(83), checkNotNull(fresh).product.price)
            assertEquals("/producto/p-80500.html", checkNotNull(server.takeRequest().url).encodedPath)

            server.enqueue(html("<html></html>", code = 404))
            assertNull(assertIs<ProviderResult.Success<ProviderProduct?>>(provider.product("1", location)).value)
        }

    @Test
    fun `an id that is not a number is never requested`() =
        runTest {
            assertNull(assertIs<ProviderResult.Success<ProviderProduct?>>(provider.product("../x", location)).value)
            assertEquals(0, server.requestCount)
        }

    @Test
    fun `a page without data is a parse error, and 403 a block`() =
        runTest {
            server.enqueue(html("<html><body>Un momento…</body></html>"))
            val odd = provider.productsIn(ProviderCategory("despensa/pasta", "Pasta"), location)
            assertIs<ProviderError.Parse>(assertIs<ProviderResult.Failure>(odd).error)
            server.enqueue(html("", code = 403))
            val blocked = provider.productsIn(ProviderCategory("despensa/pasta", "Pasta"), location)
            assertEquals(ProviderError.Blocked, assertIs<ProviderResult.Failure>(blocked).error)
        }
}
