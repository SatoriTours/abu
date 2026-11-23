package local.abu.data

import io.ktor.client.HttpClient
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.*
import io.ktor.utils.io.readBuffer
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

    suspend fun reply(token: String, messageId: String, retry: Boolean, botId: String? = null): ChatMessage {
        val scope = botId?.let(::validateBotId) ?: DEFAULT_BOT_ID
        val payload = buildJsonObject {
            put("messageId", messageId)
            put("retry", retry)
            if (botId != null) put("botId", scope)
        }
        return request(token, HttpMethod.Post, "replies", payload) { parse(it, scope) }
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
