package com.tsm.ocrx.translate

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Cloud translation via Google's public translate endpoint. Needs internet but
 * no API key, auto-detects the source language, and generally gives higher
 * quality than the small on-device models. Translates line by line to preserve
 * the row structure.
 *
 * Note: this is an unofficial/free endpoint and may rate-limit; a keyed provider
 * (Google Cloud Translation, DeepL) can be slotted in behind the same interface.
 */
object OnlineTranslator {

    suspend fun translate(text: String, targetCode: String): String = withContext(Dispatchers.IO) {
        val sb = StringBuilder()
        val lines = text.split('\n')
        for ((i, line) in lines.withIndex()) {
            if (i > 0) sb.append('\n')
            sb.append(if (line.isBlank()) line else translateLine(line, targetCode))
        }
        sb.toString()
    }

    private fun translateLine(line: String, targetCode: String): String {
        val q = URLEncoder.encode(line, "UTF-8")
        val url = URL(
            "https://translate.googleapis.com/translate_a/single" +
                "?client=gtx&sl=auto&tl=$targetCode&dt=t&q=$q"
        )
        val conn = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000
            readTimeout = 15000
            requestMethod = "GET"
            setRequestProperty("User-Agent", "Mozilla/5.0")
        }
        try {
            if (conn.responseCode != 200) {
                throw RuntimeException("Translation service returned ${conn.responseCode}")
            }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            // Response: [[["translated","original",...],...], ...]
            val segments = JSONArray(body).getJSONArray(0)
            val out = StringBuilder()
            for (j in 0 until segments.length()) {
                out.append(segments.getJSONArray(j).getString(0))
            }
            return out.toString()
        } finally {
            conn.disconnect()
        }
    }
}
