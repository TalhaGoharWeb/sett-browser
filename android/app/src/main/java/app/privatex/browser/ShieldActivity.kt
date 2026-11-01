package app.privatex.browser

import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import mozilla.components.browser.state.selector.selectedTab
import mozilla.components.concept.engine.content.blocking.TrackingProtectionException

/**
 * The Sett Shield: a state-and-configuration dashboard. It shows only
 * what the platform actually exposes — protection modes, per-site
 * exception state, connection security, the fingerprinting prefs in
 * force, and the per-page blocked-tracker list exactly as the engine
 * reports it. It deliberately has NO cumulative "trackers blocked"
 * score: no trustworthy all-time total exists, and Sett does not
 * invent numbers.
 */
class ShieldActivity : AppCompatActivity() {

    private val components get() = (application as PrivateXApp).components
    private lateinit var exceptionsBox: LinearLayout
    private lateinit var siteBox: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(INK_BG)
            setPadding(dp(18), dp(18), dp(18), dp(16))
        }
        root.addView(sectionTitle("🛡️  Sett Shield"))
        root.addView(body("What is protecting you, stated exactly — no invented counters."))
        root.addView(sectionTitle("This site"))
        siteBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(siteBox)
        buildSiteCard()
        root.addView(sectionTitle("Protection state"))
        root.addView(kv("Tracking protection (ETP)", if (components.settings.etpStrict) "Strict" else "Standard"))
        root.addView(kv("HTTPS-Only Mode", "On — plain HTTP is upgraded or blocked"))
        root.addView(kv("Global Privacy Control", "On — sites are told not to sell/share your data"))
        root.addView(kv("Tracking-parameter stripping", "On — known click IDs are removed from URLs"))
        root.addView(kv("Total Cookie Protection", "On — cookies are partitioned per site"))
        root.addView(kv("Mozilla telemetry & studies", "Off — disabled at engine level"))
        root.addView(sectionTitle("Tracking protection exceptions"))
        exceptionsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(exceptionsBox)
        loadExceptions()
        root.addView(sectionTitle("Fingerprinting & network — custom"))
        buildFingerprintSection(root)
        root.addView(body("Fingerprinting and network changes apply when Sett next starts (engine settings are read at engine creation)."))
        root.addView(sectionTitle("Data"))
        val clearOnExit = CheckBox(this).apply {
            text = "Clear all browsing data when Sett closes"
            textSize = 15f
            setTextColor(TEXT)
            isChecked = components.settings.clearOnExit
            setOnCheckedChangeListener { _, checked ->
                components.settings.clearOnExit = checked
                Toast.makeText(
                    this@ShieldActivity,
                    if (checked) "Clear-on-exit on" else "Clear-on-exit off",
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
        root.addView(clearOnExit)
        root.addView(body("If the app is killed before it can clear, the clear runs at the next start instead — before any tab is restored. Bookmarks are never cleared."))
        val fire = TextView(this).apply {
            text = "🔥  Fire — clear all browsing data now"
            textSize = 16f
            setTextColor(Color.parseColor("#F87171"))
            setPadding(0, dp(12), 0, dp(6))
            setOnClickListener { confirmFire() }
        }
        root.addView(fire)
        root.addView(sectionTitle("What this screen will never show"))
        root.addView(
            body(
                "A cumulative “trackers blocked: N” score. The engine reports what it " +
                    "blocked on each page (shown above, per page, exactly as reported) — " +
                    "but no trustworthy all-time total exists, so Sett will not invent one.",
            ),
        )
        setContentView(ScrollView(this).apply { addView(root) })
    }

    // --- This site ------------------------------------------------------

    private fun buildSiteCard() {
        siteBox.removeAllViews()
        val tab = components.store.state.selectedTab
        if (tab == null) {
            siteBox.addView(body("No page is open."))
            return
        }
        val host = runCatching { java.net.URI(tab.content.url).host }.getOrNull() ?: tab.content.url
        siteBox.addView(kv("Site", host))
        val security = when (tab.content.securityInfo) {
            is mozilla.components.browser.state.state.SecurityInfo.Secure ->
                "Secure connection (HTTPS)"
            is mozilla.components.browser.state.state.SecurityInfo.Insecure ->
                "Not secure — no valid HTTPS"
            else -> "Connection state unknown"
        }
        siteBox.addView(kv("Connection", security))
        // The engine itself reports the trackers it blocked on this
        // page (this is the same data Firefox's panel shows). It is
        // per-page and reported, never accumulated or estimated.
        val blocked = tab.trackingProtection.blockedTrackers
        siteBox.addView(
            kv(
                "Trackers the engine blocked on this page",
                if (blocked.isEmpty()) {
                    "None reported for this page"
                } else {
                    blocked.joinToString("\n") { tracker ->
                        val cats = tracker.trackingCategories.joinToString(", ") {
                            it.name.lowercase().replace('_', ' ')
                        }
                        "• ${tracker.url}" + if (cats.isBlank()) "" else " — $cats"
                    }
                },
            ),
        )
        val session = tab.engineState.engineSession
        if (session != null) {
            components.engine.trackingProtectionExceptionStore.contains(session) { isExcepted ->
                runOnUiThread {
                    siteBox.addView(
                        kv(
                            "Tracking protection for this site",
                            if (isExcepted) "OFF (your exception)" else "Active",
                        ),
                    )
                    val toggle = TextView(this).apply {
                        text = if (isExcepted) {
                            "▶  Enable protection for this site"
                        } else {
                            "⏸  Disable protection for this site (exception)"
                        }
                        textSize = 15f
                        setTextColor(TEAL)
                        setPadding(0, dp(6), 0, dp(10))
                        setOnClickListener {
                            if (isExcepted) {
                                components.engine.trackingProtectionExceptionStore.remove(session)
                            } else {
                                components.engine.trackingProtectionExceptionStore.add(session)
                            }
                            components.sessionUseCases.reload()
                            buildSiteCard()
                            loadExceptions()
                        }
                    }
                    siteBox.addView(toggle)
                }
            }
        }
    }

    // --- Exceptions -----------------------------------------------------

    private fun loadExceptions() {
        components.engine.trackingProtectionExceptionStore.fetchAll { exceptions ->
            runOnUiThread {
                exceptionsBox.removeAllViews()
                if (exceptions.isEmpty()) {
                    exceptionsBox.addView(body("No exceptions — protection is active on every site."))
                } else {
                    exceptionsBox.addView(body("${exceptions.size} site(s) where you turned protection off:"))
                    exceptions.forEach { ex -> exceptionsBox.addView(exceptionRow(ex)) }
                }
            }
        }
    }

    private fun exceptionRow(ex: TrackingProtectionException): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(6), 0, dp(6))
        }
        row.addView(TextView(this).apply {
            text = ex.url
            textSize = 14f
            setTextColor(TEXT)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        row.addView(TextView(this).apply {
            text = "Remove"
            textSize = 14f
            setTextColor(TEAL)
            setPadding(dp(10), 0, 0, 0)
            setOnClickListener {
                components.engine.trackingProtectionExceptionStore.remove(ex)
                loadExceptions()
                buildSiteCard()
            }
        })
        return row
    }

    // --- Fingerprinting -------------------------------------------------

    private fun buildFingerprintSection(root: LinearLayout) {
        val s = components.settings
        if (s.strictFingerprinting) {
            root.addView(
                body(
                    "The Strict fingerprinting profile is ON (Settings). It forces " +
                        "resist-fingerprinting and letterboxing; turn the profile off " +
                        "there to use the custom toggles below.",
                ),
            )
        }
        fun toggle(label: String, hint: String, checked: Boolean, enabled: Boolean = true, set: (Boolean) -> Unit): CheckBox =
            CheckBox(this).apply {
                text = label
                textSize = 15f
                setTextColor(TEXT)
                isChecked = checked
                isEnabled = enabled
                setOnCheckedChangeListener { _, v -> set(v) }
            }.also {
                root.addView(it)
                root.addView(body(hint))
            }
        toggle(
            "Resist fingerprinting",
            "Reports a standardised set of device traits to sites. Can break some sites' layouts.",
            s.strictFingerprinting || s.fpResist,
            enabled = !s.strictFingerprinting,
        ) { s.fpResist = it }
        toggle(
            "Letterboxing",
            "Rounds the window size to standard steps so it cannot identify you. Adds grey margins.",
            s.strictFingerprinting || s.fpLetterbox,
            enabled = !s.strictFingerprinting,
        ) { s.fpLetterbox = it }
        toggle(
            "WebGL",
            "3D graphics API and a strong fingerprinting surface. Turning it off breaks some games/maps.",
            s.webglEnabled,
        ) { s.webglEnabled = it }
        toggle(
            "WebRTC",
            "Voice/video calls in the browser. Can expose your local network address to sites.",
            s.webrtcEnabled,
        ) { s.webrtcEnabled = it }
    }

    // --- Fire -----------------------------------------------------------

    private fun confirmFire() {
        AlertDialog.Builder(this)
            .setTitle("Clear all browsing data?")
            .setMessage(
                "Deletes cookies, site data, caches and history for every site, plus saved " +
                    "sessions and the recently-closed list. Open tabs stay open; their stored " +
                    "data is wiped. Bookmarks stay.",
            )
            .setPositiveButton("Clear everything") { _, _ ->
                components.clearAllData {
                    runOnUiThread {
                        Toast.makeText(this, "Browsing data cleared", Toast.LENGTH_SHORT).show()
                        buildSiteCard()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // --- Small view helpers ----------------------------------------------

    private fun sectionTitle(text: String) = TextView(this).apply {
        this.text = text
        textSize = 17f
        setTextColor(TEXT)
        setPadding(0, dp(16), 0, dp(6))
    }

    private fun body(text: String) = TextView(this).apply {
        this.text = text
        textSize = 13f
        setTextColor(DIM)
        setPadding(0, dp(2), 0, dp(6))
    }

    private fun kv(key: String, value: String): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(4), 0, dp(4))
        }
        row.addView(TextView(this).apply {
            text = key
            textSize = 12f
            setTextColor(DIM)
        })
        row.addView(TextView(this).apply {
            text = value
            textSize = 15f
            setTextColor(TEXT)
        })
        return row
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        val INK_BG = Color.parseColor("#0B1626")
        val TEXT = Color.parseColor("#E6F1F5")
        val DIM = Color.parseColor("#8FB3C7")
        val TEAL = Color.parseColor("#2DD4BF")
    }
}
