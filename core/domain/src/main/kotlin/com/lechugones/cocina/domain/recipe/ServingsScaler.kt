package com.lechugones.cocina.domain.recipe

import com.lechugones.cocina.domain.quantity.Amount
import com.lechugones.cocina.domain.units.MeasureUnit
import java.math.BigDecimal
import java.math.RoundingMode

/** Scales ingredient quantities between serving counts (SPEC § 4.2). */
object ServingsScaler {
    private const val SCALE = 9

    /**
     * Returns [quantity] scaled from [fromServings] to [toServings], at full precision.
     * Negligible units (`pizca`, `al_gusto`) are returned unchanged. Null stays null.
     */
    fun scale(
        quantity: Amount?,
        unit: MeasureUnit,
        fromServings: Int,
        toServings: Int,
    ): BigDecimal? {
        require(fromServings > 0 && toServings > 0) { "servings must be positive" }
        val value = quantity?.toBigDecimal() ?: return null
        if (!unit.scalesWithServings || fromServings == toServings) return value
        return value
            .multiply(BigDecimal(toServings))
            .divide(BigDecimal(fromServings), SCALE, RoundingMode.HALF_UP)
    }
}
