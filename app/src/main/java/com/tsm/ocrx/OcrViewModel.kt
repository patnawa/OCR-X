package com.tsm.ocrx

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tsm.ocrx.model.OcrResult
import com.tsm.ocrx.ocr.OcrEngine
import com.tsm.ocrx.ocr.OcrEngineType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface OcrStatus {
    data object Processing : OcrStatus
    data object Done : OcrStatus
    data class Error(val message: String) : OcrStatus
}

/** One scanned image and the (possibly edited) text recognized from it. */
data class Page(
    val id: Long,
    val imageUri: Uri,
    val status: OcrStatus,
    val text: String = ""
)

data class OcrUiState(
    val multiMode: Boolean = false,
    val enhance: Boolean = true,
    val engine: OcrEngineType = OcrEngineType.PP_OCR_V6,
    val pages: List<Page> = emptyList()
) {
    val isEmpty: Boolean get() = pages.isEmpty()
    val isProcessing: Boolean get() = pages.any { it.status is OcrStatus.Processing }

    /** All pages' text joined, in capture order — the single source for export. */
    val combinedText: String
        get() = pages.joinToString("\n") { it.text }.trim()

    /** Combined text parsed into a table for preview and export. */
    val table: OcrResult get() = OcrEngine.parse(combinedText)
}

class OcrViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow(OcrUiState())
    val state: StateFlow<OcrUiState> = _state.asStateFlow()

    private var nextId = 1L

    fun setMultiMode(enabled: Boolean) {
        _state.value = _state.value.copy(multiMode = enabled)
    }

    fun setEnhance(enabled: Boolean) {
        _state.value = _state.value.copy(enhance = enabled)
    }

    fun setEngine(engine: OcrEngineType) {
        _state.value = _state.value.copy(engine = engine)
    }

    /** Adds an image. In multi mode it appends a page; otherwise it replaces. */
    fun onImagePicked(uri: Uri) {
        val page = Page(id = nextId++, imageUri = uri, status = OcrStatus.Processing)
        val pages = if (_state.value.multiMode) _state.value.pages + page else listOf(page)
        _state.value = _state.value.copy(pages = pages)
        process(page.id, uri)
    }

    private fun process(id: Long, uri: Uri) {
        viewModelScope.launch {
            val update: (OcrStatus, String) -> Unit = { status, text ->
                _state.value = _state.value.copy(
                    pages = _state.value.pages.map {
                        if (it.id == id) it.copy(status = status, text = text) else it
                    }
                )
            }
            try {
                val recognized = OcrEngine.recognize(
                    getApplication(), uri, _state.value.engine, _state.value.enhance
                )
                update(OcrStatus.Done, recognized)
            } catch (e: Exception) {
                val chain = generateSequence(e as Throwable) { it.cause }
                    .mapNotNull { it.message?.takeIf { m -> m.isNotBlank() } }
                    .toList()
                    .distinct()
                    .joinToString(" ← ")
                android.util.Log.e("OcrX", "OCR failed", e)
                update(OcrStatus.Error(chain.ifBlank { "OCR failed" }), "")
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
    }

    fun reset() {
        _state.value = _state.value.copy(pages = emptyList())
    }
}
