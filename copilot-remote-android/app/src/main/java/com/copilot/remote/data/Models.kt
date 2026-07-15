package com.copilot.remote.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * Data models matching the WebSocket protocol defined in the VS Code extension.
 */

// ─── Connection State ─────────────────────────────────────────

enum class ConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    AUTHENTICATED,
    ERROR
}

// ─── Chat ─────────────────────────────────────────────────────

data class ChatMessage(
    val role: String, // "user" | "assistant"
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
    val modelId: String? = null,
    val isStreaming: Boolean = false,
    val attachments: List<ChatAttachment> = emptyList(),
    val id: String = java.util.UUID.randomUUID().toString(),
    val kind: String = "message",
    val toolName: String = "",
    val toolStatus: String = "",
    val toolInput: String = "",
    val toolOutput: String = "",
)

data class UiNotice(
    val id: Long = System.nanoTime(),
    val message: String,
    val isError: Boolean = false,
)

data class ChatTodoItem(
    val id: Int,
    val title: String,
    val status: String,
)

data class ChatUsageInfo(
    val promptTokens: Int,
    val completionTokens: Int,
    val outputBuffer: Int = 0,
    val maxInputTokens: Int,
    val estimated: Boolean = false,
)

data class ChatAttachment(
    val name: String,
    val mimeType: String,
    val dataBase64: String,
)

data class ChatHistoryItem(
    val id: String,
    val role: String,
    val content: String,
    val timestamp: Long,
    val modelId: String?,
)

data class ChatSession(
    val id: String,
    val title: String,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val messages: List<ChatMessage> = emptyList(),
)

data class NativeChatSession(
    val id: String,
    val title: String,
    val source: String,
    val filePath: String? = null,
    val workspaceName: String? = null,
    val workspaceFolder: String? = null,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
    val requestCount: Int = 0,
    val messages: List<ChatMessage> = emptyList(),
)

// ─── Models ───────────────────────────────────────────────────

data class ModelInfo(
    val id: String,
    val name: String,
    val vendor: String,
    val family: String,
    val version: String,
    val maxInputTokens: Int,
    val isDefault: Boolean = false,
    val reasoningEfforts: List<String> = emptyList(),
)

// ─── Commands ─────────────────────────────────────────────────

data class CommandInfo(
    val id: String,
    val isCopilot: Boolean = false,
)

// ─── Participants / Agents ────────────────────────────────────

data class ParticipantCommand(
    val name: String,
    val description: String,
)

data class ParticipantInfo(
    val id: String,
    val name: String,
    val fullName: String,
    val description: String,
    val isSticky: Boolean,
    val commands: List<ParticipantCommand>,
    val isBuiltIn: Boolean,
)

// ─── Extensions ───────────────────────────────────────────────

data class ExtensionInfo(
    val id: String,
    val name: String,
    val displayName: String,
    val version: String,
    val description: String,
    val isActive: Boolean,
    val isCopilot: Boolean,
    val isAI: Boolean,
)

// ─── MCP ──────────────────────────────────────────────────────

data class McpServerInfo(
    val name: String,
    val command: String,
    val args: List<String>,
    val env: Map<String, String>?,
    val status: String?,
)

// ─── Skills ───────────────────────────────────────────────────

data class SkillInfo(
    val id: String,
    val name: String,
    val description: String,
    val source: String,
    val sourceLabel: String,
)

// ─── Workspace ────────────────────────────────────────────────

data class EditorInfo(
    val fileName: String,
    val filePath: String,
    val languageId: String,
    val isDirty: Boolean,
    val isUntitled: Boolean,
    val scheme: String,
)

data class WorkspaceInfo(
    val name: String,
    val folders: List<WorkspaceFolder>,
    val activeEditor: EditorInfo?,
    val openEditors: List<EditorInfo>,
)

data class WorkspaceFolder(
    val name: String,
    val uri: String,
)

// ─── Status ───────────────────────────────────────────────────

