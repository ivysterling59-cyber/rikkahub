package me.rerere.rikkahub.data.ai

import android.util.Log
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

/**
 * Creates the single AI-only client used by the HTTP/1.1 compatibility experiment.
 * The parent client's dispatcher, connection pool, timeouts and interceptors are retained.
 */
internal fun OkHttpClient.newHttp1AiClient(): OkHttpClient = newBuilder()
    .protocols(listOf(Protocol.HTTP_1_1))
    .eventListenerFactory { AiHttpEventListener() }
    .build()

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
    private val requestId = nextRequestId.incrementAndGet()
    private val startedAtMs = System.currentTimeMillis()
    private val startedAtNanos = System.nanoTime()
    private var protocol: Protocol? = null
    private var statusCode: Int? = null
    private var normalEof = false

    override fun callStart(call: Call) {
        log(call, "start", "startMs=$startedAtMs")
    }

    override fun connectionAcquired(call: Call, connection: Connection) {
        protocol = connection.protocol()
        log(call, "connection_acquired")
    }

    override fun responseHeadersEnd(call: Call, response: Response) {
        protocol = response.protocol
        statusCode = response.code
        log(call, "response_headers")
    }

    override fun responseBodyEnd(call: Call, byteCount: Long) {
        normalEof = true
        log(call, "response_eof", "normalEof=true bytes=$byteCount")
    }

    override fun canceled(call: Call) {
        log(call, "cancel", "cancelled=true")
    }

    override fun callEnd(call: Call) {
        log(
            call,
            "end",
            "endMs=${System.currentTimeMillis()} normalEof=$normalEof cancelled=${call.isCanceled()}",
        )
    }

    override fun callFailed(call: Call, ioe: IOException) {
        val root = ioe.rootCause()
        log(
            call,
            "failure",
            "endMs=${System.currentTimeMillis()} normalEof=$normalEof cancelled=${call.isCanceled()} " +
                "exception=${ioe.javaClass.name} message=${ioe.message.safeForLog()} " +
                "rootCause=${root.javaClass.name} rootMessage=${root.message.safeForLog()}",
        )
    }

    private fun log(call: Call, stage: String, extra: String = "") {
        val request = call.request()
        val description = request.describeAiRequest()
        val durationMs = (System.nanoTime() - startedAtNanos) / 1_000_000
        Log.i(
            TAG,
            "id=$requestId stage=$stage provider=${description.provider} host=${description.host} " +
                "pathType=${description.pathType} streaming=${description.streaming} " +
                "protocol=${protocol?.name ?: "UNKNOWN"} status=${statusCode ?: "none"} " +
                "durationMs=$durationMs thread=${Thread.currentThread().name} $extra",
        )
    }

    private fun Throwable.rootCause(): Throwable {
        var current = this
        while (current.cause != null && current.cause !== current) {
            current = current.cause!!
        }
        return current
    }

    private fun String?.safeForLog(): String = this
        ?.replace(Regex("[\\r\\n\\t]+"), " ")
        ?.take(300)
        ?: "none"

    private companion object {
        val nextRequestId = AtomicLong(0)
    }
}
