package me.rerere.common.http

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.roundToLong

enum class AiHttpDiagLevel {
    INFO,
    WARN,
    ERROR,
}

data class AiHttpDiagEntry(
    val id: Long,
    val timestamp: Long,
    val level: AiHttpDiagLevel,
    val requestId: String?,
    val provider: String?,
    val host: String?,
    val event: String,
    val message: String,
    val stackTrace: String? = null,
)

/** Thread-safe, process-local ring buffer for non-sensitive AI HTTP diagnostics. */
object AiHttpDiagStore {
    const val MAX_ENTRIES = 1_500

    private val lock = Any()
    private val sequence = AtomicLong(0)
    private val buffer = ArrayDeque<AiHttpDiagEntry>(MAX_ENTRIES)
    private val mutableEntries = MutableStateFlow<List<AiHttpDiagEntry>>(emptyList())

    val entries: StateFlow<List<AiHttpDiagEntry>> = mutableEntries.asStateFlow()

    fun append(
        level: AiHttpDiagLevel,
        event: String,
        requestId: String? = null,
        provider: String? = null,
        host: String? = null,
        message: String = "",
        throwable: Throwable? = null,
        timestamp: Long = System.currentTimeMillis(),
    ) {
        val entry = AiHttpDiagEntry(
            id = sequence.incrementAndGet(),
            timestamp = timestamp,
            level = level,
            requestId = requestId,
            provider = provider,
            host = host,
            event = event,
            message = message,
            stackTrace = throwable?.safeStackTraceForAiDiagnostics(),
        )
        synchronized(lock) {
            buffer.addLast(entry)
            while (buffer.size > MAX_ENTRIES) buffer.removeFirst()
            mutableEntries.value = buffer.toList()
        }
    }

    fun snapshot(): List<AiHttpDiagEntry> = synchronized(lock) { buffer.toList() }

    fun clear() {
        synchronized(lock) {
            buffer.clear()
            mutableEntries.value = emptyList()
        }
    }

    fun latestFailedRequestText(source: List<AiHttpDiagEntry> = snapshot()): String? {
        val terminalIndex = source.indexOfLast { it.isFailureTerminal() && !it.requestId.isNullOrBlank() }
        if (terminalIndex < 0) return null

        val terminal = source[terminalIndex]
        val requestId = terminal.requestId ?: return null
        val startIndex = (terminalIndex downTo 0).firstOrNull { index ->
            source[index].requestId == requestId && source[index].event == "REQUEST_START"
        } ?: (0..terminalIndex).firstOrNull { source[it].requestId == requestId } ?: terminalIndex
        val requestEntries = source.subList(startIndex, terminalIndex + 1)
            .filter { it.requestId == requestId }
        val provider = requestEntries.firstNotNullOfOrNull { it.provider } ?: "unknown"
        val host = requestEntries.firstNotNullOfOrNull { it.host } ?: "unknown"
        val stream = requestEntries.asSequence()
            .mapNotNull { STREAM_REGEX.find(it.message)?.groupValues?.getOrNull(1) }
            .firstOrNull() ?: "unknown"

        return buildString {
            appendLine("=== AiHttpDiag Request ===")
            appendLine("requestId=$requestId")
            appendLine("provider=$provider")
            appendLine("host=$host")
            appendLine("stream=$stream")
            appendLine()
            append(formatEntries(requestEntries, includeStructuredFields = false))
            if (isNotEmpty() && !endsWith('\n')) appendLine()
            append("=== End ===")
        }
    }

