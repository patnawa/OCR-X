package com.tsm.ocrx.ocr

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** A recognized text fragment with its bounding box, used to rebuild table structure. */
data class PositionedText(
    val left: Int,
    val top: Int,
    val right: Int,
    val height: Int,
    val text: String,
    val confidence: Float = 1f
) {
    val centerX: Int get() = (left + right) / 2
}

/**
 * Reading-order text plus a per-line confidence aligned 1:1 with [text]'s lines
 * (each line's weakest fragment, so one doubtful cell flags the whole row).
 */
data class LayoutResult(
    val text: String,
    val lineConfidence: List<Float>
)

/**
 * Rebuilds table structure from positioned OCR fragments.
 *
 * 1. Fragments are grouped into visual rows (tops within a tolerance derived from
 *    the median text height).
 * 2. Column boundaries are found ONCE for the whole page by locating vertical
 *    "gutters" — x ranges that no row draws into. This is alignment-agnostic (works
 *    for left-, right- and centre-aligned columns) and only splits where a real
 *    page-spanning gap exists, so free-form text (a nameplate, a paragraph) stays a
 *    single column while genuine tables get consistent columns across every row.
 * 3. Rows are emitted TAB-delimited so [OcrEngine.parse] can recover the exact grid,
 *    including interior empty cells (a row that skips a column keeps its place).
 *
 * This replaces the old per-line "join with spaces / re-split on spaces" heuristic,
 * which reconstructed each line's columns independently and so drifted out of
 * alignment row to row.
 */
object Layout {

    private const val COLUMN_DELIMITER = "\t"

    fun buildReadingOrder(items: List<PositionedText>): LayoutResult {
        val clean = items.filter { it.text.isNotBlank() }
        if (clean.isEmpty()) return LayoutResult("", emptyList())

        val medianHeight = clean.map { it.height }.sorted()
            .let { it[it.size / 2] }.coerceAtLeast(1)
        val rows = groupIntoRows(clean, rowTolerance = max(10, (medianHeight * 0.6f).toInt()))

        val bands = detectColumnBands(clean, medianHeight)
        // One band → not a table. Emit plain reading-order lines, columns untouched.
        val singleColumn = bands.size <= 1
        val lineTexts = rows.map { row ->
            if (singleColumn) row.sortedBy { it.left }.joinToString(" ") { it.text.trim() }
            else emitRow(row, bands)
        }
        // A row's confidence is its weakest fragment: one bad cell flags the row.
        val lineConfidence = rows.map { row -> row.minOf { it.confidence } }
        return LayoutResult(lineTexts.joinToString("\n"), lineConfidence)
    }

    /** Groups fragments whose tops fall within [rowTolerance] of the row's first item. */
    private fun groupIntoRows(
        items: List<PositionedText>,
        rowTolerance: Int
    ): List<List<PositionedText>> {
        val sorted = items.sortedBy { it.top }
        val rows = mutableListOf<MutableList<PositionedText>>()
        for (item in sorted) {
            val row = rows.lastOrNull()
            if (row != null && abs(item.top - row.first().top) <= rowTolerance) row.add(item)
            else rows.add(mutableListOf(item))
        }
        return rows
    }

    /**
     * Finds column bands: the runs of x that contain content, separated by gutters.
     * A gutter is a run of empty x at least [minGutterHeightFactor]× the median text
     * height wide — wide enough that inter-word spacing inside a cell never splits it.
     */
    private fun detectColumnBands(
        items: List<PositionedText>,
        medianHeight: Int
    ): List<IntRange> {
        val minX = items.minOf { it.left }
        val maxX = items.maxOf { it.right }
        if (maxX <= minX) return listOf(minX..maxX)

        // Coverage histogram at ~half-text-height resolution.
        val bin = max(2, medianHeight / 2)
        val binCount = (maxX - minX) / bin + 1
        val coverage = IntArray(binCount)
        for (item in items) {
            val a = ((item.left - minX) / bin).coerceIn(0, binCount - 1)
            val b = ((item.right - minX) / bin).coerceIn(0, binCount - 1)
            for (i in a..b) coverage[i]++
        }

        val minGutterBins = max(1, (medianHeight * MIN_GUTTER_HEIGHT_FACTOR / bin).toInt())

        val bands = mutableListOf<IntRange>()
        var i = 0
        while (i < binCount) {
            if (coverage[i] == 0) { i++; continue }   // skip leading/inter-band gutter
            val start = i
            var end = i
            var gutterRun = 0
            while (i < binCount) {
                if (coverage[i] == 0) {
                    gutterRun++
                    if (gutterRun >= minGutterBins) break   // real column separator
                } else {
                    gutterRun = 0
                    end = i
                }
                i++
            }
            bands.add((minX + start * bin)..(minX + (end + 1) * bin))
        }
        return bands.ifEmpty { listOf(minX..maxX) }
    }

    /** Places each fragment in its column band and renders the row tab-delimited. */
    private fun emitRow(row: List<PositionedText>, bands: List<IntRange>): String {
        val cells = Array(bands.size) { StringBuilder() }
        for (item in row.sortedBy { it.left }) {
            val idx = bandIndexFor(item, bands)
            if (cells[idx].isNotEmpty()) cells[idx].append(' ')
            cells[idx].append(item.text.trim())
        }
        return cells.joinToString(COLUMN_DELIMITER) { it.toString() }
    }

    /** The band containing the fragment's centre, else the one it overlaps most. */
    private fun bandIndexFor(item: PositionedText, bands: List<IntRange>): Int {
        val direct = bands.indexOfFirst { item.centerX in it }
        if (direct >= 0) return direct
        var best = 0
        var bestOverlap = Int.MIN_VALUE
        bands.forEachIndexed { i, b ->
            val overlap = min(item.right, b.last) - max(item.left, b.first)
            if (overlap > bestOverlap) { bestOverlap = overlap; best = i }
        }
        return best
    }

    private const val MIN_GUTTER_HEIGHT_FACTOR = 1.2f
}
