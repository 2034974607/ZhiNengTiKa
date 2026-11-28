@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package app.zhinengtika.nativeapp.ui

import android.content.Context
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.zhinengtika.nativeapp.data.DemoContent
import app.zhinengtika.nativeapp.data.DirectoryEntry
import app.zhinengtika.nativeapp.data.OfflineCardSummary
import app.zhinengtika.nativeapp.data.OfflineImportProgress
import app.zhinengtika.nativeapp.data.OfflineImportReport
import app.zhinengtika.nativeapp.data.OfflineLibraryImporter
import app.zhinengtika.nativeapp.data.OfflineSubjects
import app.zhinengtika.nativeapp.data.OfflineTemplateEntry
import app.zhinengtika.nativeapp.data.OfflineTemplateStore
import app.zhinengtika.nativeapp.data.TemplateTransfer
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Lists retain metadata only; question bodies are decrypted when a card is opened. */
@Composable
fun OfflineLibraryScreen(
    store: OfflineTemplateStore,
    onBack: () -> Unit,
    onOpenCard: (String) -> Unit,
    onMessage: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var cards by remember { mutableStateOf<List<OfflineCardSummary>>(emptyList()) }
    var directory by remember { mutableStateOf<List<DirectoryEntry>>(emptyList()) }
    var query by remember { mutableStateOf("") }
    var subjectFilter by remember { mutableStateOf("全部") }
    var showDirectory by remember { mutableStateOf(true) }
    var expandedSubjects by remember { mutableStateOf(setOf<String>()) }
    var showOptions by remember { mutableStateOf(false) }
    var importSubject by remember { mutableStateOf("自动识别") }
    var manualCode by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }
    var importing by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf<OfflineImportProgress?>(null) }
    var lastReport by remember { mutableStateOf<OfflineImportReport?>(null) }
    var showReport by remember { mutableStateOf(false) }
    var pendingExport by remember { mutableStateOf<OfflineCardSummary?>(null) }
    var pendingDelete by remember { mutableStateOf<PendingDelete?>(null) }

    BackHandler(enabled = importing) { /* Finish the current batch before leaving. */ }

    suspend fun refresh() {
        val loaded = withContext(Dispatchers.IO) { store.summaries() to store.directory() }
        cards = loaded.first
        directory = loaded.second
    }

    LaunchedEffect(store) {
        try {
            refresh()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            onMessage("离线资料库读取失败（本机解密或存储错误）")
        } finally {
            loading = false
        }
    }

    fun importFiles(uris: List<Uri>, directoriesOnly: Boolean = false, archiveOnly: Boolean = false) {
        if (uris.isEmpty() || importing) return
        val subjectHint = importSubject.takeUnless { it == "自动识别" }
        val fallbackCode = manualCode.trim().takeIf { uris.size == 1 && it.isNotBlank() }
        importing = true
        lastReport = null
        scope.launch {
            try {
                val batch = withContext(Dispatchers.IO) {
                    OfflineLibraryImporter(
                        saveCard = { store.saveCard(it) },
                        saveDirectory = { store.saveDirectory(it) },
                        existingCodes = store.summaries().map { it.code }.toSet(),
                        knownSubjects = store.directory().associate { it.code to it.subject },
                    )
                }
                for ((index, uri) in uris.withIndex()) {
                    currentCoroutineContext().ensureActive()
                    val name = withContext(Dispatchers.IO) { displayName(context, uri) } ?: "导入文件 ${index + 1}"
                    progress = OfflineImportProgress(index, uris.size, name)
                    withContext(Dispatchers.IO) {
                        try {
                            if (archiveOnly || name.endsWith(".zip", true)) {
                                val temporary = cacheArchive(context, uri)
                                try {
                                    val importContext = currentCoroutineContext()
                                    batch.importZip(temporary, subjectHint,
                                        onProgress = { progress = it },
                                        checkCancelled = { importContext.ensureActive() },
                                    )
                                } finally {
                                    // Only the temporary copy created in our own cache is removed.
                                    check(temporary.canonicalFile.parentFile == context.cacheDir.canonicalFile)
                                    temporary.delete()
                                }
                            } else {
                                val bytes = context.contentResolver.openInputStream(uri).use { input ->
                                    requireNotNull(input) { "无法读取文件" }
                                    OfflineLibraryImporter.readBounded(input, TemplateTransfer.MAX_IMPORT_BYTES)
                                }
                                val sourcePath = uri.path.orEmpty() + "/" + name
                                if (directoriesOnly) batch.importDirectory(bytes, sourcePath, subjectHint)
                                else batch.importCard(bytes, sourcePath, subjectHint, fallbackCode)
                            }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Exception) {
                            batch.recordFailure(name, error)
                        }
                    }
                }
                refresh()
                val report = batch.report()
                lastReport = report
                showDirectory = true
                subjectFilter = "全部"
                onMessage(report.summary())
                if (report.failedFiles > 0) showReport = true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                onMessage("导入失败：${error.message.orEmpty()}")
            } finally {
                importing = false
                progress = null
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        importFiles(uris)
    }
    val directoryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        importFiles(uris, directoriesOnly = true)
    }
    val archiveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importFiles(listOf(uri), archiveOnly = true)
    }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/html")) { uri ->
        val entry = pendingExport
        pendingExport = null
        if (uri != null && entry != null) scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val card = requireNotNull(store.card(entry.id)) { "题卡已不存在" }
                    val html = TemplateTransfer.exportHtml(card.card)
                    context.contentResolver.openOutputStream(uri).use { output ->
                        requireNotNull(output) { "无法写入目标文件" }
                        output.write(html.toByteArray(Charsets.UTF_8))
                    }
                }
                onMessage("HTML 导出成功")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                onMessage("导出失败：${error.message.orEmpty()}")
            }
        }
    }

    fun loadDemo() {
        scope.launch {
            try {
                val subject = withContext(Dispatchers.IO) {
                    val imported = TemplateTransfer.importJson(DemoContent.json)
                    val entry = OfflineTemplateEntry(
                        id = "demo-builtin-001", card = imported.card,
                        subject = DemoContent.SUBJECT, sourceLabel = "合成演示示例", isDemo = true,
                    )
                    store.saveCard(entry)
                    OfflineSubjects.resolve(entry.card.code, entry.card.title, explicitSubject = entry.subject)
                }
                refresh()
                showDirectory = false
                subjectFilter = subject
                onMessage("已载入合成演示题卡（非真实题目）")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                onMessage("演示载入失败：${error.message.orEmpty()}")
            }
        }
    }

    val normalizedQuery = query.trim()
    val visibleCards = remember(cards, normalizedQuery, subjectFilter) {
        cards.filter {
            (subjectFilter == "全部" || it.subject == subjectFilter) &&
                (normalizedQuery.isEmpty() || it.title.contains(normalizedQuery, true) ||
                    it.code.contains(normalizedQuery, true) || it.subject.contains(normalizedQuery, true))
        }
    }
    val visibleDirectory = remember(directory, normalizedQuery, subjectFilter) {
        directory.filter {
            (subjectFilter == "全部" || it.subject == subjectFilter) &&
                (normalizedQuery.isEmpty() || it.title.contains(normalizedQuery, true) ||
                    it.code.contains(normalizedQuery, true) || it.subject.contains(normalizedQuery, true))
        }
    }
    val cardsBySubject = remember(visibleCards) { visibleCards.groupBy { it.subject } }
    val directoryBySubject = remember(visibleDirectory) { visibleDirectory.groupBy { it.subject } }
    val cardsByCode = remember(cards) { cards.associateBy { it.code } }
    val subjects = remember(cards, directory) {
        listOf("全部") + OfflineSubjects.order(OfflineSubjects.ordered.take(6) + cards.map { it.subject } + directory.map { it.subject })
    }
    val groups = OfflineSubjects.order(if (showDirectory) directoryBySubject.keys else cardsBySubject.keys)

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("离线资料库") },
            navigationIcon = {
                IconButton(onClick = onBack, enabled = !importing) {
                    Icon(Icons.Outlined.ArrowBack, contentDescription = "返回")
                }
            },
        )
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("${cards.size} 张题卡 · ${directory.size} 条目录", style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { importLauncher.launch(arrayOf("*/*")) }, enabled = !importing && !loading,
                    modifier = Modifier.weight(1f)) { Text("导入题卡") }
                Button(onClick = { directoryLauncher.launch(arrayOf("text/plain", "*/*")) }, enabled = !importing && !loading,
                    modifier = Modifier.weight(1f)) { Text("导入目录") }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { archiveLauncher.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")) },
                    enabled = !importing && !loading) { Text("整库 ZIP") }
                TextButton(onClick = { showOptions = !showOptions }, enabled = !importing) { Text(if (showOptions) "收起选项" else "导入选项") }
                if (lastReport != null) TextButton(onClick = { showReport = true }) { Text("导入结果") }
            }
            if (importing) {
                val current = progress
                if (current != null && current.total > 0) {
                    LinearProgressIndicator(progress = { current.completed.toFloat() / current.total }, modifier = Modifier.fillMaxWidth())
                    Text("正在导入 ${current.completed}/${current.total} · ${current.label}", style = MaterialTheme.typography.bodySmall, maxLines = 1)
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text("正在准备导入…", style = MaterialTheme.typography.bodySmall)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = showDirectory, onClick = { showDirectory = true }, label = { Text("目录（${visibleDirectory.size}）") })
                FilterChip(selected = !showDirectory, onClick = { showDirectory = false }, label = { Text("题卡（${visibleCards.size}）") })
            }
            OutlinedTextField(value = query, onValueChange = { query = it }, singleLine = true,
                modifier = Modifier.fillMaxWidth(), label = { Text("按题名 / 编号 / 学科搜索") })
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                subjects.forEach { subject ->
                    FilterChip(selected = subjectFilter == subject, onClick = { subjectFilter = subject }, label = { Text(subject) })
                }
            }
        }
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (showOptions) {
                item {
                    Text("导入科目：$importSubject", style = MaterialTheme.typography.labelLarge)
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        (listOf("自动识别") + OfflineSubjects.ordered.take(6)).forEach { subject ->
                            FilterChip(selected = importSubject == subject, onClick = { importSubject = subject }, label = { Text(subject) })
                        }
                    }
                    OutlinedTextField(value = manualCode, onValueChange = { manualCode = it }, singleLine = true,
                        modifier = Modifier.fillMaxWidth(), label = { Text("单张导入备用编号（可选）") })
                    Text("默认从文件、目录和题卡编号识别科目；选择科目可覆盖本批分类。", style = MaterialTheme.typography.bodySmall)
                }
            }
            if (loading) item { Text("正在读取资料库…") }
            if (!loading && groups.isEmpty()) item {
                Text(if (normalizedQuery.isNotBlank()) "没有匹配的记录。" else "暂无记录。可直接导入整库 ZIP，也可多选题卡或目录文件。")
            }
            groups.forEach { subject ->
                val subjectCards = cardsBySubject[subject].orEmpty()
                val subjectDirectory = directoryBySubject[subject].orEmpty()
                val expanded = subjectFilter != "全部" || normalizedQuery.isNotEmpty() || subject in expandedSubjects
                item(key = "group:$subject") {
                    Card(Modifier.fillMaxWidth().clickable {
                        expandedSubjects = if (subject in expandedSubjects) expandedSubjects - subject else expandedSubjects + subject
                    }) {
                        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(subject, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Text("${if (showDirectory) subjectDirectory.size else subjectCards.size} ${if (showDirectory) "条目录" else "张题卡"}  ${if (expanded) "▾" else "▸"}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                if (expanded && showDirectory) {
                    items(subjectDirectory, key = { "directory:${it.id}" }) { entry ->
                        val match = cardsByCode[entry.code]
                        Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                            Text(entry.title, style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.clickable(enabled = match != null) { match?.let { onOpenCard(it.id) } })
                            Text("${entry.subject} · ${entry.code}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Row {
                                TextButton(onClick = { match?.let { onOpenCard(it.id) } }, enabled = match != null && !importing) {
                                    Text(if (match != null) "阅读题卡" else "尚未导入题卡")
                                }
                                TextButton(onClick = {
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("题卡编号", entry.code))
                                    onMessage("编号已复制")
                                }) { Text("复制编号") }
                                TextButton(onClick = { pendingDelete = PendingDelete.Directory(entry.id, entry.title) }, enabled = !importing) { Text("移除") }
                            }
                            HorizontalDivider()
                        }
                    }
                } else if (expanded) {
                    items(subjectCards, key = { "card:${it.id}" }) { entry ->
                        OfflineCardItem(entry,
                            onOpen = { onOpenCard(entry.id) },
                            onExport = {
                                pendingExport = entry
                                exportLauncher.launch(safeFileName(entry.code) + ".html")
                            },
                            onDelete = { pendingDelete = PendingDelete.Card(entry.id, entry.title) },
                            enabled = !importing,
                        )
                    }
                }
            }
            item {
                Text("导入内容只在本机加密保存，仅供离线阅读。历史答卷与账号资料不会导入。", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = { loadDemo() }, enabled = !importing && !loading) { Text("载入合成演示示例（非真实题目）") }
            }
        }
    }

    if (showReport) lastReport?.let { report ->
        AlertDialog(onDismissRequest = { showReport = false }, title = { Text("导入结果") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) { Text(report.details()) }
            },
            confirmButton = { TextButton(onClick = { showReport = false }) { Text("知道了") } },
        )
    }
    pendingDelete?.let { target ->
        AlertDialog(onDismissRequest = { pendingDelete = null }, title = { Text("删除本机离线记录？") },
            text = { Text("将删除本机副本「${target.label}」。不会修改原始文件。") },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete = null
                    scope.launch {
                        try {
                            withContext(Dispatchers.IO) {
                                when (target) {
                                    is PendingDelete.Card -> store.deleteCard(target.id)
                                    is PendingDelete.Directory -> store.deleteDirectory(target.id)
                                }
                            }
                            refresh()
                            onMessage("已删除本机离线副本")
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Exception) {
                            onMessage("删除失败：${error.message.orEmpty()}")
                        }
                    }
                }) { Text("删除副本") }
            }, dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("取消") } },
        )
    }
}