    /** Client-fingerprint export. Values come only from the allowlisted diagnostic profile fields. */
    fun latestFailedRequestProfileText(source: List<AiHttpDiagEntry> = snapshot()): String? {
        val terminalIndex = source.indexOfLast { it.isFailureTerminal() && !it.requestId.isNullOrBlank() }
        if (terminalIndex < 0) return null
        val terminal = source[terminalIndex]
        val requestId = terminal.requestId ?: return null
        val requestEntries = source.subList(0, terminalIndex + 1).filter { it.requestId == requestId }
        val messages = requestEntries.map { it.message }

        fun field(name: String): String? = messages.asSequence()
            .mapNotNull { message -> fieldValue(message, name) }
            .lastOrNull()
        fun value(name: String): String = field(name)
            ?.takeUnless { it.equals("none", true) }
            ?: "null"
        fun header(section: String, name: String): String = value("$section.$name")

        val provider = requestEntries.firstNotNullOfOrNull { it.provider } ?: "unknown"
        val host = requestEntries.firstNotNullOfOrNull { it.host } ?: "unknown"
        val fields = field("fields")
            ?.removePrefix("[")
            ?.removeSuffix("]")
            ?.split(',')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            .orEmpty()

        return buildString {
            appendLine("=== RikkaHub Failed Request Profile ===")
            appendLine()
            appendLine("requestId=$requestId")
            appendLine("provider=$provider")
            appendLine("host=$host")
            appendLine("model=${value("model")}")
            appendLine()
            appendLine("networkMode=${value("networkMode")}")
            appendLine("requestMode=${value("requestMode")}")
            appendLine("protocol=${value("protocol")}")
            appendLine("stream=${value("stream")}")
            appendLine()
            appendLine("durationMs=${value("durationMs")}")
            appendLine("eventCount=${value("eventCount")}")
            appendLine("lastEventElapsedMs=${value("lastEventElapsedMs")}")
            appendLine("timeSinceLastEventMs=${value("timeSinceLastEventMs")}")
            appendLine()
            appendLine("Request Headers:")
            REQUEST_PROFILE_HEADERS.forEach { name -> appendLine("$name=${header("request", name)}") }
            appendLine()
            appendLine("Request Fields:")
            if (fields.isEmpty()) appendLine("null") else fields.forEach { appendLine(it) }
            appendLine("streamOptionsPresent=${value("streamOptionsPresent")}")
            appendLine("includeUsage=${value("includeUsage")}")
            appendLine("temperaturePresent=${value("temperaturePresent")}")
            appendLine("toolsPresent=${value("toolsPresent")}")
            appendLine()
            appendLine("Response:")
            appendLine("status=${value("statusCode")}")
            RESPONSE_PROFILE_HEADERS.forEach { name -> appendLine("$name=${header("response", name)}") }
            appendLine()
            appendLine("Failure:")
            appendLine("event=${terminal.event}")
            appendLine("exceptionClass=${value("exceptionClass")}")
            appendLine("exceptionMessage=${value("exceptionMessage")}")
            appendLine("rootCause=${value("rootCause")}")
            append("=== End ===")
        }
    }

    /** Human-readable distribution of the latest 50 unique failed requests. */
    fun failureStatisticsText(source: List<AiHttpDiagEntry> = snapshot()): String {
        val seenRequestIds = HashSet<String>()
        val failures = source.asReversed().mapNotNull { terminal ->
            val requestId = terminal.requestId
            if (terminal.event !in FAILURE_EVENTS || requestId.isNullOrBlank() || !seenRequestIds.add(requestId)) {
                return@mapNotNull null
            }
            val messages = source.asSequence()
                .filter { it.requestId == requestId }
                .map { it.message }
                .toList()
            fun field(name: String): String? = messages.asSequence()
                .mapNotNull { fieldValue(it, name) }
                .lastOrNull()
            FailureRecord(
                durationMs = field("durationMs")?.toLongOrNull() ?: return@mapNotNull null,
                protocol = field("protocol") ?: "unknown",
                networkMode = field("networkMode") ?: "unknown",
                exceptionClass = field("exceptionClass") ?: "unknown",
                eventCount = field("eventCount")?.toLongOrNull() ?: 0,
                timeSinceLastEventMs = field("timeSinceLastEventMs")?.toLongOrNull(),
            )
        }.take(50)

        if (failures.isEmpty()) return "失败统计\n暂无失败记录"
        val sortedDurations = failures.map { it.durationMs }.sorted()
        val medianMs = if (sortedDurations.size % 2 == 1) {
            sortedDurations[sortedDurations.size / 2].toDouble()
        } else {
            val upper = sortedDurations.size / 2
            (sortedDurations[upper - 1] + sortedDurations[upper]) / 2.0
        }
        val averageMs = failures.map { it.durationMs }.average()

        return buildString {
            appendLine("失败统计")
            appendLine("最近失败次数：${failures.size}（最多 50 次）")
            appendLine()
            appendLine("平均失败时间：${formatSeconds(averageMs)}")
            appendLine("中位数：${formatSeconds(medianMs)}")
            appendLine()
            appendLine("<30s：${failures.count { it.durationMs < 30_000 }} 次")
            appendLine("30-40s：${failures.count { it.durationMs in 30_000 until 40_000 }} 次")
            appendLine("40-60s：${failures.count { it.durationMs in 40_000 until 60_000 }} 次")
            appendLine(">=60s：${failures.count { it.durationMs >= 60_000 }} 次")
            appendLine()
            append("最近一次：")
            failures.first().let { latest ->
                append("durationMs=${latest.durationMs}")
                append(" protocol=${latest.protocol}")
                append(" networkMode=${latest.networkMode}")
                append(" exceptionClass=${latest.exceptionClass}")
                append(" eventCount=${latest.eventCount}")
                append(" timeSinceLastEventMs=${latest.timeSinceLastEventMs ?: "null"}")
            }
        }
    }

