package com.lechugones.cocina.feature.export

import org.junit.Test
import java.util.Locale
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PdfLinksTest {
    /** A two-page PDF laid out as Android's PdfDocument writes it: classic xref, flat page tree. */
    private fun pdf(pageExtra: String = ""): ByteArray {
        val objects =
            listOf(
                "<</Producer (test)>>",
                "<</Type /Page\n/MediaBox [0 0 595 842]\n/Contents 4 0 R$pageExtra\n/Parent 5 0 R>>",
                "<</Type /Page\n/MediaBox [0 0 595 842]\n/Contents 4 0 R\n/Parent 5 0 R>>",
                "<</Length 0>> stream\n\nendstream",
                "<</Type /Pages\n/Count 2\n/Kids [2 0 R 3 0 R]>>",
                "<</Type /Catalog\n/Pages 5 0 R>>",
            )
        val text = StringBuilder("%PDF-1.4\n")
        val offsets =
            objects.mapIndexed { i, body ->
                val offset = text.length
                text
                    .append(i + 1)
                    .append(" 0 obj\n")
                    .append(body)
                    .append("\nendobj\n")
                offset
            }
        val xref = text.length
        text.append("xref\n0 ${objects.size + 1}\n0000000000 65535 f \n")
        offsets.forEach { text.append(String.format(Locale.ROOT, "%010d 00000 n \n", it)) }
        text.append("trailer\n<</Size ${objects.size + 1}\n/Root 6 0 R\n/Info 1 0 R>>\nstartxref\n$xref\n%%EOF")
        return text.toString().toByteArray(Charsets.ISO_8859_1)
    }

    private val link =
        PdfLink(page = 2, left = 50f, top = 100f, right = 150f, bottom = 112f, url = "https://tienda.mercadona.es/product/3400/patatas")

    @Test
    fun `links are appended as an incremental update`() {
        val original = pdf()
        val updated = PdfLinks.add(original, listOf(link), 842f)
        assertContentEquals(original, updated.copyOf(original.size))
        val tail = String(updated, original.size, updated.size - original.size, Charsets.ISO_8859_1)
        // The annotation, y flipped to PDF space, and the second page rewritten with it.
        assertTrue("7 0 obj\n<</Type /Annot\n/Subtype /Link\n/Rect [50.00 730.00 150.00 742.00]" in tail, tail)
        assertTrue("/URI (https://tienda.mercadona.es/product/3400/patatas)" in tail)
        assertTrue("3 0 obj\n<</Type /Page" in tail && "/Annots [7 0 R]>>" in tail, tail)
        assertTrue("trailer\n<</Size 8\n/Root 6 0 R\n/Info 1 0 R\n/Prev " in tail, tail)
        // The new cross-reference entries point exactly at the new objects.
        val xrefAt = Regex("""startxref\n(\d+)\n%%EOF\n$""").find(tail)!!.groupValues[1].toInt()
        val sections = Regex("""(\d+) 1\n(\d{10}) 00000 n""").findAll(String(updated, xrefAt, updated.size - xrefAt, Charsets.ISO_8859_1))
        val entries = sections.associate { it.groupValues[1].toInt() to it.groupValues[2].toInt() }
        assertEquals(setOf(3, 7), entries.keys)
        entries.forEach { (number, offset) ->
            assertTrue(String(updated, offset, 12, Charsets.ISO_8859_1).startsWith("$number 0 obj"))
        }
    }

    @Test
    fun `odd characters are escaped, and only web links are kept`() {
        assertEquals("""(https://x.es/a\(1\)%C3%B1%20b)""", PdfLinks.literal("https://x.es/a(1)ñ b"))
        val original = pdf()
        assertContentEquals(original, PdfLinks.add(original, listOf(link.copy(url = "javascript:alert(1)")), 842f))
        assertContentEquals(original, PdfLinks.add(original, emptyList(), 842f))
    }

    @Test
    fun `files it does not understand come back unchanged`() {
        val annotated = pdf("\n/Annots [9 0 R]")
        assertContentEquals(annotated, PdfLinks.add(annotated, listOf(link.copy(page = 1)), 842f))
        val garbage = "not a pdf".toByteArray()
        assertContentEquals(garbage, PdfLinks.add(garbage, listOf(link), 842f))
    }
}
