import * as vscode from 'vscode';
import * as net from 'net';
import * as crypto from 'crypto';
import { WebSocketServer, WebSocket } from 'ws';
import { CopilotApi } from './copilot-api';
import { InstanceDiscovery } from './instance-discovery';
import { InstanceProxy } from './instance-proxy';
import type { ClientMessage, ServerMessage } from './types';
import { FcmPush } from './fcm-push';

interface AuthenticatedClient {
  ws: WebSocket;
  clientId: string;
  authenticated: boolean;
  authFailures: number;
}

/**
 * Check if a TCP port is already in use.
 */
function isPortInUse(port: number, host: string): Promise<boolean> {
  return new Promise((resolve) => {
    const tester = net.createServer()
      .once('error', () => resolve(true))
      .once('listening', () => {
        tester.close(() => resolve(false));
      })
      .listen(port, host);
  });
}

/**
 * WebSocket server that exposes Copilot API functionality to remote clients.
 * Supports multi-window: each VS Code window gets its own server instance
 * with automatic port increment to avoid conflicts.
 */
export class CopilotRemoteServer {
  private readonly fcm: FcmPush;
  private wss: WebSocketServer | null = null;
  private clients: Map<WebSocket, AuthenticatedClient> = new Map();
  private copilotApi: CopilotApi;
  private startTime: number = 0;
  private activeRequests: Map<string, vscode.CancellationTokenSource> = new Map();
  private suppressedBridgeRequests = new Map<string, string>();
  private bridgeEventsSubscription: vscode.Disposable | undefined;
  private proxySubscriptions = new Map<WebSocket, { instanceId: string; disposable: vscode.Disposable }>();

  // ─── Multi-window instance identity ──────────────────────
  readonly instanceId: string;
  private actualPort: number = 0;
  private actualHost: string = '';

  constructor(
    private outputChannel: vscode.OutputChannel,
    private discovery: InstanceDiscovery,
    private proxyManager: InstanceProxy,
    context: vscode.ExtensionContext,
  ) {
    this.copilotApi = new CopilotApi();
    this.fcm = new FcmPush(context.secrets);
    // Generate a stable instance ID for this VS Code window.
    // Combines PID + workspace name hash so it's deterministic across restarts of the same workspace.
    const wsName = vscode.workspace.name || 'untitled';
    const wsHash = crypto.createHash('md5').update(wsName).digest('hex').slice(0, 8);
    this.instanceId = `vscode-${process.pid}-${wsHash}`;
    context.subscriptions.push(vscode.workspace.onDidChangeWorkspaceFolders(() => {
      void this.handleWorkspaceChanged();
    }));
  }

  get isRunning(): boolean {
    return this.wss !== null;
  }

  get port(): number {
    return this.actualPort;
  }

  get connectedClients(): number {
    let count = 0;
    for (const client of this.clients.values()) {
      if (client.authenticated) count++;
    }
    return count;
  }

