package com.tsm.ocrx

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tsm.ocrx.model.OcrResult
import com.tsm.ocrx.model.ScanConfidence
import com.tsm.ocrx.ocr.OcrEngine
import com.tsm.ocrx.ocr.OcrLanguage
import com.tsm.ocrx.ocr.OcrMode
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

/** One scanned image and the (possibly edited) text recognized from it. */
data class Page(
    val id: Long,
    val imageUri: Uri?,   // null when restored from storage (thumbnail not persisted)
    val status: OcrStatus,
    val text: String = "",
    // Scan-time OCR confidence; null when restored from storage (not persisted).
    val confidence: ScanConfidence? = null
)

data class OcrUiState(
    val multiMode: Boolean = false,
    val mode: OcrMode = OcrMode.QUALITY,
    val language: OcrLanguage = OcrLanguage.LATIN,
    val cropEnabled: Boolean = true,
    val pages: List<Page> = emptyList(),
    val targetLang: Language = TranslationEngine.LANGUAGES.first(),
    val translationMode: TranslationMode = TranslationMode.OFFLINE,
    val translateStatus: TranslateStatus = TranslateStatus.Idle,
    val translatedText: String = ""
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
}

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
                pages = pages,
                targetLang = r.targetLang,
                translationMode = r.translationMode,
                translateStatus = if (r.translatedText.isNotBlank()) TranslateStatus.Done else TranslateStatus.Idle,
                translatedText = r.translatedText
            )
        }
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

    fun setLanguage(language: OcrLanguage) {
        _state.value = _state.value.copy(language = language)
    }

    fun setCropEnabled(enabled: Boolean) {
        _state.value = _state.value.copy(cropEnabled = enabled)
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
    fun onImagePicked(uri: Uri) {
        val page = Page(id = nextId++, imageUri = uri, status = OcrStatus.Processing)
        val pages = if (_state.value.multiMode) _state.value.pages + page else listOf(page)
        _state.value = _state.value.copy(
            pages = pages,
            translateStatus = TranslateStatus.Idle,
            translatedText = ""
        )
        process(page.id, uri)
    }

    private fun process(id: Long, uri: Uri) {
        viewModelScope.launch {
            val update: (OcrStatus, String, ScanConfidence?) -> Unit = { status, text, conf ->
                _state.value = _state.value.copy(
                    pages = _state.value.pages.map {
                        if (it.id == id) it.copy(status = status, text = text, confidence = conf) else it
                    }
                )
            }
            try {
                val recognized = OcrEngine.recognize(
                    getApplication(), uri, _state.value.mode, _state.value.language
                )
                update(OcrStatus.Done, recognized.text, recognized.confidence)
            } catch (e: Exception) {
                val chain = generateSequence(e as Throwable) { it.cause }
                    .mapNotNull { it.message?.takeIf { m -> m.isNotBlank() } }
                    .toList()
                    .distinct()
                    .joinToString(" ← ")
                android.util.Log.e("OcrX", "OCR failed", e)
                update(OcrStatus.Error(chain.ifBlank { "OCR failed" }), "", null)
            }
        }
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
        _state.value = _state.value.copy(pages = emptyList())
        clearTranslation()
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
