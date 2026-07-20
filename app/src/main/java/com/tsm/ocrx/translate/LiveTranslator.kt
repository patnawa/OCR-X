package com.tsm.ocrx.translate

import com.google.android.gms.tasks.Task
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Stateful offline translator for the live camera mode. Unlike the one-shot
 * [TranslationEngine], it keeps a [Translator] per source language and caches
 * per-line results, so repeated frames don't re-download or re-translate.
 */
class LiveTranslator(private val targetCode: String) {

    private val translators = HashMap<String, Translator>()
    private val cache = HashMap<String, String>()
    private val languageId = LanguageIdentification.getClient()

    suspend fun translate(text: String): String {
        val sb = StringBuilder()
        val lines = text.split('\n')
        for ((i, line) in lines.withIndex()) {
            if (i > 0) sb.append('\n')
            sb.append(if (line.isBlank()) line else translateLine(line))
        }
        return sb.toString()
    }

    private suspend fun translateLine(line: String): String {
        cache[line]?.let { return it }
        val source = detect(line) ?: return line
        if (source == targetCode) return line
        val translator = translatorFor(source) ?: return line
        return try {
            val result = translator.translate(line).await()
            cache[line] = result
            result
        } catch (t: Throwable) {
            line
        }
    }

    private suspend fun detect(text: String): String? {
        return try {
            val tag = languageId.identifyLanguage(text).await()
            if (tag == "und") TranslateLanguage.ENGLISH
            else TranslateLanguage.fromLanguageTag(tag) ?: TranslateLanguage.ENGLISH
        } catch (t: Throwable) {
            TranslateLanguage.ENGLISH
        }
    }

    private suspend fun translatorFor(source: String): Translator? {
        translators[source]?.let { return it }
        val translator = Translation.getClient(
            TranslatorOptions.Builder()
                .setSourceLanguage(source)
                .setTargetLanguage(targetCode)
                .build()
        )
        return try {
            translator.downloadModelIfNeeded(DownloadConditions.Builder().build()).await()
            translators[source] = translator
            translator
        } catch (t: Throwable) {
            translator.close()
            null
        }
    }

    fun close() {
        translators.values.forEach { it.close() }
        translators.clear()
        cache.clear()
        languageId.close()
    }

    private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
        addOnSuccessListener { cont.resume(it) }
        addOnFailureListener { e -> cont.resumeWithException(e) }
    }
}
