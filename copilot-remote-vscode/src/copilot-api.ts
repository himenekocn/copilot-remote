import * as vscode from 'vscode';
import * as fs from 'fs/promises';
import * as http from 'http';
import * as https from 'https';
import * as path from 'path';
import * as os from 'os';
import { execFile } from 'child_process';
import { promisify } from 'util';
import type {
  ModelInfo,
  CommandInfo,
  SlashCommandInfo,
  ParticipantInfo,
  ExtensionInfo,
  McpServerInfo,
  SkillInfo,
  ToolInfo,
  WorkspaceInfo,
  EditorInfo,
  ChatMessage,
  ChatHistoryItem,
  NativeChatSessionInfo,
} from './types';

/**
 * Wrapper around VS Code's Language Model API and command system.
 * Provides access to Copilot models, chat, commands, extensions, MCP, and workspace.
 */
export class CopilotApi {
  private chatHistory: ChatHistoryItem[] = [];
  private cachedModels: ModelInfo[] = [];
  private readonly terminalIds = new WeakMap<vscode.Terminal, string>();
  private nextTerminalId = 1;

  async listTools(): Promise<ToolInfo[]> {
    const contributionOwners = new Map<string, { id: string; name: string }>();
    for (const extension of vscode.extensions.all) {
      const contributed = extension.packageJSON?.contributes?.languageModelTools;
      if (!Array.isArray(contributed)) continue;
      for (const tool of contributed) {
        if (typeof tool?.name === 'string') {
          contributionOwners.set(tool.name, {
            id: extension.id,
            name: extension.packageJSON?.displayName || extension.packageJSON?.name || extension.id,
          });
        }
      }
    }
    const mcpServers = await this.listMcpServers();
    const normalize = (value: string) => value.toLowerCase().replace(/[^a-z0-9]+/g, '');
    const builtinGroup = (name: string) => {
      const value = name.toLowerCase();
      if (/read|file/.test(value)) return ['builtin.read', '读取'];
      if (/search|find|grep/.test(value)) return ['builtin.search', '搜索'];
      if (/todo|task/.test(value)) return ['builtin.todo', '任务'];
      if (/web|fetch|http/.test(value)) return ['builtin.web', '网页'];
      if (/terminal|shell|powershell|python/.test(value)) return ['builtin.terminal', '终端'];
      return ['builtin.vscode', 'VS Code'];
    };
    return vscode.lm.tools.map(tool => {
      const owner = contributionOwners.get(tool.name);
      const normalizedName = normalize(tool.name);
      const server = mcpServers.find(item => {
        const key = normalize(item.name);
        return key.length >= 3 && normalizedName.includes(key);
      });
      let source: ToolInfo['source'];
      let groupId: string;
      let groupName: string;
      if (server || /(^|[._-])mcp([._-]|$)/i.test(tool.name)) {
        source = 'mcp';
        groupId = `mcp.${server?.name || tool.name.split(/[._-]/)[1] || 'other'}`;
        groupName = server?.name || 'MCP 工具';
      } else if (owner && owner.id !== 'github.copilot-chat') {
        source = 'extension';
        groupId = `extension.${owner.id}`;
        groupName = owner.name;
      } else {
        source = 'builtin';
        [groupId, groupName] = builtinGroup(tool.name);
      }
      return { id: tool.name, name: tool.name, description: tool.description || '', groupId, groupName, source };
    }).sort((a, b) => a.groupName.localeCompare(b.groupName) || a.name.localeCompare(b.name));
  }

  private async terminalInfo(terminal: vscode.Terminal) {
    const processId = await terminal.processId;
    let id = this.terminalIds.get(terminal);
    if (!id) {
      id = `terminal-${this.nextTerminalId++}`;
      this.terminalIds.set(terminal, id);
    }
    return { id, name: terminal.name, processId, isActive: vscode.window.activeTerminal === terminal };
  }

  async listTerminals() {
    const visibleTerminals = vscode.window.terminals.filter(terminal =>
      (terminal.creationOptions as vscode.TerminalOptions).hideFromUser !== true &&
      (terminal === vscode.window.activeTerminal || terminal.state.isInteractedWith),
    );
    const terminals = await Promise.all(visibleTerminals.map((terminal) => this.terminalInfo(terminal)));
    // Proxy refreshes can overlap while a shell is starting. Stable IDs plus
    // this final de-duplication prevent the same terminal appearing twice.
    return [...new Map(terminals.map(terminal => [terminal.id, terminal])).values()]
      .sort((a, b) => Number(b.isActive) - Number(a.isActive));
  }

  async createTerminal(name = 'Copilot Remote', cwd?: string) {
    const terminal = vscode.window.createTerminal({ name, cwd });
    terminal.show(true);
    return this.terminalInfo(terminal);
  }

  async closeTerminal(terminalId: string) {
    const terminal = await this.findTerminal(terminalId);
    if (!terminal) return false;
    terminal.dispose();
    return true;
  }

  async captureTerminal(terminalId: string) {
    const terminal = await this.findTerminal(terminalId);
    if (!terminal) throw new Error(`Terminal not found: ${terminalId}`);
    const previousClipboard = await vscode.env.clipboard.readText();
    terminal.show(true);
    try {
      await vscode.commands.executeCommand('workbench.action.terminal.selectAll');
      await vscode.commands.executeCommand('workbench.action.terminal.copySelection');
      const content = await vscode.env.clipboard.readText();
      await vscode.commands.executeCommand('workbench.action.terminal.clearSelection');
      return { id: terminalId, name: terminal.name, content };
    } finally {
      await vscode.env.clipboard.writeText(previousClipboard);
    }
  }

  async executeTerminal(terminalId: string, command: string) {
    const terminal = await this.findTerminal(terminalId);
    if (!terminal) throw new Error(`Terminal not found: ${terminalId}`);
    const cleanCommand = command
      .replace(/\r\n?/g, '\n')
      .replace(/[\u0000-\u0008\u000B\u000C\u000E-\u001F\u007F]/g, '')
      .trim();
    if (!cleanCommand) throw new Error('Command is empty after removing control characters');
    terminal.show(true);
    const shellIntegration = terminal.shellIntegration || await new Promise<vscode.TerminalShellIntegration | undefined>(resolve => {
      const timeout = setTimeout(() => { listener.dispose(); resolve(undefined); }, 5000);
      const listener = vscode.window.onDidChangeTerminalShellIntegration(event => {
        if (event.terminal !== terminal) return;
        clearTimeout(timeout);
        listener.dispose();
        resolve(event.shellIntegration);
      });
    });
    if (!shellIntegration) {
      throw new Error('VS Code terminal shell integration is unavailable. Enable terminal.integrated.shellIntegration.enabled and reopen the terminal.');
    }

    // sendText uses the terminal's own WSL/SSH/container shell input path.
    // shellIntegration.executeCommand() injects protocol sequences directly;
    // some WSL prompts interpret parts of those sequences as '?' commands.
    let startListener: vscode.Disposable | undefined;
    let startTimer: NodeJS.Timeout | undefined;
    const started = new Promise<{ execution: vscode.TerminalShellExecution; end: Promise<number | undefined> }>((resolve, reject) => {
      startTimer = setTimeout(() => {
        startListener?.dispose();
        reject(new Error('The selected terminal did not start the command. Check VS Code shell integration for this WSL/remote shell.'));
      }, 5000);
      startListener = vscode.window.onDidStartTerminalShellExecution(event => {
        if (event.terminal !== terminal) return;
        if (startTimer) clearTimeout(startTimer);
        startListener?.dispose();
        const end = new Promise<number | undefined>(endResolve => {
          const endListener = vscode.window.onDidEndTerminalShellExecution(endEvent => {
            if (endEvent.terminal !== terminal || endEvent.execution !== event.execution) return;
            endListener.dispose();
            endResolve(endEvent.exitCode);
          });
        });
        resolve({ execution: event.execution, end });
      });
    });
    terminal.sendText(cleanCommand, true);
    const { execution, end } = await started;
    let output = '';
    for await (const chunk of execution.read()) {
      output += chunk;
      if (output.length > 8 * 1024 * 1024) output = output.slice(-8 * 1024 * 1024);
    }
    const exitCode = await end;
    return { output: output || '(command completed with no output)', exitCode };
  }

  private async findTerminal(terminalId: string) {
    for (const terminal of vscode.window.terminals) {
      if (this.terminalIds.get(terminal) === terminalId) return terminal;
    }
    return undefined;
  }

  // ─── Models ─────────────────────────────────────────────────

  async listModels(): Promise<ModelInfo[]> {
    if (this.isPatchedBridgeEnabled()) {
      const patched = await this.tryPatchedBridge<{ models?: ModelInfo[] }>('GET', '/models');
      if (!patched.success || !Array.isArray(patched.data?.models)) {
        throw new Error(patched.warning || 'Patched Copilot bridge model request failed');
      }
      this.cachedModels = patched.data.models;
      return this.cachedModels;
    }
    try {
      const models = await vscode.lm.selectChatModels({});
      this.cachedModels = models.map((m) => ({
        id: m.id,
        name: m.name,
        vendor: m.vendor,
        family: m.family,
        version: m.version,
        maxInputTokens: m.maxInputTokens,
      }));
      return this.cachedModels;
    } catch (err) {
      console.error('[CopilotApi] Failed to list models:', err);
      return [];
    }
  }

