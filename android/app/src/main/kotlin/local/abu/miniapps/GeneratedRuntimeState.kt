package local.abu.miniapps

import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import local.abu.data.*

internal class GeneratedOpenPage internal constructor(
    val bundle: GeneratedBundle,
    val origin: GeneratedOrigin,
    internal val ticket: GeneratedPortGate.Page,
    internal val pageReads: Boolean = false,
)

internal data class GeneratedPageState(
    val page: GeneratedOpenPage,
    val confirmed: GeneratedTimed?,
    val online: Boolean,
    val pending: GeneratedPending?,
    val approval: GeneratedPortGate.Ticket?,
) {
    fun webState(error: String? = null): String = encodeGeneratedPageState(
        confirmed?.snapshot ?: GeneratedSnapshot(0, buildJsonObject {}),
        when {
            page.ticket.preview -> "preview"
            confirmed == null -> "unavailable"
            !online -> "offline"
            pending != null -> "pending"
            else -> "online"
        },
        confirmed?.millis,
        error,
    )
}

internal interface GeneratedRuntimeSource {
    suspend fun currentVersion(appId: String): Int
    suspend fun manifest(appId: String, version: Int): GeneratedManifest
    suspend fun asset(manifest: GeneratedManifest, path: String, draft: String?): ByteArray
    suspend fun job(id: String): GeneratedJob
    suspend fun read(appId: String): GeneratedSnapshot
    suspend fun write(appId: String, update: GeneratedUpdate, current: () -> Boolean): GeneratedSnapshot
}

internal class GeneratedRuntimeNetwork(private val client: GeneratedAppsClient, private val dev: String) : GeneratedRuntimeSource {
    override suspend fun currentVersion(appId: String): Int = client.catalog(dev).singleOrNull { it.id == appId }?.currentVersion
        ?: throw GeneratedAppsException("generated_not_found")
    override suspend fun manifest(appId: String, version: Int) = client.manifest(dev, appId, version)
    override suspend fun asset(manifest: GeneratedManifest, path: String, draft: String?) = client.asset(dev, manifest, path, draft)
    override suspend fun job(id: String) = client.job(dev, id)
    override suspend fun read(appId: String) = client.data(client.openSession(dev, appId))
    override suspend fun write(appId: String, update: GeneratedUpdate, current: () -> Boolean): GeneratedSnapshot {
        val session = client.openSession(dev, appId)
        if (!current()) throw GeneratedAppsException("stale_page")
        return client.write(session, update)
    }
}

