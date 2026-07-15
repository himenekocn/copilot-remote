package com.copilot.remote.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.copilot.remote.data.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import com.copilot.remote.NotificationHelper

data class CopilotUiState(
    val connectionState: ConnectionState = ConnectionState.DISCONNECTED,
    val status: StatusInfo? = null,
    val models: List<ModelInfo> = emptyList(),
    val participants: List<ParticipantInfo> = emptyList(),
    val commands: List<CommandInfo> = emptyList(),
    val extensions: List<ExtensionInfo> = emptyList(),
    val mcpServers: List<McpServerInfo> = emptyList(),
    val skills: List<SkillInfo> = emptyList(),
    val workspaceInfo: WorkspaceInfo? = null,
    val chatSessions: List<ChatSession> = emptyList(),
    val activeChatSessionId: String = "",
    val nativeChatSessions: List<NativeChatSession> = emptyList(),
    val activeNativeChatSessionId: String = "",
    val nativeSessionTotal: Int = 0,
    val nativeSessionsHasMore: Boolean = false,
    val nativeSessionsLoading: Boolean = false,
    val nativeMessageStart: Int = 0,
    val nativeMessageTotal: Int = 0,
    val nativeMessagesHasMoreBefore: Boolean = false,
    val nativeMessagesLoading: Boolean = false,
    val chatMessages: List<ChatMessage> = emptyList(),
    val selectedModelId: String = "",
    val selectedReasoningEffort: String = "",
    val activeReasoningEffort: String = "",
    val activeOfficialSessionResource: String = "",
    val activeResponseModelId: String = "",
    val chatUsage: ChatUsageInfo? = null,
    val chatTodos: List<ChatTodoItem> = emptyList(),
    val selectedParticipant: String = "",
    val permissionLevel: String = "default",
    val enabledTools: Set<String>? = null,
    val isSending: Boolean = false,
    val errorMessage: String? = null,
    val notice: UiNotice? = null,
    val commandResults: Map<String, Any?> = emptyMap(),
    val activeEditorContent: String? = null,
    val editorFilePath: String = "",
    val editorContent: String = "",
    val editorOriginalContent: String = "",
    val editorLoading: Boolean = false,
    val terminals: List<TerminalInfo> = emptyList(),
    val terminalOutput: String = "",
    val searchResults: List<SearchResult> = emptyList(),
    val searchResultKind: String = "",
    val directoryPath: String = "",
    val directoryEntries: List<DirectoryEntry> = emptyList(),
    val gitBranch: String = "",
    val gitBranches: List<String> = emptyList(),
    val gitChanges: List<GitChange> = emptyList(),
    val gitHistory: List<GitCommit> = emptyList(),
    val gitDiff: String = "",
    val pullRequests: List<PullRequestInfo> = emptyList(),
    val editorOperationResult: String = "",
    val pendingSharedText: String = "",
    val pendingSharedImageUri: String = "",
    val pendingCaptureAttachment: ChatAttachment? = null,
    val pushStatus: String = "正在检查推送通知…",
    val captureFrames: List<String> = emptyList(),
    val captureIntervalMs: Int = 0,
    // Connection settings
    val wsUrl: String = "",
    val key: String = "",
    // Multi-server profiles
    val serverProfiles: List<ServerProfile> = emptyList(),
    val activeProfileId: String = "",
    // Multi-connection / multi-instance
    val connections: Map<String, Connection> = emptyMap(),
    val activeConnectionId: String = "",
    val activeInstanceId: String = "",
    val instances: List<InstanceInfo> = emptyList(),
    // Current instance info from active connection
    val connectedInstanceId: String = "",
    val connectedWorkspaceName: String = "",
)

