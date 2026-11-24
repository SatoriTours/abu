package local.abu.data

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.*
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class ServerEndpointTest {
    private val json = headersOf(HttpHeaders.ContentType, "application/json")
    private val configured = { "https://abu.example:8443/" }

    @Test fun defaultEndpointIsTheLoopbackDevelopmentServer() {
        assertEquals("http://127.0.0.1:8080", DEFAULT_SERVER_BASE_URL)
        assertEquals("http://127.0.0.1:8080", normalizeServerBaseUrl(" http://127.0.0.1:8080/ ", true))
    }

    @Test fun httpsAddressesBecomeACanonicalOrigin() {
        assertEquals("https://abu.example", normalizeServerBaseUrl("https://abu.example", false))
        assertEquals("https://abu.example", normalizeServerBaseUrl("https://abu.example/", false))
        assertEquals("https://abu.example:8443", normalizeServerBaseUrl("https://abu.example:8443/", false))
        assertEquals("https://abu.example:8443", normalizeServerBaseUrl("  HTTPS://abu.example:8443  ", false))
        assertEquals("https://127.0.0.1", normalizeServerBaseUrl("https://127.0.0.1", false))
        assertEquals("https://abu.local:80", normalizeServerBaseUrl("https://abu.local:80", false))
        assertEquals("https://[::1]:8443", normalizeServerBaseUrl("https://[::1]:8443", false))
        assertEquals("https://[fe80::1]", normalizeServerBaseUrl("https://[fe80::1]/", false))
    }

    @Test fun cleartextNeedsAnExplicitOptIn() {
        assertNull(normalizeServerBaseUrl("http://192.168.2.10:8080", false))
        assertEquals("http://192.168.2.10:8080", normalizeServerBaseUrl("http://192.168.2.10:8080", true))
        assertEquals("http://[fe80::1]:8080", normalizeServerBaseUrl("http://[fe80::1]:8080", true))
        assertEquals("http://127.0.0.1:8080", normalizeServerBaseUrl("http://127.0.0.1:8080/", true))
    }

    @Test fun ambiguousOrIncompleteAddressesAreRejected() {
        val rejected = listOf(
            "", "   ", "abu.example", "//abu.example", "ftp://abu.example", "https:/abu.example",
            "https://", "https:///path", "https://user@abu.example", "https://abu.example?x=1",
            "https://abu.example#frag", "https://abu.example/api", "https://abu.example//",
            "https://abu.example///", "https://abu.example:0",
            "https://abu.example:65536", "https://abu.example:", "https://abu.example:8o80",
            "https://abu example", "https://-bad.example", "https://abu.exa\u0000mple",
            "https://[::1", "https://[::1]x", "https://:", "https://abu.example\\path",
        )
        for (raw in rejected) {
            assertNull(normalizeServerBaseUrl(raw, true), raw)
        }
    }

    @Test fun healthClientUsesTheConfiguredAddress() = runBlocking {
        val urls = mutableListOf<String>()
        HttpClient(MockEngine { request ->
            urls.add(request.url.toString())
            respond("""{"status":"ok"}""", HttpStatusCode.OK, json)
        }).use { http ->
            assertEquals(HealthResult.Connected, HealthClient(http, configured).check())
        }
        assertEquals(listOf("https://abu.example:8443/healthz"), urls)
    }

    @Test fun chatClientUsesTheConfiguredAddress() = runBlocking {
        val urls = mutableListOf<String>()
        HttpClient(MockEngine { request ->
            urls.add(request.url.toString())
            respond("""{"messages":[],"modelConfigured":false}""", HttpStatusCode.OK, json)
        }).use { http ->
            assertTrue(ChatClient(http, configured).loadHistory("token").messages.isEmpty())
        }
        assertEquals(listOf("https://abu.example:8443/api/chat/messages"), urls)
    }

    @Test fun botsClientUsesTheConfiguredAddress() = runBlocking {
        val urls = mutableListOf<String>()
        HttpClient(MockEngine { request ->
            urls.add(request.url.toString())
            respond(
                """{"bots":[{"id":"abu","name":"阿布","instructions":"","avatar":"abu","revision":1}]}""",
                HttpStatusCode.OK, json,
            )
        }) { followRedirects = false }.use { http ->
            assertEquals(listOf("abu"), BotsClient(http, configured).list("token").map { it.id })
        }
        assertEquals(listOf("https://abu.example:8443/api/bots"), urls)
    }

    @Test fun taskClientUsesTheConfiguredAddress() = runBlocking {
        val urls = mutableListOf<String>()
        HttpClient(MockEngine { request ->
            urls.add(request.url.toString())
            respond("""{"tasks":[],"modelConfigured":false,"storageAvailable":true}""", HttpStatusCode.OK, json)
        }) { followRedirects = false }.use { http ->
            assertTrue(TaskClient(http, configured).list("token").tasks.isEmpty())
        }
        assertEquals(listOf("https://abu.example:8443/api/tasks"), urls)
    }

    @Test fun appsClientUsesTheConfiguredAddress() = runBlocking {
        val urls = mutableListOf<String>()
        HttpClient(MockEngine { request ->
            urls.add(request.url.toString())
            respond("""{"appToken":"tok_1","expiresAt":"2024-01-01T00:00:00Z"}""", HttpStatusCode.OK, json)
        }) { followRedirects = false }.use { http ->
            assertEquals("todo", AppsClient(http, configured).openSession("token", "todo").appId)
        }
        assertEquals(listOf("https://abu.example:8443/api/apps/todo/session"), urls)
    }

    @Test fun generatedAppsClientUsesTheConfiguredAddress() = runBlocking {
        val urls = mutableListOf<String>()
        HttpClient(MockEngine { request ->
            urls.add(request.url.toString())
            respond("""{"jobs":[],"modelConfigured":false}""", HttpStatusCode.OK, json)
        }) { followRedirects = false }.use { http ->
            assertTrue(GeneratedAppsClient(http, configured).jobs("token").jobs.isEmpty())
        }
        assertEquals(listOf("https://abu.example:8443/api/app-drafts"), urls)
    }

    @Test fun anUnconfiguredClientKeepsTheDevelopmentDefault() = runBlocking {
        val urls = mutableListOf<String>()
        HttpClient(MockEngine { request ->
            urls.add(request.url.toString())
            respond("""{"status":"ok"}""", HttpStatusCode.OK, json)
        }).use { http ->
            assertEquals(HealthResult.Connected, HealthClient(http).check())
        }
        assertEquals(listOf("http://127.0.0.1:8080/healthz"), urls)
    }

    @Test fun aChangedAddressReachesAnExistingClient() = runBlocking {
        val urls = mutableListOf<String>()
        var base = "http://127.0.0.1:8080"
        HttpClient(MockEngine { request ->
            urls.add(request.url.toString())
            respond("""{"status":"ok"}""", HttpStatusCode.OK, json)
        }).use { http ->
            val client = HealthClient(http) { base }
            client.check()
            base = "https://abu.example:8443/"
            client.check()
        }
        assertEquals(
            listOf("http://127.0.0.1:8080/healthz", "https://abu.example:8443/healthz"),
            urls,
        )
    }
}
