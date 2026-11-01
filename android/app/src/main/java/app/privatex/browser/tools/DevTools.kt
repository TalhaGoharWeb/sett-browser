package app.privatex.browser.tools

import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.Base64
import org.json.JSONObject

/**
 * Developer Studio logic (Phase 6): JWT decoding, security-header
 * analysis, regex testing, URL parsing and a direct HTTP fetcher.
 * All pure JVM code except [httpRequest], which is still plain
 * java.net — requests are made by the app process directly (no
 * cookies, no JavaScript), and the UI says so wherever that matters.
 */
object DevTools {

    // ------------------------------------------------------------- JWT

    data class JwtResult(
        val headerJson: String,
        val payloadJson: String,
        val algorithm: String?,
        val hasSignature: Boolean,
        /** Epoch seconds from the payload's `exp` claim, if present. */
        val expiresAtEpochSeconds: Long?,
        val warnings: List<String>,
    )

    /**
     * Decodes a JWT's header and payload. This NEVER verifies the
     * signature — decoding shows what the token CLAIMS, and the result
     * is labelled as unverified everywhere it is displayed.
     */
    fun decodeJwt(token: String): JwtResult {
        val parts = token.trim().split(".")
        require(parts.size == 3) { "A JWT has three dot-separated parts" }
        val header = JSONObject(decodeBase64Url(parts[0]))
        val payload = JSONObject(decodeBase64Url(parts[1]))
        val warnings = mutableListOf<String>()
        val algorithm = header.optString("alg").ifEmpty { null }
        if (algorithm.equals("none", ignoreCase = true)) {
            warnings.add("alg is 'none' — this token is unsigned; any server accepting it is broken.")
        }
        if (parts[2].isEmpty()) {
            warnings.add("The signature part is empty.")
        }
        if (!payload.has("exp")) {
            warnings.add("No exp claim — this token may never expire.")
        }
        return JwtResult(
            headerJson = header.toString(2),
            payloadJson = payload.toString(2),
            algorithm = algorithm,
            hasSignature = parts[2].isNotEmpty(),
            expiresAtEpochSeconds = if (payload.has("exp")) payload.optLong("exp") else null,
            warnings = warnings,
        )
    }

    private fun decodeBase64Url(part: String): String {
        val decoded = Base64.getUrlDecoder().decode(part)
        return String(decoded, Charsets.UTF_8)
    }

    // ------------------------------------------------- security headers

    data class HeaderFinding(
        val header: String,
        val present: Boolean,
        val value: String?,
        val note: String,
    )

    /** Analyses a response-header map (case-insensitive) — presence and shape, never a made-up "score". */
    fun analyzeHeaders(rawHeaders: Map<String, String>): List<HeaderFinding> {
        val headers = rawHeaders.entries.associate { it.key.lowercase() to it.value }
        fun finding(
            header: String,
            key: String,
            presentNote: String,
            missingNote: String,
            extraCheck: (String) -> String? = { null },
        ): HeaderFinding {
            val value = headers[key]
            return if (value == null) {
                HeaderFinding(header, false, null, missingNote)
            } else {
                HeaderFinding(header, true, value, extraCheck(value) ?: presentNote)
            }
        }
        return listOf(
            finding(
                "Strict-Transport-Security", "strict-transport-security",
                "HSTS is set — HTTPS is enforced for repeat visits.",
                "No HSTS — nothing stops a first-visit or downgrade attack on plain HTTP.",
            ) { value ->
                if (!value.contains("max-age", ignoreCase = true)) {
                    "HSTS present but without max-age it does nothing."
                } else {
                    null
                }
            },
            finding(
                "Content-Security-Policy", "content-security-policy",
                "CSP is set — it limits what the page may load and run.",
                "No CSP — the browser applies no page-level load restrictions.",
            ),
            finding(
                "X-Content-Type-Options", "x-content-type-options",
                "MIME sniffing is restricted.",
                "No nosniff header — browsers may guess content types.",
            ) { value ->
                if (!value.contains("nosniff", ignoreCase = true)) {
                    "Present but does not say nosniff."
                } else {
                    null
                }
            },
            finding(
                "Referrer-Policy", "referrer-policy",
                "Referrer leakage is controlled.",
                "No Referrer-Policy — full URLs may leak to third parties via the Referer header.",
            ),
            finding(
                "Permissions-Policy", "permissions-policy",
                "Browser features (camera, mic, geolocation…) are explicitly gated.",
                "No Permissions-Policy — page features default to the browser's policy.",
            ),
            finding(
                "X-Frame-Options", "x-frame-options",
                "Framing by other sites is restricted.",
                "No X-Frame-Options (a CSP frame-ancestors directive can cover this instead).",
            ),
            finding(
                "Cross-Origin-Opener-Policy", "cross-origin-opener-policy",
                "Cross-origin window references are restricted.",
                "No COOP — cross-origin windows can keep a reference to this page.",
            ),
        )
    }