data class StatusInfo(
    val serverRunning: Boolean,
    val port: Int,
    val host: String,
    val connectedClients: Int,
    val copilotAvailable: Boolean,
    val modelCount: Int,
    val workspaceName: String,
    val uptime: Long,
    // Multi-window instance fields
    val instanceId: String = "",
    val windowTitle: String = "",
    val workspaceFolders: List<String> = emptyList(),
    val pid: Int = 0,
    val isPrimary: Boolean = true,
)

// ─── Saved Server Profile (for multi-server support) ─────────

data class ServerProfile(
    val id: String,           // UUID for this saved profile
    val label: String,        // user-friendly label, e.g. "My Project"
    val wsUrl: String,        // ws://192.168.1.100:9876
    val key: String,          // auth key
    val lastConnected: Long = 0,
    val lastInstanceId: String = "",  // instanceId from server, if known
    val lastWorkspaceName: String = "", // workspace name from server
)

// ─── Multi-Window Instance Info ───────────────────────────────

data class InstanceInfo(
    val instanceId: String,
    val workspaceName: String,
    val windowTitle: String,
    val host: String,
    val port: Int,
    val isRunning: Boolean,
    val isPrimary: Boolean,
    val isLocal: Boolean,
    val pid: Int,
    val connected: Boolean,
)

data class TerminalInfo(
    val id: String,
    val name: String,
    val processId: Int?,
    val isActive: Boolean,
)

data class SearchResult(
    val name: String = "",
    val filePath: String,
    val line: Int? = null,
    val character: Int? = null,
    val preview: String = "",
    val kind: String = "",
)

data class DirectoryEntry(val name: String, val path: String, val isDirectory: Boolean)
data class GitChange(val path: String, val status: String)
data class GitCommit(val hash: String, val message: String, val author: String, val date: String)
data class PullRequestInfo(val number: Int, val title: String, val state: String, val author: String, val headRefName: String, val baseRefName: String, val url: String)

// ─── Per-Connection State ─────────────────────────────────────

/**
 * Holds all state for a single server connection (profile).
 * The UI switches between active connections via [activeInstanceId].
 */
data class Connection(
    val profileId: String,
    val profile: ServerProfile? = null,
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
    val nativeSessionsNextOffset: Int = 0,
    val nativeSessionsWorkspace: String = "",
    val nativeMessageStart: Int = 0,
    val nativeMessageTotal: Int = 0,
    val nativeMessagesHasMoreBefore: Boolean = false,
    val nativeMessagesLoading: Boolean = false,
    val chatMessages: List<ChatMessage> = emptyList(),
    val activeOfficialSessionResource: String = "",
    val activeResponseModelId: String = "",
    val activeReasoningEffort: String = "",
    val activeRequestId: String = "",
    val chatUsage: ChatUsageInfo? = null,
    val chatTodos: List<ChatTodoItem> = emptyList(),
    val instances: List<InstanceInfo> = emptyList(),
    val supportsProxy: Boolean = false,
    val activeInstanceId: String = "",
    val connectedInstanceId: String = "",
    val connectedWorkspaceName: String = "",
    val isSending: Boolean = false,
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
    val captureFrames: List<String> = emptyList(),
    val captureIntervalMs: Int = 0,
    val errorMessage: String? = null,
)

// ─── JSON Parsing Helpers ─────────────────────────────────────

fun parseModels(json: JSONObject): List<ModelInfo> {
    val arr = json.optJSONArray("models") ?: return emptyList()
    return (0 until arr.length()).map { i ->
        val m = arr.getJSONObject(i)
        ModelInfo(
            id = m.optString("id"),
            name = m.optString("name"),
            vendor = m.optString("vendor"),
            family = m.optString("family"),
            version = m.optString("version"),
            maxInputTokens = m.optInt("maxInputTokens"),
            isDefault = m.optBoolean("isDefault"),
            reasoningEfforts = m.optJSONArray("reasoningEfforts")?.let { values ->
                (0 until values.length()).map { values.optString(it) }
            } ?: emptyList(),
        )
    }
}