    /** Compact, paste-friendly summary of the latest AI network experiment request. */
    fun latestNetworkExperimentText(
        appVersion: String,
        selectedMode: String,
        source: List<AiHttpDiagEntry> = snapshot(),
    ): String? {
        val terminalIndex = source.indexOfLast {
            !it.requestId.isNullOrBlank() && it.event in TERMINAL_EVENTS
        }
        val endIndex = terminalIndex.takeIf { it >= 0 }
            ?: source.indexOfLast { !it.requestId.isNullOrBlank() }
        if (endIndex < 0) return null

        val requestId = source[endIndex].requestId ?: return null
        val startIndex = (endIndex downTo 0).firstOrNull { index ->
            source[index].requestId == requestId && source[index].event == "REQUEST_START"
        } ?: (0..endIndex).firstOrNull { source[it].requestId == requestId } ?: endIndex
        val requestEntries = source.subList(startIndex, endIndex + 1)
            .filter { it.requestId == requestId }
        val combinedMessages = requestEntries.asSequence().map { it.message }.toList()

        fun field(name: String): String? = combinedMessages.asSequence()
            .mapNotNull { message -> fieldValue(message, name) }
            .filterNot { value -> value.equals("none", true) || value.equals("unknown", true) }
            .lastOrNull()

        val provider = requestEntries.firstNotNullOfOrNull { it.provider } ?: "unknown"
        val host = requestEntries.firstNotNullOfOrNull { it.host } ?: "unknown"
        val final = requestEntries.last()
        val networkMode = field("networkMode") ?: selectedMode

        return buildString {
            appendLine("=== RikkaHub Network Experiment ===")
            appendLine("appVersion=$appVersion")
            appendLine("networkMode=$networkMode")
            appendLine("requestId=$requestId")
            appendLine("provider=$provider")
            appendLine("host=$host")
            appendLine("stream=${field("stream") ?: "unknown"}")
            appendLine("protocol=${field("protocol") ?: "unknown"}")
            appendLine("statusCode=${field("statusCode") ?: "unknown"}")
            appendLine("durationMs=${field("durationMs") ?: "unknown"}")
            appendLine("normalEof=${field("normalEof") ?: "unknown"}")
            appendLine("callCancelled=${field("callCancelled") ?: "unknown"}")
            appendLine("exceptionClass=${field("exceptionClass") ?: "none"}")
            appendLine("exceptionMessage=${field("exceptionMessage") ?: "none"}")
            appendLine("finalEvent=${final.event}")
            append("=== End ===")
        }
    }

