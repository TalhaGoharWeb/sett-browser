package app.privatex.browser

import app.privatex.browser.research.ResearchStore
import app.privatex.browser.tools.ScholarTools
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Phase 8 Scholar Desk logic: API parsers, DOI handling, exports, Arabic search, collections. */
class ScholarToolsTest {

    @Test
    fun openAlexParsesWorks() {
        val json = """
        {"results":[{"title":"Deep Learning","display_name":"Deep Learning",
          "publication_year":2015,"doi":"https://doi.org/10.1038/nature14539",
          "cited_by_count":100000,
          "authorships":[{"author":{"display_name":"Yann LeCun"}},
                         {"author":{"display_name":"Yoshua Bengio"}}],
          "primary_location":{"source":{"display_name":"Nature"}},
          "open_access":{"is_oa":true,"oa_url":"https://example.org/dl.pdf"},
          "id":"https://openalex.org/W123"}]}
        """.trimIndent()
        val works = ScholarTools.parseOpenAlex(json)
        assertEquals(1, works.size)
        val work = works[0]
        assertEquals("Deep Learning", work.title)
        assertEquals(listOf("Yann LeCun", "Yoshua Bengio"), work.authors)
        assertEquals(2015, work.year)
        assertEquals("10.1038/nature14539", work.doi)
        assertEquals("Nature", work.source)
        assertEquals(true, work.openAccess)
        assertEquals(100000, work.citedByCount)
    }

    @Test
    fun crossrefParsesWorks() {
        val json = """
        {"message":{"items":[{"title":["On Computable Numbers"],
          "author":[{"given":"Alan","family":"Turing"}],
          "issued":{"date-parts":[[1936]]},
          "DOI":"10.1112/plms/s2-42.1.230",
          "container-title":["Proceedings of the LMS"],
          "URL":"https://doi.org/10.1112/plms/s2-42.1.230",
          "is-referenced-by-count":5000}]}}
        """.trimIndent()
        val works = ScholarTools.parseCrossref(json)
        assertEquals(1, works.size)
        assertEquals("Alan Turing", works[0].authors[0])
        assertEquals(1936, works[0].year)
        assertEquals("10.1112/plms/s2-42.1.230", works[0].doi)
        assertEquals("Proceedings of the LMS", works[0].source)
    }

    @Test
    fun dataCiteParsesWorks() {
        val json = """
        {"data":[{"attributes":{"titles":[{"title":"Some Dataset"}],
          "creators":[{"name":"Farid, Talha"}],
          "publicationYear":2024,"doi":"10.5281/zenodo.1234",
          "publisher":"Zenodo","url":"https://zenodo.org/1234"}}]}
        """.trimIndent()
        val works = ScholarTools.parseDataCite(json)
        assertEquals(1, works.size)
        assertEquals("Some Dataset", works[0].title)
        assertEquals(2024, works[0].year)
        assertEquals("10.5281/zenodo.1234", works[0].doi)
    }

    @Test
    fun doajParsesWorksAsOpenAccess() {
        val json = """
        {"results":[{"bibjson":{"title":"Open Journal Paper","year":"2023",
          "author":[{"name":"Amina Noor"}],
          "identifier":[{"type":"doi","id":"10.1234/ojp.1"}],
          "journal":{"title":"Journal of Open Things"},
          "link":[{"url":"https://example.org/paper"}]}}]}
        """.trimIndent()
        val works = ScholarTools.parseDoaj(json)
        assertEquals(1, works.size)
        assertEquals(true, works[0].openAccess)
        assertEquals("10.1234/ojp.1", works[0].doi)
        assertEquals(2023, works[0].year)
    }

    @Test
    fun doiNormalisationAndExtraction() {
        assertEquals("10.1038/nature14539", ScholarTools.normalizeDoi("https://doi.org/10.1038/nature14539"))
        assertEquals("10.1234/abc", ScholarTools.normalizeDoi("doi:10.1234/abc."))
        assertNull(ScholarTools.normalizeDoi("just some words"))
        val text = "Refs: [1] doi:10.1000/xyz123. [2] https://doi.org/10.5555/abc-def, and again 10.1000/xyz123"
        assertEquals(listOf("10.1000/xyz123", "10.5555/abc-def"), ScholarTools.extractDois(text))
    }

    @Test
    fun exportsContainKeyFields() {
        val work = ScholarTools.Work(
            title = "A Title", authors = listOf("Talha Farid"), year = 2020,
            doi = "10.1/x", source = "Some Journal", url = "https://e.org",
            openAccess = true, oaUrl = null, citedByCount = 3, origin = "OpenAlex",
        )
        val bib = ScholarTools.toBibTex(work)
        assertTrue(bib.startsWith("@article{farid2020,"))
        assertTrue(bib.contains("doi = {10.1/x}"))
        val ris = ScholarTools.toRis(work)
        assertTrue(ris.startsWith("TY  - JOUR"))
        assertTrue(ris.contains("DO  - 10.1/x"))
    }

    @Test
    fun arabicNormalisationIgnoresDiacriticsAndUnifiesLetters() {
        // "al-hamdu" fully vocalised vs plain: same after normalisation.
        val vocalised = "ٱلْحَمْدُ لِلَّهِ"
        val plain = "الحمد لله"
        assertEquals(ScholarTools.normalizeArabic(plain), ScholarTools.normalizeArabic(vocalised))
        assertTrue(ScholarTools.arabicContains("قال رسول الله صلى الله عليه وسلم", "رَسُول"))
        // Alef maksura / yeh and Urdu letters unify.
        assertTrue(ScholarTools.arabicContains("موسى", "موسي"))
        assertTrue(ScholarTools.arabicContains("کتاب", "كتاب"))
        assertFalse(ScholarTools.arabicContains("الحمد لله", "الصلاه"))
    }

    @Test
    fun researchStoreRoundTrips() {
        val file = File.createTempFile("sett-research-test", ".json")
        file.delete()
        try {
            val store = ResearchStore(file)
            val collection = store.createCollection("Fiqh papers")
            val work = ScholarTools.Work(
                title = "Paper One", authors = listOf("A Author"), year = 2021,
                doi = "10.9/p1", source = null, url = null,
                openAccess = null, oaUrl = null, citedByCount = null, origin = "Crossref",
            )
            store.addWork(collection.id, work)
            store.addWork(collection.id, work) // duplicate DOI — must not double
            val loaded = store.listCollections().single()
            assertEquals(1, loaded.works.size)
            store.setNote(collection.id, 0, "read chapter 2")
            assertEquals("read chapter 2", store.listCollections().single().works[0].note)
            assertTrue(store.exportBibTex(collection.id).contains("Paper One"))
            assertTrue(store.exportRis(collection.id).contains("Paper One"))
            store.removeWork(collection.id, 0)
            assertTrue(store.listCollections().single().works.isEmpty())
            store.deleteCollection(collection.id)
            assertTrue(store.listCollections().isEmpty())
        } finally {
            file.delete()
        }
    }
}
