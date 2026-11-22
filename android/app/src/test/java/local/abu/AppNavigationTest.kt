package local.abu

import local.abu.data.ChatMessage
import local.abu.ui.navigation.AppNavigationState
import local.abu.ui.navigation.ChatScreen
import local.abu.ui.navigation.NavTab
import local.abu.ui.model.ConversationItem
import local.abu.ui.model.formatPreview
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AppNavigationTest {

    @Test
    fun initialStateIsChatListWithBottomNavigationAndCollapsedSearch() {
        val nav = AppNavigationState()
        assertEquals(NavTab.CHAT, nav.currentTab)
        assertEquals(ChatScreen.LIST, nav.chatScreen)
        assertFalse(nav.searchOpen)
        assertTrue(nav.showsBottomBar(todoViewing = false))
    }

    @Test
    fun cancellingSearchClearsQuery() {
        val nav = AppNavigationState()
        nav.openSearch()
        nav.updateSearch("文字")
        assertEquals("文字", nav.searchQuery)
        nav.closeSearch()
        assertFalse(nav.searchOpen)
        assertEquals("", nav.searchQuery)
    }

    @Test
    fun changingTabsClearsSearchAndTodoOnlyHidesBarInItsOwnDetail() {
        val nav = AppNavigationState()
        nav.openSearch()
        nav.updateSearch("文字")
        nav.selectTab(NavTab.MINIAPPS)
        assertFalse(nav.searchOpen)
        assertEquals("", nav.searchQuery)
        assertFalse(nav.showsBottomBar(todoViewing = true))
        assertTrue(nav.showsBottomBar(todoViewing = false))
        nav.selectTab(NavTab.SETTINGS)
        assertTrue(nav.showsBottomBar(todoViewing = true))
    }

    @Test
    fun openChatDetailAndNavigateBack() {
        val nav = AppNavigationState()
        nav.openChatDetail()
        assertEquals(ChatScreen.DETAIL, nav.chatScreen)

        val handled = nav.handleBackPressed()
        assertTrue(handled)
        assertEquals(ChatScreen.LIST, nav.chatScreen)

        // Second back at list should not be handled by internal navigation
        val handledAgain = nav.handleBackPressed()
        assertFalse(handledAgain)
    }

    @Test
    fun backDismissesSearchWithoutLeavingListAndChatDetailHidesNav() {
        val nav = AppNavigationState()
        nav.openSearch()
        nav.updateSearch("测试")
        assertTrue(nav.handleBackPressed())
        assertFalse(nav.searchOpen)
        assertEquals("", nav.searchQuery)
        assertEquals(ChatScreen.LIST, nav.chatScreen)
        nav.openChatDetail()
        assertFalse(nav.showsBottomBar(todoViewing = false))
    }

    @Test
    fun searchFilterMatchesAbu() {
        val item = ConversationItem(
            id = "abu",
            name = "Abu",
            isPinned = true,
            lastMessagePreview = "你好",
            timestamp = "13:49"
        )
        assertTrue(item.matchesQuery(""))
        assertTrue(item.matchesQuery("abu"))
        assertTrue(item.matchesQuery("ABU"))
        assertTrue(item.matchesQuery("你好"))
        assertFalse(item.matchesQuery("未关联"))
    }

    @Test
    fun chatPreviewReflectsLatestMessage() {
        val messages = listOf(
            ChatMessage(id = "1", content = "第一条", createdAt = "2026-11-08T10:00:00Z", reply = "回复一", replyStatus = "completed"),
            ChatMessage(id = "2", content = "第二条", createdAt = "2026-11-08T10:05:00Z", reply = "最新回复", replyStatus = "completed")
        )
        val preview = formatPreview(messages)
        assertEquals("最新回复", preview)

        val pendingMessage = listOf(
            ChatMessage(id = "1", content = "待回复内容", createdAt = "2026-11-08T10:00:00Z", reply = "", replyStatus = "running")
        )
        assertEquals("待回复内容", formatPreview(pendingMessage))

        val empty = emptyList<ChatMessage>()
        assertEquals("", formatPreview(empty))
    }
}
