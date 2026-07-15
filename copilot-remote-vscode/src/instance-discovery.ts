import * as vscode from 'vscode';
import { WebSocket } from 'ws';
import type { InstanceInfo, StatusInfo } from './types';

/**
 * Discovers other VS Code windows running the Copilot Remote extension
 * by scanning a port range and authenticating via WebSocket.
 */
export class InstanceDiscovery implements vscode.Disposable {
  private readonly _onDidChangeInstances = new vscode.EventEmitter<InstanceInfo[]>();
  public readonly onDidChangeInstances = this._onDidChangeInstances.event;

  private instances: InstanceInfo[] = [];
  private interval: NodeJS.Timeout | undefined;
  private disposed = false;

  constructor(
    private readonly outputChannel: vscode.OutputChannel,
    private readonly getCurrentPort: () => number,
    private readonly getCurrentInstanceId: () => string,
    private readonly isConnected?: (instanceId: string) => boolean,
  ) {}

  /**
   * Returns the last set of discovered instances (excluding this window).
   */
  getInstances(): InstanceInfo[] {
    return [...this.instances];
  }

  /**
   * Starts periodic discovery and runs an initial scan.
   */
  start(): void {
    if (this.disposed) {
      return;
    }
    this.refresh();
    if (!this.interval) {
      this.interval = setInterval(() => this.refresh(), 5000);
    }
  }

  /**
   * Runs a single discovery scan and fires change events.
   */
  async refresh(): Promise<void> {
    if (this.disposed) {
      return;
    }
    try {
      const found = await this.scan();
      const currentId = this.getCurrentInstanceId();
      this.instances = found.filter((info) => info.instanceId !== currentId);
      this._onDidChangeInstances.fire([...this.instances]);
    } catch (err) {
      this.log(`Discovery refresh failed: ${err}`, 'error');
    }
  }

  dispose(): void {
    this.disposed = true;
    if (this.interval) {
      clearInterval(this.interval);
      this.interval = undefined;
    }
  }

  private async scan(): Promise<InstanceInfo[]> {
    const config = vscode.workspace.getConfiguration('copilotRemote');
    const basePort = config.get<number>('port') || 9876;
    const key = config.get<string>('key') || '';
    const currentPort = this.getCurrentPort();

    const ports: number[] = [];
    for (let offset = 0; offset <= 10; offset++) {
      const port = basePort + offset;
      if (port !== currentPort) {
        ports.push(port);
      }
    }

    const results = await Promise.all(ports.map((port) => this.probePort(port, key)));
    return results.filter((info): info is InstanceInfo => info !== undefined);
  }

  private async probePort(port: number, key: string): Promise<InstanceInfo | undefined> {
    return new Promise<InstanceInfo | undefined>((resolve) => {
      let settled = false;
      let ws: WebSocket | undefined;

      const timer = setTimeout(() => {
        if (!settled) {
          settled = true;
          safeClose(ws);
          resolve(undefined);
        }
      }, 1500);

      const cleanupAndResolve = (result?: InstanceInfo): void => {
        if (!settled) {
          settled = true;
          clearTimeout(timer);
          safeClose(ws);
          resolve(result);
        }
      };

      try {
        ws = new WebSocket(`ws://127.0.0.1:${port}`);
      } catch {
        cleanupAndResolve(undefined);
        return;
      }

      ws.on('open', () => {
        ws?.send(JSON.stringify({ type: 'auth', key }));
      });

      ws.on('message', (data: Buffer | ArrayBuffer | Buffer[]) => {
        const text = bufferToString(data);
        try {
          const msg = JSON.parse(text) as { type: string; [key: string]: unknown };
          if (msg.type === 'authResult') {
            if (msg.success !== true) {
              cleanupAndResolve(undefined);
              return;
            }
            ws?.send(JSON.stringify({ type: 'getStatus' }));
          } else if (msg.type === 'status' && msg.status && typeof msg.status === 'object') {
            const status = msg.status as StatusInfo;
            const host = status.host === '0.0.0.0' ? '127.0.0.1' : status.host;
            const info: InstanceInfo = {
              instanceId: status.instanceId,
              workspaceName: status.workspaceName,
              windowTitle: status.windowTitle,
              host,
              port,
              isRunning: true,
              isPrimary: status.isPrimary,
              isLocal: true,
              pid: status.pid,
              connected: this.isConnected ? this.isConnected(status.instanceId) : false,
            };
            cleanupAndResolve(info);
          }
        } catch {
          // Ignore malformed messages
        }
      });

      ws.on('error', () => cleanupAndResolve(undefined));
      ws.on('close', () => cleanupAndResolve(undefined));
    });
  }

  private log(message: string, level: 'info' | 'warn' | 'error' = 'info'): void {
    const prefix = '[Copilot Remote]';
    const levelTag = level === 'error' ? '[ERROR] ' : level === 'warn' ? '[WARN] ' : '';
    this.outputChannel.appendLine(`${prefix} ${levelTag}${message}`);
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
