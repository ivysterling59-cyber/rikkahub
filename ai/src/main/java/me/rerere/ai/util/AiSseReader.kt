package me.rerere.ai.util

import me.rerere.ai.provider.AiStreamReaderMode
import me.rerere.common.http.aiRequestTrace
import okhttp3.Call
import okhttp3.Request
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources

/** Selects the transport reader without changing the request or the OkHttpClient. */
object AiSseReader {
    fun open(
        callFactory: Call.Factory,
        mode: AiStreamReaderMode,
        request: Request,
        listener: AiSseListener,
    ): AiSseConnection {
        request.aiRequestTrace()?.markStreamReader(mode.name)
        return when (mode) {
            AiStreamReaderMode.EVENT_SOURCE -> {
                val eventSource = EventSources.createFactory(callFactory).newEventSource(
                    request,
                    object : EventSourceListener() {
                        override fun onOpen(eventSource: EventSource, response: Response) {
                            listener.onOpen(response)
                        }

                        override fun onEvent(
                            eventSource: EventSource,
                            id: String?,
                            type: String?,
                            data: String,
                        ) {
                            listener.onEvent(id, type, data)
                        }

                        override fun onClosed(eventSource: EventSource) {
                            listener.onClosed()
                        }

                        override fun onFailure(
                            eventSource: EventSource,
                            t: Throwable?,
                            response: Response?,
                        ) {
                            listener.onFailure(t, response)
                        }
                    },
                )
                AiSseConnection(eventSource::cancel)
            }
            AiStreamReaderMode.MANUAL_SSE ->
                ManualSseCall(request, listener).apply { connect(callFactory) }
        }
    }
}

fun interface AiSseConnection {
    fun cancel()
}

interface AiSseListener {
    fun onOpen(response: Response) = Unit
    fun onEvent(id: String?, type: String?, data: String) = Unit
    fun onClosed() = Unit
    fun onFailure(t: Throwable?, response: Response?) = Unit
}
