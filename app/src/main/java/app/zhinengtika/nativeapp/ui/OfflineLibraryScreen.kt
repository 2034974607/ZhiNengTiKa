@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package app.zhinengtika.nativeapp.ui

import android.content.Context
import android.net.Uri
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
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
import app.zhinengtika.nativeapp.data.OfflineTemplateEntry
import app.zhinengtika.nativeapp.data.OfflineTemplateStore
import app.zhinengtika.nativeapp.data.TemplateTransfer
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Offline-only library. Nothing here authenticates against a server, and nothing here can submit.
 * Imported content is stored encrypted and account-independently.
 */
@Composable
fun OfflineLibraryScreen(
    store: OfflineTemplateStore,
    onBack: () -> Unit,
    onOpenCard: (String) -> Unit,
    onMessage: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var cards by remember { mutableStateOf<List<OfflineTemplateEntry>>(emptyList()) }
    var directory by remember { mutableStateOf<List<DirectoryEntry>>(emptyList()) }
    var query by remember { mutableStateOf("") }
    var subjectFilter by remember { mutableStateOf("全部") }
    var directorySubject by remember { mutableStateOf("other") }
    var manualCode by remember { mutableStateOf("") }
    var pendingExport by remember { mutableStateOf<OfflineTemplateEntry?>(null) }
    // Sealed so one dialog can confirm either a card or a directory deletion.
    var pendingDelete by remember { mutableStateOf<PendingDelete?>(null) }

    LaunchedEffect(store) {
        val result = runCatching {
            withContext(Dispatchers.IO) { store.cards() to store.directory() }
        }
        result.onSuccess { (loadedCards, loadedDirectory) ->
            cards = loadedCards
            directory = loadedDirectory
        }.onFailure {
            // Keep existing lists intact; surface the error instead of crashing.
            onMessage("离线资料库读取失败（本机解密或存储错误）")
        }
    }

    val subjects = remember(cards, directory) {
        (listOf("全部") + cards.map { it.subject } + directory.map { it.subject })
            .filter { it.isNotBlank() }.distinct().sorted()
    }
    val normalizedQuery = query.trim()
    val visibleCards = remember(cards, normalizedQuery, subjectFilter) {
        cards.filter { entry ->
            (subjectFilter == "全部" || entry.subject == subjectFilter) &&
                (normalizedQuery.isEmpty() || entry.card.title.contains(normalizedQuery, true) ||
                    entry.card.code.contains(normalizedQuery, true) || entry.subject.contains(normalizedQuery, true))
        }
    }
    val visibleDirectory = remember(directory, normalizedQuery, subjectFilter) {
        directory.filter { entry ->
            (subjectFilter == "全部" || entry.subject == subjectFilter) &&
                (normalizedQuery.isEmpty() || entry.title.contains(normalizedQuery, true) ||
                    entry.code.contains(normalizedQuery, true) || entry.subject.contains(normalizedQuery, true))
        }
    }

    fun persist() {
        scope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) { store.cards() to store.directory() }
            }
            result.onSuccess { (loadedCards, loadedDirectory) ->
                cards = loadedCards
                directory = loadedDirectory
            }.onFailure {
                onMessage("刷新离线资料库失败，列表可能不是最新")
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            var imported = 0
            for (uri in uris) {
                var warnings = emptyList<String>()
                val result = runCatching {
                    val bytes = withContext(Dispatchers.IO) { readBounded(context, uri, TemplateTransfer.MAX_IMPORT_BYTES) }
                    if (!withContext(Dispatchers.IO) { store.canAcceptCard(bytes.size) }) {
                        throw IOException("离线资料库已满或超过总量预算，未导入该文件")
                    }
                    // ContentResolver queries run on IO; legacy files often lack data.templateCode,
                    // so fall back to the file name (without extension) or a user-entered code.
                    val fileName = withContext(Dispatchers.IO) { displayName(context, uri) }
                    val fallback = manualCode.trim().ifBlank {
                        fileName?.substringBeforeLast('.')?.trim()
                    }
                    val importedCard = withContext(Dispatchers.IO) {
                        TemplateTransfer.importJson(bytes, fallback?.ifBlank { null })
                    }
                    warnings = importedCard.warnings
                    val entry = OfflineTemplateEntry(
                        id = UUID.randomUUID().toString(),
                        card = importedCard.card,
                        subject = "other",
                        sourceLabel = fileName ?: "导入文件",
                        warnings = importedCard.warnings,
                    )
                    withContext(Dispatchers.IO) { store.saveCard(entry) }
                }
                if (result.isSuccess) {
                    imported++
                    // Surface all import warnings (read-only, metadata stripped, merged groups).
                    if (warnings.isNotEmpty()) onMessage(warnings.joinToString(" "))
                } else {
                    onMessage("导入失败：${result.exceptionOrNull()?.message.orEmpty()}")
                }
            }
            persist()
            if (imported > 0) onMessage("已导入 $imported 个离线题卡（只读）")
        }
    }

    val directoryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            var added = 0
            for (uri in uris) {
                val result = runCatching {
                    val text = withContext(Dispatchers.IO) {
                        val bytes = readBounded(context, uri, TemplateTransfer.MAX_IMPORT_BYTES)
                        java.nio.charset.StandardCharsets.UTF_8.newDecoder()
                            .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                            .decode(java.nio.ByteBuffer.wrap(bytes)).toString()
                    }
                    val parsed = withContext(Dispatchers.IO) {
                        TemplateTransfer.parseLegacyListWithWarnings(text, directorySubject)
                    }
                    if (!withContext(Dispatchers.IO) { store.canAcceptDirectory(parsed.templates.size) }) {
                        throw IOException("离线目录超过条目上限，未导入该文件")
                    }
                    val fileName = withContext(Dispatchers.IO) { displayName(context, uri) }
                    val entries = parsed.templates.map {
                        DirectoryEntry(
                            id = UUID.randomUUID().toString(),
                            code = it.code,
                            title = it.title,
                            subject = it.subject,
                            sourceLabel = fileName ?: "目录文件",
                        )
                    }
                    withContext(Dispatchers.IO) { store.saveDirectory(entries) }
                    added += entries.size
                    if (parsed.warnings.isNotEmpty()) onMessage(parsed.warnings.joinToString(" "))
                }
                if (result.isFailure) onMessage("目录导入失败：${result.exceptionOrNull()?.message.orEmpty()}")
            }
            persist()
            if (added > 0) onMessage("已导入 $added 条目录记录")
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/html"),
    ) { uri ->
        val entry = pendingExport
        pendingExport = null
        if (uri == null || entry == null) {
            if (uri == null) onMessage("已取消导出")
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            val result = runCatching {
                val html = withContext(Dispatchers.IO) { TemplateTransfer.exportHtml(entry.card) }
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri).use { out ->
                        requireNotNull(out) { "无法写入目标文件" }
                        out.write(html.toByteArray(Charsets.UTF_8))
                    }
                }
            }
            onMessage(if (result.isSuccess) "HTML 导出成功（不含历史答卷）" else "导出失败：${result.exceptionOrNull()?.message.orEmpty()}")
        }
    }

    fun loadDemo() {
        scope.launch {
            val result = runCatching {
                val imported = withContext(Dispatchers.IO) { TemplateTransfer.importJson(DemoContent.json, null) }
                val entry = OfflineTemplateEntry(
                    id = "demo-builtin-001",
                    card = imported.card,
                    subject = DemoContent.SUBJECT,
                    sourceLabel = "合成演示示例",
                    isDemo = true,
                    warnings = imported.warnings,
                )
                withContext(Dispatchers.IO) { store.saveCard(entry) }
            }
            persist()
            onMessage(if (result.isSuccess) "已载入合成演示题卡（非真实题目）" else "演示载入失败")
        }
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("离线资料库") },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.Outlined.ArrowBack, contentDescription = "返回")
                }
            },
        )
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    "无需登录。导入内容仅在本机加密保存，仅供离线阅读，不能提交作业，也不会联网。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("按题名 / 编号 / 学科搜索") },
                )
            }
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    subjects.forEach { subject ->
                        FilterChip(
                            selected = subjectFilter == subject,
                            onClick = { subjectFilter = subject },
                            label = { Text(subject) },
                        )
                    }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        importLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
                    }, modifier = Modifier.weight(1f)) { Text("导入题卡") }
                    Button(onClick = {
                        directoryLauncher.launch(arrayOf("text/plain", "*/*"))
                    }, modifier = Modifier.weight(1f)) { Text("导入目录") }
                }
            }
            item {
                OutlinedTextField(
                    value = directorySubject,
                    onValueChange = { directorySubject = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("导入目录的学科标记") },
                )
                OutlinedTextField(
                    value = manualCode,
                    onValueChange = { manualCode = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("备用题卡编号（可选，文件缺编号时使用）") },
                )
                TextButton(onClick = { loadDemo() }) { Text("载入合成演示示例（非真实题目）") }
            }
            item { Text("离线题卡（${visibleCards.size}）", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold) }
            if (visibleCards.isEmpty()) {
                item { Text("暂无离线题卡。可导入文件或载入演示示例。", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            } else {
                items(visibleCards, key = { it.id }) { entry ->
                    OfflineCardItem(
                        entry = entry,
                        onOpen = { onOpenCard(entry.id) },
                        onExport = {
                            pendingExport = entry
                            exportLauncher.launch(safeFileName(entry.card.code) + ".html")
                        },
                        onDelete = { pendingDelete = PendingDelete.Card(entry.id, entry.card.title) },
                    )
                }
            }
            item { Spacer(Modifier.height(4.dp)) }
            item { Text("离线目录（${visibleDirectory.size}）", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold) }
            if (visibleDirectory.isEmpty()) {
                item { Text("暂无目录记录。", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            } else {
                items(visibleDirectory, key = { it.id }) { entry ->
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp),
                    ) {
                        Text("${entry.title}（${entry.subject}）", style = MaterialTheme.typography.bodyMedium)
                        Text("编号 ${entry.code} · ${entry.sourceLabel}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                            TextButton(onClick = { query = entry.code }) { Text("填入搜索") }
                            TextButton(onClick = {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("题卡编号", entry.code))
                                onMessage("编号已复制")
                            }) { Text("复制编号") }
                            TextButton(onClick = {
                                val match = cards.firstOrNull { it.card.code == entry.code }
                                if (match != null) onOpenCard(match.id) else onMessage("尚未导入该编号的离线题卡")
                            }) { Text("查看同编号卡") }
                            TextButton(onClick = {
                                pendingDelete = PendingDelete.Directory(entry.id, entry.title)
                            }) { Text("移除") }
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                    }
                }
            }
        }
    }

    pendingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除本机离线记录？") },
            text = {
                Text("将删除本机已导入的副本「${target.label}」。不会删除或修改你的原始文件；此操作不能撤销。")
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete = null
                    scope.launch {
                        val result = runCatching {
                            withContext(Dispatchers.IO) {
                                when (target) {
                                    is PendingDelete.Card -> store.deleteCard(target.id)
                                    is PendingDelete.Directory -> store.deleteDirectory(target.id)
                                }
                            }
                        }
                        if (result.isSuccess) {
                            persist()
                            onMessage("已删除本机离线副本")
                        } else {
                            onMessage("删除失败：${result.exceptionOrNull()?.message.orEmpty()}")
                        }
                    }
                }) { Text("删除副本") }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("取消") } },
        )
    }
}