    /** Server banners that disclose technology versions. */
    fun disclosureHeaders(rawHeaders: Map<String, String>): List<Pair<String, String>> {
        val headers = rawHeaders.entries.associate { it.key.lowercase() to it.value }
        return listOf("server", "x-powered-by")
            .mapNotNull { key -> headers[key]?.let { key to it } }
    }

    // ------------------------------------------------------------ regex

    /** All match ranges of [pattern] in [text]; throws PatternSyntaxException on a bad pattern. */
    fun regexMatches(pattern: String, text: String): List<IntRange> =
        Regex(pattern).findAll(text).map { it.range }.toList()

    // -------------------------------------------------------------- URL

    /** Splits a URL into labelled parts; query parameters listed individually. */
    fun parseUrl(url: String): List<Pair<String, String>> {
        val uri = URI(url.trim())
        val parts = mutableListOf<Pair<String, String>>()
        uri.scheme?.let { parts.add("Scheme" to it) }
        uri.host?.let { parts.add("Host" to it) }
        if (uri.port >= 0) parts.add("Port" to uri.port.toString())
        uri.userInfo?.let { parts.add("User info" to it) }
        parts.add("Path" to (uri.rawPath?.ifEmpty { "/" } ?: "/"))
        uri.rawQuery?.split("&")?.forEach { pair ->
            val idx = pair.indexOf('=')
            if (idx >= 0) {
                parts.add("Query: ${pair.substring(0, idx)}" to pair.substring(idx + 1))
            } else {
                parts.add("Query: $pair" to "(no value)")
            }
        }
        uri.fragment?.let { parts.add("Fragment" to it) }
        return parts
    }

    // ------------------------------------------------------------- HTTP

    data class HttpResult(
        val statusCode: Int,
        val statusMessage: String,
        val headers: Map<String, String>,
        val body: String,
        val bodyTruncated: Boolean,
        val elapsedMillis: Long,
    )

    private const val MAX_BODY_BYTES = 512 * 1024

    /**
     * One direct HTTP request from the app process. Redirects are NOT
     * followed — a tester should see the 301/302 itself. `Accept-Encoding:
     * identity` keeps bodies readable. Reading stops at 512 KB.
     */
    fun httpRequest(
        method: String,
        url: String,
        headers: Map<String, String>,
        body: String?,
    ): HttpResult {
        val started = System.currentTimeMillis()
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = method
        connection.instanceFollowRedirects = false
        connection.connectTimeout = 20000
        connection.readTimeout = 20000
        connection.setRequestProperty("Accept-Encoding", "identity")
        connection.setRequestProperty("User-Agent", "Sett-DevTools/1.0 (direct fetch, no cookies)")
        headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
        if (body != null && method != "GET" && method != "HEAD") {
            connection.doOutput = true
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        }
        val status = connection.responseCode
        val responseHeaders = LinkedHashMap<String, String>()
        // Header field 0 is the status line; real headers start at 1.
        var index = 1
        while (true) {
            val value = connection.getHeaderField(index) ?: break
            val key = connection.getHeaderFieldKey(index)
            if (key != null) responseHeaders[key] = value
            index++
        }
        val stream = if (status in 200..299 || connection.errorStream == null) {
            runCatching { connection.inputStream }.getOrNull()
        } else {
            connection.errorStream
        }
        var text = ""
        var truncated = false
        stream?.use { input ->
            val buffer = ByteArray(MAX_BODY_BYTES + 1)
            var filled = 0
            while (filled < buffer.size) {
                val n = input.read(buffer, filled, buffer.size - filled)
                if (n < 0) break
                filled += n
            }
            if (filled > MAX_BODY_BYTES) {
                truncated = true
                text = String(buffer, 0, MAX_BODY_BYTES, Charsets.UTF_8)
            } else {
                text = String(buffer, 0, filled, Charsets.UTF_8)
            }
        }
        return HttpResult(
            statusCode = status,
            statusMessage = connection.responseMessage ?: "",
            headers = responseHeaders,
            body = text,
            bodyTruncated = truncated,
            elapsedMillis = System.currentTimeMillis() - started,
        )
    }

    /** Fetches `scheme://host[:port]<path>` for robots.txt / sitemap viewers. */
    fun siteFileUrl(siteUrl: String, path: String): String {
        val uri = URI(siteUrl.trim())
        val scheme = uri.scheme ?: "https"
        val host = uri.host ?: throw IllegalArgumentException("Enter a site URL with a host")
        val port = if (uri.port >= 0) ":${uri.port}" else ""
        return "$scheme://$host$port$path"
    }
}
