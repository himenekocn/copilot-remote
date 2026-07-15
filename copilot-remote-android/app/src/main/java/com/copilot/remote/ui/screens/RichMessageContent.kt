package com.copilot.remote.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.copilot.remote.data.ChatMessage
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun RichMessageContent(message: ChatMessage) {
    when (message.kind) {
        "thinking" -> ThinkingBlock(message)
        "tool" -> ToolCallBlock(message)
        else -> MarkdownBody(message.content.ifBlank { if (message.isStreaming) "正在思考…" else "" })
    }
}

@Composable
fun ProcessMessageGroup(messages: List<ChatMessage>) {
    val toolMessages = messages.filter { it.kind == "tool" }
    val hasThinking = messages.any { it.kind == "thinking" }
    val failed = toolMessages.count { it.toolStatus == "error" }
    val running = messages.any { it.isStreaming } || toolMessages.any { it.toolStatus !in setOf("completed", "error") }
    var expanded by remember(messages.firstOrNull()?.id) { mutableStateOf(failed > 0) }
    val summary = buildList {
        if (hasThinking) add("思考")
        if (toolMessages.isNotEmpty()) add("${toolMessages.size} 个工具")
        if (failed > 0) add("$failed 个失败")
    }.joinToString(" · ")
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MiuixTheme.colorScheme.surfaceContainer,
    ) {
        Column {
            Row(
                Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(horizontal = 11.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    if (running) Icons.Default.Pending else if (failed > 0) Icons.Default.Error else Icons.Default.CheckCircle,
                    null,
                    Modifier.size(18.dp),
                    tint = if (failed > 0) MiuixTheme.colorScheme.error else MiuixTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(if (running) "正在执行" else "执行过程", style = MiuixTheme.textStyles.body2, fontWeight = FontWeight.SemiBold)
                    if (summary.isNotBlank()) {
                        Text(summary, style = MiuixTheme.textStyles.footnote2, color = MiuixTheme.colorScheme.onSurfaceSecondary, maxLines = 1)
                    }
                }
                if (running) InfiniteProgressIndicator(Modifier.width(42.dp))
                Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, if (expanded) "收起执行过程" else "展开执行过程", Modifier.size(20.dp))
            }
            AnimatedVisibility(expanded) {
                Column(
                    Modifier.fillMaxWidth().padding(start = 10.dp, end = 10.dp, bottom = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    messages.forEach { message ->
                        when (message.kind) {
                            "thinking" -> ThinkingBlock(message)
                            "tool" -> ToolCallBlock(message)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ThinkingBlock(message: ChatMessage) {
    var expanded by remember(message.id) { mutableStateOf(message.isStreaming) }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MiuixTheme.colorScheme.surfaceContainer,
    ) {
        Column {
            Row(
                Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(horizontal = 11.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Default.Psychology, null, Modifier.size(19.dp), tint = MiuixTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text("思考过程", modifier = Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                if (message.isStreaming) InfiniteProgressIndicator(Modifier.width(48.dp))
                Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, if (expanded) "收起" else "展开", Modifier.size(20.dp))
            }
            AnimatedVisibility(expanded) {
                Box(Modifier.fillMaxWidth().padding(start = 39.dp, end = 12.dp, bottom = 12.dp)) {
                    MarkdownBody(message.content)
                }
            }
        }
    }
}

@Composable
private fun ToolCallBlock(message: ChatMessage) {
    var expanded by remember(message.id) { mutableStateOf(message.toolStatus == "error") }
    val statusText = when (message.toolStatus) {
        "completed" -> "已完成"
        "error" -> "失败"
        else -> "运行中"
    }
    val statusIcon = when (message.toolStatus) {
        "completed" -> Icons.Default.CheckCircle
        "error" -> Icons.Default.Error
        else -> Icons.Default.Pending
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MiuixTheme.colorScheme.surfaceContainer,
    ) {
        Column {
            Row(
                Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(horizontal = 11.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Default.Build, null, Modifier.size(19.dp), tint = MiuixTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(message.toolName.ifBlank { "工具调用" }, fontWeight = FontWeight.SemiBold)
                    message.content.takeIf { it.isNotBlank() && it != message.toolName }?.let {
                        Text(it, style = MiuixTheme.textStyles.footnote2, color = MiuixTheme.colorScheme.onSurfaceSecondary, maxLines = 1)
                    }
                }
                Icon(statusIcon, null, Modifier.size(17.dp), tint = if (message.toolStatus == "error") MiuixTheme.colorScheme.error else MiuixTheme.colorScheme.primary)
                Spacer(Modifier.width(4.dp))
                Text(statusText, style = MiuixTheme.textStyles.footnote2, color = MiuixTheme.colorScheme.onSurfaceSecondary)
                Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, if (expanded) "收起" else "展开", Modifier.size(20.dp))
            }
            AnimatedVisibility(expanded) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (message.toolInput.isNotBlank()) {
                        Text("输入", style = MiuixTheme.textStyles.footnote1, fontWeight = FontWeight.SemiBold)
                        CodeBlock("json", message.toolInput)
                    }
                    if (message.toolOutput.isNotBlank()) {
                        Text("结果", style = MiuixTheme.textStyles.footnote1, fontWeight = FontWeight.SemiBold)
                        CodeBlock("text", message.toolOutput)
                    }
                    if (message.toolInput.isBlank() && message.toolOutput.isBlank() && message.content.isNotBlank()) {
                        MarkdownBody(message.content)
                    }
                }
            }
        }
    }
}

private data class MarkdownSegment(val text: String, val language: String? = null)

@Composable
fun MarkdownBody(markdown: String, modifier: Modifier = Modifier) {
    val segments = remember(markdown) { splitMarkdown(markdown) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(7.dp)) {
        segments.forEach { segment ->
            if (segment.language != null) {
                CodeBlock(segment.language, segment.text)
            } else {
                val lines = segment.text.lines()
                var lineIndex = 0
                while (lineIndex < lines.size) {
                    val raw = lines[lineIndex]
                    val line = raw.trimEnd()
                    if (line.contains('|') && lineIndex + 1 < lines.size && isMarkdownTableSeparator(lines[lineIndex + 1])) {
                        val tableLines = mutableListOf(line, lines[lineIndex + 1])
                        lineIndex += 2
                        while (lineIndex < lines.size && lines[lineIndex].contains('|') && lines[lineIndex].isNotBlank()) {
                            tableLines += lines[lineIndex]
                            lineIndex++
                        }
                        MarkdownTable(tableLines)
                        continue
                    }
                    when {
                        line.isBlank() -> Spacer(Modifier.height(2.dp))
                        line.startsWith("### ") -> SelectionContainer { Text(inlineMarkdown(line.removePrefix("### ")), style = MiuixTheme.textStyles.title4, fontWeight = FontWeight.Bold) }
                        line.startsWith("## ") -> SelectionContainer { Text(inlineMarkdown(line.removePrefix("## ")), style = MiuixTheme.textStyles.title3, fontWeight = FontWeight.Bold) }
                        line.startsWith("# ") -> SelectionContainer { Text(inlineMarkdown(line.removePrefix("# ")), style = MiuixTheme.textStyles.title2, fontWeight = FontWeight.Bold) }
                        line.matches(Regex("^\\s*[-*+]\\s+.*")) -> Row(Modifier.padding(start = 4.dp)) {
                            Text("•", modifier = Modifier.width(20.dp), color = MiuixTheme.colorScheme.primary)
                            SelectionContainer { Text(inlineMarkdown(line.replaceFirst(Regex("^\\s*[-*+]\\s+"), "")), modifier = Modifier.weight(1f)) }
                        }
                        line.matches(Regex("^\\s*\\d+[.)]\\s+.*")) -> {
                            val marker = Regex("^\\s*(\\d+[.)])\\s+").find(line)
                            Row(Modifier.padding(start = 4.dp)) {
                                Text(marker?.groupValues?.get(1).orEmpty(), modifier = Modifier.width(28.dp), color = MiuixTheme.colorScheme.primary)
                                SelectionContainer { Text(inlineMarkdown(line.substring(marker?.range?.last?.plus(1) ?: 0).trimStart()), modifier = Modifier.weight(1f)) }
                            }
                        }
                        line.startsWith("> ") -> Surface(shape = RoundedCornerShape(8.dp), color = MiuixTheme.colorScheme.surfaceContainer) {
                            SelectionContainer { Text(inlineMarkdown(line.removePrefix("> ")), modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp), color = MiuixTheme.colorScheme.onSurfaceSecondary) }
                        }
                        else -> SelectionContainer { Text(inlineMarkdown(line), style = MiuixTheme.textStyles.body2) }
                    }
                    lineIndex++
                }
            }
        }
    }
}

@Composable
private fun MarkdownTable(lines: List<String>) {
    val rows = lines.filterIndexed { index, _ -> index != 1 }.map(::markdownTableCells)
    val columnCount = rows.maxOfOrNull { it.size } ?: return
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MiuixTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MiuixTheme.colorScheme.dividerLine),
        modifier = Modifier.fillMaxWidth().horizontalScroll(androidx.compose.foundation.rememberScrollState()),
    ) {
        Column {
            rows.forEachIndexed { rowIndex, cells ->
                Row {
                    repeat(columnCount) { columnIndex ->
                        Box(
                            Modifier.width(132.dp).padding(horizontal = 9.dp, vertical = 7.dp),
                        ) {
                            SelectionContainer {
                                Text(
                                    inlineMarkdown(cells.getOrElse(columnIndex) { "" }),
                                    style = MiuixTheme.textStyles.footnote1,
                                    fontWeight = if (rowIndex == 0) FontWeight.Bold else FontWeight.Normal,
                                )
                            }
                        }
                    }
                }
                if (rowIndex < rows.lastIndex) HorizontalDivider()
            }
        }
    }
}

