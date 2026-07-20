package com.tsm.ocrx

import android.content.Context
import com.tsm.ocrx.ocr.OcrEngineType
import com.tsm.ocrx.translate.Language
import com.tsm.ocrx.translate.TranslationEngine
import com.tsm.ocrx.translate.TranslationMode
import org.json.JSONArray
import org.json.JSONObject

/** Restored session data (text only; image thumbnails aren't persisted). */
data class RestoredSession(
    val multiMode: Boolean,
    val enhance: Boolean,
    val engine: OcrEngineType,
    val targetLang: Language,
    val translationMode: TranslationMode,
    val translatedText: String,
    val pageTexts: List<String>
)

/**
 * Persists the recognized text and settings to the app's private storage so the
 * session survives the app being backgrounded and killed (common on aggressive
 * OEMs). Image thumbnails are not restored, but the extracted text — which
 * drives the table, translation and export — is.
 */
class SessionStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("session", Context.MODE_PRIVATE)

    fun save(
        multiMode: Boolean,
        enhance: Boolean,
        engine: OcrEngineType,
        targetLang: Language,
        translationMode: TranslationMode,
        translatedText: String,
        pageTexts: List<String>
    ) {
        val json = JSONObject()
            .put("multiMode", multiMode)
            .put("enhance", enhance)
            .put("engine", engine.name)
            .put("targetLang", targetLang.code)
            .put("mode", translationMode.name)
            .put("translated", translatedText)
            .put("pages", JSONArray().apply { pageTexts.forEach { put(it) } })
        // commit() (not apply) so the data is on disk even if the process is
        // killed abruptly right after; callers run this off the main thread.
        prefs.edit().putString("state", json.toString()).commit()
    }

    fun load(): RestoredSession? {
        val raw = prefs.getString("state", null) ?: return null
        return try {
            val json = JSONObject(raw)
            val texts = json.optJSONArray("pages")?.let { arr ->
                (0 until arr.length()).map { arr.getString(it) }.filter { it.isNotBlank() }
            }.orEmpty()
            if (texts.isEmpty()) return null
            RestoredSession(
                multiMode = json.optBoolean("multiMode", false),
                enhance = json.optBoolean("enhance", true),
                engine = runCatching { OcrEngineType.valueOf(json.optString("engine")) }
                    .getOrDefault(OcrEngineType.PP_OCR_V6),
                targetLang = TranslationEngine.LANGUAGES
                    .firstOrNull { it.code == json.optString("targetLang") }
                    ?: TranslationEngine.LANGUAGES.first(),
                translationMode = runCatching { TranslationMode.valueOf(json.optString("mode")) }
                    .getOrDefault(TranslationMode.OFFLINE),
                translatedText = json.optString("translated"),
                pageTexts = texts
            )
        } catch (t: Throwable) {
            null
        }
    }
}
