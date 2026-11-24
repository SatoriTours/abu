package local.abu.data

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpRedirect
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.plugins.pluginOrNull
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.*
import io.ktor.utils.io.readBuffer
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.readByteArray
import kotlinx.serialization.json.*
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Instant

data class AppCatalog(val apps: List<AppCatalogEntry>)
data class AppCatalogEntry(val id: String, val name: String, val currentVersion: String, val versions: List<String>)
data class AppManifest(
    val schemaVersion: Int,
    val id: String,
    val version: String,
    val entry: String,
    val capabilities: List<String>,
    val assets: List<AppAsset>,
)
data class AppAsset(val path: String, val mime: String, val size: Int, val sha256: String)

class AppSession internal constructor(val appId: String, internal val appToken: String, val expiresAt: Instant) {
    override fun toString(): String = "AppSession(appId=$appId, expiresAt=$expiresAt)"
}

data class TodoItem(val id: String, val text: String, val done: Boolean)
data class TodoSnapshot(val revision: Long, val items: List<TodoItem>)
data class TodoUpdate(val mutationId: String, val baseRevision: Long, val items: List<TodoItem>)

class AppsException(val code: String, val statusCode: Int? = null, val writeOutcomeUnknown: Boolean = false) : Exception(code)

/** Receives the existing redirect-disabled client. The caller owns that client and session lifetime. */
class AppsClient(
    private val client: HttpClient,
    // Resolved per request so a changed setting reaches the client that already exists.
    private val baseUrl: () -> String = { DEFAULT_SERVER_BASE_URL },
) {
    init {
        require(client.pluginOrNull(HttpRedirect) == null) { "App HTTP client must disable redirects" }
    }

    suspend fun catalog(devToken: String): AppCatalog =
        json(devToken, HttpMethod.Get, "", manifestLimit, parse = ::parseAppCatalog)

    suspend fun manifest(devToken: String, appId: String, version: String): AppManifest {
        val path = bundlePath(appId, version)
        return json(devToken, HttpMethod.Get, path, manifestLimit) { parseAppManifest(it, appId, version) }
    }

    suspend fun asset(devToken: String, manifest: AppManifest, path: String): ByteArray {
        val route = bundlePath(manifest.id, manifest.version)
        require(validAppAssetPath(path)) { "Invalid app asset path" }
        val asset = requireNotNull(manifest.assets.singleOrNull { it.path == path }) { "Asset not listed" }
        require(validAppAsset(asset)) { "Invalid asset metadata" }
        return fetch(devToken, HttpMethod.Get, "$route/assets/$path", asset.size.toLong(), overflowCode = "asset_integrity") { body, mime ->
            if (mime != asset.mime || !verifyAppAsset(asset, body)) throw AppsException("asset_integrity", 200)
            body
        }
    }

    suspend fun openSession(devToken: String, appId: String): AppSession {
        require(appIdPattern.matches(appId))
        return json(devToken, HttpMethod.Post, "/$appId/session", 4096) { root ->
            root.exact("appToken", "expiresAt")
            val token = root.string("appToken")
            require(sessionPattern.matches(token))
            AppSession(appId, token, Instant.parse(root.string("expiresAt")))
        }
    }

    suspend fun getData(session: AppSession): TodoSnapshot =
        json(session.appToken, HttpMethod.Get, "/${session.appId}/data", snapshotLimit, parse = ::parseTodoSnapshot)

    // Reuse the exact operation on explicit retry. Cancellation also leaves the caller's operation unconfirmed.
    suspend fun putData(session: AppSession, update: TodoUpdate): TodoSnapshot {
        val items = update.items.toList()
        if (!todoIdPattern.matches(update.mutationId) || update.baseRevision !in 0 until maxTodoRevision || !validTodoItems(items)) {
            throw AppsException("invalid_data")
        }
        val payload = buildJsonObject {
            put("mutationId", update.mutationId)
            put("baseRevision", update.baseRevision)
            putJsonArray("items") {
                for (item in items) addJsonObject {
                    put("id", item.id); put("text", item.text); put("done", item.done)
                }
            }
        }.toString()
        return json(session.appToken, HttpMethod.Put, "/${session.appId}/data", snapshotLimit, payload) {
            parseTodoSnapshot(it).also { saved ->
                require(saved.revision == update.baseRevision + 1 && saved.items == items)
            }
        }
    }

    private suspend fun <T> json(
        token: String, verb: HttpMethod, path: String, limit: Long, payload: String? = null,
        parse: (JsonObject) -> T,
    ): T = fetch(token, verb, path, limit, payload) { body, _ ->
        try {
            parse(parseAppJson(body))
        } catch (_: IllegalArgumentException) {
            throw AppsException("invalid_response", 200, verb == HttpMethod.Put)
        } catch (_: NoSuchElementException) {
            throw AppsException("invalid_response", 200, verb == HttpMethod.Put)
        } catch (_: CharacterCodingException) {
            throw AppsException("invalid_response", 200, verb == HttpMethod.Put)
        }
    }

    private suspend fun <T> fetch(
        token: String, verb: HttpMethod, path: String, limit: Long, payload: String? = null,
        overflowCode: String = "response_too_large", parse: (ByteArray, String?) -> T,
    ): T {
        val isWrite = verb == HttpMethod.Put
        try {
            // Keep the per-call deadline separate from cancellation of the caller's coroutine.
            return withTimeoutOrNull(12_000) {
                client.prepareRequest(endpoint() + path) {
                    method = verb
                    expectSuccess = false
                    bearerAuth(token)
                    if (payload != null) {
                        contentType(ContentType.Application.Json)
                        setBody(payload)
                    }
                }.execute { response ->
                    val status = response.status.value
                    val bound = if (status == 200) limit else 4096
                    val body = response.bodyAsChannel().readBuffer(bound + 1)
                    if (body.size > bound) throw AppsException(if (status == 200) overflowCode else "response_too_large", status, isWrite)
                    val bytes = body.readByteArray()
                    if (status != 200) {
                        val code = errorCode(status, bytes)
                        throw AppsException(code, status, isWrite && code == "http_error")
                    }
                    parse(bytes, response.headers[HttpHeaders.ContentType])
                }
            } ?: throw AppsException("request_timeout", writeOutcomeUnknown = isWrite)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: AppsException) {
            throw failure
        } catch (_: Exception) {
            throw AppsException("connection_failed", writeOutcomeUnknown = isWrite)
        }
    }

    private fun endpoint(): String = baseUrl().trimEnd('/') + endpointPath

    private fun errorCode(status: Int, body: ByteArray): String {
        return try {
            val json = parseAppJson(body)
            json.exact("error")
            val code = json.string("error")
            if (errorStatuses[code] == status) code else "http_error"
        } catch (_: Exception) {
            "http_error"
        }
    }

    private fun bundlePath(appId: String, version: String): String {
        require(appIdPattern.matches(appId) && appVersionPattern.matches(version)) { "Invalid application identity" }
        return "/$appId/versions/$version"
    }

    private companion object {
        const val endpointPath = "/api/apps"
        const val manifestLimit = 16L * 1024
        const val snapshotLimit = 256L * 1024
        val sessionPattern = Regex("[A-Za-z0-9_-]{1,140}")
        val errorStatuses = mapOf(
            "invalid_data" to 400, "unauthorized" to 401, "invalid_app_token" to 401,
            "app_scope_mismatch" to 403, "app_not_found" to 404, "revision_conflict" to 409,
            "mutation_conflict" to 409, "revision_limit" to 409, "body_too_large" to 413,
            "storage_unavailable" to 503,
        )
    }
}
