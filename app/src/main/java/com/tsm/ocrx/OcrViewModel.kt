package com.tsm.ocrx

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tsm.ocrx.export.CorpusCase
import com.tsm.ocrx.export.CorpusExporter
import com.tsm.ocrx.model.OcrResult
import com.tsm.ocrx.model.ScanConfidence
import com.tsm.ocrx.model.ScanGeometry
import com.tsm.ocrx.ocr.DownloadResult
import com.tsm.ocrx.ocr.ExtractedFields
import com.tsm.ocrx.ocr.FieldExtractor
import com.tsm.ocrx.ocr.OcrEngine
import com.tsm.ocrx.ocr.OcrLanguage
import com.tsm.ocrx.ocr.OcrMode
import com.tsm.ocrx.ocr.OcrModelStore
import com.tsm.ocrx.ocr.OcrSettings
import com.tsm.ocrx.ocr.PaddleEngine
import com.tsm.ocrx.ocr.PdfImporter
import com.tsm.ocrx.ocr.RecModel
import com.tsm.ocrx.ocr.TextBox
import com.tsm.ocrx.translate.Language
import com.tsm.ocrx.translate.TranslationEngine
import com.tsm.ocrx.translate.TranslationMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

sealed interface OcrStatus {
    data object Processing : OcrStatus
    data object Done : OcrStatus
    data class Error(val message: String) : OcrStatus
}

sealed interface TranslateStatus {
    data object Idle : TranslateStatus
    data class Running(val stage: String) : TranslateStatus
    data object Done : TranslateStatus
    data class Error(val message: String) : TranslateStatus
}

/** Progress of an on-demand recognition model download. */
sealed interface ModelDownload {
    data object Idle : ModelDownload
    data class Running(val model: RecModel, val progress: Float) : ModelDownload
    data class Failed(val model: RecModel, val message: String) : ModelDownload
}

/** One scanned image and the (possibly edited) text recognized from it. */
data class Page(
    val id: Long,
    val imageUri: Uri?,   // null when restored from storage (thumbnail not persisted)
    val status: OcrStatus,
    val text: String = "",
    // Scan-time OCR confidence; null when restored from storage (not persisted).
    val confidence: ScanConfidence? = null,
    // Scan-time line geometry, backing the row inspector. Also not persisted.
    val geometry: ScanGeometry? = null,
    /** Cells the post-OCR corrector repaired on this page, as "before → after". */
    val corrections: List<String> = emptyList(),
    /**
     * The text exactly as the engine produced it, before any edit. Kept so an edited
     * page can be told from an untouched one and the raw scan can be scored against
     * the user's correction. Scan-time only, like [geometry]; null when restored.
     */
    val ocrText: String? = null,
    /** Recognition language the scan was made with, so a test case can reproduce it. */
    val scanLanguage: OcrLanguage? = null
) {
    /** Scanned in this session with its image cached: what a golden test case needs. */
    val isCorpusCandidate: Boolean
        get() = status is OcrStatus.Done && text.isNotBlank() && geometry?.imagePath != null
}

