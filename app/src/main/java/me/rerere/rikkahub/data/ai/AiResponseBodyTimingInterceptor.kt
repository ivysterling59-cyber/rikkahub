package me.rerere.rikkahub.data.ai

import me.rerere.common.http.AiRequestTrace
import me.rerere.common.http.aiRequestTrace
import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer

/** Records body-byte timing for both streaming and non-streaming AI responses. */
internal class AiResponseBodyTimingInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())
        val trace = chain.request().aiRequestTrace() ?: return response
        val body = response.body
        return response.newBuilder()
            .body(TimedResponseBody(body, trace))
            .build()
    }

    private class TimedResponseBody(
        private val delegate: ResponseBody,
        private val trace: AiRequestTrace,
    ) : ResponseBody() {
        private val timedSource: BufferedSource by lazy {
            object : ForwardingSource(delegate.source()) {
                override fun read(sink: Buffer, byteCount: Long): Long {
                    val read = super.read(sink, byteCount)
                    val timing = trace.recordBodyData(read)
                    if (timing?.firstBodyByte == true) {
                        trace.log(
                            event = "FIRST_BODY_BYTE",
                            extra = "firstBodyByteElapsedMs=${timing.elapsedMillis}",
                        )
                    }
                    return read
                }
            }.buffer()
        }

        override fun contentType(): MediaType? = delegate.contentType()

        override fun contentLength(): Long = delegate.contentLength()

        override fun source(): BufferedSource = timedSource
    }
}
