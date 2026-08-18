package me.rerere.rikkahub.data.ai

import me.rerere.common.android.LogEntry
import me.rerere.common.android.Logging
import okhttp3.Interceptor
import okhttp3.Response

class RequestLoggingInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        if (!Logging.isRequestLoggingEnabled()) {
            return chain.proceed(chain.request())
        }

        val request = chain.request()
        val startTime = System.currentTimeMillis()

        val requestHeaders = request.headers.toSafeMap()
        // Request bodies can contain prompts and complete conversation history.
        val requestBody: String? = null
        val safeUrl = request.url.newBuilder().query(null).fragment(null).build().toString()

        val response: Response
        var error: String? = null

        try {
            response = chain.proceed(request)
        } catch (e: Exception) {
            error = e.message
            Logging.logRequest(
                LogEntry.RequestLog(
                    tag = "HTTP",
                    url = safeUrl,
                    method = request.method,
                    requestHeaders = requestHeaders,
                    requestBody = requestBody,
                    error = error
                )
            )
            throw e
        }

        val durationMs = System.currentTimeMillis() - startTime
        val responseHeaders = response.headers.toSafeMap()

        Logging.logRequest(
            LogEntry.RequestLog(
                tag = "HTTP",
                url = safeUrl,
                method = request.method,
                requestHeaders = requestHeaders,
                requestBody = requestBody,
                responseCode = response.code,
                responseHeaders = responseHeaders,
                durationMs = durationMs,
                error = error
            )
        )

        return response
    }

    private fun okhttp3.Headers.toSafeMap(): Map<String, String> {
        return names().associateWith { name ->
            if (name.lowercase() in SENSITIVE_HEADERS) "██" else get(name).orEmpty()
        }
    }

    private companion object {
        val SENSITIVE_HEADERS = setOf(
            "authorization",
            "proxy-authorization",
            "x-api-key",
            "x-goog-api-key",
        )
    }
}
