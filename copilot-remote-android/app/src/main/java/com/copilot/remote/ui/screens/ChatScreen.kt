package com.copilot.remote.ui.screens

import android.net.Uri
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.animation.AnimatedVisibility
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.copilot.remote.data.ChatAttachment
import com.copilot.remote.data.ChatMessage
import com.copilot.remote.data.ChatTodoItem
import com.copilot.remote.data.ModelInfo
import com.copilot.remote.data.effectiveReasoningEfforts
import com.copilot.remote.viewmodel.CopilotViewModel
import kotlinx.coroutines.flow.first
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.extra.SuperDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun ChatScreen(viewModel: CopilotViewModel, onOpenNavigation: (() -> Unit)? = null) {
    val state by viewModel.uiState.collectAsState()
    val darkMode = isSystemInDarkTheme()
    val context = LocalContext.current
    val listState = rememberLazyListState()
    val timeline = remember(state.chatMessages) { buildChatTimeline(state.chatMessages) }
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
    LaunchedEffect(timeline.size, state.chatMessages.lastOrNull()?.id, state.chatMessages.lastOrNull()?.content?.length) {
        if (timeline.isNotEmpty()) {
            // Wait until LazyColumn has measured the newly received timeline.
            // During cold start it initially exposes the cached item count, which
            // can omit the final response even though state already contains it.
            val itemCount = snapshotFlow { listState.layoutInfo.totalItemsCount }
                .first { it >= timeline.size }
            withFrameNanos { }
            val lastIndex = itemCount - 1
            // First bring the final item into the measured viewport, then move to
            // the real scroll limit. LazyColumn cannot calculate that limit while
            // the final process/message cards are still virtualized.
            listState.scrollToItem(lastIndex)
            snapshotFlow { listState.layoutInfo.visibleItemsInfo.any { it.index == lastIndex && it.size > 0 } }
                .first { it }
            withFrameNanos { }
            listState.scrollToItem(lastIndex, Int.MAX_VALUE)
        }
    }
    val send = {
        if (input.isNotBlank() || attachments.isNotEmpty()) {
            viewModel.sendMessage(input, attachments)
            input = ""
            attachments = emptyList()
        }
    }
    val canSubmit = state.isSending || input.isNotBlank() || attachments.isNotEmpty()

    Box(Modifier.fillMaxSize().background(chatPageColor(darkMode)), contentAlignment = Alignment.TopCenter) {
      Column(Modifier.fillMaxHeight().widthIn(max = 900.dp).fillMaxWidth().imePadding()) {
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (onOpenNavigation != null) {
                IconButton(onClick = onOpenNavigation, modifier = Modifier.size(52.dp), backgroundColor = MiuixTheme.colorScheme.surfaceContainer) { Icon(Icons.Default.ArrowBack, "打开导航", modifier = Modifier.size(25.dp)) }
            }
            Surface(Modifier.weight(1f).padding(horizontal = 8.dp), shape = RoundedCornerShape(25.dp), color = MiuixTheme.colorScheme.surfaceContainer) {
                Column(Modifier.clickable { showSessions = true }.padding(horizontal = 18.dp, vertical = 9.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    val storedTitle = state.nativeChatSessions.find { it.id == state.activeNativeChatSessionId }?.title
                        ?: state.chatSessions.find { it.id == state.activeChatSessionId }?.title
                    val firstUserMessage = state.chatMessages.firstOrNull { it.role == "user" }?.content
                        ?.lineSequence()?.firstOrNull()?.trim()
                    val activeTitle = storedTitle?.takeUnless { it.isBlank() || it == "新对话" }
                        ?: firstUserMessage?.takeIf { it.isNotBlank() }
                        ?: "新对话"
                    Text(activeTitle, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val latestDate = state.chatMessages.lastOrNull()?.timestamp?.let(::formatHeaderDate)
                    Text(
                        listOfNotNull(state.connectedWorkspaceName.ifBlank { "Copilot" }, latestDate).joinToString(" · "),
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onSurfaceSecondary,
                        maxLines = 1,
                    )
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
        Box(Modifier.weight(1f).fillMaxWidth()) {
        LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = if (state.chatTodos.isNotEmpty()) 76.dp else 12.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (state.activeNativeChatSessionId.isNotBlank() && state.nativeMessagesHasMoreBefore) item { Button(viewModel::loadOlderNativeMessages, modifier = Modifier.fillMaxWidth()) { Text("加载更早消息") } }
            if (state.nativeMessagesLoading && state.chatMessages.isEmpty()) item {
                Column(Modifier.fillMaxWidth().padding(top = 100.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(Modifier.size(30.dp))
                    Spacer(Modifier.height(12.dp))
                    Text("正在读取会话记录…", color = MiuixTheme.colorScheme.onSurfaceSecondary)
                }
            } else if (state.chatMessages.isEmpty()) item { EmptyConversation() }
            items(timeline, key = { it.id }) { entry ->
                if (entry.dateLabel != null) {
                    DateSeparator(entry.dateLabel)
                } else if (entry.isProcess) {
                    ProcessMessageGroup(entry.messages)
                } else {
                    val message = entry.messages.first()
                    MessageCard(
                        message,
                        { viewModel.retryMessage(message.id) },
                        { previewAttachment = it },
                    ) { reference ->
                        val parsed = parseVsCodeReference(reference)
                        viewModel.openFileInVsCodeWithFeedback(parsed.first, parsed.second)
                    }
                }
            }
        }
        if (state.chatTodos.isNotEmpty()) {
            Box(Modifier.align(Alignment.TopCenter).fillMaxWidth().background(MiuixTheme.colorScheme.surface).padding(horizontal = 12.dp)) {
                TodoProgressCard(state.chatTodos, state.isSending)
            }
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
            items(state.nativeChatSessions, key = { it.id }) { session ->
                val date = formatConversationDate(session.updatedAt.takeIf { it > 0 } ?: session.createdAt)
                val location = session.workspaceName ?: session.source
                BasicComponent(
                    title = session.title.takeUnless { it.isBlank() || it == session.id || it.matches(Regex("[0-9a-fA-F-]{36}")) } ?: "未命名对话",
                    summary = listOfNotNull(date, location.takeIf { it.isNotBlank() }).joinToString(" · "),
                    onClick = { viewModel.selectNativeChatSession(session.id); showSessions = false },
                )
            }
        }
    }
    previewAttachment?.let { attachment ->
        ZoomableAttachmentViewer(attachment) { previewAttachment = null }
    }
}

@Composable
private fun ZoomableAttachmentViewer(attachment: ChatAttachment, onDismiss: () -> Unit) {
    val bitmap = remember(attachment.dataBase64) {
        runCatching { Base64.decode(attachment.dataBase64, Base64.DEFAULT) }.getOrNull()?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }?.asImageBitmap()
    }
    var scale by remember(attachment.dataBase64) { mutableFloatStateOf(1f) }
    var offset by remember(attachment.dataBase64) { mutableStateOf(Offset.Zero) }
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    fun bounded(value: Offset, targetScale: Float): Offset {
        val maxX = viewport.width * (targetScale - 1f) / 2f
        val maxY = viewport.height * (targetScale - 1f) / 2f
        return Offset(value.x.coerceIn(-maxX, maxX), value.y.coerceIn(-maxY, maxY))
    }
    fun reset() { scale = 1f; offset = Offset.Zero }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black).onSizeChanged { viewport = it }) {
            if (bitmap != null) {
                androidx.compose.foundation.Image(
                    bitmap = bitmap,
                    contentDescription = attachment.name,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize()
                        .graphicsLayer { scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y }
                        .pointerInput(attachment.dataBase64) {
                            detectTransformGestures { centroid, pan, zoom, _ ->
                                val nextScale = (scale * zoom).coerceIn(1f, 6f)
                                val center = Offset(viewport.width / 2f, viewport.height / 2f)
                                val focusAdjustment = (centroid - center) * (1f - nextScale / scale)
                                offset = if (nextScale == 1f) Offset.Zero else bounded(offset + pan + focusAdjustment, nextScale)
                                scale = nextScale
                            }
                        }
                        .pointerInput(attachment.dataBase64) {
                            detectTapGestures(onDoubleTap = {
                                if (scale > 1f) reset() else { scale = 2.5f; offset = Offset.Zero }
                            })
                        },
                )
            } else {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Icon(Icons.Default.BrokenImage, "图片无法显示", tint = Color.White) }
            }
            Row(Modifier.fillMaxWidth().statusBarsPadding().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onDismiss, modifier = Modifier.size(48.dp), backgroundColor = Color(0x99000000)) { Icon(Icons.Default.Close, "关闭图片", tint = Color.White) }
                Text(attachment.name, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(horizontal = 12.dp))
                if (scale > 1f) TextButton("${(scale * 100).toInt()}% · 复位", onClick = ::reset)
            }
            Text("双指缩放 · 拖动查看 · 双击放大/复位", color = Color.White.copy(alpha = .72f), style = MiuixTheme.textStyles.footnote1, modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(18.dp))
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
    val dark = isSystemInDarkTheme()
    Surface(
        modifier = Modifier.width(88.dp).height(40.dp).clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        color = if (dark) Color(0xFF3A3A3C) else Color(0xFFEBEBED),
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

private data class ChatTimelineEntry(
    val id: String,
    val messages: List<ChatMessage>,
    val isProcess: Boolean,
    val dateLabel: String? = null,
)

private fun buildChatTimeline(messages: List<ChatMessage>): List<ChatTimelineEntry> {
    val result = mutableListOf<ChatTimelineEntry>()
    val process = mutableListOf<ChatMessage>()
	var processRequestId: String? = null
    var activeDate: LocalDate? = null
    fun flushProcess() {
        if (process.isEmpty()) return
        result += ChatTimelineEntry("process:${process.first().id}", process.toList(), true)
        process.clear()
		processRequestId = null
    }
    messages.forEach { message ->
        val messageDate = messageDate(message.timestamp)
        if (messageDate != activeDate) {
            flushProcess()
            activeDate = messageDate
            result += ChatTimelineEntry("date:$messageDate", emptyList(), false, formatDateSeparator(messageDate))
        }
		if (message.kind == "thinking" && message.content.isBlank()) return@forEach
		if (message.kind == "tool" || message.kind == "thinking") {
			val requestId = message.id.substringBefore(':')
			if (process.isNotEmpty() && processRequestId != requestId) flushProcess()
			processRequestId = requestId
            process += message
        } else {
            flushProcess()
            result += ChatTimelineEntry(message.id, listOf(message), false)
        }
    }
    flushProcess()
    return result
}

@Composable
private fun DateSeparator(label: String) {
    val darkMode = isSystemInDarkTheme()
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f).height(1.dp).background(if (darkMode) Color(0xFF343A43) else Color(0xFFD6DCE5)))
        Surface(shape = RoundedCornerShape(12.dp), color = if (darkMode) Color(0xFF20252C) else Color(0xFFE8EDF4)) {
            Text(label, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp), style = MiuixTheme.textStyles.footnote2, color = MiuixTheme.colorScheme.onSurfaceSecondary)
        }
        Box(Modifier.weight(1f).height(1.dp).background(if (darkMode) Color(0xFF343A43) else Color(0xFFD6DCE5)))
    }
}

