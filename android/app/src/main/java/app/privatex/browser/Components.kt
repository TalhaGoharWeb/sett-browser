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
              body{background:#050B18;color:#E6F1F5;font-family:sans-serif;text-align:center;padding-top:18vh;margin:0}
              h1{color:#2DD4BF;letter-spacing:2px;font-weight:600}
              p{color:#8FA3B0}
              input{width:78%;max-width:520px;padding:14px;border-radius:10px;border:1px solid #164E63;background:#0B1626;color:#E6F1F5;font-size:16px}
            </style></head><body>
            <h1>PRIVATE X</h1>
            <p>Private. Secure. In Control.</p>
            <form action="$action" method="get"><input name="$param" placeholder="Search privately" autofocus></form>
            <p>Enhanced Tracking Protection is on. HTTPS-Only is on.<br>This start page never leaves your device.</p>
            </body></html>
        """.trimIndent()
        return RequestInterceptor.InterceptionResponse.Content(html, "text/html")
    }
}
