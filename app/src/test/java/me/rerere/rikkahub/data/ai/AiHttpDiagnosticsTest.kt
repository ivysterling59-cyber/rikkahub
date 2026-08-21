package me.rerere.rikkahub.data.ai

import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiHttpDiagnosticsTest {
    @Test
    fun `diagnostic client preserves normal protocol configuration`() {
        val parent = OkHttpClient()

        val client = parent.newDiagnosedAiClient(logConfiguration = false)

        assertEquals(parent.protocols, client.protocols)
        assertEquals(parent.callTimeoutMillis, client.callTimeoutMillis)
        assertEquals(parent.readTimeoutMillis, client.readTimeoutMillis)
        assertEquals(parent.dispatcher, client.dispatcher)
        assertEquals(parent.connectionPool, client.connectionPool)
    }

    @Test
    fun `request classification never includes query or request body`() {
        val google = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models/gemini:streamGenerateContent?alt=sse&key=secret")
            .build()
            .describeAiRequest()
        val openAi = Request.Builder()
            .url("https://api.openai.com/v1/chat/completions?secret=value")
            .header("Accept", "text/event-stream")
            .build()
            .describeAiRequest()

        assertEquals("google", google.provider)
        assertEquals("generate-content", google.pathType)
        assertTrue(google.streaming)
        assertFalse(google.toString().contains("secret"))

        assertEquals("openai-compatible", openAi.provider)
        assertEquals("chat-completions", openAi.pathType)
        assertTrue(openAi.streaming)
        assertFalse(openAi.toString().contains("secret"))
    }
}
