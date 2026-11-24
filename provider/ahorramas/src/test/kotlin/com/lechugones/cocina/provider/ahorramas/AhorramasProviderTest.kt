package com.lechugones.cocina.provider.ahorramas

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
 * `search-*.html` are real grid answers recorded on 2026-11-24 with the app's User-Agent, trimmed
 * to a few tiles (scripts removed); `supermercapy-*.html` are supermercapy's recordings
 * (third_party/supermercapy, MIT).
 */
class AhorramasProviderTest {
    private val now = Instant.parse("2026-11-24T08:00:00Z")

    private fun fixture(name: String): String =
        checkNotNull(javaClass.getResource("/fixtures/ahorramas/$name")) { "missing fixture $name" }.readText()

    private fun mapped(
        file: String,
        id: String,
    ): ProviderProduct {
        val tile = checkNotNull(AhorramasGrid.tiles(fixture(file)).firstOrNull { it.id == id }) { "no $id in $file" }
        return checkNotNull(AhorramasMapper.map(tile, now))
    }

    // --- Mapping --------------------------------------------------------------------------

    @Test
    fun `a litre of store-brand milk with its unit price, category, picture and link`() {
        val p = mapped("search-leche.html", "70865")
        assertEquals(Quantity(Amount.of(1000), MeasureUnit.ML), p.product.net)
        assertFalse(p.product.approx)
        assertEquals(Money(83), p.product.price)
        assertEquals("Alipende", p.product.brand)
        assertEquals(BrandKind.STORE, p.product.brandKind)
        assertEquals(Money(83), p.referencePrice)
        assertEquals("L", p.referenceFormat)
        assertEquals(listOf("Lácteos", "Leche"), p.categoryPath)
        assertTrue(checkNotNull(p.imageUrl).endsWith("?sw=260&sh=260&sm=fit"))
        assertEquals("https://www.ahorramas.com/leche-semidesnatada-alipende-1l-70865.html", p.webUrl)
    }

    @Test
    fun `decimal litres and national brands`() {
        val p = mapped("search-leche.html", "72025")
        assertEquals(Quantity(Amount.of(1500), MeasureUnit.ML), p.product.net)
        assertEquals("Asturiana", p.product.brand)
        assertEquals(BrandKind.NATIONAL, p.product.brandKind)
    }

    @Test
    fun `fruit sold by weight is one approximate kilo at the price per kilo`() {
        val banana = mapped("search-platano.html", "75248")
        assertEquals(Quantity(Amount.of(1000), MeasureUnit.G), banana.product.net)
        assertTrue(banana.product.approx)
        assertEquals(Money(348), banana.product.price)
        assertEquals(Money(348), banana.referencePrice)
        assertEquals("kg", banana.referenceFormat)
        assertEquals(BrandKind.UNBRANDED, mapped("search-platano.html", "44078").product.brandKind)
    }

    @Test
    fun `a pack written 4x100g is 400 g in 4 pieces`() {
        val pouch = mapped("search-platano.html", "52135")
        assertEquals(Quantity(Amount.of(400), MeasureUnit.G), pouch.product.net)
        assertEquals(4, pouch.product.unitCount)
    }

    @Test
    fun `eggs are pieces with a price per dozen`() {
        val eggs = mapped("search-huevos.html", "34649")
        assertEquals(Quantity(Amount.of(12), MeasureUnit.UD), eggs.product.net)
        assertEquals(12, eggs.product.unitCount)
        assertEquals("docena", eggs.referenceFormat)
        assertEquals(Money(305), eggs.referencePrice)
        val quail = mapped("search-huevos.html", "82112")
        assertEquals(Quantity(Amount.of(18), MeasureUnit.UD), quail.product.net)
        assertFalse(quail.product.approx)
        assertFalse(mapped("search-huevos.html", "70453").product.approx) // 6 unid, priced per kilo
    }

    @Test
    fun `tins without a size in the name are sized from the drained-weight price, approximately`() {
        val tuna = mapped("search-atun.html", "84950")
        assertEquals(Quantity(Amount.of(312), MeasureUnit.G), tuna.product.net) // 4,90 € / 15,71 €/kg
        assertTrue(tuna.product.approx)
        assertEquals(6, tuna.product.unitCount)
        assertEquals("kg", tuna.referenceFormat)
    }

