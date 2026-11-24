package com.lechugones.cocina.provider.tapestry

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
 * `eroski-*` and `caprabo-*` are real answers recorded from the phone on 2026-11-24 with the app's
 * User-Agent, trimmed to a few tiles; `supermercapy-*` are supermercapy's recordings
 * (third_party/supermercapy, MIT).
 */
class TapestryProviderTest {
    private val now = Instant.parse("2026-11-24T08:00:00Z")
    private val eroski = TapestryChain.EROSKI

    private fun fixture(name: String): String =
        checkNotNull(javaClass.getResource("/fixtures/tapestry/$name")) { "missing fixture $name" }.readText()

    private fun mapped(
        file: String,
        id: String,
        chain: TapestryChain = eroski,
    ): ProviderProduct {
        val tile = checkNotNull(TapestryPages.page(fixture(file)).tiles.firstOrNull { it.id == id }) { "no $id in $file" }
        return checkNotNull(TapestryMapper.map(chain, tile, now))
    }

    // --- Mapping --------------------------------------------------------------------------

    @Test
    fun `a litre of store-brand milk without a printed unit price`() {
        val p = mapped("eroski-search-leche.json", "18672311")
        assertEquals(Quantity(Amount.of(1000), MeasureUnit.ML), p.product.net)
        assertFalse(p.product.approx)
        assertEquals(Money(105), p.product.price)
        assertEquals("Eroski", p.product.brand)
        assertEquals(BrandKind.STORE, p.product.brandKind)
        assertEquals(Money(105), p.referencePrice) // computed from the size
        assertEquals("L", p.referenceFormat)
        assertEquals("https://supermercado.eroski.es//images/18672311.jpg", p.imageUrl)
        assertTrue(checkNotNull(p.webUrl).startsWith("https://supermercado.eroski.es/es/productdetail/18672311-"))
        assertEquals("eroski", p.product.key.providerId)
    }

    @Test
    fun `decimal litres with the printed unit price`() {
        val p = mapped("eroski-search-leche.json", "157719")
        assertEquals(Quantity(Amount.of(1500), MeasureUnit.ML), p.product.net)
        assertEquals(Money(104), p.referencePrice)
        assertEquals(BrandKind.NATIONAL, mapped("eroski-search-leche.json", "26084350").product.brandKind)
    }

    @Test
    fun `eggs by the piece with a price per dozen, and per-100-ml prices restated per litre`() {
        val eggs = mapped("eroski-search-huevos.json", "21176342")
        assertEquals(Quantity(Amount.of(6), MeasureUnit.UD), eggs.product.net)
        assertEquals("docena", eggs.referenceFormat)
        assertEquals(Money(428), eggs.referencePrice)
        assertEquals(Quantity(Amount.of(18), MeasureUnit.UD), mapped("eroski-search-huevos.json", "19352863").product.net)
        val shampoo = mapped("eroski-search-huevos.json", "10894442")
        assertEquals(Quantity(Amount.of(750), MeasureUnit.ML), shampoo.product.net)
        assertEquals(Money(310), shampoo.referencePrice)
        assertEquals("L", shampoo.referenceFormat)
        // "1 ud, 300 g": the weight is the size.
        assertEquals(Quantity(Amount.of(300), MeasureUnit.G), mapped("eroski-search-huevos.json", "24141236").product.net)
    }

    @Test
    fun `multipacks, the price now rather than the struck-through one, and Eroski Basic`() {
        val albo = mapped("eroski-search-atun.json", "19134873")
        assertEquals(Quantity(Amount.of(390), MeasureUnit.G), albo.product.net)
        assertEquals(6, albo.product.unitCount)
        assertEquals(Money(900), albo.product.price)
        val basic = mapped("eroski-search-atun.json", "27115682")
        assertEquals("Eroski Basic", basic.product.brand)
        assertEquals(BrandKind.STORE, basic.product.brandKind)
        assertEquals(Quantity(Amount.of(225), MeasureUnit.G), mapped("eroski-search-atun.json", "27414911").product.net)
    }

    @Test
    fun `produce sold by weight is the minimum purchase, approximately`() {
        val banana = mapped("supermercapy-eroski-listing.json", "12069175")
        assertTrue(banana.product.approx)
        assertEquals(Quantity(Amount.of(1000), MeasureUnit.G), banana.product.net)
        assertEquals(Money(199), banana.product.price)
        assertEquals(Money(199), banana.referencePrice)
        val kiwi = mapped("supermercapy-eroski-listing.json", "18702746")
        assertEquals(Quantity(Amount.of(500), MeasureUnit.G), kiwi.product.net)
        assertEquals(Money(770), kiwi.referencePrice)
    }

    @Test
    fun `ad slots are skipped and marketplace sellers are never products`() {
        val listing = TapestryPages.page(fixture("supermercapy-eroski-listing.json"))
        assertTrue(listing.tiles.none { it.id.isBlank() })
        val market = TapestryPages.page(fixture("supermercapy-eroski-listing_marketplace.json")).tiles
        val console = market.first { it.id == "MP96290" }
        assertTrue(console.marketplace)
        assertNull(TapestryMapper.map(eroski, console, now))
    }

