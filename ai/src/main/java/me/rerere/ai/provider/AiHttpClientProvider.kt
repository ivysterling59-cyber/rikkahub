package me.rerere.ai.provider

import kotlinx.serialization.Serializable
import okhttp3.OkHttpClient

/** Supplies the AI-only OkHttpClient to use for the next HTTP request. */
interface AiHttpClientProvider {
    fun clientForRequest(): OkHttpClient

    fun streamReaderMode(): AiStreamReaderMode = AiStreamReaderMode.EVENT_SOURCE
}

@Serializable
enum class AiStreamReaderMode(val displayName: String) {
    EVENT_SOURCE("OkHttp EventSource"),
    MANUAL_SSE("手工 SSE Reader"),
}

internal fun fixedAiHttpClientProvider(client: OkHttpClient): AiHttpClientProvider =
    object : AiHttpClientProvider {
        override fun clientForRequest(): OkHttpClient = client
    }
