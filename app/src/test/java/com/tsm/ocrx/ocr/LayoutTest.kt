package com.tsm.ocrx.ocr

import org.junit.Assert.assertEquals
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
}
