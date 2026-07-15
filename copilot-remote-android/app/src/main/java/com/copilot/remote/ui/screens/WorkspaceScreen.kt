package com.copilot.remote.ui.screens

import top.yukonga.miuix.kmp.theme.MiuixTheme

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.copilot.remote.data.EditorInfo
import com.copilot.remote.data.WorkspaceInfo
import com.copilot.remote.ui.components.*
import com.copilot.remote.viewmodel.CopilotViewModel
import top.yukonga.miuix.kmp.basic.*

@Composable
fun WorkspaceScreen(viewModel: CopilotViewModel) {
    val state by viewModel.uiState.collectAsState()
    var startLine by remember { mutableStateOf("1") }
    var startColumn by remember { mutableStateOf("1") }
    var endLine by remember { mutableStateOf("1") }
    var endColumn by remember { mutableStateOf("1") }

    LaunchedEffect(Unit) { viewModel.refreshWorkspace() }

    Box(Modifier.fillMaxSize()) {
        val ws = state.workspaceInfo
        if (ws == null) {
            LoadingIndicator(text = "正在加载工作区…")
            return@Box
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Workspace info card
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    cornerRadius = 16.dp,
                    colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceContainer, contentColor = MiuixTheme.colorScheme.onSurface),
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Folder, contentDescription = null, tint = MiuixTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(ws.name, style = MiuixTheme.textStyles.title1, fontWeight = FontWeight.Bold)
                        }
                        ws.folders.forEach { folder ->
                            Text("  ${folder.name}", style = MiuixTheme.textStyles.footnote1, fontFamily = FontFamily.Monospace, color = MiuixTheme.colorScheme.onSurfaceSecondary)
                        }
                    }
                }
            }

            // Active editor
            ws.activeEditor?.let { editor ->
                item {
                    SectionHeader("活动编辑器")
                    EditorCard(editor = editor, isActive = true, onOpen = { viewModel.openFile(editor.filePath) }, onClose = { viewModel.closeFile(editor.filePath) })
                    Column(Modifier.fillMaxWidth().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(listOf("起始行" to startLine, "起始列" to startColumn), listOf("结束行" to endLine, "结束列" to endColumn)).forEachIndexed { row, fields ->
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                fields.forEachIndexed { column, (label, value) ->
                                    TextField(value, { next ->
                                        val digits = next.filter(Char::isDigit)
                                        when (row * 2 + column) { 0 -> startLine = digits; 1 -> startColumn = digits; 2 -> endLine = digits; else -> endColumn = digits }
                                    }, label = label, modifier = Modifier.weight(1f), singleLine = true)
                                }
                            }
                        }
                    }
                    Button(
                        onClick = { viewModel.setEditorSelection(editor.filePath, startLine.toIntOrNull() ?: 1, startColumn.toIntOrNull() ?: 1, endLine.toIntOrNull() ?: startLine.toIntOrNull() ?: 1, endColumn.toIntOrNull() ?: startColumn.toIntOrNull() ?: 1) },
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    ) { Text("在 VS Code 中选中并显示") }
                }
            }

            // Open editors
            if (ws.openEditors.isNotEmpty()) {
                item {
                    SectionHeader("已打开的编辑器（${ws.openEditors.size}）")
                }
                items(ws.openEditors) { editor ->
                    EditorCard(editor = editor, isActive = false, onOpen = { viewModel.openFile(editor.filePath) }, onClose = { viewModel.closeFile(editor.filePath) })
                }
            }

            // Quick actions
            item {
                SectionHeader("快捷操作")
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    cornerRadius = 12.dp,
                    colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceVariant),
                ) {
                    Column(modifier = Modifier.padding(8.dp)) {
                        QuickActionButton(
                            icon = Icons.Default.Code,
                            label = "获取活动文件内容",
                            onClick = { viewModel.getActiveEditorContent() },
                        )
                        HorizontalDivider()
                        QuickActionButton(
                            icon = Icons.AutoMirrored.Filled.Send,
                            label = "发送到 Copilot 对话",
                            onClick = {
                                state.activeEditorContent?.let { content ->
                                    viewModel.sendToCopilotChat("Explain this code:\n\n$content")
                                }
                            },
                        )
                    }
                }
            }
            state.activeEditorContent?.let { content ->
                item {
                    SectionHeader("活动文件内容")
                    Surface(color = MiuixTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(16.dp)) {
                        SelectionContainer {
                            Text(content, Modifier.fillMaxWidth().padding(12.dp), fontFamily = FontFamily.Monospace)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EditorCard(editor: EditorInfo, isActive: Boolean, onOpen: () -> Unit, onClose: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        cornerRadius = 12.dp,
        colors = CardDefaults.defaultColors(
            color = if (isActive) MiuixTheme.colorScheme.secondaryContainer else MiuixTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Default.Description,
                contentDescription = null,
                tint = if (isActive) MiuixTheme.colorScheme.onTertiaryContainer else MiuixTheme.colorScheme.onSurfaceSecondary,
                modifier = Modifier.size(20.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    editor.fileName,
                    style = MiuixTheme.textStyles.body2,
                    fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (editor.languageId.isNotBlank()) {
                    Text(
                        editor.languageId,
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceSecondary,
                    )
                }
            }
            if (editor.isDirty) {
                InfoChip("已修改", icon = Icons.Default.Edit, containerColor = MiuixTheme.colorScheme.errorContainer, contentColor = MiuixTheme.colorScheme.onErrorContainer)
            }
            IconButton(onClick = onOpen) { Icon(Icons.Default.OpenInNew, "打开文件") }
            IconButton(onClick = onClose) { Icon(Icons.Default.Close, "关闭文件") }
        }
    }
}

@Composable
private fun QuickActionButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(modifier = Modifier.width(8.dp))
        Text(label, modifier = Modifier.weight(1f))
    }
}
