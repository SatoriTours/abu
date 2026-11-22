package local.abu.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import local.abu.R
import local.abu.data.ChatClient
import local.abu.data.ChatException
import local.abu.ui.ChatState
import local.abu.ui.model.formatChatTime
import local.abu.ui.model.sendableChatText
import local.abu.ui.navigation.AppNavigationState
import java.util.UUID

@Composable
fun ChatDetailScreen(client: ChatClient, token: String, state: ChatState, navState: AppNavigationState) {
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val colors = MaterialTheme.colorScheme
    val keyboard = LocalSoftwareKeyboardController.current
    var refresh by remember { mutableIntStateOf(0) }

    LaunchedEffect(state, token, refresh) {
        if (token.isBlank()) return@LaunchedEffect
        state.refreshHistory { client.loadHistory(token) }
    }

    LaunchedEffect(state.messages.size) {
        if (state.messages.isNotEmpty()) listState.scrollToItem(state.messages.lastIndex)
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
        state.replyActivity = id
        try {
            state.acceptReply(client.reply(token, id, retry))
        } finally {
            state.replyActivity = null
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
                val message = client.send(token, id, content)
                state.update(message)
                state.draft = ""
                state.pendingId = null
                state.pendingText = null
                saved = true
                if (state.modelConfigured) requestReply(message.id, false)
            } catch (e: CancellationException) {
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

    Column(Modifier.fillMaxSize().imePadding()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { keyboard?.hide(); navState.backToList() }) {
                Icon(painterResource(R.drawable.ic_arrow_back), "返回对话列表")
            }
            BotAvatar(
                size = 44.dp, animate = true,
                working = state.replyActivity != null || state.messages.lastOrNull()?.replyStatus == "running",
                completed = state.justCompletedReply,
            )
            Column(Modifier.weight(1f).padding(start = 5.dp)) {
                Text("阿布", style = MaterialTheme.typography.titleMedium)
                Text("你的日常小搭子", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            }
            TextButton(onClick = { refresh++ }, enabled = token.isNotBlank() && !state.busy) { Text("刷新") }
        }
        HorizontalDivider(color = colors.outlineVariant)
        LazyColumn(
            state = listState, modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
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
                            color = colors.onPrimaryContainer, style = MaterialTheme.typography.bodyLarge
                        )
                    }
                    if (time.isNotEmpty()) {
                        Text(time, Modifier.align(Alignment.End), color = colors.onSurfaceVariant,
                            style = MaterialTheme.typography.labelMedium)
                    }
                    when {
                        msg.replyStatus == "completed" -> {
                            ReplyHeading()
                            SelectionContainer(Modifier.padding(start = 34.dp)) {
                                Text(msg.reply, style = MaterialTheme.typography.bodyLarge)
                            }
                        }
                        state.replyActivity == msg.id || msg.replyStatus == "running" -> {
                            ReplyHeading()
                            Text("阿布正在生成回复…", Modifier.padding(start = 34.dp), color = colors.onSurfaceVariant)
                        }
                        state.modelConfigured && index == state.messages.lastIndex -> {
                            if (msg.replyStatus.isNotEmpty()) {
                                Text("上次回复失败或中断；重试可能再次计费。", color = colors.error,
                                    style = MaterialTheme.typography.bodyMedium)
                            }
                            TextButton(
                                enabled = !state.busy,
                                onClick = {
                                    if (!state.busy) {
                                        state.busy = true
                                        state.error = null
                                        scope.launch {
                                            try {
                                                requestReply(msg.id, msg.replyStatus.isNotEmpty())
                                            } catch (e: CancellationException) {
                                                throw e
                                            } catch (e: Exception) {
                                                state.error = (e as? ChatException)?.message
                                                    ?: "回复未确认，请刷新历史后再操作"
                                                refreshAfterError()
                                            } finally {
                                                state.busy = false
                                            }
                                        }
                                    }
                                }
                            ) { Text(if (msg.replyStatus.isEmpty()) "生成回复" else "重试回复") }
                        }
                    }
                }
            }
            if (state.messages.isEmpty()) {
                item {
                    Text(when {
                        token.isBlank() -> "请先在设置中应用开发令牌，再开始对话。"
                        state.busy -> "正在读取对话…"
                        state.historyLoaded -> "还没有对话，和阿布说点什么吧。"
                        else -> "连接后台后才能读取对话。"
                    }, color = colors.onSurfaceVariant)
                }
            }
            if (state.historyLoaded && !state.modelConfigured) {
                item { Text("后台未配置模型。可以保存文字，暂不生成回复。", color = colors.onSurfaceVariant) }
            }
            state.error?.let { message ->
                item { Text(message, color = colors.error, style = MaterialTheme.typography.bodyMedium) }
            }
            if (state.draft.codePointCount(0, state.draft.length) > 4000) {
                item { Text("消息最多 4000 字符，请缩短正文。", color = colors.error) }
            }
        }

        val canSend = token.isNotBlank() && sendableChatText(state.draft, state.busy) != null
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 8.dp).fillMaxWidth().heightIn(min = 48.dp)
                .background(colors.surfaceVariant, CircleShape).border(1.dp, colors.outlineVariant, CircleShape)
                .padding(start = 15.dp, end = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            BasicTextField(
                value = state.draft,
                onValueChange = { if (it.length <= 16_000) state.draft = it },
                modifier = Modifier.weight(1f).semantics { contentDescription = "消息正文" },
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.onSurface),
                cursorBrush = SolidColor(colors.primary),
                singleLine = true,
                enabled = !state.busy && token.isNotBlank(),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { keyboard?.hide() }),
                decorationBox = { field ->
                    Box {
                        if (state.draft.isEmpty()) Text("和阿布说点什么…", color = colors.onSurfaceVariant)
                        field()
                    }
                }
            )
            IconButton(onClick = ::send, enabled = canSend, modifier = Modifier.size(48.dp)) {
                Box(
                    Modifier.size(34.dp).background(
                        if (canSend) colors.primary else colors.outlineVariant, CircleShape
                    ), contentAlignment = Alignment.Center
                ) {
                    Icon(painterResource(R.drawable.ic_arrow_up), "发送",
                        Modifier.size(19.dp), tint = if (canSend) colors.onPrimary else colors.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun ReplyHeading() {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        BotAvatar(size = 29.dp)
        Text("阿布", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
