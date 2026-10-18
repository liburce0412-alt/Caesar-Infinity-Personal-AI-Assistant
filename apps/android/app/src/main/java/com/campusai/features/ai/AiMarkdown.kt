package com.campusai.features.ai

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.campusai.core.designsystem.SpectraColors
import java.net.URI

internal sealed interface AiMarkdownBlock {
    data class Paragraph(val text: String) : AiMarkdownBlock
    data class Heading(val level: Int, val text: String) : AiMarkdownBlock
    data class ListItem(val marker: String, val text: String) : AiMarkdownBlock
    data class Quote(val text: String) : AiMarkdownBlock
    data class Code(val language: String, val code: String) : AiMarkdownBlock
    data object Divider : AiMarkdownBlock
}

/**
 * Small, deterministic Markdown subset for streamed chat output. It never resolves URLs,
 * downloads images, opens a WebView, or creates link previews.
 */
internal fun parseAiMarkdown(value: String): List<AiMarkdownBlock> {
    val lines = value.replace("\r\n", "\n").replace('\r', '\n').split('\n')
    val blocks = mutableListOf<AiMarkdownBlock>()
    val paragraph = mutableListOf<String>()
    fun flushParagraph() {
        if (paragraph.isNotEmpty()) {
            blocks += AiMarkdownBlock.Paragraph(paragraph.joinToString("\n").trimEnd())
            paragraph.clear()
        }
    }

    var index = 0
    while (index < lines.size) {
        val line = lines[index]
        val trimmedStart = line.trimStart()
        val fence = when {
            trimmedStart.startsWith("```") -> "```"
            trimmedStart.startsWith("~~~") -> "~~~"
            else -> null
        }
        if (fence != null) {
            flushParagraph()
            val language = trimmedStart.removePrefix(fence).trim().take(40)
            val code = mutableListOf<String>()
            index += 1
            while (index < lines.size && !lines[index].trimStart().startsWith(fence)) {
                code += lines[index]
                index += 1
            }
            if (index < lines.size) index += 1
            blocks += AiMarkdownBlock.Code(language, code.joinToString("\n"))
            continue
        }
        val heading = HEADING.matchEntire(line)
        val unordered = UNORDERED_LIST.matchEntire(line)
        val ordered = ORDERED_LIST.matchEntire(line)
        when {
            line.isBlank() -> flushParagraph()
            heading != null -> {
                flushParagraph()
                blocks += AiMarkdownBlock.Heading(heading.groupValues[1].length, heading.groupValues[2])
            }
            HORIZONTAL_RULE.matches(line) -> {
                flushParagraph()
                blocks += AiMarkdownBlock.Divider
            }
            unordered != null -> {
                flushParagraph()
                blocks += AiMarkdownBlock.ListItem("•", unordered.groupValues[1])
            }
            ordered != null -> {
                flushParagraph()
                blocks += AiMarkdownBlock.ListItem("${ordered.groupValues[1]}.", ordered.groupValues[2])
            }
            trimmedStart.startsWith(">") -> {
                flushParagraph()
                blocks += AiMarkdownBlock.Quote(trimmedStart.removePrefix(">").trimStart())
            }
            else -> paragraph += line
        }
        index += 1
    }
    flushParagraph()
    return blocks
}

@Composable
internal fun AiMarkdownText(value: String, modifier: Modifier = Modifier) {
    val blocks = remember(value) { parseAiMarkdown(value) }
    val contentColor = MaterialTheme.colorScheme.onSurface
    val quietColor = contentColor.copy(alpha = .72f)
    val inlineCodeBackground = contentColor.copy(alpha = .075f)
    SelectionContainer(modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            blocks.forEachIndexed { index, block ->
                key(index, block::class) {
                    when (block) {
                        is AiMarkdownBlock.Paragraph -> Text(
                            inlineAiMarkdown(block.text, contentColor, inlineCodeBackground),
                            style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 24.sp),
                        )
                        is AiMarkdownBlock.Heading -> Text(
                            inlineAiMarkdown(block.text, contentColor, inlineCodeBackground),
                            style = when (block.level) {
                                1 -> MaterialTheme.typography.headlineSmall
                                2 -> MaterialTheme.typography.titleLarge
                                else -> MaterialTheme.typography.titleMedium
                            },
                            fontWeight = FontWeight.SemiBold,
                        )
                        is AiMarkdownBlock.ListItem -> Row(
                            verticalAlignment = Alignment.Top,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(block.marker, modifier = Modifier.width(24.dp), color = SpectraColors.Focus)
                            Text(
                                inlineAiMarkdown(block.text, contentColor, inlineCodeBackground),
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 24.sp),
                            )
                        }
                        is AiMarkdownBlock.Quote -> Row(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.Top,
                        ) {
                            Box(
                                Modifier
                                    .width(3.dp)
                                    .height(24.dp)
                                    .background(SpectraColors.Focus, RoundedCornerShape(2.dp)),
                            )
                            Text(
                                inlineAiMarkdown(block.text, quietColor, inlineCodeBackground),
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyLarge.copy(fontStyle = FontStyle.Italic),
                            )
                        }
                        is AiMarkdownBlock.Code -> AiCodeBlock(block)
                        AiMarkdownBlock.Divider -> HorizontalDivider(color = contentColor.copy(alpha = .12f))
                    }
                }
            }
        }
    }
}

