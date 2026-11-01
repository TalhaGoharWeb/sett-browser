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
    fun ddgUrlSafeSearchAndRegion() {
        val strict = SearchResults.ddgUrl(
            "q", SearchResults.SafeLevel.STRICT, "pk-en",
        )
        assertTrue(strict.contains("kp=1"))
        assertTrue(strict.contains("kl=pk-en"))
        val off = SearchResults.ddgUrl("q", SearchResults.SafeLevel.OFF, "bogus")
        assertTrue(off.contains("kp=-2"))
        assertTrue(!off.contains("kl="))
    }

    @Test
    fun parseDdgHtmlUnwrapsRedirects() {
        val html = """
            <div class="result">
              <a rel="nofollow" class="result__a" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fexample.org%2Fpage&amp;rut=abc">Example &amp; Title</a>
              <a class="result__snippet" href="//duckduckgo.com/l/?uddg=x">A <b>snippet</b> here</a>
            </div>
            <div class="result">
              <a rel="nofollow" class="result__a" href="https://direct.example/x">Direct</a>
              <a class="result__snippet" href="https://direct.example/x">Second snippet</a>
            </div>
        """.trimIndent()
        val results = SearchResults.parseDdgHtml(html)
        assertEquals(2, results.size)
        assertEquals("Example & Title", results[0].title)
        assertEquals("https://example.org/page", results[0].url)
        assertEquals("A snippet here", results[0].snippet)
        assertEquals("https://direct.example/x", results[1].url)
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
