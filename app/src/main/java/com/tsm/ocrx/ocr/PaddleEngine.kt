package com.tsm.ocrx.ocr

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import com.paddle.ocr.EngineConfig
import com.paddle.ocr.PaddleOCR
import com.paddle.ocr.PaddleOCRConfig
import com.tsm.ocrx.model.ScanConfidence
import com.tsm.ocrx.model.ScanGeometry
import com.tsm.ocrx.model.ScanResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/**
 * On-device PP-OCR engine backed by the official PaddleOCR Android SDK
 * (ONNX Runtime + OpenCV). The model session is heavy to construct, so a single
 * instance is created lazily and reused across scans.
 */
object PaddleEngine {

    private const val DET_MODEL_ASSET = "models/det/inference.onnx"
    private const val SCAN_CACHE_DIR = "scan-images"

    /** Everything that, when changed, requires rebuilding the ONNX sessions. */
    private data class EngineKey(
        val language: OcrLanguage,
        val mixedScript: Boolean,
        val useNnapi: Boolean
    ) {
        companion object {
            /**
             * Normalizes the key so it reflects the sessions actually built. Mixed
             * script is a no-op for a Latin page, and folding that in here stops the
             * toggle from pointlessly rebuilding the engine.
             */
            fun of(language: OcrLanguage, mixedScript: Boolean, useNnapi: Boolean) = EngineKey(
                language = language,
                mixedScript = mixedScript && language.mixedScriptPartner != null,
                useNnapi = useNnapi
            )
        }
    }

    private val mutex = Mutex()
    private var instance: PaddleOCR? = null
    private var loadedKey: EngineKey? = null

    /** Raised when the selected language's model has not been downloaded yet. */
    class ModelMissing(val model: RecModel) :
        IllegalStateException("The ${model.displayName} recognition model is not installed yet.")

    /**
     * Builds the engine ahead of the first scan. The ONNX sessions take a second or
     * two to construct and that cost otherwise lands on the user's first scan, which
     * is the moment they are least willing to wait.
     */
    fun warmUp(context: Context, language: OcrLanguage, mixedScript: Boolean, useNnapi: Boolean) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                engine(context, EngineKey.of(language, mixedScript, useNnapi))
            } catch (_: Throwable) {
                // A failed warm-up must stay invisible: the real scan will surface it.
            }
        }
    }

    /**
     * Returns the engine for [key], (re)building it when anything in the key changes.
     * The detection model is shared; only the recognition models differ, but the SDK
     * binds them at construction, so a language switch rebuilds the session. Switches
     * are rare (a user setting), so the cold-load cost is fine.
     */
    private suspend fun engine(context: Context, key: EngineKey): PaddleOCR = mutex.withLock {
        instance?.let { if (loadedKey == key) return@withLock it }

        // Tear down the previous session before loading the new one so we never hold
        // two model sets in memory at once.
        instance?.release()
        instance = null
        loadedKey = null

        val app = context.applicationContext
        // OpenCV's native library must be loaded before the engine touches any Mat.
        // Recognition cannot degrade gracefully without it, so this one throws.
        OpenCvLoader.ensureLoaded()

        val primary = OcrModelStore.resolve(app, key.language.model)
            ?: throw ModelMissing(key.language.model)
        // Pair in the complementary script so a page carrying both is read by the
        // model that knows each half. Silently skipped when the partner is not
        // installed — mixed script is an enhancement, never a reason to fail a scan.
        val secondary = if (key.mixedScript) {
            key.language.mixedScriptPartner?.let { OcrModelStore.resolve(app, it) }
        } else null

        // Detection at the standard PP-OCR resolution: resize so the LONG edge is
        // <= 1280. The SDK default ("min", 4000) keeps full-size photos and builds a
        // >100 MB input tensor that OOMs the app heap.
        //
        // Recognition runs a batch of lines per inference rather than one at a time.
        // A page's lines are batched by shape, so the batch tensor stays close to the
        // size the text actually needs — see RecBatchPlanner.
        val config = PaddleOCRConfig(
            detLimitType = "max",
            detLimitSideLen = 1280,
            detMaxSideLimit = 1280,
            recBatchSize = 8
        )
        val created = PaddleOCR.create(
            app, config, EngineConfig(useNnapi = key.useNnapi),
            detModelAssetPath = DET_MODEL_ASSET,
            recModelAssetPath = primary.model,
            recConfigAssetPath = primary.config,
            recModelAssetPath2 = secondary?.model,
            recConfigAssetPath2 = secondary?.config
        )
        instance = created
        loadedKey = key
        created
    }

    /**
     * Recognizes [bitmap] and returns its text in reading order, together with the
     * confidence and geometry signals the UI needs to flag and inspect weak rows.
     *
     * [bitmap] must already be straightened — see [Deskew] — because the returned box
     * coordinates are in its space and are cached alongside it for the inspector.
     */
    suspend fun recognize(
        context: Context,
        bitmap: Bitmap,
        language: OcrLanguage = OcrLanguage.LATIN,
        mixedScript: Boolean = true,
        useNnapi: Boolean = false,
        cacheKey: String? = null
    ): ScanResult {
        val engine = engine(context, EngineKey.of(language, mixedScript, useNnapi))
        val result = engine.recognize(bitmap)
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

        // A drawn grid beats inferred whitespace when the page has one.
        val separators = TableRules.detect(bitmap)?.verticalX ?: emptyList()
        val layout = Layout.buildReadingOrder(items, separators)

        val overall = if (items.isEmpty()) 0f
        else items.map { it.confidence }.average().toFloat()

        val lines = layout.text.lines()
        val byLine = lines.mapIndexedNotNull { i, line ->
            val key = ScanConfidence.keyOf(line)
            if (key.isEmpty()) null else key to layout.lineConfidence[i]
        }.toMap()
        val byLineBox = lines.mapIndexedNotNull { i, line ->
            val key = ScanConfidence.keyOf(line)
            val box = layout.lineBoxes.getOrNull(i)
            if (key.isEmpty() || box == null) null else key to box
        }.toMap()

        // Repair digits misread as letters in numeric columns, and check the columns'
        // arithmetic. Corrections are reported, never silent.
        val corrected = TextCorrector.correct(layout.text)

        val imagePath = cacheKey?.let { cacheScanImage(context, bitmap, it) }
        return ScanResult(
            text = corrected.text,
            confidence = ScanConfidence(overall, byLine, layout.geometryConfidence),
            geometry = ScanGeometry(byLineBox, imagePath, bitmap.width, bitmap.height),
            corrections = corrected.corrections.map { "${it.before} → ${it.after}" },
            failedSumColumns = corrected.failedSums.size
        )
    }

    /**
     * Writes the scanned bitmap to the cache so the inspector can crop regions out of
     * it later. Keeping a JPEG on disk rather than the bitmap in memory is what makes
     * inspection affordable on multi-page sessions — the old in-memory approach is
     * exactly what got this app killed by the OEM memory manager.
     */
    private fun cacheScanImage(context: Context, bitmap: Bitmap, cacheKey: String): String? = try {
        val dir = File(context.cacheDir, SCAN_CACHE_DIR).apply { mkdirs() }
        val file = File(dir, "$cacheKey.jpg")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        file.absolutePath
    } catch (_: Throwable) {
        null
    }

    /** Drops cached scan images. Called when a session is reset or replaced. */
    fun clearScanCache(context: Context) {
        try {
            File(context.cacheDir, SCAN_CACHE_DIR).listFiles()?.forEach { it.delete() }
        } catch (_: Throwable) {
        }
    }
}
