package com.adhook.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.ClickableText
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp

/**
 * Small, dependency-free Markdown renderer for plugin README content.
 *
 * Supported: headings, paragraphs, unordered/ordered lists, block quotes, fenced code,
 * horizontal rules, bold/italic/strikethrough, inline code and http(s) links. Raw HTML,
 * tables and remote images are intentionally not executed or downloaded.
 */
@Composable
fun MarkdownText(markdown: String, modifier: Modifier = Modifier) {
    val blocks = rememberMarkdownBlocks(markdown)
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (block in blocks) {
            when (block) {
                is MarkdownBlock.Heading -> Text(
                    text = inlineMarkdown(block.text),
                    style = when (block.level) {
                        1 -> MaterialTheme.typography.headlineSmall
                        2 -> MaterialTheme.typography.titleLarge
                        3 -> MaterialTheme.typography.titleMedium
                        else -> MaterialTheme.typography.titleSmall
                    },
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                is MarkdownBlock.Paragraph -> InlineMarkdown(block.text)
                is MarkdownBlock.Quote -> Row(Modifier.fillMaxWidth()) {
                    Box(Modifier.width(3.dp).height(20.dp)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)))
                    Spacer(Modifier.width(8.dp))
                    InlineMarkdown(block.text, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                is MarkdownBlock.BulletList -> Column(
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    block.items.forEach { item ->
                        Row(Modifier.fillMaxWidth()) {
                            Text("•", color = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(8.dp))
                            InlineMarkdown(item)
                        }
                    }
                }
                is MarkdownBlock.OrderedList -> Column(
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    block.items.forEachIndexed { index, item ->
                        Row(Modifier.fillMaxWidth()) {
                            Text("${index + 1}.", color = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(8.dp))
                            InlineMarkdown(item)
                        }
                    }
                }
                is MarkdownBlock.Code -> Text(
                    text = block.text,
                    modifier = Modifier.fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(10.dp),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                MarkdownBlock.Rule -> HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                )
            }
        }
    }
}

@Composable
private fun InlineMarkdown(text: String, color: Color = MaterialTheme.colorScheme.onSurface) {
    val uriHandler = LocalUriHandler.current
    val annotated = inlineMarkdown(text)
    ClickableText(
        text = annotated,
        style = MaterialTheme.typography.bodyMedium.copy(color = color),
        onClick = { offset ->
            annotated.getStringAnnotations("URL", offset, offset)
                .firstOrNull()?.item?.let { url ->
                    try { uriHandler.openUri(url) } catch (_: Throwable) { }
                }
        }
    )
}

private sealed interface MarkdownBlock {
    data class Heading(val level: Int, val text: String) : MarkdownBlock
    data class Paragraph(val text: String) : MarkdownBlock
    data class BulletList(val items: List<String>) : MarkdownBlock
    data class OrderedList(val items: List<String>) : MarkdownBlock
    data class Code(val text: String) : MarkdownBlock
    data class Quote(val text: String) : MarkdownBlock
    data object Rule : MarkdownBlock
}

private val headingPattern = Regex("^(#{1,6})\\s+(.*)$")
private val orderedPattern = Regex("^\\s*\\d+[.)]\\s+(.*)$")

@Composable
private fun rememberMarkdownBlocks(markdown: String): List<MarkdownBlock> =
    remember(markdown) { parseMarkdown(markdown) }

