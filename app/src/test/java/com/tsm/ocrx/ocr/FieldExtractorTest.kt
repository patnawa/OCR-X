package com.tsm.ocrx.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit tests for receipt/invoice field extraction. The rule that matters most is that
 * a field is either right or absent — a wrong total quietly written into a spreadsheet
 * is worse than a blank the user notices and fills in.
 */
class FieldExtractorTest {

    @Test
    fun `reads vendor, date and total from an English receipt`() {
        val text = """
            ACME HARDWARE LTD
            123 Industrial Road
            Invoice No: INV-2024-0091
            Date: 14/03/2025
            Bolt M8	2	24.00
            Nut M8	4	8.00
            Subtotal	32.00
            VAT 7%	2.24
            TOTAL	34.24
        """.trimIndent()

        val fields = FieldExtractor.extract(text)

        assertEquals("ACME HARDWARE LTD", fields.vendor)
        assertEquals("2025-03-14", fields.documentDate)
        assertEquals("INV-2024-0091", fields.documentNumber)
        assertEquals(32.00, fields.subtotal!!, 1e-6)
        assertEquals(2.24, fields.tax!!, 1e-6)
        assertEquals(34.24, fields.total!!, 1e-6)
    }

    @Test
    fun `does not mistake the subtotal for the grand total`() {
        val text = "Subtotal\t100.00\nTOTAL\t107.00"

        val fields = FieldExtractor.extract(text)

        assertEquals(107.00, fields.total!!, 1e-6)
        assertEquals(100.00, fields.subtotal!!, 1e-6)
    }

    @Test
    fun `reads Thai anchors and converts a Buddhist-era year`() {
        // 2568 BE is 2025 CE — the year printed on essentially every Thai receipt.
        val text = """
            ร้านข้าวแกงภูเก็ต
            วันที่ 14/03/2568
            หมูปิ้ง	40.00
            ภาษีมูลค่าเพิ่ม	2.80
            รวมทั้งสิ้น	42.80
        """.trimIndent()

        val fields = FieldExtractor.extract(text)

        assertEquals("2025-03-14", fields.documentDate)
        assertEquals(2.80, fields.tax!!, 1e-6)
        assertEquals(42.80, fields.total!!, 1e-6)
    }

    @Test
    fun `reads an ISO date and a textual date`() {
        assertEquals("2025-03-14", FieldExtractor.extract("Date: 2025-03-14").documentDate)
        assertEquals("2025-03-14", FieldExtractor.extract("Date: 14 Mar 2025").documentDate)
    }

    @Test
    fun `returns nothing rather than guessing on unrelated text`() {
        val fields = FieldExtractor.extract("the quick brown fox\njumped over")

        assertNull(fields.total)
        assertNull(fields.documentDate)
        assertNull(fields.tax)
    }

    @Test
    fun `empty input yields empty fields`() {
        assertEquals(true, FieldExtractor.extract("").isEmpty)
    }
}