    @Test
    fun `Caprabo reads the same markup`() {
        val p = mapped("caprabo-search-platano.json", "21812524", TapestryChain.CAPRABO)
        assertEquals(Quantity(Amount.of(1000), MeasureUnit.G), p.product.net)
        assertEquals("caprabo", p.product.key.providerId)
        assertEquals(BrandKind.STORE, p.product.brandKind)
        val yogurt = mapped("caprabo-search-platano.json", "937128", TapestryChain.CAPRABO)
        assertEquals(Quantity(Amount.of(500), MeasureUnit.G), yogurt.product.net)
        assertEquals(4, yogurt.product.unitCount)
    }

    @Test
    fun `an empty search says it is finished`() {
        val page = TapestryPages.page(fixture("eroski-search-nothing.json"))
        assertTrue(page.tiles.isEmpty())
        assertTrue(page.finished)
    }

    @Test
    fun `a product page gives the price now, the unit price and the photo`() {
        val tile = checkNotNull(TapestryPages.productPage(fixture("supermercapy-eroski-product.html"), eroski.baseUrl))
        assertEquals("3430469", tile.id)
        val p = checkNotNull(TapestryMapper.map(eroski, tile, now))
        assertEquals(Money(1395), p.product.price)
        assertEquals(Quantity(Amount.of(3000), MeasureUnit.ML), p.product.net)
        assertEquals(Money(465), p.referencePrice)
        assertEquals("Hojiblanca", p.product.brand)
        assertTrue(checkNotNull(p.imageUrl).endsWith("/images/3430469.jpg"))
        val milk = checkNotNull(TapestryPages.productPage(fixture("supermercapy-caprabo-product.html"), TapestryChain.CAPRABO.baseUrl))
        assertEquals(Money(99), checkNotNull(TapestryMapper.map(TapestryChain.CAPRABO, milk, now)).product.price)
    }

    // --- Requests -------------------------------------------------------------------------

    private lateinit var server: MockWebServer
    private lateinit var provider: TapestryProvider
    private val location = ProviderLocation("48001", TapestryProvider.PINNED_SHOP)

    @BeforeEach
    fun startServer() {
        server = MockWebServer().apply { start() }
        provider =
            TapestryProvider.create(
                chain = eroski,
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
        type: String = "application/json;charset=UTF-8",
    ) = server.enqueue(
        MockResponse
            .Builder()
            .code(code)
            .addHeader("Content-Type", type)
            .body(body)
            .build(),
    )

    @Test
    fun `a search reads two fragments from the scroll endpoint`() =
        runTest {
            respond(fixture("eroski-search-leche.json"))
            respond(fixture("eroski-search-atun.json"))
            val result = provider.productsIn(ProviderCategory("leche", "leche"), location)
            assertEquals(8, assertIs<ProviderResult.Success<List<ProviderProduct>>>(result).value.size)
            val first = server.takeRequest()
            val url = checkNotNull(first.url)
            assertEquals("/es/search/results:loadpage", url.encodedPath)
            assertEquals("leche", url.queryParameter("q"))
            assertEquals("productListZone", url.queryParameter("t:zoneid"))
            assertEquals("1", url.queryParameter("pageNumber"))
            assertEquals("XMLHttpRequest", first.headers["X-Requested-With"])
            assertEquals("2", checkNotNull(server.takeRequest().url).queryParameter("pageNumber"))
        }

    @Test
    fun `a finished search stops after its first fragment`() =
        runTest {
            respond(fixture("eroski-search-nothing.json"))
            val result = provider.productsIn(ProviderCategory("xyz", "xyz"), location)
            assertEquals(emptyList(), assertIs<ProviderResult.Success<List<ProviderProduct>>>(result).value)
            assertEquals(1, server.requestCount)
        }

    @Test
    fun `the reCAPTCHA page is a block, never solved`() =
        runTest {
            respond(fixture("supermercapy-eroski-recaptcha_challenge.html"), type = "text/html")
            val result = provider.productsIn(ProviderCategory("leche", "leche"), location)
            assertEquals(ProviderError.Blocked, assertIs<ProviderResult.Failure>(result).error)
            assertEquals(1, server.requestCount)
        }

    @Test
    fun `a product refresh reads the product page, and the error page means gone`() =
        runTest {
            respond(fixture("supermercapy-eroski-product.html").replace("3430469", "18672311"), type = "text/html")
            val fresh = assertIs<ProviderResult.Success<ProviderProduct?>>(provider.product("18672311", location)).value
            assertEquals(Money(1395), checkNotNull(fresh).product.price)
            assertEquals("/es/productdetail/18672311-producto/", checkNotNull(server.takeRequest().url).encodedPath)

            server.enqueue(
                MockResponse
                    .Builder()
                    .code(302)
                    .addHeader("Location", "/es/error/404/")
                    .build(),
            )
            respond("<html>error</html>", type = "text/html")
            assertNull(assertIs<ProviderResult.Success<ProviderProduct?>>(provider.product("1", location)).value)
            assertNull(assertIs<ProviderResult.Success<ProviderProduct?>>(provider.product("MP96290", location)).value)
            assertEquals(3, server.requestCount)
        }

    @Test
    fun `403 is a block`() =
        runTest {
            respond("", code = 403)
            val result = provider.productsIn(ProviderCategory("leche", "leche"), location)
            assertEquals(ProviderError.Blocked, assertIs<ProviderResult.Failure>(result).error)
        }
}
