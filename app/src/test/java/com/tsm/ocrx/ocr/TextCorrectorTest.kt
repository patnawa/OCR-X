package com.tsm.ocrx.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the post-OCR correction pass. These pin the property that makes the
 * pass safe to run automatically: it repairs cells that are provably numbers misread
 * as letters, and touches nothing else.
 */
class TextCorrectorTest {

    @Test
    fun `repairs letters misread as digits inside a numeric column`() {
        // Column 1 is clearly numeric; "1O.50" and "S.00" are misreads of 10.50 and 5.00.
        val text = listOf(
            "Bolt\t12.00",
            "Nut\t1O.50",
            "Washer\tS.00",
            "Screw\t3.25"
        ).joinToString("\n")

        val result = TextCorrector.correct(text)

        assertEquals(
            listOf("Bolt\t12.00", "Nut\t10.50", "Washer\t5.00", "Screw\t3.25"),
            result.text.lines()
        )
        assertEquals(2, result.corrections.size)
    }

    @Test
    fun `leaves text columns completely alone`() {
        // "SOLO" and "BOB" would be mangled to 5010/808 by a blind substitution.
        val text = listOf(
            "SOLO\t1.00",
            "BOB\t2.00",
            "LILO\t3.00"
        ).joinToString("\n")

        val result = TextCorrector.correct(text)

        assertEquals(text, result.text)
        assertTrue(result.corrections.isEmpty())
    }

    @Test
    fun `a column of single letters is never treated as numeric`() {
        // A, B and D all map to digits under substitution. Without the "at least one
        // cell already parses" anchor this column would be rewritten to 4, 8, C, 0.
        val text = listOf("A\t1.00", "B\t2.00", "C\t3.00", "D\t4.00").joinToString("\n")

        val result = TextCorrector.correct(text)

        assertEquals(text, result.text)
        assertTrue(result.corrections.isEmpty())
    }

    @Test
    fun `leaves an alphabetic cell inside a numeric column alone`() {
        // "N/A" cannot become a number by substitution, so it must survive untouched.
        val text = listOf("A\t1.00", "B\tN/A", "C\t3.00", "D\t4.00").joinToString("\n")

        val result = TextCorrector.correct(text)

        assertEquals(text, result.text)
        assertTrue(result.corrections.isEmpty())
    }

    @Test
    fun `flags a column whose declared total disagrees with its rows`() {
        val text = listOf(
            "Bolt\t10.00",
            "Nut\t5.00",
            "Washer\t2.50",
            "TOTAL\t99.00"
        ).joinToString("\n")

        val result = TextCorrector.correct(text)

        assertEquals(1, result.failedSums.size)
        assertEquals(17.5, result.failedSums.first().summedRows, 1e-6)
        assertEquals(99.0, result.failedSums.first().declaredTotal, 1e-6)
    }

    @Test
    fun `accepts a column whose total adds up`() {
        val text = listOf(
            "Bolt\t10.00",
            "Nut\t5.00",
            "Washer\t2.50",
            "TOTAL\t17.50"
        ).joinToString("\n")

        val result = TextCorrector.correct(text)

        assertTrue(result.failedSums.isEmpty())
        assertEquals(1, result.sumChecks.size)
    }

    @Test
    fun `parses amounts in both thousands conventions`() {
        assertEquals(1234.56, TextCorrector.numericValue("1,234.56")!!, 1e-6)
        assertEquals(1234.56, TextCorrector.numericValue("1.234,56")!!, 1e-6)
        assertEquals(1234.0, TextCorrector.numericValue("1,234")!!, 1e-6)
        assertEquals(0.5, TextCorrector.numericValue("0,5")!!, 1e-6)
        assertEquals(-42.0, TextCorrector.numericValue("(42)")!!, 1e-6)
        assertEquals(99.0, TextCorrector.numericValue("฿99")!!, 1e-6)
        assertEquals(250.0, TextCorrector.numericValue("250 บาท")!!, 1e-6)
    }

    @Test
    fun `rejects things that are not amounts`() {
        assertNull(TextCorrector.numericValue("N/A"))
        assertNull(TextCorrector.numericValue(""))
        assertNull(TextCorrector.numericValue("ACME LTD"))
        assertNull(TextCorrector.numericValue("12A34"))
    }

    @Test
    fun `preserves untouched lines exactly, including tab-free ones`() {
        val text = "ACME MOTOR WORKS\nBolt\t1O.00\nNut\t5.00\nScrew\t2.00"

        val result = TextCorrector.correct(text)

        // The heading had no tabs and must not gain any.
        assertEquals("ACME MOTOR WORKS", result.text.lines().first())
        assertEquals("Bolt\t10.00", result.text.lines()[1])
    }
}
