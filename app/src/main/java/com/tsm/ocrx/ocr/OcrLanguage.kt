package com.tsm.ocrx.ocr

/**
 * A PP-OCR recognition model: an ONNX graph plus the character dictionary that its
 * CTC decoder indexes into. Detection is script-agnostic and shared by all of them.
 *
 * Models are either **bundled** in the APK or **downloaded on demand**. Only the
 * scripts most users scan ship in the APK; the large CJK dictionaries are fetched
 * when first selected, which keeps the install ~30 MB smaller. [OcrModelStore]
 * resolves whichever copy is present.
 *
 * @param bundledAssetDir assets directory holding the model, or null when the model
 *                        is download-only.
 * @param modelSha256     digest of the expected `inference.onnx`. Verified after every
 *                        download so a truncated or tampered file is never loaded.
 * @param downloadBytes   total download size, shown in the UI before committing.
 */
enum class RecModel(
    val key: String,
    val displayName: String,
    val bundledAssetDir: String?,
    val modelUrl: String,
    val configUrl: String,
    val modelSha256: String,
    val configSha256: String,
    val downloadBytes: Long
) {
    LATIN(
        key = "latin",
        displayName = "English / Latin",
        bundledAssetDir = "models/rec",
        modelUrl = "",
        configUrl = "",
        modelSha256 = "5435fd747c9e0efe15a96d0b378d5bd157e9492ed8fd80edf08f30d02fa24634",
        configSha256 = "ab078671bb49f06228eadccd34f1bb501e157f7a047095ffb943ba81512c77d1",
        downloadBytes = 0L
    ),
    THAI(
        key = "thai",
        displayName = "Thai",
        bundledAssetDir = "models/rec/thai",
        modelUrl = "https://www.modelscope.cn/models/RapidAI/RapidOCR/resolve/v3.9.1/onnx/PP-OCRv5/rec/th_PP-OCRv5_rec_mobile.onnx",
        configUrl = "https://huggingface.co/PaddlePaddle/th_PP-OCRv5_mobile_rec/resolve/main/inference.yml",
        modelSha256 = "de541dd83161c241ff426f7ecfd602a0ba77d686cf3ab9a6c255ea82fd08006e",
        configSha256 = "f6ba7fefc38ca1ff398ddafa75d67d16e0b3757c4e6c833adffee98a981766c9",
        downloadBytes = 7_919_961L
    ),
    CHINESE(
        key = "chinese",
        displayName = "Chinese / Japanese",
        bundledAssetDir = null,
        modelUrl = "https://www.modelscope.cn/models/RapidAI/RapidOCR/resolve/v3.9.1/onnx/PP-OCRv5/rec/ch_PP-OCRv5_rec_mobile.onnx",
        configUrl = "https://huggingface.co/PaddlePaddle/PP-OCRv5_mobile_rec/resolve/main/inference.yml",
        modelSha256 = "5825fc7ebf84ae7a412be049820b4d86d77620f204a041697b0494669b1742c5",
        configSha256 = "5dfeb2777f6d0db8177d8128a8acfcf6e6276dc4ac73ea3bf0dc06d6a5e85d8e",
        downloadBytes = 16_779_651L
    ),
    KOREAN(
        key = "korean",
        displayName = "Korean",
        bundledAssetDir = null,
        modelUrl = "https://www.modelscope.cn/models/RapidAI/RapidOCR/resolve/v3.9.1/onnx/PP-OCRv5/rec/korean_PP-OCRv5_rec_mobile.onnx",
        configUrl = "https://huggingface.co/PaddlePaddle/korean_PP-OCRv5_mobile_rec/resolve/main/inference.yml",
        modelSha256 = "cd6e2ea50f6943ca7271eb8c56a877a5a90720b7047fe9c41a2e541a25773c9b",
        configSha256 = "f757fa1c40e99edcf27e9cce879b93eb2a51fa46f5ef39095689b8c37dd75998",
        downloadBytes = 13_584_787L
    );

    /** Bundled models are always usable; the rest must be downloaded once. */
    val isBundled: Boolean get() = bundledAssetDir != null

    companion object {
        /** Models the user can add or remove, in the order the manager lists them. */
        val downloadable: List<RecModel> get() = entries.filter { !it.isBundled }
    }
}

/**
 * Recognition language offered in the UI. Text *detection* is script-agnostic and
 * shared across all languages; only the recognition model and its character
 * dictionary change per language.
 *
 * Japanese is covered by the same CJK model as Chinese, so it reuses those assets.
 */
enum class OcrLanguage(
    val displayName: String,
    val model: RecModel
) {
    LATIN("English / Latin", RecModel.LATIN),
    THAI("Thai", RecModel.THAI),
    CHINESE("Chinese", RecModel.CHINESE),
    JAPANESE("Japanese", RecModel.CHINESE),
    KOREAN("Korean", RecModel.KOREAN);

    /**
     * The model paired with this language in mixed-script mode, or null when there is
     * nothing useful to pair.
     *
     * A non-Latin script pairs with Latin. Latin pairs back with **Thai**, which is
     * not symmetry for its own sake: leaving the picker on the default and scanning a
     * Thai page is the realistic mistake, and unpaired it produces transliterated
     * noise because the Latin dictionary holds no Thai glyphs. Thai is the one
     * non-Latin model bundled in the APK, so the pairing is always available offline.
     *
     * Measured on device: script models such as Thai's already carry Latin letters and
     * digits in their dictionaries — receipts in those scripts are full of both — so
     * pairing rarely has to rescue the Latin half of a page. It is the other
     * direction that needs help.
     */
    val mixedScriptPartner: RecModel?
        get() = if (model == RecModel.LATIN) RecModel.THAI else RecModel.LATIN
}
