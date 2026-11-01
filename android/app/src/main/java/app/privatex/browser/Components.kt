package app.privatex.browser

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import mozilla.components.browser.engine.gecko.GeckoEngine
import mozilla.components.browser.session.storage.SessionStorage
import mozilla.components.browser.state.engine.EngineMiddleware
import mozilla.components.browser.state.state.BrowserState
import mozilla.components.browser.state.state.recover.toTabSessionStates
import mozilla.components.browser.state.store.BrowserStore
import mozilla.components.feature.prompts.PromptMiddleware
import mozilla.components.feature.session.middleware.undo.UndoMiddleware
import mozilla.components.concept.engine.DefaultSettings
import mozilla.components.concept.engine.Engine
import mozilla.components.concept.engine.EngineSession
import mozilla.components.concept.engine.preferences.BrowserPreferencesRuntime
import mozilla.components.browser.errorpages.ErrorPages
import mozilla.components.concept.engine.request.ErrorType
import mozilla.components.concept.engine.request.RequestInterceptor
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import mozilla.components.browser.storage.sync.PlacesBookmarksStorage
import mozilla.components.browser.storage.sync.PlacesHistoryStorage
import mozilla.components.concept.base.crash.Breadcrumb
import mozilla.components.concept.base.crash.CrashReporting
import mozilla.components.feature.downloads.DownloadsUseCases
import mozilla.components.feature.readerview.ReaderViewMiddleware
import mozilla.components.feature.recentlyclosed.RecentlyClosedMiddleware
import mozilla.components.feature.recentlyclosed.RecentlyClosedTabsStorage
import mozilla.components.feature.session.HistoryDelegate
import mozilla.components.feature.session.SessionUseCases
import mozilla.components.feature.tabs.TabsUseCases
import mozilla.components.lib.fetch.httpurlconnection.HttpURLConnectionClient
import mozilla.components.support.base.android.NotificationsDelegate
import androidx.core.app.NotificationManagerCompat
import org.mozilla.geckoview.GeckoRuntime

/**
 * Wires the browser: GeckoView engine (Mozilla's prebuilt build) via
 * Android Components. The engine's privacy behaviour is configured here —
 * this is the single place Private X's defaults live.
 */
class Components(val appContext: Context) {

    val settings = SettingsStore(appContext)

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Caught exceptions from Mozilla's storage layer are recorded to the
     * same local-only crash log as fatal crashes (PrivateXApp.CrashLog).
     * Nothing is transmitted anywhere — there is no crash service.
     */
    val crashReporter: CrashReporting = object : CrashReporting {
        override fun submitCaughtException(throwable: Throwable): Job =
            appScope.launch { CrashLog.recordCaught(appContext, throwable) }

        override fun recordCrashBreadcrumb(breadcrumb: Breadcrumb) = Unit
    }

    /**
     * The places database (Mozilla's Rust storage): history and
     * bookmarks live on-device in one store, behind AC's storage
     * interfaces. Sync stays off — Sett has no account system.
     */
    val historyStorage by lazy { PlacesHistoryStorage(appContext, crashReporter) }
    val bookmarksStorage by lazy { PlacesBookmarksStorage(appContext) }

    val recentlyClosedTabsStorage by lazy {
        RecentlyClosedTabsStorage(appContext, engine, crashReporter)
    }

    /** libsodium (bundled native build) — the vault's crypto engine. */
    val sodium by lazy {
        com.goterl.lazysodium.LazySodiumAndroid(com.goterl.lazysodium.SodiumAndroid())
    }

    /** The encrypted password vault (Phase 4). One instance; lock state lives here. */
    val vault by lazy {
        app.privatex.browser.vault.VaultCore(
            sodium,
            java.io.File(appContext.filesDir, "sett-vault.json"),
        )
    }

    /** Research collections (Phase 8): saved works + notes, local JSON storage. */
    val researchStore by lazy {
        app.privatex.browser.research.ResearchStore(
            java.io.File(appContext.filesDir, "sett-research.json"),
        )
    }

    /** Study Center store (Phase 9): decks, vocabulary, notes, reading log. */
    /** Home-page shortcuts (redesign Phase 3). */
    val homeStore by lazy {
        app.privatex.browser.home.HomeStore(
            java.io.File(appContext.filesDir, "sett-home.json"),
        )
    }

    val studyStore by lazy {
        app.privatex.browser.study.StudyStore(
            java.io.File(appContext.filesDir, "sett-study.json"),
        )
    }

    val runtime: GeckoRuntime by lazy { GeckoRuntime.create(appContext) }