private sealed class PendingDelete(val id: String, val label: String) {
    class Card(id: String, label: String) : PendingDelete(id, label)
    class Directory(id: String, label: String) : PendingDelete(id, label)
}

@Composable
private fun OfflineCardItem(
    entry: OfflineTemplateEntry,
    onOpen: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen),
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(8.dp),
        tonalElevation = 1.dp,
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(entry.card.title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                if (entry.isDemo) {
                    Surface(color = MaterialTheme.colorScheme.tertiaryContainer, shape = RoundedCornerShape(4.dp)) {
                        Text("演示", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            Text("编号 ${entry.card.code} · 学科 ${entry.subject}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("来源 ${entry.sourceLabel} · 只读", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (entry.warnings.isNotEmpty()) {
                Text(
                    entry.warnings.joinToString("；"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onOpen) { Text("查看") }
                TextButton(onClick = onExport) { Text("导出 HTML") }
                TextButton(onClick = onDelete) { Text("删除") }
            }
        }
    }
}

private fun safeFileName(value: String): String =
    value.replace(Regex("[^A-Za-z0-9._()\\-]"), "_").take(80).ifBlank { "offline-card" }

private fun readBounded(context: Context, uri: Uri, maxBytes: Int): ByteArray {
    context.contentResolver.openInputStream(uri).use { input ->
        requireNotNull(input) { "无法读取文件" }
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        var total = 0
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > maxBytes) throw IOException("文件超过 8 MiB")
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }
}

private fun displayName(context: Context, uri: Uri): String? {
    val cursor = context.contentResolver.query(uri, null, null, null, null) ?: return null
    return cursor.use {
        val index = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
        if (index >= 0 && it.moveToFirst()) it.getString(index) else null
    }
}