  async getModel(modelId?: string): Promise<vscode.LanguageModelChat | undefined> {
    if (modelId) {
      const models = await vscode.lm.selectChatModels({ id: modelId });
      return models[0];
    }
    // Default to first available Copilot model
    const models = await vscode.lm.selectChatModels({ vendor: 'copilot' });
    return models[0];
  }

  // ─── Chat ───────────────────────────────────────────────────

  async *sendChat(
    modelId: string | undefined,
    messages: ChatMessage[],
    token?: vscode.CancellationToken,
  ): AsyncGenerator<{ delta: string; done: boolean }> {
    const userMsg = messages[messages.length - 1];
    const model = await this.getModel(modelId);
    if (!model) {
      throw new Error(
        modelId
          ? `Model not found: ${modelId}. Ensure Copilot is installed and you have access.`
          : 'No language models available. Ensure GitHub Copilot is installed and signed in.',
      );
    }

    // Convert to VS Code LanguageModelChatMessage format
    const lmMessages: vscode.LanguageModelChatMessage[] = messages.map((msg) => {
      if (msg.role === 'assistant') {
        return vscode.LanguageModelChatMessage.Assistant(msg.content);
      }
      return vscode.LanguageModelChatMessage.User(msg.content);
    });

    // Add workspace context if an editor is open
    const activeEditor = vscode.window.activeTextEditor;
    if (activeEditor) {
      const doc = activeEditor.document;
      const contextMsg = vscode.LanguageModelChatMessage.User(
        `Current file context:\nFile: ${doc.fileName}\nLanguage: ${doc.languageId}\n\nSelected text (if any):\n${activeEditor.selection.isEmpty ? '' : doc.getText(activeEditor.selection)}`,
      );
      lmMessages.unshift(contextMsg);
    }

    let fullResponse = '';
    try {
      const response = await model.sendRequest(lmMessages, {}, token);
      for await (const chunk of response.text) {
        fullResponse += chunk;
        yield { delta: chunk, done: false };
      }
    } catch (err) {
      if (this.isTokenizerError(err)) {
        const prompt = userMsg?.content || messages.map((m) => m.content).join('\n');
        const chatResult = await this.sendToCopilotChat(prompt);
        if (!chatResult.success) {
          const message = err instanceof Error ? err.message : String(err);
          throw new Error(`Language Model API tokenizer failed and Copilot Chat fallback failed: ${chatResult.error || message}`);
        }
        const fallbackText = 'Opened this request in VS Code Copilot Chat because the Language Model API tokenizer failed in this VS Code/Copilot build. Continue the conversation in the VS Code Chat panel.';
        if (userMsg && userMsg.role === 'user') {
          this.addToHistory('user', userMsg.content, modelId);
        }
        this.addToHistory('assistant', fallbackText, modelId);
        yield { delta: fallbackText, done: false };
        yield { delta: '', done: true };
        return;
      }
      throw err;
    }

    // Save to history
    if (userMsg && userMsg.role === 'user') {
      this.addToHistory('user', userMsg.content, modelId);
    }
    this.addToHistory('assistant', fullResponse, modelId);

    yield { delta: '', done: true };
  }

  private isTokenizerError(err: unknown): boolean {
    const message = err instanceof Error ? err.message : String(err);
    return message.toLowerCase().includes('unknown tokenizer');
  }

  private addToHistory(role: 'user' | 'assistant', content: string, modelId?: string) {
    this.chatHistory.push({
      id: `${Date.now()}-${Math.random().toString(36).slice(2, 9)}`,
      role,
      content,
      timestamp: Date.now(),
      modelId,
    });
    // Keep last 200 messages
    if (this.chatHistory.length > 200) {
      this.chatHistory = this.chatHistory.slice(-200);
    }
  }

  getHistory(): ChatHistoryItem[] {
    return [...this.chatHistory];
  }

  clearHistory() {
    this.chatHistory = [];
  }

  // ─── Native VS Code Chat Sessions ──────────────────────────────

  async listNativeChatSessions(offset = 0, limit = 50, workspace?: string): Promise<{ sessions: NativeChatSessionInfo[]; total?: number; offset?: number; limit?: number; hasMore?: boolean; nextOffset?: number; warning?: string }> {
    const query = new URLSearchParams({ offset: String(offset), limit: String(limit) });
    if (workspace) query.set('workspace', workspace);
    const patched = await this.tryPatchedBridge<{ sessions?: NativeChatSessionInfo[]; total?: number; offset?: number; limit?: number; hasMore?: boolean; nextOffset?: number }>('GET', `/sessions?${query}`);
    if (patched.success && Array.isArray(patched.data?.sessions)) {
      return { sessions: patched.data.sessions, total: patched.data.total, offset: patched.data.offset, limit: patched.data.limit, hasMore: patched.data.hasMore, nextOffset: patched.data.nextOffset };
    }
    if (this.isPatchedBridgeEnabled()) {
      throw new Error(patched.warning || 'Patched Copilot bridge session request failed');
    }

    const roots = await this.getNativeChatSessionRoots();
    const sessions: NativeChatSessionInfo[] = [];
    const warnings: string[] = [];

    for (const root of roots) {
      try {
        const entries = await fs.readdir(root.dir, { withFileTypes: true });
        for (const entry of entries) {
          if (!entry.isFile() || (!entry.name.endsWith('.json') && !entry.name.endsWith('.jsonl'))) {
            continue;
          }
          const filePath = path.join(root.dir, entry.name);
          try {
            const session = await this.readNativeChatSessionFile(filePath, root.source, false, root.workspaceName, root.workspaceFolder);
            if (session) {
              sessions.push(session);
            }
          } catch (err) {
            warnings.push(`${entry.name}: ${err instanceof Error ? err.message : String(err)}`);
          }
        }
      } catch (err) {
        const code = (err as NodeJS.ErrnoException).code;
        if (code !== 'ENOENT') {
          warnings.push(`${root.dir}: ${err instanceof Error ? err.message : String(err)}`);
        }
      }
    }

    const normalizedWorkspace = workspace?.trim().toLowerCase();
    const visibleSessions = sessions.filter(session => {
      if (session.requestCount <= 0) return false;
      if (!normalizedWorkspace) return true;
      return session.workspaceName?.toLowerCase() === normalizedWorkspace
        || session.workspaceFolder?.toLowerCase().includes(normalizedWorkspace);
    });
    visibleSessions.sort((a, b) => {
      const workspaceRank = Number(b.source === 'workspace') - Number(a.source === 'workspace');
      if (workspaceRank) return workspaceRank;
      return (b.updatedAt || b.createdAt || 0) - (a.updatedAt || a.createdAt || 0);
    });
    const page = visibleSessions.slice(offset, offset + limit);
    return {
      sessions: page,
      total: visibleSessions.length,
      offset,
      limit,
      hasMore: offset + limit < visibleSessions.length,
      nextOffset: Math.min(visibleSessions.length, offset + page.length),
      warning: [patched.warning, warnings.length ? warnings.slice(0, 5).join('; ') : undefined].filter(Boolean).join('; ') || undefined,
    };
  }

  async getNativeChatSession(sessionId: string, before?: number, limit = 100): Promise<{ session?: NativeChatSessionInfo; messagePage?: { start: number; end: number; total: number; hasMoreBefore: boolean }; warning?: string }> {
    const query = new URLSearchParams({ limit: String(limit) });
    if (before !== undefined) query.set('before', String(before));
    const patched = await this.tryPatchedBridge<{ session?: NativeChatSessionInfo; messagePage?: { start: number; end: number; total: number; hasMoreBefore: boolean } }>('GET', `/sessions/${encodeURIComponent(sessionId)}?${query}`);
    if (patched.success && patched.data?.session) {
      return { session: patched.data.session, messagePage: patched.data.messagePage };
    }
    if (this.isPatchedBridgeEnabled()) {
      throw new Error(patched.warning || `Patched Copilot bridge session request failed: ${sessionId}`);
    }

    const { sessions, warning } = await this.listNativeChatSessions();
    const match = sessions.find((s) => s.id === sessionId);
    if (!match?.filePath) {
      return { warning: warning || `Native chat session not found: ${sessionId}` };
    }
    const full = await this.readNativeChatSessionFile(match.filePath, match.source, true, match.workspaceName, match.workspaceFolder);
    return { session: full || match, warning: [patched.warning, warning].filter(Boolean).join('; ') || undefined };
  }

