package local.abu

import local.abu.data.Bot
import local.abu.data.BotAvatar
import local.abu.data.ChatMessage
import local.abu.ui.model.findMessageSnippet
import local.abu.ui.model.formatChatDayHeader
import local.abu.ui.model.formatRelativeChatTime
import local.abu.ui.model.formatShortClock
import local.abu.ui.model.formatTimeGreeting
import local.abu.ui.model.sendableChatText
import local.abu.ui.model.shouldShowTimeHeader
import local.abu.ui.model.sortBotsByActivity
import local.abu.ui.theme.ThemeMode
import local.abu.ui.theme.ThemePreference
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.*

class BulingUiStateTest {
    @Test
    fun themeDefaultsToSystemAndPersistsEveryExplicitChoice() {
        var setting: String? = null
        fun reopen() = ThemePreference({ setting }, { setting = it })
        assertEquals(ThemeMode.SYSTEM, reopen().mode)
        assertTrue(reopen().mode.isDark(systemDark = true))
        reopen().choose(ThemeMode.LIGHT)
        assertFalse(reopen().mode.isDark(systemDark = true))
        reopen().choose(ThemeMode.DARK)
        assertTrue(reopen().mode.isDark(systemDark = false))
        reopen().choose(ThemeMode.SYSTEM)
        assertFalse(reopen().mode.isDark(systemDark = false))
        setting = "unrecognized"
        assertEquals(ThemeMode.SYSTEM, reopen().mode)
    }

    @Test
    fun systemChangesRespectSelectedModeWithoutChangingThePreference() {
        var setting: String? = null
        val preference = ThemePreference({ setting }, { setting = it })
        val systemInputs = listOf(false, true, false)
        val expected = mapOf(
            ThemeMode.SYSTEM to listOf(false, true, false),
            ThemeMode.LIGHT to listOf(false, false, false),
            ThemeMode.DARK to listOf(true, true, true),
        )
        for ((mode, colors) in expected) {
            preference.choose(mode)
            assertEquals(colors, systemInputs.map { preference.mode.isDark(systemDark = it) })
            assertEquals(mode, preference.mode)
        }
    }

    @Test
    fun composerKeepsOriginalUnicodeTextAndBlocksInvalidOrRepeatedSend() {
        val max = "🪻".repeat(4000)
        assertEquals(max, sendableChatText(max, busy = false))
        assertEquals("  一行\n第二行  ", sendableChatText("  一行\n第二行  ", busy = false))
        assertNull(sendableChatText(max + "a", busy = false))
        assertNull(sendableChatText("　", busy = false))
        assertNull(sendableChatText("不可重复提交", busy = true))
    }

    @Test
    fun timeGreetingCoversAllDaySegments() {
        assertTrue(formatTimeGreeting(8).startsWith("早上好"))
        assertTrue(formatTimeGreeting(12).startsWith("中午好"))
        assertTrue(formatTimeGreeting(15).startsWith("下午好"))
        assertTrue(formatTimeGreeting(20).startsWith("晚上好"))
        assertTrue(formatTimeGreeting(2).startsWith("夜深了"))
    }

