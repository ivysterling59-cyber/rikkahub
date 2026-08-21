package me.rerere.common.http

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.Job
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import okhttp3.internal.closeQuietly
import okio.IOException
import kotlin.coroutines.resumeWithException

suspend fun Call.await(): Response {
    return suspendCancellableCoroutine { continuation ->
        val trace = request().aiRequestTrace()
        val job = continuation.context[Job]
        trace?.log(
            event = "CALL_CREATED",
            extra = "source=Call.await callCancelled=${isCanceled()}",
        )
        trace?.log(
            event = "CALL_ENQUEUE",
            extra = "continuationActive=${continuation.isActive} callCancelled=${isCanceled()} " +
                "jobActive=${job?.isActive} jobCancelled=${job?.isCancelled}",
        )
        continuation.invokeOnCancellation { cause ->
            trace?.markCoroutineCancellation(cause)
            trace?.log(
                event = "REQUEST_CANCELLED",
                extra = "source=Call.await continuationActive=${continuation.isActive} " +
                    "continuationCancelled=${continuation.isCancelled} callCancelled=${isCanceled()} " +
                    "jobActive=${job?.isActive} jobCancelled=${job?.isCancelled} " +
                    "callCancelInvokedHere=false stackTrace=${Throwable().stackTraceToString().safeForAiDiagnostics()}",
                error = cause,
            )
        }
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                trace?.log(
                    event = "CALLBACK_FAILURE",
                    extra = "continuationActive=${continuation.isActive} " +
                        "continuationCancelled=${continuation.isCancelled} callCancelled=${call.isCanceled()}",
                    error = e,
                )
                if (continuation.isActive) {
                    continuation.resumeWithException(e)
                }
            }

            override fun onResponse(call: Call, response: Response) {
                trace?.log(
                    event = "CALLBACK_RESPONSE",
                    extra = "status=${response.code} continuationActive=${continuation.isActive} " +
                        "continuationCancelled=${continuation.isCancelled} callCancelled=${call.isCanceled()}",
                )
                continuation.resume(response) { cause, _, _ ->
                    trace?.log(
                        event = "RESPONSE_CLOSED_AFTER_CANCELLED_CONTINUATION",
                        extra = "status=${response.code}",
                        error = cause,
                    )
                    response.closeQuietly()
                }
            }
        })
    }
}
