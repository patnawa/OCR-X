package com.tsm.ocrx.ocr

/**
 * The business answer a scanned receipt or invoice is actually for: who issued it,
 * when, and how much. A text blob is not that answer — a row in a ledger is.
 *
 * Extraction is offline, rule-based and deliberately conservative: it reads anchor
 * words ("TOTAL", "รวมทั้งสิ้น", "VAT", "วันที่") and takes the amount or date near
 * them. Anything it cannot find with confidence stays null rather than being guessed,
 * because a wrong total silently entered into a spreadsheet is far worse than a blank
 * one the user fills in.
 */
data class ExtractedFields(
    val vendor: String? = null,
    val documentDate: String? = null,
    val documentNumber: String? = null,
    val subtotal: Double? = null,
    val tax: Double? = null,
    val total: Double? = null
) {
    val isEmpty: Boolean
        get() = vendor == null && documentDate == null && documentNumber == null &&
            subtotal == null && tax == null && total == null

    /** Field name → displayable value, in reading order, skipping what was not found. */
    fun asPairs(): List<Pair<String, String>> = buildList {
        vendor?.let { add("Vendor" to it) }
        documentDate?.let { add("Date" to it) }
        documentNumber?.let { add("Document no." to it) }
        subtotal?.let { add("Subtotal" to formatAmount(it)) }
        tax?.let { add("Tax / VAT" to formatAmount(it)) }
        total?.let { add("Total" to formatAmount(it)) }
    }

    private fun formatAmount(value: Double): String =
        if (value == value.toLong().toDouble()) value.toLong().toString()
        else String.format("%.2f", value)
}

object FieldExtractor {

    // Anchors are matched longest-first so "grand total" wins over "total", and
    // "subtotal" is never mistaken for the grand total.
    private val TOTAL_ANCHORS = listOf(
        "grand total", "amount due", "balance due", "total due", "net total", "total",
        "รวมทั้งสิ้น", "ยอดรวมสุทธิ", "จำนวนเงินรวม", "ยอดรวม", "รวมเงิน", "รวม"
    )
    private val SUBTOTAL_ANCHORS = listOf(
        "subtotal", "sub total", "sub-total", "ยอดรวมย่อย", "รวมย่อย", "ราคารวม"
    )
    private val TAX_ANCHORS = listOf(
        "vat", "tax", "gst", "sales tax", "ภาษีมูลค่าเพิ่ม", "ภาษี"
    )
    private val DATE_ANCHORS = listOf("date", "issued", "วันที่", "ลงวันที่")
    private val NUMBER_ANCHORS = listOf(
        "invoice no", "invoice number", "invoice #", "invoice", "receipt no", "receipt #",
        "receipt", "bill no", "ref no", "no.", "เลขที่", "ใบเสร็จเลขที่", "เลขที่ใบกำกับภาษี"
    )

    /** dd/mm/yyyy, yyyy-mm-dd and dd MMM yyyy, with 2- or 4-digit years. */
    private val NUMERIC_DATE = Regex("""\b(\d{1,4})[/\-.](\d{1,2})[/\-.](\d{2,4})\b""")
    private val TEXT_DATE = Regex(
        """\b(\d{1,2})\s+([A-Za-z]{3,9})\.?\s+(\d{2,4})\b""",
        RegexOption.IGNORE_CASE
    )
    private val MONTHS = listOf(
        "jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec"
    )
    private val DOCUMENT_NUMBER = Regex("""[A-Za-z0-9][A-Za-z0-9\-/]{3,}""")

    /**
     * Extracts fields from recognized text. [text] may be tab-delimited (the usual
     * table output) or plain lines; cells are flattened per line so an anchor in one
     * column can pick up the amount in another.
     */
    fun extract(text: String): ExtractedFields {
        val lines = text.split('\n').map { it.trim() }.filter { it.isNotBlank() }
        if (lines.isEmpty()) return ExtractedFields()

        return ExtractedFields(
            vendor = findVendor(lines),
            documentDate = findDate(lines),
            documentNumber = findDocumentNumber(lines),
            subtotal = findAmount(lines, SUBTOTAL_ANCHORS, excluding = TOTAL_ANCHORS),
            tax = findAmount(lines, TAX_ANCHORS, excluding = emptyList()),
            total = findTotal(lines)
        )
    }

