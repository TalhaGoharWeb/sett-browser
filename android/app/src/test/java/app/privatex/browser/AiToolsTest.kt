package app.privatex.browser

import app.privatex.browser.tools.AiTools
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Phase 10 AI Workspace logic: text extraction, offline summary, provider payloads. */
class AiToolsTest {

    @Test
    fun htmlToTextStripsMarkupAndScripts() {
        val html = """
            <html><head><style>body{color:red}</style><script>track()</script></head>
            <body><h1>Title</h1><p>First &amp; foremost.</p><p>Second&nbsp;para.</p></body></html>
        """.trimIndent()
        val text = AiTools.htmlToText(html)
        assertTrue(text.contains("Title"))
        assertTrue(text.contains("First & foremost."))
        assertTrue(text.contains("Second para."))
        assertFalse(text.contains("track()"))
        assertFalse(text.contains("color:red"))
    }

    @Test
    fun extractiveSummaryPicksTopicalSentences() {
        val text = """
            The cat sat quietly on the warm windowsill all afternoon long.
            Quantum entanglement allows particles to share states across vast distances in experiments.
            She bought milk and bread from the small shop near her house.
            Entanglement experiments have repeatedly confirmed quantum correlations between distant particles.
            The weather tomorrow will be sunny with a light breeze expected.
            Physicists use entanglement to test the foundations of quantum mechanics itself today.
        """.trimIndent()
        val summary = AiTools.extractiveSummarize(text, maxSentences = 2)
        assertEquals(2, summary.size)
        // Both picks must come from the quantum cluster (highest shared-word density),
        // and stay in original document order.
        assertTrue(summary.all { it.contains("ntanglement") || it.contains("quantum", ignoreCase = true) })
        assertTrue(summary[0].startsWith("Quantum") || summary[0].startsWith("Entanglement"))
    }

    @Test
    fun promptsCarryTheTaskAndContent() {
        val summarize = AiTools.buildPrompt(AiTools.Task.SUMMARIZE, "PAGE", "")
        assertTrue(summarize.contains("Summarize"))
        assertTrue(summarize.endsWith("PAGE"))
        val ask = AiTools.buildPrompt(AiTools.Task.ASK, "PAGE", "What is X?")
        assertTrue(ask.contains("What is X?"))
        val translate = AiTools.buildPrompt(AiTools.Task.TRANSLATE, "PAGE", "Arabic")
        assertTrue(translate.contains("into Arabic"))
    }

    @Test
    fun chatPayloadRoundTrips() {
        val body = AiTools.buildChatRequestJson("model-x", "hello")
        assertTrue(body.contains("\"model\":\"model-x\""))
        assertTrue(body.contains("\"content\":\"hello\""))
        val reply = AiTools.parseChatResponse(
            """{"choices":[{"message":{"role":"assistant","content":"Hi there"}}]}""",
        )
        assertEquals("Hi there", reply)
        var error: String? = null
        try {
            AiTools.parseChatResponse("""{"error":{"message":"bad key"}}""")
        } catch (e: IllegalStateException) {
            error = e.message
        }
        assertTrue(error!!.contains("bad key"))
    }
}
