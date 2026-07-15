package com.copilot.remote.ui.screens

import top.yukonga.miuix.kmp.theme.MiuixTheme

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.copilot.remote.viewmodel.CopilotViewModel
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.RadioButton
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField

@Composable
fun TerminalScreen(viewModel: CopilotViewModel) {
    val state by viewModel.uiState.collectAsState()
    var selectedId by remember { mutableStateOf("") }
    var command by remember { mutableStateOf("") }
    val displayOutput = remember(state.terminalOutput) {
        state.terminalOutput
            .replace(Regex("\\u001B\\][^\\u0007]*(?:\\u0007|\\u001B\\\\)"), "")
            .replace(Regex("\\u001B\\[[0-?]*[ -/]*[@-~]"), "")
            .replace("\r\n", "\n")
            .replace('\r', '\n')
    }
    LaunchedEffect(Unit) { viewModel.refreshTerminals() }
    LaunchedEffect(state.terminals) {
        if (state.terminals.none { it.id == selectedId }) selectedId = state.terminals.firstOrNull { it.isActive }?.id ?: state.terminals.firstOrNull()?.id.orEmpty()
    }
    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Spacer(Modifier.weight(1f))
            IconButton(onClick = viewModel::refreshTerminals, modifier = Modifier.size(44.dp)) { Icon(Icons.Default.Refresh, "刷新终端", modifier = Modifier.size(21.dp)) }
            IconButton(onClick = { viewModel.createTerminal("Copilot Remote") }, modifier = Modifier.size(44.dp)) { Icon(Icons.Default.Add, "创建终端", modifier = Modifier.size(21.dp)) }
        }
        LazyColumn(Modifier.heightIn(max = 132.dp)) {
            items(state.terminals) { terminal ->
                BasicComponent(
                    title = terminal.name,
                    summary = "VS Code 集成终端 · PID ${terminal.processId ?: "正在启动"}",
                    startAction = { RadioButton(selectedId == terminal.id, { selectedId = terminal.id }) },
                    endActions = { IconButton(onClick = { viewModel.closeTerminal(terminal.id) }) { Icon(Icons.Default.Close, "关闭终端") } },
                    onClick = { selectedId = terminal.id },
                )
            }
        }
        TextField(command, { command = it }, label = "命令", modifier = Modifier.fillMaxWidth(), maxLines = 3)
        Button(
            onClick = { viewModel.executeTerminal(selectedId, command) },
            enabled = selectedId.isNotBlank() && command.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) { Icon(Icons.Default.PlayArrow, null); Spacer(Modifier.width(8.dp)); Text("运行") }
        Surface(Modifier.fillMaxWidth().weight(1f), color = Color(0xFF1E1E1E), shape = RoundedCornerShape(14.dp)) {
            Box(
                Modifier.fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .horizontalScroll(rememberScrollState())
                    .padding(12.dp),
            ) {
                SelectionContainer {
                    Text(
                        displayOutput.ifBlank { "命令输出将显示在这里" },
                        color = Color(0xFFD4D4D4),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                        softWrap = false,
                    )
                }
            }
        }
    }
}
