package app.privatex.browser

import app.privatex.browser.AddressResolver.Resolution
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 3a gate (docs/SETT_040_PLAN.md): the address-bar classification
 * and start-page matching rules, pinned on the JVM. These are exactly
 * the rules behind the failures Talha hit on device in v0.3.0 — a
 * pre-rename restored tab going blank, and home text being searched.
 */
class SettLogicTest {

    private fun classify(input: String) = AddressResolver.classify(input)

    // ---- start-page matching -------------------------------------------------

    @Test
    fun homeUrlsMatchBothGenerations() {
        assertTrue(HomeUrls.isHome("sett://home"))
        assertTrue(HomeUrls.isHome("sett://home/"))
        assertTrue(HomeUrls.isHome("privatex://home")) // pre-rename restored tabs
        assertTrue(HomeUrls.isHome("privatex://home/"))
        assertFalse(HomeUrls.isHome("https://example.com"))
        assertFalse(HomeUrls.isHome("sett://settings"))
    }

    @Test
    fun homeInputWords() {
        assertTrue(HomeUrls.isHomeInput("home"))
        assertTrue(HomeUrls.isHomeInput("  Home "))
        assertTrue(HomeUrls.isHomeInput("sett:home"))
        assertTrue(HomeUrls.isHomeInput("SETT://HOME"))
        assertFalse(HomeUrls.isHomeInput("homework help"))
    }

    // ---- address-bar classification --------------------------------------------

    @Test
    fun emptyInputGoesHome() {
        assertEquals(Resolution.Home, classify(""))
        assertEquals(Resolution.Home, classify("   "))
    }

    @Test
    fun homeWordsGoHomeNotSearch() {
        assertEquals(Resolution.Home, classify("home"))
        assertEquals(Resolution.Home, classify("sett://home"))
        assertEquals(Resolution.Home, classify("privatex://home")) // legacy tab re-typed
    }

    @Test
    fun fullUrlsPassThrough() {
        assertEquals(Resolution.Direct("https://example.com/a?b=c"), classify("https://example.com/a?b=c"))
        assertEquals(Resolution.Direct("http://example.com"), classify("http://example.com"))
        assertEquals(Resolution.Direct("about:config"), classify("about:config"))
    }

    @Test
    fun bareHostsGetHttps() {
        assertEquals(Resolution.Direct("https://example.com"), classify("example.com"))
        assertEquals(
            Resolution.Direct("https://en.wikipedia.org/wiki/Badger"),
            classify("en.wikipedia.org/wiki/Badger")
        )
        assertEquals(Resolution.Direct("https://localhost:8080/x"), classify("localhost:8080/x"))
        assertEquals(Resolution.Direct("https://localhost"), classify("localhost"))
        assertEquals(Resolution.Direct("https://192.168.1.1/admin"), classify("192.168.1.1/admin"))
    }

    @Test
    fun everythingElseIsASearch() {
        assertEquals(Resolution.Search("what is a badger"), classify("what is a badger"))
        assertEquals(Resolution.Search("badger"), classify("badger"))
        assertEquals(Resolution.Search("example"), classify("example")) // no dot/port → not a host
    }
}