    @Test
    fun chatTimeFormattingHandlesRelativeDayHeadersAndInvalidInputs() {
        val zone = ZoneOffset.UTC
        val now = Instant.parse("2026-11-24T09:41:30Z")
        assertEquals("刚刚", formatRelativeChatTime("2026-11-24T09:41:00Z", now, zone))
        assertEquals("08:15", formatRelativeChatTime("2026-11-24T08:15:00Z", now, zone))
        assertEquals("昨天", formatRelativeChatTime("2026-11-23T21:00:00Z", now, zone))
        assertEquals("11-20", formatRelativeChatTime("2026-11-20T10:00:00Z", now, zone))
        assertEquals("", formatRelativeChatTime("invalid", now, zone))
        assertEquals("", formatRelativeChatTime(null, now, zone))

        assertEquals("09:41", formatShortClock("2026-11-24T09:41:00Z", zone))
        assertEquals("", formatShortClock("", zone))

        assertEquals("今天 09:41", formatChatDayHeader("2026-11-24T09:41:00Z", now, zone))
        assertEquals("昨天 21:00", formatChatDayHeader("2026-11-23T21:00:00Z", now, zone))
        assertEquals("11-20 10:00", formatChatDayHeader("2026-11-20T10:00:00Z", now, zone))

        assertTrue(shouldShowTimeHeader(null, "2026-11-24T09:41:00Z", zone))
        assertFalse(shouldShowTimeHeader("2026-11-24T09:38:00Z", "2026-11-24T09:41:00Z", zone))
        assertFalse(shouldShowTimeHeader("2026-11-24T09:36:00.500Z", "2026-11-24T09:41:00Z", zone))
        assertFalse(shouldShowTimeHeader("2026-11-24T09:41:00Z", "2026-11-24T09:36:00.500Z", zone))
        assertTrue(shouldShowTimeHeader("2026-11-24T09:36:00Z", "2026-11-24T09:41:00Z", zone))
        assertTrue(shouldShowTimeHeader("2026-11-24T09:41:00Z", "2026-11-24T09:36:00Z", zone))
        assertTrue(shouldShowTimeHeader("2026-11-24T09:35:00Z", "2026-11-24T09:41:00Z", zone))
        assertTrue(shouldShowTimeHeader("2026-11-23T23:59:00Z", "2026-11-24T00:01:00Z", zone))
        assertFalse(shouldShowTimeHeader("2026-11-24T09:35:00Z", "not-a-timestamp", zone))
    }

    @Test
    fun sortBotsByActivityKeepsAbuPinnedAndOrdersOthersByLatestMessage() {
        val abu = Bot("abu", "阿布", "", BotAvatar.ABU, 1)
        val leaf = Bot("leaf", "小叶", "", BotAvatar.LEAF, 1)
        val flame = Bot("flame", "小火", "", BotAvatar.FLAME, 1)
        val wave = Bot("wave", "小浪", "", BotAvatar.WAVE, 1)
        val sorted = sortBotsByActivity(
            listOf(abu, leaf, flame, wave),
            mapOf(
                "abu" to "2026-11-20T08:00:00Z",
                "leaf" to null,
                "flame" to "2026-11-24T12:00:00Z",
                "wave" to "2026-11-24T18:30:00Z",
            ),
        )
        assertEquals(listOf("abu", "wave", "flame", "leaf"), sorted.map { it.id })

        val withoutHistory = sortBotsByActivity(
            listOf(abu, leaf, flame),
            mapOf("leaf" to "bad-ts", "flame" to null),
        )
        assertEquals(listOf("abu", "leaf", "flame"), withoutHistory.map { it.id })
    }

    @Test
    fun findMessageSnippetSearchesHistoryNewestFirstAndTruncatesLongMatches() {
        val messages = listOf(
            ChatMessage("m1", "早期讨论咖啡豆", "2026-11-24T08:00:00Z", reply = "旧回复", replyStatus = "completed"),
            ChatMessage("m2", "无关内容", "2026-11-24T09:00:00Z", reply = "这是最新回复里提到的深度烘焙咖啡豆方案", replyStatus = "completed"),
        )
        assertNull(findMessageSnippet(messages, "   "))
        assertNull(findMessageSnippet(messages, "不存在的关键词"))
        assertEquals("这是最新回复里提到的深度烘焙咖啡豆方案", findMessageSnippet(messages, "咖啡豆"))

        val longMsg = listOf(
            ChatMessage("m3", "前缀".repeat(20) + "关键目标词" + "后缀".repeat(20), "2026-11-24T10:00:00Z"),
        )
        val snippet = assertNotNull(findMessageSnippet(longMsg, "关键目标词", maxLength = 20))
        assertTrue(snippet.contains("关键目标词"))
        assertTrue(snippet.startsWith("…") && snippet.endsWith("…"))
    }
}
