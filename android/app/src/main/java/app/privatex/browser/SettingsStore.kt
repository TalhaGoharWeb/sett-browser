package app.privatex.browser

import android.content.Context
import android.net.Uri

/** Private X's own settings. Stored locally only; nothing leaves the device. */
class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("privatex-settings", Context.MODE_PRIVATE)

    /** Enhanced Tracking Protection: false = Standard (recommended policy), true = Strict. */
    var etpStrict: Boolean
        get() = prefs.getBoolean(KEY_ETP_STRICT, false)
        set(value) = prefs.edit().putBoolean(KEY_ETP_STRICT, value).apply()

    /** Strict fingerprinting profile: Gecko resistFingerprinting + letterboxing (applies after restart). */
    var strictFingerprinting: Boolean
        get() = prefs.getBoolean(KEY_FP_STRICT, false)
        set(value) = prefs.edit().putBoolean(KEY_FP_STRICT, value).apply()

    /** Selected search engine key. */
    var searchEngine: String
        get() = prefs.getString(KEY_SEARCH, "duckduckgo") ?: "duckduckgo"
        set(value) = prefs.edit().putString(KEY_SEARCH, value).apply()

    fun searchUrl(query: String): String {
        val q = Uri.encode(query)
        return when (searchEngine) {
            "brave" -> "https://search.brave.com/search?q=$q"
            "mojeek" -> "https://www.mojeek.com/search?q=$q"
            "startpage" -> "https://www.startpage.com/sp/search?query=$q"
            else -> "https://html.duckduckgo.com/html/?q=$q"
        }
    }

    companion object {
        private const val KEY_ETP_STRICT = "etp_strict"
        private const val KEY_FP_STRICT = "fp_strict"
        private const val KEY_SEARCH = "search_engine"
        val SEARCH_ENGINES = linkedMapOf(
            "duckduckgo" to "DuckDuckGo (HTML)",
            "brave" to "Brave Search",
            "mojeek" to "Mojeek",
            "startpage" to "Startpage",
        )
    }
}

/** Address-bar resolution: bare domains go over HTTPS, everything else is a search. */
object AddressResolver {

    /** What typed input means. Pure classification — unit-tested on the JVM. */
    sealed interface Resolution {
        data object Home : Resolution
        data class Direct(val url: String) : Resolution
        data class Search(val query: String) : Resolution
    }

    fun classify(input: String): Resolution {
        val text = input.trim()
        if (text.isEmpty()) return Resolution.Home
        // "home" and either start-page URL (current or pre-rename) go home,
        // never to a search or a bogus https:// wrap of the custom scheme.
        if (HomeUrls.isHomeInput(text)) return Resolution.Home
        if (text.startsWith("about:") || text.startsWith("view-source:") ||
            text.startsWith("http://") || text.startsWith("https://")
        ) {
            return Resolution.Direct(text)
        }
        val hostPart = text.substringBefore('/').substringBefore('?').substringBefore('#')
        val looksLikeHost = !text.contains(' ') && hostPart.isNotEmpty() &&
            hostPart.all { it.isLetterOrDigit() || it in ".-_:" } &&
            (hostPart.contains('.') || hostPart.contains(':') ||
                hostPart.equals("localhost", ignoreCase = true))
        return if (looksLikeHost) Resolution.Direct("https://$text") else Resolution.Search(text)
    }

    fun resolve(input: String, settings: SettingsStore): String =
        when (val r = classify(input)) {
            Resolution.Home -> HomeUrls.CURRENT
            is Resolution.Direct -> r.url
            is Resolution.Search -> settings.searchUrl(r.query)
        }
}
