package me.rerere.rikkahub.data.ai

import me.rerere.ai.provider.AiStreamReaderMode
import okhttp3.OkHttpClient
import okhttp3.Protocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test
import java.util.concurrent.TimeUnit

class AiHttpClientFactoryTest {
    private var mode = AiNetworkMode.DEFAULT
    private val baseClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.MINUTES)
        .writeTimeout(120, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(0, TimeUnit.MILLISECONDS)
        .protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
        .build()
    private val factory = AiHttpClientFactory(baseClient, { mode }, logConfiguration = false)

    @Test
    fun `default mode preserves ALPN and shared pool`() {
        val first = factory.clientForRequest()
        val second = factory.clientForRequest()

        assertSame(first, second)
        assertSame(baseClient.connectionPool, first.connectionPool)
        assertEquals(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1), first.protocols)
        assertTimeoutsUnchanged(first)
    }

    @Test
    fun `http1 mode applies only HTTP 1 1 and keeps shared pool`() {
        mode = AiNetworkMode.HTTP1_ONLY

        val client = factory.clientForRequest()

        assertSame(baseClient.connectionPool, client.connectionPool)
        assertEquals(listOf(Protocol.HTTP_1_1), client.protocols)
        assertTimeoutsUnchanged(client)
    }

    @Test
    fun `fresh mode creates a new client and connection pool for every request`() {
        mode = AiNetworkMode.FRESH_CONNECTION

        val first = factory.clientForRequest()
        val second = factory.clientForRequest()

        assertNotSame(first, second)
        assertNotSame(first.connectionPool, second.connectionPool)
        assertNotSame(baseClient.connectionPool, first.connectionPool)
        assertEquals(baseClient.protocols, first.protocols)
        assertEquals(baseClient.protocols, second.protocols)
        assertTimeoutsUnchanged(first)
        assertTimeoutsUnchanged(second)
    }

    @Test
    fun `mode switch affects the next requested AI client`() {
        val defaultClient = factory.clientForRequest()
        mode = AiNetworkMode.HTTP1_ONLY
        val http1Client = factory.clientForRequest()
        mode = AiNetworkMode.FRESH_CONNECTION
        val freshClient = factory.clientForRequest()

        assertEquals(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1), defaultClient.protocols)
        assertEquals(listOf(Protocol.HTTP_1_1), http1Client.protocols)
        assertEquals(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1), freshClient.protocols)
        assertNotSame(defaultClient, http1Client)
        assertNotSame(http1Client, freshClient)
    }

    @Test
    fun `stream reader mode is selected independently of network mode`() {
        var readerMode = AiStreamReaderMode.EVENT_SOURCE
        val readerFactory = AiHttpClientFactory(
            baseClient = baseClient,
            modeProvider = { mode },
            logConfiguration = false,
            streamReaderModeProvider = { readerMode },
        )

        assertEquals(AiStreamReaderMode.EVENT_SOURCE, readerFactory.streamReaderMode())
        readerMode = AiStreamReaderMode.MANUAL_SSE
        assertEquals(AiStreamReaderMode.MANUAL_SSE, readerFactory.streamReaderMode())
        assertEquals(baseClient.protocols, readerFactory.clientForRequest().protocols)
    }

    private fun assertTimeoutsUnchanged(client: OkHttpClient) {
        assertEquals(20_000, client.connectTimeoutMillis)
        assertEquals(600_000, client.readTimeoutMillis)
        assertEquals(120_000, client.writeTimeoutMillis)
        assertEquals(0, client.callTimeoutMillis)
        assertEquals(0, client.pingIntervalMillis)
        assertEquals(baseClient.retryOnConnectionFailure, client.retryOnConnectionFailure)
    }
}
