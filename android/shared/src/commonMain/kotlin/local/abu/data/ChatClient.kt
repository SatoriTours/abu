package local.abu.data

import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import kotlinx.serialization.json.*

data class ChatMessage(
    val id: String, val content: String, val createdAt: String,
    val reply: String = "", val replyStatus: String = ""
)
data class ChatHistory(val messages: List<ChatMessage>, val modelConfigured: Boolean)
class ChatException(message: String) : Exception(message)

class ChatClient(private val client: HttpClient) {
    private val endpoint = "http://127.0.0.1:8080/api/chat/messages"
    suspend fun history(token: String): List<ChatMessage> = loadHistory(token).messages
    suspend fun loadHistory(token: String): ChatHistory {
        val response = client.get(endpoint) { bearerAuth(token) }
        if (response.status != HttpStatusCode.OK) fail(response.status.value)
        val root = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        return ChatHistory(
            root.getValue("messages").jsonArray.map { parse(it) },
            root.getValue("modelConfigured").jsonPrimitive.boolean
        )
    }
    suspend fun send(token: String, id: String, content: String): ChatMessage {
        val response = client.post(endpoint) {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject { put("id", id); put("content", content) }.toString())
        }
        if (response.status.value !in listOf(200, 201)) fail(response.status.value)
        return parse(Json.parseToJsonElement(response.bodyAsText()))
    }
    suspend fun reply(token: String, messageId: String, retry: Boolean): ChatMessage {
        val response = client.post("http://127.0.0.1:8080/api/chat/replies") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject { put("messageId", messageId); put("retry", retry) }.toString())
        }
        if (response.status != HttpStatusCode.OK) fail(response.status.value)
        return parse(Json.parseToJsonElement(response.bodyAsText()))
    }
    private fun parse(element: JsonElement): ChatMessage {
        val value = element.jsonObject
        return ChatMessage(value.getValue("id").jsonPrimitive.content,
            value.getValue("content").jsonPrimitive.content,
            value.getValue("createdAt").jsonPrimitive.content,
            value["reply"]?.jsonPrimitive?.content ?: "",
            value["replyStatus"]?.jsonPrimitive?.content ?: "")
    }
    private fun fail(status: Int): Nothing = throw ChatException(when (status) {
        401 -> "开发令牌不正确，请在设置中重新填写"
        404 -> "后端未启用聊天记录，请配置 ABU_DEV_TOKEN"
        409 -> "操作冲突：可能正在回复、需要明确重试或已达记录上限，请刷新历史"
        502 -> "模型回复失败，消息已保存；可明确重试（可能再次计费）"
        503 -> "后端尚未配置模型"
        else -> "服务暂不可用（HTTP $status），请重试"
    })
}
