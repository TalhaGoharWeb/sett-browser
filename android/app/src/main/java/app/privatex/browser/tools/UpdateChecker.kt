package app.privatex.browser.tools

import org.json.JSONObject

/**
 * Update checking (Phase 12, closing audit finding S1 as far as zero
 * spend allows): Sett compares its own version against the project's
 * GitHub Releases feed — the same signed channel every release ships
 * through. Detection only: the user taps through to the release page
 * and installs manually; there is no silent self-update, and the
 * release APKs remain signed with the same project key, so a
 * mismatched download cannot install over Sett.
 */
object UpdateChecker {

    const val LATEST_RELEASE_API =
        "https://api.github.com/repos/TalhaGoharWeb/sett-browser/releases/latest"
    const val RELEASES_PAGE = "https://github.com/TalhaGoharWeb/sett-browser/releases"

    /** "v0.15.0" / "0.15.0" → [0, 15, 0]. Unparsable → null. */
    fun parseVersion(raw: String): List<Int>? {
        val cleaned = raw.trim().removePrefix("v").removePrefix("V")
        if (cleaned.isEmpty()) return null
        val parts = cleaned.split(".")
        if (parts.size !in 1..4) return null
        return parts.map { part -> part.toIntOrNull() ?: return null }
    }

    /** True only when [latest] is strictly newer than [current]. */
    fun isNewer(latest: String, current: String): Boolean {
        val l = parseVersion(latest) ?: return false
        val c = parseVersion(current) ?: return false
        val size = maxOf(l.size, c.size)
        for (i in 0 until size) {
            val li = l.getOrElse(i) { 0 }
            val ci = c.getOrElse(i) { 0 }
            if (li != ci) return li > ci
        }
        return false
    }

    data class LatestRelease(val tag: String, val pageUrl: String, val name: String)

    /** Parses the GitHub "latest release" payload; null when unusable. */
    fun parseLatestRelease(json: String): LatestRelease? {
        return runCatching {
            val root = JSONObject(json)
            val tag = root.optString("tag_name")
            val page = root.optString("html_url")
            if (tag.isBlank() || page.isBlank()) {
                null
            } else {
                LatestRelease(tag, page, root.optString("name").ifBlank { tag })
            }
        }.getOrNull()
    }
}
