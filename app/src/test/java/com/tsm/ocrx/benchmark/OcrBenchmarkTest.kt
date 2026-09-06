package com.tsm.ocrx.benchmark

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OcrBenchmarkTest {

    @Test
    fun `runs full pipeline over english fixture and extracts total exactly`() {
        val rows = OcrBenchmark.run(listOf(SyntheticReceipts.englishReceipt()))
        assertEquals(1, rows.size)
        val r = rows.single()
        assertTrue(
            "CER should be 0 on perfect text, was ${r.accuracy.cer}",
            r.accuracy.cer < 0.001
        )
        assertEquals(0.0, r.fields.totalDelta, 1e-6)
        assertTrue("vendor not extracted", r.fields.vendorOk)
        assertTrue("date not extracted", r.fields.dateOk)
    }

    @Test
    fun `runs full pipeline over thai fixture and converts Buddhist year to 2025`() {
        val rows = OcrBenchmark.run(listOf(SyntheticReceipts.thaiReceipt()))
        assertTrue(
            "Expected date to be extracted as Gregorian 2025-03-14",
            rows.single().fields.dateOk
        )
    }

    @Test
    fun `report renders from run output`() {
        val rows = OcrBenchmark.run(
            listOf(
                SyntheticReceipts.englishReceipt(),
                SyntheticReceipts.thaiReceipt()
            )
        )
        val md = BenchmarkReport.render(rows)
        assertTrue("Missing CER column", md.contains("CER"))
        assertTrue("Missing english fixture name", md.contains("english-01"))
        assertTrue("Missing thai fixture name", md.contains("thai-01"))
    }
}