package me.rerere.ai.util

import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Test

class ManualSseParserTest {
    @Test
    fun `parses standard fields multiline data done and retry without exposing content`() {
        val events = mutableListOf<Triple<String?, String?, String>>()
        val retries = mutableListOf<Long>()
        val source = Buffer().writeUtf8(
            ": comment\n" +
                "id: event-1\n" +
                "event: message\n" +
                "data: first line\n" +
                "data: second line\n\n" +
                "retry: 1500\n" +
                "data: [DONE]\n\n",
        )

        ManualSseParser(callback(events, retries)).process(source)

        assertEquals(
            listOf(
                Triple("event-1", "message", "first line\nsecond line"),
                Triple("event-1", null, "[DONE]"),
            ),
            events,
        )
        assertEquals(listOf(1500L), retries)
    }

    @Test
    fun `dispatches final data at eof and ignores id containing nul`() {
        val events = mutableListOf<Triple<String?, String?, String>>()
        val source = Buffer().writeUtf8("id: valid\ndata: one\n\nid: bad\u0000id\ndata: two")

        ManualSseParser(callback(events, mutableListOf())).process(source)

        assertEquals(
            listOf(
                Triple("valid", null, "one"),
                Triple("valid", null, "two"),
            ),
            events,
        )
    }

    private fun callback(
        events: MutableList<Triple<String?, String?, String>>,
        retries: MutableList<Long>,
    ) = object : ManualSseParser.Callback {
        override fun onEvent(id: String?, type: String?, data: String) {
            events += Triple(id, type, data)
        }

        override fun onRetry(timeMs: Long) {
            retries += timeMs
        }
    }
}