  private async tryPatchedBridge<T>(method: 'GET' | 'POST', route: string, body?: unknown): Promise<{ success: boolean; data?: T; warning?: string }> {
    const config = vscode.workspace.getConfiguration('copilotRemote.patchedCopilotBridge');
    if (!config.get<boolean>('enabled', false)) {
      return { success: false };
    }
    const key = String(config.get<string>('key', '') || '').trim();
    if (!key) {
      return { success: false, warning: 'Patched Copilot bridge is enabled but copilotRemote.patchedCopilotBridge.key is empty' };
    }
    const host = String(config.get<string>('host', '127.0.0.1') || '127.0.0.1');
    const port = Number(config.get<number>('port', 9877) || 9877);
    const payload = body === undefined ? undefined : JSON.stringify(body);

    return new Promise((resolve) => {
      const req = http.request({
        host,
        port,
        path: route,
        method,
        timeout: 30000,
        headers: {
          'x-copilot-remote-key': key,
          ...(payload ? { 'content-type': 'application/json; charset=utf-8', 'content-length': Buffer.byteLength(payload) } : {}),
        },
      }, (res) => {
        const chunks: Buffer[] = [];
        res.on('data', chunk => chunks.push(Buffer.isBuffer(chunk) ? chunk : Buffer.from(chunk)));
        res.on('end', () => {
          const raw = Buffer.concat(chunks).toString('utf8');
          if ((res.statusCode || 0) < 200 || (res.statusCode || 0) >= 300) {
            resolve({ success: false, warning: `Patched Copilot bridge ${route} returned ${res.statusCode}: ${raw.slice(0, 200)}` });
            return;
          }
          try {
            resolve({ success: true, data: JSON.parse(raw) as T });
          } catch (err) {
            resolve({ success: false, warning: `Patched Copilot bridge ${route} returned invalid JSON: ${err instanceof Error ? err.message : String(err)}` });
          }
        });
      });
      req.on('timeout', () => {
        req.destroy(new Error('timeout'));
      });
      req.on('error', err => resolve({ success: false, warning: `Patched Copilot bridge unavailable: ${err.message}` }));
      if (payload) {
        req.write(payload);
      }
      req.end();
    });
  }

  private isPatchedBridgeEnabled(): boolean {
    return vscode.workspace.getConfiguration('copilotRemote.patchedCopilotBridge').get<boolean>('enabled', false);
  }

  async getActiveChatSession(): Promise<Record<string, unknown>> {
    const result = await this.tryPatchedBridge<Record<string, unknown>>('GET', '/chat/active-session');
    if (!result.success || !result.data) throw new Error(result.warning || 'Failed to read active Copilot Chat session');
    return result.data;
  }

  async getActiveChatTodos(): Promise<Record<string, unknown>> {
    const result = await this.tryPatchedBridge<Record<string, unknown>>('GET', '/chat/active-session/todos');
    if (!result.success || !result.data) throw new Error(result.warning || 'Failed to read active Copilot Chat todos');
    return result.data;
  }

  subscribeToPatchedBridgeEvents(onEvent: (event: Record<string, unknown>) => void, onError: (error: Error) => void): vscode.Disposable {
    if (!this.isPatchedBridgeEnabled()) return { dispose() {} };
    const config = vscode.workspace.getConfiguration('copilotRemote.patchedCopilotBridge');
    const key = String(config.get<string>('key', '') || '').trim();
    const host = String(config.get<string>('host', '127.0.0.1') || '127.0.0.1');
    const port = Number(config.get<number>('port', 19877) || 19877);
    let disposed = false;
    let request: http.ClientRequest | undefined;
    let retry: NodeJS.Timeout | undefined;
    const reconnect = (error?: Error) => {
      if (disposed) return;
      if (error) onError(error);
      if (!retry) retry = setTimeout(() => { retry = undefined; connect(); }, 2_000);
    };
    const connect = () => {
      if (disposed) return;
      let buffer = '';
      request = http.request({ host, port, path: '/chat/events', method: 'GET', headers: { 'x-copilot-remote-key': key, accept: 'text/event-stream' } }, response => {
        if (response.statusCode !== 200) {
          response.resume();
          reconnect(new Error(`Patched bridge event stream returned ${response.statusCode}`));
          return;
        }
        response.setEncoding('utf8');
        response.on('data', chunk => {
          buffer += chunk;
          const frames = buffer.split(/\r?\n\r?\n/);
          buffer = frames.pop() || '';
          for (const frame of frames) {
            const data = frame.split(/\r?\n/).filter(line => line.startsWith('data:')).map(line => line.slice(5).trim()).join('\n');
            if (data) try { onEvent(JSON.parse(data) as Record<string, unknown>); } catch { }
          }
        });
        response.once('end', () => reconnect(new Error('Patched bridge event stream ended')));
        response.once('error', reconnect);
      });
      request.once('error', reconnect);
      request.end();
    };
    connect();
    return { dispose: () => { disposed = true; if (retry) clearTimeout(retry); request?.destroy(); } };
  }

  async streamChatViaPatchedBridge(
    requestId: string,
    modelId: string | undefined,
    messages: ChatMessage[],
  options: { sessionResource?: string; participant?: string; reasoningEffort?: string; permissionLevel?: string; enabledTools?: string[] } | undefined,
    onEvent: (event: Record<string, unknown>) => void,
    token: vscode.CancellationToken,
  ): Promise<void> {
    if (!this.isPatchedBridgeEnabled()) {
      throw new Error('Patched Copilot bridge is required for chat. Enable copilotRemote.patchedCopilotBridge.enabled.');
    }
    const config = vscode.workspace.getConfiguration('copilotRemote.patchedCopilotBridge');
    const key = String(config.get<string>('key', '') || '').trim();
    const host = String(config.get<string>('host', '127.0.0.1') || '127.0.0.1');
    const port = Number(config.get<number>('port', 19877) || 19877);
    if (!key) throw new Error('Patched Copilot bridge key is empty');
    const payload = JSON.stringify({ requestId, modelId, messages, sessionResource: options?.sessionResource, participant: options?.participant, reasoningEffort: options?.reasoningEffort, permissionLevel: options?.permissionLevel, enabledTools: options?.enabledTools });

    await new Promise<void>((resolve, reject) => {
      const req = http.request({
        host,
        port,
        path: '/chat/submit',
        method: 'POST',
        headers: {
          'x-copilot-remote-key': key,
          'content-type': 'application/json; charset=utf-8',
          'content-length': Buffer.byteLength(payload),
        },
      }, res => {
        if ((res.statusCode || 0) < 200 || (res.statusCode || 0) >= 300) {
          const chunks: Buffer[] = [];
          res.on('data', chunk => chunks.push(Buffer.from(chunk)));
          res.on('end', () => reject(new Error(`Patched Copilot chat returned ${res.statusCode}: ${Buffer.concat(chunks).toString('utf8').slice(0, 300)}`)));
          return;
        }
        let buffer = '';
        res.setEncoding('utf8');
        res.on('data', chunk => {
          buffer += chunk;
          let newline = buffer.indexOf('\n');
          while (newline >= 0) {
            const line = buffer.slice(0, newline).trim();
            buffer = buffer.slice(newline + 1);
            if (line) onEvent(JSON.parse(line) as Record<string, unknown>);
            newline = buffer.indexOf('\n');
          }
        });
        res.on('end', () => resolve());
      });
      req.on('error', reject);
      const cancellation = token.onCancellationRequested(() => {
        const cancelReq = http.request({ host, port, path: `/chat/cancel/${encodeURIComponent(requestId)}`, method: 'POST', headers: { 'x-copilot-remote-key': key } });
        cancelReq.end();
      });
      req.on('close', () => cancellation.dispose());
      req.write(payload);
      req.end();
    });
  }

  private async getNativeChatSessionRoots(): Promise<Array<{ dir: string; source: NativeChatSessionInfo['source']; workspaceName?: string; workspaceFolder?: string }>> {
    const appData = process.env.APPDATA || path.join(os.homedir(), 'AppData', 'Roaming');
    const userData = process.env.VSCODE_PORTABLE
      ? path.join(process.env.VSCODE_PORTABLE, 'user-data', 'User')
      : path.join(appData, 'Code', 'User');
    const globalStorage = path.join(userData, 'globalStorage');
    const workspaceStorage = path.join(userData, 'workspaceStorage');
    const roots: Array<{ dir: string; source: NativeChatSessionInfo['source']; workspaceName?: string; workspaceFolder?: string }> = [];

    try {
      const workspaceEntries = await fs.readdir(workspaceStorage, { withFileTypes: true });
      for (const entry of workspaceEntries) {
        if (!entry.isDirectory()) continue;
        const workspaceDir = path.join(workspaceStorage, entry.name);
        const chatSessionsDir = path.join(workspaceDir, 'chatSessions');
        try {
          const stat = await fs.stat(chatSessionsDir);
          if (!stat.isDirectory()) continue;
        } catch {
          continue;
        }
        const workspaceFolder = await this.readWorkspaceStorageFolder(workspaceDir);
        roots.push({
          dir: chatSessionsDir,
          source: 'workspace',
          workspaceFolder,
          workspaceName: this.workspaceDisplayName(workspaceFolder) || entry.name,
        });
      }
    } catch {
      // Ignore missing workspace storage.
    }

    roots.push(
      { dir: path.join(globalStorage, 'emptyWindowChatSessions'), source: 'emptyWindow' },
      { dir: path.join(globalStorage, 'transferredChatSessions'), source: 'transferred' },
    );
    return roots;
  }

