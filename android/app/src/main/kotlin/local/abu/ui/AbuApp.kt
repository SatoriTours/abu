package local.abu.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import local.abu.data.AppsClient
import local.abu.data.ChatClient
import local.abu.data.HealthClient
import local.abu.data.HealthResult
import local.abu.data.TaskClient
import local.abu.miniapps.ClientTodoService
import local.abu.miniapps.TodoCache
import local.abu.miniapps.TodoState
import local.abu.platform.createHealthHttpClient
import local.abu.ui.components.*
import local.abu.ui.model.TasksState
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
        TasksState(client::list, client::create, client::cancel)
    }

    val context = LocalContext.current.applicationContext
    val todo = remember(http, context) {
        TodoState(TodoCache(context.noBackupFilesDir.resolve("miniapps")), ClientTodoService(AppsClient(http)))
    }

    var devToken by remember { mutableStateOf("") }
    var tokenInput by remember { mutableStateOf("") }
    var chatState by remember { mutableStateOf(ChatState()) }
    var checking by remember { mutableStateOf(false) }
    var healthMessage by remember { mutableStateOf("尚未检查") }

    DisposableEffect(http) {
        onDispose { http.close() }
    }

    val todoBack = navState.currentTab == NavTab.MINIAPPS && todo.viewing
    BackHandler(enabled = navState.canHandleBack() || todoBack) {
        if (!navState.handleBackPressed() && todoBack) todo.close()
    }

    LaunchedEffect(chatState, devToken, navState.currentTab, navState.chatScreen) {
        if (navState.currentTab != NavTab.CHAT || navState.chatScreen != ChatScreen.LIST ||
            devToken.isBlank() || chatState.historyLoaded || chatState.busy) return@LaunchedEffect
        val current = chatState
        val token = devToken
        current.refreshHistory { chat.loadHistory(token) }
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            Box(Modifier.weight(1f)) {
                when (navState.currentTab) {
                    NavTab.CHAT -> {
                        when (navState.chatScreen) {
                            ChatScreen.LIST -> {
                                ConversationListScreen(
                                    navState = navState,
                                    chatState = chatState
                                )
                            }
                            ChatScreen.DETAIL -> {
                                ChatDetailScreen(
                                    client = chat,
                                    token = devToken,
                                    state = chatState,
                                    navState = navState
                                )
                            }
                        }
                    }
                    NavTab.TASKS -> {
                        TasksScreen(
                            state = tasks,
                            onRefresh = { scope.launch { tasks.refresh() } },
                            onSubmit = { scope.launch { tasks.submit() } },
                            onRerun = { id -> scope.launch { tasks.rerun(id) } },
                            onCancel = { id -> scope.launch { tasks.cancel(id) } }
                        )
                    }
                    NavTab.MINIAPPS -> {
                        MiniappsScreen(state = todo, scope = scope)
                    }
                    NavTab.SETTINGS -> {
                        SettingsScreen(
                            devToken = devToken,
                            onDevTokenChange = {
                                tasks.setToken(it)
                                todo.setToken(it)
                                devToken = it
                            },
                            tokenInput = tokenInput,
                            onTokenInputChange = { tokenInput = it },
                            chatState = chatState,
                            onResetChatState = { chatState = ChatState() },
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
            if (navState.showsBottomBar(todo.viewing)) AbuBottomBar(navState.currentTab, navState::selectTab)
        }
    }
}
