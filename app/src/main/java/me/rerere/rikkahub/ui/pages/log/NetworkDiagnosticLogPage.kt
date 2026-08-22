package me.rerere.rikkahub.ui.pages.log

import android.content.ClipData
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import me.rerere.common.http.AiHttpDiagEntry
import me.rerere.common.http.AiHttpDiagStore
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.ui.theme.JetbrainsMono
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class DiagnosticEventFilter(val label: String) {
    ALL("全部"),
    ERROR("错误"),
    CANCELLATION("取消"),
    SUCCESS("成功"),
}

@Composable
fun NetworkDiagnosticLogPage() {
    val entries by AiHttpDiagStore.entries.collectAsStateWithLifecycle()
    var eventFilter by remember { mutableStateOf(DiagnosticEventFilter.ALL) }
    var providerFilter by remember { mutableStateOf<String?>(null) }
    var requestIdQuery by remember { mutableStateOf("") }
    var showClearConfirmation by remember { mutableStateOf(false) }
    var exportText by remember { mutableStateOf("") }
    var expandedIds by remember { mutableStateOf(emptySet<Long>()) }
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    val providers = remember(entries) {
        entries.mapNotNull { it.provider }.filter { it.isNotBlank() }.distinct().sorted()
    }
    LaunchedEffect(providers, providerFilter) {
        if (providerFilter != null && providerFilter !in providers) providerFilter = null
    }
    val filteredEntries = remember(entries, eventFilter, providerFilter, requestIdQuery) {
        entries.filter { entry ->
            entry.matches(eventFilter) &&
                (providerFilter == null || entry.provider == providerFilter) &&
                (requestIdQuery.isBlank() || entry.requestId.orEmpty().contains(requestIdQuery.trim(), true))
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(exportText) }
                ?: error("无法打开目标文件")
        }.onSuccess {
            Toast.makeText(context, "日志已导出", Toast.LENGTH_SHORT).show()
        }.onFailure {
            Toast.makeText(context, "导出失败：${it.message}", Toast.LENGTH_LONG).show()
        }
    }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text("网络诊断日志") },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { contentPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(contentPadding),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                DiagnosticSummaryCard(
                    count = entries.size,
                    filteredCount = filteredEntries.size,
                    lastUpdatedAt = entries.lastOrNull()?.timestamp,
                    onCopyLatestFailure = {
                        val text = AiHttpDiagStore.latestFailedRequestText()
                        if (text == null) {
                            Toast.makeText(context, "还没有失败或取消的请求", Toast.LENGTH_SHORT).show()
                        } else {
                            scope.launch {
                                clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("AiHttpDiag", text)))
                                Toast.makeText(context, "最近失败请求已复制", Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    onExport = {
                        exportText = AiHttpDiagStore.formatEntries(filteredEntries)
                        val stamp = SimpleDateFormat("yyyy-MM-dd-HHmmss", Locale.ROOT).format(Date())
                        exportLauncher.launch("RikkaHub-AiHttpDiag-$stamp.txt")
                    },
                    onClear = { showClearConfirmation = true },
                )
            }

            item {
                DiagnosticFilters(
                    eventFilter = eventFilter,
                    onEventFilterChange = { eventFilter = it },
                    providers = providers,
                    providerFilter = providerFilter,
                    onProviderFilterChange = { providerFilter = it },
                    requestIdQuery = requestIdQuery,
                    onRequestIdQueryChange = { requestIdQuery = it },
                )
            }

            if (filteredEntries.isEmpty()) {
                item {
                    Text(
                        text = if (entries.isEmpty()) "暂无 AiHttpDiag 日志" else "当前筛选条件下没有日志",
                        modifier = Modifier.fillMaxWidth().padding(24.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                items(filteredEntries, key = { it.id }) { entry ->
                    DiagnosticEntryCard(
                        entry = entry,
                        expanded = entry.id in expandedIds,
                        onToggleExpanded = {
                            expandedIds = if (entry.id in expandedIds) {
                                expandedIds - entry.id
                            } else {
                                expandedIds + entry.id
                            }
                        },
                    )
                }
            }
        }
    }

    if (showClearConfirmation) {
        AlertDialog(
            onDismissRequest = { showClearConfirmation = false },
            title = { Text("清空日志") },
            text = { Text("确定清空所有网络诊断日志吗？") },
            confirmButton = {
                TextButton(onClick = {
                    AiHttpDiagStore.clear()
                    expandedIds = emptySet()
                    showClearConfirmation = false
                }) { Text("清空") }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirmation = false }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun DiagnosticSummaryCard(
    count: Int,
    filteredCount: Int,
    lastUpdatedAt: Long?,
    onCopyLatestFailure: () -> Unit,
    onExport: () -> Unit,
    onClear: () -> Unit,
) {
    val time = remember(lastUpdatedAt) {
        lastUpdatedAt?.let {
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(Date(it))
        } ?: "暂无"
    }
    Card(colors = CustomColors.cardColorsOnSurfaceContainer) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("当前日志：$count 条（筛选后 $filteredCount 条）", style = MaterialTheme.typography.titleSmall)
            Text("最后更新：$time", style = MaterialTheme.typography.bodySmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onCopyLatestFailure) { Text("复制最近失败") }
                OutlinedButton(onClick = onExport) { Text("导出当前筛选") }
                OutlinedButton(onClick = onClear) { Text("清空日志") }
            }
        }
    }
}

@Composable
private fun DiagnosticFilters(
    eventFilter: DiagnosticEventFilter,
    onEventFilterChange: (DiagnosticEventFilter) -> Unit,
    providers: List<String>,
    providerFilter: String?,
    onProviderFilterChange: (String?) -> Unit,
    requestIdQuery: String,
    onRequestIdQueryChange: (String) -> Unit,
) {
    Card(colors = CustomColors.cardColorsOnSurfaceContainer) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("事件类型", style = MaterialTheme.typography.labelLarge)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(DiagnosticEventFilter.entries) { filter ->
                    FilterChip(
                        selected = eventFilter == filter,
                        onClick = { onEventFilterChange(filter) },
                        label = { Text(filter.label) },
                    )
                }
            }
            Text("Provider", style = MaterialTheme.typography.labelLarge)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    FilterChip(
                        selected = providerFilter == null,
                        onClick = { onProviderFilterChange(null) },
                        label = { Text("全部") },
                    )
                }
                items(providers) { provider ->
                    FilterChip(
                        selected = providerFilter == provider,
                        onClick = { onProviderFilterChange(provider) },
                        label = { Text(provider) },
                    )
                }
            }
            OutlinedTextField(
                value = requestIdQuery,
                onValueChange = onRequestIdQueryChange,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("搜索 requestId") },
            )
        }
    }
}