    /**
     * The issuer name: the first line near the top that reads like a name rather than
     * an address, a date or an amount. Receipts put it in the header, so scanning
     * beyond the first few lines finds line items instead.
     */
    private fun findVendor(lines: List<String>): String? {
        for (line in lines.take(5)) {
            val candidate = line.replace('\t', ' ').trim()
            if (candidate.length !in 3..60) continue
            val letters = candidate.count { it.isLetter() }
            val digits = candidate.count { it.isDigit() }
            if (letters < 3 || digits > letters) continue
            if (TextCorrector.numericValue(candidate) != null) continue
            if (NUMERIC_DATE.containsMatchIn(candidate)) continue
            val lower = candidate.lowercase()
            if (anchorsIn(lower, TOTAL_ANCHORS + TAX_ANCHORS + DATE_ANCHORS) != null) continue
            return candidate
        }
        return null
    }

    /**
     * The document date, preferring a line that names one. Thai receipts commonly
     * print a Buddhist-era year (2568), which is converted so the exported value is a
     * date a spreadsheet will accept.
     */
    private fun findDate(lines: List<String>): String? {
        val anchored = lines.filter { anchorsIn(it.lowercase(), DATE_ANCHORS) != null }
        for (line in anchored + lines) {
            parseDate(line)?.let { return it }
        }
        return null
    }

    private fun parseDate(line: String): String? {
        NUMERIC_DATE.find(line)?.let { match ->
            val (a, b, c) = match.destructured
            val first = a.toInt()
            val second = b.toInt()
            val third = c.toInt()
            // A 4-digit leading group is an ISO date; otherwise it is day-first, which
            // is the convention everywhere this app is used.
            return if (a.length == 4) isoDate(first, second, third)
            else isoDate(normalizeYear(third), second, first)
        }
        TEXT_DATE.find(line)?.let { match ->
            val (day, monthName, year) = match.destructured
            val month = MONTHS.indexOf(monthName.lowercase().take(3)) + 1
            if (month > 0) return isoDate(normalizeYear(year.toInt()), month, day.toInt())
        }
        return null
    }

    /** Expands a 2-digit year and converts a Buddhist-era year to Gregorian. */
    private fun normalizeYear(year: Int): Int = when {
        year > 2400 -> year - 543   // Buddhist era, as printed across Thailand
        year in 100..999 -> year
        year < 100 -> 2000 + year
        else -> year
    }

    private fun isoDate(year: Int, month: Int, day: Int): String? {
        if (month !in 1..12 || day !in 1..31 || year !in 1900..2200) return null
        return "%04d-%02d-%02d".format(year, month, day)
    }

    private fun findDocumentNumber(lines: List<String>): String? {
        for (line in lines) {
            val lower = line.lowercase()
            val anchor = anchorsIn(lower, NUMBER_ANCHORS) ?: continue
            val after = line.substring(minOf(lower.indexOf(anchor) + anchor.length, line.length))
                .trimStart(':', '#', ' ', '\t', '-')
            val candidate = DOCUMENT_NUMBER.find(after)?.value ?: continue
            // A bare amount after "no." is a misread, not a document number.
            if (TextCorrector.numericValue(candidate) != null && candidate.length < 6) continue
            return candidate
        }
        return null
    }

    /**
     * The grand total. Searched from the bottom up because a receipt's final total is
     * the last amount printed, and read with subtotal/tax anchors excluded so a
     * "Total before VAT" line never wins.
     */
    private fun findTotal(lines: List<String>): Double? =
        findAmount(lines.asReversed(), TOTAL_ANCHORS, excluding = SUBTOTAL_ANCHORS + TAX_ANCHORS)

    /**
     * The amount on the first line carrying one of [anchors] and none of [excluding].
     * The rightmost number on the line wins, which is where the value sits in every
     * receipt layout.
     */
    private fun findAmount(
        lines: List<String>,
        anchors: List<String>,
        excluding: List<String>
    ): Double? {
        for (line in lines) {
            val lower = line.lowercase()
            val anchor = anchorsIn(lower, anchors) ?: continue
            val excluded = anchorsIn(lower, excluding)
            // "subtotal" contains "total": only skip when the excluded anchor is a
            // genuinely different, longer match.
            if (excluded != null && excluded.length > anchor.length) continue
            amountOnLine(line)?.let { return it }
        }
        return null
    }

    /** Rightmost parseable number on a line, across tab- and space-separated cells. */
    private fun amountOnLine(line: String): Double? =
        line.split('\t', ' ')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .asReversed()
            .firstNotNullOfOrNull { TextCorrector.numericValue(it) }

    /** The longest anchor present in [lower], or null. */
    private fun anchorsIn(lower: String, anchors: List<String>): String? =
        anchors.filter { lower.contains(it) }.maxByOrNull { it.length }
}
