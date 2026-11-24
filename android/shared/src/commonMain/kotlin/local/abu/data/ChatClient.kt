package local.abu.data

import io.ktor.client.HttpClient
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.*
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readBuffer
import io.ktor.utils.io.readUTF8Line
import kotlinx.io.readByteArray
import kotlinx.serialization.json.*
import kotlin.coroutines.cancellation.CancellationException

const val DEFAULT_SESSION_ID = "default"

data class ChatMessage(
    val id: String, val content: String, val createdAt: String,
    val reply: String = "", val replyStatus: String = "", val botId: String = DEFAULT_BOT_ID,
    val sessionId: String = DEFAULT_SESSION_ID,
)

data class ChatHistory(
    val messages: List<ChatMessage>, val modelConfigured: Boolean,
    val sessionId: String = DEFAULT_SESSION_ID, val hasMore: Boolean = false, val nextBefore: String? = null,
)

/** One named conversation of one role, as reported by the server. */
data class ChatSessionInfo(
    val id: String, val botId: String, val title: String, val createdAt: String, val updatedAt: String,
)

class ChatException(message: String, val code: String = "http_error", val statusCode: Int? = null) : Exception(message)

class ChatClient(
    private val client: HttpClient,
    // Resolved per request so a changed setting reaches the client that already exists.
    private val baseUrl: () -> String = { DEFAULT_SERVER_BASE_URL },
) {
    suspend fun history(token: String, botId: String? = null, sessionId: String? = null): List<ChatMessage> =
        loadHistory(token, botId, sessionId).messages

    suspend fun loadHistory(
        token: String, botId: String? = null, sessionId: String? = null,
        before: String? = null, limit: Int? = null,
    ): ChatHistory {
        botId?.let(::validateBotId)
        sessionId?.let(::validateSessionId)
        before?.let(::validateMessageId)
        if (limit != null && limit !in 1..MAX_PAGE_LIMIT) {
            throw ChatException("分页条数需为 1 到 " + MAX_PAGE_LIMIT, "invalid_message")
        }
        val scope = botId ?: DEFAULT_BOT_ID
        val sessionScope = sessionId ?: DEFAULT_SESSION_ID
        val query = queryOf(
            "botId" to botId, "sessionId" to sessionId, "before" to before, "limit" to limit?.toString(),
        )
        return request(token, HttpMethod.Get, "messages", query = query, limit = 10L * 1024 * 1024) { root ->
            val rows = root.getValue("messages").jsonArray
            require(rows.size <= MAX_PAGE_LIMIT)
            val configured = root.getValue("modelConfigured").jsonPrimitive
            require(!configured.isString)
            val wired = if ("sessionId" in root) validateSessionId(root.string("sessionId")) else DEFAULT_SESSION_ID
            // A page that belongs to another conversation must never be shown as this one.
            require(wired == sessionScope)
            val hasMore = if ("hasMore" in root) root.booleanFlag("hasMore") else false
            val nextBefore = if ("nextBefore" in root) root.string("nextBefore").ifEmpty { null } else null
            if (hasMore) require(nextBefore != null)
            ChatHistory(rows.map { parse(it.jsonObject, scope) }, configured.boolean, wired, hasMore, nextBefore)
        }
    }

    suspend fun listSessions(token: String, botId: String? = null): List<ChatSessionInfo> {
        botId?.let(::validateBotId)
        return request(token, HttpMethod.Get, "sessions", query = queryOf("botId" to botId)) { root ->
            val rows = root.getValue("sessions").jsonArray
            require(rows.size in 1..MAX_SESSIONS)
            val sessions = rows.map { parseSession(it.jsonObject) }
            require(sessions.distinctBy { it.id }.size == sessions.size)
            require(sessions.any { it.id == DEFAULT_SESSION_ID })
            require(botId == null || sessions.all { it.botId == botId })
            sessions
        }
    }

    suspend fun createSession(
        token: String, id: String, title: String? = null, botId: String? = null,
    ): ChatSessionInfo {
        validateSessionId(id)
        if (id == DEFAULT_SESSION_ID) throw ChatException("默认对话不能新建", "invalid_session")
        val requested = title?.takeIf { it.isNotEmpty() }?.let(::validateTitle)
        botId?.let(::validateBotId)
        val payload = buildJsonObject {
            put("id", id)
            if (requested != null) put("title", requested)
            if (botId != null) put("botId", botId)
        }
        return request(token, HttpMethod.Post, "sessions", payload, acceptCreated = true) { root ->
            parseSession(root).also { require(it.id == id && (botId == null || it.botId == botId)) }
        }
    }

    suspend fun renameSession(
        token: String, sessionId: String, title: String, botId: String? = null,
    ): ChatSessionInfo {
        validateSessionId(sessionId)
        validateTitle(title)
        botId?.let(::validateBotId)
        val payload = buildJsonObject {
            put("title", title)
            if (botId != null) put("botId", botId)
        }
        return request(token, HttpMethod.Put, "sessions/" + sessionId.encodeURLPathPart(), payload) { root ->
            parseSession(root).also { require(it.id == sessionId) }
        }
    }

    /** Deletes one session and returns how many messages it held. */
    suspend fun deleteSession(token: String, sessionId: String, botId: String? = null): Int {
        validateSessionId(sessionId)
        botId?.let(::validateBotId)
        return request(token, HttpMethod.Delete, "sessions/" + sessionId.encodeURLPathPart(),
            query = queryOf("botId" to botId)) { root ->
            val deleted = root.getValue("deleted").jsonPrimitive
            require(!deleted.isString && deleted.boolean)
            require(root.string("id") == sessionId)
            val removed = root.getValue("deletedMessages").jsonPrimitive
            require(!removed.isString)
            removed.int
        }
    }

    suspend fun send(
        token: String, id: String, content: String, botId: String? = null, sessionId: String? = null,
    ): ChatMessage {
        val scope = botId?.let(::validateBotId) ?: DEFAULT_BOT_ID
        val sessionScope = sessionId?.let(::validateSessionId)
        val payload = buildJsonObject {
            put("id", id)
            put("content", content)
            if (botId != null) put("botId", scope)
            if (sessionScope != null) put("sessionId", sessionScope)
        }
        return request(token, HttpMethod.Post, "messages", payload) { parse(it, scope) }
    }

    suspend fun reply(
        token: String, messageId: String, retry: Boolean, botId: String? = null, runId: String? = null,
        regenerate: Boolean = false, sessionId: String? = null,
    ): ChatMessage {
        val scope = botId?.let(::validateBotId) ?: DEFAULT_BOT_ID
        val payload = replyBody(messageId, retry, botId, scope, runId, regenerate,
            sessionId?.let(::validateSessionId))
        return request(token, HttpMethod.Post, "replies", payload) { parse(it, scope) }
    }

    /** Shared request body; `regenerate` asks for a fresh answer instead of the stored one. */
    private fun replyBody(
        messageId: String, retry: Boolean, botId: String?, scope: String, runId: String?,
        regenerate: Boolean, sessionId: String?,
    ) = buildJsonObject {
        put("messageId", messageId)
        put("retry", retry)
        if (regenerate) put("regenerate", true)
        if (botId != null) put("botId", scope)
        if (runId != null) put("runId", validateRunId(runId))
        if (sessionId != null) put("sessionId", sessionId)
    }

    /** Rewrites the newest turn of one session and clears its stored reply. */
    suspend fun editMessage(
        token: String, messageId: String, content: String, botId: String? = null, sessionId: String? = null,
    ): ChatMessage {
        val scope = botId?.let(::validateBotId) ?: DEFAULT_BOT_ID
        val sessionScope = sessionId?.let(::validateSessionId)
        validateMessageId(messageId)
        val payload = buildJsonObject {
            put("content", content)
            if (botId != null) put("botId", scope)
            if (sessionScope != null) put("sessionId", sessionScope)
        }
        return request(token, HttpMethod.Put, "messages/" + messageId.encodeURLPathPart(), payload) {
            parse(it, scope)
        }
    }

    /**
     * Streams one reply. Deltas are delivered as they arrive; a normal end returns the stored
     * message. Any interrupted or failing stream throws so the caller never shows a truncated
     * reply as complete.
     */
    suspend fun replyStream(
        token: String, messageId: String, retry: Boolean, botId: String? = null, runId: String? = null,
        regenerate: Boolean = false, sessionId: String? = null, onDelta: (String) -> Unit,
    ): ChatMessage {
        val scope = botId?.let(::validateBotId) ?: DEFAULT_BOT_ID
        val payload = replyBody(messageId, retry, botId, scope, runId, regenerate,
            sessionId?.let(::validateSessionId))
        // Once the response is a 200 the reply stream is open: everything that happens while
        // reading it, including a failing body cleanup, is a broken stream and not a failed
        // connection.
        var streaming = false
        return try {
            client.prepareRequest(endpoint() + "replies/stream") {
                method = HttpMethod.Post
                expectSuccess = false
                bearerAuth(token)
                contentType(ContentType.Application.Json)
                accept(ContentType.Text.EventStream)
                setBody(payload.toString())
            }.execute { response ->
                val status = response.status.value
                if (status != 200) {
                    val bytes = try {
                        response.bodyAsChannel().readBuffer(4096).readByteArray()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        ByteArray(0)
                    }
                    val code = try {
                        Json.parseToJsonElement(bytes.decodeToString()).jsonObject.string("error")
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        null
                    }
                    fail(status, code?.takeIf { it in serverErrors } ?: "http_error")
                }
                streaming = true
                try {
                    readEventStream(response.bodyAsChannel(), scope, onDelta)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: ChatException) {
                    throw failure
                } catch (_: Exception) {
                    throw streamInterrupted()
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: ChatException) {
            throw failure
        } catch (_: Exception) {
            throw if (streaming) streamInterrupted() else ChatException("无法连接聊天服务，请重试", "connection_failed")
        }
    }

    private fun streamInterrupted() = ChatException("回复流异常中断，请刷新历史", "stream_interrupted")

    suspend fun cancelReply(
        token: String, messageId: String, botId: String? = null, runId: String? = null,
        sessionId: String? = null,
    ): Boolean {
        val scope = botId?.let(::validateBotId) ?: DEFAULT_BOT_ID
        val payload = buildJsonObject {
            put("messageId", messageId)
            if (botId != null) put("botId", scope)
            if (runId != null) put("runId", validateRunId(runId))
            if (sessionId != null) put("sessionId", validateSessionId(sessionId))
        }
        return request(token, HttpMethod.Post, "replies/cancel", payload) {
            it.getValue("cancelled").jsonPrimitive.boolean
        }
    }

    suspend fun deleteMessage(
        token: String, messageId: String, botId: String? = null, sessionId: String? = null,
    ) {
        botId?.let(::validateBotId)
        sessionId?.let(::validateSessionId)
        validateMessageId(messageId)
        request(token, HttpMethod.Delete, "messages/" + messageId.encodeURLPathPart(),
            query = queryOf("botId" to botId, "sessionId" to sessionId)) { }
    }

    suspend fun clearMessages(token: String, botId: String? = null, sessionId: String? = null): Int {
        botId?.let(::validateBotId)
        sessionId?.let(::validateSessionId)
        return request(token, HttpMethod.Delete, "messages",
            query = queryOf("botId" to botId, "sessionId" to sessionId)) {
            it.getValue("deletedCount").jsonPrimitive.int
        }
    }

    private fun endpoint(): String = baseUrl().trimEnd('/') + "/api/chat/"

    private fun queryOf(vararg entries: Pair<String, String?>): List<Pair<String, String>> {
        val result = ArrayList<Pair<String, String>>(entries.size)
        for ((name, value) in entries) {
            if (value != null) result.add(name to value)
        }
        return result
    }

    private fun validateSessionId(id: String): String {
        if (!sessionIdPattern.matches(id)) throw ChatException("会话标识不合法", "invalid_session_id")
        return id
    }

    private fun validateMessageId(id: String): String {
        val unsafe = id.any {
            it == '/' || it == '?' || it == '#' || it.isWhitespace() || it.code < 0x20 || it.code == 0x7f
        }
        if (id.isEmpty() || id.length > 128 || id == "." || id == ".." || unsafe) {
            throw ChatException("消息标识不合法", "invalid_message")
        }
        return id
    }

    // A round id is shared with the server so a late cancel cannot stop a newer retry.
    private fun validateRunId(runId: String): String {
        val safe = runId.all { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '_' || it == '-' }
        if (runId.isEmpty() || runId.length > 128 || !safe) {
            throw ChatException("回复轮次标识不合法", "invalid_reply_request")
        }
        return runId
    }

    private fun validateTitle(title: String): String {
        if (title.isBlank() || titleLength(title) !in 1..MAX_TITLE_LENGTH) {
            throw ChatException("会话标题需为 1 到 " + MAX_TITLE_LENGTH + " 个字符", "invalid_session")
        }
        return title
    }

    // Count code points so a surrogate pair counts as the one character the server counts.
    private fun titleLength(text: String): Int {
        var count = 0
        var i = 0
        while (i < text.length) {
            val c = text[i++]
            if (c.isHighSurrogate()) {
                if (i == text.length || !text[i++].isLowSurrogate()) return -1
            } else if (c.isLowSurrogate()) {
                return -1
            }
            count++
        }
        return count
    }

    private fun parseSession(value: JsonObject): ChatSessionInfo {
        value.exact("id", "botId", "title", "createdAt", "updatedAt")
        val id = validateSessionId(value.string("id"))
        val botId = validateBotId(value.string("botId"))
        val title = validateTitle(value.string("title"))
        return ChatSessionInfo(id, botId, title, value.string("createdAt"), value.string("updatedAt"))
    }

    // Maps any unexpected payload shape to one protocol error instead of leaking parser details.
    private inline fun <T> requireProtocol(block: () -> T): T = try {
        block()
    } catch (failure: ChatException) {
        throw failure
    } catch (_: Exception) {
        throw ChatException("聊天响应不符合协议", "invalid_response")
    }

    private fun requireElement(payload: String): JsonElement =
        runCatching { Json.parseToJsonElement(payload) }.getOrElse {
            throw ChatException("聊天响应不符合协议", "invalid_response")
        }

    private suspend fun readEventStream(
        channel: ByteReadChannel, scope: String, onDelta: (String) -> Unit,
    ): ChatMessage {
        var event = ""
        val data = StringBuilder()
        var bytes = 0L
        var message: ChatMessage? = null
        fun dispatch() {
            if (event.isEmpty() && data.isEmpty()) return
            val payload = data.toString()
            when (event) {
                "delta" -> {
                    val delta = requireProtocol { requireElement(payload).jsonObject.string("delta") }
                    if (delta.isNotEmpty()) onDelta(delta)
                }
                "done" -> message = requireProtocol { parse(requireElement(payload).jsonObject, scope) }
                "error" -> {
                    val code = runCatching { requireElement(payload).jsonObject.string("error") }.getOrNull()
                    fail(if (code == "reply_storage_failed") 500 else 502, code ?: "http_error")
                }
            }
            event = ""
            data.clear()
        }
        while (message == null) {
            val line = channel.readUTF8Line(64 * 1024) ?: break
            bytes += line.encodeToByteArray().size.toLong() + 1
            if (bytes > 4L * 1024 * 1024) throw ChatException("回复流过大", "response_too_large")
            if (line.isEmpty()) {
                dispatch()
                continue
            }
            if (line.startsWith(":")) continue
            val colon = line.indexOf(':')
            val name = if (colon < 0) line else line.substring(0, colon)
            val value = if (colon < 0) "" else line.substring(colon + 1).removePrefix(" ")
            when (name) {
                "event" -> event = value
                "data" -> {
                    if (data.isNotEmpty()) data.append('\n')
                    data.append(value)
                }
            }
        }
        if (message == null) dispatch()
        return message ?: throw ChatException("回复流提前中断，请刷新历史", "stream_interrupted")
    }

    private suspend fun <T> request(
        token: String, method: HttpMethod, path: String, body: JsonObject? = null,
        query: List<Pair<String, String>> = emptyList(), limit: Long = 64L * 1024,
        acceptCreated: Boolean = false, parse: (JsonObject) -> T,
    ): T = try {
        client.prepareRequest(endpoint() + path) {
            this.method = method
            expectSuccess = false
            bearerAuth(token)
            for ((name, value) in query) parameter(name, value)
            if (body != null) {
                contentType(ContentType.Application.Json)
                setBody(body.toString())
            }
        }.execute { response ->
            val status = response.status.value
            val success = status == 200 ||
                (method == HttpMethod.Post && status == 201 && (path == "messages" || acceptCreated))
            val bound = if (success) limit else 4096L
            val packet = response.bodyAsChannel().readBuffer(bound + 1)
            if (packet.size > bound) throw ChatException("聊天响应过大", "response_too_large", status)
            val bytes = packet.readByteArray()
            if (!success) {
                val code = runCatching { Json.parseToJsonElement(bytes.decodeToString()).jsonObject.string("error") }.getOrNull()
                fail(status, code?.takeIf { it in serverErrors } ?: "http_error")
            }
            try {
                parse(Json.parseToJsonElement(bytes.decodeToString(throwOnInvalidSequence = true)).jsonObject)
            } catch (_: Exception) {
                throw ChatException("聊天响应不符合协议", "invalid_response", status)
            }
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: ChatException) {
        throw failure
    } catch (_: Exception) {
        throw ChatException("无法连接聊天服务，请重试", "connection_failed")
    }

    private fun parse(value: JsonObject, scope: String): ChatMessage =
        ChatMessage(value.string("id"), value.string("content"), value.string("createdAt"),
            if ("reply" in value) value.string("reply") else "",
            if ("replyStatus" in value) value.string("replyStatus") else "",
            value.responseBotId(scope),
            if ("sessionId" in value) validateSessionId(value.string("sessionId")) else DEFAULT_SESSION_ID)

    private fun JsonObject.booleanFlag(key: String): Boolean =
        getValue(key).jsonPrimitive.let {
            require(!it.isString)
            it.boolean
        }

    private fun fail(status: Int, code: String): Nothing = throw ChatException(when (code) {
        "bot_not_found", "invalid_bot_id" -> "角色不可用，请刷新角色列表"
        "message_not_found" -> "此角色下未找到消息，请刷新历史"
        "not_latest_message" -> "仅支持编辑当前角色的最新一条消息"
        "invalid_session" -> "会话参数不合法"
        "invalid_session_id" -> "会话标识不合法"
        "session_not_found" -> "会话不存在，请刷新会话列表"
        "session_limit" -> "会话数量已达上限，请先删除不用的会话"
        "cannot_delete_default_session" -> "默认对话不能删除"
        else -> when (status) {
            401 -> "开发令牌不正确，请在设置中重新填写"
            404 -> "后端未启用聊天记录，请配置 ABU_DEV_TOKEN"
            409 -> "操作冲突：可能正在回复、需要明确重试或已达记录上限，请刷新历史"
            502 -> "模型回复失败，消息已保存；可明确重试（可能再次计费）"
            503 -> "后端尚未配置模型"
            else -> "服务暂不可用（HTTP " + status + "），请重试"
        }
    }, code, status)

    private companion object {
        const val MAX_PAGE_LIMIT = 200
        const val MAX_SESSIONS = 50
        const val MAX_TITLE_LENGTH = 60
        val sessionIdPattern = Regex("[A-Za-z0-9_-]{1,128}")
        val serverErrors = setOf("unauthorized", "invalid_bot_id", "bot_not_found", "invalid_message",
            "invalid_reply_request", "message_not_found", "id_conflict", "reply_in_progress", "history_limit",
            "storage_failed", "model_not_configured", "reply_in_progress_or_not_latest", "explicit_retry_required",
            "reply_storage_failed", "model_reply_failed", "not_latest_message", "invalid_session",
            "invalid_session_id", "session_not_found", "session_limit", "cannot_delete_default_session")
    }
}
