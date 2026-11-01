package app.privatex.browser.tools

import org.json.JSONObject

/**
 * Scholar Research Desk logic (Phase 8): parsers for the keyless
 * scholarly APIs (OpenAlex, Crossref, DataCite, DOAJ), DOI handling,
 * citation export (BibTeX / RIS), and Arabic-script normalisation for
 * diacritic-insensitive search over local texts. Pure JVM code — the
 * HTTP calls live in the activity, the interpretation of what comes
 * back lives here and is unit-tested against sample payloads.
 */
object ScholarTools {

    data class Work(
        val title: String,
        val authors: List<String>,
        val year: Int?,
        val doi: String?,
        val source: String?,
        val url: String?,
        val openAccess: Boolean?,
        val oaUrl: String?,
        val citedByCount: Int?,
        val origin: String,
    ) {
        /** One-line display form used in result lists. */
        fun summaryLine(): String = buildString {
            append(title)
            if (authors.isNotEmpty()) {
                append(" — ").append(authors.take(3).joinToString(", "))
                if (authors.size > 3) append(" et al.")
            }
            year?.let { append(" (").append(it).append(")") }
        }
    }

    // --------------------------------------------------------- OpenAlex

    fun parseOpenAlex(json: String): List<Work> {
        val results = JSONObject(json).optJSONArray("results") ?: return emptyList()
        return (0 until results.length()).map { i ->
            val item = results.getJSONObject(i)
            val authors = item.optJSONArray("authorships")?.let { arr ->
                (0 until arr.length()).mapNotNull { j ->
                    arr.getJSONObject(j).optJSONObject("author")?.optString("display_name")
                        ?.takeIf { it.isNotEmpty() }
                }
            } ?: emptyList()
            val oa = item.optJSONObject("open_access")
            Work(
                title = item.optString("title").ifEmpty { item.optString("display_name") },
                authors = authors,
                year = if (item.has("publication_year")) item.optInt("publication_year") else null,
                doi = item.optString("doi").removePrefix("https://doi.org/").ifEmpty { null },
                source = item.optJSONObject("primary_location")
                    ?.optJSONObject("source")?.optString("display_name")?.ifEmpty { null },
                url = item.optString("id").ifEmpty { null },
                openAccess = oa?.optBoolean("is_oa"),
                oaUrl = oa?.optString("oa_url")?.ifEmpty { null },
                citedByCount = if (item.has("cited_by_count")) item.optInt("cited_by_count") else null,
                origin = "OpenAlex",
            )
        }
    }

    // --------------------------------------------------------- Crossref

    fun parseCrossref(json: String): List<Work> {
        val items = JSONObject(json).optJSONObject("message")?.optJSONArray("items")
            ?: return emptyList()
        return (0 until items.length()).map { i ->
            val item = items.getJSONObject(i)
            val authors = item.optJSONArray("author")?.let { arr ->
                (0 until arr.length()).map { j ->
                    val author = arr.getJSONObject(j)
                    listOf(author.optString("given"), author.optString("family"))
                        .filter { it.isNotEmpty() }.joinToString(" ")
                }.filter { it.isNotEmpty() }
            } ?: emptyList()
            val year = item.optJSONObject("issued")
                ?.optJSONArray("date-parts")
                ?.optJSONArray(0)
                ?.let { if (it.length() > 0) it.optInt(0) else null }
            Work(
                title = item.optJSONArray("title")?.optString(0) ?: "(untitled)",
                authors = authors,
                year = year,
                doi = item.optString("DOI").ifEmpty { null },
                source = item.optJSONArray("container-title")?.optString(0)?.ifEmpty { null },
                url = item.optString("URL").ifEmpty { null },
                openAccess = null,
                oaUrl = null,
                citedByCount = if (item.has("is-referenced-by-count")) {
                    item.optInt("is-referenced-by-count")
                } else {
                    null
                },
                origin = "Crossref",
            )
        }
    }

    // --------------------------------------------------------- DataCite

    fun parseDataCite(json: String): List<Work> {
        val data = JSONObject(json).optJSONArray("data") ?: return emptyList()
        return (0 until data.length()).map { i ->
            val attributes = data.getJSONObject(i).optJSONObject("attributes") ?: JSONObject()
            val title = attributes.optJSONArray("titles")?.optJSONObject(0)
                ?.optString("title") ?: "(untitled)"
            val creators = attributes.optJSONArray("creators")?.let { arr ->
                (0 until arr.length()).mapNotNull { j ->
                    arr.getJSONObject(j).optString("name").ifEmpty { null }
                }
            } ?: emptyList()
            Work(
                title = title,
                authors = creators,
                year = attributes.optString("publicationYear")
                    .toIntOrNull() ?: attributes.optInt("publicationYear").takeIf { it > 0 },
                doi = attributes.optString("doi").ifEmpty { null },
                source = attributes.optJSONObject("publisher")?.optString("name")
                    ?.ifEmpty { null }
                    ?: attributes.optString("publisher").ifEmpty { null },
                url = attributes.optString("url").ifEmpty { null },
                openAccess = null,
                oaUrl = null,
                citedByCount = null,
                origin = "DataCite",
            )
        }
    }

    // ------------------------------------------------------------- DOAJ