    fun formatEntries(
        source: List<AiHttpDiagEntry> = snapshot(),
        includeStructuredFields: Boolean = true,
    ): String {
        val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.ROOT)
        return source.joinToString("\n") { entry ->
            buildString {
                append(formatter.format(Date(entry.timestamp)))
                append(' ').append(entry.level)
                append(' ').append(entry.event)
                if (includeStructuredFields) {
                    entry.requestId?.let { append(" requestId=").append(it) }
                    entry.provider?.let { append(" provider=").append(it) }
                    entry.host?.let { append(" host=").append(it) }
                }
                if (entry.message.isNotBlank()) append(' ').append(entry.message)
                entry.stackTrace?.takeIf { it.isNotBlank() }?.let { append('\n').append(it) }
            }
        }
    }

    private fun AiHttpDiagEntry.isFailureTerminal(): Boolean = when (event) {
        "REQUEST_FAILED", "REMOTE_CONNECTION_FAILURE" -> true
        "REQUEST_CANCELLED", "LOCAL_CALL_CANCEL" ->
            !message.contains("closeReason=provider_completed") &&
                !message.contains("closeReason=transport_closed")
        else -> false
    }

    private fun fieldValue(message: String, name: String): String? =
        Regex("(?:^|\\s)${Regex.escape(name)}=(.*?)(?=\\s[A-Za-z][A-Za-z0-9._-]*=|$)")
            .find(message)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()

    private val STREAM_REGEX = Regex("(?:^|\\s)stream=(true|false)(?:\\s|$)")
    private val TERMINAL_EVENTS = setOf(
        "REQUEST_SUCCESS",
        "REQUEST_FAILED",
        "REQUEST_CANCELLED",
        "REMOTE_CONNECTION_FAILURE",
        "LOCAL_CALL_CANCEL",
    )
    private val FAILURE_EVENTS = setOf("REQUEST_FAILED", "REMOTE_CONNECTION_FAILURE")
    private val REQUEST_PROFILE_HEADERS = listOf(
        "Content-Type",
        "Accept",
        "Accept-Encoding",
        "User-Agent",
        "Connection",
        "Cache-Control",
        "TE",
    )
    private val RESPONSE_PROFILE_HEADERS = listOf(
        "Content-Type",
        "Content-Encoding",
        "Transfer-Encoding",
        "Content-Length",
        "Server",
        "Via",
        "CF-Ray",
        "CF-Cache-Status",
    )

    private data class FailureRecord(
        val durationMs: Long,
        val protocol: String,
        val networkMode: String,
        val exceptionClass: String,
        val eventCount: Long,
        val timeSinceLastEventMs: Long?,
    )

    private fun formatSeconds(milliseconds: Double): String =
        "${(milliseconds / 100.0).roundToLong() / 10.0}s"
}

/** Single logging gateway used by the network diagnostics instrumentation. */
object AiHttpDiag {
    private const val TAG = "AiHttpDiag"

    fun info(
        event: String,
        requestId: String? = null,
        provider: String? = null,
        host: String? = null,
        message: String = "",
    ) = log(AiHttpDiagLevel.INFO, event, requestId, provider, host, message, null)

    fun warn(
        event: String,
        requestId: String? = null,
        provider: String? = null,
        host: String? = null,
        message: String = "",
        throwable: Throwable? = null,
    ) = log(AiHttpDiagLevel.WARN, event, requestId, provider, host, message, throwable)

    fun error(
        event: String,
        requestId: String? = null,
        provider: String? = null,
        host: String? = null,
        message: String = "",
        throwable: Throwable? = null,
    ) = log(AiHttpDiagLevel.ERROR, event, requestId, provider, host, message, throwable)

    private fun log(
        level: AiHttpDiagLevel,
        event: String,
        requestId: String?,
        provider: String?,
        host: String?,
        message: String,
        throwable: Throwable?,
    ) {
        val diagnosticMessage = buildString {
            if (message.isNotBlank()) append(message.trim())
            if (throwable != null) {
                val root = throwable.rootCauseForAiDiagnostics()
                if (isNotEmpty()) append(' ')
                append("exceptionClass=").append(throwable.javaClass.name)
                append(" exceptionMessage=").append(throwable.message.safeForAiDiagnostics())
                append(" rootCause=").append(root.javaClass.name)
                append(" rootMessage=").append(root.message.safeForAiDiagnostics())
            }
        }
        val logcatMessage = buildString {
            append("event=").append(event)
            requestId?.let { append(" requestId=").append(it) }
            provider?.let { append(" provider=").append(it) }
            host?.let { append(" host=").append(it) }
            if (diagnosticMessage.isNotBlank()) append(' ').append(diagnosticMessage)
        }
        when (level) {
            AiHttpDiagLevel.INFO -> Log.i(TAG, logcatMessage)
            AiHttpDiagLevel.WARN -> Log.w(
                TAG,
                throwable?.let { "$logcatMessage\n${it.safeStackTraceForAiDiagnostics()}" } ?: logcatMessage,
            )
            AiHttpDiagLevel.ERROR -> Log.e(
                TAG,
                throwable?.let { "$logcatMessage\n${it.safeStackTraceForAiDiagnostics()}" } ?: logcatMessage,
            )
        }
        AiHttpDiagStore.append(
            level = level,
            event = event,
            requestId = requestId,
            provider = provider,
            host = host,
            message = diagnosticMessage,
            throwable = throwable,
        )
    }
}