private sealed class PendingDelete(val id: String, val label: String) {
    class Card(id: String, label: String) : PendingDelete(id, label)
    class Directory(id: String, label: String) : PendingDelete(id, label)
}

@Composable
private fun OfflineCardItem(entry: OfflineCardSummary, onOpen: () -> Unit, onExport: () -> Unit, onDelete: () -> Unit, enabled: Boolean) {
    Surface(Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onOpen), shape = RoundedCornerShape(8.dp), tonalElevation = 1.dp) {
        Column(Modifier.padding(14.dp)) {
            Text(entry.title, style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(4.dp))
            Text("${entry.subject} · ${entry.code}${if (entry.isDemo) " · 演示" else ""}", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            val specificWarnings = entry.warnings.filterNot { it in TemplateTransfer.importNotices }
            if (specificWarnings.isNotEmpty()) Text(specificWarnings.joinToString("；"), style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onOpen, enabled = enabled) { Text("查看") }
                TextButton(onClick = onExport, enabled = enabled) { Text("导出 HTML") }
                TextButton(onClick = onDelete, enabled = enabled) { Text("删除") }
            }
        }
    }
}

private fun safeFileName(value: String): String = value.replace(Regex("[^A-Za-z0-9._()\\-]"), "_").take(80).ifBlank { "offline-card" }

private fun displayName(context: Context, uri: Uri): String? =
    context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        val column = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
        if (column >= 0 && cursor.moveToFirst()) cursor.getString(column) else null
    }

private fun cacheArchive(context: Context, uri: Uri): File {
    val target = File.createTempFile("offline-import-", ".zip", context.cacheDir)
    try {
        context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "无法读取压缩包" }
            target.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > OfflineLibraryImporter.MAX_ARCHIVE_BYTES) throw IOException("压缩包不能超过 128 MiB")
                    output.write(buffer, 0, count)
                }
            }
        }
        return target
    } catch (error: Exception) {
        check(target.canonicalFile.parentFile == context.cacheDir.canonicalFile)
        target.delete()
        throw error
    }
}
