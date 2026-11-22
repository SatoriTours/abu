package local.abu.ui.navigation

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

enum class NavTab(val title: String) {
    CHAT("对话"), TASKS("任务"), MINIAPPS("小程序"), SETTINGS("设置")
}

enum class ChatScreen { LIST, DETAIL }

class AppNavigationState {
    var currentTab by mutableStateOf(NavTab.CHAT)
        private set
    var chatScreen by mutableStateOf(ChatScreen.LIST)
        private set
    var searchOpen by mutableStateOf(false)
        private set
    var searchQuery by mutableStateOf("")
        private set

    fun openSearch() { if (currentTab == NavTab.CHAT && chatScreen == ChatScreen.LIST) searchOpen = true }
    fun updateSearch(value: String) { if (searchOpen) searchQuery = value }
    fun closeSearch() {
        searchOpen = false
        searchQuery = ""
    }

    fun selectTab(tab: NavTab) {
        closeSearch()
        currentTab = tab
    }

    fun openChatDetail() {
        closeSearch()
        chatScreen = ChatScreen.DETAIL
    }

    fun backToList() { chatScreen = ChatScreen.LIST }

    fun showsBottomBar(todoViewing: Boolean) = when (currentTab) {
        NavTab.CHAT -> chatScreen == ChatScreen.LIST
        NavTab.MINIAPPS -> !todoViewing
        else -> true
    }

    fun canHandleBack() = currentTab == NavTab.CHAT && (searchOpen || chatScreen == ChatScreen.DETAIL)

    fun handleBackPressed(): Boolean {
        if (!canHandleBack()) return false
        if (searchOpen) closeSearch() else backToList()
        return true
    }
}
