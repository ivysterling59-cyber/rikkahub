package me.rerere.rikkahub.data.ai

import android.util.Log
import me.rerere.common.http.aiRequestTrace
import me.rerere.common.http.rootCauseForAiDiagnostics
import me.rerere.common.http.safeForAiDiagnostics
import okhttp3.Call
import okhttp3.Connection
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong

private const val TAG = "AiHttpDiag"

/** Adds request diagnostics to the AI client without changing protocols or timeouts. */
internal fun OkHttpClient.newDiagnosedAiClient(logConfiguration: Boolean = true): OkHttpClient {
    if (logConfiguration) {
        Log.i(
            TAG,
            "event=AI_CLIENT_CONFIG connectTimeoutMs=$connectTimeoutMillis " +
                "readTimeoutMs=$readTimeoutMillis writeTimeoutMs=$writeTimeoutMillis " +
                "callTimeoutMs=$callTimeoutMillis pingIntervalMs=$pingIntervalMillis " +
                "protocols=${protocols.joinToString { it.name }} " +
                "retryOnConnectionFailure=$retryOnConnectionFailure " +
                "maxRequests=${dispatcher.maxRequests} maxRequestsPerHost=${dispatcher.maxRequestsPerHost}",
        )
    }
    return newBuilder()
        .eventListenerFactory { AiHttpEventListener() }
        .build()
}

internal data class AiRequestDescription(
    val provider: String,
    val host: String,
    val pathType: String,
    val streaming: Boolean,
)

internal fun Request.describeAiRequest(): AiRequestDescription {
    val path = url.encodedPath
    val pathType = when {
        path.contains("generateContent", ignoreCase = true) -> "generate-content"
        path.endsWith("/chat/completions", ignoreCase = true) -> "chat-completions"
        path.endsWith("/responses", ignoreCase = true) -> "responses"
        path.endsWith("/messages", ignoreCase = true) -> "messages"
        path.endsWith("/embeddings", ignoreCase = true) -> "embeddings"
        path.contains("/images/", ignoreCase = true) -> "images"
        path.contains("/models", ignoreCase = true) -> "models"
        else -> "other-ai"
    }
    val provider = when {
        path.contains("generateContent", ignoreCase = true) -> "google"
        path.endsWith("/messages", ignoreCase = true) -> "claude"
        pathType in setOf("chat-completions", "responses", "embeddings", "images") -> "openai-compatible"
        else -> "ai"
    }
    return AiRequestDescription(
        provider = provider,
        host = url.host,
        pathType = pathType,
        streaming = header("Accept")?.contains("text/event-stream", ignoreCase = true) == true ||
            path.contains(":streamGenerateContent", ignoreCase = true),
    )
}

private class AiHttpEventListener : EventListener() {
    private val fallbackRequestId = "http-${fallbackSequence.incrementAndGet()}"
    private val startedAtNanos = System.nanoTime()
    private var protocol: Protocol? = null
    private var statusCode: Int? = null
    private var normalEof = false

    override fun callStart(call: Call) = log(call, "REQUEST_START")

    override fun connectionAcquired(call: Call, connection: Connection) {
        protocol = connection.protocol()
        log(call, "CONNECTION_ACQUIRED")
    }

    override fun requestHeadersStart(call: Call) = log(call, "REQUEST_HEADERS_START")

    override fun responseHeadersEnd(call: Call, response: Response) {
        protocol = response.protocol
        statusCode = response.code
        log(call, "HEADERS_RECEIVED")
    }

    override fun responseBodyStart(call: Call) = log(call, "BODY_READ_START")

    override fun responseBodyEnd(call: Call, byteCount: Long) {
        normalEof = true
        log(call, "BODY_EOF", "normalEof=true bytes=$byteCount")
    }

    override fun canceled(call: Call) {
        val cancellation = call.request().aiRequestTrace()?.coroutineCancellation()
        log(
            call,
            "LOCAL_CALL_CANCEL",
            "normalEof=$normalEof coroutineCancellation=${cancellation?.javaClass?.name ?: "none"} " +
                "cancellationMessage=${cancellation?.message.safeForAiDiagnostics()}",
            Throwable("LOCAL CALL.CANCEL() invocation stack"),
        )
    }

    override fun callEnd(call: Call) = log(
        call,
        "REQUEST_SUCCESS",
        "normalEof=$normalEof callCancelled=${call.isCanceled()}",
    )

    override fun callFailed(call: Call, ioe: IOException) {
        val trace = call.request().aiRequestTrace()
        val cancelled = call.isCanceled() || trace?.coroutineCancellation() != null
        log(
            call,
            if (cancelled) "REQUEST_CANCELLED" else "REMOTE_CONNECTION_FAILURE",
            "normalEof=$normalEof callCancelled=${call.isCanceled()}",
            ioe,
        )
    }

    private fun log(call: Call, event: String, extra: String = "", error: Throwable? = null) {
        val request = call.request()
        val trace = request.aiRequestTrace()
        val description = request.describeAiRequest()
        val durationMs = trace?.durationMillis()
            ?: (System.nanoTime() - startedAtNanos) / 1_000_000
        val root = error?.rootCauseForAiDiagnostics()
        val message = buildString {
            append("event=").append(event)
            append(" requestId=").append(trace?.requestId ?: fallbackRequestId)
            append(" provider=").append(trace?.provider ?: description.provider)
            append(" host=").append(description.host)
            append(" pathType=").append(description.pathType)
            append(" stream=").append(trace?.streaming ?: description.streaming)
            append(" protocol=").append(protocol?.name ?: "UNKNOWN")
            append(" status=").append(statusCode ?: "none")
            append(" durationMs=").append(durationMs)
            append(" closeReason=").append(trace?.closeReason() ?: "not_traced")
            append(" thread=").append(Thread.currentThread().name)
            if (error != null) {
                append(" exceptionClass=").append(error.javaClass.name)
                append(" exceptionMessage=").append(error.message.safeForAiDiagnostics())
                append(" rootCause=").append(root?.javaClass?.name ?: "none")
                append(" rootMessage=").append(root?.message.safeForAiDiagnostics())
            }
            if (extra.isNotBlank()) append(' ').append(extra)
        }
        if (error == null) Log.i(TAG, message) else Log.w(TAG, message, error)
    }

    private companion object {
        val fallbackSequence = AtomicLong(0)
    }
}
