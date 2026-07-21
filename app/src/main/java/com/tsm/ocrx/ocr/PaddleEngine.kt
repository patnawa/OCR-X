package com.tsm.ocrx.ocr

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import com.paddle.ocr.EngineConfig
import com.paddle.ocr.PaddleOCR
import com.paddle.ocr.PaddleOCRConfig
import com.tsm.ocrx.model.ScanConfidence
import com.tsm.ocrx.model.ScanResult
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * On-device PP-OCRv6 engine backed by the official PaddleOCR Android SDK
 * (ONNX Runtime + OpenCV). The model session is heavy to construct, so a single
 * instance is created lazily and reused across scans.
 */
object PaddleEngine {

    private const val DET_MODEL_ASSET = "models/det/inference.onnx"

    private val mutex = Mutex()
    private var instance: PaddleOCR? = null
    private var loadedLanguage: OcrLanguage? = null

    /**
     * Returns the engine for [language], (re)building it when the language changes.
     * The detection model is shared; only the recognition model + dictionary differ,
     * but the SDK binds them at construction, so a language switch rebuilds the
     * session. Switches are rare (a user setting), so the cold-load cost is fine.
     */
    private suspend fun engine(context: Context, language: OcrLanguage): PaddleOCR = mutex.withLock {
        instance?.let { if (loadedLanguage == language) return@withLock it }

        // Tear down the previous language's session before loading the new one so we
        // never hold two model sets in memory at once.
        instance?.release()
        instance = null
        loadedLanguage = null

        val app = context.applicationContext
        // OpenCV's native library must be loaded before the engine touches any Mat.
        // Load it ourselves so we can surface the real dlopen failure (e.g. an
        // ABI/architecture mismatch) instead of a generic message.
        try {
            System.loadLibrary("opencv_java4")
        } catch (e: Throwable) {
            val abis = Build.SUPPORTED_ABIS.joinToString(", ")
            throw IllegalStateException(
                "OpenCV failed to load for this device (ABIs: $abis). ${e.message}"
            )
        }
        // Detection at the standard PP-OCR resolution: resize so the LONG edge is
        // <= 1280. The SDK default ("min", 4000) keeps full-size photos and builds a
        // >100 MB input tensor that OOMs the app heap.
        val config = PaddleOCRConfig(
            detLimitType = "max",
            detLimitSideLen = 1280,
            detMaxSideLimit = 1280
        )
        val created = if (language.usesDefaultModel) {
            PaddleOCR.create(app, config)
        } else {
            PaddleOCR.create(
                app, config, EngineConfig(),
                detModelAssetPath = DET_MODEL_ASSET,
                recModelAssetPath = language.recModelAsset!!,
                recConfigAssetPath = language.recConfigAsset!!
            )
        }
        instance = created
        loadedLanguage = language
        created
    }

    suspend fun recognize(
        context: Context,
        bitmap: Bitmap,
        language: OcrLanguage = OcrLanguage.LATIN
    ): ScanResult {
        val result = engine(context, language).recognize(bitmap)
        val items = result.results.map { r ->
            val xs = r.box.points.map { it.x }
            val ys = r.box.points.map { it.y }
            val top = ys.min()
            val height = (ys.max() - top).toInt().coerceAtLeast(1)
            PositionedText(
                left = xs.min().toInt(),
                top = top.toInt(),
                right = xs.max().toInt(),
                height = height,
                text = r.text,
                confidence = r.confidence
            )
        }
        val layout = Layout.buildReadingOrder(items)
        val overall = if (items.isEmpty()) 0f
        else items.map { it.confidence }.average().toFloat()
        val byLine = layout.text.lines().mapIndexedNotNull { i, line ->
            val key = ScanConfidence.keyOf(line)
            if (key.isEmpty()) null else key to layout.lineConfidence[i]
        }.toMap()
        return ScanResult(layout.text, ScanConfidence(overall, byLine))
    }
}
