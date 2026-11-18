package com.lechugones.cocina.domain.recipepack

import com.lechugones.cocina.domain.recipe.NameKey
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** A recipe to export, with its photo if it has one. */
class ExportItem(
    val draft: RecipeDraft,
    val image: ExportImage? = null,
)

class ExportImage(
    val bytes: ByteArray,
    /** File extension without the dot: `jpg`, `png`, `webp`. */
    val extension: String,
)

/** Writes `.md` documents and `.zip` packs in the canonical layout (RECIPE_FORMAT § 11). */
object RecipePackWriter {
    /** A single document; the draft's image reference is dropped because a `.md` cannot carry it. */
    fun writeDocument(draft: RecipeDraft): String = RecipeDocumentWriter.write(draft.copy(image = null))

    /** A zip with every recipe at the root as `<slug>.md` and photos in `images/<slug>.<ext>`. */
    fun writeZip(
        items: List<ExportItem>,
        out: OutputStream,
    ) {
        val used = mutableSetOf<String>()
        ZipOutputStream(out).use { zip ->
            items.forEach { item ->
                val slug = uniqueSlug(item.draft.title, used)
                val imagePath = item.image?.let { "images/$slug.${it.extension.lowercase()}" }
                zip.putNextEntry(ZipEntry("$slug.md"))
                zip.write(RecipeDocumentWriter.write(item.draft.copy(image = imagePath)).toByteArray(Charsets.UTF_8))
                zip.closeEntry()
                if (item.image != null && imagePath != null) {
                    zip.putNextEntry(ZipEntry(imagePath))
                    zip.write(item.image.bytes)
                    zip.closeEntry()
                }
            }
        }
    }

    /** `Tacos de Pollo!` → `tacos-de-pollo`; empty titles become `receta`. */
    fun slug(title: String): String =
        NameKey
            .of(title)
            .replace(' ', '-')
            .take(SLUG_MAX)
            .trimEnd('-')
            .ifEmpty { "receta" }

    private fun uniqueSlug(
        title: String,
        used: MutableSet<String>,
    ): String {
        val base = slug(title)
        var candidate = base
        var n = 2
        while (!used.add(candidate)) candidate = "$base-${n++}"
        return candidate
    }

    private const val SLUG_MAX = 60
}
