package local.abu.miniapps

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import local.abu.data.*
import kotlin.test.*

class GeneratedMiniAppTest {
    @Test fun validatesExactResourceUrlAgainstNativeOriginAndFrame() {
        val route = GeneratedOrigin.published("gen-one", 2)
        assertEquals("index.html", route.pathFor("GET", route.entryUrl, true))
        assertEquals("app.css", route.pathFor("GET", route.root + "app.css", false))
        assertNull(route.pathFor("GET", route.root + "index.html", false))
        assertNull(route.pathFor("GET", route.root + "app.js", true))
        for (path in listOf("../app.js", "%61pp.js", "app.js?x=y", "app.js#x", "worker.js", "app.js/")) {
            assertNull(route.pathFor("GET", route.root + path, false))
        }
        assertNull(route.pathFor("GET", GeneratedOrigin.published("gen-two", 2).entryUrl, true))
        assertNotEquals(route.origin, GeneratedOrigin.preview("gen-one", "draft").origin)
    }

    @Test fun oneNativeTicketBoundToPageAndCurrentRevisionOnly() {
        var now = 100L
        val gate = GeneratedPortGate { now }
        val first = gate.attach("gen-one", preview = false)
        val snap = GeneratedSnapshot(1, buildJsonObject {})
        val request = assertIs<GeneratedBridgeCommand.Propose>(parseGeneratedCommand("""["propose",1,{"note":"example"}]"""))
        val confirmation = assertNotNull(gate.propose(first, request, snap, online = true))
        assertNull(gate.propose(first, request, snap, online = true))
        assertNull(gate.confirm(first, confirmation, snap.copy(revision = 2), online = true))
        val current = gate.attach("gen-two", preview = false)
        assertNull(gate.confirm(current, confirmation, snap, online = true))
        assertNull(gate.propose(current, request, snap, online = false))
        val accepted = assertNotNull(gate.propose(current, request, snap, online = true))
        assertNotNull(gate.confirm(current, accepted, snap, online = true))
        assertNull(gate.confirm(current, accepted, snap, online = true))
        val preview = gate.attach("gen-two", preview = true)
        assertNull(gate.propose(preview, request, snap, online = true))
        assertNull(parseGeneratedCommand("""["propose",1,{},true]"""))
        assertNull(parseGeneratedCommand("""["write","gen-two",{}]"""))
        repeat(12) { assertTrue(gate.allow(preview)) }
        assertFalse(gate.allow(preview))
        now += 1_001
        assertTrue(gate.allow(preview))
    }

