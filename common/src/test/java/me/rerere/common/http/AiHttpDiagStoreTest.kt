package me.rerere.common.http

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AiHttpDiagStoreTest {
    @Before
    fun setUp() = AiHttpDiagStore.clear()

    @After
    fun tearDown() = AiHttpDiagStore.clear()

    @Test
    fun `ring buffer keeps only newest entries`() {
        repeat(AiHttpDiagStore.MAX_ENTRIES + 25) { index ->
            AiHttpDiagStore.append(AiHttpDiagLevel.INFO, "EVENT_$index")
        }

        val entries = AiHttpDiagStore.snapshot()
        assertEquals(AiHttpDiagStore.MAX_ENTRIES, entries.size)
        assertEquals("EVENT_25", entries.first().event)
        assertEquals("EVENT_${AiHttpDiagStore.MAX_ENTRIES + 24}", entries.last().event)
    }

    @Test
    fun `latest failure export contains only matching request from start through terminal`() {
        append("request-a", "REQUEST_START", "stream=true")
        append("request-a", "HEADERS_RECEIVED", "protocol=h2 statusCode=200")
        append("request-b", "REQUEST_START", "stream=false")
        append("request-a", "BODY_PROGRESS", "bytes=100")
        append("request-a", "REMOTE_CONNECTION_FAILURE", "durationMs=1234")
        append("request-a", "STREAM_CLEANUP", "after-terminal")

        val text = AiHttpDiagStore.latestFailedRequestText()

        assertNotNull(text)
        assertTrue(text!!.contains("requestId=request-a"))
        assertTrue(text.contains("provider=openai-compatible"))
        assertTrue(text.contains("host=api.example.com"))
        assertTrue(text.contains("stream=true"))
        assertTrue(text.contains("REQUEST_START"))
        assertTrue(text.contains("REMOTE_CONNECTION_FAILURE"))
        assertFalse(text.contains("request-b"))
        assertFalse(text.contains("after-terminal"))
    }

    @Test
    fun `normal provider cleanup is not treated as latest failure`() {
        append("request-a", "REQUEST_START", "stream=true")
        append("request-a", "LOCAL_CALL_CANCEL", "closeReason=provider_completed")

        assertNull(AiHttpDiagStore.latestFailedRequestText())
    }

    @Test
    fun `diagnostic messages redact URL queries and credential parameters`() {
        val message = "failed https://api.example.com/v1/chat?key=secret&mode=sse api_key=another-secret"

        val sanitized = message.safeForAiDiagnostics()

        assertFalse(sanitized.contains("secret"))
        assertTrue(sanitized.contains("https://api.example.com/v1/chat?<redacted>"))
        assertTrue(sanitized.contains("api_key=<redacted>"))
    }

    private fun append(requestId: String, event: String, message: String) {
        AiHttpDiagStore.append(
            level = AiHttpDiagLevel.INFO,
            event = event,
            requestId = requestId,
            provider = "openai-compatible",
            host = "api.example.com",
            message = message,
        )
    }
}