/** One instance per native identity generation. Call on the main thread and retire on identity change. */
internal class GeneratedRuntimeState(
    private val network: GeneratedRuntimeSource,
    cacheRoot: File,
    devToken: String,
    private val identityStillActive: () -> Boolean,
    private val changed: (GeneratedPageState?) -> Unit,
    private val wallMillis: () -> Long = System::currentTimeMillis,
    monotonicMillis: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private val cache = GeneratedCache(cacheRoot)
    private val identity = todoIdentity(devToken)
    private val gate = GeneratedPortGate(monotonicMillis)
    private val serial = Mutex()
    private var epoch = 0L
    private var retired = false
    private var mounted: Mount? = null

    private class Mount(
        val update: (GeneratedPageState) -> Unit,
        val revoke: () -> Unit,
        val onDenied: (String) -> Unit,
    )

    internal fun bind(
        page: GeneratedOpenPage, update: (GeneratedPageState) -> Unit, revoke: () -> Unit,
        onDenied: (String) -> Unit = {},
    ) {
        requirePage(page)
        check(mounted == null) { "Generated page already has a host" }
        mounted = Mount(update, revoke, onDenied)
        update(requirePage(page))
    }
    var state: GeneratedPageState? = null
        private set

    fun close() {
        epoch++
        gate.revoke()
        val old = mounted
        mounted = null
        expose(null)
        old?.revoke?.invoke()
    }

    fun retire() {
        retired = true
        close()
    }

    private fun expose(value: GeneratedPageState?) {
        state = value
        changed(value)
        if (value != null && state === value) mounted?.update?.invoke(value)
    }

    private fun alive(mark: Long) = !retired && identityStillActive() && mark == epoch
    fun active(page: GeneratedOpenPage) = alive(epoch) && state?.page === page && gate.active(page.ticket)
    private fun verify(mark: Long) { if (!alive(mark)) throw GeneratedAppsException("stale_page") }

    suspend fun openPreview(jobId: String): GeneratedOpenPage {
        close()
        val mark = epoch
        return serial.withLock {
            verify(mark)
            val job = network.job(jobId)
            verify(mark)
            val manifest = requireNotNull(job.manifest)
            require(job.summary.id == jobId && job.summary.kind == "draft" && job.summary.status == "ready" &&
                manifest.version == 0 && manifest.appId == job.summary.appId)
            val bundle = download(manifest, job.summary.id)
            verify(mark)
            val page = GeneratedOpenPage(bundle, GeneratedOrigin.preview(manifest.appId, job.summary.id),
                gate.attach(manifest.appId, preview = true))
            expose(GeneratedPageState(page, null, false, null, null))
            page
        }
    }

    // With no explicit version, an unusable download leaves the previous verified selection usable.
    suspend fun openPublished(appId: String, version: Int? = null, online: Boolean = true): GeneratedOpenPage {
        close()
        val mark = epoch
        return serial.withLock {
            verify(mark)
            require(validGeneratedAppId(appId) && (version == null || version in 1..16))
            val bundle = if (online) try {
                val v = version ?: network.currentVersion(appId)
                val m = network.manifest(appId, v)
                require(m.appId == appId && m.version == v && verifyGeneratedManifest(m))
                val downloaded = withContext(Dispatchers.IO) { cache.bundle(identity, appId, v)?.takeIf { it.manifest == m } }
                    ?: download(m, null)
                verify(mark)
                withContext(Dispatchers.IO) {
                    cache.store(identity, downloaded)
                    cache.select(identity, appId, v)
                }
                downloaded
            } catch (e: GeneratedAppsException) {
                if (e.isAccessDenied() || e.code == "stale_page") throw e
                cached(appId, version) ?: throw e
            } else cached(appId, version) ?: throw GeneratedAppsException("no_offline_bundle")
            verify(mark)
            val journal = withContext(Dispatchers.IO) { cache.journal(identity, appId) }
            verify(mark)
            val page = GeneratedOpenPage(bundle, GeneratedOrigin.published(appId, bundle.manifest.version), gate.attach(appId, preview = false), pageReads = online)
            // Start read-only. Network access is enabled only after reading a scoped live snapshot.
            expose(GeneratedPageState(page, journal.timed, false, journal.pending, null))
            if (online) {
                try { refreshLocked(page, mark) }
                catch (e: GeneratedAppsException) {
                    if (e.isAccessDenied() || e.code == "stale_page") throw e
                }
            }
            verify(mark)
            page
        }
    }

    private suspend fun cached(appId: String, version: Int?): GeneratedBundle? = withContext(Dispatchers.IO) {
        val v = version ?: cache.selected(identity, appId) ?: return@withContext null
        cache.bundle(identity, appId, v)
    }

    private suspend fun download(manifest: GeneratedManifest, draft: String?): GeneratedBundle =
        GeneratedBundle(manifest, GENERATED_FILES.associateWith { network.asset(manifest, it, draft) })

    suspend fun refresh(page: GeneratedOpenPage) = serial.withLock { refreshLocked(page, epoch) }

    private suspend fun refreshLocked(page: GeneratedOpenPage, mark: Long) {
        val old = requirePage(page)
        require(!page.ticket.preview)
        gate.discard()
        expose(old.copy(online = false, approval = null))
        val data = readAuthorized(page, mark, fromPort = false)
        verify(mark)
        val current = if (old.pending != null && !old.pending.conflict) old.confirmed
            else GeneratedTimed(data, wallMillis())
        val next = old.copy(confirmed = current, approval = null)
        persist(next, mark)
        expose(next.copy(online = true))
    }

    /** Port reads use the same bounded Go data API, never the model or native retry action. */
    suspend fun portCommand(page: GeneratedOpenPage, raw: String?): String? {
        if (!active(page)) return null
        clearExpiredApproval(page)
        val old = state?.takeIf { active(page) } ?: return null
        if (!gate.allow(page.ticket)) return old.webState("rate_limited")
        return when (val command = parseGeneratedCommand(raw)) {
            null -> old.webState("invalid_message")
            GeneratedBridgeCommand.Read -> readFromPage(page, old)
            is GeneratedBridgeCommand.Propose -> {
                if (serial.isLocked) return old.webState("busy")
                if (propose(page, command) == null) state?.takeIf { active(page) }?.webState("proposal_unavailable")
                else state?.takeIf { active(page) }?.webState()
            }
        }
    }

    private suspend fun readFromPage(page: GeneratedOpenPage, old: GeneratedPageState): String? {
        if (page.ticket.preview) return old.webState()
        if (!page.pageReads) return old.webState("offline")
        if (!serial.tryLock()) return old.webState("busy")
        val mark = epoch
        try {
            val data = readAuthorized(page, mark, fromPort = true)
            verify(mark)
            val current = requirePage(page)
            // Do not rebase or unlock an unconfirmed write from an untrusted page read.
            if (current.pending != null) return current.webState()
            val same = current.confirmed?.snapshot == data
            val next = current.copy(confirmed = GeneratedTimed(data, wallMillis()), online = true,
                approval = current.approval.takeIf { same })
            persist(next, mark)
            if (!same) gate.discard()
            expose(next)
            return next.takeIf { active(page) }?.webState()
        } catch (e: GeneratedAppsException) {
            return if (e.isAccessDenied()) null else pageReadUnavailable(page)
        } catch (_: IOException) {
            return pageReadUnavailable(page)
        } finally {
            serial.unlock()
        }
    }

    private suspend fun readAuthorized(page: GeneratedOpenPage, mark: Long, fromPort: Boolean): GeneratedSnapshot =
        try {
            network.read(page.ticket.appId)
        } catch (e: GeneratedAppsException) {
            currentCoroutineContext().ensureActive()
            if (e.isAccessDenied() && alive(mark) && active(page)) {
                val notify = mounted?.onDenied
                close()
                if (fromPort && epoch == mark + 1 && !retired && identityStillActive()) notify?.invoke(e.code)
            }
            throw e
        }

    private fun GeneratedAppsException.isAccessDenied() =
        statusCode == 401 || statusCode == 403 ||
            code in setOf("unauthorized", "invalid_app_token", "app_scope_mismatch")

    private fun pageReadUnavailable(page: GeneratedOpenPage): String? {
        if (!active(page)) return null
        gate.discard()
        val next = requirePage(page).copy(online = false, approval = null)
        expose(next)
        return next.takeIf { active(page) }?.webState("read_unavailable")
    }

    // Pure native test seam; the WebView uses portCommand, never this cache-only path.
    fun command(page: GeneratedOpenPage, raw: String?): String? {
        if (!active(page)) return null
        clearExpiredApproval(page)
        if (!gate.allow(page.ticket)) return null
        val command = parseGeneratedCommand(raw) ?: return null
        val current = requireNotNull(state)
        if (command is GeneratedBridgeCommand.Propose) {
            propose(page, command)
        }
        return state?.takeIf { it.page === current.page }?.webState()
    }

    // The supplied job must come from the same native session. Refresh it under the current token.
    suspend fun reviewModelProposal(page: GeneratedOpenPage, jobId: String): GeneratedPortGate.Ticket? =
        serial.withLock {
            val mark = epoch
            clearExpiredApproval(page)
            verify(mark)
            val job = network.job(jobId)
            verify(mark)
            if (job.summary.id != jobId || job.summary.kind != "proposal" || job.summary.status != "ready" || job.summary.appId != page.ticket.appId) {
                throw GeneratedAppsException("proposal_scope_mismatch")
            }
            val proposal = requireNotNull(job.proposal)
            propose(page, GeneratedBridgeCommand.Propose(proposal.baseRevision, proposal.document))
        }

    private fun clearExpiredApproval(page: GeneratedOpenPage) {
        val old = requirePage(page)
        if (old.approval != null && gate.currentApproval(page.ticket) !== old.approval) {
            expose(old.copy(approval = null))
        }
    }

    private fun propose(page: GeneratedOpenPage, proposal: GeneratedBridgeCommand.Propose): GeneratedPortGate.Ticket? {
        clearExpiredApproval(page)
        val old = requirePage(page)
        if (old.pending != null || old.approval != null) return null
        val confirmed = old.confirmed?.snapshot ?: return null
        return gate.propose(page.ticket, proposal, confirmed, old.online)?.also { expose(old.copy(approval = it)) }
    }

    fun dismiss(page: GeneratedOpenPage, approval: GeneratedPortGate.Ticket) {
        if (!active(page) || state?.approval !== approval) return
        val old = requireNotNull(state)
        gate.discard()
        expose(old.copy(approval = null))
    }

    suspend fun confirm(page: GeneratedOpenPage, approval: GeneratedPortGate.Ticket) = serial.withLock {
        val mark = epoch
        val old = requirePage(page)
        val snapshot = old.confirmed?.snapshot ?: throw GeneratedAppsException("snapshot_unavailable")
        val update = gate.confirm(page.ticket, approval, snapshot, old.online)
        if (update == null) {
            if (old.approval === approval) expose(old.copy(approval = null))
            throw GeneratedAppsException("approval_not_current")
        }
        // Commit the exact retry tuple before sending anything. No background write queue.
        val pending = old.copy(approval = null, pending = GeneratedPending(update))
        try { persist(pending, mark) }
        catch (e: IOException) {
            if (alive(mark)) expose(old.copy(approval = null))
            throw e
        }
        send(pending, mark)
    }

    suspend fun retry(page: GeneratedOpenPage) = serial.withLock {
        val old = requirePage(page)
        val pending = old.pending
        if (!old.online || pending == null || pending.conflict) throw GeneratedAppsException("retry_unavailable")
        send(old, epoch)
    }

    // Selectively clear a reviewed pending operation. The caller may keep its document as a new draft.
    suspend fun discardPending(page: GeneratedOpenPage): GeneratedUpdate? = serial.withLock {
        val old = requirePage(page)
        val pending = old.pending ?: return@withLock null
        val next = old.copy(pending = null, online = false)
        persist(next, epoch)
        expose(next)
        pending.update
    }

    private suspend fun send(old: GeneratedPageState, mark: Long) {
        val pending = requireNotNull(old.pending)
        verify(mark)
        // A write is unconfirmed until both the server response and its local journal commit succeed.
        // Keep retry locked on every other exit, including cancellation and local IO failure.
        val locked = old.copy(online = false)
        expose(locked)
        verify(mark)
        try {
            val saved = network.write(old.page.ticket.appId, pending.update) { alive(mark) }
            verify(mark)
            val next = locked.copy(confirmed = GeneratedTimed(saved, wallMillis()), online = true, pending = null)
            persist(next, mark)
            expose(next)
        } catch (e: GeneratedAppsException) {
            if (alive(mark) && e.code in setOf("revision_conflict", "mutation_conflict")) {
                val next = locked.copy(pending = pending.copy(conflict = true))
                persist(next, mark)
                expose(next)
            }
            throw e
        }
    }

    private suspend fun persist(next: GeneratedPageState, mark: Long) {
        verify(mark)
        withContext(Dispatchers.IO) {
            cache.storeJournal(identity, next.page.ticket.appId, GeneratedJournal(next.confirmed, next.pending))
        }
        verify(mark)
    }

    private fun requirePage(page: GeneratedOpenPage): GeneratedPageState {
        if (!active(page)) throw GeneratedAppsException("stale_page")
        return requireNotNull(state)
    }
}
