package local.abu.data

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.*

class GeneratedAppsClientTest {
    @Test fun scopesPublicDataToNativeSessionAndClassifiesConflict() = runBlocking<Unit> {
        val root = "/api/generated-apps/gen-counter"
        HttpClient(MockEngine { req ->
            assertEquals("127.0.0.1", req.url.host)
            when (req.url.encodedPath) {
                "$root/session" -> {
                    assertEquals("Bearer dev", req.headers[HttpHeaders.Authorization])
                    respond("""{"appToken":"scoped","expiresAt":"2035-01-02T00:00:00Z"}""")
                }
                "$root/data" -> {
                    assertEquals("Bearer scoped", req.headers[HttpHeaders.Authorization])
                    if (req.method == HttpMethod.Get) respond("""{"revision":1,"document":{"value":2.5}}""")
                    else respond("""{"error":"revision_conflict"}""", HttpStatusCode.Conflict)
                }
                else -> error("wrong route")
            }
        }) { followRedirects = false }.use {
            val client = GeneratedAppsClient(it)
            val session = client.openSession("dev", "gen-counter")
            assertFalse(session.toString().contains("scoped"))
            assertEquals(1L, client.data(session).revision)
            val err = assertFailsWith<GeneratedAppsException> {
                client.write(session, GeneratedUpdate("m", 1, Json.parseToJsonElement("""{"value":2}""").jsonObject))
            }
            assertEquals("revision_conflict", err.code)
        }
    }

    @Test fun strictGeneratedDocumentsRejectDuplicatesUnicodeAndUnboundedNumbers() {
        for (raw in listOf("""{"a":1,"a":2}""", """{"a":"\udfff"}""", """{"a":1e400}""",
            """{"a":9007199254740993}""", """{"a":9007199254740991.1}""", """{"a":[[[[[[[[0]]]]]]]]}""")) {
            assertFailsWith<IllegalArgumentException> { parseGeneratedDocument(raw) }
        }
        assertNotNull(parseGeneratedDocument("""{"a":[true,null,2.5,0.00001]}"""))
    }
}
