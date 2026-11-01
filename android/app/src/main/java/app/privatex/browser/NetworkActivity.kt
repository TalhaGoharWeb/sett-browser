package app.privatex.browser

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * Network Protection center: DNS-over-HTTPS provider choice and
 * proxy profiles, written to real Gecko preferences. Everything here
 * states its limits: a proxy is not anonymity, Orbot routing needs
 * Orbot installed, and leak tests are external pages Sett opens for
 * you (there is no self-hosted test server — zero spend).
 * Changes apply when Sett next starts, like all engine preferences.
 */
class NetworkActivity : AppCompatActivity() {

    private val components get() = (application as PrivateXApp).components
    private val ui by lazy { app.privatex.browser.ui.SettUi(this, components.settings) }
    private val BG get() = ui.palette.bg
    private val TEXT get() = ui.palette.text
    private val DIM get() = ui.palette.dim
    private val TEAL get() = ui.palette.accent
    private val settings get() = components.settings

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui.applyPlatformTheme()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
            setPadding(dp(18), dp(18), dp(18), dp(16))
        }
        root.addView(title("🌐  Network protection"))
        root.addView(body("Changes apply when Sett next starts."))

        // ---------------------------------------------------------- DoH
        root.addView(title("DNS-over-HTTPS"))
        root.addView(body(
            "Encrypted DNS hides which sites you look up from your network. " +
                "The resolver itself sees the queries instead — choose who you trust.",
        ))
        val dohGroup = RadioGroup(this)
        val providers = listOf(
            "off" to "Off — use the network's plain DNS",
            "cloudflare" to "Cloudflare (via Mozilla's endpoint)",
            "mullvad" to "Mullvad",
            "quad9" to "Quad9",
            "custom" to "Custom resolver",
        )
        providers.forEach { (key, label) ->
            dohGroup.addView(RadioButton(this).apply {
                text = label
                textSize = 15f
                setTextColor(TEXT)
                tag = key
                isChecked = settings.dohProvider == key
            })
        }
        root.addView(dohGroup)
        val customUri = EditText(this).apply {
            hint = "Custom DoH URI (https://…/dns-query)"
            textSize = 14f
            setTextColor(TEXT)
            setHintTextColor(DIM)
            setText(settings.dohCustomUri)
        }
        root.addView(customUri)
        val strict = CheckBox(this).apply {
            text = "Strict — DoH only, no plain-DNS fallback (breaks if the resolver is unreachable)"
            textSize = 14f
            setTextColor(TEXT)
            isChecked = settings.dohStrict
        }
        root.addView(strict)

        // ---------------------------------------------------------- Proxy
        root.addView(title("Proxy"))
        root.addView(body(
            "A proxy routes your traffic through another server. It is not anonymity: " +
                "the proxy operator can see your traffic's destinations, and a badly " +
                "run proxy is worse than none.",
        ))
        val proxyGroup = RadioGroup(this)
        listOf(
            "none" to "No proxy",
            "orbot" to "Orbot (Tor network via the Orbot app)",
            "manual" to "Manual proxy",
        ).forEach { (key, label) ->
            proxyGroup.addView(RadioButton(this).apply {
                text = label
                textSize = 15f
                setTextColor(TEXT)
                tag = key
                isChecked = settings.proxyType == key
            })
        }
        root.addView(proxyGroup)
        root.addView(body(
            "Orbot preset: SOCKS 127.0.0.1 port 9050 with remote DNS. Orbot must be " +
                "installed and running (get it from F-Droid / Guardian Project). Even " +
                "then, this is a normal browser routed through Tor — not the Tor " +
                "Browser, and it does not make you anonymous.",
        ))
        val httpHost = field("HTTP proxy host", settings.proxyHttpHost)
        val httpPort = field("HTTP proxy port", settings.proxyHttpPort.takeIf { it > 0 }?.toString() ?: "")
        val socksHost = field("SOCKS host", settings.proxySocksHost)
        val socksPort = field("SOCKS port", settings.proxySocksPort.takeIf { it > 0 }?.toString() ?: "")
        root.addView(httpHost); root.addView(httpPort)
        root.addView(socksHost); root.addView(socksPort)
        val socksV5 = CheckBox(this).apply {
            text = "SOCKS version 5 (off = version 4)"
            textSize = 14f
            setTextColor(TEXT)
            isChecked = settings.proxySocksV5
        }
        val remoteDns = CheckBox(this).apply {
            text = "Resolve DNS through the proxy (SOCKS)"
            textSize = 14f
            setTextColor(TEXT)
            isChecked = settings.proxyRemoteDns
        }
        root.addView(socksV5); root.addView(remoteDns)

        root.addView(Button(this).apply {
            text = "Save network settings"
            setOnClickListener {
                settings.dohProvider = selectedKey(dohGroup) ?: "off"
                settings.dohCustomUri = customUri.text.toString().trim()
                settings.dohStrict = strict.isChecked
                settings.proxyType = selectedKey(proxyGroup) ?: "none"
                settings.proxyHttpHost = httpHost.text.toString().trim()
                settings.proxyHttpPort = httpPort.text.toString().toIntOrNull() ?: 0
                settings.proxySocksHost = socksHost.text.toString().trim()
                settings.proxySocksPort = socksPort.text.toString().toIntOrNull() ?: 0
                settings.proxySocksV5 = socksV5.isChecked
                settings.proxyRemoteDns = remoteDns.isChecked
                toast("Saved — applies when Sett next starts")
            }
        })

        // ---------------------------------------------------------- Leak checks
        root.addView(title("Check for leaks"))
        root.addView(body(
            "External test pages, opened in Sett. They show what a website can see " +
                "about your connection — run them after changing anything above.",
        ))
        listOf(
            "IP & DNS leak test (ipleak.net)" to "https://ipleak.net",
            "WebRTC leak test (browserleaks.com)" to "https://browserleaks.com/webrtc",
            "DNS leak test (dnsleaktest.com)" to "https://www.dnsleaktest.com",
        ).forEach { (label, url) ->
            root.addView(Button(this).apply {
                text = label
                setOnClickListener {
                    startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse(url))
                            .setClass(this@NetworkActivity, BrowserActivity::class.java),
                    )
                    finish()
                }
            })
        }
        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun selectedKey(group: RadioGroup): String? {
        val view = group.findViewById<RadioButton>(group.checkedRadioButtonId) ?: return null
        return view.tag as? String
    }

    private fun field(hint: String, value: String) = EditText(this).apply {
        this.hint = hint
        textSize = 14f
        setTextColor(TEXT)
        setHintTextColor(DIM)
        setText(value)
        setPadding(0, dp(6), 0, dp(6))
    }

    private fun title(text: String) = TextView(this).apply {
        this.text = text
        textSize = 18f
        setTextColor(TEXT)
        setPadding(0, dp(14), 0, dp(6))
    }

    private fun body(text: String) = TextView(this).apply {
        this.text = text
        textSize = 13f
        setTextColor(DIM)
        setPadding(0, dp(2), 0, dp(8))
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
    }
}
