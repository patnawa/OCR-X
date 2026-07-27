package com.tsm.ocrx

import android.content.Context
import com.tsm.ocrx.ocr.OcrLanguage
import com.tsm.ocrx.ocr.OcrMode
import com.tsm.ocrx.ocr.OcrSettings
import com.tsm.ocrx.translate.Language
import com.tsm.ocrx.translate.TranslationEngine
import com.tsm.ocrx.translate.TranslationMode
import org.json.JSONArray
import org.json.JSONObject

/** Restored session data (text only; image thumbnails aren't persisted). */
data class RestoredSession(
    val multiMode: Boolean,
    val mode: OcrMode,
    val language: OcrLanguage,
    val cropEnabled: Boolean,
    val settings: OcrSettings,
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
        mode: OcrMode,
        language: OcrLanguage,
        cropEnabled: Boolean,
        settings: OcrSettings,
        targetLang: Language,
        translationMode: TranslationMode,
        translatedText: String,
        pageTexts: List<String>
    ) {
        val json = JSONObject()
            .put("multiMode", multiMode)
            .put("ocrMode", mode.name)
            .put("ocrLang", language.name)
            .put("crop", cropEnabled)
            .put("mixedScript", settings.mixedScript)
            .put("deskew", settings.deskew)
            .put("nnapi", settings.useNnapi)
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
            // Restored even with no pages: the accuracy settings and chosen script are
            // worth keeping on their own, and an empty page list is a valid session.
            RestoredSession(
                multiMode = json.optBoolean("multiMode", false),
                mode = runCatching { OcrMode.valueOf(json.optString("ocrMode")) }
                    .getOrDefault(OcrMode.QUALITY),
                language = runCatching { OcrLanguage.valueOf(json.optString("ocrLang")) }
                    .getOrDefault(OcrLanguage.LATIN),
                cropEnabled = json.optBoolean("crop", true),
                settings = OcrSettings(
                    mixedScript = json.optBoolean("mixedScript", true),
                    deskew = json.optBoolean("deskew", true),
                    useNnapi = json.optBoolean("nnapi", false)
                ),
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
