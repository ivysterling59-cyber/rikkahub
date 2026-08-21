package me.rerere.common.http

import android.util.Log
import okhttp3.Request
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

private const val AI_HTTP_DIAGNOSTICS_TAG = "AiHttpDiag"

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

    fun log(event: String, extra: String = "", error: Throwable? = null) {
        val root = error?.rootCauseForAiDiagnostics()
        val details = buildString {
            append("event=").append(event)
            append(" requestId=").append(requestId)
            append(" provider=").append(provider)
            append(" stream=").append(streaming)
            append(" durationMs=").append(durationMillis())
            append(" closeReason=").append(closeReason())
            append(" thread=").append(Thread.currentThread().name)
            if (error != null) {
                append(" exceptionClass=").append(error.javaClass.name)
                append(" exceptionMessage=").append(error.message.safeForAiDiagnostics())
                append(" rootCause=").append(root?.javaClass?.name ?: "none")
                append(" rootMessage=").append(root?.message.safeForAiDiagnostics())
            }
            if (extra.isNotBlank()) append(' ').append(extra)
        }
        if (error == null) Log.i(AI_HTTP_DIAGNOSTICS_TAG, details)
        else Log.w(AI_HTTP_DIAGNOSTICS_TAG, details, error)
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
    ?.take(300)
    ?: "none"
