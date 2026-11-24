package local.abu.ui.components

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import local.abu.ui.model.InlineSpan
import local.abu.ui.model.MarkdownBlock
import local.abu.ui.model.parseMarkdown

@Composable
internal fun MarkdownContent(
    text: String,
    onCopyCode: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val blocks = remember(text) { parseMarkdown(text) }
    val colors = MaterialTheme.colorScheme
    val context = LocalContext.current
    var pendingLink by remember { mutableStateOf<String?>(null) }

    pendingLink?.let { url ->
        AlertDialog(
            onDismissRequest = { pendingLink = null },
            containerColor = colors.surface,
            shape = RoundedCornerShape(22.dp),
            title = { Text("打开外部链接？", fontWeight = FontWeight.SemiBold) },
            text = { Text("将在系统浏览器中打开以下地址：\n$url", color = colors.onSurfaceVariant) },
            dismissButton = { TextButton(onClick = { pendingLink = null }) { Text("取消") } },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingLink = null
                        if (url.startsWith("https://") || url.startsWith("http://")) {
                            runCatching {
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                context.startActivity(intent)
                            }
                        }
                    },
                ) { Text("打开") }
            },
        )
    }

    val onLinkClick: (String) -> Unit = { url ->
        if (url.startsWith("https://") || url.startsWith("http://")) {
            pendingLink = url
        }
    }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (blocks.isEmpty()) {
            SelectionContainer {
                Text(text, style = MaterialTheme.typography.bodyLarge, color = colors.onSurface)
            }
            return@Column
        }
        blocks.forEach { block ->
            when (block) {
                is MarkdownBlock.Heading -> {
                    val style = when (block.level) {
                        1 -> MaterialTheme.typography.titleLarge
                        2 -> MaterialTheme.typography.titleMedium
                        3 -> MaterialTheme.typography.titleSmall
                        4 -> MaterialTheme.typography.bodyLarge
                        else -> MaterialTheme.typography.bodyMedium
                    }.copy(fontWeight = FontWeight.SemiBold)
                    val headingColor = if (block.level >= 5) colors.onSurfaceVariant else colors.onSurface
                    SelectionContainer {
                        Text(renderInlineSpans(block.spans, colors, onLinkClick), style = style, color = headingColor)
                    }
                }

                is MarkdownBlock.Paragraph -> {
                    SelectionContainer {
                        Text(
                            renderInlineSpans(block.spans, colors, onLinkClick),
                            style = MaterialTheme.typography.bodyLarge,
                            color = colors.onSurface,
                        )
                    }
                }

                is MarkdownBlock.CodeBlock -> {
                    CodeBlockCard(block.language, block.code, onCopyCode)
                }

                is MarkdownBlock.BulletList -> {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        block.entries.forEach { entry ->
                            val marker = when (entry.indent % 3) {
                                0 -> "•"
                                1 -> "◦"
                                else -> "▪"
                            }
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(start = (entry.indent.coerceAtMost(6) * 16).dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.Top,
                            ) {
                                Text(marker, style = MaterialTheme.typography.bodyLarge, color = colors.primary)
                                SelectionContainer(Modifier.weight(1f)) {
                                    Text(
                                        renderInlineSpans(entry.spans, colors, onLinkClick),
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = colors.onSurface,
                                    )
                                }
                            }
                        }
                    }
                }

                is MarkdownBlock.OrderedList -> {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        block.entries.forEach { entry ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(start = (entry.indent.coerceAtMost(6) * 16).dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.Top,
                            ) {
                                Text(
                                    "${entry.number}.",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = colors.primary,
                                    fontWeight = FontWeight.Medium,
                                )
                                SelectionContainer(Modifier.weight(1f)) {
                                    Text(
                                        renderInlineSpans(entry.spans, colors, onLinkClick),
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = colors.onSurface,
                                    )
                                }
                            }
                        }
                    }
                }

                MarkdownBlock.HorizontalRule -> {
                    HorizontalDivider(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        thickness = 0.75.dp,
                        color = colors.outlineVariant.copy(alpha = 0.8f),
                    )
                }

                is MarkdownBlock.Table -> {
                    MarkdownTableCard(block, colors, onLinkClick)
                }

                is MarkdownBlock.BlockQuote -> {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .height(IntrinsicSize.Min)
                            .clip(RoundedCornerShape(8.dp))
                            .background(colors.surfaceVariant.copy(alpha = 0.55f))
                            .padding(end = 10.dp),
                    ) {
                        Box(
                            Modifier
                                .width(3.dp)
                                .fillMaxHeight()
                                .background(colors.primary),
                        )
                        SelectionContainer(Modifier.padding(horizontal = 10.dp, vertical = 7.dp)) {
                            Text(
                                renderInlineSpans(block.spans, colors, onLinkClick),
                                style = MaterialTheme.typography.bodyMedium,
                                color = colors.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MarkdownTableCard(
    table: MarkdownBlock.Table,
    colors: ColorScheme,
    onLinkClick: (String) -> Unit,
) {
    val shape = RoundedCornerShape(12.dp)
    val columnCount = maxOf(table.headers.size, table.rows.maxOfOrNull { it.size } ?: 0).coerceAtLeast(1)
    val stretchToWidth = columnCount <= 3
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val colWidth = if (stretchToWidth) null else maxOf(maxWidth / columnCount, 108.dp)
        val scrollModifier = if (stretchToWidth) Modifier else Modifier.horizontalScroll(rememberScrollState())
        val contentModifier = if (colWidth == null) Modifier.fillMaxWidth() else Modifier.width(colWidth * columnCount)
        Box(
            Modifier
                .fillMaxWidth()
                .clip(shape)
                .border(0.75.dp, colors.outlineVariant.copy(alpha = 0.85f), shape)
                .then(scrollModifier),
        ) {
            Column(contentModifier) {
                TableRow(
                    cells = table.headers,
                    columnCount = columnCount,
                    colWidth = colWidth,
                    colors = colors,
                    header = true,
                    onLinkClick = onLinkClick,
                    modifier = Modifier.background(colors.secondaryContainer.copy(alpha = 0.72f)),
                )
                table.rows.forEachIndexed { rowIndex, row ->
                    HorizontalDivider(thickness = 0.5.dp, color = colors.outlineVariant.copy(alpha = 0.65f))
                    val stripe = if (rowIndex % 2 == 1) colors.surfaceVariant.copy(alpha = 0.32f) else colors.surface
                    TableRow(
                        cells = row,
                        columnCount = columnCount,
                        colWidth = colWidth,
                        colors = colors,
                        header = false,
                        onLinkClick = onLinkClick,
                        modifier = Modifier.background(stripe),
                    )
                }
            }
        }
    }
}

@Composable
private fun TableRow(
    cells: List<List<InlineSpan>>,
    columnCount: Int,
    colWidth: androidx.compose.ui.unit.Dp?,
    colors: ColorScheme,
    header: Boolean,
    onLinkClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        for (col in 0 until columnCount) {
            val spans = cells.getOrElse(col) { emptyList() }
            val cellWidth = if (colWidth == null) Modifier.weight(1f) else Modifier.width(colWidth)
            SelectionContainer(
                cellWidth.padding(horizontal = 12.dp, vertical = 9.dp),
            ) {
                Text(
                    renderInlineSpans(spans, colors, onLinkClick),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (header) colors.onSurface else colors.onSurfaceVariant,
                    fontWeight = if (header) FontWeight.SemiBold else FontWeight.Normal,
                )
            }
        }
    }
}

@Composable
private fun CodeBlockCard(
    language: String,
    code: String,
    onCopyCode: (String) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    var copied by remember(code) { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1_500)
            copied = false
        }
    }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(colors.surface)
            .border(0.75.dp, colors.outlineVariant, RoundedCornerShape(14.dp)),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(colors.surfaceVariant.copy(alpha = 0.72f))
                .padding(start = 12.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                language.ifBlank { "代码" },
                Modifier.weight(1f),
                style = MaterialTheme.typography.labelMedium,
                color = colors.onSurfaceVariant,
                fontFamily = FontFamily.Monospace,
            )
            TextButton(
                onClick = {
                    onCopyCode(code)
                    copied = true
                },
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
            ) {
                Text(
                    if (copied) "已复制" else "复制代码",
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
        SelectionContainer(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(12.dp),
        ) {
            Text(
                code,
                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                color = colors.onSurface,
            )
        }
    }
}

