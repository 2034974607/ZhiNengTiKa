@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package app.zhinengtika.nativeapp.ui

import android.widget.Toast
import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.zhinengtika.nativeapp.BuildConfig
import app.zhinengtika.nativeapp.R
import app.zhinengtika.nativeapp.data.UiPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .widthIn(max = 640.dp)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(top = 28.dp, bottom = 32.dp),
        ) {
            Text(
                "设置",
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 4.dp).semantics { heading() },
            )
            Text(
                "按你的习惯使用",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, top = 4.dp, bottom = 24.dp),
            )

            SettingsGroup {
                SettingsRow(
                    icon = Icons.Outlined.Shield,
                    title = "隐私与数据",
                    description = "离线导入与搜索在本机完成，账号与题卡数据加密保存",
                    onClick = { section = Section.PRIVACY },
                )
            }

            SettingsHeading("应用")
            SettingsGroup {
                SettingsRow(
                    icon = Icons.Outlined.Info,
                    title = "应用版本",
                    description = BuildConfig.VERSION_NAME,
                )
                SettingsRow(
                    icon = Icons.Outlined.Code,
                    title = "关于智能题卡",
                    description = "使用说明与应用介绍",
                    onClick = { section = Section.ABOUT },
                )
                SettingsRow(
                    icon = Icons.AutoMirrored.Outlined.HelpOutline,
                    title = "常见问题",
                    description = "离线使用、扫码与作业提交",
                    onClick = { section = Section.FAQ },
                )
                SettingsRow(
                    icon = Icons.Outlined.Description,
                    title = "开源说明",
                    description = "开源许可与第三方组件",
                    onClick = { section = Section.OPEN_SOURCE },
                )
            }

            SettingsHeading("贡献人员")
            SettingsGroup {
                Spacer(Modifier.height(4.dp))
                ContributorRow(
                    name = "LFWQSP2641",
                    contribution = "上游仓库作者",
                    githubUsername = "LFWQSP2641",
                    avatar = R.drawable.avatar_lfwqsp2641,
                )
                ContributorRow(
                    name = "2034974607",
                    contribution = "项目贡献",
                    githubUsername = "2034974607",
                    avatar = R.drawable.avatar_2034974607,
                )
                ContributorRow(
                    name = "OpenAI",
                    contribution = "GPT-6.1-Sol · AI 辅助开发",
                    githubUsername = "openai",
                    avatar = R.drawable.avatar_openai,
                )
                Text(
                    "点击条目，在外部浏览器或应用中打开 GitHub 主页",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 20.dp),
                )
            }

            SettingsHeading("项目仓库")
            SettingsGroup {
                RepositoryRow("GitHub 仓库", "2034974607/ZhiNengTiKa")
                RepositoryRow("上游仓库", "LFWQSP2641/ZhiNengTiKa")
            }

            SettingsHeading("外观")
            SettingsGroup {
                SettingsRow(
                    icon = Icons.Outlined.Palette,
                    title = "主题模式",
                    description = "选择适合你的外观",
                )
                FlowRow(
                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
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
            }

            SettingsHeading("数据管理")
            SettingsGroup {
                SettingsRow(
                    icon = Icons.Outlined.Storage,
                    title = "清除范围说明",
                    description = "了解缓存、离线资料与提交标记的区别",
                    onClick = { section = Section.DATA },
                )
                SettingsRow(
                    icon = Icons.Outlined.DeleteOutline,
                    title = "清除在线缓存与历史",
                    description = "删除线上题卡缓存和最近访问记录",
                    onClick = { confirmClear = true },
                    tint = MaterialTheme.colorScheme.error,
                )
                SettingsRow(
                    icon = Icons.Outlined.DeleteOutline,
                    title = "清除离线资料库",
                    description = "删除已导入的离线题卡与目录",
                    onClick = { confirmClearLibrary = true },
                    tint = MaterialTheme.colorScheme.error,
                )
            }

            Text(
                "本机处理 · 无广告 · 原生 Android",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 28.dp, bottom = 4.dp),
            )
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
private fun SettingsHeading(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 28.dp, bottom = 12.dp).semantics { heading() },
    )
}

@Composable
private fun SettingsGroup(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.38f)
            .compositeOver(MaterialTheme.colorScheme.background),
    ) {
        Column(content = content)
    }
}

@Composable
private fun SettingsRow(
    icon: ImageVector,
    title: String,
    description: String,
    onClick: (() -> Unit)? = null,
    tint: Color = MaterialTheme.colorScheme.onSurface,
) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 80.dp)
            .then(if (onClick == null) Modifier else Modifier.clickable(role = Role.Button, onClick = onClick))
            .padding(horizontal = 20.dp, vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Icon(icon, contentDescription = null, tint = tint.copy(alpha = 0.78f), modifier = Modifier.size(24.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = tint)
            Text(
                description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (onClick != null) {
            Icon(
                Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun ContributorRow(
    name: String,
    contribution: String,
    githubUsername: String,
    @DrawableRes avatar: Int,
) {
    val openGitHub = rememberGitHubOpener(githubUsername)
    Row(
        modifier = Modifier.fillMaxWidth()
            .clickable(role = Role.Button, onClickLabel = "打开 $name 的 GitHub 主页", onClick = openGitHub)
            .padding(horizontal = 20.dp, vertical = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Image(
            painter = painterResource(avatar),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(56.dp).clip(CircleShape)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape),
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                contribution,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "GitHub · @$githubUsername",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Icon(
                    Icons.AutoMirrored.Outlined.OpenInNew,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
    }
}

@Composable
private fun RepositoryRow(title: String, path: String) {
    val openGitHub = rememberGitHubOpener(path)
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 88.dp)
            .clickable(role = Role.Button, onClickLabel = "打开 $path 仓库", onClick = openGitHub)
            .padding(horizontal = 20.dp, vertical = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Icon(
            painterResource(R.drawable.ic_github),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(24.dp),
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                "github.com/$path",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Icon(
            Icons.AutoMirrored.Outlined.OpenInNew,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
    }
}

@Composable
private fun rememberGitHubOpener(path: String): () -> Unit {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    return remember(context, uriHandler, path) {
        {
            runCatching { uriHandler.openUri("https://github.com/$path") }
                .onFailure {
                    Toast.makeText(context, "无法打开 GitHub 链接，请检查是否安装浏览器。", Toast.LENGTH_LONG).show()
                }
            Unit
        }
    }
}

private enum class Section(val title: String, val body: String) {
    DATA(
        "清除范围说明",
        "扫码、导入、搜索和 HTML 导出都在本机完成。账号会话、题卡副本、浏览历史、草稿、离线资料库和提交状态使用 Android Keystore 加密存储；密码不会保存。\n\n" +
            "· “清除在线缓存与历史” 只删除所有账号线上题卡的加密缓存和最近访问记录；不会退出账号、不会删除已提交作业，也不会清除草稿或提交状态标记。\n\n" +
            "· “清除离线资料库” 只删除 SAF 导入/演示的离线题卡与目录记录。\n\n" +
            "· 手动解锁提交只清除本机的“结果未知”标记，不会改变服务端状态。",
    ),
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
            "这是独立实现的原生 Android 应用，使用 Kotlin、Jetpack Compose、Material 3，不含 WebView。它与旧版应用使用不同的应用标识与签名身份，不会自动读取旧版应用的私有数据。安装包不包含旧版题卡数据或个人记录。",
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
