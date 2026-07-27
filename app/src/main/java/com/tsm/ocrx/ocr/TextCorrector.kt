package com.tsm.ocrx.ocr

/**
 * Repairs the recognition errors that a *typed* view of the table can prove are
 * errors, and cross-checks the arithmetic of numeric columns.
 *
 * The recogniser confuses glyph shapes: `O`/`0`, `l`/`1`, `S`/`5`, `B`/`8`. Fixing
 * those blind would wreck ordinary words, so the repair is narrowly scoped:
 *
 * - only in columns that are already **mostly numeric**, so a text column is never touched;
 * - only in cells that **fail to parse as a number as they stand**, so a correct value
 *   is never rewritten;
 * - only when substitution **turns the cell into a valid number**, so a genuinely
 *   alphabetic cell that happens to sit in a numeric column is left alone.
 *
 * Every change is reported so the UI can show what was altered rather than silently
 * rewriting the user's data.
 */
object TextCorrector {

    /** One applied repair, for display and for undo. */
    data class Correction(
        val row: Int,
        val column: Int,
        val before: String,
        val after: String
    )

    /**
     * Arithmetic check of a numeric column: does a row labelled as the total agree
     * with the sum of the other rows? A mismatch is the strongest available signal
     * that a digit was misread somewhere in the column.
     */
    data class SumCheck(
        val column: Int,
        val summedRows: Double,
        val declaredTotal: Double,
        val matches: Boolean
    )

    data class Result(
        val text: String,
        val corrections: List<Correction>,
        val sumChecks: List<SumCheck>
    ) {
        val hasChanges: Boolean get() = corrections.isNotEmpty()
        /** Columns whose declared total disagrees with the rows above it. */
        val failedSums: List<SumCheck> get() = sumChecks.filterNot { it.matches }
    }

    /** Glyphs the recogniser substitutes for digits, applied only in numeric cells. */
    private val CONFUSABLES = mapOf(
        'O' to '0', 'o' to '0', 'D' to '0', 'Q' to '0',
        'l' to '1', 'I' to '1', 'i' to '1', '|' to '1', '!' to '1',
        'Z' to '2', 'z' to '2',
        'A' to '4',
        'S' to '5', 's' to '5',
        'G' to '6', 'b' to '6',
        'B' to '8',
        'g' to '9', 'q' to '9'
    )

    /** Words that mark a row as the column total, in English and Thai. */
    private val TOTAL_LABELS = listOf(
        "total", "grand total", "amount due", "balance due", "sum",
        "รวม", "รวมทั้งสิ้น", "ยอดรวม", "จำนวนเงินรวม"
    )

    /** Share of a column's cells that must read as numbers for it to count as numeric. */
    private const val NUMERIC_COLUMN_RATIO = 0.6
    /** Tolerance for the sum check, absorbing ordinary rounding. */
    private const val SUM_TOLERANCE = 0.02

    /**
     * Corrects [text] (the tab-delimited grid produced by [Layout]) and returns the
     * rewritten text alongside what changed. Blank lines and the row/column shape are
     * preserved exactly, so the result is a drop-in replacement.
     */
    fun correct(text: String): Result {
        if (text.isBlank()) return Result(text, emptyList(), emptyList())

        val lines = text.split('\n')
        // Work on the grid but remember which source line each row came from, so
        // blank lines and spacing survive untouched.
        val rowIndices = lines.indices.filter { lines[it].isNotBlank() }
        val grid = rowIndices.map { splitCells(lines[it]) }
        if (grid.isEmpty()) return Result(text, emptyList(), emptyList())

        val columnCount = grid.maxOf { it.size }
        val corrections = mutableListOf<Correction>()
        val repaired = grid.map { it.toMutableList() }

        for (column in 0 until columnCount) {
            val cells = repaired.mapNotNull { it.getOrNull(column) }.filter { it.isNotBlank() }
            if (cells.size < 2) continue
            if (!isNumericColumn(cells)) continue

            for (row in repaired.indices) {
                val cell = repaired[row].getOrNull(column) ?: continue
                if (cell.isBlank() || numericValue(cell) != null) continue
                val fixed = applyConfusables(cell)
                if (fixed != cell && numericValue(fixed) != null) {
                    corrections.add(Correction(row, column, cell, fixed))
                    repaired[row][column] = fixed
                }
            }
        }

        val sumChecks = checkColumnSums(repaired, columnCount)

        val rebuilt = lines.toMutableList()
        rowIndices.forEachIndexed { index, lineIndex ->
            // Only rewrite lines we actually touched, so a line that never had tabs
            // does not acquire them.
            if (repaired[index] != grid[index]) {
                rebuilt[lineIndex] = repaired[index].joinToString("\t")
            }
        }
        return Result(rebuilt.joinToString("\n"), corrections, sumChecks)
    }

