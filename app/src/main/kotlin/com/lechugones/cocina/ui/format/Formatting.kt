package com.lechugones.cocina.ui.format

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.lechugones.cocina.R
import com.lechugones.cocina.domain.format.SpanishNumberFormat
import com.lechugones.cocina.domain.quantity.DisplayQuantity
import com.lechugones.cocina.domain.quantity.QuantityDisplay
import com.lechugones.cocina.domain.recipepack.IssueCode
import com.lechugones.cocina.domain.recipepack.PackIssue
import com.lechugones.cocina.domain.units.MeasureUnit
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** es-ES dates, independent of the device locale (CLAUDE.md). */
object Dates {
    private val SPANISH: Locale = Locale.forLanguageTag("es-ES")
    private val DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy", SPANISH)
    private val DATE_TIME = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm", SPANISH)

    fun date(instant: Instant): String = DATE.format(instant.atZone(ZoneId.systemDefault()))

    fun dateTime(instant: Instant): String = DATE_TIME.format(instant.atZone(ZoneId.systemDefault()))

    /** A calendar date (best-before), with no time zone involved. */
    fun date(date: LocalDate): String = DATE.format(date)
}

/** "300 g", "1 1/2 cucharadas", "4 ud", "1 pizca", "al gusto". */
@Composable
fun quantityText(
    value: BigDecimal?,
    unit: MeasureUnit,
): String =
    when (val display = QuantityDisplay.friendly(value, unit)) {
        is DisplayQuantity.NoQuantity -> {
            stringResource(R.string.unit_al_gusto)
        }

        is DisplayQuantity.Decimal -> {
            val plural = display.value > BigDecimal.ONE
            "${SpanishNumberFormat.decimal(display.value)} ${unitLabel(display.unit, plural)}"
        }

        is DisplayQuantity.Fraction -> {
            val fraction = display.value
            val plural = fraction.whole > 1 || (fraction.whole == 1 && fraction.numerator > 0)
            "${SpanishNumberFormat.mixedFraction(fraction)} ${unitLabel(display.unit, plural)}"
        }
    }

@Composable
fun unitLabel(
    unit: MeasureUnit,
    plural: Boolean,
): String =
    stringResource(
        when (unit) {
            MeasureUnit.G -> R.string.unit_g
            MeasureUnit.KG -> R.string.unit_kg
            MeasureUnit.ML -> R.string.unit_ml
            MeasureUnit.CL -> R.string.unit_cl
            MeasureUnit.L -> R.string.unit_l
            MeasureUnit.UD -> R.string.unit_ud
            MeasureUnit.CDTA -> if (plural) R.string.unit_cdta_other else R.string.unit_cdta_one
            MeasureUnit.CDA -> if (plural) R.string.unit_cda_other else R.string.unit_cda_one
            MeasureUnit.TAZA -> if (plural) R.string.unit_taza_other else R.string.unit_taza_one
            MeasureUnit.DIENTE -> if (plural) R.string.unit_diente_other else R.string.unit_diente_one
            MeasureUnit.LONCHA -> if (plural) R.string.unit_loncha_other else R.string.unit_loncha_one
            MeasureUnit.REBANADA -> if (plural) R.string.unit_rebanada_other else R.string.unit_rebanada_one
            MeasureUnit.HOJA -> if (plural) R.string.unit_hoja_other else R.string.unit_hoja_one
            MeasureUnit.RAMITA -> if (plural) R.string.unit_ramita_other else R.string.unit_ramita_one
            MeasureUnit.PIZCA -> if (plural) R.string.unit_pizca_other else R.string.unit_pizca_one
            MeasureUnit.AL_GUSTO -> R.string.unit_al_gusto
        },
    )

/** Unit picker entry: "cda — cucharada (15 ml)". */
@Composable
fun unitPickerLabel(unit: MeasureUnit): String {
    @StringRes val name =
        when (unit) {
            MeasureUnit.G -> R.string.unit_name_g
            MeasureUnit.KG -> R.string.unit_name_kg
            MeasureUnit.ML -> R.string.unit_name_ml
            MeasureUnit.CL -> R.string.unit_name_cl
            MeasureUnit.L -> R.string.unit_name_l
            MeasureUnit.CDTA -> R.string.unit_name_cdta
            MeasureUnit.CDA -> R.string.unit_name_cda
            MeasureUnit.TAZA -> R.string.unit_name_taza
            MeasureUnit.UD -> R.string.unit_name_ud
            MeasureUnit.DIENTE -> R.string.unit_name_diente
            MeasureUnit.LONCHA -> R.string.unit_name_loncha
            MeasureUnit.REBANADA -> R.string.unit_name_rebanada
            MeasureUnit.HOJA -> R.string.unit_name_hoja
            MeasureUnit.RAMITA -> R.string.unit_name_ramita
            MeasureUnit.PIZCA -> R.string.unit_name_pizca
            MeasureUnit.AL_GUSTO -> R.string.unit_name_al_gusto
        }
    return if (unit == MeasureUnit.AL_GUSTO) stringResource(name) else "${unit.code} — ${stringResource(name)}"
}

