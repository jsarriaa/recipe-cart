package com.lechugones.cocina.domain.pantry

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Why a lot is "Usar pronto" (SPEC § 4.5). */
enum class UseSoonReason {
    MANUAL,

    /** Best-before date has passed: shown as "Caducado". */
    EXPIRED,

    /** Best-before date is within the warning window. */
    BEST_BEFORE_SOON,

    /** Opened, and its opened shelf life ends within the warning window. */
    OPENED_SHELF_LIFE,
}

data class UseSoonStatus(
    /** Null when the lot is not "usar pronto". */
    val reason: UseSoonReason?,
    /** The date the food should be used by, if known (best-before or opened limit). */
    val deadline: LocalDate?,
    /** Past its best-before date, whether or not the tag was cleared. */
    val expired: Boolean,
) {
    val useSoon: Boolean get() = reason != null
}

/**
 * The "Usar pronto" rule (SPEC § 4.5, AC5.3). Pure: [today] and [zone] come from the caller.
 *
 * A manual tag always wins. An automatic reason is suppressed after the user cleared it
 * ([PantryLot.useSoonDismissedAt]), unless its trigger changed since: the lot was opened again
 * after the dismissal. Changing the best-before date clears the dismissal (the repository does
 * that when it saves the new date).
 */
object UseSoon {
    const val DEFAULT_WINDOW_DAYS = 3

    fun evaluate(
        lot: PantryLot,
        today: LocalDate,
        zone: ZoneId,
        windowDays: Int = DEFAULT_WINDOW_DAYS,
    ): UseSoonStatus {
        val limit = today.plusDays(windowDays.toLong())
        val openedLimit =
            if (lot.opened && lot.openedAt != null && lot.openedShelfLifeDays != null) {
                lot.openedAt
                    .atZone(zone)
                    .toLocalDate()
                    .plusDays(lot.openedShelfLifeDays.toLong())
            } else {
                null
            }
        val deadline = listOfNotNull(lot.bestBefore, openedLimit).minOrNull()
        val expired = lot.bestBefore != null && lot.bestBefore.isBefore(today)

        val automatic =
            when {
                expired -> UseSoonReason.EXPIRED
                lot.bestBefore != null && !lot.bestBefore.isAfter(limit) -> UseSoonReason.BEST_BEFORE_SOON
                openedLimit != null && !openedLimit.isAfter(limit) -> UseSoonReason.OPENED_SHELF_LIFE
                else -> null
            }
        val dismissed = lot.useSoonDismissedAt
        val suppressed =
            dismissed != null &&
                !(automatic == UseSoonReason.OPENED_SHELF_LIFE && lot.openedAt != null && lot.openedAt.isAfter(dismissed))
        val reason =
            when {
                lot.useSoonManual -> UseSoonReason.MANUAL
                automatic != null && !suppressed -> automatic
                else -> null
            }
        return UseSoonStatus(reason, deadline, expired)
    }

    /** What "Quitar de usar pronto" stores: no manual tag, and the automatic one dismissed now. */
    fun clear(
        lot: PantryLot,
        now: Instant,
    ): PantryLot = lot.copy(useSoonManual = false, useSoonDismissedAt = now)
}