    fun parseDoaj(json: String): List<Work> {
        val results = JSONObject(json).optJSONArray("results") ?: return emptyList()
        return (0 until results.length()).map { i ->
            val bib = results.getJSONObject(i).optJSONObject("bibjson") ?: JSONObject()
            val authors = bib.optJSONArray("author")?.let { arr ->
                (0 until arr.length()).mapNotNull { j ->
                    arr.getJSONObject(j).optString("name").ifEmpty { null }
                }
            } ?: emptyList()
            val identifiers = bib.optJSONArray("identifier")
            var doi: String? = null
            if (identifiers != null) {
                for (j in 0 until identifiers.length()) {
                    val id = identifiers.getJSONObject(j)
                    if (id.optString("type") == "doi") doi = id.optString("id")
                }
            }
            val link = bib.optJSONArray("link")?.optJSONObject(0)?.optString("url")
            Work(
                title = bib.optString("title").ifEmpty { "(untitled)" },
                authors = authors,
                year = bib.optString("year").toIntOrNull(),
                doi = doi,
                source = bib.optJSONObject("journal")?.optString("title")?.ifEmpty { null },
                url = link?.ifEmpty { null },
                // Listing in DOAJ means the journal is open access by definition.
                openAccess = true,
                oaUrl = link?.ifEmpty { null },
                citedByCount = null,
                origin = "DOAJ",
            )
        }
    }

    // -------------------------------------------------------------- DOI

    private val DOI_PATTERN = Regex("""10\.\d{4,9}/[^\s"'<>\[\]{}]+""")

    /** Normalises user input into a bare DOI, or null when it is not DOI-shaped. */
    fun normalizeDoi(input: String): String? {
        val cleaned = input.trim()
            .removePrefix("https://doi.org/")
            .removePrefix("http://doi.org/")
            .removePrefix("doi:")
            .trim()
        val match = DOI_PATTERN.find(cleaned) ?: return null
        return match.value.trimEnd('.', ',', ';', ')')
    }

    /** Finds every DOI in a block of text (e.g. references extracted from a PDF). */
    fun extractDois(text: String): List<String> =
        DOI_PATTERN.findAll(text)
            .map { it.value.trimEnd('.', ',', ';', ')') }
            .distinct()
            .toList()

    // ----------------------------------------------------------- export

    private fun citeKey(work: Work): String {
        val surname = work.authors.firstOrNull()
            ?.split(" ")?.lastOrNull()
            ?.lowercase()?.filter { it.isLetter() }
            ?.ifEmpty { "work" } ?: "work"
        return surname + (work.year?.toString() ?: "nd")
    }

    fun toBibTex(work: Work): String = buildString {
        append("@article{").append(citeKey(work)).append(",\n")
        append("  title = {").append(work.title.replace("{", "").replace("}", "")).append("},\n")
        if (work.authors.isNotEmpty()) {
            append("  author = {").append(work.authors.joinToString(" and ")).append("},\n")
        }
        work.year?.let { append("  year = {").append(it).append("},\n") }
        work.source?.let { append("  journal = {").append(it).append("},\n") }
        work.doi?.let { append("  doi = {").append(it).append("},\n") }
        work.url?.let { append("  url = {").append(it).append("},\n") }
        append("}")
    }

    fun toRis(work: Work): String = buildString {
        append("TY  - JOUR\n")
        append("TI  - ").append(work.title).append('\n')
        work.authors.forEach { append("AU  - ").append(it).append('\n') }
        work.year?.let { append("PY  - ").append(it).append('\n') }
        work.source?.let { append("JO  - ").append(it).append('\n') }
        work.doi?.let { append("DO  - ").append(it).append('\n') }
        work.url?.let { append("UR  - ").append(it).append('\n') }
        append("ER  - \n")
    }

    // ------------------------------------------- Arabic/Urdu normalise

    /**
     * Normalises Arabic-script text for search: strips harakat and
     * tatweel, unifies alef variants to bare alef, teh marbuta to heh,
     * alef maksura to yeh, and Persian/Urdu yeh/kaf variants to their
     * Arabic counterparts. Matching is then diacritic-insensitive and
     * tolerant of the common spelling variants.
     */
    fun normalizeArabic(text: String): String {
        val out = StringBuilder(text.length)
        for (ch in text) {
            when (ch) {
                // Harakat U+064B..U+0652, superscript alef U+0670, tatweel U+0640: drop.
                in '\u064B'..'\u0652', '\u0670', '\u0640' -> Unit
                'أ', 'إ', 'آ', 'ٱ' -> out.append('ا')
                'ة' -> out.append('ه')
                'ى' -> out.append('ي')
                'ی' -> out.append('ي') // Urdu yeh U+06CC -> Arabic yeh
                'ک' -> out.append('ك') // Urdu kaf U+06A9 -> Arabic kaf
                'گ' -> out.append('ك')
                else -> out.append(ch)
            }
        }
        return out.toString().lowercase()
    }

    /**
     * All match positions of [needle] in [text] under Arabic
     * normalisation, returned as ranges over the NORMALISED text
     * (positions do not map 1:1 onto the original when diacritics were
     * stripped — the UI shows snippets from the original by searching
     * near matches, and labels the approach honestly).
     */
    fun arabicFindAll(text: String, needle: String): List<IntRange> {
        val haystack = normalizeArabic(text)
        val target = normalizeArabic(needle).trim()
        if (target.isEmpty()) return emptyList()
        val ranges = mutableListOf<IntRange>()
        var from = 0
        while (true) {
            val idx = haystack.indexOf(target, from)
            if (idx < 0) break
            ranges.add(idx until idx + target.length)
            from = idx + 1
        }
        return ranges
    }

    /** Convenience: true when the needle occurs under normalisation. */
    fun arabicContains(text: String, needle: String): Boolean =
        arabicFindAll(text, needle).isNotEmpty()
}
