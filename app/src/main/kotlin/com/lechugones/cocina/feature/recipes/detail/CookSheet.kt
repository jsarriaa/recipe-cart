package com.lechugones.cocina.feature.recipes.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.lechugones.cocina.R
import com.lechugones.cocina.domain.quantity.Amount
import com.lechugones.cocina.domain.units.MeasureUnit
import com.lechugones.cocina.feature.pantry.LotForm
import com.lechugones.cocina.ui.format.quantityText
import java.math.BigDecimal

/** One editable take from one lot in the "Lo he cocinado" sheet. */
data class CookTake(
    val lotId: String,
    val lotName: String,
    val unit: MeasureUnit,
    /** What the lot has left before cooking. */
    val available: BigDecimal,
    /** Typed amount, decimal comma; blank means nothing is taken. */
    val amount: String,
    val skip: Boolean = false,
) {
    val parsed: BigDecimal?
        get() =
            amount
                .trim()
                .replace(',', '.')
                .toBigDecimalOrNull()
                ?.takeIf { it.signum() >= 0 }
    val valid: Boolean
        get() {
            val value = parsed
            return skip || amount.isBlank() || (value != null && value <= available)
        }

    /** The confirmed take, or null when skipped or empty. */
    fun toAmount(): Amount? = if (skip) null else parsed?.takeIf { it.signum() > 0 }?.let { Amount.of(it) }
}

/** One ingredient in the sheet. [presence] are staples offered "Se ha acabado". */
data class CookRow(
    val ingredientName: String,
    val needed: BigDecimal?,
    val neededUnit: MeasureUnit,
    val takes: List<CookTake>,
    val missing: BigDecimal?,
    val presence: List<Pair<String, String>> = emptyList(),
)

data class CookSheetState(
    val servings: Int,
    val rows: List<CookRow>,
    /** Presence-only lots marked "Se ha acabado". */
    val finished: Set<String> = emptySet(),
    val saving: Boolean = false,
) {
    val valid: Boolean get() = rows.all { row -> row.takes.all { it.valid } }
}

/**
 * "Lo he cocinado" confirmation (SPEC § 4.5, steps 2–4; AC5.2): every deduction can be edited
 * or skipped before anything changes.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CookSheet(
    state: CookSheetState,
    onAmount: (row: Int, take: Int, String) -> Unit,
    onSkip: (row: Int, take: Int, Boolean) -> Unit,
    onFinished: (lotId: String, Boolean) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
            Text(
                pluralStringResource(R.plurals.cook_title, state.servings, state.servings),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            Text(
                stringResource(R.string.cook_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 520.dp)) {
                state.rows.forEachIndexed { r, row ->
                    item(key = "row$r") {
                        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            val needed = row.needed?.let { quantityText(it, row.neededUnit) }
                            Text(listOfNotNull(needed, row.ingredientName).joinToString(" "), style = MaterialTheme.typography.bodyLarge)
                            if (row.takes.isEmpty() && row.presence.isEmpty() && row.needed != null) {
                                Text(
                                    stringResource(R.string.cook_not_in_pantry),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            row.takes.forEachIndexed { t, take ->
                                TakeRow(take, onAmount = { onAmount(r, t, it) }, onSkip = { onSkip(r, t, it) })
                            }
                            row.missing?.let {
                                if (row.takes.isNotEmpty()) {
                                    Text(
                                        stringResource(R.string.cook_missing, quantityText(it, row.neededUnit)),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                }
                            }
                            row.presence.forEach { (lotId, name) ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(checked = lotId in state.finished, onCheckedChange = { onFinished(lotId, it) })
                                    Text(stringResource(R.string.cook_finished, name), style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                        }
                        HorizontalDivider()
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onConfirm, enabled = state.valid && !state.saving) { Text(stringResource(R.string.cook_confirm)) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
            }
        }
    }
}

@Composable
private fun TakeRow(
    take: CookTake,
    onAmount: (String) -> Unit,
    onSkip: (Boolean) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Checkbox(checked = !take.skip, onCheckedChange = { onSkip(!it) })
        Column(Modifier.weight(1f)) {
            Text(take.lotName, style = MaterialTheme.typography.bodyMedium)
            Text(
                stringResource(R.string.cook_available, quantityText(take.available, take.unit)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        OutlinedTextField(
            value = take.amount,
            onValueChange = onAmount,
            enabled = !take.skip,
            isError = !take.valid,
            singleLine = true,
            suffix = { Text(take.unit.code) },
            modifier = Modifier.width(120.dp),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        )
    }
}

/** Typed form of a planned amount. */
fun editableAmount(amount: Amount): String = LotForm.editable(amount.toBigDecimal())
