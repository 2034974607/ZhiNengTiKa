@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package app.zhinengtika.nativeapp.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.unit.dp
import app.zhinengtika.nativeapp.BuildConfig
import app.zhinengtika.nativeapp.data.UiPreferences

@Composable
fun SettingsScreen(
    onClearLocalData: () -> Unit,
    themeMode: UiPreferences.ThemeMode,
    onThemeModeChange: (UiPreferences.ThemeMode) -> Unit,
    onClearOfflineLibrary: () -> Unit,
) {
    var confirmClear by remember { mutableStateOf(false) }
    var confirmClearLibrary by remember { mutableStateOf(false) }
    var section by remember { mutableStateOf<Section?>(null) }
    val context = LocalContext.current
    var legalAssets by remember { mutableStateOf<List<String>>(emptyList()) }
    var legalAsset by remember { mutableStateOf<String?>(null) }
    var legalText by remember { mutableStateOf("正在读取…") }
    LaunchedEffect(Unit) {
        legalAssets = withContext(Dispatchers.IO) {
            runCatching { context.assets.list("legal")?.sorted().orEmpty() }.getOrDefault(emptyList())
        }
    }
    LaunchedEffect(legalAsset) {
        val name = legalAsset ?: return@LaunchedEffect
        legalText = "正在读取…"
        legalText = withContext(Dispatchers.IO) {
            runCatching { context.assets.open("legal/$name").bufferedReader(Charsets.UTF_8).use { it.readText() } }
                .getOrDefault("无法读取许可文本，请查看随安装包提供的完整源码与许可证。")
        }
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { Text("设置") })
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("版本 ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.titleMedium)

            Text("外观", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                UiPreferences.ThemeMode.values().forEach { mode ->
                    FilterChip(
                        selected = themeMode == mode,
                        onClick = { onThemeModeChange(mode) },
                        label = {
                            Text(
                                when (mode) {
                                    UiPreferences.ThemeMode.SYSTEM -> "跟随系统"
                                    UiPreferences.ThemeMode.LIGHT -> "浅色"
                                    UiPreferences.ThemeMode.DARK -> "深色"
                                },
                            )
                        },
                    )
                }
            }

            Text("隐私与数据", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            Text("扫码、导入、搜索和 HTML 导出都在本机完成。账号会话、题卡副本、浏览历史、草稿、离线资料库和提交状态使用 Android Keystore 加密存储；密码不会保存。")
            Text("清除范围说明：", style = MaterialTheme.typography.bodyMedium, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
            Text("· “清除在线缓存与历史” 只删除线上题卡的加密缓存和最近访问记录；不会退出账号、不会删除已提交作业，也不会清除草稿或提交状态标记。")
            Text("· “清除离线资料库” 只删除 SAF 导入/演示的离线题卡与目录记录。")
            Text("· 手动解锁提交只清除本机的“结果未知”标记，不会改变服务端状态。")
            Button(onClick = { confirmClear = true }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Outlined.DeleteOutline, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("清除在线缓存与历史")
            }
            Button(onClick = { confirmClearLibrary = true }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Outlined.DeleteOutline, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("清除离线资料库")
            }

            Text("说明", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            InfoButton("常见问题", Section.FAQ) { section = it }
            InfoButton("关于", Section.ABOUT) { section = it }
            InfoButton("隐私说明", Section.PRIVACY) { section = it }
            InfoButton("开源说明", Section.OPEN_SOURCE) { section = it }

            Text("项目仓库", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("本项目", style = MaterialTheme.typography.titleMedium)
                    GitHubLink("github.com/2034974607/ZhiNengTiKa", "2034974607/ZhiNengTiKa")
                    Text("上游仓库", style = MaterialTheme.typography.titleMedium)
                    GitHubLink("github.com/LFWQSP2641/ZhiNengTiKa", "LFWQSP2641/ZhiNengTiKa")
                }
            }

            Text("贡献人员", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            ContributorCard(
                name = "LFWQSP2641",
                contribution = "上游仓库作者",
                githubUsername = "LFWQSP2641",
            )
            ContributorCard(
                name = "2034974607",
                contribution = "项目贡献",
                githubUsername = "2034974607",
            )
            ContributorCard(
                name = "OpenAI",
                contribution = "GPT-6.1-Sol · AI 辅助开发",
                githubUsername = "openai",
            )

            Text(
                "本应用为独立原生重写版本，采用 Kotlin / Jetpack Compose / Material 3，不含 WebView。安装包不包含旧版题卡数据或个人记录。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
        }
    }

    section?.takeIf { legalAsset == null }?.let { current ->
        AlertDialog(
            onDismissRequest = { section = null },
            title = { Text(current.title) },
            text = {
                Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState())) {
                    Text(current.body)
                    if (current == Section.OPEN_SOURCE) {
                        Spacer(Modifier.height(12.dp))
                        legalAssets.forEach { name ->
                            TextButton(onClick = { legalAsset = name }) { Text(name) }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { section = null }) { Text("关闭") } },
        )
    }
    legalAsset?.let { name ->
        AlertDialog(
            onDismissRequest = { legalAsset = null },
            title = { Text(name) },
            text = {
                SelectionContainer {
                    Text(legalText, modifier = Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()))
                }
            },
            confirmButton = { TextButton(onClick = { legalAsset = null }) { Text("返回") } },
        )
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("清除在线缓存与历史？") },
            text = { Text("将删除线上题卡的加密缓存和最近访问记录；不会退出账号、删除已提交作业，也不会清除草稿或提交状态标记。此操作不能撤销。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    onClearLocalData()
                }) { Text("清除") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("取消") } },
        )
    }
    if (confirmClearLibrary) {
        AlertDialog(
            onDismissRequest = { confirmClearLibrary = false },
            title = { Text("清除离线资料库？") },
            text = { Text("将删除所有 SAF 导入及演示的离线题卡与目录记录。此操作不能撤销。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmClearLibrary = false
                    onClearOfflineLibrary()
                }) { Text("清除") }
            },
            dismissButton = { TextButton(onClick = { confirmClearLibrary = false }) { Text("取消") } },
        )
    }
}

