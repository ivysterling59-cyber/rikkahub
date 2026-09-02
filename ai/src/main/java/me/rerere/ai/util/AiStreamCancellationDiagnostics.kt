package me.rerere.ai.util

import kotlinx.coroutines.Job
import me.rerere.common.http.AiRequestTrace

class AiStreamCancellationDiagnostics(
    private val trace: AiRequestTrace,
    private val flowJob: Job?,
) {
    private var lastProgressLogElapsedMillis = -1L

    init {
        trace.log(
            event = "CALL_CREATE_REQUESTED",
            extra = "source=${trace.streamReader()} jobActive=${flowJob?.isActive} " +
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
        val timing = trace.recordSseEvent()
        if (
            timing.eventCount == 1L ||
            timing.elapsedMillis - lastProgressLogElapsedMillis >= PROGRESS_INTERVAL_MILLIS
        ) {
            trace.log(
                event = "SSE_PROGRESS",
                extra = "eventCount=${timing.eventCount} elapsedMs=${timing.elapsedMillis} " +
                    "sinceLastEventMs=${timing.sinceLastEventMillis}",
            )
            lastProgressLogElapsedMillis = timing.elapsedMillis
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
                "streamConnectionCancelAboutToRun=true",
            error = cancellation,
        )
    }

    private companion object {
        const val PROGRESS_INTERVAL_MILLIS = 1_000L
    }
}
