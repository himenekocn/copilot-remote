/**
 * Shared type definitions for the Copilot Remote Control WebSocket protocol.
 * Both the VS Code extension (server) and Android app (client) use these.
 */

// ─── Chat Types ──────────────────────────────────────────────

export interface ChatMessage {
  role: 'user' | 'assistant' | 'system';
  content: string;
  timestamp?: number;
  attachments?: ChatAttachment[];
}

export interface ChatAttachment {
  name?: string;
  mimeType: string;
  dataBase64: string;
}

export interface ChatHistoryItem {
  id: string;
  role: 'user' | 'assistant';
  content: string;
  timestamp: number;
  modelId?: string;
  kind?: 'message' | 'thinking' | 'tool';
  toolName?: string;
  toolStatus?: 'running' | 'completed' | 'error';
  toolInput?: string;
  toolOutput?: string;
  metadata?: Record<string, unknown>;
}

export interface NativeChatSessionInfo {
  id: string;
  title: string;
  source: 'workspace' | 'emptyWindow' | 'transferred' | 'copilotStore' | 'officialTranscript' | 'vscodeChatSession';
  filePath?: string;
  workspaceName?: string;
  workspaceFolder?: string;
  createdAt?: number;
  updatedAt?: number;
  requestCount: number;
  messages?: ChatHistoryItem[];
}

export interface ChatTodoItem {
  id: number;
  title: string;
  status: 'not-started' | 'in-progress' | 'completed';
}

export interface ChatUsageInfo {
  promptTokens: number;
  completionTokens: number;
  outputBuffer?: number;
  maxInputTokens: number;
}

// ─── Model Types ─────────────────────────────────────────────

export interface ModelInfo {
  id: string;
  name: string;
  vendor: string;
  family: string;
  version: string;
  maxInputTokens: number;
  isDefault?: boolean;
  reasoningEfforts?: string[];
}

export interface ChatOptions {
  modelId?: string;
  participant?: string;
  command?: string;
  workspaceContext?: boolean;
  temperature?: number;
  sessionResource?: string;
  reasoningEffort?: string;
  permissionLevel?: 'default' | 'autoApprove' | 'autopilot';
  enabledTools?: string[];
}

// ─── Command Types ───────────────────────────────────────────

export interface CommandInfo {
  id: string;
  title?: string;
  category?: string;
  isCopilot?: boolean;
}

// ─── Participant Types ──────────────────────────────────────

export interface ParticipantInfo {
  id: string;
  name: string;
  fullName: string;
  description: string;
  isSticky: boolean;
  commands: ParticipantCommand[];
  isBuiltIn: boolean;
}

export interface ParticipantCommand {
  name: string;
  description: string;
}

// ─── Extension/Plugin Types ─────────────────────────────────

export interface ExtensionInfo {
  id: string;
  name: string;
  displayName: string;
  version: string;
  description: string;
  isActive: boolean;
  isCopilot: boolean;
  isAI: boolean;
}

// ─── MCP Types ──────────────────────────────────────────────

export interface McpServerInfo {
  name: string;
  command: string;
  args: string[];
  env?: Record<string, string>;
  status?: 'connected' | 'disconnected' | 'error';
  tools?: McpToolInfo[];
}

export interface McpToolInfo {
  name: string;
  description: string;
  serverName: string;
  inputSchema?: Record<string, unknown>;
}

// ─── Skill/Tool Types ───────────────────────────────────────

export interface SkillInfo {
  id: string;
  name: string;
  description: string;
  source: 'user' | 'extension';
  sourceLabel?: string;
}

export interface ToolInfo {
  id: string;
  name: string;
  description: string;
  groupId: string;
  groupName: string;
  source: 'builtin' | 'mcp' | 'extension';
}

// ─── Workspace Types ────────────────────────────────────────

export interface WorkspaceInfo {
  name: string;
  folders: WorkspaceFolderInfo[];
  activeEditor?: EditorInfo;
  openEditors: EditorInfo[];
}

export interface WorkspaceFolderInfo {
  name: string;
  uri: string;
}

export interface EditorInfo {
  fileName: string;
  filePath: string;
  languageId: string;
  isDirty: boolean;
  isUntitled: boolean;
  scheme: string;
}

export interface StatusInfo {
  serverRunning: boolean;
  port: number;
  host: string;
  connectedClients: number;
  copilotAvailable: boolean;
  modelCount: number;
  workspaceName: string;
  uptime: number;
  // ─── Multi-window instance fields ──────────────────────
  instanceId: string;          // unique per VS Code window
  windowTitle: string;         // workspace folder name or "Untitled"
  workspaceFolders: string[];  // folder paths
  pid: number;                 // process ID for identification
  isPrimary: boolean;          // first server instance on this machine
}

// ─── Discovered Instance Types ──────────────────────────────

