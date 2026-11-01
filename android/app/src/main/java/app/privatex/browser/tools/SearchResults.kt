package app.privatex.browser.tools

import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/**
 * Native search results — pure parsers and URL builders for the
 * sources Sett may render itself under their own terms:
 *
 * - A SearXNG instance's JSON API (format=json) — an official
 *   feature of the SearXNG software, used with instances the user
 *   runs or public instances listed by searx.space.
 * - The scholarly APIs in ScholarTools (OpenAlex / Semantic
 *   Scholar), which are documented, keyless and made for this.
 *
 * DuckDuckGo results are NOT parsed here. An earlier version read
 * DuckDuckGo's HTML endpoint; that was removed in v0.24.0 —
 * DuckDuckGo's terms do not offer that use, so DuckDuckGo is a
 * direct-mode provider: its own results page opens in the browser
 * (SearchRouting), exactly as its terms intend.
 *
 * A failed native fetch surfaces an error with a visible "open
 * the provider page" fallback — never a silent provider switch.
 */
object SearchResults {

    data class Result(
        val title: String,
        val url: String,
        val snippet: String,
        /** Engine name for SearXNG results; "" for single-source lists. */
        val source: String,
    )

    enum class SafeLevel(val label: String) {
        STRICT("Strict"),
        MODERATE("Moderate"),
        OFF("Off"),
    }

    // ------------------------------------------------------------ SearXNG

    /** Accepted SearXNG time_range values ("" = any time). */
    val TIME_RANGES = listOf("", "day", "week", "month", "year")

    fun searxngUrl(
        base: String,
        query: String,
        category: SearchRouting.Category,
        safe: SafeLevel,
        language: String,
        page: Int,
        timeRange: String = "",
    ): String {
        val b = SearchRouting.normalizeSearxngBase(base)
        val categories = when (category) {
            SearchRouting.Category.IMAGES -> "images"
            SearchRouting.Category.NEWS -> "news"
            SearchRouting.Category.VIDEOS -> "videos"
            SearchRouting.Category.MAPS -> "map"
            SearchRouting.Category.BOOKS -> "books"
            SearchRouting.Category.ACADEMIC -> "science"
            else -> "general"
        }
        val lang = language.trim().ifBlank { "all" }
        val safeValue = when (safe) {
            SafeLevel.STRICT -> 2
            SafeLevel.MODERATE -> 1
            SafeLevel.OFF -> 0
        }
        val timePart = if (timeRange in TIME_RANGES && timeRange.isNotEmpty()) {
            "&time_range=$timeRange"
        } else {
            ""
        }
        return "$b/search?q=${enc(query)}&format=json&categories=$categories" +
            "&safesearch=$safeValue&language=$lang&pageno=${page.coerceAtLeast(1)}$timePart"
    }

    /**
     * True when the text is a SearXNG JSON response (an object with
     * a results array). Distinguishes a genuine empty result set
     * from an instance answering with an HTML error page or
     * declining the JSON format — the two need different handling.
     */
    fun looksLikeSearxngJson(text: String): Boolean {
        val root = runCatching { JSONObject(text) }.getOrNull() ?: return false
        return root.has("results")
    }

    fun parseSearxng(json: String): List<Result> {
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return emptyList()
        val arr: JSONArray = root.optJSONArray("results") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val url = o.optString("url")
            if (url.isBlank()) {
                null
            } else {
                Result(
                    title = o.optString("title").ifBlank { url },
                    url = url,
                    snippet = o.optString("content"),
                    source = o.optString("engine"),
                )
            }
        }
    }

    // ------------------------------------------------------------ suggestions

    const val SUGGESTIONS_URL = "https://duckduckgo.com/ac/?type=json&q="

    fun parseSuggestions(json: String): List<String> {
        val arr = runCatching { JSONArray(json) }.getOrNull() ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            arr.optJSONObject(i)?.optString("phrase")?.takeIf { it.isNotBlank() }
        }
    }

    // ------------------------------------------------------------ helpers

    private fun enc(query: String): String = URLEncoder.encode(query.trim(), "UTF-8")
}
