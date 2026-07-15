package com.copilot.remote.ui.screens

import top.yukonga.miuix.kmp.theme.MiuixTheme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.copilot.remote.data.McpServerInfo
import com.copilot.remote.ui.components.*



import com.copilot.remote.viewmodel.CopilotViewModel
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.extra.SuperDialog

@Composable
fun McpScreen(viewModel: CopilotViewModel) {
    val state by viewModel.uiState.collectAsState()
    var showAddDialog by remember { mutableStateOf(false) }
    var pendingRemove by remember { mutableStateOf<McpServerInfo?>(null) }

    LaunchedEffect(Unit) { viewModel.refreshMcpServers() }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.weight(1f))
            IconButton(onClick = viewModel::refreshMcpServers, modifier = Modifier.size(44.dp)) { Icon(Icons.Default.Refresh, "刷新", modifier = Modifier.size(21.dp)) }
            IconButton(onClick = { showAddDialog = true }, modifier = Modifier.size(44.dp)) { Icon(Icons.Default.Add, "添加 MCP 服务器", modifier = Modifier.size(21.dp)) }
        }
        if (state.mcpServers.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                EmptyState(
                    icon = Icons.Default.Hub,
                    title = "没有 MCP 服务器",
                    subtitle = "点击 + 添加 MCP 服务器配置",
                )
            }
            return@Column
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                SectionHeader("模型上下文协议", "已配置 ${state.mcpServers.size} 个服务器")
            }
            items(state.mcpServers) { server ->
                McpServerCard(
                    server = server,
                    onRemove = { pendingRemove = server },
                )
            }
        }
    }

    if (showAddDialog) {
        AddMcpServerDialog(
            onAdd = { name, command, args ->
                viewModel.addMcpServer(name, command, args)
                showAddDialog = false
            },
            onDismiss = { showAddDialog = false },
        )
    }
    pendingRemove?.let { server ->
        SuperDialog(show = true, title = "移除 MCP 服务器？", onDismissRequest = { pendingRemove = null }) {
            Column(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
                Text("将移除“${server.name}”的配置，此操作不会卸载相关扩展。", color = MiuixTheme.colorScheme.onSurfaceSecondary)
                Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.End) {
                    TextButton("取消", onClick = { pendingRemove = null })
                    TextButton("移除", onClick = { viewModel.removeMcpServer(server.name); pendingRemove = null }, colors = ButtonDefaults.textButtonColors(textColor = MiuixTheme.colorScheme.error))
                }
            }
        }
    }
}

@Composable
private fun McpServerCard(
    server: McpServerInfo,
    onRemove: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        cornerRadius = 12.dp,
        colors = CardDefaults.defaultColors(
            color = MiuixTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MiuixTheme.colorScheme.primaryContainer,
                    modifier = Modifier.size(36.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.Hub, contentDescription = null, tint = MiuixTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(18.dp))
                    }
                }
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(server.name, style = MiuixTheme.textStyles.title4, fontWeight = FontWeight.SemiBold)
                    Text(
                        "${server.command} ${server.args.joinToString(" ")}",
                        style = MiuixTheme.textStyles.footnote1,
                        fontFamily = FontFamily.Monospace,
                        color = MiuixTheme.colorScheme.onSurfaceSecondary,
                        maxLines = 1,
                    )
                }
                server.status?.let { status ->
                    val color = when (status) {
                        "connected" -> MiuixTheme.colorScheme.primary
                        "error" -> MiuixTheme.colorScheme.error
                        else -> MiuixTheme.colorScheme.secondary
                    }
                    Box(modifier = Modifier.size(10.dp).clip(RoundedCornerShape(50)).background(color))
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton("移除", onClick = onRemove, colors = ButtonDefaults.textButtonColors(textColor = MiuixTheme.colorScheme.error))
            }
        }
    }
}

@Composable
private fun AddMcpServerDialog(
    onAdd: (String, String, List<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var command by remember { mutableStateOf("") }
    var args by remember { mutableStateOf("") }

    SuperDialog(show = true, onDismissRequest = onDismiss, title = "添加 MCP 服务器") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TextField(
                    value = name,
                    onValueChange = { name = it },
                    label = "服务器名称",
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                TextField(
                    value = command,
                    onValueChange = { command = it },
                    label = "命令",
                    useLabelAsPlaceholder = true,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                TextField(
                    value = args,
                    onValueChange = { args = it },
                    label = "参数（以空格分隔）",
                    useLabelAsPlaceholder = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton("取消", onClick = onDismiss)
            Button(
                onClick = {
                    val argList = args.split(" ").filter { it.isNotBlank() }
                    onAdd(name, command, argList)
                },
                enabled = name.isNotBlank() && command.isNotBlank(),
            ) { Text("添加") }
                }
            }
    }
}