    val engine: Engine by lazy {
        GeckoEngine(
            appContext,
            DefaultSettings(
                trackingProtectionPolicy = if (settings.etpStrict) {
                    EngineSession.TrackingProtectionPolicy.strict()
                } else {
                    EngineSession.TrackingProtectionPolicy.recommended()
                },
                httpsOnlyMode = Engine.HttpsOnlyMode.ENABLED,
                globalPrivacyControlEnabled = true,
                fingerprintingProtection = true,
                queryParameterStripping = true,
                dohSettingsMode = Engine.DohSettingsMode.DEFAULT,
                remoteDebuggingEnabled = settings.remoteDebugging,
                javascriptEnabled = true,
                historyTrackingDelegate = HistoryDelegate(lazy { historyStorage }),
                requestInterceptor = HomeInterceptor(appContext, settings, homeStore),
            ),
            runtime,
        ).also { applyGeckoPrefs(it) }
    }

    /**
     * Engine preferences GeckoView does not surface as settings:
     * Mozilla telemetry/experimentation off, and the optional strict
     * fingerprinting profile (resistFingerprinting + letterboxing).
     */
    @OptIn(mozilla.components.ExperimentalAndroidComponentsApi::class)
    private fun applyGeckoPrefs(engine: Engine) {
        val prefs = engine as? BrowserPreferencesRuntime ?: return
        fun set(name: String, value: Boolean) {
            prefs.setBrowserPref(
                name,
                value,
                mozilla.components.concept.engine.preferences.Branch.USER,
                {},
                {},
            )
        }
        set("toolkit.telemetry.enabled", false)
        set("datareporting.healthreport.uploadEnabled", false)
        set("datareporting.policy.dataSubmissionEnabled", false)
        set("app.normandy.enabled", false)
        set("app.shield.optoutstudies.enabled", false)
        set("browser.ping-centre.telemetry", false)
        set("network.trr.confirmation_telemetry_enabled", false)
        // Fingerprinting: the strict profile wins when on; otherwise
        // the individual Custom-mode toggles from the Shield screen.
        val resist = settings.strictFingerprinting || settings.fpResist
        val letterbox = settings.strictFingerprinting || settings.fpLetterbox
        if (resist) set("privacy.resistFingerprinting", true)
        if (letterbox) set("privacy.resistFingerprinting.letterboxing", true)
        set("webgl.disabled", !settings.webglEnabled)
        set("media.peerconnection.enabled", settings.webrtcEnabled)
        // Network center (Phase 4): DNS-over-HTTPS + proxy, as real
        // Gecko preferences, applied at engine creation (next start).
        fun setInt(name: String, value: Int) {
            prefs.setBrowserPref(
                name, value,
                mozilla.components.concept.engine.preferences.Branch.USER, {}, {},
            )
        }
        fun setStr(name: String, value: String) {
            prefs.setBrowserPref(
                name, value,
                mozilla.components.concept.engine.preferences.Branch.USER, {}, {},
            )
        }
        val dohUri = settings.dohUri()
        if (settings.dohProvider == "off" || dohUri.isBlank()) {
            setInt("network.trr.mode", 5) // off by user choice
        } else {
            setInt("network.trr.mode", if (settings.dohStrict) 3 else 2)
            setStr("network.trr.uri", dohUri)
        }
        when (settings.proxyType) {
            "orbot" -> {
                // Orbot's SOCKS listener; Orbot must be installed and running.
                setInt("network.proxy.type", 1)
                setStr("network.proxy.socks", "127.0.0.1")
                setInt("network.proxy.socks_port", 9050)
                setInt("network.proxy.socks_version", 5)
                set("network.proxy.socks_remote_dns", true)
                setStr("network.proxy.http", "")
                setStr("network.proxy.ssl", "")
            }
            "manual" -> {
                setInt("network.proxy.type", 1)
                setStr("network.proxy.http", settings.proxyHttpHost)
                setInt("network.proxy.http_port", settings.proxyHttpPort)
                setStr("network.proxy.ssl", settings.proxyHttpHost)
                setInt("network.proxy.ssl_port", settings.proxyHttpPort)
                setStr("network.proxy.socks", settings.proxySocksHost)
                setInt("network.proxy.socks_port", settings.proxySocksPort)
                setInt("network.proxy.socks_version", if (settings.proxySocksV5) 5 else 4)
                set("network.proxy.socks_remote_dns", settings.proxyRemoteDns)
            }
            else -> setInt("network.proxy.type", 0)
        }
    }