@Composable
private fun AiCodeBlock(block: AiMarkdownBlock.Code) {
    val contentColor = MaterialTheme.colorScheme.onSurface
    val shape = RoundedCornerShape(12.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .background(contentColor.copy(alpha = .055f), shape)
            .border(1.dp, contentColor.copy(alpha = .10f), shape)
            .padding(vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        if (block.language.isNotBlank()) {
            Text(
                block.language,
                modifier = Modifier.padding(horizontal = 12.dp),
                style = MaterialTheme.typography.labelSmall,
                color = SpectraColors.Focus,
                maxLines = 1,
            )
        }
        Text(
            block.code,
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp),
            style = MaterialTheme.typography.bodyMedium.copy(
                fontFamily = FontFamily.Monospace,
                lineHeight = 20.sp,
            ),
            color = contentColor,
        )
    }
}

/** Parse only web destinations; the platform UriHandler opens them only after a user click. */
internal fun aiMarkdownWebUrl(value: String): String? = runCatching {
    val uri = URI(value)
    val scheme = uri.scheme?.lowercase() ?: return@runCatching null
    if (scheme !in setOf("http", "https") || uri.host.isNullOrBlank() || uri.rawUserInfo != null ||
        (uri.port != -1 && uri.port !in 1..65535)) return@runCatching null
    scheme + value.substring(uri.scheme.length)
}.getOrNull()

internal fun inlineAiMarkdown(text: String, contentColor: Color, codeBackground: Color): AnnotatedString =
    buildAnnotatedString {
        var index = 0
        while (index < text.length) {
            if (text[index] == '\\' && index + 1 < text.length) {
                append(text[index + 1])
                index += 2
                continue
            }
            val marker = when {
                text.startsWith("**", index) -> "**"
                text.startsWith("__", index) -> "__"
                text.startsWith("~~", index) -> "~~"
                text[index] == '`' -> "`"
                text[index] == '*' -> "*"
                text[index] == '_' -> "_"
                else -> null
            }
            if (marker != null) {
                val closing = text.indexOf(marker, index + marker.length)
                if (closing > index + marker.length) {
                    val inner = text.substring(index + marker.length, closing)
                    val style = when (marker) {
                        "**", "__" -> SpanStyle(fontWeight = FontWeight.Bold)
                        "~~" -> SpanStyle(textDecoration = TextDecoration.LineThrough)
                        "`" -> SpanStyle(fontFamily = FontFamily.Monospace, background = codeBackground)
                        else -> SpanStyle(fontStyle = FontStyle.Italic)
                    }
                    pushStyle(style)
                    append(inner)
                    pop()
                    index = closing + marker.length
                    continue
                }
            }
            if (text[index] == '[') {
                val labelEnd = text.indexOf(']', index + 1)
                val targetStart = labelEnd + 1
                if (labelEnd > index + 1 && targetStart < text.length && text[targetStart] == '(') {
                    val targetEnd = text.indexOf(')', targetStart + 1)
                    if (targetEnd > targetStart + 1) {
                        val label = text.substring(index + 1, labelEnd)
                        val url = aiMarkdownWebUrl(text.substring(targetStart + 1, targetEnd))
                        if (url != null) {
                            withLink(LinkAnnotation.Url(url, TextLinkStyles(
                                style = SpanStyle(color = SpectraColors.Focus, textDecoration = TextDecoration.Underline),
                            ))) { append(label) }
                        } else {
                            pushStyle(SpanStyle(color = contentColor))
                            append(label)
                            pop()
                        }
                        index = targetEnd + 1
                        continue
                    }
                }
            }
            pushStyle(SpanStyle(color = contentColor))
            append(text[index])
            pop()
            index += 1
        }
    }

private val HEADING = Regex("^\\s*(#{1,6})\\s+(.+?)\\s*$")
private val UNORDERED_LIST = Regex("^\\s*[-+*]\\s+(.+?)\\s*$")
private val ORDERED_LIST = Regex("^\\s*(\\d{1,4})[.)]\\s+(.+?)\\s*$")
private val HORIZONTAL_RULE = Regex("^\\s*((-{3,})|(\\*{3,})|(_{3,}))\\s*$")
