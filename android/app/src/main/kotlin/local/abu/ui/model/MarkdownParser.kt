package local.abu.ui.model

/**
 * Minimal, allocation-cheap Markdown model for chat rendering. It is pure Kotlin so it can be
 * unit tested without Compose or Android, and it never throws on partial streaming input.
 */
sealed interface MarkdownBlock {
    data class Heading(val level: Int, val spans: List<InlineSpan>) : MarkdownBlock
    data class Paragraph(val spans: List<InlineSpan>) : MarkdownBlock
    data class CodeBlock(val language: String, val code: String) : MarkdownBlock
    data class BulletList(val items: List<List<InlineSpan>>) : MarkdownBlock
    data class OrderedList(val items: List<Pair<Int, List<InlineSpan>>>) : MarkdownBlock
    data class BlockQuote(val spans: List<InlineSpan>) : MarkdownBlock
}

sealed interface InlineSpan {
    data class Text(val text: String) : InlineSpan
    data class Bold(val text: String) : InlineSpan
    data class Italic(val text: String) : InlineSpan
    data class Code(val text: String) : InlineSpan
}

private val headingPattern = Regex("^(#{1,3})\\s+(.*)$")
private val bulletPattern = Regex("^\\s*[-*]\\s+(.*)$")
private val orderedPattern = Regex("^\\s*(\\d{1,9})\\.\\s+(.*)$")
private val quotePattern = Regex("^\\s*>\\s?(.*)$")

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

            headingPattern.matches(line) -> {
                val match = headingPattern.matchEntire(line)!!
                blocks.add(MarkdownBlock.Heading(match.groupValues[1].length, parseInline(match.groupValues[2])))
                i++
            }

            bulletPattern.matches(line) -> {
                val items = mutableListOf<List<InlineSpan>>()
                while (i < lines.size) {
                    val match = bulletPattern.matchEntire(lines[i]) ?: break
                    items.add(parseInline(match.groupValues[1].trim()))
                    i++
                }
                blocks.add(MarkdownBlock.BulletList(items))
            }

            orderedPattern.matches(line) -> {
                val items = mutableListOf<Pair<Int, List<InlineSpan>>>()
                while (i < lines.size) {
                    val match = orderedPattern.matchEntire(lines[i]) ?: break
                    val number = match.groupValues[1].toIntOrNull() ?: break
                    items.add(number to parseInline(match.groupValues[2].trim()))
                    i++
                }
                blocks.add(MarkdownBlock.OrderedList(items))
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
                val paragraph = mutableListOf<String>()
                while (i < lines.size && !startsBlock(lines[i])) {
                    paragraph.add(lines[i].trim())
                    i++
                }
                blocks.add(MarkdownBlock.Paragraph(parseInline(paragraph.joinToString("\n"))))
            }
        }
    }
    return blocks
}

private fun startsBlock(line: String): Boolean {
    val trimmed = line.trim()
    return trimmed.isEmpty() || trimmed.startsWith("```") ||
        headingPattern.matches(line) || bulletPattern.matches(line) ||
        orderedPattern.matches(line) || quotePattern.matches(line)
}

/**
 * Parses bold, italic and inline code. Unclosed markers stay literal text so a partially
 * streamed message never loses or corrupts characters.
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
        val c = input[i]
        when {
            c == '`' -> {
                val end = input.indexOf('`', i + 1)
                if (end > i + 1) {
                    flush()
                    spans.add(InlineSpan.Code(input.substring(i + 1, end)))
                    i = end + 1
                } else {
                    literal.append(c)
                    i++
                }
            }

            c == '*' && i + 1 < input.length && input[i + 1] == '*' -> {
                val end = input.indexOf("**", i + 2)
                if (end > i + 2) {
                    flush()
                    spans.add(InlineSpan.Bold(input.substring(i + 2, end)))
                    i = end + 2
                } else {
                    literal.append(c)
                    i++
                }
            }

            c == '*' -> {
                val end = input.indexOf('*', i + 1)
                if (end > i + 1) {
                    flush()
                    spans.add(InlineSpan.Italic(input.substring(i + 1, end)))
                    i = end + 1
                } else {
                    literal.append(c)
                    i++
                }
            }

            else -> {
                literal.append(c)
                i++
            }
        }
    }
    flush()
    return spans
}
