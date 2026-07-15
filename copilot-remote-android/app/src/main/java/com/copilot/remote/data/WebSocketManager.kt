package com.copilot.remote.data

import android.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.*
import okio.ByteString
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * WebSocket client that connects to one or more VS Code extension servers.
 *
 * Each saved server profile gets its own WebSocket connection keyed by [profileId].
 * Messages are emitted with [profileId] context via [routedMessages] so the ViewModel
 * can route them to the correct [Connection].
 */
class WebSocketManager {

    companion object {
        private const val TAG = "WebSocketManager"
        private const val RECONNECT_DELAY_MS = 2000L
        private const val DEFAULT_PROFILE_ID = "__default__"
    }

    /** Emitted whenever any connection changes state. */
    data class ConnectionStateEvent(val profileId: String, val state: ConnectionState)

    /** Emitted whenever any connection receives a server message. */
    data class RoutedMessage(val profileId: String, val instanceId: String?, val json: JSONObject)

    private val client: OkHttpClient = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.SECONDS) // No timeout for WebSocket
        // Session discovery can briefly keep the extension busy on large histories.
        // Keep heartbeats frequent enough to detect dead peers without treating a
        // single expensive scan as a broken connection.
        // Keep LAN/NAT mappings alive. Session indexing now yields often enough
        // that a 25-second control ping can be answered without false timeouts.
        .pingInterval(25, TimeUnit.SECONDS)
        .build()

    private val connections = ConcurrentHashMap<String, ConnectionHolder>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _connectionStates = MutableSharedFlow<ConnectionStateEvent>(extraBufferCapacity = 64)
    val connectionStates: SharedFlow<ConnectionStateEvent> = _connectionStates.asSharedFlow()

    private val _routedMessages = MutableSharedFlow<RoutedMessage>(extraBufferCapacity = 256)
    val routedMessages: SharedFlow<RoutedMessage> = _routedMessages.asSharedFlow()

    // ─── Backward-compatible single-connection API ─────────────────

    private val _legacyConnectionState = MutableSharedFlow<ConnectionState>(replay = 1, extraBufferCapacity = 1)
    val connectionState: SharedFlow<ConnectionState> = _legacyConnectionState.asSharedFlow()

    private val _legacyMessages = MutableSharedFlow<JSONObject>(extraBufferCapacity = 64)
    val messages: SharedFlow<JSONObject> = _legacyMessages.asSharedFlow()

    fun connect(url: String, key: String) = connect(DEFAULT_PROFILE_ID, url, key)

    fun disconnect() = disconnect(DEFAULT_PROFILE_ID)

    fun send(json: JSONObject): Boolean = sendTo(DEFAULT_PROFILE_ID, json)

    fun sendRaw(json: JSONObject): Boolean = sendRawTo(DEFAULT_PROFILE_ID, json)

    val isConnected: Boolean
        get() = isConnected(DEFAULT_PROFILE_ID)

    // ─── Multi-connection API ──────────────────────────────────────

    fun connect(profileId: String, url: String, key: String) {
        disconnect(profileId)
        val holder = ConnectionHolder(profileId, url, key)
        connections[profileId] = holder
        holder.connect()
    }

    fun disconnect(profileId: String) {
        connections.remove(profileId)?.disconnect()
    }

    fun disconnectAll() {
        connections.values.forEach { it.disconnect() }
        connections.clear()
    }

    fun isConnected(profileId: String): Boolean {
        return connections[profileId]?.isConnected == true
    }

    fun getConnectionState(profileId: String): ConnectionState {
        return connections[profileId]?.state ?: ConnectionState.DISCONNECTED
    }

    fun sendTo(profileId: String, json: JSONObject): Boolean {
        val holder = connections[profileId] ?: return false
        return holder.send(json)
    }

    fun sendRawTo(profileId: String, json: JSONObject): Boolean {
        val holder = connections[profileId] ?: return false
        return holder.sendRaw(json)
    }

    // ─── Convenience Send Methods ──────────────────────────────────

    private fun sendOrDefault(profileId: String?, json: JSONObject): Boolean {
        return if (profileId.isNullOrBlank()) send(json) else sendTo(profileId, json)
    }

    fun listModels(profileId: String? = null) =
        sendOrDefault(profileId, JSONObject().put("type", "listModels"))

    fun sendChat(
        profileId: String? = null,
        requestId: String,
        modelId: String?,
        messages: List<ChatMessage>,
        sessionResource: String? = null,
        reasoningEffort: String? = null,
    ) {
        val arr = org.json.JSONArray()
        messages.forEach { message ->
            arr.put(JSONObject().apply {
                put("role", message.role)
                put("content", message.content)
                if (message.attachments.isNotEmpty()) {
                    put("attachments", org.json.JSONArray(message.attachments.map { attachment ->
                        JSONObject().apply {
                            put("name", attachment.name)
                            put("mimeType", attachment.mimeType)
                            put("dataBase64", attachment.dataBase64)
                        }
                    }))
                }
            })
        }
        sendOrDefault(profileId, JSONObject().apply {
            put("type", "chat")
            put("requestId", requestId)
            if (modelId != null) put("modelId", modelId)
            put("messages", arr)
            put("options", JSONObject().apply {
                if (!sessionResource.isNullOrBlank()) put("sessionResource", sessionResource)
                if (!reasoningEffort.isNullOrBlank()) put("reasoningEffort", reasoningEffort)
            })
        })
    }

    fun cancelChat(profileId: String? = null, requestId: String) {
        sendOrDefault(profileId, JSONObject().apply {
            put("type", "cancelChat")
            put("requestId", requestId)
        })
    }

    fun listCommands(profileId: String? = null) =
        sendOrDefault(profileId, JSONObject().put("type", "listCommands"))

    fun executeCommand(profileId: String? = null, commandId: String, args: List<Any>? = null) {
        sendOrDefault(profileId, JSONObject().apply {
            put("type", "executeCommand")
            put("commandId", commandId)
            if (args != null) put("args", org.json.JSONArray(args))
        })
    }

    fun getChatHistory(profileId: String? = null) =
        sendOrDefault(profileId, JSONObject().put("type", "getChatHistory"))

    fun clearHistory(profileId: String? = null) =
        sendOrDefault(profileId, JSONObject().put("type", "clearHistory"))

    fun listNativeChatSessions(profileId: String? = null, offset: Int = 0, limit: Int = 50, workspace: String? = null) =
        sendOrDefault(profileId, JSONObject().apply {
            put("type", "listNativeChatSessions")
            put("offset", offset)
            put("limit", limit)
            if (!workspace.isNullOrBlank()) put("workspace", workspace)
        })

    fun getNativeChatSession(profileId: String? = null, sessionId: String, before: Int? = null, limit: Int = 100) {
        sendOrDefault(profileId, JSONObject().apply {
            put("type", "getNativeChatSession")
            put("sessionId", sessionId)
            if (before != null) put("before", before)
            put("limit", limit)
        })
    }

    fun getActiveChatSession(profileId: String? = null) =
        sendOrDefault(profileId, JSONObject().put("type", "getActiveChatSession"))

    fun getActiveChatTodos(profileId: String? = null) =
        sendOrDefault(profileId, JSONObject().put("type", "getActiveChatTodos"))

    fun listParticipants(profileId: String? = null) =
        sendOrDefault(profileId, JSONObject().put("type", "listParticipants"))

    fun listExtensions(profileId: String? = null) =
        sendOrDefault(profileId, JSONObject().put("type", "listExtensions"))

    fun toggleExtension(profileId: String? = null, extensionId: String, enable: Boolean) {
        sendOrDefault(profileId, JSONObject().apply {
            put("type", "toggleExtension")
            put("extensionId", extensionId)
            put("enable", enable)
        })
    }

    fun listMcpServers(profileId: String? = null) =
        sendOrDefault(profileId, JSONObject().put("type", "listMcpServers"))

    fun addMcpServer(profileId: String? = null, name: String, command: String, args: List<String>) {
        sendOrDefault(profileId, JSONObject().apply {
            put("type", "addMcpServer")
            put("config", JSONObject().apply {
                put("name", name)
                put("command", command)
                put("args", org.json.JSONArray(args))
            })
        })
    }

    fun removeMcpServer(profileId: String? = null, name: String) {
        sendOrDefault(profileId, JSONObject().apply {
            put("type", "removeMcpServer")
            put("name", name)
        })
    }

    fun listSkills(profileId: String? = null) =
        sendOrDefault(profileId, JSONObject().put("type", "listSkills"))

    fun invokeSkill(profileId: String? = null, skillId: String) {
        sendOrDefault(profileId, JSONObject().apply {
            put("type", "invokeSkill")
            put("skillId", skillId)
        })
    }

    fun getWorkspaceInfo(profileId: String? = null) =
        sendOrDefault(profileId, JSONObject().put("type", "getWorkspaceInfo"))

    fun getOpenEditors(profileId: String? = null) =
        sendOrDefault(profileId, JSONObject().put("type", "getOpenEditors"))

    fun getFileContent(profileId: String? = null, filePath: String) {
        sendOrDefault(profileId, JSONObject().apply {
            put("type", "getFileContent")
            put("filePath", filePath)
        })
    }

    fun getStatus(profileId: String? = null) =
        sendOrDefault(profileId, JSONObject().put("type", "getStatus"))

    fun sendToCopilotChat(
        profileId: String? = null,
        prompt: String,
        participant: String? = null,
        command: String? = null,
    ) {
        sendOrDefault(profileId, JSONObject().apply {
            put("type", "sendToCopilotChat")
            put("prompt", prompt)
            if (participant != null) put("participant", participant)
            if (command != null) put("command", command)
        })
    }

    fun getActiveEditorContent(profileId: String? = null) =
        sendOrDefault(profileId, JSONObject().put("type", "getActiveEditorContent"))

    fun insertText(profileId: String? = null, text: String) {
        sendOrDefault(profileId, JSONObject().apply {
            put("type", "insertText")
            put("text", text)
        })
    }

    fun ping(profileId: String? = null) =
        sendOrDefault(profileId, JSONObject().put("type", "ping"))

    // ─── Multi-instance / aggregator helpers ───────────────────────

    fun listInstances(profileId: String) =
        sendTo(profileId, JSONObject().put("type", "listInstances"))

    fun sendProxy(profileId: String, targetInstanceId: String, message: JSONObject): Boolean {
        val json = JSONObject().apply {
            put("type", "proxy")
            put("targetInstanceId", targetInstanceId)
            put("message", message)
        }
        return sendTo(profileId, json)
    }

    // ─── Connection Holder ─────────────────────────────────────────

    private inner class ConnectionHolder(
        val profileId: String,
        private val url: String,
        private val key: String,
    ) {
        @Volatile
        var state: ConnectionState = ConnectionState.DISCONNECTED
            private set

        @Volatile
        var isAuthenticated: Boolean = false
            private set

        val isConnected: Boolean
            get() = webSocket != null && isAuthenticated

        private var webSocket: WebSocket? = null
        private var shouldReconnect = true
        private var reconnectJob: Job? = null
        private var reconnectDelayMs = RECONNECT_DELAY_MS

        private val _stateFlow = MutableSharedFlow<ConnectionState>(replay = 1, extraBufferCapacity = 1)
        val stateFlow: SharedFlow<ConnectionState> = _stateFlow.asSharedFlow()

        init {
            _stateFlow.tryEmit(ConnectionState.DISCONNECTED)
        }

        fun connect() {
            shouldReconnect = true
            isAuthenticated = false
            reconnectJob?.cancel()
            reconnectJob = null
            doConnect()
        }

        private fun doConnect() {
            if (url.isBlank()) {
                Log.e(TAG, "[$profileId] URL is blank, cannot connect")
                emitState(ConnectionState.ERROR)
                return
            }

            emitState(ConnectionState.CONNECTING)

            val request = Request.Builder().url(url).build()

            val previousSocket = webSocket
            webSocket = null
            previousSocket?.cancel()
            webSocket = client.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    if (this@ConnectionHolder.webSocket !== webSocket) return
                    Log.i(TAG, "[$profileId] WebSocket connected")
                    emitState(ConnectionState.CONNECTED)
                    sendAuth()
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    if (this@ConnectionHolder.webSocket !== webSocket) return
                    try {
                        val json = JSONObject(text)
                        handleMessage(json)
                    } catch (e: Exception) {
                        Log.e(TAG, "[$profileId] Failed to parse message: ${e.message}")
                    }
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    onMessage(webSocket, bytes.utf8())
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    if (this@ConnectionHolder.webSocket !== webSocket) return
                    Log.i(TAG, "[$profileId] WebSocket closing: $code $reason")
                    webSocket.close(1000, null)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    if (this@ConnectionHolder.webSocket !== webSocket) return
                    Log.i(TAG, "[$profileId] WebSocket closed: $code $reason")
                    this@ConnectionHolder.webSocket = null
                    isAuthenticated = false
                    emitState(ConnectionState.DISCONNECTED)
                    tryReconnect()
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    if (this@ConnectionHolder.webSocket !== webSocket) return
                    Log.e(TAG, "[$profileId] WebSocket failure: ${t.message}")
                    this@ConnectionHolder.webSocket = null
                    isAuthenticated = false
                    emitState(ConnectionState.ERROR)
                    tryReconnect()
                }
            })
        }

        private fun handleMessage(json: JSONObject) {
            val type = json.optString("type")

            if (type == "authResult") {
                val success = json.optBoolean("success")
                if (success) {
                    isAuthenticated = true
                    reconnectDelayMs = RECONNECT_DELAY_MS
                    emitState(ConnectionState.AUTHENTICATED)
                    Log.i(TAG, "[$profileId] Authenticated successfully")
                } else {
                    Log.e(TAG, "[$profileId] Authentication failed: ${json.optString("message")}")
                    emitState(ConnectionState.ERROR)
                    shouldReconnect = false
                }
                // authResult is also useful to the ViewModel for instance metadata.
                emitRouted(null, json)
                return
            }

            val instanceId = when (type) {
                "proxyResult" -> json.optString("targetInstanceId").takeIf { it.isNotEmpty() }
                "status" -> json.optJSONObject("status")?.optString("instanceId")?.takeIf { it.isNotEmpty() }
                    ?: json.optString("instanceId").takeIf { it.isNotEmpty() }
                else -> json.optString("instanceId").takeIf { it.isNotEmpty() }
            }
            emitRouted(instanceId, json)
        }

        private fun sendAuth() {
            val authMsg = JSONObject().apply {
                put("type", "auth")
                put("key", key)
                put("clientId", "android-${System.currentTimeMillis()}")
            }
            sendRaw(authMsg)
        }

        private fun tryReconnect() {
            if (!shouldReconnect || reconnectJob?.isActive == true) return
            val delayMs = reconnectDelayMs
            reconnectDelayMs = (reconnectDelayMs * 2).coerceAtMost(30_000L)
            Log.i(TAG, "[$profileId] Reconnecting in ${delayMs}ms...")
            reconnectJob = scope.launch {
                delay(delayMs)
                reconnectJob = null
                if (shouldReconnect) doConnect()
            }
        }

        fun disconnect() {
            shouldReconnect = false
            reconnectJob?.cancel()
            isAuthenticated = false
            webSocket?.close(1000, "Client disconnecting")
            webSocket = null
            emitState(ConnectionState.DISCONNECTED)
        }

        fun send(json: JSONObject): Boolean {
            if (webSocket == null || !isAuthenticated) {
                Log.w(TAG, "[$profileId] Cannot send: WebSocket not connected/authenticated")
                return false
            }
            return webSocket?.send(json.toString()) ?: false
        }

        fun sendRaw(json: JSONObject): Boolean {
            return webSocket?.send(json.toString()) ?: false
        }

        private fun emitState(newState: ConnectionState) {
            state = newState
            _stateFlow.tryEmit(newState)
            _connectionStates.tryEmit(ConnectionStateEvent(profileId, newState))
            if (profileId == DEFAULT_PROFILE_ID) {
                _legacyConnectionState.tryEmit(newState)
            }
        }

        private fun emitRouted(instanceId: String?, json: JSONObject) {
            val routed = RoutedMessage(profileId, instanceId, json)
            _routedMessages.tryEmit(routed)
            if (profileId == DEFAULT_PROFILE_ID) {
                _legacyMessages.tryEmit(json)
            }
        }
    }
}
