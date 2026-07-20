package com.tsm.ocrx.model

/**
 * Result of running OCR on one image.
 *
 * @param rawText   the full recognized text, blocks separated by blank lines.
 * @param rows      each recognized line split into columns. Columns are detected
 *                  by runs of 2+ spaces, which OCR typically produces between
 *                  table columns. A single-column line yields a one-element row.
 */
data class OcrResult(
    val rawText: String,
    val rows: List<List<String>>
) {
    /** Widest row — used to pad every row to a rectangular grid for export. */
    val columnCount: Int = rows.maxOfOrNull { it.size } ?: 0

    val isEmpty: Boolean get() = rows.isEmpty()

    companion object {
        val EMPTY = OcrResult("", emptyList())
    }
}
