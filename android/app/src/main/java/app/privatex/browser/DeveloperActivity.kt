package app.privatex.browser

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import app.privatex.browser.tools.DevTools
import kotlinx.coroutines.Dispatchers
import mozilla.components.browser.state.selector.selectedTab
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Developer Studio (Phase 6) — the honest subset GeckoView allows an
 * embedder: web-developer utilities (API tester, security-header
 * analyzer, JWT/regex/URL tools, robots.txt and sitemap viewers),
 * view-source, and the remote-debugging toggle that hands real
 * inspection to desktop Firefox. There is no embeddable DOM/console/
 * network inspector for GeckoView; this screen says so plainly
 * instead of faking one.
 *
 * All fetches are made directly by the app process — no cookies, no
 * JavaScript — and the screens label that, because responses can
 * legitimately differ from what the browsing engine receives.
 */
class DeveloperActivity : AppCompatActivity() {

    private val components get() = (application as PrivateXApp).components

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showHub()
    }

    private fun currentUrl(): String =
        components.store.state.selectedTab?.content?.url ?: ""

    // ------------------------------------------------------------ hub

    private fun showHub() {
        val layout = page("🛠  Developer Studio")
        layout.addView(body(
            "Developer utilities plus GeckoView's real debugging path. " +
                "What this is NOT: there is no embeddable DOM inspector, console or " +
                "network monitor for GeckoView apps — desktop Firefox DevTools (below) " +
                "is the real inspector, so Sett does not fake one.",
        ))
        val url = currentUrl()
        layout.addView(section("Current page"))
        layout.addView(body(if (url.isBlank()) "No page is open." else url))
        layout.addView(Button(this).apply {
            text = "View page source"
            isEnabled = url.isNotBlank()
            setOnClickListener {
                startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse("view-source:$url"))
                        .setClass(this@DeveloperActivity, BrowserActivity::class.java),
                )
                finish()
            }
        })
        fun tool(label: String, screen: () -> Unit) {
            layout.addView(Button(this).apply {
                text = label
                textSize = 16f
                setOnClickListener { screen() }
            })
        }
        tool("🌐  HTTP API tester") { showApiTester() }
        tool("🛡  Security header analyzer") { showHeaderAnalyzer() }
        tool("🎫  JWT decoder (decode only — never verifies)") { showJwt() }
        tool(".*  Regex tester") { showRegex() }
        tool("🔗  URL parser") { showUrlParser() }
        tool("🤖  robots.txt & sitemap viewer") { showSiteFiles() }

        layout.addView(section("Remote debugging (desktop Firefox DevTools)"))
        layout.addView(body(
            "GeckoView's supported debugging path: with this ON, desktop Firefox can " +
                "inspect Sett's pages, console and network over USB via about:debugging. " +
                "It is a debugging backdoor by design — keep it OFF unless you are " +
                "actively debugging. Applies at the next start.",
        ))
        layout.addView(CheckBox(this).apply {
            text = "Enable remote debugging (next start)"
            setTextColor(TEXT)
            isChecked = components.settings.remoteDebugging
            setOnCheckedChangeListener { _, checked ->
                components.settings.remoteDebugging = checked
                toast(if (checked) "Remote debugging ON at next start" else "Remote debugging OFF at next start")
            }
        })
        setContentView(ScrollView(this).apply { addView(layout) })
    }

    // ------------------------------------------------------------ API tester

    private fun showApiTester() {
        val layout = page("🌐  API tester")
        back(layout) { showHub() }
        layout.addView(body(
            "The request is sent directly by Sett (no cookies, no JavaScript), " +
                "and redirects are shown, not followed — the response can differ " +
                "from what the browser engine would get.",
        ))
        val methods = listOf("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD")
        val methodSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@DeveloperActivity,
                android.R.layout.simple_spinner_dropdown_item,
                methods,
            )
        }
        val urlField = field("https://…").apply { setText(currentUrl()) }
        val headersField = field("Headers, one per line — Name: value").apply { minLines = 2 }
        val bodyField = field("Request body (POST/PUT/PATCH)").apply { minLines = 2 }
        val out = body("")
        layout.addView(methodSpinner)
        layout.addView(urlField)
        layout.addView(headersField)
        layout.addView(bodyField)
        layout.addView(Button(this).apply {
            text = "Send request"
            setOnClickListener {
                val headers = LinkedHashMap<String, String>()
                headersField.text.toString().lines().forEach { line ->
                    val idx = line.indexOf(':')
                    if (idx > 0) {
                        headers[line.substring(0, idx).trim()] = line.substring(idx + 1).trim()
                    }
                }
                val bodyText = bodyField.text.toString().ifBlank { null }
                val method = methods[methodSpinner.selectedItemPosition]
                val target = urlField.text.toString().trim()
                bg {
                    val result = DevTools.httpRequest(method, target, headers, bodyText)
                    ui {
                        out.text = buildString {
                            append("${result.statusCode} ${result.statusMessage} · ${result.elapsedMillis} ms\n\n")
                            append("Response headers:\n")
                            result.headers.forEach { (name, value) -> append("$name: $value\n") }
                            append("\nBody")
                            if (result.bodyTruncated) append(" (first 512 KB shown)")
                            append(":\n").append(result.body.take(8000))
                            if (result.body.length > 8000) {
                                append("\n… (display truncated; use Copy result for the full fetched text)")
                            }
                        }
                        out.tag = result
                    }
                }
            }
        })
        layout.addView(Button(this).apply {
            text = "Copy result"
            setOnClickListener {
                val result = out.tag as? DevTools.HttpResult
                if (result != null) {
                    copy("http response", "${result.statusCode}\n" +
                        result.headers.entries.joinToString("\n") { "${it.key}: ${it.value}" } +
                        "\n\n${result.body}")
                } else {
                    toast("Send a request first")
                }
            }
        })
        layout.addView(out)
        setContentView(ScrollView(this).apply { addView(layout) })
    }

    // ------------------------------------------------- security headers

    private fun showHeaderAnalyzer() {
        val layout = page("🛡  Security headers")
        back(layout) { showHub() }
        layout.addView(body(
            "Fetches the URL directly (no cookies) and reports which security " +
                "headers the server sends. Presence is reported as fact; there is " +
                "no invented letter grade or percentage score.",
        ))
        val urlField = field("https://…").apply { setText(currentUrl()) }
        val out = body("")
        layout.addView(urlField)
        layout.addView(Button(this).apply {
            text = "Analyze"
            setOnClickListener {
                val target = urlField.text.toString().trim()
                bg {
                    val result = DevTools.httpRequest("GET", target, emptyMap(), null)
                    val findings = DevTools.analyzeHeaders(result.headers)
                    val disclosures = DevTools.disclosureHeaders(result.headers)
                    ui {
                        out.text = buildString {
                            append("Response: ${result.statusCode} ${result.statusMessage}\n\n")
                            findings.forEach { f ->
                                append(if (f.present) "✓ " else "✗ ")
                                append(f.header).append('\n')
                                append("   ").append(f.note).append('\n')
                                if (f.present) append("   Value: ${f.value?.take(120)}\n")
                                append('\n')
                            }
                            if (disclosures.isNotEmpty()) {
                                append("Technology disclosure headers:\n")
                                disclosures.forEach { (name, value) -> append("• $name: $value\n") }
                            }
                        }
                    }
                }
            }
        })
        layout.addView(out)
        setContentView(ScrollView(this).apply { addView(layout) })
    }

    // ------------------------------------------------------------ JWT

    private fun showJwt() {
        val layout = page("🎫  JWT decoder")
        back(layout) { showHub() }
        layout.addView(body(
            "Decodes the header and payload. This NEVER verifies the signature — " +
                "a decoded token shows what it claims, not whether it is genuine.",
        ))
        val token = field("Paste a JWT").apply { minLines = 3 }
        val out = body("")
        layout.addView(token)
        layout.addView(Button(this).apply {
            text = "Decode"
            setOnClickListener {
                runCatching { DevTools.decodeJwt(token.text.toString()) }
                    .onSuccess { result ->
                        out.text = buildString {
                            append("Algorithm: ${result.algorithm ?: "?"}\n")
                            append("Signature part: ${if (result.hasSignature) "present (NOT verified)" else "empty"}\n")
                            result.expiresAtEpochSeconds?.let { exp ->
                                val when_ = java.util.Date(exp * 1000)
                                val state = if (exp * 1000 < System.currentTimeMillis()) "EXPIRED" else "not yet expired"
                                append("Expires: $when_ ($state)\n")
                            }
                            result.warnings.forEach { append("⚠ $it\n") }
                            append("\nHeader:\n").append(result.headerJson)
                            append("\n\nPayload:\n").append(result.payloadJson)
                        }
                    }
                    .onFailure { out.text = it.message ?: "Not a decodable JWT" }
            }
        })
        layout.addView(out)
        setContentView(ScrollView(this).apply { addView(layout) })
    }

    // ------------------------------------------------------------ regex

    private fun showRegex() {
        val layout = page(".*  Regex tester")
        back(layout) { showHub() }
        val pattern = field("Pattern, e.g. \\b\\w+@\\w+\\.\\w+\\b")
        val sampleText = field("Test text").apply { minLines = 3 }
        val out = body("")
        layout.addView(pattern)
        layout.addView(sampleText)
        layout.addView(Button(this).apply {
            text = "Test"
            setOnClickListener {
                runCatching { DevTools.regexMatches(pattern.text.toString(), sampleText.text.toString()) }
                    .onSuccess { ranges ->
                        val source = sampleText.text.toString()
                        out.text = if (ranges.isEmpty()) {
                            "No matches."
                        } else {
                            "${ranges.size} match(es):\n" + ranges.joinToString("\n") { range ->
                                "• [${range.first}–${range.last}] \"${source.substring(range)}\""
                            }
                        }
                    }
                    .onFailure { out.text = "Pattern error: ${it.message}" }
            }
        })
        layout.addView(out)
        setContentView(ScrollView(this).apply { addView(layout) })
    }

    // ------------------------------------------------------------ URL parser

    private fun showUrlParser() {
        val layout = page("🔗  URL parser")
        back(layout) { showHub() }
        val urlField = field("https://…").apply { setText(currentUrl()) }
        val out = body("")
        layout.addView(urlField)
        layout.addView(Button(this).apply {
            text = "Parse"
            setOnClickListener {
                runCatching { DevTools.parseUrl(urlField.text.toString()) }
                    .onSuccess { parts ->
                        out.text = parts.joinToString("\n") { (name, value) -> "$name: $value" }
                    }
                    .onFailure { out.text = it.message ?: "Unparseable URL" }
            }
        })
        layout.addView(out)
        setContentView(ScrollView(this).apply { addView(layout) })
    }

    // ------------------------------------------------------------ site files

    private fun showSiteFiles() {
        val layout = page("🤖  robots.txt & sitemap")
        back(layout) { showHub() }
        layout.addView(body("Fetches the file straight from the site, exactly as a crawler would see it."))
        val siteField = field("https://site.example").apply {
            setText(currentUrl().ifBlank { "" })
        }
        val out = body("")
        layout.addView(siteField)
        fun fetch(path: String, label: String) {
            bg {
                val url = DevTools.siteFileUrl(siteField.text.toString(), path)
                val result = DevTools.httpRequest("GET", url, emptyMap(), null)
                ui {
                    out.text = if (result.statusCode == 200) {
                        "$label from $url\n\n${result.body.take(8000)}"
                    } else {
                        "$url answered ${result.statusCode} ${result.statusMessage} — no $label file there (or it is not public)."
                    }
                }
            }
        }
        layout.addView(Button(this).apply {
            text = "Fetch /robots.txt"
            setOnClickListener { fetch("/robots.txt", "robots.txt") }
        })
        layout.addView(Button(this).apply {
            text = "Fetch /sitemap.xml"
            setOnClickListener { fetch("/sitemap.xml", "sitemap.xml") }
        })
        layout.addView(out)
        setContentView(ScrollView(this).apply { addView(layout) })
    }

    // ------------------------------------------------------------ plumbing

    private fun bg(work: suspend () -> Unit) {
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.Default) { work() }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { toast(e.message ?: "Something went wrong") }
            }
        }
    }

    private suspend fun ui(block: () -> Unit) = withContext(Dispatchers.Main) { block() }

    private fun page(titleText: String): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(BG)
        setPadding(dp(18), dp(18), dp(18), dp(16))
        addView(TextView(this@DeveloperActivity).apply {
            text = titleText
            textSize = 20f
            setTextColor(TEXT)
            setPadding(0, 0, 0, dp(10))
        })
    }

    private fun back(layout: LinearLayout, action: () -> Unit) {
        layout.addView(TextView(this).apply {
            text = "← Developer Studio"
            textSize = 14f
            setTextColor(TEAL)
            setPadding(0, 0, 0, dp(8))
            setOnClickListener { action() }
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

    private fun copy(label: String, value: String) {
        getSystemService(ClipboardManager::class.java)
            .setPrimaryClip(ClipData.newPlainText(label, value))
        toast("$label copied")
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        val BG = Color.parseColor("#0B1626")
        val TEXT = Color.parseColor("#E6F1F5")
        val DIM = Color.parseColor("#8FB3C7")
        val TEAL = Color.parseColor("#2DD4BF")
    }
}
