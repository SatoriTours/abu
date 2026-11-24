package local.abu.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import local.abu.data.ChatClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import local.abu.data.ChatHistory
import local.abu.data.ChatMessage

internal class ChatSession(val token: String) {
    private val conversations = mutableMapOf<String, ChatState>()

    fun forBot(botId: String): ChatState = conversations.getOrPut(botId) { ChatState(botId) }
}

class ChatState(val botId: String = "abu") {
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
    var streamingText by mutableStateOf("")
    var stopping by mutableStateOf(false)

    // Every generation of a reply carries its own run id, so a stop request or a late delta from
    // an earlier attempt can never touch a later retry of the same message.
    var activeReplyRunId by mutableStateOf<String?>(null)
    internal var activeReplyJob: Job? = null

    fun startStreaming(messageId: String, runId: String = java.util.UUID.randomUUID().toString()): String {
        replyActivity = messageId
        activeReplyRunId = runId
        activeReplyJob = null
        streamingText = ""
        stopping = false
        return runId
    }

    fun bindReplyJob(runId: String, job: Job) {
        if (activeReplyRunId == runId) activeReplyJob = job
    }

    // Only the active reply may append, so a late delta cannot leak into another message.
    fun appendDelta(messageId: String, delta: String, runId: String? = null) {
        if (replyActivity == messageId && (runId == null || activeReplyRunId == runId) && delta.isNotEmpty()) {
            streamingText += delta
        }
    }

    // Cancels the job of exactly this generation; an outdated run id cancels nothing.
    fun cancelReplyJobIfMatching(runId: String): Boolean {
        if (activeReplyRunId != runId) return false
        val job = activeReplyJob ?: return false
        job.cancel()
        return true
    }

    fun finishStreaming(runId: String? = null) {
        if (runId != null && activeReplyRunId != runId) return
        replyActivity = null
        activeReplyRunId = null
        activeReplyJob = null
        streamingText = ""
        stopping = false
    }

    fun removeMessage(messageId: String) {
        messages = messages.filterNot { it.id == messageId }
    }

    // Clears the transcript while keeping the draft the user is still editing.
    fun clearAll() {
        messages = emptyList()
        error = null
    }

    fun acceptReply(message: ChatMessage) {
        update(message)
        if (message.replyStatus == "completed") justCompletedReply = java.util.UUID.randomUUID().toString()
    }

    fun update(message: ChatMessage) {
        require(message.botId == botId)
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
        applyHistory(client.loadHistory(token, botId))
    }

    private suspend fun applyHistory(history: ChatHistory) {
        currentCoroutineContext().ensureActive()
        require(history.messages.all { it.botId == botId })
        messages = history.messages
        modelConfigured = history.modelConfigured
        historyLoaded = true
    }
}
