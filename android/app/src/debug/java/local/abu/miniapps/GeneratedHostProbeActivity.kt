package local.abu.miniapps

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.webkit.WebView
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File
import java.util.UUID
import kotlin.coroutines.resume
import kotlinx.coroutines.*
import local.abu.data.*
import local.abu.platform.createHealthHttpClient

/** Debug-only fixed fixture runner. Ignores Intent data, uses a run-as supplied disposable token. */
class GeneratedHostProbeActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val http by lazy { createHealthHttpClient() }
    private var runtime: GeneratedRuntimeState? = null
    private var generation = 0
    private lateinit var root: LinearLayout
    private lateinit var status: TextView
    private lateinit var approve: Button
    private var manualApproval: CompletableDeferred<Unit>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        status = TextView(this).apply { text = "Testing isolated fixture" }
        approve = Button(this).apply {
            text = "Approve fixture write"
            isEnabled = false
            setOnClickListener { manualApproval?.complete(Unit) }
        }
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            fitsSystemWindows = true
            addView(status)
            addView(approve)
        }
        setContentView(root)
        if (Build.VERSION.SDK_INT < 28) { status.text = "Debug fixture requires API 28"; return }
        if (!suffixSet) {
            WebView.setDataDirectorySuffix("generated_host_probe")
            suffixSet = true
        }
        scope.launch {
            try { withTimeout(100_000) { runProbe() } }
            catch (failure: Exception) {
                if (!isActive) return@launch
                step("FAIL " + failure.javaClass.simpleName + " " + (failure.message ?: ""))
            }
        }
    }

    private fun step(text: String) { status.text = text; Log.i("AbuGenHostProbe", text) }

    private suspend fun runProbe() {
        val tokenFile = File(filesDir, "generated-host-probe.token")
        val dev = withContext(Dispatchers.IO) {
            require(tokenFile.isFile && tokenFile.length() in 32..128) { "fixture token unavailable" }
            tokenFile.readText().trim()
        }
        val client = GeneratedAppsClient(http)
        val id = UUID.randomUUID().toString().take(8)
        val app = "gen-probe-$id"
        val draft1 = "draft-one-$id"
        val draft2 = "draft-two-$id"
        val proposalId = "proposal-$id"
        val cache = File(cacheDir, "generated-host-probe")
        fun state(token: String = dev): GeneratedRuntimeState {
            val mark = ++generation
            return GeneratedRuntimeState(GeneratedRuntimeNetwork(client, token), cache, token, { mark == generation }, {}).also {
                runtime = it
            }
        }
        suspend fun ready(id: String): GeneratedJob {
            return withTimeout(8000) {
                while (true) {
                    val job = client.job(dev, id)
                    if (job.summary.status == "ready") return@withTimeout job
                    check(job.summary.status in setOf("queued", "running")) { "fixture generation failed" }
                    delay(100)
                }
                error("unreachable")
            }
        }
        fun mount(state: GeneratedRuntimeState, page: GeneratedOpenPage): GeneratedWebView =
            GeneratedPageHost.mount(this, state, page, dark = true) { step("HOST FAILURE $it") }.also {
                root.addView(it, LinearLayout.LayoutParams(-1, 0, 1f))
            }
        client.createDraft(dev, DraftRequest(draft1, "abu", app, "Generated Counter", "fixture initial", 0))
        val initial = ready(draft1)
        var current = state()
        val preview = current.openPreview(draft1)
        var view = mount(current, preview)
        awaitDOM(view, "V1|preview|dark|0|")
        check(current.state?.approval == null)
        view.release()
        check(current.portCommand(preview, """["read"]""") == null)
        client.publish(dev, draft1, initial.manifest!!.hash, 0, 0)
        var published = current.openPublished(app, 1)
        view = mount(current, published)
        awaitDOM(view, "V1|online|dark|0|")
        step("CHECK initial three-file render; tap Refresh after update")
        val session = client.openSession(dev, app)
        client.write(session, GeneratedUpdate("external", 0, parseGeneratedDocument("""{"count":7}""")))
        awaitDOM(view, "V1|online|dark|1|7")
        step("CHECK live read; tap Propose to test native approval")
        withTimeout(20_000) { while (current.state?.approval == null) delay(75) }
        val ticket = requireNotNull(current.state?.approval)
        check(client.data(session).revision == 1L)
        manualApproval = CompletableDeferred()
        approve.isEnabled = true
        step("CHECK pending; tap native Approve fixture write")
        withTimeout(20_000) { requireNotNull(manualApproval).await() }
        approve.isEnabled = false
        manualApproval = null
        current.confirm(published, ticket)
        awaitDOM(view, "V1|online|dark|2|8")
        step("CHECK committed through native confirmation")
        client.propose(dev, app, ProposalRequest(proposalId, "abu", "fixture proposal", 2))
        check(ready(proposalId).proposal?.baseRevision == 2L)
        check(client.data(session).revision == 2L)
        client.createDraft(dev, DraftRequest(draft2, "abu", app, "Generated Counter", "fixture second", 1))
        client.publish(dev, draft2, ready(draft2).manifest!!.hash, 1, 1)
        published = current.openPublished(app, 2)
        view = mount(current, published)
        awaitDOM(view, "V2|online|dark|2|8")
        client.activate(dev, app, "rollback", 1, 2)
        val oldRuntime = current
        val oldPage = published
        oldRuntime.retire()
        val alternateIdentity = state("local-fixture-no-access")
        check(runCatching { alternateIdentity.openPublished(app, online = false) }.exceptionOrNull()
            .let { it is GeneratedAppsException && it.code == "no_offline_bundle" })
        check(oldRuntime.portCommand(oldPage, """["read"]""") == null)
        alternateIdentity.retire()
        current = state()
        view = mount(current, current.openPublished(app))
        awaitDOM(view, "V1|online|dark|2|8")
        check(client.data(session).revision == 2L)
        view = mount(current, current.openPublished(app, online = false))
        awaitDOM(view, "V1|offline|dark|2|8")
        step("PASS offline cache; rollback retained data; prior identity/page revoked")
    }

    private suspend fun awaitDOM(view: GeneratedWebView, expected: String) = withTimeout(22_000) {
        while (true) {
            val raw = suspendCancellableCoroutine<String> { cont ->
                view.evaluateJavascript("document.querySelector('#result')?.textContent+'|'+document.documentElement.dataset.checked") {
                    if (cont.isActive) cont.resume(it ?: "")
                }
            }
            if (raw.contains(expected) && raw.contains("guards-ok")) return@withTimeout
            delay(200)
        }
    }

    override fun onDestroy() {
        runtime?.retire()
        scope.cancel()
        http.close()
        super.onDestroy()
    }

    private companion object { var suffixSet = false }
}
