package com.lechugones.cocina.provider.consum

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
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `listing-*`, `category-*`, `menu` and `product-*` are real answers recorded from the phone on
 * 2026-11-24 with the app's User-Agent, trimmed to a few products; `supermercapy-*` are
 * supermercapy's recordings (third_party/supermercapy, MIT).
 */
class ConsumProviderTest {
    private val now = Instant.parse("2026-11-24T08:00:00Z")
    private val json = Json { ignoreUnknownKeys = true }

    private fun fixture(name: String): String =
        checkNotNull(javaClass.getResource("/fixtures/consum/$name")) { "missing fixture $name" }.readText()

    private fun mapped(
        file: String,
        code: String,
    ): ProviderProduct {
        val listing = json.decodeFromString<ConsumListing>(fixture(file))
        val source = checkNotNull(listing.products.firstOrNull { it.code == code }) { "no $code in $file" }
        return checkNotNull(ConsumMapper.map(source, now))
    }

    // --- Mapping --------------------------------------------------------------------------

    @Test
    fun `a litre of store-brand milk with its unit price, picture and link`() {
        val p = mapped("listing-leche.json", "7080604")
        assertEquals(Quantity(Amount.of(1000), MeasureUnit.ML), p.product.net)
        assertFalse(p.product.approx)
        assertEquals(Money(83), p.product.price)
        assertEquals("Consum", p.product.brand)
        assertEquals(BrandKind.STORE, p.product.brandKind)
        assertEquals(Money(83), p.referencePrice)
        assertEquals("L", p.referenceFormat)
        assertEquals(listOf("Semidesnatada"), p.categoryPath)
        // The listed photo, not `imageURL`, which the CDN answers with 404.
        assertEquals(
            "https://cdn-consum.aktiosdigitalservices.com/tol/consum/media/product/img/300x300/7080604_001.jpg?t=20250522091502",
            p.imageUrl,
        )
        assertEquals("https://tienda.consum.es/es/p/leche-semidesnatada-brik/7080604", p.webUrl)
    }

    @Test
    fun `an immediate offer replaces the shelf price`() {
        val p = mapped("listing-leche.json", "7461007")
        assertEquals(Money(129), p.product.price)
        assertEquals(Money(129), p.referencePrice)
        assertEquals("Puleva", p.product.brand)
        assertEquals(BrandKind.NATIONAL, p.product.brandKind)
    }

    @Test
    fun `decimal litres from the description`() {
        val p = mapped("category-semidesnatada.json", "203745")
        assertEquals(Quantity(Amount.of(1500), MeasureUnit.ML), p.product.net)
        assertEquals(Money(119), p.referencePrice)
    }

    @Test
    fun `fruit as a piece at an approximate price is sized from the price per kilo`() {
        val banana = mapped("listing-platano.json", "26104")
        assertEquals(Quantity(Amount.of(184), MeasureUnit.G), banana.product.net) // 0,59 € / 3,20 €/kg
        assertTrue(banana.product.approx)
        assertEquals(Money(59), banana.product.price)
        assertEquals(Money(320), banana.referencePrice)
        assertEquals("kg", banana.referenceFormat)
        assertNull(banana.product.brand)
        assertEquals(BrandKind.UNBRANDED, banana.product.brandKind)
    }

    @Test
    fun `a pack written 4 x 100 Gr is 400 g in 4 pieces`() {
        val pouch = mapped("listing-platano.json", "4507471")
        assertEquals(Quantity(Amount.of(400), MeasureUnit.G), pouch.product.net)
        assertEquals(4, pouch.product.unitCount)
    }

    @Test
    fun `eggs come in dozens, half dozens, tens and eighteens`() {
        assertEquals(Quantity(Amount.of(12), MeasureUnit.UD), mapped("listing-huevos.json", "7098382").product.net)
        val half = mapped("listing-huevos.json", "7098411")
        assertEquals(Quantity(Amount.of(6), MeasureUnit.UD), half.product.net)
        assertEquals("docena", half.referenceFormat)
        assertEquals(Money(360), half.referencePrice)
        assertEquals(Quantity(Amount.of(10), MeasureUnit.UD), mapped("listing-huevos.json", "7359040").product.net)
        assertEquals(Quantity(Amount.of(18), MeasureUnit.UD), mapped("listing-huevos.json", "7300853").product.net)
        assertEquals(Quantity(Amount.of(300), MeasureUnit.ML), mapped("listing-huevos.json", "7385719").product.net)
    }

    @Test
    fun `multipacks of tins and decimal grams`() {
        val six = mapped("listing-atun.json", "7083231")
        assertEquals(Quantity(Amount.of(312), MeasureUnit.G), six.product.net)
        assertEquals(6, six.product.unitCount)
        assertEquals(BrandKind.STORE, six.product.brandKind)
        assertEquals(Quantity(Amount.of(73), MeasureUnit.G), mapped("listing-atun.json", "7369890").product.net) // 72,5 Gr
        assertEquals(BrandKind.NATIONAL, mapped("listing-atun.json", "7168677").product.brandKind)
    }