data class OcrUiState(
    val multiMode: Boolean = false,
    val mode: OcrMode = OcrMode.QUALITY,
    val language: OcrLanguage = OcrLanguage.LATIN,
    val cropEnabled: Boolean = true,
    val settings: OcrSettings = OcrSettings(),
    val pages: List<Page> = emptyList(),
    val targetLang: Language = TranslationEngine.LANGUAGES.first(),
    val translationMode: TranslationMode = TranslationMode.OFFLINE,
    val translateStatus: TranslateStatus = TranslateStatus.Idle,
    val translatedText: String = "",
    val modelDownload: ModelDownload = ModelDownload.Idle,
    /** Recognition models currently installed, for the language picker and manager. */
    val installedModels: Set<RecModel> = emptySet(),
    /** Set when the chosen language's model still has to be downloaded. */
    val missingModel: RecModel? = null,
    val notice: String? = null
) {
    val isEmpty: Boolean get() = pages.isEmpty()
    val isProcessing: Boolean get() = pages.any { it.status is OcrStatus.Processing }

    /** All pages' text joined, in capture order — the single source for export. */
    val combinedText: String
        get() = pages.joinToString("\n") { it.text }.trim()

    /** Combined text parsed into a table for preview and export. */
    val table: OcrResult get() = OcrEngine.parse(combinedText)

    /** Translated text parsed into a table for export. */
    val translatedTable: OcrResult get() = OcrEngine.parse(translatedText)

    val hasTranslation: Boolean get() = translateStatus == TranslateStatus.Done && translatedText.isNotBlank()

    /** Mean confidence across scored pages, or null if none carry confidence. */
    val overallConfidence: Float?
        get() = pages.mapNotNull { it.confidence?.overall }
            .let { if (it.isEmpty()) null else it.average().toFloat() }

    // All pages' per-line confidence merged; on key collision keep the weakest.
    private val mergedConfidence: Map<String, Float>
        get() = buildMap {
            for (page in pages) page.confidence?.byLine?.forEach { (k, v) ->
                merge(k, v, ::minOf)
            }
        }

    /** Confidence of a rendered table row, or null if unknown (edited/restored). */
    fun rowConfidence(cells: List<String>): Float? =
        mergedConfidence[ScanConfidence.keyOf(cells.joinToString(""))]

    /**
     * Where a rendered table row came from in its source image, so the UI can show
     * the original pixels. Null once the text has been edited or restored, since the
     * geometry is a scan-time signal that is not persisted.
     */
    fun rowSource(cells: List<String>): RowSource? {
        val key = ScanConfidence.keyOf(cells.joinToString(""))
        for (page in pages) {
            val geometry = page.geometry ?: continue
            val box = geometry.byLine[key] ?: continue
            val path = geometry.imagePath ?: continue
            return RowSource(path, box, geometry.imageWidth, geometry.imageHeight)
        }
        return null
    }

    /** Fields extracted from the combined text (vendor, date, total, …). */
    val fields: ExtractedFields get() = FieldExtractor.extract(combinedText)

    /** All post-OCR repairs across pages, for the review banner. */
    val corrections: List<String> get() = pages.flatMap { it.corrections }
}

/** A table row's origin in the scanned image, for the inspector. */
data class RowSource(
    val imagePath: String,
    val box: TextBox,
    val imageWidth: Int,
    val imageHeight: Int
)

class OcrViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow(OcrUiState())
    val state: StateFlow<OcrUiState> = _state.asStateFlow()

    private val store = SessionStore(app)
    private val history = HistoryStore(app)
    private var nextId = 1L
    private var sessionId = System.currentTimeMillis()

    init {
        store.load()?.let { r ->
            val pages = r.pageTexts.mapIndexed { i, t ->
                Page(id = (i + 1).toLong(), imageUri = null, status = OcrStatus.Done, text = t)
            }
            nextId = pages.size + 1L
            _state.value = OcrUiState(
                multiMode = r.multiMode,
                mode = r.mode,
                language = r.language,
                cropEnabled = r.cropEnabled,
                settings = r.settings,
                pages = pages,
                targetLang = r.targetLang,
                translationMode = r.translationMode,
                translateStatus = if (r.translatedText.isNotBlank()) TranslateStatus.Done else TranslateStatus.Idle,
                translatedText = r.translatedText
            )
        }
        refreshInstalledModels()
        warmUpEngine()
        // Persist on every change (skip the initial value) so the session survives
        // the process being killed in the background.
        viewModelScope.launch {
            state.drop(1).collect { s ->
                val texts = s.pages
                    .filter { it.status is OcrStatus.Done && it.text.isNotBlank() }
                    .map { it.text }
                withContext(Dispatchers.IO) {
                    store.save(
                        multiMode = s.multiMode,
                        mode = s.mode,
                        language = s.language,
                        cropEnabled = s.cropEnabled,
                        settings = s.settings,
                        targetLang = s.targetLang,
                        translationMode = s.translationMode,
                        translatedText = s.translatedText,
                        pageTexts = texts
                    )
                    // Keep the current session in the scan history too.
                    if (texts.isNotEmpty()) {
                        history.upsert(
                            HistoryEntry(
                                id = sessionId,
                                time = System.currentTimeMillis(),
                                pageTexts = texts,
                                translatedText = s.translatedText,
                                targetLangCode = s.targetLang.code
                            )
                        )
                    }
                }
            }
        }
    }

    fun setMultiMode(enabled: Boolean) {
        _state.value = _state.value.copy(multiMode = enabled)
    }

    fun setMode(mode: OcrMode) {
        _state.value = _state.value.copy(mode = mode)
    }

    /**
     * Selects the recognition script. When its model is not installed the selection
     * still sticks — the UI then offers the download rather than silently reverting a
     * choice the user just made.
     */
    fun setLanguage(language: OcrLanguage) {
        val app = getApplication<Application>()
        val missing = if (OcrModelStore.isInstalled(app, language.model)) null else language.model
        _state.value = _state.value.copy(language = language, missingModel = missing)
        if (missing == null) warmUpEngine()
    }

    fun setCropEnabled(enabled: Boolean) {
        _state.value = _state.value.copy(cropEnabled = enabled)
    }

    fun setMixedScript(enabled: Boolean) {
        _state.value = _state.value.copy(
            settings = _state.value.settings.copy(mixedScript = enabled)
        )
        warmUpEngine()
    }

    fun setDeskew(enabled: Boolean) {
        _state.value = _state.value.copy(settings = _state.value.settings.copy(deskew = enabled))
    }

    fun setUseNnapi(enabled: Boolean) {
        _state.value = _state.value.copy(settings = _state.value.settings.copy(useNnapi = enabled))
        warmUpEngine()
    }

    fun dismissNotice() {
        _state.value = _state.value.copy(notice = null)
    }

    /* ---- Recognition models ---- */

    private fun refreshInstalledModels() {
        val app = getApplication<Application>()
        val installed = RecModel.entries.filter { OcrModelStore.isInstalled(app, it) }.toSet()
        val language = _state.value.language
        _state.value = _state.value.copy(
            installedModels = installed,
            missingModel = if (language.model in installed) null else language.model
        )
    }

    /** Bytes a downloaded model occupies, for the manager screen. */
    fun modelSizeOnDisk(model: RecModel): Long =
        OcrModelStore.downloadedBytes(getApplication(), model)

    fun isModelDownloaded(model: RecModel): Boolean =
        OcrModelStore.isDownloaded(getApplication(), model)

    fun downloadModel(model: RecModel) {
        if (_state.value.modelDownload is ModelDownload.Running) return
        _state.value = _state.value.copy(modelDownload = ModelDownload.Running(model, 0f))
        viewModelScope.launch {
            val result = OcrModelStore.download(getApplication(), model) { progress ->
                _state.value = _state.value.copy(modelDownload = ModelDownload.Running(model, progress))
            }
            when (result) {
                is DownloadResult.Success -> {
                    _state.value = _state.value.copy(
                        modelDownload = ModelDownload.Idle,
                        notice = "${model.displayName} is ready — it now works offline."
                    )
                    refreshInstalledModels()
                    warmUpEngine()
                }
                is DownloadResult.Failed ->
                    _state.value = _state.value.copy(
                        modelDownload = ModelDownload.Failed(model, result.message)
                    )
            }
        }
    }

    fun deleteModel(model: RecModel) {
        OcrModelStore.delete(getApplication(), model)
        refreshInstalledModels()
    }

    /**
     * Builds the ONNX sessions in the background so the first scan does not pay the
     * model load. Safe to call repeatedly — the engine rebuilds only when the
     * language or accuracy settings actually changed.
     */
    private fun warmUpEngine() {
        val s = _state.value
        if (!OcrModelStore.isInstalled(getApplication(), s.language.model)) return
        PaddleEngine.warmUp(
            getApplication(), s.language, s.settings.mixedScript, s.settings.useNnapi
        )
    }

    fun setTargetLang(lang: Language) {
        _state.value = _state.value.copy(
            targetLang = lang,
            translateStatus = TranslateStatus.Idle,
            translatedText = ""
        )
    }

    fun setTranslationMode(mode: TranslationMode) {
        _state.value = _state.value.copy(
            translationMode = mode,
            translateStatus = TranslateStatus.Idle,
            translatedText = ""
        )
    }

    fun onTranslatedTextChanged(newText: String) {
        _state.value = _state.value.copy(translatedText = newText)
    }

    /** Translates the combined recognized text into the selected target language. */
    fun translate() {
        val source = _state.value.combinedText
        if (source.isBlank()) return
        val target = _state.value.targetLang.code
        _state.value = _state.value.copy(
            translateStatus = TranslateStatus.Running("Preparing…"),
            translatedText = ""
        )
        viewModelScope.launch {
            try {
                val result = TranslationEngine.translate(source, target, _state.value.translationMode) { stage ->
                    _state.value = _state.value.copy(translateStatus = TranslateStatus.Running(stage))
                }
                _state.value = _state.value.copy(
                    translatedText = result,
                    translateStatus = TranslateStatus.Done
                )
            } catch (e: Exception) {
                android.util.Log.e("OcrX", "Translation failed", e)
                val msg = e.message ?: "Translation failed"
                val offline = e is java.net.UnknownHostException ||
                    msg.contains("resolve host", true) || msg.contains("Unable to resolve", true) ||
                    msg.contains("download", true) || msg.contains("network", true)
                val friendly = if (offline)
                    "No internet — connect once to download the offline language model (scanning & export work offline)"
                else msg
                _state.value = _state.value.copy(translateStatus = TranslateStatus.Error(friendly))
            }
        }
    }

    private fun clearTranslation() {
        _state.value = _state.value.copy(
            translateStatus = TranslateStatus.Idle,
            translatedText = ""
        )
    }

    /** Adds an image. In multi mode it appends a page; otherwise it replaces. */
    fun onImagePicked(uri: Uri) = onImagesPicked(listOf(uri))

    /**
     * Adds one or more images. Several at once always append as pages — selecting a
     * batch is an unambiguous request for multiple pages, so multi mode is turned on
     * rather than throwing all but the last away.
     */
    fun onImagesPicked(uris: List<Uri>) {
        if (uris.isEmpty()) return
        val multi = _state.value.multiMode || uris.size > 1
        val newPages = uris.map { Page(id = nextId++, imageUri = it, status = OcrStatus.Processing) }
        val pages = if (multi) _state.value.pages + newPages else newPages
        _state.value = _state.value.copy(
            multiMode = multi,
            pages = pages,
            translateStatus = TranslateStatus.Idle,
            translatedText = ""
        )
        // Pages are scanned one at a time: the engine holds a single ONNX session and
        // each scan already saturates the CPU, so concurrency would only add memory
        // pressure on the OEM killers this app has to survive.
        viewModelScope.launch {
            newPages.zip(uris).forEach { (page, uri) -> process(page.id, uri) }
        }
    }

    /** Renders a PDF's pages and scans them as a multi-page session. */
    fun onPdfPicked(uri: Uri) {
        _state.value = _state.value.copy(notice = "Rendering PDF…")
        viewModelScope.launch {
            try {
                val imported = PdfImporter.importPages(getApplication(), uri, _state.value.mode.maxLongEdge)
                _state.value = _state.value.copy(
                    notice = if (imported.skipped > 0)
                        "Imported ${imported.uris.size} of ${imported.totalPages} pages " +
                            "(limit ${PdfImporter.MAX_PAGES})."
                    else null
                )
                onImagesPicked(imported.uris)
            } catch (e: Exception) {
                android.util.Log.e("OcrX", "PDF import failed", e)
                _state.value = _state.value.copy(notice = e.message ?: "Could not import that PDF.")
            }
        }
    }

    private suspend fun process(id: Long, uri: Uri) {
        val update: (OcrStatus, String, ScanConfidence?, ScanGeometry?, List<String>, OcrLanguage?) -> Unit =
            { status, text, conf, geometry, corrections, language ->
                _state.value = _state.value.copy(
                    pages = _state.value.pages.map {
                        if (it.id == id) it.copy(
                            status = status,
                            text = text,
                            confidence = conf,
                            geometry = geometry,
                            corrections = corrections,
                            // The raw output is fixed at scan time; edits change only `text`.
                            ocrText = if (status == OcrStatus.Done) text else null,
                            scanLanguage = language
                        ) else it
                    }
                )
            }
        try {
            val language = _state.value.language
            val recognized = OcrEngine.recognize(
                context = getApplication(),
                imageUri = uri,
                mode = _state.value.mode,
                language = language,
                settings = _state.value.settings,
                cacheKey = "page-$id"
            )
            update(
                OcrStatus.Done, recognized.text, recognized.confidence,
                recognized.geometry, recognized.corrections, language
            )
            if (recognized.failedSumColumns > 0) {
                _state.value = _state.value.copy(
                    notice = "A column's total does not match the rows above it — check the amounts."
                )
            }
        } catch (e: Exception) {
            if (e is PaddleEngine.ModelMissing) {
                refreshInstalledModels()
                update(
                    OcrStatus.Error("Download the ${e.model.displayName} model to scan this script."),
                    "", null, null, emptyList(), null
                )
                return
            }
            val chain = generateSequence(e as Throwable) { it.cause }
                .mapNotNull { it.message?.takeIf { m -> m.isNotBlank() } }
                .toList()
                .distinct()
                .joinToString(" ← ")
            android.util.Log.e("OcrX", "OCR failed", e)
            update(OcrStatus.Error(chain.ifBlank { "OCR failed" }), "", null, null, emptyList(), null)
        }
    }

    /* ---- Accuracy corpus ---- */

    /**
     * The pages of this session that can become golden test cases: those whose
     * straightened scan image is still on disk. Pages restored after a kill or loaded
     * from history have text but no image, so there is nothing to measure them
     * against, and they are left out rather than exported half-formed.
     *
     * Case names use the page's id, not its position: positions shift when a page is
     * removed, and two exports of one session must never give the same name to
     * different documents.
     */
    fun corpusCases(): List<CorpusCase> =
        _state.value.pages.mapIndexedNotNull { i, page ->
            if (!page.isCorpusCandidate) return@mapIndexedNotNull null
            val image = File(page.geometry?.imagePath ?: return@mapIndexedNotNull null)
            if (!image.isFile) return@mapIndexedNotNull null
            CorpusCase(
                index = i + 1,
                name = CorpusExporter.caseName(sessionId, page.id.toInt()),
                image = image,
                text = page.text,
                language = page.scanLanguage ?: _state.value.language,
                ocrText = page.ocrText
            )
        }

    fun corpusFileName(): String = CorpusExporter.defaultFileName(sessionId)

    /**
     * Replaces the source line a rendered table row came from with [newText].
     *
     * The table is a projection of the pages' text, so a correction made in the row
     * inspector has to be written back to whichever page produced that line. Matching
     * on the whitespace-insensitive key is what makes this survive the tab/space
     * round-trip between the raw text and the rendered grid.
     */
    fun updateRow(cells: List<String>, newText: String) {
        val key = ScanConfidence.keyOf(cells.joinToString(""))
        var replaced = false
        val pages = _state.value.pages.map { page ->
            if (replaced) return@map page
            val lines = page.text.split('\n')
            val index = lines.indexOfFirst { ScanConfidence.keyOf(it) == key }
            if (index < 0) return@map page
            replaced = true
            page.copy(text = lines.toMutableList().also { it[index] = newText }.joinToString("\n"))
        }
        if (replaced) _state.value = _state.value.copy(pages = pages)
    }

    fun onPageTextChanged(id: Long, newText: String) {
        _state.value = _state.value.copy(
            pages = _state.value.pages.map {
                if (it.id == id) it.copy(text = newText) else it
            }
        )
    }

    fun removePage(id: Long) {
        _state.value = _state.value.copy(
            pages = _state.value.pages.filterNot { it.id == id }
        )
        clearTranslation()
    }

    fun reset() {
        _state.value = _state.value.copy(pages = emptyList(), notice = null)
        clearTranslation()
        PaddleEngine.clearScanCache(getApplication())
        sessionId = System.currentTimeMillis()   // next scans become a new history entry
    }

    /* ---- Scan history ---- */

    fun historyList(): List<HistoryEntry> = history.list()

    fun deleteHistoryEntry(id: Long) = history.delete(id)

    fun clearHistory() = history.clear()

    /** Loads a past scan back into the main screen for re-export/translation. */
    fun loadHistoryEntry(entry: HistoryEntry) {
        sessionId = entry.id
        val pages = entry.pageTexts.mapIndexed { i, t ->
            Page(id = nextId + i, imageUri = null, status = OcrStatus.Done, text = t)
        }
        nextId += entry.pageTexts.size
        _state.value = _state.value.copy(
            pages = pages,
            multiMode = _state.value.multiMode || pages.size > 1,
            targetLang = TranslationEngine.LANGUAGES
                .firstOrNull { it.code == entry.targetLangCode } ?: _state.value.targetLang,
            translatedText = entry.translatedText,
            translateStatus = if (entry.translatedText.isNotBlank()) TranslateStatus.Done
            else TranslateStatus.Idle
        )
    }
}
