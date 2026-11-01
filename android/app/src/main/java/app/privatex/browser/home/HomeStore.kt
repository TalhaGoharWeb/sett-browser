package app.privatex.browser.home

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * User shortcuts for the home page (redesign Phase 3) — the tile
 * grid is the user's own list, editable in the app, stored as JSON
 * in app-private storage. No defaults are planted and nothing syncs.
 */
class HomeStore(private val file: File) {

    data class Shortcut(val id: String, val title: String, val url: String)

    @Synchronized
    fun shortcuts(): List<Shortcut> = parse(readText())

    @Synchronized
    fun add(title: String, url: String): Shortcut {
        val clean = url.trim()
        require(clean.startsWith("https://") || clean.startsWith("http://")) {
            "Shortcut URL must start with https://"
        }
        val item = Shortcut(
            id = "s${System.currentTimeMillis().toString(36)}${(0..9999).random()}",
            title = title.trim().ifBlank { hostOf(clean) },
            url = clean,
        )
        save(shortcuts() + item)
        return item
    }

    @Synchronized
    fun remove(id: String) = save(shortcuts().filterNot { it.id == id })

    @Synchronized
    fun move(id: String, delta: Int) {
        val list = shortcuts().toMutableList()
        val i = list.indexOfFirst { it.id == id }
        if (i < 0) return
        val j = i + delta
        if (j !in list.indices) return
        val item = list.removeAt(i)
        list.add(j, item)
        save(list)
    }

    private fun save(items: List<Shortcut>) {
        val arr = JSONArray()
        items.forEach { s ->
            arr.put(JSONObject().put("id", s.id).put("title", s.title).put("url", s.url))
        }
        file.parentFile?.mkdirs()
        file.writeText(JSONObject().put("shortcuts", arr).toString())
    }

    private fun readText(): String = runCatching { file.readText() }.getOrDefault("")

    companion object {
        fun parse(text: String): List<Shortcut> {
            if (text.isBlank()) return emptyList()
            val root = runCatching { JSONObject(text) }.getOrNull() ?: return emptyList()
            val arr = root.optJSONArray("shortcuts") ?: return emptyList()
            return (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val url = o.optString("url")
                if (url.isBlank()) null
                else Shortcut(o.optString("id"), o.optString("title"), url)
            }
        }

        fun hostOf(url: String): String =
            url.removePrefix("https://").removePrefix("http://")
                .substringBefore('/').substringBefore('?').removePrefix("www.")
    }
}
