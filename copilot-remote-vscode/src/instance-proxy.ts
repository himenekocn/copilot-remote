import * as vscode from 'vscode';
import { WebSocket } from 'ws';
import type { ClientMessage, ServerMessage } from './types';
import type { InstanceInfo } from './types';
import type { InstanceDiscovery } from './instance-discovery';

interface ProxyClient {
  ws: WebSocket;
  authenticated: boolean;
}

/**
 * Maintains persistent WebSocket connections to discovered instances and
 * forwards (proxies) client messages to them.
 */
export class InstanceProxy implements vscode.Disposable {
  private sockets = new Map<string, ProxyClient>();
  private connecting = new Map<string, Promise<WebSocket>>();
  private disposed = false;

  constructor(
    private readonly outputChannel: vscode.OutputChannel,
    private readonly discovery: InstanceDiscovery,
  ) {}

  /**
   * Returns true if there is an open, authenticated connection to the instance.
   */
  isConnected(instanceId: string): boolean {
    const client = this.sockets.get(instanceId);
    return !!client && client.authenticated && client.ws.readyState === WebSocket.OPEN;
  }

  /**
   * Establishes a connection to the given instance. Useful for the TreeView
   * "connect" command.
   */
  async connect(instanceId: string): Promise<void> {
    if (this.disposed) {
      throw new Error('InstanceProxy has been disposed');
    }
    await this.ensureConnection(instanceId);
  }

  async subscribe(
    instanceId: string,
    listener: (message: ServerMessage) => void,
  ): Promise<vscode.Disposable> {
    const client = await this.ensureConnection(instanceId);
    const onMessage = (data: Buffer | ArrayBuffer | Buffer[]): void => {
      try {
        const message = JSON.parse(bufferToString(data)) as ServerMessage;
        if (message.type !== 'authResult' && message.type !== 'pong' && message.type !== 'log') {
          listener(message);
        }
      } catch {
        // Ignore malformed messages.
      }
    };
    client.ws.on('message', onMessage);
    return { dispose: () => client.ws.removeListener('message', onMessage) };
  }

  /**
   * Forwards a message to the target instance and resolves with the first
   * non-control response received from it.
   */
  async proxy(
    instanceId: string,
    message: Omit<ClientMessage, 'type'> & { type: string },
  onEvent?: (message: ServerMessage) => void,
  ): Promise<ServerMessage> {
    if (this.disposed) {
      throw new Error('InstanceProxy has been disposed');
    }
    const client = await this.ensureConnection(instanceId);

    return new Promise<ServerMessage>((resolve, reject) => {
      let settled = false;
      const timer = setTimeout(() => {
        if (!settled) {
          settled = true;
          cleanup();
          reject(new Error('Proxy response timeout'));
        }
      }, message.type === 'chat' ? 10 * 60 * 1000 : message.type === 'executeTerminal' ? 305_000 : 10_000);

      const cleanup = (): void => {
        clearTimeout(timer);
        client.ws.removeListener('message', onMessage);
        client.ws.removeListener('error', onError);
        client.ws.removeListener('close', onClose);
      };

      const onMessage = (data: Buffer | ArrayBuffer | Buffer[]): void => {
        try {
          const msg = JSON.parse(bufferToString(data)) as ServerMessage;
          if (
            'type' in msg &&
            (msg.type === 'authResult' || msg.type === 'pong' || msg.type === 'log')
          ) {
            return;
          }
            if (message.type === 'chat' && 'requestId' in message && 'requestId' in msg && msg.requestId !== message.requestId) {
            return;
            }
          if (!this.isResponseFor(message, msg)) {
            return;
          }
          onEvent?.(msg);
          const streaming = message.type === 'chat';
          const terminal = msg.type === 'chatDone' || msg.type === 'chatError' || msg.type === 'chatCancelled' || msg.type === 'error';
          if (!settled && (!streaming || terminal)) {
            settled = true;
            cleanup();
            resolve(msg);
          }
        } catch {
          // Ignore malformed messages
        }
      };

      const onError = (err: Error): void => {
        if (!settled) {
          settled = true;
          cleanup();
          reject(err);
        }
      };

      const onClose = (): void => {
        if (!settled) {
          settled = true;
          cleanup();
          reject(new Error('Connection closed while waiting for proxy response'));
        }
      };

      client.ws.on('message', onMessage);
      client.ws.on('error', onError);
      client.ws.on('close', onClose);
      client.ws.send(JSON.stringify(message));
    });
  }

