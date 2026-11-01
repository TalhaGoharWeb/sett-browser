package app.privatex.browser

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import app.privatex.browser.tools.SearchRouting
import kotlinx.coroutines.launch

/**
 * SETT Search — Search freely. Search intelligently.
 *
 * A white-label search experience (v0.25.0): Sett owns the brand,
 * interface and settings; the results come from existing
 * providers under their own terms. Two integration modes:
 *  - Custom results: a SearXNG instance's JSON API (an official
 *    feature of that software) — the user's own instance, or a
 *    public one chosen from the searx.space registry — plus the
 *    scholarly APIs (OpenAlex / Semantic Scholar). Rendered here.
 *  - Direct mode: every other provider's own results page opens
 *    in the browser, untouched.
 * DuckDuckGo HTML parsing was removed in v0.24.0 (its terms do
 * not offer that use). No crawler, no index, no Sett server, no
 * query logging beyond the on-device recents the user controls.
 */
class SearchActivity : AppCompatActivity() {

    private val components get() = (application as PrivateXApp).components
    private val ui by lazy { app.privatex.browser.ui.SettUi(this, components.settings) }
    private val BG get() = ui.palette.bg
    private val TEXT get() = ui.palette.text
    private val DIM get() = ui.palette.dim
    private val TEAL get() = ui.palette.accent
    private val FAINT get() = ui.palette.faint
    private var selectedCategory: SearchRouting.Category = SearchRouting.Category.WEB
    private var initialQuery: String = ""
    private var autoRun: Boolean = false
    private var brockBitmap: android.graphics.Bitmap? = null
    /** Public SearXNG candidates from the last registry fetch. */
    private var publicCandidates:
        List<app.privatex.browser.search.SearxngInstances.Instance> = emptyList()
    private var loadingInstances = false
    private var instanceLabelView: TextView? = null
    private val faviconCache = mutableMapOf<String, android.graphics.Bitmap?>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui.applyPlatformTheme()
        // Home-page category chips (redesign Phase 3) preselect here.
        intent.getStringExtra(EXTRA_CATEGORY)?.let { key ->
            selectedCategory = SearchRouting.Category.entries
                .firstOrNull { it.name == key } ?: SearchRouting.Category.WEB
        }
        // Home-page search field (white-label flow): a general query
        // arrives here to run as a SETT Search.
        initialQuery = intent.getStringExtra(EXTRA_QUERY).orEmpty()
        autoRun = intent.getBooleanExtra(EXTRA_AUTORUN, false)
        brockBitmap = runCatching {
            assets.open("brock.png").use {
                android.graphics.BitmapFactory.decodeStream(it)
            }
        }.getOrNull()
        showSearch()
    }



    private fun providerKeyFor(category: SearchRouting.Category): String {
        if (category == SearchRouting.Category.WEB) {
            return components.settings.searchEngine
        }
        val stored = components.settings.searchCategoryProvider(category.name)
        val providers = SearchRouting.categoryProviders.getValue(category)
        return providers.firstOrNull { it.key == stored }?.key ?: providers.first().key
    }

    private fun showSearch() {
        val settings = components.settings
        val layout = page("SETT Search")
        brockBitmap?.let { bmp ->
            layout.addView(android.widget.ImageView(this).apply {
                setImageBitmap(bmp)
                contentDescription = "Brock the badger, SETT Search mascot"
                layoutParams = LinearLayout.LayoutParams(dp(76), dp(76)).apply {
                    gravity = android.view.Gravity.CENTER_HORIZONTAL
                }
            })
        }
        layout.addView(TextView(this).apply {
            text = "Search freely. Search intelligently."
            textSize = 14f
            setTextColor(TEAL)
            gravity = android.view.Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(2), 0, dp(8))
        })
        layout.addView(body(
            "Sett has no search server, crawler or index, and receives " +
                "nothing. In custom-results mode a SearXNG instance — " +
                "your own, or a public one from the searx.space list — " +
                "answers through its official JSON API and Sett renders " +
                "the results here; every other provider opens its own " +
                "results page in the browser. What each provider " +
                "receives is written under Data flow below.",
        ))
        val query = field("Search…")
        layout.addView(query)
        val suggestBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        layout.addView(suggestBox)

        val chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val chipButtons = mutableListOf<Button>()
        val providerSpinner = Spinner(this)
        val flowNote = body("")
        fun refreshProviders() {
            val providers = SearchRouting.categoryProviders.getValue(selectedCategory)
            providerSpinner.adapter = ArrayAdapter(
                this,
                android.R.layout.simple_spinner_dropdown_item,
                providers.map { it.name },
            )
            val current = providerKeyFor(selectedCategory)
            providerSpinner.setSelection(providers.indexOfFirst { it.key == current }.coerceAtLeast(0))
        }
        fun refreshChips() {
            chipButtons.forEachIndexed { index, button ->
                val category = SearchRouting.Category.entries[index]
                button.setTextColor(if (category == selectedCategory) TEAL else DIM)
            }
        }
        SearchRouting.Category.entries.forEach { category ->
            val button = Button(this).apply {
                text = category.label
                textSize = 13f
                setOnClickListener {
                    selectedCategory = category
                    refreshChips()
                    refreshProviders()
                }
            }
            chipButtons.add(button)
            chips.addView(button)
        }
        layout.addView(android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(chips)
        })
        layout.addView(providerSpinner)
        layout.addView(flowNote)
        providerSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long,
            ) {
                val provider = SearchRouting.categoryProviders
                    .getValue(selectedCategory)[position]
                if (selectedCategory == SearchRouting.Category.WEB) {
                    settings.searchEngine = provider.key
                } else {
                    settings.setSearchCategoryProvider(selectedCategory.name, provider.key)
                }
                flowNote.text = provider.dataFlow
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        val status = body("")
        val resultsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        var searxngPage = 1
        var lastNativeQuery = ""
        val loadMore = Button(this).apply {
            text = "Load more results"
            visibility = View.GONE
        }

        fun openInBrowser(url: String, newTab: Boolean = false) {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                .setClass(this@SearchActivity, BrowserActivity::class.java)
            if (newTab) {
                startActivity(intent)
            } else {
                intent.putExtra(EXTRA_LOAD_IN_CURRENT, true)
                startActivity(intent)
                finish()
            }
        }

        fun shareLink(url: String) {
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, url)
            }
            startActivity(Intent.createChooser(send, "Share link"))
        }

        fun bookmarkResult(title: String, url: String) {
            lifecycleScope.launch {
                val added = runCatching {
                    components.bookmarksStorage.addItem(
                        mozilla.appservices.places.BookmarkRoot.Mobile.id,
                        url, title, null,
                    )
                }.getOrNull()
                toast(
                    if (added?.isSuccess == true) "Bookmarked" else "Could not bookmark",
                )
            }
        }

        /** Result favicons load from each result's own site (never a
         *  third-party favicon service); any failure keeps the
         *  letter tile. Small in-memory cache only. */
        fun faviconInto(host: String, target: android.widget.ImageView) {
            if (faviconCache.containsKey(host)) {
                faviconCache[host]?.let {
                    target.setImageBitmap(it)
                    target.visibility = View.VISIBLE
                }
                return
            }
            lifecycleScope.launch {
                val bmp = runCatching {
                    val bytes = kotlinx.coroutines.withContext(
                        kotlinx.coroutines.Dispatchers.IO,
                    ) {
                        val conn = java.net.URL("https://$host/favicon.ico")
                            .openConnection() as java.net.HttpURLConnection
                        conn.connectTimeout = 6_000
                        conn.readTimeout = 6_000
                        conn.instanceFollowRedirects = true
                        conn.inputStream.use { input ->
                            val out = java.io.ByteArrayOutputStream()
                            val buf = ByteArray(4096)
                            var total = 0
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0 || total > 65_536) break
                                out.write(buf, 0, n)
                                total += n
                            }
                            out.toByteArray()
                        }
                    }
                    android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                }.getOrNull()
                if (faviconCache.size > 96) faviconCache.clear()
                faviconCache[host] = bmp
                if (bmp != null) {
                    target.setImageBitmap(bmp)
                    target.visibility = View.VISIBLE
                }
            }
        }

        fun renderResults(
            results: List<app.privatex.browser.tools.SearchResults.Result>,
            sourceLabel: String,
            append: Boolean,
        ) {
            if (!append) resultsBox.removeAllViews()
            if (results.isEmpty() && !append) {
                status.text = "No results came back. The provider may be unreachable " +
                    "or returned an empty page — nothing was substituted."
                return
            }
            status.text = sourceLabel
            results.forEach { r ->
                val host = app.privatex.browser.home.HomeStore.hostOf(r.url)
                val favicon = android.widget.ImageView(this).apply {
                    visibility = View.INVISIBLE
                    layoutParams = LinearLayout.LayoutParams(dp(28), dp(28))
                }
                val favBox = android.widget.FrameLayout(this).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(36), dp(36)).apply {
                        setMargins(0, dp(2), dp(10), 0)
                    }
                    addView(TextView(this@SearchActivity).apply {
                        text = (r.title.firstOrNull { it.isLetterOrDigit() }
                            ?: host.firstOrNull() ?: 'S').uppercaseChar().toString()
                        textSize = 15f
                        setTextColor(TEAL)
                        gravity = android.view.Gravity.CENTER
                        layoutParams = android.widget.FrameLayout.LayoutParams(
                            dp(36), dp(36),
                        )
                    })
                    addView(favicon)
                }
                val texts = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
                }
                val card = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(dp(12), dp(10), dp(12), dp(10))
                    setOnClickListener { openInBrowser(r.url) }
                    setOnLongClickListener {
                        val actions = arrayOf(
                            "Open", "Open in new tab", "Share", "Copy link",
                            "Bookmark", "Save to reading list",
                        )
                        AlertDialog.Builder(this@SearchActivity)
                            .setTitle(r.title)
                            .setItems(actions) { _, which ->
                                when (which) {
                                    0 -> openInBrowser(r.url)
                                    1 -> openInBrowser(r.url, newTab = true)
                                    2 -> shareLink(r.url)
                                    3 -> {
                                        val cm = getSystemService(CLIPBOARD_SERVICE)
                                            as android.content.ClipboardManager
                                        cm.setPrimaryClip(
                                            android.content.ClipData.newPlainText("link", r.url),
                                        )
                                        toast("Link copied")
                                    }
                                    4 -> bookmarkResult(r.title, r.url)
                                    5 -> {
                                        components.readingListStore.add(r.title, r.url)
                                        toast("Saved to reading list")
                                    }
                                }
                            }
                            .show()
                        true
                    }
                }
                card.contentDescription = "${r.title}, $host"
                texts.addView(TextView(this).apply {
                    text = r.title
                    textSize = 15f
                    setTextColor(TEXT)
                })
                texts.addView(TextView(this).apply {
                    text = host +
                        if (r.source.isNotBlank()) "  ·  via ${r.source}" else ""
                    textSize = 12f
                    setTextColor(TEAL)
                })
                if (r.snippet.isNotBlank()) {
                    texts.addView(TextView(this).apply {
                        text = r.snippet
                        textSize = 13f
                        setTextColor(DIM)
                    })
                }
                card.addView(favBox)
                card.addView(texts)
                resultsBox.addView(card)
                faviconInto(host, favicon)
            }
        }

        suspend fun fetchText(url: String): String =
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val conn = java.net.URL(url).openConnection()
                    as java.net.HttpURLConnection
                conn.connectTimeout = 12_000
                conn.readTimeout = 15_000
                conn.instanceFollowRedirects = true
                conn.setRequestProperty(
                    "User-Agent",
                    "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Sett",
                )
                conn.inputStream.use { input ->
                    val out = java.io.ByteArrayOutputStream()
                    val buf = ByteArray(8192)
                    var total = 0
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0 || total > 1_500_000) break
                        out.write(buf, 0, n)
                        total += n
                    }
                    String(out.toByteArray(), Charsets.UTF_8)
                }
            }

        fun safeLevel(): app.privatex.browser.tools.SearchResults.SafeLevel =
            runCatching {
                app.privatex.browser.tools.SearchResults.SafeLevel
                    .valueOf(settings.searchSafeLevel)
            }.getOrDefault(app.privatex.browser.tools.SearchResults.SafeLevel.STRICT)

        fun effectiveSearxngBase(): String =
            settings.searxngUrl.ifBlank { settings.searxngPublicInstance }

        fun searxngLabel(base: String): String =
            if (settings.searxngUrl.isNotBlank() && base == settings.searxngUrl) {
                "your SearXNG instance"
            } else {
                "public SearXNG instance " +
                    app.privatex.browser.search.SearxngInstances.hostOf(base)
            }

        fun refreshInstanceLabel() {
            val own = settings.searxngUrl
            val pub = settings.searxngPublicInstance
            instanceLabelView?.text = when {
                own.isNotBlank() -> "Custom results via: your own instance (" +
                    app.privatex.browser.search.SearxngInstances.hostOf(own) + ")"
                pub.isNotBlank() -> "Custom results via: public instance " +
                    app.privatex.browser.search.SearxngInstances.hostOf(pub) +
                    " (searx.space registry)"
                else -> "Custom results via: no instance picked yet — a search " +
                    "fetches the public list, or set your own below."
            }
        }

        fun nextPublicCandidate(currentBase: String):
            app.privatex.browser.search.SearxngInstances.Instance? {
            if (publicCandidates.size < 2) return null
            val idx = publicCandidates.indexOfFirst { it.baseUrl == currentBase }
            return publicCandidates.getOrNull(idx + 1) ?: publicCandidates.first()
        }

        fun loadPublicInstances(then: () -> Unit) {
            if (loadingInstances) return
            loadingInstances = true
            status.text = "Fetching the public SearXNG instance list (searx.space)…"
            lifecycleScope.launch {
                val list = runCatching {
                    app.privatex.browser.search.SearxngInstances.parse(
                        fetchText(
                            app.privatex.browser.search.SearxngInstances.LIST_URL,
                        ),
                    )
                }.getOrDefault(emptyList())
                loadingInstances = false
                if (list.isEmpty()) {
                    status.text = "Could not load the public instance list. " +
                        "Check the connection, set your own instance below, " +
                        "or choose another provider."
                    return@launch
                }
                publicCandidates = list
                if (settings.searxngPublicInstance.isBlank() ||
                    list.none { it.baseUrl == settings.searxngPublicInstance }
                ) {
                    settings.searxngPublicInstance = list.first().baseUrl
                }
                refreshInstanceLabel()
                then()
            }
        }

        fun runNativeSearxng(searchText: String, pageNo: Int, attempt: Int = 0) {
            val base = effectiveSearxngBase()
            lastNativeQuery = searchText
            status.text = "Fetching results from ${searxngLabel(base)}…"
            lifecycleScope.launch {
                runCatching {
                    val url = app.privatex.browser.tools.SearchResults.searxngUrl(
                        base, searchText, selectedCategory,
                        safeLevel(), settings.searchLanguage, pageNo,
                        settings.searchTimeRange,
                    )
                    val raw = fetchText(url)
                    if (!app.privatex.browser.tools.SearchResults
                            .looksLikeSearxngJson(raw)
                    ) {
                        throw java.io.IOException(
                            "instance did not return JSON results",
                        )
                    }
                    val results = app.privatex.browser.tools.SearchResults
                        .parseSearxng(raw)
                    renderResults(
                        results,
                        "Results via ${searxngLabel(base)} — rendered by Sett. " +
                            "Engine names are shown per result.",
                        append = pageNo > 1,
                    )
                    loadMore.visibility = if (results.isNotEmpty()) View.VISIBLE else View.GONE
                }.onFailure {
                    // A public instance may decline the JSON format or
                    // be down: try the next registry candidate ONCE,
                    // saying so on screen — never a silent provider
                    // switch, only another instance of the same one.
                    val next = nextPublicCandidate(base)
                    if (attempt == 0 && settings.searxngUrl.isBlank() && next != null) {
                        settings.searxngPublicInstance = next.baseUrl
                        refreshInstanceLabel()
                        status.text = "${searxngLabel(base)} did not answer " +
                            "with results — trying ${next.host} instead."
                        runNativeSearxng(searchText, pageNo, attempt + 1)
                    } else {
                        status.text = "Could not fetch from ${searxngLabel(base)} " +
                            "(${it.message ?: "network error"}). Try another " +
                            "instance below, or open its results page instead:"
                        loadMore.visibility = View.GONE
                        resultsBox.removeAllViews()
                        resultsBox.addView(Button(this@SearchActivity).apply {
                            text = "Open SearXNG results page"
                            setOnClickListener {
                                openInBrowser(
                                    SearchRouting.route(
                                        selectedCategory, "searxng", searchText, base,
                                    ),
                                )
                            }
                        })
                    }
                }
            }
        }

        fun renderWorks(
            works: List<app.privatex.browser.tools.ScholarTools.Work>,
            sourceLabel: String,
        ) {
            resultsBox.removeAllViews()
            if (works.isEmpty()) {
                status.text = "No scholarly results came back — nothing was substituted."
                return
            }
            status.text = sourceLabel
            works.forEach { w ->
                val landing = w.doi?.let { "https://doi.org/$it" } ?: w.url ?: ""
                val card = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(12), dp(10), dp(12), dp(10))
                    if (landing.isNotBlank()) {
                        setOnClickListener { openInBrowser(landing) }
                    }
                    setOnLongClickListener {
                        val labels = mutableListOf<String>()
                        val runners = mutableListOf<() -> Unit>()
                        if (landing.isNotBlank()) {
                            labels.add("Open")
                            runners.add { openInBrowser(landing) }
                            labels.add("Open in new tab")
                            runners.add { openInBrowser(landing, newTab = true) }
                            labels.add("Share")
                            runners.add { shareLink(landing) }
                            labels.add("Bookmark")
                            runners.add { bookmarkResult(w.title, landing) }
                        }
                        if (w.oaUrl != null) {
                            labels.add("Open open-access PDF")
                            runners.add { openInBrowser(w.oaUrl) }
                        }
                        if (w.doi != null) {
                            labels.add("Copy DOI")
                            runners.add {
                                val cm = getSystemService(CLIPBOARD_SERVICE)
                                    as android.content.ClipboardManager
                                cm.setPrimaryClip(
                                    android.content.ClipData.newPlainText("doi", w.doi),
                                )
                                toast("DOI copied")
                            }
                        }
                        if (landing.isNotBlank()) {
                            labels.add("Save to reading list")
                            runners.add {
                                components.readingListStore.add(w.title, landing)
                                toast("Saved to reading list")
                            }
                        }
                        if (labels.isNotEmpty()) {
                            AlertDialog.Builder(this@SearchActivity)
                                .setTitle(w.title)
                                .setItems(labels.toTypedArray()) { _, which ->
                                    runners[which]()
                                }
                                .show()
                        }
                        true
                    }
                }
                card.contentDescription =
                    "${w.title}, ${w.origin}${w.year?.let { ", $it" } ?: ""}"
                card.addView(TextView(this).apply {
                    text = w.title
                    textSize = 15f
                    setTextColor(TEXT)
                })
                val meta = buildString {
                    if (w.authors.isNotEmpty()) {
                        append(w.authors.take(3).joinToString(", "))
                        if (w.authors.size > 3) append(" et al.")
                    }
                    w.year?.let { append(" · ").append(it) }
                    w.source?.let { append(" · ").append(it) }
                }
                if (meta.isNotBlank()) {
                    card.addView(TextView(this).apply {
                        text = meta
                        textSize = 12.5f
                        setTextColor(DIM)
                    })
                }
                val badges = buildString {
                    append(w.origin)
                    w.citedByCount?.let { append(" · cited by ").append(it) }
                    if (w.openAccess == true) append(" · OPEN ACCESS")
                    w.doi?.let { append(" · doi:").append(it) }
                }
                card.addView(TextView(this).apply {
                    text = badges
                    textSize = 12f
                    setTextColor(if (w.openAccess == true) TEAL else FAINT)
                })
                resultsBox.addView(card)
            }
        }

        fun runNativeScholar(searchText: String) {
            val providerKey = providerKeyFor(selectedCategory)
            status.text = "Fetching scholarly results…"
            loadMore.visibility = View.GONE
            lifecycleScope.launch {
                runCatching {
                    val enc = java.net.URLEncoder.encode(searchText, "UTF-8")
                    val works = when (providerKey) {
                        "semanticscholar" -> app.privatex.browser.tools.ScholarTools
                            .parseSemanticScholar(
                                fetchText(
                                    "https://api.semanticscholar.org/graph/v1/paper/search" +
                                        "?query=$enc&limit=15&fields=title,authors,year," +
                                        "externalIds,venue,citationCount,openAccessPdf",
                                ),
                            )
                        else -> app.privatex.browser.tools.ScholarTools.parseOpenAlex(
                            fetchText(
                                "https://api.openalex.org/works?search=$enc&per-page=15",
                            ),
                        )
                    }
                    val name = if (providerKey == "semanticscholar") {
                        "Semantic Scholar"
                    } else {
                        "OpenAlex"
                    }
                    renderWorks(
                        works,
                        "Scholarly results from $name — catalogue metadata; " +
                            "check the source before citing.",
                    )
                }.onFailure {
                    status.text = "Could not fetch scholarly results " +
                        "(${it.message ?: "network error"}). Nothing was substituted — " +
                        "open the provider page instead:"
                    resultsBox.removeAllViews()
                    resultsBox.addView(Button(this@SearchActivity).apply {
                        text = "Open provider results page"
                        setOnClickListener {
                            openInBrowser(
                                SearchRouting.route(
                                    selectedCategory,
                                    providerKeyFor(selectedCategory),
                                    searchText,
                                    settings.searxngUrl,
                                ),
                            )
                        }
                    })
                }
            }
        }

        fun runSearch() {
            val text = query.text.toString().trim()
            if (text.isEmpty()) {
                toast("Type a search first")
                return
            }
            if (settings.searchKeepHistory) {
                components.searchHistoryStore.record(text)
            }
            val providerKey = providerKeyFor(selectedCategory)
            val searxngReady = providerKey == "searxng" &&
                effectiveSearxngBase().isNotBlank()
            val scholarNative = selectedCategory == SearchRouting.Category.ACADEMIC &&
                providerKey in setOf("openalex", "semanticscholar")
            when {
                searxngReady -> {
                    searxngPage = 1
                    runNativeSearxng(text, 1)
                }
                providerKey == "searxng" -> loadPublicInstances { runSearch() }
                scholarNative -> runNativeScholar(text)
                else -> {
                    val url = SearchRouting.route(
                        selectedCategory, providerKey, text, settings.searxngUrl,
                    )
                    openInBrowser(url)
                    finish()
                }
            }
        }
        loadMore.setOnClickListener {
            searxngPage += 1
            runNativeSearxng(lastNativeQuery, searxngPage)
        }
        layout.addView(Button(this).apply {
            text = "Search"
            setOnClickListener { runSearch() }
        })
        layout.addView(status)
        layout.addView(resultsBox)
        layout.addView(loadMore)

        // Suggestions: opt-in only (Settings below). When ON, partial
        // queries go to DuckDuckGo's autocomplete — disclosed here
        // and next to the toggle.
        var suggestSeq = 0
        query.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) {
                if (!settings.searchSuggestions) {
                    suggestBox.removeAllViews()
                    return
                }
                val text = s?.toString()?.trim().orEmpty()
                if (text.length < 2) {
                    suggestBox.removeAllViews()
                    return
                }
                val seq = ++suggestSeq
                lifecycleScope.launch {
                    kotlinx.coroutines.delay(250)
                    if (seq != suggestSeq) return@launch
                    val found = runCatching {
                        app.privatex.browser.tools.SearchResults.parseSuggestions(
                            fetchText(
                                app.privatex.browser.tools.SearchResults.SUGGESTIONS_URL +
                                    java.net.URLEncoder.encode(text, "UTF-8"),
                            ),
                        )
                    }.getOrDefault(emptyList())
                    if (seq != suggestSeq) return@launch
                    suggestBox.removeAllViews()
                    found.take(6).forEach { phrase ->
                        suggestBox.addView(TextView(this@SearchActivity).apply {
                            this.text = "↳ $phrase"
                            textSize = 14f
                            setTextColor(DIM)
                            setPadding(dp(10), dp(9), dp(10), dp(9))
                            setOnClickListener {
                                query.setText(phrase)
                                query.setSelection(phrase.length)
                                runSearch()
                            }
                        })
                    }
                }
            }

            override fun beforeTextChanged(
                s: CharSequence?, start: Int, count: Int, after: Int,
            ) = Unit

            override fun onTextChanged(
                s: CharSequence?, start: Int, before: Int, count: Int,
            ) = Unit
        })

        layout.addView(section("Recent searches"))
        val recentsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fun renderRecents() {
            recentsBox.removeAllViews()
            if (!settings.searchKeepHistory) {
                recentsBox.addView(body("Recent searches are off — nothing is kept."))
                return
            }
            val recent = components.searchHistoryStore.recent()
            if (recent.isEmpty()) {
                recentsBox.addView(body("None yet. Searches you run are kept on this device only."))
                return
            }
            recent.forEach { past ->
                recentsBox.addView(TextView(this).apply {
                    text = past
                    textSize = 14f
                    setTextColor(TEXT)
                    setPadding(dp(10), dp(9), dp(10), dp(9))
                    setOnClickListener {
                        query.setText(past)
                        runSearch()
                    }
                })
            }
            recentsBox.addView(Button(this).apply {
                text = "Clear recent searches"
                setOnClickListener {
                    components.searchHistoryStore.clear()
                    renderRecents()
                    toast("Recent searches cleared")
                }
            })
        }
        layout.addView(recentsBox)
        renderRecents()

        layout.addView(section("Search options"))
        layout.addView(body("SafeSearch — a real parameter sent to the provider (DuckDuckGo kp, SearXNG safesearch):"))
        val safeSpinner = Spinner(this)
        val safeLevels = app.privatex.browser.tools.SearchResults.SafeLevel.entries
        safeSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            safeLevels.map { it.label },
        )
        safeSpinner.setSelection(safeLevels.indexOf(safeLevel()).coerceAtLeast(0))
        safeSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?, view: View?, position: Int, id: Long,
            ) {
                settings.searchSafeLevel = safeLevels[position].name
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        layout.addView(safeSpinner)
        layout.addView(body(
            "Language / region code — SearXNG language (e.g. en, ur, all) and " +
                "DuckDuckGo region in xx-xx form (e.g. pk-en). Blank = provider default.",
        ))
        val langField = field("Language code (optional)").apply {
            setText(settings.searchLanguage)
        }
        langField.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) {
                settings.searchLanguage = s?.toString()?.trim().orEmpty()
            }

            override fun beforeTextChanged(
                s: CharSequence?, start: Int, count: Int, after: Int,
            ) = Unit

            override fun onTextChanged(
                s: CharSequence?, start: Int, before: Int, count: Int,
            ) = Unit
        })
        layout.addView(langField)
        layout.addView(body("Time range — a real SearXNG parameter (time_range); direct-mode providers apply their own filters on their pages:"))
        val timeSpinner = Spinner(this)
        val timeLabels = listOf("Any time", "Past day", "Past week", "Past month", "Past year")
        val timeValues = app.privatex.browser.tools.SearchResults.TIME_RANGES
        timeSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            timeLabels,
        )
        timeSpinner.setSelection(
            timeValues.indexOf(settings.searchTimeRange).coerceAtLeast(0),
        )
        timeSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?, view: View?, position: Int, id: Long,
            ) {
                settings.searchTimeRange = timeValues[position]
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        layout.addView(timeSpinner)
        layout.addView(android.widget.CheckBox(this).apply {
            text = "Query suggestions while typing (sends partial queries to DuckDuckGo autocomplete)"
            isChecked = settings.searchSuggestions
            setTextColor(TEXT)
            setOnCheckedChangeListener { _, checked ->
                settings.searchSuggestions = checked
                if (!checked) suggestBox.removeAllViews()
            }
        })
        layout.addView(android.widget.CheckBox(this).apply {
            text = "Keep recent searches on this device"
            isChecked = settings.searchKeepHistory
            setTextColor(TEXT)
            setOnCheckedChangeListener { _, checked ->
                settings.searchKeepHistory = checked
                renderRecents()
            }
        })
        refreshChips()
        refreshProviders()

        layout.addView(section("SearXNG instance — custom results"))
        layout.addView(body(
            "Custom-results mode needs a SearXNG instance. Use your own, " +
                "or a public one: public instances are independent " +
                "operators listed in the SearXNG project's registry " +
                "(searx.space); Sett picks from its reliability data and " +
                "names the instance in use on every results screen. " +
                "Your queries go to that instance and are covered by " +
                "its operator's privacy policy — Sett cannot vouch for " +
                "a third party's logs.",
        ))
        instanceLabelView = body("")
        layout.addView(instanceLabelView!!)
        refreshInstanceLabel()
        val instanceButtons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        instanceButtons.addView(Button(this).apply {
            text = "Refresh public list"
            setOnClickListener {
                loadPublicInstances {
                    toast("Public instance list updated")
                }
            }
        })
        instanceButtons.addView(Button(this).apply {
            text = "Use another instance"
            setOnClickListener {
                val next = nextPublicCandidate(effectiveSearxngBase())
                if (next != null) {
                    settings.searxngPublicInstance = next.baseUrl
                    refreshInstanceLabel()
                    toast("Now using ${next.host}")
                } else {
                    loadPublicInstances {
                        val first = publicCandidates.firstOrNull()
                        if (first != null) {
                            toast("Now using ${first.host}")
                        }
                    }
                }
            }
        })
        layout.addView(instanceButtons)
        layout.addView(body(
            "Your own instance (optional) — takes precedence over the " +
                "public one while set. Clear it to return to public.",
        ))
        val searxngField = field("https://search.example.org").apply {
            setText(settings.searxngUrl)
        }
        layout.addView(searxngField)
        layout.addView(Button(this).apply {
            text = "Save instance URL"
            setOnClickListener {
                settings.searxngUrl = SearchRouting.normalizeSearxngBase(searxngField.text.toString())
                refreshInstanceLabel()
                toast("Saved")
            }
        })

        layout.addView(section("Data flow — what each provider receives"))
        SearchRouting.categoryProviders.forEach { (category, providers) ->
            providers.distinctBy { it.key }.forEach { provider ->
                layout.addView(body("• ${category.label} · ${provider.name}: ${provider.dataFlow}"))
            }
        }
        layout.addView(body(
            "Suggestions are OFF unless you enable them above: with them off, " +
                "Sett sends nothing while you type. Your query leaves the device " +
                "only when you press Search, and only to the provider shown.",
        ))
        layout.addView(body(
            "Result favicons load directly from each result's own site " +
                "(that site sees a request for its icon, as it would when " +
                "you visit); no favicon service is involved. Failed icons " +
                "fall back to a letter tile.",
        ))
        if (initialQuery.isNotBlank()) {
            query.setText(initialQuery)
            if (autoRun) runSearch()
        }
        setContentView(ScrollView(this).apply { addView(layout) })
    }

    // ------------------------------------------------------------ plumbing

    private fun page(titleText: String): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(BG)
        setPadding(dp(18), dp(18), dp(18), dp(16))
        addView(TextView(this@SearchActivity).apply {
            text = titleText
            textSize = 20f
            setTextColor(TEXT)
            setPadding(0, 0, 0, dp(10))
        })
    }

    private fun section(text: String) = TextView(this).apply {
        this.text = text
        textSize = 16f
        setTextColor(TEXT)
        setPadding(0, dp(16), 0, dp(6))
    }

    private fun body(text: String) = TextView(this).apply {
        this.text = text
        textSize = 13f
        setTextColor(DIM)
        setPadding(0, dp(4), 0, dp(8))
    }

    private fun field(hint: String) = EditText(this).apply {
        this.hint = hint
        textSize = 15f
        setTextColor(TEXT)
        setHintTextColor(DIM)
        setPadding(0, dp(8), 0, dp(8))
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_CATEGORY = "sett_search_category"
        const val EXTRA_QUERY = "sett_search_query"
        const val EXTRA_AUTORUN = "sett_search_autorun"
    }
}
