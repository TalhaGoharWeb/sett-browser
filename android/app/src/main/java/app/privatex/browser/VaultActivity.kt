package app.privatex.browser

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.core.widget.addTextChangedListener
import androidx.lifecycle.lifecycleScope
import app.privatex.browser.vault.BiometricVault
import app.privatex.browser.vault.PasswordGenerator
import app.privatex.browser.vault.VaultCore
import app.privatex.browser.vault.VaultEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The Vault screen: create / unlock / browse / edit the encrypted
 * password vault. Manual copy-and-paste fill in this phase (Android
 * Autofill integration is a later, separate step — stated in the UI).
 * The vault auto-locks after 3 minutes without interaction.
 */
class VaultActivity : AppCompatActivity() {

    private val components get() = (application as PrivateXApp).components
    private val vault: VaultCore get() = components.vault
    private var lastInteraction = System.currentTimeMillis()
    private var entries: List<VaultEntry> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        route()
    }

    override fun onResume() {
        super.onResume()
        if (vault.isUnlocked &&
            System.currentTimeMillis() - lastInteraction > AUTO_LOCK_MS
        ) {
            vault.lock()
            entries = emptyList()
            route()
        }
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        lastInteraction = System.currentTimeMillis()
    }

    private fun route() {
        when {
            vault.isUnlocked -> showList()
            vault.exists() -> showUnlock()
            else -> showCreate()
        }
    }

    // ------------------------------------------------------------ create

    private fun showCreate() {
        val layout = page("🔑  Create your vault")
        layout.addView(body(
            "One master password encrypts everything (Argon2id + XChaCha20-Poly1305, " +
                "the VaultX design). It is never stored — if you lose it and your " +
                "recovery key, the vault cannot be opened. That is the point.",
        ))
        val pw = field("Master password", password = true)
        val pw2 = field("Repeat master password", password = true)
        layout.addView(pw); layout.addView(pw2)
        layout.addView(button("Create vault") {
            val a = pw.text.toString(); val b = pw2.text.toString()
            when {
                a.length < 8 -> toast("Use at least 8 characters — longer is stronger")
                a != b -> toast("The two passwords do not match")
                else -> busy("Creating vault…") {
                    val recovery = vault.create(a.toCharArray(), withRecovery = true)
                    ui { if (recovery != null) showRecoveryKey(recovery) else showList() }
                }
            }
        })
        setContentView(ScrollView(this).apply { addView(layout) })
    }

    private fun showRecoveryKey(recovery: String) {
        AlertDialog.Builder(this)
            .setTitle("Your recovery key")
            .setMessage(
                "Shown ONCE. If you forget the master password, this key is the only " +
                    "other way in. Save it somewhere safe (not in this vault).\n\n$recovery",
            )
            .setPositiveButton("Copy") { _, _ ->
                copy("recovery key", recovery)
                showList()
            }
            .setNegativeButton("I saved it") { _, _ -> showList() }
            .setCancelable(false)
            .show()
    }

    // ------------------------------------------------------------ unlock

    private fun showUnlock() {
        val layout = page("🔑  Unlock vault")
        val pw = field("Master password", password = true)
        layout.addView(pw)
        layout.addView(button("Unlock") {
            val text = pw.text.toString()
            if (text.isEmpty()) {
                toast("Enter your master password")
            } else {
                busy("Unlocking… (key derivation takes a moment)") {
                    if (vault.unlock(text.toCharArray())) {
                        lastInteraction = System.currentTimeMillis()
                        ui { loadAndShowList() }
                    } else {
                        ui { toast("Wrong password") }
                    }
                }
            }
        })
        if (BiometricVault.isAvailable(this) && BiometricVault.isEnrolled(this)) {
            layout.addView(button("Use fingerprint") { biometricUnlock() })
        }
        val recovery = TextView(this).apply {
            text = "Forgot the password? Use the recovery key"
            textSize = 14f
            setTextColor(TEAL)
            setPadding(0, dp(14), 0, 0)
            setOnClickListener { showRecoveryUnlock() }
        }
        layout.addView(recovery)
        setContentView(ScrollView(this).apply { addView(layout) })
    }

    private fun showRecoveryUnlock() {
        val input = EditText(this).apply {
            hint = "Recovery key"
            setTextColor(TEXT)
            setHintTextColor(DIM)
        }
        AlertDialog.Builder(this)
            .setTitle("Unlock with recovery key")
            .setView(input)
            .setPositiveButton("Unlock") { _, _ ->
                busy("Unlocking…") {
                    if (vault.unlockWithRecoveryKey(input.text.toString())) {
                        lastInteraction = System.currentTimeMillis()
                        ui {
                            toast("Unlocked — set a new master password from the vault screen")
                            loadAndShowList()
                        }
                    } else {
                        ui { toast("That recovery key did not work") }
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun biometricUnlock() {
        val cipher = runCatching { BiometricVault.decryptCipher(this) }.getOrNull()
        if (cipher == null) {
            toast("Fingerprint unlock is unavailable — use the password")
            return
        }
        val prompt = BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    val authed = result.cryptoObject?.cipher ?: return
                    val vdk = BiometricVault.unwrap(this@VaultActivity, authed)
                    if (vdk == null) {
                        toast("Could not unwrap the vault key — use the password")
                        return
                    }
                    busy("Unlocking…") {
                        val ok = vault.unlockWithVdk(vdk)
                        vdk.fill(0)
                        if (ok) {
                            lastInteraction = System.currentTimeMillis()
                            ui { loadAndShowList() }
                        } else {
                            ui { toast("Vault key check failed — use the password") }
                        }
                    }
                }

                override fun onAuthenticationError(code: Int, msg: CharSequence) {
                    if (code != BiometricPrompt.ERROR_USER_CANCELED &&
                        code != BiometricPrompt.ERROR_NEGATIVE_BUTTON
                    ) {
                        toast(msg.toString())
                    }
                }
            },
        )
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Unlock Sett vault")
            .setSubtitle("Fingerprint unlocks your vault key")
            .setNegativeButtonText("Use password")
            .build()
        prompt.authenticate(info, BiometricPrompt.CryptoObject(cipher))
    }

    // ------------------------------------------------------------ list

    private fun loadAndShowList() {
        busy("Opening vault…") {
            entries = vault.listRecords().map { meta ->
                VaultEntry.fromBytes(meta.id, vault.readRecord(meta.id))
            }.sortedBy { it.title.lowercase() }
            ui { showList() }
        }
    }

    private fun showList() {
        val layout = page("🔑  Vault")
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(actionButton("＋ Add") { showEdit(null) })
        actions.addView(actionButton("🔒 Lock") {
            vault.lock()
            entries = emptyList()
            route()
        })
        actions.addView(actionButton("🔑 Password") { showChangePassword() })
        layout.addView(actions)
        if (BiometricVault.isAvailable(this) && !BiometricVault.isEnrolled(this)) {
            layout.addView(actionButton("👆 Enable fingerprint unlock") { biometricEnroll() })
        }
        if (BiometricVault.isEnrolled(this)) {
            layout.addView(actionButton("👆 Disable fingerprint unlock") {
                BiometricVault.disable(this)
                toast("Fingerprint unlock disabled")
                showList()
            })
        }
        val search = field("Search entries")
        layout.addView(search)
        val listBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        layout.addView(listBox)
        fun render(query: String) {
            listBox.removeAllViews()
            val visible = entries.filter {
                query.isBlank() || it.title.lowercase().contains(query.lowercase()) ||
                    it.username.lowercase().contains(query.lowercase()) ||
                    it.url.lowercase().contains(query.lowercase())
            }
            if (visible.isEmpty()) {
                listBox.addView(body(
                    if (entries.isEmpty()) {
                        "No entries yet. Tap ＋ Add to store your first login. " +
                            "Fill is manual for now: open an entry and copy — Android " +
                            "Autofill integration is a later step."
                    } else {
                        "Nothing matches that search."
                    },
                ))
            }
            visible.forEach { entry ->
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(6), dp(10), dp(6), dp(10))
                    setOnClickListener { showDetail(entry) }
                }
                row.addView(TextView(this).apply {
                    text = entry.title.ifBlank { entry.url }
                    textSize = 16f
                    setTextColor(TEXT)
                })
                row.addView(TextView(this).apply {
                    text = listOf(entry.username, entry.url).filter { it.isNotBlank() }.joinToString(" · ")
                    textSize = 13f
                    setTextColor(DIM)
                })
                listBox.addView(row)
            }
        }
        search.addTextChangedListener { render(it?.toString().orEmpty()) }
        render("")
        layout.addView(body("Auto-locks after 3 minutes without interaction."))
        setContentView(ScrollView(this).apply { addView(layout) })
    }

    private fun biometricEnroll() {
        val cipher = runCatching { BiometricVault.encryptCipher() }.getOrNull()
        if (cipher == null) {
            toast("Could not create the biometric key")
            return
        }
        val prompt = BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    val authed = result.cryptoObject?.cipher ?: return
                    busy("Enabling…") {
                        val vdk = vault.vdkCopy()
                        try {
                            BiometricVault.finishEnroll(this@VaultActivity, authed, vdk)
                        } finally {
                            vdk.fill(0)
                        }
                        ui {
                            toast("Fingerprint unlock enabled")
                            showList()
                        }
                    }
                }

                override fun onAuthenticationError(code: Int, msg: CharSequence) {
                    if (code != BiometricPrompt.ERROR_USER_CANCELED &&
                        code != BiometricPrompt.ERROR_NEGATIVE_BUTTON
                    ) {
                        toast(msg.toString())
                    }
                }
            },
        )
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Enable fingerprint unlock")
            .setSubtitle("Your fingerprint will unwrap the vault key")
            .setNegativeButtonText("Cancel")
            .build()
        prompt.authenticate(info, BiometricPrompt.CryptoObject(cipher))
    }

    private fun showChangePassword() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        val new1 = field("New master password", password = true)
        val new2 = field("Repeat new password", password = true)
        layout.addView(new1); layout.addView(new2)
        AlertDialog.Builder(this)
            .setTitle("Change master password")
            .setView(layout)
            .setPositiveButton("Change") { _, _ ->
                val a = new1.text.toString(); val b = new2.text.toString()
                when {
                    a.length < 8 -> toast("Use at least 8 characters")
                    a != b -> toast("The two passwords do not match")
                    else -> busy("Re-wrapping the vault key…") {
                        vault.changeMasterPassword(a.toCharArray())
                        ui { toast("Master password changed") }
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ------------------------------------------------------------ detail & edit

    private fun showDetail(entry: VaultEntry) {
        val layout = page("🔑  ${entry.title.ifBlank { "Entry" }}")
        layout.addView(kvRow("Username", entry.username) { copy("username", entry.username) })
        var revealed = false
        val pwValue = TextView(this).apply {
            text = "••••••••••"
            textSize = 16f
            setTextColor(TEXT)
        }
        layout.addView(label("Password"))
        val pwRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        pwRow.addView(pwValue.apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        pwRow.addView(actionButton("Show") {
            revealed = !revealed
            pwValue.text = if (revealed) entry.password else "••••••••••"
            (it as Button).text = if (revealed) "Hide" else "Show"
        })
        pwRow.addView(actionButton("Copy") { copy("password", entry.password) })
        layout.addView(pwRow)
        if (entry.url.isNotBlank()) {
            layout.addView(kvRow("Website", entry.url) {
                startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse(entry.url))
                        .setClass(this, BrowserActivity::class.java),
                )
                finish()
            })
        }
        if (entry.notes.isNotBlank()) {
            layout.addView(label("Notes"))
            layout.addView(body(entry.notes))
        }
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(actionButton("✏️ Edit") { showEdit(entry) })
        actions.addView(actionButton("🗑 Delete") {
            AlertDialog.Builder(this)
                .setTitle("Delete entry")
                .setMessage("Delete “${entry.title}” from the vault? This cannot be undone.")
                .setPositiveButton("Delete") { _, _ ->
                    busy("Deleting…") {
                        vault.deleteRecord(entry.id)
                        entries = entries.filter { it.id != entry.id }
                        ui { showList() }
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        })
        actions.addView(actionButton("← Back") { showList() })
        layout.addView(actions)
        setContentView(ScrollView(this).apply { addView(layout) })
    }

    private fun showEdit(existing: VaultEntry?) {
        val layout = page(if (existing == null) "＋  New entry" else "✏️  Edit entry")
        val title = field("Title (e.g. Proton Mail)").apply { setText(existing?.title ?: "") }
        val username = field("Username / email").apply { setText(existing?.username ?: "") }
        val password = field("Password", password = true).apply { setText(existing?.password ?: "") }
        val url = field("Website (https://…)").apply { setText(existing?.url ?: "") }
        val notes = field("Notes").apply { setText(existing?.notes ?: "") }
        layout.addView(title); layout.addView(username)
        val pwRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        pwRow.addView(password.apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        pwRow.addView(actionButton("🎲 Generate") {
            password.setText(PasswordGenerator.generate(16, symbols = true))
        })
        layout.addView(pwRow)
        layout.addView(url); layout.addView(notes)
        layout.addView(button("Save") {
            val entry = VaultEntry(
                id = existing?.id ?: "",
                title = title.text.toString().trim(),
                username = username.text.toString().trim(),
                password = password.text.toString(),
                url = url.text.toString().trim(),
                notes = notes.text.toString().trim(),
            )
            if (entry.title.isBlank() && entry.url.isBlank()) {
                toast("Give the entry at least a title or a website")
            } else {
                busy("Saving…") {
                    val id = vault.putRecord(
                        existing?.id, VaultCore.TYPE_LOGIN, entry.toBytes(),
                    )
                    val saved = entry.copy(id = id)
                    entries = (entries.filter { it.id != id } + saved)
                        .sortedBy { it.title.lowercase() }
                    ui { showDetail(saved) }
                }
            }
        })
        layout.addView(actionButton("← Cancel") { if (existing != null) showDetail(existing) else showList() })
        setContentView(ScrollView(this).apply { addView(layout) })
    }

    // ------------------------------------------------------------ helpers

    /** Runs vault work off the main thread (Argon2id is deliberately slow). */
    private fun busy(message: String, work: suspend () -> Unit) {
        val loading = page("🔑  Vault")
        loading.addView(body(message))
        setContentView(ScrollView(this).apply { addView(loading) })
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.Default) { work() }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    toast(e.message ?: "Vault error")
                    route()
                }
            }
        }
    }

    /** Hops to the main thread for a UI transition from inside [busy]. */
    private suspend fun ui(block: () -> Unit) = withContext(Dispatchers.Main) { block() }

    private fun copy(label: String, value: String) {
        val cm = getSystemService(ClipboardManager::class.java)
        cm.setPrimaryClip(ClipData.newPlainText(label, value))
        toast("$label copied — the clipboard is shared with other apps; paste promptly")
    }

    private fun toast(text: String) =
        Toast.makeText(this, text, Toast.LENGTH_LONG).show()

    private fun page(title: String): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(BG)
        setPadding(dp(18), dp(18), dp(18), dp(16))
        addView(TextView(this@VaultActivity).apply {
            text = title
            textSize = 20f
            setTextColor(TEXT)
            setPadding(0, 0, 0, dp(10))
        })
    }

    private fun body(text: String) = TextView(this).apply {
        this.text = text
        textSize = 13f
        setTextColor(DIM)
        setPadding(0, dp(4), 0, dp(8))
    }

    private fun label(text: String) = TextView(this).apply {
        this.text = text
        textSize = 12f
        setTextColor(DIM)
        setPadding(0, dp(10), 0, dp(2))
    }

    private fun field(hint: String, password: Boolean = false) = EditText(this).apply {
        this.hint = hint
        textSize = 15f
        setTextColor(TEXT)
        setHintTextColor(DIM)
        if (password) {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        setPadding(0, dp(8), 0, dp(8))
    }

    private fun button(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        setOnClickListener { onClick() }
    }

    private fun actionButton(text: String, onClick: (View) -> Unit) = Button(this).apply {
        this.text = text
        textSize = 13f
        setOnClickListener(onClick)
    }

    private fun kvRow(key: String, value: String, onAction: (() -> Unit)?): View {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        box.addView(label(key))
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(TextView(this).apply {
            text = value.ifBlank { "—" }
            textSize = 16f
            setTextColor(TEXT)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        if (onAction != null && value.isNotBlank()) {
            row.addView(actionButton(if (key == "Website") "Open" else "Copy") { onAction() })
        }
        box.addView(row)
        return box
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val AUTO_LOCK_MS = 3 * 60 * 1000L
        val BG = Color.parseColor("#0B1626")
        val TEXT = Color.parseColor("#E6F1F5")
        val DIM = Color.parseColor("#8FB3C7")
        val TEAL = Color.parseColor("#2DD4BF")
    }
}