    @Test
    fun `trays at an approximate price keep their tray text, weighed food is one kilo`() {
        val tray = mapped("listing-pollo.json", "220707")
        assertEquals(Quantity(Amount.of(704), MeasureUnit.G), tray.product.net) // 2,85 € / 4,05 €/kg
        assertTrue(tray.product.approx)
        assertEquals("Bandeja 700 g aprox.", tray.product.packaging)
        // The meat counter's own trays carry the Consum brand.
        assertEquals(BrandKind.STORE, tray.product.brandKind)
        val chicken = mapped("listing-pollo.json", "218115")
        assertEquals(Quantity(Amount.of(2000), MeasureUnit.G), chicken.product.net)
        val weighed = mapped("listing-pollo.json", "7454739")
        assertEquals(Quantity(Amount.of(1000), MeasureUnit.G), weighed.product.net)
        assertTrue(weighed.product.approx)
        assertEquals(Money(1750), weighed.product.price)
        assertEquals(BrandKind.UNBRANDED, mapped("listing-pollo.json", "7358232").product.brandKind)
    }

    @Test
    fun `per-100-g unit prices are restated per kilogram`() {
        val p = mapped("supermercapy-category_listing.json", "1826")
        assertEquals(Quantity(Amount.of(1000), MeasureUnit.G), p.product.net) // nuts sold by weight
        assertEquals(Money(620), p.product.price)
    }

    // --- Requests -------------------------------------------------------------------------

    private lateinit var server: MockWebServer
    private lateinit var provider: ConsumProvider
    private val location = ProviderLocation("46001", ConsumProvider.NATIONAL)

    @BeforeEach
    fun startServer() {
        server = MockWebServer().apply { start() }
        provider =
            ConsumProvider.create(
                client = OkHttpClient(),
                clock = Clock.fixed(now, ZoneOffset.UTC),
                baseUrl = server.url("/").toString(),
                limiter = RateLimiter(0, sleep = {}),
                sleep = {},
            )
    }

    @AfterEach
    fun stopServer() = server.close()

    private fun respond(
        body: String,
        code: Int = 200,
    ) = server.enqueue(
        MockResponse
            .Builder()
            .code(code)
            .addHeader("Content-Type", "application/json;charset=UTF-8")
            .body(body)
            .build(),
    )

    @Test
    fun `the sync lists the second-level food categories and skips the rest`() =
        runTest {
            respond(fixture("menu.json"))
            val categories = assertIs<ProviderResult.Success<List<ProviderCategory>>>(provider.categories(location)).value
            val roots = categories.mapNotNull { it.parentName }.toSet()
            assertEquals(setOf("Despensa", "Bebidas", "Frescos", "Horno", "Platos preparados", "Congelados y helados"), roots)
            assertEquals(17, categories.size)
            assertEquals("/shopping/category/menu", checkNotNull(server.takeRequest().url).encodedPath)
        }

    @Test
    fun `a category is listed a hundred rows at a time with the locale header`() =
        runTest {
            respond(fixture("listing-leche.json")) // hasMore = true
            respond(fixture("category-semidesnatada.json")) // hasMore = false
            val result = provider.productsIn(ProviderCategory("2075", "Semidesnatada", "Lácteos y huevos"), location)
            val products = assertIs<ProviderResult.Success<List<ProviderProduct>>>(result).value
            assertEquals(5, products.size) // 7080604 is on both pages
            assertEquals(listOf("Lácteos y huevos", "Semidesnatada"), products.first().categoryPath)
            val first = server.takeRequest()
            assertEquals("2075", checkNotNull(first.url).queryParameter("categories"))
            assertEquals("100", checkNotNull(first.url).queryParameter("limit"))
            assertEquals("0", checkNotNull(first.url).queryParameter("offset"))
            assertEquals("es", first.headers["X-TOL-LOCALE"])
            assertEquals("2", checkNotNull(server.takeRequest().url).queryParameter("offset"))
        }

    @Test
    fun `a product refresh reads it by code, and a 404 means gone`() =
        runTest {
            respond(fixture("product-7080604.json"))
            val fresh = assertIs<ProviderResult.Success<ProviderProduct?>>(provider.product("7080604", location)).value
            assertEquals(Money(83), checkNotNull(fresh).product.price)
            assertEquals("/catalog/product/code/7080604", checkNotNull(server.takeRequest().url).encodedPath)

            respond(fixture("supermercapy-error_404.json"), code = 404)
            assertNull(assertIs<ProviderResult.Success<ProviderProduct?>>(provider.product("1", location)).value)
            assertNull(assertIs<ProviderResult.Success<ProviderProduct?>>(provider.product("../x", location)).value)
            assertEquals(2, server.requestCount)
        }

    @Test
    fun `403 is a block`() =
        runTest {
            respond("", code = 403)
            val result = provider.categories(location)
            assertEquals(ProviderError.Blocked, assertIs<ProviderResult.Failure>(result).error)
        }
}
