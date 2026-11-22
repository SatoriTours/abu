package local.abu.data

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import io.ktor.http.content.OutgoingContent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.*

class AppsClientTest {
    private val json = headersOf(HttpHeaders.ContentType, "application/json")
    private val html = headersOf(HttpHeaders.ContentType, "text/html; charset=utf-8")
    private val hash = "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824"
    private val token = "test_token_from_mock"
    private val fakeSession = """{"appToken":"$token","expiresAt":"2026-11-21T00:15:00Z"}"""
    private val manifest = """{"schemaVersion":1,"id":"personal-todo","version":"v2","entry":"index.html","capabilities":["todo.read","todo.write"],"assets":[{"path":"index.html","mime":"text/html; charset=utf-8","size":5,"sha256":"$hash"}]}"""

    @Test fun catalogManifestAssetAndScopedDataUseFixedRoutes() = runTest {
        var writeRequests = 0
        mock { req ->
            assertEquals("127.0.0.1", req.url.host)
            assertEquals(8080, req.url.port)
            val path = req.url.encodedPath
            assertEquals("Bearer " + if (path.endsWith("/data")) token else "dev", req.headers[HttpHeaders.Authorization])
            when (req.method to path) {
                HttpMethod.Get to "/api/apps" -> respond(
                    """{"apps":[{"id":"personal-todo","name":"个人待办","currentVersion":"v2","versions":["v1","v2"]}]}""",
                    headers = json)
                HttpMethod.Get to "/api/apps/personal-todo/versions/v2" -> respond(manifest, headers = json)
                HttpMethod.Get to "/api/apps/personal-todo/versions/v2/assets/index.html" -> respond("hello", headers = html)
                HttpMethod.Post to "/api/apps/personal-todo/session" -> respond(fakeSession, headers = json)
                HttpMethod.Get to "/api/apps/personal-todo/data" -> respond("""{"revision":0,"items":[]}""", headers = json)
                HttpMethod.Put to "/api/apps/personal-todo/data" -> {
                    assertEquals("""{"mutationId":"m1","baseRevision":0,"items":[{"id":"todo","text":"测😀","done":false}]}""",
                        (req.body as OutgoingContent.ByteArrayContent).bytes().decodeToString())
                    if (++writeRequests == 1) throw IllegalStateException("request result lost")
                    respond("""{"revision":1,"items":[{"id":"todo","text":"测😀","done":false}]}""", headers = json)
                }
                else -> error("unexpected endpoint")
            }
        }.use { http ->
            val client = AppsClient(http)
            val entry = client.catalog("dev").apps.single()
            val bundle = client.manifest("dev", entry.id, entry.currentVersion)
            assertContentEquals("hello".encodeToByteArray(), client.asset("dev", bundle, bundle.entry))
            val session = client.openSession("dev", entry.id)
            assertFalse(session.toString().contains(token))
            assertEquals(0L, client.getData(session).revision)
            val update = TodoUpdate("m1", 0, listOf(TodoItem("todo", "测😀", false)))
            val lost = assertFailsWith<AppsException> { client.putData(session, update) }
            assertEquals("connection_failed", lost.code)
            assertTrue(lost.writeOutcomeUnknown)
            assertEquals(1, writeRequests)
            assertEquals(TodoSnapshot(1, update.items), client.putData(session, update))
        }
        assertEquals(2, writeRequests)
    }

    @Test fun manifestRejectsIdentityPathAndCapacityViolations() = runTest {
        val bad = listOf(
            manifest.replace("personal-todo", "another-app"),
            manifest.replace("v2", "v1"),
            manifest.replace("index.html", "%2e%2e/index.html"),
            manifest.replace("index.html", "../index.html"),
            manifest.replace("index.html", "/index.html"),
            manifest.replace("index.html", "https://other.invalid/index.html"),
            manifest.replace("\"size\":5", "\"size\":262145"),
            manifest.replace("\"size\":5", "\"size\":5,\"size\":5"),
            manifest.replace("\"size\":5", "\"size\":5,\"extra\":true"),
            manifest.replace("\"todo.write\"", "\"calendar.write\""),
            " ".repeat(16_385)
        ).iterator()
        mock { respond(bad.next(), headers = json) }.use { http ->
            val client = AppsClient(http)
            assertFailsWith<IllegalArgumentException> { client.manifest("dev", "personal-todo", "../v1") }
            repeat(10) {
                assertEquals("invalid_response", assertFailsWith<AppsException> {
                    client.manifest("dev", "personal-todo", "v2")
                }.code)
            }
            assertEquals("response_too_large", assertFailsWith<AppsException> {
                client.manifest("dev", "personal-todo", "v2")
            }.code)
        }
    }

