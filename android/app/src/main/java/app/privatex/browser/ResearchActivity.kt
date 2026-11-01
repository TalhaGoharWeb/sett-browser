package app.privatex.browser

import android.app.Activity
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
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import app.privatex.browser.research.ResearchStore
import app.privatex.browser.tools.DevTools
import app.privatex.browser.tools.PdfTools
import app.privatex.browser.tools.ScholarTools
import java.io.File
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Scholar Research Desk (Phase 8): live scholarly search against the
 * keyless open APIs (OpenAlex, Crossref, DataCite, DOAJ), DOI lookup,
 * open-access signals taken only from what the APIs themselves
 * report, local collections with notes and BibTeX/RIS export, DOI
 * extraction from PDFs, and Arabic/Urdu diacritic-insensitive search
 * over texts the user imports. No account, no key, nothing is sent
 * anywhere except the query to the chosen API.
 */
class ResearchActivity : AppCompatActivity() {

    private val components get() = (application as PrivateXApp).components
    private val ui by lazy { app.privatex.browser.ui.SettUi(this, components.settings) }
    private val BG get() = ui.palette.bg
    private val TEXT get() = ui.palette.text
    private val DIM get() = ui.palette.dim
    private val TEAL get() = ui.palette.accent
    private var lastResults: List<ScholarTools.Work> = emptyList()

    private var pendingPick: ((Uri) -> Unit)? = null
    private var pendingSave: (() -> ByteArray)? = null

