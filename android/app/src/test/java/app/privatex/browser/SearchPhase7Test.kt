package app.privatex.browser

import app.privatex.browser.search.SearchHistoryStore
import app.privatex.browser.tools.SearchResults
import app.privatex.browser.tools.SearchRouting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Redesign Phase 7: native search results, new categories, recents. */
class SearchPhase7Test {

    @Test
    fun searxngUrlCarriesRealParams() {
        val url = SearchResults.searxngUrl(
            "https://search.example.org/", "hello world",
            SearchRouting.Category.NEWS, SearchResults.SafeLevel.STRICT, "ur", 2,
        )
        assertTrue(url.startsWith("https://search.example.org/search?q=hello+world"))
        assertTrue(url.contains("format=json"))
        assertTrue(url.contains("categories=news"))
        assertTrue(url.contains("safesearch=2"))
        assertTrue(url.contains("language=ur"))
        assertTrue(url.contains("pageno=2"))
    }

    @Test
    fun parseSearxngResults() {
        val json = """{"results":[
            {"title":"A","url":"https://a.example","content":"alpha","engine":"duckduckgo"},
            {"title":"","url":"https://b.example","content":"","engine":"brave"},
            {"title":"no url","content":"x"}]}"""
        val results = SearchResults.parseSearxng(json)
        assertEquals(2, results.size)
        assertEquals("alpha", results[0].snippet)
        assertEquals("duckduckgo", results[0].source)
        assertEquals("https://b.example", results[1].title)
        assertEquals(0, SearchResults.parseSearxng("not json").size)
    }

    @Test
    fun searxngUrlTimeRange() {
        val withTime = SearchResults.searxngUrl(
            "https://search.example.org", "q",
            SearchRouting.Category.WEB, SearchResults.SafeLevel.MODERATE, "", 1,
            timeRange = "week",
        )
        assertTrue(withTime.contains("time_range=week"))
        val bogus = SearchResults.searxngUrl(
            "https://search.example.org", "q",
            SearchRouting.Category.WEB, SearchResults.SafeLevel.MODERATE, "", 1,
            timeRange = "year; DROP",
        )
        assertTrue(!bogus.contains("time_range"))
    }

    @Test
    fun looksLikeSearxngJsonDistinguishesErrorPages() {
        assertTrue(SearchResults.looksLikeSearxngJson("""{"results":[]}"""))
        assertTrue(!SearchResults.looksLikeSearxngJson("<html>403 Forbidden</html>"))
        assertTrue(!SearchResults.looksLikeSearxngJson("""{"error":"x"}"""))
    }

    @Test
    fun suggestionsParse() {
        val json = """[{"phrase":"sett browser"},{"phrase":"sett badger"},{}]"""
        assertEquals(
            listOf("sett browser", "sett badger"),
            SearchResults.parseSuggestions(json),
        )
    }

    @Test
    fun newCategoriesRoute() {
        assertEquals(
            "https://openlibrary.org/search?q=dune",
            SearchRouting.route(SearchRouting.Category.BOOKS, "openlibrary", "dune", ""),
        )
        assertTrue(
            SearchRouting.route(SearchRouting.Category.PDFS, "duckduckgo", "paper", "")
                .contains("filetype%3Apdf"),
        )
        assertEquals(
            "https://grep.app/search?q=rust",
            SearchRouting.route(SearchRouting.Category.CODE, "grepapp", "rust", ""),
        )
        assertEquals(
            "https://stackoverflow.com/search?q=kotlin",
            SearchRouting.route(SearchRouting.Category.FORUMS, "stackoverflow", "kotlin", ""),
        )
        assertTrue(
            SearchRouting.route(SearchRouting.Category.OPEN_SOURCE, "github", "browser", "")
                .contains("type=repositories"),
        )
        assertEquals(11, SearchRouting.Category.entries.size)
    }

    @Test
    fun searchHistoryDedupesCapsAndClears() {
        val file = File.createTempFile("sett-search-history", ".json")
        val store = SearchHistoryStore(file)
        store.record("One")
        store.record("two")
        store.record("ONE")
        assertEquals(listOf("ONE", "two"), SearchHistoryStore(file).recent())
        repeat(25) { store.record("q$it") }
        assertEquals(20, SearchHistoryStore(file).recent().size)
        store.clear()
        assertEquals(0, SearchHistoryStore(file).recent().size)
        file.delete()
    }
}
