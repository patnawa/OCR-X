package com.tsm.ocrx.benchmark

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SyntheticReceiptsTest {

    @Test
    fun `generated english receipt matches its known truth`() {
        val r = SyntheticReceipts.englishReceipt()
        assertEquals("ACME HARDWARE LTD", r.vendor)
        assertEquals("2025-03-14", r.date)
        assertEquals(34.24, r.total!!, 1e-6)
        assertTrue(r.lines.size >= 6)
        assertTrue(r.lines.any { it.contains("TOTAL") })
    }

    @Test
    fun `generated thai receipt has a Buddhist-era year in the blob and Gregorian in truth`() {
        val r = SyntheticReceipts.thaiReceipt()
        assertEquals("2025-03-14", r.date)
        assertTrue("OCR blob should carry BE year 2568", r.ocrBlob.contains("2568"))
    }
}