/** A recipe-pack issue as a Spanish sentence, prefixed with where it happened. */
@Composable
fun issueText(issue: PackIssue): String {
    val message = issueMessage(issue)
    val location =
        when {
            issue.line != null -> stringResource(R.string.issue_location_line, issue.line ?: 0)
            issue.ingredientIndex != null -> stringResource(R.string.issue_location_ingredient, (issue.ingredientIndex ?: 0) + 1)
            else -> null
        }
    return if (location == null) message else "$location: $message"
}

/** The issue sentence alone, for messages shown next to the field itself. */
@Composable
fun issueMessage(issue: PackIssue): String {
    val field = fieldName(issue.field)
    val argument = issue.value ?: field
    val message =
        when (issue.code) {
            IssueCode.P01 -> stringResource(R.string.issue_P01)
            IssueCode.P02 -> stringResource(R.string.issue_P02)
            IssueCode.P03 -> stringResource(R.string.issue_P03)
            IssueCode.P04 -> stringResource(R.string.issue_P04)
            IssueCode.P05 -> stringResource(R.string.issue_P05)
            IssueCode.P06 -> stringResource(R.string.issue_P06, issue.path)
            IssueCode.E01 -> stringResource(R.string.issue_E01)
            IssueCode.E02 -> stringResource(R.string.issue_E02)
            IssueCode.E03 -> stringResource(R.string.issue_E03)
            IssueCode.E04 -> stringResource(R.string.issue_E04)
            IssueCode.E05 -> stringResource(R.string.issue_E05, field)
            IssueCode.E06 -> stringResource(R.string.issue_E06, field)
            IssueCode.E07 -> stringResource(R.string.issue_E07, field)
            IssueCode.E08 -> stringResource(R.string.issue_E08, argument)
            IssueCode.E09 -> stringResource(R.string.issue_E09)
            IssueCode.E10 -> stringResource(R.string.issue_E10, argument)
            IssueCode.E11 -> stringResource(R.string.issue_E11, argument)
            IssueCode.E12 -> stringResource(R.string.issue_E12)
            IssueCode.W01 -> stringResource(R.string.issue_W01, field)
            IssueCode.W02 -> stringResource(R.string.issue_W02, field)
            IssueCode.W03 -> stringResource(R.string.issue_W03)
            IssueCode.W04 -> stringResource(R.string.issue_W04)
            IssueCode.W05 -> stringResource(R.string.issue_W05)
            IssueCode.W06 -> stringResource(R.string.issue_W06)
            IssueCode.W07 -> stringResource(R.string.issue_W07)
            IssueCode.W08 -> stringResource(R.string.issue_W08)
            IssueCode.W09 -> stringResource(R.string.issue_W09)
            IssueCode.W10 -> stringResource(R.string.issue_W10)
            IssueCode.W11 -> stringResource(R.string.issue_W11)
            IssueCode.W12 -> stringResource(R.string.issue_W12)
        }
    return message
}

@Composable
private fun fieldName(field: String?): String {
    @StringRes val id =
        when (field) {
            "title" -> R.string.field_title
            "servings" -> R.string.field_servings
            "ingredients" -> R.string.field_ingredients
            "name" -> R.string.field_name
            "quantity" -> R.string.field_quantity
            "unit" -> R.string.field_unit
            "tags" -> R.string.field_tags
            "description" -> R.string.field_description
            "prep_minutes" -> R.string.field_prep_minutes
            "cook_minutes" -> R.string.field_cook_minutes
            "image" -> R.string.field_image
            "source" -> R.string.field_source
            "note" -> R.string.field_note
            "group" -> R.string.field_group
            "query" -> R.string.field_query
            "package_size" -> R.string.field_package_size
            "unit_weight_g" -> R.string.field_unit_weight_g
            "body" -> R.string.field_body
            else -> 0
        }
    return if (id == 0) field.orEmpty() else stringResource(id)
}
