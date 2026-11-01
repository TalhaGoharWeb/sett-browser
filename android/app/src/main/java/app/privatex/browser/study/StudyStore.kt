package app.privatex.browser.study

import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * Study Center store (Phase 9): flashcard decks (SM-2 state per
 * card), a vocabulary notebook, page/PDF notes (associated by a
 * title the user types — a URL or a file name), and a reading log.
 * One JSON file in app-private storage, JVM-testable like the other
 * Sett stores. Everything is local; nothing syncs anywhere.
 */
class StudyStore(private val file: File) {

    data class Card(
        val front: String,
        val back: String,
        val state: Sm2.CardState,
    )

    data class Deck(val id: String, val name: String, val cards: List<Card>)

    data class VocabEntry(val word: String, val meaning: String, val note: String)

    data class PageNote(val subject: String, val body: String, val updatedAtMillis: Long)

    data class ReadingEntry(val title: String, val minutes: Int, val epochDay: Long)

    private data class Data(
        val decks: List<Deck>,
        val vocab: List<VocabEntry>,
        val notes: List<PageNote>,
        val reading: List<ReadingEntry>,
    )

    // ------------------------------------------------------------ decks

    @Synchronized
    fun listDecks(): List<Deck> = load().decks

    @Synchronized
    fun createDeck(name: String): Deck {
        val data = load()
        val deck = Deck(newId(), name.trim().ifEmpty { "Untitled deck" }, emptyList())
        save(data.copy(decks = data.decks + deck))
        return deck
    }

    @Synchronized
    fun deleteDeck(id: String) {
        val data = load()
        save(data.copy(decks = data.decks.filterNot { it.id == id }))
    }

    @Synchronized
    fun addCard(deckId: String, front: String, back: String, todayEpochDay: Long) {
        val data = load()
        val decks = data.decks.map { deck ->
            if (deck.id == deckId) {
                deck.copy(cards = deck.cards + Card(front, back, Sm2.newCard(todayEpochDay)))
            } else {
                deck
            }
        }
        save(data.copy(decks = decks))
    }

    @Synchronized
    fun reviewCard(deckId: String, cardIndex: Int, quality: Int, todayEpochDay: Long) {
        val data = load()
        val decks = data.decks.map { deck ->
            if (deck.id == deckId && cardIndex in deck.cards.indices) {
                deck.copy(
                    cards = deck.cards.mapIndexed { i, card ->
                        if (i == cardIndex) {
                            card.copy(state = Sm2.review(card.state, quality, todayEpochDay))
                        } else {
                            card
                        }
                    },
                )
            } else {
                deck
            }
        }
        save(data.copy(decks = decks))
    }

    /** Cards due on or before [todayEpochDay], with their indexes in the deck. */
    fun dueCards(deck: Deck, todayEpochDay: Long): List<Pair<Int, Card>> =
        deck.cards.withIndex()
            .filter { it.value.state.dueEpochDay <= todayEpochDay }
            .map { it.index to it.value }

    // ------------------------------------------------------------ vocab

    @Synchronized
    fun listVocab(): List<VocabEntry> = load().vocab

    @Synchronized
    fun addVocab(word: String, meaning: String, note: String) {
        val data = load()
        save(data.copy(vocab = data.vocab + VocabEntry(word, meaning, note)))
    }

    @Synchronized
    fun removeVocab(index: Int) {
        val data = load()
        if (index in data.vocab.indices) {
            save(data.copy(vocab = data.vocab.filterIndexed { i, _ -> i != index }))
        }
    }

    // ------------------------------------------------------------ notes

    @Synchronized
    fun listNotes(): List<PageNote> = load().notes

    @Synchronized
    fun upsertNote(subject: String, body: String) {
        val data = load()
        val existing = data.notes.indexOfFirst { it.subject == subject }
        val note = PageNote(subject, body, System.currentTimeMillis())
        val notes = if (existing >= 0) {
            data.notes.mapIndexed { i, n -> if (i == existing) note else n }
        } else {
            data.notes + note
        }
        save(data.copy(notes = notes))
    }

    @Synchronized
    fun deleteNote(subject: String) {
        val data = load()
        save(data.copy(notes = data.notes.filterNot { it.subject == subject }))
    }

    // ------------------------------------------------------------ reading

