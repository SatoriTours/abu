package local.abu.data

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import io.ktor.http.content.OutgoingContent
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class TaskClientTest {
    private val headers = headersOf(HttpHeaders.ContentType, "application/json")
    private val task = """{"id":"stable_id","content":"hello","status":"queued","createdAt":"2026-11-21T00:00:00Z","updatedAt":"2026-11-21T00:00:00Z"}"""
    private fun response(record: String) = """{"task":$record,"modelConfigured":true,"storageAvailable":true}"""

    @Test fun usesFixedRoutesAndCallerAssignedIdForUnconfirmedSubmissions() = runTest {
        val methods = mutableListOf<Pair<HttpMethod, String>>()
        var submits = 0
        HttpClient(MockEngine { request ->
            assertEquals("127.0.0.1", request.url.host)
            assertEquals(8080, request.url.port)
            assertEquals("Bearer dev-token", request.headers[HttpHeaders.Authorization])
            methods += request.method to request.url.encodedPath
            when (request.method to request.url.encodedPath) {
                HttpMethod.Post to "/api/tasks" -> {
                    assertEquals("""{"id":"stable_id","content":"hello"}""",
                        (request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString())
                    if (++submits == 1) respondError(HttpStatusCode.ServiceUnavailable, """{"error":"storage_unavailable"}""")
                    else respond(response(task), if (submits == 2) HttpStatusCode.Created else HttpStatusCode.OK, headers)
                }
                HttpMethod.Get to "/api/tasks" ->
                    respond("""{"tasks":[$task],"modelConfigured":false,"storageAvailable":false}""", headers = headers)
                HttpMethod.Get to "/api/tasks/stable_id" ->
                    respond(response(task.replace("queued", "completed").dropLast(1) + ""","result":"reply"}"""), headers = headers)
                HttpMethod.Post to "/api/tasks/stable_id/cancel" ->
                    respond(response(task.replace("queued", "cancelled")), headers = headers)
                else -> error("unexpected request")
            }
        }) { followRedirects = false }.use { http ->
            TaskClient(http).let { client ->
                val failure = assertFailsWith<TaskException> { client.create("dev-token", "stable_id", "hello") }
                assertEquals("storage_unavailable", failure.code)
                for (i in 1..2) {
                    val created = client.create("dev-token", "stable_id", "hello")
                    assertEquals("stable_id", created.task.id)
                    assertEquals(TaskStatus.QUEUED, created.task.status)
                }
                val history = client.list("dev-token")
                assertFalse(history.modelConfigured)
                assertFalse(history.storageAvailable)
                assertEquals("hello", history.tasks.single().content)
                assertEquals("reply", client.detail("dev-token", "stable_id").task.result)
                assertEquals(TaskStatus.CANCELLED, client.cancel("dev-token", "stable_id").task.status)
            }
        }
        assertEquals(listOf(
            HttpMethod.Post to "/api/tasks", HttpMethod.Post to "/api/tasks", HttpMethod.Post to "/api/tasks",
            HttpMethod.Get to "/api/tasks", HttpMethod.Get to "/api/tasks/stable_id",
            HttpMethod.Post to "/api/tasks/stable_id/cancel"
        ), methods)
    }

    @Test fun doesNotRedirectOrAcceptUnsafePathIds() = runTest {
        HttpClient(MockEngine { error("must not send a request") }).use { http ->
            assertFailsWith<IllegalArgumentException> { TaskClient(http) }
        }
        var requests = 0
        HttpClient(MockEngine { request ->
            requests++
            assertEquals("/api/tasks", request.url.encodedPath)
            respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "/other"))
        }) { followRedirects = false }.use { http ->
            TaskClient(http).let { client ->
                assertFailsWith<IllegalArgumentException> { client.cancel("token", "../other") }
                assertEquals(302, assertFailsWith<TaskException> { client.list("token") }.statusCode)
            }
        }
        assertEquals(1, requests)
    }

    @Test fun rejectsOversizedMalformedAndPrivateErrorBodies() = runTest {
        val bodies = listOf(
            " ".repeat(65_537) to HttpStatusCode.OK,
            "not JSON" to HttpStatusCode.OK,
            """{"error":"provider-private-details"}""" to HttpStatusCode.Conflict,
            """{"error":"task_limit"}""" to HttpStatusCode.Conflict,
            " ".repeat(10 * 1024 * 1024 + 1) to HttpStatusCode.OK
        ).iterator()
        HttpClient(MockEngine {
            val (body, status) = bodies.next()
            respond(body, status, headers)
        }) { followRedirects = false }.use { http ->
            TaskClient(http).let { client ->
                val actual = (1..4).map {
                    assertFailsWith<TaskException> { client.detail("token", "stable_id") }.code
                }
                assertEquals(listOf("response_too_large", "invalid_response", "http_error", "task_limit"), actual)
                assertEquals("response_too_large", assertFailsWith<TaskException> { client.list("token") }.code)
            }
        }
    }

    @Test fun preservesStructuredCoroutineCancellation() = runTest {
        val started = CompletableDeferred<Unit>()
        HttpClient(MockEngine {
            started.complete(Unit)
            awaitCancellation()
        }) { followRedirects = false }.use { http ->
            TaskClient(http).let { client ->
                val call = launch { client.list("token") }
                started.await()
                call.cancelAndJoin()
                assertTrue(call.isCancelled)
            }
        }
    }
}
