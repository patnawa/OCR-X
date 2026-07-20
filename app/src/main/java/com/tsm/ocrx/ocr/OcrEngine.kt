package com.tsm.ocrx.ocr

import android.content.Context
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.tsm.ocrx.model.OcrResult
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Selectable OCR engine. */
enum class OcrEngineType(
    val displayName: String,
    val tagline: String
) {
    PP_OCR_V6("PP-OCRv6", "Highest accuracy · on-device"),
    ML_KIT("ML Kit", "Fast · on-device")
}

/**
 * Facade over the available OCR engines. Both run fully on-device and return text
 * in reading order (one visual row per line).
 */
object OcrEngine {

    private val mlKit =
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    private val columnSplitter = Regex("\\s{2,}")

    /**
     * Runs OCR and returns recognized text in reading order.
     *
     * @param engine  which engine to use.
     * @param enhance ML Kit only — preprocess (orient, resize, contrast) before OCR.
     *   Ignored by PP-OCRv6, which does its own preprocessing.
     */
    suspend fun recognize(
        context: Context,
        imageUri: Uri,
        engine: OcrEngineType,
        enhance: Boolean = true
    ): String = when (engine) {
        OcrEngineType.PP_OCR_V6 -> recognizePaddle(context, imageUri)
        OcrEngineType.ML_KIT -> recognizeMlKit(context, imageUri, enhance)
    }

    private suspend fun recognizePaddle(context: Context, imageUri: Uri): String {
        // 2048 keeps detail for the recognizer while staying memory-safe; the
        // detector downsizes to <=1280 internally.
        val bitmap = ImagePreprocessor.decodeOriented(context, imageUri, maxLongEdge = 2048)
        return PaddleEngine.recognize(context, bitmap)
    }

    private suspend fun recognizeMlKit(context: Context, imageUri: Uri, enhance: Boolean): String {
        val image = if (enhance) {
            InputImage.fromBitmap(ImagePreprocessor.processForMlKit(context, imageUri), 0)
        } else {
            InputImage.fromFilePath(context, imageUri)
        }
        val text = mlKit.await(image)

        val items = mutableListOf<PositionedText>()
        var fallbackTop = 0
        for (block in text.textBlocks) {
            for (line in block.lines) {
                val box = line.boundingBox
                items.add(
                    PositionedText(
                        top = box?.top ?: fallbackTop,
                        left = box?.left ?: 0,
                        height = box?.height() ?: 20,
                        text = line.text
                    )
                )
                fallbackTop += 100
            }
        }
        return Layout.buildReadingOrder(items)
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

    private suspend fun com.google.mlkit.vision.text.TextRecognizer.await(
        image: InputImage
    ): com.google.mlkit.vision.text.Text =
        suspendCancellableCoroutine { cont ->
            process(image)
                .addOnSuccessListener { result -> cont.resume(result) }
                .addOnFailureListener { e -> cont.resumeWithException(e) }
        }
}
