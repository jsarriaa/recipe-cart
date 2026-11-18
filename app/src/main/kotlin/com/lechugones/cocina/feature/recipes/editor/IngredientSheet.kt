package com.lechugones.cocina.feature.recipes.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.lechugones.cocina.R
import com.lechugones.cocina.domain.recipepack.PackIssue
import com.lechugones.cocina.domain.units.MeasureUnit
import com.lechugones.cocina.ui.format.unitPickerLabel

/** Bottom sheet to add or edit one ingredient (SPEC § 4.2). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IngredientSheet(
    sheet: IngredientSheetState,
    onChange: ((IngredientForm) -> IngredientForm) -> Unit,
    onApply: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    val form = sheet.form

    fun error(field: String): PackIssue? = sheet.errors.firstOrNull { it.path.endsWith(".$field") }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .imePadding()
                    .verticalScroll(
                        rememberScrollState(),
                    ).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                stringResource(if (sheet.index == null) R.string.ingredient_new else R.string.ingredient_edit),
                style = MaterialTheme.typography.titleLarge,
            )
            Field(R.string.ingredient_name, form.name, error("name"), help = stringResource(R.string.ingredient_name_help)) { v ->
                onChange { it.copy(name = v) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Field(
                    R.string.ingredient_quantity,
                    form.quantity,
                    error("quantity"),
                    help = stringResource(R.string.ingredient_quantity_help),
                    modifier = Modifier.weight(1f),
                ) { v -> onChange { it.copy(quantity = v) } }
                UnitPicker(form.unit, modifier = Modifier.weight(1f)) { unit -> onChange { it.copy(unit = unit) } }
            }
            Field(
                R.string.ingredient_unit_weight,
                form.unitWeight,
                error("unit_weight_g"),
                help = stringResource(R.string.ingredient_unit_weight_help),
            ) { v -> onChange { it.copy(unitWeight = v) } }
            Field(R.string.ingredient_note, form.note, error("note"), help = stringResource(R.string.ingredient_note_help)) { v ->
                onChange { it.copy(note = v) }
            }
            Field(R.string.ingredient_group, form.group, error("group"), help = stringResource(R.string.ingredient_group_help)) { v ->
                onChange { it.copy(group = v) }
            }
            SwitchRow(R.string.ingredient_optional, form.optional) { v -> onChange { it.copy(optional = v) } }
            SwitchRow(R.string.ingredient_pantry, form.pantry) { v -> onChange { it.copy(pantry = v) } }

            Text(
                stringResource(R.string.ingredient_match_title),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(top = 8.dp),
            )
            Field(R.string.ingredient_match_query, form.matchQuery, error("query")) { v -> onChange { it.copy(matchQuery = v) } }
            Field(
                R.string.ingredient_match_package,
                form.matchPackage,
                error("package_size"),
                help = stringResource(R.string.ingredient_match_package_help),
            ) { v -> onChange { it.copy(matchPackage = v) } }
            SwitchRow(R.string.ingredient_match_store_brand, form.storeBrandOk) { v -> onChange { it.copy(storeBrandOk = v) } }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 8.dp)) {
                if (sheet.index != null) {
                    TextButton(onClick = onDelete) { Text(stringResource(R.string.action_delete)) }
                }
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
                    Button(onClick = onApply) { Text(stringResource(R.string.ingredient_apply)) }
                }
            }
        }
    }
}

@Composable
private fun SwitchRow(
    label: Int,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(label), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UnitPicker(
    unit: MeasureUnit,
    modifier: Modifier = Modifier,
    onSelect: (MeasureUnit) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }, modifier = modifier) {
        OutlinedTextField(
            value = unitPickerLabel(unit),
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            label = { Text(stringResource(R.string.ingredient_unit)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            MeasureUnit.entries.forEach { option ->
                DropdownMenuItem(
                    text = { Text(unitPickerLabel(option)) },
                    onClick = {
                        onSelect(option)
                        expanded = false
                    },
                )
            }
        }
    }
}