  private async readWorkspaceStorageFolder(workspaceDir: string): Promise<string | undefined> {
    try {
      const raw = await fs.readFile(path.join(workspaceDir, 'workspace.json'), 'utf8');
      const json = JSON.parse(raw);
      return typeof json.folder === 'string' ? decodeURIComponent(json.folder.replace(/^file:\/\//, '')) : undefined;
    } catch {
      return undefined;
    }
  }

  private workspaceDisplayName(folder?: string): string | undefined {
    if (!folder) return undefined;
    const normalized = folder.replace(/^\/+([a-zA-Z]:)/, '$1').replace(/\//g, path.sep);
    return path.basename(normalized) || normalized;
  }

  private async readNativeChatSessionFile(
    filePath: string,
    source: NativeChatSessionInfo['source'],
    includeMessages: boolean,
    workspaceName?: string,
    workspaceFolder?: string,
  ): Promise<NativeChatSessionInfo | undefined> {
    const stat = await fs.stat(filePath);
    const raw = await fs.readFile(filePath, 'utf8');
    const id = path.basename(filePath).replace(/\.jsonl?$/i, '');
    const state = filePath.endsWith('.jsonl') ? this.replayJsonlSessionState(raw) : JSON.parse(raw);
    const requests: unknown[] = Array.isArray(state?.requests) ? state.requests : [];
    const title = this.pickNativeChatTitle(state, requests) || id;
    const messages = includeMessages ? this.extractNativeChatMessages(requests) : undefined;

    return {
      id: String(state?.sessionId || id),
      title,
      source,
      filePath,
      workspaceName,
      workspaceFolder,
      createdAt: typeof state?.creationDate === 'number' ? state.creationDate : stat.birthtimeMs,
      updatedAt: stat.mtimeMs,
      requestCount: requests.length,
      messages,
    };
  }

  private replayJsonlSessionState(raw: string): Record<string, unknown> {
    let state: Record<string, unknown> = {};
    for (const line of raw.split(/\r?\n/)) {
      if (!line.trim()) continue;
      const item = JSON.parse(line);
      if (item.kind === 0 && item.v && typeof item.v === 'object') {
        state = { ...(item.v as Record<string, unknown>) };
      } else if (item.kind === 1 && Array.isArray(item.k)) {
        this.setDeepValue(state, item.k, item.v);
      } else if (item.kind === 2 && Array.isArray(item.k)) {
        this.setDeepValue(state, item.k, item.v);
      }
    }
    return state;
  }

  private setDeepValue(target: Record<string, unknown>, keys: unknown[], value: unknown): void {
    let current: Record<string, unknown> | unknown[] = target;
    for (let i = 0; i < keys.length - 1; i++) {
      const key = keys[i];
      const nextKey = keys[i + 1];
      const prop = typeof key === 'number' ? key : String(key);
      const container = current as Record<string, unknown>;
      if (!container[prop] || typeof container[prop] !== 'object') {
        container[prop] = typeof nextKey === 'number' ? [] : {};
      }
      current = container[prop] as Record<string, unknown> | unknown[];
    }
    const last = keys[keys.length - 1];
    (current as Record<string, unknown>)[typeof last === 'number' ? last : String(last)] = value;
  }

  private pickNativeChatTitle(state: Record<string, unknown>, requests: unknown[]): string {
    const customTitle = typeof state.customTitle === 'string' ? state.customTitle.trim() : '';
    if (customTitle) return customTitle;
    const first = requests[0] as Record<string, unknown> | undefined;
    const text = this.extractRequestText(first).trim();
    return text.split(/\r?\n/)[0]?.slice(0, 80) || '';
  }

  private extractNativeChatMessages(requests: unknown[]): ChatHistoryItem[] {
    const messages: ChatHistoryItem[] = [];
    for (const req of requests) {
      if (!req || typeof req !== 'object') continue;
      const request = req as Record<string, unknown>;
      const timestamp = typeof request.timestamp === 'number' ? request.timestamp : Date.now();
      const userText = this.extractRequestText(request);
      if (userText) {
        messages.push({
          id: String(request.requestId || `${timestamp}-user-${messages.length}`),
          role: 'user',
          content: userText,
          timestamp,
        });
      }
      const assistantText = this.extractResponseText(request.response) || this.extractResultText(request.result);
      if (assistantText) {
        messages.push({
          id: `${String(request.requestId || timestamp)}-assistant`,
          role: 'assistant',
          content: assistantText,
          timestamp: timestamp + 1,
        });
      }
    }
    return messages;
  }

  private extractRequestText(request?: Record<string, unknown>): string {
    if (!request) return '';
    const message = request.message as Record<string, unknown> | undefined;
    if (typeof message?.text === 'string') return message.text;
    if (Array.isArray(message?.parts)) {
      return message.parts
        .map((part) => typeof (part as Record<string, unknown>).text === 'string' ? (part as Record<string, unknown>).text : '')
        .filter(Boolean)
        .join('\n');
    }
    if (typeof request.prompt === 'string') return request.prompt;
    return '';
  }

  private extractResponseText(response: unknown): string {
    if (!Array.isArray(response)) return '';
    const parts: string[] = [];
    for (const item of response) {
      const obj = item as Record<string, unknown>;
      const kind = typeof obj.kind === 'string' ? obj.kind : '';
      const stepLabel = this.chatResponseStepLabel(kind);
      const value = obj.value;
      if (typeof value === 'string') {
        parts.push(stepLabel ? `**${stepLabel}**\n\n${value}` : value);
      } else if (value && typeof value === 'object') {
        const nested = value as Record<string, unknown>;
        if (typeof nested.value === 'string') {
          parts.push(stepLabel ? `**${stepLabel}**\n\n${nested.value}` : nested.value);
        } else if (stepLabel) {
          parts.push(`**${stepLabel}**\n\n${this.safeJsonSummary(nested)}`);
        }
      } else if (stepLabel) {
        parts.push(`**${stepLabel}**\n\n${this.safeJsonSummary(obj)}`);
      }
    }
    return parts.join('\n').trim();
  }

  private chatResponseStepLabel(kind: string): string | undefined {
    if (!kind) return undefined;
    if (kind === 'thinking') return 'Thinking';
    if (kind.includes('toolInvocation')) return 'Tool invocation';
    if (kind.includes('mcpServers')) return 'MCP servers';
    if (kind.toLowerCase().includes('tool')) return 'Tool step';
    return undefined;
  }

  private safeJsonSummary(value: unknown): string {
    try {
      return JSON.stringify(value, null, 2).slice(0, 4000);
    } catch {
      return String(value);
    }
  }

  private extractResultText(result: unknown): string {
    if (!result || typeof result !== 'object') return '';
    const obj = result as Record<string, unknown>;
    const errorDetails = obj.errorDetails as Record<string, unknown> | undefined;
    if (typeof errorDetails?.message === 'string') {
      return errorDetails.message;
    }
    const metadata = obj.metadata as Record<string, unknown> | undefined;
    const rounds = metadata?.toolCallRounds;
    if (Array.isArray(rounds)) {
      const texts = rounds
        .map((round) => {
          const response = (round as Record<string, unknown>).response;
          return typeof response === 'string' ? response.trim() : '';
        })
        .filter(Boolean);
      if (texts.length) return texts.join('\n\n');
    }
    return '';
  }

  // ─── Commands ───────────────────────────────────────────────

  async listCommands(): Promise<CommandInfo[]> {
    const allCommands = await vscode.commands.getCommands(true);
    return allCommands
      .filter((cmd) => {
        // Include Copilot-related and chat-related commands
        const lower = cmd.toLowerCase();
        return (
          lower.includes('copilot') ||
          lower.includes('chat') ||
          lower.includes('github') ||
          lower.includes('ai') ||
          lower.includes('mcp') ||
          lower.includes('inline') ||
          lower.includes('workbench.action.chat') ||
          lower.includes('workbench.action.inline')
        );
      })
      .map((cmd) => ({
        id: cmd,
        isCopilot: cmd.toLowerCase().includes('copilot'),
      }))
      .sort((a, b) => a.id.localeCompare(b.id));
  }

  async executeCommand(commandId: string, args?: unknown[]): Promise<unknown> {
    return vscode.commands.executeCommand(commandId, ...(args || []));
  }

  async listSlashCommands(): Promise<SlashCommandInfo[]> {
    const commands: SlashCommandInfo[] = [];
    const add = (name: unknown, description: unknown, source: string) => {
      if (typeof name !== 'string') return;
      const normalizedName = name.trim().replace(/^\/+/, '').replace(/\s+/g, '-');
      if (!normalizedName || normalizedName.includes('/')) return;
      const normalizedDescription = typeof description === 'string' && !/^%[^%]+%$/.test(description)
        ? description.trim()
        : '';
      commands.push({ name: normalizedName, description: normalizedDescription, source });
    };

    for (const [name, description] of [
      ['compact', '压缩当前对话上下文'], ['explain', '解释选中或当前代码'], ['fix', '分析并修复问题'],
      ['new', '创建新项目或文件'], ['setupTests', '配置测试框架'], ['tests', '为代码生成测试'],
    ]) add(name, description, 'Copilot');

    // Read contribution points directly. listParticipants intentionally only
    // exposes the mode picker, while slash commands also come from hidden chat
    // participants, chat sessions, prompt files and language-model tools.
    for (const extension of vscode.extensions.all) {
      const pkg = extension.packageJSON;
      const contributes = pkg?.contributes || {};
      const extensionName = String(pkg?.displayName || pkg?.name || extension.id);

      for (const participant of Array.isArray(contributes.chatParticipants) ? contributes.chatParticipants : []) {
        const participantName = String(participant?.fullName || participant?.name || extensionName);
        for (const command of Array.isArray(participant?.commands) ? participant.commands : []) {
          add(command?.name, command?.description, `参与者 · ${participantName}`);
        }
      }

      for (const session of Array.isArray(contributes.chatSessions) ? contributes.chatSessions : []) {
        const sessionName = String(session?.displayName || session?.name || extensionName);
        for (const command of Array.isArray(session?.commands) ? session.commands : []) {
          add(command?.name, command?.description, `会话 · ${sessionName}`);
        }
      }

      for (const prompt of Array.isArray(contributes.chatPromptFiles) ? contributes.chatPromptFiles : []) {
        if (typeof prompt?.path !== 'string') continue;
        const name = path.basename(prompt.path).replace(/\.prompt\.md$/i, '').replace(/\.md$/i, '');
        add(name, prompt.description || '运行扩展提供的提示文件', `提示 · ${extensionName}`);
      }

      for (const tool of Array.isArray(contributes.languageModelTools) ? contributes.languageModelTools : []) {
        // toolReferenceName is the name users can type after '/' in VS Code.
        if (typeof tool?.toolReferenceName === 'string') {
          add(tool.toolReferenceName, tool.description, `工具 · ${extensionName}`);
        }
      }
    }

    const participants = await this.listParticipants();
    for (const participant of participants) {
      for (const command of participant.commands) {
        add(command.name, command.description, `参与者 · ${participant.fullName || participant.name}`);
      }
    }

    for (const skill of await this.listSkills()) {
      add(skill.name, skill.description, `技能 · ${skill.sourceLabel || (skill.source === 'user' ? '用户' : '扩展')}`);
    }

    // Workspace prompt files are dynamic and are not necessarily declared by
    // an extension manifest. They behave like slash commands in VS Code chat.
    try {
      const promptFiles = await vscode.workspace.findFiles('**/*.prompt.md', '**/{node_modules,.git}/**', 200);
      for (const uri of promptFiles) {
        add(path.basename(uri.fsPath).replace(/\.prompt\.md$/i, ''), '运行工作区提示文件', '提示 · 工作区');
      }
    } catch {
      // A remote/virtual workspace may not provide a file search provider.
    }

    // MCP tools are exposed by the runtime and often are not present in an
    // extension manifest. Match VS Code's /mcp.server.tool style closely.
    for (const tool of await this.listTools()) {
      if (tool.source !== 'mcp') continue;
      const referenceName = tool.name.startsWith('mcp__')
        ? tool.name.replace(/^mcp__/, 'mcp.').replace(/__/g, '.')
        : tool.name.replace(/^mcp[_-]/i, 'mcp.');
      add(referenceName, tool.description, `MCP · ${tool.groupName}`);
    }

    const unique = new Map<string, SlashCommandInfo>();
    for (const command of commands) {
      const key = command.name.toLocaleLowerCase();
      const existing = unique.get(key);
      if (!existing || (!existing.description && command.description)) unique.set(key, command);
    }
    return [...unique.values()].sort((a, b) => a.name.localeCompare(b.name));
  }

  // ─── Chat Participants (Agents) ─────────────────────────────

  async listParticipants(): Promise<ParticipantInfo[]> {
    // Match the actual VS Code Chat mode picker instead of exposing internal
    // chat participants contributed by extensions.
    const builtIn: ParticipantInfo[] = [
      {
        id: 'Agent', name: 'Agent', fullName: 'Agent',
        description: 'Autonomous coding and tool use',
        isSticky: true,
        isBuiltIn: true,
        commands: [],
      },
      {
        id: 'Ask', name: 'Ask', fullName: 'Ask',
        description: 'Ask questions without editing files',
        isSticky: true,
        isBuiltIn: true,
        commands: [],
      },
      {
        id: 'Edit', name: 'Edit', fullName: 'Edit',
        description: 'Make focused edits across files',
        isSticky: true,
        isBuiltIn: true,
        commands: [],
      },
      {
        id: 'Plan', name: 'Plan', fullName: 'Plan',
        description: 'Create an implementation plan before editing',
        isSticky: true,
        isBuiltIn: true,
        commands: [],
      },
    ];

    const fileAgents = await this.discoverUserAgents();
    const all = [...builtIn, ...fileAgents];
    const seen = new Set<string>();
    return all.filter((p) => {
      const key = p.id || p.name;
      if (seen.has(key)) return false;
      seen.add(key);
      return true;
    });
  }

  private async discoverUserAgents(): Promise<ParticipantInfo[]> {
    const patterns = [
      '**/*.agent.md',
      '.github/copilot/agents/**/*.md',
      '.copilot/agents/**/*.md',
    ];
    const found: ParticipantInfo[] = [];
    const addAgent = async (uri: vscode.Uri) => {
      const base = path.basename(uri.fsPath);
      const fallbackName = base.replace(/\.agent\.md$/i, '').replace(/\.md$/i, '');
      const content = new TextDecoder().decode(await vscode.workspace.fs.readFile(uri));
      if (/^user-invocable:\s*(?:false|no|0)\s*$/mi.test(content)) return;
      if (/^target:\s*github-copilot\s*$/mi.test(content)) return;
      const clean = (value: string) => value.trim().replace(/^['"]|['"]$/g, '');
      const name = clean(content.match(/^name:\s*(.+)$/m)?.[1] || fallbackName);
      const description = clean(content.match(/^description:\s*(.+)$/m)?.[1] || '')
        || `User agent: ${vscode.workspace.asRelativePath(uri)}`;
      found.push({ id: name, name, fullName: name, description, isSticky: false, isBuiltIn: false, commands: [] });
    };
    for (const pattern of patterns) {
      try {
        const files = await vscode.workspace.findFiles(pattern, '**/{node_modules,.git,build,dist}/**', 50);
        for (const uri of files) {
          await addAgent(uri);
        }
      } catch {
        // ignore inaccessible patterns
      }
    }
    try {
      const userAgentDir = vscode.Uri.file(path.join(os.homedir(), '.copilot', 'agents'));
      for (const [name, type] of await vscode.workspace.fs.readDirectory(userAgentDir)) {
        if (type === vscode.FileType.File && name.endsWith('.agent.md')) {
          await addAgent(vscode.Uri.joinPath(userAgentDir, name));
        }
      }
    } catch {
      // User-level agent directory is optional.
    }
    return found;
  }

  async sendToCopilotChat(
    prompt: string,
    participant?: string,
    command?: string,
  ): Promise<{ success: boolean; error?: string }> {
    try {
      let fullPrompt = '';
      if (command) {
        fullPrompt += `/${command} `;
      }
      fullPrompt += prompt;

      await vscode.commands.executeCommand('workbench.action.chat.open', {
        query: fullPrompt,
        mode: participant,
      });
      return { success: true };
    } catch (err) {
      return { success: false, error: String(err) };
    }
  }

  // ─── Extensions / Plugins ───────────────────────────────────

  async listExtensions(): Promise<ExtensionInfo[]> {
    return vscode.extensions.all
      .filter((ext) => !ext.packageJSON?.isBuiltin)
      .map((ext) => {
        const pkg = ext.packageJSON;
        const isCopilot =
          ext.id.toLowerCase().includes('copilot') ||
          pkg.name?.toLowerCase().includes('copilot');
        const isAI =
          isCopilot ||
          ext.id.toLowerCase().includes('ai') ||
          pkg.contributes?.chatParticipants ||
          pkg.contributes?.chat?.participants ||
          pkg.contributes?.languageModelTools;
        return {
          id: ext.id,
          name: pkg.name || ext.id,
          displayName: pkg.displayName || pkg.name || ext.id,
          version: pkg.version || '0.0.0',
          description: pkg.description || '',
          isActive: ext.isActive,
          isCopilot: !!isCopilot,
          isAI: !!isAI,
        };
      })
      .sort((a, b) => {
        // Copilot and AI extensions first
        if (a.isCopilot !== b.isCopilot) return a.isCopilot ? -1 : 1;
        if (a.isAI !== b.isAI) return a.isAI ? -1 : 1;
        return a.displayName.localeCompare(b.displayName);
      });
  }

  async toggleExtension(extensionId: string, enable: boolean): Promise<{ success: boolean; error?: string }> {
    try {
      if (enable) {
        await vscode.commands.executeCommand('workbench.extensions.enableExtension', extensionId);
      } else {
        await vscode.commands.executeCommand('workbench.extensions.disableExtension', extensionId);
      }
      return { success: true };
    } catch (err) {
      return { success: false, error: String(err) };
    }
  }

  // ─── MCP Servers ────────────────────────────────────────────

  async listMcpServers(): Promise<McpServerInfo[]> {
    const result: McpServerInfo[] = [];

    const addServers = (servers: Record<string, unknown> | undefined, status: 'connected' | 'disconnected' | 'error' = 'disconnected') => {
      if (!servers) return;
      for (const [name, config] of Object.entries(servers)) {
        if (result.find((s) => s.name === name)) continue;
        const serverConfig = config as Record<string, unknown>;
        result.push({
          name,
          command: String(serverConfig.command || serverConfig.type || ''),
          args: (serverConfig.args as string[]) || [],
          env: (serverConfig.env as Record<string, string>) || undefined,
          status,
        });
      }
    };

    // VS Code MCP uses mcp.servers; older Copilot settings used github.copilot.chat.mcp.servers.
    addServers(vscode.workspace.getConfiguration('mcp').get<Record<string, unknown>>('servers'));
    addServers(vscode.workspace.getConfiguration('github.copilot.chat.mcp').get<Record<string, unknown>>('servers'));

    // Some users keep MCP config in JSON object settings.
    const mcpConfig = vscode.workspace.getConfiguration().get<Record<string, unknown>>('mcp');
    if (mcpConfig?.servers && typeof mcpConfig.servers === 'object') {
      addServers(mcpConfig.servers as Record<string, unknown>);
    }

    // Also check workspace .vscode/mcp.json if it exists
    try {
      const mcpJsonUri = vscode.workspace.workspaceFolders?.[0]?.uri.with({
        path: vscode.workspace.workspaceFolders[0].uri.path + '/.vscode/mcp.json',
      });
      if (mcpJsonUri) {
        const doc = await vscode.workspace.fs.readFile(mcpJsonUri);
        const content = Buffer.from(doc).toString('utf8');
        const parsed = JSON.parse(content);
        addServers(parsed.servers || parsed.mcpServers);
      }
    } catch {
      // mcp.json might not exist
    }

    return result;
  }

  async addMcpServer(config: McpServerInfo): Promise<{ success: boolean; error?: string }> {
    try {
      const wsConfig = vscode.workspace.getConfiguration('mcp');
      const servers = wsConfig.get<Record<string, unknown>>('servers') || {};
      servers[config.name] = {
        command: config.command,
        args: config.args,
        ...(config.env ? { env: config.env } : {}),
      };
      await wsConfig.update('servers', servers, vscode.ConfigurationTarget.Global);
      return { success: true };
    } catch (err) {
      return { success: false, error: String(err) };
    }
  }

  async removeMcpServer(name: string): Promise<{ success: boolean; error?: string }> {
    try {
      const wsConfig = vscode.workspace.getConfiguration('mcp');
      const servers = wsConfig.get<Record<string, unknown>>('servers') || {};
      delete servers[name];
      await wsConfig.update('servers', servers, vscode.ConfigurationTarget.Global);
      return { success: true };
    } catch (err) {
      return { success: false, error: String(err) };
    }
  }

  /* legacy implementation removed */
  private unusedLegacyMcpParser(): void {
    return;
    /*
    for (const [name, config] of Object.entries({})) {
      const serverConfig = config as Record<string, unknown>;
      result.push({
        name,
        command: String(serverConfig.command || ''),
        args: (serverConfig.args as string[]) || [],
        env: (serverConfig.env as Record<string, string>) || undefined,
        status: 'connected', // Would need actual MCP client to verify
      });
    }
    */
  }

  // ─── Agent skills ───────────────────────────────────────────

  async listSkills(): Promise<SkillInfo[]> {
    const skills: SkillInfo[] = [];
    const addSkill = async (filePath: string, source: SkillInfo['source'], sourceLabel?: string) => {
      try {
        const stat = await fs.stat(filePath);
        const skillFile = stat.isDirectory() ? path.join(filePath, 'SKILL.md') : filePath;
        const content = await fs.readFile(skillFile, 'utf8');
        const fallbackName = path.basename(path.dirname(skillFile));
        const metadata = this._readSkillMetadata(content, fallbackName);
        const id = `${source}:${sourceLabel || 'local'}:${metadata.name}`;
        if (!skills.some(skill => skill.source === source && skill.name === metadata.name && skill.sourceLabel === sourceLabel)) {
          skills.push({ id, name: metadata.name, description: metadata.description, source, sourceLabel });
        }
      } catch {
        // Contributions may be disabled, conditional, or missing on disk.
      }
    };

    for (const root of [path.join(os.homedir(), '.agents', 'skills'), path.join(os.homedir(), '.copilot', 'skills'), path.join(os.homedir(), '.codex', 'skills')]) {
      try {
        for (const entry of await fs.readdir(root, { withFileTypes: true })) {
          if (entry.isDirectory()) await addSkill(path.join(root, entry.name), 'user');
        }
      } catch {
        // User skill directories are optional.
      }
    }

    for (const extension of vscode.extensions.all) {
      const contributions = extension.packageJSON?.contributes?.chatSkills;
      if (!Array.isArray(contributions)) continue;
      const label = String(extension.packageJSON?.displayName || extension.packageJSON?.name || extension.id);
      for (const contribution of contributions) {
        if (typeof contribution?.path !== 'string') continue;
        await addSkill(path.resolve(extension.extensionPath, contribution.path), 'extension', label);
      }
    }

    return skills.sort((a, b) => a.source.localeCompare(b.source) || a.name.localeCompare(b.name));
  }

  private _readSkillMetadata(content: string, fallbackName: string): { name: string; description: string } {
    const frontmatter = content.match(/^---\s*\r?\n([\s\S]*?)\r?\n---/)?.[1] || content;
    const clean = (value: string) => value.trim().replace(/^['"]|['"]$/g, '');
    const name = clean(frontmatter.match(/^name:\s*(.+)$/m)?.[1] || fallbackName);
    const inlineDescription = frontmatter.match(/^description:\s*([^>|].*)$/m)?.[1];
    const blockDescription = frontmatter.match(/^description:\s*[>|][-+]?\s*\r?\n((?:[ \t]+.*(?:\r?\n|$))+)/m)?.[1]
      ?.split(/\r?\n/).map(line => line.trim()).filter(Boolean).join(' ');
    return { name, description: clean(inlineDescription || blockDescription || '') };
  }

  async invokeSkill(
    skillId: string,
    args?: Record<string, unknown>,
  ): Promise<{ success: boolean; result?: unknown; error?: string }> {
    try {
      const result = await vscode.commands.executeCommand(skillId, ...(args ? [args] : []));
      return { success: true, result };
    } catch (err) {
      return { success: false, error: String(err) };
    }
  }

  // ─── Workspace ──────────────────────────────────────────────

  async getWorkspaceInfo(): Promise<WorkspaceInfo> {
    const folders = (vscode.workspace.workspaceFolders || []).map((f) => ({
      name: f.name,
      uri: f.uri.toString(),
    }));

    const tabEditors: EditorInfo[] = vscode.window.tabGroups.all.flatMap((group) =>
      group.tabs.flatMap((tab) => {
        const input = tab.input;
        const uri = input instanceof vscode.TabInputText ? input.uri
          : input instanceof vscode.TabInputTextDiff ? input.modified
          : input instanceof vscode.TabInputNotebook ? input.uri
          : input instanceof vscode.TabInputCustom ? input.uri
          : undefined;
        if (!uri || (uri.scheme !== 'file' && uri.scheme !== 'untitled')) return [];
        const document = vscode.workspace.textDocuments.find(item => item.uri.toString() === uri.toString());
        return {
          fileName: path.basename(uri.fsPath || uri.path) || tab.label,
          filePath: uri.fsPath,
          languageId: document?.languageId || '',
          isDirty: tab.isDirty,
          isUntitled: uri.scheme === 'untitled',
          scheme: uri.scheme,
        };
      }),
    );
    const openEditors = [...new Map(tabEditors.map(editor => [editor.filePath, editor])).values()];

    const activeEditor = vscode.window.activeTextEditor;
    const activeEditorInfo: EditorInfo | undefined = activeEditor
      ? {
          fileName: activeEditor.document.fileName.split('/').pop() || activeEditor.document.fileName,
          filePath: activeEditor.document.fileName,
          languageId: activeEditor.document.languageId,
          isDirty: activeEditor.document.isDirty,
          isUntitled: activeEditor.document.isUntitled,
          scheme: activeEditor.document.uri.scheme,
        }
      : undefined;

    return {
      name: vscode.workspace.name || 'No Workspace',
      folders,
      activeEditor: activeEditorInfo,
      openEditors,
    };
  }

  async getOpenEditors(): Promise<EditorInfo[]> {
    const info = await this.getWorkspaceInfo();
    return info.openEditors;
  }

  private workspaceUri(filePath = ''): vscode.Uri {
    const root = vscode.workspace.workspaceFolders?.[0]?.uri;
    if (!root) throw new Error('No workspace is open');
    const uri = path.isAbsolute(filePath) ? vscode.Uri.file(filePath) : vscode.Uri.joinPath(root, filePath);
    const relative = path.relative(root.fsPath, uri.fsPath);
    if (relative.startsWith('..') || path.isAbsolute(relative)) throw new Error('Path is outside the workspace');
    return uri;
  }

  async searchFiles(query: string, include = '**/*') {
    const pattern = query.includes('*') || query.includes('?') ? `**/${query}` : `**/*${query}*`;
    const files = await vscode.workspace.findFiles(include === '**/*' ? pattern : include, '**/{node_modules,.git,.venv,venv,.gradle,.idea,build,dist,obj,out,target}/**', 200);
    const needle = query.replace(/[?*]/g, '').toLowerCase();
    return files.filter(uri => include === '**/*' || path.basename(uri.fsPath).toLowerCase().includes(needle)).map((uri) => ({ name: path.basename(uri.fsPath), filePath: vscode.workspace.asRelativePath(uri) }));
  }

  async searchText(query: string, include = '**/*') {
    const files = await vscode.workspace.findFiles(include, '**/{node_modules,.git,.venv,venv,.gradle,.idea,build,dist,obj,out,target}/**', 2000);
    const results: Array<{ filePath: string; line: number; preview: string }> = [];
    const needle = query.toLowerCase();
    for (const uri of files) {
      if (results.length >= 200) break;
      try {
        if ((await vscode.workspace.fs.stat(uri)).size > 2 * 1024 * 1024) continue;
        const bytes = Buffer.from(await vscode.workspace.fs.readFile(uri));
        if (bytes.includes(0)) continue;
        const text = bytes.toString('utf8');
        text.split(/\r?\n/).forEach((line, index) => {
          if (results.length < 200 && line.toLowerCase().includes(needle)) results.push({ filePath: vscode.workspace.asRelativePath(uri), line: index + 1, preview: line.trim().slice(0, 240) });
        });
      } catch { /* binary or unreadable */ }
    }
    return results;
  }

  async searchSymbols(query: string) {
    const symbols = await vscode.commands.executeCommand<vscode.SymbolInformation[]>('vscode.executeWorkspaceSymbolProvider', query) || [];
    return symbols.slice(0, 200).map((symbol) => ({ name: symbol.name, kind: vscode.SymbolKind[symbol.kind], filePath: vscode.workspace.asRelativePath(symbol.location.uri), line: symbol.location.range.start.line + 1, character: symbol.location.range.start.character + 1 }));
  }

  async listDirectory(filePath = '') {
    const uri = this.workspaceUri(filePath);
    const entries = await vscode.workspace.fs.readDirectory(uri);
    return entries.map(([name, type]) => ({ name, path: path.posix.join(filePath.replace(/\\/g, '/'), name), type: type === vscode.FileType.Directory ? 'directory' as const : 'file' as const })).sort((a, b) => a.type.localeCompare(b.type) || a.name.localeCompare(b.name));
  }

  async openFile(filePath: string, line?: number, character?: number) {
    const document = await vscode.workspace.openTextDocument(this.workspaceUri(filePath));
    const editor = await vscode.window.showTextDocument(document);
    if (line !== undefined) {
      const position = new vscode.Position(Math.max(0, line - 1), Math.max(0, (character ?? 1) - 1));
      editor.selection = new vscode.Selection(position, position);
      editor.revealRange(new vscode.Range(position, position));
    }
  }

  async closeFile(filePath: string) {
    const target = this.workspaceUri(filePath).toString();
    const tabs = vscode.window.tabGroups.all.flatMap((group) => group.tabs).filter((tab) => (tab.input as { uri?: vscode.Uri })?.uri?.toString() === target);
    if (!tabs.length) return false;
    await vscode.window.tabGroups.close(tabs);
    return true;
  }

  async findLocations(kind: 'definition' | 'references', filePath: string, line: number, character: number) {
    const uri = this.workspaceUri(filePath);
    const position = new vscode.Position(Math.max(0, line - 1), Math.max(0, character - 1));
    const command = kind === 'definition' ? 'vscode.executeDefinitionProvider' : 'vscode.executeReferenceProvider';
    const locations = await vscode.commands.executeCommand<Array<vscode.Location | vscode.LocationLink>>(command, uri, position) || [];
    return locations.map((item) => {
      const targetUri = 'uri' in item ? item.uri : item.targetUri;
      const range = 'range' in item ? item.range : item.targetRange;
      return { filePath: vscode.workspace.asRelativePath(targetUri), line: range.start.line + 1, character: range.start.character + 1 };
    });
  }

  private async gitRepositories(): Promise<any[]> {
    const extension = vscode.extensions.getExtension('vscode.git');
    if (!extension) throw new Error('Built-in Git extension is unavailable');
    const exports = extension.isActive ? extension.exports : await extension.activate();
    return exports.getAPI(1).repositories;
  }

  private async gitRepository(repositoryId?: string): Promise<any> {
    const repositories = await this.gitRepositories();
    const repository = repositoryId ? repositories.find(repo => repo.rootUri.toString() === repositoryId || repo.rootUri.fsPath === repositoryId) : repositories[0];
    if (!repository) throw new Error('No Git repository is open');
    return repository;
  }

  async getGitStatus(repositoryId?: string) {
    const all = await this.gitRepositories();
    if (!all.length) throw new Error('No Git repository is open');
    const repository = await this.gitRepository(repositoryId);
    const indexChanges = repository.state.indexChanges.map((change: any) => ({ change, staged: true }));
    const otherChanges = [...repository.state.workingTreeChanges, ...repository.state.mergeChanges].map((change: any) => ({ change, staged: false }));
    return {
      repositoryId: repository.rootUri.toString(),
      repositories: all.map(repo => ({ id: repo.rootUri.toString(), name: path.basename(repo.rootUri.fsPath), root: repo.rootUri.fsPath, branch: repo.state.HEAD?.name || '', changes: repo.state.indexChanges.length + repo.state.workingTreeChanges.length + repo.state.mergeChanges.length })),
      branch: repository.state.HEAD?.name || '',
      branches: (await repository.getBranches({ remote: false })).map((branch: any) => branch.name).filter(Boolean),
      ahead: repository.state.HEAD?.ahead || 0,
      behind: repository.state.HEAD?.behind || 0,
      remotes: repository.state.remotes.map((remote: any) => remote.name),
      changes: [...indexChanges, ...otherChanges].map(({ change, staged }) => ({ path: path.relative(repository.rootUri.fsPath, change.uri.fsPath), filePath: change.uri.fsPath, status: String(change.status), staged })),
    };
  }

  async getGitDiff(filePath?: string, repositoryId?: string, commit?: string) {
    const repository = await this.gitRepository(repositoryId);
    if (commit) return repository.diffWith(commit + '^', commit);
    if (filePath) {
      const path = this.workspaceUri(filePath).fsPath;
      return (await Promise.all([repository.diffIndexWithHEAD(path), repository.diffWithHEAD(path)])).filter(Boolean).join('\n');
    }
    return (await Promise.all([repository.diff(true), repository.diff(false)])).filter(Boolean).join('\n');
  }

  async getGitHistory(limit = 50, repositoryId?: string) {
    const repository = await this.gitRepository(repositoryId);
    const commits = await repository.log({ maxEntries: Math.min(Math.max(limit, 1), 200) });
    return commits.map((commit: any) => ({ hash: commit.hash, message: commit.message, author: commit.authorName || '', date: commit.authorDate ? new Date(commit.authorDate).toISOString() : '' }));
  }

  async checkoutGitBranch(branch: string, repositoryId?: string) { await (await this.gitRepository(repositoryId)).checkout(branch); }
  async createGitBranch(branch: string, checkout = true, repositoryId?: string) { await (await this.gitRepository(repositoryId)).createBranch(branch, checkout); }
  async commitGitChanges(message: string, all = false, repositoryId?: string) {
    const repository = await this.gitRepository(repositoryId);
    if (all) {
      await repository.status();
      const paths = [...repository.state.workingTreeChanges, ...repository.state.mergeChanges].map((change: any) => change.uri.fsPath);
      if (paths.length) await repository.add(paths);
    }
    await repository.commit(message);
  }

  async gitAction(action: string, repositoryId?: string, paths: string[] = []) {
    const repository = await this.gitRepository(repositoryId);
    if (action === 'stage') await repository.add(paths);
    else if (action === 'unstage') await repository.revert(paths);
    else if (action === 'discard') await repository.clean(paths);
    else if (action === 'pull') await repository.pull();
    else if (action === 'push') await repository.push();
    else if (action === 'fetch') await repository.fetch();
    else if (action === 'sync') await repository.sync();
    else throw new Error(`Unsupported Git action: ${action}`);
  }

  async listPullRequests(state = 'open') {
    const { owner, repo } = await this.githubRepository();
    const items = await this.githubRequest<any[]>(`/repos/${owner}/${repo}/pulls?state=${state === 'merged' ? 'closed' : state}&per_page=100`);
    return items.filter(pr => state !== 'merged' || pr.merged_at).map(pr => ({ number: pr.number, title: pr.title, state: pr.merged_at ? 'merged' : pr.state, author: pr.user?.login || '', headRefName: pr.head?.ref || '', baseRefName: pr.base?.ref || '', url: pr.html_url }));
  }

  async openPullRequest(number: number) {
    const { owner, repo } = await this.githubRepository();
    const pr = await this.githubRequest<any>(`/repos/${owner}/${repo}/pulls/${number}`);
    await vscode.env.openExternal(vscode.Uri.parse(pr.html_url));
    return pr.html_url as string;
  }

  async createPullRequest(title: string, body = '', base = '', draft = false) {
    const repository = await this.gitRepository();
    const { owner, repo } = await this.githubRepository();
    if (!base) base = (await this.githubRequest<any>(`/repos/${owner}/${repo}`)).default_branch;
    const pr = await this.githubRequest<any>(`/repos/${owner}/${repo}/pulls`, 'POST', { title, body, base, head: repository.state.HEAD?.name, draft });
    return pr.html_url as string;
  }

  private async githubRepository() {
    const repository = await this.gitRepository();
    const remote = repository.state.remotes.find((item: any) => item.name === 'origin') || repository.state.remotes[0];
    const match = String(remote?.fetchUrl || remote?.pushUrl || '').match(/github\.com[/:]([^/]+)\/([^/]+?)(?:\.git)?$/i);
    if (!match) throw new Error('The Git remote is not a GitHub repository');
    return { owner: match[1], repo: match[2] };
  }

  private async githubRequest<T>(apiPath: string, method = 'GET', body?: unknown): Promise<T> {
    const session = await vscode.authentication.getSession('github', ['repo'], { createIfNone: false });
    if (!session && method !== 'GET') throw new Error('Sign in to GitHub in VS Code before changing pull requests');
    const headers: Record<string, string> = {
      accept: 'application/vnd.github+json',
      'user-agent': 'copilot-remote',
      'content-type': 'application/json',
    };
    if (session) headers.authorization = `Bearer ${session.accessToken}`;
    return new Promise<T>((resolve, reject) => {
      const req = https.request({ hostname: 'api.github.com', path: apiPath, method, headers }, res => {
        const chunks: Buffer[] = [];
        res.on('data', chunk => chunks.push(Buffer.from(chunk)));
        res.on('end', () => { const text = Buffer.concat(chunks).toString('utf8'); if ((res.statusCode || 0) >= 200 && (res.statusCode || 0) < 300) resolve(JSON.parse(text) as T); else reject(new Error(`GitHub HTTP ${res.statusCode}: ${text.slice(0, 300)}`)); });
      });
      req.setTimeout(10_000, () => req.destroy(new Error('GitHub request timed out')));
      req.on('error', reject); req.end(body === undefined ? undefined : JSON.stringify(body));
    });
  }

  async setEditorSelection(filePath: string, startLine: number, startCharacter = 1, endLine = startLine, endCharacter = startCharacter) {
    const document = await vscode.workspace.openTextDocument(this.workspaceUri(filePath));
    const editor = await vscode.window.showTextDocument(document);
    const start = new vscode.Position(Math.max(0, startLine - 1), Math.max(0, startCharacter - 1));
    const end = new vscode.Position(Math.max(0, endLine - 1), Math.max(0, endCharacter - 1));
    editor.selection = new vscode.Selection(start, end);
    editor.revealRange(new vscode.Range(start, end), vscode.TextEditorRevealType.InCenterIfOutsideViewport);
  }

  async replaceInFiles(query: string, replacement: string, include = '**/*') {
    if (!query) throw new Error('Search text is required');
    const files = await vscode.workspace.findFiles(include, '**/{node_modules,.git,build,dist}/**', 500);
    const edit = new vscode.WorkspaceEdit();
    let filesChanged = 0;
    let replacements = 0;
    for (const uri of files) {
      try {
        const document = await vscode.workspace.openTextDocument(uri);
        const text = document.getText();
        let offset = text.indexOf(query);
        let count = 0;
        while (offset >= 0) {
          edit.replace(uri, new vscode.Range(document.positionAt(offset), document.positionAt(offset + query.length)), replacement);
          count++;
          offset = text.indexOf(query, offset + query.length);
        }
        if (count) { filesChanged++; replacements += count; }
      } catch { /* binary or unsupported document */ }
    }
    if (replacements && !(await vscode.workspace.applyEdit(edit))) throw new Error('VS Code rejected the workspace edit');
    return { filesChanged, replacements };
  }

  async captureWindow(seconds = 0, fps = 1) {
    if (process.platform !== 'win32') throw new Error('Window capture currently requires Windows');
    const count = seconds > 0 ? Math.min(Math.ceil(seconds * Math.min(Math.max(fps, 1), 3)), 30) : 1;
    const intervalMs = seconds > 0 ? Math.round(1000 / Math.min(Math.max(fps, 1), 3)) : 0;
    // ponytail: low-FPS PNG frames avoid bundling ffmpeg; switch to a native video encoder if long recordings are needed.
    const script = `Add-Type -AssemblyName System.Drawing; Add-Type @'
using System; using System.Runtime.InteropServices;
public class Win { [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr h, out RECT r); public struct RECT { public int Left,Top,Right,Bottom; } }
'@; $p=Get-Process Code | Where-Object {$_.MainWindowHandle -ne 0 -and $_.MainWindowTitle -like ('*'+$env:COPILOT_WINDOW_TITLE+'*')} | Select-Object -First 1; if(!$p){$p=Get-Process Code | Where-Object {$_.MainWindowHandle -ne 0}|Select-Object -First 1}; if(!$p){throw 'VS Code window not found'}; $frames=@(); 1..([int]$env:COPILOT_FRAME_COUNT) | ForEach-Object { $r=New-Object Win+RECT; [Win]::GetWindowRect($p.MainWindowHandle,[ref]$r)|Out-Null; $w=$r.Right-$r.Left; $h=$r.Bottom-$r.Top; $b=New-Object Drawing.Bitmap $w,$h; $g=[Drawing.Graphics]::FromImage($b); $g.CopyFromScreen($r.Left,$r.Top,0,0,$b.Size); $m=New-Object IO.MemoryStream; $b.Save($m,[Drawing.Imaging.ImageFormat]::Png); $frames += [Convert]::ToBase64String($m.ToArray()); $g.Dispose();$b.Dispose();$m.Dispose(); if([int]$env:COPILOT_FRAME_DELAY -gt 0){Start-Sleep -Milliseconds ([int]$env:COPILOT_FRAME_DELAY)} }; ConvertTo-Json $frames -Compress`;
    const run = promisify(execFile);
    const { stdout } = await run('powershell.exe', ['-NoProfile', '-Command', script], { maxBuffer: 80 * 1024 * 1024, env: { ...process.env, COPILOT_WINDOW_TITLE: vscode.workspace.name || '', COPILOT_FRAME_COUNT: String(count), COPILOT_FRAME_DELAY: String(intervalMs) } });
    const parsed = JSON.parse(stdout.trim());
    return { frames: Array.isArray(parsed) ? parsed : [parsed], intervalMs };
  }

  async getFileContent(filePath: string): Promise<{ content: string; error?: string }> {
    try {
      const uri = filePath.startsWith('file://')
        ? this.workspaceUri(vscode.Uri.parse(filePath).fsPath)
        : this.workspaceUri(filePath);
      const doc = await vscode.workspace.openTextDocument(uri);
      return { content: doc.getText() };
    } catch (err) {
      return { content: '', error: String(err) };
    }
  }

  async saveFileContent(
    filePath: string,
    content: string,
  ): Promise<{ success: boolean; error?: string }> {
    try {
      const uri = filePath.startsWith('file://')
        ? this.workspaceUri(vscode.Uri.parse(filePath).fsPath)
        : this.workspaceUri(filePath);
      const buffer = Buffer.from(content, 'utf8');
      await vscode.workspace.fs.writeFile(uri, buffer);
      return { success: true };
    } catch (err) {
      return { success: false, error: String(err) };
    }
  }

  async getActiveEditorContent(): Promise<{
    content: string;
    fileName: string;
    languageId: string;
    error?: string;
  }> {
    const editor = vscode.window.activeTextEditor;
    if (!editor) {
      return { content: '', fileName: '', languageId: '', error: 'No active editor' };
    }
    return {
      content: editor.document.getText(),
      fileName: editor.document.fileName.split('/').pop() || editor.document.fileName,
      languageId: editor.document.languageId,
    };
  }

  async insertText(
    text: string,
    position?: { line: number; character: number },
  ): Promise<{ success: boolean; error?: string }> {
    try {
      const editor = vscode.window.activeTextEditor;
      if (!editor) {
        return { success: false, error: 'No active editor' };
      }
      await editor.edit((editBuilder) => {
        const pos = position
          ? new vscode.Position(position.line, position.character)
          : editor.selection.active;
        editBuilder.insert(pos, text);
      });
      return { success: true };
    } catch (err) {
      return { success: false, error: String(err) };
    }
  }

  // ─── Copilot Availability ───────────────────────────────────

  async isCopilotAvailable(): Promise<boolean> {
    try {
      const copilotExt = vscode.extensions.getExtension('GitHub.copilot-chat');
      const copilotComplete = vscode.extensions.getExtension('GitHub.copilot');
      if (copilotExt || copilotComplete) {
        const models = await vscode.lm.selectChatModels({ vendor: 'copilot' });
        return models.length > 0;
      }
      // Even without Copilot, check for any available models (BYOK)
      const models = await vscode.lm.selectChatModels({});
      return models.length > 0;
    } catch {
      return false;
    }
  }

  async getCachedModelCount(): Promise<number> {
    if (this.cachedModels.length === 0) {
      await this.listModels();
    }
    return this.cachedModels.length;
  }
}
