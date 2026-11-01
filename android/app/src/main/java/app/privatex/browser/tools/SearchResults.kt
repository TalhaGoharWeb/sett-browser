package app.privatex.browser.tools

import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Native search results (redesign Phase 7) — pure parsers and URL
 * builders for the two sources Sett can render itself:
 *
 * - A user-configured SearXNG instance's JSON API (format=json).
 * - DuckDuckGo's HTML endpoint (html.duckduckgo.com), whose result
 *   markup Sett parses into title/link/snippet cards.
 *
 * Everything else opens the provider's own page (SearchRouting) —
 * Sett never presents another engine's page as its own results,
 * and a failed native fetch surfaces an error with a visible
 * "open the provider page" fallback, never a silent switch.
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

    fun searxngUrl(
        base: String,
        query: String,
        category: SearchRouting.Category,
        safe: SafeLevel,
        language: String,
        page: Int,
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
        return "$b/search?q=${enc(query)}&format=json&categories=$categories" +
            "&safesearch=$safeValue&language=$lang&pageno=${page.coerceAtLeast(1)}"
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

    // ------------------------------------------------------------ DDG HTML

    /** DuckDuckGo HTML endpoint with real SafeSearch (kp) and region (kl) params. */
    fun ddgUrl(query: String, safe: SafeLevel, region: String): String {
        val kp = when (safe) {
            SafeLevel.STRICT -> "1"
            SafeLevel.MODERATE -> "-1"
            SafeLevel.OFF -> "-2"
        }
        val kl = region.trim()
        val klPart = if (kl.matches(Regex("[a-z]{2}-[a-z]{2}"))) "&kl=$kl" else ""
        return "https://html.duckduckgo.com/html/?q=${enc(query)}&kp=$kp$klPart"
    }

    private val RESULT_LINK = Regex(
        "<a[^>]*class=\"result__a\"[^>]*href=\"([^\"]+)\"[^>]*>(.*?)</a>",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
    )
    private val RESULT_SNIPPET = Regex(
        "class=\"result__snippet\"[^>]*>(.*?)</(?:a|td)>",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
    )

    fun parseDdgHtml(html: String): List<Result> {
        val links = RESULT_LINK.findAll(html).toList()
        val snippets = RESULT_SNIPPET.findAll(html).map { clean(it.groupValues[1]) }.toList()
        return links.mapIndexed { i, m ->
            Result(
                title = clean(m.groupValues[2]),
                url = unwrapDdg(m.groupValues[1]),
                snippet = snippets.getOrElse(i) { "" },
                source = "",
            )
        }.filter { it.url.startsWith("http") }
    }

    /** DDG result links are redirects: //duckduckgo.com/l/?uddg=<target>. */
    fun unwrapDdg(href: String): String {
        val decoded = decodeEntities(href)
        val uddg = decoded.substringAfter("uddg=", "")
            .substringBefore('&')
        if (uddg.isNotBlank()) {
            return runCatching { URLDecoder.decode(uddg, "UTF-8") }.getOrDefault(decoded)
        }
        return when {
            decoded.startsWith("//") -> "https:$decoded"
            else -> decoded
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

    fun clean(htmlFragment: String): String = decodeEntities(
        htmlFragment.replace(Regex("<[^>]+>"), " "),
    ).replace(Regex("\\s+"), " ").trim()

    fun decodeEntities(text: String): String = text
        .replace("&amp;", "&")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&apos;", "'")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&nbsp;", " ")
        .replace(Regex("&#(\\d+);")) { m ->
            m.groupValues[1].toIntOrNull()?.toChar()?.toString() ?: m.value
        }

    private fun enc(query: String): String = URLEncoder.encode(query.trim(), "UTF-8")
}
