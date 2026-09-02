package me.rerere.ai.util

import me.rerere.common.http.aiRequestTrace
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.IOException

/** Debug A/B path built only from a regular OkHttp call and a line-based Okio parser. */
internal class ManualSseCall(
    private val originalRequest: Request,
    private val listener: AiSseListener,
) : AiSseConnection, Callback {
    private val request = if (originalRequest.header("Accept") == null) {
        originalRequest.newBuilder().header("Accept", "text/event-stream").build()
    } else {
        originalRequest
    }

    @Volatile
    private var canceled = false

    @Volatile
    private var completed = false

    private var call: Call? = null

    fun connect(callFactory: Call.Factory) {
        call = callFactory.newCall(request).also { it.enqueue(this) }
    }

    override fun cancel() {
        canceled = true
        if (!completed) {
            request.aiRequestTrace()?.log(
                event = "MANUAL_SSE_CANCEL",
                extra = "callCancelAboutToRun=true",
                error = Throwable("Manual SSE connection cancel() invocation stack"),
            )
        }
        call?.cancel()
    }

    override fun onFailure(call: Call, e: IOException) {
        completed = true
        listener.onFailure(e, null)
    }

    override fun onResponse(call: Call, response: Response) {
        response.use {
            if (!response.isSuccessful) {
                completed = true
                listener.onFailure(null, response)
                return
            }
            val body = response.body
            if (!body.isEventStream()) {
                completed = true
                listener.onFailure(
                    IllegalStateException("Invalid content-type: ${body.contentType()}"),
                    response,
                )
                return
            }

            // A configured full-call timeout must not bound a long-lived response body.
            call.timeout().cancel()
            val callbackResponse = response.newBuilder()
                .body(ByteArray(0).toResponseBody(body.contentType()))
                .build()
            listener.onOpen(callbackResponse)

            try {
                ManualSseParser(
                    object : ManualSseParser.Callback {
                        override fun onEvent(id: String?, type: String?, data: String) {
                            if (!canceled) listener.onEvent(id, type, data)
                        }

                        override fun onRetry(timeMs: Long) {
                            request.aiRequestTrace()?.log(
                                event = "SSE_RETRY_HINT",
                                extra = "streamReader=MANUAL_SSE retryMs=$timeMs autoRetry=false",
                            )
                        }
                    },
                ).process(body.source())
            } catch (error: Throwable) {
                completed = true
                val failure = if (canceled) IOException("canceled", error) else error
                listener.onFailure(failure, callbackResponse)
                return
            }

            completed = true
            if (canceled) {
                listener.onFailure(IOException("canceled"), callbackResponse)
            } else {
                listener.onClosed()
            }
        }
    }

    private fun ResponseBody.isEventStream(): Boolean {
        val contentType = contentType() ?: return false
        return contentType.type == "text" && contentType.subtype == "event-stream"
    }
}