  private isResponseFor(message: Omit<ClientMessage, 'type'> & { type: string }, response: ServerMessage): boolean {
    if (response.type === 'error') return true;
    switch (message.type) {
      case 'chat':
        return 'requestId' in message && 'requestId' in response && response.requestId === message.requestId;
      case 'cancelChat':
        return response.type === 'chatCancelled' && 'requestId' in message && response.requestId === message.requestId;
      case 'listModels': return response.type === 'models';
      case 'registerPushToken': return response.type === 'pushTokenRegistered';
      case 'unregisterPushToken': return response.type === 'pushTokenUnregistered';
      case 'listCommands': return response.type === 'commands';
      case 'getChatHistory': return response.type === 'chatHistory';
      case 'clearHistory': return response.type === 'historyCleared';
      case 'listNativeChatSessions': return response.type === 'nativeChatSessions';
      case 'getNativeChatSession': return response.type === 'nativeChatSession';
      case 'getActiveChatSession': return response.type === 'activeChatSession';
      case 'getActiveChatTodos': return response.type === 'todoUpdated';
      case 'listParticipants': return response.type === 'participants';
      case 'listExtensions': return response.type === 'extensions';
      case 'toggleExtension': return response.type === 'extensionToggled';
      case 'listMcpServers': return response.type === 'mcpServers';
      case 'addMcpServer': return response.type === 'mcpServerAdded';
      case 'removeMcpServer': return response.type === 'mcpServerRemoved';
      case 'listSkills': return response.type === 'skills';
      case 'invokeSkill': return response.type === 'skillInvoked';
      case 'getWorkspaceInfo': return response.type === 'workspaceInfo';
      case 'getOpenEditors': return response.type === 'openEditors';
      case 'getFileContent': return response.type === 'fileContent';
      case 'saveFileContent': return response.type === 'fileSaved';
      case 'getStatus': return response.type === 'status';
      case 'sendToCopilotChat': return response.type === 'copilotChatSent';
      case 'getActiveEditorContent': return response.type === 'activeEditorContent';
      case 'insertText': return response.type === 'textInserted';
      case 'listTerminals': return response.type === 'terminals';
      case 'createTerminal': return response.type === 'terminalCreated';
      case 'closeTerminal': return response.type === 'terminalClosed';
      case 'executeTerminal': return response.type === 'terminalOutput';
      case 'searchFiles':
      case 'searchText':
      case 'searchSymbols':
      case 'findDefinitions':
      case 'findReferences': return response.type === 'searchResults';
      case 'listDirectory': return response.type === 'directoryEntries';
      case 'openFile': return response.type === 'fileOpened';
      case 'closeFile': return response.type === 'fileClosed';
      case 'getGitStatus': return response.type === 'gitStatus';
      case 'getGitDiff': return response.type === 'gitDiff';
      case 'getGitHistory': return response.type === 'gitHistory';
      case 'checkoutGitBranch':
      case 'createGitBranch':
      case 'commitGitChanges': return response.type === 'gitOperation';
      case 'listPullRequests': return response.type === 'pullRequests';
      case 'openPullRequest':
      case 'createPullRequest': return response.type === 'pullRequestOperation';
      case 'setEditorSelection': return response.type === 'editorSelectionSet';
      case 'replaceInFiles': return response.type === 'replaceResult';
      case 'captureWindow':
      case 'recordWindow': return response.type === 'windowCapture';
      case 'listInstances': return response.type === 'instances';
      case 'ping': return response.type === 'pong';
      default: return true;
    }
  }

