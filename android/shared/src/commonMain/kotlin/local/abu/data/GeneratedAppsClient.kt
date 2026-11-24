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

class GeneratedAppsException(val code: String, val statusCode: Int? = null) : Exception(code)

class GeneratedAppsClient(
    private val http: HttpClient,
    // Resolved per request so a changed setting reaches the client that already exists.
    private val baseUrl: () -> String = { DEFAULT_SERVER_BASE_URL },
) {
    init { require(http.pluginOrNull(HttpRedirect) == null) { "Generated app client must disable redirects" } }

    suspend fun createDraft(dev: String, input: DraftRequest): GeneratedJob =
        json(dev, HttpMethod.Post, drafts, buildJsonObject {
            put("id", validateBotId(input.id)); put("botId", validateBotId(input.botId))
            put("appId", appId(input.appId)); put("name", input.name)
            put("request", input.request); put("baseVersion", version(input.baseVersion, true))
        }) {
            parseJob(it, input.id).also { job ->
                require(job.summary.kind == "draft" && job.summary.appId == input.appId && job.summary.botId == input.botId)
            }
        }

    suspend fun propose(dev: String, appId: String, input: ProposalRequest): GeneratedJob =
        json(dev, HttpMethod.Post, "${appPath(appId)}/proposals", buildJsonObject {
            put("id", validateBotId(input.id)); put("botId", validateBotId(input.botId))
            put("request", input.request); put("baseRevision", revision(input.baseRevision))
        }) {
            parseJob(it, input.id).also { job ->
                require(job.summary.kind == "proposal" && job.summary.appId == appId && job.summary.botId == input.botId)
            }
        }

    suspend fun jobs(dev: String): GeneratedJobs = json(dev, HttpMethod.Get, drafts, max = 384 * 1024) {
        it.exact("jobs", "modelConfigured")
        val list = it.getValue("jobs").jsonArray
        require(list.size <= 300)
        val jobs = list.map { node -> summary(node.jsonObject, false) }
        require(jobs.distinctBy { j -> j.id }.size == jobs.size)
        val configured = it.getValue("modelConfigured").jsonPrimitive
        require(!configured.isString)
        GeneratedJobs(jobs, configured.boolean)
    }

    suspend fun job(dev: String, id: String): GeneratedJob =
        json(dev, HttpMethod.Get, draftPath(id)) { parseJob(it, id) }

    suspend fun cancel(dev: String, id: String): GeneratedJob =
        json(dev, HttpMethod.Post, "${draftPath(id)}/cancel") { parseJob(it, id) }

    suspend fun publish(dev: String, draftId: String, hash: String, expectedVersion: Int, expectedRevision: Long): GeneratedPublication =
        json(dev, HttpMethod.Post, "${draftPath(draftId)}/publish", buildJsonObject {
            require(hash.matches(digest))
            put("hash", hash); put("expectedVersion", version(expectedVersion, true)); put("expectedRevision", revision(expectedRevision))
        }) { publication(it, expectedRevision) }

    suspend fun activate(dev: String, appId: String, requestId: String, version: Int, expectedRevision: Long): GeneratedPublication =
        json(dev, HttpMethod.Put, "${appPath(appId)}/active", buildJsonObject {
            put("id", validateBotId(requestId)); put("version", version(version)); put("expectedRevision", revision(expectedRevision))
        }) { publication(it, expectedRevision).also { row -> require(row.appId == appId && row.version == version) } }

    suspend fun catalog(dev: String): List<GeneratedApp> = json(dev, HttpMethod.Get, apps) {
        it.exact("apps")
        val rows = it.getValue("apps").jsonArray
        require(rows.size <= 15)
        rows.map { row ->
            val o = row.jsonObject
            o.exact("id", "name", "currentVersion", "revision", "versions")
            val id = appId(o.string("id"))
            val versions = o.getValue("versions").jsonArray.map { v -> wireVersion(v.generatedLong()) }
            val current = wireVersion(o.generatedLong("currentVersion"))
            val epoch = o.generatedLong("revision")
            require(versions == (1..versions.size).toList() && current in versions && epoch >= versions.size)
            GeneratedApp(id, o.string("name"), current, epoch, versions)
        }.also { list -> require(list.distinctBy { row -> row.id }.size == list.size) }
    }

    suspend fun manifest(dev: String, appId: String, version: Int): GeneratedManifest =
        json(dev, HttpMethod.Get, versionPath(appId, version)) { parseGeneratedManifest(it, appId, version) }

    suspend fun asset(dev: String, manifest: GeneratedManifest, path: String, draftId: String? = null): ByteArray {
        require(verifyGeneratedManifest(manifest))
        val a = manifest.assets.single { it.path == path }
        val root = if (draftId == null) versionPath(manifest.appId, manifest.version) else {
            require(manifest.version == 0)
            draftPath(draftId)
        }
        return fetch(dev, HttpMethod.Get, "$root/assets/$path", null, a.size) { bytes, mime ->
            if (mime != a.mime || !verifyGeneratedAsset(a, bytes)) throw GeneratedAppsException("asset_integrity")
            bytes
        }
    }

    suspend fun openSession(dev: String, appId: String): GeneratedAppSession =
        json(dev, HttpMethod.Post, "${appPath(appId)}/session") {
            it.exact("appToken", "expiresAt")
            val token = it.string("appToken")
            require(Regex("[A-Za-z0-9_-]{1,140}").matches(token))
            GeneratedAppSession(appId, token, Instant.parse(it.string("expiresAt")))
        }

    suspend fun data(session: GeneratedAppSession): GeneratedSnapshot =
        json(session.token, HttpMethod.Get, "${appPath(session.appId)}/data", parse = ::snapshot)

    suspend fun write(session: GeneratedAppSession, update: GeneratedUpdate): GeneratedSnapshot =
        json(session.token, HttpMethod.Put, "${appPath(session.appId)}/data", buildJsonObject {
            val doc = parseGeneratedDocument(update.document.toString())
            put("mutationId", validateBotId(update.mutationId)); put("baseRevision", revision(update.baseRevision)); put("document", doc)
        }) { snapshot(it).also { saved -> require(saved.revision == update.baseRevision + 1 && saved.document == update.document) } }

    private fun snapshot(o: JsonObject): GeneratedSnapshot {
        o.exact("revision", "document")
        return GeneratedSnapshot(o.generatedLong("revision"), parseGeneratedDocument(o.getValue("document").toString()))
    }

    private fun publication(o: JsonObject, expected: Long): GeneratedPublication {
        o.exact("appId", "version", "revision")
        return GeneratedPublication(appId(o.string("appId")), wireVersion(o.generatedLong("version")),
            o.generatedLong("revision").also { require(it == expected + 1) })
    }

    private fun summary(o: JsonObject, detail: Boolean): GeneratedJobSummary {
        val allowed = if (detail) summaryKeys + detailKeys else summaryKeys
        require(o.keys.all { it in allowed })
        val kind = o.string("kind").also { require(it == "draft" || it == "proposal") }
        val name = o["name"]?.let { o.string("name") }
        require((kind == "draft") == (name != null))
        return GeneratedJobSummary(validateBotId(o.string("id")), validateBotId(o.string("botId")), appId(o.string("appId")),
            kind, o.string("status").also { require(it in statuses) }, name)
    }

    private fun parseJob(o: JsonObject, id: String): GeneratedJob {
        val info = summary(o, true)
        require(info.id == id)
        val m = o["manifest"]?.let { parseGeneratedManifest(it.jsonObject, info.appId, 0) }
        val p = o["proposal"]?.jsonObject?.let {
            it.exact("baseRevision", "document")
            GeneratedProposal(it.generatedLong("baseRevision"), parseGeneratedDocument(it.getValue("document").toString()))
        }
        require((m != null) == (info.kind == "draft" && info.status == "ready"))
        require((p != null) == (info.kind == "proposal" && info.status == "ready"))
        require(if (m == null) "hash" !in o else o.string("hash") == m.hash)
        val error = o["error"]?.let { o.string("error") }
        require((info.status in setOf("failed", "interrupted")) == (error != null))
        val published = o["publishedVersion"]?.let { wireVersion(it.generatedLong()).also { require(m != null) } }
        return GeneratedJob(info, o.string("request"), wireVersion(o.generatedLong("baseVersion"), true), error, m, p, published)
    }

    private suspend fun <T> json(
        token: String, method: HttpMethod, path: String, body: JsonObject? = null, max: Int = 96 * 1024,
        parse: (JsonObject) -> T,
    ): T = fetch(token, method, path, body, max) { bytes, _ ->
            try { parse(generatedJson(bytes)) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { throw GeneratedAppsException("invalid_response") }
        }

    private suspend fun <T> fetch(
        token: String, method: HttpMethod, path: String, body: JsonObject?, max: Int, parse: (ByteArray, String?) -> T,
    ): T = try {
        withTimeoutOrNull(12_000) {
            http.prepareRequest(endpoint() + path) {
                this.method = method
                expectSuccess = false
                bearerAuth(token)
                if (body != null) {
                    contentType(ContentType.Application.Json)
                    setBody(body.toString())
                }
            }.execute { response ->
                val status = response.status.value
                val success = status == 200 || method == HttpMethod.Post && status == 201
                val bound = if (success) max else 4096
                val bytes = response.bodyAsChannel().readBuffer(bound + 1)
                if (bytes.size > bound) throw GeneratedAppsException("response_too_large", status)
                val payload = bytes.readByteArray()
                if (!success) {
                    val code = runCatching { generatedJson(payload).string("error") }.getOrNull()
                    throw GeneratedAppsException(code?.takeIf { it in errors } ?: "http_error", status)
                }
                parse(payload, response.headers[HttpHeaders.ContentType])
            }
        } ?: throw GeneratedAppsException("request_timeout")
    } catch (e: CancellationException) {
        throw e
    } catch (e: GeneratedAppsException) {
        throw e
    } catch (_: Exception) {
        throw GeneratedAppsException("connection_failed")
    }

    private fun endpoint(): String = baseUrl().trimEnd('/')

    private fun appId(id: String) = id.also { require(validGeneratedAppId(it)) }
    private fun draftPath(id: String) = "$drafts/${validateBotId(id)}"
    private fun appPath(id: String) = "$apps/${appId(id)}"
    private fun versionPath(id: String, v: Int) = "${appPath(id)}/versions/${version(v)}"
    private fun version(v: Int, zero: Boolean = false) = wireVersion(v.toLong(), zero)
    private fun wireVersion(v: Long, zero: Boolean = false): Int {
        require(v in (if (zero) 0L else 1L)..16L)
        return v.toInt()
    }
    private fun revision(v: Long) = v.also { require(v in 0..GENERATED_MAX_REVISION) }

    private companion object {
        const val drafts = "/api/app-drafts"
        const val apps = "/api/generated-apps"
        val digest = Regex("[a-f0-9]{64}")
        val summaryKeys = setOf("id", "botId", "appId", "kind", "status", "name")
        val detailKeys = setOf("request", "baseVersion", "manifest", "hash", "proposal", "publishedVersion", "error")
        val statuses = setOf("queued", "running", "ready", "failed", "interrupted", "cancelled")
        val errors = setOf("unauthorized", "invalid_app_token", "app_scope_mismatch", "invalid_generated_request", "generated_not_found",
            "invalid_bot_id", "bot_not_found", "id_conflict", "generation_unavailable", "body_too_large", "storage_unavailable",
            "generated_storage_limit", "generated_job_limit", "draft_not_ready", "publication_conflict", "draft_hash_conflict",
            "version_conflict", "revision_conflict", "mutation_conflict", "version_limit", "generated_app_limit")
    }
}