private fun isMarkdownTableSeparator(line: String): Boolean = markdownTableCells(line).let { cells ->
    cells.isNotEmpty() && cells.all { it.trim().matches(Regex(":?-{3,}:?")) }
}

private fun markdownTableCells(line: String): List<String> = line.trim().trim('|').split('|').map { it.trim() }

@Composable
private fun CodeBlock(language: String, code: String) {
    val clipboard = LocalClipboardManager.current
    Surface(shape = RoundedCornerShape(12.dp), color = MiuixTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
        Column {
            Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(language.ifBlank { "code" }, modifier = Modifier.weight(1f), style = MiuixTheme.textStyles.footnote2, color = MiuixTheme.colorScheme.onSurfaceSecondary)
                IconButton(onClick = { clipboard.setText(AnnotatedString(code)) }, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Default.ContentCopy, "复制代码", Modifier.size(18.dp))
                }
            }
            SelectionContainer {
                Text(
                    syntaxHighlight(code),
                    modifier = Modifier.fillMaxWidth().horizontalScroll(androidx.compose.foundation.rememberScrollState()).padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                    fontFamily = FontFamily.Monospace,
                    style = MiuixTheme.textStyles.body2,
                )
            }
        }
    }
}

private fun splitMarkdown(markdown: String): List<MarkdownSegment> {
    if (markdown.isBlank()) return emptyList()
    val result = mutableListOf<MarkdownSegment>()
    val text = StringBuilder()
    val code = StringBuilder()
    var language: String? = null
    fun flushText() { if (text.isNotEmpty()) { result += MarkdownSegment(text.toString().trimEnd()); text.clear() } }
    fun flushCode() { result += MarkdownSegment(code.toString().trimEnd(), language.orEmpty()); code.clear(); language = null }
    for (line in markdown.lines()) {
        if (line.trimStart().startsWith("```")) {
            if (language == null) {
                flushText()
                language = line.trim().removePrefix("```").trim()
            } else flushCode()
        } else if (language != null) code.appendLine(line) else text.appendLine(line)
    }
    if (language != null) flushCode() else flushText()
    return result
}

