package local.abu.ui.model

/**
 * Minimal, allocation-cheap Markdown model for chat rendering. It is pure Kotlin so it can be
 * unit tested without Compose or Android, and it never throws on partial streaming input.
 */
sealed interface MarkdownBlock {
    data class Heading(val level: Int, val spans: List<InlineSpan>) : MarkdownBlock

    data class Paragraph(val spans: List<InlineSpan>) : MarkdownBlock

    data class CodeBlock(val language: String, val code: String) : MarkdownBlock

    /**
     * [items] keeps the flat span lists renderers already use; [indents] holds one nesting
     * depth per item so a list written with indentation can still be drawn nested.
     */
    data class BulletList(
        val items: List<List<InlineSpan>>,
        val indents: List<Int> = List(items.size) { 0 },
    ) : MarkdownBlock {
        /** [items] paired with their nesting depth. */
        val entries: List<ListItem>
            get() = items.mapIndexed { index, spans -> ListItem(indents.getOrElse(index) { 0 }, spans) }
    }

    /** [items] keeps the number and spans of every row; [indents] holds one depth per row. */
    data class OrderedList(
        val items: List<Pair<Int, List<InlineSpan>>>,
        val indents: List<Int> = List(items.size) { 0 },
    ) : MarkdownBlock {
        val entries: List<OrderedListItem>
            get() = items.mapIndexed { index, (number, spans) ->
                OrderedListItem(number, indents.getOrElse(index) { 0 }, spans)
            }
    }

    data class BlockQuote(val spans: List<InlineSpan>) : MarkdownBlock

    /** One GFM pipe table: every cell is already inline-parsed. */
    data class Table(
        val headers: List<List<InlineSpan>>,
        val rows: List<List<List<InlineSpan>>>,
    ) : MarkdownBlock

    data object HorizontalRule : MarkdownBlock
}

/** One bullet row with its nesting depth, for renderers that indent. */
data class ListItem(val indent: Int, val spans: List<InlineSpan>)

/** One numbered row with its nesting depth, for renderers that indent. */
data class OrderedListItem(val number: Int, val indent: Int, val spans: List<InlineSpan>)

sealed interface InlineSpan {
    data class Text(val text: String) : InlineSpan
    data class Bold(val text: String) : InlineSpan
    data class Italic(val text: String) : InlineSpan
    data class Code(val text: String) : InlineSpan

    /** `~~text~~` */
    data class Strikethrough(val text: String) : InlineSpan

    /** `***text***` */
    data class BoldItalic(val text: String) : InlineSpan

    /** `[label](https://example.test)`; only http and https targets are recognised. */
    data class Link(val label: String, val url: String) : InlineSpan
}

private val headingPattern = Regex("^(#{1,6})\\s+(.*)$")
private val bulletPattern = Regex("^([ \\t]*)[-*]\\s+(.*)$")
private val orderedPattern = Regex("^([ \\t]*)(\\d{1,9})\\.\\s+(.*)$")
private val quotePattern = Regex("^\\s*>\\s?(.*)$")
private val horizontalRulePattern = Regex("^\\s*([-*_])(?:\\s*\\1){2,}\\s*$")
private val tableSeparatorPattern = Regex("^:?-{3,}:?$")