  /**
   * Start the WebSocket server. If the configured port is already in use
   * (e.g. another VS Code window is running), automatically try the next port.
   * Tries up to maxRetries ports before giving up.
   */
  async start(port: number, host: string, maxRetries: number = 10): Promise<void> {
    if (this.wss) {
      await this.stop();
    }
  this.bridgeEventsSubscription?.dispose();
  this.bridgeEventsSubscription = this.copilotApi.subscribeToPatchedBridgeEvents(
    event => {
    this.broadcastBridgeEvent(event as ServerMessage);
    },
    error => this.log(`Patched bridge event stream disconnected: ${error.message}`, 'warn'),
  );

    // Find an available port, starting from the configured one
    let chosenPort = port;
    let attempt = 0;
    while (attempt < maxRetries) {
      const inUse = await isPortInUse(chosenPort, host);
      if (!inUse) break;
      this.log(`Port ${chosenPort} is in use (likely another VS Code window), trying ${chosenPort + 1}...`);
      chosenPort++;
      attempt++;
    }

    if (attempt >= maxRetries) {
      throw new Error(
        `Could not find an available port after ${maxRetries} attempts (tried ${port}–${chosenPort - 1}). ` +
        `Close another VS Code window or change the configured port.`
      );
    }

    return new Promise((resolve, reject) => {
      try {
        this.wss = new WebSocketServer({ port: chosenPort, host });

        this.wss.on('connection', (ws: WebSocket, req) => {
          const clientIp = req.socket.remoteAddress;
          this.log(`New connection from ${clientIp} (total: ${this.clients.size + 1})`);

          const client: AuthenticatedClient = {
            ws,
            clientId: `${Date.now()}-${Math.random().toString(36).slice(2, 9)}`,
            authenticated: false,
            authFailures: 0,
          };
          this.clients.set(ws, client);

          ws.on('message', (data: Buffer) => {
            this.handleMessage(ws, data).catch((err) => {
              this.log(`Error handling message: ${err}`, 'error');
            });
          });

          ws.on('close', (code: number, reason: Buffer) => {
            const wasAuthenticated = client.authenticated;
            const reasonStr = reason ? reason.toString() : 'none';
            this.log(`Client disconnected (${clientIp}, code=${code}, reason=${reasonStr}, wasAuthenticated=${wasAuthenticated})`);
            this.clients.delete(ws);
            this.proxySubscriptions.get(ws)?.disposable.dispose();
            this.proxySubscriptions.delete(ws);
            for (const [requestId, tokenSource] of this.activeRequests) {
              if (requestId.startsWith(client.clientId)) {
                tokenSource.cancel();
                this.activeRequests.delete(requestId);
              }
            }
          });

          ws.on('error', (err: Error) => {
            this.log(`Client WebSocket error: ${err.message}`, 'error');
          });

          this.send(ws, {
            type: 'log',
            level: 'info',
            message: `Connected to Copilot Remote (instance: ${this.instanceId}, workspace: ${vscode.workspace.name || 'Untitled'}). Please authenticate.`,
          });
        });

        this.wss.on('error', (err: Error) => {
          this.log(`Server error: ${err.message}`, 'error');
          this.log(`Server error stack: ${err.stack}`, 'error');
          reject(err);
        });

        this.wss.on('headers', (headers, req) => {
          this.log(`Incoming connection request from ${req.socket.remoteAddress}:${req.socket.remotePort}`);
        });

        this.wss.on('listening', () => {
          this.startTime = Date.now();
          this.actualPort = chosenPort;
          this.actualHost = host;
          const wsName = vscode.workspace.name || 'Untitled';
          if (chosenPort !== port) {
            this.log(`Server started on ${host}:${chosenPort} (auto-incremented from ${port} for workspace "${wsName}")`);
          } else {
            this.log(`Server started on ${host}:${chosenPort} (workspace: "${wsName}")`);
          }
          this.discovery.refresh().catch((err) => {
            this.log(`Instance discovery refresh failed: ${err}`, 'error');
          });
          resolve();
        });
      } catch (err) {
        reject(err);
      }
    });
  }

  async stop(): Promise<void> {
  this.bridgeEventsSubscription?.dispose();
  this.bridgeEventsSubscription = undefined;
    for (const tokenSource of this.activeRequests.values()) {
      tokenSource.cancel();
    }
    this.activeRequests.clear();

    for (const client of this.clients.values()) {
      client.ws.close();
    }
    for (const subscription of this.proxySubscriptions.values()) {
      subscription.disposable.dispose();
    }
    this.proxySubscriptions.clear();
    this.clients.clear();

    if (this.wss) {
      await new Promise<void>((resolve) => {
        this.wss!.close(() => resolve());
      });
      this.wss = null;
    }
    this.actualPort = 0;
    this.log('Server stopped');
  }

