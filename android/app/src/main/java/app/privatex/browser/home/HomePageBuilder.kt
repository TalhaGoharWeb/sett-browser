package app.privatex.browser.home

import app.privatex.browser.ui.SettTheme

/**
 * Native action links used by the home page (redesign Phase 3).
 * The interceptor serves a tiny bounce page for these; BrowserActivity
 * watches the tab URL, performs the action natively and the bounce
 * lands back on the home page.
 */
object HomeActions {
    const val PREFIX = "sett://action/"
    const val SHIELD = "shield"
    const val SETTINGS = "settings"
    const val EDIT_SHORTCUTS = "edit-shortcuts"
    const val SECTIONS = "sections"
    /** Home-page search field submitted a general query: open the
     *  SETT Search screen with it (white-label flow, v0.25.0). */
    const val RUN_SEARCH = "runsearch"

    fun runSearchUrl(query: String): String =
        PREFIX + RUN_SEARCH + "?q=" + java.net.URLEncoder.encode(query, "UTF-8")

    fun url(action: String): String = PREFIX + action

    fun searchUrl(categoryKey: String): String = PREFIX + "search-" + categoryKey.lowercase()

    /** The action key for a sett://action/... URL, or null. */
    fun parse(uri: String): String? =
        if (uri.startsWith(PREFIX)) uri.removePrefix(PREFIX).substringBefore('?').substringBefore('/') else null
}

/**
 * The home page (redesign Phase 3) — a pure builder: every value on
 * the page comes from the data object, which the interceptor fills
 * from real stores and settings. Nothing is hardcoded: the
 * protection card reads the actual settings, the sections render
 * only when their data exists and the section is enabled, and the
 * shortcut grid is the user's own list.
 */
object HomePageBuilder {

    data class Link(val title: String, val url: String)

    data class Protection(
        val profile: String,
        val httpsOnly: Boolean,
        val gpc: Boolean,
        val clearOnExit: Boolean,
        val dohLabel: String,
    )

    data class Data(
        val palette: SettTheme.Palette,
        val engineName: String,
        val shortcuts: List<HomeStore.Shortcut>,
        val bookmarks: List<Link>,
        val recent: List<Link>,
        val closedTabs: List<Link>,
        val protection: Protection,
        val sections: Set<String>,
        val categories: List<Pair<String, String>>,
        /** Brock as a data URI — the only image channel the engine
         *  interceptor can serve (binary assets cannot be returned);
         *  the previous sett://asset/ URL was served by nothing and
         *  is removed. Blank = render without the image. */
        val brockDataUri: String = "",
    )

    const val SECTION_BOOKMARKS = "bookmarks"
    const val SECTION_RECENT = "recent"
    const val SECTION_CLOSED = "closed"
    val ALL_SECTIONS = setOf(SECTION_BOOKMARKS, SECTION_RECENT, SECTION_CLOSED)

