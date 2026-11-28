@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package app.zhinengtika.nativeapp.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import app.zhinengtika.nativeapp.data.StudentAccount
import app.zhinengtika.nativeapp.data.TemplateHistoryEntry

@Composable
fun HomeScreen(
    account: StudentAccount?,
    history: List<TemplateHistoryEntry>,
    loading: Boolean,
    scannedCode: String,
    onScannedCodeConsumed: () -> Unit,
    onFetch: (String) -> Unit,
    onOpenHistory: (String) -> Unit,
    onScan: () -> Unit,
    onOpenOfflineLibrary: () -> Unit,
) {
    var code by remember { mutableStateOf("") }
    var historyQuery by remember { mutableStateOf("") }
    val visibleHistory = remember(history, historyQuery) {
        val query = historyQuery.trim()
        history.filter { query.isEmpty() || it.title.contains(query, ignoreCase = true) || it.code.contains(query, ignoreCase = true) }
    }
    LaunchedEffect(scannedCode) {
        if (scannedCode.isNotBlank()) {
            code = scannedCode.trim()
            onScannedCodeConsumed()
        }
    }

    Column(Modifier.fillMaxSize()) {
        androidx.compose.material3.TopAppBar(
            title = { Text("新教育智能题卡") },
            actions = {
                IconButton(onClick = onOpenOfflineLibrary) {
                    Icon(Icons.Outlined.FolderOpen, contentDescription = "离线资料库")
                }
                IconButton(onClick = onScan) {
                    Icon(Icons.Outlined.QrCodeScanner, contentDescription = "扫码")
                }
            },
        )
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, top = 8.dp, end = 20.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.surface,
                    shape = RoundedCornerShape(8.dp),
                    tonalElevation = 1.dp,
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text("查询题卡", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(10.dp))
                        OutlinedTextField(
                            value = code,
                            onValueChange = { code = it },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            label = { Text("题卡编号") },
                            keyboardOptions = KeyboardOptions(
                                capitalization = KeyboardCapitalization.None,
                                imeAction = ImeAction.Search,
                            ),
                            trailingIcon = {
                                IconButton(onClick = onScan) {
                                    Icon(Icons.Outlined.QrCodeScanner, contentDescription = "扫描编号")
                                }
                            },
                        )
                        Spacer(Modifier.height(12.dp))
                        Button(
                            onClick = { onFetch(code) },
                            enabled = code.isNotBlank() && !loading,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            if (loading) {
                                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.size(10.dp))
                                Text("正在读取")
                            } else {
                                Icon(Icons.Outlined.Search, contentDescription = null)
                                Spacer(Modifier.size(8.dp))
                                Text("查询")
                            }
                        }
                    }
                }
            }
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = account?.let { "当前账号：${it.displayName.ifBlank { it.username }}" } ?: "未登录",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            text = if (account == null) "登录后可查询新题卡和提交作业" else "题卡会加密缓存在本机",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (account == null) {
                        TextButton(onClick = onScan) { Text("扫码") }
                    }
                }
            }
            item {
                Surface(
                    modifier = Modifier.fillMaxWidth().clickable(onClick = onOpenOfflineLibrary),
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 1.dp,
                ) {
                    Row(
                        Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Outlined.FolderOpen, contentDescription = null)
                        Spacer(Modifier.size(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("离线资料库", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                            Text("无需登录，可导入题卡 / 目录并导出 HTML", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            item {
                Text("最近访问", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            if (history.isNotEmpty()) {
                item {
                    OutlinedTextField(
                        value = historyQuery,
                        onValueChange = { historyQuery = it },
                        label = { Text("搜索历史名称或编号") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                    )
                }
            }
            if (history.isEmpty()) {
                item {
                    Text(
                        "查询后的题卡会显示在这里",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 12.dp),
                    )
                }
            } else if (visibleHistory.isEmpty()) {
                item { Text("没有匹配的历史记录", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            } else {
                items(visibleHistory, key = TemplateHistoryEntry::code) { entry ->
                    Column {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onOpenHistory(entry.code) }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(entry.title, style = MaterialTheme.typography.bodyLarge)
                                Text(entry.code, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            IconButton(onClick = { onOpenHistory(entry.code) }) {
                                Icon(Icons.Outlined.Search, contentDescription = "打开题卡")
                            }
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                    }
                }
            }
        }
    }
}
