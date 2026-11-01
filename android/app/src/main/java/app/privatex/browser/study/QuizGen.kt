package app.privatex.browser.study

import kotlin.random.Random

/**
 * Rule-based quiz generation (Phase 9) from the user's own text —
 * deliberately NOT AI, and labelled as rule-based wherever shown:
 *
 * 1. Definition sentences ("X is/are/means/refers to Y") become
 *    questions: "What is X?" (answer Y) and cloze items with X
 *    blanked out.
 * 2. Every other content sentence can become a cloze item by blanking
 *    its longest word (7+ letters), a weak but honest heuristic.
 * Multiple-choice options are drawn from the other extracted answers
 * of the same batch, shuffled with a caller-supplied seed so tests
 * are deterministic.
 */
object QuizGen {

    data class QuizItem(
        val question: String,
        val answer: String,
        /** For MCQs: all options including the answer, shuffled. Null = open recall. */
        val options: List<String>?,
    )

    private val DEFINITION = Regex(
        """^(.{2,60}?)\s+(is|are|means|refers to|is defined as)\s+(.{8,})[.?!]?$""",
        RegexOption.IGNORE_CASE,
    )

    private fun sentences(text: String): List<String> =
        text.replace("\n", " ")
            .split(Regex("""(?<=[.?!])\s+"""))
            .map { it.trim() }
            .filter { it.length in 20..400 }

    fun generate(text: String, seed: Long = 42): List<QuizItem> {
        val items = mutableListOf<QuizItem>()
        val definitions = mutableListOf<Pair<String, String>>() // term -> definition
        val clozePool = mutableListOf<Pair<String, String>>() // question -> answer

        for (sentence in sentences(text)) {
            val match = DEFINITION.find(sentence)
            if (match != null) {
                val term = match.groupValues[1].trim()
                    .removePrefix("The ").removePrefix("A ").removePrefix("An ")
                val definition = match.groupValues[3].trim()
                if (term.length in 2..60 && definition.length >= 8) {
                    definitions.add(term to definition)
                    items.add(
                        QuizItem(
                            question = "What is $term?",
                            answer = definition,
                            options = null,
                        ),
                    )
                    clozePool.add(sentence.replace(term, "_____") to term)
                }
            } else {
                val words = sentence.split(Regex("""\s+"""))
                    .map { it.trim(',', '.', ';', ':', '(', ')', '"') }
                val candidate = words.filter { it.length >= 7 }
                    .maxByOrNull { it.length }
                if (candidate != null) {
                    clozePool.add(sentence.replace(candidate, "_____") to candidate)
                }
            }
        }

        val allAnswers = (definitions.map { it.second } + clozePool.map { it.second }).distinct()
        val random = Random(seed)
        val mcq: (question: String, answer: String) -> QuizItem? = { question, answer ->
            val distractors = allAnswers.filter { it != answer }.shuffled(random).take(3)
            if (distractors.size < 3) {
                null
            } else {
                QuizItem(question, answer, (distractors + answer).shuffled(random))
            }
        }
        clozePool.forEach { (question, answer) ->
            mcq("Fill in the blank: $question", answer)?.let { items.add(it) }
        }
        definitions.forEach { (term, definition) ->
            mcq("Which term means: $definition", term)?.let { items.add(it) }
        }
        return items
    }
}
