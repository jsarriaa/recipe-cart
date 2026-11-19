package com.lechugones.cocina.feature.community

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.lechugones.cocina.R
import com.lechugones.cocina.domain.recipepack.RecipeRules

/** Shows whichever publishing step [state] is in. */
@Composable
fun PublishDialogs(
    state: PublishUi,
    onSaveName: (String) -> Unit,
    onChangeName: () -> Unit,
    onPublish: () -> Unit,
    onDismiss: () -> Unit,
) {
    when (state) {
        PublishUi.Hidden -> {
            Unit
        }

        is PublishUi.AskName -> {
            NameDialog(state.initial, state.invalid, onSave = onSaveName, onDismiss = onDismiss)
        }

        is PublishUi.Confirm -> {
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text(stringResource(R.string.publish_confirm_title)) },
                text = { Text(stringResource(R.string.publish_confirm_body, state.title, state.name)) },
                confirmButton = { TextButton(onClick = onPublish) { Text(stringResource(R.string.publish_action)) } },
                dismissButton = {
                    Column(horizontalAlignment = androidx.compose.ui.Alignment.End) {
                        TextButton(onClick = onChangeName) { Text(stringResource(R.string.publish_change_name)) }
                        TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
                    }
                },
            )
        }

        PublishUi.Publishing -> {
            AlertDialog(
                onDismissRequest = {},
                title = { Text(stringResource(R.string.publish_publishing)) },
                text = { LinearProgressIndicator(Modifier.fillMaxWidth()) },
                confirmButton = {},
            )
        }

        is PublishUi.Done -> {
            val message =
                when (state.result) {
                    PublishResult.Published -> R.string.publish_done
                    PublishResult.RateLimited -> R.string.publish_rate_limited
                    PublishResult.Rejected -> R.string.publish_rejected
                    PublishResult.Failed -> R.string.publish_failed
                    PublishResult.NotAvailable -> R.string.publish_not_available
                }
            AlertDialog(
                onDismissRequest = onDismiss,
                text = { Text(stringResource(message)) },
                confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) } },
            )
        }

        is PublishUi.OtherAuthor -> {
            AlertDialog(
                onDismissRequest = onDismiss,
                text = { Text(stringResource(R.string.publish_other_author, state.author)) },
                confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) } },
            )
        }
    }
}

/** Asks for the name shown as "por …"; also used from Ajustes. */
@Composable
fun NameDialog(
    initial: String,
    invalid: Boolean,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember(initial) { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.publish_name_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.publish_name_body))
                OutlinedTextField(
                    value = name,
                    onValueChange = { if (it.length <= RecipeRules.AUTHOR_MAX) name = it },
                    singleLine = true,
                    isError = invalid,
                    placeholder = { Text(stringResource(R.string.publish_name_hint)) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSave(name) }) { Text(stringResource(R.string.action_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
