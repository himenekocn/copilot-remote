package com.copilot.remote.ui.screens

import android.net.Uri
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
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
fun ChatScreen(viewModel: CopilotViewModel, onOpenNavigation: () -> Unit = {}) {
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
    var previewAttachment by remember { mutableStateOf<ChatAttachment?>(null) }
    var showAddMenu by remember { mutableStateOf(false) }
    var showChatMenu by remember { mutableStateOf(false) }
    var showPermissions by remember { mutableStateOf(false) }
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
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onOpenNavigation, modifier = Modifier.size(52.dp), backgroundColor = MiuixTheme.colorScheme.surfaceContainer) { Icon(Icons.Default.ArrowBack, "打开导航", modifier = Modifier.size(25.dp)) }
            Surface(Modifier.weight(1f).padding(horizontal = 8.dp), shape = RoundedCornerShape(25.dp), color = MiuixTheme.colorScheme.surfaceContainer) {
                Column(Modifier.clickable { showSessions = true }.padding(horizontal = 18.dp, vertical = 9.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    val activeTitle = state.nativeChatSessions.find { it.id == state.activeNativeChatSessionId }?.title
                        ?: state.chatSessions.find { it.id == state.activeChatSessionId }?.title
                        ?: "新对话"
                    Text(activeTitle.ifBlank { "新对话" }, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(state.connectedWorkspaceName.ifBlank { "Copilot" }, style = MiuixTheme.textStyles.footnote2, color = MiuixTheme.colorScheme.onSurfaceSecondary, maxLines = 1)
                }
            }
            Surface(shape = RoundedCornerShape(25.dp), color = MiuixTheme.colorScheme.surfaceContainer) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (state.isSending) CircularProgressIndicator(Modifier.padding(start = 14.dp).size(22.dp))
                    IconButton({ showChatMenu = true }, modifier = Modifier.size(52.dp)) { Icon(Icons.Default.MoreVert, "对话菜单", modifier = Modifier.size(25.dp)) }
                }
            }
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
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState, contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (state.activeNativeChatSessionId.isNotBlank() && state.nativeMessagesHasMoreBefore) item { Button(viewModel::loadOlderNativeMessages, modifier = Modifier.fillMaxWidth()) { Text("加载更早消息") } }
            if (state.nativeMessagesLoading && state.chatMessages.isEmpty()) item {
                Column(Modifier.fillMaxWidth().padding(top = 100.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(Modifier.size(30.dp))
                    Spacer(Modifier.height(12.dp))
                    Text("正在读取会话记录…", color = MiuixTheme.colorScheme.onSurfaceSecondary)
                }
            } else if (state.chatMessages.isEmpty()) item { EmptyConversation() }
            items(state.chatMessages, key = { it.id }) { message ->
                MessageCard(message) { viewModel.retryMessage(message.id) }
            }
        }
        if (attachments.isNotEmpty()) LazyRow(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(attachments.size) { index ->
                val item = attachments[index]
                AttachmentThumbnail(item, onPreview = { previewAttachment = item }, onRemove = { attachments = attachments.filterIndexed { i, _ -> i != index } })
            }
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
        val selectedModel = state.models.find { it.id == state.selectedModelId }
        val effortLabel = state.selectedReasoningEffort.takeIf { it.isNotBlank() }?.let(::reasoningEffortLabel)
        val selectedAgent = state.participants.find { it.name == state.selectedParticipant || it.id == state.selectedParticipant }
        LazyRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item { ChatOptionButton(listOfNotNull(selectedModel?.name ?: "自动模型", effortLabel).joinToString(" · ")) { showModels = true } }
            item { ChatOptionButton(selectedAgent?.fullName ?: "直接对话") { showAgents = true } }
            item { ChatOptionButton(permissionLabel(state.permissionLevel)) { showPermissions = true } }
            item { ChatOptionButton(state.enabledTools?.let { "工具 ${it.size}" } ?: "工具 默认") { showTools = true } }
        }
        Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 12.dp, end = 12.dp, bottom = 10.dp), verticalAlignment = Alignment.Bottom) {
            IconButton(onClick = { showAddMenu = true }, enabled = !state.isSending, modifier = Modifier.size(54.dp), backgroundColor = MiuixTheme.colorScheme.surfaceContainer) { Icon(Icons.Default.Add, "添加上下文", modifier = Modifier.size(27.dp)) }
            Spacer(Modifier.width(8.dp))
            Surface(modifier = Modifier.weight(1f), shape = RoundedCornerShape(28.dp), color = MiuixTheme.colorScheme.surfaceVariant) {
              Row(Modifier.padding(5.dp), verticalAlignment = Alignment.Bottom) {
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
                                    "向 Copilot 提问",
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
    SuperDialog(show = showPermissions, title = "访问权限", onDismissRequest = { showPermissions = false }) {
        Column {
            listOf("default" to "默认权限", "autoApprove" to "自动批准", "autopilot" to "完全访问权限").forEach { (value, label) ->
                BasicComponent(title = label, endActions = { if (state.permissionLevel == value) Icon(Icons.Default.Check, null) }, onClick = { viewModel.selectPermissionLevel(value); showPermissions = false })
            }
        }
    }
    SuperDialog(show = showAddMenu, title = "添加到对话", onDismissRequest = { showAddMenu = false }) {
        Column {
            BasicComponent(title = "选择图片", startAction = { Icon(Icons.Default.Image, null) }, onClick = { showAddMenu = false; picker.launch("image/*") })
            BasicComponent(title = "截取 VS Code 窗口", startAction = { Icon(Icons.Default.PhotoCamera, null) }, onClick = { showAddMenu = false; viewModel.captureWindowForChat() })
            BasicComponent(title = "附加 VS Code 终端", startAction = { Icon(Icons.Default.Terminal, null) }, onClick = { showAddMenu = false; viewModel.refreshTerminals(); showTerminals = true })
        }
    }
    SuperDialog(show = showChatMenu, title = "对话操作", onDismissRequest = { showChatMenu = false }) {
        Column {
            BasicComponent(title = "新建对话", startAction = { Icon(Icons.Default.AddComment, null) }, onClick = { viewModel.newChatSession(); showChatMenu = false })
            BasicComponent(title = "全部对话", startAction = { Icon(Icons.Default.History, null) }, onClick = { showChatMenu = false; showSessions = true })
            BasicComponent(title = "配置工具", startAction = { Icon(Icons.Default.Handyman, null) }, onClick = { showChatMenu = false; showTools = true })
        }
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
    previewAttachment?.let { attachment ->
        SuperDialog(show = true, title = attachment.name, onDismissRequest = { previewAttachment = null }) {
            AttachmentImage(attachment, Modifier.fillMaxWidth().heightIn(max = 620.dp), ContentScale.Fit)
        }
    }
}

@Composable
private fun AttachmentThumbnail(attachment: ChatAttachment, onPreview: () -> Unit, onRemove: () -> Unit) {
    if (!attachment.mimeType.startsWith("image/")) {
        TextButton("${attachment.name} ×", onClick = onRemove)
        return
    }
    Box(Modifier.size(76.dp).clip(RoundedCornerShape(14.dp))) {
        AttachmentImage(attachment, Modifier.fillMaxSize().clickable(onClick = onPreview), ContentScale.Crop)
        IconButton(onClick = onRemove, modifier = Modifier.align(Alignment.TopEnd).size(28.dp), backgroundColor = MiuixTheme.colorScheme.surface.copy(alpha = .82f)) {
            Icon(Icons.Default.Close, "移除图片", modifier = Modifier.size(16.dp))
        }
    }
}

@Composable
private fun AttachmentImage(attachment: ChatAttachment, modifier: Modifier, contentScale: ContentScale) {
    val bitmap = remember(attachment.dataBase64) {
        runCatching { Base64.decode(attachment.dataBase64, Base64.DEFAULT) }.getOrNull()?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }?.asImageBitmap()
    }
    if (bitmap != null) androidx.compose.foundation.Image(bitmap, attachment.name, modifier, contentScale = contentScale)
    else Box(modifier, contentAlignment = Alignment.Center) { Icon(Icons.Default.BrokenImage, "图片无法显示") }
}

@Composable
private fun ChatOptionButton(label: String, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.width(104.dp).height(40.dp).clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        color = MiuixTheme.colorScheme.surfaceVariant,
    ) {
        Box(Modifier.fillMaxSize().padding(horizontal = 10.dp), contentAlignment = Alignment.Center) {
            Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

private fun permissionLabel(level: String) = when (level) {
    "autoApprove" -> "自动批准"
    "autopilot" -> "完全访问权限"
    else -> "默认权限"
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
