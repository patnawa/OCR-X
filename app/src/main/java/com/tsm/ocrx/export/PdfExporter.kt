package com.tsm.ocrx.export

import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import com.tsm.ocrx.model.OcrResult
import java.io.OutputStream
import kotlin.math.floor

/**
 * Renders the OCR result to a paginated A4 PDF using Android's built-in
 * [PdfDocument] — no third-party dependency. Each recognized row becomes a line;
 * long lines wrap to the page width, and content flows across pages.
 */
object PdfExporter {

    private const val PAGE_W = 595   // A4 @ 72 dpi
    private const val PAGE_H = 842
    private const val MARGIN = 40f

    fun write(result: OcrResult, out: OutputStream) {
        val body = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF111111.toInt()
            textSize = 10f
            typeface = Typeface.MONOSPACE
        }
        val title = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF111111.toInt()
            textSize = 16f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val faint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF888888.toInt()
            textSize = 9f
            typeface = Typeface.MONOSPACE
        }

        val lineHeight = body.fontSpacing
        val charWidth = body.measureText("M").coerceAtLeast(1f)
        val maxChars = floor((PAGE_W - 2 * MARGIN) / charWidth).toInt().coerceAtLeast(8)

        // Build the wrapped list of physical lines from the table rows.
        val physical = mutableListOf<String>()
        for (row in result.rows) {
            val text = row.joinToString("    ")
            if (text.isEmpty()) physical.add("") else physical.addAll(wrap(text, maxChars))
        }
        if (physical.isEmpty()) physical.add("(no text recognized)")

        val doc = PdfDocument()
        var pageNumber = 1
        var page = doc.startPage(pageInfo(pageNumber))
        var canvas = page.canvas
        var y = MARGIN

        // Title on the first page.
        canvas.drawText("OCR-X — Extracted Text", MARGIN, y + title.textSize, title)
        y += title.fontSpacing + 8f
        canvas.drawText("${result.rows.size} lines", MARGIN, y, faint)
        y += lineHeight + 6f

        for (line in physical) {
            if (y + lineHeight > PAGE_H - MARGIN) {
                doc.finishPage(page)
                pageNumber++
                page = doc.startPage(pageInfo(pageNumber))
                canvas = page.canvas
                y = MARGIN
            }
            canvas.drawText(line, MARGIN, y + body.textSize, body)
            y += lineHeight
        }
        doc.finishPage(page)
        doc.writeTo(out)
        doc.close()
    }

    private fun pageInfo(number: Int) =
        PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, number).create()

    private fun wrap(text: String, maxChars: Int): List<String> {
        if (text.length <= maxChars) return listOf(text)
        val out = mutableListOf<String>()
        var start = 0
        while (start < text.length) {
            val end = (start + maxChars).coerceAtMost(text.length)
            // Prefer breaking on the last space within the window.
            val slice = text.substring(start, end)
            val breakAt = if (end < text.length) slice.lastIndexOf(' ') else -1
            if (breakAt > 0) {
                out.add(text.substring(start, start + breakAt))
                start += breakAt + 1
            } else {
                out.add(slice)
                start = end
            }
        }
        return out
    }
}
