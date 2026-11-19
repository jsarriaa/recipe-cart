package com.lechugones.cocina.feature.pantry

import com.lechugones.cocina.domain.pantry.PantryLocation
import com.lechugones.cocina.domain.pantry.PantryLot
import com.lechugones.cocina.domain.product.ProductKey
import com.lechugones.cocina.domain.quantity.Amount
import com.lechugones.cocina.domain.units.MeasureUnit
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

/** A pantry lot as typed in the editor. Numbers are text with a decimal comma. */
data class LotForm(
    val id: String,
    val isNew: Boolean,
    val addedAt: Instant,
    val name: String = "",
    val product: ProductKey? = null,
    val quantity: String = "",
    val unit: MeasureUnit = MeasureUnit.G,
    val packageQuantity: Amount? = null,
    val opened: Boolean = false,
    val openedAt: Instant? = null,
    val bestBefore: LocalDate? = null,
    val location: PantryLocation? = null,
    val presenceOnly: Boolean = false,
    val useSoonManual: Boolean = false,
    val useSoonDismissedAt: Instant? = null,
    val shelfLifeDays: String = "",
) {
    val nameValid: Boolean get() = name.isNotBlank()
    val quantityValid: Boolean get() = presenceOnly || quantity.isBlank() || parsedQuantity() != null
    val shelfLifeValid: Boolean get() = shelfLifeDays.isBlank() || shelfLifeDays.toIntOrNull()?.let { it in 1..MAX_SHELF_LIFE } == true
    val valid: Boolean get() = nameValid && quantityValid && shelfLifeValid

    private fun parsedQuantity(): Amount? =
        quantity
            .trim()
            .replace(',', '.')
            .toBigDecimalOrNull()
            ?.takeIf { it.signum() >= 0 }
            ?.let { Amount.of(it) }

    /** The lot to save, or null while the form is invalid. [now] stamps a lot that is opened here. */
    fun toLot(now: Instant): PantryLot? {
        if (!valid) return null
        val amount = if (presenceOnly) null else parsedQuantity()
        return PantryLot(
            id = id,
            name = name.trim(),
            addedAt = addedAt,
            product = product,
            quantity = amount,
            unit = if (amount == null) null else unit,
            packageQuantity = packageQuantity.takeIf { amount != null },
            opened = opened && !presenceOnly,
            openedAt = if (opened && !presenceOnly) openedAt ?: now else null,
            bestBefore = bestBefore,
            location = location,
            presenceOnly = presenceOnly,
            useSoonManual = useSoonManual,
            useSoonDismissedAt = useSoonDismissedAt,
            openedShelfLifeDays = shelfLifeDays.toIntOrNull(),
        )
    }

    companion object {
        const val MAX_SHELF_LIFE = 365

        /** Units offered for lots: what packages are measured in. */
        val UNITS = listOf(MeasureUnit.G, MeasureUnit.ML, MeasureUnit.UD)

        fun fromLot(
            lot: PantryLot,
            isNew: Boolean = false,
        ) = LotForm(
            id = lot.id,
            isNew = isNew,
            addedAt = lot.addedAt,
            name = lot.name,
            product = lot.product,
            quantity = lot.quantity?.let { editable(it.toBigDecimal()) }.orEmpty(),
            unit = lot.unit ?: MeasureUnit.G,
            packageQuantity = lot.packageQuantity,
            opened = lot.opened,
            openedAt = lot.openedAt,
            bestBefore = lot.bestBefore,
            location = lot.location,
            presenceOnly = lot.presenceOnly,
            useSoonManual = lot.useSoonManual,
            useSoonDismissedAt = lot.useSoonDismissedAt,
            shelfLifeDays = lot.openedShelfLifeDays?.toString().orEmpty(),
        )

        /** `1,5`, `360`: decimal comma, no grouping, so it can be typed back. */
        fun editable(value: BigDecimal): String = value.stripTrailingZeros().toPlainString().replace('.', ',')
    }
}
