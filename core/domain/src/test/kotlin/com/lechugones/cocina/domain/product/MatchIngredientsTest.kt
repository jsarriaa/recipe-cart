package com.lechugones.cocina.domain.product

import com.lechugones.cocina.domain.money.Money
import com.lechugones.cocina.domain.quantity.Amount
import com.lechugones.cocina.domain.quantity.PackageSize
import com.lechugones.cocina.domain.quantity.Quantity
import com.lechugones.cocina.domain.recipe.MatchHints
import com.lechugones.cocina.domain.units.MeasureUnit
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Matching quality against a small fixture catalogue of real Mercadona names (SPEC § 5.4). */
class MatchIngredientsTest {
    private fun product(
        id: String,
        name: String,
        grams: Int,
        kind: BrandKind = BrandKind.STORE,
        unit: MeasureUnit = MeasureUnit.G,
        available: Boolean = true,
    ) = Product(
        key = ProductKey("mercadona", id),
        name = name,
        net = Quantity(Amount.of(grams), unit),
        price = Money(100),
        brandKind = kind,
        available = available,
    )

    private val catalogue =
        listOf(
            product("tortillas", "Tortillas de trigo Hacendado", 360),
            product("maxi", "Maxi tortillas de trigo Hacendado", 360),
            product("mini", "Mini tortillas de trigo Hacendado", 300),
            product("old-el-paso", "Tortillas de trigo Old El Paso", 326, BrandKind.NATIONAL),
            product("tomate-pera", "Tomate pera", 1000, BrandKind.UNBRANDED),
            product("tomate-frito", "Tomate frito Hacendado", 400),
            product("tomates-cherry", "Tomates cherry", 250, BrandKind.UNBRANDED),
            product("limon", "Limón", 1000, BrandKind.UNBRANDED),
            product("zumo-limon", "Zumo de limón exprimido Hacendado", 1000, unit = MeasureUnit.ML),
            product("aguacate", "Aguacate", 200, BrandKind.UNBRANDED),
            product("guacamole", "Guacamole Hacendado", 200),
            product("gone", "Pechuga de pollo", 500, BrandKind.UNBRANDED, available = false),
        )

    private fun best(
        name: String,
        hints: MatchHints? = null,
        storeBrandOnly: Boolean = false,
    ) = ProductMatcher.match(name, hints, searchResults = catalogue, storeBrandOnly = storeBrandOnly)

    @Test
    fun `the plain product beats longer names that contain the words`() {
        val result = best("tortillas", MatchHints("tortillas de trigo", PackageSize.parse("360 g")))
        assertEquals(
            "tortillas",
            result.best
                ?.product
                ?.key
                ?.productId,
        )
        assertTrue(checkNotNull(result.preselected).score >= ProductMatcher.PRESELECT_SCORE)
    }

    @Test
    fun `plurals and accents fold both ways`() {
        assertEquals(
            "limon",
            best("limones")
                .best
                ?.product
                ?.key
                ?.productId,
        )
        assertEquals(
            "tomates-cherry",
            best("tomates cherry")
                .best
                ?.product
                ?.key
                ?.productId,
        )
        assertEquals(ProductMatcher.stem("tomates"), ProductMatcher.stem("tomate"))
        assertEquals(ProductMatcher.stem("limones"), ProductMatcher.stem("limon"))
    }

    @Test
    fun `word order earns a bonus`() {
        assertTrue(
            ProductMatcher.textScore("tomate frito", "Tomate frito Hacendado") >
                ProductMatcher.textScore("frito tomate", "Tomate frito Hacendado"),
        )
        assertEquals(60, ProductMatcher.textScore("tomate frito", "Tomate frito Hacendado"))
    }

    @ParameterizedTest
    @CsvSource("360,25", "720,13", "1440,0", "90,0", "180,13")
    fun `package size score follows the log distance to the hint`(
        grams: Int,
        expected: Int,
    ) {
        val hints = MatchHints("x", PackageSize.parse("360 g"))
        assertEquals(expected, ProductMatcher.sizeScore(hints, product("p", "x", grams)))
    }

    @Test
    fun `size is neutral without a hint or with another dimension`() {
        assertEquals(12, ProductMatcher.sizeScore(null, product("p", "x", 360)))
        assertEquals(12, ProductMatcher.sizeScore(MatchHints("x", PackageSize.parse("1 l")), product("p", "x", 360)))
    }

    @Test
    fun `store brand only drops national brands, unavailable products are never suggested`() {
        val ids = best("tortillas trigo", storeBrandOnly = true).suggestions.map { it.product.key.productId }
        assertTrue("old-el-paso" !in ids)
        assertTrue("tortillas" in ids)
        assertTrue(best("pechuga de pollo").suggestions.none { it.product.key.productId == "gone" })
    }

    @Test
    fun `national brands rank below store brands unless a brand is asked for`() {
        assertEquals(15, ProductMatcher.brandScore(null, catalogue[0]))
        assertEquals(5, ProductMatcher.brandScore(null, catalogue[3]))
        assertEquals(10, ProductMatcher.brandScore(MatchHints("old el paso", storeBrandOk = false), catalogue[3]))
        assertEquals(
            "old-el-paso",
            best("tortillas", MatchHints("tortillas old el paso", storeBrandOk = false))
                .best
                ?.product
                ?.key
                ?.productId,
        )
    }

    @Test
    fun `file links and remembered links come first and are always preselected`() {
        val remembered = catalogue.first { it.key.productId == "maxi" }
        val file = catalogue.first { it.key.productId == "mini" }
        val result =
            ProductMatcher.match(
                "tortillas",
                null,
                fileLinks = listOf(file),
                remembered = listOf(remembered, file),
                searchResults = catalogue,
            )
        assertEquals(listOf("mini", "maxi"), result.suggestions.take(2).map { it.product.key.productId })
        assertEquals(SuggestionSource.FILE, result.preselected?.source)
        assertEquals(
            result.suggestions.size,
            result.suggestions
                .map { it.product.key }
                .toSet()
                .size,
            "no duplicates",
        )
    }

    @Test
    fun `a weak match is suggested but not preselected`() {
        val result = best("salsa de aguacate y lima")
        assertTrue(result.best != null)
        assertNull(result.preselected)
    }

    @Test
    fun `nothing to suggest for an empty catalogue`() {
        val result = ProductMatcher.match("sal", null)
        assertNull(result.best)
        assertNull(result.preselected)
    }
}
