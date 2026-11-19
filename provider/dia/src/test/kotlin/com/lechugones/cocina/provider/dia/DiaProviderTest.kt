package com.lechugones.cocina.provider.dia

import com.lechugones.cocina.domain.money.Money
import com.lechugones.cocina.domain.product.BrandKind
import com.lechugones.cocina.domain.quantity.Amount
import com.lechugones.cocina.domain.quantity.Quantity
import com.lechugones.cocina.domain.units.MeasureUnit
import com.lechugones.cocina.provider.ProviderCategory
import com.lechugones.cocina.provider.ProviderError
import com.lechugones.cocina.provider.ProviderLocation
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
import kotlin.test.assertTrue

/** The fixture is a real search answer recorded from the phone on 2026-11-19, trimmed (docs/providers/dia.md). */
class DiaProviderTest {
    private val now = Instant.parse("2026-11-20T08:00:00Z")
    private val json = Json { ignoreUnknownKeys = true }

    private fun fixture(name: String): String =
        checkNotNull(javaClass.getResource("/fixtures/dia/$name")) { "missing fixture $name" }.readText()

    private val items by lazy {
        json
            .decodeFromString(
                SearchResponse.serializer(),
                fixture("search-leche.json"),
            ).items
            .associateBy { it.id }
    }

    private fun mapped(id: String) = checkNotNull(DiaMapper.map(checkNotNull(items[id]), now))

    // --- Mapping --------------------------------------------------------------------------

    @Test
    fun `a pack of six litres is 6000 ml in 6 units, store brand, with picture and reference price`() {
        val p = mapped("504P6")
        assertEquals(Quantity(Amount.of(6000), MeasureUnit.ML), p.product.net)
        assertEquals(6, p.product.unitCount)
        assertEquals(Money(498), p.product.price)
        assertEquals(BrandKind.STORE, p.product.brandKind)
        assertEquals("Dia Láctea", p.product.brand)
        assertEquals("https://www.dia.es/product_images/504P6/504P6_ISO_0_ES.jpg", p.imageUrl)
        assertEquals(Money(83), p.referencePrice)
        assertEquals("L", p.referenceFormat)
        assertEquals(listOf("Huevos, leche y mantequilla", "Leche"), p.categoryPath)
        assertEquals(false, p.product.approx)
    }

    @Test
    fun `single packs, small packs, eggs by the unit and bread by weight`() {
        assertEquals(Quantity(Amount.of(1000), MeasureUnit.ML), mapped("504").product.net)
        assertEquals(Quantity(Amount.of(600), MeasureUnit.ML), mapped("312953").product.net)
        assertEquals(3, mapped("312953").product.unitCount)
        val eggs = mapped("274009").product
        assertEquals(Quantity(Amount.of(24), MeasureUnit.UD), eggs.net)
        assertEquals(Quantity(Amount.of(350), MeasureUnit.G), mapped("288023").product.net)
    }

    @Test
    fun `the Club Dia price is not used and national brands are national`() {
        val p = mapped("148777P6").product
        assertEquals(Money(804), p.price, "regular price, not the 7,38 € Club price")
        assertEquals(BrandKind.NATIONAL, p.brandKind)
        assertEquals(BrandKind.NATIONAL, mapped("17548").product.brandKind)
    }

    @Test
    fun `the size comes from the price when the name has none, and is then approximate`() {
        val item = DiaItem("x", "Pan del día", prices = DiaPrices(price = 1.30, pricePerUnit = 2.60, measureUnit = "KILO"))
        val p = checkNotNull(DiaMapper.map(item, now)).product
        assertEquals(Quantity(Amount.of(500), MeasureUnit.G), p.net)
        assertTrue(p.approx)
    }

    // --- Endpoints ------------------------------------------------------------------------

    private lateinit var server: MockWebServer
    private lateinit var provider: DiaProvider
    private val location = ProviderLocation("08001", "08001")

    @BeforeEach
    fun startServer() {
        server = MockWebServer().apply { start() }
        provider =
            DiaProvider.create(
                client = OkHttpClient(),
                clock = Clock.fixed(now, ZoneOffset.UTC),
                baseUrl = server.url("/api/v1/").toString(),
                limiter = RateLimiter(0),
                sleep = {},
            )
    }

    @AfterEach
    fun stopServer() = server.close()

    private fun respond(
        body: String = "",
        code: Int = 200,
        header: Pair<String, String>? = null,
    ) {
        server.enqueue(
            MockResponse
                .Builder()
                .code(code)
                .body(body)
                .apply { header?.let { addHeader(it.first, it.second) } }
                .build(),
        )
    }

    @Test
    fun `the postal code session cookie is sent with the search`() =
        runTest {
            respond(code = 204, header = "Set-Cookie" to "session_id=abc; Path=/")
            respond(fixture("search-leche.json"))
            assertIs<ProviderResult.Success<*>>(provider.resolveLocation("08001"))
            val products =
                assertIs<ProviderResult.Success<List<*>>>(
                    provider.productsIn(ProviderCategory("leche", "leche"), location),
                ).value
            assertEquals(7, products.size)

            val put = server.takeRequest()
            assertEquals("PUT", put.method)
            assertTrue(put.url.toString().contains("new_postal_code=08001"))
            val search = server.takeRequest()
            assertEquals("session_id=abc", search.headers["Cookie"])
            assertTrue(search.url.toString().contains("q=leche"))
        }

    @Test
    fun `206 means the postal code is not served, 403 is a block`() =
        runTest {
            respond("""{"code":206,"type":"VALIDATION_ERROR"}""", code = 206)
            assertEquals(ProviderResult.Failure(ProviderError.NotServed), provider.resolveLocation("99999"))
            respond("<html>Access Denied</html>", code = 403)
            assertEquals(ProviderResult.Failure(ProviderError.Blocked), provider.resolveLocation("08001"))
        }

    @Test
    fun `a sync sets the session first and lists the food searches`() =
        runTest {
            respond(code = 204)
            val categories = assertIs<ProviderResult.Success<List<ProviderCategory>>>(provider.categories(location)).value
            assertEquals(DiaProvider.SEARCHES.size, categories.size)
            assertEquals("PUT", server.takeRequest().method)
        }
}
