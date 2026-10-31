package app.privatex.browser

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.Menu
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import mozilla.components.browser.state.selector.selectedTab
import mozilla.components.browser.state.state.BrowserState
import mozilla.components.browser.state.state.TabSessionState
import mozilla.components.concept.engine.Engine
import mozilla.components.concept.engine.EngineView
import mozilla.components.feature.downloads.DownloadsFeature
import mozilla.components.feature.downloads.manager.FetchDownloadManager
import mozilla.components.feature.findinpage.FindInPageFeature
import mozilla.components.feature.findinpage.view.FindInPageBar
import mozilla.components.feature.session.SessionFeature
import mozilla.components.lib.state.ext.flow

class BrowserActivity : AppCompatActivity() {

    private lateinit var engineView: EngineView
    private lateinit var sessionFeature: SessionFeature
    private lateinit var findFeature: FindInPageFeature
    private lateinit var downloadsFeature: DownloadsFeature
    private lateinit var findBar: FindInPageBar

    private lateinit var address: EditText
    private lateinit var progress: ProgressBar

    private var lastShownUrl: String? = null
    private var lastTabCount: Int = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_browser)

        val components = components
        address = findViewById(R.id.address)
        progress = findViewById(R.id.progress)

        val container = findViewById<ViewGroup>(R.id.engine_container)
        engineView = components.engine.createView(this)
        container.addView(engineView.asView())

        // Find-in-page bar, inserted above the bottom toolbar, hidden until used.
        findBar = FindInPageBar(this)
        findBar.visibility = View.GONE
        val root = findViewById<View>(R.id.progress).parent as LinearLayout
        root.addView(
            findBar,
            root.childCount - 1,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )

        sessionFeature = SessionFeature(
            components.store,
            components.sessionUseCases.goBack,
            components.sessionUseCases.goForward,
            engineView,
        )
        findFeature = FindInPageFeature(components.store, findBar, engineView) {
            findBar.visibility = View.GONE
        }
        downloadsFeature = DownloadsFeature(
            applicationContext = applicationContext,
            store = components.store,
            useCases = components.downloadsUseCases,
            downloadFileUtils = components.downloadFileUtils,
            downloadManager = FetchDownloadManager(
                applicationContext = applicationContext,
                store = components.store,
                service = DownloadService::class,
                notificationsDelegate = components.notificationsDelegate,
            ),
            fragmentManager = supportFragmentManager,
            onDownloadStartedListener = {
                Toast.makeText(this, "Download started", Toast.LENGTH_SHORT).show()
            },
        )

        // First run / all tabs closed: open the local start page.
        if (components.store.state.tabs.isEmpty()) {
            components.tabsUseCases.addTab(Components.HOME_URL, selectTab = true)
        }
        handleViewIntent(intent)

        address.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO) {
                navigate(address.text.toString())
                true
            } else {
                false
            }
        }
        address.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) address.selectAll()
        }
        findViewById<ImageButton>(R.id.btn_reload).setOnClickListener {
            components.sessionUseCases.reload()
        }
        findViewById<ImageButton>(R.id.btn_tabs).setOnClickListener { showTabsTray() }
        findViewById<ImageButton>(R.id.btn_shields).setOnClickListener { showShields() }
        findViewById<ImageButton>(R.id.btn_menu).setOnClickListener { showMenu(it) }

        onBackPressedDispatcher.addCallback(
            this,
            object : androidx.activity.OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    when {
                        findFeature.onBackPressed() -> Unit
                        sessionFeature.onBackPressed() -> Unit
                        else -> finish()
                    }
                }
            },
        )

        lifecycleScope.launch {
            components.store.flow().collectLatest { state -> render(state) }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleViewIntent(intent)
    }

    private fun handleViewIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_VIEW) {
            intent.dataString?.let { url ->
                components.tabsUseCases.addTab(url, selectTab = true)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        sessionFeature.start()
        findFeature.start()
        downloadsFeature.start()
    }

    override fun onStop() {
        super.onStop()
        sessionFeature.stop()
        findFeature.stop()
        downloadsFeature.stop()
    }

    override fun onPause() {
        super.onPause()
        engineView.onPause()
    }

    override fun onResume() {
        super.onResume()
        engineView.onResume()
    }

    private fun render(state: BrowserState) {
        val tab = state.selectedTab
        val url = tab?.content?.url.orEmpty()
        if (url != lastShownUrl) {
            lastShownUrl = url
            if (!address.hasFocus()) {
                address.setText(if (url == Components.HOME_URL) "" else url)
            }
        }
        val loading = tab?.content?.loading == true
        progress.visibility = if (loading) View.VISIBLE else View.GONE
        if (loading) progress.progress = tab?.content?.progress ?: 0
        if (state.tabs.size != lastTabCount) {
            lastTabCount = state.tabs.size
            findViewById<ImageButton>(R.id.btn_tabs).contentDescription =
                "Tabs (${state.tabs.size})"
        }
        tab?.let { findFeature.bind(it) }
    }

    private fun navigate(input: String) {
        val url = AddressResolver.resolve(input, components.settings)
        components.sessionUseCases.loadUrl(url)
        address.clearFocus()
    }

    // ------------------------------------------------------------------ tabs

    private fun showTabsTray() {
        val dialog = BottomSheetDialog(this)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
        }
        val list = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@BrowserActivity)
            adapter = TabsAdapter(
                tabs = components.store.state.tabs,
                selectedId = components.store.state.selectedTabId,
                onSelect = { id ->
                    components.tabsUseCases.selectTab(id)
                    dialog.dismiss()
                },
                onClose = { id ->
                    components.tabsUseCases.removeTab(id)
                    dialog.dismiss()
                    if (components.store.state.tabs.isEmpty()) {
                        components.tabsUseCases.addTab(Components.HOME_URL, selectTab = true)
                    }
                },
            )
        }
        layout.addView(list)
        val newTab = TextView(this).apply {
            text = "+  New tab"
            textSize = 16f
            setPadding(16, 28, 16, 12)
            setOnClickListener {
                components.tabsUseCases.addTab(Components.HOME_URL, selectTab = true)
                dialog.dismiss()
            }
        }
        val newPrivate = TextView(this).apply {
            text = "+  New private tab"
            textSize = 16f
            setPadding(16, 12, 16, 28)
            setOnClickListener {
                components.tabsUseCases.addTab(Components.HOME_URL, selectTab = true, private = true)
                dialog.dismiss()
            }
        }
        layout.addView(newTab)
        layout.addView(newPrivate)
        dialog.setContentView(layout)
        dialog.show()
    }

    private inner class TabsAdapter(
        private val tabs: List<TabSessionState>,
        private val selectedId: String?,
        private val onSelect: (String) -> Unit,
        private val onClose: (String) -> Unit,
    ) : RecyclerView.Adapter<TabsAdapter.Holder>() {

        inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
            val title: TextView = view.findViewById(android.R.id.text1)
            val close: TextView = view.findViewById(android.R.id.text2)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val row = LinearLayout(parent.context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(16, 20, 16, 20)
                layoutParams = RecyclerView.LayoutParams(
                    RecyclerView.LayoutParams.MATCH_PARENT,
                    RecyclerView.LayoutParams.WRAP_CONTENT,
                )
            }
            val title = TextView(parent.context).apply {
                id = android.R.id.text1
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                textSize = 15f
            }
            val close = TextView(parent.context).apply {
                id = android.R.id.text2
                text = "✕"
                textSize = 16f
                setPadding(24, 8, 8, 8)
            }
            row.addView(title)
            row.addView(close)
            return Holder(row)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val tab = tabs[position]
            val name = tab.content.title.ifBlank { tab.content.url }
            val marker = when {
                tab.id == selectedId -> "● "
                else -> ""
            }
            val kind = if (tab.content.private) " (private)" else ""
            holder.title.text = "$marker$name$kind"
            holder.itemView.setOnClickListener { onSelect(tab.id) }
            holder.close.setOnClickListener { onClose(tab.id) }
        }

        override fun getItemCount() = tabs.size
    }

    // --------------------------------------------------------------- shields

    private fun showShields() {
        val tab = components.store.state.selectedTab
        val session = tab?.engineState?.engineSession
        val host = tab?.content?.url?.let { runCatching { java.net.URI(it).host }.getOrNull() }
        val security = when (tab?.content?.securityInfo) {
            is mozilla.components.browser.state.state.SecurityInfo.Secure -> "Secure connection (HTTPS)"
            is mozilla.components.browser.state.state.SecurityInfo.Insecure -> "Not secure (no valid HTTPS)"
            else -> "Connection state unknown"
        }
        val etp = if (components.settings.etpStrict) "Strict" else "Standard"
        val base = StringBuilder()
            .append(security).append("\n\n")
            .append("Enhanced Tracking Protection: $etp (engine-level)\n")
            .append("HTTPS-Only Mode: on\n")
            .append("Global Privacy Control: on\n")
            .append("Fingerprinting protection: on")
            .append(if (components.settings.strictFingerprinting) " + resistFingerprinting profile" else "")
            .append("\nTracking-parameter stripping: on\n")
            .append("Site: ").append(host ?: "—")

        if (session == null) {
            AlertDialog.Builder(this).setTitle("Shields")
                .setMessage(base.toString()).setPositiveButton("OK", null).show()
            return
        }
        components.engine.trackingProtectionExceptionStore.contains(session) { isExcepted ->
            runOnUiThread {
                val msg = base.append(
                    if (isExcepted) {
                        "\n\nTracking protection is OFF for this site (your exception)."
                    } else {
                        "\n\nTracking protection is active for this site."
                    },
                ).toString()
                AlertDialog.Builder(this).setTitle("Shields")
                    .setMessage(msg)
                    .setPositiveButton("OK", null)
                    .setNegativeButton(
                        if (isExcepted) "Enable protection for this site" else "Disable protection for this site",
                    ) { _, _ ->
                        if (isExcepted) {
                            components.engine.trackingProtectionExceptionStore.remove(session)
                        } else {
                            components.engine.trackingProtectionExceptionStore.add(session)
                        }
                        components.sessionUseCases.reload()
                    }
                    .show()
            }
        }
    }

    // ------------------------------------------------------------------ menu

    private fun showMenu(anchor: View) {
        val popup = PopupMenu(this, anchor)
        popup.menu.add(Menu.NONE, 1, 0, "New tab")
        popup.menu.add(Menu.NONE, 2, 1, "New private tab")
        popup.menu.add(Menu.NONE, 3, 2, "Find in page")
        popup.menu.add(Menu.NONE, 4, 3, "Downloads")
        popup.menu.add(Menu.NONE, 5, 4, "Settings")
        popup.menu.add(Menu.NONE, 6, 5, "Clear all browsing data")
        popup.menu.add(Menu.NONE, 7, 6, "About & limits")
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> components.tabsUseCases.addTab(Components.HOME_URL, selectTab = true)
                2 -> components.tabsUseCases.addTab(Components.HOME_URL, selectTab = true, private = true)
                3 -> {
                    findBar.visibility = View.VISIBLE
                    findBar.requestFocus()
                }
                4 -> showDownloads()
                5 -> showSettings()
                6 -> confirmFire()
                7 -> showAbout()
            }
            true
        }
        popup.show()
    }

    private fun showDownloads() {
        val downloads = components.store.state.downloads.values.toList()
        if (downloads.isEmpty()) {
            Toast.makeText(this, "No downloads yet", Toast.LENGTH_SHORT).show()
            return
        }
        val labels = downloads.map { d ->
            val name = d.fileName ?: d.url
            "$name — ${d.status.name.lowercase()}"
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Downloads")
            .setItems(labels) { _, which ->
                val d = downloads[which]
                components.downloadsUseCases.removeDownload(d.id)
                Toast.makeText(this, "Removed from list", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun showSettings() {
        val settings = components.settings
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 8)
        }
        fun label(text: String) = TextView(this).apply {
            this.text = text
            textSize = 15f
            setPadding(0, 24, 0, 8)
        }
        fun option(group: RadioGroup, text: String, checked: Boolean): RadioButton {
            val rb = RadioButton(this).apply {
                this.text = text
                isChecked = checked
                textSize = 14f
            }
            group.addView(rb)
            return rb
        }

        val etpGroup = RadioGroup(this)
        val etpStandard = option(etpGroup, "Tracking protection: Standard", !settings.etpStrict)
        option(etpGroup, "Tracking protection: Strict (may break some sites)", settings.etpStrict)
        layout.addView(label("Privacy"))
        layout.addView(etpGroup)

        val searchGroup = RadioGroup(this)
        val searchButtons = SettingsStore.SEARCH_ENGINES.map { (key, name) ->
            key to option(searchGroup, name, settings.searchEngine == key)
        }
        layout.addView(label("Search engine"))
        layout.addView(searchGroup)

        val fpGroup = RadioGroup(this)
        val fpBalanced = option(fpGroup, "Fingerprinting: Balanced", !settings.strictFingerprinting)
        option(
            fpGroup,
            "Fingerprinting: Strict — resistFingerprinting + letterboxing (applies after restart; may break some sites)",
            settings.strictFingerprinting,
        )
        layout.addView(label("Fingerprinting"))
        layout.addView(fpGroup)

        AlertDialog.Builder(this)
            .setTitle("Settings")
            .setView(layout)
            .setPositiveButton("Apply") { _, _ ->
                settings.etpStrict = !etpStandard.isChecked
                components.engine.settings.trackingProtectionPolicy =
                    if (settings.etpStrict) {
                        mozilla.components.concept.engine.EngineSession.TrackingProtectionPolicy.strict()
                    } else {
                        mozilla.components.concept.engine.EngineSession.TrackingProtectionPolicy.recommended()
                    }
                settings.searchEngine = searchButtons.firstOrNull { it.second.isChecked }?.first
                    ?: settings.searchEngine
                settings.strictFingerprinting = !fpBalanced.isChecked
                Toast.makeText(
                    this,
                    "Applied to new tabs. Fingerprinting profile applies after restart.",
                    Toast.LENGTH_LONG,
                ).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmFire() {
        AlertDialog.Builder(this)
            .setTitle("Clear all browsing data?")
            .setMessage(
                "Deletes cookies, site data, caches and history for every site, " +
                    "and closes all tabs. This is done by the engine itself.",
            )
            .setPositiveButton("Clear everything") { _, _ ->
                components.engine.clearData(
                    Engine.BrowsingData.all(),
                    null,
                    {
                        runOnUiThread {
                            val ids = components.store.state.tabs.map { it.id }
                            ids.forEach { components.tabsUseCases.removeTab(it) }
                            components.tabsUseCases.addTab(Components.HOME_URL, selectTab = true)
                            Toast.makeText(this, "Browsing data cleared", Toast.LENGTH_SHORT).show()
                        }
                    },
                    {
                        runOnUiThread {
                            Toast.makeText(this, "Clear failed — see limits", Toast.LENGTH_SHORT).show()
                        }
                    },
                )
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showAbout() {
        AlertDialog.Builder(this)
            .setTitle("Private X — About & limits")
            .setMessage(
                "Private X 0.1.0 — free, open-source (GPL-3.0), no accounts, no telemetry.\n\n" +
                    "Engine: GeckoView ${components.engine.version} (Mozilla's build), " +
                    "the same engine family as Firefox for Android.\n\n" +
                    "Honest limits:\n" +
                    "• A browser reduces tracking; it cannot make you anonymous. " +
                    "Your network provider still sees the sites you connect to.\n" +
                    "• Fingerprinting resistance protects best when many users share " +
                    "the same fingerprint; no score is claimed.\n" +
                    "• Private tabs isolate cookies/storage from normal tabs; " +
                    "they are not separate identities against a determined observer.\n" +
                    "• Extensions, password vault and Private Spaces arrive in later versions.",
            )
            .setPositiveButton("OK", null)
            .show()
    }
}
