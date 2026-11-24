package local.abu.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
import local.abu.ui.ChatState
import local.abu.ui.model.formatChatTime
import local.abu.ui.model.sendableChatText
import local.abu.ui.navigation.AppNavigationState
import java.util.UUID

@Composable
internal fun ChatDetailScreen(
    client: ChatClient, token: String, state: ChatState, navState: AppNavigationState, bot: Bot, scope: CoroutineScope,
    onApps: () -> Unit,
) {
    val listState = rememberLazyListState()
    val colors = MaterialTheme.colorScheme
    val context = LocalContext.current
    val keyboard = LocalSoftwareKeyboardController.current
    var refresh by remember { mutableIntStateOf(0) }
    var menuOpen by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<ChatMessage?>(null) }
    var copiedKey by remember { mutableStateOf<String?>(null) }

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
        state.refreshHistory { client.loadHistory(token, state.botId) }
    }

    LaunchedEffect(state.messages.size) {
        if (state.messages.isNotEmpty()) listState.scrollToItem(state.messages.lastIndex)
    }

    LaunchedEffect(state.streamingText.length) {
        if (state.streamingText.isNotEmpty() && state.messages.isNotEmpty()) {
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            if (lastVisible >= state.messages.lastIndex - 1) {
                listState.scrollToItem(state.messages.lastIndex)
            }
        }
    }

    suspend fun refreshAfterError() {
        try {
            state.reload(client, token)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }

    suspend fun requestReply(id: String, retry: Boolean) {
        val runId = state.startStreaming(id)
        currentCoroutineContext()[Job]?.let { state.bindReplyJob(runId, it) }
        try {
            val reply = client.replyStream(token, id, retry, state.botId, runId = runId) { delta ->
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
                    client.cancelReply(token, activeId, state.botId, runId = runId)
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
                val message = client.send(token, id, content, state.botId)
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

    fun retryReply(msg: ChatMessage) {
        if (state.busy || token.isBlank()) return
        state.busy = true
        state.error = null
        scope.launch {
            try {
                requestReply(msg.id, msg.replyStatus.isNotEmpty())
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

    fun deleteMessage(msg: ChatMessage) {
        if (state.busy || token.isBlank()) return
        state.busy = true
        state.error = null
        scope.launch {
            try {
                client.deleteMessage(token, msg.id, state.botId)
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
                client.clearMessages(token, state.botId)
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

    pendingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除这条消息？") },
            text = { Text("将从当前角色的对话记录中删除该消息及其回复，删除后无法恢复。") },
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
            title = { Text("清空与「${bot.name}」的对话？") },
            text = { Text("仅清空当前角色的聊天记录，不影响其他角色、未发送草稿和后台任务。") },
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

    Column(Modifier.fillMaxSize().imePadding()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { keyboard?.hide(); navState.backToList() }) {
                Icon(painterResource(R.drawable.ic_arrow_back), "返回对话列表")
            }
            BotAvatar(
                size = 44.dp, avatar = bot.avatar, animate = true,
                working = isStreaming || state.messages.lastOrNull()?.replyStatus == "running",
                completed = state.justCompletedReply,
            )
            Column(Modifier.weight(1f).padding(start = 5.dp)) {
                Text(bot.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            IconButton(onClick = { keyboard?.hide(); onApps() }, enabled = token.isNotBlank()) {
                Icon(painterResource(R.drawable.ic_tab_apps), "此角色的小程序操作")
            }
            Box {
                IconButton(onClick = { menuOpen = true }, enabled = token.isNotBlank()) {
                    Icon(painterResource(R.drawable.ic_more), "更多对话操作")
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("刷新对话") },
                        enabled = token.isNotBlank() && !state.busy,
                        onClick = {
                            menuOpen = false
                            refresh++
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
        HorizontalDivider(color = colors.outlineVariant)

        LazyColumn(
            state = listState, modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            itemsIndexed(state.messages, key = { _, msg -> msg.id }) { index, msg ->
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    val time = formatChatTime(msg.createdAt)
                    SelectionContainer(Modifier.align(Alignment.End)) {
                        Text(
                            msg.content,
                            Modifier.fillMaxWidth(0.86f)
                                .background(colors.primaryContainer, RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp))
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            color = colors.onPrimaryContainer, style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                    Row(
                        Modifier.align(Alignment.End),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        if (time.isNotEmpty()) {
                            Text(time, color = colors.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
                        }
                        val userCopyKey = "user:${msg.id}"
                        IconButton(
                            onClick = { copyText(msg.content, userCopyKey) },
                            modifier = Modifier.size(32.dp),
                        ) {
                            Icon(
                                painterResource(R.drawable.ic_copy),
                                contentDescription = if (copiedKey == userCopyKey) "已复制消息" else "复制消息",
                                modifier = Modifier.size(15.dp),
                                tint = if (copiedKey == userCopyKey) colors.primary else colors.onSurfaceVariant,
                            )
                        }
                        if (canMutateHistory) {
                            IconButton(
                                onClick = { pendingDelete = msg },
                                modifier = Modifier.size(32.dp),
                            ) {
                                Icon(
                                    painterResource(R.drawable.ic_delete),
                                    contentDescription = "删除消息",
                                    modifier = Modifier.size(15.dp),
                                    tint = colors.onSurfaceVariant,
                                )
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
                                    modifier = Modifier.padding(start = 34.dp),
                                )
                            } else {
                                Text(
                                    "${bot.name}正在思考…",
                                    Modifier.padding(start = 34.dp),
                                    color = colors.onSurfaceVariant,
                                )
                            }
                        }

                        msg.replyStatus == "running" -> {
                            ReplyHeading(bot, badge = "生成中…")
                            Text("${bot.name}正在生成回复…", Modifier.padding(start = 34.dp), color = colors.onSurfaceVariant)
                        }

                        msg.replyStatus == "completed" -> {
                            ReplyHeading(bot)
                            MarkdownContent(
                                text = msg.reply,
                                onCopyCode = { copyText(it) },
                                modifier = Modifier.padding(start = 34.dp),
                            )
                            val replyCopyKey = "reply:${msg.id}"
                            Row(
                                Modifier.padding(start = 26.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                TextButton(
                                    onClick = { copyText(msg.reply, replyCopyKey) },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                ) {
                                    Icon(
                                        painterResource(R.drawable.ic_copy),
                                        contentDescription = null,
                                        modifier = Modifier.size(14.dp),
                                    )
                                    Spacer(Modifier.width(4.dp))
                                    Text(
                                        if (copiedKey == replyCopyKey) "已复制" else "复制回复",
                                        style = MaterialTheme.typography.labelMedium,
                                    )
                                }
                            }
                        }

                        msg.replyStatus == "interrupted" && msg.reply.isNotBlank() -> {
                            ReplyHeading(bot, badge = "已停止")
                            MarkdownContent(
                                text = msg.reply,
                                onCopyCode = { copyText(it) },
                                modifier = Modifier.padding(start = 34.dp),
                            )
                            val replyCopyKey = "reply:${msg.id}"
                            Row(
                                Modifier.padding(start = 26.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                TextButton(
                                    onClick = { copyText(msg.reply, replyCopyKey) },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                ) {
                                    Icon(
                                        painterResource(R.drawable.ic_copy),
                                        contentDescription = null,
                                        modifier = Modifier.size(14.dp),
                                    )
                                    Spacer(Modifier.width(4.dp))
                                    Text(
                                        if (copiedKey == replyCopyKey) "已复制" else "复制回复",
                                        style = MaterialTheme.typography.labelMedium,
                                    )
                                }
                                if (state.modelConfigured && index == state.messages.lastIndex) {
                                    TextButton(
                                        enabled = !state.busy,
                                        onClick = { retryReply(msg) },
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                    ) { Text("重试回复", style = MaterialTheme.typography.labelMedium) }
                                }
                            }
                            if (state.modelConfigured && index == state.messages.lastIndex) {
                                Text(
                                    "已停止生成；重试将重新生成并可能再次计费。",
                                    Modifier.padding(start = 34.dp),
                                    color = colors.onSurfaceVariant,
                                    style = MaterialTheme.typography.labelMedium,
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
        val composerShape = RoundedCornerShape(24.dp)
        Row(
            Modifier
                .padding(horizontal = 14.dp, vertical = 8.dp)
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .background(colors.surfaceVariant, composerShape)
                .border(1.dp, colors.outlineVariant, composerShape)
                .padding(start = 15.dp, end = 2.dp, top = 2.dp, bottom = 2.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            BasicTextField(
                value = state.draft,
                onValueChange = { incoming ->
                    val cp = incoming.codePointCount(0, incoming.length)
                    state.draft = if (cp <= 4_500) incoming else incoming.substring(0, incoming.offsetByCodePoints(0, 4_500))
                },
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = 10.dp)
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
                                color = colors.onSurfaceVariant,
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
                    modifier = Modifier.size(48.dp),
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
                IconButton(onClick = ::send, enabled = canSend, modifier = Modifier.size(48.dp)) {
                    Box(
                        Modifier
                            .size(34.dp)
                            .background(
                                if (canSend) colors.primary else colors.outlineVariant,
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
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        BotAvatar(size = 29.dp, avatar = bot.avatar)
        Text(bot.name, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
