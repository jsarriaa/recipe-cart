package com.lechugones.cocina.feature.export

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.lechugones.cocina.R
import kotlinx.coroutines.launch

/** Lets the user share an export or save it with the system file picker. */
@Composable
fun ExportDialog(
    export: ExportFile,
    snackbarHostState: SnackbarHostState,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val saved = stringResource(R.string.export_saved)
    val failed = stringResource(R.string.export_error)
    val onCreated: (android.net.Uri?) -> Unit = { uri ->
        if (uri != null) {
            scope.launch {
                snackbarHostState.showSnackbar(if (ExportActions.save(context, export, uri)) saved else failed)
            }
        }
        onDismiss()
    }
    val saveMarkdown = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(RecipeExporter.MIME_MARKDOWN), onCreated)
    val saveZip = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(RecipeExporter.MIME_ZIP), onCreated)
    val savePdf = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(RecipeExporter.MIME_PDF), onCreated)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(export.name) },
        text = { Text(stringResource(R.string.export_dialog_body)) },
        confirmButton = {
            TextButton(onClick = {
                context.startActivity(ExportActions.shareIntent(context, export))
                onDismiss()
            }) { Text(stringResource(R.string.export_share)) }
        },
        dismissButton = {
            TextButton(onClick = {
                when (export.mimeType) {
                    RecipeExporter.MIME_ZIP -> saveZip.launch(export.name)
                    RecipeExporter.MIME_PDF -> savePdf.launch(export.name)
                    else -> saveMarkdown.launch(export.name)
                }
            }) { Text(stringResource(R.string.export_save)) }
        },
    )
}
