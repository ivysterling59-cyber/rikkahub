package me.rerere.ai.util

import okio.BufferedSource

/** Minimal WHATWG-compatible SSE field parser with no okhttp-sse dependency. */
internal class ManualSseParser(
    private val callback: Callback,
) {
    private var lastEventId: String? = null

    fun process(source: BufferedSource) {
        var data = StringBuilder()
        var eventType: String? = null

        fun dispatch() {
            if (data.isEmpty()) {
                eventType = null
                return
            }
            if (data.last() == '\n') data.setLength(data.length - 1)
            callback.onEvent(lastEventId, eventType, data.toString())
            data = StringBuilder()
            eventType = null
        }

        while (true) {
            val line = source.readUtf8Line()
            if (line == null) {
                dispatch()
                return
            }
            if (line.isEmpty()) {
                dispatch()
                continue
            }
            if (line.startsWith(':')) continue

            val separator = line.indexOf(':')
            val field = if (separator >= 0) line.substring(0, separator) else line
            var value = if (separator >= 0) line.substring(separator + 1) else ""
            if (value.startsWith(' ')) value = value.substring(1)

            when (field) {
                "event" -> eventType = value
                "data" -> data.append(value).append('\n')
                "id" -> if ('\u0000' !in value) lastEventId = value
                "retry" -> value.toLongOrNull()?.takeIf { it >= 0 }?.let(callback::onRetry)
            }
        }
    }

    interface Callback {
        fun onEvent(id: String?, type: String?, data: String)
        fun onRetry(timeMs: Long)
    }
}