export interface InstanceInfo {
  instanceId: string;
  workspaceName: string;
  windowTitle: string;
  host: string;
  port: number;
  isRunning: boolean;
  isPrimary: boolean;
  isLocal: boolean;
  pid: number;
  connected: boolean;          // whether this aggregator is currently connected to it
}

export interface TerminalInfo {
  id: string;
  name: string;
  processId?: number;
  isActive: boolean;
}

export interface PullRequestInfo {
  number: number;
  title: string;
  state: string;
  author: string;
  headRefName: string;
  baseRefName: string;
  url: string;
}

// ─── WebSocket Protocol Messages ────────────────────────────
// Client → Server

export type ClientMessage =
  | { type: 'auth'; key: string; clientId?: string }
  | { type: 'listModels' }
  | { type: 'registerPushToken'; token: string }
  | { type: 'unregisterPushToken'; token: string }
  | { type: 'chat'; requestId: string; modelId?: string; messages: ChatMessage[]; options?: ChatOptions }
  | { type: 'cancelChat'; requestId: string }
  | { type: 'listCommands' }
  | { type: 'executeCommand'; commandId: string; args?: unknown[] }
  | { type: 'getChatHistory' }
  | { type: 'clearHistory' }
  | { type: 'listNativeChatSessions'; offset?: number; limit?: number; workspace?: string }
  | { type: 'getNativeChatSession'; sessionId: string; before?: number; limit?: number }
  | { type: 'getActiveChatSession' }
  | { type: 'getActiveChatTodos' }
  | { type: 'listParticipants' }
  | { type: 'listExtensions' }
  | { type: 'toggleExtension'; extensionId: string; enable: boolean }
  | { type: 'listMcpServers' }
  | { type: 'addMcpServer'; config: McpServerInfo }
  | { type: 'removeMcpServer'; name: string }
  | { type: 'listSkills' }
  | { type: 'listTools' }
  | { type: 'invokeSkill'; skillId: string; args?: Record<string, unknown> }
  | { type: 'getWorkspaceInfo' }
  | { type: 'getOpenEditors' }
  | { type: 'getFileContent'; filePath: string }
  | { type: 'saveFileContent'; filePath: string; content: string }
  | { type: 'getStatus' }
  | { type: 'sendToCopilotChat'; prompt: string; participant?: string; command?: string }
  | { type: 'getActiveEditorContent' }
  | { type: 'insertText'; text: string; position?: { line: number; character: number } }
  | { type: 'listTerminals' }
  | { type: 'createTerminal'; name?: string; cwd?: string }
  | { type: 'closeTerminal'; terminalId: string }
  | { type: 'executeTerminal'; terminalId: string; command: string }
  | { type: 'searchFiles'; query: string; include?: string }
  | { type: 'searchText'; query: string; include?: string }
  | { type: 'searchSymbols'; query: string }
  | { type: 'listDirectory'; path?: string }
  | { type: 'openFile'; filePath: string; line?: number; character?: number }
  | { type: 'closeFile'; filePath: string }
  | { type: 'findDefinitions'; filePath: string; line: number; character: number }
  | { type: 'findReferences'; filePath: string; line: number; character: number }
  | { type: 'getGitStatus' }
  | { type: 'getGitDiff'; filePath?: string }
  | { type: 'getGitHistory'; limit?: number }
  | { type: 'checkoutGitBranch'; branch: string }
  | { type: 'createGitBranch'; branch: string; checkout?: boolean }
  | { type: 'commitGitChanges'; message: string; all?: boolean }
  | { type: 'listPullRequests'; state?: 'open' | 'closed' | 'merged' | 'all' }
  | { type: 'openPullRequest'; number: number }
  | { type: 'createPullRequest'; title: string; body?: string; base?: string; draft?: boolean }
  | { type: 'setEditorSelection'; filePath: string; startLine: number; startCharacter?: number; endLine?: number; endCharacter?: number }
  | { type: 'replaceInFiles'; query: string; replacement: string; include?: string }
  | { type: 'captureWindow' }
  | { type: 'recordWindow'; seconds?: number; fps?: number }
  | { type: 'listInstances' }
  | { type: 'proxy'; targetInstanceId: string; message: Omit<ClientMessage, 'type'> & { type: string } }
  | { type: 'ping' };

// Server → Client

