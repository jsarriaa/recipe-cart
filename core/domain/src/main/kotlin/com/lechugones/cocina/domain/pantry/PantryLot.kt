package com.lechugones.cocina.domain.pantry

import com.lechugones.cocina.domain.product.ProductKey
import com.lechugones.cocina.domain.quantity.Amount
import com.lechugones.cocina.domain.recipe.NameKey
import com.lechugones.cocina.domain.units.MeasureUnit
import java.time.Instant
import java.time.LocalDate

/** Where a lot is kept (SPEC § 4.5). Codes are stored in the database. */
enum class PantryLocation(
    val code: String,
) {
    FRIDGE("nevera"),
    FREEZER("congelador"),
    CUPBOARD("armario"),
    ;

    companion object {
        fun fromCode(code: String?): PantryLocation? = entries.firstOrNull { it.code == code }
    }
}

/**
 * One package or one opened remainder in the pantry (SPEC § 4.5).
 *
 * [quantity] is what is left, in [unit] (a package base unit: g, ml or ud, or a count unit such
 * as loncha). [presenceOnly] staples ("tengo sal") have no quantity.
 */
data class PantryLot(
    val id: String,
    val name: String,
    val addedAt: Instant,
    val product: ProductKey? = null,
    val quantity: Amount? = null,
    val unit: MeasureUnit? = null,
    /** Size of a full package, in [unit]; null when unknown. */
    val packageQuantity: Amount? = null,
    val opened: Boolean = false,
    val openedAt: Instant? = null,
    val bestBefore: LocalDate? = null,
    val location: PantryLocation? = null,
    val presenceOnly: Boolean = false,
    /** The user tagged it "Usar pronto" by hand. */
    val useSoonManual: Boolean = false,
    /** The user cleared an automatic "Usar pronto"; it returns only when its trigger changes. */
    val useSoonDismissedAt: Instant? = null,
    /** "Una vez abierto, se conserva N días". */
    val openedShelfLifeDays: Int? = null,
) {
    init {
        require(name.isNotBlank()) { "a pantry lot needs a name" }
        require(presenceOnly || (quantity == null) == (unit == null)) { "quantity and unit go together" }
        require(quantity == null || quantity.milli >= 0) { "quantity cannot be negative" }
        require(openedShelfLifeDays == null || openedShelfLifeDays > 0) { "shelf life must be positive" }
    }

    val nameKey: String get() = NameKey.of(name)

    /** Has an amount that recipes can use. */
    val hasStock: Boolean get() = !presenceOnly && quantity != null && quantity.milli > 0
}
