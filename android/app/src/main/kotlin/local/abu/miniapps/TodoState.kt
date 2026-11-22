package local.abu.miniapps

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.*
import local.abu.data.AppCatalogEntry
import local.abu.data.AppsClient
import local.abu.data.AppsException
import local.abu.data.TodoItem
import local.abu.data.TodoSnapshot
import local.abu.data.TodoUpdate
import java.io.IOException
import java.text.DateFormat
import java.util.Date
import java.util.UUID

internal interface TodoService {
    suspend fun catalog(token: String): AppCatalogEntry
    suspend fun download(token: String, version: String): TodoBundle
    suspend fun connect(token: String): TodoConnection
}

internal class ClientTodoService(private val client: AppsClient) : TodoService {
    override suspend fun catalog(token: String): AppCatalogEntry =
        client.catalog(token).apps.singleOrNull { it.id == TodoAppId } ?: throw AppsException("app_not_found")

    override suspend fun download(token: String, version: String): TodoBundle {
        val manifest = client.manifest(token, TodoAppId, version)
        requireTodoManifest(manifest, version)
        return TodoBundle(manifest, manifest.assets.associate { it.path to client.asset(token, manifest, it.path) })
    }

    override suspend fun connect(token: String): TodoConnection {
        val session = client.openSession(token, TodoAppId)
        return TodoConnection(client.getData(session)) { client.putData(session, it) }
    }
}

internal class TodoConnection(val snapshot: TodoSnapshot, val save: suspend (TodoUpdate) -> TodoSnapshot)

// Compose owns calls on the UI dispatcher. Network suspensions may outlive the current identity.
internal class TodoState(private val cache: TodoCache, private val service: TodoService) {
    var generation by mutableStateOf(0L)
        private set
    var viewing by mutableStateOf(false)
        private set
    var bundle by mutableStateOf<TodoBundle?>(null)
        private set
    var catalog by mutableStateOf<AppCatalogEntry?>(null)
        private set
    var preferred by mutableStateOf<String?>(null)
        private set
    var cachedVersions by mutableStateOf(emptyList<String>())
        private set
    var busy by mutableStateOf(false)
        private set
    var notice by mutableStateOf<String?>(null)
        private set
    var authRejected by mutableStateOf(false)
        private set
    var recoveredDraft by mutableStateOf<TodoCommand.Change?>(null)
        private set
    // The v3 editor mirrors an unsubmitted draft only in this identity's memory.
    private var editorDraft: TodoCommand.Draft? = null
    private var journal by mutableStateOf(TodoJournal())
    private var connection by mutableStateOf<TodoConnection?>(null)
    private var authorizedReadAfterConflict by mutableStateOf(false)
    private var cacheFault by mutableStateOf(false)
    private var savedMutation by mutableStateOf<String?>(null)
    private var token = ""
    private var identity = ""

    val hasToken get() = generation != 0L && token.isNotBlank()
    val snapshot get() = journal.timed?.snapshot
    val pending get() = journal.pending
    val writable get() = !busy && pending == null && canContactData && snapshot?.revision != MaxTodoRevision
    val canRetry get() = !busy && canContactData && pending?.conflict == false
    val canRecoverDraft get() = !busy && authorizedReadAfterConflict && pending?.conflict == true && !cacheFault
    val online get() = connection != null
    private val canContactData get() = online && !authRejected && !cacheFault && bundle != null

    fun setToken(value: String) {
        if (value == token) return
        token = value
        generation++
        viewing = false
        busy = false
        authRejected = false
        recoveredDraft = null
        savedMutation = null
        cacheFault = false
        authorizedReadAfterConflict = false
        clearVisibleData()
        notice = null
        if (!hasToken) return
        identity = todoIdentity(value)
        loadLocal()
    }

    fun close() {
        viewing = false
    }

    suspend fun refreshCatalog() = perform { current ->
        val remote = service.catalog(current.token)
        current.check()
        cache.saveCatalog(current.identity, remote)
        if (authRejected) loadLocal()
        authRejected = false
        catalog = remote
    }

    suspend fun open() {
        if (!hasToken || busy || authRejected) return
        viewing = true
        perform { current ->
            if (bundle == null) {
                val local = preferred ?: catalog?.currentVersion ?: cachedVersions.lastOrNull()
                val cached = local?.let { cache.bundle(current.identity, it) }
                    ?: TodoVersions.asReversed().firstNotNullOfOrNull { cache.bundle(current.identity, it) }
                if (cached != null) {
                    bundle = cached
                    preferred = cached.manifest.version
                    cachedVersions = (cachedVersions + cached.manifest.version).distinct().sorted()
                } else {
                    val remote = catalog ?: service.catalog(current.token).also {
                        current.check()
                        cache.saveCatalog(current.identity, it)
                        catalog = it
                    }
                    install(current, remote.currentVersion, force = false)
                }
            }
            refreshData(current)
        }
    }