  private async handleMessage(ws: WebSocket, data: Buffer): Promise<void> {
    let message: ClientMessage;
    try {
      message = JSON.parse(data.toString());
    } catch {
      this.send(ws, { type: 'error', message: 'Invalid JSON message' });
      return;
    }

    const client = this.clients.get(ws);
    if (!client) return;

    if (!client.authenticated && message.type !== 'auth' && message.type !== 'ping') {
      this.send(ws, {
        type: 'error',
        message: 'Not authenticated. Send auth message with your key first.',
        code: 'NOT_AUTHENTICATED',
      });
      return;
    }

    switch (message.type) {
      case 'auth':
        await this.handleAuth(ws, client, message.key, message.clientId);
        break;

      case 'ping':
        this.send(ws, { type: 'pong', timestamp: Date.now() });
        break;

      case 'listModels':
        await this.handleListModels(ws);
        break;
      case 'registerPushToken':
        try { await this.fcm.register(message.token); this.send(ws, { type: 'pushTokenRegistered', success: true, configured: this.fcm.isConfigured() }); }
        catch (err) { this.send(ws, { type: 'pushTokenRegistered', success: false, error: err instanceof Error ? err.message : String(err) }); }
        break;
      case 'unregisterPushToken':
        try { await this.fcm.unregister(message.token); this.send(ws, { type: 'pushTokenUnregistered', success: true }); }
        catch (err) { this.send(ws, { type: 'pushTokenUnregistered', success: false, error: err instanceof Error ? err.message : String(err) }); }
        break;

      case 'chat':
        await this.handleChat(ws, client, message);
        break;

      case 'cancelChat':
        this.handleCancelChat(ws, client, message.requestId);
        break;

      case 'listCommands':
        await this.handleListCommands(ws);
        break;

      case 'executeCommand':
        await this.handleExecuteCommand(ws, message.commandId, message.args);
        break;

      case 'getChatHistory':
        this.send(ws, { type: 'chatHistory', history: this.copilotApi.getHistory() });
        break;

      case 'clearHistory':
        this.copilotApi.clearHistory();
        this.send(ws, { type: 'historyCleared' });
        break;

      case 'listNativeChatSessions': {
        try {
          // A conversation list belongs to the VS Code window that serves it.
          // Defaulting here also keeps older Android clients workspace-safe.
          const workspace = message.workspace?.trim() || vscode.workspace.name || undefined;
          const result = await this.copilotApi.listNativeChatSessions(message.offset, message.limit, workspace);
          this.send(ws, { type: 'nativeChatSessions', workspace, sessions: result.sessions, total: result.total, offset: result.offset, limit: result.limit, hasMore: result.hasMore, nextOffset: result.nextOffset, warning: result.warning });
        } catch (err) {
          this.send(ws, { type: 'error', message: err instanceof Error ? err.message : String(err), code: 'REMOTE_BRIDGE_SESSIONS_FAILED' });
        }
        break;
      }

      case 'getNativeChatSession': {
        try {
          const result = await this.copilotApi.getNativeChatSession(message.sessionId, message.before, message.limit);
          if (result.session) {
            this.send(ws, { type: 'nativeChatSession', session: result.session, messagePage: result.messagePage, warning: result.warning });
          } else {
            this.send(ws, { type: 'error', message: result.warning || `Native chat session not found: ${message.sessionId}` });
          }
        } catch (err) {
          this.send(ws, { type: 'error', message: err instanceof Error ? err.message : String(err), code: 'REMOTE_BRIDGE_SESSION_FAILED' });
        }
        break;
      }

      case 'getActiveChatSession': {
        try {
          this.send(ws, { type: 'activeChatSession', ...(await this.copilotApi.getActiveChatSession()) } as ServerMessage);
        } catch (err) {
          this.send(ws, { type: 'error', message: err instanceof Error ? err.message : String(err), code: 'REMOTE_BRIDGE_ACTIVE_SESSION_FAILED' });
        }
        break;
      }

      case 'getActiveChatTodos': {
        try {
          const result = await this.copilotApi.getActiveChatTodos();
          this.send(ws, {
            type: 'todoUpdated',
            items: Array.isArray(result.items) ? result.items : [],
            sessionResource: typeof result.sessionResource === 'string' ? result.sessionResource : undefined,
          } as ServerMessage);
        } catch (err) {
          const message = err instanceof Error ? err.message : String(err);
          if (/no active copilot chat panel session/i.test(message)) {
            this.send(ws, { type: 'todoUpdated', items: [] } as ServerMessage);
          } else {
            this.send(ws, { type: 'error', message, code: 'REMOTE_BRIDGE_TODOS_FAILED' });
          }
        }
        break;
      }

      case 'listParticipants':
        await this.handleListParticipants(ws);
        break;

      case 'listExtensions':
        await this.handleListExtensions(ws);
        break;

      case 'toggleExtension':
        await this.handleToggleExtension(ws, message.extensionId, message.enable);
        break;

      case 'listMcpServers':
        await this.handleListMcpServers(ws);
        break;

      case 'addMcpServer':
        await this.handleAddMcpServer(ws, message.config);
        break;

      case 'removeMcpServer':
        await this.handleRemoveMcpServer(ws, message.name);
        break;

      case 'listSkills':
        await this.handleListSkills(ws);
        break;

      case 'invokeSkill':
        await this.handleInvokeSkill(ws, message.skillId, message.args);
        break;

      case 'getWorkspaceInfo':
        const info = await this.copilotApi.getWorkspaceInfo();
        this.send(ws, { type: 'workspaceInfo', info });
        break;

      case 'getOpenEditors':
        const editors = await this.copilotApi.getOpenEditors();
        this.send(ws, { type: 'openEditors', editors });
        break;

      case 'getFileContent':
        const fileResult = await this.copilotApi.getFileContent(message.filePath);
        this.send(ws, {
          type: 'fileContent',
          filePath: message.filePath,
          content: fileResult.content,
          error: fileResult.error,
        });
        break;

      case 'saveFileContent':
        const saveResult = await this.copilotApi.saveFileContent(message.filePath, message.content);
        this.send(ws, {
          type: 'fileSaved',
          filePath: message.filePath,
          success: saveResult.success,
          error: saveResult.error,
        });
        break;

      case 'getStatus':
        await this.handleGetStatus(ws);
        break;

      case 'sendToCopilotChat':
        const chatResult = await this.copilotApi.sendToCopilotChat(
          message.prompt,
          message.participant,
          message.command,
        );
        this.send(ws, {
          type: 'copilotChatSent',
          success: chatResult.success,
          error: chatResult.error,
        });
        break;

      case 'getActiveEditorContent':
        const editorContent = await this.copilotApi.getActiveEditorContent();
        this.send(ws, {
          type: 'activeEditorContent',
          content: editorContent.content,
          fileName: editorContent.fileName,
          languageId: editorContent.languageId,
          error: editorContent.error,
        });
        break;

      case 'insertText':
        const insertResult = await this.copilotApi.insertText(message.text, message.position);
        this.send(ws, {
          type: 'textInserted',
          success: insertResult.success,
          error: insertResult.error,
        });
        break;

      case 'listTerminals':
        this.send(ws, { type: 'terminals', terminals: await this.copilotApi.listTerminals() });
        break;

      case 'createTerminal': {
        const terminal = await this.copilotApi.createTerminal(message.name, message.cwd);
        this.send(ws, { type: 'terminalCreated', terminal });
        break;
      }

      case 'closeTerminal': {
        const success = await this.copilotApi.closeTerminal(message.terminalId);
        this.send(ws, { type: 'terminalClosed', terminalId: message.terminalId, success, error: success ? undefined : 'Terminal not found' });
        break;
      }

      case 'executeTerminal': {
        try {
          const output = await this.copilotApi.executeTerminal(message.terminalId, message.command);
          this.send(ws, { type: 'terminalOutput', terminalId: message.terminalId, command: message.command, output });
        } catch (err) {
          this.send(ws, { type: 'terminalOutput', terminalId: message.terminalId, command: message.command, output: '', error: err instanceof Error ? err.message : String(err) });
        }
        break;
      }

      case 'searchFiles':
        this.send(ws, { type: 'searchResults', kind: 'files', results: await this.copilotApi.searchFiles(message.query, message.include) });
        break;
      case 'searchText':
        this.send(ws, { type: 'searchResults', kind: 'text', results: await this.copilotApi.searchText(message.query, message.include) });
        break;
      case 'searchSymbols':
        this.send(ws, { type: 'searchResults', kind: 'symbols', results: await this.copilotApi.searchSymbols(message.query) });
        break;
      case 'listDirectory':
        this.send(ws, { type: 'directoryEntries', path: message.path || '', entries: await this.copilotApi.listDirectory(message.path) });
        break;
      case 'openFile':
        try { await this.copilotApi.openFile(message.filePath, message.line, message.character); this.send(ws, { type: 'fileOpened', filePath: message.filePath, success: true }); }
        catch (err) { this.send(ws, { type: 'fileOpened', filePath: message.filePath, success: false, error: err instanceof Error ? err.message : String(err) }); }
        break;
      case 'closeFile': {
        const success = await this.copilotApi.closeFile(message.filePath);
        this.send(ws, { type: 'fileClosed', filePath: message.filePath, success, error: success ? undefined : 'File is not open' });
        break;
      }
      case 'findDefinitions':
        this.send(ws, { type: 'searchResults', kind: 'definitions', results: await this.copilotApi.findLocations('definition', message.filePath, message.line, message.character) });
        break;
      case 'findReferences':
        this.send(ws, { type: 'searchResults', kind: 'references', results: await this.copilotApi.findLocations('references', message.filePath, message.line, message.character) });
        break;
      case 'getGitStatus':
        try { this.send(ws, { type: 'gitStatus', ...(await this.copilotApi.getGitStatus()) }); }
        catch (err) { this.send(ws, { type: 'gitStatus', branch: '', branches: [], changes: [], error: err instanceof Error ? err.message : String(err) }); }
        break;
      case 'getGitDiff':
        try { this.send(ws, { type: 'gitDiff', filePath: message.filePath, diff: await this.copilotApi.getGitDiff(message.filePath) }); }
        catch (err) { this.send(ws, { type: 'gitDiff', filePath: message.filePath, diff: '', error: err instanceof Error ? err.message : String(err) }); }
        break;
      case 'getGitHistory':
        try { this.send(ws, { type: 'gitHistory', commits: await this.copilotApi.getGitHistory(message.limit) }); }
        catch (err) { this.send(ws, { type: 'gitHistory', commits: [], error: err instanceof Error ? err.message : String(err) }); }
        break;
      case 'checkoutGitBranch':
      case 'createGitBranch':
      case 'commitGitChanges': {
        try {
          if (message.type === 'checkoutGitBranch') await this.copilotApi.checkoutGitBranch(message.branch);
          else if (message.type === 'createGitBranch') await this.copilotApi.createGitBranch(message.branch, message.checkout);
          else await this.copilotApi.commitGitChanges(message.message, message.all);
          this.send(ws, { type: 'gitOperation', operation: message.type, success: true });
        } catch (err) {
          this.send(ws, { type: 'gitOperation', operation: message.type, success: false, error: err instanceof Error ? err.message : String(err) });
        }
        break;
      }
      case 'listPullRequests':
        try { this.send(ws, { type: 'pullRequests', pullRequests: await this.copilotApi.listPullRequests(message.state) }); }
        catch (err) { this.send(ws, { type: 'pullRequests', pullRequests: [], error: err instanceof Error ? err.message : String(err) }); }
        break;
      case 'openPullRequest':
        try { this.send(ws, { type: 'pullRequestOperation', operation: message.type, success: true, url: await this.copilotApi.openPullRequest(message.number) }); }
        catch (err) { this.send(ws, { type: 'pullRequestOperation', operation: message.type, success: false, error: err instanceof Error ? err.message : String(err) }); }
        break;
      case 'createPullRequest':
        try { this.send(ws, { type: 'pullRequestOperation', operation: message.type, success: true, url: await this.copilotApi.createPullRequest(message.title, message.body, message.base, message.draft) }); }
        catch (err) { this.send(ws, { type: 'pullRequestOperation', operation: message.type, success: false, error: err instanceof Error ? err.message : String(err) }); }
        break;
      case 'setEditorSelection':
        try {
          await this.copilotApi.setEditorSelection(message.filePath, message.startLine, message.startCharacter, message.endLine, message.endCharacter);
          this.send(ws, { type: 'editorSelectionSet', filePath: message.filePath, success: true });
        } catch (err) { this.send(ws, { type: 'editorSelectionSet', filePath: message.filePath, success: false, error: err instanceof Error ? err.message : String(err) }); }
        break;
      case 'replaceInFiles':
        try { this.send(ws, { type: 'replaceResult', ...(await this.copilotApi.replaceInFiles(message.query, message.replacement, message.include)) }); }
        catch (err) { this.send(ws, { type: 'replaceResult', filesChanged: 0, replacements: 0, error: err instanceof Error ? err.message : String(err) }); }
        break;
      case 'captureWindow':
      case 'recordWindow':
        try {
          const result = await this.copilotApi.captureWindow(message.type === 'recordWindow' ? message.seconds : 0, message.type === 'recordWindow' ? message.fps : 1);
          this.send(ws, { type: 'windowCapture', ...result });
        } catch (err) { this.send(ws, { type: 'windowCapture', frames: [], intervalMs: 0, error: err instanceof Error ? err.message : String(err) }); }
        break;

      case 'listInstances':
        this.send(ws, { type: 'instances', instances: this.getAllInstances() });
        break;

      case 'proxy':
        try {
          const isStreamingChat = message.message.type === 'chat';
          await this.ensureProxySubscription(ws, message.targetInstanceId);
          const response = await this.proxyManager.proxy(message.targetInstanceId, message.message);
          if (!isStreamingChat) {
            this.send(ws, {
              type: 'proxyResult',
              targetInstanceId: message.targetInstanceId,
              message: response,
            });
          }
        } catch (err) {
          const error = err instanceof Error ? err.message : String(err);
          this.send(ws, {
            type: 'proxyResult',
            targetInstanceId: message.targetInstanceId,
            message: { type: 'error', message: error },
          });
        }
        break;

      default:
        this.send(ws, {
          type: 'error',
          message: `Unknown message type: ${(message as { type: string }).type}`,
        });
    }
  }

