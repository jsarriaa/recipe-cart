package com.lechugones.cocina.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.lechugones.cocina.R
import com.lechugones.cocina.domain.product.ManualProductField
import com.lechugones.cocina.domain.product.ManualProductForm

/**
 * Form for a product of a shop without an API (built-in manual provider, SPEC § 4.1).
 * [errors] are the fields the last save rejected.
 */
@Composable
fun ManualProductDialog(
    initialName: String,
    errors: Set<ManualProductField>,
    onSave: (ManualProductForm) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by rememberSaveable { mutableStateOf(initialName) }
    var brand by rememberSaveable { mutableStateOf("") }
    var storeBrand by rememberSaveable { mutableStateOf(false) }
    var size by rememberSaveable { mutableStateOf("") }
    var price by rememberSaveable { mutableStateOf("") }
    var shop by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.manual_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.manual_name)) },
                    isError = ManualProductField.NAME in errors,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                )
                OutlinedTextField(
                    value = size,
                    onValueChange = { size = it },
                    label = { Text(stringResource(R.string.manual_size)) },
                    placeholder = { Text(stringResource(R.string.manual_size_hint)) },
                    isError = ManualProductField.SIZE in errors,
                    supportingText = { if (ManualProductField.SIZE in errors) Text(stringResource(R.string.manual_size_error)) },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = price,
                    onValueChange = { price = it },
                    label = { Text(stringResource(R.string.manual_price)) },
                    isError = ManualProductField.PRICE in errors,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
                OutlinedTextField(
                    value = brand,
                    onValueChange = { brand = it },
                    label = { Text(stringResource(R.string.manual_brand)) },
                    singleLine = true,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = storeBrand, onCheckedChange = { storeBrand = it })
                    Text(stringResource(R.string.catalogue_store_brand))
                }
                OutlinedTextField(
                    value = shop,
                    onValueChange = { shop = it },
                    label = { Text(stringResource(R.string.manual_shop)) },
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(ManualProductForm(name, brand, storeBrand, size, price, shop)) }) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