class CopilotViewModel(
    private val settingsStore: SettingsStore,
    private val wsManager: WebSocketManager,
    private val notifications: NotificationHelper,
) : ViewModel() {

    private companion object {
        const val DEFAULT_CONNECTION_ID = "__default__"
    }

    private val TAG = "CopilotViewModel"
    private val INSTANCE_SCOPED_RESPONSE_TYPES = setOf(
        "models", "status", "workspaceInfo", "openEditors", "chatHistory",
        "activeChatSession", "activeSessionChanged", "todoUpdated", "nativeChatSessions",
        "nativeChatSession", "participants", "commands", "extensions", "mcpServers", "skills",
        "terminals", "terminalOutput", "searchResults", "directoryEntries", "fileContent",
        "fileSaved", "fileOpened", "fileClosed", "gitStatus", "gitDiff", "gitHistory",
        "pullRequests", "windowCapture", "chatStart", "chatDelta", "chatThinkingDelta",
        "toolUpdated", "chatUsage", "chatDone", "chatError", "chatCancelled",
    )

    private val _uiState = MutableStateFlow(CopilotUiState())
    val uiState: StateFlow<CopilotUiState> = _uiState.asStateFlow()

    private val _connections = MutableStateFlow<Map<String, Connection>>(emptyMap())

    private var messageCollectorJob: Job? = null
    private var stateCollectorJob: Job? = null
    private var settingsCollectorJob: Job? = null
    private val todoPollingJobs = mutableMapOf<String, Job>()
    private val thinkingSegments = mutableMapOf<String, Int>()
    private val pendingFeedbacks = mutableMapOf<String, String>()
    private var fcmToken = ""

    init {
        observeSettings()
        observeConnectionStates()
        observeRoutedMessages()
    }

    // ─── Settings observation ─────────────────────────────────────

    private fun observeSettings() {
        settingsCollectorJob = viewModelScope.launch {
            settingsStore.settings.collect { settings ->
                val oldProfiles = _uiState.value.serverProfiles
                val newProfiles = settings.serverProfiles

                _uiState.update {
                    it.copy(
                        wsUrl = settings.wsUrl,
                        key = settings.key,
                        selectedModelId = settings.selectedModelId,
                        selectedReasoningEffort = settings.selectedReasoningEffort,
                        selectedParticipant = settings.selectedParticipant,
                        permissionLevel = settings.permissionLevel,
                        enabledTools = settings.enabledTools,
                        serverProfiles = newProfiles,
                        activeProfileId = settings.activeProfileId,
                    )
                }

                // Update profile metadata inside each connection so UI labels stay current.
                if (oldProfiles != newProfiles) {
                    _connections.update { map ->
                        map.mapValues { (_, conn) ->
                            val profile = newProfiles.find { it.id == conn.profileId }
                            if (profile != null && profile != conn.profile) conn.copy(profile = profile) else conn
                        }
                    }
                    syncUiState()
                }
            }
        }
    }

    // ─── Connection state observation ─────────────────────────────

    private fun observeConnectionStates() {
        stateCollectorJob = viewModelScope.launch {
            wsManager.connectionStates.collect { event ->
                val profileId = event.profileId
                _connections.update { map ->
                    val conn = map[profileId] ?: Connection(profileId = profileId)
                    map + (profileId to conn.copy(connectionState = event.state))
                }

                if (event.state == ConnectionState.DISCONNECTED || event.state == ConnectionState.ERROR) {
                    stopTodoPolling(profileId)
                    _connections.update { map ->
                        val conn = map[profileId] ?: return@update map
                        map + (profileId to conn.copy(
                            connectionState = event.state,
                            // Keep other cached data so UI doesn't flash empty on reconnect.
                        ))
                    }
                }
                syncUiState()
            }
        }
    }

    // ─── Message routing ──────────────────────────────────────────

    private fun observeRoutedMessages() {
        messageCollectorJob = viewModelScope.launch {
            wsManager.routedMessages.collect { routed ->
                val json = if (routed.json.optString("type") == "proxyResult") {
                    routed.json.optJSONObject("message") ?: routed.json
                } else {
                    routed.json
                }
                handleMessage(routed.profileId, routed.instanceId, json)
            }
        }
    }

    private fun handleMessage(profileId: String, sourceInstanceId: String?, json: JSONObject) {
        val type = json.optString("type")
        Log.d(TAG, "[$profileId] Received message: $type")

        val connection = _connections.value[profileId]
        val selectedInstanceId = connection?.activeInstanceId.orEmpty()
        val effectiveSourceInstanceId = sourceInstanceId.orEmpty().ifBlank { connection?.connectedInstanceId.orEmpty() }
        if (
            type in INSTANCE_SCOPED_RESPONSE_TYPES &&
            selectedInstanceId.isNotBlank() &&
            effectiveSourceInstanceId.isNotBlank() &&
            selectedInstanceId != effectiveSourceInstanceId
        ) {
            Log.d(TAG, "[$profileId] Ignoring stale $type from $effectiveSourceInstanceId; active instance is $selectedInstanceId")
            return
        }

        when (type) {
            "authResult" -> {
                val success = json.optBoolean("success")
                if (success) {
                    val instanceId = json.optString("instanceId", "")
                    val wsName = json.optString("workspaceName", "")
                    updateConnection(profileId) { conn ->
                        val workspaceChanged = conn.connectedWorkspaceName.isNotBlank() &&
                            !conn.connectedWorkspaceName.equals(wsName, ignoreCase = true)
                        conn.copy(
                            connectedInstanceId = instanceId,
                            connectedWorkspaceName = wsName,
                            activeInstanceId = instanceId,
                            status = if (workspaceChanged) null else conn.status,
                            nativeChatSessions = if (workspaceChanged) emptyList() else conn.nativeChatSessions,
                            activeNativeChatSessionId = if (workspaceChanged) "" else conn.activeNativeChatSessionId,
                            nativeSessionTotal = if (workspaceChanged) 0 else conn.nativeSessionTotal,
                            nativeSessionsHasMore = if (workspaceChanged) false else conn.nativeSessionsHasMore,
                            nativeSessionsNextOffset = if (workspaceChanged) 0 else conn.nativeSessionsNextOffset,
                            nativeSessionsWorkspace = if (workspaceChanged) "" else conn.nativeSessionsWorkspace,
                            nativeSessionsLoading = if (workspaceChanged) true else conn.nativeSessionsLoading,
                            chatMessages = if (workspaceChanged && conn.activeNativeChatSessionId.isNotBlank()) emptyList() else conn.chatMessages,
                        )
                    }
                    if (_uiState.value.activeConnectionId == profileId) {
                        _uiState.update { it.copy(activeInstanceId = instanceId) }
                    }
                    saveProfileInstanceInfo(profileId, instanceId, wsName)
                    if (fcmToken.isNotBlank()) sendViaConnection(profileId, buildJsonCommand("registerPushToken") { put("token", fcmToken) })
                    refreshInstanceScopedState(profileId)
                    requestInstances(profileId)
                }
            }

            "instances" -> {
                val instances = parseInstances(json)
                updateConnection(profileId) { conn ->
                    val selectedInstanceId = conn.activeInstanceId.takeIf { selected -> instances.any { it.instanceId == selected } }
                        ?: instances.find { it.isPrimary }?.instanceId
                        ?: instances.firstOrNull()?.instanceId
                        ?: ""
                    conn.copy(
                        instances = instances,
                        supportsProxy = instances.isNotEmpty(),
                        activeInstanceId = selectedInstanceId,
                    )
                }
                syncUiState()
            }

            "models" -> {
                val models = parseModels(json)
                updateConnection(profileId) { it.copy(models = models) }
                if (_uiState.value.selectedModelId.isNotBlank() && models.none { it.id == _uiState.value.selectedModelId }) {
                    selectModel("")
                }
            }

            "activeChatSession", "activeSessionChanged" -> {
                val sessionResource = json.optString("sessionResource")
                val snapshot = json.optJSONObject("snapshot")
                val snapshotModelId = snapshot?.optString("modelId").orEmpty()
                val snapshotReasoningEffort = snapshot?.optString("reasoningEffort").orEmpty()
                updateConnection(profileId) { conn ->
                    if (snapshot == null) {
                        conn.copy(activeOfficialSessionResource = sessionResource)
                    } else {
                        val snapshotMessages = parseActiveSnapshotMessages(snapshot)
                        val snapshotTodos = parseTodoItems(snapshot.optJSONArray("todos"))
                        val snapshotUsage = snapshot.optJSONObject("usage")?.let { usage ->
                            ChatUsageInfo(
                                promptTokens = usage.optInt("promptTokens"),
                                completionTokens = usage.optInt("completionTokens"),
                                outputBuffer = usage.optInt("outputBuffer"),
                                maxInputTokens = usage.optInt("maxInputTokens", snapshot.optInt("maxInputTokens")),
                            )
                        }
                        conn.copy(
                        activeOfficialSessionResource = sessionResource,
                        activeResponseModelId = snapshotModelId.ifBlank { conn.activeResponseModelId },
                        activeReasoningEffort = snapshotReasoningEffort,
                        activeRequestId = snapshot.optString("requestId"),
                        chatMessages = snapshotMessages,
                        chatTodos = snapshotTodos,
                        chatUsage = snapshotUsage ?: conn.chatUsage,
                        isSending = snapshot.optString("status") == "running",
                    )
                    }
                }
                if (snapshot != null) {
                    syncModelConfigurationFromVscode(snapshotModelId, snapshotReasoningEffort)
                    if (snapshot.optString("status") == "running") startTodoPolling(profileId) else stopTodoPolling(profileId)
                }
                if (sessionResource.isNotBlank()) {
                    sendViaConnection(profileId, buildJsonCommand("getActiveChatTodos") {})
                    sendViaConnection(profileId, buildJsonCommand("listNativeChatSessions") {
                        put("offset", 0)
                        put("limit", 50)
                    })
                    if (json.optString("type") == "activeSessionChanged") {
                        sendViaConnection(profileId, buildJsonCommand("getActiveChatSession") {})
                    }
                }
            }

            "chatStart" -> {
                val requestId = json.optString("requestId")
                thinkingSegments["$profileId:$requestId"] = 0
                val responseModelId = json.optString("modelId")
                val responseReasoningEffort = json.optString("reasoningEffort")
                updateConnection(profileId) { conn ->
                    val isLocalRequest = conn.activeRequestId.isNotBlank() && requestId == conn.activeRequestId
                    val prompt = json.optString("prompt")
                    val messages = conn.chatMessages.toMutableList()
                    if (!isLocalRequest && prompt.isNotBlank()) {
                        messages.add(ChatMessage("user", prompt, id = "$requestId:user"))
                        messages.add(ChatMessage("assistant", "", isStreaming = true, id = "$requestId:assistant"))
                    }
                    conn.copy(
                        chatMessages = messages,
                        chatUsage = ChatUsageInfo(
                            promptTokens = estimateTokens(messages.filter { it.role == "user" }.sumOf { it.content.toByteArray().size }),
                            completionTokens = estimateTokens(messages.filter { it.role == "assistant" }.sumOf { it.content.toByteArray().size }),
                            maxInputTokens = json.optInt("maxInputTokens"),
                            estimated = true,
                        ),
                        isSending = true,
                        activeOfficialSessionResource = json.optString("sessionResource", conn.activeOfficialSessionResource),
                        activeResponseModelId = responseModelId.ifBlank { conn.activeResponseModelId },
                        activeReasoningEffort = responseReasoningEffort,
                        activeRequestId = requestId,
                    )
                }
                syncModelConfigurationFromVscode(responseModelId, responseReasoningEffort)
                startTodoPolling(profileId)
            }

            "chatDelta" -> {
                val requestId = json.optString("requestId")
                val delta = json.optString("delta")
                if (delta.isNotEmpty()) {
                    updateConnection(profileId) { conn ->
                        if (requestId.isNotBlank() && conn.activeRequestId.isNotBlank() && requestId != conn.activeRequestId) return@updateConnection conn
                        val messages = conn.chatMessages.toMutableList()
                        val lastIdx = messages.indexOfLast { it.isStreaming }
                        if (lastIdx >= 0) {
                            messages[lastIdx] = messages[lastIdx].copy(content = messages[lastIdx].content + delta)
                        } else {
                            messages.add(ChatMessage("assistant", delta, isStreaming = true, id = "$requestId:assistant"))
                        }
                        updateActiveSessionMessages(conn, messages)
                    }
                }
            }

            "chatDone" -> {
                val requestId = json.optString("requestId")
                val fullResponse = json.optString("fullResponse")
                updateConnection(profileId) { conn ->
                    if (requestId.isNotBlank() && conn.activeRequestId.isNotBlank() && requestId != conn.activeRequestId) return@updateConnection conn
                    val messages = conn.chatMessages.toMutableList()
                    val lastIdx = messages.indexOfLast { it.isStreaming }
                    val finalContent = fullResponse.ifEmpty { if (lastIdx >= 0) messages[lastIdx].content else "" }
                    if (lastIdx >= 0 && finalContent.isBlank()) {
                        messages.removeAt(lastIdx)
                    } else if (lastIdx >= 0) {
                        messages[lastIdx] = messages[lastIdx].copy(
                            content = finalContent,
                            isStreaming = false
                        )
                    } else if (fullResponse.isNotEmpty()) {
                        messages.add(ChatMessage("assistant", fullResponse))
                    }
                    updateActiveSessionMessages(conn, messages).copy(isSending = false, activeRequestId = "")
                }
                sendViaConnection(profileId, buildJsonCommand("getActiveChatSession") {})
                sendViaConnection(profileId, buildJsonCommand("getActiveChatTodos") {})
                sendViaConnection(profileId, buildJsonCommand("listNativeChatSessions") {
                    put("offset", 0)
                    put("limit", 50)
                })
                stopTodoPolling(profileId)
                notifications.show("Copilot finished", fullResponse.ifBlank { "Response completed" })
            }

            "chatThinkingDelta" -> {
                val requestId = json.optString("requestId")
                val segmentKey = "$profileId:$requestId"
                val segment = thinkingSegments[segmentKey] ?: 0
                val delta = json.optString("delta")
                if (delta.isNotBlank()) {
                    val sourceId = json.optString("thinkingId").ifBlank { segment.toString() }
                    upsertProgressMessage(profileId, requestId, "thinking", "Thinking", delta, append = true, idSuffix = "thinking:$segment:$sourceId")
                }
                if (json.optBoolean("done")) thinkingSegments[segmentKey] = segment + 1
            }

            "toolUpdated" -> {
                val requestId = json.optString("requestId")
                val toolCallId = json.optString("toolCallId")
                updateConnection(profileId) { conn ->
                    val messages = conn.chatMessages.toMutableList()
                    val id = "$requestId:tool:$toolCallId"
                    val message = ChatMessage(
                        role = "assistant",
                        content = json.optString("message").ifBlank { json.optString("output") }.ifBlank { json.optString("toolName") },
                        id = id,
                        kind = "tool",
                        toolName = json.optString("toolName"),
                        toolStatus = json.optString("status"),
                        toolInput = json.optString("input"),
                        toolOutput = json.optString("output"),
                    )
                    val index = messages.indexOfFirst { it.id == id }
                    if (index >= 0) messages[index] = message else {
                        val streamingIndex = messages.indexOfLast { it.isStreaming }
                        messages.add(if (streamingIndex >= 0) streamingIndex else messages.size, message)
                    }
                    updateActiveSessionMessages(conn, messages)
                }
            }

            "chatUsage" -> {
                val usage = json.optJSONObject("usage")
                if (usage != null) updateConnection(profileId) { it.copy(chatUsage = ChatUsageInfo(
                    promptTokens = usage.optInt("promptTokens"),
                    completionTokens = usage.optInt("completionTokens"),
                    outputBuffer = usage.optInt("outputBuffer"),
                    maxInputTokens = usage.optInt("maxInputTokens"),
                    estimated = false,
                )) }
            }

            "todoUpdated" -> {
                val todos = parseTodoItems(json.optJSONArray("items"))
                updateConnection(profileId) { it.copy(chatTodos = todos) }
            }

            "chatError" -> {
                val error = json.optString("error")
                notifications.show("Copilot error", error)
                updateConnection(profileId) { conn ->
                    val messages = conn.chatMessages.toMutableList()
                    val lastIdx = messages.indexOfLast { it.isStreaming }
                    if (lastIdx >= 0) {
                        messages[lastIdx] = messages[lastIdx].copy(
                            content = "Error: $error",
                            isStreaming = false
                        )
                    }
                    updateActiveSessionMessages(conn, messages).copy(isSending = false, activeRequestId = "", errorMessage = error)
                }
                stopTodoPolling(profileId)
            }

            "eventNotification" -> notifications.show(json.optString("title"), json.optString("message"))

            "pushTokenRegistered" -> {
                val error = json.optString("error")
                _uiState.update { it.copy(pushStatus = if (!json.optBoolean("success")) "推送注册失败：$error" else if (json.optBoolean("configured")) "推送通知已就绪" else "令牌已注册；请在 VS Code 中配置 Firebase 服务账号") }
            }

            "pushTokenUnregistered" -> Unit
            "windowCapture" -> updateConnection(profileId) { conn ->
                val frames = json.optJSONArray("frames") ?: JSONArray()
                conn.copy(captureFrames = (0 until frames.length()).map { frames.optString(it) }, captureIntervalMs = json.optInt("intervalMs"), errorMessage = json.optString("error").takeIf(String::isNotBlank))
            }

            "chatCancelled" -> {
                updateConnection(profileId) { conn ->
                    val messages = conn.chatMessages.toMutableList()
                    val lastIdx = messages.indexOfLast { it.isStreaming }
                    if (lastIdx >= 0) {
                        messages[lastIdx] = messages[lastIdx].copy(
                            content = messages[lastIdx].content + " [cancelled]",
                            isStreaming = false
                        )
                    }
                    updateActiveSessionMessages(conn, messages).copy(isSending = false, activeRequestId = "")
                }
                stopTodoPolling(profileId)
            }

            "commands" -> {
                updateConnection(profileId) { it.copy(commands = parseCommands(json)) }
            }

            "commandResult" -> {
                val commandId = json.optString("commandId")
                val result = json.opt("result")
                val error = json.optString("error").takeIf { it.isNotEmpty() }
                updateConnection(profileId) { conn ->
                    conn.copy(
                        commandResults = conn.commandResults + (commandId to (error ?: result)),
                        errorMessage = error
                    )
                }
            }

            "terminals" -> updateConnection(profileId) { conn ->
                val items = json.optJSONArray("terminals") ?: JSONArray()
                conn.copy(terminals = (0 until items.length()).map { index ->
                    items.getJSONObject(index).let { terminal ->
                        TerminalInfo(terminal.optString("id"), terminal.optString("name"), terminal.optInt("processId").takeIf { terminal.has("processId") }, terminal.optBoolean("isActive"))
                    }
                })
            }

            "terminalCreated" -> sendViaConnection(profileId, buildJsonCommand("listTerminals") {})

            "terminalClosed" -> {
                if (json.optBoolean("success")) sendViaConnection(profileId, buildJsonCommand("listTerminals") {})
                else updateConnection(profileId) { it.copy(errorMessage = json.optString("error")) }
            }

            "terminalOutput" -> updateConnection(profileId) { conn ->
                val error = json.optString("error")
                conn.copy(
                    terminalOutput = if (error.isBlank()) json.optString("output") else error,
                    errorMessage = error.takeIf { it.isNotBlank() },
                )
            }

            "searchResults" -> updateConnection(profileId) { conn ->
                val items = json.optJSONArray("results") ?: JSONArray()
                conn.copy(
                    searchResultKind = json.optString("kind"),
                    searchResults = (0 until items.length()).map { index ->
                        when (val item = items.get(index)) {
                            is String -> SearchResult(filePath = item)
                            else -> (item as JSONObject).let { result ->
                                SearchResult(
                                    name = result.optString("name"), filePath = result.optString("filePath"),
                                    line = result.optInt("line").takeIf { result.has("line") },
                                    character = result.optInt("character").takeIf { result.has("character") },
                                    preview = result.optString("preview"), kind = result.optString("kind"),
                                )
                            }
                        }
                    },
                    errorMessage = json.optString("error").takeIf { it.isNotBlank() },
                )
            }

            "directoryEntries" -> updateConnection(profileId) { conn ->
                val items = json.optJSONArray("entries") ?: JSONArray()
                conn.copy(
                    directoryPath = json.optString("path"),
                    directoryEntries = (0 until items.length()).map { index ->
                        items.getJSONObject(index).let { entry -> DirectoryEntry(entry.optString("name"), entry.optString("path"), entry.optString("type") == "directory") }
                    },
                    errorMessage = json.optString("error").takeIf { it.isNotBlank() },
                )
            }

            "fileOpened", "fileClosed" -> {
                val error = json.optString("error")
                if (error.isNotBlank()) updateConnection(profileId) { it.copy(errorMessage = error) }
                else sendViaConnection(profileId, buildJsonCommand("getWorkspaceInfo") {})
            }

            "fileContent" -> {
                val error = json.optString("error")
                val filePath = json.optString("filePath")
                val content = json.optString("content")
                updateConnection(profileId) { conn ->
                    conn.copy(
                        editorFilePath = filePath,
                        editorContent = if (error.isBlank()) content else conn.editorContent,
                        editorOriginalContent = if (error.isBlank()) content else conn.editorOriginalContent,
                        editorLoading = false,
                        errorMessage = error.takeIf(String::isNotBlank),
                    )
                }
            }

            "fileSaved" -> {
                val success = json.optBoolean("success")
                val error = json.optString("error")
                updateConnection(profileId) { conn ->
                    conn.copy(
                        editorOriginalContent = if (success) conn.editorContent else conn.editorOriginalContent,
                        editorLoading = false,
                        errorMessage = error.takeIf(String::isNotBlank),
                    )
                }
                showNotice(if (success) "文件已保存" else error.ifBlank { "文件保存失败" }, isError = !success)
            }

            "gitStatus" -> updateConnection(profileId) { conn ->
                val branches = json.optJSONArray("branches") ?: JSONArray()
                val changes = json.optJSONArray("changes") ?: JSONArray()
                conn.copy(
                    gitBranch = json.optString("branch"),
                    gitBranches = (0 until branches.length()).map { branches.optString(it) },
                    gitChanges = (0 until changes.length()).map { index -> changes.getJSONObject(index).let { GitChange(it.optString("path"), it.optString("status")) } },
                    errorMessage = json.optString("error").takeIf { it.isNotBlank() },
                )
            }
            "gitDiff" -> updateConnection(profileId) { it.copy(gitDiff = json.optString("diff"), errorMessage = json.optString("error").takeIf(String::isNotBlank)) }
            "gitHistory" -> updateConnection(profileId) { conn ->
                val commits = json.optJSONArray("commits") ?: JSONArray()
                conn.copy(gitHistory = (0 until commits.length()).map { index -> commits.getJSONObject(index).let { GitCommit(it.optString("hash"), it.optString("message"), it.optString("author"), it.optString("date")) } }, errorMessage = json.optString("error").takeIf(String::isNotBlank))
            }
            "gitOperation" -> {
                val error = json.optString("error")
                if (error.isNotBlank()) updateConnection(profileId) { it.copy(errorMessage = error) }
                else { sendViaConnection(profileId, buildJsonCommand("getGitStatus") {}); sendViaConnection(profileId, buildJsonCommand("getGitHistory") {}) }
            }

            "pullRequests" -> {
                val items = json.optJSONArray("pullRequests") ?: JSONArray()
                updateConnection(profileId) { it.copy(
                    pullRequests = (0 until items.length()).map { index -> items.getJSONObject(index).let { pr -> PullRequestInfo(pr.optInt("number"), pr.optString("title"), pr.optString("state"), pr.optString("author"), pr.optString("headRefName"), pr.optString("baseRefName"), pr.optString("url")) } },
                    errorMessage = json.optString("error").takeIf(String::isNotBlank),
                ) }
            }

            "pullRequestOperation" -> {
                val error = json.optString("error")
                if (error.isNotBlank()) updateConnection(profileId) { it.copy(errorMessage = error) }
                else sendViaConnection(profileId, buildJsonCommand("listPullRequests") {})
            }
            "editorSelectionSet" -> updateConnection(profileId) { conn ->
                val error = json.optString("error")
                conn.copy(editorOperationResult = if (error.isBlank()) "已在 VS Code 中更新选区" else error, errorMessage = error.takeIf(String::isNotBlank))
            }
            "replaceResult" -> updateConnection(profileId) { conn ->
                val error = json.optString("error")
                conn.copy(editorOperationResult = if (error.isBlank()) "已在 ${json.optInt("filesChanged")} 个文件中替换 ${json.optInt("replacements")} 处" else error, errorMessage = error.takeIf(String::isNotBlank))
            }

            "chatHistory" -> {
                val history = parseChatHistory(json)
                updateConnection(profileId) { conn ->
                    if (conn.activeOfficialSessionResource.isNotBlank() || conn.isSending) return@updateConnection conn
                    updateActiveSessionMessages(conn, history.map { h ->
                        ChatMessage(h.role, h.content, h.timestamp, h.modelId)
                    })
                }
            }

            "historyCleared" -> {
                updateConnection(profileId) { it.copy(chatMessages = emptyList()) }
            }

            "nativeChatSessions" -> {
                val sessions = parseNativeChatSessions(json).filter { it.requestCount > 0 }
                val offset = json.optInt("offset", 0)
                updateConnection(profileId) { conn ->
                    val responseWorkspace = json.optString("workspace")
                    if (responseWorkspace.isNotBlank() && conn.nativeSessionsWorkspace.isNotBlank() &&
                        !responseWorkspace.equals(conn.nativeSessionsWorkspace, ignoreCase = true)) {
                        Log.d(TAG, "[$profileId] Ignoring stale conversation list for $responseWorkspace; expected ${conn.nativeSessionsWorkspace}")
                        return@updateConnection conn
                    }
                    val merged = (if (offset == 0) sessions else conn.nativeChatSessions + sessions).distinctBy { it.id }
                    val hasMore = json.optBoolean("hasMore")
                    conn.copy(
                        nativeChatSessions = merged,
                        nativeSessionTotal = if (hasMore) json.optInt("total", merged.size) else merged.size,
                        nativeSessionsHasMore = hasMore,
                        nativeSessionsLoading = false,
                        nativeSessionsNextOffset = json.optInt("nextOffset", offset + sessions.size),
                    )
                }
                val warning = json.optString("warning").takeIf { it.isNotEmpty() }
                if (warning != null) {
                    updateConnection(profileId) { it.copy(errorMessage = warning) }
                }
            }

            "nativeChatSession" -> {
                val sessionJson = json.optJSONObject("session")
                if (sessionJson != null) {
                    val session = parseNativeChatSession(sessionJson)
                    val page = json.optJSONObject("messagePage")
                    updateConnection(profileId) { conn ->
                        if (conn.activeNativeChatSessionId != session.id) return@updateConnection conn
                        val sessions = conn.nativeChatSessions.filterNot { it.id == session.id } + session
                        val loadingOlder = conn.nativeMessagesLoading && conn.activeNativeChatSessionId == session.id
                        val mergedMessages = if (loadingOlder) {
                            (session.messages + conn.chatMessages).distinctBy { it.id }
                        } else session.messages
                        conn.copy(
                            nativeChatSessions = sessions.sortedByDescending { it.updatedAt },
                            activeNativeChatSessionId = session.id,
                            activeChatSessionId = "",
                            chatMessages = mergedMessages,
                            nativeMessageStart = page?.optInt("start") ?: 0,
                            nativeMessageTotal = page?.optInt("total") ?: mergedMessages.size,
                            nativeMessagesHasMoreBefore = page?.optBoolean("hasMoreBefore") ?: false,
                            nativeMessagesLoading = false,
                            isSending = false,
                        )
                    }
                }
            }

            "participants" -> {
                val participants = parseParticipants(json)
                updateConnection(profileId) { it.copy(participants = participants) }
                if (profileId == _uiState.value.activeConnectionId) {
                    val current = _uiState.value.selectedParticipant
                    if (participants.none { it.name == current }) {
                        selectParticipant(participants.firstOrNull()?.name.orEmpty())
                    }
                }
            }

            "extensions" -> {
                updateConnection(profileId) { it.copy(extensions = parseExtensions(json)) }
            }

            "extensionToggled" -> {
                wsManager.listExtensions(profileId)
            }

            "mcpServers" -> {
                updateConnection(profileId) { it.copy(mcpServers = parseMcpServers(json)) }
            }

            "mcpServerAdded", "mcpServerRemoved" -> {
                wsManager.listMcpServers(profileId)
            }

            "skills" -> {
                updateConnection(profileId) { it.copy(skills = parseSkills(json)) }
            }

            "skillInvoked" -> {
                val success = json.optBoolean("success")
                val error = json.optString("error").takeIf { it.isNotEmpty() }
                if (!success && error != null) {
                    updateConnection(profileId) { it.copy(errorMessage = error) }
                }
            }

            "workspaceInfo" -> {
                updateConnection(profileId) { it.copy(workspaceInfo = parseWorkspaceInfo(json)) }
            }

            "openEditors" -> {
                val editors = parseWorkspaceInfo(json).openEditors
                updateConnection(profileId) { conn ->
                    conn.copy(workspaceInfo = conn.workspaceInfo?.copy(openEditors = editors))
                }
            }

            "status" -> {
                val status = parseStatus(json)
                var workspaceChanged = false
                updateConnection(profileId) { conn ->
                    // The transport endpoint identity is established by authResult.
                    // A proxied status belongs to the selected VS Code window and
                    // must not overwrite the physical connection's primary ID.
                    val oldWorkspace = conn.status?.workspaceName.orEmpty()
                    workspaceChanged = oldWorkspace.isNotBlank() &&
                        !oldWorkspace.equals(status.workspaceName, ignoreCase = true)
                    conn.copy(
                        status = status,
                        nativeChatSessions = if (workspaceChanged) emptyList() else conn.nativeChatSessions,
                        activeNativeChatSessionId = if (workspaceChanged) "" else conn.activeNativeChatSessionId,
                        nativeSessionTotal = if (workspaceChanged) 0 else conn.nativeSessionTotal,
                        nativeSessionsHasMore = if (workspaceChanged) false else conn.nativeSessionsHasMore,
                        nativeSessionsLoading = if (workspaceChanged) true else conn.nativeSessionsLoading,
                        nativeSessionsNextOffset = if (workspaceChanged) 0 else conn.nativeSessionsNextOffset,
                        nativeSessionsWorkspace = if (workspaceChanged) status.workspaceName else conn.nativeSessionsWorkspace,
                        chatMessages = if (workspaceChanged && conn.activeNativeChatSessionId.isNotBlank()) emptyList() else conn.chatMessages,
                    )
                }
                if (workspaceChanged) requestNativeChatSessions(profileId, 0, withFeedback = false)
            }

            "copilotChatSent" -> {
                val success = json.optBoolean("success")
                if (!success) {
                    updateConnection(profileId) {
                        it.copy(errorMessage = json.optString("error", "Failed to send to Copilot chat"))
                    }
                }
            }

            "activeEditorContent" -> {
                val content = json.optString("content")
                updateConnection(profileId) { it.copy(activeEditorContent = content) }
            }

            "textInserted" -> {
                val success = json.optBoolean("success")
                if (!success) {
                    updateConnection(profileId) {
                        it.copy(errorMessage = json.optString("error", "Failed to insert text"))
                    }
                }
            }

            "pong" -> { /* heartbeat response */ }

            "error" -> {
                Log.e(TAG, "[$profileId] Server error [${json.optString("code")}]: ${json.optString("message")}")
                updateConnection(profileId) { it.copy(
                    errorMessage = json.optString("message"),
                    nativeSessionsLoading = false,
                    nativeMessagesLoading = false,
                ) }
            }

            "log" -> {
                Log.d(TAG, "[$profileId] Server log [${json.optString("level")}]: ${json.optString("message")}")
            }
        }

        completePendingFeedback(profileId, effectiveSourceInstanceId, type)

        syncUiState()
    }

    // ─── Connection helpers ───────────────────────────────────────

    private fun updateConnection(profileId: String, transform: (Connection) -> Connection) {
        _connections.update { map ->
            val conn = map[profileId] ?: Connection(profileId = profileId)
            map + (profileId to transform(conn))
        }
    }

    private fun activeConnection(): Connection? {
        val id = _uiState.value.activeConnectionId
        return if (id.isNotBlank()) _connections.value[id] else null
    }

    private fun pickActiveConnectionId(): String {
        val current = _uiState.value.activeConnectionId
        val map = _connections.value
        if (current.isNotBlank() && map[current]?.connectionState == ConnectionState.AUTHENTICATED) {
            return current
        }
        return map.values
            .sortedWith(compareByDescending<Connection> { it.connectionState == ConnectionState.AUTHENTICATED }
                .thenByDescending { it.status?.isPrimary == true }
                .thenByDescending { it.connectionState == ConnectionState.CONNECTED }
                .thenByDescending { it.connectionState == ConnectionState.CONNECTING })
            .firstOrNull()?.profileId
            ?: current
    }

    private fun syncUiState() {
        val activeId = pickActiveConnectionId()
        val activeConn = if (activeId.isNotBlank()) _connections.value[activeId] else null
        val activeInstanceId = _uiState.value.activeInstanceId
        val instances = activeConn?.instances ?: emptyList()
        val effectiveInstanceId = if (instances.any { it.instanceId == activeInstanceId }) {
            activeInstanceId
        } else {
            instances.find { it.isPrimary }?.instanceId ?: instances.firstOrNull()?.instanceId ?: ""
        }
		val effectiveWorkspaceName = instances.find { it.instanceId == effectiveInstanceId }
			?.workspaceName
			?.takeIf { it.isNotBlank() }
			?: activeConn?.connectedWorkspaceName.orEmpty()

        _uiState.update { state ->
            state.copy(
                connections = _connections.value,
                activeConnectionId = activeId,
                activeInstanceId = effectiveInstanceId,
                instances = instances,
                connectionState = activeConn?.connectionState ?: ConnectionState.DISCONNECTED,
                status = activeConn?.status,
                models = activeConn?.models ?: emptyList(),
                participants = activeConn?.participants ?: emptyList(),
                commands = activeConn?.commands ?: emptyList(),
                extensions = activeConn?.extensions ?: emptyList(),
                mcpServers = activeConn?.mcpServers ?: emptyList(),
                skills = activeConn?.skills ?: emptyList(),
                workspaceInfo = activeConn?.workspaceInfo,
                chatSessions = activeConn?.chatSessions ?: emptyList(),
                activeChatSessionId = activeConn?.activeChatSessionId ?: "",
                nativeChatSessions = activeConn?.nativeChatSessions ?: emptyList(),
                activeNativeChatSessionId = activeConn?.activeNativeChatSessionId ?: "",
                nativeSessionTotal = activeConn?.nativeSessionTotal ?: 0,
                nativeSessionsHasMore = activeConn?.nativeSessionsHasMore ?: false,
                nativeSessionsLoading = activeConn?.nativeSessionsLoading ?: false,
                nativeMessageStart = activeConn?.nativeMessageStart ?: 0,
                nativeMessageTotal = activeConn?.nativeMessageTotal ?: 0,
                nativeMessagesHasMoreBefore = activeConn?.nativeMessagesHasMoreBefore ?: false,
                nativeMessagesLoading = activeConn?.nativeMessagesLoading ?: false,
                chatMessages = activeConn?.chatMessages ?: emptyList(),
                activeOfficialSessionResource = activeConn?.activeOfficialSessionResource ?: "",
                activeResponseModelId = activeConn?.activeResponseModelId ?: "",
                activeReasoningEffort = activeConn?.activeReasoningEffort ?: "",
                chatUsage = activeConn?.chatUsage,
                chatTodos = activeConn?.chatTodos ?: emptyList(),
                isSending = activeConn?.isSending ?: false,
                errorMessage = activeConn?.errorMessage,
                commandResults = activeConn?.commandResults ?: emptyMap(),
                activeEditorContent = activeConn?.activeEditorContent,
                editorFilePath = activeConn?.editorFilePath ?: "",
                editorContent = activeConn?.editorContent ?: "",
                editorOriginalContent = activeConn?.editorOriginalContent ?: "",
                editorLoading = activeConn?.editorLoading ?: false,
                terminals = activeConn?.terminals ?: emptyList(),
                terminalOutput = activeConn?.terminalOutput ?: "",
                searchResults = activeConn?.searchResults ?: emptyList(),
                searchResultKind = activeConn?.searchResultKind ?: "",
                directoryPath = activeConn?.directoryPath ?: "",
                directoryEntries = activeConn?.directoryEntries ?: emptyList(),
                gitBranch = activeConn?.gitBranch ?: "",
                gitBranches = activeConn?.gitBranches ?: emptyList(),
                gitChanges = activeConn?.gitChanges ?: emptyList(),
                gitHistory = activeConn?.gitHistory ?: emptyList(),
                gitDiff = activeConn?.gitDiff ?: "",
                pullRequests = activeConn?.pullRequests ?: emptyList(),
                editorOperationResult = activeConn?.editorOperationResult ?: "",
                captureFrames = activeConn?.captureFrames ?: emptyList(),
                captureIntervalMs = activeConn?.captureIntervalMs ?: 0,
                connectedInstanceId = activeConn?.connectedInstanceId ?: "",
                connectedWorkspaceName = effectiveWorkspaceName,
            )
        }
    }

    private fun saveProfileInstanceInfo(profileId: String, instanceId: String, workspaceName: String) {
        val profile = _uiState.value.serverProfiles.find { it.id == profileId }
        if (profile != null) {
            viewModelScope.launch {
                settingsStore.saveProfile(profile.copy(
                    lastConnected = System.currentTimeMillis(),
                    lastInstanceId = instanceId,
                    lastWorkspaceName = workspaceName,
                ))
            }
        }
    }

    // ─── Public connection API ────────────────────────────────────

    fun connect(url: String, key: String) {
        viewModelScope.launch {
            settingsStore.updateWsUrl(url)
            settingsStore.updateKey(key)
        }
        _connections.update { map ->
            val existing = map[DEFAULT_CONNECTION_ID]
            map + (DEFAULT_CONNECTION_ID to (existing ?: Connection(profileId = DEFAULT_CONNECTION_ID)))
        }
        _uiState.update { it.copy(activeConnectionId = DEFAULT_CONNECTION_ID, activeInstanceId = "") }
        syncUiState()
        wsManager.connect(DEFAULT_CONNECTION_ID, url, key)
    }

    fun disconnect() {
        val activeId = _uiState.value.activeConnectionId
        if (activeId.isNotBlank()) {
            disconnectFromProfile(activeId)
        } else {
            wsManager.disconnect()
        }
    }

    fun updateSettings(url: String, key: String) {
        viewModelScope.launch {
            settingsStore.updateWsUrl(url)
            settingsStore.updateKey(key)
        }
    }

    // ─── Server Profile Management ────────────────────────────────

    fun saveProfile(label: String, wsUrl: String, key: String) {
        val profile = ServerProfile(
            id = UUID.randomUUID().toString(),
            label = label,
            wsUrl = wsUrl,
            key = key,
        )
        viewModelScope.launch {
            settingsStore.saveProfile(profile)
        }
    }

    fun connectToProfile(profile: ServerProfile) {
        viewModelScope.launch {
            settingsStore.setActiveProfile(profile.id)
            settingsStore.updateWsUrl(profile.wsUrl)
            settingsStore.updateKey(profile.key)
        }
        _connections.update { map ->
            val existing = map[profile.id]
            map + (profile.id to (existing?.copy(profile = profile) ?: Connection(profileId = profile.id, profile = profile)))
        }
        _uiState.update { it.copy(activeConnectionId = profile.id) }
        wsManager.connect(profile.id, profile.wsUrl, profile.key)
    }

    fun disconnectFromProfile(profileId: String) {
        wsManager.disconnect(profileId)
        _connections.update { map ->
            map + (profileId to (map[profileId]?.copy(connectionState = ConnectionState.DISCONNECTED) ?: Connection(profileId = profileId)))
        }
        syncUiState()
    }

    fun deleteProfile(profileId: String) {
        viewModelScope.launch {
            settingsStore.deleteProfile(profileId)
            if (_uiState.value.activeProfileId == profileId) {
                settingsStore.setActiveProfile("")
            }
        }
        disconnectFromProfile(profileId)
        _connections.update { it - profileId }
        syncUiState()
    }

    fun selectConnection(profileId: String) {
        val conn = _connections.value[profileId] ?: return
        _uiState.update { it.copy(activeConnectionId = profileId) }
        if (conn.connectionState == ConnectionState.AUTHENTICATED) {
            refreshAll(profileId)
            requestInstances(profileId)
        }
        syncUiState()
    }

    fun refreshInstances(showFeedback: Boolean = true) {
        val profileId = _uiState.value.activeConnectionId
        if (profileId.isBlank()) return
        if (showFeedback) {
            pendingFeedbacks[feedbackKey(profileId, "", "instances")] = "VS Code 实例列表已刷新"
            showNotice("正在查找 VS Code 实例…")
        }
        if (!wsManager.listInstances(profileId) && showFeedback) {
            pendingFeedbacks.remove(feedbackKey(profileId, "", "instances"))
            showNotice("未连接到 VS Code，无法刷新实例", isError = true)
        }
    }

    // ─── Instance selection ───────────────────────────────────────

    fun selectInstance(instanceId: String) {
        _uiState.update { it.copy(activeInstanceId = instanceId) }
        val activeId = _uiState.value.activeConnectionId
        if (activeId.isNotBlank()) {
            stopTodoPolling(activeId)
            _connections.update { map ->
                val conn = map[activeId] ?: return@update map
                map + (activeId to conn.copy(
                    activeInstanceId = instanceId,
                    status = null,
                    models = emptyList(),
                    participants = emptyList(),
                    commands = emptyList(),
                    extensions = emptyList(),
                    mcpServers = emptyList(),
                    skills = emptyList(),
                    workspaceInfo = null,
                    nativeChatSessions = emptyList(),
                    activeNativeChatSessionId = "",
                    nativeSessionTotal = 0,
                    nativeSessionsHasMore = false,
                    nativeSessionsLoading = true,
                    nativeSessionsNextOffset = 0,
                    nativeSessionsWorkspace = "",
                    chatMessages = emptyList(),
                    activeOfficialSessionResource = "",
                    activeResponseModelId = "",
                    activeReasoningEffort = "",
                    chatUsage = null,
                    chatTodos = emptyList(),
                    terminals = emptyList(),
                    terminalOutput = "",
                    searchResults = emptyList(),
                    directoryPath = "",
                    directoryEntries = emptyList(),
                    gitBranch = "",
                    gitBranches = emptyList(),
                    gitChanges = emptyList(),
                    gitHistory = emptyList(),
                    gitDiff = "",
                    pullRequests = emptyList(),
                    editorFilePath = "",
                    editorContent = "",
                    editorOriginalContent = "",
                    editorLoading = false,
                    errorMessage = null,
                ))
            }
            val label = _connections.value[activeId]?.instances?.find { it.instanceId == instanceId }?.workspaceName.orEmpty().ifBlank { "VS Code 实例" }
            pendingFeedbacks[feedbackKey(activeId, instanceId, "workspaceInfo")] = "已切换到 $label"
            showNotice("正在切换到 $label…")
            refreshInstanceScopedState(activeId)
        }
        syncUiState()
    }

    private fun requestInstances(profileId: String) {
        wsManager.listInstances(profileId)
    }

    // ─── Remote Chat Sessions ────────────────────────────────────

    private fun ensureChatSession(conn: Connection): Connection {
        if (conn.activeChatSessionId.isNotBlank() && conn.chatSessions.any { it.id == conn.activeChatSessionId }) {
            return conn
        }
        val session = ChatSession(
            id = UUID.randomUUID().toString(),
            title = "新对话",
        )
        return conn.copy(
            chatSessions = conn.chatSessions + session,
            activeChatSessionId = session.id,
            chatMessages = session.messages,
        )
    }

    private fun updateActiveSessionMessages(conn: Connection, messages: List<ChatMessage>): Connection {
        val ensured = ensureChatSession(conn)
        val activeId = ensured.activeChatSessionId
        val title = messages.firstOrNull { it.role == "user" }?.content
            ?.lineSequence()?.firstOrNull()
            ?.take(32)
            ?.ifBlank { null }
            ?: ensured.chatSessions.find { it.id == activeId }?.title
            ?: "新对话"
        val sessions = ensured.chatSessions.map { session ->
            if (session.id == activeId) {
                session.copy(title = title, updatedAt = System.currentTimeMillis(), messages = messages)
            } else session
        }
        return ensured.copy(chatSessions = sessions, chatMessages = messages)
    }

    fun newChatSession() {
        val activeId = _uiState.value.activeConnectionId
        if (activeId.isBlank()) return
        val session = ChatSession(UUID.randomUUID().toString(), "新对话")
        updateConnection(activeId) { conn ->
            conn.copy(
                chatSessions = conn.chatSessions + session,
                activeChatSessionId = session.id,
                chatMessages = emptyList(),
                isSending = false,
            )
        }
        syncUiState()
    }

    fun selectChatSession(sessionId: String) {
        val activeId = _uiState.value.activeConnectionId
        if (activeId.isBlank()) return
        updateConnection(activeId) { conn ->
            val session = conn.chatSessions.find { it.id == sessionId } ?: return@updateConnection conn
            conn.copy(activeChatSessionId = session.id, chatMessages = session.messages, isSending = false)
        }
        syncUiState()
    }

    fun deleteChatSession(sessionId: String) {
        val activeId = _uiState.value.activeConnectionId
        if (activeId.isBlank()) return
        updateConnection(activeId) { conn ->
            val remaining = conn.chatSessions.filterNot { it.id == sessionId }
            val next = remaining.maxByOrNull { it.updatedAt }
            conn.copy(
                chatSessions = remaining,
                activeChatSessionId = next?.id ?: "",
                chatMessages = next?.messages ?: emptyList(),
                isSending = false,
            )
        }
        syncUiState()
    }

    fun refreshNativeChatSessions() = refreshActive { profileId ->
        requestNativeChatSessions(profileId, 0, withFeedback = true)
    }

    fun loadMoreNativeChatSessions() {
        val activeId = _uiState.value.activeConnectionId
        val conn = _connections.value[activeId] ?: return
        if (!conn.nativeSessionsHasMore || conn.nativeSessionsLoading) return
        requestNativeChatSessions(activeId, conn.nativeSessionsNextOffset, withFeedback = false)
    }

    fun selectNativeChatSession(sessionId: String) {
        val activeId = _uiState.value.activeConnectionId
        if (activeId.isBlank()) return
        updateConnection(activeId) { it.copy(activeNativeChatSessionId = sessionId, chatMessages = emptyList(), nativeMessagesLoading = true, isSending = false) }
        sendViaConnection(activeId, buildJsonCommand("getNativeChatSession") {
            put("sessionId", sessionId)
            put("limit", 100)
        })
        syncUiState()
    }

    fun loadOlderNativeMessages() {
        val activeId = _uiState.value.activeConnectionId
        val conn = _connections.value[activeId] ?: return
        if (!conn.nativeMessagesHasMoreBefore || conn.nativeMessagesLoading || conn.activeNativeChatSessionId.isBlank()) return
        updateConnection(activeId) { it.copy(nativeMessagesLoading = true) }
        sendViaConnection(activeId, buildJsonCommand("getNativeChatSession") {
            put("sessionId", conn.activeNativeChatSessionId)
            put("before", conn.nativeMessageStart)
            put("limit", 100)
        })
        syncUiState()
    }

    // ─── Chat ─────────────────────────────────────────────────────

    fun sendMessage(text: String, attachments: List<ChatAttachment> = emptyList()) {
        if (text.isBlank() && attachments.isEmpty()) return

        val activeId = _uiState.value.activeConnectionId
        if (activeId.isBlank()) return

        val state = _uiState.value
        if (state.activeOfficialSessionResource.isBlank()) {
            updateConnection(activeId) { it.copy(errorMessage = "Open the target Copilot Chat conversation in VS Code before sending.") }
            syncUiState()
            return
        }
        val messages = state.chatMessages.toMutableList()

        messages.add(ChatMessage("user", text, attachments = attachments))
        messages.add(ChatMessage("assistant", "", isStreaming = true))

        // Update both the connection cache and the derived UI state.
        updateConnection(activeId) { conn ->
            updateActiveSessionMessages(
                conn,
                conn.chatMessages + ChatMessage("user", text, attachments = attachments) + ChatMessage("assistant", "", isStreaming = true),
            ).copy(isSending = true)
        }
        syncUiState()

        val apiMessages = state.chatMessages.filter { !it.isStreaming }.toMutableList()
        apiMessages.add(ChatMessage("user", text, attachments = attachments))

        val requestId = UUID.randomUUID().toString()
        updateConnection(activeId) { it.copy(activeRequestId = requestId) }
        val chatJson = JSONObject().apply {
            put("type", "chat")
            put("requestId", requestId)
            if (state.selectedModelId.isNotBlank()) put("modelId", state.selectedModelId)
            put("messages", JSONArray(apiMessages.map { chatMessage ->
                JSONObject().apply {
                    put("role", chatMessage.role)
                    put("content", chatMessage.content)
                    if (chatMessage.attachments.isNotEmpty()) {
                        put("attachments", JSONArray(chatMessage.attachments.map { attachment ->
                            JSONObject().apply {
                                put("name", attachment.name)
                                put("mimeType", attachment.mimeType)
                                put("dataBase64", attachment.dataBase64)
                            }
                        }))
                    }
                }
            }))
            put("options", JSONObject().apply {
                put("sessionResource", state.activeOfficialSessionResource)
                if (state.selectedParticipant.isNotBlank()) put("participant", state.selectedParticipant)
                if (state.selectedReasoningEffort.isNotBlank()) put("reasoningEffort", state.selectedReasoningEffort)
                put("permissionLevel", state.permissionLevel)
                state.enabledTools?.let { put("enabledTools", JSONArray(it.toList())) }
            })
        }

        val conn = _connections.value[activeId]
        val targetInstanceId = state.activeInstanceId
        if (
            conn?.supportsProxy == true &&
            targetInstanceId.isNotBlank() &&
            targetInstanceId != conn.connectedInstanceId &&
            conn.instances.size > 1
        ) {
            wsManager.sendProxy(activeId, targetInstanceId, chatJson)
        } else {
            wsManager.sendTo(activeId, chatJson)
        }
    }

    fun selectPermissionLevel(level: String) {
        _uiState.update { it.copy(permissionLevel = level) }
        viewModelScope.launch { settingsStore.updatePermissionLevel(level) }
    }

    fun selectEnabledTools(tools: Set<String>?) {
        _uiState.update { it.copy(enabledTools = tools) }
        viewModelScope.launch { settingsStore.updateEnabledTools(tools) }
    }

    fun retryMessage(messageId: String) {
        val activeId = _uiState.value.activeConnectionId
        val state = _uiState.value
        val selectedIndex = state.chatMessages.indexOfFirst { it.id == messageId }
        val retryIndex = if (selectedIndex >= 0) {
            (selectedIndex downTo 0).firstOrNull { state.chatMessages[it].role == "user" } ?: -1
        } else -1
        if (activeId.isBlank() || retryIndex < 0 || state.isSending) return
        val message = state.chatMessages[retryIndex]
        updateConnection(activeId) { conn ->
            updateActiveSessionMessages(conn, state.chatMessages.take(retryIndex))
        }
        syncUiState()
        sendMessage(message.content, message.attachments)
    }

    fun cancelChat() {
        val activeId = _uiState.value.activeConnectionId
        val requestId = _connections.value[activeId]?.activeRequestId.orEmpty()
        if (activeId.isBlank() || requestId.isBlank()) return
        sendViaConnection(activeId, buildJsonCommand("cancelChat") { put("requestId", requestId) })
    }

    fun clearChat() {
        val activeId = _uiState.value.activeConnectionId
        if (activeId.isNotBlank()) {
            updateConnection(activeId) { updateActiveSessionMessages(it, emptyList()) }
            syncUiState()
        }
    }

    // ─── Model Selection ──────────────────────────────────────────

    fun selectModel(modelId: String) {
        val model = _uiState.value.models.find { it.id == modelId }
        val effort = _uiState.value.selectedReasoningEffort.takeIf { model?.reasoningEfforts?.contains(it) == true } ?: ""
        _uiState.update { it.copy(selectedModelId = modelId, selectedReasoningEffort = effort) }
        viewModelScope.launch {
            settingsStore.updateSelectedModel(modelId)
            settingsStore.updateSelectedReasoningEffort(effort)
        }
    }

    fun selectReasoningEffort(effort: String) {
        _uiState.update { it.copy(selectedReasoningEffort = effort) }
        viewModelScope.launch { settingsStore.updateSelectedReasoningEffort(effort) }
    }

    private fun syncModelConfigurationFromVscode(modelId: String, reasoningEffort: String) {
        val knownModelId = modelId.takeIf { id ->
            id.isNotBlank() && (_uiState.value.models.isEmpty() || _uiState.value.models.any { it.id == id })
        }.orEmpty()
        _uiState.update { state ->
            state.copy(
                selectedModelId = knownModelId.ifBlank { state.selectedModelId },
                selectedReasoningEffort = reasoningEffort,
            )
        }
        viewModelScope.launch {
            if (knownModelId.isNotBlank()) settingsStore.updateSelectedModel(knownModelId)
            settingsStore.updateSelectedReasoningEffort(reasoningEffort)
        }
    }

    fun selectParticipant(participant: String) {
        _uiState.update { it.copy(selectedParticipant = participant) }
        viewModelScope.launch {
            settingsStore.updateSelectedParticipant(participant)
        }
    }

    // ─── Commands ─────────────────────────────────────────────────

    fun executeCommand(commandId: String, args: List<Any>? = null) {
        sendViaActiveConnection(buildJsonCommand("executeCommand") {
            put("commandId", commandId)
            if (args != null) put("args", org.json.JSONArray(args))
        })
    }

    // ─── Extensions ───────────────────────────────────────────────

    fun toggleExtension(extensionId: String, enable: Boolean) {
        sendViaActiveConnection(buildJsonCommand("toggleExtension") {
            put("extensionId", extensionId)
            put("enable", enable)
        })
    }

    // ─── MCP ──────────────────────────────────────────────────────

    fun addMcpServer(name: String, command: String, args: List<String>) {
        sendViaActiveConnection(buildJsonCommand("addMcpServer") {
            put("config", JSONObject().apply {
                put("name", name)
                put("command", command)
                put("args", org.json.JSONArray(args))
            })
        })
    }

    fun removeMcpServer(name: String) {
        sendViaActiveConnection(buildJsonCommand("removeMcpServer") {
            put("name", name)
        })
    }

    // ─── Skills ───────────────────────────────────────────────────

    fun invokeSkill(skillId: String) {
        sendViaActiveConnection(buildJsonCommand("invokeSkill") {
            put("skillId", skillId)
        })
    }

    // ─── Copilot Chat ─────────────────────────────────────────────

    fun sendToCopilotChat(prompt: String, participant: String? = null, command: String? = null) {
        sendViaActiveConnection(buildJsonCommand("sendToCopilotChat") {
            put("prompt", prompt)
            if (participant != null) put("participant", participant)
            if (command != null) put("command", command)
        })
    }

    // ─── Workspace ────────────────────────────────────────────────

    fun insertText(text: String) {
        sendViaActiveConnection(buildJsonCommand("insertText") {
            put("text", text)
        })
    }

    fun getActiveEditorContent() {
        sendViaActiveConnection(buildJsonCommand("getActiveEditorContent") {})
    }

    // ─── Data Refresh ─────────────────────────────────────────────

    fun refreshAll(profileId: String? = null) {
        val id = profileId ?: _uiState.value.activeConnectionId
        if (id.isBlank()) return
        refreshInstanceScopedState(id)
    }

    fun refreshModels() = refreshCommand("listModels", "models", "模型列表已刷新")
    fun refreshParticipants() = refreshCommand("listParticipants", "participants", "智能体列表已刷新")
    fun refreshCommands() = refreshCommand("listCommands", "commands", "命令列表已刷新")
    fun refreshExtensions() = refreshCommand("listExtensions", "extensions", "扩展列表已刷新")
    fun refreshMcpServers() = refreshCommand("listMcpServers", "mcpServers", "MCP 服务已刷新")
    fun refreshSkills() = sendViaActiveConnection(buildJsonCommand("listSkills") {})
    fun refreshTerminals() = sendViaActiveConnection(buildJsonCommand("listTerminals") {})

    fun createTerminal(name: String) = sendViaActiveConnection(buildJsonCommand("createTerminal") {
        if (name.isNotBlank()) put("name", name)
    })

    fun closeTerminal(id: String) = sendViaActiveConnection(buildJsonCommand("closeTerminal") { put("terminalId", id) })

    fun executeTerminal(id: String, command: String) {
        if (id.isBlank() || command.isBlank()) return
        val activeId = _uiState.value.activeConnectionId
        updateConnection(activeId) { it.copy(terminalOutput = "正在运行…") }
        syncUiState()
        if (!sendViaActiveConnection(buildJsonCommand("executeTerminal") { put("terminalId", id); put("command", command) })) {
            updateConnection(activeId) { it.copy(terminalOutput = "未连接到 VS Code", errorMessage = "命令未发送") }
            syncUiState()
        }
    }

    fun searchWorkspace(kind: String, query: String) {
        if (query.isBlank()) return
        val type = when (kind) { "Files" -> "searchFiles"; "Symbols" -> "searchSymbols"; else -> "searchText" }
        sendViaActiveConnection(buildJsonCommand(type) { put("query", query) })
    }

    fun findDefinitions(filePath: String, line: Int, character: Int) = sendViaActiveConnection(buildJsonCommand("findDefinitions") {
        put("filePath", filePath); put("line", line); put("character", character)
    })

    fun findReferences(filePath: String, line: Int, character: Int) = sendViaActiveConnection(buildJsonCommand("findReferences") {
        put("filePath", filePath); put("line", line); put("character", character)
    })

    fun listDirectory(path: String = "") = sendViaActiveConnection(buildJsonCommand("listDirectory") { put("path", path) })

    fun refreshDirectory(path: String = _uiState.value.directoryPath) = refreshActive { profileId ->
        sendWithFeedback(profileId, buildJsonCommand("listDirectory") { put("path", path) }, "directoryEntries", "文件列表已刷新")
    }

    fun openFile(path: String, line: Int? = null, character: Int? = null) = sendViaActiveConnection(buildJsonCommand("openFile") {
        put("filePath", path); line?.let { put("line", it) }; character?.let { put("character", it) }
    })

    fun openEditorFile(path: String, line: Int? = null, character: Int? = null) {
        val activeId = _uiState.value.activeConnectionId
        if (activeId.isBlank()) return
        updateConnection(activeId) {
            it.copy(editorFilePath = path, editorContent = "", editorOriginalContent = "", editorLoading = true)
        }
        syncUiState()
        sendViaConnection(activeId, buildJsonCommand("openFile") {
            put("filePath", path); line?.let { put("line", it) }; character?.let { put("character", it) }
        })
        if (!sendViaConnection(activeId, buildJsonCommand("getFileContent") { put("filePath", path) })) {
            updateConnection(activeId) { it.copy(editorLoading = false, errorMessage = "无法读取文件：未连接到 VS Code") }
            syncUiState()
        }
    }

    fun updateEditorContent(content: String) {
        val activeId = _uiState.value.activeConnectionId
        if (activeId.isBlank()) return
        updateConnection(activeId) { it.copy(editorContent = content) }
        syncUiState()
    }

    fun saveEditorFile() {
        val activeId = _uiState.value.activeConnectionId
        val conn = _connections.value[activeId] ?: return
        if (conn.editorFilePath.isBlank() || conn.editorLoading) return
        updateConnection(activeId) { it.copy(editorLoading = true) }
        syncUiState()
        if (!sendViaConnection(activeId, buildJsonCommand("saveFileContent") {
            put("filePath", conn.editorFilePath)
            put("content", conn.editorContent)
        })) {
            updateConnection(activeId) { it.copy(editorLoading = false, errorMessage = "文件保存请求未发送") }
            syncUiState()
        }
    }

    fun closeEditorFile() {
        val activeId = _uiState.value.activeConnectionId
        if (activeId.isBlank()) return
        updateConnection(activeId) {
            it.copy(editorFilePath = "", editorContent = "", editorOriginalContent = "", editorLoading = false)
        }
        syncUiState()
    }

    fun closeFile(path: String) = sendViaActiveConnection(buildJsonCommand("closeFile") { put("filePath", path) })

    fun refreshGit() = refreshActive { profileId ->
        sendViaConnection(profileId, buildJsonCommand("getGitStatus") {})
        sendViaConnection(profileId, buildJsonCommand("getGitHistory") {})
        sendViaConnection(profileId, buildJsonCommand("listPullRequests") {})
    }
    fun getGitDiff(path: String? = null) = sendViaActiveConnection(buildJsonCommand("getGitDiff") { path?.let { put("filePath", it) } })
    fun checkoutBranch(branch: String) = sendViaActiveConnection(buildJsonCommand("checkoutGitBranch") { put("branch", branch) })
    fun createBranch(branch: String) { if (branch.isNotBlank()) sendViaActiveConnection(buildJsonCommand("createGitBranch") { put("branch", branch); put("checkout", true) }) }
    fun commitChanges(message: String) { if (message.isNotBlank()) sendViaActiveConnection(buildJsonCommand("commitGitChanges") { put("message", message); put("all", true) }) }
    fun openPullRequest(number: Int) = sendViaActiveConnection(buildJsonCommand("openPullRequest") { put("number", number) })
    fun createPullRequest(title: String, body: String = "", base: String = "") { if (title.isNotBlank()) sendViaActiveConnection(buildJsonCommand("createPullRequest") { put("title", title); put("body", body); if (base.isNotBlank()) put("base", base) }) }
    fun setEditorSelection(filePath: String, startLine: Int, startCharacter: Int, endLine: Int, endCharacter: Int) = sendViaActiveConnection(buildJsonCommand("setEditorSelection") {
        put("filePath", filePath); put("startLine", startLine); put("startCharacter", startCharacter); put("endLine", endLine); put("endCharacter", endCharacter)
    })
    fun replaceInFiles(query: String, replacement: String) { if (query.isNotEmpty()) sendViaActiveConnection(buildJsonCommand("replaceInFiles") { put("query", query); put("replacement", replacement) }) }
    fun acceptSharedText(text: String) { _uiState.update { it.copy(pendingSharedText = text) } }
    fun consumeSharedText() { _uiState.update { it.copy(pendingSharedText = "") } }
    fun acceptSharedImage(uri: String) { _uiState.update { it.copy(pendingSharedImageUri = uri) } }
    fun consumeSharedImage() { _uiState.update { it.copy(pendingSharedImageUri = "") } }
    fun attachCaptureToChat(frameIndex: Int) {
        val frame = _uiState.value.captureFrames.getOrNull(frameIndex) ?: return
        _uiState.update {
            it.copy(pendingCaptureAttachment = ChatAttachment("vscode-capture.png", "image/png", frame))
        }
    }
    fun consumeCaptureAttachment() { _uiState.update { it.copy(pendingCaptureAttachment = null) } }
    fun registerPushToken(token: String) {
        fcmToken = token
        _uiState.update { it.copy(pushStatus = "正在注册推送令牌…") }
        val profileId = _uiState.value.activeConnectionId
        if (profileId.isNotBlank()) sendViaConnection(profileId, buildJsonCommand("registerPushToken") { put("token", token) })
    }
    fun updatePushStatus(status: String) { _uiState.update { it.copy(pushStatus = status) } }
    fun captureWindow() = sendViaActiveConnection(buildJsonCommand("captureWindow") {})
    fun recordWindow() = sendViaActiveConnection(buildJsonCommand("recordWindow") { put("seconds", 5); put("fps", 2) })
    fun refreshWorkspace() = refreshCommand("getWorkspaceInfo", "workspaceInfo", "工作区信息已刷新")
    fun refreshStatus() = refreshCommand("getStatus", "status", "服务器状态已刷新")
    fun refreshHistory() = refreshCommand("getChatHistory", "chatHistory", "对话记录已刷新")
    fun refreshNativeSessions() = refreshNativeChatSessions()

    private fun estimateTokens(byteCount: Int): Int = if (byteCount == 0) 0 else (byteCount + 3) / 4

    private fun refreshActive(block: (String) -> Unit) {
        val activeId = _uiState.value.activeConnectionId
        if (activeId.isNotBlank()) block(activeId)
    }

    private fun refreshCommand(commandType: String, responseType: String, successMessage: String) = refreshActive { profileId ->
        sendWithFeedback(profileId, buildJsonCommand(commandType) {}, responseType, successMessage)
    }

    private fun sendWithFeedback(profileId: String, message: JSONObject, responseType: String, successMessage: String): Boolean {
        val instanceId = _connections.value[profileId]?.activeInstanceId.orEmpty()
        pendingFeedbacks[feedbackKey(profileId, instanceId, responseType)] = successMessage
        val sent = sendViaConnection(profileId, message)
        if (!sent) {
            pendingFeedbacks.remove(feedbackKey(profileId, instanceId, responseType))
            showNotice("未连接到 VS Code，刷新请求未发送", isError = true)
        }
        return sent
    }

    private fun completePendingFeedback(profileId: String, sourceInstanceId: String, responseType: String) {
        val activeInstanceId = _connections.value[profileId]?.activeInstanceId.orEmpty()
        val key = feedbackKey(profileId, sourceInstanceId.ifBlank { activeInstanceId }, responseType)
        val message = pendingFeedbacks.remove(key)
            ?: pendingFeedbacks.remove(feedbackKey(profileId, activeInstanceId, responseType))
            ?: pendingFeedbacks.remove(feedbackKey(profileId, "", responseType))
            ?: return
        showNotice(message)
    }

    private fun feedbackKey(profileId: String, instanceId: String, responseType: String) = "$profileId:$instanceId:$responseType"

    private fun showNotice(message: String, isError: Boolean = false) {
        _uiState.update { it.copy(notice = UiNotice(message = message, isError = isError)) }
    }

    fun consumeNotice(id: Long) {
        _uiState.update { state -> if (state.notice?.id == id) state.copy(notice = null) else state }
    }

    private fun refreshInstanceScopedState(profileId: String) {
        sendViaConnection(profileId, buildJsonCommand("listModels") {})
        sendViaConnection(profileId, buildJsonCommand("listParticipants") {})
        sendViaConnection(profileId, buildJsonCommand("listCommands") {})
        sendViaConnection(profileId, buildJsonCommand("listExtensions") {})
        sendViaConnection(profileId, buildJsonCommand("listMcpServers") {})
        sendViaConnection(profileId, buildJsonCommand("listSkills") {})
        sendViaConnection(profileId, buildJsonCommand("listTerminals") {})
        sendViaConnection(profileId, buildJsonCommand("getWorkspaceInfo") {})
        sendViaConnection(profileId, buildJsonCommand("getStatus") {})
        requestNativeChatSessions(profileId, 0, withFeedback = false)
        sendViaConnection(profileId, buildJsonCommand("getActiveChatSession") {})
        sendViaConnection(profileId, buildJsonCommand("getChatHistory") {})
    }

    private fun requestNativeChatSessions(profileId: String, offset: Int, withFeedback: Boolean) {
        val conn = _connections.value[profileId] ?: return
        val workspace = conn.status?.workspaceName?.takeIf { it.isNotBlank() }
            ?: conn.instances.find { it.instanceId == conn.activeInstanceId }?.workspaceName?.takeIf { it.isNotBlank() }
            ?: conn.connectedWorkspaceName.takeIf { it.isNotBlank() }
            ?: return
        updateConnection(profileId) {
            it.copy(
                nativeSessionsLoading = true,
                nativeSessionsWorkspace = workspace,
                nativeChatSessions = if (offset == 0 && !it.nativeSessionsWorkspace.equals(workspace, ignoreCase = true)) emptyList() else it.nativeChatSessions,
            )
        }
        syncUiState()
        val command = buildJsonCommand("listNativeChatSessions") {
            put("offset", offset)
            put("limit", 50)
            put("workspace", workspace)
        }
        if (withFeedback) {
            sendWithFeedback(profileId, command, "nativeChatSessions", "对话列表已刷新")
        } else {
            sendViaConnection(profileId, command)
        }
    }

    private fun startTodoPolling(profileId: String) {
        if (todoPollingJobs[profileId]?.isActive == true) return
        todoPollingJobs[profileId] = viewModelScope.launch {
            while (isActive) {
                sendViaConnection(profileId, buildJsonCommand("getActiveChatTodos") {})
                delay(1_500)
            }
        }
    }

    private fun stopTodoPolling(profileId: String) {
        todoPollingJobs.remove(profileId)?.cancel()
    }

    private fun upsertProgressMessage(profileId: String, requestId: String, kind: String, label: String, content: String, append: Boolean, idSuffix: String = kind) {
        val body = content.trim().takeIf { it.isNotBlank() } ?: return
        updateConnection(profileId) { conn ->
            val messages = conn.chatMessages.toMutableList()
            val id = "${requestId.ifBlank { "current" }}:$idSuffix"
            val index = messages.indexOfFirst { it.id == id }
            val next = if (append && index >= 0) messages[index].content + body else body
            val message = ChatMessage("assistant", next, id = id, kind = kind, toolName = label)
            if (index >= 0) messages[index] = message else {
                val streamingIndex = messages.indexOfLast { it.isStreaming }
                messages.add(if (streamingIndex >= 0) streamingIndex else messages.size, message)
            }
            updateActiveSessionMessages(conn, messages)
        }
    }

    private fun parseActiveSnapshotMessages(snapshot: JSONObject): List<ChatMessage> {
        val messages = snapshot.optJSONArray("messages") ?: return emptyList()
        val runningRequestId = snapshot.optString("requestId")
        val isRunning = snapshot.optString("status") == "running"
        return (0 until messages.length()).map { index ->
            val message = messages.getJSONObject(index)
            val id = message.optString("id").ifBlank { "snapshot:$index" }
            ChatMessage(
                id = id,
                role = message.optString("role"),
                content = message.optString("content"),
                timestamp = message.optLong("timestamp", System.currentTimeMillis()),
                isStreaming = isRunning && id == "$runningRequestId:assistant",
                kind = message.optString("kind", "message"),
                toolName = message.optString("toolName"),
                toolStatus = message.optString("toolStatus"),
                toolInput = message.optString("toolInput"),
                toolOutput = message.optString("toolOutput"),
            )
        }
    }

    private fun parseTodoItems(items: JSONArray?): List<ChatTodoItem> {
        if (items == null) return emptyList()
        return (0 until items.length()).map { index ->
            val item = items.getJSONObject(index)
            ChatTodoItem(item.optInt("id", index + 1), item.optString("title"), item.optString("status", "not-started"))
        }
    }

    // ─── Generic active-connection send helpers ───────────────────

    private fun buildJsonCommand(type: String, builder: JSONObject.() -> Unit): JSONObject {
        return JSONObject().apply {
            put("type", type)
            builder()
        }
    }

    private fun sendViaActiveConnection(message: JSONObject): Boolean {
        val activeId = _uiState.value.activeConnectionId
        if (activeId.isBlank()) return false

        return sendViaConnection(activeId, message)
    }

    private fun sendViaConnection(profileId: String, message: JSONObject): Boolean {
        if (profileId.isBlank()) return false

        val conn = _connections.value[profileId]
        val targetInstanceId = conn?.activeInstanceId?.ifBlank { _uiState.value.activeInstanceId } ?: _uiState.value.activeInstanceId
        val useProxy =
            conn?.supportsProxy == true &&
                targetInstanceId.isNotBlank() &&
                targetInstanceId != conn.connectedInstanceId &&
                conn.instances.size > 1
        if (useProxy) {
            return wsManager.sendProxy(profileId, targetInstanceId, message)
        } else {
            return wsManager.sendTo(profileId, message)
        }
    }

    fun clearError() {
        val activeId = _uiState.value.activeConnectionId
        if (activeId.isNotBlank()) {
            updateConnection(activeId) { it.copy(errorMessage = null) }
        }
        _uiState.update { it.copy(errorMessage = null) }
    }

    override fun onCleared() {
        super.onCleared()
        wsManager.disconnectAll()
    }
}
