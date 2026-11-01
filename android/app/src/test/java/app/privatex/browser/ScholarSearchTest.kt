package app.privatex.browser

import app.privatex.browser.tools.ScholarTools
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Redesign Phase 8: scholarly results rendered natively in SETT Search. */
class ScholarSearchTest {

    @Test
    fun parseSemanticScholarWorks() {
        val json = """{"data":[
            {"paperId":"abc123","title":"Deep Nets","year":2020,
             "authors":[{"name":"A U Thor"},{"name":"B Writer"}],
             "venue":"NeurIPS","citationCount":42,
             "externalIds":{"DOI":"10.1000/xyz"},
             "openAccessPdf":{"url":"https://example.org/p.pdf"}},
            {"paperId":"def456","title":"No Year Paper","authors":[],
             "externalIds":{},"openAccessPdf":null}
        ]}"""
        val works = ScholarTools.parseSemanticScholar(json)
        assertEquals(2, works.size)
        val first = works[0]
        assertEquals("Deep Nets", first.title)
        assertEquals(2020, first.year)
        assertEquals("10.1000/xyz", first.doi)
        assertEquals(42, first.citedByCount)
        assertEquals(true, first.openAccess)
        assertEquals("https://example.org/p.pdf", first.oaUrl)
        assertEquals(
            "https://www.semanticscholar.org/paper/abc123",
            first.url,
        )
        assertNull(works[1].year)
        assertEquals(false, works[1].openAccess)
        assertTrue(works[1].summaryLine().contains("No Year Paper"))
    }
}
