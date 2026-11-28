@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package app.zhinengtika.nativeapp.ui

import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.text.Html
import android.util.TypedValue
import android.widget.TextView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import app.zhinengtika.nativeapp.data.ApiException
import app.zhinengtika.nativeapp.data.AnswerLetters
import app.zhinengtika.nativeapp.data.AnswerSubmission
import app.zhinengtika.nativeapp.data.DraftStore
import app.zhinengtika.nativeapp.data.QuestionItem
import app.zhinengtika.nativeapp.data.StoredAnswerDraft
import app.zhinengtika.nativeapp.data.StudentAccount
import app.zhinengtika.nativeapp.data.StudentApi
import app.zhinengtika.nativeapp.data.SubmissionJournal
import app.zhinengtika.nativeapp.data.TemplateCard
import app.zhinengtika.nativeapp.data.TemplateTransfer
import coil.compose.AsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlin.math.max
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private data class DisplayQuestion(
    val item: QuestionItem,
    val parentHtml: String? = null,
    val label: String,
)

private data class AnswerDraft(
    val selected: Set<String> = emptySet(),
    val photos: List<Uri> = emptyList(),
    val existingImageUrls: List<String> = emptyList(),
)

@OptIn(FlowPreview::class)
@Composable
fun QuestionDetailScreen(
    card: TemplateCard?,
    account: StudentAccount?,
    api: StudentApi,
    fromCache: Boolean,
    loading: Boolean,
    error: String?,
    onBack: () -> Unit,
    onLogin: () -> Unit,
    readOnly: Boolean = false,
    offlineSourceLabel: String? = null,
    draftStore: DraftStore? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val journal = remember(context) { SubmissionJournal(context) }
    // Read-only/offline cards must never submit. Even if a caller passes an account, gate here.
    val isReadOnly = readOnly || account == null
    val displayQuestions = remember(card) { card?.let(::buildDisplayQuestions).orEmpty() }
    val accountScopeKey = account?.let { "${it.schoolId}\u0000${it.studentId}" }
    val stateKey = Triple(card?.code, accountScopeKey, isReadOnly)
    var selectedTab by remember(stateKey) { mutableStateOf(0) }
    var questionIndex by remember(stateKey) { mutableStateOf(0) }
    var onlyAnswer by remember(stateKey) { mutableStateOf(false) }
    var originalShowAnswer by remember(stateKey) { mutableStateOf(false) }
    var showAllAnswers by remember(stateKey) { mutableStateOf(false) }
    val drafts = remember(stateKey) { mutableStateMapOf<String, AnswerDraft>() }
    var draftsLoaded by remember(stateKey) { mutableStateOf(false) }
    var pickerQuestionId by remember(stateKey) { mutableStateOf<String?>(null) }
    var photosProcessing by remember(stateKey) { mutableStateOf(false) }
    var photoSelectionError by remember(stateKey) { mutableStateOf<String?>(null) }
    var showUnansweredWarning by remember(stateKey) { mutableStateOf(false) }
    var showFinalConfirmation by remember(stateKey) { mutableStateOf(false) }
    var showSourceConfirmation by remember(stateKey) { mutableStateOf<String?>(null) }
    var showManualUnlock by remember(stateKey) { mutableStateOf(false) }
    var unresolvedSubmission by remember(stateKey) { mutableStateOf(false) }
    var submissionStateReady by remember(stateKey) { mutableStateOf(false) }
    var submitting by remember(stateKey) { mutableStateOf(false) }
    var statusMessage by remember(stateKey) { mutableStateOf<String?>(null) }
    var successDialog by remember(stateKey) { mutableStateOf(false) }
    var exportMessage by remember(stateKey) { mutableStateOf<String?>(null) }
    // Only enable writes after a successful read; a failed read must not overwrite old drafts.
    var persistenceEnabled by remember(stateKey) { mutableStateOf(false) }
    // Serialize every draft write (debounced writes + leave/ON_STOP flushes) with one mutex.
    val writeMutex = remember(stateKey) { Mutex() }
    var writerJob by remember(stateKey) { mutableStateOf<Job?>(null) }
    var writerGeneration by remember(stateKey) { mutableIntStateOf(0) }
    var leaving by remember(stateKey) { mutableStateOf(false) }
    var showLeaveConfirm by remember(stateKey) { mutableStateOf(false) }

    // Load persisted drafts for online cards only; offline cards never touch drafts.
    LaunchedEffect(stateKey, draftsLoaded) {
        if (isReadOnly || draftsLoaded || draftStore == null || card == null || account == null) {
            if (isReadOnly) draftsLoaded = true
            return@LaunchedEffect
        }
        val result = runCatching {
            withContext(Dispatchers.IO) { draftStore.read(card.code, account) }
        }
        result.onSuccess { stored ->
            stored.forEach { item ->
                drafts[item.questionId] = AnswerDraft(
                    selected = item.selected,
                    photos = item.photoUris.mapNotNull { runCatching { Uri.parse(it) }.getOrNull() },
                    existingImageUrls = item.existingImageUrls,
                )
            }
            persistenceEnabled = true
        }.onFailure {
            persistenceEnabled = false
            statusMessage = "草稿读取失败；为避免覆盖旧草稿，本次作答不会自动保存。"
        }
        draftsLoaded = true
    }

    // Persist draft changes (debounced) for online cards only, and only after a successful read.
    // Every write — debounced or forced on leave — goes through writeMutex so a stale debounced
    // write can never land after a final flush.
    LaunchedEffect(stateKey, draftsLoaded, persistenceEnabled, writerGeneration) {
        val c = card
        val a = account
        val store = draftStore
        if (isReadOnly || !draftsLoaded || !persistenceEnabled || store == null || c == null || a == null) {
            return@LaunchedEffect
        }
        writerJob = currentCoroutineContext().job
        snapshotFlow { drafts.toMap() }
            .debounce(500)
            .collect { current ->
                val stored = buildStoredDrafts(current)
                try {
                    writeMutex.withLock {
                        withContext(Dispatchers.IO) { store.write(c.code, a, stored) }
                    }
                } catch (cancelled: CancellationException) {
                    // Deliberate cancellation (leave/stop) must not disable persistence.
                    throw cancelled
                } catch (_: Exception) {
                    persistenceEnabled = false
                    statusMessage = "草稿保存失败，本次作答不会自动保存。"
                }
            }
    }

    // Cancel the pending debounced writer and force one final write of the latest snapshot.
    // Returns failure instead of throwing so the caller can ask the user before leaving.
    suspend fun flushDrafts(): Result<Unit> {
        val c = card
        val a = account
        val store = draftStore
        if (isReadOnly || store == null || c == null || a == null || !persistenceEnabled) {
            return Result.success(Unit)
        }
        return try {
            writerJob?.let { job ->
                if (job.isActive) job.cancelAndJoin()
            }
            writeMutex.withLock {
                withContext(Dispatchers.IO) { store.write(c.code, a, buildStoredDrafts(drafts.toMap())) }
            }
            writerGeneration++ // restart the debounced writer for any continued editing
            Result.success(Unit)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    fun requestLeave() {
        if (leaving) return
        if (photosProcessing) {
            statusMessage = "正在准备所选图片，请稍候再返回。"
            return
        }
        if (showLeaveConfirm) {
            showLeaveConfirm = false
            return
        }
        if (isReadOnly || !persistenceEnabled) {
            onBack()
            return
        }
        leaving = true
        scope.launch {
            val result = flushDrafts()
            leaving = false
            if (result.isSuccess) onBack() else showLeaveConfirm = true
        }
    }

    // ON_STOP (backgrounding / navigating away by other means) must also flush pending edits.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, stateKey) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP && !isReadOnly && persistenceEnabled) {
                scope.launch {
                    val result = flushDrafts()
                    if (result.isFailure) {
                        statusMessage = "草稿未能保存（本机存储错误），请返回本页重试后再离开。"
                    }
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(stateKey) {
        submissionStateReady = false
        val currentCard = card
        val currentAccount = account
        if (currentCard == null || currentAccount == null || isReadOnly) {
            unresolvedSubmission = false
            submissionStateReady = true
        } else {
            try {
                unresolvedSubmission = withContext(Dispatchers.IO) {
                    journal.hasUnresolvedSubmission(currentCard.code, currentAccount)
                }
                if (unresolvedSubmission) statusMessage = "存在尚未核实的提交记录。"
            } catch (_: Exception) {
                unresolvedSubmission = true
                statusMessage = "无法核实历史提交状态。请勿继续提交，先检查本机数据或人工核实。"
            } finally {
                submissionStateReady = true
            }
        }
    }

    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(maxItems = 8),
    ) { uris ->
        val questionId = pickerQuestionId
        pickerQuestionId = null
        if (uris.isEmpty()) {
            photosProcessing = false
            return@rememberLauncherForActivityResult
        }
        if (questionId == null || isReadOnly || displayQuestions.none { it.item.id == questionId }) {
            photosProcessing = false
            photoSelectionError = "页面已重新载入，无法确定图片所属题目；请回到作业页重新选择图片。"
            return@rememberLauncherForActivityResult
        }
        photosProcessing = true
        scope.launch {
            try {
                // Persist access before adding photos to the confirmed draft. Keep submission and
                // leaving disabled until this finishes, so the selected photos cannot miss its snapshot.
                val persisted = withContext(Dispatchers.IO) {
                    uris.filter { uri ->
                        val already = context.contentResolver.persistedUriPermissions
                            .any { it.uri == uri && it.isReadPermission }
                        already || runCatching {
                            context.contentResolver.takePersistableUriPermission(
                                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION,
                            )
                            true
                        }.getOrDefault(false)
                    }
                }
                val rejected = uris.size - persisted.size
                if (persisted.isNotEmpty()) {
                    val old = drafts[questionId] ?: AnswerDraft()
                    val remaining = (8 - old.existingImageUrls.size).coerceAtLeast(0)
                    drafts[questionId] = old.copy(photos = (old.photos + persisted).distinct().take(remaining))
                }
                if (rejected > 0) {
                    statusMessage = "有 $rejected 张图片无法保存读取权限，已跳过；请重新选择。"
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                statusMessage = "无法读取所选图片的授权，请重新选择。"
            } finally {
                photosProcessing = false
            }
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/html"),
    ) { uri ->
        val currentCard = card
        if (uri == null) {
            exportMessage = "已取消导出"
            return@rememberLauncherForActivityResult
        }
        if (currentCard == null) {
            exportMessage = "导出失败：题卡尚未载入"
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            val result = runCatching {
                val html = withContext(Dispatchers.IO) { TemplateTransfer.exportHtml(currentCard) }
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri).use { out ->
                        requireNotNull(out) { "无法写入目标文件" }
                        out.write(html.toByteArray(Charsets.UTF_8))
                    }
                }
            }
            exportMessage = if (result.isSuccess) {
                "HTML 导出成功（不含历史答卷）"
            } else {
                "导出失败：${result.exceptionOrNull()?.message.orEmpty()}"
            }
        }
    }

    fun beginSubmission(confirmedDrafts: Map<String, AnswerDraft>) {
        val currentCard = card ?: return
        val currentAccount = account ?: return
        if (isReadOnly) {
            statusMessage = "离线导入的题卡不能提交。"
            return
        }
        if (!submissionStateReady || !draftsLoaded || unresolvedSubmission || submitting || photosProcessing || leaving) {
            statusMessage = "提交状态尚未核实，当前不能再次提交。"
            return
        }
        scope.launch {
            submitting = true
            statusMessage = null
            var attemptId: String? = null
            var networkOwnsAttempt = false
            try {
                val uploadedByQuestion = mutableMapOf<String, List<String>>()
                displayQuestions.forEach { display ->
                    val draft = confirmedDrafts[display.item.id] ?: AnswerDraft()
                    val urls = draft.existingImageUrls.toMutableList()
                    draft.photos.forEachIndexed { index, uri ->
                        val bytes = withContext(Dispatchers.IO) { normalizePhotoJpeg(context, uri) }
                        val fileName = "answer-${System.currentTimeMillis()}-$index.jpg"
                        urls += api.uploadImage(bytes, fileName, currentAccount)
                    }
                    uploadedByQuestion[display.item.id] = urls
                }

                val answers = displayQuestions.map { display ->
                    val question = display.item
                    val draft = confirmedDrafts[question.id] ?: AnswerDraft()
                    val answer = question.options
                        .filter { it.label in draft.selected }
                        .joinToString("") { it.label }
                    AnswerSubmission(
                        questionId = question.id,
                        answer = answer,
                        rawScan = uploadedByQuestion[question.id].orEmpty().joinToString(","),
                    )
                }
                val started = withContext(Dispatchers.IO) {
                    // Keep the reserved id even if dispatching back to the UI is cancelled.
                    journal.tryBegin(currentCard.code, currentAccount).also { attemptId = it }
                }
                if (started == null) {
                    unresolvedSubmission = true
                    statusMessage = "已有一次提交尚未核实，请勿重复发送。"
                    return@launch
                }
                attemptId = started
                unresolvedSubmission = true
                // onCallFinished fires only from OkHttp's terminal callback, never on coroutine
                // cancellation. The in-memory guard is released only then; the persistent UNKNOWN
                // marker survives until an explicit, user-confirmed resolve.
                networkOwnsAttempt = true
                api.submitAnswers(currentAccount, currentCard.code, answers) {
                    journal.finishAttempt(started)
                }
                withContext(Dispatchers.IO) {
                    journal.resolve(currentCard.code, currentAccount, started)
                }
                unresolvedSubmission = false
                successDialog = true
                // Clearing drafts is best-effort: a failure must NOT turn a confirmed success
                // into an UNKNOWN submission (the journal marker is already resolved).
                writerJob?.cancelAndJoin()
                drafts.clear()
                if (draftStore != null) {
                    runCatching {
                        writeMutex.withLock {
                            withContext(Dispatchers.IO) { draftStore.clear(currentCard.code, currentAccount) }
                        }
                    }
                }
                writerGeneration++
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                // Cancellation only asks OkHttp to stop; the persistent UNKNOWN marker stays.
                throw cancelled
            } catch (error: ApiException) {
                if (attemptId != null) {
                    unresolvedSubmission = true
                    statusMessage = "服务器没有返回成功确认（业务码 ${error.businessCode ?: "未知"}）。请人工核实后再操作。"
                } else {
                    statusMessage = "图片上传未完成，答卷尚未提交：${error.message}"
                }
            } catch (error: Exception) {
                if (attemptId != null) {
                    unresolvedSubmission = true
                    statusMessage = "提交结果未知。请先在服务端核实，不要重复提交。"
                } else {
                    statusMessage = "图片上传或预处理未完成，答卷尚未提交：${error.message.orEmpty()}"
                }
            } finally {
                if (!networkOwnsAttempt) attemptId?.let(journal::finishAttempt)
                submitting = false
            }
        }
    }

    BackHandler { if (!leaving) requestLeave() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(card?.title ?: "题卡", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (isReadOnly) {
                            Text("离线只读 · 不能提交", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                        } else if (fromCache) {
                            Text("离线缓存", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = ::requestLeave, enabled = !leaving && !photosProcessing) {
                        Icon(Icons.Outlined.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            card?.let {
                                exportLauncher.launch(safeDetailFileName(it.code) + ".html")
                            }
                        },
                        enabled = card != null,
                    ) {
                        Icon(Icons.Outlined.Share, contentDescription = "导出 HTML")
                    }
                },
            )
        },
    ) { padding ->
        when {
            loading -> Column(Modifier.fillMaxSize().padding(padding), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator()
                Text("正在读取题卡", modifier = Modifier.padding(top = 12.dp))
            }
            error != null -> Column(Modifier.fillMaxSize().padding(padding).padding(24.dp), verticalArrangement = Arrangement.Center) {
                Text(error, color = MaterialTheme.colorScheme.error)
                TextButton(onClick = onBack) { Text("返回") }
            }
            card == null || displayQuestions.isEmpty() -> Column(Modifier.fillMaxSize().padding(padding).padding(24.dp), verticalArrangement = Arrangement.Center) {
                Text("没有可显示的题目")
            }
            else -> {
                val display = displayQuestions[questionIndex.coerceIn(displayQuestions.indices)]
                val question = display.item
                Column(Modifier.fillMaxSize().padding(padding)) {
                    TabRow(selectedTabIndex = selectedTab) {
                        val tabs = if (isReadOnly) listOf("全部", "解析", "答案", "原题") else listOf("全部", "解析", "答案", "原题", "作业")
                        tabs.forEachIndexed { index, label ->
                            Tab(selected = selectedTab == index, onClick = { selectedTab = index }, text = { Text(label) })
                        }
                    }
                    if (selectedTab == 0) {
                        AllTab(
                            questions = displayQuestions,
                            showAnswers = showAllAnswers,
                            onShowAnswers = { showAllAnswers = it },
                            allowImages = !isReadOnly,
                        )
                    } else {
                        // Tab 0 is 全部; the remaining tabs map to the legacy 0..3 handlers.
                        val adjusted = selectedTab - 1
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            IconButton(onClick = { if (questionIndex > 0) questionIndex-- }, enabled = questionIndex > 0) {
                                Icon(Icons.Outlined.ChevronLeft, contentDescription = "上一题")
                            }
                            Text(
                                "${display.label}  ·  ${questionIndex + 1}/${displayQuestions.size}",
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.labelLarge,
                            )
                            IconButton(onClick = { if (questionIndex < displayQuestions.lastIndex) questionIndex++ }, enabled = questionIndex < displayQuestions.lastIndex) {
                                Icon(Icons.Outlined.ChevronRight, contentDescription = "下一题")
                            }
                        }
                        HorizontalDivider()
                        when (adjusted) {
                            0 -> AnalysisTab(display, onlyAnswer, onOnlyAnswerChange = { onlyAnswer = it }, allowImages = !isReadOnly)
                            1 -> AnswerTab(question)
                            2 -> OriginalTab(display, originalShowAnswer, onShowAnswer = { originalShowAnswer = it }, allowImages = !isReadOnly)
                            else -> UploadTab(
                                questions = displayQuestions,
                                current = question,
                                account = account,
                                drafts = drafts,
                                pickerQuestionId = { pickerQuestionId = it },
                                pickPhotos = {
                                    photosProcessing = true
                                    try {
                                        imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                                    } catch (_: Exception) {
                                        pickerQuestionId = null
                                        photosProcessing = false
                                        statusMessage = "无法打开相册，请稍后重试。"
                                    }
                                },
                                onLogin = onLogin,
                                onFillSource = { source -> showSourceConfirmation = source },
                                onSubmit = {
                                    val missing = displayQuestions.count { displayQuestion ->
                                        val draft = drafts[displayQuestion.item.id] ?: AnswerDraft()
                                        draft.selected.isEmpty() && draft.photos.isEmpty() && draft.existingImageUrls.isEmpty()
                                    }
                                    if (missing > 0) showUnansweredWarning = true else showFinalConfirmation = true
                                },
                                submitting = submitting,
                                unresolvedSubmission = unresolvedSubmission,
                                submissionStateReady = submissionStateReady && draftsLoaded && !leaving && !photosProcessing,
                                statusMessage = when {
                                    !draftsLoaded -> "正在读取加密草稿，请稍候…"
                                    photosProcessing -> "正在准备所选图片，请稍候…"
                                    else -> statusMessage
                                },
                                onManualUnlock = { showManualUnlock = true },
                            )
                        }
                    }
                }
            }
        }
    }

    if (showUnansweredWarning) {
        val missing = displayQuestions.count { displayQuestion ->
            val draft = drafts[displayQuestion.item.id] ?: AnswerDraft()
            draft.selected.isEmpty() && draft.photos.isEmpty() && draft.existingImageUrls.isEmpty()
        }
        AlertDialog(
            onDismissRequest = { showUnansweredWarning = false },
            title = { Text("仍有 $missing 道题未答") },
            text = { Text("确认后将进入最终提交确认。") },
            confirmButton = {
                TextButton(onClick = {
                    showUnansweredWarning = false
                    showFinalConfirmation = true
                }) { Text("继续") }
            },
            dismissButton = { TextButton(onClick = { showUnansweredWarning = false }) { Text("返回作答") } },
        )
    }
    if (showFinalConfirmation) {
        AlertDialog(
            onDismissRequest = { showFinalConfirmation = false },
            title = { Text("确认提交作业？") },
            text = {
                Text("目标账号：${account?.displayName?.ifBlank { account.username }.orEmpty()}\n本操作不可撤回。图片会先上传，随后单独提交答卷。")
            },
            confirmButton = {
                TextButton(enabled = !photosProcessing && !leaving && !submitting, onClick = {
                    showFinalConfirmation = false
                    // Freeze an immutable snapshot so the submitted answers are exactly what was confirmed.
                    val frozenDrafts = displayQuestions.associate { display ->
                        val draft = drafts[display.item.id] ?: AnswerDraft()
                        display.item.id to draft.copy(
                            selected = draft.selected.toSet(),
                            photos = draft.photos.toList(),
                            existingImageUrls = draft.existingImageUrls.toList(),
                        )
                    }
                    beginSubmission(frozenDrafts)
                }) { Text("确认提交") }
            },
            dismissButton = { TextButton(onClick = { showFinalConfirmation = false }) { Text("取消") } },
        )
    }
    showSourceConfirmation?.let { source ->
        AlertDialog(
            onDismissRequest = { showSourceConfirmation = null },
            title = { Text(if (source == "correct") "填入参考答案？" else "回填上次答卷？") },
            text = { Text("此操作会覆盖当前作答，可继续修改后再提交。") },
            confirmButton = {
                TextButton(onClick = {
                    displayQuestions.forEach { display ->
                        val item = display.item
                        drafts[item.id] = if (source == "correct") {
                            AnswerDraft(selected = AnswerLetters.parse(item.answer))
                        } else {
                            AnswerDraft(
                                selected = answerLetters(item.previousAnswer),
                                existingImageUrls = item.previousRawScan.split(',').filter(String::isNotBlank),
                            )
                        }
                    }
                    showSourceConfirmation = null
                }) { Text("覆盖作答") }
            },
            dismissButton = { TextButton(onClick = { showSourceConfirmation = null }) { Text("取消") } },
        )
    }
    if (showManualUnlock) {
        AlertDialog(
            onDismissRequest = { showManualUnlock = false },
            title = { Text("重新开放提交？") },
            text = { Text("只有已在服务端人工确认该答卷未提交时才能继续。应用无法自动查询提交状态；确认后仍需再次通过最终提交确认。") },
            confirmButton = {
                TextButton(onClick = {
                    showManualUnlock = false
                    val currentCard = card
                    val currentAccount = account
                    if (currentCard != null && currentAccount != null && !isReadOnly) {
                        scope.launch {
                            val result = runCatching {
                                withContext(Dispatchers.IO) { journal.resolve(currentCard.code, currentAccount) }
                            }
                            if (result.isSuccess) {
                                unresolvedSubmission = false
                                statusMessage = "已解锁。请再次核对作答和目标账号。"
                            } else {
                                statusMessage = "仍无法核实上次提交状态（可能仍在执行），请稍后再试。"
                            }
                        }
                    }
                }) { Text("我已人工核实") }
            },
            dismissButton = { TextButton(onClick = { showManualUnlock = false }) { Text("取消") } },
        )
    }
    if (successDialog) {
        AlertDialog(
            onDismissRequest = { successDialog = false },
            title = { Text("提交成功") },
            text = { Text("服务端已返回成功状态。") },
            confirmButton = { TextButton(onClick = { successDialog = false }) { Text("完成") } },
        )
    }
    exportMessage?.let { message ->
        AlertDialog(
            onDismissRequest = { exportMessage = null },
            title = { Text("HTML 导出") },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = { exportMessage = null }) { Text("知道了") } },
        )
    }
    photoSelectionError?.let { message ->
        AlertDialog(
            onDismissRequest = { photoSelectionError = null },
            title = { Text("请重新选择图片") },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = {
                    photoSelectionError = null
                    if (!isReadOnly) selectedTab = 4
                }) { Text("知道了") }
            },
        )
    }
    if (showLeaveConfirm) {
        AlertDialog(
            onDismissRequest = { showLeaveConfirm = false },
            title = { Text("草稿未能保存") },
            text = { Text("本机存储或加密出错，无法保存本次作答。确定不保存并离开吗？") },
            confirmButton = {
                TextButton(onClick = {
                    showLeaveConfirm = false
                    onBack()
                }) { Text("不保存并离开") }
            },
            dismissButton = {
                TextButton(onClick = {
                    // Retry the flush; stay on this screen if it succeeds.
                    showLeaveConfirm = false
                    leaving = true
                    scope.launch {
                        val result = flushDrafts()
                        leaving = false
                        if (result.isSuccess) onBack() else showLeaveConfirm = true
                    }
                }) { Text("重试保存") }
            },
        )
    }
}

