package app.privatex.browser

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.widget.addTextChangedListener
import androidx.lifecycle.lifecycleScope
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import mozilla.components.browser.state.state.content.DownloadState
import mozilla.appservices.places.BookmarkRoot
import mozilla.components.concept.storage.BookmarkNode
import mozilla.components.concept.storage.BookmarkNodeType
import mozilla.components.concept.storage.VisitInfo

/**
 * The Library: bookmarks, history, downloads and recently closed tabs —
 * all backed by the on-device places database / browser store. Nothing
 * here syncs anywhere; there is no account.
 */
class LibraryActivity : AppCompatActivity() {

    private data class Row(
        val title: String,
        val subtitle: String,
        val header: Boolean = false,
        val onTap: (() -> Unit)? = null,
        val onLongPress: (() -> Unit)? = null,
    )

    private lateinit var list: ListView
    private lateinit var search: EditText
    private lateinit var sectionButtons: List<TextView>
    private var section = SECTION_BOOKMARKS
    private var allRows: List<Row> = emptyList()

    private val components get() = (application as PrivateXApp).components
    private val ui by lazy { app.privatex.browser.ui.SettUi(this, components.settings) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui.applyPlatformTheme()
        val navy = ui.palette.bg
        val teal = ui.palette.accent
        val ink = ui.palette.text
        val dim = ui.palette.dim

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(navy)
            setPadding(dp(16), dp(18), dp(16), dp(12))
        }
        root.addView(TextView(this).apply {
            text = "🗂  Library"
            textSize = 20f
            setTextColor(ink)
            setPadding(0, 0, 0, dp(12))
        })
        val strip = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        sectionButtons = listOf("Bookmarks", "History", "Downloads", "Closed tabs", "Reading").mapIndexed { i, label ->
            TextView(this).apply {
                text = label
                textSize = 14f
                gravity = Gravity.CENTER
                setPadding(dp(10), dp(8), dp(10), dp(8))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener { section = i; refreshTabs(teal, dim); load() }
            }.also { strip.addView(it) }
        }
        root.addView(strip)
        search = EditText(this).apply {
            hint = "Search"
            setTextColor(ink)
            setHintTextColor(dim)
            textSize = 15f
            addTextChangedListener { applyFilter(it?.toString().orEmpty()) }
        }
        root.addView(search)
        list = ListView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f,
            )
            divider = null
        }
        root.addView(list)
        setContentView(root)
        refreshTabs(teal, dim)
        load()
    }

    private fun refreshTabs(teal: Int, dim: Int) {
        sectionButtons.forEachIndexed { i, tv ->
            tv.setTextColor(if (i == section) teal else dim)
        }
        search.visibility = if (section == SECTION_DOWNLOADS || section == SECTION_CLOSED) {
            View.GONE
        } else {
            View.VISIBLE
        }
    }

    private fun load() {
        when (section) {
            SECTION_BOOKMARKS -> loadBookmarks()
            SECTION_HISTORY -> loadHistory()
            SECTION_DOWNLOADS -> loadDownloads()
            SECTION_CLOSED -> loadClosed()
            SECTION_READING -> loadReading()
        }
    }

    private fun showRows(rows: List<Row>) {
        allRows = rows
        applyFilter(search.text?.toString().orEmpty())
    }

    private fun applyFilter(query: String) {
        val q = query.trim().lowercase()
        val visible = if (q.isEmpty()) {
            allRows
        } else {
            allRows.filter {
                it.header || it.title.lowercase().contains(q) || it.subtitle.lowercase().contains(q)
            }
        }
        val adapter = object : ArrayAdapter<Row>(this, 0, visible) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val row = getItem(position)!!
                val layout = (convertView as? LinearLayout) ?: LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(10), dp(10), dp(10), dp(10))
                }
                layout.removeAllViews()
                layout.addView(TextView(context).apply {
                    text = row.title
                    textSize = if (row.header) 13f else 15f
                    setTextColor(
                        if (row.header) ui.palette.accent else ui.palette.text,
                    )
                })
                if (row.subtitle.isNotBlank()) {
                    layout.addView(TextView(context).apply {
                        text = row.subtitle
                        textSize = 12f
                        setTextColor(ui.palette.dim)
                        maxLines = 1
                        ellipsize = android.text.TextUtils.TruncateAt.END
                    })
                }
                return layout
            }
        }
        list.adapter = adapter
        list.setOnItemClickListener { _, _, pos, _ -> visible.getOrNull(pos)?.onTap?.invoke() }
        list.setOnItemLongClickListener { _, _, pos, _ ->
            visible.getOrNull(pos)?.onLongPress?.let { it(); true } ?: false
        }
    }

    // --- Reading list (redesign Phase 5) --------------------------------

    private fun loadReading() {
        val items = runCatching { components.readingListStore.items() }
            .getOrDefault(emptyList())
        if (items.isEmpty()) {
            showRows(
                listOf(
                    Row(
                        "No saved pages yet",
                        "On any page: menu → Tabs & page → Save to reading list.",
                    ),
                ),
            )
            return
        }
        showRows(
            items.map { item ->
                Row(
                    title = item.title,
                    subtitle = item.url,
                    onTap = { openInBrowser(item.url) },
                    onLongPress = {
                        confirm("Remove from reading list", item.title) {
                            components.readingListStore.remove(item.id)
                            loadReading()
                        }
                    },
                )
            },
        )
    }

    // --- Bookmarks -----------------------------------------------------

    private fun flatten(node: BookmarkNode, depth: Int, out: MutableList<Row>) {
        when (node.type) {
            BookmarkNodeType.FOLDER -> {
                if (depth > 0 || !node.title.isNullOrBlank()) {
                    out.add(Row("📁 ${node.title.orEmpty()}", "", header = true))
                }
                node.children?.forEach { flatten(it, depth + 1, out) }
            }
            BookmarkNodeType.ITEM -> {
                val url = node.url.orEmpty()
                out.add(
                    Row(
                        title = node.title?.ifBlank { url } ?: url,
                        subtitle = url,
                        onTap = { openInBrowser(url) },
                        onLongPress = {
                            confirm("Remove bookmark", node.title ?: url) {
                                lifecycleScope.launch {
                                    runCatching {
                                        components.bookmarksStorage.deleteNode(node.guid)
                                    }
                                    loadBookmarks()
                                }
                            }
                        },
                    ),
                )
            }
            else -> Unit
        }
    }

    private fun loadBookmarks() {
        lifecycleScope.launch {
            val tree = runCatching {
                components.bookmarksStorage.getTree(BookmarkRoot.Mobile.id, recursive = true)
            }.getOrNull()?.getOrNull()
            val rows = mutableListOf<Row>()
            if (tree != null) flatten(tree, 0, rows)
            if (rows.none { !it.header }) {
                rows.add(Row("No bookmarks yet", "Use the menu on any page: “Bookmark this page”."))
            }
            showRows(rows)
        }
    }

    // --- History -------------------------------------------------------

    private fun loadHistory() {
        lifecycleScope.launch {
            val allVisits = runCatching {
                components.historyStorage.getDetailedVisits(0, Long.MAX_VALUE)
            }.getOrNull().orEmpty()
                .sortedByDescending { it.visitTime }
            // Cap the rendered list (audit SCR-2): 300 rows render
            // instantly; the note says plainly when more exist.
            val visits = allVisits.take(300)
            val rows = mutableListOf<Row>()
            if (allVisits.size > visits.size) {
                rows.add(
                    Row(
                        "Showing the ${visits.size} most recent of ${allVisits.size} visits",
                        "Older visits are still stored; clear history from the menu's Fire action.",
                        header = true,
                    ),
                )
            }
            rows += visits.map { visit: VisitInfo ->
                Row(
                    title = visit.title?.ifBlank { visit.url } ?: visit.url,
                    subtitle = visit.url,
                    onTap = { openInBrowser(visit.url) },
                    onLongPress = {
                        confirm("Delete this visit", visit.url) {
                            lifecycleScope.launch {
                                runCatching {
                                    components.historyStorage.deleteVisit(visit.url, visit.visitTime)
                                }
                                loadHistory()
                            }
                        }
                    },
                )
            }.toMutableList()
            if (rows.isEmpty()) {
                rows.add(Row("No history yet", "Pages you visit will appear here. Private tabs never record history."))
            } else {
                rows.add(
                    0,
                    Row(
                        title = "🧹 Clear all history",
                        subtitle = "Deletes every recorded visit on this device",
                        onTap = {
                            confirm("Clear all history", "Every recorded visit will be deleted.") {
                                lifecycleScope.launch {
                                    runCatching { components.historyStorage.deleteEverything() }
                                    loadHistory()
                                }
                            }
                        },
                    ),
                )
            }
            showRows(rows)
        }
    }

    // --- Downloads -----------------------------------------------------

    private fun loadDownloads() {
        val downloads = components.store.state.downloads.values
            .sortedByDescending { it.id }
        val rows = downloads.map { d: DownloadState ->
            Row(
                title = d.fileName ?: d.url,
                subtitle = "${d.status.name.lowercase()} · ${d.url}",
                onTap = {
                    // AC 159's DownloadsUseCases has no pause/resume —
                    // those live on the download notification in this
                    // version. Tapping a finished file opens it.
                    if (d.status == DownloadState.Status.COMPLETED) {
                        openDownloaded(d)
                    } else {
                        Toast.makeText(
                            this@LibraryActivity,
                            "Status: ${d.status.name.lowercase()} — pause/resume is on the download notification",
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                },
                onLongPress = {
                    confirm("Remove from list", d.fileName ?: d.url) {
                        components.downloadsUseCases.removeDownload(d.id)
                        loadDownloads()
                    }
                },
            )
        }.toMutableList()
        if (rows.isEmpty()) {
            rows.add(Row("No downloads yet", "Files you download appear here and in your Downloads/Sett folder."))
        }
        showRows(rows)
    }

    private fun openDownloaded(d: DownloadState) {
        val name = d.fileName ?: return
        val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val file = listOf(File(dir, name), File(File(dir, "Sett"), name)).firstOrNull { it.exists() }
        if (file == null) {
            Toast.makeText(this, "File not found — it was moved or deleted", Toast.LENGTH_LONG).show()
            return
        }
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, contentResolver.getType(uri) ?: "*/*")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { startActivity(intent) }.onFailure {
            Toast.makeText(this, "No app can open this file", Toast.LENGTH_SHORT).show()
        }
    }

    // --- Recently closed ----------------------------------------------

    private fun loadClosed() {
        lifecycleScope.launch {
            val tabs = runCatching { components.recentlyClosedTabsStorage.getTabs().first() }
                .getOrNull().orEmpty()
            val rows = tabs.map { tab ->
                Row(
                    title = tab.title.ifBlank { tab.url },
                    subtitle = tab.url,
                    onTap = {
                        lifecycleScope.launch {
                            runCatching {
                                components.tabsUseCases.restore(
                                    tab,
                                    components.recentlyClosedTabsStorage.engineStateStorage(),
                                )
                                components.recentlyClosedTabsStorage.removeTab(tab)
                            }
                            startActivity(Intent(this@LibraryActivity, BrowserActivity::class.java))
                            finish()
                        }
                    },
                )
            }.toMutableList()
            if (rows.isEmpty()) {
                rows.add(Row("No recently closed tabs", "Closed tabs are remembered here (up to ${Components.RECENTLY_CLOSED_MAX})."))
            }
            showRows(rows)
        }
    }

    // --- Helpers -------------------------------------------------------

    private fun openInBrowser(url: String) {
        startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url))
                .setClass(this, BrowserActivity::class.java),
        )
        finish()
    }

    private fun confirm(title: String, message: String, action: () -> Unit) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("Yes") { _, _ -> action() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val SECTION_BOOKMARKS = 0
        const val SECTION_HISTORY = 1
        const val SECTION_DOWNLOADS = 2
        const val SECTION_CLOSED = 3
        const val SECTION_READING = 4
    }
}