    fun build(data: Data): String {
        val p = data.palette
        val bg = hex(p.bg); val surface = hex(p.surface); val elevated = hex(p.elevated)
        val text = hex(p.text); val dim = hex(p.dim); val accent = hex(p.accent)
        val onAccent = hex(p.onAccent); val warn = hex(p.warn)
        val sb = StringBuilder()
        sb.append(
            """
            <!DOCTYPE html><html lang="en"><head><meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <title>Sett</title>
            <style>
              *{box-sizing:border-box}
              body{margin:0;min-height:100vh;background:$bg;color:$text;font-family:system-ui,sans-serif;padding:30px 18px 28px}
              .wrap{max-width:560px;margin:0 auto}
              .brand{text-align:center;margin-bottom:20px}
              .brand img{width:56px;height:56px;border-radius:16px}
              .word{font-size:29px;font-weight:800;letter-spacing:7px;margin-top:8px}
              .word span{color:$accent}
              .tag{color:$dim;font-size:13.5px;margin-top:4px}
              form{display:flex;gap:8px;margin:18px 0 8px}
              input{flex:1;padding:15px 17px;border-radius:26px;border:1px solid $elevated;background:$surface;color:$text;font-size:16px;min-height:52px}
              input::placeholder{color:$dim}
              button{padding:0 20px;border:0;border-radius:26px;background:$accent;color:$onAccent;font-weight:700;font-size:15px;min-height:52px}
              h2{font-size:13px;letter-spacing:1.6px;text-transform:uppercase;color:$dim;margin:26px 0 10px}
              .card{background:$surface;border:1px solid $elevated;border-radius:16px;padding:14px 16px}
              .prow{display:flex;justify-content:space-between;gap:12px;padding:5px 0;font-size:14px}
              .prow b{font-weight:600;text-align:end}
              .on{color:$accent}.off{color:$warn}
              .card a{color:$accent;text-decoration:none;font-weight:600}
              .grid{display:grid;grid-template-columns:repeat(4,1fr);gap:10px}
              .tile{background:$surface;border:1px solid $elevated;border-radius:16px;padding:12px 4px 10px;text-align:center;text-decoration:none;color:$text;min-height:84px}
              .tile .fav{width:26px;height:26px;border-radius:8px;display:block;margin:0 auto 6px}
              .tile .letter{width:26px;height:26px;border-radius:8px;background:$elevated;color:$accent;font-weight:800;display:flex;align-items:center;justify-content:center;margin:0 auto 6px;font-size:14px}
              .tile .t{font-size:12px;line-height:1.25;display:block;overflow:hidden}
              .row{display:flex;align-items:center;gap:12px;background:$surface;border:1px solid $elevated;border-radius:14px;padding:12px 14px;margin-bottom:8px;text-decoration:none;color:$text;min-height:52px}
              .row .letter{width:30px;height:30px;flex:none;border-radius:9px;background:$elevated;color:$accent;font-weight:800;display:flex;align-items:center;justify-content:center;font-size:14px}
              .row .rt{flex:1;min-width:0}
              .row .rt b{display:block;font-size:14px;font-weight:600;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
              .row .rt span{font-size:12px;color:$dim}
              .chips{display:flex;flex-wrap:wrap;gap:8px}
              .chip{background:$surface;border:1px solid $elevated;color:$text;border-radius:20px;padding:13px 16px;font-size:13.5px;text-decoration:none}
              .muted{color:$dim;font-size:13px}
              .actions{display:flex;gap:14px;justify-content:center;margin-top:8px;font-size:13.5px}
              .actions a{color:$accent;text-decoration:none;font-weight:600}
              footer{color:$dim;font-size:12px;text-align:center;margin-top:30px;line-height:1.7}
            </style></head><body><div class="wrap">
            <div class="brand">
              ${if (data.brockDataUri.isNotBlank()) "<img src=\"${data.brockDataUri}\" alt=\"Brock the badger\">" else ""}
              <div class="word">SE<span>T</span>T</div>
              <div class="tag">Your burrow on the web — private by default</div>
            </div>
            <form action="sett://resolve" method="get">
              <input name="q" type="text" dir="auto" placeholder="Search privately or enter address" autocomplete="off" aria-label="Search or enter address">
              <button type="submit">Go</button>
            </form>
            <div class="muted" style="text-align:center">Search engine: ${esc(data.engineName)} · addresses open directly</div>
            """.trimIndent(),
        )

        // Protection summary — real settings, read at render time.
        val pr = data.protection
        sb.append("<h2>Your protection</h2><div class=\"card\">")
        sb.append(prow("Tracking protection", pr.profile, true))
        sb.append(prow("HTTPS-Only mode", if (pr.httpsOnly) "On" else "Off", pr.httpsOnly))
        sb.append(prow("Global Privacy Control", if (pr.gpc) "Sent" else "Off", pr.gpc))
        sb.append(prow("Clear on exit", if (pr.clearOnExit) "On" else "Off", pr.clearOnExit))
        sb.append(prow("Encrypted DNS (DoH)", pr.dohLabel, pr.dohLabel != "Off"))
        sb.append(
            "<div style=\"margin-top:8px\"><a href=\"${HomeActions.url(HomeActions.SHIELD)}\">Shield details</a>" +
                " &nbsp;·&nbsp; <a href=\"${HomeActions.url(HomeActions.SETTINGS)}\">Settings</a></div></div>",
        )

        // Shortcuts — the user's own grid.
        sb.append("<h2>Shortcuts</h2>")
        if (data.shortcuts.isEmpty()) {
            sb.append("<div class=\"card muted\">No shortcuts yet. Add the sites you open most — they live on this device only.</div>")
        } else {
            sb.append("<div class=\"grid\">")
            data.shortcuts.forEach { s ->
                val host = HomeStore.hostOf(s.url)
                sb.append(
                    "<a class=\"tile\" href=\"${escAttr(s.url)}\">" +
                        "<img class=\"fav\" src=\"https://$host/favicon.ico\" alt=\"\" loading=\"lazy\" " +
                        "onerror=\"this.outerHTML='<span class=letter>${esc(initial(s.title, host))}</span>'\">" +
                        "<span class=\"t\">${esc(s.title)}</span></a>",
                )
            }
            sb.append("</div>")
        }
        sb.append(
            "<div class=\"actions\"><a href=\"${HomeActions.url(HomeActions.EDIT_SHORTCUTS)}\">Edit shortcuts</a>" +
                "<a href=\"${HomeActions.url(HomeActions.SECTIONS)}\">Choose sections</a></div>",
        )

        if (SECTION_BOOKMARKS in data.sections && data.bookmarks.isNotEmpty()) {
            sb.append("<h2>Bookmarks</h2>")
            data.bookmarks.forEach { sb.append(row(it)) }
        }
        if (SECTION_RECENT in data.sections && data.recent.isNotEmpty()) {
            sb.append("<h2>Continue browsing</h2>")
            data.recent.forEach { sb.append(row(it)) }
        }
        if (SECTION_CLOSED in data.sections && data.closedTabs.isNotEmpty()) {
            sb.append("<h2>Recently closed</h2>")
            data.closedTabs.forEach { sb.append(row(it)) }
        }

        if (data.categories.isNotEmpty()) {
            sb.append("<h2>Search categories</h2><div class=\"chips\">")
            data.categories.forEach { (label, key) ->
                sb.append("<a class=\"chip\" href=\"${HomeActions.searchUrl(key)}\">${esc(label)}</a>")
            }
            sb.append("</div>")
        }

        sb.append(
            "<footer>Fully offline · No account · No telemetry · No ads<br>" +
                "This page made no network request. Shortcut icons load from their own sites.</footer>" +
                "</div></body></html>",
        )
        return sb.toString()
    }