    @Synchronized
    fun logReading(title: String, minutes: Int, epochDay: Long) {
        if (minutes <= 0) return
        val data = load()
        save(data.copy(reading = data.reading + ReadingEntry(title, minutes, epochDay)))
    }

    @Synchronized
    fun listReading(): List<ReadingEntry> = load().reading

    /** Total minutes logged in the 7 days ending [todayEpochDay] inclusive. */
    fun readingMinutesThisWeek(todayEpochDay: Long): Int =
        load().reading.filter { it.epochDay in (todayEpochDay - 6)..todayEpochDay }
            .sumOf { it.minutes }

    // ------------------------------------------------------------ storage

    private fun newId(): String =
        "d${System.currentTimeMillis().toString(36)}${(Math.random() * 1e6).toInt().toString(36)}"

    private fun load(): Data {
        if (!file.exists()) return Data(emptyList(), emptyList(), emptyList(), emptyList())
        return try {
            val root = JSONObject(file.readText())
            val decks = root.optJSONArray("decks")?.let { arr ->
                (0 until arr.length()).map { i ->
                    val obj = arr.getJSONObject(i)
                    val cardsArr = obj.optJSONArray("cards") ?: JSONArray()
                    Deck(
                        id = obj.getString("id"),
                        name = obj.getString("name"),
                        cards = (0 until cardsArr.length()).map { j ->
                            val c = cardsArr.getJSONObject(j)
                            Card(
                                front = c.getString("front"),
                                back = c.getString("back"),
                                state = Sm2.CardState(
                                    easeFactor = c.optDouble("ef", Sm2.INITIAL_EASE),
                                    intervalDays = c.optInt("interval"),
                                    repetitions = c.optInt("reps"),
                                    dueEpochDay = c.optLong("due"),
                                ),
                            )
                        },
                    )
                }
            } ?: emptyList()
            val vocab = root.optJSONArray("vocab")?.let { arr ->
                (0 until arr.length()).map { i ->
                    val v = arr.getJSONObject(i)
                    VocabEntry(v.getString("word"), v.optString("meaning"), v.optString("note"))
                }
            } ?: emptyList()
            val notes = root.optJSONArray("notes")?.let { arr ->
                (0 until arr.length()).map { i ->
                    val n = arr.getJSONObject(i)
                    PageNote(n.getString("subject"), n.optString("body"), n.optLong("updated"))
                }
            } ?: emptyList()
            val reading = root.optJSONArray("reading")?.let { arr ->
                (0 until arr.length()).map { i ->
                    val r = arr.getJSONObject(i)
                    ReadingEntry(r.getString("title"), r.getInt("minutes"), r.getLong("day"))
                }
            } ?: emptyList()
            Data(decks, vocab, notes, reading)
        } catch (e: Exception) {
            Data(emptyList(), emptyList(), emptyList(), emptyList())
        }
    }

    private fun save(data: Data) {
        val root = JSONObject()
        root.put("decks", JSONArray().apply {
            data.decks.forEach { deck ->
                put(JSONObject().apply {
                    put("id", deck.id)
                    put("name", deck.name)
                    put("cards", JSONArray().apply {
                        deck.cards.forEach { card ->
                            put(JSONObject().apply {
                                put("front", card.front)
                                put("back", card.back)
                                put("ef", card.state.easeFactor)
                                put("interval", card.state.intervalDays)
                                put("reps", card.state.repetitions)
                                put("due", card.state.dueEpochDay)
                            })
                        }
                    })
                })
            }
        })
        root.put("vocab", JSONArray().apply {
            data.vocab.forEach { v ->
                put(JSONObject().apply {
                    put("word", v.word)
                    put("meaning", v.meaning)
                    put("note", v.note)
                })
            }
        })
        root.put("notes", JSONArray().apply {
            data.notes.forEach { n ->
                put(JSONObject().apply {
                    put("subject", n.subject)
                    put("body", n.body)
                    put("updated", n.updatedAtMillis)
                })
            }
        })
        root.put("reading", JSONArray().apply {
            data.reading.forEach { r ->
                put(JSONObject().apply {
                    put("title", r.title)
                    put("minutes", r.minutes)
                    put("day", r.epochDay)
                })
            }
        })
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(root.toString())
        tmp.renameTo(file)
    }
}
