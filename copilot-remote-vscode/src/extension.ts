import * as vscode from 'vscode';
import * as crypto from 'crypto';
import * as os from 'os';
import { CopilotRemoteServer } from './server';
import { InstanceDiscovery } from './instance-discovery';
import { InstanceProxy } from './instance-proxy';
import { InstancePanelProvider, InstanceItem, ThisWindowItem, StatusPanelProvider } from './panel-provider';

let server: CopilotRemoteServer;
let outputChannel: vscode.OutputChannel;
let statusBarItem: vscode.StatusBarItem;
let discovery: InstanceDiscovery;
let proxy: InstanceProxy;
let panelProvider: InstancePanelProvider;
let statusPanelProvider: StatusPanelProvider;

export function activate(context: vscode.ExtensionContext) {
  outputChannel = vscode.window.createOutputChannel('Copilot Remote');

  // Use refs to break the circular dependency between discovery/proxy and server.
  const serverRef = { current: null as CopilotRemoteServer | null };
  const proxyRef = { current: null as InstanceProxy | null };

  discovery = new InstanceDiscovery(
    outputChannel,
    () => serverRef.current?.port ?? 0,
    () => serverRef.current?.instanceId ?? '',
    (id) => proxyRef.current?.isConnected(id) ?? false,
  );
  proxy = new InstanceProxy(outputChannel, discovery);
  proxyRef.current = proxy;
  server = new CopilotRemoteServer(outputChannel, discovery, proxy, context);
  serverRef.current = server;
  panelProvider = new InstancePanelProvider(server, discovery, proxy);
  statusPanelProvider = new StatusPanelProvider(server);

  const statusTreeView = vscode.window.createTreeView('copilotRemote.statusView', {
    treeDataProvider: statusPanelProvider,
  });
  const treeView = vscode.window.createTreeView('copilotRemote.instancesView', {
    treeDataProvider: panelProvider,
  });
  context.subscriptions.push(statusTreeView);
  context.subscriptions.push(treeView);

  context.subscriptions.push(vscode.languages.onDidChangeDiagnostics((event) => {
    const errors = event.uris.flatMap((uri) => vscode.languages.getDiagnostics(uri).filter((item) => item.severity === vscode.DiagnosticSeverity.Error));
    if (errors.length) server.broadcast({ type: 'eventNotification', category: 'build', title: 'VS Code problems', message: `${errors.length} new error(s) detected`, success: false });
  }));
  context.subscriptions.push(vscode.tasks.onDidEndTaskProcess((event) => {
    const name = event.execution.task.name;
    const isTest = /test/i.test(name);
    server.broadcast({ type: 'eventNotification', category: isTest ? 'test' : 'build', title: `${name} finished`, message: event.exitCode === 0 ? 'Completed successfully' : `Failed with exit code ${event.exitCode ?? 'unknown'}`, success: event.exitCode === 0 });
  }));

  // Status bar item
  statusBarItem = vscode.window.createStatusBarItem(vscode.StatusBarAlignment.Right, 100);
  statusBarItem.command = 'copilotRemote.showStatus';
  statusBarItem.text = '$(radio-tower) Copilot Remote';
  statusBarItem.tooltip = 'Copilot Remote Server - Click for status';
  statusBarItem.show();
  context.subscriptions.push(statusBarItem);

  // ─── Commands ───────────────────────────────────────────────

  context.subscriptions.push(
    vscode.commands.registerCommand('copilotRemote.startServer', async () => {
      const config = vscode.workspace.getConfiguration('copilotRemote');
      const key = config.get<string>('key') || '';
      const port = config.get<number>('port') || 9876;
      const host = config.get<string>('host') || '0.0.0.0';

      if (!key) {
        const action = await vscode.window.showWarningMessage(
          'Copilot Remote: No authentication key set. Would you like to generate one?',
          'Generate Key',
          'Cancel',
        );
        if (action === 'Generate Key') {
          await vscode.commands.executeCommand('copilotRemote.generateKey');
        } else {
          return;
        }
      }

      try {
        await server.start(port, host);
        updateStatusBar();
        statusPanelProvider.refresh();
        const actualPort = server.port;
        if (actualPort !== port) {
          vscode.window.showInformationMessage(
            `Copilot Remote server started on ${host}:${actualPort} (port ${port} was in use — another VS Code window is running). Workspace: ${vscode.workspace.name || 'Untitled'}`,
          );
        } else {
          vscode.window.showInformationMessage(
            `Copilot Remote server started on ${host}:${actualPort} (workspace: ${vscode.workspace.name || 'Untitled'})`,
          );
        }
        outputChannel.show();
      } catch (err) {
        vscode.window.showErrorMessage(
          `Failed to start Copilot Remote server: ${err}`,
        );
      }
    }),

    vscode.commands.registerCommand('copilotRemote.stopServer', async () => {
      await server.stop();
      updateStatusBar();
      statusPanelProvider.refresh();
      vscode.window.showInformationMessage('Copilot Remote server stopped');
    }),

    vscode.commands.registerCommand('copilotRemote.restartServer', async () => {
      const config = vscode.workspace.getConfiguration('copilotRemote');
      const port = config.get<number>('port') || 9876;
      const host = config.get<string>('host') || '0.0.0.0';
      await server.stop();
      await server.start(port, host);
      updateStatusBar();
      statusPanelProvider.refresh();
      vscode.window.showInformationMessage(`Copilot Remote server restarted on port ${server.port}`);
    }),

    vscode.commands.registerCommand('copilotRemote.showStatus', async () => {
      const config = vscode.workspace.getConfiguration('copilotRemote');
      const host = config.get<string>('host') || '0.0.0.0';
      const key = config.get<string>('key') || '';
      const port = server.port || config.get<number>('port') || 9876;

      const status = server.isRunning ? 'Running' : 'Stopped';
      const clients = server.connectedClients;
      const wsName = vscode.workspace.name || 'Untitled';

      const panel = vscode.window.createWebviewPanel(
        'copilotRemoteStatus',
        'Copilot Remote Status',
        vscode.ViewColumn.Active,
        {},
      );

      panel.webview.html = getStatusHtml(status, host, port, clients, key, wsName, server.instanceId);
    }),

    vscode.commands.registerCommand('copilotRemote.generateKey', async () => {
      const key = crypto.randomBytes(24).toString('base64url');
      await vscode.workspace.getConfiguration('copilotRemote').update('key', key, vscode.ConfigurationTarget.Global);
      vscode.window.showInformationMessage(`Copilot Remote key generated: ${key}`);
      outputChannel.appendLine(`[Copilot Remote] New key generated: ${key}`);
    }),

    vscode.commands.registerCommand('copilotRemote.showQRCode', async () => {
      const config = vscode.workspace.getConfiguration('copilotRemote');
      const host = config.get<string>('host') || '0.0.0.0';
      const key = config.get<string>('key') || '';
      const port = server.port || config.get<number>('port') || 9876;

      // Get local IP address
      const localIp = await getLocalIp();
      const wsUrl = `ws://${localIp}:${port}`;

      const panel = vscode.window.createWebviewPanel(
        'copilotRemoteQR',
        'Copilot Remote - QR Code',
        vscode.ViewColumn.Active,
        {},
      );

      panel.webview.html = getQrHtml(wsUrl, key);
    }),

    vscode.commands.registerCommand('copilotRemote.refreshInstances', async () => {
      await discovery.refresh();
      panelProvider.refresh();
      vscode.window.showInformationMessage('Copilot Remote instances refreshed');
    }),

    vscode.commands.registerCommand('copilotRemote.connectToInstance', async (item?: InstanceItem | string) => {
      let instanceId: string | undefined;
      if (item instanceof InstanceItem) {
        instanceId = item.instance.instanceId;
      } else if (typeof item === 'string') {
        instanceId = item;
      }

      if (!instanceId) {
        const instances = discovery.getInstances();
        if (instances.length === 0) {
          vscode.window.showInformationMessage('No discovered Copilot Remote instances');
          return;
        }
        const pick = await vscode.window.showQuickPick(
          instances.map((i) => ({ label: `${i.workspaceName}:${i.port}`, instanceId: i.instanceId })),
          { placeHolder: 'Select an instance to connect' },
        );
        if (!pick) {
          return;
        }
        instanceId = pick.instanceId;
      }

      try {
        await proxy.connect(instanceId);
        panelProvider.refresh();
        vscode.window.showInformationMessage(`Connected to Copilot Remote instance ${instanceId}`);
      } catch (err) {
        vscode.window.showErrorMessage(`Failed to connect to instance: ${err}`);
      }
    }),

    vscode.commands.registerCommand('copilotRemote.showOutputChannel', () => {
      outputChannel.show(true);
    }),

    vscode.commands.registerCommand('copilotRemote.testNotification', () => {
      server.broadcast({
        type: 'eventNotification',
        category: 'copilot',
        title: 'Copilot Remote test',
        message: 'Notification delivery is working.',
        success: true,
      });
      vscode.window.showInformationMessage('Copilot Remote test notification sent.');
    }),

    vscode.commands.registerCommand('copilotRemote.openPanel', () => {
      const config = vscode.workspace.getConfiguration('copilotRemote');
      const host = config.get<string>('host') || '0.0.0.0';
      const key = config.get<string>('key') || '';
      const port = server.port || config.get<number>('port') || 9876;
      const wsName = vscode.workspace.name || 'Untitled';

      const panel = vscode.window.createWebviewPanel(
        'copilotRemoteDashboard',
        'Copilot Remote Dashboard',
        vscode.ViewColumn.Beside,
        { enableScripts: true, retainContextWhenHidden: true },
      );

      panel.webview.html = getDashboardHtml(
        server.isRunning,
        host,
        port,
        server.connectedClients,
        key,
        wsName,
        server.instanceId,
      );

      // Update dashboard periodically
      const updateInterval = setInterval(() => {
        if (!panel.visible) return;
        panel.webview.html = getDashboardHtml(
          server.isRunning,
          host,
          port,
          server.connectedClients,
          key,
          wsName,
          server.instanceId,
        );
      }, 3000);

      panel.onDidDispose(() => {
        clearInterval(updateInterval);
      });

      // Handle messages from the webview
      panel.webview.onDidReceiveMessage(async (msg) => {
        switch (msg.command) {
          case 'startServer':
            await vscode.commands.executeCommand('copilotRemote.startServer');
            break;
          case 'stopServer':
            await vscode.commands.executeCommand('copilotRemote.stopServer');
            break;
          case 'restartServer':
            await vscode.commands.executeCommand('copilotRemote.restartServer');
            break;
          case 'showQR':
            await vscode.commands.executeCommand('copilotRemote.showQRCode');
            break;
          case 'generateKey':
            await vscode.commands.executeCommand('copilotRemote.generateKey');
            break;
          case 'showOutput':
            outputChannel.show(true);
            break;
        }
      });
    }),

    vscode.commands.registerCommand('copilotRemote.copyInstanceUrl', async (item?: InstanceItem | ThisWindowItem) => {
      let host: string | undefined;
      let port: number | undefined;
      if (item instanceof InstanceItem) {
        host = item.instance.host;
        port = item.instance.port;
      } else if (item instanceof ThisWindowItem) {
        host = item.host;
        port = item.port;
      }

      if (!host || !port) {
        const config = vscode.workspace.getConfiguration('copilotRemote');
        host = config.get<string>('host') || '0.0.0.0';
        port = server.port || config.get<number>('port') || 9876;
      }

      const connectHost = host === '0.0.0.0' ? '127.0.0.1' : host;
      const url = `ws://${connectHost}:${port}`;
      await vscode.env.clipboard.writeText(url);
      vscode.window.showInformationMessage(`Copied instance URL: ${url}`);
    }),
  );

  // ─── Configuration Change Listener ──────────────────────────

  context.subscriptions.push(
    vscode.workspace.onDidChangeConfiguration((e) => {
      if (e.affectsConfiguration('copilotRemote')) {
        const config = vscode.workspace.getConfiguration('copilotRemote');
        if (server.isRunning && (e.affectsConfiguration('copilotRemote.port') || e.affectsConfiguration('copilotRemote.host'))) {
          vscode.window
            .showInformationMessage(
              'Copilot Remote: Port or host changed. Restart server?',
              'Restart',
              'Cancel',
            )
            .then((action) => {
              if (action === 'Restart') {
                const port = config.get<number>('port') || 9876;
                const host = config.get<string>('host') || '0.0.0.0';
                server.stop().then(() => server.start(port, host));
              }
            });
        }
        updateStatusBar();
      }
    }),
  );

  // ─── Auto-start ─────────────────────────────────────────────

  const config = vscode.workspace.getConfiguration('copilotRemote');
  const autoStart = config.get<boolean>('autoStart');
  if (autoStart) {
    outputChannel.appendLine('[Copilot Remote] Auto-start is enabled, waiting 3s for Copilot to initialize...');
    // Delay to allow Copilot to initialize
    setTimeout(async () => {
      const key = config.get<string>('key') || '';
      const port = config.get<number>('port') || 9876;
      const host = config.get<string>('host') || '0.0.0.0';

      if (key) {
        try {
          await server.start(port, host);
          updateStatusBar();
          outputChannel.appendLine(`[Copilot Remote] Auto-started on ${host}:${port}`);
          outputChannel.show(); // Show output so user can see the status
        } catch (err) {
          outputChannel.appendLine(`[Copilot Remote] Auto-start failed: ${err}`);
          outputChannel.show(true); // Show output on failure too
        }
      } else {
        outputChannel.appendLine(
          '[Copilot Remote] No key set. Server not auto-started. Set "copilotRemote.key" and start manually.',
        );
      }
    }, 3000);
  }

  discovery.start();
  updateStatusBar();

  // Set context for view visibility
  vscode.commands.executeCommand('setContext', 'copilotRemote.activated', true);
}

