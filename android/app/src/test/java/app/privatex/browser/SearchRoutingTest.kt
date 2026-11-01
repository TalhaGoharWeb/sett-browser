package app.privatex.browser

import app.privatex.browser.tools.SearchRouting
import app.privatex.browser.tools.SearchRouting.Category
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Phase 7 SETT Search routing: categories, providers, SearXNG handling. */
class SearchRoutingTest {

    @Test
    fun webRoutesByEngine() {
        assertEquals(
            "https://html.duckduckgo.com/html/?q=hello+world",
            SearchRouting.route(Category.WEB, "duckduckgo", "hello world", ""),
        )
        assertEquals(
            "https://search.brave.com/search?q=hello+world",
            SearchRouting.route(Category.WEB, "brave", "hello world", ""),
        )
        assertEquals(
            "https://www.startpage.com/sp/search?query=hello+world",
            SearchRouting.route(Category.WEB, "startpage", "hello world", ""),
        )
    }

    @Test
    fun searxngRoutesToUserInstanceAndFallsBackWhenBlank() {
        assertEquals(
            "https://search.example.org/search?q=test",
            SearchRouting.route(Category.WEB, "searxng", "test", "https://search.example.org/"),
        )
        assertEquals(
            "https://search.example.org/search?q=test",
            SearchRouting.route(Category.WEB, "searxng", "test", "  https://search.example.org  "),
        )
        // No instance configured: honest fallback to DuckDuckGo, never a made-up host.
        assertEquals(
            "https://html.duckduckgo.com/html/?q=test",
            SearchRouting.route(Category.WEB, "searxng", "test", ""),
        )
    }

    @Test
    fun categoryRoutes() {
        assertEquals(
            "https://duckduckgo.com/?q=cats&iax=images&ia=images",
            SearchRouting.route(Category.IMAGES, "duckduckgo", "cats", ""),
        )
        assertEquals(
            "https://search.brave.com/news?q=lahore",
            SearchRouting.route(Category.NEWS, "brave", "lahore", ""),
        )
        assertEquals(
            "https://duckduckgo.com/?q=talk&iax=videos&ia=videos",
            SearchRouting.route(Category.VIDEOS, "duckduckgo", "talk", ""),
        )
        assertEquals(
            "https://www.openstreetmap.org/search?query=Haveli+Lakha",
            SearchRouting.route(Category.MAPS, "openstreetmap", "Haveli Lakha", ""),
        )
        assertEquals(
            "https://openalex.org/works?search=maliki+fiqh",
            SearchRouting.route(Category.ACADEMIC, "openalex", "maliki fiqh", ""),
        )
    }

    @Test
    fun everyCategoryHasProvidersWithDataFlowNotes() {
        SearchRouting.Category.entries.forEach { category ->
            val providers = SearchRouting.categoryProviders.getValue(category)
            assertTrue(providers.isNotEmpty())
            providers.forEach { provider ->
                assertTrue(provider.dataFlow.isNotBlank())
            }
        }
    }

    @Test
    fun queriesAreEncoded() {
        val url = SearchRouting.route(Category.WEB, "duckduckgo", "a&b=c?", "")
        assertTrue(url.contains("a%26b%3Dc%3F"))
    }
}
