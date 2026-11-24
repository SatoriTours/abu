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

data class ChatMessage(
    val id: String, val content: String, val createdAt: String,
    val reply: String = "", val replyStatus: String = "", val botId: String = DEFAULT_BOT_ID,
)
data class ChatHistory(val messages: List<ChatMessage>, val modelConfigured: Boolean)
class ChatException(message: String, val code: String = "http_error", val statusCode: Int? = null) : Exception(message)

class ChatClient(private val client: HttpClient) {
    suspend fun history(token: String, botId: String? = null): List<ChatMessage> = loadHistory(token, botId).messages

    suspend fun loadHistory(token: String, botId: String? = null): ChatHistory {
        val scope = botId?.let(::validateBotId) ?: DEFAULT_BOT_ID
        return request(token, HttpMethod.Get, "messages", queryBot = botId, limit = 10L * 1024 * 1024) { root ->
            val rows = root.getValue("messages").jsonArray
            require(rows.size <= 200)
            val configured = root.getValue("modelConfigured").jsonPrimitive
            require(!configured.isString)
            ChatHistory(rows.map { parse(it.jsonObject, scope) }, configured.boolean)
        }
    }

    suspend fun send(token: String, id: String, content: String, botId: String? = null): ChatMessage {
        val scope = botId?.let(::validateBotId) ?: DEFAULT_BOT_ID
        val payload = buildJsonObject {
            put("id", id)
            put("content", content)
            if (botId != null) put("botId", scope)
        }
        return request(token, HttpMethod.Post, "messages", payload) { parse(it, scope) }
    }

    suspend fun reply(
        token: String, messageId: String, retry: Boolean, botId: String? = null, runId: String? = null,
    ): ChatMessage {
        val scope = botId?.let(::validateBotId) ?: DEFAULT_BOT_ID
        val payload = buildJsonObject {
            put("messageId", messageId)
            put("retry", retry)
            if (botId != null) put("botId", scope)
            if (runId != null) put("runId", validateRunId(runId))
        }
        return request(token, HttpMethod.Post, "replies", payload) { parse(it, scope) }
    }

    /**
     * Streams one reply. Deltas are delivered as they arrive; a normal end returns the stored
     * message. Any interrupted or failing stream throws so the caller never shows a truncated
     * reply as complete.
     */
    suspend fun replyStream(
        token: String, messageId: String, retry: Boolean, botId: String? = null, runId: String? = null,
        onDelta: (String) -> Unit,
    ): ChatMessage {
        val scope = botId?.let(::validateBotId) ?: DEFAULT_BOT_ID
        val payload = buildJsonObject {
            put("messageId", messageId)
            put("retry", retry)
            if (botId != null) put("botId", scope)
            if (runId != null) put("runId", validateRunId(runId))
        }
        // Once the response is a 200 the reply stream is open: everything that happens while
        // reading it, including a failing body cleanup, is a broken stream and not a failed
        // connection.
        var streaming = false
        return try {
            client.prepareRequest("http://127.0.0.1:8080/api/chat/replies/stream") {
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
    ): Boolean {
        val scope = botId?.let(::validateBotId) ?: DEFAULT_BOT_ID
        val payload = buildJsonObject {
            put("messageId", messageId)
            if (botId != null) put("botId", scope)
            if (runId != null) put("runId", validateRunId(runId))
        }
        return request(token, HttpMethod.Post, "replies/cancel", payload) {
            it.getValue("cancelled").jsonPrimitive.boolean
        }
    }

    suspend fun deleteMessage(token: String, messageId: String, botId: String? = null) {
        botId?.let(::validateBotId)
        validateMessageId(messageId)
        request(token, HttpMethod.Delete, "messages/" + messageId.encodeURLPathPart(), queryBot = botId) { }
    }

    suspend fun clearMessages(token: String, botId: String? = null): Int {
        botId?.let(::validateBotId)
        return request(token, HttpMethod.Delete, "messages", queryBot = botId) {
            it.getValue("deletedCount").jsonPrimitive.int
        }
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
        token: String, method: HttpMethod, path: String, body: JsonObject? = null, queryBot: String? = null,
        limit: Long = 64L * 1024, parse: (JsonObject) -> T,
    ): T = try {
        client.prepareRequest("http://127.0.0.1:8080/api/chat/$path") {
            this.method = method
            expectSuccess = false
            bearerAuth(token)
            if (queryBot != null) parameter("botId", queryBot)
            if (body != null) {
                contentType(ContentType.Application.Json)
                setBody(body.toString())
            }
        }.execute { response ->
            val status = response.status.value
            val success = status == 200 || (method == HttpMethod.Post && path == "messages" && status == 201)
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
            value.responseBotId(scope))

    private fun fail(status: Int, code: String): Nothing = throw ChatException(when (code) {
        "bot_not_found", "invalid_bot_id" -> "角色不可用，请刷新角色列表"
        "message_not_found" -> "此角色下未找到消息，请刷新历史"
        else -> when (status) {
            401 -> "开发令牌不正确，请在设置中重新填写"
            404 -> "后端未启用聊天记录，请配置 ABU_DEV_TOKEN"
            409 -> "操作冲突：可能正在回复、需要明确重试或已达记录上限，请刷新历史"
            502 -> "模型回复失败，消息已保存；可明确重试（可能再次计费）"
            503 -> "后端尚未配置模型"
            else -> "服务暂不可用（HTTP $status），请重试"
        }
    }, code, status)

    private companion object {
        val serverErrors = setOf("unauthorized", "invalid_bot_id", "bot_not_found", "invalid_message",
            "invalid_reply_request", "message_not_found", "id_conflict", "reply_in_progress", "history_limit",
            "storage_failed", "model_not_configured", "reply_in_progress_or_not_latest", "explicit_retry_required",
            "reply_storage_failed", "model_reply_failed")
    }
}