export async function deactivate(): Promise<void> {
  if (server) {
    await server.stop();
  }
  discovery?.dispose();
  proxy?.dispose();
}

// ─── Helper Functions ─────────────────────────────────────────

function updateStatusBar(): void {
  if (server.isRunning) {
    const wsName = vscode.workspace.name || 'Untitled';
    statusBarItem.text = `$(radio-tower) Remote:${server.port}`;
    statusBarItem.tooltip = `Copilot Remote — Port ${server.port} | Workspace: ${wsName} | ${server.connectedClients} client(s) | Click for details`;
    statusBarItem.backgroundColor = undefined;
  } else {
    statusBarItem.text = '$(circle-slash) Remote:Off';
    statusBarItem.tooltip = 'Copilot Remote Server Stopped - Click to view status';
  }
}

async function getLocalIp(): Promise<string> {
  const interfaces = os.networkInterfaces();
  for (const name of Object.keys(interfaces)) {
    for (const iface of interfaces[name] || []) {
      if (iface.family === 'IPv4' && !iface.internal) {
        return iface.address;
      }
    }
  }
  return '127.0.0.1';
}

function getStatusHtml(
  status: string,
  host: string,
  port: number,
  clients: number,
  key: string,
  workspaceName: string,
  instanceId: string,
): string {
  const keyDisplay = key ? `${key.slice(0, 8)}...${key.slice(-4)}` : '(not set)';
  const statusColor = status === 'Running' ? '#4caf50' : '#f44336';

  return `<!DOCTYPE html>
<html>
<head>
<style>
  body { font-family: var(--vscode-font-family); padding: 20px; color: var(--vscode-foreground); }
  .card { background: var(--vscode-editor-background); border: 1px solid var(--vscode-panel-border); border-radius: 8px; padding: 16px; margin: 8px 0; }
  .status { font-size: 24px; font-weight: bold; color: ${statusColor}; }
  .row { display: flex; justify-content: space-between; padding: 4px 0; }
  .label { color: var(--vscode-descriptionForeground); }
  .value { font-family: var(--vscode-editor-font-family); }
  button { background: var(--vscode-button-background); color: var(--vscode-button-foreground); border: none; padding: 8px 16px; border-radius: 4px; cursor: pointer; margin: 4px; }
  .instance { font-size: 11px; color: var(--vscode-descriptionForeground); font-family: var(--vscode-editor-font-family); margin-top: 4px; }
</style>
</head>
<body>
  <h1>Copilot Remote Server</h1>
  <div class="card">
    <div class="status">${status}</div>
    <div class="instance">Instance ID: ${instanceId}</div>
  </div>
  <div class="card">
    <div class="row"><span class="label">Workspace:</span><span class="value">${workspaceName}</span></div>
    <div class="row"><span class="label">Host:</span><span class="value">${host}</span></div>
    <div class="row"><span class="label">Port:</span><span class="value">${port}</span></div>
    <div class="row"><span class="label">Connected Clients:</span><span class="value">${clients}</span></div>
    <div class="row"><span class="label">Auth Key:</span><span class="value">${keyDisplay}</span></div>
  </div>
  <div>
    <button onclick="fetch('/start')">Start Server</button>
    <button onclick="fetch('/stop')">Stop Server</button>
    <button onclick="fetch('/restart')">Restart Server</button>
  </div>
  <div class="card">
    <p style="color: var(--vscode-descriptionForeground); font-size: 12px;">
      <strong>Multi-window support:</strong> If you open multiple VS Code windows, each gets its own server
      on an auto-incremented port (e.g. 9876, 9877, 9878...). The Android app can connect to any of them.
    </p>
  </div>
</body>
</html>`;
}

