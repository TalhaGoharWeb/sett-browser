package app.privatex.browser

import app.privatex.browser.tools.DevTools
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Phase 6 Developer Studio logic: JWT decode, header analysis, regex, URL parsing. */
class DevToolsTest {

    private fun b64url(json: String): String =
        Base64.getUrlEncoder().withoutPadding()
            .encodeToString(json.toByteArray(Charsets.UTF_8))

    @Test
    fun jwtDecodesHeaderPayloadAndExpiry() {
        val token = b64url("""{"alg":"HS256","typ":"JWT"}""") + "." +
            b64url("""{"sub":"talha","exp":1500000000}""") + ".c2ln"
        val result = DevTools.decodeJwt(token)
        assertEquals("HS256", result.algorithm)
        assertTrue(result.hasSignature)
        assertEquals(1500000000L, result.expiresAtEpochSeconds)
        assertTrue(result.payloadJson.contains("\"talha\""))
        assertTrue(result.warnings.isEmpty())
    }

    @Test
    fun jwtFlagsNoneAlgorithmEmptySignatureAndMissingExpiry() {
        val token = b64url("""{"alg":"none"}""") + "." + b64url("""{"sub":"x"}""") + "."
        val result = DevTools.decodeJwt(token)
        assertFalse(result.hasSignature)
        assertEquals(3, result.warnings.size)
        assertTrue(result.warnings.any { it.contains("none") })
    }

    @Test
    fun jwtRejectsWrongPartCount() {
        var threw = false
        try {
            DevTools.decodeJwt("only.two")
        } catch (e: IllegalArgumentException) {
            threw = true
        }
        assertTrue(threw)
    }

    @Test
    fun headerAnalysisReportsPresenceAndAbsence() {
        val full = DevTools.analyzeHeaders(
            mapOf(
                "Strict-Transport-Security" to "max-age=63072000; includeSubDomains",
                "Content-Security-Policy" to "default-src 'self'",
                "X-Content-Type-Options" to "nosniff",
                "Referrer-Policy" to "no-referrer",
                "Permissions-Policy" to "camera=()",
                "X-Frame-Options" to "DENY",
                "Cross-Origin-Opener-Policy" to "same-origin",
            ),
        )
        assertTrue(full.all { it.present })
        val empty = DevTools.analyzeHeaders(emptyMap())
        assertEquals(7, empty.size)
        assertTrue(empty.none { it.present })
    }

    @Test
    fun headerAnalysisCatchesHstsWithoutMaxAge() {
        val findings = DevTools.analyzeHeaders(
            mapOf("Strict-Transport-Security" to "includeSubDomains"),
        )
        val hsts = findings.first { it.header == "Strict-Transport-Security" }
        assertTrue(hsts.present)
        assertTrue(hsts.note.contains("without max-age"))
    }

    @Test
    fun disclosureHeadersAreSurfaced() {
        val found = DevTools.disclosureHeaders(
            mapOf("Server" to "Apache/2.4.58", "Content-Type" to "text/html"),
        )
        assertEquals(listOf("server" to "Apache/2.4.58"), found)
    }

    @Test
    fun regexFindsAllMatchRanges() {
        val ranges = DevTools.regexMatches("\\d+", "a12b345c")
        assertEquals(listOf(1..2, 4..6), ranges)
    }

    @Test
    fun urlParseBreaksDownParts() {
        val parts = DevTools.parseUrl("https://user@example.com:8443/p/a?q=1&r=two#frag")
            .toMap()
        assertEquals("https", parts["Scheme"])
        assertEquals("example.com", parts["Host"])
        assertEquals("8443", parts["Port"])
        assertEquals("1", parts["Query: q"])
        assertEquals("two", parts["Query: r"])
        assertEquals("frag", parts["Fragment"])
    }

    @Test
    fun siteFileUrlUsesSiteOrigin() {
        assertEquals(
            "https://example.com/robots.txt",
            DevTools.siteFileUrl("https://example.com/some/page?x=1", "/robots.txt"),
        )
        assertEquals(
            "http://localhost:8080/sitemap.xml",
            DevTools.siteFileUrl("http://localhost:8080/app", "/sitemap.xml"),
        )
    }
}
