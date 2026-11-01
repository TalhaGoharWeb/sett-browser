package app.privatex.browser

import app.privatex.browser.search.SearxngInstances
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** White-label search (v0.25.0): searx.space registry parsing. */
class SearxngInstancesTest {

    private fun entry(
        status: Int = 200,
        network: String = "normal",
        success: Double = 100.0,
        median: Double = 1.0,
        analytics: Boolean = false,
    ): String =
        """{"network_type":"$network","analytics":$analytics,""" +
            """"http":{"status_code":$status},""" +
            """"timing":{"search":{"success_percentage":$success,""" +
            """"all":{"median":$median}}}}"""

    private val fixture = """{"instances":{
        "https://fast.example.org/": ${entry(median = 0.4)},
        "https://slow.example.org/": ${entry(median = 2.5)},
        "https://tracked.example.org/": ${entry(median = 0.1, analytics = true)},
        "http://plain.example.org/": ${entry(median = 0.2)},
        "https://onion.example.org/": ${entry(network = "tor")},
        "https://down.example.org/": ${entry(status = 503)},
        "https://flaky.example.org/": ${entry(success = 92.0)}
    }}"""

    @Test
    fun filtersAndOrders() {
        val list = SearxngInstances.parse(fixture)
        assertEquals(
            listOf("fast.example.org", "slow.example.org", "tracked.example.org"),
            list.map { it.host },
        )
        // http, tor, down and flaky are gone; the analytics instance
        // sorts last despite being fastest (declared-analytics rule).
        assertEquals("https://fast.example.org", list.first().baseUrl)
    }

    @Test
    fun garbageGivesEmpty() {
        assertTrue(SearxngInstances.parse("not json").isEmpty())
        assertTrue(SearxngInstances.parse("{}").isEmpty())
    }

    @Test
    fun hostExtraction() {
        assertEquals("search.example.org", SearxngInstances.hostOf("https://search.example.org/"))
        assertEquals("x.org:8080".substringBefore(':'), SearxngInstances.hostOf("https://x.org:8080/s"))
    }
}
