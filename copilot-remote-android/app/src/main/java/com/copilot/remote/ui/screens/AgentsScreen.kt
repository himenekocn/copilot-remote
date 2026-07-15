package com.copilot.remote.ui.screens

import top.yukonga.miuix.kmp.theme.MiuixTheme

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.copilot.remote.data.ParticipantInfo
import com.copilot.remote.ui.components.*
import com.copilot.remote.viewmodel.CopilotViewModel
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.extra.SuperDialog

@Composable
fun AgentsScreen(viewModel: CopilotViewModel) {
    val state by viewModel.uiState.collectAsState()

    LaunchedEffect(Unit) { viewModel.refreshParticipants() }

    Box(Modifier.fillMaxSize()) {
        if (state.participants.isEmpty()) {
            LoadingIndicator(text = "正在加载智能体…")
            return@Box
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                SectionHeader("对话模式", "与 VS Code 的模式选择器保持一致")
            }
            items(state.participants) { participant ->
                AgentCard(
                    participant = participant,
                    isSelected = participant.name == state.selectedParticipant,
                    onSelect = { viewModel.selectParticipant(participant.name) },
                    onSendToChat = { prompt ->
                        viewModel.sendToCopilotChat(prompt, participant.name)
                    },
                )
            }
        }
    }
}

@Composable
private fun AgentCard(
    participant: ParticipantInfo,
    isSelected: Boolean,
    onSelect: () -> Unit,
    onSendToChat: (String) -> Unit,
) {
    var showSendDialog by remember { mutableStateOf(false) }

    Card(
        onClick = onSelect,
        modifier = Modifier.fillMaxWidth(),
        cornerRadius = 16.dp,
        colors = CardDefaults.defaultColors(
            color = MiuixTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MiuixTheme.colorScheme.surface,
                    modifier = Modifier.size(40.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            if (participant.isBuiltIn) Icons.Default.Star else Icons.Default.SmartToy,
                            contentDescription = null,
                            tint = if (isSelected) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurfaceSecondary,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            participant.fullName,
                            style = MiuixTheme.textStyles.title3,
                            fontWeight = FontWeight.SemiBold,
                        )
                        if (isSelected) {
                            Spacer(modifier = Modifier.width(6.dp))
                            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = MiuixTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                        }
                    }
                    if (participant.isBuiltIn) {
                        InfoChip("内置", icon = Icons.Default.Verified)
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                participant.description,
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceSecondary,
            )
            if (participant.commands.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text("命令：", style = MiuixTheme.textStyles.footnote1, fontWeight = FontWeight.Medium)
                participant.commands.forEach { cmd ->
                    Text(
                        "  /${cmd.name} - ${cmd.description}",
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceSecondary,
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                Button(onClick = { showSendDialog = true }) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("发送到 VS Code")
                }
            }
        }
    }

    var prompt by remember { mutableStateOf("") }
    SuperDialog(
            show = showSendDialog,
            onDismissRequest = { showSendDialog = false },
            title = "使用 ${participant.fullName} 模式发送",
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                TextField(
                    value = prompt,
                    onValueChange = { prompt = it },
                    label = "提示词",
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton("取消", onClick = { showSendDialog = false })
                Button(
                    onClick = {
                        if (prompt.isNotBlank()) {
                            onSendToChat(prompt)
                            showSendDialog = false
                        }
                    }
                ) { Text("发送") }
                }
            }
        }
}