    /**
     * The Fire operation, shared by the menu's Fire button and the
     * clear-on-exit machinery: engine browsing data (cookies, site
     * data, caches), places history, saved sessions and the
     * recently-closed list. Bookmarks are NOT browsing data and stay.
     */
    fun clearAllData(onDone: () -> Unit) {
        engine.clearData(
            Engine.BrowsingData.all(),
            null,
            {
                appScope.launch {
                    runCatching { historyStorage.deleteEverything() }
                    runCatching { sessionStorage.clear() }
                    runCatching { recentlyClosedTabsStorage.removeAllTabs() }
                    settings.sessionDirty = false
                    onDone()
                }
            },
            { onDone() },
        )
    }

    /**
     * Startup sweep for clear-on-exit: if the previous session ended
     * without clearing (process death), clear now — before any tab is
     * restored. Bounded so a wedged engine can never hang startup.
     */
    fun performStartupSweepIfNeeded() {
        if (!settings.clearOnExit || !settings.sessionDirty) return
        kotlinx.coroutines.runBlocking {
            kotlinx.coroutines.withTimeoutOrNull(5_000) {
                suspendClearEngineData()
                runCatching { historyStorage.deleteEverything() }
                runCatching { sessionStorage.clear() }
                runCatching { recentlyClosedTabsStorage.removeAllTabs() }
            }
        }
        settings.sessionDirty = false
    }

    private suspend fun suspendClearEngineData() {
        val done = kotlinx.coroutines.CompletableDeferred<Unit>()
        engine.clearData(
            Engine.BrowsingData.all(),
            null,
            { done.complete(Unit) },
            { done.complete(Unit) },
        )
        done.await()
    }

    val fetchClient by lazy { HttpURLConnectionClient() }

    val notificationsDelegate by lazy {
        NotificationsDelegate(NotificationManagerCompat.from(appContext))
    }

    val sessionStorage by lazy {
        SessionStorage(appContext, engine, applicationScope = appScope)
    }

    val store: BrowserStore by lazy {
        val recovered = runCatching { sessionStorage.restore() }.getOrNull()
        BrowserStore(
            initialState = if (recovered != null) {
                BrowserState(
                    tabs = recovered.tabs.toTabSessionStates(),
                    selectedTabId = recovered.selectedTabId,
                )
            } else {
                BrowserState()
            },
            // UndoMiddleware snapshots removed tabs so a close can be
            // undone for a few seconds (the pattern Fenix ships).
            // PromptMiddleware keeps prompt state consistent across tab
            // changes for the PromptFeature wired in BrowserActivity.
            middleware = listOf(
                UndoMiddleware(),
                PromptMiddleware(),
                RecentlyClosedMiddleware(lazy { recentlyClosedTabsStorage }, RECENTLY_CLOSED_MAX, appScope),
                ReaderViewMiddleware(),
            ) + EngineMiddleware.create(engine),
        )
    }

    val sessionUseCases by lazy { SessionUseCases(store) }
    val tabsUseCases by lazy { TabsUseCases(store) }
    val contextMenuUseCases by lazy {
        mozilla.components.feature.contextmenu.ContextMenuUseCases(store)
    }

    val downloadFileUtils by lazy {
        mozilla.components.support.utils.DefaultDownloadFileUtils(appContext)
    }

    val downloadsUseCases by lazy { DownloadsUseCases(store, downloadFileUtils) }

    companion object {
        /** How many closed tabs the Recently Closed list remembers. */
        const val RECENTLY_CLOSED_MAX = 10

        const val HOME_URL = HomeUrls.CURRENT

        /** Pre-rename start page (v0.1.0–v0.2.2) — see [HomeUrls]. */
        const val LEGACY_HOME_URL = HomeUrls.LEGACY

        fun isHomeUrl(uri: String): Boolean = HomeUrls.isHome(uri)
    }
}

/**
 * Serves the local start page for the home URLs entirely from
 * memory — opening Sett or a new tab makes no network request.
 * Redesign Phase 3: the page is built by HomePageBuilder from the
 * user's real stores and settings (shortcuts, Library snapshot,
 * protection state); its search form resolves through
 * sett://resolve so addresses open directly (audit HOME-1), and
 * sett://action/... links bounce to native screens.
 */