fun parseMarkdown(input: String): List<MarkdownBlock> {
    val lines = input.replace("\r\n", "\n").replace('\r', '\n').split('\n')
    val blocks = mutableListOf<MarkdownBlock>()
    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        val trimmed = line.trim()
        when {
            trimmed.isEmpty() -> i++

            trimmed.startsWith("```") -> {
                // An unclosed fence is still a code block: streaming may stop mid-block.
                val language = trimmed.removePrefix("```").trim().substringBefore(' ').take(32)
                val code = mutableListOf<String>()
                i++
                while (i < lines.size && !lines[i].trim().startsWith("```")) {
                    code.add(lines[i])
                    i++
                }
                if (i < lines.size) i++
                blocks.add(MarkdownBlock.CodeBlock(language, code.joinToString("\n")))
            }

            isTableStart(lines, i) -> {
                val headers = parseTableRow(lines[i]).orEmpty()
                i += 2
                val rows = mutableListOf<List<List<InlineSpan>>>()
                while (i < lines.size) {
                    val cells = parseTableRow(lines[i])
                    if (cells == null || lines[i].trim().isEmpty()) break
                    rows.add(normalizeCells(cells, headers.size))
                    i++
                }
                blocks.add(MarkdownBlock.Table(headers.map { parseInline(it.trim()) }, rows))
            }

            horizontalRulePattern.matches(line) -> {
                blocks.add(MarkdownBlock.HorizontalRule)
                i++
            }

            headingPattern.matches(line) -> {
                val match = headingPattern.matchEntire(line)
                if (match != null) {
                    blocks.add(MarkdownBlock.Heading(match.groupValues[1].length, parseInline(match.groupValues[2])))
                }
                i++
            }

            bulletPattern.matches(line) -> {
                val items = mutableListOf<List<InlineSpan>>()
                val indents = mutableListOf<Int>()
                while (i < lines.size) {
                    val match = bulletPattern.matchEntire(lines[i]) ?: break
                    items.add(parseInline(match.groupValues[2].trim()))
                    indents.add(indentLevel(match.groupValues[1]))
                    i++
                }
                blocks.add(MarkdownBlock.BulletList(items, indents))
            }

            orderedPattern.matches(line) -> {
                val items = mutableListOf<Pair<Int, List<InlineSpan>>>()
                val indents = mutableListOf<Int>()
                while (i < lines.size) {
                    val match = orderedPattern.matchEntire(lines[i]) ?: break
                    val number = match.groupValues[2].toIntOrNull() ?: break
                    items.add(number to parseInline(match.groupValues[3].trim()))
                    indents.add(indentLevel(match.groupValues[1]))
                    i++
                }
                blocks.add(MarkdownBlock.OrderedList(items, indents))
            }

            quotePattern.matches(line) -> {
                val quoted = mutableListOf<String>()
                while (i < lines.size) {
                    val match = quotePattern.matchEntire(lines[i]) ?: break
                    quoted.add(match.groupValues[1])
                    i++
                }
                blocks.add(MarkdownBlock.BlockQuote(parseInline(quoted.joinToString("\n"))))
            }

            else -> {
                // The first line is always consumed, so partial input can never loop forever.
                val paragraph = mutableListOf(lines[i].trim())
                i++
                while (i < lines.size && !startsBlock(lines, i)) {
                    paragraph.add(lines[i].trim())
                    i++
                }
                blocks.add(MarkdownBlock.Paragraph(parseInline(paragraph.joinToString("\n"))))
            }
        }
    }
    return blocks
}

private fun startsBlock(lines: List<String>, index: Int): Boolean {
    val line = lines[index]
    if (line.trim().isEmpty()) return true
    return line.trim().startsWith("```") || horizontalRulePattern.matches(line) ||
        headingPattern.matches(line) || bulletPattern.matches(line) || orderedPattern.matches(line) ||
        quotePattern.matches(line) || isTableStart(lines, index)
}

/** Two spaces (or one tab) of indentation per nesting depth. */
private fun indentLevel(prefix: String): Int {
    var columns = 0
    for (character in prefix) columns += if (character == '\t') 4 else 1
    return (columns / 2).coerceIn(0, 8)
}

/** True when [index] starts a table: a pipe row followed by a matching separator row. */
private fun isTableStart(lines: List<String>, index: Int): Boolean {
    if (index + 1 >= lines.size) return false
    val header = parseTableRow(lines[index]) ?: return false
    val separator = parseTableRow(lines[index + 1]) ?: return false
    if (header.isEmpty() || header.size != separator.size) return false
    return separator.all { tableSeparatorPattern.matches(it.trim()) }
}

