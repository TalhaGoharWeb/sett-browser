package app.privatex.browser.library

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Reading list (redesign Phase 5) — pages saved to read later.
 * Deliberately NOT bookmarks: a lightweight on-device list with a
 * saved-at timestamp, stored as JSON in app-private storage.
 * Saving the same URL twice updates the entry instead of
 * duplicating it. No sync, no account, no telemetry.
 */
class ReadingListStore(private val file: File) {

    data class Item(val id: String, val title: String, val url: String, val savedAt: Long)

    @Synchronized
    fun items(): List<Item> = parse(readText()).sortedByDescending { it.savedAt }

    @Synchronized
    fun add(title: String, url: String, now: Long = System.currentTimeMillis()): Item {
        val clean = url.trim()
        require(clean.startsWith("https://") || clean.startsWith("http://")) {
            "Reading list URL must start with https://"
        }
        val existing = items().firstOrNull { it.url == clean }
        val item = Item(
            id = existing?.id ?: "r${now.toString(36)}${(0..9999).random()}",
            title = title.trim().ifBlank { clean },
            url = clean,
            savedAt = now,
        )
        save(items().filterNot { it.url == clean } + item)
        return item
    }

    @Synchronized
    fun remove(id: String) = save(items().filterNot { it.id == id })

    @Synchronized
    fun contains(url: String): Boolean = items().any { it.url == url.trim() }

    private fun save(items: List<Item>) {
        val arr = JSONArray()
        items.forEach { i ->
            arr.put(
                JSONObject().put("id", i.id).put("title", i.title)
                    .put("url", i.url).put("savedAt", i.savedAt),
            )
        }
        file.parentFile?.mkdirs()
        file.writeText(JSONObject().put("items", arr).toString())
    }

    private fun readText(): String = runCatching { file.readText() }.getOrDefault("")

    companion object {
        fun parse(text: String): List<Item> {
            if (text.isBlank()) return emptyList()
            val root = runCatching { JSONObject(text) }.getOrNull() ?: return emptyList()
            val arr = root.optJSONArray("items") ?: return emptyList()
            return (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val url = o.optString("url")
                if (url.isBlank()) null
                else Item(o.optString("id"), o.optString("title"), url, o.optLong("savedAt"))
            }
        }
    }
}