private fun renderInlineSpans(
    spans: List<InlineSpan>,
    colors: ColorScheme,
    onLinkClick: (String) -> Unit,
): AnnotatedString = buildAnnotatedString {
    spans.forEach { span ->
        when (span) {
            is InlineSpan.Text -> append(span.text)
            is InlineSpan.Bold -> withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) {
                append(span.text)
            }
            is InlineSpan.Italic -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                append(span.text)
            }
            is InlineSpan.Code -> withStyle(
                SpanStyle(
                    fontFamily = FontFamily.Monospace,
                    background = colors.surfaceVariant,
                    color = colors.primary,
                ),
            ) {
                append(span.text)
            }
            is InlineSpan.Strikethrough -> withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) {
                append(span.text)
            }
            is InlineSpan.BoldItalic -> withStyle(
                SpanStyle(fontWeight = FontWeight.SemiBold, fontStyle = FontStyle.Italic),
            ) {
                append(span.text)
            }
            is InlineSpan.Link -> withLink(
                LinkAnnotation.Clickable(
                    tag = "abu-link",
                    styles = TextLinkStyles(
                        style = SpanStyle(color = colors.primary, textDecoration = TextDecoration.Underline),
                    ),
                    linkInteractionListener = { onLinkClick(span.url) },
                ),
            ) {
                append(span.label)
            }
        }
    }
}
