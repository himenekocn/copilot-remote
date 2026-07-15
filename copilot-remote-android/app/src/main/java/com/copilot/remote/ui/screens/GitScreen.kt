package com.copilot.remote.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.shape.RoundedCornerShape
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

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
    Column(Modifier.fillMaxHeight().widthIn(max = 960.dp).fillMaxWidth().padding(horizontal = 12.dp)) {
        RepositoryCard(
            name = state.gitRepositories.find { it.id == state.gitRepositoryId }?.name ?: "选择仓库",
            branch = state.gitBranch,
            ahead = state.gitAhead,
            behind = state.gitBehind,
            changes = state.gitChanges.size,
            onRepository = { showRepos = true },
            onBranch = { showBranches = true },
            onRefresh = viewModel::refreshGit,
            onActions = { showActions = true },
        )
        GitTabs(tab, state.gitChanges.size, state.gitHistory.size, state.pullRequests.size) { tab = it }
        Spacer(Modifier.height(10.dp))
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
        Surface(shape = RoundedCornerShape(18.dp), color = MiuixTheme.colorScheme.surfaceVariant) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("提交更改", style = MiuixTheme.textStyles.title4, fontWeight = FontWeight.SemiBold)
                TextField(message, onMessage, label = "输入提交信息", modifier = Modifier.fillMaxWidth(), maxLines = 3)
                Button(
                    onClick = { viewModel.commitChanges(message, false); onMessage("") },
                    enabled = message.isNotBlank() && changes.any { it.staged },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 46.dp),
                ) { Icon(Icons.Default.Check, null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("提交已暂存的更改") }
                if (changes.none { it.staged }) Text("请先暂存需要提交的文件", style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceSecondary)
            }
        }
        Spacer(Modifier.height(10.dp))
        val staged = changes.filter { it.staged }; val unstaged = changes.filterNot { it.staged }
        LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(bottom = 20.dp)) {
            if (staged.isNotEmpty()) { item { GitHeader("已暂存", staged.size, "全部取消暂存", Icons.Default.Remove) { viewModel.gitAction("unstage", staged.map { it.filePath }) } }; items(staged, key = { "s:${it.filePath}" }) { GitChangeRow(it, onSelect) } }
            if (unstaged.isNotEmpty()) { item { GitHeader("未暂存", unstaged.size, "全部暂存", Icons.Default.Add) { viewModel.gitAction("stage", unstaged.map { it.filePath }) } }; items(unstaged, key = { "u:${it.filePath}" }) { GitChangeRow(it, onSelect) } }
            if (changes.isEmpty()) item { EmptyGitState() }
        }
    }
}

@Composable private fun GitHeader(title: String, count: Int, actionLabel: String, icon: androidx.compose.ui.graphics.vector.ImageVector, action: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, fontWeight = FontWeight.Bold)
        Surface(Modifier.padding(start = 7.dp), shape = RoundedCornerShape(10.dp), color = MiuixTheme.colorScheme.surfaceVariant) { Text("$count", style = MiuixTheme.textStyles.footnote2, modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)) }
        Spacer(Modifier.weight(1f))
        IconButton(action, modifier = Modifier.size(44.dp)) { Icon(icon, actionLabel, modifier = Modifier.size(19.dp)) }
    }
}

@Composable private fun GitChangeRow(change: GitChange, onSelect: (GitChange) -> Unit) {
    val normalized = change.path.replace('\\', '/')
    val fileName = normalized.substringAfterLast('/')
    val parent = normalized.substringBeforeLast('/', "")
    Surface(modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp).clickable { onSelect(change) }, shape = RoundedCornerShape(15.dp), color = MiuixTheme.colorScheme.surfaceVariant) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(if (change.staged) Icons.Default.Inventory2 else Icons.Default.Description, null, tint = MiuixTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
            Column(Modifier.weight(1f).padding(horizontal = 11.dp)) {
                Text(fileName, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                if (parent.isNotBlank()) Text(parent, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MiuixTheme.textStyles.footnote2, color = MiuixTheme.colorScheme.onSurfaceSecondary)
            }
            Surface(shape = RoundedCornerShape(10.dp), color = MiuixTheme.colorScheme.primaryContainer) { Text(change.status, color = MiuixTheme.colorScheme.primary, fontWeight = FontWeight.Bold, style = MiuixTheme.textStyles.footnote1, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) }
        }
    }
}

@Composable private fun RepositoryCard(name: String, branch: String, ahead: Int, behind: Int, changes: Int, onRepository: () -> Unit, onBranch: () -> Unit, onRefresh: () -> Unit, onActions: () -> Unit) {
    Surface(Modifier.fillMaxWidth().padding(top = 8.dp), shape = RoundedCornerShape(20.dp), color = MiuixTheme.colorScheme.surfaceVariant) {
        Row(Modifier.padding(start = 16.dp, end = 6.dp, top = 11.dp, bottom = 11.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).clickable(onClick = onRepository).padding(vertical = 3.dp)) {
                Text(name, style = MiuixTheme.textStyles.title3, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(listOfNotNull(branch.ifBlank { "无分支" }, "$changes 个更改", if (ahead > 0) "↑$ahead" else null, if (behind > 0) "↓$behind" else null).joinToString(" · "), style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceSecondary)
            }
            IconButton(onBranch, modifier = Modifier.size(44.dp)) { Icon(Icons.Default.AccountTree, "切换分支", modifier = Modifier.size(20.dp)) }
            IconButton(onRefresh, modifier = Modifier.size(44.dp)) { Icon(Icons.Default.Refresh, "刷新 Git", modifier = Modifier.size(20.dp)) }
            IconButton(onActions, modifier = Modifier.size(44.dp)) { Icon(Icons.Default.MoreVert, "更多 Git 操作", modifier = Modifier.size(20.dp)) }
        }
    }
}

@Composable private fun GitTabs(selected: Int, changes: Int, history: Int, pullRequests: Int, onSelect: (Int) -> Unit) {
    val tabs = listOf("变更 $changes", "历史${if (history > 0) " $history" else ""}", "差异", "PR${if (pullRequests > 0) " $pullRequests" else ""}")
    Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        tabs.forEachIndexed { index, title ->
            Surface(modifier = Modifier.weight(1f).height(46.dp).clickable { onSelect(index) }, shape = RoundedCornerShape(14.dp), color = if (selected == index) MiuixTheme.colorScheme.primaryContainer else MiuixTheme.colorScheme.surfaceVariant) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, color = if (selected == index) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurface, fontWeight = if (selected == index) FontWeight.Bold else FontWeight.Normal) }
            }
        }
    }
}

@Composable private fun EmptyGitState() {
    Column(Modifier.fillMaxWidth().padding(vertical = 56.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(Icons.Default.CheckCircle, null, tint = MiuixTheme.colorScheme.primary, modifier = Modifier.size(38.dp))
        Text("工作树干净", style = MiuixTheme.textStyles.title4, fontWeight = FontWeight.SemiBold)
        Text("所有更改都已提交", style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceSecondary)
    }
}

@Composable private fun PullRequestsPane(viewModel: CopilotViewModel) { val state by viewModel.uiState.collectAsState(); LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(10.dp)) { items(state.pullRequests, key = { it.number }) { pr -> BasicComponent(title = "#${pr.number} ${pr.title}", summary = "${pr.headRefName} → ${pr.baseRefName} · ${pr.author}", endActions = { Text(pr.state) }, onClick = { viewModel.openPullRequest(pr.number) }) } } }
