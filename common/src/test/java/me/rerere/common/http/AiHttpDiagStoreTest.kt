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
    fun `network experiment export reports actual runtime mode and outcome`() {
        append(
            "request-a",
            "REQUEST_START",
            "stream=true networkMode=HTTP1_ONLY clientId=ai-client-2 " +
                "connectionPoolId=shared-pool protocol=UNKNOWN statusCode=none durationMs=0",
        )
        append(
            "request-a",
            "HEADERS_RECEIVED",
            "networkMode=HTTP1_ONLY protocol=HTTP_1_1 statusCode=200 durationMs=321",
        )
        append(
            "request-a",
            "REMOTE_CONNECTION_FAILURE",
            "networkMode=HTTP1_ONLY protocol=HTTP_1_1 statusCode=200 durationMs=1234 " +
                "normalEof=false callCancelled=false exceptionClass=java.io.EOFException " +
                "exceptionMessage=unexpected end of stream rootCause=java.io.EOFException",
        )

        val text = AiHttpDiagStore.latestNetworkExperimentText(
            appVersion = "2.4.10-network-debug3",
            selectedMode = "DEFAULT",
        )

        assertNotNull(text)
        assertTrue(text!!.contains("appVersion=2.4.10-network-debug3"))
        assertTrue(text.contains("networkMode=HTTP1_ONLY"))
        assertTrue(text.contains("protocol=HTTP_1_1"))
        assertTrue(text.contains("statusCode=200"))
        assertTrue(text.contains("durationMs=1234"))
        assertTrue(text.contains("normalEof=false"))
        assertTrue(text.contains("callCancelled=false"))
        assertTrue(text.contains("exceptionClass=java.io.EOFException"))
        assertTrue(text.contains("exceptionMessage=unexpected end of stream"))
        assertTrue(text.contains("finalEvent=REMOTE_CONNECTION_FAILURE"))
    }

    @Test
    fun `network experiment export falls back to selected mode before runtime metadata exists`() {
        append("request-a", "REQUEST_START", "stream=false")

        val text = AiHttpDiagStore.latestNetworkExperimentText(
            appVersion = "test",
            selectedMode = "FRESH_CONNECTION",
        )

        assertTrue(text!!.contains("networkMode=FRESH_CONNECTION"))
        assertTrue(text.contains("finalEvent=REQUEST_START"))
    }

    @Test
    fun `failed profile exports only allowlisted structure and headers`() {
        append(
            "request-a",
            "REQUEST_PROFILE",
            "networkMode=DEFAULT requestMode=NORMAL stream=true model=test-model " +
                "fields=[model,messages,stream,stream_options,temperature,tools] " +
                "streamOptionsPresent=true includeUsage=true temperaturePresent=true toolsPresent=true " +
                "request.Content-Type=application/json request.Accept=text/event-stream " +
                "request.Accept-Encoding=gzip request.User-Agent=RikkaHub-Android/test " +
                "request.Connection=null request.Cache-Control=no-cache request.TE=trailers",
        )
        append(
            "request-a",
            "RESPONSE_PROFILE",
            "protocol=h2 statusCode=200 response.Content-Type=text/event-stream " +
                "response.Content-Encoding=null response.Transfer-Encoding=null " +
                "response.Content-Length=null response.Server=cloudflare response.Via=null " +
                "response.CF-Ray=example-ray response.CF-Cache-Status=DYNAMIC",
        )
        append(
            "request-a",
            "REMOTE_CONNECTION_FAILURE",
            "durationMs=35123 eventCount=42 lastEventElapsedMs=30000 timeSinceLastEventMs=5123 " +
                "exceptionClass=java.io.EOFException exceptionMessage=unexpected_end " +
                "rootCause=java.io.EOFException Authorization=Bearer_secret " +
                "messages=private_prompt Cookie=session_secret",
        )

        val text = AiHttpDiagStore.latestFailedRequestProfileText()!!

        assertTrue(text.contains("model=test-model"))
        assertTrue(text.contains("Accept-Encoding=gzip"))
        assertTrue(text.contains("stream_options"))
        assertTrue(text.contains("Server=cloudflare"))
        assertTrue(text.contains("CF-Ray=example-ray"))
        assertTrue(text.contains("timeSinceLastEventMs=5123"))
        assertFalse(text.contains("Bearer_secret"))
        assertFalse(text.contains("private_prompt"))
        assertFalse(text.contains("session_secret"))
        assertFalse(text.contains("Authorization="))
        assertFalse(text.contains("Cookie="))
    }

    @Test
    fun `failure statistics deduplicate requests and calculate time buckets`() {
        append("under-30", "REQUEST_FAILED", "durationMs=20000 protocol=h2 networkMode=DEFAULT exceptionClass=E1")
        append("thirties", "REQUEST_FAILED", "durationMs=35000 protocol=h2 networkMode=DEFAULT exceptionClass=E2")
        append("thirties", "REMOTE_CONNECTION_FAILURE", "durationMs=35000 protocol=h2 networkMode=DEFAULT exceptionClass=E2")
        append("forties", "REQUEST_FAILED", "durationMs=50000 protocol=http1 networkMode=HTTP1_ONLY exceptionClass=E3")
        append(
            "over-60",
            "REQUEST_FAILED",
            "durationMs=65000 protocol=h2 networkMode=FRESH_CONNECTION exceptionClass=E4 " +
                "eventCount=9 timeSinceLastEventMs=7000",
        )

        val text = AiHttpDiagStore.failureStatisticsText()

        assertTrue(text.contains("最近失败次数：4"))
        assertTrue(text.contains("平均失败时间：42.5s"))
        assertTrue(text.contains("中位数：42.5s"))
        assertTrue(text.contains("<30s：1 次"))
        assertTrue(text.contains("30-40s：1 次"))
        assertTrue(text.contains("40-60s：1 次"))
        assertTrue(text.contains(">=60s：1 次"))
        assertTrue(text.contains("durationMs=65000"))
        assertTrue(text.contains("eventCount=9"))
    }

    @Test
    fun `thirty second pattern shows recent durations and requested buckets`() {
        append("a", "REQUEST_FAILED", "durationMs=32630 protocol=h2 networkMode=DEFAULT exceptionClass=E1")
        append("b", "REQUEST_FAILED", "durationMs=34140 protocol=h2 networkMode=DEFAULT exceptionClass=E2")
        append("c", "REQUEST_FAILED", "durationMs=35510 protocol=http1 networkMode=HTTP1_ONLY exceptionClass=E3")
        append("d", "REQUEST_FAILED", "durationMs=38000 protocol=h2 networkMode=DEFAULT exceptionClass=E4")
        append("e", "REQUEST_FAILED", "durationMs=41000 protocol=h2 networkMode=DEFAULT exceptionClass=E5")

        val text = AiHttpDiagStore.thirtySecondPatternText()

        assertTrue(text.contains("32.6s"))
        assertTrue(text.contains("34.1s"))
        assertTrue(text.contains("35.5s"))
        assertTrue(text.contains("30-33s：1 次"))
        assertTrue(text.contains("33-36s：2 次"))
        assertTrue(text.contains("36-40s：1 次"))
        assertTrue(text.contains("40s+：1 次"))
        assertTrue(text.contains("最小：32.6s"))
        assertTrue(text.contains("最大：41.0s"))
    }

    @Test
    fun `diagnostic messages redact URL queries and credential parameters`() {
        val message = "failed https://api.example.com/v1/chat?key=secret&mode=sse api_key=another-secret"

        val sanitized = message.safeForAiDiagnostics()

        assertFalse(sanitized.contains("secret"))
        assertTrue(sanitized.contains("https://api.example.com/v1/chat?<redacted>"))
        assertTrue(sanitized.contains("api_key=<redacted>"))
    }

    @Test
    fun `stored stack traces redact exception secrets`() {
        AiHttpDiagStore.append(
            level = AiHttpDiagLevel.WARN,
            event = "REQUEST_FAILED",
            requestId = "request-a",
            throwable = IllegalStateException("failed https://api.example.com/path?key=secret"),
        )

        val stackTrace = AiHttpDiagStore.snapshot().single().stackTrace.orEmpty()
        assertFalse(stackTrace.contains("key=secret"))
        assertTrue(stackTrace.contains("https://api.example.com/path?<redacted>"))
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