private fun safeDetailFileName(value: String): String =
    value.replace(Regex("[^A-Za-z0-9._()\\-]"), "_").take(80).ifBlank { "card" }

@Composable
private fun AllTab(
    questions: List<DisplayQuestion>,
    showAnswers: Boolean,
    onShowAnswers: (Boolean) -> Unit,
    allowImages: Boolean,
) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp)) {
        item {
            Text("共 ${questions.size} 道小题（含父子层级）", style = MaterialTheme.typography.titleMedium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("显示答案与解析", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                androidx.compose.material3.Switch(checked = showAnswers, onCheckedChange = onShowAnswers)
            }
            Spacer(Modifier.height(8.dp))
        }
        items(questions, key = { it.item.id }) { display ->
            Card(
                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text("题目 ${display.label}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    display.parentHtml?.takeIf(String::isNotBlank)?.let {
                        RichHtmlText(it, allowImages = allowImages)
                        Spacer(Modifier.height(6.dp))
                    }
                    RichHtmlText(display.item.contentHtml, allowImages = allowImages)
                    OptionsList(display.item, allowImages = allowImages)
                    if (showAnswers) {
                        SectionLabel("答案")
                        RichHtmlText(display.item.answer.ifBlank { "暂无答案" }, allowImages = allowImages)
                        if (display.item.explanationHtml.isNotBlank()) {
                            SectionLabel("解析")
                            RichHtmlText(display.item.explanationHtml, allowImages = allowImages)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AnalysisTab(display: DisplayQuestion, onlyAnswer: Boolean, onOnlyAnswerChange: (Boolean) -> Unit, allowImages: Boolean) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("只看答案", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            androidx.compose.material3.Switch(checked = onlyAnswer, onCheckedChange = onOnlyAnswerChange)
        }
        if (!onlyAnswer) {
            display.parentHtml?.takeIf(String::isNotBlank)?.let {
                RichHtmlText(it, allowImages = allowImages)
                Spacer(Modifier.height(8.dp))
            }
            RichHtmlText(display.item.contentHtml, allowImages = allowImages)
            OptionsList(display.item, allowImages = allowImages)
        }
        SectionLabel("答案")
        RichHtmlText(display.item.answer.ifBlank { "暂无答案" }, allowImages = allowImages)
        if (!onlyAnswer && display.item.explanationHtml.isNotBlank()) {
            SectionLabel("解析")
            RichHtmlText(display.item.explanationHtml, allowImages = allowImages)
        }
    }
}

@Composable
private fun AnswerTab(question: QuestionItem) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp)) {
        SectionLabel("答案")
        RichHtmlText(question.answer.ifBlank { "暂无答案" }, allowImages = false)
    }
}

@Composable
private fun OriginalTab(display: DisplayQuestion, showAnswer: Boolean, onShowAnswer: (Boolean) -> Unit, allowImages: Boolean) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp)) {
        display.parentHtml?.takeIf(String::isNotBlank)?.let {
            RichHtmlText(it, allowImages = allowImages)
            Spacer(Modifier.height(8.dp))
        }
        RichHtmlText(display.item.contentHtml, allowImages = allowImages)
        OptionsList(display.item, allowImages = allowImages)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("显示答案与解析", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            androidx.compose.material3.Switch(checked = showAnswer, onCheckedChange = onShowAnswer)
        }
        if (showAnswer) {
            SectionLabel("答案")
            RichHtmlText(display.item.answer.ifBlank { "暂无答案" }, allowImages = allowImages)
            if (display.item.explanationHtml.isNotBlank()) {
                SectionLabel("解析")
                RichHtmlText(display.item.explanationHtml, allowImages = allowImages)
            }
        }
    }
}

