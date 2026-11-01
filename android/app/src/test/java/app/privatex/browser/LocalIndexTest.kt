package app.privatex.browser

import app.privatex.browser.tools.LocalIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Phase 11 on-device library index. */
class LocalIndexTest {

    private fun doc(id: String, source: String, title: String, body: String, url: String = "") =
        LocalIndex.Doc(id, source, title, body, url)

    @Test
    fun andSemanticsAndTitleWeighting() {
        val index = LocalIndex.Index(
            listOf(
                doc("1", "Bookmark", "Maliki fiqh of prayer", "notes about salah", "https://a"),
                doc("2", "History", "Cooking rice", "fiqh fiqh fiqh mentioned thrice in body", "https://b"),
                doc("3", "History", "Prayer times", "unrelated", "https://c"),
            ),
        )
        // Both terms required: only doc 1 has fiqh AND prayer.
        val hits = index.search("fiqh prayer")
        assertEquals(listOf("1"), hits.map { it.doc.id })
        // Title hits outrank body-only repetition for a single term.
        val fiqh = index.search("fiqh")
        assertEquals("1", fiqh.first().doc.id)
        assertEquals(2, fiqh.size)
    }

    @Test
    fun arabicNormalizedMatching() {
        val index = LocalIndex.Index(
            listOf(doc("1", "Note", "الصلاة", "أحكام الصلاة والطهارة")),
        )
        // Query without diacritics and with ه for ة still matches.
        val hits = index.search("الصلاه")
        assertEquals(1, hits.size)
        assertTrue(index.search("احكام").isNotEmpty())
    }

    @Test
    fun emptyAndMissingQueries() {
        val index = LocalIndex.Index(listOf(doc("1", "Note", "hello world", "body")))
        assertTrue(index.search("").isEmpty())
        assertTrue(index.search("zzz").isEmpty())
        assertEquals(3, LocalIndex.tokenize("Hello, world! a bb").size)
    }
}
