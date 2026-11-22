package local.abu

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import local.abu.data.AppAsset
import local.abu.data.AppCatalogEntry
import local.abu.data.AppManifest
import local.abu.data.AppsException
import local.abu.data.TodoItem
import local.abu.data.TodoSnapshot
import local.abu.data.TodoUpdate
import local.abu.miniapps.*
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class TodoMiniAppTest {
    @Test
    fun messageCodecRejectsInvalidTextExtraFieldsUrlsAndCrossAppCommands() {
        assertEquals(TodoCommand.Add("任务"), parseTodoCommand("""["add","任务"]"""))
        assertEquals(TodoCommand.Toggle("x", true), parseTodoCommand("""["toggle","x",true]"""))
        for (bad in listOf(
            """["read","other-app"]""", """["read","https://outside.invalid"]""",
            """{"op":"read","op":"delete"}""", """["eval","alert(1)"]""",
            """["delete","../x"]""", """["toggle","x","true"]""",
            """["add","　"]""", """["add","\ud800"]""",
            """["add","${"💡".repeat(201)}"]""", """["refresh"] []""",
            """["read"] ${" ".repeat(4096)}""",
            """["add","${"a".repeat(4000)}"]"""
        )) assertNull(parseTodoCommand(bad), bad.take(100))
    }

    @Test
    fun versionRouteDeniesOtherOriginsVersionsAndDocumentsInFrames() {
        val bundle = bundle("v1")
        val base = "https://personal-todo.apps.abu.invalid/v1/"
        assertEquals("index.html", bundle.pathFor("GET", base + "index.html", true))
        assertEquals("todo.js", bundle.pathFor("GET", base + "todo.js", false))
        assertNull(bundle.pathFor("GET", base + "index.html", false))
        assertNull(bundle.pathFor("POST", base + "index.html", true))
        assertNull(bundle.pathFor("GET", base + "todo.js", true))
        for (url in listOf(base + "%74odo.js", base + "todo.js?next=other", base + "todo.js#part",
            base + "../v1/todo.js", base.replace("v1", "v2") + "todo.js",
            base.replace("personal-todo", "other") + "todo.js", "file:///v1/todo.js")) {
            assertNull(bundle.pathFor("GET", url, false))
        }
    }

    @Test
    fun incompleteAndCorruptBundleCannotReplacePreviousVersion() = withCache { cache ->
        val id = todoIdentity("first")
        val original = bundle("v1")
        cache.saveBundle(id, original)
        val newer = bundle("v1", marker = "replacement")
        assertFails { cache.saveBundle(id, newer.copy(resources = newer.resources - "todo.js")) }
        assertFails { cache.saveBundle(id, newer.copy(resources = newer.resources + ("todo.js" to byteArrayOf(0)))) }
        assertContentEquals(original.resources.getValue("index.html"), cache.bundle(id, "v1")!!.resources.getValue("index.html"))
        assertNull(cache.bundle(todoIdentity("other"), "v1"))
    }

    @Test
    fun versionRollbackDoesNotRollBackSnapshot() = withCache { cache ->
        runTest {
            val server = Server()
            val state = TodoState(cache, server)
            state.setToken("first")
            state.refreshCatalog()
            state.open()
            state.command(TodoCommand.Add("不随版本回退"))
            state.switchVersion("v1")
            assertEquals("v1", state.bundle?.manifest?.version)
            assertEquals("不随版本回退", state.snapshot?.items?.single()?.text)
            assertEquals(1L, state.snapshot?.revision)
        }
    }

    @Test
    fun offlineViewNeverCreatesOrInventsSnapshotAndExplicitRefreshRecovers() = withCache { cache ->
        runTest {
            val server = Server()
            val state = TodoState(cache, server)
            val id = todoIdentity("first")
            state.setToken("first")
            server.offline = true
            state.open()
            assertNull(state.bundle)
            assertNull(state.snapshot)
            cache.saveBundle(id, bundle("v2"))
            state.open()
            assertNotNull(state.bundle)
            assertNull(state.snapshot)
            state.command(TodoCommand.Add("no"))
            assertTrue(server.updates.isEmpty())
            server.offline = false
            state.command(TodoCommand.Refresh)
            assertTrue(state.writable)
            state.command(TodoCommand.Add("yes"))
            assertEquals("yes", state.snapshot?.items?.single()?.text)
        }
    }

    @Test
    fun lostWriteResponsePersistsExactUpdateAndBlocksEditingAndVersionChange() = withCache { cache ->
        runTest {
            val server = Server()
            val state = TodoState(cache, server)
            state.setToken("first")
            state.open()
            server.loseNextResponse = true
            state.command(TodoCommand.Add("  原文  "))
            val pending = assertNotNull(state.pending)
            assertTrue(state.snapshot?.items?.isEmpty() == true)
            assertFalse(state.writable)
            state.command(TodoCommand.Add("不能替换"))
            state.switchVersion("v1")
            assertEquals("v2", state.bundle?.manifest?.version)
            assertEquals(listOf(pending.update), server.updates)

            val reopened = TodoState(cache, server)
            reopened.setToken("first")
            reopened.open()
            assertEquals(pending, reopened.pending)
            reopened.command(TodoCommand.Retry)
            assertEquals(listOf(pending.update, pending.update), server.updates)
            assertNull(reopened.pending)
            assertEquals("  原文  ", reopened.snapshot?.items?.single()?.text)
        }
    }

    @Test
    fun conflictRequiresReadThenPreservesDraftWithoutRebasingAutomatically() = withCache { cache ->
        runTest {
            val server = Server()
            val state = TodoState(cache, server)
            state.setToken("first")
            state.open()
            state.command(TodoCommand.Add("旧文字"))
            val row = state.snapshot!!.items.single()
            server.data = TodoSnapshot(2, listOf(row.copy(done = true)))
            state.command(TodoCommand.Edit(row.id, "编辑草稿"))
            assertTrue(state.pending?.conflict == true)
            state.command(TodoCommand.RecoverDraft)
            assertNotNull(state.pending)
            state.command(TodoCommand.Refresh)
            state.command(TodoCommand.RecoverDraft)
            assertNull(state.pending)
            assertEquals(TodoCommand.Edit(row.id, "编辑草稿"), state.recoveredDraft)
            assertEquals("旧文字", state.snapshot!!.items.single().text)
            assertTrue(state.snapshot!!.items.single().done)
            assertEquals(2, server.updates.size)
        }
    }

    @Test
    fun oldIdentityCannotActivateLateReadAndAuthFailureHidesCachedRecords() = withCache { cache ->
        runTest {
            val server = Server()
            val state = TodoState(cache, server)
            state.setToken("first")
            val late = CompletableDeferred<TodoSnapshot>()
            server.waitRead = late
            val oldRequest = launch { state.open() }
            runCurrent()
            state.setToken("second")
            late.complete(TodoSnapshot(1, listOf(TodoItem("x", "旧身份", false))))
            oldRequest.join()
            assertNull(state.bundle)
            assertNull(state.snapshot)
            assertNull(cache.data(todoIdentity("first")).timed)

            server.waitRead = null
            state.open()
            state.command(TodoCommand.Add("当前身份"))
            server.rejected = true
            state.command(TodoCommand.Refresh)
            assertFalse(state.writable)
            assertNull(state.snapshot)
            assertNull(state.bundle)
        }
    }

    @Test
    fun detachedPortRejectsMessagesEvenWhenRetainedByAQueuedCallback() {
        val gate = TodoPortGate()
        val old = gate.attach()
        assertTrue(gate.active(old))
        gate.detach(old)
        assertFalse(gate.active(old))
        val current = gate.attach()
        gate.detach(old)
        assertFalse(gate.active(old))
        assertTrue(gate.active(current))
        gate.detach(current)
        assertFalse(gate.active(current))
    }

    @Test
    fun staleDownloadCannotWriteAfterChangingIdentity() = withCache { cache ->
        runTest {
            val server = Server()
            val state = TodoState(cache, server)
            state.setToken("first")
            val download = CompletableDeferred<TodoBundle>()
            server.waitDownload = download
            val old = launch { state.open() }
            runCurrent()
            state.setToken("second")
            download.complete(bundle("v2"))
            old.join()
            assertNull(state.bundle)
            assertNull(cache.bundle(todoIdentity("first"), "v2"))
            assertNull(cache.bundle(todoIdentity("second"), "v2"))
        }
    }

    @Test
    fun cancellingAnInFlightWriteLeavesTheSamePendingOperationOnDisk() = withCache { cache ->
        runTest {
            val server = Server()
            val state = TodoState(cache, server)
            state.setToken("do-not-persist")
            state.open()
            server.waitWrite = CompletableDeferred()
            val write = launch { state.command(TodoCommand.Add("取消请求后仍待确认")) }
            runCurrent()
            val pending = assertNotNull(state.pending)
            write.cancel()
            write.join()
            assertEquals(pending, cache.data(todoIdentity("do-not-persist")).pending)
            assertEquals(TodoSnapshot(0, emptyList()), state.snapshot)
            assertFalse(state.writable)
            assertFalse(state.canRetry)
            state.command(TodoCommand.Refresh)
            assertTrue(state.canRetry)
        }
    }

    @Test
    fun aDamagedCachedAssetCannotBeLoadedAndPartitionUsesOnlyTheFingerprint() {
        val root = Files.createTempDirectory("abu-corrupt-todo-test").toFile()
        try {
            val token = "raw-token-not-a-path"
            val id = todoIdentity(token)
            val cache = TodoCache(root)
            cache.saveBundle(id, bundle("v1"))
            assertNotNull(cache.bundle(id, "v1"))
            val partition = root.listFiles()!!.single()
            assertEquals("$id-$TodoAppId", partition.name)
            assertFalse(partition.name.contains(token))
            val saved = partition.listFiles()!!.single()
            val changed = saved.readBytes()
            changed[changed.lastIndex] = 0
            saved.writeBytes(changed)
            assertNull(cache.bundle(id, "v1"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun oldCatalogAndVersionRemainUsableAfterUpgradeWithUnchangedData() = withCache { cache ->
        runTest {
            val id = todoIdentity("first")
            cache.saveCatalog(id, AppCatalogEntry(TodoAppId, "个人待办", "v2", listOf("v1", "v2")))
            assertEquals(listOf("v1", "v2"), cache.catalog(id)?.versions)
            cache.saveBundle(id, bundle("v2"))
            cache.prefer(id, "v2")
            val server = Server().apply { latest = "v3" }
            val state = TodoState(cache, server)
            state.setToken("first")
            state.refreshCatalog()
            assertEquals(listOf("v1", "v2", "v3"), cache.catalog(id)?.versions)
            state.open()
            assertEquals("v2", state.preferred)
            state.command(TodoCommand.Add("不随升级改变"))
            val before = state.snapshot
            state.switchVersion("v3")
            assertEquals("v3", state.preferred)
            state.switchVersion("v2")
            assertEquals(before, state.snapshot)
        }
    }

    @Test
    fun freshIdentityDefaultsToV3AndThemeEnvelopeDoesNotAcceptCommands() = withCache { cache ->
        runTest {
            val server = Server().apply { latest = "v3" }
            val state = TodoState(cache, server)
            state.setToken("first")
            state.open()
            assertEquals("v3", state.bundle?.manifest?.version)
            assertTrue(state.pageState(TodoTheme.DARK).contains(""""theme":"dark""""))
            assertTrue(state.pageState(TodoTheme.LIGHT).contains(""""theme":"light""""))
            assertNull(parseTodoCommand("""["theme","dark"]"""))
        }
    }

    @Test
    fun legacyBinaryCatalogWithoutVersionListStillLoads() {
        val root = Files.createTempDirectory("abu-legacy-catalog-test").toFile()
        try {
            val id = todoIdentity("legacy")
            val partition = root.resolve("$id-$TodoAppId").apply { mkdir() }
            java.io.DataOutputStream(partition.resolve("catalog").outputStream()).use {
                it.writeInt(1)
                it.writeUTF("v2")
            }
            assertEquals(listOf("v1", "v2"), TodoCache(root).catalog(id)?.versions)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun v3DraftSurvivesPageReplacementAndNeverCrossesIdentity() = withCache { cache ->
        runTest {
            val cmd = """["draft","","未提交草稿"]"""
            assertNull(parseTodoCommand(cmd, "v2"))
            assertNull(parseTodoCommand("""["draft","other/app","正文"]""", "v3"))
            val draft = assertIs<TodoCommand.Draft>(parseTodoCommand(cmd, "v3"))
            val state = TodoState(cache, Server().apply { latest = "v3" })
            state.setToken("first")
            state.open()
            state.command(draft)
            state.switchVersion("v2")
            assertFalse(state.pageState(TodoTheme.DARK).contains("未提交草稿"))
            state.switchVersion("v3")
            assertTrue(state.pageState(TodoTheme.LIGHT).contains("未提交草稿"))
            state.setToken("second")
            state.open()
            assertFalse(state.pageState(TodoTheme.LIGHT).contains("未提交草稿"))
        }
    }

    private class Server : TodoService {
        var latest = "v2"
        var offline = false
        var rejected = false
        var loseNextResponse = false
        var waitRead: CompletableDeferred<TodoSnapshot>? = null
        var waitDownload: CompletableDeferred<TodoBundle>? = null
        var waitWrite: CompletableDeferred<TodoSnapshot>? = null
        val updates = mutableListOf<TodoUpdate>()
        var data = TodoSnapshot(0, emptyList())
        private var saved: TodoUpdate? = null

        private fun check() {
            if (rejected) throw AppsException("unauthorized", 401)
            if (offline) throw AppsException("connection_failed")
        }

        override suspend fun catalog(token: String): AppCatalogEntry {
            check()
            return AppCatalogEntry(TodoAppId, "个人待办", latest, if (latest == "v3") listOf("v1", "v2", "v3") else listOf("v1", "v2"))
        }
        override suspend fun download(token: String, version: String): TodoBundle {
            check()
            return waitDownload?.await() ?: bundle(version)
        }
        override suspend fun connect(token: String): TodoConnection {
            check()
            return TodoConnection(waitRead?.await() ?: data) { update ->
                check()
                updates += update
                waitWrite?.await()?.let { return@TodoConnection it }
                if (saved != update) {
                    if (update.baseRevision != data.revision) throw AppsException("revision_conflict", 409)
                    data = TodoSnapshot(data.revision + 1, update.items)
                    saved = update
                }
                if (loseNextResponse) {
                    loseNextResponse = false
                    throw AppsException("request_timeout", writeOutcomeUnknown = true)
                }
                data
            }
        }
    }

    companion object {
        private fun bundle(version: String, marker: String = version): TodoBundle {
            val resources = listOf("index.html", "todo.js", "todo.css").associateWith { "$it $marker".toByteArray() }
            val mime = listOf("text/html", "text/javascript", "text/css")
            val assets = resources.entries.mapIndexed { index, (path, bytes) ->
                val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
                AppAsset(path, "${mime[index]}; charset=utf-8", bytes.size, hash)
            }
            return TodoBundle(AppManifest(1, TodoAppId, version, "index.html", listOf("todo.read", "todo.write"), assets), resources)
        }

        private fun withCache(test: (TodoCache) -> Unit) {
            val dir = Files.createTempDirectory("abu-todo-test").toFile()
            try {
                test(TodoCache(dir))
            } finally {
                dir.deleteRecursively()
            }
        }
    }
}
