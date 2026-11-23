package local.abu

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import local.abu.data.*
import local.abu.ui.ChatSession
import local.abu.ui.model.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class MultiBotUiTest {
    @Test fun chatDraftsAndSameIdAreScopedAndOldTokenCannotPopulateNewSession() = runTest {
        val previous = ChatSession("old")
        val a = previous.forBot("a")
        val b = previous.forBot("b")
        a.draft = "a draft"
        a.pendingId = "same"
        a.pendingText = a.draft
        b.draft = "b draft"
        val first = ChatMessage("same", "first", "", botId = "a")
        val second = ChatMessage("same", "second", "", botId = "b")
        val reply = CompletableDeferred<ChatHistory>()
        val inflight = launch { a.refreshHistory { reply.await() } }
        runCurrent()
        b.update(second)
        val next = ChatSession("new")
        reply.complete(ChatHistory(listOf(first), true))
        inflight.join()
        assertEquals(listOf(first), previous.forBot("a").messages)
        assertEquals(listOf(second), b.messages)
        assertEquals("b draft", b.draft)
        assertEquals("same", previous.forBot("a").pendingId)
        assertEquals("a draft", previous.forBot("a").pendingText)
        assertTrue(next.forBot("a").messages.isEmpty())
        assertNull(next.forBot("a").pendingId)
        assertFailsWith<IllegalArgumentException> { b.update(first) }
    }

    @Test fun lostCreateReusesAllFieldsAndConflictedEditRequiresExplicitReview() = runTest {
        val creates = mutableListOf<Bot>()
        var remote = Bot("abu", "阿布", "", BotAvatar.ABU, 1)
        val state = BotsState(
            load = { listOf(remote) },
            create = { _, bot ->
                creates += bot
                if (creates.size == 1) throw java.io.IOException("lost response")
                bot
            },
            update = { _, update ->
                if (update.revision != remote.revision) throw BotsException("revision_conflict",409)
                update.copy(revision = update.revision + 1).also { remote = it }
            },
            newId = { "stable" },
        )
        state.setToken("token")
        state.refresh()
        state.startCreate()
        state.editFields(BotFields("小叶", "整理", BotAvatar.LEAF))
        state.save()
        assertTrue(state.pendingCreate != null)
        state.editFields(BotFields("其他", "", BotAvatar.WAVE))
        state.save()
        assertEquals(listOf(creates.first(), creates.first()), creates)
        assertEquals("stable", state.bots.last().id)

        state.startEdit("abu")
        state.editFields(BotFields("我的草稿", "原文", BotAvatar.FLAME))
        remote = remote.copy(name = "并发修改", revision = 2)
        state.save()
        assertTrue(state.conflict)
        state.save()
        assertEquals(2L, remote.revision)
        state.refreshConflict()
        assertEquals("并发修改", state.review?.name)
        assertEquals("我的草稿", state.fields.name)
        state.save()
        assertEquals(2L, remote.revision)
        state.acceptReview()
        state.save()
        assertEquals("我的草稿", remote.name)
        assertEquals(3L, remote.revision)
    }

    @Test fun botFieldsCountUnicodeAndRejectBlank() {
        assertTrue(BotFields("🌿".repeat(40), "🧵".repeat(4000), BotAvatar.LEAF).valid)
        assertFalse(BotFields("🌿".repeat(41), "", BotAvatar.LEAF).valid)
        assertFalse(BotFields("　", "", BotAvatar.LEAF).valid)
        assertFalse(BotFields("阿布", "x".repeat(4001), BotAvatar.ABU).valid)
    }

    @Test fun taskCollisionAndDraftUseTheirOwnBotEvenOnRetry() = runTest {
        fun task(bot: String, status: TaskStatus = TaskStatus.RUNNING) =
            TaskRecord("same", "input", status, "", "", null, null, botId = bot)
        val creates = mutableListOf<Triple<String,String,String>>()
        val state = TasksState(
            loadTasks = { TaskList(listOf(task("a"), task("b")),true,true) },
            createTask = { _, id, content, bot ->
                creates += Triple(id,content,bot)
                if (creates.size == 1) throw TaskException("connection_failed")
                TaskDetail(task(bot).copy(id=id,content=content),true,true)
            },
            cancelTask = { _, id, bot ->
                assertEquals("same",id)
                assertEquals("b",bot)
                TaskDetail(task(bot,TaskStatus.CANCELLED),true,true)
            },
            newId = { "new" },
        )
        state.setToken("token")
        state.refresh()
        assertNotEquals(task("a").key,task("b").key)
        state.cancel(task("b").key)
        assertEquals(listOf(TaskStatus.RUNNING,TaskStatus.CANCELLED),state.tasks.map { it.status })
        state.selectBot("a")
        state.edit("draft A")
        state.submit("a")
        state.selectBot("b")
        assertFalse(state.canSubmitFor("b"))
        state.edit("draft B")
        state.submit("b")
        assertEquals(1, creates.size)
        state.submit("a") // Explicit retry of the pending A, never B.
        assertEquals(listOf(Triple("new","draft A","a"),Triple("new","draft A","a")),creates)
        assertEquals("draft B",state.draft)
    }
}
