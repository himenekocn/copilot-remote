package com.copilot.remote.ui.screens

import android.net.Uri
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.copilot.remote.data.ChatAttachment
import com.copilot.remote.data.ChatMessage
import com.copilot.remote.data.ModelInfo
import com.copilot.remote.data.effectiveReasoningEfforts
import com.copilot.remote.viewmodel.CopilotViewModel
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.extra.SuperDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun ChatScreen(viewModel: CopilotViewModel) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val listState = rememberLazyListState()
    var input by remember { mutableStateOf("") }
    var attachments by remember { mutableStateOf<List<ChatAttachment>>(emptyList()) }
    var showModels by remember { mutableStateOf(false) }
    var showAgents by remember { mutableStateOf(false) }
    var showTools by remember { mutableStateOf(false) }
    var showSessions by remember { mutableStateOf(false) }
    var showTerminals by remember { mutableStateOf(false) }
    val attach: (Uri) -> Unit = { uri ->
        runCatching {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val bytes = stream.readBytes().takeIf { it.size <= 8 * 1024 * 1024 }
                bytes?.let { attachments = attachments + ChatAttachment(uri.lastPathSegment ?: "image", context.contentResolver.getType(uri) ?: "image/jpeg", Base64.encodeToString(it, Base64.NO_WRAP)) }
            }
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { it?.let(attach) }
    LaunchedEffect(state.pendingSharedText) { if (state.pendingSharedText.isNotBlank()) { input = state.pendingSharedText; viewModel.consumeSharedText() } }
    LaunchedEffect(state.pendingSharedImageUri) { if (state.pendingSharedImageUri.isNotBlank()) { attach(Uri.parse(state.pendingSharedImageUri)); viewModel.consumeSharedImage() } }
    LaunchedEffect(state.pendingCaptureAttachment) { state.pendingCaptureAttachment?.let { attachments = attachments + it; viewModel.consumeCaptureAttachment() } }
    LaunchedEffect(state.pendingTerminalAttachment) { state.pendingTerminalAttachment?.let { attachments = attachments + it; viewModel.consumeTerminalAttachment() } }
    LaunchedEffect(Unit) { viewModel.refreshSlashCommands() }
    LaunchedEffect(state.chatMessages.size, state.chatMessages.lastOrNull()?.content?.length) { if (state.chatMessages.isNotEmpty()) listState.animateScrollToItem(state.chatMessages.lastIndex) }
    val send = {
        if (input.isNotBlank() || attachments.isNotEmpty()) {
            viewModel.sendMessage(input, attachments)
            input = ""
            attachments = emptyList()
        }
    }
    val canSubmit = state.isSending || input.isNotBlank() || attachments.isNotEmpty()

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
      Column(Modifier.fillMaxHeight().widthIn(max = 900.dp).fillMaxWidth().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            val selectedModel = state.models.find { it.id == state.selectedModelId }
            val effortLabel = state.selectedReasoningEffort.takeIf { it.isNotBlank() }?.let(::reasoningEffortLabel)
            TextButton(listOfNotNull(selectedModel?.name ?: "自动选择模型", effortLabel).joinToString(" · "), { showModels = true })
            Spacer(Modifier.width(8.dp))
            val selectedAgent = state.participants.find { it.name == state.selectedParticipant || it.id == state.selectedParticipant }
            TextButton(selectedAgent?.fullName ?: "直接对话", { showAgents = true })
            Spacer(Modifier.width(8.dp))
            TextButton(state.enabledTools?.let { "工具 · ${it.size}" } ?: "工具 · 默认", { showTools = true })
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { showSessions = true }, modifier = Modifier.size(44.dp)) { Icon(Icons.Default.History, "全部对话", modifier = Modifier.size(20.dp)) }
        }
        if (state.activeOfficialSessionResource.isBlank()) {
            Surface(modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp), shape = RoundedCornerShape(14.dp), color = MiuixTheme.colorScheme.surfaceContainer) {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Info, null, modifier = Modifier.size(18.dp), tint = MiuixTheme.colorScheme.onSurfaceSecondary)
                    Spacer(Modifier.width(8.dp))
                    Text("在 VS Code 中打开目标对话后即可发送", color = MiuixTheme.colorScheme.onSurfaceSecondary, style = MiuixTheme.textStyles.footnote1)
                }
            }
        }
        state.chatUsage?.let { usage ->
            val used = usage.promptTokens + usage.completionTokens
            LinearProgressIndicator(progress = if (usage.maxInputTokens > 0) used.toFloat() / usage.maxInputTokens else 0f, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp))
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState, contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (state.activeNativeChatSessionId.isNotBlank() && state.nativeMessagesHasMoreBefore) item { Button(viewModel::loadOlderNativeMessages, modifier = Modifier.fillMaxWidth()) { Text("加载更早消息") } }
            if (state.nativeMessagesLoading && state.chatMessages.isEmpty()) item {
                Column(Modifier.fillMaxWidth().padding(top = 100.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(Modifier.size(30.dp))
                    Spacer(Modifier.height(12.dp))
                    Text("正在读取会话记录…", color = MiuixTheme.colorScheme.onSurfaceSecondary)
                }
            } else if (state.chatMessages.isEmpty()) item { EmptyConversation() }
            items(groupTimelineMessages(state.chatMessages), key = { it.key }) { item ->
                when (item) {
                    is ChatTimelineItem.Message -> MessageCard(item.message) { viewModel.retryMessage(item.message.id) }
                    is ChatTimelineItem.Process -> ProcessMessageGroup(item.messages)
                }
            }
        }
        if (attachments.isNotEmpty()) Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            attachments.forEachIndexed { index, item -> TextButton("${item.name} ×", { attachments = attachments.filterIndexed { i, _ -> i != index } }) }
        }
        val slashQuery = input.takeIf { it.startsWith("/") && !it.contains(Regex("\\s")) }?.drop(1)
        val slashMatches = if (slashQuery == null) emptyList() else state.slashCommands.filter { it.name.contains(slashQuery, ignoreCase = true) }.take(8)
        if (slashQuery != null) {
            Surface(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                shape = RoundedCornerShape(16.dp),
                color = MiuixTheme.colorScheme.surfaceContainer,
            ) {
                if (slashMatches.isEmpty()) {
                    Text("没有匹配的 / 指令", color = MiuixTheme.colorScheme.onSurfaceSecondary, modifier = Modifier.padding(14.dp))
                } else LazyColumn(Modifier.heightIn(max = 300.dp)) {
                    items(slashMatches, key = { "${it.source}:${it.name}" }) { command ->
                        Row(
                            Modifier.fillMaxWidth().clickable { input = "/${command.name} " }.padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("/${command.name}", color = MiuixTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold, modifier = Modifier.widthIn(min = 96.dp))
                            Column(Modifier.weight(1f)) {
                                if (command.description.isNotBlank()) Text(command.description, style = MiuixTheme.textStyles.footnote1, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(command.source, style = MiuixTheme.textStyles.footnote2, color = MiuixTheme.colorScheme.onSurfaceSecondary)
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
        }
        Surface(
            modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 10.dp, vertical = 8.dp),
            shape = RoundedCornerShape(24.dp),
            color = MiuixTheme.colorScheme.surfaceVariant,
        ) {
            Row(Modifier.padding(5.dp), verticalAlignment = Alignment.Bottom) {
                IconButton(
                    onClick = { picker.launch("image/*") },
                    enabled = !state.isSending,
                    modifier = Modifier.size(44.dp),
                    backgroundColor = MiuixTheme.colorScheme.surfaceContainer,
                ) {
                    Icon(Icons.Default.Add, "添加图片", modifier = Modifier.size(21.dp))
                }
                IconButton(
                    onClick = { viewModel.refreshTerminals(); showTerminals = true },
                    enabled = !state.isSending,
                    modifier = Modifier.size(44.dp),
                    backgroundColor = MiuixTheme.colorScheme.surfaceContainer,
                ) { Icon(Icons.Default.Terminal, "附加终端", modifier = Modifier.size(20.dp)) }
                BasicTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.weight(1f).heightIn(min = 44.dp, max = 132.dp).padding(horizontal = 10.dp, vertical = 11.dp),
                    textStyle = MiuixTheme.textStyles.body1.copy(color = MiuixTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MiuixTheme.colorScheme.primary),
                    maxLines = 6,
                    decorationBox = { innerTextField ->
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
                            if (input.isEmpty()) {
                                Text(
                                    "向 Copilot 发送消息…",
                                    style = MiuixTheme.textStyles.body1,
                                    color = MiuixTheme.colorScheme.onSurfaceSecondary,
                                )
                            }
                            innerTextField()
                        }
                    },
                )
                IconButton(
                    onClick = { if (state.isSending) viewModel.cancelChat() else send() },
                    enabled = canSubmit,
                    modifier = Modifier.size(44.dp),
                    backgroundColor = if (canSubmit) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.surfaceContainer,
                ) {
                    Icon(
                        if (state.isSending) Icons.Default.Stop else Icons.Default.ArrowUpward,
                        if (state.isSending) "停止" else "发送",
                        tint = if (canSubmit) MiuixTheme.colorScheme.onPrimary else MiuixTheme.colorScheme.onSurfaceSecondary,
                        modifier = Modifier.size(21.dp),
                    )
                }
            }
        }
      }
    }

    SuperDialog(show = showModels, title = "选择模型", onDismissRequest = { showModels = false }) {
        val selected = state.models.find { it.id == state.selectedModelId }
        val efforts = effectiveReasoningEfforts(selected)
        Column(Modifier.heightIn(max = 560.dp)) {
          LazyColumn(Modifier.weight(1f, fill = false)) {
            item { BasicComponent(title = "默认（自动选择）", onClick = { viewModel.selectModel(""); showModels = false }) }
            items(state.models, key = { it.id }) { model -> BasicComponent(title = model.name, summary = "${model.vendor} · ${model.maxInputTokens} tokens", endActions = { if (model.id == state.selectedModelId) Icon(Icons.Default.Check, null) }, onClick = { viewModel.selectModel(model.id) }) }
          }
          if (selected != null && efforts.isNotEmpty()) {
              HorizontalDivider()
              Text("思考程度", style = MiuixTheme.textStyles.title4, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 12.dp, top = 12.dp))
              Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                  efforts.chunked(3).forEach { rowEfforts ->
                      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                          rowEfforts.forEach { effort ->
                              TextButton(
                                  reasoningEffortLabel(effort),
                                  { viewModel.selectReasoningEffort(effort) },
                                  modifier = Modifier.weight(1f),
                                  colors = ButtonDefaults.textButtonColors(color = if (effort == state.selectedReasoningEffort) MiuixTheme.colorScheme.primaryContainer else MiuixTheme.colorScheme.surfaceVariant),
                              )
                          }
                          repeat(3 - rowEfforts.size) { Spacer(Modifier.weight(1f)) }
                      }
                  }
              }
          }
          Button(onClick = { showModels = false }, modifier = Modifier.fillMaxWidth()) { Text("完成") }
        }
    }
    SuperDialog(show = showAgents, title = "选择智能体", onDismissRequest = { showAgents = false }) {
        LazyColumn(Modifier.heightIn(max = 500.dp)) {
            item { BasicComponent(title = "直接对话", onClick = { viewModel.selectParticipant(""); showAgents = false }) }
            items(state.participants.distinctBy { it.name.lowercase() }, key = { it.id }) { agent -> BasicComponent(title = agent.fullName, summary = agent.description, endActions = { if (agent.name == state.selectedParticipant) Icon(Icons.Default.Check, null) }, onClick = { viewModel.selectParticipant(agent.name); showAgents = false }) }
        }
    }
    SuperDialog(show = showTools, title = "配置工具", onDismissRequest = { showTools = false }) {
        ToolConfiguration(state.tools, state.enabledTools, viewModel::toggleTool, viewModel::setTools, viewModel::resetTools, Modifier.heightIn(max = 560.dp), compact = true)
    }
    SuperDialog(show = showTerminals, title = "附加 VS Code 终端", onDismissRequest = { showTerminals = false }) {
        Column(Modifier.heightIn(max = 420.dp)) {
            Text("选择后会读取该终端当前可复制的历史输出，并作为上下文附加到输入框。", style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceSecondary, modifier = Modifier.padding(bottom = 8.dp))
            if (state.terminals.isEmpty()) Text("没有可附加的集成终端", modifier = Modifier.padding(16.dp))
            else LazyColumn {
                items(state.terminals, key = { it.id }) { terminal ->
                    BasicComponent(title = terminal.name, summary = listOfNotNull(terminal.processId?.let { "PID $it" }, if (terminal.isActive) "当前终端" else null).joinToString(" · "), onClick = {
                        viewModel.captureTerminalForChat(terminal.id)
                        showTerminals = false
                    })
                }
            }
        }
    }
    SuperDialog(show = showSessions, title = "VS Code 对话", onDismissRequest = { showSessions = false }) {
        LazyColumn(Modifier.heightIn(max = 520.dp)) {
            item { Button(onClick = viewModel::refreshNativeChatSessions, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.Refresh, null); Spacer(Modifier.width(6.dp)); Text("刷新") } }
            items(state.nativeChatSessions, key = { it.id }) { session -> BasicComponent(title = session.title.takeUnless { it.isBlank() || it == session.id || it.matches(Regex("[0-9a-fA-F-]{36}")) } ?: "未命名对话", summary = session.workspaceName ?: session.source, onClick = { viewModel.selectNativeChatSession(session.id); showSessions = false }) }
        }
    }
}

