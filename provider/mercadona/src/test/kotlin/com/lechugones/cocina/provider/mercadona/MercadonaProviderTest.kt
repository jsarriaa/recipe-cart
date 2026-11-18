package com.lechugones.cocina.provider.mercadona

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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Fixtures are real responses recorded on 2026-11-18 and trimmed (docs/providers/mercadona.md). */
class MercadonaProviderTest {
    private val now = Instant.parse("2026-11-20T08:00:00Z")
    private val json = Json { ignoreUnknownKeys = true }

    private fun fixture(name: String): String =
        checkNotNull(javaClass.getResource("/fixtures/mercadona/$name")) { "missing fixture $name" }.readText()

    private fun listing(id: String) =
        checkNotNull(MercadonaMapper.map(json.decodeFromString(ProductDto.serializer(), fixture("listing-$id.json")), now))

    // --- Mapping --------------------------------------------------------------------------

    @Test
    fun `maxi tortillas are 360 g with 6 pieces, store brand`() {
        val p = listing("80870").product
        assertEquals("Maxi tortillas de trigo Hacendado", p.name)
        assertEquals(Quantity(Amount.of(360), MeasureUnit.G), p.net)
        assertEquals(6, p.unitCount)
        assertEquals(MeasureUnit.UD, p.unitCountUnit)
        assertEquals(Money(150), p.price)
        assertEquals(BrandKind.STORE, p.brandKind)
        assertEquals("Paquete", p.packaging)
        assertEquals(now, p.fetchedAt)
    }

    @Test
    fun `a single avocado is one approximate piece of 200 g, unbranded`() {
        val p = listing("3830").product
        assertEquals(Quantity(Amount.of(200), MeasureUnit.G), p.net)
        assertEquals(1, p.unitCount)
        assertTrue(p.approx)
        assertEquals(BrandKind.UNBRANDED, p.brandKind)
    }

    @Test
    fun `fresh mozzarella keeps its drained weight`() {
        val p = listing("51050").product
        assertEquals(Quantity(Amount.of(250), MeasureUnit.G), p.net)
        assertEquals(Quantity(Amount.of(125), MeasureUnit.G), p.drained)
        assertEquals(Money(90), p.price)
    }

    @Test
    fun `sliced cheese counts slices`() {
        val p = listing("51126").product
        assertEquals(10, p.unitCount)
        assertEquals(MeasureUnit.LONCHA, p.unitCountUnit)
    }

    @Test
    fun `meat trays are approximate and unbranded, with image and reference price`() {
        val mapped = listing("2789")
        assertTrue(mapped.product.approx)
        assertEquals(BrandKind.UNBRANDED, mapped.product.brandKind)
        assertNotNull(mapped.imageUrl)
        assertEquals(Money(680), mapped.referencePrice)
        assertEquals("kg", mapped.referenceFormat)
    }

    @Test
    fun `product detail carries the brand`() {
        val detail = json.decodeFromString(ProductDto.serializer(), fixture("product-23353.json"))
        val p = checkNotNull(MercadonaMapper.map(detail, now)).product
        assertEquals("Hacendado", p.brand)
        assertEquals(Quantity(Amount.of(17), MeasureUnit.G), p.net)
    }

    @Test
    fun `brand classification`() {
        fun dto(
            name: String,
            brand: String? = null,
            top: Int = 12,
        ) = ProductDto(
            id = "1",
            displayName = name,
            brand = brand,
            categories = listOf(CategoryRef(top, "x")),
            price = PriceInstructions("1.00", 1.0, "kg"),
        )
        assertEquals(BrandKind.STORE, MercadonaMapper.brandKind(dto("Tomate frito Hacendado")))
        assertEquals(BrandKind.STORE, MercadonaMapper.brandKind(dto("Gel", brand = "Deliplus")))
        assertEquals(BrandKind.NATIONAL, MercadonaMapper.brandKind(dto("Ketchup", brand = "Heinz")))
        assertEquals(BrandKind.NATIONAL, MercadonaMapper.brandKind(dto("Ketchup Heinz")))
        assertEquals(BrandKind.UNBRANDED, MercadonaMapper.brandKind(dto("Manzana golden", top = 1)))
    }

    @Test
    fun `products without size or price are skipped`() {
        val noSize = ProductDto(id = "1", displayName = "x", price = PriceInstructions("1.00", null, "kg"))
        val badPrice = ProductDto(id = "2", displayName = "y", price = PriceInstructions("gratis", 1.0, "kg"))
        assertNull(MercadonaMapper.map(noSize, now))
        assertNull(MercadonaMapper.map(badPrice, now))
    }

    // --- Endpoints ------------------------------------------------------------------------

    private lateinit var server: MockWebServer
    private lateinit var provider: MercadonaProvider
    private val location = ProviderLocation("08001", "bcn1")

    @BeforeEach
    fun startServer() {
        server = MockWebServer().apply { start() }
        provider =
            MercadonaProvider.create(
                client = OkHttpClient(),
                clock = Clock.fixed(now, ZoneOffset.UTC),
                baseUrl = server.url("/api/").toString(),
                limiter = RateLimiter(0),
                sleep = {},
            )
    }

    @AfterEach
    fun stopServer() = server.close()

    private fun respond(
        body: String,
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
    fun `postal code resolves to the warehouse header`() =
        runTest {
            respond("""{"warehouse_changed":false}""", header = "x-customer-wh" to "bcn1")
            assertEquals(ProviderResult.Success(location), provider.resolveLocation("08001"))
            val request = server.takeRequest()
            assertEquals("PUT", request.method)
            assertTrue(
                request.body
                    ?.utf8()
                    .orEmpty()
                    .contains("\"new_postal_code\":\"08001\""),
            )
        }

    @Test
    fun `a postal code without warehouse is not served`() =
        runTest {
            respond("{}")
            assertEquals(ProviderResult.Failure(ProviderError.NotServed), provider.resolveLocation("99999"))
        }

    @Test
    fun `categories skip non-food sections`() =
        runTest {
            respond(fixture("categories.json"))
            val categories = assertIs<ProviderResult.Success<List<ProviderCategory>>>(provider.categories(location)).value
            val parents = categories.map { it.parentName }.toSet()
            assertTrue("Limpieza y hogar" !in parents)
            assertTrue(categories.any { it.name == "Especias" && it.parentName == "Aceite, especias y salsas" })
            assertTrue(categories.any { it.parentName == "Bebé" && it.name == "Alimentación infantil" })
            assertTrue(categories.none { it.id in setOf("217", "218", "219") })
            assertTrue(
                server
                    .takeRequest()
                    .url
                    .toString()
                    .contains("wh=bcn1"),
            )
        }

    @Test
    fun `products of a category carry their path`() =
        runTest {
            respond(fixture("category-115.json"))
            val products =
                assertIs<ProviderResult.Success<List<*>>>(
                    provider.productsIn(ProviderCategory("115", "Especias", "Aceite, especias y salsas"), location),
                ).value
            assertEquals(6, products.size)
            val first = products.first() as com.lechugones.cocina.provider.ProviderProduct
            assertEquals("Aceite, especias y salsas", first.categoryPath.first())
            assertEquals("Especias", first.categoryPath[1])
        }

    @Test
    fun `a missing product is reported as gone, a block as blocked`() =
        runTest {
            respond("{}", code = 404)
            assertEquals(ProviderResult.Success(null), provider.product("999", location))
            respond("<html>Access Denied</html>", code = 403)
            assertEquals(ProviderResult.Failure(ProviderError.Blocked), provider.product("80870", location))
        }
}
