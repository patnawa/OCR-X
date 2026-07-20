package com.tsm.ocrx.voice

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Fetches the models available to a Gemini API key via the ListModels endpoint,
 * so the user picks a real, currently-available Live model instead of guessing.
 */
object GeminiModels {

    /**
     * Returns model ids (without the "models/" prefix) that support the Live API.
     * Falls back to a name heuristic, then to all models, if the method metadata
     * isn't present.
     */
    suspend fun listLiveModels(apiKey: String): List<String> = withContext(Dispatchers.IO) {
        val all = mutableListOf<Pair<String, Boolean>>() // id, supportsLive
        var pageToken: String? = null
        do {
            val url = buildString {
                append("https://generativelanguage.googleapis.com/v1beta/models?key=")
                append(apiKey)
                append("&pageSize=200")
                if (pageToken != null) append("&pageToken=").append(pageToken)
            }
            val json = JSONObject(httpGet(url))
            val arr = json.optJSONArray("models")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val m = arr.getJSONObject(i)
                    val id = m.getString("name").removePrefix("models/")
                    val methods = m.optJSONArray("supportedGenerationMethods")
                    val byMethod = methods != null &&
                        (0 until methods.length()).any { methods.getString(it) == "bidiGenerateContent" }
                    val byName = id.contains("live", true) || id.contains("native-audio", true)
                    all.add(id to (byMethod || byName))
                }
            }
            pageToken = json.optString("nextPageToken").ifBlank { null }
        } while (pageToken != null)

        val live = all.filter { it.second }.map { it.first }
        when {
            live.isNotEmpty() -> live.sorted()
            else -> all.map { it.first }.sorted()
        }
    }

    private fun httpGet(url: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000
            readTimeout = 15000
            requestMethod = "GET"
        }
        try {
            if (conn.responseCode != 200) {
                val err = conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                val reason = try {
                    JSONObject(err).optJSONObject("error")?.optString("message")
                } catch (t: Throwable) { null }
                throw RuntimeException(reason ?: "HTTP ${conn.responseCode}")
            }
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }
}
