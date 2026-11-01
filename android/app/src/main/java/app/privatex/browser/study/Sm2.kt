package app.privatex.browser.study

import kotlin.math.max
import kotlin.math.roundToInt

/**
 * SM-2 spaced repetition (Phase 9), implemented from the published
 * algorithm (Wozniak, 1990 — the same family Anki started from):
 * quality 0–5 per review; ease factor starts at 2.5 and is floored
 * at 1.3; intervals go 1 day, 6 days, then previous × ease; a quality
 * below 3 restarts the repetition count (the card relearns) without
 * discarding the ease adjustment.
 */
object Sm2 {

    const val MIN_EASE = 1.3
    const val INITIAL_EASE = 2.5

    data class CardState(
        val easeFactor: Double = INITIAL_EASE,
        val intervalDays: Int = 0,
        val repetitions: Int = 0,
        /** Epoch day (days since 1970-01-01) on which the card is next due. */
        val dueEpochDay: Long = 0,
    )

    fun newCard(todayEpochDay: Long): CardState = CardState(dueEpochDay = todayEpochDay)

    fun review(state: CardState, quality: Int, todayEpochDay: Long): CardState {
        require(quality in 0..5) { "quality must be 0..5" }
        val ease = max(
            MIN_EASE,
            state.easeFactor + (0.1 - (5 - quality) * (0.08 + (5 - quality) * 0.02)),
        )
        return if (quality < 3) {
            state.copy(
                easeFactor = ease,
                intervalDays = 1,
                repetitions = 0,
                dueEpochDay = todayEpochDay + 1,
            )
        } else {
            val interval = when (state.repetitions) {
                0 -> 1
                1 -> 6
                else -> (state.intervalDays * ease).roundToInt().coerceAtLeast(1)
            }
            state.copy(
                easeFactor = ease,
                intervalDays = interval,
                repetitions = state.repetitions + 1,
                dueEpochDay = todayEpochDay + interval,
            )
        }
    }

    /** Quality presets the review UI offers, labelled honestly by what they do. */
    enum class Grade(val quality: Int, val label: String) {
        AGAIN(2, "Again — relearn tomorrow"),
        HARD(3, "Hard — recalled with difficulty"),
        GOOD(4, "Good — recalled"),
        EASY(5, "Easy — effortless"),
    }
}