private fun parseMarkdown(source: String): List<MarkdownBlock> {
    val lines = source.replace("\r\n", "\n").replace('\r', '\n').split('\n')
    val blocks = mutableListOf<MarkdownBlock>()
    var index = 0
    while (index < lines.size) {
        val line = lines[index]
        val trimmed = line.trim()
        when {
            trimmed.isEmpty() -> index++
            trimmed.startsWith("```") -> {
                index++
                val code = StringBuilder()
                while (index < lines.size && !lines[index].trim().startsWith("```")) {
                    code.appendLine(lines[index])
                    index++
                }
                if (index < lines.size) index++
                blocks += MarkdownBlock.Code(code.toString().trimEnd())
            }
            headingPattern.matches(trimmed) -> {
                val match = headingPattern.matchEntire(trimmed)!!
                blocks += MarkdownBlock.Heading(match.groupValues[1].length, match.groupValues[2])
                index++
            }
            trimmed.matches(Regex("^(?:---|\\*\\*\\*|___)$")) -> {
                blocks += MarkdownBlock.Rule
                index++
            }
            trimmed.startsWith("> ") || trimmed == ">" -> {
                val quote = mutableListOf<String>()
                while (index < lines.size) {
                    val current = lines[index].trim()
                    if (!current.startsWith(">")) break
                    quote += current.removePrefix(">").trimStart()
                    index++
                }
                blocks += MarkdownBlock.Quote(quote.joinToString("\n"))
            }
            trimmed.startsWith("- ") || trimmed.startsWith("* ") || trimmed.startsWith("+ ") -> {
                val items = mutableListOf<String>()
                while (index < lines.size) {
                    val current = lines[index].trim()
                    if (!(current.startsWith("- ") || current.startsWith("* ")
                            || current.startsWith("+ "))) break
                    items += current.substring(2).trim()
                    index++
                }
                blocks += MarkdownBlock.BulletList(items)
            }
            orderedPattern.matches(trimmed) -> {
                val items = mutableListOf<String>()
                while (index < lines.size) {
                    val current = lines[index].trim()
                    val match = orderedPattern.matchEntire(current) ?: break
                    items += match.groupValues[1].trim()
                    index++
                }
                blocks += MarkdownBlock.OrderedList(items)
            }
            else -> {
                val paragraph = mutableListOf<String>()
                while (index < lines.size) {
                    val current = lines[index].trim()
                    if (current.isEmpty() || current.startsWith("```")
                        || headingPattern.matches(current) || current.startsWith("> ")
                        || current.startsWith("- ") || current.startsWith("* ")
                        || current.startsWith("+ ") || orderedPattern.matches(current)
                        || current.matches(Regex("^(?:---|\\*\\*\\*|___)$"))) break
                    paragraph += current
                    index++
                }
                blocks += MarkdownBlock.Paragraph(paragraph.joinToString(" "))
            }
        }
    }
    return blocks
}

private fun inlineMarkdown(source: String): AnnotatedString = buildAnnotatedString {
    var index = 0
    while (index < source.length) {
        if ((source[index] == '!' || source[index] == '[') && source.startsWith("[", index + if (source[index] == '!') 1 else 0)) {
            val image = source[index] == '!'
            val bracket = index + if (image) 1 else 0
            val labelEnd = source.indexOf(']', bracket)
            if (labelEnd > bracket && labelEnd + 1 < source.length && source[labelEnd + 1] == '(') {
                val urlEnd = source.indexOf(')', labelEnd + 2)
                if (urlEnd > labelEnd) {
                    val label = source.substring(bracket + 1, labelEnd).ifBlank { source.substring(labelEnd + 2, urlEnd) }
                    val url = source.substring(labelEnd + 2, urlEnd).trim()
                    val start = length
                    append(label)
                    if (isHttpUrl(url)) {
                        addStringAnnotation("URL", url, start, length)
                        addStyle(SpanStyle(color = Color(0xFF64B5F6),
                            textDecoration = TextDecoration.Underline), start, length)
                    }
                    index = urlEnd + 1
                    continue
                }
            }
        }
        if (source[index] == '`') {
            val end = source.indexOf('`', index + 1)
            if (end > index + 1) {
                val start = length
                append(source.substring(index + 1, end))
                addStyle(SpanStyle(fontFamily = FontFamily.Monospace,
                    background = Color.White.copy(alpha = 0.10f)), start, length)
                index = end + 1
                continue
            }
        }
        val marker = when {
            source.startsWith("**", index) -> "**"
            source.startsWith("__", index) -> "__"
            source.startsWith("~~", index) -> "~~"
            else -> null
        }
        if (marker != null) {
            val end = source.indexOf(marker, index + marker.length)
            if (end > index + marker.length) {
                val start = length
                append(source.substring(index + marker.length, end))
                addStyle(when (marker) {
                    "**", "__" -> SpanStyle(fontWeight = FontWeight.Bold)
                    "~~" -> SpanStyle(textDecoration = TextDecoration.LineThrough)
                    else -> SpanStyle()
                }, start, length)
                index = end + marker.length
                continue
            }
        }
        val single = source[index]
        if (single == '*' || single == '_') {
            val end = source.indexOf(single, index + 1)
            if (end > index + 1) {
                val start = length
                append(source.substring(index + 1, end))
                addStyle(SpanStyle(fontStyle = FontStyle.Italic), start, length)
                index = end + 1
                continue
            }
        }
        append(single)
        index++
    }
}

private fun isHttpUrl(value: String): Boolean =
    value.startsWith("http://") || value.startsWith("https://")
