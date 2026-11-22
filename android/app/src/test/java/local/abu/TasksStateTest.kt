package local.abu

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import local.abu.data.TaskDetail
import local.abu.data.TaskException
import local.abu.data.TaskList
import local.abu.data.TaskRecord
import local.abu.data.TaskStatus
import local.abu.ui.model.TasksState
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class TasksStateTest {
    @Test
    fun retryAfterLostResponseAndFailedRefreshUsesSameIdAndExactBody() = runTest {
        var saved: TaskRecord? = null
        var offline = false
        val requests = mutableListOf<Pair<String, String>>()
        val state = TasksState(
            loadTasks = {
                if (offline) throw TaskException("connection_failed")
                TaskList(listOfNotNull(saved), true, true)
            },
            createTask = { _, id, content ->
                requests.add(id to content)
                if (saved == null) {
                    saved = record(id, content)
                    throw TaskException("connection_failed")
                }
                detail(checkNotNull(saved))
            },
            cancelTask = { _, _ -> error("unexpected cancellation") },
            newId = { "attempt-${requests.size + 1}" }
        )
        state.setToken("first")
        state.refresh()
        state.edit("  原文\n第二行  ")
        state.submit()
        val pending = assertNotNull(state.pending)
        offline = true
        state.refresh()
        state.edit("不能替换正文")

        assertEquals(pending, state.pending)
        assertEquals("  原文\n第二行  ", state.draft)
        state.submit()

        assertEquals(List(2) { "attempt-1" to "  原文\n第二行  " }, requests)
        assertEquals(listOf(saved), state.tasks)
        assertNull(state.pending)
        assertEquals("", state.draft)
    }

    @Test
    fun deliberateRerunCreatesNewRecordWithoutChangingOldOneOrOtherDraft() = runTest {
        val old = record("old", "任务正文", TaskStatus.INTERRUPTED).copy(errorCode = "interrupted")
        val state = stateWith(old)
        state.refresh()
        state.edit("未提交的新草稿")
        state.rerun(old.id)
        assertEquals(listOf(old, record("new-id", "任务正文")), state.tasks)
        assertEquals("未提交的新草稿", state.draft)
    }

    @Test
    fun oldCreateCannotPopulateNewTokenSessionOrReleaseItsBusyState() = runTest {
        val oldReply = CompletableDeferred<TaskDetail>()
        val newList = CompletableDeferred<TaskList>()
        val state = TasksState(
            loadTasks = { token ->
                if (token == "old") TaskList(emptyList(), true, true) else newList.await()
            },
            createTask = { _, _, _ -> oldReply.await() },
            cancelTask = { _, _ -> error("unexpected cancellation") },
            newId = { "old-result" },
        )
        state.setToken("old")
        state.refresh()
        state.edit("旧令牌下的文本")
        val oldRequest = launch { state.submit() }
        runCurrent()
        assertNotNull(state.pending)

        state.setToken("new")
        assertNull(state.pending)
        assertEquals("", state.draft)
        state.edit("当前草稿")
        val newRequest = launch { state.refresh() }
        runCurrent()
        oldReply.complete(detail(record("old-result", "旧令牌下的文本")))
        oldRequest.join()
        assertTrue(state.busy)
        assertTrue(state.tasks.isEmpty())
        assertEquals("当前草稿", state.draft)
        newList.complete(TaskList(listOf(record("new-result", "新令牌下的记录")), true, true))
        newRequest.join()
        assertEquals(listOf("new-result"), state.tasks.map { it.id })
        assertFalse(state.busy)
    }

    @Test
    fun cancelShowsActualCompletedRecordWhenCompletionWinsRace() = runTest {
        val running = record("active", "正文", TaskStatus.RUNNING)
        val completed = running.copy(status = TaskStatus.COMPLETED, result = "真实结果")
        val state = stateWith(running, cancel = { token, id ->
            assertEquals("test", token)
            assertEquals("active", id)
            detail(completed)
        })
        state.refresh()
        state.cancel(running.id)
        assertEquals(listOf(completed), state.tasks)
        assertFalse(state.canCancel(completed))
    }

    @Test
    fun rejectsBlankAndOverLimitByUnicodeCodePointsBeforeIssuingCreate() = runTest {
        val state = stateWith()
        state.refresh()
        state.edit("　\n")
        state.submit()
        assertTrue(state.tasks.isEmpty())
        state.edit("💡".repeat(4001))
        state.submit()
        assertTrue(state.tasks.isEmpty())
        val valid = "💡".repeat(4000)
        state.edit(valid)
        state.submit()
        assertEquals(valid, state.tasks.single().content)
    }

    @Test
    fun readsRecordsWithNoModelOrWritableStorageButDoesNotCreate() = runTest {
        val failed = record("failed", "可读取", TaskStatus.FAILED).copy(errorCode = "model_failed")
        for ((model, storage) in listOf(false to true, true to false)) {
            val state = stateWith(failed, model = model, storage = storage)
            state.refresh()
            state.edit("不发送")
            state.submit()
            state.rerun(failed.id)
            assertEquals(listOf(failed), state.tasks)
            assertFalse(state.canSubmit)
            assertFalse(state.canRerun(failed))
        }
    }

    @Test
    fun coroutineCancellationIsNotConvertedToNetworkErrorOrNewSubmission() = runTest {
        val inFlight = CompletableDeferred<TaskDetail>()
        val state = TasksState(
            loadTasks = { TaskList(emptyList(), true, true) },
            createTask = { _, _, _ -> inFlight.await() },
            cancelTask = { _, _ -> error("unexpected cancellation") },
        )
        state.setToken("test")
        state.refresh()
        state.edit("在途")
        val job = launch { assertFailsWith<CancellationException> { state.submit() } }
        runCurrent()
        val pending = assertNotNull(state.pending)
        state.submit() // Cannot launch a duplicate while the first request is suspended.
        job.cancel()
        job.join()
        assertFalse(state.busy)
        assertNull(state.error)
        assertEquals(pending, state.pending)
    }

    @Test
    fun fullStorageKeepsRecordsReadableWithoutStartingNewJobs() = runTest {
        val records = (1..200).map { record("record-$it", "已保存", TaskStatus.FAILED) }
        val state = stateWith(*records.toTypedArray())
        state.refresh()
        state.edit("新增")
        state.submit()
        state.rerun("record-1")
        assertEquals(records, state.tasks)
        assertFalse(state.canSubmit)
        assertFalse(state.canRerun(records.first()))
    }

    @Test
    fun abandoningUnconfirmedSubmissionAllowsEditingButDoesNotClearDraft() = runTest {
        val state = TasksState(
            loadTasks = { TaskList(emptyList(), true, true) },
            createTask = { _, _, _ -> throw TaskException("connection_failed") },
            cancelTask = { _, _ -> error("unexpected cancellation") },
        )
        state.setToken("test")
        state.refresh()
        state.edit("原文")
        state.submit()
        assertNotNull(state.pending)
        state.abandon()
        assertNull(state.pending)
        assertEquals("原文", state.draft)
        state.edit("其他任务")
        assertEquals("其他任务", state.draft)
    }

    private fun stateWith(
        vararg records: TaskRecord,
        model: Boolean = true,
        storage: Boolean = true,
        cancel: suspend (String, String) -> TaskDetail = { _, _ -> error("unexpected cancellation") },
    ) = TasksState(
        loadTasks = { TaskList(records.toList(), model, storage) },
        createTask = { _, id, content -> detail(record(id, content)) },
        cancelTask = cancel,
        newId = { "new-id" },
    ).also { it.setToken("test") }

    private fun record(id: String, content: String, status: TaskStatus = TaskStatus.QUEUED) =
        TaskRecord(id, content, status, "2026-11-21T00:00:00Z", "2026-11-21T00:00:00Z", null, null)

    private fun detail(record: TaskRecord) = TaskDetail(record, modelConfigured = true, storageAvailable = true)
}
