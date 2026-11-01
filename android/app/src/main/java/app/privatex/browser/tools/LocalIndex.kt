package app.privatex.browser.tools

/**
 * On-device library index (Phase 11 — the buildable residue of the
 * independent-search-infrastructure phase: a public crawler/index is
 * blocked by zero spend, but a personal index of the user's own
 * library is not). A small inverted index over bookmarks, history,
 * research collections and study material. Tokens are Arabic-
 * normalised (via [ScholarTools.normalizeArabic]) so Arabic/Urdu
 * queries match Latin and Arabic text alike. Everything here is
 * pure and unit-tested; the activity feeds it and renders results.
 */
object LocalIndex {

    data class Doc(
        val id: String,
        /** Display source label: Bookmark, History, Research, Flashcards, Vocabulary, Note. */
        val source: String,
        val title: String,
        val body: String,
        /** A web URL when the doc opens in a tab; empty otherwise. */
        val url: String,
    )

    data class Hit(val doc: Doc, val score: Int)

    fun tokenize(text: String): List<String> =
        ScholarTools.normalizeArabic(text.lowercase())
            .split(Regex("[^\\p{L}\\p{N}]+"))
            .filter { it.length >= 2 }

    class Index(docs: List<Doc>) {
        private val docsById: Map<String, Doc> = docs.associateBy { it.id }
        private val postings: Map<String, Map<String, Int>>

        init {
            val build = mutableMapOf<String, MutableMap<String, Int>>()
            docs.forEach { doc ->
                val titleTokens = tokenize(doc.title)
                val bodyTokens = tokenize(doc.body)
                val frequencies = mutableMapOf<String, Int>()
                titleTokens.forEach { frequencies[it] = (frequencies[it] ?: 0) + 3 }
                bodyTokens.forEach { frequencies[it] = (frequencies[it] ?: 0) + 1 }
                frequencies.forEach { (token, tf) ->
                    build.getOrPut(token) { mutableMapOf() }[doc.id] = tf
                }
            }
            postings = build
        }

        val size: Int get() = docsById.size

        /**
         * AND semantics: every query term must appear. Score is the
         * summed (title-weighted) term frequency. Empty query → no hits.
         */
        fun search(query: String, limit: Int = 50): List<Hit> {
            val terms = tokenize(query).distinct()
            if (terms.isEmpty()) return emptyList()
            var candidates: MutableMap<String, Int>? = null
            for (term in terms) {
                val posting = postings[term] ?: return emptyList()
                if (candidates == null) {
                    candidates = posting.toMutableMap()
                } else {
                    val iterator = candidates.entries.iterator()
                    while (iterator.hasNext()) {
                        val entry = iterator.next()
                        val tf = posting[entry.key]
                        if (tf == null) iterator.remove() else entry.setValue(entry.value + tf)
                    }
                    if (candidates.isEmpty()) return emptyList()
                }
            }
            return candidates.orEmpty().entries
                .sortedByDescending { it.value }
                .take(limit)
                .mapNotNull { (id, score) -> docsById[id]?.let { Hit(it, score) } }
        }
    }
}
