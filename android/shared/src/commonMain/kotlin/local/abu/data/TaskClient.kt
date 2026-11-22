package local.abu.data

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpRedirect
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.plugins.pluginOrNull
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.*
import io.ktor.utils.io.readBuffer
import kotlinx.io.readByteArray
import kotlinx.serialization.json.*
import kotlin.coroutines.cancellation.CancellationException

enum class TaskStatus { QUEUED, RUNNING, COMPLETED, FAILED, INTERRUPTED, CANCELLED }

data class TaskRecord(
    val id: String,
    val content: String,
    val status: TaskStatus,
    val createdAt: String,
    val updatedAt: String,
    val result: String?,
    val errorCode: String?,
)

data class TaskDetail(val task: TaskRecord, val modelConfigured: Boolean, val storageAvailable: Boolean)
data class TaskList(val tasks: List<TaskRecord>, val modelConfigured: Boolean, val storageAvailable: Boolean)

class TaskException(val code: String, val statusCode: Int? = null) : Exception(code)

// The caller owns the redirect-disabled HTTP client (e.g. createHealthHttpClient on Android).
class TaskClient(private val client: HttpClient) {
    init {
        require(client.pluginOrNull(HttpRedirect) == null) { "Task HTTP client must disable redirects" }
    }

    // Callers retain id and content while submission is unconfirmed. Explicit reruns use a new id.
    suspend fun create(token: String, id: String, content: String): TaskDetail {
        validateId(id)
        if (content.length > 8_000) throw TaskException("invalid_task")
        val payload = buildJsonObject { put("id", id); put("content", content) }.toString()
        return fetch(token, HttpMethod.Post, "", detailLimit, payload, ::parseDetail)
    }

    suspend fun list(token: String): TaskList =
        fetch(token, HttpMethod.Get, "", listLimit) { root ->
            val tasks = root.getValue("tasks").jsonArray
            require(tasks.size <= 200)
            TaskList(tasks.map { parseRecord(it.jsonObject) }, root.flag("modelConfigured"), root.flag("storageAvailable"))
        }

    suspend fun detail(token: String, id: String): TaskDetail =
        fetch(token, HttpMethod.Get, "/${validateId(id)}", detailLimit, parse = ::parseDetail)

    suspend fun cancel(token: String, id: String): TaskDetail =
        fetch(token, HttpMethod.Post, "/${validateId(id)}/cancel", detailLimit, parse = ::parseDetail)

    private suspend fun <T> fetch(
        token: String,
        verb: HttpMethod,
        path: String,
        limit: Long,
        payload: String? = null,
        parse: (JsonObject) -> T,
    ): T = try {
        client.prepareRequest(endpoint + path) {
            method = verb
            expectSuccess = false
            bearerAuth(token)
            accept(ContentType.Application.Json)
            if (payload != null) {
                contentType(ContentType.Application.Json)
                setBody(payload)
            }
        }.execute { response ->
            val status = response.status.value
            val success = status == 200 || (payload != null && status == 201)
            val bound = if (success) limit else 4096L
            val body = response.bodyAsChannel().readBuffer(bound + 1)
            if (body.size > bound) throw TaskException("response_too_large", status)
            val bytes = body.readByteArray()
            if (!success) {
                val error = runCatching {
                    Json.parseToJsonElement(bytes.decodeToString()).jsonObject.getValue("error").jsonPrimitive.content
                }.getOrNull()
                throw TaskException(error?.takeIf { it in httpErrors } ?: "http_error", status)
            }
            try {
                parse(Json.parseToJsonElement(bytes.decodeToString(throwOnInvalidSequence = true)).jsonObject)
            } catch (_: IllegalArgumentException) {
                throw TaskException("invalid_response", status)
            } catch (_: NoSuchElementException) {
                throw TaskException("invalid_response", status)
            } catch (_: CharacterCodingException) {
                throw TaskException("invalid_response", status)
            }
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: TaskException) {
        throw failure
    } catch (_: Exception) {
        throw TaskException("connection_failed")
    }

    private fun parseDetail(root: JsonObject): TaskDetail =
        TaskDetail(parseRecord(root.getValue("task").jsonObject), root.flag("modelConfigured"), root.flag("storageAvailable"))

    private fun parseRecord(task: JsonObject): TaskRecord {
        val status = TaskStatus.valueOf(task.text("status").uppercase())
        val code = task["error"]?.let { task.text("error") }
        require(code == null || code in taskErrors)
        return TaskRecord(validateId(task.text("id")), task.text("content"), status,
            task.text("createdAt"), task.text("updatedAt"), task["result"]?.let { task.text("result") }, code)
    }

    private fun JsonObject.text(key: String): String =
        getValue(key).jsonPrimitive.let { require(it.isString); it.content }

    private fun JsonObject.flag(key: String): Boolean =
        getValue(key).jsonPrimitive.let { require(!it.isString); it.boolean }

    private fun validateId(id: String): String = id.also { require(idPattern.matches(it)) { "Invalid task id" } }

    private companion object {
        const val endpoint = "http://127.0.0.1:8080/api/tasks"
        const val detailLimit = 64L * 1024
        const val listLimit = 10L * 1024 * 1024
        val idPattern = Regex("[A-Za-z0-9_-]{1,128}")
        val httpErrors = setOf("unauthorized", "invalid_task", "body_too_large", "task_not_found",
            "id_conflict", "task_limit", "model_not_configured", "storage_unavailable", "shutting_down")
        val taskErrors = setOf("model_failed", "model_timeout", "interrupted", "storage_uncertain")
    }
}
