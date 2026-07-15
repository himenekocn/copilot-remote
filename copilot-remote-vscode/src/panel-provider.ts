import * as vscode from 'vscode';
import type { InstanceInfo } from './types';
import type { CopilotRemoteServer } from './server';
import type { InstanceDiscovery } from './instance-discovery';
import type { InstanceProxy } from './instance-proxy';

export class StatusActionItem extends vscode.TreeItem {
  constructor(
    label: string,
    description: string,
    icon: string,
    command: string,
  ) {
    super(label, vscode.TreeItemCollapsibleState.None);
    this.description = description;
    this.iconPath = new vscode.ThemeIcon(icon);
    this.command = { command, title: label };
  }
}

export class StatusInfoItem extends vscode.TreeItem {
  constructor(label: string, description: string, icon: string) {
    super(label, vscode.TreeItemCollapsibleState.None);
    this.description = description;
    this.iconPath = new vscode.ThemeIcon(icon);
  }
}

export type StatusTreeItem = StatusActionItem | StatusInfoItem;

/**
 * TreeDataProvider for the Copilot Remote server status view.
 */
export class StatusPanelProvider implements vscode.TreeDataProvider<StatusTreeItem> {
  private readonly _onDidChangeTreeData = new vscode.EventEmitter<StatusTreeItem | undefined | void>();
  public readonly onDidChangeTreeData = this._onDidChangeTreeData.event;

  constructor(private readonly server: CopilotRemoteServer) {}

  refresh(): void {
    this._onDidChangeTreeData.fire();
  }

  getTreeItem(element: StatusTreeItem): vscode.TreeItem {
    return element;
  }

  getChildren(): StatusTreeItem[] {
    const config = vscode.workspace.getConfiguration('copilotRemote');
    const configuredPort = config.get<number>('port') || 9876;
    const host = config.get<string>('host') || '0.0.0.0';
    const port = this.server.port || configuredPort;
    const status = this.server.isRunning ? 'Running' : 'Stopped';

    return [
      new StatusInfoItem('Server', status, this.server.isRunning ? 'check' : 'circle-slash'),
      new StatusInfoItem('Endpoint', `${host}:${port}`, 'plug'),
      new StatusInfoItem('Clients', `${this.server.connectedClients}`, 'device-mobile'),
      new StatusActionItem('Open Dashboard', 'show control panel', 'dashboard', 'copilotRemote.openPanel'),
      new StatusActionItem('Start Server', 'start websocket server', 'debug-start', 'copilotRemote.startServer'),
      new StatusActionItem('Show QR Code', 'connect Android app', 'symbol-misc', 'copilotRemote.showQRCode'),
      new StatusActionItem('Show Logs', 'open output channel', 'output', 'copilotRemote.showOutputChannel'),
    ];
  }
}

/**
 * TreeView item representing the current VS Code window / server instance.
 */
export class ThisWindowItem extends vscode.TreeItem {
  public readonly host: string;
  public readonly port: number;

  constructor(server: CopilotRemoteServer, host: string) {
    super('This Window', vscode.TreeItemCollapsibleState.None);
    this.contextValue = 'copilotRemote.thisWindow';
    this.port = server.port;
    this.host = host === '0.0.0.0' ? '127.0.0.1' : host;
    this.description = `${vscode.workspace.name || 'Untitled'}:${this.port}`;
    this.tooltip = [
      'Current instance',
      `Instance ID: ${server.instanceId}`,
      `Host: ${this.host}:${this.port}`,
    ].join('\n');
    this.iconPath = new vscode.ThemeIcon('screen-full');
  }
}

/**
 * TreeView group item that contains discovered instances.
 */
export class DiscoveredGroupItem extends vscode.TreeItem {
  constructor(count: number) {
    super('Discovered Instances', vscode.TreeItemCollapsibleState.Expanded);
    this.contextValue = 'copilotRemote.discoveredGroup';
    this.description = `${count} found`;
    this.iconPath = new vscode.ThemeIcon('remote');
  }
}

/**
 * TreeView item for a single discovered instance.
 */
export class InstanceItem extends vscode.TreeItem {
  constructor(
    public readonly instance: InstanceInfo,
    proxy: InstanceProxy,
  ) {
    super(instance.workspaceName, vscode.TreeItemCollapsibleState.None);
    this.contextValue = 'copilotRemote.instance';
    const connected = proxy.isConnected(instance.instanceId);
    this.description = `:${instance.port}${connected ? ' (connected)' : ''}`;
    this.tooltip = [
      instance.windowTitle,
      `Instance ID: ${instance.instanceId}`,
      `Host: ${instance.host}:${instance.port}`,
      `PID: ${instance.pid}`,
      `Primary: ${instance.isPrimary ? 'yes' : 'no'}`,
      `Connected: ${connected ? 'yes' : 'no'}`,
    ].join('\n');
    this.iconPath = new vscode.ThemeIcon('window');
  }
}

export type InstanceTreeItem = ThisWindowItem | DiscoveredGroupItem | InstanceItem;

/**
 * TreeDataProvider for the Copilot Remote Instances explorer view.
 */
export class InstancePanelProvider implements vscode.TreeDataProvider<InstanceTreeItem> {
  private readonly _onDidChangeTreeData = new vscode.EventEmitter<InstanceTreeItem | undefined | void>();
  public readonly onDidChangeTreeData = this._onDidChangeTreeData.event;

  constructor(
    private readonly server: CopilotRemoteServer,
    private readonly discovery: InstanceDiscovery,
    private readonly proxy: InstanceProxy,
  ) {
    this.discovery.onDidChangeInstances(() => {
      this.refresh();
    });
  }

  refresh(): void {
    this._onDidChangeTreeData.fire();
  }

  getTreeItem(element: InstanceTreeItem): vscode.TreeItem {
    return element;
  }

  getChildren(element?: InstanceTreeItem): InstanceTreeItem[] {
    if (!element) {
      const config = vscode.workspace.getConfiguration('copilotRemote');
      const host = config.get<string>('host') || '0.0.0.0';
      return [
        new ThisWindowItem(this.server, host),
        new DiscoveredGroupItem(this.discovery.getInstances().length),
      ];
    }

    if (element instanceof DiscoveredGroupItem) {
      return this.discovery.getInstances().map((info) => new InstanceItem(info, this.proxy));
    }

    return [];
  }
}