private val conversationDateFormatter = DateTimeFormatter.ofPattern("yyyy年M月d日 EEEE", Locale.SIMPLIFIED_CHINESE)
private val shortConversationDateFormatter = DateTimeFormatter.ofPattern("M月d日 EEEE", Locale.SIMPLIFIED_CHINESE)

private fun messageDate(timestamp: Long): LocalDate {
    val safeTimestamp = timestamp.takeIf { it >= 946684800000L } ?: System.currentTimeMillis()
    return Instant.ofEpochMilli(safeTimestamp).atZone(ZoneId.systemDefault()).toLocalDate()
}

private fun formatDateSeparator(date: LocalDate): String {
    val today = LocalDate.now()
    return when (date) {
        today -> "今天 · ${date.format(shortConversationDateFormatter)}"
        today.minusDays(1) -> "昨天 · ${date.format(shortConversationDateFormatter)}"
        else -> date.format(if (date.year == today.year) shortConversationDateFormatter else conversationDateFormatter)
    }
}

private fun formatConversationDate(timestamp: Long): String? {
    if (timestamp < 946684800000L) return null
    val date = messageDate(timestamp)
    val today = LocalDate.now()
    return when (date) {
        today -> "今天"
        today.minusDays(1) -> "昨天"
        else -> date.format(DateTimeFormatter.ofPattern(if (date.year == today.year) "M月d日" else "yyyy年M月d日", Locale.SIMPLIFIED_CHINESE))
    }
}

