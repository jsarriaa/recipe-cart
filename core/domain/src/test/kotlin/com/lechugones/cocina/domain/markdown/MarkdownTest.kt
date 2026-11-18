package com.lechugones.cocina.domain.markdown

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class MarkdownTest {
    @Test
    fun `recipe body with headings, ordered steps and notes`() {
        val blocks =
            Markdown.parse(
                """
                ## Preparación

                1. Corta el pollo
                   en tiras.
                2. Saltéalo **6-8 minutos**.

                ## Notas

                - Con *lima* queda mejor.
                - Aguanta 2 días.
                """.trimIndent(),
            )
        assertEquals(
            listOf(
                MdBlock.Heading(2, listOf(MdSpan("Preparación"))),
                MdBlock.OrderedList(
                    1,
                    listOf(
                        listOf(MdSpan("Corta el pollo en tiras.")),
                        listOf(MdSpan("Saltéalo "), MdSpan("6-8 minutos", bold = true), MdSpan(".")),
                    ),
                ),
                MdBlock.Heading(2, listOf(MdSpan("Notas"))),
                MdBlock.BulletList(
                    listOf(
                        listOf(MdSpan("Con "), MdSpan("lima", italic = true), MdSpan(" queda mejor.")),
                        listOf(MdSpan("Aguanta 2 días.")),
                    ),
                ),
            ),
            blocks,
        )
    }

    @Test
    fun `paragraph lines join, two trailing spaces break`() {
        assertEquals(
            listOf(MdBlock.Paragraph(listOf(MdSpan("uno dos\ntres")))),
            Markdown.parse("uno\ndos  \ntres"),
        )
    }

    @Test
    fun `ordered lists keep their start number`() {
        assertEquals(MdBlock.OrderedList(3, listOf(listOf(MdSpan("tres")))), Markdown.parse("3) tres").single())
    }

    @Test
    fun `underscores inside words are not emphasis`() {
        assertEquals(listOf(MdSpan("al_gusto y "), MdSpan("cursiva", italic = true)), Markdown.inline("al_gusto y _cursiva_"))
    }

    @Test
    fun `links, images, code and html stay as plain text`() {
        assertEquals(listOf(MdSpan("web (https://x.es)")), Markdown.inline("[web](https://x.es)"))
        assertEquals(listOf(MdSpan("foto")), Markdown.inline("![foto](https://x.es/a.jpg)"))
        assertEquals(listOf(MdSpan("usa sal")), Markdown.inline("usa `sal`"))
        assertEquals(listOf(MdSpan("<b>no</b>")), Markdown.inline("<b>no</b>"))
        assertEquals(listOf(MdSpan("2 * 3 = *6*")), Markdown.inline("2 \\* 3 = \\*6\\*"))
    }

    @Test
    fun `bold and italic combine`() {
        assertEquals(listOf(MdSpan("muy ", bold = true), MdSpan("rico", bold = true, italic = true)), Markdown.inline("**muy *rico***"))
    }
}
