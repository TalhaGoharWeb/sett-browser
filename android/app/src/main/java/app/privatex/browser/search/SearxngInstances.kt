package app.privatex.browser.search

import org.json.JSONObject

/**
 * Public SearXNG instances (white-label search, v0.25.0).
 *
 * Sett runs no server (zero spend), so custom-results mode uses
 * either the user's own SearXNG instance or a public one. The
 * public list comes from searx.space — the SearXNG project's own
 * instance registry — whose data file records, per instance:
 * transport, network type, HTTP status and measured search
 * reliability. Selection here is a pure function of that data:
 * HTTPS only, normal (clearnet) network, HTTP 200, at least 99%
 * measured search success, fastest first. Nothing is hardcoded:
 * the list is fetched at runtime and this parser is unit-tested
 * against fixtures with the same schema.
 *
 * Honesty note: an instance can still decline the JSON format
 * (its operator controls that). The search screen treats a
 * non-JSON answer as a failed fetch from that instance, offers
 * the next one, and never substitutes another provider silently.
 */
object SearxngInstances {

    const val LIST_URL = "https://searx.space/data/instances.json"

    data class Instance(
        val baseUrl: String,
        val host: String,
        val searchSuccessPct: Double,
        val searchMedianSeconds: Double,
        val analytics: Boolean,
    )

    fun parse(json: String, limit: Int = 12): List<Instance> {
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return emptyList()
        val instances = root.optJSONObject("instances") ?: return emptyList()
        val out = mutableListOf<Instance>()
        val keys = instances.keys()
        while (keys.hasNext()) {
            val rawKey = keys.next()
            val base = rawKey.trimEnd('/')
            if (!base.startsWith("https://")) continue
            val o = instances.optJSONObject(rawKey) ?: continue
            if (o.optString("network_type") != "normal") continue
            if (o.optJSONObject("http")?.optInt("status_code") != 200) continue
            val search = o.optJSONObject("timing")?.optJSONObject("search") ?: continue
            val success = search.optDouble("success_percentage", 0.0)
            if (success < 99.0) continue
            val median = search.optJSONObject("all")?.optDouble("median", 999.0) ?: 999.0
            out += Instance(
                baseUrl = base,
                host = hostOf(base),
                searchSuccessPct = success,
                searchMedianSeconds = median,
                analytics = o.optBoolean("analytics", false),
            )
        }
        // Prefer instances that declare no analytics, then fastest.
        return out.sortedWith(compareBy({ it.analytics }, { it.searchMedianSeconds }))
            .take(limit)
    }

    fun hostOf(baseUrl: String): String =
        baseUrl.removePrefix("https://").removePrefix("http://")
            .substringBefore('/').substringBefore(':')
}
