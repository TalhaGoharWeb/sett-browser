package app.privatex.browser.search

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Recent searches for SETT Search (redesign Phase 7) — on-device
 * only, newest first, capped, deduped case-insensitively. The
 * screen shows the list with Clear and a keep-history toggle;
 * nothing is ever transmitted.
 */
class SearchHistoryStore(private val file: File) {

    @Synchronized
    fun recent(): List<String> = parse(readText())

    @Synchronized
    fun record(query: String) {
        val clean = query.trim()
        if (clean.isEmpty()) return
        val rest = recent().filterNot { it.equals(clean, ignoreCase = true) }
        save((listOf(clean) + rest).take(MAX))
    }

    @Synchronized
    fun clear() {
        runCatching { file.delete() }
    }

    private fun save(items: List<String>) {
        val arr = JSONArray()
        items.forEach { arr.put(it) }
        file.parentFile?.mkdirs()
        file.writeText(JSONObject().put("queries", arr).toString())
    }

    private fun readText(): String = runCatching { file.readText() }.getOrDefault("")

    companion object {
        const val MAX = 20

        fun parse(text: String): List<String> {
            if (text.isBlank()) return emptyList()
            val root = runCatching { JSONObject(text) }.getOrNull() ?: return emptyList()
            val arr = root.optJSONArray("queries") ?: return emptyList()
            return (0 until arr.length()).mapNotNull { i ->
                arr.optString(i).takeIf { it.isNotBlank() }
            }.take(MAX)
        }
    }
}