    @Test
    fun `grams in the name win, store lines and no-brand are classified`() {
        val fillet = mapped("search-atun.html", "54826")
        assertEquals(Quantity(Amount.of(240), MeasureUnit.G), fillet.product.net)
        assertEquals(BrandKind.STORE, fillet.product.brandKind)
        val loose = mapped("search-atun.html", "62118")
        assertNull(loose.product.brand)
        assertEquals(BrandKind.UNBRANDED, loose.product.brandKind)
    }

    @Test
    fun `a size followed by pack 3 is one tin's when the unit price says so`() {
        // Seen on the phone on 2026-11-24: 2,10 € at 13,46 €/kg is 156 g, three 52 g tins.
        val tile =
            AhorramasTile(
                id = "1",
                name = "Atún Alipende 52G pack 3 en tomate",
                brand = "ALIPENDE",
                price = "2.10",
                unitPrice = "13,46€/KG.PESO ESC",
                pricePerKilo = false,
                mediumWeight = null,
                available = true,
                imageUrl = null,
                path = null,
                category1 = null,
                category2 = null,
            )
        val p = checkNotNull(AhorramasMapper.map(tile, now))
        assertEquals(Quantity(Amount.of(156), MeasureUnit.G), p.product.net)
        assertEquals(3, p.product.unitCount)
        assertFalse(p.product.approx)
    }

    @Test
    fun `a discounted tile uses the price it shows, not the struck-through one`() {
        val water = mapped("supermercapy-search.html", "52028")
        assertEquals(Money(48), water.product.price)
        assertEquals(Quantity(Amount.of(1500), MeasureUnit.ML), water.product.net)
    }

    @Test
    fun `an empty grid has no tiles`() {
        assertEquals(emptyList(), AhorramasGrid.tiles(fixture("supermercapy-search_empty.html")))
    }

    // --- Requests -------------------------------------------------------------------------

    private lateinit var server: MockWebServer
    private lateinit var provider: AhorramasProvider
    private val location = ProviderLocation("28001", AhorramasProvider.SINGLE_PRICE_LIST)

    @BeforeEach
    fun startServer() {
        server = MockWebServer().apply { start() }
        provider =
            AhorramasProvider.create(
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
            .addHeader("Content-Type", "text/html;charset=UTF-8")
            .body(body)
            .build(),
    )

    @Test
    fun `a category is one grid page of 40 tiles from the allowed controller`() =
        runTest {
            respond(fixture("search-leche.html"))
            val result = provider.productsIn(ProviderCategory("leche", "leche"), location)
            assertEquals(3, assertIs<List<*>>(assertIs<ProviderResult.Success<*>>(result).value).size)
            val request = server.takeRequest()
            val url = checkNotNull(request.url)
            assertEquals("/on/demandware.store/Sites-Ahorramas-Site/es/Search-UpdateGrid", url.encodedPath)
            assertEquals("leche", url.queryParameter("q"))
            assertEquals("40", url.queryParameter("sz"))
            assertEquals("XMLHttpRequest", request.headers["X-Requested-With"])
        }

    @Test
    fun `a product refresh searches the grid for the id`() =
        runTest {
            respond(fixture("search-leche.html"))
            val fresh = assertIs<ProviderResult.Success<ProviderProduct?>>(provider.product("46423", location)).value
            assertEquals("46423", checkNotNull(fresh).product.key.productId)
            assertEquals("46423", checkNotNull(server.takeRequest().url).queryParameter("q"))

            respond(fixture("supermercapy-search_empty.html"))
            assertNull(assertIs<ProviderResult.Success<ProviderProduct?>>(provider.product("1", location)).value)
            assertNull(assertIs<ProviderResult.Success<ProviderProduct?>>(provider.product("x y", location)).value)
            assertEquals(2, server.requestCount)
        }

    @Test
    fun `403 is a block`() =
        runTest {
            respond("", code = 403)
            val result = provider.productsIn(ProviderCategory("leche", "leche"), location)
            assertEquals(ProviderError.Blocked, assertIs<ProviderResult.Failure>(result).error)
        }
}
