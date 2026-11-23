package local.abu.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import local.abu.R
import local.abu.ui.ChatSession
import local.abu.ui.model.BotsState
import local.abu.ui.model.ConversationItem
import local.abu.ui.model.formatChatTime
import local.abu.ui.model.formatPreview
import local.abu.ui.navigation.AppNavigationState

@Composable
internal fun ConversationListScreen(
    nav: AppNavigationState, state: BotsState, chats: ChatSession,
    onRefresh: () -> Unit, onChat: (String) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val rows = state.bots.mapNotNull { bot ->
        val chat = chats.forBot(bot.id)
        val row = ConversationItem(bot.id, bot.name, bot.id == "abu", formatPreview(chat.messages),
            formatChatTime(chat.messages.lastOrNull()?.createdAt))
        if (row.matchesQuery(nav.searchQuery) || bot.id == "abu" && nav.searchQuery.trim().equals("Abu",true)) {
            Triple(bot, chat, row)
        } else null
    }
    LazyColumn(
        Modifier.fillMaxSize().imePadding(), contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            Row(Modifier.fillMaxWidth().padding(start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("你的搭子", fontSize = 25.sp, fontWeight = FontWeight.SemiBold)
                    Text("今天从哪件事开始？", style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                }
                if (!nav.searchOpen) IconButton(onClick = nav::openSearch) {
                    Icon(painterResource(R.drawable.ic_search), "搜索对话")
                }
                IconButton(onClick = { keyboard?.hide(); nav.closeSearch(); state.startCreate() }, enabled = state.canCreate) {
                    Icon(painterResource(R.drawable.ic_add_bot), "创建角色")
                }
            }
        }
        if (nav.searchOpen) item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(nav.searchQuery, nav::updateSearch, Modifier.weight(1f).focusRequester(focus),
                    label = { Text("搜索对话") }, singleLine = true, shape = RoundedCornerShape(14.dp))
                TextButton(onClick = { keyboard?.hide(); nav.closeSearch() }) { Text("取消") }
            }
            LaunchedEffect(Unit) { focus.requestFocus(); keyboard?.show() }
        }
        item {
            Row(Modifier.fillMaxWidth().padding(start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("助手列表", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, color = colors.onSurfaceVariant)
                TextButton(onClick = onRefresh, enabled = state.hasToken && !state.busy) { Text("刷新角色") }
            }
            val notice = when {
                !state.hasToken -> "请先在设置中应用开发令牌。"
                state.error != null -> state.error
                state.busy -> "正在读取角色…"
                rows.isEmpty() && nav.searchQuery.isNotBlank() -> "没有匹配的对话"
                state.bots.size >= 32 -> "角色已达 32 个。"
                else -> null
            }
            notice?.let { Text(it, Modifier.padding(start = 4.dp), color = colors.onSurfaceVariant) }
        }
        items(rows, key = { it.first.id }) { (bot, chat, row) ->
            val working = chat.replyActivity != null || chat.messages.lastOrNull()?.replyStatus == "running"
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(15.dp))
                    .background(if (row.isPinned) colors.secondaryContainer else colors.surfaceVariant),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    Modifier.weight(1f).clickable { keyboard?.hide(); onChat(bot.id) }.padding(9.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically,
                ) {
                    BotAvatar(46.dp, avatar = bot.avatar, animate = true, working = working, completed = chat.justCompletedReply)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(row.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(when {
                            working -> "正在生成回复…"
                            row.lastMessagePreview.isNotEmpty() -> row.lastMessagePreview
                            chat.busy -> "正在读取记录…"
                            chat.error != null -> "记录未读取，打开后可刷新"
                            chat.historyLoaded -> "还没有对话，来聊聊吧"
                            else -> "打开对话"
                        }, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (row.timestamp.isNotEmpty()) Text(row.timestamp, style = MaterialTheme.typography.labelMedium,
                            color = colors.onSurfaceVariant)
                    }
                }
                IconButton(onClick = { keyboard?.hide(); nav.closeSearch(); state.startEdit(bot.id) }, enabled = !state.busy) {
                    Icon(painterResource(R.drawable.ic_edit_bot), "编辑角色：${bot.name}")
                }
            }
        }
    }
}