    /**
     * Minimal home page the interceptor serves if building the full
     * page ever fails on a device: a working search/address form and
     * nothing that can fail. A degraded home beats a blank white
     * tab — that failure mode is what this exists to prevent.
     */
    fun fallbackHtml(palette: SettTheme.Palette): String {
        val bg = hex(palette.bg)
        val surface = hex(palette.surface)
        val text = hex(palette.text)
        val accent = hex(palette.accent)
        return "<!DOCTYPE html><html><head><meta charset=\"utf-8\">" +
            "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">" +
            "<title>Sett home</title></head>" +
            "<body style=\"background:$bg;color:$text;font-family:sans-serif;margin:0;padding:32px 20px\">" +
            "<div style=\"font-size:30px;font-weight:800;letter-spacing:2px\">SE<span style=\"color:$accent\">T</span>T</div>" +
            "<p>Your burrow on the web — private by default.</p>" +
            "<form action=\"sett://resolve\" method=\"get\">" +
            "<input name=\"q\" type=\"text\" placeholder=\"Search privately or enter address\" " +
            "style=\"width:100%;box-sizing:border-box;padding:14px;border-radius:12px;border:1px solid $surface;" +
            "background:$surface;color:$text;font-size:16px\">" +
            "<button type=\"submit\" style=\"margin-top:10px;padding:12px 22px;border-radius:12px;" +
            "background:$accent;color:#04241F;border:0;font-size:15px;font-weight:700\">Go</button></form>" +
            "<p style=\"opacity:.75;font-size:13px\">The full home page could not be built this " +
            "time; search and addresses work normally.</p></body></html>"
    }

    /** The bounce page served for sett://action/... URLs. */
    fun bounceHtml(): String =
        "<!DOCTYPE html><html><head><meta charset=\"utf-8\"><title>Sett</title></head>" +
            "<body style=\"background:#0B1626;color:#E6F1F5;font-family:sans-serif;text-align:center;padding-top:40px\">" +
            "<p>Opening…</p><p><a style=\"color:#2DD4BF\" href=\"sett://home\">Back to Sett home</a></p>" +
            "<script>location.replace('sett://home')</script></body></html>"

    private fun prow(label: String, value: String, good: Boolean): String =
        "<div class=\"prow\"><span>$label</span><b class=\"${if (good) "on" else "off"}\">${esc(value)}</b></div>"

    private fun row(link: Link): String {
        val host = HomeStore.hostOf(link.url)
        val title = link.title.ifBlank { host }
        return "<a class=\"row\" href=\"${escAttr(link.url)}\">" +
            "<span class=\"letter\">${esc(initial(title, host))}</span>" +
            "<span class=\"rt\"><b>${esc(title)}</b><span>${esc(host)}</span></span></a>"
    }

    private fun initial(title: String, host: String): String =
        (title.firstOrNull { it.isLetterOrDigit() } ?: host.firstOrNull() ?: 'S').uppercaseChar().toString()

    fun hex(argb: Int): String = "#%06X".format(0xFFFFFF and argb)

    fun esc(text: String): String = text
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    fun escAttr(text: String): String = esc(text).replace("\"", "&quot;")
}