/** Splits one GFM pipe row into raw cells; null when the line is not a row. */
private fun parseTableRow(line: String): List<String>? {
    val trimmed = line.trim()
    if (!trimmed.contains('|')) return null
    val cells = mutableListOf<String>()
    val current = StringBuilder()
    var escaped = false
    for (character in trimmed) {
        when {
            escaped -> {
                current.append(character)
                escaped = false
            }

            character == '\\' -> escaped = true
            character == '|' -> {
                cells.add(current.toString())
                current.clear()
            }

            else -> current.append(character)
        }
    }
    cells.add(current.toString())
    if (trimmed.startsWith("|") && cells.first().isBlank()) cells.removeAt(0)
    if (trimmed.endsWith("|") && cells.last().isBlank()) cells.removeAt(cells.size - 1)
    return cells
}

private fun normalizeCells(cells: List<String>, width: Int): List<List<InlineSpan>> =
    (0 until width).map { index -> parseInline(cells.getOrNull(index)?.trim().orEmpty()) }

/**
 * Parses bold, italic, strikethrough, links and inline code. Unclosed markers stay literal text
 * so a partially streamed message never loses or corrupts characters.
 */
fun parseInline(input: String): List<InlineSpan> {
    val spans = mutableListOf<InlineSpan>()
    val literal = StringBuilder()
    fun flush() {
        if (literal.isNotEmpty()) {
            spans.add(InlineSpan.Text(literal.toString()))
            literal.clear()
        }
    }
    var i = 0
    while (i < input.length) {
        val character = input[i]
        when {
            character == '`' -> {
                val end = input.indexOf('`', i + 1)
                if (end > i + 1) {
                    flush()
                    spans.add(InlineSpan.Code(input.substring(i + 1, end)))
                    i = end + 1
                } else {
                    literal.append(character)
                    i++
                }
            }

            character == '~' && i + 1 < input.length && input[i + 1] == '~' -> {
                val end = input.indexOf("~~", i + 2)
                if (end > i + 2) {
                    flush()
                    spans.add(InlineSpan.Strikethrough(input.substring(i + 2, end)))
                    i = end + 2
                } else {
                    literal.append(character)
                    i++
                }
            }

            character == '*' && i + 2 < input.length && input[i + 1] == '*' && input[i + 2] == '*' -> {
                val end = input.indexOf("***", i + 3)
                if (end > i + 3) {
                    flush()
                    spans.add(InlineSpan.BoldItalic(input.substring(i + 3, end)))
                    i = end + 3
                } else {
                    literal.append(character)
                    i++
                }
            }

            character == '*' && i + 1 < input.length && input[i + 1] == '*' -> {
                val end = input.indexOf("**", i + 2)
                if (end > i + 2) {
                    flush()
                    spans.add(InlineSpan.Bold(input.substring(i + 2, end)))
                    i = end + 2
                } else {
                    literal.append(character)
                    i++
                }
            }

            character == '*' -> {
                val end = input.indexOf('*', i + 1)
                if (end > i + 1) {
                    flush()
                    spans.add(InlineSpan.Italic(input.substring(i + 1, end)))
                    i = end + 1
                } else {
                    literal.append(character)
                    i++
                }
            }

            character == '[' -> {
                val end = linkEnd(input, i)
                val close = if (end > 0) input.indexOf("](", i + 1) else -1
                if (end > 0 && close > i + 1 && validLinkTarget(input.substring(close + 2, end))) {
                    flush()
                    spans.add(InlineSpan.Link(input.substring(i + 1, close), input.substring(close + 2, end)))
                    i = end + 1
                } else {
                    literal.append(character)
                    i++
                }
            }

            else -> {
                literal.append(character)
                i++
            }
        }
    }
    flush()
    return spans
}

/** Returns the index of the closing bracket of a `[label](url)` pair, or -1 when incomplete. */
private fun linkEnd(input: String, start: Int): Int {
    val label = input.indexOf("](", start + 1)
    if (label <= start + 1) return -1
    return input.indexOf(')', label + 2)
}

/** Only absolute http(s) targets are links; anything else stays literal text. */
private fun validLinkTarget(url: String): Boolean {
    if (url.length > 2048) return false
    if (!url.startsWith("http://") && !url.startsWith("https://")) return false
    return url.all { it.code > 0x20 && it.code != 0x7f }
}