export type ServerMessage =
  | { type: 'authResult'; success: boolean; message?: string; serverVersion?: string; instanceId?: string; workspaceName?: string }
  | { type: 'models'; models: ModelInfo[] }
  | { type: 'pushTokenRegistered'; success: boolean; configured?: boolean; error?: string }
  | { type: 'pushTokenUnregistered'; success: boolean; error?: string }
  | { type: 'activeChatSession'; active: boolean; sessionResource?: string; snapshot?: Record<string, unknown> }
  | { type: 'activeSessionChanged'; sessionResource?: string }
  | { type: 'chatStart'; requestId: string; modelId: string; modelName?: string; sessionResource?: string; reasoningEffort?: string; maxInputTokens?: number }
  | { type: 'chatDelta'; requestId: string; delta: string }
  | { type: 'chatThinkingDelta'; requestId: string; delta: string; thinkingId?: string; done?: boolean }
  | { type: 'chatUsage'; requestId: string; usage: ChatUsageInfo }
  | { type: 'todoUpdated'; requestId?: string; sessionResource?: string; items: ChatTodoItem[] }
  | { type: 'toolUpdated'; requestId: string; sessionResource?: string; toolCallId: string; toolName: string; status: 'running' | 'completed' | 'error'; message?: string; input?: string; output?: string }
  | { type: 'chatDone'; requestId: string; fullResponse: string; metadata?: Record<string, unknown> }
  | { type: 'chatError'; requestId: string; error: string; code?: string }
  | { type: 'chatCancelled'; requestId: string }
  | { type: 'commands'; commands: CommandInfo[] }
  | { type: 'commandResult'; commandId: string; result: unknown; error?: string }
  | { type: 'chatHistory'; history: ChatHistoryItem[] }
  | { type: 'historyCleared' }
  | { type: 'nativeChatSessions'; workspace?: string; sessions: NativeChatSessionInfo[]; total?: number; offset?: number; limit?: number; hasMore?: boolean; nextOffset?: number; warning?: string }
  | { type: 'nativeChatSession'; session: NativeChatSessionInfo; messagePage?: { start: number; end: number; total: number; hasMoreBefore: boolean }; warning?: string }
  | { type: 'participants'; participants: ParticipantInfo[] }
  | { type: 'extensions'; extensions: ExtensionInfo[] }
  | { type: 'extensionToggled'; extensionId: string; success: boolean; error?: string }
  | { type: 'mcpServers'; servers: McpServerInfo[] }
  | { type: 'mcpServerAdded'; success: boolean; error?: string }
  | { type: 'mcpServerRemoved'; success: boolean; error?: string }
  | { type: 'skills'; skills: SkillInfo[] }
  | { type: 'tools'; tools: ToolInfo[] }
  | { type: 'skillInvoked'; skillId: string; success: boolean; result?: unknown; error?: string }
  | { type: 'workspaceInfo'; info: WorkspaceInfo }
  | { type: 'openEditors'; editors: EditorInfo[] }
  | { type: 'fileContent'; filePath: string; content: string; error?: string }
  | { type: 'fileSaved'; filePath: string; success: boolean; error?: string }
  | { type: 'status'; status: StatusInfo }
  | { type: 'copilotChatSent'; success: boolean; error?: string }
  | { type: 'activeEditorContent'; content: string; fileName: string; languageId: string; error?: string }
  | { type: 'textInserted'; success: boolean; error?: string }
  | { type: 'terminals'; terminals: TerminalInfo[] }
  | { type: 'terminalCreated'; terminal: TerminalInfo }
  | { type: 'terminalClosed'; terminalId: string; success: boolean; error?: string }
  | { type: 'terminalOutput'; terminalId: string; command: string; output: string; exitCode?: number; error?: string }
  | { type: 'searchResults'; kind: 'files' | 'text' | 'symbols' | 'definitions' | 'references'; results: unknown[]; error?: string }
  | { type: 'directoryEntries'; path: string; entries: Array<{ name: string; path: string; type: 'file' | 'directory' }> ; error?: string }
  | { type: 'fileOpened'; filePath: string; success: boolean; error?: string }
  | { type: 'fileClosed'; filePath: string; success: boolean; error?: string }
  | { type: 'gitStatus'; branch: string; branches: string[]; changes: Array<{ path: string; status: string }>; error?: string }
  | { type: 'gitDiff'; filePath?: string; diff: string; error?: string }
  | { type: 'gitHistory'; commits: Array<{ hash: string; message: string; author: string; date: string }>; error?: string }
  | { type: 'gitOperation'; operation: string; success: boolean; error?: string }
  | { type: 'pullRequests'; pullRequests: PullRequestInfo[]; error?: string }
  | { type: 'pullRequestOperation'; operation: string; success: boolean; url?: string; error?: string }
  | { type: 'editorSelectionSet'; filePath: string; success: boolean; error?: string }
  | { type: 'replaceResult'; filesChanged: number; replacements: number; error?: string }
  | { type: 'eventNotification'; category: 'build' | 'test' | 'copilot'; title: string; message: string; success?: boolean }
  | { type: 'windowCapture'; frames: string[]; intervalMs: number; error?: string }
  | { type: 'instances'; instances: InstanceInfo[] }
  | { type: 'proxyResult'; targetInstanceId: string; message: ServerMessage }
  | { type: 'pong'; timestamp: number }
  | { type: 'error'; message: string; code?: string }
  | { type: 'log'; level: 'info' | 'warn' | 'error'; message: string };