    suspend fun switchVersion(version: String, downloadAgain: Boolean = false) {
        if (!hasToken || !viewing || pending != null || cacheFault || authRejected || version !in TodoVersions) return
        perform { current ->
            install(current, version, force = downloadAgain)
        }
    }

    suspend fun command(command: TodoCommand) {
        if (!viewing || bundle == null || authRejected) return
        when (command) {
            TodoCommand.Read -> Unit
            TodoCommand.Refresh -> perform { refreshData(it) }
            TodoCommand.Retry -> if (canRetry) perform { current -> confirm(current, checkNotNull(pending)) }
            TodoCommand.RecoverDraft -> if (canRecoverDraft) perform { current -> recover(current) }
            TodoCommand.ClearDraft -> if (!busy && pending == null) {
                recoveredDraft = null
                editorDraft = null
            }
            is TodoCommand.Draft -> if (bundle?.manifest?.version == "v3" && pending == null) {
                editorDraft = command.takeUnless { it.id == null && it.text.isEmpty() }
            }
            is TodoCommand.Change -> if (writable) perform { current -> submit(current, command) }
        }
    }

    fun pageState(theme: TodoTheme): String = buildJsonObject {
        if (bundle?.manifest?.version == "v3") {
            put("theme", theme.wire)
            val inFlight = when (val command = pending?.command) {
                is TodoCommand.Add -> TodoCommand.Draft(null, command.text)
                is TodoCommand.Edit -> TodoCommand.Draft(command.id, command.text)
                else -> null
            }
            (inFlight ?: editorDraft)?.let { draft ->
                putJsonObject("editorDraft") {
                    put("id", draft.id)
                    put("text", draft.text)
                }
            }
        }
        put("online", online)
        put("writable", writable)
        put("busy", busy)
        put("snapshotTime", journal.timed?.let { DateFormat.getDateTimeInstance().format(Date(it.timeMillis)) })
        put("notice", notice)
        put("retry", canRetry)
        put("recover", canRecoverDraft)
        put("pending", pending?.let { if (it.conflict) "conflict" else "unknown" })
        put("saved", savedMutation)
        put("draft", recoveredDraft?.let(::commandJson))
        val data = snapshot
        if (data != null) putJsonArray("items") {
            data.items.forEach { item -> addJsonObject {
                put("id", item.id)
                put("text", item.text)
                put("done", item.done)
            } }
        }
    }.toString()

    private suspend fun install(current: Request, version: String, force: Boolean) {
        val next = (if (force) null else cache.bundle(current.identity, version)) ?: service.download(current.token, version).also {
            current.check()
            requireTodoManifest(it.manifest, version)
            cache.saveBundle(current.identity, it)
        }
        current.check()
        cache.prefer(current.identity, version)
        bundle = next
        preferred = version
        cachedVersions = (cachedVersions + version).distinct().sorted()
    }

    private suspend fun refreshData(current: Request) {
        connection = null
        authorizedReadAfterConflict = false
        val remote = service.connect(current.token)
        current.check()
        if (cacheFault) return
        persist(current, journal.copy(timed = TimedTodo(remote.snapshot, System.currentTimeMillis())))
        connection = remote
        authorizedReadAfterConflict = pending?.conflict == true
    }

    private suspend fun submit(current: Request, command: TodoCommand.Change) {
        val before = checkNotNull(snapshot)
        val items = before.items.toMutableList()
        when (command) {
            is TodoCommand.Add -> {
                if (!validTodoText(command.text) || items.size == 100) {
                    notice = "每条待办需为 1–200 个字符，列表最多保留 100 项。"
                    return
                }
                items += TodoItem(UUID.randomUUID().toString(), command.text, false)
            }
            else -> {
                val id = when (command) {
                    is TodoCommand.Edit -> command.id
                    is TodoCommand.Toggle -> command.id
                    is TodoCommand.Delete -> command.id
                    else -> error("Unknown change")
                }
                val index = items.indexOfFirst { it.id == id }
                if (index == -1) {
                    notice = "找不到这条待办，请刷新后再修改。"
                    return
                }
                val row = items[index]
                when (command) {
                    is TodoCommand.Edit -> {
                        if (!validTodoText(command.text)) {
                            notice = "每条待办需为 1–200 个字符。"
                            return
                        }
                        items[index] = row.copy(text = command.text)
                    }
                    is TodoCommand.Toggle -> items[index] = row.copy(done = command.done)
                    is TodoCommand.Delete -> items.removeAt(index)
                    else -> error("Unknown change")
                }
            }
        }
        val request = PendingTodo(TodoUpdate(UUID.randomUUID().toString(), before.revision, items.toList()), command)
        persist(current, journal.copy(pending = request))
        if (command is TodoCommand.Add || command is TodoCommand.Edit) recoveredDraft = null
        confirm(current, request)
    }

