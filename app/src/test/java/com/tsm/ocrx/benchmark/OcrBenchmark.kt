package com.tsm.ocrx.benchmark

import com.tsm.ocrx.ocr.Accuracy
import com.tsm.ocrx.ocr.FieldExtractor

/**
 * Runs the pure-JVM OCR pipeline (FieldExtractor) over a synthetic corpus and
 * scores each case. No device required — the synthetic text mimics what the
 * OCR engine would emit, so the field-extraction logic is exercised end-to-end
 * on every CI run.
 *
 * The Layout/TextCorrector pieces also feed into FieldExtractor indirectly
 * (it accepts tab-delimited text), so a regression in either shows up here.
 */
object OcrBenchmark {

    fun run(corpus: List<SyntheticReceipt>): List<BenchmarkRow> = corpus.map { r ->
        val blob = r.ocrBlob
        val truth = r.lines.joinToString("\n")
        val score = Accuracy.score(blob, truth)
        val fields = FieldExtractor.extract(blob)
        val totalDelta = kotlin.math.abs((fields.total ?: 0.0) - r.total)

        BenchmarkRow(
            name = corpusName(r),
            language = detectLang(blob),
            accuracy = score,
            fields = FieldAccuracy(
                vendorOk = fields.vendor == r.vendor,
                dateOk = fields.documentDate == r.date,
                totalDelta = totalDelta
            )
        )
    }

    private fun corpusName(r: SyntheticReceipt): String = when {
        r.vendor.contains("ACME") -> "english-01"
        r.vendor.contains("ภูเก็ต") -> "thai-01"
        else -> "unknown"
    }

    private fun detectLang(blob: String): String =
        if (blob.any { it.code in 0x0E00..0x0E7F }) "THAI" else "LATIN"
}