fun parseCommands(json: JSONObject): List<CommandInfo> {
    val arr = json.optJSONArray("commands") ?: return emptyList()
    return (0 until arr.length()).map { i ->
        val c = arr.getJSONObject(i)
        CommandInfo(
            id = c.optString("id"),
            isCopilot = c.optBoolean("isCopilot"),
        )
    }
}

fun parseParticipants(json: JSONObject): List<ParticipantInfo> {
    val arr = json.optJSONArray("participants") ?: return emptyList()
    return (0 until arr.length()).map { i ->
        val p = arr.getJSONObject(i)
        val cmds = p.optJSONArray("commands") ?: JSONArray()
        ParticipantInfo(
            id = p.optString("id"),
            name = p.optString("name"),
            fullName = p.optString("fullName"),
            description = p.optString("description"),
            isSticky = p.optBoolean("isSticky"),
            commands = (0 until cmds.length()).map { j ->
                val cmd = cmds.getJSONObject(j)
                ParticipantCommand(
                    name = cmd.optString("name"),
                    description = cmd.optString("description"),
                )
            },
            isBuiltIn = p.optBoolean("isBuiltIn"),
        )
    }
}

fun parseExtensions(json: JSONObject): List<ExtensionInfo> {
    val arr = json.optJSONArray("extensions") ?: return emptyList()
    return (0 until arr.length()).map { i ->
        val e = arr.getJSONObject(i)
        ExtensionInfo(
            id = e.optString("id"),
            name = e.optString("name"),
            displayName = e.optString("displayName"),
            version = e.optString("version"),
            description = e.optString("description"),
            isActive = e.optBoolean("isActive"),
            isCopilot = e.optBoolean("isCopilot"),
            isAI = e.optBoolean("isAI"),
        )
    }
}

fun parseMcpServers(json: JSONObject): List<McpServerInfo> {
    val arr = json.optJSONArray("servers") ?: return emptyList()
    return (0 until arr.length()).map { i ->
        val s = arr.getJSONObject(i)
        val argsArr = s.optJSONArray("args") ?: JSONArray()
        McpServerInfo(
            name = s.optString("name"),
            command = s.optString("command"),
            args = (0 until argsArr.length()).map { j -> argsArr.getString(j) },
            env = null,
            status = s.optString("status", null),
        )
    }
}

fun parseSkills(json: JSONObject): List<SkillInfo> {
    val arr = json.optJSONArray("skills") ?: return emptyList()
    return (0 until arr.length()).map { i ->
        val s = arr.getJSONObject(i)
        SkillInfo(
            id = s.optString("id"),
            name = s.optString("name"),
            description = s.optString("description"),
            source = s.optString("source", "user"),
            sourceLabel = s.optString("sourceLabel"),
        )
    }
}

fun parseWorkspaceInfo(json: JSONObject): WorkspaceInfo {
    val root = json.optJSONObject("info") ?: json
    val foldersArr = root.optJSONArray("folders") ?: JSONArray()
    val editorsArr = root.optJSONArray("openEditors") ?: root.optJSONArray("editors") ?: JSONArray()
    val activeEditor = root.optJSONObject("activeEditor")

    return WorkspaceInfo(
        name = root.optString("name"),
        folders = (0 until foldersArr.length()).map { i ->
            val f = foldersArr.getJSONObject(i)
            WorkspaceFolder(f.optString("name"), f.optString("uri"))
        },
        activeEditor = activeEditor?.let {
            EditorInfo(
                fileName = it.optString("fileName"),
                filePath = it.optString("filePath"),
                languageId = it.optString("languageId"),
                isDirty = it.optBoolean("isDirty"),
                isUntitled = it.optBoolean("isUntitled"),
                scheme = it.optString("scheme"),
            )
        },
        openEditors = (0 until editorsArr.length()).map { i ->
            val e = editorsArr.getJSONObject(i)
            EditorInfo(
                fileName = e.optString("fileName"),
                filePath = e.optString("filePath"),
                languageId = e.optString("languageId"),
                isDirty = e.optBoolean("isDirty"),
                isUntitled = e.optBoolean("isUntitled"),
                scheme = e.optString("scheme"),
            )
        },
    )
}

