package local.abu

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import local.abu.data.*
import local.abu.miniapps.GeneratedRuntimeSource
import local.abu.miniapps.GeneratedRuntimeState
import java.nio.file.Files
import java.security.MessageDigest
import kotlinx.serialization.json.buildJsonObject
import local.abu.ui.model.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class GeneratedEntryTest {
    @Test fun connectionLossResubmitsExactRequestWithoutReplacingIdsOrRequest() = runTest {
        val calls = mutableListOf<GenerationRequest>()
        val state = GeneratedRequests {
            calls += it
            if (calls.size == 1) throw java.io.IOException("lost")
            summary(it as GenerationRequest.Draft)
        }
        val input = GenerationRequest.Draft(DraftRequest("job","bot","gen-app","工具","生成两列",0))
        assertNull(state.send(input))
        assertEquals(input, state.pending)
        assertNull(state.send(input.copy(input.input.copy(request = "other"))))
        assertEquals("job", state.retry()?.id)
        assertEquals(listOf<GenerationRequest>(input,input), calls)
        assertNull(state.pending)
    }

    @Test fun retiringIdentityDiscardsAReplyEvenIfNetworkReturnsIt() = runTest {
        val response = CompletableDeferred<GeneratedJobSummary>()
        val state = GeneratedRequests { response.await() }
        val input = GenerationRequest.Draft(DraftRequest("job","bot","gen-app","工具","需求",0))
        var saved: GeneratedJobSummary? = null
        val job = launch { saved = state.send(input) }
        runCurrent()
        state.retire()
        response.complete(summary(input))
        job.join()
        assertNull(saved)
        assertNull(state.pending)
    }

    @Test fun explicitRefreshIsRequiredAndTheGateIsBoundToThePage() {
        val gate = GeneratedRetryGate()
        val old = Any()
        val newer = Any()
        assertFalse(gate.allows(old))
        gate.unlock(old)
        assertTrue(gate.allows(old))
        assertFalse(gate.allows(newer))
        gate.lock()
        assertFalse(gate.allows(old))
    }

    @Test fun publicationRetryKeepsFrozenActivationRevision() = runTest {
        val calls = mutableListOf<PublicationIntent>()
        val state = GeneratedPublications {
            calls += it
            if (calls.size == 1) throw java.io.IOException("lost")
            GeneratedPublication("gen-app",2,4)
        }
        val payload = PublicationIntent.Publish("gen-app","job","a".repeat(64),1,3)
        assertNull(state.send(payload))
        assertEquals(payload,state.pending)
        assertNull(state.send(payload.copy(revision = 4)))
        assertEquals(2,state.retry()?.version)
        assertEquals(listOf<PublicationIntent>(payload,payload),calls)
    }

    @Test fun themeWorkSurvivesTheTriggerButIsRevokedWhenLeavingThePage() = runTest {
        val remount = GeneratedThemeRemount()
        val first = CompletableDeferred<Unit>()
        val second = CompletableDeferred<Unit>()
        var opened = 0
        val trigger = launch {
            remount.request(this@runTest, stillWanted = { true }) { first.await(); opened++ }
        }
        runCurrent()
        trigger.cancel()
        first.complete(Unit)
        runCurrent()
        assertEquals(1, opened)
        remount.request(this, stillWanted = { true }) { second.await(); opened++ }
        runCurrent()
        assertTrue(remount.running)
        remount.cancel()
        second.complete(Unit)
        runCurrent()
        assertFalse(remount.running)
        assertEquals(1, opened)
        remount.request(this, stillWanted = { false }) { opened++ }
        runCurrent()
        assertEquals(1, opened)
    }

    @Test fun suggestionForAnotherApplicationCannotObtainThisPagesApproval() = runTest {
        val root = Files.createTempDirectory("generated-proposal-scope").toFile()
        fun bytes(path: String) = if (path == "index.html") "<p>Fixture</p>".encodeToByteArray() else byteArrayOf()
        fun sha256(input: ByteArray) = MessageDigest.getInstance("SHA-256").digest(input).toHexString()
        val doc = buildJsonObject {}
        val source = object : GeneratedRuntimeSource {
            override suspend fun currentVersion(appId: String) = 1
            override suspend fun manifest(appId: String, version: Int): GeneratedManifest {
                val assets = GENERATED_FILES.mapIndexed { i, path ->
                    val mime = listOf("text/html", "text/css", "text/javascript")[i]
                    GeneratedAsset(path, "$mime; charset=utf-8", bytes(path).size, sha256(bytes(path)))
                }
                return GeneratedManifest(1, appId, version, sha256(assets.joinToString("") { it.sha256 }.encodeToByteArray()),
                    "index.html", assets)
            }
            override suspend fun asset(manifest: GeneratedManifest, path: String, draft: String?) = bytes(path)
            override suspend fun job(id: String) = GeneratedJob(
                GeneratedJobSummary(id,"abu","gen-other","proposal","ready",null),
                "add a record", 0, null, null, GeneratedProposal(0,doc), null,
            )
            override suspend fun read(appId: String) = GeneratedSnapshot(0,doc)
            override suspend fun write(appId: String, update: GeneratedUpdate, current: () -> Boolean): GeneratedSnapshot =
                error("Cross-app approval must not write")
        }
        try {
            val state = GeneratedRuntimeState(source,root,"test", { true }, {})
            val page = state.openPublished("gen-own")
            val denial = assertFailsWith<GeneratedAppsException> { state.reviewModelProposal(page,"foreign-job") }
            assertEquals("proposal_scope_mismatch",denial.code)
            assertNull(state.state?.approval)
            assertTrue(state.active(page))
            state.retire()
        } finally {
            root.deleteRecursively()
        }
    }

    private fun summary(req: GenerationRequest.Draft) = GeneratedJobSummary(
        req.input.id, req.input.botId, req.input.appId, "draft","queued",req.input.name
    )
}
