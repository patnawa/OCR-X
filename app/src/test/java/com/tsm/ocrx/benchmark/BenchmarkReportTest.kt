package com.tsm.ocrx.benchmark

import org.junit.Assert.assertTrue
import org.junit.Test

class BenchmarkReportTest {

    @Test
    fun `report contains header, one row per case, and pooled footer`() {
        val rows = listOf(
            BenchmarkRow(
                "english-01", "LATIN",
                com.tsm.ocrx.ocr.Accuracy.Score(0.05, 0.10, 100, 20),
                FieldAccuracy(vendorOk = true, dateOk = true, totalDelta = 0.0)
            ),
            BenchmarkRow(
                "thai-01", "THAI",
                com.tsm.ocrx.ocr.Accuracy.Score(0.00, 0.00, 80, 12),
                FieldAccuracy(vendorOk = true, dateOk = true, totalDelta = 0.0)
            )
        )
        val md = BenchmarkReport.render(rows)
        assertTrue("Missing header", md.contains("| case |"))
        assertTrue("Missing english row", md.contains("english-01"))
        assertTrue("Missing thai row", md.contains("thai-01"))
        assertTrue("Missing pooled footer", md.contains("Pooled"))
    }
}