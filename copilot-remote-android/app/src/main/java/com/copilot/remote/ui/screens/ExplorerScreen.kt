package com.copilot.remote.ui.screens

import top.yukonga.miuix.kmp.theme.MiuixTheme

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.copilot.remote.ui.components.LoadingIndicator
import com.copilot.remote.viewmodel.CopilotViewModel
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.extra.SuperDialog

@Composable
fun ExplorerScreen(viewModel: CopilotViewModel) {
    val state by viewModel.uiState.collectAsState()
    if (state.editorFilePath.isNotBlank()) {
        FileEditor(
            path = state.editorFilePath,
            content = state.editorContent,
            originalContent = state.editorOriginalContent,
            loading = state.editorLoading,
            onContentChange = viewModel::updateEditorContent,
            onSave = viewModel::saveEditorFile,
            onClose = viewModel::closeEditorFile,
            onOpenInVsCode = { viewModel.openFile(state.editorFilePath) },
        )
        return
    }
    var query by remember { mutableStateOf("") }
    var searchKind by remember { mutableStateOf("Text") }
    var replacement by remember { mutableStateOf("") }
    var confirmReplace by remember { mutableStateOf(false) }
    var tab by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { viewModel.listDirectory() }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("文件", "搜索", "替换").forEachIndexed { index, label ->
                Button(onClick = { tab = index }, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(color = if (tab == index) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.secondaryContainer)) { Text(label) }
            }
        }
        if (tab == 0) {
            Row(Modifier.fillMaxWidth().padding(8.dp)) {
                IconButton(onClick = {
                    val parent = state.directoryPath.substringBeforeLast('/', "")
                    viewModel.listDirectory(parent)
                }, enabled = state.directoryPath.isNotBlank(), modifier = Modifier.size(44.dp)) { Icon(Icons.Default.ArrowUpward, "上级目录", modifier = Modifier.size(21.dp)) }
                Text(state.directoryPath.ifBlank { "工作区根目录" }, modifier = Modifier.weight(1f).padding(12.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                IconButton(onClick = { viewModel.refreshDirectory(state.directoryPath) }, modifier = Modifier.size(44.dp)) { Icon(Icons.Default.Refresh, "刷新", modifier = Modifier.size(21.dp)) }
            }
            LazyColumn(Modifier.fillMaxSize()) {
                items(state.directoryEntries) { entry ->
                    BasicComponent(
                        title = entry.name,
                        startAction = { Icon(if (entry.isDirectory) Icons.Default.Folder else Icons.Default.Description, null) },
                        onClick = { if (entry.isDirectory) viewModel.listDirectory(entry.path) else viewModel.openEditorFile(entry.path) },
                    )
                }
            }
        } else if (tab == 1) {
            Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("文本" to "Text", "文件" to "Files", "符号" to "Symbols").forEach { (label, kind) -> TextButton(label, { searchKind = kind }, colors = ButtonDefaults.textButtonColors(color = if (searchKind == kind) MiuixTheme.colorScheme.primaryContainer else MiuixTheme.colorScheme.surface)) }
            }
            TextField(
                query, { query = it }, modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                label = "搜索工作区", trailingIcon = { IconButton(onClick = { viewModel.searchWorkspace(searchKind, query) }) { Icon(Icons.Default.Search, "搜索") } },
            )
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(8.dp)) {
                items(state.searchResults) { result ->
                    BasicComponent(
                        title = result.name.ifBlank { result.filePath },
                        summary = listOfNotNull(result.filePath.takeIf { result.name.isNotBlank() }, result.line?.let { "第 $it 行" }, result.preview.takeIf { it.isNotBlank() }).joinToString(" · "),
                        endActions = result.line?.let { line ->
                            @Composable {
                            Row {
                                IconButton(onClick = { viewModel.findDefinitions(result.filePath, line, result.character ?: 1) }) { Icon(Icons.Default.MyLocation, "查找定义") }
                                IconButton(onClick = { viewModel.findReferences(result.filePath, line, result.character ?: 1) }) { Icon(Icons.Default.Hub, "查找引用") }
                            }
                            }
                        },
                        onClick = { viewModel.openEditorFile(result.filePath, result.line, result.character) },
                    )
                    HorizontalDivider()
                }
            }
        } else {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("跨工作区替换", style = MiuixTheme.textStyles.title3)
                TextField(query, { query = it }, label = "查找精确文本", modifier = Modifier.fillMaxWidth())
                TextField(replacement, { replacement = it }, label = "替换为", modifier = Modifier.fillMaxWidth())
                Button(onClick = { confirmReplace = true }, enabled = query.isNotEmpty(), modifier = Modifier.fillMaxWidth()) { Text("检查并替换") }
                if (state.editorOperationResult.isNotBlank()) Text(state.editorOperationResult, color = MiuixTheme.colorScheme.primary)
            }
        }
    }
    SuperDialog(show = confirmReplace, onDismissRequest = { confirmReplace = false }, title = "确认跨工作区替换？") {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("将替换所有与“$query”完全匹配的内容。可在 VS Code 中撤销此次工作区编辑。")
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton("取消", { confirmReplace = false })
                Button(onClick = { viewModel.replaceInFiles(query, replacement); confirmReplace = false }) { Text("替换") }
            }
        }
    }
}

@Composable
private fun FileEditor(
    path: String,
    content: String,
    originalContent: String,
    loading: Boolean,
    onContentChange: (String) -> Unit,
    onSave: () -> Unit,
    onClose: () -> Unit,
    onOpenInVsCode: () -> Unit,
) {
    val dirty = content != originalContent
    var confirmClose by remember(path) { mutableStateOf(false) }
    val requestClose = { if (dirty) confirmClose = true else onClose() }
    BackHandler(onBack = requestClose)

    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = requestClose, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Default.ArrowBack, "返回", modifier = Modifier.size(22.dp))
            }
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        path.substringAfterLast('/').substringAfterLast('\\'),
                        style = MiuixTheme.textStyles.title3,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (dirty) Text("  •", color = MiuixTheme.colorScheme.primary)
                }
                Text(
                    path,
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = onOpenInVsCode, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Default.OpenInNew, "在 VS Code 中打开", modifier = Modifier.size(22.dp))
            }
            IconButton(onClick = onSave, enabled = dirty && !loading, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Default.Save, "保存", modifier = Modifier.size(22.dp))
            }
        }
        HorizontalDivider()
        if (loading) {
            LoadingIndicator(text = "正在读取文件…")
        } else {
            TextField(
                value = content,
                onValueChange = onContentChange,
                modifier = Modifier.fillMaxSize().padding(8.dp),
                textStyle = MiuixTheme.textStyles.body1.copy(fontFamily = FontFamily.Monospace),
            )
        }
    }

    SuperDialog(
        show = confirmClose,
        onDismissRequest = { confirmClose = false },
        title = "放弃未保存的修改？",
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("当前文件还有未保存的内容。")
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton("继续编辑", { confirmClose = false })
                Button(onClick = { confirmClose = false; onClose() }) { Text("放弃修改") }
            }
        }
    }
}
