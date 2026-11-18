package com.lechugones.cocina.domain.quantity

import com.lechugones.cocina.domain.units.MeasureUnit
import com.lechugones.cocina.domain.units.UnitConverter
import java.math.BigDecimal

/**
 * A typical package size hint such as `500 g`, `6 ud` or `4 x 125 g`
 * (grammar in RECIPE_FORMAT § 7).
 */
data class PackageSize(
    val count: Int,
    val amount: Amount,
    val unit: MeasureUnit,
) {
    init {
        require(count in 1..MAX_COUNT) { "count must be 1..$MAX_COUNT: $count" }
        require(amount.milli > 0) { "amount must be positive: $amount" }
        require(unit in ALLOWED_UNITS) { "unit not allowed in a package size: $unit" }
    }

    /** Total content in the package base unit (g, ml or ud). */
    fun total(): Quantity {
        val base = UnitConverter.packageBaseUnit(unit)
        val perItem =
            checkNotNull(UnitConverter.convert(amount.toBigDecimal(), unit, base)) {
                "Same-dimension conversion cannot fail: $unit -> $base"
            }
        return Quantity(Amount.of(perItem.multiply(BigDecimal(count))), base)
    }

    /** Canonical text: `4 x 125 g`, `1.5 l`, `6 ud`. */
    fun canonical(): String = (if (count > 1) "$count x " else "") + "$amount ${unit.code}"

    override fun toString(): String = canonical()

    companion object {
        const val MAX_COUNT = 100
        val ALLOWED_UNITS: Set<MeasureUnit> =
            setOf(MeasureUnit.G, MeasureUnit.KG, MeasureUnit.ML, MeasureUnit.CL, MeasureUnit.L, MeasureUnit.UD)

        private val pattern =
            Regex(
                """^\s*(?:(\d{1,3})\s*[x×]\s*)?(\d+(?:[.,]\d+)?)\s*(g|kg|ml|cl|l|ud)\s*$""",
                RegexOption.IGNORE_CASE,
            )

        /** Returns null when [raw] does not follow the grammar (error `E11`). */
        fun parse(raw: String): PackageSize? {
            val match = pattern.matchEntire(raw) ?: return null
            val (countText, amountText, unitText) = match.destructured
            val count = if (countText.isEmpty()) 1 else countText.toInt()
            if (count !in 1..MAX_COUNT) return null
            val amount = Amount.of(BigDecimal(amountText.replace(',', '.')))
            if (amount.milli <= 0) return null
            val unit = MeasureUnit.fromCode(unitText.lowercase()) ?: return null
            return PackageSize(count, amount, unit)
        }
    }
}
