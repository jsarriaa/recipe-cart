package com.lechugones.cocina.feature.export

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.lechugones.cocina.data.recipe.RecipeLibrary
import com.lechugones.cocina.domain.recipepack.RecipePackWriter
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import javax.inject.Inject

/** An exported recipe file in the app cache, ready to share or save. */
data class ExportFile(
    val file: File,
    val mimeType: String,
) {
    val name: String get() = file.name
}

/**
 * Writes recipe packs for "Exportar" (SPEC § 4.3): one recipe without a photo becomes a `.md`,
 * anything else a `.zip`.
 */
class RecipeExporter
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val library: RecipeLibrary,
    ) {
        /** Exports [ids] (all recipes when null). [allFileName] names the zip of "Exportar todas". */
        suspend fun export(
            ids: List<String>?,
            allFileName: String,
        ): ExportFile? {
            val items = library.exportItems(ids)
            if (items.isEmpty()) return null
            return withContext(Dispatchers.IO) {
                val directory = File(context.cacheDir, EXPORT_DIRECTORY).apply { deleteRecursively() }
                directory.mkdirs()
                val single = ids != null && items.size == 1
                if (single && items[0].image == null) {
                    val draft = items[0].draft
                    val file = File(directory, RecipePackWriter.slug(draft.title) + ".md")
                    file.writeText(RecipePackWriter.writeDocument(draft), Charsets.UTF_8)
                    ExportFile(file, MIME_MARKDOWN)
                } else {
                    val name = if (single) RecipePackWriter.slug(items[0].draft.title) + ".zip" else allFileName
                    val file = File(directory, name)
                    file.outputStream().use { RecipePackWriter.writeZip(items, it) }
                    ExportFile(file, MIME_ZIP)
                }
            }
        }

        companion object {
            const val EXPORT_DIRECTORY = "exports"
            const val MIME_MARKDOWN = "text/markdown"
            const val MIME_ZIP = "application/zip"
            const val MIME_PDF = "application/pdf"
        }
    }

/** Share-sheet and "save to the phone" actions for an [ExportFile]. */
object ExportActions {
    fun shareIntent(
        context: Context,
        export: ExportFile,
    ): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", export.file)
        val send =
            Intent(Intent.ACTION_SEND).apply {
                type = export.mimeType
                putExtra(Intent.EXTRA_STREAM, uri)
                clipData = ClipData.newRawUri(export.name, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        return Intent.createChooser(send, null)
    }

    /** Copies the export into a document the user created with the system file picker. */
    suspend fun save(
        context: Context,
        export: ExportFile,
        target: Uri,
    ): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val out = context.contentResolver.openOutputStream(target) ?: return@withContext false
                out.use { stream -> export.file.inputStream().use { it.copyTo(stream) } }
                true
            } catch (e: IOException) {
                false
            } catch (e: SecurityException) {
                false
            }
        }
}
