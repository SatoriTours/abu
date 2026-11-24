package local.abu.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
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
                        else -> MaterialTheme.typography.titleSmall
                    }.copy(fontWeight = FontWeight.SemiBold)
                    SelectionContainer {
                        Text(renderInlineSpans(block.spans, colors), style = style, color = colors.onSurface)
                    }
                }

                is MarkdownBlock.Paragraph -> {
                    SelectionContainer {
                        Text(
                            renderInlineSpans(block.spans, colors),
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
                        block.items.forEach { itemSpans ->
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.Top,
                            ) {
                                Text("•", style = MaterialTheme.typography.bodyLarge, color = colors.primary)
                                SelectionContainer(Modifier.weight(1f)) {
                                    Text(
                                        renderInlineSpans(itemSpans, colors),
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
                        block.items.forEach { (number, itemSpans) ->
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.Top,
                            ) {
                                Text(
                                    "$number.",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = colors.primary,
                                    fontWeight = FontWeight.Medium,
                                )
                                SelectionContainer(Modifier.weight(1f)) {
                                    Text(
                                        renderInlineSpans(itemSpans, colors),
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = colors.onSurface,
                                    )
                                }
                            }
                        }
                    }
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
                                renderInlineSpans(block.spans, colors),
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
            .clip(RoundedCornerShape(12.dp))
            .background(colors.surfaceVariant)
            .border(1.dp, colors.outlineVariant, RoundedCornerShape(12.dp)),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(colors.secondaryContainer.copy(alpha = 0.65f))
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

private fun renderInlineSpans(spans: List<InlineSpan>, colors: ColorScheme): AnnotatedString = buildAnnotatedString {
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
        }
    }
}
