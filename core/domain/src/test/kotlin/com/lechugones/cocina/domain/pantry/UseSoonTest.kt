package com.lechugones.cocina.domain.pantry

import com.lechugones.cocina.domain.quantity.Amount
import com.lechugones.cocina.domain.units.MeasureUnit
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** AC5.3: the "Usar pronto" rule. Window is the default 3 days; today is 20/11/2026. */
class UseSoonTest {
    private val zone = ZoneOffset.UTC
    private val today = LocalDate.parse("2026-11-20")
    private val now = Instant.parse("2026-11-20T10:00:00Z")

    private fun lot(
        bestBefore: LocalDate? = null,
        openedAt: Instant? = null,
        shelfLife: Int? = null,
        manual: Boolean = false,
        dismissedAt: Instant? = null,
    ) = PantryLot(
        id = "l",
        name = "nata",
        addedAt = Instant.parse("2026-11-01T00:00:00Z"),
        quantity = Amount.of(200),
        unit = MeasureUnit.ML,
        opened = openedAt != null,
        openedAt = openedAt,
        bestBefore = bestBefore,
        useSoonManual = manual,
        useSoonDismissedAt = dismissedAt,
        openedShelfLifeDays = shelfLife,
    )

    private fun reason(lot: PantryLot) = UseSoon.evaluate(lot, today, zone).reason

    @Test
    fun `a manual tag always counts`() {
        assertEquals(UseSoonReason.MANUAL, reason(lot(manual = true)))
        assertEquals(UseSoonReason.MANUAL, reason(lot(manual = true, dismissedAt = now)))
    }

    @ParameterizedTest
    @CsvSource(
        "2026-11-19,EXPIRED",
        "2026-11-20,BEST_BEFORE_SOON",
        "2026-11-23,BEST_BEFORE_SOON",
        "2026-11-24,",
    )
    fun `best-before inside the window, on its edge and outside`(
        bestBefore: String,
        expected: UseSoonReason?,
    ) {
        assertEquals(expected, reason(lot(bestBefore = LocalDate.parse(bestBefore))))
    }

    @Test
    fun `expired lots are flagged even after the tag is cleared`() {
        val status = UseSoon.evaluate(lot(bestBefore = today.minusDays(2), dismissedAt = now), today, zone)
        assertTrue(status.expired)
        assertFalse(status.useSoon)
    }

    @Test
    fun `opened shelf life counts from the opening day`() {
        val opened = Instant.parse("2026-11-18T09:00:00Z")
        assertEquals(UseSoonReason.OPENED_SHELF_LIFE, reason(lot(openedAt = opened, shelfLife = 4)))
        assertEquals(LocalDate.parse("2026-11-22"), UseSoon.evaluate(lot(openedAt = opened, shelfLife = 4), today, zone).deadline)
        assertNull(reason(lot(openedAt = opened, shelfLife = 10)))
        assertNull(reason(lot(openedAt = opened)), "no shelf life known")
    }

    @Test
    fun `a cleared automatic tag stays cleared until the lot is opened again`() {
        val opened = Instant.parse("2026-11-18T09:00:00Z")
        val cleared = UseSoon.clear(lot(openedAt = opened, shelfLife = 4), now)
        assertNull(reason(cleared))
        val reopened = cleared.copy(openedAt = now.plusSeconds(60), openedShelfLifeDays = 2)
        assertEquals(UseSoonReason.OPENED_SHELF_LIFE, reason(reopened))
        assertNull(reason(UseSoon.clear(lot(bestBefore = today.plusDays(1)), now)))
    }

    @Test
    fun `the window can be changed`() {
        val soon = lot(bestBefore = today.plusDays(5))
        assertNull(UseSoon.evaluate(soon, today, zone, windowDays = 3).reason)
        assertEquals(UseSoonReason.BEST_BEFORE_SOON, UseSoon.evaluate(soon, today, zone, windowDays = 7).reason)
    }
}
