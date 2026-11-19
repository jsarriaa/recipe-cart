package com.lechugones.cocina.ui.format

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.lechugones.cocina.R
import com.lechugones.cocina.domain.cost.IngredientCostLine
import com.lechugones.cocina.domain.cost.RecipeCostCalculator
import com.lechugones.cocina.domain.format.SpanishNumberFormat
import com.lechugones.cocina.domain.money.Money
import com.lechugones.cocina.domain.packaging.PackageFraction
import com.lechugones.cocina.domain.packaging.PackagingNoun
import com.lechugones.cocina.domain.packaging.SimpleFraction
import java.math.BigDecimal

/** "1/2 paquete", "≈1/3 bandeja", "1 1/2 paquetes", "≈ 5 % del envase" (SPEC § 5.3). */
@Composable
fun fractionText(
    fraction: PackageFraction,
    packaging: String?,
): String =
    when (fraction) {
        is PackageFraction.Percent -> {
            stringResource(R.string.cost_fraction_percent, fraction.percent)
        }

        is PackageFraction.Fraction -> {
            val number =
                when {
                    fraction.rest == SimpleFraction.ZERO -> fraction.whole.toString()
                    fraction.whole == 0 -> "${fraction.rest.numerator}/${fraction.rest.denominator}"
                    else -> "${fraction.whole} ${fraction.rest.numerator}/${fraction.rest.denominator}"
                }
            val noun =
                PackagingNoun.of(packaging, fraction.isPlural)
                    ?: stringResource(if (fraction.isPlural) R.string.cost_package_plural else R.string.cost_package)
            (if (fraction.exact) "" else "≈") + "$number $noun"
        }
    }

/** "1/2 paquete, 250 g de 500 g" or "1 1/2 paquetes, 750 g de 2 × 500 g". Null when unknown. */
@Composable
fun usageText(line: IngredientCostLine): String? {
    val usage = line.usage ?: return null
    val fraction = line.fraction ?: return null
    val unit = usage.measure.unit
    val used = quantityText(usage.used, unit)
    val one = quantityText(usage.measure.usable, unit)
    val packages = RecipeCostCalculator.packagesFor(usage.ratio)
    val packageText = if (packages > 1) stringResource(R.string.cost_packages_times, packages, one) else one
    return stringResource(R.string.cost_usage, fractionText(fraction, line.product?.packaging), used, packageText)
}

/** A money figure with "≥" when some prices are missing, or "≈" when approximate. */
fun costFigure(
    money: Money,
    missing: Boolean,
    approximate: Boolean,
): String {
    val text = SpanishNumberFormat.money(money)
    return when {
        missing -> "≥ $text"
        approximate -> "≈ $text"
        else -> text
    }
}