@Composable
private fun MessageCard(message: ChatMessage, onRetry: () -> Unit) {
    val user = message.role == "user"
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (user) Arrangement.End else Arrangement.Start) {
        if (user) {
            Card(modifier = Modifier.widthIn(max = 680.dp).fillMaxWidth(.86f), cornerRadius = 18.dp, insideMargin = PaddingValues(horizontal = 14.dp, vertical = 11.dp), colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceVariant)) {
                MessageContent(message, onRetry)
            }
        } else if (message.kind == "tool" || message.kind == "thinking") {
            Box(Modifier.widthIn(max = 760.dp).fillMaxWidth().padding(horizontal = 2.dp, vertical = 3.dp)) {
                MessageContent(message, onRetry)
            }
        } else {
            Row(Modifier.widthIn(max = 760.dp).fillMaxWidth().padding(horizontal = 2.dp, vertical = 4.dp), verticalAlignment = Alignment.Top) {
                Surface(shape = androidx.compose.foundation.shape.CircleShape, color = MiuixTheme.colorScheme.surfaceContainer, modifier = Modifier.size(30.dp)) {
                    Box(contentAlignment = Alignment.Center) { Icon(Icons.Default.AutoAwesome, null, modifier = Modifier.size(17.dp), tint = MiuixTheme.colorScheme.primary) }
                }
                Spacer(Modifier.width(10.dp))
                Box(Modifier.weight(1f).padding(top = 4.dp)) { MessageContent(message, onRetry) }
            }
        }
    }
}