@Composable
private fun InfoButton(label: String, section: Section, onSelect: (Section) -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        TextButton(onClick = { onSelect(section) }, modifier = Modifier.fillMaxWidth()) {
            Text(label, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun ContributorCard(name: String, contribution: String, githubUsername: String) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(name, style = MaterialTheme.typography.titleMedium)
            Text(
                contribution,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            GitHubLink("GitHub · @$githubUsername", githubUsername)
        }
    }
}

@Composable
private fun GitHubLink(label: String, path: String) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    TextButton(
        onClick = {
            runCatching { uriHandler.openUri("https://github.com/$path") }
                .onFailure {
                    Toast.makeText(context, "无法打开 GitHub 链接，请检查是否安装浏览器。", Toast.LENGTH_LONG).show()
                }
        },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(label, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(8.dp))
        Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = null)
    }
}

private enum class Section(val title: String, val body: String) {
    FAQ(
        "常见问题",
        "1. 离线资料库需要登录吗？不需要。导入、搜索和导出都在本机完成。\n" +
            "2. 导入的题卡能提交吗？不能。导入内容始终只读，不构成账号身份或提交依据。\n" +
            "3. 提交结果未知怎么办？应用不会自动重试不可撤回的提交。请先在服务端人工核实，再决定是否解锁本机标记。\n" +
            "4. 扫码上传图片吗？不上传。二维码在本机识别，不会把图片发给第三方服务。\n" +
            "5. 没有网络能用吗？可查看已缓存/已导入的题卡；新题卡查询和提交需要网络。",
    ),
    ABOUT(
        "关于",
        "新教育智能题卡（原生重写版）${BuildConfig.VERSION_NAME}。\n" +
            "这是独立实现的原生 Android 应用，使用 Kotlin、Jetpack Compose、Material 3。它与旧版应用使用不同的应用标识与签名身份，不会自动读取旧版应用的私有数据。",
    ),
    PRIVACY(
        "隐私说明",
        "密码仅在登录请求中使用，不写入本机存储。账号会话、题卡、历史、草稿、离线资料和提交状态经 Android Keystore 密钥加密后保存在应用私有目录。\n" +
            "应用不含广告与统计 SDK；不会上传题库、通讯录或相册。二维码识别在本机进行。查询题卡与提交作业会连接新教育服务。",
    ),
    OPEN_SOURCE(
        "开源说明",
        "本项目以 GNU AGPL v3 发布。依赖 Kotlin、Jetpack Compose / Material 3、Navigation Compose、CameraX、ZXing Core、OkHttp、Coil、Jsoup 与 Kotlinx Serialization 等组件，各自遵循其许可证。下方可查看随安装包附带的许可全文及归属说明。\n" +
            "题库与模板资源的版权状态尚未核实，因此安装包不含任何旧版题库或模板数据；示例内容为合成的演示数据。",
    ),
}
