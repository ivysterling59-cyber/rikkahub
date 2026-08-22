package me.rerere.ai.util

import kotlinx.coroutines.Job
import me.rerere.common.http.AiRequestTrace

class AiStreamCancellationDiagnostics(
    private val trace: AiRequestTrace,
    private val flowJob: Job?,
) {
    private var lastProgressLogNanos = System.nanoTime()

    init {
        trace.log(
            event = "CALL_CREATE_REQUESTED",
            extra = "source=EventSourceFactory jobActive=${flowJob?.isActive} " +
                "jobCancelled=${flowJob?.isCancelled}",
        )
        flowJob?.invokeOnCompletion { cause ->
            if (cause != null) trace.markCoroutineCancellation(cause)
            trace.log(
                event = if (cause == null) "STREAM_COROUTINE_COMPLETED" else "REQUEST_CANCELLED",
                extra = "source=streamCoroutine jobActive=${flowJob.isActive} " +
                    "jobCancelled=${flowJob.isCancelled} events=${trace.eventCount()}",
                error = cause,
            )
        }
    }

    fun mark(reason: String, error: Throwable? = null) {
        trace.markCloseReason(reason)
        when (reason) {
            "provider_completed", "transport_closed" -> trace.log(
                event = "REQUEST_SUCCESS",
                extra = "source=provider eventCount=${trace.eventCount()}",
            )
            "decode_failure", "network_failure" -> trace.log(
                event = "REQUEST_FAILED",
                extra = "source=provider eventCount=${trace.eventCount()}",
                error = error,
            )
        }
    }

    fun onEvent() {
        val count = trace.recordEvent()
        val now = System.nanoTime()
        if (count == 1L) {
            trace.log("TOKEN_RECEIVED", "eventCount=1")
            lastProgressLogNanos = now
        } else if (now - lastProgressLogNanos >= PROGRESS_INTERVAL_NANOS) {
            trace.log("BODY_PROGRESS", "eventCount=$count")
            lastProgressLogNanos = now
        }
    }

    fun logCleanup() {
        val cancellation = trace.coroutineCancellation()
        trace.log(
            event = if (flowJob?.isCancelled == true || cancellation != null) {
                "REQUEST_CANCELLED"
            } else {
                "STREAM_CLEANUP"
            },
            extra = "source=awaitClose jobActive=${flowJob?.isActive} " +
                "jobCancelled=${flowJob?.isCancelled} events=${trace.eventCount()} " +
                "eventSourceCancelAboutToRun=true",
            error = cancellation,
        )
    }

    private companion object {
        const val PROGRESS_INTERVAL_NANOS = 2_000_000_000L
    }
}