private fun formatHeaderDate(timestamp: Long): String? {
    if (timestamp < 946684800000L) return null
    return messageDate(timestamp).format(DateTimeFormatter.ofPattern("yyyy年M月d日", Locale.SIMPLIFIED_CHINESE))
}

@Composable
private fun TodoProgressCard(items: List<ChatTodoItem>, isRunning: Boolean) {
    val completed = items.count { it.status == "completed" }
    val active = items.indexOfFirst { it.status == "in-progress" }
    var expanded by remember(items.firstOrNull()?.id) { mutableStateOf(false) }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MiuixTheme.colorScheme.surfaceContainer,
    ) {
        Column {
            Row(
                Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(horizontal = 14.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Default.Checklist, "待办事项", Modifier.size(21.dp), tint = MiuixTheme.colorScheme.primary)
                Spacer(Modifier.width(9.dp))
                Column(Modifier.weight(1f)) {
                    Text("待办事项", fontWeight = FontWeight.SemiBold)
                    Text("$completed / ${items.size} 已完成", style = MiuixTheme.textStyles.footnote2, color = MiuixTheme.colorScheme.onSurfaceSecondary)
                }
                if (isRunning && completed < items.size) InfiniteProgressIndicator(Modifier.width(42.dp))
                Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, if (expanded) "收起待办" else "展开待办", Modifier.size(21.dp))
            }
            LinearProgressIndicator(
                progress = if (items.isEmpty()) 0f else completed.toFloat() / items.size,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp),
            )
            AnimatedVisibility(expanded) {
                Column(
                    Modifier.fillMaxWidth().heightIn(max = 360.dp).verticalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(9.dp),
                ) {
                    items.forEachIndexed { index, item ->
                        val inProgress = item.status == "in-progress" || (item.status == "not-started" && active < 0 && isRunning && index == completed)
                        Row(verticalAlignment = Alignment.Top) {
                            Icon(
                                when {
                                    item.status == "completed" -> Icons.Default.CheckCircle
                                    inProgress -> Icons.Default.PlayCircle
                                    else -> Icons.Default.RadioButtonUnchecked
                                },
                                null,
                                Modifier.padding(top = 1.dp).size(19.dp),
                                tint = if (item.status == "completed" || inProgress) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurfaceSecondary,
                            )
                            Spacer(Modifier.width(9.dp))
                            Text(
                                item.title,
                                modifier = Modifier.weight(1f),
                                style = MiuixTheme.textStyles.body2,
                                color = if (item.status == "completed") MiuixTheme.colorScheme.onSurfaceSecondary else MiuixTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MessageCard(message: ChatMessage, onRetry: () -> Unit, onPreviewAttachment: (ChatAttachment) -> Unit, onOpenReference: (String) -> Unit) {
    val user = message.role == "user"
    val darkMode = isSystemInDarkTheme()
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (user) Arrangement.End else Arrangement.Start) {
        if (user) {
            Card(modifier = Modifier.widthIn(max = 680.dp).wrapContentWidth(), cornerRadius = 18.dp, insideMargin = PaddingValues(horizontal = 14.dp, vertical = 11.dp), colors = CardDefaults.defaultColors(color = if (darkMode) Color(0xFF253B55) else Color(0xFFDDEBFF))) {
                MessageContent(message, onRetry, onPreviewAttachment, onOpenReference)
            }
        } else if (message.kind == "tool" || message.kind == "thinking") {
            Box(Modifier.widthIn(max = 760.dp).fillMaxWidth().padding(horizontal = 2.dp, vertical = 3.dp)) {
                MessageContent(message, onRetry, onPreviewAttachment, onOpenReference)
            }
        } else {
            Surface(
                modifier = Modifier.widthIn(max = 760.dp).fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                color = if (darkMode) Color(0xFF191D22) else Color(0xFFFFFFFF),
            ) {
                Box(Modifier.padding(horizontal = 13.dp, vertical = 11.dp)) {
                    MessageContent(message, onRetry, onPreviewAttachment, onOpenReference)
                }
            }
        }
    }
}

private fun chatPageColor(darkMode: Boolean) = if (darkMode) Color(0xFF101317) else Color(0xFFF3F5F8)

@Composable
private fun MessageContent(message: ChatMessage, onRetry: () -> Unit, onPreviewAttachment: (ChatAttachment) -> Unit, onOpenReference: (String) -> Unit) {
    Column {
        RichMessageContent(message, onOpenReference)
        if (message.attachments.isNotEmpty()) {
            LazyRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(message.attachments.size) { index ->
                    val attachment = message.attachments[index]
                    if (attachment.mimeType.startsWith("image/")) {
                        AttachmentImage(attachment, Modifier.size(92.dp).clip(RoundedCornerShape(12.dp)).clickable { onPreviewAttachment(attachment) }, ContentScale.Crop)
                    } else Text(attachment.name, style = MiuixTheme.textStyles.footnote2, color = MiuixTheme.colorScheme.onSurfaceSecondary)
                }
            }
        }
        if (!message.isStreaming && message.role != "user" && message.content.isBlank()) TextButton("重试", onRetry)
        if (message.isStreaming) InfiniteProgressIndicator(modifier = Modifier.fillMaxWidth())
    }
}

private fun parseVsCodeReference(reference: String): Pair<String, Int?> {
    val uri = Uri.parse(reference)
    val line = uri.fragment?.let { Regex("L(\\d+)", RegexOption.IGNORE_CASE).find(it)?.groupValues?.get(1)?.toIntOrNull() }
    val path = if (uri.scheme.equals("file", ignoreCase = true)) {
        Uri.decode(uri.path.orEmpty()).removePrefix("/").replace('/', '\\')
    } else reference.substringBefore('#')
    return path to line
}

private fun effectiveReasoningEfforts(model: ModelInfo?): List<String> {
    return model?.effectiveReasoningEfforts().orEmpty()
}

private fun reasoningEffortLabel(value: String) = when (value.lowercase()) {
	"minimal" -> "最小"
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
