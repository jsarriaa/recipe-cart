package com.lechugones.cocina.domain.markdown

/** A run of text with emphasis. */
data class MdSpan(
    val text: String,
    val bold: Boolean = false,
    val italic: Boolean = false,
)

sealed interface MdBlock {
    data class Heading(
        val level: Int,
        val spans: List<MdSpan>,
    ) : MdBlock

    data class Paragraph(
        val spans: List<MdSpan>,
    ) : MdBlock

    data class OrderedList(
        val start: Int,
        val items: List<List<MdSpan>>,
    ) : MdBlock

    data class BulletList(
        val items: List<List<MdSpan>>,
    ) : MdBlock
}

/**
 * The Markdown subset recipes use (RECIPE_FORMAT § 8): headings, paragraphs, ordered and
 * bullet lists, bold and italic. Everything else is kept as plain text: raw HTML is shown
 * literally, images are not loaded, links become "text (url)".
 */
object Markdown {
    private val HEADING = Regex("""^(#{1,6})\s+(.*?)\s*#*\s*$""")
    private val ORDERED = Regex("""^\s{0,3}(\d{1,9})[.)]\s+(.*)$""")
    private val BULLET = Regex("""^\s{0,3}[-*+]\s+(.*)$""")
    private val LINK = Regex("""!?\[([^\]]*)]\(([^)\s]*)[^)]*\)""")
    private val CODE = Regex("""`([^`]*)`""")

    fun parse(text: String): List<MdBlock> {
        val blocks = mutableListOf<MdBlock>()
        val paragraph = StringBuilder()
        var listItems = mutableListOf<StringBuilder>()
        var listOrdered = false
        var listStart = 1

        fun flushParagraph() {
            if (paragraph.isNotBlank()) blocks += MdBlock.Paragraph(inline(paragraph.toString().trim()))
            paragraph.clear()
        }

        fun flushList() {
            if (listItems.isNotEmpty()) {
                val items = listItems.map { inline(it.toString().trim()) }
                blocks += if (listOrdered) MdBlock.OrderedList(listStart, items) else MdBlock.BulletList(items)
            }
            listItems = mutableListOf()
        }

        for (raw in text.replace("\r\n", "\n").split('\n')) {
            val line = raw.trimEnd()
            val hardBreak = raw.endsWith("  ")
            val heading = HEADING.matchEntire(line)
            val ordered = ORDERED.matchEntire(line)
            val bullet = BULLET.matchEntire(line)
            when {
                line.isBlank() -> {
                    flushParagraph()
                    flushList()
                }

                heading != null -> {
                    flushParagraph()
                    flushList()
                    blocks += MdBlock.Heading(heading.groupValues[1].length, inline(heading.groupValues[2]))
                }

                ordered != null -> {
                    flushParagraph()
                    if (listItems.isNotEmpty() && !listOrdered) flushList()
                    if (listItems.isEmpty()) {
                        listOrdered = true
                        listStart = ordered.groupValues[1].toIntOrNull() ?: 1
                    }
                    listItems += StringBuilder(ordered.groupValues[2])
                }

                bullet != null -> {
                    flushParagraph()
                    if (listItems.isNotEmpty() && listOrdered) flushList()
                    listOrdered = false
                    listItems += StringBuilder(bullet.groupValues[1])
                }

                listItems.isNotEmpty() -> {
                    listItems.last().append(' ').append(line.trim())
                }

                else -> {
                    if (paragraph.isNotEmpty()) paragraph.append(if (paragraph.endsWith("\n")) "" else " ")
                    paragraph.append(line.trim())
                    if (hardBreak) paragraph.append('\n')
                }
            }
        }
        flushParagraph()
        flushList()
        return blocks
    }

    /** Inline emphasis: `**bold**`, `__bold__`, `*italic*`, `_italic_` (not inside words). */
    fun inline(text: String): List<MdSpan> {
        val plain =
            text
                .replace(LINK) { m -> if (m.value.startsWith("!")) m.groupValues[1] else "${m.groupValues[1]} (${m.groupValues[2]})" }
                .replace(CODE) { it.groupValues[1] }
        val spans = mutableListOf<MdSpan>()
        val current = StringBuilder()
        var bold = false
        var italic = false

        fun flush() {
            if (current.isNotEmpty()) spans += MdSpan(current.toString(), bold, italic)
            current.clear()
        }

        var i = 0
        while (i < plain.length) {
            val c = plain[i]
            val next = plain.getOrNull(i + 1)
            val prev = plain.getOrNull(i - 1)
            when {
                c == '\\' && next != null && next in "*_\\`#[]" -> {
                    current.append(next)
                    i += 2
                    continue
                }

                (c == '*' || c == '_') && next == c -> {
                    flush()
                    bold = !bold
                    i += 2
                    continue
                }

                c == '*' || (c == '_' && !(prev?.isLetterOrDigit() == true && next?.isLetterOrDigit() == true)) -> {
                    flush()
                    italic = !italic
                }

                else -> {
                    current.append(c)
                }
            }
            i++
        }
        flush()
        return mergeAdjacent(spans)
    }

    private fun mergeAdjacent(spans: List<MdSpan>): List<MdSpan> =
        spans.fold(mutableListOf()) { acc, span ->
            val last = acc.lastOrNull()
            if (last != null && last.bold == span.bold && last.italic == span.italic) {
                acc[acc.lastIndex] = last.copy(text = last.text + span.text)
            } else {
                acc += span
            }
            acc
        }
}
