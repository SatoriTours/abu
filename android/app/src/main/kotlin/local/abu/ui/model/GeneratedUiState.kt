package local.abu.ui.model

import androidx.compose.runtime.*
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import local.abu.data.*
import local.abu.miniapps.*

internal enum class GenerationAction { NEW, UPDATE, SUGGEST }
internal data class GenerationForm(val action: GenerationAction, val botId: String?, val target: GeneratedApp? = null,
    val name: String = "", val request: String = "")
internal sealed interface GeneratedRoute {
    data object Center : GeneratedRoute
    data class Form(val form: GenerationForm) : GeneratedRoute
    data class Job(val id: String) : GeneratedRoute
    data class Versions(val app: GeneratedApp) : GeneratedRoute
    data class View(val appId: String, val title: String, val previewJob: String? = null, val cached: Boolean = false) : GeneratedRoute
}

internal class GeneratedUiState(
    private val client: GeneratedAppsClient,
    val token: String,
    root: File,
    private val offlineEntries: GeneratedOfflineEntries,
) {
    private var alive = true
    private val cache = GeneratedCache(root)
    private val identity = todoIdentity(token)
    var route: GeneratedRoute by mutableStateOf(GeneratedRoute.Center)
        private set
    var page by mutableStateOf<GeneratedPageState?>(null)
        private set
    var apps by mutableStateOf(emptyList<GeneratedApp>())
        private set
    var cached by mutableStateOf(emptyList<GeneratedApp>())
        private set
    var jobs by mutableStateOf(emptyList<GeneratedJobSummary>())
        private set
    var job by mutableStateOf<GeneratedJob?>(null)
        private set
    var modelConfigured by mutableStateOf<Boolean?>(null)
        private set
    var busy by mutableStateOf(false)
        private set
    var notice by mutableStateOf<String?>(null)
        private set
    var renderingFailed by mutableStateOf(false)
        private set
    var denial by mutableStateOf(false)
        private set
    var discardedDocument by mutableStateOf<String?>(null)
        private set
    var previewSeen by mutableStateOf(false)
        private set
    val retryGate = GeneratedRetryGate()
    private val themeRemount = GeneratedThemeRemount()
    val canGoBack get() = (!busy || themeRemount.running) && !generation.busy && !publication.busy
    val hasToken get() = token.isNotBlank()
    val runtime = GeneratedRuntimeState(GeneratedRuntimeNetwork(client, token), root, token,
        identityStillActive = { alive }, changed = { page = it })
    val generation = GeneratedRequests { request ->
        when (request) {
            is GenerationRequest.Draft -> client.createDraft(token, request.input)
            is GenerationRequest.Proposal -> client.propose(token, request.appId, request.input)
        }.also { current() }.summary
    }
    val publication = GeneratedPublications { request ->
        when (request) {
            is PublicationIntent.Publish -> client.publish(token, request.jobId, request.hash, request.version, request.revision)
            is PublicationIntent.Activate -> client.activate(token, request.appId, request.id, request.version, request.revision)
        }.also { current(); require(it.appId == request.appId) }
    }

    fun retire() {
        alive = false
        themeRemount.cancel()
        generation.retire()
        publication.retire()
        retryGate.lock()
        runtime.retire()
    }

    fun center() {
        if (busy || generation.busy || publication.busy) return
        leavePage()
        route = GeneratedRoute.Center
        notice = null
    }

    fun back() {
        if (!canGoBack) return
        val from = route
        val original = (from as? GeneratedRoute.View)?.previewJob
        leavePage()
        route = if (original != null) GeneratedRoute.Job(original) else GeneratedRoute.Center
        notice = null
    }

    fun start(action: GenerationAction, botId: String?, target: GeneratedApp? = null) {
        if (!hasToken || busy || generation.pending != null) return
        leavePage()
        notice = null
        route = GeneratedRoute.Form(GenerationForm(action, botId, target, name = target?.name.orEmpty()))
    }

    fun redo(previous: GeneratedJob) {
        val summary = previous.summary
        val action = when {
            summary.kind == "proposal" -> GenerationAction.SUGGEST
            previous.baseVersion != 0 -> GenerationAction.UPDATE
            else -> GenerationAction.NEW
        }
        val app = apps.firstOrNull { it.id == summary.appId }
        if (action != GenerationAction.NEW && app == null) {
            notice = "目标应用暂不可用。请先返回刷新应用中心。"
            return
        }
        start(action,summary.botId,app)
        val form = (route as? GeneratedRoute.Form)?.form ?: return
        setForm(form.copy(name = summary.name ?: form.name, request = previous.request))
    }

    fun setForm(value: GenerationForm) {
        if (route is GeneratedRoute.Form && generation.pending == null && !busy && !generation.busy) {
            route = GeneratedRoute.Form(value)
        }
    }

    fun canGenerate(form: GenerationForm) = modelConfigured != false && !busy && !generation.busy &&
        generation.pending == null && form.botId != null && validText(form.request,4000) &&
        (form.action == GenerationAction.NEW && validText(form.name,80) || form.target != null)

    suspend fun refreshCenter() = operation {
        try {
            val list = client.catalog(token)
            current()
            denial = false
            apps = list
            val previous = withContext(Dispatchers.IO) { offlineEntries.load(identity) }
            current()
            cached = previous.filter { old -> list.any { it.id == old.id } }
        } catch (e: GeneratedAppsException) {
            current()
            if (e.code != "unauthorized" && e.statusCode != 401) {
                cached = withContext(Dispatchers.IO) { offlineEntries.load(identity) }
            }
            throw e
        }
        val saved = client.jobs(token)
        current()
        jobs = saved.jobs
        modelConfigured = saved.modelConfigured
    }

    suspend fun submit(form: GenerationForm) = operation {
        if (generation.pending != null || !inputsValid(form)) return@operation
        val bot = requireNotNull(form.botId)
        val app = form.target
        val id = uuid()
        val request = when (form.action) {
            GenerationAction.NEW -> GenerationRequest.Draft(DraftRequest(id, bot, "gen-${uuid()}", form.name, form.request, 0))
            GenerationAction.UPDATE -> {
                requireNotNull(app)
                checkVersionChange(app.id)
                GenerationRequest.Draft(DraftRequest(id, bot, app.id, app.name, form.request, app.currentVersion))
            }
            GenerationAction.SUGGEST -> {
                requireNotNull(app)
                checkVersionChange(app.id)
                val data = client.data(client.openSession(token, app.id))
                current()
                GenerationRequest.Proposal(app.id, ProposalRequest(id, bot, form.request, data.revision))
            }
        }
        generation.send(request)?.let { jumpToJob(it) }
    }

    suspend fun retryGeneration() = operation { generation.retry()?.let { jumpToJob(it) } }

    private fun inputsValid(form: GenerationForm) = modelConfigured != false && form.botId != null &&
        validText(form.request,4000) && (form.action != GenerationAction.NEW || validText(form.name,80))

    suspend fun seeJob(id: String) = operation {
        leavePage()
        route = GeneratedRoute.Job(id)
        if (job?.summary?.id != id) job = null
        loadJob(id)
    }

    suspend fun cancel(id: String) = operation {
        val result = client.cancel(token, id)
        current()
        updateJob(result)
    }

    private suspend fun jumpToJob(summary: GeneratedJobSummary) {
        job = null
        replaceSummary(summary)
        route = GeneratedRoute.Job(summary.id)
        loadJob(summary.id)
    }

    private suspend fun loadJob(id: String) {
        val result = client.job(token, id)
        current()
        updateJob(result)
    }

    private fun updateJob(value: GeneratedJob) {
        job = value
        replaceSummary(value.summary)
    }

    private fun replaceSummary(value: GeneratedJobSummary) {
        jobs = if (jobs.any { it.id == value.id }) jobs.map { if (it.id == value.id) value else it }
            else (jobs + value).takeLast(300)
    }

    fun manage(app: GeneratedApp) {
        if (busy) return
        leavePage()
        route = GeneratedRoute.Versions(app)
        notice = null
    }

    suspend fun preview(job: GeneratedJob) = operation {
        previewSeen = false
        renderingFailed = false
        runtime.openPreview(job.summary.id)
        current()
        route = GeneratedRoute.View(job.summary.appId, job.summary.name ?: "生成应用", previewJob = job.summary.id)
    }

    fun acceptPreview(page: GeneratedOpenPage) {
        if (runtime.active(page) && (route as? GeneratedRoute.View)?.previewJob != null) previewSeen = true
    }

    suspend fun open(app: GeneratedApp, offline: Boolean = false) = operation { openApp(app, offline) }

    private suspend fun openApp(app: GeneratedApp, offline: Boolean = false, expected: Int? = null) {
        retryGate.lock()
        renderingFailed = false
        val lockedVersion = withContext(Dispatchers.IO) {
            if (cache.journal(identity,app.id).pending != null) {
                cache.selected(identity,app.id) ?: throw GeneratedAppsException("no_offline_bundle")
            } else null
        }
        current()
        if (lockedVersion != null && expected != null && lockedVersion != expected) {
            throw GeneratedAppsException("pending_blocks_version")
        }
        val opened = runtime.openPublished(app.id, version = lockedVersion ?: expected, online = !offline)
        current()
        val actual = opened.bundle.manifest.version
        route = GeneratedRoute.View(app.id, app.name, cached = offline)
        if (actual != app.currentVersion) notice = "目前使用已验证的资源 v$actual；并未替换为后台版本。"
        rememberOffline(app)
    }

    private suspend fun rememberOffline(app: GeneratedApp) {
        val entries = withContext(Dispatchers.IO) {
            if (cache.selected(identity,app.id) == null) return@withContext offlineEntries.load(identity)
            offlineEntries.save(identity, app)
        }
        current()
        cached = entries
    }

    suspend fun preparePublication(job: GeneratedJob): PublicationIntent.Publish? = operation {
        val view = route as? GeneratedRoute.View ?: return@operation null
        val id = job.summary.appId
        require(view.previewJob == job.summary.id && previewSeen && runtime.active(requireNotNull(page).page))
        checkVersionChange(id)
        val top = client.catalog(token).singleOrNull { it.id == id }
        current()
        val base = top?.currentVersion ?: 0
        if (base != job.baseVersion || job.publishedVersion != null) {
            throw GeneratedAppsException("version_conflict")
        }
        PublicationIntent.Publish(id, job.summary.id, requireNotNull(job.manifest).hash, base, top?.revision ?: 0)
    }

    suspend fun switchVersion(app: GeneratedApp, version: Int) = operation {
        checkVersionChange(app.id)
        val manifest = client.manifest(token, app.id, version)
        val bytes = GENERATED_FILES.associateWith { client.asset(token,manifest,it) }
        val bundle = GeneratedBundle(manifest,bytes)
        current()
        // Preflight a complete bundle before changing the remote active version or local selection.
        withContext(Dispatchers.IO) { cache.store(identity,bundle) }
        current()
        val request = PublicationIntent.Activate(app.id,uuid(),version,app.revision)
        publication.send(request)?.let { applyReceipt(it) }
    }

    suspend fun publish(intent: PublicationIntent.Publish) = operation {
        checkVersionChange(intent.appId)
        val live = page
        if (!previewSeen || live == null || !runtime.active(live.page) ||
            (route as? GeneratedRoute.View)?.previewJob != intent.jobId ||
            live.page.bundle.manifest.hash != intent.hash) throw GeneratedAppsException("approval_not_current")
        publication.send(intent)?.let { applyReceipt(it) }
    }

    suspend fun retryPublication() = operation {
        val pending = publication.pending ?: return@operation
        checkVersionChange(pending.appId)
        publication.retry()?.let { applyReceipt(it) }
    }

    private suspend fun applyReceipt(receipt: GeneratedPublication) {
        val catalog = client.catalog(token)
        current()
        apps = catalog
        val app = catalog.single { it.id == receipt.appId }
        // An old idempotent receipt must never replace a more recently activated version.
        if (app.currentVersion != receipt.version || app.revision != receipt.revision) {
            leavePage()
            route = GeneratedRoute.Versions(app)
            notice = "后台已继续更新，未激活较早的回执版本。"
            return
        }
        try {
            openApp(app, expected = receipt.version)
            notice = "已激活资源 v${receipt.version}，数据未回退。"
        } catch (e: GeneratedAppsException) {
            if (e.code == "unauthorized" || e.statusCode == 401) throw e
            current()
            route = GeneratedRoute.Versions(app)
            notice = "后台已确认版本，设备尚未下载验证。可重新打开；旧缓存没有替换。"
        }
    }

    suspend fun reviewProposal(job: GeneratedJob) = operation {
        val app = client.catalog(token).singleOrNull { it.id == job.summary.appId }
            ?: throw GeneratedAppsException("generated_not_found")
        current()
        openApp(app)
        val current = requireNotNull(page)
        if (current.pending != null) throw GeneratedAppsException("retry_unavailable")
        if (runtime.reviewModelProposal(current.page,job.summary.id) == null) {
            notice = "建议已过期、当前不可写或修订不一致，请刷新后重新发起建议。"
        }
    }

    suspend fun refreshData() = operation {
        val visible = route as? GeneratedRoute.View ?: return@operation
        if (visible.previewJob != null) return@operation
        val old = page ?: return@operation
        if (visible.cached) {
            val app = client.catalog(token).singleOrNull { it.id == visible.appId }
                ?: throw GeneratedAppsException("generated_not_found")
            current()
            // An explicit online refresh reopens the same verified resource, not the latest version.
            openApp(app, expected = old.page.bundle.manifest.version)
        }
        val fresh = requireNotNull(page)
        retryGate.lock()
        runtime.refresh(fresh.page)
        current()
        retryGate.unlock(fresh.page)
    }

    fun requestTheme(
        scope: CoroutineScope, expected: GeneratedRoute.View, prior: GeneratedOpenPage,
        stillWanted: () -> Boolean,
    ) {
        fun matches() = alive && !busy && !renderingFailed && route === expected &&
            page?.page === prior && page?.pending == null && page?.approval == null &&
            publication.pending == null && runtime.active(prior) && stillWanted()

        themeRemount.request(scope, ::matches) {
            operation {
                remountForTheme(expected, prior)
            }
        }
    }

    private suspend fun remountForTheme(expected: GeneratedRoute.View, prior: GeneratedOpenPage) {
        if (expected.previewJob != null) {
            previewSeen = false
            runtime.openPreview(expected.previewJob)
        } else {
            val version = prior.bundle.manifest.version
            runtime.openPublished(expected.appId, version, online = !expected.cached)
        }
        current()
        retryGate.lock()
    }

    suspend fun confirm(page: GeneratedOpenPage, approval: GeneratedPortGate.Ticket) = operation {
        retryGate.lock()
        runtime.confirm(page,approval)
        current()
        notice = "数据已保存。"
    }
    fun dismiss(page: GeneratedOpenPage, approval: GeneratedPortGate.Ticket) { runtime.dismiss(page,approval) }

    suspend fun retryData(page: GeneratedOpenPage) = operation {
        if (!retryGate.allows(page)) return@operation
        retryGate.lock()
        runtime.retry(page)
        current()
        notice = "已确认原数据变更。"
    }

    suspend fun discard(page: GeneratedOpenPage) = operation {
        if (!retryGate.allows(page) || this.page?.pending?.conflict != true) return@operation
        val previous = runtime.discardPending(page)
        current()
        discardedDocument = previous?.document?.toString()
        retryGate.lock()
        notice = "已放弃原提交，草稿可供查看。请刷新后重新提交变更。"
    }

    fun rendererFailed(expected: GeneratedRoute.View) {
        if (!alive || route != expected) return
        retryGate.lock()
        previewSeen = false
        renderingFailed = true
        notice = "页面已退出。请返回应用中心；不会自动重开。"
    }

    private fun leavePage() {
        themeRemount.cancel()
        runtime.close()
        retryGate.lock()
        previewSeen = false
        renderingFailed = false
        discardedDocument = null
    }

    private suspend fun checkVersionChange(appId: String) {
        val exists = withContext(Dispatchers.IO) { cache.journal(identity,appId).pending != null }
        current()
        if (exists) throw GeneratedAppsException("pending_blocks_version")
    }

    private suspend fun current() {
        currentCoroutineContext().ensureActive()
        if (!alive) throw CancellationException("Retired generated app identity")
    }

    private suspend fun <T> operation(block: suspend () -> T): T? {
        if (!alive || !hasToken || busy) return null
        busy = true
        notice = null
        return try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            current()
            val api = e as? GeneratedAppsException
            if (api?.code == "unauthorized" || api?.statusCode == 401 && api.code != "invalid_app_token") {
                leavePage()
                route = GeneratedRoute.Center
                denial = true
                apps = emptyList()
                cached = emptyList()
                jobs = emptyList()
                job = null
            }
            notice = if (api?.code == "pending_blocks_version") "此应用有未确认的数据变更。请先打开应用处理，再修改或切换版本。"
                else if (api?.code == "retry_unavailable") "请先在应用内刷新，再处理未确认的数据变更。"
                else generatedError(e)
            null
        } finally {
            busy = false
        }
    }

    private fun uuid() = UUID.randomUUID().toString()
}

private fun validText(value: String, limit: Int) =
    value.isNotBlank() && value.codePointCount(0,value.length) in 1..limit &&
        value.codePoints().noneMatch { it in 0xd800..0xdfff }
