package com.tsm.ocrx.ocr

import android.content.Context
import android.net.Uri
import com.tsm.ocrx.model.OcrResult
import com.tsm.ocrx.model.ScanResult

/**
 * PP-OCRv6 scan mode. Both use the same on-device engine; the difference is the
 * resolution of the image handed to it.
 */
enum class OcrMode(
    val displayName: String,
    val tagline: String,
    val maxLongEdge: Int
) {
    QUALITY("Quality", "Best accuracy · full detail", 2048),
    FAST("Fast", "Quick scan · ~2× faster", 1280)
}

/**
 * Facade over the PP-OCRv6 engine. Returns text in reading order
 * (one visual row per line).
 */
object OcrEngine {

    // Legacy fallback only: sessions saved before geometric columns — and text the
    // user types by hand — have no tabs, so columns are inferred from runs of spaces.
    private val legacyColumnSplitter = Regex("\\s{2,}")

    /** Runs OCR and returns recognized text in reading order plus its confidence. */
    suspend fun recognize(
        context: Context,
        imageUri: Uri,
        mode: OcrMode = OcrMode.QUALITY,
        language: OcrLanguage = OcrLanguage.LATIN
    ): ScanResult {
        val bitmap = ImagePreprocessor.decodeOriented(context, imageUri, mode.maxLongEdge)
        try {
            return PaddleEngine.recognize(context, bitmap, language)
        } finally {
            bitmap.recycle()   // free ~15-30 MB per scan immediately
        }
    }

    /**
     * Parses recognized (and possibly user-edited) text into a table. Each non-blank
     * line becomes a row. Columns come from the tab delimiters that [Layout] writes
     * from the page geometry — this preserves interior empty cells and keeps columns
     * aligned across rows. Tab-less lines (legacy sessions, hand-typed text) fall back
     * to runs of 2+ spaces.
     */
    fun parse(text: String): OcrResult {
        val rows = text.split('\n')
            .map { it.trimEnd() }
            .filter { it.isNotBlank() }
            .map { line -> splitColumns(line) }
        return OcrResult(rawText = text, rows = rows)
    }

    private fun splitColumns(line: String): List<String> =
        if (line.contains('\t')) line.split('\t').map { it.trim() }
        else line.trim().split(legacyColumnSplitter).map { it.trim() }
}
