package app.privatex.browser

import app.privatex.browser.tools.UpdateChecker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Phase 12 update checker logic. */
class UpdateCheckerTest {

    @Test
    fun versionComparison() {
        assertTrue(UpdateChecker.isNewer("v0.16.0", "0.15.0"))
        assertTrue(UpdateChecker.isNewer("1.0.0", "0.99.9"))
        assertTrue(UpdateChecker.isNewer("0.15.1", "0.15.0"))
        assertFalse(UpdateChecker.isNewer("0.15.0", "0.15.0"))
        assertFalse(UpdateChecker.isNewer("0.14.9", "0.15.0"))
        assertFalse(UpdateChecker.isNewer("garbage", "0.15.0"))
        assertEquals(listOf(0, 15, 0), UpdateChecker.parseVersion("v0.15.0"))
    }

    @Test
    fun latestReleaseParsing() {
        val parsed = UpdateChecker.parseLatestRelease(
            """{"tag_name":"v0.16.0","html_url":"https://example.test/r","name":"Sett v0.16.0"}""",
        )
        assertEquals("v0.16.0", parsed!!.tag)
        assertEquals("https://example.test/r", parsed.pageUrl)
        assertNull(UpdateChecker.parseLatestRelease("""{"message":"Not Found"}"""))
        assertNull(UpdateChecker.parseLatestRelease("not json"))
    }
}
