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

    /**
     * Fingerprinting "Custom" mode (Phase 3): the individual Gecko
     * prefs, used only when the strict profile above is OFF. Changing
     * any of them takes effect on the next app start (engine prefs are
     * applied when the engine is created).
     */
    var fpResist: Boolean
        get() = prefs.getBoolean(KEY_FP_RESIST, false)
        set(value) = prefs.edit().putBoolean(KEY_FP_RESIST, value).apply()

    var fpLetterbox: Boolean
        get() = prefs.getBoolean(KEY_FP_LETTERBOX, false)
        set(value) = prefs.edit().putBoolean(KEY_FP_LETTERBOX, value).apply()

    /** WebGL rendering (a fingerprinting surface). Default on. */
    var webglEnabled: Boolean
        get() = prefs.getBoolean(KEY_WEBGL, true)
        set(value) = prefs.edit().putBoolean(KEY_WEBGL, value).apply()

    /** WebRTC peer connections (can reveal local network addresses). Default on. */
    var webrtcEnabled: Boolean
        get() = prefs.getBoolean(KEY_WEBRTC, true)
        set(value) = prefs.edit().putBoolean(KEY_WEBRTC, value).apply()

    /** Clear all browsing data automatically when Sett is closed. */
    var clearOnExit: Boolean
        get() = prefs.getBoolean(KEY_CLEAR_ON_EXIT, false)
        set(value) = prefs.edit().putBoolean(KEY_CLEAR_ON_EXIT, value).apply()

    /**
     * Remote debugging via desktop Firefox (GeckoView's supported
     * devtools path). Read once at engine creation — changing it
     * applies at the next app start. Default OFF.
     */
    var remoteDebugging: Boolean
        get() = prefs.getBoolean(KEY_REMOTE_DEBUGGING, false)
        set(value) = prefs.edit().putBoolean(KEY_REMOTE_DEBUGGING, value).apply()

    /**
     * Internal marker: true while a browsing session is live. If the
     * app restarts and finds it still true with clear-on-exit enabled,
     * the previous session ended without its exit-clear (process death)
     * and the clear runs at startup instead.
     */
    var sessionDirty: Boolean
        get() = prefs.getBoolean(KEY_SESSION_DIRTY, false)
        set(value) = prefs.edit().putBoolean(KEY_SESSION_DIRTY, value).apply()

    /** Selected search engine key. */
    var searchEngine: String
        get() = prefs.getString(KEY_SEARCH, "duckduckgo") ?: "duckduckgo"
        set(value) = prefs.edit().putString(KEY_SEARCH, value).apply()

    // ------------------------------------------------ Network center (Phase 4)

    /** DNS-over-HTTPS provider: off | cloudflare | mullvad | quad9 | custom. */
    var dohProvider: String
        get() = prefs.getString(KEY_DOH_PROVIDER, "off") ?: "off"
        set(value) = prefs.edit().putString(KEY_DOH_PROVIDER, value).apply()

    /** DoH strict mode: true = TRR only (mode 3), false = TRR first with fallback (mode 2). */
    var dohStrict: Boolean
        get() = prefs.getBoolean(KEY_DOH_STRICT, false)
        set(value) = prefs.edit().putBoolean(KEY_DOH_STRICT, value).apply()

    var dohCustomUri: String
        get() = prefs.getString(KEY_DOH_CUSTOM, "") ?: ""
        set(value) = prefs.edit().putString(KEY_DOH_CUSTOM, value).apply()

    fun dohUri(): String = when (dohProvider) {
        "cloudflare" -> "https://mozilla.cloudflare-dns.com/dns-query"
        "mullvad" -> "https://dns.mullvad.net/dns-query"
        "quad9" -> "https://dns.quad9.net/dns-query"
        "custom" -> dohCustomUri
        else -> ""
    }

    /** Proxy mode: none | manual | orbot. */
    var proxyType: String
        get() = prefs.getString(KEY_PROXY_TYPE, "none") ?: "none"
        set(value) = prefs.edit().putString(KEY_PROXY_TYPE, value).apply()

    var proxyHttpHost: String
        get() = prefs.getString(KEY_PROXY_HTTP_HOST, "") ?: ""
        set(value) = prefs.edit().putString(KEY_PROXY_HTTP_HOST, value).apply()

    var proxyHttpPort: Int
        get() = prefs.getInt(KEY_PROXY_HTTP_PORT, 0)
        set(value) = prefs.edit().putInt(KEY_PROXY_HTTP_PORT, value).apply()

    var proxySocksHost: String
        get() = prefs.getString(KEY_PROXY_SOCKS_HOST, "") ?: ""
        set(value) = prefs.edit().putString(KEY_PROXY_SOCKS_HOST, value).apply()

    var proxySocksPort: Int
        get() = prefs.getInt(KEY_PROXY_SOCKS_PORT, 0)
        set(value) = prefs.edit().putInt(KEY_PROXY_SOCKS_PORT, value).apply()

    var proxySocksV5: Boolean
        get() = prefs.getBoolean(KEY_PROXY_SOCKS_V5, true)
        set(value) = prefs.edit().putBoolean(KEY_PROXY_SOCKS_V5, value).apply()

    var proxyRemoteDns: Boolean
        get() = prefs.getBoolean(KEY_PROXY_REMOTE_DNS, true)
        set(value) = prefs.edit().putBoolean(KEY_PROXY_REMOTE_DNS, value).apply()

    // ------------------------------------------------ Spaces (Phase 4)

    /** Spaces as JSON: [{"id":"main","name":"Main"}, …]. Tab groups — organisation, not isolation. */
    var spacesJson: String
        get() = prefs.getString(KEY_SPACES, null) ?: "[{\"id\":\"main\",\"name\":\"Main\"}]"
        set(value) = prefs.edit().putString(KEY_SPACES, value).apply()

    var currentSpaceId: String
        get() = prefs.getString(KEY_CURRENT_SPACE, "main") ?: "main"
        set(value) = prefs.edit().putString(KEY_CURRENT_SPACE, value).apply()

    /** tabId → spaceId assignments as a JSON object. */
    var tabSpacesJson: String
        get() = prefs.getString(KEY_TAB_SPACES, "{}") ?: "{}"
        set(value) = prefs.edit().putString(KEY_TAB_SPACES, value).apply()

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
        private const val KEY_FP_RESIST = "fp_resist"
        private const val KEY_FP_LETTERBOX = "fp_letterbox"
        private const val KEY_WEBGL = "webgl_enabled"
        private const val KEY_WEBRTC = "webrtc_enabled"
        private const val KEY_CLEAR_ON_EXIT = "clear_on_exit"
        private const val KEY_SESSION_DIRTY = "session_dirty"
        private const val KEY_SEARCH = "search_engine"
        private const val KEY_DOH_PROVIDER = "doh_provider"
        private const val KEY_DOH_STRICT = "doh_strict"
        private const val KEY_DOH_CUSTOM = "doh_custom_uri"
        private const val KEY_PROXY_TYPE = "proxy_type"
        private const val KEY_PROXY_HTTP_HOST = "proxy_http_host"
        private const val KEY_PROXY_HTTP_PORT = "proxy_http_port"
        private const val KEY_PROXY_SOCKS_HOST = "proxy_socks_host"
        private const val KEY_PROXY_SOCKS_PORT = "proxy_socks_port"
        private const val KEY_PROXY_SOCKS_V5 = "proxy_socks_v5"
        private const val KEY_PROXY_REMOTE_DNS = "proxy_remote_dns"
        private const val KEY_REMOTE_DEBUGGING = "remote_debugging"
        private const val KEY_SPACES = "spaces"
        private const val KEY_CURRENT_SPACE = "current_space"
        private const val KEY_TAB_SPACES = "tab_spaces"
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
