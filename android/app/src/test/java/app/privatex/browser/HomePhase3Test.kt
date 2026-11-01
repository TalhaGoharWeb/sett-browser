package app.privatex.browser

import app.privatex.browser.home.HomeActions
import app.privatex.browser.home.HomePageBuilder
import app.privatex.browser.home.HomeStore
import app.privatex.browser.ui.SettTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Redesign Phase 3: home store, actions and page builder. */
class HomePhase3Test {

    private fun data(
        shortcuts: List<HomeStore.Shortcut> = emptyList(),
        bookmarks: List<HomePageBuilder.Link> = emptyList(),
        recent: List<HomePageBuilder.Link> = emptyList(),
        closed: List<HomePageBuilder.Link> = emptyList(),
        sections: Set<String> = HomePageBuilder.ALL_SECTIONS,
        profile: String = "Strict",
    ) = HomePageBuilder.Data(
        palette = SettTheme.DARK,
        engineName = "DuckDuckGo",
        shortcuts = shortcuts,
        bookmarks = bookmarks,
        recent = recent,
        closedTabs = closed,
        protection = HomePageBuilder.Protection(profile, true, true, false, "Cloudflare"),
        sections = sections,
        categories = listOf("Web" to "WEB", "Academic" to "ACADEMIC"),
    )

    @Test
    fun brockIsADataUriOrAbsent_neverAnUnservedScheme() {
        // Regression (device report 2026-11-01): the page referenced
        // sett://asset/brock.png, which nothing in the app serves.
        val withBrock = HomePageBuilder.build(
            data().copy(brockDataUri = "data:image/png;base64,AAAA"),
        )
        assertTrue(withBrock.contains("data:image/png;base64,AAAA"))
        assertFalse(withBrock.contains("sett://asset"))
        val without = HomePageBuilder.build(data())
        assertFalse(without.contains("sett://asset"))
        assertFalse(without.contains("<img src=\"sett:"))
    }

    @Test
    fun runSearchUrlCarriesTheQuery() {
        val url = HomeActions.runSearchUrl("hello world")
        assertTrue(url.startsWith("sett://action/runsearch?q="))
        assertTrue(url.contains("hello+world"))
        assertEquals("runsearch", HomeActions.parse(url))
    }

    @Test
    fun fallbackPageAlwaysWorks() {
        val html = HomePageBuilder.fallbackHtml(SettTheme.LIGHT)
        assertTrue(html.contains("sett://resolve"))
        assertTrue(html.contains("<form"))
        assertTrue(html.contains("Sett home"))
    }

    @Test
    fun storeRoundTripAndValidation() {
        val file = File.createTempFile("sett-home", ".json")
        val store = HomeStore(file)
        val a = store.add("Example", "https://example.org/x")
        store.add("", "https://mozilla.org")
        val all = HomeStore(file).shortcuts()
        assertEquals(2, all.size)
        assertEquals("mozilla.org", all[1].title) // host fallback title
        store.move(a.id, 1)
        assertEquals(a.id, HomeStore(file).shortcuts()[1].id)
        store.remove(a.id)
        assertEquals(1, HomeStore(file).shortcuts().size)
        val rejected = runCatching { store.add("x", "ftp://nope") }.isFailure
        assertTrue(rejected)
        file.delete()
    }

    @Test
    fun actionsParse() {
        assertEquals("shield", HomeActions.parse("sett://action/shield"))
        assertEquals("search-academic", HomeActions.parse("sett://action/search-academic"))
        assertEquals(null, HomeActions.parse("sett://home"))
        assertEquals("sett://action/search-news", HomeActions.searchUrl("NEWS"))
    }

    @Test
    fun pageResolvesAndReflectsRealState() {
        val html = HomePageBuilder.build(
            data(
                shortcuts = listOf(HomeStore.Shortcut("1", "Example", "https://example.org")),
                recent = listOf(HomePageBuilder.Link("A <b>title</b>", "https://a.example")),
            ),
        )
        // HOME-1: the form resolves natively, never posts to an engine.
        assertTrue(html.contains("action=\"sett://resolve\""))
        // HOME-2: the protection card shows the given profile.
        assertTrue(html.contains("Strict"))
        assertTrue(html.contains("Cloudflare"))
        assertTrue(html.contains("Clear on exit"))
        // Shortcut tile + favicon with letter fallback.
        assertTrue(html.contains("https://example.org/favicon.ico"))
        assertTrue(html.contains(">Example</span>"))
        // Section with data renders; empty sections do not.
        assertTrue(html.contains("Continue browsing"))
        assertFalse(html.contains("Bookmarks</h2>"))
        assertFalse(html.contains("Recently closed"))
        // Titles are escaped, not injected.
        assertTrue(html.contains("A &lt;b&gt;title&lt;/b&gt;"))
        // Category chips route through native actions.
        assertTrue(html.contains("sett://action/search-academic"))
    }

    @Test
    fun hiddenSectionDoesNotRenderEvenWithData() {
        val html = HomePageBuilder.build(
            data(
                recent = listOf(HomePageBuilder.Link("T", "https://t.example")),
                sections = setOf(HomePageBuilder.SECTION_BOOKMARKS),
            ),
        )
        assertFalse(html.contains("Continue browsing"))
    }
}
