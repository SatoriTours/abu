package local.abu.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import local.abu.data.ChatClient
import local.abu.data.ChatHistory
import local.abu.data.ChatMessage
import local.abu.data.ChatSessionInfo
import local.abu.data.DEFAULT_BOT_ID
import local.abu.data.DEFAULT_SESSION_ID
import local.abu.ui.model.DraftStorage
import local.abu.ui.model.InMemoryDraftStorage

internal class ChatSession(
    val token: String,
    private val drafts: DraftStorage = InMemoryDraftStorage(),
) {
    private val conversations = mutableMapOf<Pair<String, String>, ChatState>()
    private val activeSessions = mutableStateMapOf<String, String>()
    private val botSessions = mutableStateMapOf<String, List<ChatSessionInfo>>()

    fun activeSessionId(botId: String): String = activeSessions[botId] ?: DEFAULT_SESSION_ID

    fun selectSession(botId: String, sessionId: String) {
        activeSessions[botId] = sessionId
    }

    fun sessionsForBot(botId: String): List<ChatSessionInfo> = botSessions[botId] ?: listOf(
        ChatSessionInfo(DEFAULT_SESSION_ID, botId, "默认对话", "", ""),
    )

    fun setSessions(botId: String, sessions: List<ChatSessionInfo>) {
        botSessions[botId] = sessions
        val current = activeSessionId(botId)
        if (sessions.none { it.id == current }) {
            activeSessions[botId] = DEFAULT_SESSION_ID
        }
    }

    fun forBot(botId: String): ChatState = forSession(botId, activeSessionId(botId))

    fun forSession(botId: String, sessionId: String): ChatState =
        conversations.getOrPut(botId to sessionId) {
            ChatState(
                botId = botId,
                sessionId = sessionId,
                initialDraft = drafts.load(botId, sessionId),
                onDraftChanged = { text -> drafts.save(botId, sessionId, text) },
            )
        }

    fun allLoadedMessagesForBot(botId: String): List<ChatMessage> {
        val currentId = activeSessionId(botId)
        val merged = mutableListOf<ChatMessage>()
        conversations.forEach { (key, state) ->
            if (key.first == botId && key.second != currentId) {
                merged += state.messages
            }
        }
        conversations[botId to currentId]?.let { merged += it.messages }
        return merged
    }

    fun removeSession(botId: String, sessionId: String) {
        conversations.remove(botId to sessionId)?.activeReplyJob?.cancel()
        drafts.removeSession(botId, sessionId)
        botSessions[botId]?.let { list ->
            botSessions[botId] = list.filterNot { it.id == sessionId }
        }
        if (activeSessionId(botId) == sessionId) {
            activeSessions[botId] = DEFAULT_SESSION_ID
        }
    }

    fun removeBot(botId: String) {
        val keys = conversations.keys.filter { it.first == botId }
        keys.forEach { key -> conversations.remove(key)?.activeReplyJob?.cancel() }
        activeSessions.remove(botId)
        botSessions.remove(botId)
        drafts.removeBot(botId)
    }
}

class ChatState(
    val botId: String = DEFAULT_BOT_ID,
    val sessionId: String = DEFAULT_SESSION_ID,
    initialDraft: String = "",
    private val onDraftChanged: (String) -> Unit = {},
) {
    private var draftState by mutableStateOf(initialDraft)
    var draft: String
        get() = draftState
        set(value) {
            if (draftState != value) {
                draftState = value
                onDraftChanged(value)
            }
        }

    var pendingId: String? = null
    var pendingText: String? = null
    var messages by mutableStateOf(emptyList<ChatMessage>())
    var modelConfigured by mutableStateOf(false)
    var historyLoaded by mutableStateOf(false)
    var hasMore by mutableStateOf(false)
    var nextBefore by mutableStateOf<String?>(null)
    var loadingOlder by mutableStateOf(false)
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
        hasMore = false
        nextBefore = null
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

    internal suspend fun loadOlderHistory(load: suspend (String) -> ChatHistory): Int {
        val cursor = nextBefore ?: return 0
        if (!hasMore || loadingOlder || busy) return 0
        loadingOlder = true
        error = null
        return try {
            val older = load(cursor)
            currentCoroutineContext().ensureActive()
            prependOlderHistory(older)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            currentCoroutineContext().ensureActive()
            error = "加载更早消息失败，请重试。"
            0
        } finally {
            loadingOlder = false
        }
    }

    internal fun prependOlderHistory(older: ChatHistory): Int {
        require(older.messages.all { it.botId == botId && it.sessionId == sessionId })
        val existingIds = messages.mapTo(HashSet()) { it.id }
        val fresh = older.messages.filterNot { it.id in existingIds }
        if (fresh.isNotEmpty()) {
            messages = fresh + messages
        }
        modelConfigured = older.modelConfigured
        hasMore = older.hasMore
        nextBefore = older.nextBefore
        return fresh.size
    }

    suspend fun reload(client: ChatClient, token: String, limit: Int? = 50) {
        applyHistory(client.loadHistory(token, botId, sessionId = sessionId, limit = limit))
    }

    private suspend fun applyHistory(history: ChatHistory) {
        currentCoroutineContext().ensureActive()
        require(history.messages.all { it.botId == botId })
        messages = history.messages
        modelConfigured = history.modelConfigured
        hasMore = history.hasMore
        nextBefore = history.nextBefore
        historyLoaded = true
    }
}
