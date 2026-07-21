package com.tsm.ocrx.ocr

/**
 * Recognition language for OCR. Text *detection* is script-agnostic and shared
 * across all languages; only the *recognition* model and its character dictionary
 * change per language.
 *
 * [LATIN] uses the app's default bundled PP-OCRv6 model (English + Latin scripts).
 * The others load a PP-OCRv5 script-specific recognition model from assets. Japanese
 * is covered by the same CJK model as Chinese, so it reuses those assets.
 */
enum class OcrLanguage(
    val displayName: String,
    val recModelAsset: String?,
    val recConfigAsset: String?
) {
    LATIN("English / Latin", null, null),
    THAI("Thai", "models/rec/thai/inference.onnx", "models/rec/thai/inference.yml"),
    CHINESE("Chinese", "models/rec/chinese/inference.onnx", "models/rec/chinese/inference.yml"),
    JAPANESE("Japanese", "models/rec/chinese/inference.onnx", "models/rec/chinese/inference.yml"),
    KOREAN("Korean", "models/rec/korean/inference.onnx", "models/rec/korean/inference.yml");

    /** LATIN falls back to the SDK's built-in default model paths. */
    val usesDefaultModel: Boolean get() = recModelAsset == null
}
