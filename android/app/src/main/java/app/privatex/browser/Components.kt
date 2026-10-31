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
import mozilla.components.concept.engine.DefaultSettings
import mozilla.components.concept.engine.Engine
import mozilla.components.concept.engine.EngineSession
import mozilla.components.concept.engine.preferences.BrowserPreferencesRuntime
import mozilla.components.concept.engine.request.RequestInterceptor
import mozilla.components.feature.downloads.DownloadsUseCases
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
                remoteDebuggingEnabled = false,
                javascriptEnabled = true,
                requestInterceptor = HomeInterceptor(settings),
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
        if (settings.strictFingerprinting) {
            set("privacy.resistFingerprinting", true)
            set("privacy.resistFingerprinting.letterboxing", true)
        }
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
            middleware = EngineMiddleware.create(engine),
        )
    }

    val sessionUseCases by lazy { SessionUseCases(store) }
    val tabsUseCases by lazy { TabsUseCases(store) }

    val downloadFileUtils by lazy {
        mozilla.components.support.utils.DefaultDownloadFileUtils(appContext)
    }

    val downloadsUseCases by lazy { DownloadsUseCases(store, downloadFileUtils) }

    companion object {
        const val HOME_URL = "privatex://home"
    }
}

/**
 * Serves the local start page for [Components.HOME_URL] entirely from
 * memory — opening Private X or a new tab makes no network request.
 * The page's search form submits straight to the chosen search engine.
 */
class HomeInterceptor(private val settings: SettingsStore) : RequestInterceptor {
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
        if (uri != Components.HOME_URL) return null
        val action = settings.searchUrl("").substringBefore("?q=").substringBefore("?query=")
        val param = if (settings.searchEngine == "startpage") "query" else "q"
        val html = """
            <!DOCTYPE html><html><head><meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <title>Private X</title>
            <style>
              *{box-sizing:border-box}
              body{margin:0;min-height:100vh;color:#E6F1F5;font-family:sans-serif;text-align:center;
                   background:radial-gradient(120% 90% at 50% 0%,#0B2436 0%,#050B18 55%)}
              .wrap{max-width:560px;margin:0 auto;padding:9vh 20px 32px}
              .mark{width:74px;height:74px;margin:0 auto 10px}
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
              <svg class="mark" viewBox="0 0 100 100" fill="none">
                <path d="M50 4 L88 18 V48 C88 72 72 88 50 96 C28 88 12 72 12 48 V18 Z" fill="#0B1626" stroke="#2DD4BF" stroke-width="4"/>
                <circle cx="50" cy="44" r="13" stroke="#22D3EE" stroke-width="4"/>
                <circle cx="50" cy="44" r="4.5" fill="#2DD4BF"/>
                <path d="M50 57 V72" stroke="#22D3EE" stroke-width="4" stroke-linecap="round"/>
              </svg>
              <h1>PRIVATE X</h1>
              <p class="tag">PRIVATE · SECURE · IN CONTROL</p>
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
              <p class="foot">This start page is served from your device — opening it made no network request.<br>Private tabs isolate cookies &amp; storage. Nothing here phones home.</p>
            </div>
            </body></html>
        """.trimIndent()
        return RequestInterceptor.InterceptionResponse.Content(html, "text/html")
    }
}
