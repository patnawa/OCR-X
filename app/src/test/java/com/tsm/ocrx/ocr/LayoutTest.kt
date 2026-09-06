package com.tsm.ocrx.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the geometric table reconstruction. These pin the behaviour that
 * makes the export trustworthy: columns are found once from page geometry and stay
 * aligned across every row, interior empty cells are preserved, and free-form text
 * is never shredded into false columns.
 */
class LayoutTest {

    private fun box(
        left: Int, top: Int, right: Int, text: String, height: Int = 20, confidence: Float = 1f
    ) = PositionedText(
        left = left, top = top, right = right, height = height, text = text, confidence = confidence
    )

    @Test
    fun `aligns three columns across rows and preserves an interior empty cell`() {
        // Col A: x 0..80   Col B: x 140..190   Col C: x 300..380  (wide gutters between)
        val items = listOf(
            box(0, 0, 80, "Item"), box(140, 0, 180, "Qty"), box(300, 0, 380, "Price"),
            box(0, 30, 70, "Bolt"), /* no B cell */          box(300, 30, 350, "5.00"),
            box(0, 60, 60, "Nut"), box(140, 60, 190, "200"), box(300, 60, 350, "1.50")
        )

        val text = Layout.buildReadingOrder(items).text

        assertEquals(
            listOf("Item\tQty\tPrice", "Bolt\t\t5.00", "Nut\t200\t1.50"),
            text.lines()
        )

        // Round-trips through the parser to a rectangular grid with the empty cell kept.
        val rows = OcrEngine.parse(text).rows
        assertEquals(listOf("Bolt", "", "5.00"), rows[1])
    }

    @Test
    fun `free-form text stays a single column with no tabs`() {
        val items = listOf(
            box(0, 0, 100, "ACME MOTOR"),
            box(0, 30, 130, "SERIAL 12345")
        )

        val text = Layout.buildReadingOrder(items).text

        assertEquals(listOf("ACME MOTOR", "SERIAL 12345"), text.lines())
        assert(!text.contains('\t')) { "single-column text must not contain tabs" }
    }

    @Test
    fun `fragments closer than a gutter merge into one cell`() {
        // "PART" and "NO" are only 10px apart — an inter-word space, not a column gap.
        val items = listOf(
            box(0, 0, 40, "PART"), box(50, 0, 80, "NO"), box(300, 0, 360, "VALUE")
        )

        assertEquals("PART NO\tVALUE", Layout.buildReadingOrder(items).text)
    }

    @Test
    fun `a wide header line does not collapse the columns beneath it`() {
        // "Invoice No: INV-2024-0091" runs from x=0 to x=430, straight across the
        // gutter between the item and qty columns. Letting it vote on where the
        // columns are would merge them; only the multi-fragment rows should decide.
        val items = listOf(
            box(0, 0, 430, "Invoice No: INV-2024-0091"),
            box(0, 40, 90, "Bolt"), box(440, 40, 470, "2"), box(700, 40, 780, "24.00"),
            box(0, 80, 90, "Nut"), box(440, 80, 470, "4"), box(700, 80, 780, "8.00")
        )

        val rows = OcrEngine.parse(Layout.buildReadingOrder(items).text).rows

        assertEquals(listOf("Bolt", "2", "24.00"), rows[1])
        assertEquals(listOf("Nut", "4", "8.00"), rows[2])
    }

    @Test
    fun `drawn ruling lines define the columns`() {
        // Two cells whose whitespace gap is too narrow to be read as a gutter, but a
        // printed rule sits between them at x=100.
        val items = listOf(
            box(10, 0, 90, "PART"), box(108, 0, 190, "QTY"),
            box(10, 30, 90, "Bolt"), box(108, 30, 190, "12")
        )

        val text = Layout.buildReadingOrder(items, columnSeparators = listOf(0, 100, 200)).text

        assertEquals(listOf("PART\tQTY", "Bolt\t12"), text.lines())
    }

    @Test
    fun `a single stray rule does not split the page`() {
        // One separator cannot partition anything, so the gutter heuristic must run.
        val items = listOf(box(0, 0, 100, "ACME MOTOR"))

        val text = Layout.buildReadingOrder(items, columnSeparators = listOf(50)).text

        assertEquals("ACME MOTOR", text)
    }

    @Test
    fun `a row mixing font sizes stays one row`() {
        // A 40px-tall heading and a 16px label share a baseline but not a top edge:
        // grouping by top would split them, grouping by vertical centre keeps them.
        val items = listOf(
            box(0, 0, 80, "TOTAL", height = 40),
            box(300, 12, 380, "42.00", height = 16)
        )

        val result = Layout.buildReadingOrder(items)

        assertEquals(1, result.text.lines().size)
        assertEquals("TOTAL\t42.00", result.text)
    }

    @Test
    fun `reports the pixel bounds of every emitted line`() {
        val items = listOf(
            box(10, 0, 80, "Item"), box(300, 0, 380, "Price"),
            box(12, 30, 70, "Bolt"), box(300, 30, 350, "5.00")
        )

        val result = Layout.buildReadingOrder(items)

        assertEquals(2, result.lineBoxes.size)
        assertEquals(TextBox(left = 10, top = 0, right = 380, bottom = 20), result.lineBoxes[0])
        assertEquals(TextBox(left = 12, top = 30, right = 350, bottom = 50), result.lineBoxes[1])
    }

    @Test
    fun `line confidence is the weakest fragment in the row`() {
        val items = listOf(
            box(0, 0, 80, "Good", confidence = 0.99f), box(300, 0, 380, "Bad", confidence = 0.42f),
            box(0, 30, 80, "Fine", confidence = 0.97f), box(300, 30, 380, "OK", confidence = 0.95f)
        )

        val result = Layout.buildReadingOrder(items)

        assertEquals(2, result.lineConfidence.size)
        assertEquals(0.42f, result.lineConfidence[0], 1e-4f)  // dragged down by the bad cell
        assertEquals(0.95f, result.lineConfidence[1], 1e-4f)
    }

    @Test
    fun `geometry confidence is high when ruled lines produce columns`() {
        val items = listOf(
            box(10,  10,  90, "A"), box(120, 10, 180, "B"),
            box(10,  40,  90, "C"), box(120, 40, 180, "D")
        )
        // Two ruled vertical lines partition the page into three columns
        val r = Layout.buildReadingOrder(items, columnSeparators = listOf(100, 200))
        assertTrue("Expected >=0.9, was ${r.geometryConfidence}", r.geometryConfidence >= 0.9f)
    }

    @Test
    fun `geometry confidence is at least neutral for free-form text`() {
        // 7 short single-fragment lines = paragraph-like, no columns expected.
        // The signal can't reliably distinguish a missed gutter here, so we
        // stay neutral (>=0.7) rather than alarm on every long page.
        val items = (0..6).map { i -> box(10, i * 30, 150, "line $i") }
        val r = Layout.buildReadingOrder(items)
        assertTrue("Expected >=0.7, was ${r.geometryConfidence}", r.geometryConfidence >= 0.7f)
    }
}
