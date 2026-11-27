package app.zhinengtika.nativeapp.ui

import android.content.Context
import android.net.Uri
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import app.zhinengtika.nativeapp.data.CredentialVault
import app.zhinengtika.nativeapp.data.DraftStore
import app.zhinengtika.nativeapp.data.HistoryStore
import app.zhinengtika.nativeapp.data.OfflineTemplateStore
import app.zhinengtika.nativeapp.data.StudentAccount
import app.zhinengtika.nativeapp.data.StudentApi
import app.zhinengtika.nativeapp.data.TemplateCache
import app.zhinengtika.nativeapp.data.TemplateHistoryEntry
import app.zhinengtika.nativeapp.data.TemplateRepository
import app.zhinengtika.nativeapp.data.TemplateCard
import app.zhinengtika.nativeapp.data.UiPreferences
import app.zhinengtika.nativeapp.data.accountScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val HOME = "home"
private const val ACCOUNT = "account"
private const val SETTINGS = "settings"
private const val SCANNER = "scanner"
private const val OFFLINE = "offline"
private const val DETAIL = "detail/{code}"
private const val OFFLINE_DETAIL = "offline-detail/{id}"

@Composable
fun AppRoot(context: Context) {
    val navController = rememberNavController()
    val scope = rememberCoroutineScope()
    val api = remember { StudentApi() }
    val vault = remember { CredentialVault(context) }
    val historyStore = remember { HistoryStore(context) }
    val cache = remember { TemplateCache(context) }
    val draftStore = remember { DraftStore(context) }
    val offlineStore = remember { OfflineTemplateStore(context) }
    val uiPrefs = remember { UiPreferences(context) }
    val repository = remember { TemplateRepository(api, cache, historyStore) }
    val snackbar = remember { SnackbarHostState() }
    var themeMode by remember { mutableStateOf(uiPrefs.themeMode()) }
    var account by remember { mutableStateOf<StudentAccount?>(null) }
    var accounts by remember { mutableStateOf<List<StudentAccount>>(emptyList()) }
    var history by remember { mutableStateOf<List<TemplateHistoryEntry>>(emptyList()) }
    var activeCard by remember { mutableStateOf<TemplateCard?>(null) }
    var activeCardFromCache by remember { mutableStateOf(false) }
    var activeCardOwner by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var scanResult by remember { mutableStateOf("") }
    var fetchGeneration by remember { mutableIntStateOf(0) }
    var fetchJob by remember { mutableStateOf<Job?>(null) }
    val currentAccountScope = account?.let(::accountScope)

    LaunchedEffect(Unit) {
        try {
            accounts = withContext(Dispatchers.IO) { vault.accounts() }
            account = accounts.firstOrNull()
        } catch (_: Exception) {
            snackbar.showSnackbar("本地加密账号暂时不可读取")
        }
    }

    LaunchedEffect(currentAccountScope) {
        fetchJob?.cancel()
        fetchGeneration++
        busy = false
        activeCard = null
        activeCardFromCache = false
        activeCardOwner = null
        history = emptyList()

        val selectedAccount = account
        if (selectedAccount != null) {
            try {
                val scopedHistory = withContext(Dispatchers.IO) { historyStore.list(selectedAccount) }
                if (account?.let(::accountScope) == currentAccountScope) history = scopedHistory
            } catch (_: Exception) {
                snackbar.showSnackbar("当前账号的本地历史暂时不可读取")
            }
        }
    }

    fun openTemplate(code: String) {
        val selectedAccount = account
        if (selectedAccount == null) {
            scope.launch { snackbar.showSnackbar("请先登录后查询题卡") }
            navController.navigate(ACCOUNT)
            return
        }
        fetchJob?.cancel()
        fetchGeneration++
        val generation = fetchGeneration
        val requestScope = accountScope(selectedAccount)
        fetchJob = scope.launch {
            busy = true
            try {
                val result = withContext(Dispatchers.IO) {
                    repository.load(code.trim(), selectedAccount)
                }
                if (account?.let(::accountScope) != requestScope) return@launch
                val scopedHistory = withContext(Dispatchers.IO) { historyStore.list(selectedAccount) }
                if (account?.let(::accountScope) != requestScope) return@launch
                activeCard = result.card
                activeCardFromCache = result.fromCache
                activeCardOwner = requestScope
                history = scopedHistory
                navController.navigate("detail/${Uri.encode(result.card.code)}")
                if (result.fromCache) snackbar.showSnackbar("当前网络不可用，已打开该账号的离线题卡")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (account?.let(::accountScope) == requestScope) {
                    snackbar.showSnackbar(error.message ?: "题卡读取失败")
                }
            } finally {
                if (fetchGeneration == generation) busy = false
            }
        }
    }

    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route
    val showNavigation = currentRoute in setOf(HOME, ACCOUNT, SETTINGS)

    ZhiNengTiKaTheme(themeMode = themeMode) {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbar) },
            bottomBar = {
                if (showNavigation) {
                    NavigationBar {
                        NavigationBarItem(
                            selected = currentRoute == HOME,
                            onClick = { navController.navigate(HOME) { launchSingleTop = true } },
                            icon = { androidx.compose.material3.Icon(Icons.Outlined.Home, contentDescription = null) },
                            label = { androidx.compose.material3.Text("题卡") },
                        )
                        NavigationBarItem(
                            selected = currentRoute == ACCOUNT,
                            onClick = { navController.navigate(ACCOUNT) { launchSingleTop = true } },
                            icon = { androidx.compose.material3.Icon(Icons.Outlined.Person, contentDescription = null) },
                            label = { androidx.compose.material3.Text("账号") },
                        )
                        NavigationBarItem(
                            selected = currentRoute == SETTINGS,
                            onClick = { navController.navigate(SETTINGS) { launchSingleTop = true } },
                            icon = { androidx.compose.material3.Icon(Icons.Outlined.Settings, contentDescription = null) },
                            label = { androidx.compose.material3.Text("设置") },
                        )
                    }
                }
            },
        ) { padding ->
            NavHost(
                navController = navController,
                startDestination = HOME,
                modifier = Modifier.fillMaxSize().padding(padding),
            ) {
                composable(HOME) {
                    HomeScreen(
                        account = account,
                        history = history,
                        loading = busy,
                        scannedCode = scanResult,
                        onScannedCodeConsumed = { scanResult = "" },
                        onFetch = ::openTemplate,
                        onOpenHistory = ::openTemplate,
                        onScan = { navController.navigate(SCANNER) },
                        onOpenOfflineLibrary = { navController.navigate(OFFLINE) },
                    )
                }
                composable(ACCOUNT) {
                    AccountScreen(
                        accounts = accounts,
                        currentAccount = account,
                        api = api,
                        vault = vault,
                        onAccountSaved = { saved ->
                            accounts = saved
                            account = saved.firstOrNull()
                        },
                        onAccountSelected = { index ->
                            scope.launch {
                                runCatching {
                                    val updated = withContext(Dispatchers.IO) { vault.moveToCurrent(index) }
                                    accounts = updated
                                    account = updated.firstOrNull()
                                }.onFailure { snackbar.showSnackbar("切换账号失败") }
                            }
                        },
                        onLogout = {
                            scope.launch {
                                runCatching {
                                    val updated = withContext(Dispatchers.IO) { vault.removeCurrent() }
                                    accounts = updated
                                    account = updated.firstOrNull()
                                }.onFailure { snackbar.showSnackbar("退出账号失败") }
                            }
                        },
                    )
                }
                composable(SETTINGS) {
                    SettingsScreen(
                        onClearLocalData = {
                            scope.launch {
                                // Invalidate stale loads, then wait for any in-flight load to fully
                                // exit (its synchronous cache/history writes included) before clearing.
                                fetchGeneration++
                                busy = false
                                fetchJob?.let { job ->
                                    job.cancel()
                                    runCatching { job.join() }
                                }
                                fetchJob = null
                                try {
                                    withContext(Dispatchers.IO) {
                                        cache.clearAll()
                                        historyStore.clear()
                                    }
                                    history = emptyList()
                                    activeCard = null
                                    activeCardOwner = null
                                    snackbar.showSnackbar("在线题卡缓存和历史已清除；提交状态标记保留")
                                } catch (_: Exception) {
                                    snackbar.showSnackbar("清理失败，请稍后重试")
                                }
                            }
                        },
                        themeMode = themeMode,
                        onThemeModeChange = { mode ->
                            themeMode = mode
                            uiPrefs.setThemeMode(mode)
                        },
                        onClearOfflineLibrary = {
                            scope.launch {
                                try {
                                    withContext(Dispatchers.IO) { offlineStore.clearAll() }
                                    snackbar.showSnackbar("离线资料库已清除")
                                } catch (_: Exception) {
                                    snackbar.showSnackbar("清理离线资料库失败")
                                }
                            }
                        },
                    )
                }
                composable(OFFLINE) {
                    OfflineLibraryScreen(
                        store = offlineStore,
                        onBack = { navController.popBackStack() },
                        onOpenCard = { id -> navController.navigate("offline-detail/${Uri.encode(id)}") },
                        onMessage = { message -> scope.launch { snackbar.showSnackbar(message) } },
                    )
                }
                composable(SCANNER) {
                    QrScannerScreen(
                        onBack = { navController.popBackStack() },
                        onCode = { value ->
                            scanResult = value
                            navController.popBackStack()
                        },
                    )
                }
                composable(
                    route = DETAIL,
                    arguments = listOf(navArgument("code") { type = NavType.StringType }),
                ) { entry ->
                    val code = entry.arguments?.getString("code").orEmpty()
                    val requestedAccount = account
                    val requestedScope = requestedAccount?.let(::accountScope)
                    var card by remember(code, requestedScope, activeCardOwner) {
                        mutableStateOf(
                            activeCard?.takeIf { it.code == code && activeCardOwner == requestedScope },
                        )
                    }
                    var fromCache by remember(code, requestedScope, activeCardOwner) {
                        mutableStateOf(activeCardFromCache && activeCard?.code == code && activeCardOwner == requestedScope)
                    }
                    var loadError by remember(code, requestedScope) { mutableStateOf<String?>(null) }
                    LaunchedEffect(code, requestedScope) {
                        val selected = requestedAccount
                        if (selected == null) {
                            loadError = "请先登录后读取题卡"
                        } else if (card == null) {
                            try {
                                val result = withContext(Dispatchers.IO) { repository.load(code, selected) }
                                val scopedHistory = withContext(Dispatchers.IO) { historyStore.list(selected) }
                                if (account?.let(::accountScope) == requestedScope) {
                                    card = result.card
                                    fromCache = result.fromCache
                                    activeCard = result.card
                                    activeCardFromCache = result.fromCache
                                    activeCardOwner = requestedScope
                                    history = scopedHistory
                                }
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (error: Exception) {
                                if (account?.let(::accountScope) == requestedScope) {
                                    loadError = error.message ?: "题卡读取失败"
                                }
                            }
                        }
                    }
                    QuestionDetailScreen(
                        card = card,
                        account = requestedAccount,
                        api = api,
                        fromCache = fromCache,
                        loading = requestedAccount != null && card == null && loadError == null,
                        error = loadError,
                        onBack = { navController.popBackStack() },
                        onLogin = { navController.navigate(ACCOUNT) },
                        readOnly = false,
                        draftStore = draftStore,
                    )
                }
                composable(
                    route = OFFLINE_DETAIL,
                    arguments = listOf(navArgument("id") { type = NavType.StringType }),
                ) { entry ->
                    val id = entry.arguments?.getString("id").orEmpty()
                    var entryState by remember(id) { mutableStateOf<app.zhinengtika.nativeapp.data.OfflineTemplateEntry?>(null) }
                    var loadError by remember(id) { mutableStateOf<String?>(null) }
                    LaunchedEffect(id) {
                        val result = runCatching {
                            withContext(Dispatchers.IO) { offlineStore.cards().firstOrNull { it.id == id } }
                        }
                        result.onSuccess { loaded ->
                            entryState = loaded
                            if (loaded == null) loadError = "离线题卡不存在或已被删除"
                        }.onFailure {
                            loadError = "离线题卡读取失败（本机解密或存储错误）"
                        }
                    }
                    QuestionDetailScreen(
                        card = entryState?.card,
                        // No account is passed on purpose: offline cards can never submit.
                        account = null,
                        api = api,
                        fromCache = false,
                        loading = entryState == null && loadError == null,
                        error = loadError,
                        onBack = { navController.popBackStack() },
                        onLogin = {},
                        readOnly = true,
                        offlineSourceLabel = entryState?.sourceLabel,
                    )
                }
            }
        }
    }
}