  // ─── Message Handlers ───────────────────────────────────────

  private async handleAuth(
    ws: WebSocket,
    client: AuthenticatedClient,
    key: string,
    clientId?: string,
  ): Promise<void> {
    const configKey = (vscode.workspace.getConfiguration('copilotRemote').get<string>('key') || '').trim();

    if (!configKey) {
      this.log(`Auth failed: server key not configured`, 'warn');
      this.send(ws, {
        type: 'authResult',
        success: false,
        message: 'Server key not configured. Set "copilotRemote.key" in VS Code settings.',
      });
        return;
      }
    const suppliedKey = key.trim();
    const supplied = Buffer.from(suppliedKey);
    const expected = Buffer.from(configKey);
    if (supplied.length === expected.length && crypto.timingSafeEqual(supplied, expected)) {
      client.authenticated = true;
      client.authFailures = 0;
      if (clientId) {
        client.clientId = clientId;
      }
      this.log(`Client authenticated: ${client.clientId}`);
      this.send(ws, {
        type: 'authResult',
        success: true,
        message: 'Authentication successful',
        serverVersion: '1.0.0',
        instanceId: this.instanceId,
        workspaceName: vscode.workspace.name || 'Untitled',
      });

      await this.handleGetStatus(ws);
    } else {
      client.authFailures++;
      this.log(`Authentication failed for client "${client.clientId}" — key mismatch (configured key length: ${configKey.length}, received key length: ${key.length})`, 'warn');
      this.send(ws, {
        type: 'authResult',
        success: false,
        message: 'Invalid key',
      });
      if (client.authFailures >= 5) ws.close(1008, 'Too many authentication failures');
    }
  }

