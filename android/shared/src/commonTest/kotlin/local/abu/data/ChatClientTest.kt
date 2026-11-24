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
}