@Composable
private fun UploadTab(
    questions: List<DisplayQuestion>,
    current: QuestionItem,
    account: StudentAccount?,
    drafts: MutableMap<String, AnswerDraft>,
    pickerQuestionId: (String) -> Unit,
    pickPhotos: () -> Unit,
    onLogin: () -> Unit,
    onFillSource: (String) -> Unit,
    onSubmit: () -> Unit,
    submitting: Boolean,
    unresolvedSubmission: Boolean,
    submissionStateReady: Boolean,
    statusMessage: String?,
    onManualUnlock: () -> Unit,
) {
    val draft = drafts[current.id] ?: AnswerDraft()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { onFillSource("correct") }, enabled = submissionStateReady && !submitting && !unresolvedSubmission && account != null) { Text("填入参考答案") }
            TextButton(onClick = { onFillSource("previous") }, enabled = submissionStateReady && !submitting && !unresolvedSubmission && account != null) { Text("回填上次答卷") }
        }
        Text("可在提交前逐题修改，作答会自动加密保存到本机", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(10.dp))
        RichHtmlText(current.contentHtml, allowImages = true)
        if (current.options.isNotEmpty()) {
            val isMultiple = AnswerLetters.parse(current.answer).size > 1
            current.options.forEach { option ->
                Row(
                    modifier = Modifier.fillMaxWidth().clickable(enabled = submissionStateReady && !submitting && !unresolvedSubmission) {
                        val selected = if (isMultiple) {
                            if (option.label in draft.selected) draft.selected - option.label else draft.selected + option.label
                        } else if (option.label in draft.selected) emptySet() else setOf(option.label)
                        drafts[current.id] = draft.copy(selected = selected)
                    }.padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (isMultiple) {
                        Checkbox(
                            checked = option.label in draft.selected,
                            enabled = submissionStateReady && !submitting && !unresolvedSubmission,
                            onCheckedChange = { checked ->
                                val selected = if (checked) draft.selected + option.label else draft.selected - option.label
                                drafts[current.id] = draft.copy(selected = selected)
                            },
                        )
                    } else {
                        RadioButton(
                            selected = option.label in draft.selected,
                            enabled = submissionStateReady && !submitting && !unresolvedSubmission,
                            onClick = {
                                drafts[current.id] = draft.copy(selected = if (option.label in draft.selected) emptySet() else setOf(option.label))
                            },
                        )
                    }
                    RichHtmlText("${option.label}. ${option.html}", Modifier.weight(1f), allowImages = true)
                }
            }
        } else {
            Text("主观题图片", style = MaterialTheme.typography.titleSmall)
            Button(
                onClick = {
                    pickerQuestionId(current.id)
                    pickPhotos()
                },
                enabled = submissionStateReady && !submitting && !unresolvedSubmission && draft.photos.size + draft.existingImageUrls.size < 8,
                modifier = Modifier.padding(top = 8.dp),
            ) { Text("选择图片") }
            if (draft.photos.isNotEmpty() || draft.existingImageUrls.isNotEmpty()) {
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 10.dp)) {
                    draft.photos.forEachIndexed { index, uri ->
                        PhotoThumbnail(uri, "移除图片 ${index + 1}", enabled = submissionStateReady && !submitting && !unresolvedSubmission) {
                            drafts[current.id] = draft.copy(photos = draft.photos.filterIndexed { i, _ -> i != index })
                        }
                    }
                    draft.existingImageUrls.forEachIndexed { index, url ->
                        PhotoThumbnail(url, "移除上次答卷图片 ${index + 1}", enabled = submissionStateReady && !submitting && !unresolvedSubmission) {
                            drafts[current.id] = draft.copy(existingImageUrls = draft.existingImageUrls.filterIndexed { i, _ -> i != index })
                        }
                    }
                }
            }
        }
        if (account == null) {
            Button(onClick = onLogin, modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) { Text("登录后提交") }
        } else {
            if (unresolvedSubmission && submissionStateReady && !submitting) {
                Text(
                    "上次提交结果未知。请先在服务端人工核实，不要重复提交。",
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 12.dp),
                )
                TextButton(onClick = onManualUnlock) { Text("已核实未提交，重新开放") }
            }
            Button(
                onClick = onSubmit,
                enabled = submissionStateReady && !submitting && !unresolvedSubmission,
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
            ) {
                if (submitting) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.size(8.dp))
                Text(if (submitting) "正在上传与提交" else "提交作业（${questions.size} 题）")
            }
        }
        statusMessage?.let {
            Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 12.dp))
        }
        HorizontalDivider(Modifier.padding(top = 16.dp))
        Text(
            "图片先上传，答卷随后单独提交。提交确认不可撤回。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 10.dp),
        )
    }
}

