package local.abu.ui.model

import local.abu.data.Bot
import local.abu.data.ChatMessage
import java.time.DateTimeException
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
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

internal fun sortBotsByActivity(
    bots: List<Bot>,
    lastMessageAtByBot: Map<String, String?>,
): List<Bot> {
    val indexed = bots.withIndex()
    return indexed.sortedWith(
        compareBy<IndexedValue<Bot>>(
            { if (it.value.id == "abu") 0 else 1 },
            { if (parseInstantOrNull(lastMessageAtByBot[it.value.id]) != null) 0 else 1 },
        ).thenByDescending { parseInstantOrNull(lastMessageAtByBot[it.value.id]) ?: Instant.EPOCH }
            .thenBy { it.index },
    ).map { it.value }
}

internal fun findMessageSnippet(
    messages: List<ChatMessage>,
    query: String,
    maxLength: Int = 44,
): String? {
    val needle = query.trim()
    if (needle.isEmpty()) return null
    for (msg in messages.asReversed()) {
        val sources = listOf(msg.reply, msg.content)
        for (source in sources) {
            if (source.isBlank()) continue
            val normalized = source.replace(Regex("\\s+"), " ").trim()
            val hit = normalized.indexOf(needle, ignoreCase = true)
            if (hit >= 0) {
                if (normalized.length <= maxLength) return normalized
                val half = ((maxLength - needle.length).coerceAtLeast(8)) / 2
                val start = (hit - half).coerceAtLeast(0)
                val end = (start + maxLength).coerceAtMost(normalized.length)
                val adjustedStart = (end - maxLength).coerceAtLeast(0)
                val prefix = if (adjustedStart > 0) "…" else ""
                val suffix = if (end < normalized.length) "…" else ""
                return prefix + normalized.substring(adjustedStart, end) + suffix
            }
        }
    }
    return null
}

private val FullTimeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm")
private val ClockFormatter = DateTimeFormatter.ofPattern("HH:mm")
private val DayFormatter = DateTimeFormatter.ofPattern("MM-dd")

internal fun formatTimeGreeting(hour: Int = LocalTime.now().hour): String = when (hour) {
    in 5..11 -> "早上好，今天从哪件事开始？"
    in 12..13 -> "中午好，今天从哪件事开始？"
    in 14..17 -> "下午好，今天从哪件事开始？"
    in 18..22 -> "晚上好，今天从哪件事开始？"
    else -> "夜深了，今天从哪件事开始？"
}

internal fun formatChatTime(value: String?): String {
    if (value == null) return ""
    return try {
        FullTimeFormatter.withZone(ZoneId.systemDefault()).format(Instant.parse(value))
    } catch (_: DateTimeException) {
        ""
    }
}

internal fun formatRelativeChatTime(
    value: String?,
    now: Instant = Instant.now(),
    zone: ZoneId = ZoneId.systemDefault(),
): String {
    val instant = parseInstantOrNull(value) ?: return ""
    val diffSeconds = Duration.between(instant, now).seconds
    if (diffSeconds in 0..59) return "刚刚"
    val date = instant.atZone(zone).toLocalDate()
    val today = now.atZone(zone).toLocalDate()
    return when {
        date == today -> ClockFormatter.withZone(zone).format(instant)
        date == today.minusDays(1) -> "昨天"
        else -> DayFormatter.withZone(zone).format(instant)
    }
}

internal fun formatShortClock(
    value: String?,
    zone: ZoneId = ZoneId.systemDefault(),
): String {
    val instant = parseInstantOrNull(value) ?: return ""
    return ClockFormatter.withZone(zone).format(instant)
}

internal fun formatChatDayHeader(
    value: String?,
    now: Instant = Instant.now(),
    zone: ZoneId = ZoneId.systemDefault(),
): String {
    val instant = parseInstantOrNull(value) ?: return ""
    val clock = ClockFormatter.withZone(zone).format(instant)
    val date = instant.atZone(zone).toLocalDate()
    val today = now.atZone(zone).toLocalDate()
    return when {
        date == today -> "今天 $clock"
        date == today.minusDays(1) -> "昨天 $clock"
        else -> "${DayFormatter.withZone(zone).format(instant)} $clock"
    }
}

internal fun shouldShowTimeHeader(
    previousCreatedAt: String?,
    currentCreatedAt: String?,
    zone: ZoneId = ZoneId.systemDefault(),
): Boolean {
    val current = parseInstantOrNull(currentCreatedAt) ?: return false
    val previous = parseInstantOrNull(previousCreatedAt) ?: return true
    if (previous.atZone(zone).toLocalDate() != current.atZone(zone).toLocalDate()) return true
    return Duration.between(previous, current).abs() >= Duration.ofMinutes(5)
}

private fun parseInstantOrNull(value: String?): Instant? {
    if (value.isNullOrBlank()) return null
    return try {
        Instant.parse(value)
    } catch (_: DateTimeException) {
        null
    }
}

fun formatPreview(messages: List<ChatMessage>): String {
    if (messages.isEmpty()) {
        return ""
    }
    val last = messages.last()
    val answered = last.reply.isNotBlank() &&
        (last.replyStatus == "completed" || last.replyStatus == "interrupted")
    val raw = if (answered) last.reply else last.content
    val firstLine = raw.lineSequence()
        .map { it.trim() }
        .firstOrNull { it.isNotEmpty() }
        .orEmpty()
    return firstLine
        .replace(Regex("^>+\\s+"), "")
        .replace(Regex("^#{1,6}\\s+"), "")
        .replace(Regex("^(?:[-*+]|\\d+[.)])\\s+"), "")
        .replace("**", "")
        .replace("~~", "")
        .replace("`", "")
        .replace(Regex("\\*([^*]+)\\*"), "$1")
        .replace(Regex("(?<![\\w])_([^_]+)_(?![\\w])"), "$1")
        .trim()
        .ifEmpty { raw.trim() }
}