class HomeInterceptor(
    private val context: Context,
    private val settings: SettingsStore,
    private val homeStore: app.privatex.browser.home.HomeStore,
) : RequestInterceptor {
    override fun onLoadRequest(
        engineSession: mozilla.components.concept.engine.EngineSession,
        uri: String,
        lastUri: String?,
        hasUserGesture: Boolean,
        isSameDomain: Boolean,
        isRedirect: Boolean,
        isDirectNavigation: Boolean,
        isSubframeRequest: Boolean,
    ): RequestInterceptor.InterceptionResponse? {
        if (uri.startsWith("sett://resolve")) {
            val q = runCatching {
                java.net.URLDecoder.decode(uri.substringAfter("q=", ""), "UTF-8")
            }.getOrDefault("")
            return RequestInterceptor.InterceptionResponse.Url(
                AddressResolver.resolve(q, settings),
            )
        }
        if (app.privatex.browser.home.HomeActions.parse(uri) != null) {
            return RequestInterceptor.InterceptionResponse.Content(
                app.privatex.browser.home.HomePageBuilder.bounceHtml(), "text/html",
            )
        }
        if (!Components.isHomeUrl(uri)) return null
        return RequestInterceptor.InterceptionResponse.Content(homeHtml(), "text/html")
    }

    @Volatile
    private var cacheKey: String = ""
    @Volatile
    private var cacheHtml: String = ""

    private fun homeHtml(): String {
        val systemDark = (context.resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        val palette = app.privatex.browser.ui.SettTheme.palette(settings.themeMode, systemDark)
        val key = listOf(
            HomeSnapshot.version, settings.themeMode, systemDark,
            settings.etpStrict, settings.clearOnExit, settings.dohProvider,
            settings.searchEngine, settings.homeSections,
            homeStore.shortcuts().joinToString(",") { it.id },
        ).joinToString("|")
        if (key == cacheKey) return cacheHtml
        val data = app.privatex.browser.home.HomePageBuilder.Data(
            palette = palette,
            engineName = SettingsStore.SEARCH_ENGINES[settings.searchEngine]
                ?: settings.searchEngine,
            shortcuts = homeStore.shortcuts().take(12),
            bookmarks = HomeSnapshot.bookmarks,
            recent = HomeSnapshot.recent,
            closedTabs = HomeSnapshot.closedTabs,
            protection = app.privatex.browser.home.HomePageBuilder.Protection(
                profile = if (settings.etpStrict) "Strict" else "Standard",
                httpsOnly = true,
                gpc = true,
                clearOnExit = settings.clearOnExit,
                dohLabel = when (settings.dohProvider) {
                    "off" -> "Off"
                    "cloudflare" -> "Cloudflare"
                    "mullvad" -> "Mullvad"
                    "quad9" -> "Quad9"
                    "custom" -> "Custom"
                    else -> settings.dohProvider
                },
            ),
            sections = settings.homeSections.split(",").map { it.trim() }
                .filter { it.isNotEmpty() }.toSet(),
            categories = app.privatex.browser.tools.SearchRouting.Category.entries
                .map { it.label to it.name },
        )
        val html = app.privatex.browser.home.HomePageBuilder.build(data)
        cacheKey = key
        cacheHtml = html
        return html
    }

    /**
     * Failed loads (offline, unresolvable host, bad certificate, …) get a
     * real error page instead of a blank white tab — the pattern Fenix
     * and Focus ship (docs/UX_STUDY.md §3). ErrorPages renders AC's
     * standard page with a title, explanation and a refresh button for
     * the attempted URL. The archive.org fallback stays OFF: an error
     * page in a privacy browser should not offer to send the failed URL
     * to a third party.
     */
    override fun onErrorRequest(
        session: mozilla.components.concept.engine.EngineSession,
        errorType: ErrorType,
        uri: String?,
    ): RequestInterceptor.ErrorResponse {
        val page = ErrorPages.createUrlEncodedErrorPage(
            context = context,
            errorType = errorType,
            uri = uri,
            archiveActionEnabled = false,
        )
        return RequestInterceptor.ErrorResponse(page)
    }
}

/**
 * The Library data the home page renders (redesign Phase 3).
 * Places queries are suspend calls and the request interceptor is
 * synchronous, so BrowserActivity refreshes this snapshot on
 * resume; the page renders the latest snapshot and never blocks.
 * Empty until the first refresh — sections simply do not render,
 * which is the honest empty state.
 */
object HomeSnapshot {
    @Volatile var bookmarks: List<app.privatex.browser.home.HomePageBuilder.Link> = emptyList()
    @Volatile var recent: List<app.privatex.browser.home.HomePageBuilder.Link> = emptyList()
    @Volatile var closedTabs: List<app.privatex.browser.home.HomePageBuilder.Link> = emptyList()
    @Volatile var version: Int = 0
}
