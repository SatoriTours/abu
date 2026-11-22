package local.abu.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import local.abu.data.ChatClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import local.abu.data.ChatHistory
import local.abu.data.ChatMessage

class ChatState {
    var draft by mutableStateOf("")
    var pendingId: String? = null
    var pendingText: String? = null
    var messages by mutableStateOf(emptyList<ChatMessage>())
    var modelConfigured by mutableStateOf(false)
    var historyLoaded by mutableStateOf(false)
    var busy by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var replyActivity by mutableStateOf<String?>(null)
    var justCompletedReply by mutableStateOf<String?>(null)

    fun acceptReply(message: ChatMessage) {
        update(message)
        if (message.replyStatus == "completed") justCompletedReply = java.util.UUID.randomUUID().toString()
    }

    fun update(message: ChatMessage) {
        val index = messages.indexOfFirst { it.id == message.id }
        messages = if (index < 0) messages + message
        else messages.map { if (it.id == message.id) message else it }
    }

    internal suspend fun refreshHistory(load: suspend () -> ChatHistory) {
        busy = true
        error = null
        try {
            applyHistory(load())
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            currentCoroutineContext().ensureActive()
            error = "读取对话失败，请检查连接后刷新。"
        } finally {
            busy = false
        }
    }

    suspend fun reload(client: ChatClient, token: String) {
        applyHistory(client.loadHistory(token))
    }

    private suspend fun applyHistory(history: ChatHistory) {
        currentCoroutineContext().ensureActive()
        messages = history.messages
        modelConfigured = history.modelConfigured
        historyLoaded = true
    }
}
