package com.tsm.ocrx

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** One past scan session: its texts and (optional) translation. */
data class HistoryEntry(
    val id: Long,
    val time: Long,
    val pageTexts: List<String>,
    val translatedText: String,
    val targetLangCode: String
) {
    val preview: String get() = pageTexts.firstOrNull().orEmpty().take(80)
    val lineCount: Int get() = pageTexts.sumOf { t -> t.count { it == '\n' } + 1 }
}

/**
 * Keeps the last [MAX_ENTRIES] scan sessions in a JSON file in private storage,
 * newest first. The current session is upserted by id, so edits update their
 * entry instead of duplicating it.
 */
class HistoryStore(context: Context) {

    private val file = File(context.applicationContext.filesDir, "history.json")

    @Synchronized
    fun list(): List<HistoryEntry> {
        if (!file.exists()) return emptyList()
        return try {
            val arr = JSONArray(file.readText())
            (0 until arr.length()).mapNotNull { i -> fromJson(arr.getJSONObject(i)) }
        } catch (t: Throwable) {
            emptyList()
        }
    }

    @Synchronized
    fun upsert(entry: HistoryEntry) {
        val current = list().toMutableList()
        // Replace by id, or by identical content (avoids duplicates when a
        // restored session is re-saved under a fresh id).
        current.removeAll { it.id == entry.id || it.pageTexts == entry.pageTexts }
        current.add(0, entry)
        write(current.take(MAX_ENTRIES))
    }

    @Synchronized
    fun delete(id: Long) = write(list().filterNot { it.id == id })

    @Synchronized
    fun clear() = write(emptyList())

    private fun write(entries: List<HistoryEntry>) {
        val arr = JSONArray()
        entries.forEach { e ->
            arr.put(
                JSONObject()
                    .put("id", e.id)
                    .put("time", e.time)
                    .put("translated", e.translatedText)
                    .put("lang", e.targetLangCode)
                    .put("pages", JSONArray().apply { e.pageTexts.forEach { put(it) } })
            )
        }
        file.writeText(arr.toString())
    }

    private fun fromJson(json: JSONObject): HistoryEntry? {
        val pages = json.optJSONArray("pages")?.let { arr ->
            (0 until arr.length()).map { arr.getString(it) }
        }.orEmpty()
        if (pages.isEmpty()) return null
        return HistoryEntry(
            id = json.optLong("id"),
            time = json.optLong("time"),
            pageTexts = pages,
            translatedText = json.optString("translated"),
            targetLangCode = json.optString("lang")
        )
    }

    companion object {
        private const val MAX_ENTRIES = 50
    }
}
