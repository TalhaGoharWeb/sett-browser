package app.privatex.browser

import app.privatex.browser.library.ReadingListStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Redesign Phase 5: reading list store. */
class ReadingListTest {

    @Test
    fun addDedupesAndOrdersNewestFirst() {
        val file = File.createTempFile("sett-reading", ".json")
        val store = ReadingListStore(file)
        store.add("One", "https://one.example", now = 100)
        store.add("Two", "https://two.example", now = 200)
        store.add("One again", "https://one.example", now = 300)
        val items = ReadingListStore(file).items()
        assertEquals(2, items.size)
        assertEquals("One again", items[0].title)
        assertEquals(300L, items[0].savedAt)
        assertTrue(store.contains("https://two.example"))
        store.remove(items[0].id)
        assertFalse(ReadingListStore(file).contains("https://one.example"))
        assertTrue(runCatching { store.add("x", "sett://home") }.isFailure)
        file.delete()
    }
}
