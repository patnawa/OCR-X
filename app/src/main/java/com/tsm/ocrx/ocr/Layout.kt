package com.tsm.ocrx.ocr

import kotlin.math.abs

/** A recognized text fragment with its position, used to rebuild reading order. */
data class PositionedText(
    val top: Int,
    val left: Int,
    val height: Int,
    val text: String
)

/**
 * Rebuilds reading order from positioned OCR fragments. Fragments are grouped
 * into visual rows (tops within a tolerance derived from the median text height),
 * each row is ordered left-to-right, and same-row fragments are joined with a wide
 * gap so the downstream column splitter treats them as separate columns.
 */
object Layout {

    fun buildReadingOrder(items: List<PositionedText>): String {
        val clean = items.filter { it.text.isNotBlank() }
        if (clean.isEmpty()) return ""

        val medianHeight = clean.map { it.height }.sorted().let { it[it.size / 2] }
        val tolerance = maxOf(10, (medianHeight * 0.6f).toInt())

        val sorted = clean.sortedBy { it.top }
        val rows = mutableListOf<MutableList<PositionedText>>()
        for (item in sorted) {
            val row = rows.lastOrNull()
            if (row != null && abs(item.top - row.first().top) <= tolerance) {
                row.add(item)
            } else {
                rows.add(mutableListOf(item))
            }
        }

        return rows.joinToString("\n") { row ->
            row.sortedBy { it.left }.joinToString("   ") { it.text.trim() }
        }
    }
}
