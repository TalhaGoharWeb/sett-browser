package app.privatex.browser

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.CountDownTimer
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import app.privatex.browser.study.QuizGen
import app.privatex.browser.study.Sm2
import app.privatex.browser.tools.ScholarTools
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mozilla.components.browser.state.selector.selectedTab

/**
 * Study Center (Phase 9): flashcards with SM-2 spaced repetition, a
 * vocabulary notebook (with the Research Desk's Arabic-aware search),
 * notes tied to a page URL or PDF name the user writes, a reading
 * tracker with a focus timer, and a rule-based quiz lab that builds
 * questions from the user's own text — no AI involved, and it says
 * so. Everything is stored on-device.
 */
class StudyActivity : AppCompatActivity() {

    private val components get() = (application as PrivateXApp).components
    private val ui by lazy { app.privatex.browser.ui.SettUi(this, components.settings) }
    private val BG get() = ui.palette.bg
    private val TEXT get() = ui.palette.text
    private val DIM get() = ui.palette.dim
    private val TEAL get() = ui.palette.accent
    private var timer: CountDownTimer? = null
    private var timerStartedAt: Long = 0
    private var timerTotalMillis: Long = 0

    private var pendingPick: ((Uri) -> Unit)? = null
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

    private fun todayEpochDay(): Long =
        System.currentTimeMillis() / 86_400_000L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui.applyPlatformTheme()
        showHub()
    }

    override fun onDestroy() {
        timer?.cancel()
        super.onDestroy()
    }

    // ------------------------------------------------------------ hub

    private fun showHub() {
        val layout = page("📚  Study Center")
        layout.addView(body("Flashcards that schedule themselves, vocabulary, notes, a reading log and a quiz built from your own texts. All local."))
        fun tool(label: String, screen: () -> Unit) {
            layout.addView(Button(this).apply {
                text = label
                textSize = 16f
                setOnClickListener { screen() }
            })
        }
        tool("🃏  Flashcard decks") { showDecks() }
        tool("📖  Vocabulary notebook") { showVocab() }
        tool("📝  Page & PDF notes") { showNotes() }
        tool("⏱  Reading tracker & focus timer") { showReading() }
        tool("❓  Quiz lab — from your own text") { showQuizLab() }
        setContentView(ScrollView(this).apply { addView(layout) })
    }

    // ------------------------------------------------------------ decks

    private fun showDecks() {
        val store = components.studyStore
        val layout = page("🃏  Decks")
        back(layout) { showHub() }
        layout.addView(Button(this).apply {
            text = "＋ New deck"
            setOnClickListener {
                val input = EditText(this@StudyActivity).apply { hint = "Deck name" }
                AlertDialog.Builder(this@StudyActivity)
                    .setTitle("New deck")
                    .setView(input)
                    .setPositiveButton("Create") { _, _ ->
                        store.createDeck(input.text.toString())
                        showDecks()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        })
        val decks = store.listDecks()
        if (decks.isEmpty()) {
            layout.addView(body("No decks yet."))
        }
        val today = todayEpochDay()
        decks.forEach { deck ->
            val due = store.dueCards(deck, today).size
            layout.addView(Button(this).apply {
                text = "${deck.name} — ${deck.cards.size} card(s), $due due"
                setOnClickListener { showDeckDetail(deck.id) }
            })
        }
        setContentView(ScrollView(this).apply { addView(layout) })
    }

    private fun showDeckDetail(deckId: String) {
        val store = components.studyStore
        val deck = store.listDecks().firstOrNull { it.id == deckId } ?: run {
            showDecks()
            return
        }
        val layout = page("🃏  ${deck.name}")
        back(layout) { showDecks() }
        val today = todayEpochDay()
        val due = store.dueCards(deck, today)
        layout.addView(body("${deck.cards.size} card(s) · ${due.size} due today"))
        layout.addView(Button(this).apply {
            text = "▶ Review due cards"
            isEnabled = due.isNotEmpty()
            setOnClickListener { showReview(deckId) }
        })
        layout.addView(section("Add a card"))
        val front = field("Front (question / word)")
        val backField = field("Back (answer / meaning)")
        layout.addView(front)
        layout.addView(backField)
        layout.addView(Button(this).apply {
            text = "Add card"
            setOnClickListener {
                if (front.text.isBlank() || backField.text.isBlank()) {
                    toast("Both sides need text")
                } else {
                    store.addCard(deckId, front.text.toString(), backField.text.toString(), today)
                    showDeckDetail(deckId)
                }
            }
        })
        layout.addView(section("Cards"))
        deck.cards.forEach { card ->
            layout.addView(body("• ${card.front} → ${card.back} (due day-offset ${(card.state.dueEpochDay - today)}d, interval ${card.state.intervalDays}d)"))
        }
        layout.addView(Button(this).apply {
            text = "Delete deck"
            setOnClickListener {
                AlertDialog.Builder(this@StudyActivity)
                    .setMessage("Delete \"${deck.name}\" and all its cards?")
                    .setPositiveButton("Delete") { _, _ ->
                        store.deleteDeck(deckId)
                        showDecks()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        })
        setContentView(ScrollView(this).apply { addView(layout) })
    }

    private fun showReview(deckId: String) {
        val store = components.studyStore
        val today = todayEpochDay()
        val deck = store.listDecks().firstOrNull { it.id == deckId } ?: run {
            showDecks()
            return
        }
        val due = store.dueCards(deck, today)
        val layout = page("🃏  Review — ${deck.name}")
        back(layout) { showDeckDetail(deckId) }
        if (due.isEmpty()) {
            layout.addView(body("Nothing due. Come back when the schedule says so — that is the point of spaced repetition."))
            setContentView(ScrollView(this).apply { addView(layout) })
            return
        }
        val (cardIndex, card) = due.first()
        layout.addView(body("${due.size} due. Card:"))
        layout.addView(TextView(this).apply {
            text = card.front
            textSize = 20f
            setTextColor(TEXT)
            setPadding(0, dp(14), 0, dp(14))
        })
        val answerView = TextView(this).apply {
            textSize = 17f
            setTextColor(TEAL)
        }
        layout.addView(answerView)
        layout.addView(Button(this).apply {
            text = "Show answer"
            setOnClickListener {
                answerView.text = card.back
                Sm2.Grade.entries.forEach { grade ->
                    layout.addView(Button(this@StudyActivity).apply {
                        text = grade.label
                        setOnClickListener {
                            store.reviewCard(deckId, cardIndex, grade.quality, today)
                            showReview(deckId)
                        }
                    })
                }
                visibility = android.view.View.GONE
            }
        })
        setContentView(ScrollView(this).apply { addView(layout) })
    }

    // ------------------------------------------------------------ vocab

    private fun showVocab() {
        val store = components.studyStore
        val layout = page("📖  Vocabulary")
        back(layout) { showHub() }
        val word = field("Word")
        val meaning = field("Meaning")
        val note = field("Note (optional)")
        layout.addView(word)
        layout.addView(meaning)
        layout.addView(note)
        layout.addView(Button(this).apply {
            text = "Add"
            setOnClickListener {
                if (word.text.isBlank()) {
                    toast("Type a word")
                } else {
                    store.addVocab(word.text.toString(), meaning.text.toString(), note.text.toString())
                    showVocab()
                }
            }
        })
        val search = field("Search vocabulary (Arabic-aware: diacritics ignored)")
        val listLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fun render(filter: String) {
            listLayout.removeAllViews()
            val entries = store.listVocab()
            val shown = if (filter.isBlank()) {
                entries
            } else {
                entries.filter {
                    ScholarTools.arabicContains(it.word, filter) ||
                        it.word.contains(filter, ignoreCase = true) ||
                        it.meaning.contains(filter, ignoreCase = true)
                }
            }
            if (shown.isEmpty()) listLayout.addView(body("No entries."))
            shown.forEach { entry ->
                listLayout.addView(body("• ${entry.word} — ${entry.meaning}${if (entry.note.isNotBlank()) " (${entry.note})" else ""}"))
            }
        }
        layout.addView(search)
        layout.addView(Button(this).apply {
            text = "Search"
            setOnClickListener { render(search.text.toString()) }
        })
        layout.addView(listLayout)
        render("")
        setContentView(ScrollView(this).apply { addView(layout) })
    }

    // ------------------------------------------------------------ notes

    private fun showNotes() {
        val store = components.studyStore
        val layout = page("📝  Page & PDF notes")
        back(layout) { showHub() }
        layout.addView(body(
            "One note per page or document. The subject is the page URL or the PDF's " +
                "name — you type it, so the association is exactly the one you mean. " +
                "Saving again under the same subject replaces the note.",
        ))
        val currentUrl = components.store.state.selectedTab?.content?.url
        val subject = field("Page URL or PDF name").apply {
            if (!currentUrl.isNullOrBlank()) setText(currentUrl)
        }
        val bodyField = field("Note").apply { minLines = 3 }
        layout.addView(subject)
        layout.addView(bodyField)
        layout.addView(Button(this).apply {
            text = "Save note"
            setOnClickListener {
                if (subject.text.isBlank()) {
                    toast("Type a subject first")
                } else {
                    store.upsertNote(subject.text.toString().trim(), bodyField.text.toString())
                    showNotes()
                }
            }
        })
        layout.addView(section("Saved notes"))
        val notes = store.listNotes()
        if (notes.isEmpty()) layout.addView(body("No notes yet."))
        notes.forEach { note ->
            layout.addView(body("• ${note.subject}\n${note.body}"))
            layout.addView(Button(this).apply {
                text = "Delete this note"
                setOnClickListener {
                    store.deleteNote(note.subject)
                    showNotes()
                }
            })
        }
        setContentView(ScrollView(this).apply { addView(layout) })
    }

    // ------------------------------------------------------------ reading

    private fun showReading() {
        val store = components.studyStore
        val layout = page("⏱  Reading")
        back(layout) { showHub() }
        val today = todayEpochDay()
        layout.addView(body("This week: ${store.readingMinutesThisWeek(today)} minute(s) logged."))
        layout.addView(section("Focus timer"))
        val title = field("What are you reading?").apply { setText(lastReadingTitle) }
        val minutesField = field("Minutes").apply { setText("25") }
        val display = TextView(this).apply {
            textSize = 28f
            setTextColor(TEXT)
        }
        layout.addView(title)
        layout.addView(minutesField)
        layout.addView(display)
        layout.addView(Button(this).apply {
            text = "Start timer"
            setOnClickListener {
                val minutes = minutesField.text.toString().toIntOrNull()?.coerceIn(1, 480) ?: 25
                timer?.cancel()
                timerTotalMillis = minutes * 60_000L
                timerStartedAt = System.currentTimeMillis()
                lastReadingTitle = title.text.toString()
                timer = object : CountDownTimer(timerTotalMillis, 1000) {
                    override fun onTick(millisUntilFinished: Long) {
                        val s = millisUntilFinished / 1000
                        display.text = "⏱ %02d:%02d left".format(s / 60, s % 60)
                    }

                    override fun onFinish() {
                        display.text = "Done — session logged."
                        store.logReading(
                            lastReadingTitle.ifBlank { "Reading" },
                            (timerTotalMillis / 60_000L).toInt(),
                            todayEpochDay(),
                        )
                    }
                }.start()
                toast("Timer started")
            }
        })
        layout.addView(Button(this).apply {
            text = "Stop & log elapsed"
            setOnClickListener {
                timer?.cancel()
                val elapsedMinutes = ((System.currentTimeMillis() - timerStartedAt) / 60_000L).toInt()
                if (timerStartedAt > 0 && elapsedMinutes > 0) {
                    store.logReading(
                        lastReadingTitle.ifBlank { "Reading" },
                        elapsedMinutes,
                        todayEpochDay(),
                    )
                    display.text = "Logged $elapsedMinutes minute(s)."
                } else {
                    display.text = "Nothing to log yet."
                }
                timerStartedAt = 0
            }
        })
        layout.addView(section("Log manually"))
        val manualMinutes = field("Minutes read").apply { setText("20") }
        layout.addView(manualMinutes)
        layout.addView(Button(this).apply {
            text = "Log it"
            setOnClickListener {
                val m = manualMinutes.text.toString().toIntOrNull() ?: 0
                if (m <= 0) {
                    toast("Enter minutes")
                } else {
                    store.logReading(title.text.toString().ifBlank { "Reading" }, m, todayEpochDay())
                    showReading()
                }
            }
        })
        layout.addView(section("Recent sessions"))
        store.listReading().takeLast(10).reversed().forEach { entry ->
            layout.addView(body("• ${entry.title} — ${entry.minutes} min"))
        }
        setContentView(ScrollView(this).apply { addView(layout) })
    }

    // ------------------------------------------------------------ quiz lab

    private fun showQuizLab() {
        val layout = page("❓  Quiz lab")
        back(layout) { showHub() }
        layout.addView(body(
            "Paste or import study text. Sett builds questions with plain rules — " +
                "definition sentences become questions, key terms become blanks — " +
                "no AI is involved, so check the questions before trusting them.",
        ))
        val input = field("Study text").apply { minLines = 5 }
        layout.addView(input)
        layout.addView(Button(this).apply {
            text = "Import a text file instead"
            setOnClickListener {
                launchPick("text/*") { uri ->
                    bg {
                        val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                            ?: throw IllegalStateException("Could not read the file")
                        val text = String(bytes, Charsets.UTF_8)
                        ui { input.setText(text.take(20000)) }
                    }
                }
            }
        })
        layout.addView(Button(this).apply {
            text = "Generate quiz"
            setOnClickListener {
                val items = QuizGen.generate(input.text.toString())
                if (items.isEmpty()) {
                    toast("No questions could be built — try text with definition sentences (\"X is …\")")
                } else {
                    showQuizRun(items)
                }
            }
        })
        setContentView(ScrollView(this).apply { addView(layout) })
    }

    private fun showQuizRun(items: List<QuizGen.QuizItem>) {
        val layout = page("❓  Quiz (${items.size} questions)")
        back(layout) { showQuizLab() }
        var score = 0
        var answered = 0
        val scoreView = body("Score: 0 / ${items.size}")
        layout.addView(scoreView)
        items.forEachIndexed { index, item ->
            layout.addView(section("Q${index + 1}. ${item.question}"))
            if (item.options == null) {
                val reveal = body("Tap to reveal the answer, then mark yourself honestly.")
                layout.addView(reveal)
                layout.addView(Button(this).apply {
                    text = "Reveal answer"
                    setOnClickListener {
                        reveal.text = "Answer: ${item.answer}"
                        visibility = android.view.View.GONE
                        layout.addView(Button(this@StudyActivity).apply {
                            text = "I knew it (+1)"
                            setOnClickListener {
                                score++
                                answered++
                                scoreView.text = "Score: $score / ${items.size} (answered $answered)"
                                visibility = android.view.View.GONE
                            }
                        })
                    }
                })
            } else {
                item.options.forEach { option ->
                    layout.addView(Button(this).apply {
                        text = option
                        setOnClickListener {
                            answered++
                            if (option == item.answer) {
                                score++
                                setTextColor(TEAL)
                            } else {
                                setTextColor(ui.palette.error)
                            }
                            scoreView.text = "Score: $score / ${items.size} (answered $answered) — correct answer: ${item.answer}"
                        }
                    })
                }
            }
        }
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
        addView(TextView(this@StudyActivity).apply {
            text = titleText
            textSize = 20f
            setTextColor(TEXT)
            setPadding(0, 0, 0, dp(10))
        })
    }

    private fun back(layout: LinearLayout, action: () -> Unit) {
        layout.addView(TextView(this).apply {
            text = "← Study Center"
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

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        var lastReadingTitle: String = ""
    }
}
