package com.lechugones.cocina.feature.export

import java.util.Locale

/**
 * A clickable area on a page, in the page's own points with y growing downwards (as it was drawn).
 * [page] counts from 1.
 */
data class PdfLink(
    val page: Int,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val url: String,
)

/**
 * Adds web links to a finished PDF. Android's [android.graphics.pdf.PdfDocument] cannot make
 * them, so they are appended as an incremental update (PDF 1.4 § 3.4.5): one link annotation per
 * area, each page rewritten with its `/Annots`, a new cross-reference section and a trailer
 * pointing back at the old one. The original bytes are kept as they are. A file this does not
 * understand (no classic cross-reference table, pages that already have annotations) comes back
 * unchanged, without links.
 */
object PdfLinks {
    fun add(
        pdf: ByteArray,
        links: List<PdfLink>,
        pageHeight: Float,
    ): ByteArray {
        val usable = links.filter { it.url.startsWith("https://") || it.url.startsWith("http://") }
        if (usable.isEmpty()) return pdf
        val text = String(pdf, Charsets.ISO_8859_1)
        val document = Parsed.of(text) ?: return pdf
        val pages = document.pages() ?: return pdf

        val appended = StringBuilder("\n")
        val written = sortedMapOf<Int, Int>()
        var next = document.size

        fun write(
            number: Int,
            body: String,
        ) {
            written[number] = pdf.size + appended.length
            appended
                .append(number)
                .append(" 0 obj\n")
                .append(body)
                .append("\nendobj\n")
        }

        for ((page, pageLinks) in usable.groupBy { it.page }) {
            val pageNumber = pages.getOrNull(page - 1) ?: continue
            val pageObject = document.dictionary(pageNumber) ?: return pdf
            if ("/Annots" in pageObject) return pdf
            val annotations =
                pageLinks.map { link ->
                    val number = next++
                    write(number, annotation(link, pageHeight))
                    number
                }
            val close = pageObject.lastIndexOf(">>")
            val refs = annotations.joinToString(" ") { "$it 0 R" }
            write(pageNumber, pageObject.substring(0, close) + "\n/Annots [$refs]" + pageObject.substring(close))
        }

        val xref = pdf.size + appended.length
        appended.append("xref\n")
        written.forEach { (number, offset) ->
            appended.append(number).append(" 1\n").append(String.format(Locale.ROOT, "%010d 00000 n \n", offset))
        }
        appended
            .append("trailer\n<</Size ")
            .append(next)
            .append("\n/Root ")
            .append(document.root)
        document.info?.let { appended.append("\n/Info ").append(it) }
        appended
            .append("\n/Prev ")
            .append(document.xref)
            .append(">>\nstartxref\n")
            .append(xref)
            .append("\n%%EOF\n")
        return pdf + appended.toString().toByteArray(Charsets.ISO_8859_1)
    }

    private fun annotation(
        link: PdfLink,
        pageHeight: Float,
    ): String {
        // PDF user space has y growing upwards from the bottom of the page.
        val rect = listOf(link.left, pageHeight - link.bottom, link.right, pageHeight - link.top)
        val numbers = rect.joinToString(" ") { String.format(Locale.ROOT, "%.2f", it) }
        return "<</Type /Annot\n/Subtype /Link\n/Rect [$numbers]\n/Border [0 0 0]\n/A <</S /URI\n/URI ${literal(link.url)}>>>>"
    }

    /** A PDF string of ASCII only: other characters are percent-encoded, as in a URL. */
    fun literal(url: String): String =
        buildString {
            append('(')
            url.toByteArray(Charsets.UTF_8).forEach { byte ->
                val c = byte.toInt() and 0xFF
                when {
                    c == '('.code || c == ')'.code || c == '\\'.code -> append('\\').append(c.toChar())
                    c in 0x21..0x7E -> append(c.toChar())
                    else -> append('%').append(String.format(Locale.ROOT, "%02X", c))
                }
            }
            append(')')
        }