    @Test fun assetRejectsWrongMimeHashAndSize() = runTest {
        val bodies = listOf("world" to html, "hello!" to html, "hell" to html, "hello" to json).iterator()
        mock { req ->
            if (!req.url.encodedPath.contains("/assets/")) respond(manifest, headers = json)
            else {
                val (body, headers) = bodies.next()
                respond(body, headers = headers)
            }
        }.use { http ->
            val client = AppsClient(http)
            val bundle = client.manifest("dev", "personal-todo", "v2")
            assertFailsWith<IllegalArgumentException> { client.asset("dev", bundle, "not-listed.js") }
            repeat(4) {
                assertEquals("asset_integrity", assertFailsWith<AppsException> {
                    client.asset("dev", bundle, "index.html")
                }.code)
            }
        }
    }

    @Test fun classifiesErrorsAndRejectsRedirectsOrMismatchedWriteReplies() = runTest {
        HttpClient(MockEngine { error("must not make request") }).use {
            assertFailsWith<IllegalArgumentException> { AppsClient(it) }
        }
        val replies = listOf(
            HttpStatusCode.Unauthorized to """{"error":"invalid_app_token"}""",
            HttpStatusCode.Forbidden to """{"error":"app_scope_mismatch"}""",
            HttpStatusCode.Conflict to """{"error":"revision_conflict"}""",
            HttpStatusCode.Conflict to """{"error":"mutation_conflict"}""",
            HttpStatusCode.ServiceUnavailable to """{"error":"storage_unavailable"}""",
            HttpStatusCode.Found to "redirect",
            HttpStatusCode.OK to """{"revision":1,"items":[{"id":"foreign","text":"x","done":false}]}""",
            HttpStatusCode.OK to """{"revision":1,"items":[{"id":"a","text":"\ud800","done":false}]}"""
        ).iterator()
        var requests = 0
        mock { req ->
            requests++
            if (req.url.encodedPath.endsWith("/session")) respond(fakeSession, headers = json)
            else {
                assertEquals("/api/apps/personal-todo/data", req.url.encodedPath)
                val (status, payload) = replies.next()
                respond(payload, status, headersOf(HttpHeaders.Location, "/other"))
            }
        }.use { http ->
            val client = AppsClient(http)
            val session = client.openSession("dev", "personal-todo")
            val update = TodoUpdate("m", 0, emptyList())
            for ((code, uncertain) in listOf("invalid_app_token" to false, "app_scope_mismatch" to false,
                "revision_conflict" to false, "mutation_conflict" to false, "storage_unavailable" to false,
                "http_error" to true, "invalid_response" to true)) {
                val error = assertFailsWith<AppsException> { client.putData(session, update) }
                assertEquals(code, error.code)
                assertEquals(uncertain, error.writeOutcomeUnknown)
            }
            assertEquals("invalid_response", assertFailsWith<AppsException> { client.getData(session) }.code)
        }
        assertEquals(9, requests)
    }

    @Test fun internalDeadlineMarksWriteUnconfirmedWithoutRetry() = runTest {
        var writes = 0
        mock { req ->
            if (req.url.encodedPath.endsWith("/session")) respond(fakeSession, headers = json)
            else {
                writes++
                awaitCancellation()
            }
        }.use { http ->
            val client = AppsClient(http)
            val session = client.openSession("dev", "personal-todo")
            val error = assertFailsWith<AppsException> { client.putData(session, TodoUpdate("m", 0, emptyList())) }
            assertEquals("request_timeout", error.code)
            assertTrue(error.writeOutcomeUnknown)
            assertEquals(1, writes)
        }
    }

    private fun TestScope.mock(handler: MockRequestHandler): HttpClient = HttpClient(MockEngine) {
        followRedirects = false
        engine {
            dispatcher = StandardTestDispatcher(testScheduler)
            addHandler(handler)
        }
    }

    @Test fun externalCancellationIsNotSwallowed() = runTest {
        val started = CompletableDeferred<Unit>()
        val caught = CompletableDeferred<Unit>()
        mock { started.complete(Unit); awaitCancellation() }.use { http ->
            val work = launch {
                assertFailsWith<CancellationException> { AppsClient(http).catalog("dev") }
                caught.complete(Unit)
            }
            started.await()
            work.cancel()
            work.join()
            assertTrue(caught.isCompleted)
        }
    }
}
