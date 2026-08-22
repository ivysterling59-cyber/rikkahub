package me.rerere.common.http

import okhttp3.Request
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Non-sensitive correlation data attached to every text-generation HTTP request.
 * The OkHttp EventListener, coroutine bridge and provider stream all read this same object.
 */
class AiRequestTrace private constructor(
    val requestId: String,
    val provider: String,
    val streaming: Boolean,
    val startedAtMillis: Long,
    private val startedAtNanos: Long,
) {
    private val closeReason = AtomicReference("not_closed")
    private val eventCount = AtomicLong(0)
    private val coroutineCancellation = AtomicReference<Throwable?>(null)
    private val networkMode = AtomicReference<String?>(null)
    private val clientId = AtomicReference<String?>(null)
    private val connectionPoolId = AtomicReference<String?>(null)

    fun durationMillis(): Long = (System.nanoTime() - startedAtNanos) / 1_000_000

    fun closeReason(): String = closeReason.get()

    fun markCloseReason(reason: String) {
        closeReason.set(reason)
    }

    fun markCoroutineCancellation(cause: Throwable?) {
        coroutineCancellation.compareAndSet(null, cause)
    }

    fun coroutineCancellation(): Throwable? = coroutineCancellation.get()

    fun recordEvent(): Long = eventCount.incrementAndGet()

    fun eventCount(): Long = eventCount.get()

    fun markHttpClient(networkMode: String, clientId: String, connectionPoolId: String) {
        this.networkMode.set(networkMode)
        this.clientId.set(clientId)
        this.connectionPoolId.set(connectionPoolId)
    }

    fun log(event: String, extra: String = "", error: Throwable? = null) {
        val details = buildString {
            append("stream=").append(streaming)
            append(" durationMs=").append(durationMillis())
            append(" closeReason=").append(closeReason())
            networkMode.get()?.let { append(" networkMode=").append(it) }
            clientId.get()?.let { append(" clientId=").append(it) }
            connectionPoolId.get()?.let { append(" connectionPoolId=").append(it) }
            append(" thread=").append(Thread.currentThread().name)
            if (extra.isNotBlank()) append(' ').append(extra)
        }
        if (error == null) {
            AiHttpDiag.info(event, requestId, provider, message = details)
        } else {
            AiHttpDiag.warn(event, requestId, provider, message = details, throwable = error)
        }
    }

    companion object {
        private val sequence = AtomicLong(0)

        fun create(provider: String, streaming: Boolean): AiRequestTrace = AiRequestTrace(
            requestId = "ai-${System.currentTimeMillis()}-${sequence.incrementAndGet()}",
            provider = provider,
            streaming = streaming,
            startedAtMillis = System.currentTimeMillis(),
            startedAtNanos = System.nanoTime(),
        )
    }
}

fun Request.Builder.withAiRequestTrace(provider: String, streaming: Boolean): Request.Builder =
    tag(AiRequestTrace::class.java, AiRequestTrace.create(provider, streaming))

fun Request.aiRequestTrace(): AiRequestTrace? = tag(AiRequestTrace::class.java)

fun Throwable.rootCauseForAiDiagnostics(): Throwable {
    var current = this
    while (current.cause != null && current.cause !== current) current = current.cause!!
    return current
}

fun String?.safeForAiDiagnostics(): String = this
    ?.replace(Regex("[\\r\\n\\t]+"), " ")
    ?.redactAiDiagnosticSecrets()
    ?.take(300)
    ?: "none"

fun Throwable.safeStackTraceForAiDiagnostics(): String = stackTraceToString()
    .redactAiDiagnosticSecrets()
    .take(20_000)

private fun String.redactAiDiagnosticSecrets(): String = this
    .replace(Regex("(?i)(https?://[^\\s?]+)\\?[^\\s]+"), "$1?<redacted>")
    .replace(
        Regex("(?i)\\b(api[_-]?key|key|access[_-]?token|authorization|cookie)=([^\\s&]+)"),
        "$1=<redacted>",
    )
