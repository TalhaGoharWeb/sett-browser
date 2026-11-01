package app.privatex.browser

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import mozilla.components.concept.engine.webextension.EnableSource
import mozilla.components.concept.engine.webextension.InstallationMethod
import mozilla.components.concept.engine.webextension.WebExtension
import mozilla.components.concept.engine.webextension.WebExtensionRuntime

/**
 * Extensions manager — the honest reduced form the roadmap promised.
 * Works: install from a direct .xpi URL or a local file, list with
 * name/version/state, enable/disable, remove, and a full display of
 * the permissions and site access each extension declares. Does not
 * exist here: an AMO storefront, guaranteed auto-updates, and toolbar
 * popup support (extensions whose only UI is a toolbar popup are
 * limited; background/content extensions work best).
 */
class ExtensionsActivity : AppCompatActivity() {

    private val components get() = (application as PrivateXApp).components
    private val ui by lazy { app.privatex.browser.ui.SettUi(this, components.settings) }
    private val BG get() = ui.palette.bg
    private val TEXT get() = ui.palette.text
    private val DIM get() = ui.palette.dim
    private val TEAL get() = ui.palette.accent
    private val runtime get() = components.engine as WebExtensionRuntime
    private lateinit var listBox: LinearLayout

    private val pickXpi = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val uri = result.data?.data ?: return@registerForActivityResult
        if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
        installFromFile(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui.applyPlatformTheme()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
            setPadding(dp(18), dp(18), dp(18), dp(16))
        }
        root.addView(title("🧩  Extensions"))
        root.addView(body(
            "Standard Firefox-for-Android style WebExtensions, installed by you. " +
                "Background and content extensions (blockers, redirectors, dark-mode " +
                    "injectors) work best; an extension whose only interface is a toolbar " +
                    "popup is limited here. There is no add-on store inside Sett and no " +
                    "auto-update promise — you install, you control.",
        ))
        val urlField = EditText(this).apply {
            hint = "Direct .xpi link (https://…/addon.xpi)"
            textSize = 14f
            setTextColor(TEXT)
            setHintTextColor(DIM)
        }
        root.addView(urlField)
        root.addView(Button(this).apply {
            text = "Install from link"
            setOnClickListener {
                val url = urlField.text.toString().trim()
                if (url.startsWith("https://") || url.startsWith("http://")) {
                    installFromUrl(url)
                } else {
                    toast("Paste a direct link to an .xpi file")
                }
            }
        })
        root.addView(Button(this).apply {
            text = "Install from a file…"
            setOnClickListener {
                pickXpi.launch(
                    Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "*/*"
                    },
                )
            }
        })
        root.addView(title("Installed"))
        listBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(listBox)
        setContentView(ScrollView(this).apply { addView(root) })
        refresh()
    }

    private fun refresh() {
        listBox.removeAllViews()
        listBox.addView(body("Loading…"))
        runtime.listInstalledWebExtensions({ extensions ->
            runOnUiThread {
                listBox.removeAllViews()
                if (extensions.isEmpty()) {
                    listBox.addView(body("No extensions installed."))
                } else {
                    extensions.sortedBy { it.getMetadata()?.name ?: it.id }
                        .forEach { listBox.addView(extensionRow(it)) }
                }
            }
        }, { error ->
            runOnUiThread {
                listBox.removeAllViews()
                listBox.addView(body("Could not list extensions: ${error.message}"))
            }
        })
    }

    private fun extensionRow(ext: WebExtension): LinearLayout {
        val meta = ext.getMetadata()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(10), 0, dp(10))
        }
        box.addView(TextView(this).apply {
            text = (meta?.name ?: ext.id) + (meta?.version?.let { "  ·  v$it" } ?: "")
            textSize = 16f
            setTextColor(TEXT)
        })
        box.addView(TextView(this).apply {
            text = when {
                ext.isBuiltIn() -> "Built-in"
                ext.isEnabled() -> "Enabled"
                else -> "Disabled"
            } + (meta?.developerName?.let { "  ·  by $it" } ?: "")
            textSize = 13f
            setTextColor(DIM)
        })
        meta?.description?.takeIf { it.isNotBlank() }?.let { desc ->
            box.addView(TextView(this).apply {
                text = desc
                textSize = 13f
                setTextColor(DIM)
            })
        }
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(smallButton(if (ext.isEnabled()) "Disable" else "Enable") {
            val onError: (Throwable) -> Unit = { toast("Failed: ${it.message}") }
            val onSuccess: (WebExtension) -> Unit = { refresh() }
            if (ext.isEnabled()) {
                runtime.disableWebExtension(ext, EnableSource.USER, onSuccess, onError)
            } else {
                runtime.enableWebExtension(ext, EnableSource.USER, onSuccess, onError)
            }
        })
        actions.addView(smallButton("Permissions") { showPermissions(ext) })
        if (!ext.isBuiltIn()) {
            actions.addView(smallButton("Remove") {
                AlertDialog.Builder(this)
                    .setTitle("Remove extension")
                    .setMessage("Remove “${meta?.name ?: ext.id}”? Its data is deleted with it.")
                    .setPositiveButton("Remove") { _, _ ->
                        runtime.uninstallWebExtension(ext, { refresh() }, { _, err ->
                            toast("Remove failed: ${err.message}")
                        })
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            })
        }
        box.addView(actions)
        return box
    }

    private fun showPermissions(ext: WebExtension) {
        val meta = ext.getMetadata()
        val text = StringBuilder()
        fun section(label: String, items: List<String>) {
            if (items.isEmpty()) return
            text.append(label).append('\n')
            items.forEach { text.append("• ").append(it).append('\n') }
            text.append('\n')
        }
        if (meta == null) {
            text.append("No metadata available for this extension.")
        } else {
            section("Permissions it requires:", meta.requiredPermissions)
            section("Sites it can access:", meta.requiredOrigins)
            section("Optional permissions granted:", meta.grantedOptionalPermissions)
            section("Optional site access granted:", meta.grantedOptionalOrigins)
            if (text.isEmpty()) text.append("It declares no permissions and no site access.")
        }
        AlertDialog.Builder(this)
            .setTitle(meta?.name ?: ext.id)
            .setMessage(text.toString().trim())
            .setPositiveButton("Close", null)
            .show()
    }

    private fun installFromUrl(url: String) {
        toast("Installing…")
        runtime.installWebExtension(url, InstallationMethod.MANAGER, { ext ->
            runOnUiThread {
                toast("Installed: ${ext.getMetadata()?.name ?: ext.id}")
                refresh()
            }
        }, { error ->
            runOnUiThread { toast("Install failed: ${error.message}") }
        })
    }

    private fun installFromFile(uri: Uri) {
        val dir = File(filesDir, "extensions").apply { mkdirs() }
        val target = File(dir, "install-${System.currentTimeMillis()}.xpi")
        runCatching {
            contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { input.copyTo(it) }
            } ?: throw IllegalStateException("Could not read the file")
        }.onFailure {
            toast("Could not copy the file: ${it.message}")
            return
        }
        runtime.installWebExtension(
            Uri.fromFile(target).toString(),
            InstallationMethod.FROM_FILE,
            { ext ->
                runOnUiThread {
                    toast("Installed: ${ext.getMetadata()?.name ?: ext.id}")
                    refresh()
                }
            },
            { error -> runOnUiThread { toast("Install failed: ${error.message}") } },
        )
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

    private fun smallButton(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        textSize = 12f
        setOnClickListener { onClick() }
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
    }
}