  private async handleListModels(ws: WebSocket): Promise<void> {
    const models = await this.copilotApi.listModels();
    this.send(ws, { type: 'models', models });
  }

  private async handleChat(
    ws: WebSocket,
    client: AuthenticatedClient,
    message: { requestId: string; modelId?: string; messages: import('./types').ChatMessage[]; options?: import('./types').ChatOptions },
  ): Promise<void> {
    const requestKey = `${client.clientId}:${message.requestId}`;
    const tokenSource = new vscode.CancellationTokenSource();
    this.activeRequests.set(requestKey, tokenSource);

    try {
      await this.copilotApi.streamChatViaPatchedBridge(
        message.requestId,
        message.modelId,
        message.messages,
        message.options,
        event => {
          const bridgeRequestId = typeof event.officialRequestId === 'string' ? event.officialRequestId : '';
          if (bridgeRequestId) this.suppressedBridgeRequests.set(bridgeRequestId, client.clientId);
          this.send(ws, event as ServerMessage);
        },
        tokenSource.token,
      );
    } catch (err) {
      const error = err instanceof Error ? err : new Error(String(err));
      this.log(`Chat error: ${error.message}`, 'error');
      this.send(ws, {
        type: 'chatError',
        requestId: message.requestId,
        error: error.message,
        code: error instanceof vscode.LanguageModelError ? error.code : undefined,
      });
    } finally {
      this.activeRequests.delete(requestKey);
      for (const [bridgeRequestId, clientId] of this.suppressedBridgeRequests) {
        if (clientId === client.clientId) this.suppressedBridgeRequests.delete(bridgeRequestId);
      }
    }
  }