    private suspend fun confirm(current: Request, request: PendingTodo) {
        val conn = checkNotNull(connection)
        // A cancelled write must also drop the writable session; the journal is already durable.
        connection = null
        val saved = try {
            conn.save(request.update)
        } catch (failure: AppsException) {
            current.check()
            if (!failure.writeOutcomeUnknown && (failure.statusCode == 409 ||
                    failure.code == "invalid_data" || failure.code == "body_too_large")) {
                persist(current, journal.copy(pending = request.copy(conflict = true)))
            }
            throw failure
        }
        current.check()
        require(saved.revision == request.update.baseRevision + 1 && saved.items == request.update.items)
        persist(current, TodoJournal(TimedTodo(saved, System.currentTimeMillis())))
        connection = conn
        if (request.command is TodoCommand.Add || request.command is TodoCommand.Edit) {
            editorDraft = null
            savedMutation = request.update.mutationId
        }
        notice = "已保存到后台。"
    }

    private fun recover(current: Request) {
        val change = checkNotNull(pending).command
        persist(current, journal.copy(pending = null))
        if (change is TodoCommand.Add || change is TodoCommand.Edit) editorDraft = null
        recoveredDraft = when (change) {
            is TodoCommand.Add -> change
            is TodoCommand.Edit -> {
                if (snapshot?.items?.any { it.id == change.id } == true) change else TodoCommand.Add(change.text)
            }
            else -> null
        }
        authorizedReadAfterConflict = false
        notice = if (recoveredDraft != null) "已恢复草稿，请核对最新待办再决定是否保存。" else "已读取最新记录。此次操作未提交。"
    }

    private fun persist(current: Request, next: TodoJournal) {
        cache.saveData(current.identity, next)
        journal = next
    }

    private fun loadLocal() {
        catalog = cache.catalog(identity)
        preferred = cache.preferred(identity)
        cachedVersions = TodoVersions.filter { cache.bundle(identity, it) != null }
        try {
            journal = cache.data(identity)
            cacheFault = false
        } catch (_: IOException) {
            cacheFault = true
            notice = LocalFault
        }
    }

    private fun clearVisibleData() {
        bundle = null
        catalog = null
        preferred = null
        cachedVersions = emptyList()
        journal = TodoJournal()
        editorDraft = null
        connection = null
    }

    private suspend fun perform(action: suspend (Request) -> Unit) {
        if (!hasToken || busy) return
        val current = Request(generation, token, identity)
        busy = true
        notice = null
        try {
            action(current)
            if (cacheFault && current.active()) notice = LocalFault
        } catch (failure: AppsException) {
            if (current.active()) {
                connection = null
                val denied = failure.statusCode == 401 && failure.code != "invalid_app_token"
                if (denied || failure.code == "unauthorized") {
                    clearVisibleData()
                    recoveredDraft = null
                    savedMutation = null
                    authorizedReadAfterConflict = false
                    authRejected = true
                }
                notice = when (if (denied) "unauthorized" else failure.code) {
                    "unauthorized" -> "开发令牌未获授权。请到设置核对后刷新目录。"
                    "invalid_app_token", "app_scope_mismatch" -> "小程序会话已失效。请刷新数据重新连接。"
                    "revision_conflict", "mutation_conflict", "revision_limit" -> "服务器数据已变化或达到修订上限。请先刷新，草稿已保留。"
                    "storage_unavailable" -> "后台待办存储不可用；有快照时只能查看。"
                    "asset_integrity", "invalid_response" -> "未能验证下载或后台响应；没有替换已验证页面。"
                    "app_not_found" -> "开发服务未提供个人待办，请核对后台版本。"
                    "invalid_data", "body_too_large" -> "服务器未接受此操作。请刷新后核对草稿。"
                    else -> "暂未连接到小程序服务。若有快照，可离线查看。"
                }
            }
        } catch (_: IOException) {
            if (current.active()) {
                connection = null
                notice = LocalFault
            }
        } catch (_: IllegalArgumentException) {
            if (current.active()) {
                connection = null
                notice = "未能验证小程序数据或资源；没有替换已验证页面。"
            }
        } finally {
            if (current.active()) busy = false
        }
    }

    private inner class Request(val generation: Long, val token: String, val identity: String) {
        fun active() = generation == this@TodoState.generation
        suspend fun check() {
            currentCoroutineContext().ensureActive()
            if (!active()) throw StaleTodoRequest
        }
    }

    private companion object {
        const val LocalFault = "待办本地缓存不可用。为避免丢失未确认写入，暂时不能修改。"
    }
}

private object StaleTodoRequest : kotlinx.coroutines.CancellationException()

internal fun commandJson(command: TodoCommand.Change): String = buildJsonArray {
    when (command) {
        is TodoCommand.Add -> { add("add"); add(command.text) }
        is TodoCommand.Edit -> { add("edit"); add(command.id); add(command.text) }
        is TodoCommand.Toggle -> { add("toggle"); add(command.id); add(command.done) }
        is TodoCommand.Delete -> { add("delete"); add(command.id) }
    }
}.toString()
