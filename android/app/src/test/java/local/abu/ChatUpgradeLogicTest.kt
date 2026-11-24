package local.abu

import local.abu.data.ChatMessage
import local.abu.ui.ChatState
import local.abu.ui.model.*
import kotlinx.coroutines.Job
import kotlin.test.*

class ChatUpgradeLogicTest {
    @Test
    fun streamingTextOnlyAcceptsTheActiveMessage() {
        val state = ChatState()
        state.startStreaming("m1")
        assertEquals("m1", state.replyActivity)
        state.appendDelta("m1", "你")
        state.appendDelta("other", "不应显示")
        state.appendDelta("m1", "好")
        state.appendDelta("m1", "")
        assertEquals("你好", state.streamingText)
        assertFalse(state.stopping)
        state.stopping = true
        state.finishStreaming()
        assertNull(state.replyActivity)
        assertEquals("", state.streamingText)
        assertFalse(state.stopping)
    }

    @Test
    fun replyRunIdIsolatesRoundsOfTheSameMessage() {
        val state = ChatState()
        val first = state.startStreaming("m1")
        assertEquals("m1", state.replyActivity)
        assertEquals(first, state.activeReplyRunId)
        state.appendDelta("m1", "旧", first)
        assertEquals("旧", state.streamingText)

        // A retry of the same message starts a new run; the old run may neither append nor settle it.
        val second = state.startStreaming("m1")
        assertNotEquals(first, second)
        assertEquals(second, state.activeReplyRunId)
        state.appendDelta("m1", "旧应丢弃", first)
        assertEquals("", state.streamingText)
        state.finishStreaming(first)
        assertEquals("m1", state.replyActivity)
        assertEquals(second, state.activeReplyRunId)

        state.appendDelta("m1", "新", second)
        assertEquals("新", state.streamingText)
        state.finishStreaming(second)
        assertNull(state.replyActivity)
        assertNull(state.activeReplyRunId)
        assertEquals("", state.streamingText)
        assertFalse(state.stopping)
    }

    @Test
    fun stopOnlyCancelsTheCurrentRunJob() {
        val state = ChatState()
        val first = state.startStreaming("m1")
        val oldJob = Job()
        state.bindReplyJob(first, oldJob)
        val second = state.startStreaming("m1")
        val newJob = Job()
        state.bindReplyJob(second, newJob)

        // An outdated stop callback must never kill the retry of the same message.
        assertFalse(state.cancelReplyJobIfMatching(first))
        assertFalse(oldJob.isCancelled)
        assertTrue(state.cancelReplyJobIfMatching(second))
        assertTrue(newJob.isCancelled)
        assertFalse(oldJob.isCancelled)

        // A run without a bound job cancels nothing, and a stale job is never attached.
        val third = state.startStreaming("m1")
        assertFalse(state.cancelReplyJobIfMatching(third))
        val staleJob = Job()
        state.bindReplyJob(second, staleJob)
        assertFalse(state.cancelReplyJobIfMatching(second))
        assertFalse(staleJob.isCancelled)
    }

    @Test
    fun removeMessageDropsOnlyMatchingId() {
        val state = ChatState()
        state.update(ChatMessage(id = "a", content = "第一条", createdAt = "", botId = "abu"))
        state.update(ChatMessage(id = "b", content = "第二条", createdAt = "", botId = "abu"))
        state.removeMessage("a")
        assertEquals(listOf("b"), state.messages.map { it.id })
        state.removeMessage("missing")
        assertEquals(1, state.messages.size)
    }

    @Test
    fun clearAllKeepsDraftAndPendingRequest() {
        val state = ChatState()
        state.update(ChatMessage(id = "a", content = "第一条", createdAt = "", botId = "abu"))
        state.draft = "草稿"
        state.pendingId = "keep-id"
        state.pendingText = "待发送"
        state.error = "读取失败"
        state.clearAll()
        assertTrue(state.messages.isEmpty())
        assertNull(state.error)
        assertEquals("草稿", state.draft)
        assertEquals("keep-id", state.pendingId)
        assertEquals("待发送", state.pendingText)
    }

