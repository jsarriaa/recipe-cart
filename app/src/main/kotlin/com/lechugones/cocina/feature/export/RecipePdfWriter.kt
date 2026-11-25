package com.lechugones.cocina.feature.export

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.CharacterStyle
import android.text.style.ForegroundColorSpan
import android.text.style.MetricAffectingSpan
import android.text.style.StyleSpan
import android.text.style.UnderlineSpan
import androidx.core.content.ContextCompat
import com.lechugones.cocina.R
import com.lechugones.cocina.domain.markdown.MdBlock
import com.lechugones.cocina.domain.markdown.MdSpan
import com.lechugones.cocina.domain.recipepack.RecipePackWriter
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import javax.inject.Inject
import kotlin.math.max

/**
 * Lays a [RecipePrintout] out on A4 pages with Android's own [PdfDocument], in the app's
 * "cocina mediterránea" look: a tomato band with the logo, the photo across the page, the details
 * as pills, the ingredients in a two-column olive card with where to buy each one, and numbered
 * steps. Text stays text, so the file is small and searchable.
 */
class RecipePdfWriter
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        /** Writes the PDF into the export cache; null if it could not be written. */
        suspend fun write(printout: RecipePrintout): ExportFile? =
            withContext(Dispatchers.IO) {
                val directory = File(context.cacheDir, RecipeExporter.EXPORT_DIRECTORY).apply { deleteRecursively() }
                directory.mkdirs()
                val file = File(directory, RecipePackWriter.slug(printout.fileName) + ".pdf")
                val document = PdfDocument()
                try {
                    val pages = Pages(document, printout)
                    pages.render()
                    val bytes = ByteArrayOutputStream().also { document.writeTo(it) }.toByteArray()
                    file.writeBytes(PdfLinks.add(bytes, pages.links, PAGE_HEIGHT.toFloat()))
                    ExportFile(file, RecipeExporter.MIME_PDF)
                } catch (e: IOException) {
                    null
                } finally {
                    document.close()
                }
            }

        /** An ingredient laid out; [linkFrom] is where its shop and product start, when they link to [url]. */
        private class Cell(
            val layout: StaticLayout,
            val url: String?,
            val linkFrom: Int,
        )

        /** One row of an ingredient card: a group title across the card, or up to two ingredients. */
        private class CardRow(
            val title: StaticLayout?,
            val cells: List<Cell>,
        ) {
            val height: Float = (title?.height ?: cells.maxOf { it.layout.height }).toFloat()
        }

        private inner class Pages(
            private val document: PdfDocument,
            private val printout: RecipePrintout,
        ) {
            /** The product links drawn so far, added to the file afterwards. */
            val links = mutableListOf<PdfLink>()
            private var number = 0
            private var page: PdfDocument.Page? = null
            private var y = 0f
            private val canvas: Canvas get() = checkNotNull(page) { "no open page" }.canvas

            private val serif = serifBold()
            private val brandPaint = text(18f, WHITE, serif)
            private val titlePaint = text(27f, INK, serif)
            private val descriptionPaint = text(11.5f, MUTED, SANS_ITALIC)
            private val pillPaint = text(9.5f, TOMATO_DARK).drawnBold()
            private val sectionPaint = text(17f, TOMATO, serif)
            private val subsectionPaint = text(12.5f, OLIVE_DARK, serif)
            private val ingredientPaint = text(10.5f, INK)
            private val bodyPaint = text(11f, INK)
            private val mutedPaint = text(10f, MUTED)
            private val numberPaint = text(10f, WHITE).drawnBold().apply { textAlign = Paint.Align.CENTER }
            private val footerPaint = text(8.5f, MUTED).apply { textAlign = Paint.Align.CENTER }

            fun render() {
                newPage()
                printout.photo?.let(::photo)
                block(layout(printout.title, titlePaint), after = 6f)
                printout.description?.let { block(layout(it, descriptionPaint), after = 10f) }
                pills(printout.details)

                section(printout.ingredientsTitle)
                card(
                    printout.groups.flatMap { group ->
                        listOfNotNull(group.title?.let { CardRow(layout(it, subsectionPaint, CARD_INNER), emptyList()) }) +
                            cellRows(group.ingredients)
                    },
                )
                printout.staplesTitle?.let { title ->
                    y += 4f
                    block(layout(title, subsectionPaint), keep = 50f, after = 6f)
                    card(cellRows(printout.staples))
                }

                y += 6f
                if (printout.steps.isEmpty()) {
                    block(layout(printout.stepsEmpty, mutedPaint))
                } else {
                    printout.steps.forEach(::step)
                }
                finishPage()
            }

            // --- Pages --------------------------------------------------------------------

            private fun newPage() {
                finishPage()
                number++
                page = document.startPage(PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, number).create())
                canvas.drawColor(CREAM)
                band()
            }

            private fun finishPage() {
                val open = page ?: return
                val footerY = PAGE_HEIGHT - FOOTER_HEIGHT
                canvas.drawRect(MARGIN, footerY, PAGE_WIDTH - MARGIN, footerY + 0.6f, fill(RULE))
                canvas.drawText(printout.footer(number), PAGE_WIDTH / 2f, footerY + 16f, footerPaint)
                document.finishPage(open)
                page = null
            }

            /** The tomato band with the lettuce and the app's name, on every page. */
            private fun band() {
                canvas.drawRect(0f, 0f, PAGE_WIDTH.toFloat(), BAND_HEIGHT, fill(TOMATO))
                val logoTop = (BAND_HEIGHT - LOGO_SIZE) / 2
                ContextCompat.getDrawable(context, R.mipmap.ic_launcher)?.apply {
                    setBounds(MARGIN.toInt(), logoTop.toInt(), (MARGIN + LOGO_SIZE).toInt(), (logoTop + LOGO_SIZE).toInt())
                    draw(canvas)
                }
                val name = layout(printout.brand, brandPaint)
                draw(name, MARGIN + LOGO_SIZE + 12f, (BAND_HEIGHT - name.height) / 2)
                y = BAND_HEIGHT + 24f
            }

            private val bottom: Float get() = PAGE_HEIGHT - FOOTER_HEIGHT - 12f

            private fun ensure(height: Float) {
                if (y + height > bottom) newPage()
            }

            // --- Content ------------------------------------------------------------------

            /** Across the whole page, right under the band, fading into the cream at the bottom. */
            private fun photo(file: File) {
                val bitmap = decode(file) ?: return
                val target = RectF(0f, BAND_HEIGHT, PAGE_WIDTH.toFloat(), BAND_HEIGHT + PHOTO_HEIGHT)
                val ratio = target.width() / target.height()
                val source =
                    if (bitmap.width.toFloat() / bitmap.height > ratio) {
                        val w = (bitmap.height * ratio).toInt()
                        Rect((bitmap.width - w) / 2, 0, (bitmap.width + w) / 2, bitmap.height)
                    } else {
                        val h = (bitmap.width / ratio).toInt()
                        Rect(0, (bitmap.height - h) / 2, bitmap.width, (bitmap.height + h) / 2)
                    }
                canvas.drawBitmap(bitmap, source, target, Paint(Paint.FILTER_BITMAP_FLAG))
                bitmap.recycle()
                val fade = Paint()
                fade.shader = LinearGradient(0f, target.bottom - 40f, 0f, target.bottom, CREAM and 0x00FFFFFF, CREAM, Shader.TileMode.CLAMP)
                canvas.drawRect(0f, target.bottom - 40f, PAGE_WIDTH.toFloat(), target.bottom, fade)
                y = target.bottom + 18f
            }

            /** "4 raciones", "Preparación: 15 min" as rounded tags on one line. */
            private fun pills(labels: List<String>) {
                if (labels.isEmpty()) return
                ensure(PILL_HEIGHT)
                var x = MARGIN
                labels.forEach { label ->
                    val width = pillPaint.measureText(label) + 2 * PILL_PADDING
                    if (x + width > PAGE_WIDTH - MARGIN) {
                        x = MARGIN
                        y += PILL_HEIGHT + 6f
                    }
                    canvas.drawRoundRect(RectF(x, y, x + width, y + PILL_HEIGHT), PILL_HEIGHT / 2, PILL_HEIGHT / 2, fill(TOMATO_LIGHT))
                    canvas.drawText(
                        label,
                        x + PILL_PADDING,
                        y + PILL_HEIGHT / 2 - (pillPaint.descent() + pillPaint.ascent()) / 2,
                        pillPaint,
                    )
                    x += width + 8f
                }
                y += PILL_HEIGHT + 22f
            }

            /** A serif title with a short olive bar under it. */
            private fun section(title: String) {
                val layout = layout(title, sectionPaint)
                ensure(layout.height + 50f)
                flow(layout, MARGIN)
                canvas.drawRoundRect(RectF(MARGIN, y + 4f, MARGIN + 30f, y + 7f), 1.5f, 1.5f, fill(OLIVE))
                y += 16f
            }

            private fun cellRows(items: List<PrintIngredient>): List<CardRow> = items.map(::cell).chunked(2).map { CardRow(null, it) }

            /** **700 g** patata / en láminas finas / **Mercadona** · Patatas, linked to the shop's page. */
            private fun cell(item: PrintIngredient): Cell {
                var linkFrom = -1
                val text =
                    SpannableStringBuilder().apply {
                        span(item.quantity, BoldSpan(), ForegroundColorSpan(OLIVE_DARK))
                        // Its own span: a plain run after a styled one lost its spaces in the PDF.
                        span("  " + item.name, FontSpan(SANS))
                        item.note?.let {
                            append("\n")
                            span(it, FontSpan(SANS_ITALIC), ForegroundColorSpan(MUTED))
                        }
                        item.product?.let { product ->
                            append("\n")
                            linkFrom = length
                            item.shop?.let {
                                span(it, BoldSpan(), ForegroundColorSpan(TOMATO))
                                span(" · ", ForegroundColorSpan(MUTED))
                            }
                            if (item.url == null) {
                                span(product, ForegroundColorSpan(OLIVE_DARK))
                            } else {
                                span(product, ForegroundColorSpan(OLIVE_DARK), UnderlineSpan())
                            }
                        }
                    }
                return Cell(layout(text, ingredientPaint, CELL_WIDTH), item.url?.takeIf { linkFrom >= 0 }, linkFrom)
            }

            /** The area of [cell]'s shop and product, drawn at ([x], [top]), as a link. */
            private fun link(
                cell: Cell,
                x: Float,
                top: Float,
            ) {
                val url = cell.url ?: return
                val layout = cell.layout
                val first = layout.getLineForOffset(cell.linkFrom)
                val last = layout.lineCount - 1
                val left = (first..last).minOf { layout.getLineLeft(it) }
                val right = (first..last).maxOf { layout.getLineRight(it) }
                links +=
                    PdfLink(
                        page = number,
                        left = x + left - 2f,
                        top = top + layout.getLineTop(first) - 1f,
                        right = x + right + 2f,
                        bottom = top + layout.getLineBottom(last) + 1f,
                        url = url,
                    )
            }

            /**
             * An olive card of [rows]. Rows never split; when the page runs out the card closes
             * and goes on, on the next page.
             */
            private fun card(rows: List<CardRow>) {
                var index = 0
                while (index < rows.size) {
                    var height = 2 * CARD_PADDING
                    var end = index
                    while (end < rows.size) {
                        val next = height + rows[end].height + (if (end > index) ROW_GAP else 0f)
                        if (y + next > bottom) break
                        height = next
                        end++
                    }
                    if (end == index) {
                        newPage()
                        continue
                    }
                    canvas.drawRoundRect(RectF(MARGIN, y, PAGE_WIDTH - MARGIN, y + height), 12f, 12f, fill(OLIVE_LIGHT))
                    var rowY = y + CARD_PADDING
                    for (row in rows.subList(index, end)) {
                        row.title?.let { draw(it, MARGIN + CARD_PADDING, rowY) }
                        row.cells.forEachIndexed { column, cell ->
                            val x = MARGIN + CARD_PADDING + column * (CELL_WIDTH + COLUMN_GAP)
                            draw(cell.layout, x, rowY)
                            link(cell, x, rowY)
                        }
                        rowY += row.height + ROW_GAP
                    }
                    y += height + 16f
                    index = end
                }
            }

            private fun step(md: MdBlock) {
                when (md) {
                    is MdBlock.Heading -> {
                        if (md.level <= 2) {
                            section(plain(md.spans))
                        } else {
                            block(layout(styled(md.spans), subsectionPaint), keep = 40f, after = 6f)
                        }
                    }

                    is MdBlock.Paragraph -> {
                        block(layout(styled(md.spans), bodyPaint), after = 10f)
                    }

                    is MdBlock.OrderedList -> {
                        md.items.forEachIndexed { i, item -> numbered((md.start + i).toString(), item) }
                        y += 4f
                    }

                    is MdBlock.BulletList -> {
                        md.items.forEach { bullet(it) }
                        y += 4f
                    }
                }
            }

            /** A tomato disc with the step number, the text beside it. */
            private fun numbered(
                label: String,
                spans: List<MdSpan>,
            ) {
                val text = layout(styled(spans), bodyPaint, CONTENT_WIDTH - STEP_INDENT)
                ensure(max(STEP_DISC * 2, firstLine(text)))
                val cx = MARGIN + STEP_DISC
                val cy = y + STEP_DISC - 1f
                canvas.drawCircle(cx, cy, STEP_DISC, fill(TOMATO))
                canvas.drawText(label, cx, cy - (numberPaint.descent() + numberPaint.ascent()) / 2, numberPaint)
                y += 2f
                flow(text, MARGIN + STEP_INDENT)
                y = max(y, cy + STEP_DISC) + 12f
            }

            private fun bullet(spans: List<MdSpan>) {
                val text = layout(styled(spans), bodyPaint, CONTENT_WIDTH - STEP_INDENT)
                ensure(firstLine(text))
                canvas.drawCircle(MARGIN + STEP_DISC, y + firstLine(text) / 2, 3f, fill(OLIVE))
                flow(text, MARGIN + STEP_INDENT)
                y += 6f
            }

            // --- Text ---------------------------------------------------------------------

            /** Draws [layout] at the cursor, keeping [keep] points free below it (a title with its first lines). */
            private fun block(
                layout: StaticLayout,
                keep: Float = 0f,
                after: Float = 0f,
            ) {
                ensure(minOf(layout.height + keep, bottom - BAND_HEIGHT - 24f))
                flow(layout, MARGIN)
                y += after
            }

            /** Draws [layout] line by line, starting a new page when a line does not fit. */
            private fun flow(
                layout: StaticLayout,
                x: Float,
            ) {
                for (line in 0 until layout.lineCount) {
                    val top = layout.getLineTop(line).toFloat()
                    val height = layout.getLineBottom(line) - top
                    ensure(height)
                    canvas.save()
                    canvas.clipRect(x, y, x + layout.width, y + height)
                    canvas.translate(x, y - top)
                    layout.draw(canvas)
                    canvas.restore()
                    y += height
                }
            }

            private fun draw(
                layout: StaticLayout,
                x: Float,
                top: Float,
            ) {
                canvas.save()
                canvas.translate(x, top)
                layout.draw(canvas)
                canvas.restore()
            }

            private fun firstLine(layout: StaticLayout): Float = (layout.getLineBottom(0) - layout.getLineTop(0)).toFloat()
        }

        private fun decode(file: File): Bitmap? {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, bounds)
            if (bounds.outWidth <= 0) return null
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= MAX_PHOTO_PIXELS) sample *= 2
            return BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
        }

        companion object {
            /** A4 in points. */
            const val PAGE_WIDTH = 595
            const val PAGE_HEIGHT = 842
            private const val MARGIN = 44f
            private const val CONTENT_WIDTH = PAGE_WIDTH - 2 * MARGIN
            private const val BAND_HEIGHT = 62f
            private const val LOGO_SIZE = 40f
            private const val PHOTO_HEIGHT = 230f
            private const val FOOTER_HEIGHT = 34f
            private const val PILL_HEIGHT = 20f
            private const val PILL_PADDING = 10f
            private const val CARD_PADDING = 14f
            private const val CARD_INNER = CONTENT_WIDTH - 2 * CARD_PADDING
            private const val COLUMN_GAP = 18f
            private const val CELL_WIDTH = (CARD_INNER - COLUMN_GAP) / 2
            private const val ROW_GAP = 10f
            private const val STEP_DISC = 10f
            private const val STEP_INDENT = 32f
            private const val LINE_SPACING = 1.18f

            /** Wide enough for print, small enough to share. */
            private const val MAX_PHOTO_PIXELS = 1400

            // The app's light theme (ui/theme/Theme.kt): tomato, olive and ink on cream.
            private const val TOMATO = 0xFFB3412A.toInt()
            private const val TOMATO_DARK = 0xFF8A2E1C.toInt()
            private const val TOMATO_LIGHT = 0xFFFFE4DC.toInt()
            private const val OLIVE = 0xFF7A8A35.toInt()
            private const val OLIVE_DARK = 0xFF4A5520.toInt()
            private const val OLIVE_LIGHT = 0xFFEEF2DA.toInt()
            private const val CREAM = 0xFFFFFBF7.toInt()
            private const val INK = 0xFF231917.toInt()
            private const val MUTED = 0xFF6F5E5A.toInt()
            private const val RULE = 0xFFE7D7D2.toInt()
            private const val WHITE = 0xFFFFFFFF.toInt()

            /**
             * The PDF keeps only the default instance of a variable font, so on phones whose system
             * sans is variable a bold typeface comes out regular. Bold is drawn instead: the glyphs
             * filled and outlined in their own colour, which every PDF viewer shows.
             */
            private const val BOLD_STROKE = 0.035f
            private val SANS: Typeface = Typeface.DEFAULT
            private val SANS_ITALIC: Typeface = Typeface.create(Typeface.DEFAULT, Typeface.ITALIC)
            private const val FONTS = "/system/fonts"

            private fun TextPaint.drawnBold() =
                apply {
                    style = Paint.Style.FILL_AND_STROKE
                    strokeWidth = textSize * BOLD_STROKE
                    strokeJoin = Paint.Join.ROUND
                }

            /** Bold for a run of text, drawn (see [BOLD_STROKE]); a [StyleSpan] would pick the variable bold. */
            private class BoldSpan : CharacterStyle() {
                override fun updateDrawState(paint: TextPaint) {
                    paint.drawnBold()
                }
            }

            /** Sets a whole typeface on a run of text (italic). */
            private class FontSpan(
                private val typeface: Typeface,
            ) : MetricAffectingSpan() {
                override fun updateDrawState(paint: TextPaint) {
                    paint.typeface = typeface
                }

                override fun updateMeasureState(paint: TextPaint) {
                    paint.typeface = typeface
                }
            }

            /** Noto Serif from the system, as the app's headings: some phones map "serif" to a sans. */
            private fun serifBold(): Typeface {
                val file = File(FONTS, "NotoSerif-Bold.ttf")
                return if (file.canRead()) Typeface.createFromFile(file) else Typeface.create(Typeface.SERIF, Typeface.BOLD)
            }

            private fun text(
                size: Float,
                color: Int,
                typeface: Typeface = SANS,
            ) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = size
                this.color = color
                this.typeface = typeface
            }

            private fun fill(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }

            private fun layout(
                text: CharSequence,
                paint: TextPaint,
                width: Float = CONTENT_WIDTH,
            ): StaticLayout =
                StaticLayout.Builder
                    .obtain(text, 0, text.length, paint, width.toInt())
                    .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                    .setLineSpacing(0f, LINE_SPACING)
                    .setIncludePad(false)
                    .build()

            private fun SpannableStringBuilder.span(
                text: String,
                vararg spans: Any,
            ) {
                val start = length
                append(text)
                spans.forEach { setSpan(it, start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
            }

            private fun plain(spans: List<MdSpan>): String = spans.joinToString("") { it.text }

            /** Bold and italic of the recipe Markdown subset. */
            fun styled(spans: List<MdSpan>): CharSequence =
                SpannableStringBuilder().apply {
                    spans.forEach { span ->
                        val styles = listOfNotNull(BoldSpan().takeIf { span.bold }, FontSpan(SANS_ITALIC).takeIf { span.italic })
                        span(span.text, *styles.toTypedArray())
                    }
                }
        }
    }
