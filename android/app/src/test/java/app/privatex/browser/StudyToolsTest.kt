package app.privatex.browser

import app.privatex.browser.study.QuizGen
import app.privatex.browser.study.Sm2
import app.privatex.browser.study.StudyStore
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Phase 9 Study Center logic: SM-2 scheduling, rule-based quiz generation, store. */
class StudyToolsTest {

    @Test
    fun sm2FollowsTheCanonicalSequence() {
        val today = 20_000L
        var state = Sm2.newCard(today)
        // First good review: interval 1.
        state = Sm2.review(state, 4, today)
        assertEquals(1, state.intervalDays)
        assertEquals(today + 1, state.dueEpochDay)
        // Second: interval 6.
        state = Sm2.review(state, 4, today + 1)
        assertEquals(6, state.intervalDays)
        // Third: interval = 6 * ease(2.5) = 15.
        state = Sm2.review(state, 4, today + 7)
        assertEquals(15, state.intervalDays)
        assertEquals(3, state.repetitions)
    }

    @Test
    fun sm2FailureRestartsAndEaseIsFloored() {
        var state = Sm2.newCard(0)
        repeat(6) { state = Sm2.review(state, 0, 0) }
        assertEquals(Sm2.MIN_EASE, state.easeFactor)
        assertEquals(0, state.repetitions)
        assertEquals(1, state.intervalDays)
        assertEquals(1L, state.dueEpochDay)
    }

    @Test
    fun sm2EasyRaisesEase() {
        val state = Sm2.review(Sm2.newCard(0), 5, 0)
        assertTrue(state.easeFactor > Sm2.INITIAL_EASE)
    }

    @Test
    fun quizGenExtractsDefinitionsAndClozes() {
        val text = """
            Photosynthesis is the process by which green plants convert sunlight into chemical energy stored in glucose molecules.
            The mitochondria is the organelle responsible for producing adenosine triphosphate through cellular respiration pathways.
            Plants absorb water through their roots and transport it upwards through specialised xylem vessels towards the leaves.
            Evaporation is the process where liquid water transforms into vapour at the surface without reaching boiling temperature.
        """.trimIndent()
        val items = QuizGen.generate(text, seed = 7)
        // Open-recall definition questions exist.
        assertTrue(items.any { it.question.startsWith("What is Photosynthesis") && it.options == null })
        // MCQs have 4 options including the answer.
        val mcqs = items.filter { it.options != null }
        assertTrue(mcqs.isNotEmpty())
        mcqs.forEach { item ->
            assertEquals(4, item.options!!.size)
            assertTrue(item.options.contains(item.answer))
        }
        // A term-identification MCQ exists whose answer is a defined term.
        assertTrue(items.any { it.question.startsWith("Which term means:") })
    }

    @Test
    fun studyStoreRoundTrips() {
        val file = File.createTempFile("sett-study-test", ".json")
        file.delete()
        try {
            val store = StudyStore(file)
            val deck = store.createDeck("Arabic vocab")
            store.addCard(deck.id, "كتاب", "book", todayEpochDay = 100)
            store.addCard(deck.id, "قلم", "pen", todayEpochDay = 100)
            var loaded = store.listDecks().single()
            assertEquals(2, store.dueCards(loaded, 100).size)
            store.reviewCard(deck.id, 0, quality = 4, todayEpochDay = 100)
            loaded = store.listDecks().single()
            assertEquals(1, store.dueCards(loaded, 100).size)
            assertEquals(2, store.dueCards(loaded, 101).size)

            store.addVocab("sabr", "patience", "Qur'anic term")
            assertEquals(1, store.listVocab().size)
            store.removeVocab(0)
            assertTrue(store.listVocab().isEmpty())

            store.upsertNote("https://example.org/a", "key point")
            store.upsertNote("https://example.org/a", "key point, revised")
            assertEquals(1, store.listNotes().size)
            assertTrue(store.listNotes()[0].body.contains("revised"))

            store.logReading("Mukhtasar al-Akhdari", 25, epochDay = 100)
            store.logReading("Mukhtasar al-Akhdari", 15, epochDay = 94)
            store.logReading("Old session", 90, epochDay = 90)
            // Week ending day 100 covers days 94..100: 25 + 15 = 40.
            assertEquals(40, store.readingMinutesThisWeek(100))
        } finally {
            file.delete()
        }
    }
}
