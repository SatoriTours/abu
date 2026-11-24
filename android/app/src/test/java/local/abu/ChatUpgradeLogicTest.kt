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
        assertEquals("今日节奏建议", formatPreview(listOf(completed.copy(reply = "## **今日节奏建议**\n第二行"))))
        assertEquals("事项", formatPreview(listOf(completed.copy(reply = "1. *事项*"))))
        assertEquals("snake_case_name 重点", formatPreview(listOf(completed.copy(reply = "> - snake_case_name _重点_"))))
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
        val blocks = parseMarkdown("```\nline\r\n```\n\n### 三级\n\n#### 四级")
        assertEquals(3, blocks.size)
        val code = blocks[0] as MarkdownBlock.CodeBlock
        assertEquals("", code.language)
        assertEquals("line", code.code)
        assertEquals(3, (blocks[1] as MarkdownBlock.Heading).level)
        assertEquals(4, (blocks[2] as MarkdownBlock.Heading).level)
    }

    @Test
    fun parsesSixHeadingLevelsAndHorizontalRules() {
        val blocks = parseMarkdown("###### 六级\n\n---\n\n* * *\n\n___\n\n####### 七级")
        assertEquals(5, blocks.size)
        assertEquals(6, (blocks[0] as MarkdownBlock.Heading).level)
        assertEquals(listOf(InlineSpan.Text("六级")), (blocks[0] as MarkdownBlock.Heading).spans)
        assertSame(MarkdownBlock.HorizontalRule, blocks[1])
        assertSame(MarkdownBlock.HorizontalRule, blocks[2])
        assertSame(MarkdownBlock.HorizontalRule, blocks[3])
        // Seven hashes are not a heading, and the paragraph must keep every character.
        assertEquals(listOf(InlineSpan.Text("####### 七级")), (blocks[4] as MarkdownBlock.Paragraph).spans)
    }

    @Test
    fun parsesPipeTableWithInlineCells() {
        val blocks = parseMarkdown("| 名称 | 值 |\n| --- | :---: |\n| **甲** | 1 |\n| 乙 | 2 |")
        assertEquals(1, blocks.size)
        val table = blocks[0] as MarkdownBlock.Table
        assertEquals(listOf(listOf(InlineSpan.Text("名称")), listOf(InlineSpan.Text("值"))), table.headers)
        assertEquals(2, table.rows.size)
        assertEquals(listOf(InlineSpan.Bold("甲")), table.rows[0][0])
        assertEquals(listOf(InlineSpan.Text("1")), table.rows[0][1])
        assertEquals(listOf(InlineSpan.Text("乙")), table.rows[1][0])
        assertEquals(listOf(InlineSpan.Text("2")), table.rows[1][1])
    }

    @Test
    fun incompleteTableStaysParagraph() {
        // A streamed table stays a normal paragraph until its separator row is complete.
        assertTrue(parseMarkdown("| 名称 | 值 |").single() is MarkdownBlock.Paragraph)
        assertTrue(parseMarkdown("| 名称 | 值 |\n| --- ").all { it is MarkdownBlock.Paragraph })
        val mixed = parseMarkdown("说明\n\n| 名称 | 值 |\n| --- | --- |\n| 甲 | 1 |")
        assertEquals(2, mixed.size)
        assertTrue(mixed[0] is MarkdownBlock.Paragraph)
        assertEquals(1, (mixed[1] as MarkdownBlock.Table).rows.size)
    }

    @Test
    fun plainTextPipesNeedASeparatorRow() {
        // A pipe row without a separator row is still ordinary text.
        val paragraph = parseMarkdown("a | b\n下一行")
        assertEquals(1, paragraph.size)
        assertEquals(listOf(InlineSpan.Text("a | b\n下一行")), (paragraph[0] as MarkdownBlock.Paragraph).spans)
        // With a separator row the same line is a header, and no trailing rows are invented.
        val table = parseMarkdown("a | b\n--- | ---")
        assertEquals(1, table.size)
        val parsed = table[0] as MarkdownBlock.Table
        assertEquals(listOf(listOf(InlineSpan.Text("a")), listOf(InlineSpan.Text("b"))), parsed.headers)
        assertEquals(0, parsed.rows.size)
    }

    @Test
    fun listItemsCarryIndentDepth() {
        val blocks = parseMarkdown("- 一\n  - 一点一\n    - 一点一点一\n1. 甲\n   1. 甲一")
        assertEquals(2, blocks.size)
        val bullets = blocks[0] as MarkdownBlock.BulletList
        assertEquals(listOf(InlineSpan.Text("一")), bullets.items[0])
        assertEquals(listOf(0, 1, 2), bullets.indents)
        assertEquals(listOf(0, 1, 2), bullets.entries.map { it.indent })
        assertEquals(listOf(InlineSpan.Text("一点一点一")), bullets.entries[2].spans)
        val ordered = blocks[1] as MarkdownBlock.OrderedList
        assertEquals(listOf(1, 1), ordered.items.map { it.first })
        assertEquals(listOf(0, 1), ordered.entries.map { it.indent })
        assertEquals(listOf(InlineSpan.Text("甲一")), ordered.entries[1].spans)
    }

    @Test
    fun parsesStrikethroughBoldItalicAndSafeLinks() {
        assertEquals(listOf(InlineSpan.Strikethrough("删除")), parseInline("~~删除~~"))
        assertEquals(listOf(InlineSpan.BoldItalic("又粗又斜")), parseInline("***又粗又斜***"))
        assertEquals(
            listOf(InlineSpan.Text("见 "), InlineSpan.Link("文档", "https://example.test/a?b=1")),
            parseInline("见 [文档](https://example.test/a?b=1)"),
        )
        // Only absolute http(s) targets become links; other schemes stay literal text.
        assertEquals(listOf(InlineSpan.Text("[坏](javascript:alert(1))")), parseInline("[坏](javascript:alert(1))"))
        assertEquals(listOf(InlineSpan.Text("[坏](ftp://x)")), parseInline("[坏](ftp://x)"))
        assertEquals(listOf(InlineSpan.Text("[坏](https://exa mple)")), parseInline("[坏](https://exa mple)"))
    }

    @Test
    fun halfStreamedInlineMarkersStayLiteral() {
        assertEquals(listOf(InlineSpan.Text("未闭合 ~~删除")), parseInline("未闭合 ~~删除"))
        assertEquals(listOf(InlineSpan.Text("半截 ***粗")), parseInline("半截 ***粗"))
        assertEquals(listOf(InlineSpan.Text("半截 [链接](htt")), parseInline("半截 [链接](htt"))
        assertEquals(listOf(InlineSpan.Text("尾巴 ~~")), parseInline("尾巴 ~~"))
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
