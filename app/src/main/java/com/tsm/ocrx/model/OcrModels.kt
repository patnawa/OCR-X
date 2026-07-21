package com.tsm.ocrx.model

/**
 * Result of running OCR on one image.
 *
 * @param rawText   the full recognized text, blocks separated by blank lines.
 * @param rows      each recognized line split into columns. Columns come from the
 *                  page geometry (tab-delimited by Layout; interior empty cells are
 *                  preserved). A single-column line yields a one-element row.
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

/**
 * Per-scan OCR confidence, derived from PP-OCR's per-fragment recognition scores.
 * This is a scan-time signal: like image thumbnails it is NOT persisted and does not
 * survive text edits, so a restored or hand-edited page reports no confidence.
 *
 * @param overall  mean fragment confidence over the whole scan, 0..1.
 * @param byLine   normalized line key → that line's weakest (min) fragment
 *                 confidence, so a single doubtful cell flags the whole row.
 */
data class ScanConfidence(
    val overall: Float,
    val byLine: Map<String, Float>
) {
    companion object {
        const val LOW = 0.80f    // below this a row/scan is flagged for review
        const val GOOD = 0.92f   // at/above this a scan is considered clean

        /** Whitespace-insensitive key so a line matches across the tab/space round-trip. */
        fun keyOf(line: String): String = line.filterNot { it.isWhitespace() }
    }
}

/** Text recognized from one image together with its confidence signal. */
data class ScanResult(
    val text: String,
    val confidence: ScanConfidence
)
