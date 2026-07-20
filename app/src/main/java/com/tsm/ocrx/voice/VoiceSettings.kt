package com.tsm.ocrx.voice

import android.content.Context

/**
 * Stores the user's Gemini API key and model locally in the app's private
 * SharedPreferences (app-sandboxed; never hardcoded or checked into source).
 */
class VoiceSettings(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("voice", Context.MODE_PRIVATE)

    var apiKey: String
        get() = prefs.getString(KEY_API, "").orEmpty()
        set(value) { prefs.edit().putString(KEY_API, value.trim()).apply() }

    var model: String
        get() = prefs.getString(KEY_MODEL, DEFAULT_MODEL).orEmpty().ifBlank { DEFAULT_MODEL }
        set(value) { prefs.edit().putString(KEY_MODEL, value.trim()).apply() }

    val hasKey: Boolean get() = apiKey.isNotBlank()

    companion object {
        const val DEFAULT_MODEL = "gemini-live-2.5-flash-native-audio"
        private const val KEY_API = "gemini_api_key"
        private const val KEY_MODEL = "gemini_model"
    }
}
