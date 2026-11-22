package local.abu

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.SerializationException
import local.abu.data.ChatHistory
import local.abu.data.ChatMessage
import local.abu.ui.ChatState
import java.io.IOException
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class ChatHistoryRefreshTest {
    private val previous = ChatMessage("old", "已有记录", "2026-11-22T00:00:00Z")
    private val newer = previous.copy(id = "new", content = "重新读到的记录")

    @Test
    fun closedConnectionLeavesRecordsAndPendingDraftIntactThenRefreshCanRecover() = runTest {
        val state = withPendingDraft()
        state.refreshHistory {
            assertTrue(state.busy)
            throw IOException("unexpected end of stream; not for UI")
        }
        assertUnchangedAfterFailure(state)

        state.refreshHistory { ChatHistory(listOf(newer), modelConfigured = true) }
        assertEquals(listOf(newer), state.messages)
        assertTrue(state.historyLoaded)
        assertTrue(state.modelConfigured)
        assertFalse(state.busy)
        assertNull(state.error)
        assertDraft(state)
    }

    @Test
    fun malformedHistoryDoesNotReportSuccessOrExposeExceptionDetails() = runTest {
        val state = withPendingDraft()
        state.refreshHistory { throw SerializationException("secret payload should not be shown") }
        assertUnchangedAfterFailure(state)
    }

    @Test
    fun cancellationIsRethrownUnchangedWithoutReportingNetworkError() = runTest {
        val state = withPendingDraft()
        val cancelled = CancellationException("cancel history")
        val thrown = assertFailsWith<CancellationException> {
            state.refreshHistory { throw cancelled }
        }
        assertSame(cancelled, thrown)
        assertFalse(state.busy)
        assertFalse(state.historyLoaded)
        assertNull(state.error)
        assertDraft(state)
    }

    @Test
    fun cancelledRefreshCannotApplyAResponseEvenIfLoaderIgnoresCancellation() = runTest {
        val state = withPendingDraft()
        val waiting = CompletableDeferred<Unit>()
        val old = launch {
            state.refreshHistory {
                try {
                    waiting.await()
                } catch (_: CancellationException) {
                    // Deliberately simulate an upstream that returns after cancellation.
                }
                ChatHistory(listOf(newer), modelConfigured = true)
            }
        }
        runCurrent()
        assertTrue(state.busy)
        old.cancel()
        old.join()
        assertFalse(state.busy)
        assertFalse(state.historyLoaded)
        assertEquals(listOf(previous), state.messages)
        assertNull(state.error)
        assertDraft(state)
    }

    private fun withPendingDraft() = ChatState().apply {
        messages = listOf(previous)
        draft = "需要重试的原文"
        pendingId = "keep-this-id"
        pendingText = draft
    }

    private fun assertUnchangedAfterFailure(state: ChatState) {
        assertEquals("读取对话失败，请检查连接后刷新。", state.error)
        assertEquals(listOf(previous), state.messages)
        assertFalse(state.historyLoaded)
        assertFalse(state.busy)
        assertDraft(state)
    }

    private fun assertDraft(state: ChatState) {
        assertEquals("需要重试的原文", state.draft)
        assertEquals("keep-this-id", state.pendingId)
        assertEquals(state.draft, state.pendingText)
    }
}
