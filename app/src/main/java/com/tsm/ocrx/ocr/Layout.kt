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
    val centerY: Int get() = top + height / 2
    val bottom: Int get() = top + height
}

/** Pixel bounds of a recognized line in the scanned image's coordinate space. */
data class TextBox(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

/**
 * Reading-order text plus per-line signals aligned 1:1 with [text]'s lines:
 * [lineConfidence] is each line's weakest fragment (so one doubtful cell flags the
 * row) and [lineBoxes] is where the line sits in the image, which lets the UI show
 * the original pixels behind any row.
 */
data class LayoutResult(
    val text: String,
    val lineConfidence: List<Float>,
    val lineBoxes: List<TextBox> = emptyList(),
    /**
     * How confident the column structure is: 0.95+ when ruled lines carved the
     * columns; 0.8 when gutter detection found >=2 columns from multi-fragment
     * rows; 0.5 when a single-column result was returned for a long page (often
     * a missed gutter); 1.0 for free-form single-column text where one column is
     * the correct answer. The scan panel surfaces a hint when below 0.5.
     */
    val geometryConfidence: Float = 1f
)

/**
 * Rebuilds table structure from positioned OCR fragments.
 *
 * 1. Fragments are grouped into visual rows by clustering on their vertical centres,
 *    which keeps a row together when it mixes font sizes (a tall heading next to
 *    small print shares a centre line but not a top edge).
 * 2. Column boundaries are found ONCE for the whole page. When the page draws its own
 *    ruling lines those are used directly; otherwise boundaries come from vertical
 *    "gutters" — x ranges that no row draws into. Both are alignment-agnostic (they
 *    work for left-, right- and centre-aligned columns) and only split where a real
 *    page-spanning separation exists, so free-form text (a nameplate, a paragraph)
 *    stays a single column while genuine tables get consistent columns across rows.
 * 3. Rows are emitted TAB-delimited so [OcrEngine.parse] can recover the exact grid,
 *    including interior empty cells (a row that skips a column keeps its place).
 *
 * This replaces the old per-line "join with spaces / re-split on spaces" heuristic,
 * which reconstructed each line's columns independently and so drifted out of
 * alignment row to row.
 */
object Layout {

    private const val COLUMN_DELIMITER = "\t"

    /**
     * @param columnSeparators x positions of drawn vertical rules, when the page is a
     *        ruled table. Given these, column bands come straight from the printed
     *        grid instead of being inferred from whitespace.
     */
    fun buildReadingOrder(
        items: List<PositionedText>,
        columnSeparators: List<Int> = emptyList()
    ): LayoutResult {
        val clean = items.filter { it.text.isNotBlank() }
        if (clean.isEmpty()) return LayoutResult("", emptyList(), emptyList())

        val medianHeight = clean.map { it.height }.sorted()
            .let { it[it.size / 2] }.coerceAtLeast(1)
        val rows = groupIntoRows(clean, rowTolerance = max(10, (medianHeight * 0.6f).toInt()))

        val bands = bandsFromSeparators(clean, columnSeparators)
            ?: detectColumnBands(columnEvidence(rows, clean), medianHeight)
        // One band → not a table. Emit plain reading-order lines, columns untouched.
        val singleColumn = bands.size <= 1
        val lineTexts = rows.map { row ->
            if (singleColumn) row.sortedBy { it.left }.joinToString(" ") { it.text.trim() }
            else emitRow(row, bands)
        }
        // A row's confidence is its weakest fragment: one bad cell flags the row.
        val lineConfidence = rows.map { row -> row.minOf { it.confidence } }
        val lineBoxes = rows.map { row ->
            TextBox(
                left = row.minOf { it.left },
                top = row.minOf { it.top },
                right = row.maxOf { it.right },
                bottom = row.maxOf { it.bottom }
            )
        }
        val geomConf = geometryConfidence(rows, bands, columnSeparators)
        return LayoutResult(lineTexts.joinToString("\n"), lineConfidence, lineBoxes, geomConf)
    }

    /**
     * Groups fragments into visual rows by vertical centre. Sorting by centre and
     * comparing against the running mean keeps a tall fragment and a short one on the
     * same baseline together, which comparing top edges does not.
     */
    private fun groupIntoRows(
        items: List<PositionedText>,
        rowTolerance: Int
    ): List<List<PositionedText>> {
        val sorted = items.sortedBy { it.centerY }
        val rows = mutableListOf<MutableList<PositionedText>>()
        var runningCentre = 0
        for (item in sorted) {
            val row = rows.lastOrNull()
            if (row != null && abs(item.centerY - runningCentre) <= rowTolerance) {
                row.add(item)
                runningCentre = row.sumOf { it.centerY } / row.size
            } else {
                rows.add(mutableListOf(item))
                runningCentre = item.centerY
            }
        }
        return rows
    }

    /**
     * Turns drawn vertical rules into column bands: the content between consecutive
     * rules is one column. Returns null when the rules do not actually partition the
     * content (fewer than two resulting columns hold anything), which keeps a stray
     * detected line from collapsing the table.
     */
    private fun bandsFromSeparators(
        items: List<PositionedText>,
        separators: List<Int>
    ): List<IntRange>? {
        if (separators.size < 2) return null
        val minX = items.minOf { it.left }
        val maxX = items.maxOf { it.right }
        val edges = (listOf(minX) + separators.sorted() + listOf(maxX)).distinct().sorted()

        val bands = mutableListOf<IntRange>()
        for (i in 0 until edges.size - 1) {
            val range = edges[i]..edges[i + 1]
            // Skip a band nothing sits in — usually the sliver outside the outer rules.
            if (items.any { it.centerX in range }) bands.add(range)
        }
        return if (bands.size >= 2) bands else null
    }

    /**
     * The fragments that should decide where the columns are: those in rows holding
     * more than one fragment.
     *
     * A gutter is destroyed by any single line that spans it, and documents are full
     * of such lines — a title, an address, "Invoice No: INV-2024-0091" running across
     * the width above the table. Letting those vote collapses the table underneath
     * them into one column. A row with several fragments, by contrast, is direct
     * evidence of a row *of a table*, and only those rows know where its columns are.
     *
     * Falls back to every fragment when there is no such evidence, which is the
     * free-form case that should stay a single column anyway.
     */
    private fun columnEvidence(
        rows: List<List<PositionedText>>,
        all: List<PositionedText>
    ): List<PositionedText> {
        val multiFragmentRows = rows.filter { it.size > 1 }
        return if (multiFragmentRows.size >= 2) multiFragmentRows.flatten() else all
    }

    /**
     * Finds column bands: the runs of x that contain content, separated by gutters.
     * A gutter is a run of empty x at least [MIN_GUTTER_HEIGHT_FACTOR]× the median text
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

    /**
     * Maps column-band evidence to a 0..1 confidence:
     *  - ruled lines carved out >=2 columns → 0.95 (explicit geometry)
     *  - gutter detection found >=2 columns on rows with >1 fragment → 0.8
     *  - gutter detection found >=2 columns on weak evidence → 0.5
     *  - one band on a long page → 0.7 (uncertain; could be free-form or a missed
     *    gutter — geometric evidence alone can't tell, so we don't alarm)
     *  - short page, single band → 1.0 (free-form is the right answer)
     *
     * The scan panel surfaces a hint at <0.5, so this signal only fires when the
     * geometry is genuinely suspect. Calibrate thresholds with real cases later.
     */
    private fun geometryConfidence(
        rows: List<List<PositionedText>>,
        bands: List<IntRange>,
        columnSeparators: List<Int>
    ): Float {
        val ruledWorked = bands.size >= 2 && columnSeparators.isNotEmpty()
        val multiFragmentRows = rows.count { it.size > 1 }
        return when {
            ruledWorked -> 0.95f
            bands.size >= 2 && multiFragmentRows >= 2 -> 0.8f
            bands.size >= 2 -> 0.5f
            rows.size > 5 -> 0.7f                                 // long single band = uncertain
            else -> 1.0f                                         // short free-form
        }
    }
}
