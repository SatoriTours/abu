package local.abu.data

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import io.ktor.http.content.OutgoingContent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.*

class BotsClientTest {
    private val abu = """{"id":"abu","name":"阿布","instructions":"","avatar":"abu","revision":1}"""
    private val leaf = """{"id":"leaf","name":"叶子","instructions":"guide","avatar":"leaf","revision":1}"""
    private val message = """{"id":"one","content":"hi","createdAt":"now","botId":"leaf"}"""
    private val task = """{"id":"one","content":"hi","createdAt":"now","updatedAt":"now","status":"queued","botId":"leaf"}"""
    private val headers = headersOf(HttpHeaders.ContentType, "application/json")

    @Test fun managesBotsWithStableIdAndRevision() = runBlocking {
        var creates = 0
        HttpClient(MockEngine { r ->
            assertEquals("Bearer dev", r.headers[HttpHeaders.Authorization])
            when (r.method to r.url.encodedPath) {
                HttpMethod.Get to "/api/bots" -> respond("""{"bots":[$abu,$leaf]}""", headers = headers)
                HttpMethod.Post to "/api/bots" -> {
                    assertEquals("""{"id":"leaf","name":"叶子","instructions":"guide","avatar":"leaf"}""", body(r.body))
                    respond(leaf, if (++creates == 1) HttpStatusCode.Created else HttpStatusCode.OK, headers)
                }
                HttpMethod.Put to "/api/bots/leaf" -> {
                    assertEquals("""{"name":"叶子","instructions":"guide","avatar":"leaf","revision":1}""", body(r.body))
                    respond("""{"error":"revision_conflict"}""", HttpStatusCode.Conflict, headers)
                }
                else -> error("unexpected bot route")
            }
        }) { followRedirects = false }.use {
            val client = BotsClient(it)
            val expected = Bot("leaf", "叶子", "guide", BotAvatar.LEAF, 1)
            assertEquals(listOf(Bot("abu", "阿布", "", BotAvatar.ABU, 1), expected), client.list("dev"))
            repeat(2) { assertEquals(expected, client.create("dev", "leaf", "叶子", "guide", BotAvatar.LEAF)) }
            val conflict = assertFailsWith<BotsException> { client.update("dev", expected) }
            assertEquals("revision_conflict", conflict.code)
            assertEquals(409, conflict.statusCode)
        }
    }

    @Test fun threadsChatBotAndTaskScopeToEachEndpoint() = runBlocking {
        HttpClient(MockEngine { r ->
            val query = r.url.parameters
            assertEquals("Bearer dev", r.headers[HttpHeaders.Authorization])
            when (r.method to r.url.encodedPath) {
                HttpMethod.Get to "/api/chat/messages" -> {
                    assertEquals("leaf", query["botId"])
                    respond("""{"messages":[$message],"modelConfigured":true}""", headers = headers)
                }
                HttpMethod.Post to "/api/chat/messages" -> {
                    assertEquals("""{"id":"one","content":"hi","botId":"leaf"}""", body(r.body))
                    respond(message, HttpStatusCode.Created, headers)
                }
                HttpMethod.Post to "/api/chat/replies" -> {
                    assertEquals("""{"messageId":"one","retry":true,"botId":"leaf"}""", body(r.body))
                    respond("""{"error":"bot_not_found"}""", HttpStatusCode.NotFound, headers)
                }
                HttpMethod.Get to "/api/tasks" -> {
                    assertTrue(query["botId"] == "leaf" || query["allBots"] == "true")
                    assertFalse(query.contains("botId") && query.contains("allBots"))
                    respond("""{"tasks":[$task],"modelConfigured":true,"storageAvailable":true}""", headers = headers)
                }
                HttpMethod.Post to "/api/tasks" -> {
                    assertEquals("""{"id":"one","content":"hi","botId":"leaf"}""", body(r.body))
                    respond("""{"task":$task,"modelConfigured":true,"storageAvailable":true}""", HttpStatusCode.Created, headers)
                }
                HttpMethod.Get to "/api/tasks/one" -> {
                    assertEquals("leaf", query["botId"])
                    respond("""{"task":$task,"modelConfigured":true,"storageAvailable":true}""", headers = headers)
                }
                HttpMethod.Post to "/api/tasks/one/cancel" -> {
                    assertEquals("leaf", query["botId"])
                    respond("""{"error":"task_not_found"}""", HttpStatusCode.NotFound, headers)
                }
                else -> error("unexpected scoped route")
            }
        }) { followRedirects = false }.use {
            val chat = ChatClient(it)
            assertEquals("leaf", chat.loadHistory("dev", "leaf").messages.single().botId)
            assertEquals("leaf", chat.send("dev","one","hi","leaf").botId)
            assertEquals("bot_not_found", assertFailsWith<ChatException> { chat.reply("dev","one",true,"leaf") }.code)
            val tasks = TaskClient(it)
            assertEquals("leaf", tasks.list("dev", "leaf").tasks.single().botId)
            assertEquals("leaf", tasks.listAll("dev").tasks.single().botId)
            assertEquals("leaf", tasks.create("dev", "one", "hi","leaf").task.botId)
            assertEquals("leaf", tasks.detail("dev", "one","leaf").task.botId)
            assertEquals("task_not_found", assertFailsWith<TaskException> { tasks.cancel("dev","one","leaf") }.code)
        }
    }

    @Test fun rejectsCrossScopeResponseInsteadOfLeakingAnotherRolesRecords() = runBlocking {
        HttpClient(MockEngine { r ->
            if (r.url.encodedPath.startsWith("/api/chat")) respond(
                """{"messages":[$message],"modelConfigured":true}""", headers = headers)
            else respond("""{"tasks":[$task],"modelConfigured":true,"storageAvailable":true}""", headers = headers)
        }) { followRedirects = false }.use {
            assertEquals("invalid_response", assertFailsWith<ChatException> { ChatClient(it).loadHistory("dev", "other") }.code)
            assertEquals("invalid_response", assertFailsWith<TaskException> { TaskClient(it).list("dev", "other") }.code)
        }
    }

    @Test fun handlesInvalidResponseAndCancellationSafely() = runBlocking {
        HttpClient(MockEngine { error("must not send request") }).use {
            assertFailsWith<IllegalArgumentException> { BotsClient(it) }
        }
        val started = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        HttpClient(MockEngine { r ->
            if (r.url.encodedPath == "/api/bots") {
                respond("""{"bots":[$abu,${leaf.replace("\"revision\":1", "\"revision\":0")}]}""", headers = headers)
            } else {
                started.complete(Unit)
                awaitCancellation()
            }
        }) { followRedirects = false }.use {
            val client = BotsClient(it)
            assertEquals("invalid_response", assertFailsWith<BotsException> { client.list("dev") }.code)
            val job = launch {
                assertFailsWith<CancellationException> { client.update("dev", Bot("leaf", "叶", "", BotAvatar.LEAF,1)) }
                cancelled.complete(Unit)
            }
            started.await()
            job.cancel()
            job.join()
            assertTrue(cancelled.isCompleted)
        }
    }

    private fun body(content: OutgoingContent) = (content as OutgoingContent.ByteArrayContent).bytes().decodeToString()
}
