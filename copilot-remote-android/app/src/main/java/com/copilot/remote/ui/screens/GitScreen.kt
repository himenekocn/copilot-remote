package com.copilot.remote.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.copilot.remote.data.GitChange
import com.copilot.remote.viewmodel.CopilotViewModel
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.extra.SuperDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun GitScreen(viewModel: CopilotViewModel, openExplorer: () -> Unit = {}) {
    val state by viewModel.uiState.collectAsState()
    var commitMessage by remember { mutableStateOf("") }
    var newBranch by remember { mutableStateOf("") }
    var tab by remember { mutableIntStateOf(0) }
    var showRepos by remember { mutableStateOf(false) }
    var showBranches by remember { mutableStateOf(false) }
    var showActions by remember { mutableStateOf(false) }
    var selectedChange by remember { mutableStateOf<GitChange?>(null) }
    LaunchedEffect(Unit) { viewModel.refreshGit() }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).clickable { showRepos = true }.padding(vertical = 6.dp)) {
                Text(state.gitRepositories.find { it.id == state.gitRepositoryId }?.name ?: "选择仓库", fontWeight = FontWeight.SemiBold)
                Text(state.gitBranch.ifBlank { "无分支" } + if (state.gitAhead + state.gitBehind > 0) " · ↑${state.gitAhead} ↓${state.gitBehind}" else "", style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceSecondary)
            }
            IconButton({ showBranches = true }, modifier = Modifier.size(42.dp)) { Icon(Icons.Default.AccountTree, "切换分支") }
            IconButton(viewModel::refreshGit, modifier = Modifier.size(42.dp)) { Icon(Icons.Default.Refresh, "刷新") }
            IconButton({ showActions = true }, modifier = Modifier.size(42.dp)) { Icon(Icons.Default.MoreVert, "Git 操作") }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("变更 ${state.gitChanges.size}", "提交历史", "差异", "PR").forEachIndexed { index, title ->
                TextButton(title, { tab = index }, modifier = Modifier.weight(1f))
            }
        }
        when (tab) {
            0 -> ChangesPane(state.gitChanges, commitMessage, { commitMessage = it }, viewModel, { selectedChange = it })
            1 -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(10.dp)) {
                items(state.gitHistory, key = { it.hash }) { item ->
                    BasicComponent(title = item.message, summary = "${item.hash.take(8)} · ${item.author}\n${item.date}", onClick = { viewModel.getGitDiff(commit = item.hash); tab = 2 })
                }
            }
            2 -> LazyColumn(Modifier.fillMaxSize().padding(10.dp)) { item { SelectionContainer { Text(state.gitDiff.ifBlank { "选择变更文件或历史提交查看差异" }, fontFamily = FontFamily.Monospace, style = MiuixTheme.textStyles.footnote1) } } }
            else -> PullRequestsPane(viewModel)
        }
    }

    SuperDialog(show = showRepos, title = "选择 Git 仓库", onDismissRequest = { showRepos = false }) {
        LazyColumn(Modifier.heightIn(max = 480.dp)) { items(state.gitRepositories, key = { it.id }) { repo -> BasicComponent(title = repo.name, summary = "${repo.branch} · ${repo.changes} 个变更\n${repo.root}", endActions = { if (repo.id == state.gitRepositoryId) Icon(Icons.Default.Check, null) }, onClick = { viewModel.selectGitRepository(repo.id); showRepos = false }) } }
    }
    SuperDialog(show = showBranches, title = "分支", onDismissRequest = { showBranches = false }) {
        Column(Modifier.heightIn(max = 520.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) { TextField(newBranch, { newBranch = it }, label = "新建分支", modifier = Modifier.weight(1f)); IconButton({ viewModel.createBranch(newBranch); newBranch = "" }) { Icon(Icons.Default.Add, "创建") } }
            LazyColumn { items(state.gitBranches) { branch -> BasicComponent(title = branch, endActions = { if (branch == state.gitBranch) Icon(Icons.Default.Check, null) }, onClick = { viewModel.checkoutBranch(branch); showBranches = false }) } }
        }
    }
    SuperDialog(show = showActions, title = "仓库操作", onDismissRequest = { showActions = false }) {
        Column {
            listOf("fetch" to "抓取", "pull" to "拉取", "push" to "推送", "sync" to "同步（拉取并推送）").forEach { (action, label) -> BasicComponent(title = label, onClick = { viewModel.gitAction(action); showActions = false }) }
            if (state.gitRemotes.isNotEmpty()) Text("远程：${state.gitRemotes.joinToString()}", color = MiuixTheme.colorScheme.onSurfaceSecondary, modifier = Modifier.padding(16.dp))
        }
    }
    selectedChange?.let { change -> SuperDialog(show = true, title = change.path, onDismissRequest = { selectedChange = null }) {
        Column {
            BasicComponent(title = "查看差异", onClick = { viewModel.getGitDiff(change.filePath); tab = 2; selectedChange = null })
            BasicComponent(title = "打开并在 APP 中查看文件", onClick = { viewModel.openFile(change.filePath); openExplorer(); selectedChange = null })
            BasicComponent(title = if (change.staged) "取消暂存" else "暂存", onClick = { viewModel.gitAction(if (change.staged) "unstage" else "stage", listOf(change.filePath)); selectedChange = null })
            if (!change.staged) BasicComponent(title = "丢弃改动", summary = "此操作不可撤销", onClick = { viewModel.gitAction("discard", listOf(change.filePath)); selectedChange = null })
        }
    } }
}

