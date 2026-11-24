package com.lechugones.cocina.provider.bonarea

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
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `search-leche.json` is a real answer recorded on 2026-11-24 with the app's User-Agent, trimmed
 * to a dozen articles; `supermercapy-*.json` are supermercapy's recordings
 * (third_party/supermercapy, MIT).
 */
class BonareaProviderTest {
    private val now = Instant.parse("2026-11-24T08:00:00Z")
    private val json = Json { ignoreUnknownKeys = true }

    private fun fixture(name: String): String =
        checkNotNull(javaClass.getResource("/fixtures/bonarea/$name")) { "missing fixture $name" }.readText()

    private val articles by lazy {
        json.decodeFromString(BonareaSearchResponse.serializer(), fixture("search-leche.json")).articles.associateBy { it.identifier }
    }

    private fun mapped(id: String): ProviderProduct = checkNotNull(BonareaMapper.map(checkNotNull(articles[id]) { "no $id" }, now))

    private fun article(name: String) = json.decodeFromString(BonareaArticleResponse.serializer(), fixture(name)).article

    // --- Mapping --------------------------------------------------------------------------

    @Test
    fun `a pack of six own-brand litres is 6000 ml in 6 bricks with its price per litre`() {
        val p = mapped("13*0004")
        assertEquals(Quantity(Amount.of(6000), MeasureUnit.ML), p.product.net)
        assertEquals(6, p.product.unitCount)
        assertEquals(Money(498), p.product.price)
        assertEquals("bonÀrea", p.product.brand)
        assertEquals(BrandKind.STORE, p.product.brandKind)
        assertEquals(Money(83), p.referencePrice)
        assertEquals("L", p.referenceFormat)
        assertEquals("https://images.bonarea.com/P13_0004_1.png?width=300&height=300", p.imageUrl)
        assertEquals("https://www.bonarea-online.com/online/producto/leche-semidesnatada-paq-de-6-brics/13_0004", p.webUrl)
    }

    @Test
    fun `decimal sizes and small packs`() {
        assertEquals(Quantity(Amount.of(1500), MeasureUnit.ML), mapped("13*9103").product.net)
        val small = mapped("13*8493")
        assertEquals(Quantity(Amount.of(600), MeasureUnit.ML), small.product.net)
        assertEquals(3, small.product.unitCount)
        assertEquals(4, mapped("13*0020896").product.unitCount)
    }

    @Test
    fun `articles without the bonArea tags are national brands named in the product name`() {
        val p = mapped("13*0031")
        assertNull(p.product.brand)
        assertEquals(BrandKind.NATIONAL, p.product.brandKind)
        assertEquals(BrandKind.STORE, mapped("13*5697").product.brandKind) // GARANTIA_BONAREA
    }

    @Test
    fun `approximate weights are marked approximate`() {
        val bacon = mapped("13*5697")
        assertEquals(Quantity(Amount.of(187), MeasureUnit.G), bacon.product.net)
        assertTrue(bacon.product.approx)
        assertEquals(Money(1194), bacon.referencePrice)
        assertEquals("kg", bacon.referenceFormat)
    }

    @Test
    fun `pieces are counted, frozen food is food, and stock is availability`() {
        val capsules = mapped("13*8262")
        assertEquals(Quantity(Amount.of(16), MeasureUnit.UD), capsules.product.net)
        assertEquals(Money(22), capsules.referencePrice)
        assertEquals("ud", capsules.referenceFormat)
        assertEquals(6, mapped("99*0321").product.unitCount)
        assertFalse(mapped("13*9576").product.available)
    }

    @Test
    fun `HTML entities in names are decoded`() {
        val name = mapped("13*0088606").product.name
        assertTrue(name.endsWith("Palmolive leche & miel"), name)
    }

    @Test
    fun `garden and drugstore articles are skipped`() {
        assertNull(BonareaMapper.map(checkNotNull(articles["03*61335"]), now))
        assertNull(BonareaMapper.map(checkNotNull(articles["03*93701"]), now))
    }

    @Test
    fun `supermercapy's product answers map, and a missing article is null`() {
        val wings = checkNotNull(BonareaMapper.map(checkNotNull(article("supermercapy-product.json")), now))
        assertEquals(Quantity(Amount.of(561), MeasureUnit.G), wings.product.net)
        assertTrue(wings.product.approx)
        assertEquals(Money(283), wings.product.price)
        assertNull(article("supermercapy-product_missing.json"))
    }

    // --- Requests -------------------------------------------------------------------------

    private lateinit var server: MockWebServer
    private lateinit var provider: BonareaProvider
    private val location = ProviderLocation("25001", BonareaProvider.NATIONAL)

    @BeforeEach
    fun startServer() {
        server = MockWebServer().apply { start() }
        provider =
            BonareaProvider.create(
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
        cookie: String? = null,
    ) = server.enqueue(
        MockResponse
            .Builder()
            .code(code)
            .addHeader("Content-Type", "application/json; charset=utf-8")
            .apply { cookie?.let { addHeader("Set-Cookie", it) } }
            .body(body)
            .build(),
    )

    @Test
    fun `the first search warms the Spanish session once, then posts the query`() =
        runTest {
            assertEquals(
                FoodSearches.TERMS,
                assertIs<ProviderResult.Success<List<ProviderCategory>>>(
                    provider.categories(location),
                ).value.map {
                    it.id
                },
            )
            respond("<html></html>", cookie = "ASP.NET_SessionId=abc; Path=/")
            respond(fixture("search-leche.json"))
            respond(fixture("search-leche.json"))
            val first = provider.productsIn(ProviderCategory("leche", "leche"), location)
            assertEquals(10, assertIs<List<*>>(assertIs<ProviderResult.Success<*>>(first).value).size)
            provider.productsIn(ProviderCategory("yogur", "yogur"), location)

            val home = server.takeRequest()
            assertEquals("GET", home.method)
            assertEquals("/es", checkNotNull(home.url).encodedPath)
            val search = server.takeRequest()
            assertEquals("POST", search.method)
            assertEquals("/es/shop/search", checkNotNull(search.url).encodedPath)
            assertEquals("strQuery=leche", search.body?.utf8())
            assertEquals("ASP.NET_SessionId=abc", search.headers["Cookie"])
            assertEquals("strQuery=yogur", server.takeRequest().body?.utf8())
            assertEquals(3, server.requestCount)
        }

    @Test
    fun `a product refresh posts the id, and an empty answer means gone`() =
        runTest {
            respond(fixture("supermercapy-product_packaged.json"))
            val fresh = assertIs<ProviderResult.Success<ProviderProduct?>>(provider.product("13*0066", location)).value
            assertEquals(Money(672), checkNotNull(fresh).product.price)
            val request = server.takeRequest()
            assertEquals("/es/shop/Article", checkNotNull(request.url).encodedPath)
            assertEquals("identifier=13*0066", request.body?.utf8())

            respond(fixture("supermercapy-product_missing.json"))
            assertNull(assertIs<ProviderResult.Success<ProviderProduct?>>(provider.product("13*1", location)).value)
        }

    @Test
    fun `403 is a block`() =
        runTest {
            respond("", code = 403)
            val result = provider.productsIn(ProviderCategory("leche", "leche"), location)
            assertEquals(ProviderError.Blocked, assertIs<ProviderResult.Failure>(result).error)
            assertEquals(1, server.requestCount)
        }
}