  dispose(): void {
    this.disposed = true;
    for (const client of this.sockets.values()) {
      safeClose(client.ws);
    }
    this.sockets.clear();
    this.connecting.clear();
  }

  private async ensureConnection(instanceId: string): Promise<ProxyClient> {
    const existing = this.sockets.get(instanceId);
    if (existing && existing.ws.readyState === WebSocket.OPEN) {
      return existing;
    }
    if (existing) {
      this.sockets.delete(instanceId);
      safeClose(existing.ws);
    }

    const pending = this.connecting.get(instanceId);
    if (pending) {
      await pending;
      const client = this.sockets.get(instanceId);
      if (!client) {
        throw new Error(`Failed to connect to ${instanceId}`);
      }
      return client;
    }

    const info = this.discovery.getInstances().find((i) => i.instanceId === instanceId);
    if (!info) {
      throw new Error(`Instance ${instanceId} not discovered`);
    }

    const promise = this.connectWs(info);
    this.connecting.set(instanceId, promise);
    try {
      const ws = await promise;
      const client: ProxyClient = { ws, authenticated: true };
      this.sockets.set(instanceId, client);

      ws.once('close', () => {
        this.sockets.delete(instanceId);
      });
      ws.on('error', (err) => {
        this.outputChannel.appendLine(
          `[Copilot Remote] Proxy error for ${instanceId}: ${err.message}`,
        );
      });

      return client;
    } finally {
      this.connecting.delete(instanceId);
    }
  }

  private async connectWs(info: InstanceInfo): Promise<WebSocket> {
    const config = vscode.workspace.getConfiguration('copilotRemote');
    const key = config.get<string>('key') || '';

    return new Promise<WebSocket>((resolve, reject) => {
      let settled = false;
      let ws: WebSocket;

      const timer = setTimeout(() => {
        if (!settled) {
          settled = true;
          safeClose(ws);
          reject(new Error(`Connection timeout to ${info.host}:${info.port}`));
        }
      }, 5000);

      const cleanup = (): void => {
        clearTimeout(timer);
      };

      const onMessage = (data: Buffer | ArrayBuffer | Buffer[]): void => {
        try {
          const msg = JSON.parse(bufferToString(data)) as { type: string; success?: boolean };
          if (msg.type === 'authResult') {
            if (msg.success) {
              settled = true;
              cleanup();
              resolve(ws);
            } else {
              settled = true;
              cleanup();
              safeClose(ws);
              reject(new Error(`Authentication failed for ${info.instanceId}`));
            }
          }
        } catch {
          // Ignore malformed messages
        }
      };

      const onError = (err: Error): void => {
        if (!settled) {
          settled = true;
          cleanup();
          safeClose(ws);
          reject(err);
        }
      };

      const onClose = (): void => {
        if (!settled) {
          settled = true;
          cleanup();
          reject(new Error(`Connection closed to ${info.host}:${info.port}`));
        }
      };

      try {
        ws = new WebSocket(`ws://${info.host}:${info.port}`);
      } catch (err) {
        settled = true;
        cleanup();
        reject(err);
        return;
      }

      ws.on('open', () => {
        ws.send(JSON.stringify({ type: 'auth', key }));
      });
      ws.on('message', onMessage);
      ws.on('error', onError);
      ws.on('close', onClose);
    });
  }
}

function bufferToString(data: Buffer | ArrayBuffer | Buffer[]): string {
  if (Buffer.isBuffer(data)) {
    return data.toString('utf8');
  }
  if (Array.isArray(data)) {
    return Buffer.concat(data).toString('utf8');
  }
  return Buffer.from(data).toString('utf8');
}

function safeClose(ws: WebSocket | undefined): void {
  try {
    ws?.close();
    ws?.terminate();
  } catch {
    // Ignore close errors
  }
}
