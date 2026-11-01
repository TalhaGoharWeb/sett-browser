package app.privatex.browser

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
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import app.privatex.browser.tools.LocalIndex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mozilla.appservices.places.BookmarkRoot
import mozilla.components.concept.storage.BookmarkNode
import mozilla.components.concept.storage.BookmarkNodeType

/**
 * Library Search (Phase 11) — one search box over everything Sett
 * keeps on this device: bookmarks, history, research collections,
 * flashcards, vocabulary and notes. The index is rebuilt in memory
 * each time this screen opens; nothing is uploaded anywhere (there
 * is nowhere to upload it to). The public crawler/index from the
 * original plan stays blocked under zero spend — this personal
 * index is its buildable residue, and the screen says so.
 */
class IndexActivity : AppCompatActivity() {

    private val components get() = (application as PrivateXApp).components
    private var index: LocalIndex.Index? = null
    private lateinit var results: LinearLayout
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
            setPadding(dp(18), dp(18), dp(18), dp(16))
            addView(TextView(this@IndexActivity).apply {
                text = "🔍  Library Search"
                textSize = 20f
                setTextColor(TEXT)
                setPadding(0, 0, 0, dp(8))
            })
            addView(TextView(this@IndexActivity).apply {
                text = "Searches your bookmarks, history, research collections, flashcards, " +
                    "vocabulary and notes — all on this device. Arabic/Urdu terms match " +
                    "without diacritics. (A public Sett search index would need servers; " +
                    "under the zero-spend rule that part stays unbuilt — this personal " +
                    "index is what can honestly exist.)"
                textSize = 13f
                setTextColor(DIM)
            })
        }
        val query = EditText(this).apply {
            hint = "Words to find (all must match)"
            textSize = 15f
            setTextColor(TEXT)
            setHintTextColor(DIM)
            setPadding(0, dp(10), 0, dp(10))
        }
        root.addView(query)
        status = TextView(this).apply {
            text = "Building the index…"
            textSize = 13f
            setTextColor(DIM)
        }
        root.addView(Button(this).apply {
            text = "Search my library"
            setOnClickListener {
                val built = index
                if (built == null) {
                    toast("Index still building — one moment")
                } else {
                    render(built.search(query.text.toString()))
                }
            }
        })
        root.addView(status)
        results = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(results)
        setContentView(ScrollView(this).apply { addView(root) })
        buildIndex()
    }

    private fun buildIndex() {
        lifecycleScope.launch {
            val docs = withContext(Dispatchers.Default) { collectDocs() }
            index = LocalIndex.Index(docs)
            status.text = "Index ready: ${docs.size} items on this device."
        }
    }

    private suspend fun collectDocs(): List<LocalIndex.Doc> {
        val docs = mutableListOf<LocalIndex.Doc>()
        runCatching {
            val tree = components.bookmarksStorage
                .getTree(BookmarkRoot.Mobile.id, recursive = true).getOrNull()
            if (tree != null) collectBookmarks(tree, docs)
        }
        runCatching {
            components.historyStorage.getDetailedVisits(0, Long.MAX_VALUE)
                .sortedByDescending { it.visitTime }
                .take(2000)
                .forEach { visit ->
                    docs += LocalIndex.Doc(
                        id = "history:${visit.url}:${visit.visitTime}",
                        source = "History",
                        title = visit.title?.ifBlank { visit.url } ?: visit.url,
                        body = visit.url,
                        url = visit.url,
                    )
                }
        }
        runCatching {
            components.researchStore.listCollections().forEach { collection ->
                collection.works.forEach { saved ->
                    docs += LocalIndex.Doc(
                        id = "research:${collection.id}:${saved.work.doi ?: saved.work.title}",
                        source = "Research · ${collection.name}",
                        title = saved.work.title,
                        body = "${saved.work.authors} ${saved.work.year ?: ""} " +
                            "${saved.work.doi ?: ""} ${saved.note}",
                        url = saved.work.url,
                    )
                }
            }
        }
        runCatching {
            val study = components.studyStore
            study.listDecks().forEach { deck ->
                deck.cards.forEachIndexed { i, card ->
                    docs += LocalIndex.Doc(
                        id = "card:${deck.id}:$i",
                        source = "Flashcards · ${deck.name}",
                        title = card.front,
                        body = card.back,
                        url = "",
                    )
                }
            }
            study.listVocab().forEach { entry ->
                docs += LocalIndex.Doc(
                    id = "vocab:${entry.word}",
                    source = "Vocabulary",
                    title = entry.word,
                    body = "${entry.meaning} ${entry.note}",
                    url = "",
                )
            }
            study.listNotes().forEach { note ->
                docs += LocalIndex.Doc(
                    id = "note:${note.subject}",
                    source = "Note",
                    title = note.subject,
                    body = note.body,
                    url = note.subject.takeIf { it.startsWith("http") } ?: "",
                )
            }
        }
        return docs
    }

    private fun collectBookmarks(node: BookmarkNode, docs: MutableList<LocalIndex.Doc>) {
        if (node.type == BookmarkNodeType.ITEM) {
            val url = node.url.orEmpty()
            if (url.isNotEmpty()) {
                docs += LocalIndex.Doc(
                    id = "bookmark:${node.guid}",
                    source = "Bookmark",
                    title = node.title?.ifBlank { url } ?: url,
                    body = url,
                    url = url,
                )
            }
        }
        node.children?.forEach { collectBookmarks(it, docs) }
    }

    private fun render(hits: List<LocalIndex.Hit>) {
        results.removeAllViews()
        if (hits.isEmpty()) {
            results.addView(TextView(this).apply {
                text = "No matches in your library."
                textSize = 14f
                setTextColor(DIM)
                setPadding(0, dp(12), 0, 0)
            })
            return
        }
        hits.forEach { hit ->
            val doc = hit.doc
            results.addView(Button(this).apply {
                text = "[${doc.source}] ${doc.title}"
                textSize = 13f
                setOnClickListener {
                    when {
                        doc.url.startsWith("http") -> {
                            startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse(doc.url))
                                    .setClass(this@IndexActivity, BrowserActivity::class.java),
                            )
                            finish()
                        }
                        doc.source.startsWith("Research") ->
                            startActivity(Intent(this@IndexActivity, ResearchActivity::class.java))
                        doc.source.startsWith("Flashcards") || doc.source == "Vocabulary" ||
                            doc.source == "Note" ->
                            startActivity(Intent(this@IndexActivity, StudyActivity::class.java))
                    }
                }
            })
            results.addView(TextView(this@IndexActivity).apply {
                text = doc.body.take(140)
                textSize = 12f
                setTextColor(DIM)
                setPadding(dp(4), 0, 0, dp(8))
            })
        }
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        val BG = Color.parseColor("#0B1626")
        val TEXT = Color.parseColor("#E6F1F5")
        val DIM = Color.parseColor("#8FB3C7")
        val TEAL = Color.parseColor("#2DD4BF")
    }
}
