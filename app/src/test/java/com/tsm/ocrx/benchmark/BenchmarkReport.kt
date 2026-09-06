package com.tsm.ocrx.benchmark

import com.tsm.ocrx.ocr.Accuracy

/**
 * One row of the benchmark report: a corpus case, its language, its OCR accuracy
 * (CER/WER), and whether each receipt field was extracted correctly.
 */
data class BenchmarkRow(
    val name: String,
    val language: String,
    val accuracy: Accuracy.Score,
    val fields: FieldAccuracy
)

data class FieldAccuracy(
    val vendorOk: Boolean,
    val dateOk: Boolean,
    val totalDelta: Double  // |extracted - truth|
)

object BenchmarkReport {

    fun render(rows: List<BenchmarkRow>): String {
        val sb = StringBuilder()
        sb.appendLine("# OCR-X v1.5 benchmark")
        sb.appendLine()
        sb.appendLine("| case | lang | CER | WER | vendor | total Δ | chars |")
        sb.appendLine("|------|------|----:|----:|--------|--------:|------:|")
        for (r in rows) {
            sb.appendLine(
                "| ${r.name} | ${r.language} | %.4f | %.4f | %s | %.4f | %d |".format(
                    r.accuracy.cer,
                    r.accuracy.wer,
                    if (r.fields.vendorOk && r.fields.dateOk) "✓" else "✗",
                    r.fields.totalDelta,
                    r.accuracy.referenceChars
                )
            )
        }
        val pooled = Accuracy.pooled(rows.map { it.accuracy })
        sb.appendLine()
        sb.appendLine(
            "**Pooled over ${rows.size} cases:** CER = %.4f, WER = %.4f"
                .format(pooled.cer, pooled.wer)
        )
        return sb.toString()
    }
}