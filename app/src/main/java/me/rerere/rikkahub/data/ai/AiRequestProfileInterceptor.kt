package me.rerere.rikkahub.data.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import me.rerere.common.http.AiHttpDiag
import me.rerere.common.http.aiRequestTrace
import me.rerere.common.http.safeForAiDiagnostics
import me.rerere.rikkahub.data.datastore.SettingsStore
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.Buffer

/**
 * Debug-only request shape experiment. It preserves model, messages (including RP/system content)
 * and streaming, while removing optional top-level OpenAI Chat Completions fields.
 */
internal class MinimalOpenAiRequestInterceptor(
    private val modeProvider: () -> AiRequestMode,
) : Interceptor {
    constructor(settingsStore: SettingsStore) : this(
        modeProvider = { settingsStore.settingsFlow.value.aiRequestMode },
    )

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (modeProvider() != AiRequestMode.MINIMAL_OPENAI_COMPATIBLE) {
            return chain.proceed(request.withRequestMode(AiRequestMode.NORMAL))
        }
        if (request.describeAiRequest().pathType != "chat-completions") {
            return chain.proceed(request.withRequestMode(AiRequestMode.NORMAL))
        }

        val jsonObject = request.readJsonObject()
            ?: return chain.proceed(request.withRequestMode(AiRequestMode.NORMAL))
        val trace = request.aiRequestTrace()
        val minimalBody = jsonObject.toMinimalOpenAiCompatibleBody(
            streamingFallback = trace?.streaming ?: false,
        )
        if (minimalBody == null) {
            return chain.proceed(request.withRequestMode(AiRequestMode.NORMAL))
        }

        val removedFields = jsonObject.keys - MINIMAL_FIELDS
        trace?.log(
            event = "MINIMAL_REQUEST_APPLIED",
            extra = "requestMode=${AiRequestMode.MINIMAL_OPENAI_COMPATIBLE.name} " +
                "removedFields=[${removedFields.sorted().joinToString(",")}]",
        )
        val body = minimalBody.toString().toRequestBody(request.body?.contentType())
        val minimalRequest = request.newBuilder()
            .removeHeader("Content-Length")
            .method(request.method, body)
            .tag(AiRequestMode::class.java, AiRequestMode.MINIMAL_OPENAI_COMPATIBLE)
            .build()
        return chain.proceed(minimalRequest)
    }

    private fun Request.withRequestMode(mode: AiRequestMode): Request = newBuilder()
        .tag(AiRequestMode::class.java, mode)
        .build()

    private companion object {
        val MINIMAL_FIELDS = setOf("model", "messages", "stream")
    }
}

internal fun JsonObject.toMinimalOpenAiCompatibleBody(
    streamingFallback: Boolean = false,
): JsonObject? {
    val streaming = (get("stream") as? JsonPrimitive)?.booleanOrNull ?: streamingFallback
    val model = get("model") ?: return null
    val messages = get("messages") ?: return null
    if (!streaming) return null
    return buildJsonObject {
        put("model", model)
        put("messages", messages)
        put("stream", true)
    }
}

/** Records only non-sensitive request structure and selected headers at the network layer. */
internal class AiRequestProfileInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val trace = request.aiRequestTrace()
        val description = request.describeAiRequest()
        val jsonObject = request.readJsonObject()
        val bodySize = runCatching { request.body?.contentLength() }.getOrNull()
        val fields = jsonObject?.keys?.toList().orEmpty()
        val streamOptions = jsonObject?.get("stream_options") as? JsonObject
        val stream = (jsonObject?.get("stream") as? JsonPrimitive)?.booleanOrNull
            ?: trace?.streaming
            ?: description.streaming
        val model = (jsonObject?.get("model") as? JsonPrimitive)?.contentOrNull
        val requestMode = request.tag(AiRequestMode::class.java) ?: AiRequestMode.NORMAL
        val requestMessage = buildString {
            append("method=").append(request.method)
            append(" pathType=").append(description.pathType)
            append(" networkMode=").append(trace?.networkMode() ?: "UNKNOWN")
            append(" requestMode=").append(requestMode.name)
            append(" stream=").append(stream)
            append(" model=").append(model.profileValue())
            append(" bodySize=").append(bodySize ?: "unknown")
            append(" fields=[").append(fields.joinToString(",")).append(']')
            append(" streamOptionsPresent=").append("stream_options" in fields)
            append(" includeUsage=")
                .append((streamOptions?.get("include_usage") as? JsonPrimitive)?.booleanOrNull ?: "null")
            append(" temperaturePresent=").append("temperature" in fields)
            append(" toolsPresent=").append("tools" in fields)
            REQUEST_HEADERS.forEach { header ->
                append(" request.").append(header).append('=')
                    .append(request.header(header).profileHeaderValue())
            }
        }
        AiHttpDiag.info(
            event = "REQUEST_PROFILE",
            requestId = trace?.requestId,
            provider = trace?.provider ?: description.provider,
            host = description.host,
            message = requestMessage,
        )

        val response = chain.proceed(request)
        val responseMessage = buildString {
            append("protocol=").append(response.protocol.name)
            append(" statusCode=").append(response.code)
            RESPONSE_HEADERS.forEach { header ->
                append(" response.").append(header).append('=')
                    .append(response.header(header).profileHeaderValue())
            }
        }
        AiHttpDiag.info(
            event = "RESPONSE_PROFILE",
            requestId = trace?.requestId,
            provider = trace?.provider ?: description.provider,
            host = description.host,
            message = responseMessage,
        )
        return response
    }

    private companion object {
        val REQUEST_HEADERS = listOf(
            "Content-Type",
            "Accept",
            "Accept-Encoding",
            "User-Agent",
            "Connection",
            "Cache-Control",
            "TE",
        )
        val RESPONSE_HEADERS = listOf(
            "Content-Type",
            "Content-Encoding",
            "Transfer-Encoding",
            "Content-Length",
            "Server",
            "Via",
            "CF-Ray",
            "CF-Cache-Status",
        )
    }
}

private val diagnosticJson = Json { ignoreUnknownKeys = true }

private fun Request.readJsonObject(): JsonObject? {
    val body = body ?: return null
    if (body.contentType()?.subtype?.contains("json", ignoreCase = true) != true) return null
    val contentLength = runCatching { body.contentLength() }.getOrDefault(-1L)
    if (contentLength > MAX_PROFILE_BODY_BYTES) return null
    return runCatching {
        val buffer = Buffer()
        body.writeTo(buffer)
        if (buffer.size > MAX_PROFILE_BODY_BYTES) return@runCatching null
        diagnosticJson.parseToJsonElement(buffer.readUtf8()).jsonObject
    }.getOrNull()
}

private fun String?.profileHeaderValue(): String = this
    ?.safeForAiDiagnostics()
    ?.take(500)
    ?: "null"

private fun String?.profileValue(): String = this
    ?.safeForAiDiagnostics()
    ?.take(200)
    ?: "null"

private const val MAX_PROFILE_BODY_BYTES = 8L * 1024L * 1024L