function getQrHtml(wsUrl: string, key: string): string {
  const connectionData = JSON.stringify({ url: wsUrl, key });

  return `<!DOCTYPE html>
<html>
<head>
<style>
  body { font-family: var(--vscode-font-family); padding: 20px; text-align: center; color: var(--vscode-foreground); }
  .qr-container { background: white; padding: 20px; border-radius: 12px; display: inline-block; margin: 20px 0; }
  .info { margin: 8px 0; }
  .url { font-family: var(--vscode-editor-font-family); font-size: 16px; padding: 8px; background: var(--vscode-textBlockQuote-background); border-radius: 4px; display: inline-block; }
  .key { font-family: var(--vscode-editor-font-family); font-size: 14px; padding: 8px; background: var(--vscode-textBlockQuote-background); border-radius: 4px; display: inline-block; word-break: break-all; }
  .instructions { text-align: left; max-width: 500px; margin: 20px auto; }
</style>
<script src="https://cdn.jsdelivr.net/npm/qrcodejs@1.0.0/qrcode.min.js"></script>
</head>
<body>
  <h1>Scan to Connect</h1>
  <p>Scan this QR code with the Copilot Remote Android app</p>
  <div class="qr-container" id="qrcode"></div>
  <div class="instructions">
    <h3>Manual Connection</h3>
    <div class="info"><strong>WebSocket URL:</strong></div>
    <div class="url">${wsUrl}</div>
    <div class="info"><strong>Key:</strong></div>
    <div class="key">${key}</div>
    <h3>Instructions</h3>
    <ol>
      <li>Install the Copilot Remote Android app</li>
      <li>Open the app and go to Settings</li>
      <li>Enter the WebSocket URL and Key above</li>
      <li>Tap "Connect" to start remote controlling Copilot</li>
    </ol>
  </div>
  <script>
    new QRCode(document.getElementById("qrcode"), {
      text: '${connectionData.replace(/'/g, "\\'")}',
      width: 256,
      height: 256,
    });
  </script>
</body>
</html>`;
}

