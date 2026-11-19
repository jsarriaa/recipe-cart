package com.lechugones.cocina.feature.pantry

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.lechugones.cocina.R
import com.lechugones.cocina.domain.pantry.PantryLocation
import com.lechugones.cocina.ui.format.Dates
import com.lechugones.cocina.ui.format.unitLabel
import java.time.LocalDate

private const val MILLIS_PER_DAY = 86_400_000L

/** Add or edit one pantry lot (SPEC § 4.5). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LotEditorSheet(
    form: LotForm,
    onChange: (LotForm) -> Unit,
    onSave: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    var pickingDate by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                stringResource(if (form.isNew) R.string.pantry_editor_new else R.string.pantry_editor_edit),
                style = MaterialTheme.typography.titleMedium,
            )
            OutlinedTextField(
                value = form.name,
                onValueChange = { onChange(form.copy(name = it)) },
                label = { Text(stringResource(R.string.pantry_field_name)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.pantry_field_presence), style = MaterialTheme.typography.bodyLarge)
                    Text(
                        stringResource(R.string.pantry_field_presence_help),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = form.presenceOnly, onCheckedChange = { onChange(form.copy(presenceOnly = it)) })
            }
            if (!form.presenceOnly) {
                OutlinedTextField(
                    value = form.quantity,
                    onValueChange = { onChange(form.copy(quantity = it)) },
                    label = { Text(stringResource(R.string.pantry_field_quantity)) },
                    isError = !form.quantityValid,
                    supportingText = { if (!form.quantityValid) Text(stringResource(R.string.pantry_field_invalid_number)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LotForm.UNITS.forEach { unit ->
                        FilterChip(
                            selected = form.unit == unit,
                            onClick = { onChange(form.copy(unit = unit)) },
                            label = { Text(unitLabel(unit, plural = true)) },
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = form.opened,
                        onCheckedChange = { onChange(form.copy(opened = it, openedAt = if (it) form.openedAt else null)) },
                    )
                    Text(
                        form.openedAt?.takeIf { form.opened }?.let { stringResource(R.string.pantry_opened_on, Dates.date(it)) }
                            ?: stringResource(R.string.pantry_field_opened),
                    )
                }
                OutlinedTextField(
                    value = form.shelfLifeDays,
                    onValueChange = { onChange(form.copy(shelfLifeDays = it.filter(Char::isDigit).take(3))) },
                    label = { Text(stringResource(R.string.pantry_field_shelf_life)) },
                    supportingText = { Text(stringResource(R.string.pantry_field_shelf_life_help)) },
                    isError = !form.shelfLifeValid,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            }
            Text(stringResource(R.string.pantry_field_best_before), style = MaterialTheme.typography.labelLarge)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    form.bestBefore?.let(Dates::date) ?: stringResource(R.string.pantry_field_best_before_none),
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { pickingDate = true }) { Text(stringResource(R.string.pantry_choose_date)) }
                if (form.bestBefore != null) {
                    TextButton(onClick = { onChange(form.copy(bestBefore = null)) }) {
                        Text(stringResource(R.string.pantry_field_best_before_clear))
                    }
                }
            }
            Text(stringResource(R.string.pantry_field_location), style = MaterialTheme.typography.labelLarge)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PantryLocation.entries.forEach { location ->
                    FilterChip(
                        selected = form.location == location,
                        onClick = { onChange(form.copy(location = if (form.location == location) null else location)) },
                        label = { Text(locationLabel(location)) },
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.pantry_field_use_soon),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f),
                )
                Switch(checked = form.useSoonManual, onCheckedChange = { onChange(form.copy(useSoonManual = it)) })
            }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onSave, enabled = form.valid) { Text(stringResource(R.string.action_save)) }
                if (!form.isNew) OutlinedButton(onClick = onDelete) { Text(stringResource(R.string.action_delete)) }
            }
        }
    }

    if (pickingDate) {
        val initial = (form.bestBefore ?: LocalDate.now()).toEpochDay() * MILLIS_PER_DAY
        val picker = rememberDatePickerState(initialSelectedDateMillis = initial)
        DatePickerDialog(
            onDismissRequest = { pickingDate = false },
            confirmButton = {
                TextButton(onClick = {
                    picker.selectedDateMillis?.let { onChange(form.copy(bestBefore = LocalDate.ofEpochDay(it / MILLIS_PER_DAY))) }
                    pickingDate = false
                }) { Text(stringResource(R.string.action_ok)) }
            },
            dismissButton = { TextButton(onClick = { pickingDate = false }) { Text(stringResource(R.string.action_cancel)) } },
        ) { DatePicker(state = picker) }
    }
}
