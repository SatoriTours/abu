package local.abu.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import local.abu.data.AppsClient
import local.abu.data.Bot
import local.abu.data.GeneratedAppsClient
import local.abu.data.ChatClient
import local.abu.data.BotsClient
import local.abu.data.HealthClient
import local.abu.data.HealthResult
import local.abu.data.TaskClient
import local.abu.miniapps.ClientTodoService
import local.abu.miniapps.TodoCache
import local.abu.miniapps.TodoState
import local.abu.platform.createHealthHttpClient
import local.abu.ui.components.*
import local.abu.ui.model.TasksState
import local.abu.ui.model.BotsState
import local.abu.ui.model.GeneratedOfflineEntries
import local.abu.ui.model.GeneratedRoute
import local.abu.ui.model.GeneratedUiState
import local.abu.ui.navigation.AppNavigationState
import local.abu.ui.navigation.ChatScreen
import local.abu.ui.navigation.NavTab
import local.abu.ui.theme.ThemePreference

@Composable
internal fun AbuApp(theme: ThemePreference) {
    val navState = remember { AppNavigationState() }
    val scope = rememberCoroutineScope()
    val http = remember { createHealthHttpClient() }
    val health = remember(http) { HealthClient(http) }
    val chat = remember(http) { ChatClient(http) }
    val tasks = remember(http) {
        val client = TaskClient(http)
        TasksState(
            client::listAll,
            { token, id, text, botId -> client.create(token, id, text, botId) },
            { token, id, botId -> client.cancel(token, id, botId) },
        )
    }
    val bots = remember(http) {
        val client = BotsClient(http)
        BotsState(client::list, { token, bot ->
            client.create(token, bot.id, bot.name, bot.instructions, bot.avatar)
        }, client::update)
    }

    val context = LocalContext.current.applicationContext
    val todo = remember(http, context) {
        TodoState(TodoCache(context.noBackupFilesDir.resolve("miniapps")), ClientTodoService(AppsClient(http)))
    }

    var devToken by remember { mutableStateOf("") }
    val generatedClient = remember(http) { GeneratedAppsClient(http) }
    val generatedRoot = remember(context) { context.noBackupFilesDir.resolve("generated-miniapps") }
    val offlineEntries = remember(generatedRoot) { GeneratedOfflineEntries(generatedRoot.resolve("native-directory")) }
    val generated = remember(devToken, generatedClient) { GeneratedUiState(generatedClient,devToken,generatedRoot,offlineEntries) }
    DisposableEffect(generated) { onDispose { generated.retire() } }
    var authorShortcut by remember(devToken) { mutableStateOf<Bot?>(null) }
    var todoDirectory by remember(devToken) { mutableStateOf(false) }
    var tokenInput by remember { mutableStateOf("") }
    var conversations by remember { mutableStateOf(ChatSession("")) }
    val chatRequests = remember(conversations, scope) {
        CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))
    }
    DisposableEffect(chatRequests) { onDispose { chatRequests.cancel() } }
    var selectedChat by remember { mutableStateOf<String?>(null) }
    var discardBotEditor by remember { mutableStateOf(false) }
    var checking by remember { mutableStateOf(false) }
    var healthMessage by remember { mutableStateOf("尚未检查") }

    DisposableEffect(http) {
        onDispose { http.close() }
    }

    val todoBack = navState.currentTab == NavTab.MINIAPPS && todoDirectory
    val generatedVisible = authorShortcut != null || navState.currentTab == NavTab.MINIAPPS && !todoDirectory
    fun closeEditor() {
        if (bots.busy) return
        if (bots.dirty || bots.pendingCreate != null) discardBotEditor = true else bots.discardEditor()
    }

    BackHandler(enabled = bots.editing || navState.canHandleBack() || todoBack) {
        if (bots.editing) closeEditor()
        else if (todoBack) {
            if (todo.viewing) todo.close() else todoDirectory = false
        } else navState.handleBackPressed()
    }

    LaunchedEffect(devToken, navState.currentTab) {
        if (!bots.loaded && devToken.isNotBlank() &&
            (navState.currentTab == NavTab.CHAT || navState.currentTab == NavTab.TASKS ||
                navState.currentTab == NavTab.MINIAPPS)) bots.refresh()
    }
    LaunchedEffect(conversations, bots.bots.map { it.id }, navState.currentTab, navState.chatScreen, bots.editing) {
        if (navState.currentTab != NavTab.CHAT || navState.chatScreen != ChatScreen.LIST ||
            conversations.token.isBlank() || bots.editing) return@LaunchedEffect
        val session = conversations
        bots.bots.chunked(4).forEach { group ->
            coroutineScope {
                group.forEach { bot ->
                    launch {
                        val current = session.forBot(bot.id)
                        if (!current.historyLoaded && !current.busy) {
                            current.refreshHistory { chat.loadHistory(session.token, bot.id) }
                        }
                    }
                }
            }
        }
    }

    if (discardBotEditor) {
        AlertDialog(
            onDismissRequest = { discardBotEditor = false },
            title = { Text("放弃角色编辑？") },
            text = { Text(if (bots.pendingCreate != null) "创建可能已在后台完成；放弃后请刷新列表核对，不要重复创建。"
                else "尚未保存的角色草稿将丢失。") },
            dismissButton = { TextButton(onClick = { discardBotEditor = false }) { Text("继续编辑") } },
            confirmButton = { TextButton(onClick = {
                discardBotEditor = false
                bots.discardEditor()
            }) { Text("确认放弃") } },
        )
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            Box(Modifier.weight(1f)) {
                if (generatedVisible) {
                    GeneratedCenter(generated,bots.bots,scope,authorShortcut,
                        onTodo = if (authorShortcut == null) ({ todoDirectory = true }) else null,
                        onBack = {
                            if (generated.route == GeneratedRoute.Center && authorShortcut != null) authorShortcut = null
                            else generated.back()
                        })
                } else when (navState.currentTab) {
                    NavTab.CHAT -> {
                        val bot = bots.bots.firstOrNull { it.id == selectedChat }
                        when {
                            bots.editing -> BotEditorScreen(
                                bots, onBack = ::closeEditor,
                                onSave = { scope.launch { bots.save() } },
                                onRefreshConflict = { scope.launch { bots.refreshConflict() } },
                            )
                            navState.chatScreen == ChatScreen.DETAIL && bot != null -> {
                                key(conversations, bot.id) {
                                    ChatDetailScreen(chat, conversations.token, conversations.forBot(bot.id), navState, bot, chatRequests) {
                                        generated.center()
                                        authorShortcut = bot
                                    }
                                }
                            }
                            else -> ConversationListScreen(
                                navState, bots, conversations,
                                onRefresh = { scope.launch { bots.refresh() } },
                                onChat = { id -> selectedChat = id; navState.openChatDetail() },
                            )
                        }
                    }
                    NavTab.TASKS -> {
                        TasksScreen(
                            state = tasks,
                            bots = bots.bots,
                            onRefresh = { scope.launch { tasks.refresh() } },
                            onSubmit = { botId -> scope.launch { tasks.submit(botId) } },
                            onRerun = { id -> scope.launch { tasks.rerun(id) } },
                            onCancel = { id -> scope.launch { tasks.cancel(id) } }
                        )
                    }
                    NavTab.MINIAPPS -> {
                        MiniappsScreen(state = todo, scope = scope, onDirectoryBack = { todoDirectory = false })
                    }
                    NavTab.SETTINGS -> {
                        SettingsScreen(
                            devToken = devToken,
                            onDevTokenChange = {
                                if (devToken != it) {
                                    generated.retire()
                                    bots.setToken(it)
                                    tasks.setToken(it)
                                    todo.setToken(it)
                                    chatRequests.cancel()
                                    conversations = ChatSession(it)
                                    selectedChat = null
                                    navState.backToList()
                                    devToken = it
                                }
                            },
                            tokenInput = tokenInput,
                            onTokenInputChange = { tokenInput = it },
                            checking = checking,
                            onCheckHealth = {
                                checking = true
                                healthMessage = "检查中…"
                                scope.launch {
                                    try {
                                        healthMessage = when (val result = health.check()) {
                                            HealthResult.Connected -> "已连接 Go 服务"
                                            is HealthResult.Failed -> result.message
                                        }
                                    } catch (_: TimeoutCancellationException) {
                                        healthMessage = "连接超时，请重试"
                                    } finally {
                                        checking = false
                                    }
                                }
                            },
                            healthMessage = healthMessage,
                            theme = theme
                        )
                    }
                }
            }
            if (!bots.editing && authorShortcut == null &&
                (navState.currentTab != NavTab.MINIAPPS || !todoDirectory && generated.route == GeneratedRoute.Center) &&
                navState.showsBottomBar(todo.viewing)) {
                AbuBottomBar(navState.currentTab,navState::selectTab)
            }
        }
    }
}