  private handleCancelChat(ws: WebSocket, client: AuthenticatedClient, requestId: string): void {
    const requestKey = `${client.clientId}:${requestId}`;
    const tokenSource = this.activeRequests.get(requestKey);
    if (tokenSource) {
      tokenSource.cancel();
      this.activeRequests.delete(requestKey);
    }
    this.send(ws, { type: 'chatCancelled', requestId });
  }

  private async handleListCommands(ws: WebSocket): Promise<void> {
    const commands = await this.copilotApi.listCommands();
    this.send(ws, { type: 'commands', commands });
  }

  private async handleExecuteCommand(
    ws: WebSocket,
    commandId: string,
    args?: unknown[],
  ): Promise<void> {
    try {
      const result = await this.copilotApi.executeCommand(commandId, args);
      this.send(ws, { type: 'commandResult', commandId, result });
    } catch (err) {
      this.send(ws, {
        type: 'commandResult',
        commandId,
        result: null,
        error: String(err),
      });
    }
  }

  private async handleListParticipants(ws: WebSocket): Promise<void> {
    const participants = await this.copilotApi.listParticipants();
    this.send(ws, { type: 'participants', participants });
  }

  private async handleListExtensions(ws: WebSocket): Promise<void> {
    const extensions = await this.copilotApi.listExtensions();
    this.send(ws, { type: 'extensions', extensions });
  }

