package local.abu.ui.model

import local.abu.data.ChatMessage
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class ConversationItem(
    val id: String,
    val name: String,
    val isPinned: Boolean,
    val lastMessagePreview: String,
    val timestamp: String
) {
    fun matchesQuery(query: String): Boolean {
        val q = query.trim()
        if (q.isEmpty()) return true
        return name.contains(q, ignoreCase = true) || lastMessagePreview.contains(q, ignoreCase = true)
    }
}

internal fun formatChatTime(value: String?): String {
    if (value == null) return ""
    return try {
        DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.systemDefault()).format(Instant.parse(value))
    } catch (_: java.time.DateTimeException) {
        ""
    }
}

fun formatPreview(messages: List<ChatMessage>): String {
    if (messages.isEmpty()) {
        return ""
    }
    val last = messages.last()
    return if (last.replyStatus == "completed" && last.reply.isNotBlank()) {
        last.reply
    } else {
        last.content
    }
}
