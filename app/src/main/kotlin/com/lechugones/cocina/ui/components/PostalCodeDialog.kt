package com.lechugones.cocina.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.lechugones.cocina.R
import com.lechugones.cocina.domain.settings.AppSettings

/**
 * Asks for the postal code the supermarkets use to pick a store or warehouse. Prefilled with
 * the current one, or a central Barcelona code as a suggestion the user can change.
 */
@Composable
fun PostalCodeDialog(
    current: String?,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var code by rememberSaveable { mutableStateOf(current ?: AppSettings.SUGGESTED_POSTAL_CODE) }
    val valid = AppSettings.isValidPostalCode(code)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.postal_code_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.postal_code_body), style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    value = code,
                    onValueChange = { value -> code = value.filter(Char::isDigit).take(5) },
                    label = { Text(stringResource(R.string.postal_code_title)) },
                    singleLine = true,
                    isError = code.length == 5 && !valid,
                    supportingText = { if (code.length == 5 && !valid) Text(stringResource(R.string.postal_code_invalid)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSave(code) }, enabled = valid) { Text(stringResource(R.string.action_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