  private async handleToggleExtension(
    ws: WebSocket,
    extensionId: string,
    enable: boolean,
  ): Promise<void> {
    const result = await this.copilotApi.toggleExtension(extensionId, enable);
    this.send(ws, {
      type: 'extensionToggled',
      extensionId,
      success: result.success,
      error: result.error,
    });
  }

  private async handleListMcpServers(ws: WebSocket): Promise<void> {
    const servers = await this.copilotApi.listMcpServers();
    this.send(ws, { type: 'mcpServers', servers });
  }

  private async handleAddMcpServer(
    ws: WebSocket,
    config: import('./types').McpServerInfo,
  ): Promise<void> {
    const result = await this.copilotApi.addMcpServer(config);
    this.send(ws, { type: 'mcpServerAdded', success: result.success, error: result.error });
  }

  private async handleRemoveMcpServer(ws: WebSocket, name: string): Promise<void> {
    const result = await this.copilotApi.removeMcpServer(name);
    this.send(ws, { type: 'mcpServerRemoved', success: result.success, error: result.error });
  }

  private async handleListSkills(ws: WebSocket): Promise<void> {
    const skills = await this.copilotApi.listSkills();
    this.send(ws, { type: 'skills', skills });
  }

  private async handleInvokeSkill(
    ws: WebSocket,
    skillId: string,
    args?: Record<string, unknown>,
  ): Promise<void> {
    const result = await this.copilotApi.invokeSkill(skillId, args);
    this.send(ws, {
      type: 'skillInvoked',
      skillId,
      success: result.success,
      result: result.result,
      error: result.error,
    });
  }

  private async handleGetStatus(ws: WebSocket): Promise<void> {
    const copilotAvailable = await this.copilotApi.isCopilotAvailable();
    const modelCount = await this.copilotApi.getCachedModelCount();

    const workspaceFolders = (vscode.workspace.workspaceFolders || []).map(
      (f) => f.uri.fsPath,
    );

    // Determine if this is the primary (first) instance by checking if we're on the base port
    const configPort = vscode.workspace.getConfiguration('copilotRemote').get<number>('port') || 9876;
    const isPrimary = this.actualPort === configPort;

    this.send(ws, {
      type: 'status',
      status: {
        serverRunning: this.isRunning,
        port: this.actualPort,
        host: this.actualHost,
        connectedClients: this.connectedClients,
        copilotAvailable,
        modelCount,
        workspaceName: vscode.workspace.name || 'No Workspace',
        uptime: this.startTime ? Date.now() - this.startTime : 0,
        instanceId: this.instanceId,
        windowTitle: vscode.workspace.name || 'Untitled',
        workspaceFolders,
        pid: process.pid,
        isPrimary,
      },
    });
  }

