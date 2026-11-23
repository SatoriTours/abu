package local.abu.platform

import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import java.io.BufferedInputStream
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.*

class HealthClientTransportTest {
    @Test fun committedWriteWithLostHeadersIsNotReplayed() = runBlocking<Unit> {
        // An HTTP proxy may silently close after reading the full body. A reused connection
        // exercises OkHttp's real response-header recovery (a fresh route might not be retried).
        withServer(firstResponse = null) { url ->
            val failure = assertFailsWith<IOException> { put(url) }
            println("EOF response failure: ${generateSequence(failure as Throwable?) { it.cause }.joinToString(" -> ") { it.javaClass.simpleName + ": " + it.message }}")
        }
    }

    @Test fun serviceUnavailableDoesNotReplayEvenWithZeroRetryAfter() = runBlocking<Unit> {
        withServer(firstResponse = "503 Service Unavailable\r\nRetry-After: 0", keepAlive = true) { url ->
            assertEquals(HttpStatusCode.ServiceUnavailable, put(url).status)
        }
    }

    @Test fun ordinaryServiceUnavailableReturnsUnchangedStatus() = runBlocking<Unit> {
        withServer(firstResponse = "503 Service Unavailable") { url ->
            assertEquals(HttpStatusCode.ServiceUnavailable, put(url).status)
        }
    }

    @Test fun requestTimeoutResponseIsNotReplayed() = runBlocking<Unit> {
        withServer(firstResponse = "408 Request Timeout", keepAlive = true) { url ->
            assertEquals(HttpStatusCode.RequestTimeout, put(url).status)
        }
    }

    @Test fun redirectDoesNotForwardRequest() = runBlocking<Unit> {
        withServer(firstResponse = "307 Temporary Redirect\r\nLocation: /unexpected") { url ->
            assertEquals(HttpStatusCode.TemporaryRedirect, put(url).status)
        }
    }

    private suspend fun withServer(
        firstResponse: String?, keepAlive: Boolean = false,
        check: suspend io.ktor.client.HttpClient.(String) -> Unit,
    ) {
        ServerSocket(0, 4, InetAddress.getByName("127.0.0.1")).use { listener ->
            listener.soTimeout = 2000
            val writes = mutableListOf<String>()
            val task = FutureTask {
                var connections = 0
                fun serve(primed: Boolean) {
                    listener.accept().use { peer ->
                        connections++
                        peer.soTimeout = 1600
                        val input = BufferedInputStream(peer.getInputStream())
                        fun readRequest(allowEOF: Boolean = false): String {
                            if (allowEOF) {
                                input.mark(1)
                                if (input.read() == -1) return ""
                                input.reset()
                            }
                            val start = line(input)
                            var length = 0
                            while (true) {
                                val header = line(input)
                                if (header.isEmpty()) break
                                if (header.startsWith("Content-Length:", ignoreCase = true)) {
                                    length = header.substringAfter(':').trim().toInt()
                                }
                            }
                            check(length in 0..4096)
                            val body = ByteArray(length)
                            java.io.DataInputStream(input).readFully(body)
                            if (start.startsWith("PUT ")) {
                                val digest = MessageDigest.getInstance("SHA-256").digest(body).toHexString()
                                writes += "$start bytes=$length sha256=$digest"
                                assertContentEquals(payload.encodeToByteArray(), body)
                            }
                            return start
                        }
                        fun respond(status: String, connection: String) {
                            peer.getOutputStream().apply {
                                write("HTTP/1.1 $status\r\nContent-Length: 0\r\nConnection: $connection\r\n\r\n".encodeToByteArray())
                                flush()
                            }
                        }
                        if (primed) {
                            assertTrue(readRequest().startsWith("GET /prime "))
                            respond("200 OK", "keep-alive")
                            assertTrue(readRequest().startsWith("PUT /data "))
                            firstResponse?.let { respond(it, if (keepAlive) "keep-alive" else "close") }
                            if (keepAlive) {
                                peer.soTimeout = 450
                                val followUp = try { readRequest(allowEOF = true) } catch (_: SocketTimeoutException) { "" }
                                if (followUp.isNotEmpty()) respond("200 OK", "close")
                            }
                        } else {
                            readRequest()
                            respond("200 OK", "close")
                        }
                    }
                }
                serve(true)
                listener.soTimeout = 900
                try { serve(false) } catch (_: SocketTimeoutException) { /* No second connection. */ }
                connections
            }
            val thread = Thread(task, "abu-http-fixture").apply { isDaemon = true; start() }
            try {
                createHealthHttpClient().use { client ->
                    val url = "http://127.0.0.1:${listener.localPort}"
                    withTimeout(5000) {
                        assertEquals("", client.get("$url/prime").bodyAsText())
                        try {
                            client.check("$url/data")
                        } finally {
                            val connections = task.get(4, TimeUnit.SECONDS)
                            println("fixture connections=$connections completeWrites=${writes.size}; ${writes.joinToString(" | ")}")
                            assertEquals(1, writes.size, "unprompted request replay")
                            assertEquals(1, connections, "unprompted follow-up connection")
                        }
                    }
                }
            } finally {
                listener.close()
                thread.join(2300)
                check(!thread.isAlive) { "fixture thread did not exit" }
            }
        }
    }

    private suspend fun io.ktor.client.HttpClient.put(url: String) = put(url) {
        contentType(ContentType.Application.Json)
        setBody(payload)
    }

    private fun line(input: BufferedInputStream): String {
        val line = StringBuilder()
        repeat(8192) {
            val v = input.read()
            check(v != -1) { "fixture EOF before complete headers" }
            if (v == '\n'.code) return line.toString().trimEnd('\r')
            line.append(v.toChar())
        }
        error("fixture header line too long")
    }

    companion object {
        private const val payload = """{"mutationId":"fixture","baseRevision":2,"document":{"count":3}}"""
    }
}