    private val pickOne = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val uri = result.data?.data
        val action = pendingPick
        pendingPick = null
        if (result.resultCode == Activity.RESULT_OK && uri != null && action != null) {
            action(uri)
        }
    }

    private val saveAs = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val uri = result.data?.data
        val provider = pendingSave
        pendingSave = null
        if (result.resultCode == Activity.RESULT_OK && uri != null && provider != null) {
            bg {
                contentResolver.openOutputStream(uri)?.use { it.write(provider()) }
                ui { toast("Saved") }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui.applyPlatformTheme()
        PdfTools.init(this)
        showHub()
    }

    // ------------------------------------------------------------ hub

    private fun showHub() {
        val layout = page("🎓  Research Desk")
        layout.addView(body(
            "Scholarly search over free, keyless APIs — OpenAlex, Crossref, DataCite, " +
                "DOAJ. Open-access badges come only from what those APIs themselves " +
                "report. Collections and notes live on this device only.",
        ))
        fun tool(label: String, screen: () -> Unit) {
            layout.addView(Button(this).apply {
                text = label
                textSize = 16f
                setOnClickListener { screen() }
            })
        }
        tool("🔍  Search scholarly works") { showSearch() }
        tool("🏷  DOI lookup") { showDoiLookup() }
        tool("🗂  My collections") { showCollections() }
        tool("📄  Extract DOIs from a PDF") { showPdfDois() }
        tool("🌙  Arabic / Urdu text search (local file)") { showLocalSearch() }
        setContentView(ScrollView(this).apply { addView(layout) })
    }

    // ------------------------------------------------------------ search

    private fun showSearch(prefillDoiResult: ScholarTools.Work? = null) {
        val layout = page("🔍  Scholarly search")
        back(layout) { showHub() }
        val sources = listOf("OpenAlex", "Crossref", "DataCite", "DOAJ")
        val sourceSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@ResearchActivity,
                android.R.layout.simple_spinner_dropdown_item,
                sources,
            )
        }
        val query = field("Title, topic, author…")
        val oaOnly = CheckBox(this).apply {
            text = "Open access only (OpenAlex filters server-side; others are filtered " +
                "to results their API explicitly marks open)"
            setTextColor(TEXT)
            textSize = 12f
        }
        val resultsLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        layout.addView(sourceSpinner)
        layout.addView(query)
        layout.addView(oaOnly)
        layout.addView(Button(this).apply {
            text = "Search"
            setOnClickListener {
                val text = query.text.toString().trim()
                if (text.isEmpty()) {
                    toast("Type a search first")
                } else {
                    runSearch(
                        sources[sourceSpinner.selectedItemPosition],
                        text,
                        oaOnly.isChecked,
                        resultsLayout,
                    )
                }
            }
        })
        layout.addView(resultsLayout)
        if (prefillDoiResult != null) {
            renderResults(resultsLayout, listOf(prefillDoiResult))
        }
        setContentView(ScrollView(this).apply { addView(layout) })
    }

    private fun runSearch(
        source: String,
        query: String,
        oaOnly: Boolean,
        resultsLayout: LinearLayout,
    ) {
        bg {
            val q = URLEncoder.encode(query, "UTF-8")
            val (url, parse) = when (source) {
                "Crossref" -> "https://api.crossref.org/works?query=$q&rows=15" to
                    { body: String -> ScholarTools.parseCrossref(body) }
                "DataCite" -> "https://api.datacite.org/dois?query=$q&page[size]=15" to
                    { body: String -> ScholarTools.parseDataCite(body) }
                "DOAJ" -> "https://doaj.org/api/search/articles/$q?pageSize=15" to
                    { body: String -> ScholarTools.parseDoaj(body) }
                else -> {
                    val filter = if (oaOnly) "&filter=open_access.is_oa:true" else ""
                    "https://api.openalex.org/works?search=$q&per-page=15$filter" to
                        { body: String -> ScholarTools.parseOpenAlex(body) }
                }
            }
            val response = DevTools.httpRequest(
                "GET", url, mapOf("Accept" to "application/json"), null,
            )
            if (response.statusCode != 200) {
                throw IllegalStateException("$source answered ${response.statusCode}")
            }
            var works = parse(response.body)
            if (oaOnly && source != "OpenAlex") {
                works = works.filter { it.openAccess == true }
            }
            lastResults = works
            ui { renderResults(resultsLayout, works) }
        }
    }

    private fun renderResults(container: LinearLayout, works: List<ScholarTools.Work>) {
        container.removeAllViews()
        if (works.isEmpty()) {
            container.addView(body("No results."))
            return
        }
        container.addView(body("${works.size} result(s):"))
        works.forEach { work ->
            container.addView(workCard(work, showSave = true))
        }
    }

    private fun workCard(work: ScholarTools.Work, showSave: Boolean): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(10), dp(10), dp(10))
        }
        card.addView(TextView(this).apply {
            text = work.summaryLine()
            textSize = 15f
            setTextColor(TEXT)
        })
        card.addView(TextView(this).apply {
            text = buildString {
                append(work.origin)
                work.source?.let { append(" · ").append(it) }
                work.doi?.let { append(" · DOI: ").append(it) }
                work.citedByCount?.let { append(" · cited by ").append(it) }
                when (work.openAccess) {
                    true -> append(" · 🟢 Open access")
                    false -> append(" · 🔒 Not open access (per API)")
                    null -> Unit
                }
            }
            textSize = 12f
            setTextColor(DIM)
        })
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val openUrl = work.oaUrl ?: work.url
            ?: work.doi?.let { "https://doi.org/$it" }
        if (openUrl != null) {
            actions.addView(Button(this).apply {
                text = "Open"
                setOnClickListener { openInBrowser(openUrl) }
            })
        }
        actions.addView(Button(this).apply {
            text = "Cite"
            setOnClickListener {
                AlertDialog.Builder(this@ResearchActivity)
                    .setTitle("Citation")
                    .setMessage(
                        "BibTeX:\n${ScholarTools.toBibTex(work)}\n\nRIS:\n${ScholarTools.toRis(work)}",
                    )
                    .setPositiveButton("Copy BibTeX") { _, _ ->
                        copy("bibtex", ScholarTools.toBibTex(work))
                    }
                    .setNegativeButton("Close", null)
                    .show()
            }
        })
        if (showSave) {
            actions.addView(Button(this).apply {
                text = "Save"
                setOnClickListener { promptSaveToCollection(work) }
            })
        }
        card.addView(actions)
        return card
    }

    private fun promptSaveToCollection(work: ScholarTools.Work) {
        val store = components.researchStore
        val collections = store.listCollections()
        val names = collections.map { it.name } + "＋ New collection…"
        AlertDialog.Builder(this)
            .setTitle("Save to collection")
            .setItems(names.toTypedArray()) { _, which ->
                if (which == collections.size) {
                    promptNewCollection { collection ->
                        store.addWork(collection.id, work)
                        toast("Saved to ${collection.name}")
                    }
                } else {
                    store.addWork(collections[which].id, work)
                    toast("Saved to ${collections[which].name}")
                }
            }
            .show()
    }

    private fun promptNewCollection(onCreated: (ResearchStore.Collection) -> Unit) {
        val input = EditText(this).apply { hint = "Collection name" }
        AlertDialog.Builder(this)
            .setTitle("New collection")
            .setView(input)
            .setPositiveButton("Create") { _, _ ->
                onCreated(components.researchStore.createCollection(input.text.toString()))
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ------------------------------------------------------------ DOI lookup

    private fun showDoiLookup(prefill: String = "") {
        val layout = page("🏷  DOI lookup")
        back(layout) { showHub() }
        layout.addView(body("Paste a DOI (10.xxxx/…) or a doi.org link. Sett asks OpenAlex first, then Crossref."))
        val input = field("10.…").apply { setText(prefill) }
        val outLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        layout.addView(input)
        layout.addView(Button(this).apply {
            text = "Look up"
            setOnClickListener {
                val doi = ScholarTools.normalizeDoi(input.text.toString())
                if (doi == null) {
                    toast("That does not look like a DOI")
                } else {
                    bg {
                        val work = lookupDoi(doi)
                        ui {
                            outLayout.removeAllViews()
                            if (work == null) {
                                outLayout.addView(body("No record found for $doi in OpenAlex or Crossref."))
                            } else {
                                outLayout.addView(workCard(work, showSave = true))
                            }
                        }
                    }
                }
            }
        })
        layout.addView(outLayout)
        setContentView(ScrollView(this).apply { addView(layout) })
    }

    /** OpenAlex by-DOI, falling back to Crossref by-DOI. Null when neither knows it. */
    private fun lookupDoi(doi: String): ScholarTools.Work? {
        val openAlex = runCatching {
            val response = DevTools.httpRequest(
                "GET",
                "https://api.openalex.org/works/https://doi.org/$doi",
                mapOf("Accept" to "application/json"),
                null,
            )
            if (response.statusCode == 200 && !response.body.contains("\"results\"")) {
                ScholarTools.parseOpenAlex("""{"results":[${response.body}]}""").firstOrNull()
            } else {
                null
            }
        }.getOrNull()
        if (openAlex != null) return openAlex
        return runCatching {
            val response = DevTools.httpRequest(
                "GET",
                "https://api.crossref.org/works/$doi",
                mapOf("Accept" to "application/json"),
                null,
            )
            if (response.statusCode == 200) {
                // Crossref single-work: the work IS the message object.
                val message = org.json.JSONObject(response.body).optJSONObject("message")
                if (message != null) {
                    ScholarTools.parseCrossref("""{"message":{"items":[$message]}}""").firstOrNull()
                } else {
                    null
                }
            } else {
                null
            }
        }.getOrNull()
    }

    // ------------------------------------------------------------ collections

    private fun showCollections() {
        val layout = page("🗂  Collections")
        back(layout) { showHub() }
        val store = components.researchStore
        layout.addView(Button(this).apply {
            text = "＋ New collection"
            setOnClickListener { promptNewCollection { showCollections() } }
        })
        val collections = store.listCollections()
        if (collections.isEmpty()) {
            layout.addView(body("No collections yet. Save works from search results, or create one here."))
        }
        collections.forEach { collection ->
            layout.addView(Button(this).apply {
                text = "${collection.name} — ${collection.works.size} work(s)"
                setOnClickListener { showCollectionDetail(collection.id) }
            })
        }
        setContentView(ScrollView(this).apply { addView(layout) })
    }

    private fun showCollectionDetail(collectionId: String) {
        val store = components.researchStore
        val collection = store.listCollections().firstOrNull { it.id == collectionId }
        if (collection == null) {
            showCollections()
            return
        }
        val layout = page("🗂  ${collection.name}")
        back(layout) { showCollections() }
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(Button(this).apply {
            text = "Export .bib"
            setOnClickListener {
                launchSave("${collection.name}.bib", "text/plain") {
                    store.exportBibTex(collectionId).toByteArray()
                }
            }
        })
        actions.addView(Button(this).apply {
            text = "Export .ris"
            setOnClickListener {
                launchSave("${collection.name}.ris", "text/plain") {
                    store.exportRis(collectionId).toByteArray()
                }
            }
        })
        actions.addView(Button(this).apply {
            text = "Delete"
            setOnClickListener {
                AlertDialog.Builder(this@ResearchActivity)
                    .setMessage("Delete the collection \"${collection.name}\" and its notes?")
                    .setPositiveButton("Delete") { _, _ ->
                        store.deleteCollection(collectionId)
                        showCollections()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        })
        layout.addView(actions)
        if (collection.works.isEmpty()) {
            layout.addView(body("Empty. Save works from search results."))
        }
        collection.works.forEachIndexed { index, saved ->
            layout.addView(workCard(saved.work, showSave = false))
            if (saved.note.isNotBlank()) {
                layout.addView(body("📝 ${saved.note}"))
            }
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(Button(this).apply {
                text = "Note"
                setOnClickListener {
                    val input = EditText(this@ResearchActivity).apply {
                        hint = "Your note on this work"
                        setText(saved.note)
                    }
                    AlertDialog.Builder(this@ResearchActivity)
                        .setTitle("Note")
                        .setView(input)
                        .setPositiveButton("Save") { _, _ ->
                            store.setNote(collectionId, index, input.text.toString())
                            showCollectionDetail(collectionId)
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
            })
            row.addView(Button(this).apply {
                text = "Remove"
                setOnClickListener {
                    store.removeWork(collectionId, index)
                    showCollectionDetail(collectionId)
                }
            })
            layout.addView(row)
        }
        setContentView(ScrollView(this).apply { addView(layout) })
    }

    // ------------------------------------------------------------ PDF DOIs

    private fun showPdfDois() {
        val layout = page("📄  DOIs in a PDF")
        back(layout) { showHub() }
        layout.addView(body(
            "Pick a PDF (a paper, a references list). Sett extracts its text layer " +
                "on-device and lists every DOI it finds. Scanned-image PDFs have no " +
                "text layer — nothing will be found in those, and Sett will say so.",
        ))
        val outLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        layout.addView(Button(this).apply {
            text = "Choose a PDF"
            setOnClickListener {
                launchPick("application/pdf") { uri ->
                    bg {
                        val file = File(cacheDir, "sett-scholar-${System.currentTimeMillis()}.pdf")
                        contentResolver.openInputStream(uri)?.use { input ->
                            file.outputStream().use { input.copyTo(it) }
                        } ?: throw IllegalStateException("Could not read the file")
                        val text = PdfTools.extractText(file)
                        val dois = ScholarTools.extractDois(text)
                        ui {
                            outLayout.removeAllViews()
                            if (text.isBlank()) {
                                outLayout.addView(body("No text layer in this PDF — likely a scan. No DOIs can be extracted."))
                            } else if (dois.isEmpty()) {
                                outLayout.addView(body("Text extracted, but no DOIs found in it."))
                            } else {
                                outLayout.addView(body("${dois.size} DOI(s) found — tap one to look it up:"))
                                dois.forEach { doi ->
                                    outLayout.addView(Button(this@ResearchActivity).apply {
                                        // setText(), not the property: the enclosing
                                        // scope has a `val text` (extracted PDF text).
                                        setText(doi)
                                        setOnClickListener { showDoiLookup(doi) }
                                    })
                                }
                            }
                        }
                    }
                }
            }
        })
        layout.addView(outLayout)
        setContentView(ScrollView(this).apply { addView(layout) })
    }

    // ------------------------------------------------------------ local search

    private fun showLocalSearch() {
        val layout = page("🌙  Arabic / Urdu local search")
        back(layout) { showHub() }
        layout.addView(body(
            "Import a text file and search it the way Arabic-script readers actually " +
                "need: harakat are ignored, and أ/إ/آ count as ا, ة as ه, ى as ي, and " +
                "Urdu ی/ک as ي/ك. Matching is line-by-line and nothing leaves the device.",
        ))
        var fileText: String? = null
        val status = body("No file loaded.")
        val needle = field("Search word or phrase (Arabic/Urdu/any script)")
        val outLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        layout.addView(Button(this).apply {
            text = "Choose a text file"
            setOnClickListener {
                launchPick("text/*") { uri ->
                    bg {
                        val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                            ?: throw IllegalStateException("Could not read the file")
                        val text = String(bytes.take(2 * 1024 * 1024).toByteArray(), Charsets.UTF_8)
                        fileText = text
                        ui { status.text = "Loaded ${text.length} characters." }
                    }
                }
            }
        })
        layout.addView(status)
        layout.addView(needle)
        layout.addView(Button(this).apply {
            text = "Search the file"
            setOnClickListener {
                val text = fileText
                val target = needle.text.toString()
                if (text == null) {
                    toast("Choose a file first")
                } else if (target.isBlank()) {
                    toast("Type a search term")
                } else {
                    bg {
                        val matchingLines = text.lines()
                            .filter { ScholarTools.arabicContains(it, target) }
                            .take(100)
                        ui {
                            outLayout.removeAllViews()
                            outLayout.addView(body(
                                if (matchingLines.isEmpty()) {
                                    "No matching lines."
                                } else {
                                    "${matchingLines.size} matching line(s)${if (matchingLines.size == 100) " (first 100 shown)" else ""}:"
                                },
                            ))
                            matchingLines.forEach { line ->
                                outLayout.addView(body("• ${line.trim().take(300)}"))
                            }
                        }
                    }
                }
            }
        })
        layout.addView(outLayout)
        setContentView(ScrollView(this).apply { addView(layout) })
    }

    // ------------------------------------------------------------ plumbing

    private fun launchPick(mime: String, action: (Uri) -> Unit) {
        pendingPick = action
        pickOne.launch(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = mime
        })
    }

    private fun launchSave(suggestedName: String, mime: String, provider: () -> ByteArray) {
        pendingSave = provider
        saveAs.launch(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = mime
            putExtra(Intent.EXTRA_TITLE, suggestedName)
        })
    }

    private fun openInBrowser(url: String) {
        startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url))
                .setClass(this, BrowserActivity::class.java),
        )
        finish()
    }

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
        addView(TextView(this@ResearchActivity).apply {
            text = titleText
            textSize = 20f
            setTextColor(TEXT)
            setPadding(0, 0, 0, dp(10))
        })
    }

    private fun back(layout: LinearLayout, action: () -> Unit) {
        layout.addView(TextView(this).apply {
            text = "← Research Desk"
            textSize = 14f
            setTextColor(TEAL)
            setPadding(0, 0, 0, dp(8))
            setOnClickListener { action() }
        })
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
    }
}