  private async handleWorkspaceChanged(): Promise<void> {
    this.log(`Workspace changed to "${vscode.workspace.name || 'No Workspace'}"; refreshing connected clients.`);
    await this.discovery.refresh().catch(err => this.log(`Instance discovery refresh failed: ${err}`, 'warn'));
    for (const client of this.clients.values()) {
      if (client.authenticated) await this.handleGetStatus(client.ws);
    }
    this.broadcast({ type: 'instances', instances: this.getAllInstances() });
  }

  // ─── Utilities ──────────────────────────────────────────────

  private send(ws: WebSocket, message: ServerMessage): void {
    if (ws.readyState === WebSocket.OPEN) {
      ws.send(JSON.stringify(message));
    }
  }

  private async ensureProxySubscription(ws: WebSocket, instanceId: string): Promise<void> {
    const existing = this.proxySubscriptions.get(ws);
    if (existing?.instanceId === instanceId) return;
    existing?.disposable.dispose();
    const disposable = await this.proxyManager.subscribe(instanceId, message => {
      if (!this.isLiveProxyEvent(message)) return;
      this.send(ws, { type: 'proxyResult', targetInstanceId: instanceId, message });
    });
    this.proxySubscriptions.set(ws, { instanceId, disposable });
  }

  private isLiveProxyEvent(message: ServerMessage): boolean {
    return message.type === 'activeSessionChanged'
      || message.type === 'activeChatSession'
      || message.type === 'chatStart'
      || message.type === 'chatDelta'
      || message.type === 'chatThinkingDelta'
      || message.type === 'chatUsage'
      || message.type === 'todoUpdated'
      || message.type === 'toolUpdated'
      || message.type === 'chatDone'
      || message.type === 'chatError'
      || message.type === 'chatCancelled';
  }

  private broadcastBridgeEvent(message: ServerMessage): void {
    const requestId = 'requestId' in message && typeof message.requestId === 'string' ? message.requestId : '';
    for (const client of this.clients.values()) {
      if (!client.authenticated) continue;
      // The submitting client already receives its request over the NDJSON response.
      if ([...this.activeRequests.keys()].some(key => key.startsWith(`${client.clientId}:`))) continue;
      if (requestId && this.activeRequests.has(`${client.clientId}:${requestId}`)) continue;
      if (requestId && this.suppressedBridgeRequests.get(requestId) === client.clientId) continue;
      this.send(client.ws, message);
    }
  }

  private getAllInstances(): import('./types').InstanceInfo[] {
    const configPort = vscode.workspace.getConfiguration('copilotRemote').get<number>('port') || 9876;
    const isPrimary = this.actualPort === configPort;
    const current: import('./types').InstanceInfo = {
      instanceId: this.instanceId,
      workspaceName: vscode.workspace.name || 'No Workspace',
      windowTitle: vscode.workspace.name || 'Untitled',
      host: this.actualHost === '0.0.0.0' ? '127.0.0.1' : this.actualHost,
      port: this.actualPort,
      isRunning: this.isRunning,
      isPrimary,
      isLocal: true,
      pid: process.pid,
      connected: true,
    };
    return [current, ...this.discovery.getInstances()];
  }

  broadcast(message: ServerMessage): void {
    for (const client of this.clients.values()) {
      if (client.authenticated) {
        this.send(client.ws, message);
      }
    }
    const push = message.type === 'eventNotification'
      ? [message.title, message.message]
      : this.connectedClients === 0 && message.type === 'chatDone'
        ? ['Copilot finished', 'The response is ready']
        : this.connectedClients === 0 && message.type === 'chatError'
          ? ['Copilot error', message.error]
          : undefined;
    if (push) void this.fcm.send(push[0], push[1]).catch(err => this.log(`FCM push failed: ${err instanceof Error ? err.message : String(err)}`, 'warn'));
  }

  private log(message: string, level: 'info' | 'warn' | 'error' = 'info'): void {
    const prefix = '[Copilot Remote]';
    const levelTag = level === 'error' ? '[ERROR] ' : level === 'warn' ? '[WARN] ' : '';
    this.outputChannel.appendLine(`${prefix} ${levelTag}${message}`);
  }
}
