package local.abu.data

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import io.ktor.http.content.TextContent
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.writeStringUtf8
import kotlinx.coroutines.runBlocking
import kotlinx.io.IOException
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.*

class ChatClientTest {
    @Test fun historyUsesAuthorization() = runBlocking {
        HttpClient(MockEngine { request ->
            assertEquals("Bearer dev-token", request.headers[HttpHeaders.Authorization])
            assertEquals("/api/chat/messages", request.url.encodedPath)
            respond("""{"messages":[{"id":"1","content":"hello","createdAt":"now"}],"modelConfigured":false}""",
                HttpStatusCode.OK, headersOf(HttpHeaders.ContentType,"application/json"))
        }).use {
            assertEquals("hello", ChatClient(it).history("dev-token").single().content)
        }
    }
    @Test fun unauthorizedIsNotEmptyHistory() = runBlocking<Unit> {
        HttpClient(MockEngine { respond("{}", HttpStatusCode.Unauthorized) }).use {
            assertFailsWith<ChatException> { ChatClient(it).history("bad") }
        }
    }
    @Test fun sendReturnsSavedMessage() = runBlocking {
        HttpClient(MockEngine { request ->
            assertEquals(HttpMethod.Post, request.method)
            respond("""{"id":"1","content":"hello","createdAt":"now"}""",
                HttpStatusCode.Created, headersOf(HttpHeaders.ContentType,"application/json"))
        }).use {
            assertEquals("1", ChatClient(it).send("dev-token","1","hello").id)
        }
    }
    @Test fun replyLoadsConfigurationAndSavedResponse() = runBlocking {
        HttpClient(MockEngine { request ->
            if (request.method == HttpMethod.Get) {
                respond("""{"messages":[],"modelConfigured":true}""", HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType,"application/json"))
            } else {
                assertEquals("/api/chat/replies", request.url.encodedPath)
                respond("""{"id":"1","content":"hello","createdAt":"now","reply":"Hi","replyStatus":"completed"}""",
                    HttpStatusCode.OK, headersOf(HttpHeaders.ContentType,"application/json"))
            }
        }).use {
            val client = ChatClient(it)
            assertTrue(client.loadHistory("token").modelConfigured)
            assertEquals("Hi", client.reply("token","1",false).reply)
        }
    }

    private fun sse() = headersOf(HttpHeaders.ContentType, "text/event-stream; charset=utf-8")

    @Test fun replyStreamDeliversDeltasAndCompletedMessage() = runBlocking {
        HttpClient(MockEngine { request ->
            assertEquals(HttpMethod.Post, request.method)
            assertEquals("/api/chat/replies/stream", request.url.encodedPath)
            assertEquals("text/event-stream", request.headers[HttpHeaders.Accept])
            assertTrue((request.body as TextContent).text.contains("\"retry\":false"))
            respond(
                "event: delta\ndata: {\"delta\":\"你\"}\n\n" +
                    ": keep-alive\n\n" +
                    "event: delta\ndata: {\"delta\":\"好\"}\n\n" +
                    "event: done\ndata: {\"id\":\"1\",\"content\":\"hi\",\"createdAt\":\"now\"," +
                    "\"reply\":\"你好\",\"replyStatus\":\"completed\",\"botId\":\"abu\"}\n\n",
                HttpStatusCode.OK, sse(),
            )
        }).use {
            val deltas = mutableListOf<String>()
            val message = ChatClient(it).replyStream("dev-token", "1", false) { delta -> deltas.add(delta) }
            assertEquals(listOf("你", "好"), deltas)
            assertEquals("你好", message.reply)
            assertEquals("completed", message.replyStatus)
            assertEquals("abu", message.botId)
        }
    }

    @Test fun replyStreamRejectsIncompleteAndFailingStreams() = runBlocking<Unit> {
        val interrupted = HttpClient(MockEngine {
            respond("event: delta\ndata: {\"delta\":\"半\"}\n\n", HttpStatusCode.OK, sse())
        })
        interrupted.use {
            val failure = assertFailsWith<ChatException> { ChatClient(it).replyStream("t", "1", false) {} }
            assertEquals("stream_interrupted", failure.code)
        }
        val malformed = HttpClient(MockEngine {
            respond("event: delta\ndata: not-json\n\n", HttpStatusCode.OK, sse())
        })
        malformed.use {
            val failure = assertFailsWith<ChatException> { ChatClient(it).replyStream("t", "1", false) {} }
            assertEquals("invalid_response", failure.code)
        }
        val storage = HttpClient(MockEngine {
            respond("event: error\ndata: {\"error\":\"reply_storage_failed\"}\n\n", HttpStatusCode.OK, sse())
        })
        storage.use {
            val failure = assertFailsWith<ChatException> { ChatClient(it).replyStream("t", "1", false) {} }
            assertEquals("reply_storage_failed", failure.code)
            assertEquals(500, failure.statusCode)
        }
        val model = HttpClient(MockEngine {
            respond(
                "event: error\ndata: {\"error\":\"model_reply_failed\",\"message\":{\"id\":\"1\"," +
                    "\"content\":\"hi\",\"createdAt\":\"now\",\"replyStatus\":\"failed\"}}\n\n",
                HttpStatusCode.OK, sse(),
            )
        })
        model.use {
            val failure = assertFailsWith<ChatException> { ChatClient(it).replyStream("t", "1", false) {} }
            assertEquals("model_reply_failed", failure.code)
            assertEquals(502, failure.statusCode)
        }
    }

    @Test fun replyStreamMapsMidStreamFailureToStreamInterrupted() = runBlocking<Unit> {
        // The HTTP status is 200 and a delta already arrived, then the body read fails.
        val channel = ByteChannel(autoFlush = true)
        channel.writeStringUtf8("event: delta\ndata: {\"delta\":\"半\"}\n\n")
        channel.cancel(IOException("socket reset mid-stream"))
        HttpClient(MockEngine { respond(channel, HttpStatusCode.OK, sse()) }).use {
            val failure = assertFailsWith<ChatException> { ChatClient(it).replyStream("t", "1", false) {} }
            assertEquals("stream_interrupted", failure.code)
        }
    }

    @Test fun replyStreamDoesNotSwallowCancellation() = runBlocking<Unit> {
        val channel = ByteChannel(autoFlush = true)
        channel.writeStringUtf8("event: delta\ndata: {\"delta\":\"半\"}\n\n")
        channel.cancel(CancellationException("reply cancelled"))
        HttpClient(MockEngine { respond(channel, HttpStatusCode.OK, sse()) }).use {
            // Cancellation must reach the caller instead of turning into a chat error.
            assertFailsWith<CancellationException> { ChatClient(it).replyStream("t", "1", false) {} }
        }
    }

    @Test fun replyStreamReportsPreconditionErrors() = runBlocking<Unit> {
        HttpClient(MockEngine {
            respond("{\"error\":\"explicit_retry_required\"}", HttpStatusCode.Conflict,
                headersOf(HttpHeaders.ContentType, "application/json"))
        }).use {
            val failure = assertFailsWith<ChatException> { ChatClient(it).replyStream("t", "1", false) {} }
            assertEquals("explicit_retry_required", failure.code)
            assertEquals(409, failure.statusCode)
        }
    }

    @Test fun cancelReplyReturnsServerFlag() = runBlocking {
        HttpClient(MockEngine { request ->
            assertEquals("/api/chat/replies/cancel", request.url.encodedPath)
            assertEquals("{\"messageId\":\"1\"}", (request.body as TextContent).text)
            respond("{\"cancelled\":true,\"messageId\":\"1\",\"botId\":\"abu\"}", HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "application/json"))
        }).use {
            assertTrue(ChatClient(it).cancelReply("t", "1"))
        }
    }

    @Test fun deleteMessageUsesScopedPath() = runBlocking {
        HttpClient(MockEngine { request ->
            assertEquals(HttpMethod.Delete, request.method)
            assertEquals("/api/chat/messages/abc", request.url.encodedPath)
            assertEquals("leaf", request.url.parameters["botId"])
            respond("{\"deleted\":true,\"id\":\"abc\",\"botId\":\"leaf\"}", HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "application/json"))
        }).use {
            ChatClient(it).deleteMessage("t", "abc", "leaf")
        }
        var calls = 0
        HttpClient(MockEngine { calls++; respond("{}", HttpStatusCode.OK) }).use {
            val client = ChatClient(it)
            for (bad in listOf("", "a/b", "a?b", "a#b", "a b", ".")) {
                val failure = assertFailsWith<ChatException> { client.deleteMessage("t", bad) }
                assertEquals("invalid_message", failure.code)
            }
            assertFailsWith<ChatException> { client.deleteMessage("t", "x".repeat(129)) }
        }
        assertEquals(0, calls)
    }

    @Test fun clearMessagesReturnsDeletedCount() = runBlocking {
        HttpClient(MockEngine { request ->
            assertEquals(HttpMethod.Delete, request.method)
            assertEquals("/api/chat/messages", request.url.encodedPath)
            assertEquals("abu", request.url.parameters["botId"])
            respond("{\"deletedCount\":3,\"botId\":\"abu\"}", HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "application/json"))
        }).use {
            assertEquals(3, ChatClient(it).clearMessages("t", "abu"))
        }
    }

    @Test fun replyAndCancelCarryRunId() = runBlocking<Unit> {
        val bodies = mutableListOf<String>()
        HttpClient(MockEngine { request ->
            val path = request.url.encodedPath
            val text = (request.body as TextContent).text
            bodies.add(path + "|" + text)
            if (path == "/api/chat/replies/stream") {
                respond(
                    "event: done\ndata: {\"id\":\"1\",\"content\":\"hi\",\"createdAt\":\"now\"," +
                        "\"reply\":\"好\",\"replyStatus\":\"completed\",\"botId\":\"abu\"}\n\n",
                    HttpStatusCode.OK, sse(),
                )
            } else {
                respond("{\"cancelled\":true,\"messageId\":\"1\",\"botId\":\"abu\"}", HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, "application/json"))
            }
        }).use {
            val client = ChatClient(it)
            assertEquals("好", client.replyStream("t", "1", true, "abu", "run-2") {}.reply)
            assertTrue(client.cancelReply("t", "1", "abu", "run-2"))
        }
        assertEquals(2, bodies.size)
        assertTrue(bodies[0].startsWith("/api/chat/replies/stream|"))
        assertTrue(bodies[0].contains("\"runId\":\"run-2\""), bodies[0])
        assertTrue(bodies[0].contains("\"botId\":\"abu\""), bodies[0])
        assertTrue(bodies[1].startsWith("/api/chat/replies/cancel|"))
        assertTrue(bodies[1].contains("\"runId\":\"run-2\""), bodies[1])
    }

    @Test fun omittedRunIdKeepsRequestsBackwardCompatible() = runBlocking<Unit> {
        val bodies = mutableListOf<String>()
        HttpClient(MockEngine { request ->
            bodies.add((request.body as TextContent).text)
            respond("{\"cancelled\":false,\"messageId\":\"1\",\"botId\":\"abu\"}", HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "application/json"))
        }).use {
            assertFalse(ChatClient(it).cancelReply("t", "1"))
        }
        assertEquals(listOf("{\"messageId\":\"1\"}"), bodies)
    }

    @Test fun illegalRunIdIsRejectedBeforeAnyRequest() = runBlocking<Unit> {
        var calls = 0
        HttpClient(MockEngine { calls++; respond("{}", HttpStatusCode.OK) }).use {
            val client = ChatClient(it)
            for (bad in listOf("", "has space", "a/b", "a.b", "x".repeat(129))) {
                assertEquals("invalid_reply_request",
                    assertFailsWith<ChatException> { client.cancelReply("t", "1", runId = bad) }.code)
                assertEquals("invalid_reply_request",
                    assertFailsWith<ChatException> { client.reply("t", "1", false, runId = bad) }.code)
            }
        }
        assertEquals(0, calls)
    }

    @Test fun replyStreamMapsPreResponseIoFailureToConnectionFailed() = runBlocking<Unit> {
        // The connection fails before any header arrives, so the user gets a retry hint instead
        // of a stream error.
        HttpClient(MockEngine { throw IOException("connect refused") }).use {
            val failure = assertFailsWith<ChatException> { ChatClient(it).replyStream("t", "1", false) {} }
            assertEquals("connection_failed", failure.code)
        }
    }

    @Test fun regenerateFlagIsOnlySentWhenAsked() = runBlocking<Unit> {
        val bodies = mutableListOf<String>()
        HttpClient(MockEngine { request ->
            bodies.add((request.body as TextContent).text)
            if (request.url.encodedPath.endsWith("/stream")) {
                respond(
                    "event: done\ndata: {\"id\":\"1\",\"content\":\"hi\",\"createdAt\":\"now\"," +
                        "\"reply\":\"好\",\"replyStatus\":\"completed\"}\n\n",
                    HttpStatusCode.OK, sse(),
                )
            } else {
                respond(
                    "{\"id\":\"1\",\"content\":\"hi\",\"createdAt\":\"now\",\"reply\":\"好\",\"replyStatus\":\"completed\"}",
                    HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"),
                )
            }
        }).use {
            val client = ChatClient(it)
            assertEquals("好", client.reply("t", "1", false).reply)
            assertEquals("好", client.reply("t", "1", false, regenerate = true).reply)
            assertEquals("好", client.replyStream("t", "1", false, regenerate = true) {}.reply)
        }
        // An omitted flag keeps the old request byte-for-byte.
        assertEquals("{\"messageId\":\"1\",\"retry\":false}", bodies[0])
        assertTrue(bodies[1].contains("\"regenerate\":true"), bodies[1])
        assertTrue(bodies[2].contains("\"regenerate\":true"), bodies[2])
    }

    @Test fun editMessagePutsTheNewestTurn() = runBlocking<Unit> {
        HttpClient(MockEngine { request ->
            assertEquals(HttpMethod.Put, request.method)
            assertEquals("/api/chat/messages/abc", request.url.encodedPath)
            assertEquals("{\"content\":\"改过\"}", (request.body as TextContent).text)
            respond(
                "{\"id\":\"abc\",\"content\":\"改过\",\"createdAt\":\"now\",\"botId\":\"abu\"}",
                HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }).use {
            val edited = ChatClient(it).editMessage("t", "abc", "改过")
            assertEquals("改过", edited.content)
            assertEquals("", edited.reply)
        }
        var calls = 0
        HttpClient(MockEngine { calls++; respond("{}", HttpStatusCode.OK) }).use {
            val client = ChatClient(it)
            for (bad in listOf("", "a/b", "a?b", "a b", ".")) {
                assertEquals("invalid_message",
                    assertFailsWith<ChatException> { client.editMessage("t", bad, "x") }.code)
            }
        }
        assertEquals(0, calls)
    }

    @Test fun notLatestMessageExplainsTheEditRule() = runBlocking<Unit> {
        HttpClient(MockEngine {
            respond("{\"error\":\"not_latest_message\"}", HttpStatusCode.Conflict,
                headersOf(HttpHeaders.ContentType, "application/json"))
        }).use {
            val failure = assertFailsWith<ChatException> {
                ChatClient(it).editMessage("t", "abc", "改过")
            }
            assertEquals("not_latest_message", failure.code)
            assertEquals(409, failure.statusCode)
            assertEquals("仅支持编辑当前角色的最新一条消息", failure.message)
        }
    }

    @Test fun replyStreamDoesNotSwallowCancellationOfAnErrorBody() = runBlocking<Unit> {
        // A non-200 response whose error body can no longer be read must still report the
        // cancellation to the caller instead of a chat error.
        val channel = ByteChannel(autoFlush = true)
        channel.cancel(CancellationException("error body cancelled"))
        HttpClient(MockEngine {
            respond(channel, HttpStatusCode.Conflict, headersOf(HttpHeaders.ContentType, "application/json"))
        }).use {
            assertFailsWith<CancellationException> { ChatClient(it).replyStream("t", "1", false) {} }
        }
    }
    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")
    private val workSession =
        """{"id":"work","botId":"abu","title":"工作","createdAt":"2024-01-01T00:00:00Z","updatedAt":"2024-01-02T00:00:00Z"}"""
    private val defaultSession =
        """{"id":"default","botId":"abu","title":"默认对话","createdAt":"","updatedAt":""}"""

    @Test fun sessionCrudUsesSessionRoutes() = runBlocking {
        val calls = mutableListOf<String>()
        HttpClient(MockEngine { request ->
            val body = (request.body as? TextContent)?.text ?: ""
            calls.add(request.method.value + " " + request.url.encodedPath + "?" + request.url.encodedQuery + "|" + body)
            when {
                request.method == HttpMethod.Get ->
                    respond("{\"sessions\":[" + defaultSession + "," + workSession + "]}", HttpStatusCode.OK, jsonHeaders)
                request.method == HttpMethod.Post ->
                    respond(workSession, HttpStatusCode.Created, jsonHeaders)
                request.method == HttpMethod.Put ->
                    respond(workSession.replace("工作", "工作台"), HttpStatusCode.OK, jsonHeaders)
                else -> respond(
                    """{"deleted":true,"id":"work","botId":"abu","deletedMessages":3}""",
                    HttpStatusCode.OK, jsonHeaders,
                )
            }
        }).use { http ->
            val client = ChatClient(http)
            val sessions = client.listSessions("t", "abu")
            assertEquals(listOf("default", "work"), sessions.map { it.id })
            assertEquals("默认对话", sessions[0].title)
            assertEquals("work", client.createSession("t", "work", "工作", "abu").id)
            assertEquals("工作台", client.renameSession("t", "work", "工作台", "abu").title)
            assertEquals(3, client.deleteSession("t", "work", "abu"))
        }
        assertEquals(
            listOf(
                "GET /api/chat/sessions?botId=abu|",
                "POST /api/chat/sessions?|" + """{"id":"work","title":"工作","botId":"abu"}""",
                "PUT /api/chat/sessions/work?|" + """{"title":"工作台","botId":"abu"}""",
                "DELETE /api/chat/sessions/work?botId=abu|",
            ),
            calls,
        )
    }

    @Test fun loadHistorySendsPagingAndParsesTheCursor() = runBlocking {
        var query: Parameters? = null
        HttpClient(MockEngine { request ->
            query = request.url.parameters
            respond(
                """{"messages":[{"id":"m-1","content":"hi","createdAt":"now","botId":"abu","sessionId":"work"}],""" +
                    """"modelConfigured":true,"sessionId":"work","hasMore":true,"nextBefore":"m-1"}""",
                HttpStatusCode.OK, jsonHeaders,
            )
        }).use { http ->
            val page = ChatClient(http).loadHistory("t", "abu", "work", before = "m-9", limit = 2)
            assertEquals("work", page.sessionId)
            assertTrue(page.hasMore)
            assertEquals("m-1", page.nextBefore)
            assertEquals("work", page.messages.single().sessionId)
        }
        val params = requireNotNull(query)
        assertEquals("abu", params["botId"])
        assertEquals("work", params["sessionId"])
        assertEquals("m-9", params["before"])
        assertEquals("2", params["limit"])
    }

    @Test fun anOldHistoryResponseStaysCompatible() = runBlocking {
        HttpClient(MockEngine {
            respond(
                """{"messages":[{"id":"1","content":"hello","createdAt":"now","botId":"abu"}],"modelConfigured":false}""",
                HttpStatusCode.OK, jsonHeaders,
            )
        }).use {
            val page = ChatClient(it).loadHistory("t", "abu")
            assertEquals(DEFAULT_SESSION_ID, page.sessionId)
            assertFalse(page.hasMore)
            assertNull(page.nextBefore)
            assertEquals(DEFAULT_SESSION_ID, page.messages.single().sessionId)
        }
    }

    @Test fun aPageFromAnotherSessionIsRejected() = runBlocking {
        HttpClient(MockEngine {
            respond(
                """{"messages":[],"modelConfigured":false,"sessionId":"default"}""",
                HttpStatusCode.OK, jsonHeaders,
            )
        }).use {
            val failure = assertFailsWith<ChatException> { ChatClient(it).loadHistory("t", "abu", "work") }
            assertEquals("invalid_response", failure.code)
        }
    }

    @Test fun sessionErrorsCarryClearMessages() = runBlocking {
        for ((status, code) in listOf(
            HttpStatusCode.BadRequest to "invalid_session",
            HttpStatusCode.BadRequest to "invalid_session_id",
            HttpStatusCode.NotFound to "session_not_found",
            HttpStatusCode.Conflict to "session_limit",
            HttpStatusCode.Conflict to "cannot_delete_default_session",
        )) {
            HttpClient(MockEngine {
                respond("{\"error\":\"" + code + "\"}", status, jsonHeaders)
            }).use {
                val failure = assertFailsWith<ChatException> { ChatClient(it).listSessions("t") }
                assertEquals(code, failure.code)
                assertTrue(failure.message!!.isNotBlank())
                assertNotEquals("服务暂不可用（HTTP " + status.value + "），请重试", failure.message)
            }
        }
    }

    @Test fun omittedSessionKeepsRequestsBackwardCompatible() = runBlocking {
        val bodies = mutableListOf<String>()
        val queries = mutableListOf<String>()
        HttpClient(MockEngine { request ->
            bodies.add((request.body as? TextContent)?.text ?: "")
            queries.add(request.url.encodedQuery)
            when {
                request.url.encodedPath == "/api/chat/replies/stream" -> respond(
                    "event: done\ndata: {\"id\":\"1\",\"content\":\"hi\",\"createdAt\":\"now\"," +
                        "\"reply\":\"好\",\"replyStatus\":\"completed\"}\n\n",
                    HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/event-stream"),
                )
                request.method == HttpMethod.Get -> respond(
                    """{"messages":[],"modelConfigured":false}""", HttpStatusCode.OK, jsonHeaders,
                )
                else -> respond(
                    """{"id":"1","content":"hi","createdAt":"now"}""", HttpStatusCode.OK, jsonHeaders,
                )
            }
        }).use { http ->
            val client = ChatClient(http)
            client.send("t", "1", "hi")
            client.replyStream("t", "1", false) {}
            client.loadHistory("t")
        }
        assertEquals("""{"id":"1","content":"hi"}""", bodies[0])
        assertEquals("""{"messageId":"1","retry":false}""", bodies[1])
        assertEquals("", queries[1])
        assertEquals("", bodies[2])
    }

    @Test fun sessionScopeIsSentWithMessagesAndReplies() = runBlocking {
        val bodies = mutableListOf<String>()
        HttpClient(MockEngine { request ->
            bodies.add((request.body as? TextContent)?.text ?: "")
            val path = request.url.encodedPath
            when {
                path == "/api/chat/replies/stream" -> respond(
                    "event: done\ndata: {\"id\":\"1\",\"content\":\"hi\",\"createdAt\":\"now\"," +
                        "\"reply\":\"好\",\"replyStatus\":\"completed\",\"sessionId\":\"work\"}\n\n",
                    HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/event-stream"),
                )
                request.method == HttpMethod.Delete -> respond(
                    """{"deletedCount":1,"botId":"abu","sessionId":"work"}""", HttpStatusCode.OK, jsonHeaders,
                )
                else -> respond(
                    """{"id":"1","content":"hi","createdAt":"now","sessionId":"work"}""",
                    HttpStatusCode.OK, jsonHeaders,
                )
            }
        }).use { http ->
            val client = ChatClient(http)
            assertEquals("work", client.send("t", "1", "hi", null, "work").sessionId)
            assertEquals("work", client.reply("t", "1", false, null, null, false, "work").sessionId)
            assertEquals("work", client.replyStream("t", "1", false, null, null, false, "work") {}.sessionId)
            assertEquals(1, client.clearMessages("t", null, "work"))
        }
        assertTrue(bodies[0].contains("\"sessionId\":\"work\""), bodies[0])
        assertTrue(bodies[1].contains("\"sessionId\":\"work\""), bodies[1])
        assertTrue(bodies[2].contains("\"sessionId\":\"work\""), bodies[2])
    }

    @Test fun invalidSessionInputIsRejectedBeforeAnyRequest() = runBlocking {
        var calls = 0
        HttpClient(MockEngine { calls++; respond("{}", HttpStatusCode.OK) }).use { http ->
            val client = ChatClient(http)
            for (bad in listOf("", "a b", "a/b", "x".repeat(129))) {
                assertEquals(
                    "invalid_session_id",
                    assertFailsWith<ChatException> { client.deleteSession("t", bad) }.code,
                )
                assertEquals(
                    "invalid_session_id",
                    assertFailsWith<ChatException> { client.loadHistory("t", "abu", bad) }.code,
                )
            }
            assertEquals(
                "invalid_session",
                assertFailsWith<ChatException> { client.createSession("t", "work", "   ") }.code,
            )
            assertEquals(
                "invalid_session",
                assertFailsWith<ChatException> { client.createSession("t", "work", "字".repeat(61)) }.code,
            )
            assertEquals(
                "invalid_session",
                assertFailsWith<ChatException> { client.renameSession("t", "work", "") }.code,
            )
            assertEquals(
                "invalid_session",
                assertFailsWith<ChatException> { client.createSession("t", DEFAULT_SESSION_ID) }.code,
            )
            assertEquals(
                "invalid_message",
                assertFailsWith<ChatException> { client.loadHistory("t", "abu", null, limit = 201) }.code,
            )
        }
        assertEquals(0, calls)
    }
}
