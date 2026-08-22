package me.rerere.ai.provider

import okhttp3.OkHttpClient

/** Supplies the AI-only OkHttpClient to use for the next HTTP request. */
fun interface AiHttpClientProvider {
    fun clientForRequest(): OkHttpClient
}

internal fun fixedAiHttpClientProvider(client: OkHttpClient): AiHttpClientProvider =
    AiHttpClientProvider { client }
