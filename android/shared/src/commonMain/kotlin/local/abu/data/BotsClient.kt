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

const val DEFAULT_BOT_ID = "abu"

enum class BotAvatar(val wire: String) {
    ABU("abu"), LEAF("leaf"), FLAME("flame"), WAVE("wave"), FLUFF("fluff");
}

data class Bot(val id: String, val name: String, val instructions: String, val avatar: BotAvatar, val revision: Long)
class BotsException(val code: String, val statusCode: Int? = null) : Exception(code)

/** Uses the existing redirect-disabled HTTP client; its lifecycle belongs to the caller. */
class BotsClient(private val http: HttpClient) {
    init {
        require(http.pluginOrNull(HttpRedirect) == null) { "Bot HTTP client must disable redirects" }
    }

    suspend fun list(token: String): List<Bot> = request(token, HttpMethod.Get, "") {
        it.exact("bots")
        val rows = it.getValue("bots").jsonArray
        require(rows.size in 1..32)
        rows.map { row -> parseBot(row.jsonObject) }.also { bots ->
            require(bots.first().id == DEFAULT_BOT_ID && bots.distinctBy { bot -> bot.id }.size == bots.size)
        }
    }

    suspend fun create(token: String, id: String, name: String, instructions: String, avatar: BotAvatar): Bot {
        validateBotId(id)
        require(validBotText(name, instructions))
        val body = buildJsonObject {
            put("id", id)
            put("name", name)
            put("instructions", instructions)
            put("avatar", avatar.wire)
        }
        return request(token, HttpMethod.Post, "", body) { parseBot(it).also { b -> require(b.id == id) } }
    }

    /** A conflict requires reload/review, never an automatic overwrite. */
    suspend fun update(token: String, bot: Bot): Bot {
        validateBotId(bot.id)
        require(bot.revision in 1..maxRevision && validBotText(bot.name, bot.instructions))
        val body = buildJsonObject {
            put("name", bot.name)
            put("instructions", bot.instructions)
            put("avatar", bot.avatar.wire)
            put("revision", bot.revision)
        }
        return request(token, HttpMethod.Put, "/${bot.id}", body) {
            parseBot(it).also { b ->
                require(b.id == bot.id && b.revision == bot.revision + 1 && b.copy(revision = bot.revision) == bot)
            }
        }
    }

    private suspend fun <T> request(
        token: String, method: HttpMethod, path: String, body: JsonObject? = null, parse: (JsonObject) -> T,
    ): T = try {
        http.prepareRequest("http://127.0.0.1:8080/api/bots$path") {
            this.method = method
            expectSuccess = false
            bearerAuth(token)
            if (body != null) {
                contentType(ContentType.Application.Json)
                setBody(body.toString())
            }
        }.execute { response ->
            val status = response.status.value
            val success = status == 200 || (method == HttpMethod.Post && status == 201)
            val bound = if (success) 1024L * 1024 else 4096L
            val buffer = response.bodyAsChannel().readBuffer(bound + 1)
            if (buffer.size > bound) throw BotsException("response_too_large", status)
            val bytes = buffer.readByteArray()
            if (!success) {
                val code = runCatching { parseAppJson(bytes).string("error") }.getOrNull()
                throw BotsException(code?.takeIf { it in serverErrors } ?: "http_error", status)
            }
            try {
                parse(parseAppJson(bytes))
            } catch (_: Exception) {
                throw BotsException("invalid_response", status)
            }
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: BotsException) {
        throw failure
    } catch (_: Exception) {
        throw BotsException("connection_failed")
    }

    private fun parseBot(value: JsonObject): Bot {
        value.exact("id", "name", "instructions", "avatar", "revision")
        val revision = value.getValue("revision").jsonPrimitive
        require(!revision.isString && revision.content.all { it in '0'..'9' })
        val number = requireNotNull(revision.longOrNull)
        val id = validateBotId(value.string("id"))
        val name = value.string("name")
        val instructions = value.string("instructions")
        val avatar = BotAvatar.entries.first { it.wire == value.string("avatar") }
        require(number in 1..maxRevision && validBotText(name, instructions))
        return Bot(id, name, instructions, avatar, number)
    }

    private companion object {
        const val maxRevision = (1L shl 53) - 1
        val serverErrors = setOf("invalid_bot", "unauthorized", "bot_not_found", "id_conflict", "bot_limit",
            "revision_conflict", "body_too_large", "storage_unavailable")
    }
}

private val botIdFormat = Regex("[A-Za-z0-9_-]{1,128}")

internal fun validateBotId(id: String): String = id.also { require(botIdFormat.matches(it)) { "Invalid bot id" } }

internal fun JsonObject.responseBotId(expected: String?): String {
    val id = if ("botId" in this) validateBotId(string("botId")) else DEFAULT_BOT_ID
    require(expected == null || id == expected) { "Unexpected record scope" }
    return id
}

private fun validBotText(name: String, instructions: String): Boolean =
    name.isNotBlank() && botCodePoints(name) in 1..40 && botCodePoints(instructions) in 0..4000

private fun botCodePoints(text: String): Int {
    var count = 0
    var i = 0
    while (i < text.length) {
        val c = text[i++]
        if (c.isHighSurrogate()) {
            if (i == text.length || !text[i++].isLowSurrogate()) return -1
        } else if (c.isLowSurrogate()) return -1
        count++
    }
    return count
}
