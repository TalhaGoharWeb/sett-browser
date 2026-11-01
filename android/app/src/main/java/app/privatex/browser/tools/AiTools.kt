package app.privatex.browser.tools

import org.json.JSONArray
import org.json.JSONObject

/**
 * AI Workspace logic (Phase 10): page-text extraction, an offline
 * extractive summariser (no model, no network — word-frequency
 * sentence scoring), and the OpenAI-compatible chat request/response
 * handling for bring-your-own-key providers. The consent flow and the
 * HTTP call live in the activity; everything interpretable lives here
 * and is unit-tested.
 */
object AiTools {

    /** The vault record id under which the user's provider key is kept. */
    const val AI_KEY_RECORD_ID = "sett-ai-key"

    // ----------------------------------------------------- page text

    /** Crude but dependency-free HTML → readable text. */
    fun htmlToText(html: String): String {
        var text = html
        text = Regex(
            "<(script|style|noscript|svg)[^>]*>.*?</\\1>",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        ).replace(text, " ")
        text = Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL).replace(text, " ")
        text = Regex("<br\\s*/?>", RegexOption.IGNORE_CASE).replace(text, "\n")
        text = Regex("</(p|div|li|tr|h[1-6]|article|section)>", RegexOption.IGNORE_CASE)
            .replace(text, "\n")
        text = Regex("<[^>]+>").replace(text, " ")
        text = text
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&apos;", "'")
        return text.lines()
            .map { it.replace(Regex("\\s+"), " ").trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n")
    }

    // ------------------------------------------- extractive summariser

    private val STOPWORDS = setOf(
        "the", "a", "an", "and", "or", "but", "if", "then", "else", "of", "at", "by",
        "for", "with", "about", "into", "through", "after", "over", "between", "out",
        "against", "during", "without", "before", "under", "around", "among", "is",
        "are", "was", "were", "be", "been", "being", "to", "in", "on", "as", "it", "its",
        "this", "that", "these", "those", "from", "we", "you", "he", "she", "they",
        "them", "his", "her", "their", "our", "your", "my", "me", "him", "us", "not",
        "no", "do", "does", "did", "have", "has", "had", "will", "would", "can", "could",
    )

    private fun sentences(text: String): List<String> =
        text.replace("\n", " ")
            .split(Regex("""(?<=[.?!])\s+"""))
            .map { it.trim() }
            .filter { it.length in 30..600 }

    /**
     * Extractive summary: score each sentence by the summed frequency
     * of its content words (normalised by length), take the top
     * [maxSentences], restore original order. This is a heuristic
     * highlighter, NOT comprehension — the UI calls it "key sentences".
     */
    fun extractiveSummarize(text: String, maxSentences: Int = 5): List<String> {
        val all = sentences(text)
        if (all.size <= maxSentences) return all
        val frequency = mutableMapOf<String, Int>()
        all.forEach { sentence ->
            words(sentence).forEach { word -> frequency[word] = (frequency[word] ?: 0) + 1 }
        }
        val scored = all.mapIndexed { index, sentence ->
            val content = words(sentence)
            val score = if (content.isEmpty()) {
                0.0
            } else {
                content.sumOf { frequency[it] ?: 0 }.toDouble() / content.size
            }
            Triple(index, sentence, score)
        }
        return scored.sortedByDescending { it.third }
            .take(maxSentences)
            .sortedBy { it.first }
            .map { it.second }
    }

    private fun words(sentence: String): List<String> =
        sentence.lowercase()
            .split(Regex("""[^a-z0-9']+"""))
            .filter { it.length > 2 && it !in STOPWORDS }

    // -------------------------------------------------------- provider

    enum class Task(val label: String) {
        SUMMARIZE("Summarize this page"),
        EXPLAIN("Explain this page simply"),
        TRANSLATE("Translate this page's text"),
        ASK("Ask about this page"),
    }

    /** The exact user message that would be sent — shown in the consent dialog first. */
    fun buildPrompt(task: Task, pageText: String, extra: String): String =
        when (task) {
            Task.SUMMARIZE ->
                "Summarize the following page text in a short paragraph plus bullet key points.\n\n$pageText"
            Task.EXPLAIN ->
                "Explain the following page text in simple, clear language for a non-expert.\n\n$pageText"
            Task.TRANSLATE ->
                "Translate the following page text into ${extra.ifBlank { "Urdu" }}. " +
                    "Output only the translation.\n\n$pageText"
            Task.ASK ->
                "Using only the following page text, answer this question: " +
                    "${extra.ifBlank { "(no question given)" }}\n\n$pageText"
        }

    /** OpenAI-compatible /chat/completions request body. */
    fun buildChatRequestJson(model: String, prompt: String): String =
        JSONObject()
            .put("model", model)
            .put(
                "messages",
                JSONArray().put(
                    JSONObject().put("role", "user").put("content", prompt),
                ),
            )
            .toString()

    /** The assistant's reply text; throws with the provider's own error message when present. */
    fun parseChatResponse(json: String): String {
        val root = JSONObject(json)
        root.optJSONObject("error")?.let { error ->
            throw IllegalStateException(
                "Provider error: ${error.optString("message").ifEmpty { error.toString() }}",
            )
        }
        val content = root.optJSONArray("choices")
            ?.optJSONObject(0)
            ?.optJSONObject("message")
            ?.optString("content")
        return content?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("The provider answered without message content")
    }
}