@Composable
private fun PhotoThumbnail(model: Any, description: String, enabled: Boolean, onRemove: () -> Unit) {
    Column(Modifier.padding(end = 8.dp), horizontalAlignment = Alignment.End) {
        AsyncImage(
            model = rememberPrivateImageRequest(model),
            contentDescription = description,
            modifier = Modifier.size(92.dp).clip(RoundedCornerShape(6.dp)),
            contentScale = ContentScale.Crop,
        )
        IconButton(onClick = onRemove, enabled = enabled, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Outlined.Close, contentDescription = description, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun OptionsList(question: QuestionItem, allowImages: Boolean) {
    question.options.forEach { option ->
        Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.Top) {
            Text("${option.label}.", modifier = Modifier.padding(end = 6.dp))
            RichHtmlText(option.html, Modifier.weight(1f), allowImages = allowImages)
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 14.dp, bottom = 6.dp))
}

@Composable
private fun RichHtmlText(html: String, modifier: Modifier = Modifier, allowImages: Boolean = false) {
    val textColor = MaterialTheme.colorScheme.onSurface.toArgb()
    val imageTag = remember { Regex("<img\\b[^>]*\\bsrc\\s*=\\s*(['\"])(.*?)\\1[^>]*>", RegexOption.IGNORE_CASE) }
    val imageUrls = remember(html, allowImages) {
        if (!allowImages) emptyList()
        else imageTag.findAll(html).map { it.groupValues[2] }
            .filter { it.startsWith("https://", ignoreCase = true) }
            .distinct()
            .toList()
    }
    val textHtml = remember(html) { imageTag.replace(html, "") }
    val renderedText = remember(textHtml) { Html.fromHtml(textHtml, Html.FROM_HTML_MODE_COMPACT) }
    AndroidView(
        modifier = modifier.fillMaxWidth(),
        factory = { viewContext ->
            TextView(viewContext).apply {
                setTextColor(textColor)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                setLineSpacing(2f, 1.08f)
            }
        },
        update = { view ->
            if (view.text !== renderedText) view.text = renderedText
            view.setTextColor(textColor)
        },
    )
    imageUrls.forEach { url ->
        AsyncImage(
            model = rememberPrivateImageRequest(url),
            contentDescription = "题目图片",
            modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp).padding(top = 8.dp),
            contentScale = ContentScale.FillWidth,
        )
    }
}

private fun buildDisplayQuestions(card: TemplateCard): List<DisplayQuestion> {
    fun flatten(item: QuestionItem, ancestors: List<String>, label: String): List<DisplayQuestion> =
        if (item.children.isEmpty()) {
            listOf(DisplayQuestion(item, ancestors.joinToString("<br>").ifBlank { null }, label))
        } else {
            item.children.flatMapIndexed { index, child ->
                flatten(child, ancestors + item.contentHtml, "$label(${index + 1})")
            }
        }
    return card.questions.flatMap { flatten(it, emptyList(), it.order) }
}

private fun normalizePhotoJpeg(context: Context, uri: Uri): ByteArray {
    val bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.setTargetSampleSize(sampleSize(info.size.width, info.size.height))
        }
    } else {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IOException("无法解码所选图片")
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight)
        }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, options)
        } ?: throw IOException("无法读取所选图片")
    }
    return try {
        val output = ByteArrayOutputStream()
        if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 85, output)) {
            throw IOException("图片 JPEG 编码失败")
        }
        val bytes = output.toByteArray()
        if (bytes.isEmpty() || bytes.size > MAX_UPLOAD_BYTES) {
            throw IOException("压缩后的图片为空或超过 15 MB")
        }
        bytes
    } finally {
        bitmap.recycle()
    }
}

private fun sampleSize(width: Int, height: Int): Int {
    var sample = 1
    while (max(width / sample, height / sample) > MAX_IMAGE_EDGE) sample *= 2
    return sample
}

@Composable
private fun rememberPrivateImageRequest(model: Any): ImageRequest {
    val context = LocalContext.current
    return remember(context, model) {
        ImageRequest.Builder(context)
            .data(model)
            .diskCachePolicy(CachePolicy.DISABLED)
            .memoryCachePolicy(CachePolicy.DISABLED)
            .build()
    }
}

private fun answerLetters(value: String): Set<String> = AnswerLetters.parse(value)

private fun buildStoredDrafts(current: Map<String, AnswerDraft>): List<StoredAnswerDraft> =
    current.map { (id, draft) ->
        StoredAnswerDraft(
            questionId = id,
            selected = draft.selected,
            photoUris = draft.photos.map(Uri::toString),
            existingImageUrls = draft.existingImageUrls,
        )
    }

private const val MAX_IMAGE_EDGE = 2048
private const val MAX_UPLOAD_BYTES = 15 * 1024 * 1024