    /** The parts of an existing PDF the update needs, read through its cross-reference table. */
    private class Parsed(
        private val text: String,
        val xref: Int,
        private val offsets: Map<Int, Int>,
        val size: Int,
        val root: String,
        val info: String?,
    ) {
        /** The dictionary of object [number], from `<<` to its closing `>>`. Pages have no stream. */
        fun dictionary(number: Int): String? {
            val start = offsets[number] ?: return null
            val end = text.indexOf("endobj", start).takeIf { it > 0 } ?: return null
            val body = text.substring(start, end)
            val open = body.indexOf("<<").takeIf { it >= 0 } ?: return null
            if ("stream" in body) return null
            return body.substring(open, body.lastIndexOf(">>") + 2)
        }

        /** Page object numbers in reading order, walking the page tree. */
        fun pages(): List<Int>? {
            val catalog =
                dictionary(
                    REF
                        .find(root)
                        ?.groupValues
                        ?.get(1)
                        ?.toInt() ?: return null,
                ) ?: return null
            val tree =
                Regex("""/Pages\s+(\d+)\s+0\s+R""")
                    .find(catalog)
                    ?.groupValues
                    ?.get(1)
                    ?.toInt() ?: return null
            val result = mutableListOf<Int>()

            fun walk(
                number: Int,
                depth: Int,
            ): Boolean {
                if (depth > MAX_DEPTH) return false
                val node = dictionary(number) ?: return false
                return when {
                    Regex("""/Type\s*/Pages\b""").containsMatchIn(node) -> {
                        val kids = Regex("""/Kids\s*\[([^\]]*)]""").find(node)?.groupValues?.get(1) ?: return false
                        REF_ALL.findAll(kids).all { walk(it.groupValues[1].toInt(), depth + 1) }
                    }

                    Regex("""/Type\s*/Page\b""").containsMatchIn(node) -> {
                        result += number
                        true
                    }

                    else -> {
                        false
                    }
                }
            }
            return if (walk(tree, 0)) result else null
        }

        companion object {
            private val REF = Regex("""^(\d+)\s+0\s+R$""")
            private val REF_ALL = Regex("""(\d+)\s+0\s+R""")
            private const val MAX_DEPTH = 16

            fun of(text: String): Parsed? {
                val start =
                    Regex("""startxref\s+(\d+)\s+%%EOF\s*$""")
                        .find(text)
                        ?.groupValues
                        ?.get(1)
                        ?.toIntOrNull() ?: return null
                if (!text.startsWith("xref", start)) return null
                val trailerAt = text.indexOf("trailer", start).takeIf { it > 0 } ?: return null
                val offsets = mutableMapOf<Int, Int>()
                val lines =
                    text
                        .substring(start + 4, trailerAt)
                        .trim()
                        .lines()
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                var i = 0
                while (i < lines.size) {
                    val (first, count) =
                        lines[i].split(Regex("""\s+""")).map { it.toIntOrNull() ?: return null }.takeIf { it.size == 2 }
                            ?: return null
                    for (k in 0 until count) {
                        val entry = lines.getOrNull(i + 1 + k)?.split(Regex("""\s+""")) ?: return null
                        if (entry.getOrNull(2) == "n") offsets[first + k] = entry[0].toIntOrNull() ?: return null
                    }
                    i += 1 + count
                }
                val trailer = text.substring(trailerAt, text.indexOf("startxref", trailerAt))
                if ("/Prev" in trailer || "/Encrypt" in trailer) return null
                val size =
                    Regex("""/Size\s+(\d+)""")
                        .find(trailer)
                        ?.groupValues
                        ?.get(1)
                        ?.toInt() ?: return null
                val root = Regex("""/Root\s+(\d+\s+0\s+R)""").find(trailer)?.groupValues?.get(1) ?: return null
                val info = Regex("""/Info\s+(\d+\s+0\s+R)""").find(trailer)?.groupValues?.get(1)
                return Parsed(text, start, offsets, size, root, info)
            }
        }
    }
}