private fun inlineMarkdown(value: String): AnnotatedString = buildAnnotatedString {
    var index = 0
    while (index < value.length) {
        when {
            value[index] == '[' -> {
                val labelEnd = value.indexOf(']', index + 1)
                val urlStart = if (labelEnd > index && labelEnd + 1 < value.length && value[labelEnd + 1] == '(') labelEnd + 2 else -1
                val urlEnd = if (urlStart > 0) value.indexOf(')', urlStart) else -1
                if (urlEnd > urlStart) {
                    withStyle(SpanStyle(color = androidx.compose.ui.graphics.Color(0xFF3482F6), textDecoration = TextDecoration.Underline)) {
                        append(value.substring(index + 1, labelEnd))
                    }
                    index = urlEnd + 1
                } else append(value[index++])
            }
            value.startsWith("**", index) -> {
                val end = value.indexOf("**", index + 2)
                if (end > index) {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(value.substring(index + 2, end)) }
                    index = end + 2
                } else { append(value[index++]) }
            }
            value[index] == '`' -> {
                val end = value.indexOf('`', index + 1)
                if (end > index) {
                    withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = androidx.compose.ui.graphics.Color(0x14000000))) { append(value.substring(index + 1, end)) }
                    index = end + 1
                } else { append(value[index++]) }
            }
            else -> append(value[index++])
        }
    }
}

private fun syntaxHighlight(code: String): AnnotatedString = buildAnnotatedString {
    append(code)
    val occupied = BooleanArray(code.length)
    fun style(regex: Regex, span: SpanStyle) {
        regex.findAll(code).forEach { match ->
            if (match.range.all { it in occupied.indices && !occupied[it] }) {
                addStyle(span, match.range.first, match.range.last + 1)
                match.range.forEach { if (it in occupied.indices) occupied[it] = true }
            }
        }
    }
    style(Regex("(?m)//.*$|/\\*[\\s\\S]*?\\*/|(?m)#.*$"), SpanStyle(color = androidx.compose.ui.graphics.Color(0xFF6A9955)))
    style(Regex("\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*'"), SpanStyle(color = androidx.compose.ui.graphics.Color(0xFFB05A42)))
    style(Regex("\\b(?:class|fun|val|var|if|else|when|for|while|return|import|package|private|public|internal|interface|data|object|const|let|async|await|function|export|from|def|try|catch|throw|new|true|false|null|None|True|False)\\b"), SpanStyle(color = androidx.compose.ui.graphics.Color(0xFF7950B2), fontWeight = FontWeight.SemiBold))
    style(Regex("\\b(?:0x[0-9a-fA-F]+|\\d+(?:\\.\\d+)?)\\b"), SpanStyle(color = androidx.compose.ui.graphics.Color(0xFF1976A3)))
}