    /**
     * Decides whether a column holds numbers.
     *
     * Counting only the cells that parse *as they stand* is circular: a column with
     * two misreads out of four is exactly the column that needs repairing, yet those
     * misreads are what would disqualify it. So the share is measured *after*
     * substitution — and anchored by requiring at least one cell that is already a
     * clean number.
     *
     * That anchor is what keeps text columns safe. `SOLO`, `BOB`, `LILO` all become
     * numbers under substitution (5010, 808, 1110), so the ratio alone would happily
     * shred them; but not one of them parses beforehand, so the column is rejected.
     */
    private fun isNumericColumn(cells: List<String>): Boolean {
        val cleanlyNumeric = cells.count { numericValue(it) != null }
        if (cleanlyNumeric == 0) return false
        val numericAfterRepair = cells.count { numericValue(applyConfusables(it)) != null }
        return numericAfterRepair.toDouble() / cells.size >= NUMERIC_COLUMN_RATIO
    }

    private fun splitCells(line: String): List<String> =
        if (line.contains('\t')) line.split('\t') else listOf(line)

    private fun applyConfusables(cell: String): String =
        cell.map { CONFUSABLES[it] ?: it }.joinToString("")

    /**
     * Parses a cell as a number, tolerating currency symbols, sign, thousands
     * separators and accounting parentheses. Returns null when the cell is not a
     * number, which is what makes the correction pass safe.
     */
    fun numericValue(cell: String): Double? {
        var s = cell.trim()
        if (s.isEmpty()) return null
        var negative = false
        if (s.startsWith("(") && s.endsWith(")")) {
            negative = true
            s = s.substring(1, s.length - 1).trim()
        }
        // Strip currency symbols and common currency words from either end.
        s = s.trim { it.isWhitespace() || Character.getType(it) == Character.CURRENCY_SYMBOL.toInt() }
        s = s.removePrefix("THB").removePrefix("USD").removeSuffix("THB").removeSuffix("USD").trim()
        s = s.removeSuffix("บาท").trim()
        if (s.startsWith("-")) { negative = !negative; s = s.substring(1).trim() }
        else if (s.startsWith("+")) s = s.substring(1).trim()
        if (s.isEmpty() || s.none { it.isDigit() }) return null
        if (!s.all { it.isDigit() || it == ',' || it == '.' || it == ' ' }) return null

        // Whichever separator appears last is the decimal point; the other groups
        // thousands. "1.234,56" and "1,234.56" both mean the same amount.
        val lastComma = s.lastIndexOf(',')
        val lastDot = s.lastIndexOf('.')
        val normalized = when {
            lastComma >= 0 && lastDot >= 0 ->
                if (lastComma > lastDot) s.replace(".", "").replace(',', '.')
                else s.replace(",", "")
            lastComma >= 0 -> {
                // A lone comma is a decimal point only when it is not grouping digits.
                val tail = s.substring(lastComma + 1)
                if (tail.length == 3 && s.count { it == ',' } >= 1 && s.length > 4) s.replace(",", "")
                else s.replace(',', '.')
            }
            else -> s
        }.replace(" ", "")

        val value = normalized.toDoubleOrNull() ?: return null
        return if (negative) -value else value
    }

    /**
     * For each numeric column, finds a row labelled "total" and compares it with the
     * sum of the rows above it.
     */
    private fun checkColumnSums(grid: List<List<String>>, columnCount: Int): List<SumCheck> {
        val totalRow = grid.indexOfFirst { row ->
            row.any { cell ->
                val text = cell.trim().lowercase()
                text.isNotEmpty() && TOTAL_LABELS.any { text == it || text.startsWith("$it ") || text.contains(it) } &&
                    numericValue(cell) == null
            }
        }
        if (totalRow <= 0) return emptyList()

        val checks = mutableListOf<SumCheck>()
        for (column in 0 until columnCount) {
            val declared = grid[totalRow].getOrNull(column)?.let { numericValue(it) } ?: continue
            val above = (0 until totalRow).mapNotNull { grid[it].getOrNull(column)?.let(::numericValue) }
            if (above.size < 2) continue
            val summed = above.sum()
            checks.add(
                SumCheck(
                    column = column,
                    summedRows = summed,
                    declaredTotal = declared,
                    matches = kotlin.math.abs(summed - declared) <= SUM_TOLERANCE
                )
            )
        }
        return checks
    }
}