fun parseStatus(json: JSONObject): StatusInfo {
    val s = json.optJSONObject("status") ?: json
    val foldersArr = s.optJSONArray("workspaceFolders")
    val folders = if (foldersArr != null) {
        (0 until foldersArr.length()).map { foldersArr.getString(it) }
    } else emptyList()

    return StatusInfo(
        serverRunning = s.optBoolean("serverRunning"),
        port = s.optInt("port"),
        host = s.optString("host"),
        connectedClients = s.optInt("connectedClients"),
        copilotAvailable = s.optBoolean("copilotAvailable"),
        modelCount = s.optInt("modelCount"),
        workspaceName = s.optString("workspaceName"),
        uptime = s.optLong("uptime"),
        instanceId = s.optString("instanceId", ""),
        windowTitle = s.optString("windowTitle", ""),
        workspaceFolders = folders,
        pid = s.optInt("pid"),
        isPrimary = s.optBoolean("isPrimary", true),
    )
}

fun parseInstances(json: JSONObject): List<InstanceInfo> {
    val arr = json.optJSONArray("instances") ?: return emptyList()
    return (0 until arr.length()).map { i ->
        val inst = arr.getJSONObject(i)
        InstanceInfo(
            instanceId = inst.optString("instanceId"),
            workspaceName = inst.optString("workspaceName"),
            windowTitle = inst.optString("windowTitle"),
            host = inst.optString("host"),
            port = inst.optInt("port"),
            isRunning = inst.optBoolean("isRunning"),
            isPrimary = inst.optBoolean("isPrimary"),
            isLocal = inst.optBoolean("isLocal"),
            pid = inst.optInt("pid"),
            connected = inst.optBoolean("connected"),
        )
    }
}

fun parseChatHistory(json: JSONObject): List<ChatHistoryItem> {
    val arr = json.optJSONArray("history") ?: return emptyList()
    return (0 until arr.length()).map { i ->
        val h = arr.getJSONObject(i)
        ChatHistoryItem(
            id = h.optString("id"),
            role = h.optString("role"),
            content = h.optString("content"),
            timestamp = h.optLong("timestamp"),
            modelId = h.optString("modelId", null),
        )
    }
}

fun parseNativeChatSessions(json: JSONObject): List<NativeChatSession> {
    val arr = json.optJSONArray("sessions") ?: return emptyList()
    return (0 until arr.length()).map { i -> parseNativeChatSession(arr.getJSONObject(i)) }
}

fun parseNativeChatSession(json: JSONObject): NativeChatSession {
    val messagesArr = json.optJSONArray("messages") ?: JSONArray()
    return NativeChatSession(
        id = json.optString("id"),
        title = json.optString("title"),
        source = json.optString("source"),
        filePath = json.optString("filePath", null),
        workspaceName = json.optString("workspaceName", null),
        workspaceFolder = json.optString("workspaceFolder", null),
        createdAt = json.optLong("createdAt"),
        updatedAt = json.optLong("updatedAt"),
        requestCount = json.optInt("requestCount"),
        messages = (0 until messagesArr.length()).map { i ->
            val m = messagesArr.getJSONObject(i)
            ChatMessage(
                id = m.optString("id").ifBlank { "${json.optString("id")}:$i" },
                role = m.optString("role"),
                content = m.optString("content"),
                timestamp = m.optLong("timestamp"),
                modelId = m.optString("modelId", null),
                kind = m.optString("kind", "message"),
                toolName = m.optString("toolName"),
                toolStatus = m.optString("toolStatus"),
                toolInput = m.optString("toolInput"),
                toolOutput = m.optString("toolOutput"),
            )
        },
    )
}
