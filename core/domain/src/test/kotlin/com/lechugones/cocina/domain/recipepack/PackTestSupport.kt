package com.lechugones.cocina.domain.recipepack

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.assertEquals

/** Repository root: Gradle runs module tests with the module directory as working directory. */
val repoRoot: File = File("../..").canonicalFile

const val PREPARATION = "## Preparación\n\n1. Paso."

/** A document with [yaml] as front matter; a valid `format` line is added unless [withFormat] is false. */
fun doc(
    yaml: String,
    body: String = PREPARATION,
    withFormat: Boolean = true,
): String = "---\n" + (if (withFormat) "format: cpl-recipe/1\n" else "") + yaml.trimIndent() + "\n---\n\n" + body + "\n"

/** A minimal valid recipe front matter, plus [extra] lines. */
fun minimal(extra: String = ""): String =
    """
    title: Prueba
    servings: 2
    ingredients:
      - name: arroz
        quantity: 100
        unit: g
        match:
          query: arroz
    """.trimIndent() + (if (extra.isEmpty()) "" else "\n" + extra.trimIndent())

fun parse(
    text: String,
    images: Set<String> = emptySet(),
) = RecipeDocumentParser.parse(text, images)

fun ParsedDocument.codes(): List<IssueCode> = issues.map { it.code }

fun ParsedDocument.assertIssue(
    code: IssueCode,
    path: String? = null,
): PackIssue {
    val issue = issues.firstOrNull { it.code == code && (path == null || it.path == path) }
    assertEquals(true, issue != null, "expected $code${path?.let { " at $it" } ?: ""}, got $issues")
    return issue!!
}

fun zipOf(vararg entries: Pair<String, ByteArray>): ByteArray {
    val out = ByteArrayOutputStream()
    ZipOutputStream(out).use { zip ->
        entries.forEach { (name, bytes) ->
            zip.putNextEntry(ZipEntry(name))
            zip.write(bytes)
            zip.closeEntry()
        }
    }
    return out.toByteArray()
}

fun String.bytes(): ByteArray = toByteArray(Charsets.UTF_8)
