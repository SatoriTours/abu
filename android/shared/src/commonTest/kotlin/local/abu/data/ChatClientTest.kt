package local.abu.data

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.runBlocking
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
}
