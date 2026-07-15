package com.copilot.remote.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Search
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.copilot.remote.data.ToolInfo
import com.copilot.remote.viewmodel.CopilotViewModel
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun ToolsScreen(viewModel: CopilotViewModel) {
    val state by viewModel.uiState.collectAsState()
    LaunchedEffect(Unit) { viewModel.refreshTools() }
    ToolConfiguration(
        tools = state.tools,
        enabledTools = state.enabledTools,
        onToggle = viewModel::toggleTool,
        onSetGroup = viewModel::setTools,
        onReset = viewModel::resetTools,
        modifier = Modifier.fillMaxSize(),
    )
}

@Composable
fun ToolConfiguration(
    tools: List<ToolInfo>,
    enabledTools: Set<String>?,
    onToggle: (String) -> Unit,
    onSetGroup: (Set<String>, Boolean) -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    var query by remember { mutableStateOf("") }
    val enabled = enabledTools ?: tools.map { it.id }.toSet()
    val visible = tools.filter { query.isBlank() || it.name.contains(query, true) || it.description.contains(query, true) || it.groupName.contains(query, true) }
    val groups = visible.groupBy { it.groupId }.values.sortedBy { it.firstOrNull()?.groupName }
    LazyColumn(modifier, contentPadding = PaddingValues(horizontal = if (compact) 4.dp else 20.dp, vertical = 14.dp)) {
        item {
            if (!compact) {
                Text("配置工具", style = MiuixTheme.textStyles.title2, fontWeight = FontWeight.Bold)
                Text("按来源分类管理发送给 Copilot 的工具。未自定义时使用 VS Code 默认的全部工具。", style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurfaceSecondary, modifier = Modifier.padding(top = 4.dp, bottom = 12.dp))
            }
            TextField(query, { query = it }, label = "搜索工具…", leadingIcon = { Icon(Icons.Default.Search, null) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("已选 ${enabled.size}/${tools.size}", style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceSecondary, modifier = Modifier.weight(1f))
                TextButton("全选", onClick = { onSetGroup(tools.map { it.id }.toSet(), true) })
                TextButton("清空", onClick = { onSetGroup(tools.map { it.id }.toSet(), false) })
                TextButton("默认", onClick = onReset)
            }
        }
        groups.forEach { group ->
            item(group.first().groupId) {
                ToolGroup(group, enabled, onToggle, onSetGroup)
            }
        }
        if (tools.isEmpty()) item { Box(Modifier.fillMaxWidth().padding(36.dp), contentAlignment = Alignment.Center) { Text("尚未从 VS Code 获取到工具", color = MiuixTheme.colorScheme.onSurfaceSecondary) } }
    }
}

@Composable
private fun ToolGroup(tools: List<ToolInfo>, enabled: Set<String>, onToggle: (String) -> Unit, onSetGroup: (Set<String>, Boolean) -> Unit) {
    var expanded by remember(tools.first().groupId) { mutableStateOf(true) }
    val ids = tools.map { it.id }.toSet()
    val selected = ids.count { it in enabled }
    Row(Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = selected == ids.size, onCheckedChange = { onSetGroup(ids, it) })
        Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
            Text(tools.first().groupName, fontWeight = FontWeight.SemiBold)
            Text("$selected/${ids.size} · ${sourceLabel(tools.first().source)}", style = MiuixTheme.textStyles.footnote2, color = MiuixTheme.colorScheme.onSurfaceSecondary)
        }
        Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null)
    }
    AnimatedVisibility(expanded) {
        Column(Modifier.padding(start = 18.dp)) {
            tools.forEach { tool ->
                Row(Modifier.fillMaxWidth().clickable { onToggle(tool.id) }.padding(vertical = 9.dp), verticalAlignment = Alignment.Top) {
                    Checkbox(checked = tool.id in enabled, onCheckedChange = { onToggle(tool.id) })
                    Column(Modifier.padding(start = 10.dp)) {
                        Text(tool.name, style = MiuixTheme.textStyles.body1)
                        if (tool.description.isNotBlank()) Text(tool.description, style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceSecondary, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

private fun sourceLabel(source: String) = when (source) { "mcp" -> "MCP"; "extension" -> "扩展"; else -> "内置" }
