package app.privatex.browser

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import app.privatex.browser.tools.AiTools
import app.privatex.browser.tools.DevTools
import app.privatex.browser.vault.VaultEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mozilla.components.browser.state.selector.selectedTab

/**
 * AI Workspace (Phase 10) — bring-your-own-key assistance under hard
 * rules, printed on this screen and enforced in code:
 *  - Nothing is configured or sent by default; there is no built-in
 *    provider and no Sett server.
 *  - The API key lives in the encrypted vault, never in settings.
 *  - Private tabs are refused outright. Internal pages are refused.
 *  - Every single send first shows a consent dialog naming the
 *    provider host, the model, and exactly how many characters of
 *    page text will leave the device. No tap, no send.
 *  - Page text is fetched directly (like the Developer Studio), so
 *    it can differ from the rendered page; that is stated too.
 * An offline "key sentences" extractor works with no provider at all.
 */
class AiActivity : AppCompatActivity() {

    private val components get() = (application as PrivateXApp).components
    private var fetchedText: String = ""
    private var fetchedUrl: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showMain()
    }

    private fun vaultKey(): String? {
        val vault = components.vault
        if (!vault.isUnlocked) return null
        val meta = vault.listRecords().firstOrNull { it.id == AiTools.AI_KEY_RECORD_ID }
            ?: return null
        return runCatching {
            VaultEntry.fromBytes(meta.id, vault.readRecord(meta.id)).password
        }.getOrNull()?.ifEmpty { null }
    }

    private fun showMain() {
        val settings = components.settings
        val layout = page("🤖  AI Workspace")
        layout.addView(body(
            "Optional and off until YOU configure it. Sett ships no provider and runs " +
                "no AI server. Your key stays in the encrypted vault. Private tabs are " +
                "refused. Every send asks first, showing the provider and exactly how " +
                "much text would leave this device.",
        ))

        layout.addView(section("Provider (OpenAI-compatible)"))
        val presets = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val baseField = field("Base URL, e.g. https://openrouter.ai/api/v1").apply {
            setText(settings.aiProviderBase)
        }
        listOf(
            "OpenRouter" to "https://openrouter.ai/api/v1",
            "Groq" to "https://api.groq.com/openai/v1",
            "Ollama (this device/LAN)" to "http://localhost:11434/v1",
        ).forEach { (label, url) ->
            presets.addView(Button(this).apply {
                text = label
                textSize = 11f
                setOnClickListener { baseField.setText(url) }
            })
        }
        layout.addView(presets)
        layout.addView(baseField)
        val modelField = field("Model, e.g. the free model id your provider lists").apply {
            setText(settings.aiModel)
        }
        layout.addView(modelField)
        layout.addView(Button(this).apply {
            text = "Save provider config"
            setOnClickListener {
                settings.aiProviderBase = baseField.text.toString().trim()
                settings.aiModel = modelField.text.toString().trim()
                toast("Saved (the key is stored separately, in the vault)")
            }
        })

        layout.addView(section("API key — stored in the vault"))
        val keyStatus = body(keyStatusText())
        layout.addView(keyStatus)
        val keyField = field("Paste your provider API key").apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        layout.addView(keyField)
        layout.addView(Button(this).apply {
            text = "Store key in vault"
            setOnClickListener {
                val vault = components.vault
                val key = keyField.text.toString().trim()
                when {
                    key.isEmpty() -> toast("Paste a key first")
                    !vault.exists() -> {
                        toast("Create the vault first (menu → Vault), then store the key")
                        startActivity(Intent(this@AiActivity, VaultActivity::class.java))
                    }
                    !vault.isUnlocked -> {
                        toast("Unlock the vault first, then come back and store the key")
                        startActivity(Intent(this@AiActivity, VaultActivity::class.java))
                    }
                    else -> {
                        runCatching {
                            vault.putRecord(
                                AiTools.AI_KEY_RECORD_ID,
                                "login",
                                VaultEntry(
                                    id = AiTools.AI_KEY_RECORD_ID,
                                    title = "Sett AI provider key",
                                    username = settings.aiProviderBase,
                                    password = key,
                                    url = "",
                                    notes = "Managed by AI Workspace. Edit it there.",
                                ).toBytes(),
                            )
                        }.onSuccess {
                            keyField.setText("")
                            keyStatus.text = keyStatusText()
                            toast("Key stored in the vault")
                        }.onFailure { toast(it.message ?: "Could not store the key") }
                    }
                }
            }
        })

        layout.addView(section("Current page"))
        val tab = components.store.state.selectedTab
        val tabUrl = tab?.content?.url ?: ""
        val isPrivate = tab?.content?.private == true
        val usable = !isPrivate && tabUrl.startsWith("http")
        layout.addView(body(
            when {
                tab == null -> "No tab is open."
                isPrivate -> "⛔ This is a private tab. AI Workspace never touches private tabs — no exceptions."
                !usable -> "This page cannot be used here (internal or non-web page): $tabUrl"
                else -> "Page: ${tab.content.title}\n$tabUrl"
            },
        ))
        val fetchStatus = body("")
        layout.addView(Button(this).apply {
            text = "Fetch this page's text"
            isEnabled = usable
            setOnClickListener {
                bg {
                    val response = DevTools.httpRequest("GET", tabUrl, emptyMap(), null)
                    val text = AiTools.htmlToText(response.body)
                    fetchedText = text.take(12_000)
                    fetchedUrl = tabUrl
                    ui {
                        fetchStatus.text = "Fetched ${fetchedText.length} characters of text " +
                            "(direct fetch — may differ from the rendered page)."
                    }
                }
            }
        })
        layout.addView(fetchStatus)

        val responseView = body("")
        fun requireFetched(): Boolean {
            if (fetchedText.isEmpty()) toast("Fetch the page's text first")
            return fetchedText.isNotEmpty()
        }
        layout.addView(section("Ask the provider (consent shown before every send)"))
        val extraField = field("For Translate: language (blank = Urdu). For Ask: your question.")
        layout.addView(extraField)
        AiTools.Task.entries.forEach { task ->
            layout.addView(Button(this).apply {
                text = task.label
                isEnabled = usable
                setOnClickListener {
                    if (requireFetched()) {
                        val prompt = AiTools.buildPrompt(task, fetchedText, extraField.text.toString())
                        confirmAndSend(prompt, task.label)
                    }
                }
            })
        }
        layout.addView(section("Offline — no provider, no network"))
        layout.addView(Button(this).apply {
            text = "Key sentences (extractive, on-device)"
            isEnabled = usable
            setOnClickListener {
                if (requireFetched()) {
                    val sentences = AiTools.extractiveSummarize(fetchedText, 5)
                    responseView.text = "Key sentences (heuristic extraction — not AI):\n\n" +
                        sentences.joinToString("\n\n") { "• $it" }
                }
            }
        })
        layout.addView(section("Custom prompt (sends only what you type)"))
        val customField = field("Your prompt — no page content is attached").apply { minLines = 3 }
        layout.addView(customField)
        layout.addView(Button(this).apply {
            text = "Send custom prompt"
            setOnClickListener {
                val prompt = customField.text.toString().trim()
                if (prompt.isEmpty()) {
                    toast("Type a prompt first")
                } else {
                    confirmAndSend(prompt, "Custom prompt")
                }
            }
        })
        layout.addView(section("Response"))
        layout.addView(responseView)
        layout.addView(Button(this).apply {
            text = "Copy response"
            setOnClickListener {
                val text = responseView.text.toString()
                if (text.isNotBlank()) copy("ai response", text)
            }
        })
        layout.addView(Button(this).apply {
            text = "Save response as a study note"
            setOnClickListener {
                val text = responseView.text.toString()
                if (text.isBlank()) {
                    toast("No response yet")
                } else {
                    components.studyStore.upsertNote(
                        fetchedUrl.ifBlank { "AI Workspace" },
                        text.take(4000),
                    )
                    toast("Saved in Study Center → Page & PDF notes")
                }
            }
        })
        responseSlot = responseView
        setContentView(ScrollView(this).apply { addView(layout) })
    }

    private var responseSlot: TextView? = null

    private fun keyStatusText(): String {
        val vault = components.vault
        return when {
            !vault.exists() -> "No vault exists yet — the key can only be stored once you create one."
            !vault.isUnlocked -> "Vault is locked. Unlock it to store or use a key."
            vaultKey() != null -> "✓ A key is stored in the vault and available."
            else -> "Vault unlocked; no AI key stored yet."
        }
    }

    /** The consent gate: names host, model and character count. No tap, no send. */
    private fun confirmAndSend(prompt: String, label: String) {
        val settings = components.settings
        val base = settings.aiProviderBase.trim().trimEnd('/')
        val model = settings.aiModel.trim()
        if (base.isEmpty() || model.isEmpty()) {
            toast("Configure the provider base URL and model first")
            return
        }
        val host = runCatching { java.net.URI(base).host ?: base }.getOrDefault(base)
        val key = vaultKey()
        val isLocal = host == "localhost" || host == "127.0.0.1" || host.startsWith("192.168.")
        if (key == null && !isLocal) {
            toast("No key available — unlock the vault with a stored key first (local endpoints need none)")
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Send to your provider?")
            .setMessage(
                "$label\n\nProvider: $host\nModel: $model\n" +
                    "Characters leaving this device: ${prompt.length}\n\n" +
                    "Sett has no server in between — the request goes straight from " +
                    "this phone to the provider you configured, under their terms.",
            )
            .setPositiveButton("Send") { _, _ ->
                bg {
                    val response = DevTools.httpRequest(
                        "POST",
                        "$base/chat/completions",
                        buildMap {
                            put("Content-Type", "application/json")
                            if (key != null) put("Authorization", "Bearer $key")
                        },
                        AiTools.buildChatRequestJson(model, prompt),
                    )
                    if (response.statusCode != 200) {
                        throw IllegalStateException(
                            "Provider answered ${response.statusCode}: ${response.body.take(300)}",
                        )
                    }
                    val answer = AiTools.parseChatResponse(response.body)
                    ui { responseSlot?.text = answer }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
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
        addView(TextView(this@AiActivity).apply {
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
