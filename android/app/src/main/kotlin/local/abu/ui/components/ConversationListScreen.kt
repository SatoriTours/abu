package local.abu.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import local.abu.ui.ChatState
import local.abu.ui.model.ConversationItem
import local.abu.ui.model.formatChatTime
import local.abu.ui.model.formatPreview
import local.abu.ui.navigation.AppNavigationState

@Composable
fun ConversationListScreen(navState: AppNavigationState, chatState: ChatState) {
    val colors = MaterialTheme.colorScheme
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val item = ConversationItem("abu", "阿布", true, formatPreview(chatState.messages),
        formatChatTime(chatState.messages.lastOrNull()?.createdAt))

    Column(
        Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("你的搭子", fontSize = 25.sp, fontWeight = FontWeight.SemiBold)
                Text("今天从哪件事开始？", style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
            }
            if (!navState.searchOpen) {
                IconButton(onClick = navState::openSearch) {
                    Icon(painterResource(R.drawable.ic_search), "搜索对话")
                }
            }
        }
        if (navState.searchOpen) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = navState.searchQuery, onValueChange = navState::updateSearch,
                    modifier = Modifier.weight(1f).focusRequester(focus),
                    label = { Text("搜索对话") }, singleLine = true,
                    shape = RoundedCornerShape(14.dp)
                )
                TextButton(onClick = { keyboard?.hide(); navState.closeSearch() }) { Text("取消") }
            }
            LaunchedEffect(Unit) {
                focus.requestFocus()
                keyboard?.show()
            }
        }
        Text(if (chatState.messages.isEmpty()) "主助手" else "最近对话",
            style = MaterialTheme.typography.labelLarge, color = colors.onSurfaceVariant)
        if (item.matchesQuery(navState.searchQuery) || navState.searchQuery.trim().equals("Abu", ignoreCase = true)) {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(colors.secondaryContainer)
                    .clickable { keyboard?.hide(); navState.openChatDetail() }.padding(horizontal = 10.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically
            ) {
                BotAvatar(size = 52.dp, animate = true, working = chatState.replyActivity != null ||
                    chatState.messages.lastOrNull()?.replyStatus == "running")
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(item.name, style = MaterialTheme.typography.titleSmall, color = colors.onSecondaryContainer)
                        if (item.timestamp.isNotEmpty()) Text(item.timestamp,
                            style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                    }
                    Text(item.lastMessagePreview.ifEmpty {
                        when {
                            chatState.busy -> "正在读取记录…"
                            chatState.error != null && !chatState.historyLoaded -> "记录未读取，打开后可刷新"
                            chatState.historyLoaded -> "还没有对话，来聊聊吧"
                            else -> "和阿布聊聊"
                        }
                    },
                        style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        } else {
            Text("没有匹配的对话", color = colors.onSurfaceVariant)
        }
    }
}