    @Test
    fun previewPrefersCompletedAndPartialInterruptedReplies() {
        val completed = ChatMessage(
            id = "a", content = "提问", createdAt = "", reply = "完整回复", replyStatus = "completed", botId = "abu",
        )
        assertEquals("完整回复", formatPreview(listOf(completed)))
        assertEquals("半截回复", formatPreview(listOf(completed.copy(reply = "半截回复", replyStatus = "interrupted"))))
        assertEquals("提问", formatPreview(listOf(completed.copy(reply = " ", replyStatus = "interrupted"))))
        assertEquals("提问", formatPreview(listOf(completed.copy(reply = "", replyStatus = "running"))))
        assertEquals("提问", formatPreview(listOf(completed.copy(reply = "  ", replyStatus = "completed"))))
    }

    @Test
    fun parsesBlocksWithCrlfAndLists() {
        val input = "# 标题\r\n正文**粗体**与*斜体*和`code`\r\n\r\n" +
            "- 一\r\n* 二\r\n\r\n1. 第一\r\n2. 第二\r\n\r\n> 引用行\r\n> 第二行"
        val blocks = parseMarkdown(input)
        assertEquals(5, blocks.size)
        val heading = blocks[0] as MarkdownBlock.Heading
        assertEquals(1, heading.level)
        assertEquals(listOf(InlineSpan.Text("标题")), heading.spans)
        val paragraph = blocks[1] as MarkdownBlock.Paragraph
        assertEquals(
            listOf(
                InlineSpan.Text("正文"), InlineSpan.Bold("粗体"), InlineSpan.Text("与"),
                InlineSpan.Italic("斜体"), InlineSpan.Text("和"), InlineSpan.Code("code"),
            ),
            paragraph.spans,
        )
        val bullets = blocks[2] as MarkdownBlock.BulletList
        assertEquals(listOf(listOf(InlineSpan.Text("一")), listOf(InlineSpan.Text("二"))), bullets.items)
        val ordered = blocks[3] as MarkdownBlock.OrderedList
        assertEquals(listOf(1, 2), ordered.items.map { it.first })
        assertEquals(listOf(InlineSpan.Text("第二")), ordered.items[1].second)
        val quote = blocks[4] as MarkdownBlock.BlockQuote
        assertEquals(listOf(InlineSpan.Text("引用行\n第二行")), quote.spans)
    }

    @Test
    fun unclosedFenceStreamsAsCodeBlock() {
        val blocks = parseMarkdown("说明\n\n```kotlin\nval a = 1\nif (a > 0) {")
        assertEquals(2, blocks.size)
        assertTrue(blocks[0] is MarkdownBlock.Paragraph)
        val code = blocks[1] as MarkdownBlock.CodeBlock
        assertEquals("kotlin", code.language)
        assertEquals("val a = 1\nif (a > 0) {", code.code)
    }

    @Test
    fun closedFenceAndDeepHeading() {
        val blocks = parseMarkdown("```\nline\r\n```\n\n### 三级\n\n#### 太深")
        assertEquals(3, blocks.size)
        val code = blocks[0] as MarkdownBlock.CodeBlock
        assertEquals("", code.language)
        assertEquals("line", code.code)
        assertEquals(3, (blocks[1] as MarkdownBlock.Heading).level)
        assertTrue(blocks[2] is MarkdownBlock.Paragraph)
    }

    @Test
    fun inlineKeepsUnclosedMarkersLiteral() {
        assertEquals(listOf(InlineSpan.Text("未闭合 **粗体")), parseInline("未闭合 **粗体"))
        assertEquals(listOf(InlineSpan.Text("一个 * 号")), parseInline("一个 * 号"))
        assertEquals(listOf(InlineSpan.Code("**不解析**")), parseInline("`**不解析**`"))
        assertEquals(listOf(InlineSpan.Text("半截 "), InlineSpan.Code("code")), parseInline("半截 `code`"))
        assertEquals(listOf(InlineSpan.Text("尾巴 @@BT@")), parseInline("尾巴 @@BT@"))
        assertEquals(emptyList<InlineSpan>(), parseInline(""))
    }
}
