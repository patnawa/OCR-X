package com.tsm.ocrx.benchmark

/**
 * A synthetic receipt: the truth (vendor/date/total), the lines as the OCR pipeline
 * would emit them, and the raw blob the pipeline actually gets fed. The benchmark
 * runs the pipeline against the blob and compares its structured output to the
 * truth — no real image required, no device required, runs on every CI build.
 */
data class SyntheticReceipt(
    val vendor: String,
    val date: String,         // always Gregorian (truth)
    val total: Double,
    val lines: List<String>,  // tab-delimited, mimics Layout output
    val ocrBlob: String       // raw text the engine actually produces
)

object SyntheticReceipts {

    fun englishReceipt(): SyntheticReceipt = SyntheticReceipt(
        vendor = "ACME HARDWARE LTD",
        date = "2025-03-14",
        total = 34.24,
        lines = listOf(
            "ACME HARDWARE LTD",
            "Invoice No: INV-2024-0091",
            "Date: 14/03/2025",
            "Bolt M8\t2\t24.00",
            "Nut M8\t4\t8.00",
            "Subtotal\t32.00",
            "VAT 7%\t2.24",
            "TOTAL\t34.24"
        ),
        ocrBlob = """
            ACME HARDWARE LTD
            Invoice No: INV-2024-0091
            Date: 14/03/2025
            Bolt M8	2	24.00
            Nut M8	4	8.00
            Subtotal	32.00
            VAT 7%	2.24
            TOTAL	34.24
        """.trimIndent()
    )

    fun thaiReceipt(): SyntheticReceipt = SyntheticReceipt(
        vendor = "ร้านข้าวแกงภูเก็ต",
        date = "2025-03-14",
        total = 42.80,
        lines = listOf(
            "ร้านข้าวแกงภูเก็ต",
            "วันที่ 14/03/2568",
            "หมูปิ้ง\t40.00",
            "ภาษีมูลค่าเพิ่ม\t2.80",
            "รวมทั้งสิ้น\t42.80"
        ),
        ocrBlob = """
            ร้านข้าวแกงภูเก็ต
            วันที่ 14/03/2568
            หมูปิ้ง	40.00
            ภาษีมูลค่าเพิ่ม	2.80
            รวมทั้งสิ้น	42.80
        """.trimIndent()
    )
}