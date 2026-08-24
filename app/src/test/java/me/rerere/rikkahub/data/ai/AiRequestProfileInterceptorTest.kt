package me.rerere.rikkahub.data.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AiRequestProfileInterceptorTest {
    @Test
    fun `minimal mode preserves complete messages and strips optional top-level fields`() {
        val source = Json.parseToJsonElement(
            """
            {
              "model": "example-model",
              "messages": [
                {"role": "system", "content": "role-play system prompt"},
                {"role": "user", "content": "private user content"}
              ],
              "stream": true,
              "stream_options": {"include_usage": true},
              "temperature": 0.8,
              "tools": [{"type": "function"}],
              "reasoning_effort": "high"
            }
            """.trimIndent(),
        ).jsonObject

        val minimal = source.toMinimalOpenAiCompatibleBody()

        assertEquals(setOf("model", "messages", "stream"), minimal!!.keys)
        assertEquals(source["model"], minimal["model"])
        assertEquals(source["messages"], minimal["messages"])
        assertEquals(true, (minimal["stream"] as JsonPrimitive).content.toBoolean())
        val messages = minimal["messages"] as JsonArray
        assertEquals("role-play system prompt", messages[0].jsonObject["content"]?.let { (it as JsonPrimitive).content })
        assertTrue("stream_options" !in minimal)
        assertTrue("temperature" !in minimal)
        assertTrue("tools" !in minimal)
        assertTrue("reasoning_effort" !in minimal)
    }

    @Test
    fun `minimal mode rejects non-streaming or incomplete request bodies`() {
        val nonStreaming = Json.parseToJsonElement(
            """{"model":"m","messages":[],"stream":false}""",
        ).jsonObject
        val missingMessages = JsonObject(
            mapOf("model" to JsonPrimitive("m"), "stream" to JsonPrimitive(true)),
        )

        assertNull(nonStreaming.toMinimalOpenAiCompatibleBody())
        assertNull(missingMessages.toMinimalOpenAiCompatibleBody())
    }
}
