package com.copilot.remote.ui.screens

import top.yukonga.miuix.kmp.theme.MiuixTheme

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.copilot.remote.viewmodel.CopilotViewModel
import top.yukonga.miuix.kmp.basic.*

@Composable
fun GitScreen(viewModel: CopilotViewModel) {
    val state by viewModel.uiState.collectAsState()
    var branch by remember { mutableStateOf("") }
    var commit by remember { mutableStateOf("") }
    var prTitle by remember { mutableStateOf("") }
    var tab by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { viewModel.refreshGit() }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(8.dp)) {
            Text(state.gitBranch.ifBlank { "无分支" }, modifier = Modifier.weight(1f), color = MiuixTheme.colorScheme.onSurfaceSecondary)
            IconButton(onClick = viewModel::refreshGit, modifier = Modifier.size(44.dp)) { Icon(Icons.Default.Refresh, "刷新", modifier = Modifier.size(21.dp)) }
            TextButton("询问 @pr", onClick = { viewModel.sendToCopilotChat("Review the pull requests for this repository", "github", "pr") })
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("变更", "历史", "差异", "PR").forEachIndexed { index, label -> Button(onClick = { tab = index }, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(color = if (tab == index) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.secondaryContainer)) { Text(label) } }
        }
        if (tab == 0) {
            Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TextField(branch, { branch = it }, label = "新建分支", modifier = Modifier.weight(1f))
                Button(onClick = { viewModel.createBranch(branch); branch = "" }) { Icon(Icons.Default.Add, "创建分支") }
            }
            state.gitBranches.takeIf { it.isNotEmpty() }?.let { branches ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    branches.take(4).forEach { item -> TextButton(item, { viewModel.checkoutBranch(item) }, modifier = Modifier.weight(1f)) }
                }
            }
            TextField(commit, { commit = it }, label = "提交信息", modifier = Modifier.fillMaxWidth().padding(8.dp), trailingIcon = { IconButton(onClick = { viewModel.commitChanges(commit); commit = "" }) { Icon(Icons.Default.Check, "提交所有变更") } })
            LazyColumn(Modifier.fillMaxSize()) { items(state.gitChanges) { change -> BasicComponent(title = change.path, summary = change.status, onClick = { viewModel.getGitDiff(change.path); tab = 2 }) } }
        } else if (tab == 1) {
            LazyColumn(Modifier.fillMaxSize()) { items(state.gitHistory) { item -> BasicComponent(title = item.message, summary = "${item.hash.take(8)} · ${item.author}") } }
        } else if (tab == 2) {
            LazyColumn(Modifier.fillMaxSize().padding(8.dp)) { item { SelectionContainer { Text(state.gitDiff.ifBlank { "选择已变更文件以查看差异" }, fontFamily = FontFamily.Monospace) } } }
        } else {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TextField(prTitle, { prTitle = it }, label = "新建 PR 标题", modifier = Modifier.weight(1f))
                    Button(onClick = { viewModel.createPullRequest(prTitle); prTitle = "" }, enabled = prTitle.isNotBlank()) { Text("创建") }
                }
                LazyColumn(Modifier.fillMaxSize()) { items(state.pullRequests, key = { it.number }) { pr ->
                    BasicComponent(
                        title = "#${pr.number} ${pr.title}",
                        summary = "${pr.headRefName} → ${pr.baseRefName} · ${pr.author}",
                        endActions = { Text(pr.state) },
                        onClick = { viewModel.openPullRequest(pr.number) },
                    )
                } }
            }
        }
    }
}
