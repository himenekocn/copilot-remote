package com.copilot.remote.ui.screens

import top.yukonga.miuix.kmp.theme.MiuixTheme

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.copilot.remote.data.CommandInfo
import com.copilot.remote.ui.components.*
import com.copilot.remote.viewmodel.CopilotViewModel
import top.yukonga.miuix.kmp.basic.*

@Composable
fun CommandsScreen(viewModel: CopilotViewModel) {
    val state by viewModel.uiState.collectAsState()
    var searchQuery by remember { mutableStateOf("") }

    LaunchedEffect(Unit) { viewModel.refreshCommands() }

    val filteredCommands = remember(state.commands, searchQuery) {
        if (searchQuery.isBlank()) state.commands
        else state.commands.filter {
            it.id.contains(searchQuery, ignoreCase = true) ||
                it.title.contains(searchQuery, ignoreCase = true) ||
                it.category.contains(searchQuery, ignoreCase = true)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
            TextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                label = "搜索命令…",
                useLabelAsPlaceholder = true,
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                singleLine = true,
                cornerRadius = 24.dp,
            )

            if (filteredCommands.isEmpty()) {
                LoadingIndicator(text = "正在加载命令…")
                return@Column
            }

            LazyColumn(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(filteredCommands) { command ->
                    CommandCard(
                        command = command,
                        onExecute = { viewModel.executeCommand(command.id) },
                    )
                }
            }
    }
}

@Composable
private fun CommandCard(
    command: CommandInfo,
    onExecute: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        cornerRadius = 12.dp,
        colors = CardDefaults.defaultColors(
            color = MiuixTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                if (command.isCopilot) Icons.Default.Psychology else Icons.Default.Terminal,
                contentDescription = null,
                tint = if (command.isCopilot) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurfaceSecondary,
                modifier = Modifier.size(20.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    command.title.ifBlank { command.id.substringAfterLast('.') },
                    style = MiuixTheme.textStyles.body2,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                )
                Text(
                    command.category.ifBlank { command.id },
                    style = MiuixTheme.textStyles.footnote2,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    color = MiuixTheme.colorScheme.onSurfaceSecondary,
                )
            }
            if (command.isCopilot) {
                InfoChip("Copilot", containerColor = MiuixTheme.colorScheme.primaryContainer, contentColor = MiuixTheme.colorScheme.onPrimaryContainer)
                Spacer(modifier = Modifier.width(4.dp))
            }
            IconButton(onClick = onExecute, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.PlayArrow, contentDescription = "执行", modifier = Modifier.size(18.dp))
            }
        }
    }
}
