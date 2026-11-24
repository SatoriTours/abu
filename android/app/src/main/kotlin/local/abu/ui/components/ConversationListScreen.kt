package local.abu.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import local.abu.R
import local.abu.ui.ChatSession
import local.abu.ui.model.BotsState
import local.abu.ui.model.ConversationItem
import local.abu.ui.model.formatPreview
import local.abu.ui.model.formatRelativeChatTime
import local.abu.ui.model.formatTimeGreeting
import local.abu.ui.navigation.AppNavigationState
import java.time.Instant
import java.time.ZoneId

@Composable
internal fun ConversationListScreen(
    nav: AppNavigationState, state: BotsState, chats: ChatSession,
    onRefresh: () -> Unit, onChat: (String) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    var sectionMenuOpen by remember { mutableStateOf(false) }
    var now by remember { mutableStateOf(Instant.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            now = Instant.now()
        }
    }
    val zone = ZoneId.systemDefault()
    val greeting = formatTimeGreeting(now.atZone(zone).hour)
    val rows = state.bots.mapNotNull { bot ->
        val chat = chats.forBot(bot.id)
        val row = ConversationItem(
            bot.id,
            bot.name,
            bot.id == "abu",
            formatPreview(chat.messages),
            formatRelativeChatTime(chat.messages.lastOrNull()?.createdAt, now = now, zone = zone),
        )
        if (row.matchesQuery(nav.searchQuery) || bot.id == "abu" && nav.searchQuery.trim().equals("Abu", true)) {
            Triple(bot, chat, row)
        } else null
    }
    LazyColumn(
        Modifier.fillMaxSize().imePadding(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        item {
            Row(
                Modifier.fillMaxWidth().padding(start = 4.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("你的搭子", fontSize = 26.sp, fontWeight = FontWeight.SemiBold, color = colors.onSurface)
                    Text(greeting, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                }
                if (!nav.searchOpen) {
                    IconButton(onClick = nav::openSearch) {
                        Icon(
                            painterResource(R.drawable.ic_search),
                            "搜索对话",
                            modifier = Modifier.size(21.dp),
                            tint = colors.onSurfaceVariant,
                        )
                    }
                }
                IconButton(
                    onClick = { keyboard?.hide(); nav.closeSearch(); state.startCreate() },
                    enabled = state.canCreate,
                ) {
                    Box(
                        Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(colors.surfaceVariant)
                            .border(0.6.dp, colors.outlineVariant.copy(alpha = 0.5f), CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            painterResource(R.drawable.ic_add_bot),
                            "创建角色",
                            modifier = Modifier.size(18.dp),
                            tint = if (state.canCreate) colors.onSurface else colors.onSurfaceVariant.copy(alpha = 0.45f),
                        )
                    }
                }
            }
        }
        if (nav.searchOpen) item {
            Row(
                Modifier.padding(bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    nav.searchQuery,
                    nav::updateSearch,
                    Modifier.weight(1f).focusRequester(focus),
                    label = { Text("搜索对话") },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                )
                TextButton(onClick = { keyboard?.hide(); nav.closeSearch() }) { Text("取消") }
            }
            LaunchedEffect(Unit) { focus.requestFocus(); keyboard?.show() }
        }
        item {
            Row(
                Modifier.fillMaxWidth().padding(start = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "最近对话",
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.onSurfaceVariant.copy(alpha = 0.8f),
                )
                Box {
                    IconButton(
                        onClick = { sectionMenuOpen = true },
                        modifier = Modifier.size(36.dp),
                    ) {
                        Icon(
                            painterResource(R.drawable.ic_more),
                            "角色列表操作",
                            modifier = Modifier.size(18.dp),
                            tint = colors.onSurfaceVariant.copy(alpha = 0.72f),
                        )
                    }
                    DropdownMenu(expanded = sectionMenuOpen, onDismissRequest = { sectionMenuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("刷新角色") },
                            enabled = state.hasToken && !state.busy,
                            onClick = {
                                sectionMenuOpen = false
                                onRefresh()
                            },
                        )
                    }
                }
            }
            val notice = when {
                !state.hasToken -> "请先在设置中应用开发令牌。"
                state.error != null -> state.error
                state.busy -> "正在读取角色…"
                rows.isEmpty() && nav.searchQuery.isNotBlank() -> "没有匹配的对话"
                state.bots.size >= 32 -> "角色已达 32 个。"
                else -> null
            }
            notice?.let {
                Text(
                    it,
                    Modifier.padding(start = 4.dp, bottom = 6.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                )
            }
        }
        itemsIndexed(rows, key = { _, item -> item.first.id }) { index, (bot, chat, row) ->
            val working = chat.replyActivity != null || chat.messages.lastOrNull()?.replyStatus == "running"
            val rowShape = RoundedCornerShape(18.dp)
            val containerModifier = if (row.isPinned) {
                Modifier
                    .fillMaxWidth()
                    .clip(rowShape)
                    .background(colors.secondaryContainer)
                    .border(0.5.dp, colors.outlineVariant.copy(alpha = 0.35f), rowShape)
            } else {
                Modifier
                    .fillMaxWidth()
                    .clip(rowShape)
                    .background(Color.Transparent)
            }
            Column(Modifier.fillMaxWidth()) {
                Row(
                    containerModifier,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        Modifier
                            .weight(1f)
                            .clickable { keyboard?.hide(); onChat(bot.id) }
                            .padding(horizontal = 12.dp, vertical = 11.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        BotAvatar(
                            46.dp,
                            avatar = bot.avatar,
                            animate = true,
                            working = working,
                            completed = chat.justCompletedReply,
                        )
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(
                                Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text(
                                    row.name,
                                    Modifier.weight(1f),
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = colors.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (row.timestamp.isNotEmpty()) {
                                    Text(
                                        row.timestamp,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = colors.onSurfaceVariant.copy(alpha = 0.78f),
                                    )
                                }
                            }
                            Row(
                                Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text(
                                    when {
                                        working -> "正在生成回复…"
                                        row.lastMessagePreview.isNotEmpty() -> row.lastMessagePreview
                                        chat.busy -> "正在读取记录…"
                                        chat.error != null -> "记录未读取，打开后可刷新"
                                        chat.historyLoaded -> "还没有对话，来聊聊吧"
                                        else -> "打开对话"
                                    },
                                    Modifier.weight(1f),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = colors.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (row.isPinned || working) {
                                    Box(
                                        Modifier
                                            .size(6.dp)
                                            .clip(CircleShape)
                                            .background(colors.primary),
                                    )
                                }
                            }
                        }
                    }
                    IconButton(
                        onClick = { keyboard?.hide(); nav.closeSearch(); state.startEdit(bot.id) },
                        enabled = !state.busy,
                        modifier = Modifier.padding(end = 2.dp),
                    ) {
                        Icon(
                            painterResource(R.drawable.ic_edit_bot),
                            "编辑角色：${bot.name}",
                            modifier = Modifier.size(17.dp),
                            tint = colors.onSurfaceVariant.copy(alpha = 0.6f),
                        )
                    }
                }
                if (!row.isPinned && index < rows.lastIndex) {
                    HorizontalDivider(
                        Modifier.padding(start = 70.dp, end = 12.dp),
                        thickness = 0.5.dp,
                        color = colors.outlineVariant.copy(alpha = 0.25f),
                    )
                }
            }
        }
    }
}