    @Test fun journalsRetainPendingAndCacheOffline() = runBlocking<Unit> {
        val root = Files.createTempDirectory("generated-cache-test").toFile()
        val app = "gen-one"
        val resources = listOf("<h1>Custom</h1>", "", "")
        val mime = listOf("text/html", "text/css", "text/javascript").map { "$it; charset=utf-8" }
        fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).toHexString()
        val hashes = resources.map { digest(it.encodeToByteArray()) }
        val hash = digest(hashes.joinToString("").encodeToByteArray())
        val empty = GeneratedSnapshot(0, buildJsonObject {})
        var requests = 0
        val attempts = mutableListOf<GeneratedUpdate>()
        val source = object : GeneratedRuntimeSource {
            override suspend fun currentVersion(appId: String) = 1
            override suspend fun manifest(appId: String, version: Int): GeneratedManifest {
                requests++
                return GeneratedManifest(1, appId, version, hash, "index.html", GENERATED_FILES.indices.map { i ->
                    GeneratedAsset(GENERATED_FILES[i], mime[i], resources[i].length, hashes[i])
                })
            }

            override suspend fun asset(manifest: GeneratedManifest, path: String, draft: String?): ByteArray {
                requests++
                return resources[GENERATED_FILES.indexOf(path)].encodeToByteArray()
            }

            override suspend fun job(id: String): GeneratedJob = error("not requested")

            override suspend fun read(appId: String): GeneratedSnapshot {
                requests++
                return empty
            }

            override suspend fun write(appId: String, update: GeneratedUpdate, current: () -> Boolean): GeneratedSnapshot {
                requests++
                assertTrue(current())
                attempts += update
                if (attempts.size == 1) throw GeneratedAppsException("connection_failed")
                return GeneratedSnapshot(1, update.document)
            }
        }
        try {
            var identityActive = true
            val online = GeneratedRuntimeState(source, root, "dev-a", { identityActive }, {})
            val page = online.openPublished(app, 1)
            val changes = """["propose",0,{"name":"example"}]"""
            assertNotNull(online.command(page, changes))
            val ticket = assertNotNull(online.state?.approval)
            assertTrue(attempts.isEmpty())
            assertFailsWith<GeneratedAppsException> { online.confirm(page, ticket) }
            assertFailsWith<GeneratedAppsException> { online.confirm(page, ticket) }
            assertNotNull(online.state?.pending)
            online.close()
            val before = requests
            val reopened = GeneratedRuntimeState(source, root, "dev-a", { identityActive }, {})
            val offline = reopened.openPublished(app, online = false)
            assertFalse(reopened.state!!.online)
            assertNotNull(reopened.state!!.pending)
            assertContains(reopened.state!!.webState(), "\"mode\":\"offline\"")
            assertNotNull(reopened.command(offline, changes))
            assertNull(reopened.state!!.approval)
            assertEquals(before, requests)
            val other = GeneratedRuntimeState(source, root, "dev-b", { true }, {})
            assertEquals("no_offline_bundle", assertFailsWith<GeneratedAppsException> {
                other.openPublished(app, online = false)
            }.code)
            reopened.refresh(offline)
            reopened.retry(offline)
            assertEquals(2, attempts.size)
            assertEquals(attempts[0], attempts[1])
            assertEquals(1L, reopened.state!!.confirmed!!.snapshot.revision)
            identityActive = false
            assertNull(reopened.command(offline, """["read"]"""))
            reopened.retire()
            assertNull(reopened.state)
        } finally { root.deleteRecursively() }
    }

    @Test fun cancelledConfirmAndRetryRequireFreshReadAndKeepTheExactWrite() = runtimeTest {
        val runtime = runtime()
        val page = runtime.openPublished(app, 1)
        val ticket = propose(runtime, page)
        suspend fun cancelInWrite(operation: suspend () -> Unit) {
            val entered = CompletableDeferred<Unit>()
            source.send = {
                entered.complete(Unit)
                awaitCancellation()
            }
            val pending = async { operation() }
            entered.await()
            pending.cancel(CancellationException("testing interrupted write"))
            assertEquals("testing interrupted write", assertFailsWith<CancellationException> { pending.await() }.message)
            source.send = source::commit
        }

        cancelInWrite { runtime.confirm(page, ticket) }
        assertEquals(ticket.update, runtime.state?.pending?.update)
        assertEquals(ticket.update, cache.journal(identity, app).pending?.update)
        assertRetryBlocked(runtime, page, 1)

        source.readFailure = true
        assertFailsWith<GeneratedAppsException> { runtime.refresh(page) }
        assertRetryBlocked(runtime, page, 1)
        source.readFailure = false

        runtime.refresh(page)
        cancelInWrite { runtime.retry(page) }
        assertRetryBlocked(runtime, page, 2)
        assertEquals(ticket.update, cache.journal(identity, app).pending?.update)

        runtime.refresh(page)
        source.send = source::commit
        runtime.retry(page)
        assertEquals(List(3) { ticket.update }, source.attempts)
        assertEquals(1, source.commits)
        assertNull(runtime.state?.pending)
        assertTrue(runtime.state!!.online)
    }

    @Test fun confirmationJournalFailureAfterServerAckKeepsUnconfirmedWriteLocked() = runtimeTest {
        val runtime = runtime()
        val page = runtime.openPublished(app, 1)
        val ticket = propose(runtime, page)
        // Block only the final atomic journal rename, after the old pending tuple is durably saved.
        val journal = File(root, "$identity/$app/journal")
        val preserved = File(journal.parentFile, "pending-before-fault")
        val ackWithFault: suspend (GeneratedUpdate) -> GeneratedSnapshot = { update ->
            val saved = source.commit(update)
            Files.move(journal.toPath(), preserved.toPath())
            assertTrue(journal.mkdir())
            assertTrue(File(journal, "block-rename").createNewFile())
            saved
        }
        fun restore() {
            assertTrue(File(journal, "block-rename").delete())
            assertTrue(journal.delete())
            Files.move(preserved.toPath(), journal.toPath())
        }

        source.send = ackWithFault
        assertFailsWith<IOException> { runtime.confirm(page, ticket) }
        assertEquals(1L, source.snapshot.revision)
        assertEquals(0L, runtime.state?.confirmed?.snapshot?.revision)
        assertEquals(ticket.update, runtime.state?.pending?.update)
        restore()
        source.send = source::commit
        assertEquals(ticket.update, cache.journal(identity, app).pending?.update)
        assertRetryBlocked(runtime, page, 1)

        runtime.refresh(page)
        // The exact same pending payload gets the idempotent ACK, then another local persistence fault.
        source.send = ackWithFault
        assertFailsWith<IOException> { runtime.retry(page) }
        restore()
        source.send = source::commit
        assertRetryBlocked(runtime, page, 2)
        runtime.refresh(page)
        source.send = source::commit
        runtime.retry(page)
        assertEquals(List(3) { ticket.update }, source.attempts)
        assertEquals(1, source.commits)
        assertEquals(1L, cache.journal(identity, app).timed?.snapshot?.revision)
        assertNull(cache.journal(identity, app).pending)
    }

    @Test fun lateAcknowledgementDoesNotReopenClosedPageOrAffectNewIdentity() = runtimeTest {
        for (retire in listOf(false, true)) {
            val oldToken = "old-$retire"
            val oldSource = FixtureSource()
            val old = runtime(oldToken, oldSource)
            val page = old.openPublished(app, 1)
            val ticket = propose(old, page)
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            oldSource.send = {
                val saved = oldSource.commit(it)
                entered.complete(Unit)
                release.await()
                saved
            }
            val send = async { assertFailsWith<GeneratedAppsException> { old.confirm(page, ticket) } }
            entered.await()
            if (retire) old.retire() else old.close()
            val fresh = runtime("new-$retire", FixtureSource())
            val nextPage = fresh.openPublished(app, 1)
            val untouched = fresh.state
            release.complete(Unit)
            assertEquals("stale_page", send.await().code)
            assertNull(old.state)
            assertTrue(fresh.active(nextPage))
            assertSame(untouched, fresh.state)
            assertEquals(ticket.update, cache.journal(todoIdentity(oldToken), app).pending?.update)
            assertNull(cache.journal(todoIdentity("new-$retire"), app).pending)
        }
    }

    @Test fun runtimeReplacesExpiredApprovalWithoutReplacingALiveOne() = runtimeTest {
        val runtime = runtime()
        val page = runtime.openPublished(app, 1)
        val first = propose(runtime, page)
        clock += 59_999
        runtime.command(page, change)
        assertSame(first, runtime.state?.approval)

        clock++
        val second = propose(runtime, page)
        assertNotSame(first, second)
        assertEquals("approval_not_current", assertFailsWith<GeneratedAppsException> { runtime.confirm(page, first) }.code)
        assertSame(second, runtime.state?.approval)
        assertTrue(source.attempts.isEmpty())

        clock += 60_000
        runtime.command(page, """["propose",99,{"name":"bad revision"}]""")
        assertNull(runtime.state?.approval, "a rejected proposal must not leave an expired approval visible")
        val third = propose(runtime, page)
        clock += 60_000
        assertNull(runtime.command(page, """["propose",0,{},true]"""))
        assertNull(runtime.state?.approval, "a malformed proposal must not leave an expired approval visible")
        assertEquals("approval_not_current", assertFailsWith<GeneratedAppsException> { runtime.confirm(page, third) }.code)
        assertTrue(source.attempts.isEmpty())
    }

    @Test fun portReadFetchesLiveDataWithoutUnlockingPendingAndRejectsOldFrames() = runtimeTest {
        val runtime = runtime()
        val page = runtime.openPublished(app, 1)
        source.snapshot = GeneratedSnapshot(1, parseGeneratedDocument("""{"new":true}"""))
        assertContains(assertNotNull(runtime.portCommand(page, """["read"]""")), "\"revision\":1")
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        source.readHook = { entered.complete(Unit); release.await() }
        val first = async { runtime.portCommand(page, """["read"]""") }
        entered.await()
        assertContains(assertNotNull(runtime.portCommand(page, """["read"]""")), "\"error\":\"busy\"")
        runtime.close()
        release.complete(Unit)
        assertNull(first.await())
        val reads = source.reads
        assertNull(runtime.portCommand(page, """["read"]"""))
        assertEquals(reads, source.reads)
    }

    @Test fun portReadFailureAndInvalidMessagesAreExplicitAndDoNotWrite() = runtimeTest {
        val runtime = runtime()
        val page = runtime.openPublished(app, 1)
        source.send = { throw GeneratedAppsException("connection_failed") }
        val ticket = propose(runtime, page)
        assertFailsWith<GeneratedAppsException> { runtime.confirm(page, ticket) }
        source.readFailure = true
        assertContains(assertNotNull(runtime.portCommand(page, """["read"]""")), "\"error\":\"read_unavailable\"")
        source.readFailure = false
        assertContains(assertNotNull(runtime.portCommand(page, """["read"]""")), "\"mode\":\"offline\"")
        assertRetryBlocked(runtime, page, 1)
        assertContains(assertNotNull(runtime.portCommand(page, """["confirm",true]""")), "\"error\":\"invalid_message\"")
        assertContains(assertNotNull(runtime.portCommand(page, "x".repeat(GENERATED_FILE_LIMIT + 129))), "\"error\":\"invalid_message\"")
        repeat(12) { runtime.portCommand(page, """["read"]""") }
        assertContains(assertNotNull(runtime.portCommand(page, """["read"]""")), "\"error\":\"rate_limited\"")
        assertEquals(1, source.attempts.size)
    }

    @Test fun deniedPortReadClosesMountedPageWithoutReturningConfirmedData() = runtimeTest {
        val runtime = runtime()
        source.snapshot = GeneratedSnapshot(1, parseGeneratedDocument("""{"private":"fixture"}"""))
        for ((code, status) in listOf("unauthorized" to 401, "invalid_app_token" to 401,
            "app_scope_mismatch" to 403, "http_error" to 401, "http_error" to 403)) {
            source.readHook = {}
            val page = runtime.openPublished(app, 1)
            val delivered = mutableListOf<GeneratedPageState>()
            var revoked = 0
            val failures = mutableListOf<String>()
            runtime.bind(page, { delivered += it }, { revoked++ }) {
                assertEquals(1, revoked, "host must be disposed before notifying the UI")
                failures += it
            }
            source.readHook = { throw GeneratedAppsException(code, status) }
            assertNull(runtime.portCommand(page, """["read"]"""), "authorized cache must not be sent after $code")
            assertFalse(runtime.active(page))
            assertNull(runtime.state)
            assertEquals(1, revoked)
            assertEquals(1, delivered.size)
            assertEquals(listOf(code), failures)
            assertEquals(source.snapshot, cache.journal(identity, app).timed?.snapshot)
        }
    }

    @Test fun deniedPendingReadPreservesExactJournalAndRecoveryDoesNotRetry() = runtimeTest {
        val runtime = runtime()
        val page = runtime.openPublished(app, 1)
        val ticket = propose(runtime, page)
        source.send = { throw GeneratedAppsException("connection_failed") }
        assertFailsWith<GeneratedAppsException> { runtime.confirm(page, ticket) }
        source.readHook = { throw GeneratedAppsException("unauthorized", 401) }
        assertNull(runtime.portCommand(page, """["read"]"""))
        assertNull(runtime.state)
        assertEquals(ticket.update, cache.journal(identity, app).pending?.update)
        assertEquals("stale_page", assertFailsWith<GeneratedAppsException> { runtime.retry(page) }.code)
        assertEquals(1, source.attempts.size)
        source.readHook = {}
        val recovered = runtime.openPublished(app, 1)
        assertEquals(ticket.update, runtime.state?.pending?.update)
        assertEquals(1, source.attempts.size)
        source.readHook = { throw GeneratedAppsException("app_scope_mismatch", 403) }
        assertEquals("app_scope_mismatch", assertFailsWith<GeneratedAppsException> { runtime.refresh(recovered) }.code)
        assertNull(runtime.state)
        assertEquals(ticket.update, cache.journal(identity, app).pending?.update)
    }

    @Test fun ioAndCancellationKeepPageWhileLateDeniedReadCannotRevokeReplacement() = runtimeTest {
        val runtime = runtime()
        source.snapshot = GeneratedSnapshot(1, parseGeneratedDocument("""{"cached":true}"""))
        val page = runtime.openPublished(app, 1)
        source.readHook = { throw IOException("fixture disconnected") }
        val offline = assertNotNull(runtime.portCommand(page, """["read"]"""))
        assertContains(offline, "\"mode\":\"offline\"")
        assertContains(offline, "\"cached\":true")
        assertTrue(runtime.active(page))
        source.readHook = { throw CancellationException("fixture cancelled") }
        assertEquals("fixture cancelled", assertFailsWith<CancellationException> {
            runtime.portCommand(page, """["read"]""")
        }.message)
        assertTrue(runtime.active(page))

        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        source.readHook = {
            entered.complete(Unit)
            release.await()
            throw GeneratedAppsException("unauthorized", 401)
        }
        val late = async { runtime.portCommand(page, """["read"]""") }
        entered.await()
        runtime.retire()
        val replacement = runtime("replacement", FixtureSource())
        val fresh = replacement.openPublished(app, 1)
        release.complete(Unit)
        assertNull(late.await())
        assertTrue(replacement.active(fresh))
        assertTrue(replacement.state!!.online)
    }

    private fun runtimeTest(test: suspend Fixture.() -> Unit) = runBlocking {
        val root = Files.createTempDirectory("generated-state-test").toFile()
        try {
            withTimeout(8000) { Fixture(root, this).test() }
        } finally {
            root.deleteRecursively()
        }
    }

    private class Fixture(val root: File, scope: CoroutineScope) : CoroutineScope by scope {
        val app = "gen-one"
        val identity = todoIdentity("fixture")
        val cache = GeneratedCache(root)
        val source = FixtureSource()
        var clock = 100L
        val change = """["propose",0,{"name":"example"}]"""

        fun runtime(token: String = "fixture", network: FixtureSource = source) =
            GeneratedRuntimeState(network, root, token, { true }, {}, monotonicMillis = { clock })

        fun propose(runtime: GeneratedRuntimeState, page: GeneratedOpenPage): GeneratedPortGate.Ticket {
            assertNotNull(runtime.command(page, change))
            return assertNotNull(runtime.state?.approval)
        }

        suspend fun assertRetryBlocked(runtime: GeneratedRuntimeState, page: GeneratedOpenPage, writes: Int) {
            assertEquals("retry_unavailable", assertFailsWith<GeneratedAppsException> { runtime.retry(page) }.code)
            assertEquals(writes, source.attempts.size)
            assertFalse(runtime.state!!.online)
        }
    }

    private class FixtureSource : GeneratedRuntimeSource {
        val attempts = mutableListOf<GeneratedUpdate>()
        var commits = 0
        var snapshot = GeneratedSnapshot(0, buildJsonObject {})
        var readFailure = false
        var reads = 0
        var readHook: suspend () -> Unit = {}
        var send: suspend (GeneratedUpdate) -> GeneratedSnapshot = ::commit

        fun commit(update: GeneratedUpdate): GeneratedSnapshot {
            if (snapshot.revision == update.baseRevision) {
                snapshot = GeneratedSnapshot(snapshot.revision + 1, update.document)
                commits++
            } else {
                assertEquals(update, attempts.first())
            }
            return snapshot
        }

        override suspend fun currentVersion(appId: String) = 1
        override suspend fun manifest(appId: String, version: Int): GeneratedManifest {
            fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).toHexString()
            val assets = GENERATED_FILES.mapIndexed { i, path ->
                val bytes = bytes(path)
                val mime = listOf("text/html", "text/css", "text/javascript")[i]
                GeneratedAsset(path, "$mime; charset=utf-8", bytes.size, digest(bytes))
            }
            val fingerprint = digest(assets.joinToString("") { it.sha256 }.encodeToByteArray())
            return GeneratedManifest(1, appId, version, fingerprint, "index.html", assets)
        }

        private fun bytes(path: String) = if (path == "index.html") "<p>Fixture</p>".encodeToByteArray() else byteArrayOf()

        override suspend fun asset(manifest: GeneratedManifest, path: String, draft: String?) = bytes(path)
        override suspend fun job(id: String): GeneratedJob = error("unused")
        override suspend fun read(appId: String): GeneratedSnapshot {
            reads++
            readHook()
            if (readFailure) throw GeneratedAppsException("connection_failed")
            return snapshot
        }
        override suspend fun write(appId: String, update: GeneratedUpdate, current: () -> Boolean): GeneratedSnapshot {
            assertTrue(current())
            attempts += update
            return send(update)
        }
    }
}
