package com.tsm.ocrx.ocr

import android.content.Context
import android.net.Uri
import com.tsm.ocrx.model.OcrResult
import com.tsm.ocrx.model.ScanResult

/**
 * PP-OCR scan mode. Both use the same on-device engine; the difference is the
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
 * Accuracy/performance options that apply to every scan.
 *
 * @param mixedScript pair the selected script with the Latin model so a page mixing
 *                    scripts (a Thai receipt with English product codes) is read
 *                    correctly. Costs one extra recognition pass, no extra detection.
 * @param deskew      straighten the page before detection.
 * @param useNnapi    offer the models to the device's NPU/DSP via NNAPI. Off by
 *                    default: vendor drivers vary, and unsupported operators fall
 *                    back per partition, which can end up slower than plain CPU.
 */
data class OcrSettings(
    val mixedScript: Boolean = true,
    val deskew: Boolean = true,
    val useNnapi: Boolean = false
)

/**
 * Facade over the PP-OCR engine. Returns text in reading order
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
        language: OcrLanguage = OcrLanguage.LATIN,
        settings: OcrSettings = OcrSettings(),
        cacheKey: String? = null
    ): ScanResult {
        val decoded = ImagePreprocessor.decodeOriented(context, imageUri, mode.maxLongEdge)
        // Straightening returns the original instance when it decides not to act, so
        // only recycle the decoded bitmap when a genuinely new one replaced it.
        val scanned = if (settings.deskew) Deskew.straighten(decoded) else decoded
        try {
            return PaddleEngine.recognize(
                context = context,
                bitmap = scanned,
                language = language,
                mixedScript = settings.mixedScript,
                useNnapi = settings.useNnapi,
                cacheKey = cacheKey
            )
        } finally {
            // Free ~15-30 MB per scan immediately.
            if (scanned !== decoded) scanned.recycle()
            decoded.recycle()
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

    /**
     * The grid as tab-separated values, padded rectangular — the format Excel, Google
     * Sheets and Numbers all paste natively into cells.
     */
    fun toTsv(result: OcrResult): String {
        if (result.isEmpty) return ""
        return result.rows.joinToString("\n") { row ->
            (0 until result.columnCount).joinToString("\t") { row.getOrElse(it) { "" } }
        }
    }

    private fun splitColumns(line: String): List<String> =
        if (line.contains('\t')) line.split('\t').map { it.trim() }
        else line.trim().split(legacyColumnSplitter).map { it.trim() }
}