@Composable
private fun DiagnosticEntryCard(
    entry: AiHttpDiagEntry,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
) {
    val errorLike = entry.event in ERROR_EVENTS || entry.event in CANCELLATION_EVENTS
    val time = remember(entry.timestamp) {
        SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date(entry.timestamp))
    }
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onToggleExpanded),
        colors = if (errorLike) {
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.55f))
        } else {
            CustomColors.cardColorsOnSurfaceContainer
        },
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    entry.event,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = if (errorLike) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                )
                Text(time, style = MaterialTheme.typography.labelSmall)
            }
            entry.provider?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            entry.host?.let { Text(it, style = MaterialTheme.typography.bodySmall, fontFamily = JetbrainsMono) }
            entry.requestId?.let {
                Text("requestId=$it", style = MaterialTheme.typography.labelSmall, fontFamily = JetbrainsMono)
            }
            if (entry.message.isNotBlank()) {
                SelectionContainer {
                    Text(
                        entry.message,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = JetbrainsMono,
                        maxLines = if (expanded) Int.MAX_VALUE else 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (expanded) entry.stackTrace?.let {
                SelectionContainer {
                    Text(it, style = MaterialTheme.typography.labelSmall, fontFamily = JetbrainsMono)
                }
            }
        }
    }
}

private fun AiHttpDiagEntry.matches(filter: DiagnosticEventFilter): Boolean = when (filter) {
    DiagnosticEventFilter.ALL -> true
    DiagnosticEventFilter.ERROR -> event in ERROR_EVENTS
    DiagnosticEventFilter.CANCELLATION -> event in CANCELLATION_EVENTS
    DiagnosticEventFilter.SUCCESS -> event == "REQUEST_SUCCESS"
}

private val ERROR_EVENTS = setOf("REQUEST_FAILED", "REMOTE_CONNECTION_FAILURE")
private val CANCELLATION_EVENTS = setOf("REQUEST_CANCELLED", "LOCAL_CALL_CANCEL")
