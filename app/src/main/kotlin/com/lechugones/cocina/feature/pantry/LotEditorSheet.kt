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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.lechugones.cocina.R
import com.lechugones.cocina.domain.pantry.PantryLocation
import com.lechugones.cocina.ui.format.unitLabel

/** Add or edit one pantry lot (SPEC § 4.5): name, amount left, opened, where it is. No dates. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LotEditorSheet(
    form: LotForm,
    onChange: (LotForm) -> Unit,
    onSave: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
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
                    Text(stringResource(R.string.pantry_field_opened))
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
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onSave, enabled = form.valid) { Text(stringResource(R.string.action_save)) }
                if (!form.isNew) OutlinedButton(onClick = onDelete) { Text(stringResource(R.string.action_delete)) }
            }
        }
    }
}
