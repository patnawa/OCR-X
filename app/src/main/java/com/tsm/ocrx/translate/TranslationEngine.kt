package com.tsm.ocrx.translate

import com.google.android.gms.tasks.Task
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class Language(val name: String, val code: String)

/** Where translation runs: on-device model, or cloud service. */
enum class TranslationMode(val label: String) {
    OFFLINE("Offline"),
    ONLINE("Online")
}

/**
 * On-device text translation via ML Kit. The source language is auto-detected;
 * the model for a source→target pair downloads once (needs network) and then
 * runs fully offline.
 */
object TranslationEngine {

    val LANGUAGES = listOf(
        Language("Thai", TranslateLanguage.THAI),
        Language("English", TranslateLanguage.ENGLISH),
        Language("Chinese", TranslateLanguage.CHINESE),
        Language("Japanese", TranslateLanguage.JAPANESE),
        Language("Korean", TranslateLanguage.KOREAN),
        Language("Spanish", TranslateLanguage.SPANISH),
        Language("French", TranslateLanguage.FRENCH),
        Language("German", TranslateLanguage.GERMAN),
        Language("Italian", TranslateLanguage.ITALIAN),
        Language("Portuguese", TranslateLanguage.PORTUGUESE),
        Language("Russian", TranslateLanguage.RUSSIAN),
        Language("Arabic", TranslateLanguage.ARABIC),
        Language("Hindi", TranslateLanguage.HINDI),
        Language("Vietnamese", TranslateLanguage.VIETNAMESE),
        Language("Indonesian", TranslateLanguage.INDONESIAN)
    )

    /** Detects the source language, falling back to English when unknown/unsupported. */
    private suspend fun detectSource(text: String): String {
        val client = LanguageIdentification.getClient()
        return try {
            val tag = client.identifyLanguage(text.take(400)).await()
            if (tag == "und") TranslateLanguage.ENGLISH
            else TranslateLanguage.fromLanguageTag(tag) ?: TranslateLanguage.ENGLISH
        } catch (t: Throwable) {
            TranslateLanguage.ENGLISH
        } finally {
            client.close()
        }
    }

    /**
     * Translates [text] to [targetCode], preserving line breaks. Reports coarse
     * progress via [onStage]. Returns the original text if source == target.
     */
    suspend fun translate(
        text: String,
        targetCode: String,
        mode: TranslationMode,
        onStage: (String) -> Unit = {}
    ): String {
        if (mode == TranslationMode.ONLINE) {
            onStage("Translating online…")
            return OnlineTranslator.translate(text, targetCode)
        }
        return translateOffline(text, targetCode, onStage)
    }

    private suspend fun translateOffline(
        text: String,
        targetCode: String,
        onStage: (String) -> Unit
    ): String {
        // Detect the source. If detection lands on the target language (common with
        // short OCR text — it mis-detects), assume English so we still translate
        // instead of returning the text unchanged.
        var sourceCode = detectSource(text)
        if (sourceCode == targetCode) sourceCode = TranslateLanguage.ENGLISH
        if (sourceCode == targetCode) return text

        val options = TranslatorOptions.Builder()
            .setSourceLanguage(sourceCode)
            .setTargetLanguage(targetCode)
            .build()
        val translator = Translation.getClient(options)
        return try {
            onStage("Preparing model…")
            translator.downloadModelIfNeeded(DownloadConditions.Builder().build()).await()
            onStage("Translating…")
            val out = StringBuilder()
            val lines = text.split('\n')
            for ((i, line) in lines.withIndex()) {
                if (i > 0) out.append('\n')
                out.append(if (line.isBlank()) line else translator.translate(line).await())
            }
            out.toString()
        } finally {
            translator.close()
        }
    }

    private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
        addOnSuccessListener { cont.resume(it) }
        addOnFailureListener { e -> cont.resumeWithException(e) }
    }
}
