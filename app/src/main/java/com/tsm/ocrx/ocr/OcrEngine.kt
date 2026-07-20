package com.tsm.ocrx.ocr

import android.content.Context
import android.net.Uri
import com.tsm.ocrx.model.OcrResult

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

    private val columnSplitter = Regex("\\s{2,}")

    /** Runs OCR and returns recognized text in reading order. */
    suspend fun recognize(
        context: Context,
        imageUri: Uri,
        mode: OcrMode = OcrMode.QUALITY
    ): String {
        val bitmap = ImagePreprocessor.decodeOriented(context, imageUri, mode.maxLongEdge)
        try {
            return PaddleEngine.recognize(context, bitmap)
        } finally {
            bitmap.recycle()   // free ~15-30 MB per scan immediately
        }
    }

    /**
     * Parses recognized (and possibly user-edited) text into a table. Each
     * non-blank line becomes a row; columns are detected by runs of 2+ spaces.
     */
    fun parse(text: String): OcrResult {
        val rows = text.split('\n')
            .map { it.trimEnd() }
            .filter { it.isNotBlank() }
            .map { line -> line.trim().split(columnSplitter).map { it.trim() } }
        return OcrResult(rawText = text, rows = rows)
    }
}
