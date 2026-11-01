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
                requestInterceptor = HomeInterceptor(appContext, settings),
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
 * Serves the local start page for [Components.HOME_URL] entirely from
 * memory — opening Sett or a new tab makes no network request.
 * The page's search form submits straight to the chosen search engine.
 */
class HomeInterceptor(
    private val context: Context,
    private val settings: SettingsStore,
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
        if (!Components.isHomeUrl(uri)) return null
        val action = settings.searchUrl("").substringBefore("?q=").substringBefore("?query=")
        val param = if (settings.searchEngine == "startpage") "query" else "q"
        val html = """
            <!DOCTYPE html><html><head><meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <title>Sett</title>
            <style>
              *{box-sizing:border-box}
              body{margin:0;min-height:100vh;color:#E6F1F5;font-family:sans-serif;text-align:center;
                   background:radial-gradient(120% 90% at 50% 0%,#0B2436 0%,#050B18 55%)}
              .wrap{max-width:560px;margin:0 auto;padding:9vh 20px 32px}
              .mark{width:84px;height:84px;margin:0 auto 10px;border-radius:50%}
              h1{color:#2DD4BF;letter-spacing:5px;font-weight:700;font-size:27px;margin:6px 0 2px}
              .tag{color:#8FA3B0;margin:0 0 26px;font-size:14px;letter-spacing:1px}
              form{display:flex;gap:8px;background:#0B1626;border:1px solid #164E63;border-radius:26px;padding:5px;margin:0 2px}
              input{flex:1;border:0;background:transparent;color:#E6F1F5;font-size:16px;padding:11px 14px;outline:none;min-width:0}
              input::placeholder{color:#5E7484}
              button{border:0;border-radius:21px;background:#2DD4BF;color:#04241F;font-size:15px;font-weight:700;padding:0 18px;cursor:pointer}
              .chips{display:flex;flex-wrap:wrap;gap:8px;justify-content:center;margin:22px 0 6px}
              .chip{border:1px solid #164E63;background:#0B1626;color:#BFEDE4;border-radius:16px;padding:7px 12px;font-size:12.5px}
              .grid{display:grid;grid-template-columns:1fr 1fr;gap:10px;margin-top:20px}
              .tile{display:block;text-decoration:none;background:#0B1626;border:1px solid #12303F;border-radius:16px;padding:16px 10px;color:#E6F1F5}
              .tile b{display:block;font-size:15px;margin-bottom:3px}
              .tile span{color:#8FA3B0;font-size:12px}
              .foot{color:#5E7484;font-size:12px;margin-top:26px;line-height:1.6}
            </style></head><body>
            <div class="wrap">
              <img class="mark" src="data:image/png;base64,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" alt="Brock the badger">
              <h1>SETT</h1>
              <p class="tag">YOUR BURROW ON THE WEB</p>
              <form action="$action" method="get">
                <input name="$param" placeholder="Search privately or enter address" autofocus>
                <button type="submit">Go</button>
              </form>
              <div class="chips">
                <div class="chip">🛡 Tracking protection on</div>
                <div class="chip">🔒 HTTPS-Only on</div>
                <div class="chip">✋ GPC signal sent</div>
                <div class="chip">🍪 Cookies partitioned</div>
              </div>
              <div class="grid">
                <a class="tile" href="https://html.duckduckgo.com/html/"><b>DuckDuckGo</b><span>Private search</span></a>
                <a class="tile" href="https://search.brave.com/"><b>Brave Search</b><span>Independent index</span></a>
                <a class="tile" href="https://www.mojeek.com/"><b>Mojeek</b><span>No tracking search</span></a>
                <a class="tile" href="https://www.startpage.com/"><b>Startpage</b><span>Google results, privately</span></a>
              </div>
              <p class="foot">This start page is served from your device — opening it made no network request.<br>Private tabs isolate cookies &amp; storage. Nothing here phones home.<br>Guarded by Brock the badger.</p>
            </div>
            </body></html>
        """.trimIndent()
        return RequestInterceptor.InterceptionResponse.Content(html, "text/html")
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
