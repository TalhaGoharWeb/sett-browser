package app.privatex.browser.tools

import java.net.URLEncoder

/**
 * SETT Search routing (Phase 7): transparent per-category provider
 * routing. Sett has no search server and receives nothing — choosing
 * a provider and pressing Search opens that provider's own results
 * page in the browser, and the provider receives exactly what any
 * direct visit would send (the query, the device IP, the browser
 * user-agent). [dataFlowNote] states this per provider, in the app.
 *
 * The one partial exception is a user-supplied SearXNG instance:
 * queries go to that instance and nowhere else, which is the point
 * of self-hosting.
 */
object SearchRouting {

    enum class Category(val label: String) {
        WEB("Web"),
        IMAGES("Images"),
        NEWS("News"),
        VIDEOS("Videos"),
        MAPS("Maps"),
        ACADEMIC("Academic"),
    }

    data class Provider(
        val key: String,
        val name: String,
        /** What this provider receives when used — plain-language, shown in the app. */
        val dataFlow: String,
    )

    private const val STANDARD_FLOW =
        "Receives your search terms, your IP address and the browser user-agent, " +
            "like any website you visit. Sett itself receives and stores nothing."

    val webProviders = listOf(
        Provider("duckduckgo", "DuckDuckGo", STANDARD_FLOW),
        Provider("brave", "Brave Search", STANDARD_FLOW),
        Provider("mojeek", "Mojeek", STANDARD_FLOW),
        Provider("startpage", "Startpage", STANDARD_FLOW),
        Provider(
            "searxng", "Your SearXNG instance",
            "Receives your search terms and IP — but it is a server YOU chose and control. " +
                "Sett sends queries only there.",
        ),
    )

    val categoryProviders: Map<Category, List<Provider>> = mapOf(
        Category.WEB to webProviders,
        Category.IMAGES to listOf(
            Provider("duckduckgo", "DuckDuckGo Images", STANDARD_FLOW),
            Provider("brave", "Brave Images", STANDARD_FLOW),
        ),
        Category.NEWS to listOf(
            Provider("duckduckgo", "DuckDuckGo News", STANDARD_FLOW),
            Provider("brave", "Brave News", STANDARD_FLOW),
        ),
        Category.VIDEOS to listOf(
            Provider("duckduckgo", "DuckDuckGo Videos", STANDARD_FLOW),
            Provider("brave", "Brave Videos", STANDARD_FLOW),
        ),
        Category.MAPS to listOf(
            Provider(
                "openstreetmap", "OpenStreetMap",
                "Receives your search terms and IP. OpenStreetMap is run by a " +
                    "non-profit foundation with a published privacy policy.",
            ),
            Provider(
                "google", "Google Maps",
                "Receives your search terms, IP and — if you are signed in anywhere " +
                    "in the browser — whatever Google ties to that session. Included " +
                    "because its local coverage is strong; it is not the private option.",
            ),
        ),
        Category.ACADEMIC to listOf(
            Provider(
                "openalex", "OpenAlex",
                "Receives your search terms and IP. OpenAlex is an open scholarly " +
                    "catalogue (OurResearch, non-profit).",
            ),
            Provider("semanticscholar", "Semantic Scholar", STANDARD_FLOW),
            Provider(
                "scholararchive", "Scholar Archive (Internet Archive)",
                "Receives your search terms and IP. Run by the Internet Archive.",
            ),
        ),
    )

    private fun enc(query: String): String =
        URLEncoder.encode(query.trim(), "UTF-8")

    /** Normalises a user-entered SearXNG base URL: trims, strips trailing slashes. */
    fun normalizeSearxngBase(raw: String): String = raw.trim().trimEnd('/')

    /**
     * The URL a search opens. [webEngineKey] is the SettingsStore web
     * engine (used only for the WEB category's duckduckgo/brave/mojeek/
     * startpage/searxng choice), [providerKey] the per-category choice
     * for the other categories.
     */
    fun route(
        category: Category,
        providerKey: String,
        query: String,
        searxngBase: String,
    ): String {
        val q = enc(query)
        return when (category) {
            Category.WEB -> when (providerKey) {
                "brave" -> "https://search.brave.com/search?q=$q"
                "mojeek" -> "https://www.mojeek.com/search?q=$q"
                "startpage" -> "https://www.startpage.com/sp/search?query=$q"
                "searxng" -> {
                    val base = normalizeSearxngBase(searxngBase)
                    if (base.isEmpty()) {
                        "https://html.duckduckgo.com/html/?q=$q"
                    } else {
                        "$base/search?q=$q"
                    }
                }
                else -> "https://html.duckduckgo.com/html/?q=$q"
            }
            Category.IMAGES -> when (providerKey) {
                "brave" -> "https://search.brave.com/images?q=$q"
                else -> "https://duckduckgo.com/?q=$q&iax=images&ia=images"
            }
            Category.NEWS -> when (providerKey) {
                "brave" -> "https://search.brave.com/news?q=$q"
                else -> "https://duckduckgo.com/?q=$q&ia=news&iar=news"
            }
            Category.VIDEOS -> when (providerKey) {
                "brave" -> "https://search.brave.com/videos?q=$q"
                else -> "https://duckduckgo.com/?q=$q&iax=videos&ia=videos"
            }
            Category.MAPS -> when (providerKey) {
                "google" -> "https://www.google.com/maps/search/$q"
                else -> "https://www.openstreetmap.org/search?query=$q"
            }
            Category.ACADEMIC -> when (providerKey) {
                "semanticscholar" -> "https://www.semanticscholar.org/search?q=$q"
                "scholararchive" -> "https://scholar.archive.org/search?q=$q"
                else -> "https://openalex.org/works?search=$q"
            }
        }
    }
}
