package com.lechugones.cocina.feature.shopping

import com.lechugones.cocina.domain.format.SpanishNumberFormat
import com.lechugones.cocina.domain.money.Money
import com.lechugones.cocina.domain.product.Product
import com.lechugones.cocina.domain.product.ProductKey
import com.lechugones.cocina.domain.quantity.Amount
import com.lechugones.cocina.domain.quantity.Quantity
import com.lechugones.cocina.domain.shopping.ShoppingGroup
import com.lechugones.cocina.domain.shopping.ShoppingItem
import com.lechugones.cocina.domain.shopping.ShoppingList
import com.lechugones.cocina.domain.units.MeasureUnit
import org.junit.Test
import kotlin.test.assertEquals

/** AC6.2: the shared text follows the SPEC § 4.6 example line by line. */
class ShoppingShareTextTest {
    private fun item(
        name: String,
        cents: Long?,
        grams: Long = 0,
        approx: Boolean = false,
        id: String = name,
    ) = ShoppingItem(
        key = id,
        name = name,
        product =
            cents?.let {
                Product(
                    ProductKey("mercadona", id),
                    name,
                    Quantity(Amount.of(grams.coerceAtLeast(1)), MeasureUnit.G),
                    Money(it),
                    approx = approx,
                )
            },
        packages = cents?.let { 1 },
        needed = null,
        unit = null,
        cost = cents?.let(::Money),
        approximate = approx,
        sources = emptyList(),
    )

    private val sizes =
        mapOf(
            "Contramuslos de pollo deshuesados y sin piel" to "≈ 550 g",
            "Maxi tortillas de trigo Hacendado" to "6 ud",
            "Aguacate" to "≈ 200 g",
            "Tomates cherry" to "500 g",
        )

    private val labels =
        ShareLabels(
            title = "Lista de la compra — 18/11/2026",
            noSupermarket = "SIN SUPERMERCADO",
            providerName = { "MERCADONA" },
            total = { money, missing -> "Total estimado: $money" + if (missing == 1) " (1 artículo sin precio)" else "" },
            size = { sizes[it.name] },
        )

    @Test
    fun `matches the documented example`() {
        val mercadona =
            listOf(
                item("Contramuslos de pollo deshuesados y sin piel", 374, 550, approx = true),
                item("Maxi tortillas de trigo Hacendado", 150, 360),
                item("Aguacate", 112, 200, approx = true),
                item("Tomates cherry", 220, 500),
            )
        val noShop = listOf(item("Cilantro fresco", null))
        val list =
            ShoppingList(
                groups =
                    listOf(
                        ShoppingGroup("mercadona", mercadona, Money(856), approximate = true, missingPrices = 0),
                        ShoppingGroup(null, noShop, Money.ZERO, approximate = false, missingPrices = 0),
                    ),
                total = Money(856),
                approximate = true,
                missingPrices = 1,
            )
        val euro = SpanishNumberFormat.NO_BREAK_SPACE + "€"
        val expected =
            """
            Lista de la compra — 18/11/2026

            MERCADONA (≈ 8,56$euro)
            [ ] 1 × Contramuslos de pollo deshuesados y sin piel (≈ 550 g) — ≈ 3,74$euro
            [ ] 1 × Maxi tortillas de trigo Hacendado (6 ud) — 1,50$euro
            [ ] 1 × Aguacate (≈ 200 g) — ≈ 1,12$euro
            [ ] 1 × Tomates cherry (500 g) — 2,20$euro

            SIN SUPERMERCADO
            [ ] Cilantro fresco

            Total estimado: ≈ 8,56$euro (1 artículo sin precio)
            """.trimIndent()
        assertEquals(expected, ShoppingShareText.format(list, labels))
    }

    @Test
    fun `checked items are marked`() {
        val list =
            ShoppingList(
                listOf(ShoppingGroup(null, listOf(item("Sal", null).copy(checked = true)), Money.ZERO, false, 0)),
                Money.ZERO,
                approximate = false,
                missingPrices = 1,
            )
        assertEquals(true, ShoppingShareText.format(list, labels).contains("[x] Sal"))
    }
}