@Composable
private fun ChangesPane(changes: List<GitChange>, message: String, onMessage: (String) -> Unit, viewModel: CopilotViewModel, onSelect: (GitChange) -> Unit) {
    Column(Modifier.fillMaxSize()) {
        TextField(message, onMessage, label = "提交信息", modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp), trailingIcon = { IconButton({ viewModel.commitChanges(message, false); onMessage("") }, enabled = message.isNotBlank()) { Icon(Icons.Default.Check, "提交已暂存更改") } })
        val staged = changes.filter { it.staged }; val unstaged = changes.filterNot { it.staged }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 10.dp)) {
            if (staged.isNotEmpty()) { item { GitHeader("已暂存的更改", staged.size, { viewModel.gitAction("unstage", staged.map { it.filePath }) }) }; items(staged, key = { "s:${it.filePath}" }) { GitChangeRow(it, onSelect) } }
            if (unstaged.isNotEmpty()) { item { GitHeader("更改", unstaged.size, { viewModel.gitAction("stage", unstaged.map { it.filePath }) }) }; items(unstaged, key = { "u:${it.filePath}" }) { GitChangeRow(it, onSelect) } }
            if (changes.isEmpty()) item { Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) { Text("工作树干净", color = MiuixTheme.colorScheme.onSurfaceSecondary) } }
        }
    }
}

@Composable private fun GitHeader(title: String, count: Int, action: () -> Unit) { Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) { Text("$title $count", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f)); IconButton(action, modifier = Modifier.size(36.dp)) { Icon(Icons.Default.Add, null, modifier = Modifier.size(18.dp)) } } }
@Composable private fun GitChangeRow(change: GitChange, onSelect: (GitChange) -> Unit) { Row(Modifier.fillMaxWidth().clickable { onSelect(change) }.padding(horizontal = 8.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.Description, null, modifier = Modifier.size(19.dp)); Text(change.path, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(horizontal = 10.dp)); Text(change.status, color = MiuixTheme.colorScheme.primary) } }

@Composable private fun PullRequestsPane(viewModel: CopilotViewModel) { val state by viewModel.uiState.collectAsState(); LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(10.dp)) { items(state.pullRequests, key = { it.number }) { pr -> BasicComponent(title = "#${pr.number} ${pr.title}", summary = "${pr.headRefName} → ${pr.baseRefName} · ${pr.author}", endActions = { Text(pr.state) }, onClick = { viewModel.openPullRequest(pr.number) }) } } }
