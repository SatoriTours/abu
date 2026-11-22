package local.abu.data

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.*
import kotlinx.coroutines.runBlocking
import kotlin.test.*
import kotlin.coroutines.cancellation.CancellationException

class HealthClientTest {
    private suspend fun check(body: String, status: HttpStatusCode = HttpStatusCode.OK): HealthResult {
        val engine = MockEngine { request ->
            assertEquals("/healthz", request.url.encodedPath)
            respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        return HttpClient(engine).use { HealthClient(it).check() }
    }
    @Test fun healthyResponseConnects() = runBlocking {
        assertEquals(HealthResult.Connected, check("""{"status":"ok"}"""))
    }
    @Test fun jsonWhitespaceIsAccepted() = runBlocking {
        assertEquals(HealthResult.Connected, check("""{ "status" : "ok" }"""))
    }
    @Test fun serverFailureIsReported() = runBlocking {
        assertTrue(check("""{"status":"ok"}""", HttpStatusCode.ServiceUnavailable) is HealthResult.Failed)
    }
    @Test fun malformedOrWrongStatusIsRejected() = runBlocking {
        for (body in listOf("not-json", """{"status":"down"}""", "[]", """{"status":true}""")) {
            assertTrue(check(body) is HealthResult.Failed, body)
        }
    }
    @Test fun oversizedResponseIsRejected() = runBlocking {
        assertTrue(check(" ".repeat(5000) + """{"status":"ok"}""") is HealthResult.Failed)
    }
    @Test fun connectionFailureIsReported() = runBlocking {
        HttpClient(MockEngine { throw IllegalStateException("network unavailable") }).use {
            assertTrue(HealthClient(it).check() is HealthResult.Failed)
        }
    }
    @Test fun cancellationIsNotSwallowed() = runBlocking<Unit> {
        HttpClient(MockEngine { throw CancellationException("cancelled") }).use {
            assertFailsWith<CancellationException> { HealthClient(it).check() }
        }
    }
}
