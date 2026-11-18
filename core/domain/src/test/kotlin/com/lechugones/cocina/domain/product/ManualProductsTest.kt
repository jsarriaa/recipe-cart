package com.lechugones.cocina.domain.product

import com.lechugones.cocina.domain.money.Money
import com.lechugones.cocina.domain.quantity.Amount
import com.lechugones.cocina.domain.quantity.Quantity
import com.lechugones.cocina.domain.units.MeasureUnit
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class ManualProductsTest {
    private val now = Instant.parse("2026-11-19T10:00:00Z")

    private fun valid(form: ManualProductForm) = assertIs<ManualProductResult.Valid>(ManualProducts.build("p1", form, now))

    @Test
    fun `a typed product becomes a normal product of the manual provider`() {
        val result = valid(ManualProductForm(" Yogur griego ", brand = "Ecológica", size = "4 x 125 g", price = "2,35", shop = " Mercado "))
        val p = result.product
        assertEquals(ProductKey("manual", "p1"), p.key)
        assertEquals("Yogur griego", p.name)
        assertEquals(Quantity(Amount.of(500), MeasureUnit.G), p.net)
        assertEquals(4, p.unitCount)
        assertEquals(Money(235), p.price)
        assertEquals(BrandKind.NATIONAL, p.brandKind)
        assertEquals("Mercado", result.shop)
        assertEquals(now, p.fetchedAt)
    }

    @Test
    fun `litres are stored in ml, price and brand are optional`() {
        val p = valid(ManualProductForm("Leche fresca", size = "1 l")).product
        assertEquals(Quantity(Amount.of(1000), MeasureUnit.ML), p.net)
        assertNull(p.price)
        assertEquals(BrandKind.UNBRANDED, p.brandKind)
        assertEquals(
            BrandKind.STORE,
            valid(ManualProductForm("Arroz", brand = "Bonpreu", storeBrand = true, size = "1 kg")).product.brandKind,
        )
    }

    @Test
    fun `every bad field is reported`() {
        val result = ManualProducts.build("p1", ManualProductForm(" ", size = "un paquete", price = "barato"), now)
        assertEquals(
            setOf(ManualProductField.NAME, ManualProductField.SIZE, ManualProductField.PRICE),
            assertIs<ManualProductResult.Invalid>(result).fields,
        )
    }
}
