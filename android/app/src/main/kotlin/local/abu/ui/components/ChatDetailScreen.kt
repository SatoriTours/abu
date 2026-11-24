package local.abu.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import local.abu.R
import local.abu.data.Bot
import local.abu.data.ChatClient
import local.abu.data.ChatException
import local.abu.data.ChatMessage
import local.abu.data.ChatSessionInfo
import local.abu.data.DEFAULT_SESSION_ID
import local.abu.ui.ChatSession
import local.abu.ui.ChatState
import local.abu.ui.model.formatChatDayHeader
import local.abu.ui.model.formatRelativeChatTime
import local.abu.ui.model.formatShortClock
import local.abu.ui.model.sendableChatText
import local.abu.ui.model.shouldShowTimeHeader
import local.abu.ui.navigation.AppNavigationState
import java.util.UUID

private const val CHAT_PAGE_LIMIT = 50

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChatDetailScreen(
    client: ChatClient,
    token: String,
    session: ChatSession,
    state: ChatState,
    navState: AppNavigationState,
    bot: Bot,
    scope: CoroutineScope,
    onEditBot: () -> Unit = {},
    onApps: () -> Unit,
) {
    val listState = rememberLazyListState()
    val colors = MaterialTheme.colorScheme
    val context = LocalContext.current
    val keyboard = LocalSoftwareKeyboardController.current
    var refresh by remember { mutableIntStateOf(0) }
    var menuOpen by remember { mutableStateOf(false) }
    var messageMenuId by remember { mutableStateOf<String?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<ChatMessage?>(null) }
    var editingMessage by remember { mutableStateOf<ChatMessage?>(null) }
    var editDraft by remember { mutableStateOf("") }
    var searchOpen by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var searchCursor by remember { mutableIntStateOf(0) }
    var copiedKey by remember { mutableStateOf<String?>(null) }
    var sessionsDialogOpen by remember { mutableStateOf(false) }
    var newSessionTitle by remember { mutableStateOf("") }
    var sessionBusy by remember { mutableStateOf(false) }
    var sessionError by remember { mutableStateOf<String?>(null) }
    var renamingSession by remember { mutableStateOf<ChatSessionInfo?>(null) }
    var renameDraft by remember { mutableStateOf("") }
    var deletingSession by remember { mutableStateOf<ChatSessionInfo?>(null) }

    val sessions = session.sessionsForBot(bot.id)
    val activeSessionTitle = sessions.firstOrNull { it.id == state.sessionId }?.title
        ?: if (state.sessionId == DEFAULT_SESSION_ID) "默认对话" else "对话"
    val headerOffset = if (state.hasMore) 1 else 0

    val matchingIndices = remember(state.messages, searchQuery, searchOpen) {
        val needle = searchQuery.trim()
        if (!searchOpen || needle.isEmpty()) emptyList()
        else state.messages.indices.filter { idx ->
            val msg = state.messages[idx]
            msg.content.contains(needle, ignoreCase = true) ||
                msg.reply.contains(needle, ignoreCase = true)
        }
    }
    LaunchedEffect(matchingIndices, headerOffset) {
        if (matchingIndices.isNotEmpty()) {
            searchCursor = matchingIndices.lastIndex
            listState.scrollToItem(matchingIndices[searchCursor] + headerOffset)
        } else {
            searchCursor = 0
        }
    }

    fun copyText(text: String, feedbackKey: String? = null) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        clipboard.setPrimaryClip(ClipData.newPlainText("abu-chat", text))
        if (feedbackKey != null) copiedKey = feedbackKey
    }

    LaunchedEffect(copiedKey) {
        if (copiedKey != null) {
            delay(1_500)
            copiedKey = null
        }
    }

    LaunchedEffect(state, token, refresh) {
        if (token.isBlank() || state.busy) return@LaunchedEffect
        state.refreshHistory {
            client.loadHistory(token, state.botId, sessionId = state.sessionId, limit = CHAT_PAGE_LIMIT)
        }
    }

    LaunchedEffect(session, bot.id, token, refresh) {
        if (token.isBlank()) return@LaunchedEffect
        try {
            val loaded = client.listSessions(token, bot.id)
            currentCoroutineContext().ensureActive()
            session.setSessions(bot.id, loaded)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }

    val lastMessageId = state.messages.lastOrNull()?.id
    LaunchedEffect(lastMessageId) {
        if (state.messages.isNotEmpty()) {
            listState.scrollToItem(state.messages.lastIndex + headerOffset)
        }
    }

    LaunchedEffect(state.streamingText.length) {
        if (state.streamingText.isNotEmpty() && state.messages.isNotEmpty()) {
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            val bottomIndex = state.messages.lastIndex + headerOffset
            if (lastVisible >= bottomIndex - 1) {
                listState.scrollToItem(bottomIndex)
            }
        }
    }

    suspend fun refreshAfterError() {
        try {
            state.reload(client, token, limit = CHAT_PAGE_LIMIT)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }

    suspend fun requestReply(id: String, retry: Boolean, regenerate: Boolean = false) {
        val runId = state.startStreaming(id)
        currentCoroutineContext()[Job]?.let { state.bindReplyJob(runId, it) }
        try {
            val reply = client.replyStream(
                token = token,
                messageId = id,
                retry = retry,
                botId = state.botId,
                runId = runId,
                regenerate = regenerate,
                sessionId = state.sessionId,
            ) { delta ->
                state.appendDelta(id, delta, runId)
            }
            currentCoroutineContext().ensureActive()
            state.acceptReply(reply)
        } finally {
            state.finishStreaming(runId)
        }
    }

    fun stopReply() {
        val activeId = state.replyActivity ?: return
        val runId = state.activeReplyRunId ?: return
        if (state.stopping || token.isBlank()) return
        state.stopping = true
        scope.launch {
            val cancelledRemotely = try {
                withTimeoutOrNull(3_000) {
                    client.cancelReply(token, activeId, state.botId, runId = runId, sessionId = state.sessionId)
                } ?: false
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                false
            }
            if (!cancelledRemotely) {
                // Network dropped, timed out, or server no longer active: abort only this exact run.
                state.cancelReplyJobIfMatching(runId)
            } else {
                // Give the server a short window to flush the final `done` frame with partial text.
                delay(2_500)
                state.cancelReplyJobIfMatching(runId)
            }
        }
    }

    fun loadOlder() {
        if (token.isBlank() || !state.hasMore || state.loadingOlder || state.busy) return
        scope.launch {
            val added = state.loadOlderHistory { beforeId ->
                client.loadHistory(
                    token = token,
                    botId = state.botId,
                    sessionId = state.sessionId,
                    before = beforeId,
                    limit = CHAT_PAGE_LIMIT,
                )
            }
            if (added > 0) {
                val nextOffset = if (state.hasMore) 1 else 0
                listState.scrollToItem((added + nextOffset).coerceAtMost(state.messages.lastIndex + nextOffset))
            }
        }
    }

    fun send() {
        if (token.isBlank()) return
        val content = sendableChatText(state.draft, state.busy) ?: return
        if (state.pendingText != content) {
            state.pendingId = UUID.randomUUID().toString()
            state.pendingText = content
        }
        val id = requireNotNull(state.pendingId)
        state.busy = true
        state.error = null
        scope.launch {
            var saved = false
            try {
                val message = client.send(token, id, content, state.botId, sessionId = state.sessionId)
                currentCoroutineContext().ensureActive()
                state.update(message)
                state.draft = ""
                state.pendingId = null
                state.pendingText = null
                saved = true
                if (state.modelConfigured) requestReply(message.id, false)
            } catch (e: CancellationException) {
                if (saved && scope.isActive) {
                    scope.launch { refreshAfterError() }
                }
                throw e
            } catch (e: Exception) {
                state.error = (e as? ChatException)?.message
                    ?: if (saved) "消息已保存，回复未确认；请刷新历史" else "保存失败，内容已保留，可重试"
                if (saved) refreshAfterError()
            } finally {
                state.busy = false
            }
        }
    }

    fun retryReply(msg: ChatMessage, regenerate: Boolean = false) {
        if (state.busy || token.isBlank()) return
        state.busy = true
        state.error = null
        scope.launch {
            try {
                requestReply(msg.id, retry = !regenerate && msg.replyStatus.isNotEmpty(), regenerate = regenerate)
            } catch (e: CancellationException) {
                if (scope.isActive) {
                    scope.launch { refreshAfterError() }
                }
                throw e
            } catch (e: Exception) {
                state.error = (e as? ChatException)?.message ?: "回复未确认，请刷新历史后再操作"
                refreshAfterError()
            } finally {
                state.busy = false
            }
        }
    }

    fun editAndResend(msg: ChatMessage, updatedText: String) {
        if (state.busy || token.isBlank()) return
        val content = sendableChatText(updatedText, busy = false) ?: return
        state.busy = true
        state.error = null
        scope.launch {
            var edited = false
            try {
                val updated = client.editMessage(token, msg.id, content, state.botId, sessionId = state.sessionId)
                currentCoroutineContext().ensureActive()
                state.update(updated)
                edited = true
                if (state.modelConfigured) requestReply(updated.id, retry = false)
            } catch (e: CancellationException) {
                if (edited && scope.isActive) {
                    scope.launch { refreshAfterError() }
                }
                throw e
            } catch (e: Exception) {
                state.error = (e as? ChatException)?.message
                    ?: if (edited) "消息已修改，回复未确认；请刷新历史" else "修改消息失败，请重试"
                if (edited) refreshAfterError()
            } finally {
                state.busy = false
            }
        }
    }

    fun deleteMessage(msg: ChatMessage) {
        if (state.busy || token.isBlank()) return
        state.busy = true
        state.error = null
        scope.launch {
            try {
                client.deleteMessage(token, msg.id, state.botId, sessionId = state.sessionId)
                currentCoroutineContext().ensureActive()
                state.removeMessage(msg.id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                state.error = (e as? ChatException)?.message ?: "删除失败，请重试"
            } finally {
                state.busy = false
            }
        }
    }

    fun clearConversation() {
        if (state.busy || token.isBlank()) return
        state.busy = true
        state.error = null
        scope.launch {
            try {
                client.clearMessages(token, state.botId, sessionId = state.sessionId)
                currentCoroutineContext().ensureActive()
                state.clearAll()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                state.error = (e as? ChatException)?.message ?: "清空对话失败，请重试"
            } finally {
                state.busy = false
            }
        }
    }

    fun createNewSession() {
        if (token.isBlank() || sessionBusy) return
        val title = newSessionTitle.trim().takeIf { it.isNotEmpty() }
        if (title != null && title.codePointCount(0, title.length) > 60) {
            sessionError = "会话标题最多 60 个字符"
            return
        }
        sessionBusy = true
        sessionError = null
        scope.launch {
            try {
                val created = client.createSession(token, UUID.randomUUID().toString(), title, bot.id)
                val latest = runCatching { client.listSessions(token, bot.id) }.getOrElse { sessions + created }
                currentCoroutineContext().ensureActive()
                session.setSessions(bot.id, latest)
                newSessionTitle = ""
                sessionsDialogOpen = false
                session.selectSession(bot.id, created.id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                sessionError = (e as? ChatException)?.message ?: "新建会话失败，请重试"
            } finally {
                sessionBusy = false
            }
        }
    }

    fun commitRenameSession(target: ChatSessionInfo, rawTitle: String) {
        val title = rawTitle.trim()
        if (token.isBlank() || sessionBusy || title.isEmpty() || title.codePointCount(0, title.length) > 60) return
        sessionBusy = true
        sessionError = null
        scope.launch {
            try {
                val updated = client.renameSession(token, target.id, title, bot.id)
                val latest = runCatching { client.listSessions(token, bot.id) }
                    .getOrElse { sessions.map { if (it.id == updated.id) updated else it } }
                currentCoroutineContext().ensureActive()
                session.setSessions(bot.id, latest)
                renamingSession = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                sessionError = (e as? ChatException)?.message ?: "重命名失败，请重试"
            } finally {
                sessionBusy = false
            }
        }
    }

    fun confirmDeleteSession(target: ChatSessionInfo) {
        if (token.isBlank() || sessionBusy || target.id == DEFAULT_SESSION_ID) return
        sessionBusy = true
        sessionError = null
        scope.launch {
            try {
                client.deleteSession(token, target.id, bot.id)
                currentCoroutineContext().ensureActive()
                session.removeSession(bot.id, target.id)
                deletingSession = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                sessionError = (e as? ChatException)?.message ?: "删除会话失败，请重试"
            } finally {
                sessionBusy = false
            }
        }
    }

    if (sessionsDialogOpen) {
        val currentBusy by rememberUpdatedState(sessionBusy)
        val sheetState = rememberModalBottomSheetState(
            skipPartiallyExpanded = true,
            confirmValueChange = { targetValue ->
                !currentBusy || targetValue != SheetValue.Hidden
            },
        )
        ModalBottomSheet(
            onDismissRequest = { if (!sessionBusy) sessionsDialogOpen = false },
            sheetState = sheetState,
            properties = ModalBottomSheetProperties(shouldDismissOnBackPress = !sessionBusy),
            containerColor = colors.surface,
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
            dragHandle = { BottomSheetDefaults.DragHandle(color = colors.outlineVariant) },
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .imePadding()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        "${bot.name} 的对话会话",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.onSurface,
                    )
                    TextButton(enabled = !sessionBusy, onClick = { sessionsDialogOpen = false }) {
                        Text("完成")
                    }
                }
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    AbuTextField(
                        value = newSessionTitle,
                        onValueChange = { if (it.length <= 120) newSessionTitle = it },
                        modifier = Modifier.weight(1f),
                        placeholder = "新对话标题（可选）",
                        singleLine = true,
                        enabled = !sessionBusy,
                    )
                    Button(
                        onClick = ::createNewSession,
                        enabled = !sessionBusy && token.isNotBlank(),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.height(42.dp),
                        contentPadding = PaddingValues(horizontal = 16.dp),
                    ) { Text("新建", fontWeight = FontWeight.Medium) }
                }
                sessionError?.let { err ->
                    Text(err, color = colors.error, style = MaterialTheme.typography.bodySmall)
                }
                LazyColumn(
                    Modifier.fillMaxWidth().heightIn(max = 320.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    itemsIndexed(sessions, key = { _, item -> item.id }) { _, item ->
                        val selected = item.id == state.sessionId
                        val itemShape = RoundedCornerShape(14.dp)
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(itemShape)
                                .background(if (selected) colors.secondaryContainer else colors.background)
                                .border(
                                    width = if (selected) 1.dp else 0.75.dp,
                                    color = if (selected) colors.primary.copy(alpha = 0.45f) else colors.outlineVariant.copy(alpha = 0.7f),
                                    shape = itemShape,
                                )
                                .clickable(enabled = !sessionBusy) {
                                    sessionsDialogOpen = false
                                    session.selectSession(bot.id, item.id)
                                }
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(
                                    item.title,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                    color = colors.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                val timeLabel = formatRelativeChatTime(item.updatedAt)
                                if (timeLabel.isNotEmpty()) {
                                    Text(
                                        timeLabel,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = colors.onSurfaceVariant,
                                    )
                                }
                            }
                            TextButton(
                                enabled = !sessionBusy,
                                onClick = {
                                    renameDraft = item.title
                                    renamingSession = item
                                },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                            ) { Text("改名") }
                            if (item.id != DEFAULT_SESSION_ID) {
                                TextButton(
                                    enabled = !sessionBusy,
                                    onClick = { deletingSession = item },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                ) { Text("删除", color = colors.error) }
                            }
                        }
                    }
                }
            }
        }
    }

    renamingSession?.let { target ->
        val cp = renameDraft.trim().codePointCount(0, renameDraft.trim().length)
        AlertDialog(
            onDismissRequest = { if (!sessionBusy) renamingSession = null },
            containerColor = colors.surface,
            shape = RoundedCornerShape(22.dp),
            title = { Text("重命名会话", fontWeight = FontWeight.SemiBold) },
            text = {
                AbuTextField(
                    value = renameDraft,
                    onValueChange = { if (it.length <= 120) renameDraft = it },
                    placeholder = "输入新会话名称",
                    singleLine = true,
                    isError = cp > 60,
                    supportingText = "$cp / 60",
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            dismissButton = { TextButton(enabled = !sessionBusy, onClick = { renamingSession = null }) { Text("取消") } },
            confirmButton = {
                TextButton(
                    enabled = !sessionBusy && cp in 1..60,
                    onClick = { commitRenameSession(target, renameDraft) },
                ) { Text("保存") }
            },
        )
    }

    deletingSession?.let { target ->
        AlertDialog(
            onDismissRequest = { if (!sessionBusy) deletingSession = null },
            containerColor = colors.surface,
            shape = RoundedCornerShape(22.dp),
            title = { Text("删除会话「${target.title}」？", fontWeight = FontWeight.SemiBold) },
            text = { Text("将删除该会话中的全部消息及本地草稿，且无法恢复。", color = colors.onSurfaceVariant) },
            dismissButton = { TextButton(enabled = !sessionBusy, onClick = { deletingSession = null }) { Text("取消") } },
            confirmButton = {
                TextButton(
                    enabled = !sessionBusy,
                    onClick = { confirmDeleteSession(target) },
                ) { Text("确认删除", color = colors.error) }
            },
        )
    }

    editingMessage?.let { target ->
        val editCodePoints = editDraft.codePointCount(0, editDraft.length)
        val canConfirmEdit = sendableChatText(editDraft, busy = state.busy) != null
        AlertDialog(
            onDismissRequest = { editingMessage = null },
            containerColor = colors.surface,
            shape = RoundedCornerShape(22.dp),
            title = { Text("编辑并重发", fontWeight = FontWeight.SemiBold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    AbuTextField(
                        value = editDraft,
                        onValueChange = { incoming ->
                            val cp = incoming.codePointCount(0, incoming.length)
                            editDraft = if (cp <= 4_500) incoming else incoming.substring(0, incoming.offsetByCodePoints(0, 4_500))
                        },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = false,
                        minLines = 2,
                        maxLines = 6,
                        isError = editCodePoints > 4000,
                        supportingText = if (editCodePoints > 4000) "最多 4000 字符（当前 $editCodePoints）"
                        else "$editCodePoints / 4000",
                    )
                    Text(
                        if (state.modelConfigured) "保存后会清空原回复并重新生成（可能再次计费）。"
                        else "保存后会更新这条消息内容。",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                    )
                }
            },
            dismissButton = { TextButton(onClick = { editingMessage = null }) { Text("取消") } },
            confirmButton = {
                TextButton(
                    enabled = canConfirmEdit,
                    onClick = {
                        val next = editDraft
                        editingMessage = null
                        editAndResend(target, next)
                    },
                ) { Text(if (state.modelConfigured) "保存并重发" else "保存") }
            },
        )
    }

    pendingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            containerColor = colors.surface,
            shape = RoundedCornerShape(22.dp),
            title = { Text("删除这条消息？", fontWeight = FontWeight.SemiBold) },
            text = { Text("将从当前角色的对话记录中删除该消息及其回复，删除后无法恢复。", color = colors.onSurfaceVariant) },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("取消") } },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingDelete = null
                        deleteMessage(target)
                    },
                ) { Text("确认删除", color = colors.error) }
            },
        )
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            containerColor = colors.surface,
            shape = RoundedCornerShape(22.dp),
            title = { Text("清空与「${bot.name}」的对话？", fontWeight = FontWeight.SemiBold) },
            text = { Text("仅清空当前角色的聊天记录，不影响其他角色、未发送草稿和后台任务。", color = colors.onSurfaceVariant) },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("取消") } },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmClear = false
                        clearConversation()
                    },
                ) { Text("确认清空", color = colors.error) }
            },
        )
    }

    val subtitle = remember(bot.instructions) {
        bot.instructions.trim().lineSequence().firstOrNull()?.takeIf { it.isNotBlank() } ?: "你的日常小搭子"
    }
    val canMutateHistory = token.isNotBlank() && !state.busy && state.replyActivity == null
    val isStreaming = state.replyActivity != null

    Column(Modifier.fillMaxSize().background(colors.background).imePadding()) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(colors.surface)
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { keyboard?.hide(); navState.backToList() }) {
                Icon(painterResource(R.drawable.ic_arrow_back), "返回对话列表")
            }
            BotAvatar(
                size = 40.dp, avatar = bot.avatar, animate = true,
                working = isStreaming || state.messages.lastOrNull()?.replyStatus == "running",
                completed = state.justCompletedReply,
            )
            Column(
                Modifier
                    .weight(1f)
                    .padding(start = 8.dp)
                    .clickable(enabled = token.isNotBlank()) {
                        sessionError = null
                        sessionsDialogOpen = true
                    },
                verticalArrangement = Arrangement.spacedBy(1.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        bot.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "$activeSessionTitle ▾",
                        Modifier
                            .clip(RoundedCornerShape(99.dp))
                            .background(colors.surfaceVariant.copy(alpha = 0.72f))
                            .border(0.6.dp, colors.outlineVariant.copy(alpha = 0.7f), RoundedCornerShape(99.dp))
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Box(
                        Modifier
                            .size(5.dp)
                            .clip(CircleShape)
                            .background(colors.primary),
                    )
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Box {
                IconButton(onClick = { menuOpen = true }, enabled = token.isNotBlank()) {
                    Icon(
                        painterResource(R.drawable.ic_more),
                        "更多对话操作",
                        tint = colors.onSurfaceVariant,
                    )
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("切换 / 管理会话") },
                        enabled = token.isNotBlank(),
                        onClick = {
                            menuOpen = false
                            sessionError = null
                            sessionsDialogOpen = true
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(if (searchOpen) "关闭搜索" else "搜索消息") },
                        enabled = state.messages.isNotEmpty() || searchOpen,
                        onClick = {
                            menuOpen = false
                            if (searchOpen) {
                                searchOpen = false
                                searchQuery = ""
                            } else {
                                searchOpen = true
                            }
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("刷新对话") },
                        enabled = token.isNotBlank() && !state.busy,
                        onClick = {
                            menuOpen = false
                            refresh++
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("编辑角色") },
                        enabled = token.isNotBlank() && !state.busy,
                        onClick = {
                            menuOpen = false
                            keyboard?.hide()
                            onEditBot()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("小程序") },
                        enabled = token.isNotBlank(),
                        onClick = {
                            menuOpen = false
                            keyboard?.hide()
                            onApps()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("清空对话", color = if (canMutateHistory && state.messages.isNotEmpty()) colors.error else colors.onSurfaceVariant) },
                        enabled = canMutateHistory && state.messages.isNotEmpty(),
                        onClick = {
                            menuOpen = false
                            confirmClear = true
                        },
                    )
                }
            }
        }
        HorizontalDivider(thickness = 0.5.dp, color = colors.outlineVariant.copy(alpha = 0.55f))

        if (searchOpen) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(colors.surface)
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                AbuSearchPill(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = "搜索本对话内容…",
                    contentDescription = "搜索本对话消息",
                    modifier = Modifier.weight(1f),
                    trailing = if (searchQuery.isNotBlank()) ({
                        Text(
                            if (matchingIndices.isEmpty()) "0/0" else "${searchCursor + 1}/${matchingIndices.size}",
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.onSurfaceVariant,
                        )
                    }) else null,
                )
                if (matchingIndices.size > 1) {
                    TextButton(
                        onClick = {
                            searchCursor = if (searchCursor <= 0) matchingIndices.lastIndex else searchCursor - 1
                            scope.launch { listState.scrollToItem(matchingIndices[searchCursor] + headerOffset) }
                        },
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                    ) { Text("上一条") }
                    TextButton(
                        onClick = {
                            searchCursor = if (searchCursor >= matchingIndices.lastIndex) 0 else searchCursor + 1
                            scope.launch { listState.scrollToItem(matchingIndices[searchCursor] + headerOffset) }
                        },
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                    ) { Text("下一条") }
                }
                TextButton(
                    onClick = {
                        searchOpen = false
                        searchQuery = ""
                    },
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                ) { Text("关闭") }
            }
            HorizontalDivider(thickness = 0.5.dp, color = colors.outlineVariant.copy(alpha = 0.7f))
        }

        LazyColumn(
            state = listState, modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            if (state.hasMore) {
                item(key = "__load_older__") {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        TextButton(
                            enabled = !state.loadingOlder && !state.busy && token.isNotBlank(),
                            onClick = ::loadOlder,
                        ) {
                            Text(if (state.loadingOlder) "正在加载更早消息…" else "加载更早消息")
                        }
                    }
                }
            }
            itemsIndexed(state.messages, key = { _, msg -> msg.id }) { index, msg ->
                val prevCreatedAt = state.messages.getOrNull(index - 1)?.createdAt
                val showDayHeader = shouldShowTimeHeader(prevCreatedAt, msg.createdAt)
                val dayHeader = if (showDayHeader) formatChatDayHeader(msg.createdAt) else ""
                val clock = formatShortClock(msg.createdAt)
                val userCopyKey = "user:${msg.id}"
                val isSearchHit = matchingIndices.getOrNull(searchCursor) == index
                val itemModifier = if (isSearchHit) {
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(colors.secondaryContainer.copy(alpha = 0.52f))
                        .padding(horizontal = 10.dp, vertical = 8.dp)
                } else {
                    Modifier.fillMaxWidth()
                }
                Column(itemModifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (dayHeader.isNotEmpty()) {
                        Text(
                            dayHeader,
                            Modifier.align(Alignment.CenterHorizontally).padding(vertical = 2.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.onSurfaceVariant.copy(alpha = 0.72f),
                        )
                    }
                    BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                        val bubbleMaxWidth = maxWidth * 0.82f
                        SelectionContainer {
                            Text(
                                msg.content,
                                Modifier
                                    .widthIn(max = bubbleMaxWidth)
                                    .background(colors.primaryContainer, RoundedCornerShape(18.dp, 18.dp, 5.dp, 18.dp))
                                    .padding(horizontal = 14.dp, vertical = 10.dp),
                                color = colors.onPrimaryContainer,
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        }
                    }
                    Row(
                        Modifier.align(Alignment.End),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        val meta = if (clock.isNotEmpty()) "$clock · 已发送" else "已发送"
                        Text(
                            meta,
                            color = colors.onSurfaceVariant.copy(alpha = 0.72f),
                            style = MaterialTheme.typography.labelSmall,
                        )
                        if (copiedKey == userCopyKey) {
                            Text(
                                "· 已复制",
                                color = colors.primary,
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                        Box {
                            IconButton(
                                onClick = { messageMenuId = msg.id },
                                modifier = Modifier.size(26.dp),
                            ) {
                                Icon(
                                    painterResource(R.drawable.ic_more),
                                    contentDescription = "消息操作",
                                    modifier = Modifier.size(14.dp),
                                    tint = colors.onSurfaceVariant.copy(alpha = 0.62f),
                                )
                            }
                            DropdownMenu(
                                expanded = messageMenuId == msg.id,
                                onDismissRequest = { messageMenuId = null },
                            ) {
                                DropdownMenuItem(
                                    text = { Text("复制消息") },
                                    onClick = {
                                        messageMenuId = null
                                        copyText(msg.content, userCopyKey)
                                    },
                                )
                                if (canMutateHistory && index == state.messages.lastIndex) {
                                    DropdownMenuItem(
                                        text = { Text("编辑并重发") },
                                        onClick = {
                                            messageMenuId = null
                                            editDraft = msg.content
                                            editingMessage = msg
                                        },
                                    )
                                }
                                if (canMutateHistory) {
                                    DropdownMenuItem(
                                        text = { Text("删除消息", color = colors.error) },
                                        onClick = {
                                            messageMenuId = null
                                            pendingDelete = msg
                                        },
                                    )
                                }
                            }
                        }
                    }

                    when {
                        state.replyActivity == msg.id -> {
                            ReplyHeading(bot, badge = if (state.stopping) "正在停止…" else "生成中…")
                            if (state.streamingText.isNotEmpty()) {
                                MarkdownContent(
                                    text = state.streamingText,
                                    onCopyCode = { copyText(it) },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            } else {
                                Text(
                                    "${bot.name}正在思考…",
                                    color = colors.onSurfaceVariant,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                        }

                        msg.replyStatus == "running" -> {
                            ReplyHeading(bot, badge = "生成中…")
                            Text(
                                "${bot.name}正在生成回复…",
                                color = colors.onSurfaceVariant,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }

                        msg.replyStatus == "completed" -> {
                            ReplyHeading(bot)
                            MarkdownContent(
                                text = msg.reply,
                                onCopyCode = { copyText(it) },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            val replyCopyKey = "reply:${msg.id}"
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(2.dp),
                            ) {
                                IconButton(
                                    onClick = { copyText(msg.reply, replyCopyKey) },
                                    modifier = Modifier.size(30.dp),
                                ) {
                                    Icon(
                                        painterResource(R.drawable.ic_copy),
                                        contentDescription = if (copiedKey == replyCopyKey) "已复制回复" else "复制回复",
                                        modifier = Modifier.size(15.dp),
                                        tint = if (copiedKey == replyCopyKey) colors.primary else colors.onSurfaceVariant.copy(alpha = 0.68f),
                                    )
                                }
                                if (copiedKey == replyCopyKey) {
                                    Text(
                                        "已复制",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = colors.primary,
                                    )
                                }
                                if (state.modelConfigured && index == state.messages.lastIndex) {
                                    IconButton(
                                        enabled = !state.busy,
                                        onClick = { retryReply(msg, regenerate = true) },
                                        modifier = Modifier.size(30.dp),
                                    ) {
                                        Icon(
                                            painterResource(R.drawable.ic_refresh),
                                            contentDescription = "重新生成回复",
                                            modifier = Modifier.size(15.dp),
                                            tint = colors.onSurfaceVariant.copy(alpha = 0.68f),
                                        )
                                    }
                                }
                            }
                        }

                        msg.replyStatus == "interrupted" && msg.reply.isNotBlank() -> {
                            ReplyHeading(bot, badge = "已停止")
                            MarkdownContent(
                                text = msg.reply,
                                onCopyCode = { copyText(it) },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            val replyCopyKey = "reply:${msg.id}"
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(2.dp),
                            ) {
                                IconButton(
                                    onClick = { copyText(msg.reply, replyCopyKey) },
                                    modifier = Modifier.size(30.dp),
                                ) {
                                    Icon(
                                        painterResource(R.drawable.ic_copy),
                                        contentDescription = if (copiedKey == replyCopyKey) "已复制回复" else "复制回复",
                                        modifier = Modifier.size(15.dp),
                                        tint = if (copiedKey == replyCopyKey) colors.primary else colors.onSurfaceVariant.copy(alpha = 0.68f),
                                    )
                                }
                                if (copiedKey == replyCopyKey) {
                                    Text(
                                        "已复制",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = colors.primary,
                                    )
                                }
                                if (state.modelConfigured && index == state.messages.lastIndex) {
                                    IconButton(
                                        enabled = !state.busy,
                                        onClick = { retryReply(msg) },
                                        modifier = Modifier.size(30.dp),
                                    ) {
                                        Icon(
                                            painterResource(R.drawable.ic_refresh),
                                            contentDescription = "重试回复",
                                            modifier = Modifier.size(15.dp),
                                            tint = colors.onSurfaceVariant.copy(alpha = 0.68f),
                                        )
                                    }
                                }
                            }
                            if (state.modelConfigured && index == state.messages.lastIndex) {
                                Text(
                                    "已停止生成；重试将重新生成并可能再次计费。",
                                    color = colors.onSurfaceVariant.copy(alpha = 0.78f),
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                        }

                        state.modelConfigured && index == state.messages.lastIndex -> {
                            if (msg.replyStatus.isNotEmpty()) {
                                Text(
                                    "上次回复失败或中断；重试可能再次计费。",
                                    color = colors.error,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                            TextButton(
                                enabled = !state.busy,
                                onClick = { retryReply(msg) },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                            ) { Text(if (msg.replyStatus.isEmpty()) "生成回复" else "重试回复") }
                        }
                    }
                }
            }

            if (state.messages.isEmpty()) {
                item {
                    Text(
                        when {
                            token.isBlank() -> "请先在设置中应用开发令牌，再开始对话。"
                            state.busy -> "正在读取对话…"
                            state.historyLoaded -> "还没有对话，和${bot.name}说点什么吧。"
                            else -> "连接后台后才能读取对话。"
                        },
                        color = colors.onSurfaceVariant,
                    )
                }
            }
            if (state.historyLoaded && !state.modelConfigured) {
                item { Text("后台未配置模型。可以保存文字，暂不生成回复。", color = colors.onSurfaceVariant) }
            }
            state.error?.let { message ->
                item { Text(message, color = colors.error, style = MaterialTheme.typography.bodyMedium) }
            }
        }

        val draftCodePoints = state.draft.codePointCount(0, state.draft.length)
        if (draftCodePoints > 3600) {
            Text(
                if (draftCodePoints > 4000) "消息最多 4000 字符（当前 $draftCodePoints），请缩短后再发送。"
                else "$draftCodePoints / 4000",
                Modifier.padding(horizontal = 20.dp, vertical = 2.dp),
                color = if (draftCodePoints > 4000) colors.error else colors.onSurfaceVariant,
                style = MaterialTheme.typography.labelMedium,
            )
        }

        val canSend = token.isNotBlank() && sendableChatText(state.draft, state.busy) != null
        val composerShape = RoundedCornerShape(26.dp)
        Row(
            Modifier
                .padding(horizontal = 14.dp, vertical = 8.dp)
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clip(composerShape)
                .background(colors.surface)
                .border(0.75.dp, colors.outlineVariant.copy(alpha = 0.9f), composerShape)
                .padding(start = 4.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            IconButton(
                onClick = { keyboard?.hide(); onApps() },
                enabled = token.isNotBlank(),
                modifier = Modifier.size(44.dp),
            ) {
                Icon(
                    painterResource(R.drawable.ic_add_bot),
                    contentDescription = "此角色的小程序操作",
                    modifier = Modifier.size(20.dp),
                    tint = if (token.isNotBlank()) colors.onSurfaceVariant else colors.onSurfaceVariant.copy(alpha = 0.4f),
                )
            }
            BasicTextField(
                value = state.draft,
                onValueChange = { incoming ->
                    val cp = incoming.codePointCount(0, incoming.length)
                    state.draft = if (cp <= 4_500) incoming else incoming.substring(0, incoming.offsetByCodePoints(0, 4_500))
                },
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = 11.dp, horizontal = 2.dp)
                    .semantics { contentDescription = "消息正文" },
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.onSurface),
                cursorBrush = SolidColor(colors.primary),
                singleLine = false,
                minLines = 1,
                maxLines = 5,
                enabled = !state.busy && token.isNotBlank(),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
                decorationBox = { field ->
                    Box {
                        if (state.draft.isEmpty()) {
                            Text(
                                "和${bot.name}说点什么…",
                                color = colors.onSurfaceVariant.copy(alpha = 0.78f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        field()
                    }
                },
            )
            if (isStreaming) {
                IconButton(
                    onClick = ::stopReply,
                    enabled = !state.stopping,
                    modifier = Modifier.size(44.dp),
                ) {
                    Box(
                        Modifier
                            .size(34.dp)
                            .background(
                                if (state.stopping) colors.outlineVariant else colors.errorContainer,
                                CircleShape,
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            painterResource(R.drawable.ic_stop),
                            contentDescription = "停止生成",
                            modifier = Modifier.size(16.dp),
                            tint = if (state.stopping) colors.onSurfaceVariant else colors.onErrorContainer,
                        )
                    }
                }
            } else {
                IconButton(onClick = ::send, enabled = canSend, modifier = Modifier.size(44.dp)) {
                    Box(
                        Modifier
                            .size(34.dp)
                            .background(
                                if (canSend) colors.primary else colors.outlineVariant.copy(alpha = 0.75f),
                                CircleShape,
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            painterResource(R.drawable.ic_arrow_up),
                            contentDescription = "发送",
                            modifier = Modifier.size(19.dp),
                            tint = if (canSend) colors.onPrimary else colors.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ReplyHeading(bot: Bot, badge: String? = null) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        BotAvatar(size = 26.dp, avatar = bot.avatar)
        Text(
            bot.name,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!badge.isNullOrBlank()) {
            Text(
                badge,
                Modifier
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(99.dp))
                    .padding(horizontal = 7.dp, vertical = 1.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