private sealed interface ChatTimelineItem {
    val key: String

    data class Message(val message: ChatMessage) : ChatTimelineItem {
        override val key = message.id
    }

    data class Process(val messages: List<ChatMessage>) : ChatTimelineItem {
        override val key = "process:${messages.first().id}"
    }
}

private fun groupTimelineMessages(messages: List<ChatMessage>): List<ChatTimelineItem> {
    val result = mutableListOf<ChatTimelineItem>()
    var process = mutableListOf<ChatMessage>()
    fun flushProcess() {
        if (process.isNotEmpty()) {
            result += ChatTimelineItem.Process(process.toList())
            process = mutableListOf()
        }
    }
    messages.forEach { message ->
        if (message.kind == "thinking" || message.kind == "tool") {
            process += message
        } else {
            flushProcess()
            result += ChatTimelineItem.Message(message)
        }
    }
    flushProcess()
    return result
}

@Composable
private fun MessageContent(message: ChatMessage, onRetry: () -> Unit) {
    Column {
        RichMessageContent(message)
        if (message.attachments.isNotEmpty()) Text("${message.attachments.size} 个附件", style = MiuixTheme.textStyles.footnote2, color = MiuixTheme.colorScheme.onSurfaceSecondary)
        if (!message.isStreaming && message.role != "user" && message.content.isBlank()) TextButton("重试", onRetry)
        if (message.isStreaming) InfiniteProgressIndicator(modifier = Modifier.fillMaxWidth())
    }
}

private fun effectiveReasoningEfforts(model: ModelInfo?): List<String> {
    return model?.effectiveReasoningEfforts().orEmpty()
}

private fun reasoningEffortLabel(value: String) = when (value.lowercase()) {
    "none" -> "关闭"
    "low" -> "低"
    "medium" -> "中"
    "high" -> "高"
    "xhigh" -> "极高"
    "max" -> "最大"
    else -> value
}

@Composable
private fun EmptyConversation() {
    Column(Modifier.fillMaxWidth().padding(top = 110.dp, bottom = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(shape = androidx.compose.foundation.shape.CircleShape, color = MiuixTheme.colorScheme.surfaceContainer, modifier = Modifier.size(58.dp)) {
            Box(contentAlignment = Alignment.Center) { Icon(Icons.Default.AutoAwesome, null, modifier = Modifier.size(28.dp), tint = MiuixTheme.colorScheme.onSurface) }
        }
        Spacer(Modifier.height(14.dp))
        Text("有什么可以帮忙的？", style = MiuixTheme.textStyles.title2, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text("消息会发送到当前 VS Code 工作区", color = MiuixTheme.colorScheme.onSurfaceSecondary)
    }
}