function getDashboardHtml(
  isRunning: boolean,
  host: string,
  port: number,
  clients: number,
  key: string,
  workspaceName: string,
  instanceId: string,
): string {
  const keyDisplay = key ? `${key.slice(0, 8)}...${key.slice(-4)}` : '(not set)';
  const statusColor = isRunning ? '#4caf50' : '#f44336';
  const statusText = isRunning ? 'Running' : 'Stopped';

  return `<!DOCTYPE html>
<html>
<head>
<style>
  * { box-sizing: border-box; margin: 0; padding: 0; }
  body { font-family: var(--vscode-font-family); padding: 16px; color: var(--vscode-foreground); background: var(--vscode-sideBar-background); }
  .header { display: flex; align-items: center; gap: 12px; margin-bottom: 20px; }
  .status-dot { width: 12px; height: 12px; border-radius: 50%; background: ${statusColor}; flex-shrink: 0; }
  .header h1 { font-size: 18px; font-weight: 600; }
  .grid { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; margin-bottom: 16px; }
  .card { background: var(--vscode-editor-background); border: 1px solid var(--vscode-panel-border); border-radius: 8px; padding: 14px; }
  .card h3 { font-size: 11px; text-transform: uppercase; color: var(--vscode-descriptionForeground); margin-bottom: 8px; letter-spacing: 0.5px; }
  .card.full { grid-column: 1 / -1; }
  .value { font-size: 22px; font-weight: 600; }
  .value.green { color: #4caf50; }
  .value.red { color: #f44336; }
  .value.blue { color: #2196f3; }
  .row { display: flex; justify-content: space-between; padding: 3px 0; font-size: 13px; }
  .label { color: var(--vscode-descriptionForeground); }
  .mono { font-family: var(--vscode-editor-font-family); font-size: 12px; }
  .actions { display: flex; gap: 8px; flex-wrap: wrap; margin-bottom: 16px; }
  button { background: var(--vscode-button-background); color: var(--vscode-button-foreground); border: none; padding: 8px 16px; border-radius: 4px; cursor: pointer; font-size: 13px; display: flex; align-items: center; gap: 6px; }
  button:hover { background: var(--vscode-button-hoverBackground); }
  button.secondary { background: var(--vscode-button-secondaryBackground); color: var(--vscode-button-secondaryForeground); }
  button.secondary:hover { background: var(--vscode-button-secondaryHoverBackground); }
  button.danger { background: #d32f2f; }
  button.danger:hover { background: #b71c1c; }
  .info-text { font-size: 12px; color: var(--vscode-descriptionForeground); margin-top: 12px; line-height: 1.5; }
  .badge { display: inline-block; background: var(--vscode-badge-background); color: var(--vscode-badge-foreground); font-size: 11px; padding: 2px 8px; border-radius: 10px; }
  @media (max-width: 500px) { .grid { grid-template-columns: 1fr; } }
</style>
</head>
<body>
  <div class="header">
    <div class="status-dot"></div>
    <h1>Copilot Remote Dashboard</h1>
    <span class="badge">${workspaceName}</span>
  </div>

  <div class="actions">
    <button onclick="postMsg('startServer')">&#9654; Start</button>
    <button class="danger" onclick="postMsg('stopServer')">&#9632; Stop</button>
    <button class="secondary" onclick="postMsg('restartServer')">&#8635; Restart</button>
    <button class="secondary" onclick="postMsg('showQR')">&#9807; QR Code</button>
    <button class="secondary" onclick="postMsg('generateKey')">&#9881; New Key</button>
    <button class="secondary" onclick="postMsg('showOutput')">&#9998; Logs</button>
  </div>

  <div class="grid">
    <div class="card">
      <h3>Server Status</h3>
      <div class="value ${isRunning ? 'green' : 'red'}">${statusText}</div>
    </div>
    <div class="card">
      <h3>Connected Clients</h3>
      <div class="value blue">${clients}</div>
    </div>
    <div class="card">
      <h3>Port</h3>
      <div class="value">${port}</div>
    </div>
    <div class="card">
      <h3>Auth Key</h3>
      <div class="value mono" style="font-size:13px">${keyDisplay}</div>
    </div>
  </div>

  <div class="card full">
    <h3>Connection Details</h3>
    <div class="row"><span class="label">Instance ID</span><span class="mono">${instanceId}</span></div>
    <div class="row"><span class="label">Host</span><span class="mono">${host}</span></div>
    <div class="row"><span class="label">Workspace</span><span>${workspaceName}</span></div>
    <div class="row"><span class="label">Key (first 8..last 4)</span><span class="mono">${keyDisplay}</span></div>
  </div>

  <div class="card full">
    <h3>Quick Setup</h3>
    <ol class="info-text">
      <li>Install the Copilot Remote Android app on your phone</li>
      <li>Make sure phone and PC are on the same network</li>
      <li>Click "QR Code" above and scan with the app, or manually enter the URL and key</li>
      <li>WebSocket URL: <strong>ws://<span id="localIp">your-pc-ip</span>:${port}</strong></li>
    </ol>
  </div>

  <script>
    const vscode = acquireVsCodeApi();
    function postMsg(command) { vscode.postMessage({ command }); }
    // Fetch local IP
    fetch('https://api.ipify.org?format=json').then(r => r.json()).then(d => {
      document.getElementById('localIp').textContent = d.ip;
    }).catch(() => {
      document.getElementById('localIp').textContent = '(use LAN IP)';
    });
  </script>
</body>
</html>`;
}
