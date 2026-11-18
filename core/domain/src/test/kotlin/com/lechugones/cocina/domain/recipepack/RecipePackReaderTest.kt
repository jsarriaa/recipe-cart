package com.lechugones.cocina.domain.recipepack

import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipInputStream
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RecipePackReaderTest {
    private val tacos = File(repoRoot, "examples/recipe-packs/tacos-de-pollo.md").readBytes()
    private val withImage = doc(minimal("image: images/foto.jpg")).bytes()
    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 1, 2, 3)

    private fun read(
        bytes: ByteArray,
        name: String = "pack.zip",
        maxBytes: Long = RecipePackReader.ZIP_MAX_BYTES,
    ) = RecipePackReader.read(ByteArrayInputStream(bytes), name, maxBytes)

    @Test
    fun `a single markdown file is one document`() {
        val result = read(tacos, "tacos-de-pollo.md")
        assertEquals(emptyList(), result.issues)
        assertEquals("tacos-de-pollo.md", result.documents.single().name)
        assertEquals(
            emptyList(),
            result.documents
                .single()
                .parsed.issues,
        )
    }

    @Test
    fun `shared text is one document`() {
        val result = RecipePackReader.readText(String(tacos, Charsets.UTF_8), "texto")
        assertNotNull(
            result.documents
                .single()
                .parsed.draft,
        )
    }

    @Test
    fun `a zip with several recipes and an image`() {
        val result = read(zipOf("tacos.md" to tacos, "otra.md" to withImage, "images/foto.jpg" to jpeg))
        assertEquals(emptyList(), result.issues)
        assertEquals(listOf("tacos.md", "otra.md"), result.documents.map { it.name })
        assertEquals(
            "images/foto.jpg",
            result.documents[1]
                .parsed.draft
                ?.image,
            "image resolves inside the pack",
        )
        assertTrue(result.images.getValue("images/foto.jpg").contentEquals(jpeg))
    }

    @Test
    fun `unsafe entry paths reject the whole pack`() {
        listOf("../fuera.md", "images/../../x.jpg", "/abs.md", "C:/win.md", "dir\\x.md").forEach { path ->
            val result = read(zipOf("tacos.md" to tacos, path to tacos))
            assertTrue(result.isRejected, path)
            assertEquals(IssueCode.P03, result.issues.single().code, path)
            assertTrue(result.documents.isEmpty())
        }
    }

    @Test
    fun `an app backup is recognised`() {
        val result = read(zipOf("backup-manifest.json" to "{}".bytes(), "database/cocina.db" to byteArrayOf(1)))
        assertTrue(result.isBackup)
    }

    @Test
    fun `a zip without recipes is P04`() {
        assertEquals(IssueCode.P04, read(zipOf("images/a.jpg" to jpeg)).issues.single().code)
    }

    @Test
    fun `other files are ignored with P06, system junk silently`() {
        val result =
            read(
                zipOf(
                    "tacos.md" to tacos,
                    "notas.txt" to "x".bytes(),
                    "sub/otra.md" to tacos,
                    "images/raro.gif" to jpeg,
                    "__MACOSX/._tacos.md" to byteArrayOf(0),
                    ".DS_Store" to byteArrayOf(0),
                ),
            )
        assertFalse(result.isRejected)
        assertEquals(listOf("notas.txt", "sub/otra.md", "images/raro.gif"), result.issues.map { it.path })
        assertTrue(result.issues.all { it.code == IssueCode.P06 })
        assertEquals(1, result.documents.size)
    }

    @Test
    fun `oversized images are skipped, oversized documents are E12`() {
        val bigImage = ByteArray(RecipePackReader.IMAGE_MAX_BYTES + 10)
        val bigDoc = ByteArray(RecipeRules.DOCUMENT_MAX_BYTES + 10) { 'a'.code.toByte() }
        val result = read(zipOf("tacos.md" to tacos, "grande.md" to bigDoc, "images/enorme.jpg" to bigImage))
        assertEquals(IssueCode.P06, result.issues.single().code)
        assertEquals(listOf(IssueCode.E12), result.documents[1].parsed.codes())
        assertTrue(result.images.isEmpty())
    }

    @Test
    fun `zip bombs stop at the uncompressed limit`() {
        // 21 entries of 5 MiB zeros compress to almost nothing but inflate past 100 MiB.
        val zeros = ByteArray(RecipePackReader.IMAGE_MAX_BYTES)
        val entries = (1..21).map { "images/$it.jpg" to zeros }.toTypedArray()
        val result = read(zipOf("tacos.md" to tacos, *entries))
        assertEquals(IssueCode.P02, result.issues.single().code)
    }

    @Test
    fun `too many entries or too many compressed bytes are P02`() {
        val many = (1..RecipePackReader.ENTRIES_MAX + 1).map { "n$it.txt" to byteArrayOf(1) }.toTypedArray()
        assertEquals(IssueCode.P02, read(zipOf("tacos.md" to tacos, *many)).issues.single().code)
        assertEquals(IssueCode.P02, read(zipOf("tacos.md" to tacos), maxBytes = 100).issues.single().code)
    }

    @Test
    fun `invalid text and corrupt zips are P01`() {
        assertEquals(IssueCode.P01, read(byteArrayOf(0xC3.toByte(), 0x28), "malo.md").issues.single().code)
        val corrupt = byteArrayOf(0x50, 0x4B, 0x03, 0x04) + ByteArray(40) { 0x7F }
        assertEquals(IssueCode.P01, read(corrupt).issues.single().code)
    }

    @Test
    fun `written zips read back with images and zero issues`() {
        val draft = assertNotNull(parse(String(tacos, Charsets.UTF_8)).draft)
        val out = ByteArrayOutputStream()
        RecipePackWriter.writeZip(
            listOf(
                ExportItem(draft, ExportImage(jpeg, "jpg")),
                ExportItem(draft.copy(uid = null)),
            ),
            out,
        )
        val names = mutableListOf<String>()
        ZipInputStream(ByteArrayInputStream(out.toByteArray())).use { zip ->
            while (true) names += (zip.nextEntry ?: break).name
        }
        val slug = "tacos-de-pollo-con-aguacate-y-mozzarella"
        assertEquals(listOf("$slug.md", "images/$slug.jpg", "$slug-2.md"), names)

        val result = read(out.toByteArray())
        assertEquals(emptyList(), result.issues)
        assertEquals(draft.copy(image = "images/$slug.jpg"), result.documents[0].parsed.draft)
        assertEquals(draft.copy(uid = null), result.documents[1].parsed.draft)
    }

    @Test
    fun `slugs are ascii and never empty`() {
        assertEquals("noquis-con-tomate", RecipePackWriter.slug("Ñoquis con tomate!"))
        assertEquals("receta", RecipePackWriter.slug("¡¿?!"))
        assertTrue(RecipePackWriter.slug("x".repeat(200)).length <= 60)
    }
}
