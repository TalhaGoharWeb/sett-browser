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
        BOOKS("Books"),
        PDFS("PDFs"),
        ACADEMIC("Academic"),
        CODE("Code"),
        FORUMS("Forums"),
        OPEN_SOURCE("Open source"),
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
            "searxng", "SearXNG (your instance or public)",
            "Receives your search terms and IP. Your own instance is a server you " +
                "control; a public one is an independent operator from the searx.space " +
                "registry, named on screen before every search. Sett sends queries " +
                "only to the instance shown.",
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
        Category.BOOKS to listOf(
            Provider(
                "openlibrary", "Open Library",
                "Receives your search terms and IP. Open Library is the Internet " +
                    "Archive's open catalogue of books.",
            ),
            Provider(
                "archive", "Internet Archive — texts",
                "Receives your search terms and IP. Run by the Internet Archive.",
            ),
        ),
        Category.PDFS to listOf(
            Provider(
                "duckduckgo", "DuckDuckGo + filetype:pdf",
                "Your query plus the text filetype:pdf goes to DuckDuckGo, which " +
                    "filters to PDF documents. " + STANDARD_FLOW,
            ),
            Provider(
                "brave", "Brave Search + filetype:pdf",
                "Your query plus the text filetype:pdf goes to Brave Search, which " +
                    "filters to PDF documents. " + STANDARD_FLOW,
            ),
        ),
        Category.CODE to listOf(
            Provider(
                "grepapp", "grep.app — code search",
                "Receives your search terms and IP. grep.app indexes public code " +
                    "repositories.",
            ),
            Provider(
                "sourcegraph", "Sourcegraph — public code",
                "Receives your search terms and IP. Sourcegraph's public code search.",
            ),
        ),
        Category.FORUMS to listOf(
            Provider(
                "stackoverflow", "Stack Overflow",
                "Receives your search terms and IP. Stack Overflow's own search.",
            ),
            Provider(
                "duckduckgo", "DuckDuckGo — forum sites only",
                "Your query plus site: filters for Stack Overflow, Reddit and " +
                    "Hacker News goes to DuckDuckGo. " + STANDARD_FLOW,
            ),
        ),
        Category.OPEN_SOURCE to listOf(
            Provider(
                "github", "GitHub — repositories",
                "Receives your search terms and IP. GitHub repository search is " +
                    "public; GitHub is owned by Microsoft.",
            ),
            Provider(
                "gitlab", "GitLab — projects",
                "Receives your search terms and IP. GitLab's public project search.",
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
                        "https://duckduckgo.com/?q=$q"
                    } else {
                        "$base/search?q=$q"
                    }
                }
                else -> "https://duckduckgo.com/?q=$q"
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
            Category.BOOKS -> when (providerKey) {
                "archive" -> "https://archive.org/search?query=$q"
                else -> "https://openlibrary.org/search?q=$q"
            }
            Category.PDFS -> {
                val filtered = enc("$query filetype:pdf")
                when (providerKey) {
                    "brave" -> "https://search.brave.com/search?q=$filtered"
                    else -> "https://duckduckgo.com/?q=$filtered"
                }
            }
            Category.CODE -> when (providerKey) {
                "sourcegraph" -> "https://sourcegraph.com/search?q=${enc("context:global $query")}"
                else -> "https://grep.app/search?q=$q"
            }
            Category.FORUMS -> when (providerKey) {
                "stackoverflow" -> "https://stackoverflow.com/search?q=$q"
                else -> "https://duckduckgo.com/?q=" +
                    enc("$query (site:stackoverflow.com OR site:reddit.com OR site:news.ycombinator.com)")
            }
            Category.OPEN_SOURCE -> when (providerKey) {
                "gitlab" -> "https://gitlab.com/explore/projects?name=$q"
                else -> "https://github.com/search?q=$q&type=repositories"
            }
        }
    }
}
