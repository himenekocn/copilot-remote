/*---------------------------------------------------------------------------------------------
 *  Copyright (c) Microsoft Corporation. All rights reserved.
 *  Licensed under the MIT License. See License.txt in the project root for license information.
 *--------------------------------------------------------------------------------------------*/

import * as fs from 'fs/promises';
import * as http from 'http';
import * as os from 'os';
import * as path from 'path';
import * as crypto from 'crypto';
import * as vscode from 'vscode';
import { execFile } from 'child_process';
import { IVSCodeExtensionContext } from '../../../platform/extContext/common/extensionContext';
import { IEndpointProvider } from '../../../platform/endpoint/common/endpointProvider';
import { ILogService } from '../../../platform/log/common/logService';
import { Disposable } from '../../../util/vs/base/common/lifecycle';
import { URI } from '../../../util/vs/base/common/uri';
import { IExtensionContribution } from '../../common/contributions';
import { ITodoListContextProvider } from '../../prompt/node/todoListContextProvider';
import { remoteChatState, RemoteChatEvent, RemoteChatTodoItem } from '../common/remoteChatState';

interface RemoteBridgeSessionInfo {
	id: string;
	title: string;
	source: 'officialTranscript' | 'vscodeChatSession';
	filePath: string;
	workspaceName?: string;
	workspaceFolder?: string;
	createdAt?: number;
	updatedAt: number;
	requestCount: number;
	messages?: RemoteBridgeSessionMessage[];
}

interface RemoteBridgeSessionMessage {
	id: string;
	role: 'user' | 'assistant' | 'tool';
	content: string;
	timestamp: number;
	modelId?: string;
	kind?: 'message' | 'thinking' | 'tool';
	toolName?: string;
	toolStatus?: 'running' | 'completed' | 'error';
	toolInput?: string;
	toolOutput?: string;
}

interface RemoteBridgeSessionPage {
	sessions: RemoteBridgeSessionInfo[];
	total: number;
	offset: number;
	limit: number;
	hasMore: boolean;
	nextOffset: number;
}

interface RemoteBridgeProviderInfo {
	id: string;
	name: string;
	type: 'vscode-lm' | 'cli';
	available: boolean;
	command?: string;
	version?: string;
	capabilities: string[];
	notes?: string;
}

interface RemoteBridgeChatAttachment {
	mimeType?: string;
	dataBase64?: string;
	name?: string;
}

interface RemoteBridgeChatMessage {
	role?: string;
	content?: string;
	attachments?: RemoteBridgeChatAttachment[];
}

export class RemoteBridgeContribution extends Disposable implements IExtensionContribution {
	readonly id = 'github.copilot.chat.remoteBridge';
	private _server: http.Server | undefined;
	private _actualPort = 0;
	private readonly _activeChatRequests = new Map<string, vscode.CancellationTokenSource>();
	private readonly _sessionIndexCache = new Map<string, { expiresAt: number; sessions: RemoteBridgeSessionInfo[] }>();
	private readonly _sessionIndexInflight = new Map<string, Promise<RemoteBridgeSessionInfo[]>>();
	private readonly _sessionFileMetadataCache = new Map<string, { mtimeMs: number; size: number; session: RemoteBridgeSessionInfo }>();

	constructor(
		@IVSCodeExtensionContext private readonly _context: IVSCodeExtensionContext,
		@ILogService private readonly _logService: ILogService,
		@IEndpointProvider private readonly _endpointProvider: IEndpointProvider,
		@ITodoListContextProvider private readonly _todoListContextProvider: ITodoListContextProvider,
	) {
		super();
		this._register(vscode.window.onDidChangeActiveChatPanelSessionResource(resource => remoteChatState.activeSessionChanged(resource?.toString())));
		this._register(vscode.workspace.onDidChangeConfiguration(e => {
			if (e.affectsConfiguration('github.copilot.chat.remoteBridge')) {
				void this._restart();
			}
		}));
		void this._restart();
	}

	override dispose(): void {
		this._stop();
		super.dispose();
	}

	private async _restart(): Promise<void> {
		this._stop();
		const config = vscode.workspace.getConfiguration('github.copilot.chat.remoteBridge');
		if (!config.get<boolean>('enabled', false)) {
			return;
		}

		const key = String(config.get<string>('key', '') || '').trim();
		if (!key) {
			this._logService.warn('[RemoteBridge] Refusing to start: github.copilot.chat.remoteBridge.key is empty');
			return;
		}

		const host = String(config.get<string>('host', '127.0.0.1') || '127.0.0.1');
		const port = Number(config.get<number>('port', 9877) || 9877);
		await this._start(host, port, key);
	}

	private async _start(host: string, port: number, key: string): Promise<void> {
		this._server = http.createServer((req, res) => {
			void this._handleRequest(req, res, key);
		});
		this._server.on('error', err => this._logService.error(`[RemoteBridge] Server error: ${err.message}`));
		await new Promise<void>((resolve, reject) => {
			this._server!.once('error', reject);
			this._server!.listen(port, host, () => resolve());
		});
		const address = this._server.address();
		this._actualPort = typeof address === 'object' && address ? address.port : port;
		this._logService.info(`[RemoteBridge] Listening on http://${host}:${this._actualPort}`);
	}

	private _stop(): void {
		for (const source of this._activeChatRequests.values()) {
			source.cancel();
			source.dispose();
		}
		this._activeChatRequests.clear();
		if (this._server) {
			this._server.close();
			this._server = undefined;
			this._actualPort = 0;
		}
	}

	private async _handleRequest(req: http.IncomingMessage, res: http.ServerResponse, key: string): Promise<void> {
		try {
			if (!this._isAuthorized(req, key)) {
				this._sendJson(res, 401, { error: 'Unauthorized' });
				return;
			}

			const url = new URL(req.url || '/', `http://${req.headers.host || '127.0.0.1'}`);
			if (req.method === 'GET' && url.pathname === '/status') {
				this._sendJson(res, 200, {
					success: true,
					bridge: 'patched-copilot-chat',
					port: this._actualPort,
					workspaceName: vscode.workspace.name || 'No Workspace',
					storageUri: this._context.storageUri?.fsPath,
				});
				return;
			}

			if (req.method === 'GET' && url.pathname === '/models') {
				const models = await vscode.lm.selectChatModels({});
				const endpoints = await this._endpointProvider.getAllChatEndpoints();
				this._sendJson(res, 200, {
					models: models.map(model => {
						const normalize = (value: string | undefined) => String(value || '').toLowerCase().replace(/[^a-z0-9]+/g, '');
						const modelKeys = [model.id, model.name, model.family].map(normalize).filter(Boolean);
						const endpoint = endpoints.find(item => {
							const endpointKeys = [item.model, item.name, item.family].map(normalize).filter(Boolean);
							return endpointKeys.some(key => modelKeys.includes(key));
						});
						const declaredEfforts = endpoint?.supportsReasoningEffort || [];
						return {
						id: model.id,
						name: model.name,
						vendor: model.vendor,
						family: model.family,
						version: model.version,
						maxInputTokens: model.maxInputTokens,
						reasoningEfforts: declaredEfforts,
					};
					}),
				});
				return;
			}

			if (req.method === 'GET' && url.pathname === '/providers') {
				this._sendJson(res, 200, { providers: await this._listProviders() });
				return;
			}

			if (req.method === 'GET' && url.pathname === '/capabilities') {
				this._sendJson(res, 200, {
					chat: { streaming: true, cancellation: false, imageAttachments: true, activeSession: true, uiSynchronized: true, reasoningEffort: true },
					sessions: { pagination: true, messagePagination: true },
					slashCommands: [
						{ name: 'compact', description: 'Compact the current conversation context.' },
						{ name: 'explain', description: 'Explain selected or active code.' },
						{ name: 'tests', description: 'Generate tests for the selected code.' },
						{ name: 'fix', description: 'Propose and apply a fix.' },
						{ name: 'new', description: 'Create a new project or file.' },
						{ name: 'setupTests', description: 'Configure a test framework.' },
					],
				});
				return;
			}

			if (req.method === 'GET' && url.pathname === '/chat/active-session') {
				const sessionResource = vscode.window.activeChatPanelSessionResource?.toString();
				const snapshot = sessionResource ? remoteChatState.get(sessionResource) : undefined;
				this._sendJson(res, 200, { active: Boolean(sessionResource), sessionResource, snapshot });
				return;
			}

			if (req.method === 'GET' && url.pathname === '/chat/active-session/todos') {
				const sessionResource = vscode.window.activeChatPanelSessionResource?.toString();
				if (!sessionResource) {
					this._sendJson(res, 409, { error: 'No active Copilot Chat panel session' });
					return;
				}
				const todoContext = await this._todoListContextProvider.getCurrentTodoContext(sessionResource);
				const items = this._parseTodoContext(todoContext);
				const snapshot = remoteChatState.get(sessionResource);
				if (snapshot?.requestId) {
					remoteChatState.todos(sessionResource, snapshot.requestId, items);
				}
				this._sendJson(res, 200, { sessionResource, items, raw: todoContext });
				return;
			}

			if (req.method === 'GET' && url.pathname === '/chat/events') {
				this._streamChatEvents(req, res);
				return;
			}

			if (req.method === 'GET' && url.pathname === '/sessions') {
				const offset = this._boundedInteger(url.searchParams.get('offset'), 0, 0, 1_000_000);
				const limit = this._boundedInteger(url.searchParams.get('limit'), 50, 1, 200);
				const workspace = url.searchParams.get('workspace')?.trim().toLowerCase();
				this._sendJson(res, 200, await this._listSessionPage(offset, limit, workspace));
				return;
			}

			if (req.method === 'GET' && url.pathname.startsWith('/sessions/')) {
				const id = decodeURIComponent(url.pathname.slice('/sessions/'.length));
				const session = await this._getSession(id);
				if (!session) {
					this._sendJson(res, 404, { error: `Session not found: ${id}` });
					return;
				}
				const allMessages = session.messages || [];
				const before = this._boundedInteger(url.searchParams.get('before'), allMessages.length, 0, allMessages.length);
				const limit = this._boundedInteger(url.searchParams.get('limit'), 100, 1, 500);
				const start = Math.max(0, before - limit);
				this._sendJson(res, 200, {
					session: { ...session, messages: allMessages.slice(start, before) },
					messagePage: {
						start,
						end: before,
						total: allMessages.length,
						hasMoreBefore: start > 0,
					},
				});
				return;
			}

			if (req.method === 'POST' && url.pathname === '/chat/open') {
				const body = await this._readJsonBody(req);
				const prompt = typeof body.prompt === 'string' ? body.prompt : '';
				if (!prompt.trim()) {
					this._sendJson(res, 400, { error: 'Missing prompt' });
					return;
				}
				await vscode.commands.executeCommand('workbench.action.chat.open', prompt);
				this._sendJson(res, 200, { success: true });
				return;
			}

			if (req.method === 'POST' && (url.pathname === '/chat/submit' || url.pathname === '/chat/stream')) {
				await this._submitToActiveChat(req, res);
				return;
			}

			if (req.method === 'POST' && url.pathname.startsWith('/chat/cancel/')) {
				const requestId = decodeURIComponent(url.pathname.slice('/chat/cancel/'.length));
				const source = this._activeChatRequests.get(requestId);
				if (!source) {
					this._sendJson(res, 404, { error: `Active chat request not found: ${requestId}` });
					return;
				}
				source.cancel();
				this._sendJson(res, 200, { success: true, requestId });
				return;
			}

			this._sendJson(res, 404, { error: 'Not found' });
		} catch (err) {
			this._logService.error('[RemoteBridge] Request failed', err);
			this._sendJson(res, 500, { error: err instanceof Error ? err.message : String(err) });
		}
	}

	private _isAuthorized(req: http.IncomingMessage, key: string): boolean {
		const header = req.headers['x-copilot-remote-key'];
		return typeof header === 'string' && header === key;
	}

	private async _readJsonBody(req: http.IncomingMessage): Promise<Record<string, unknown>> {
		const chunks: Buffer[] = [];
		for await (const chunk of req) {
			chunks.push(Buffer.isBuffer(chunk) ? chunk : Buffer.from(chunk));
		}
		const raw = Buffer.concat(chunks).toString('utf8').trim();
		return raw ? JSON.parse(raw) : {};
	}

	private _sendJson(res: http.ServerResponse, statusCode: number, body: unknown): void {
		res.writeHead(statusCode, { 'content-type': 'application/json; charset=utf-8' });
		res.end(JSON.stringify(body));
	}

	private _streamChatEvents(req: http.IncomingMessage, res: http.ServerResponse): void {
		res.writeHead(200, {
			'content-type': 'text/event-stream; charset=utf-8',
			'cache-control': 'no-cache, no-transform',
			'connection': 'keep-alive',
		});
		const send = (event: RemoteChatEvent) => res.write(`data: ${JSON.stringify(event)}\n\n`);
		const active = vscode.window.activeChatPanelSessionResource?.toString();
		send({ type: 'activeSessionChanged', sessionResource: active });
		const listener = remoteChatState.listen(send);
		const heartbeat = setInterval(() => res.write(': heartbeat\n\n'), 15_000);
		const cleanup = () => { clearInterval(heartbeat); listener.dispose(); };
		req.once('close', cleanup);
		res.once('close', cleanup);
	}

	private async _submitToActiveChat(req: http.IncomingMessage, res: http.ServerResponse): Promise<void> {
		const body = await this._readJsonBody(req);
		const requestId = typeof body.requestId === 'string' && body.requestId.trim() ? body.requestId : crypto.randomUUID();
		const targetSessionResource = typeof body.sessionResource === 'string' ? body.sessionResource : undefined;
		const activeSessionUri = vscode.window.activeChatPanelSessionResource;
		const activeSessionResource = activeSessionUri?.toString();
		if (!activeSessionResource) {
			this._sendJson(res, 409, { error: 'No active Copilot Chat panel session. Open the target chat in VS Code first.' });
			return;
		}
		if (targetSessionResource && targetSessionResource !== activeSessionResource) {
			this._sendJson(res, 409, { error: 'The requested chat is not the active VS Code Chat panel session', activeSessionResource });
			return;
		}
		const rawMessages = Array.isArray(body.messages) ? body.messages as RemoteBridgeChatMessage[] : [];
		const latestUserMessage = rawMessages.filter(message => message.role !== 'assistant').at(-1);
		let prompt = typeof body.prompt === 'string' ? body.prompt : latestUserMessage?.content || '';
		const textAttachments = (latestUserMessage?.attachments || []).filter(attachment => attachment.mimeType?.startsWith('text/') && attachment.dataBase64);
		for (const attachment of textAttachments) {
			const content = Buffer.from(attachment.dataBase64!, 'base64').toString('utf8').slice(-120000);
			prompt += `\n\n<terminal_context name="${(attachment.name || 'terminal').replace(/"/g, '&quot;')}">\n${content}\n</terminal_context>`;
		}
		if (!prompt.trim()) {
			this._sendJson(res, 400, { error: 'Missing prompt' });
			return;
		}
		const modelId = typeof body.modelId === 'string' && body.modelId ? body.modelId : undefined;
		const participant = typeof body.participant === 'string' && body.participant.trim() ? body.participant.trim() : undefined;
		const rawReasoningEffort = typeof body.reasoningEffort === 'string' && body.reasoningEffort ? body.reasoningEffort : undefined;
		const reasoningEffort = rawReasoningEffort === 'max' ? 'xhigh' : rawReasoningEffort;
		const permissionLevel = body.permissionLevel === 'autoApprove' || body.permissionLevel === 'autopilot' ? body.permissionLevel : undefined;
		const enabledTools = Array.isArray(body.enabledTools) ? body.enabledTools.filter((tool): tool is string => typeof tool === 'string') : undefined;
		const models = modelId ? await vscode.lm.selectChatModels({ id: modelId }) : [];
		if (modelId && !models.length) {
			this._sendJson(res, 400, { error: `Model not found: ${modelId}` });
			return;
		}
		if (reasoningEffort || permissionLevel || enabledTools) {
			remoteChatState.setNextModelConfiguration(activeSessionResource, { reasoningEffort, permissionLevel, enabledTools });
		}
		res.writeHead(200, {
			'content-type': 'application/x-ndjson; charset=utf-8',
			'cache-control': 'no-cache, no-transform',
			'connection': 'keep-alive',
		});
		res.flushHeaders();
		const writeEvent = (event: unknown) => res.write(`${JSON.stringify(event)}\n`);
		const listener = remoteChatState.listen(event => {
			if ('sessionResource' in event && event.sessionResource !== activeSessionResource) return;
			if (event.type === 'chatStart') writeEvent({ ...event, requestId, officialRequestId: event.requestId });
			else if (event.type !== 'activeSessionChanged') writeEvent({ ...event, requestId, officialRequestId: 'requestId' in event ? event.requestId : undefined });
			if (event.type === 'chatDone' || event.type === 'chatError' || event.type === 'chatCancelled') {
				listener.dispose();
				res.end();
			}
		});
		try {
			const attachFiles = await this._materializeAttachments(requestId, latestUserMessage?.attachments || []);
			if (participant) {
				await vscode.commands.executeCommand('workbench.action.chat.toggleAgentMode', {
					modeId: participant,
					sessionResource: activeSessionUri,
				});
			}
			await vscode.commands.executeCommand('workbench.action.chat.open', {
				query: prompt,
				modelSelector: modelId ? { id: modelId } : undefined,
				attachFiles,
			});
		} catch (error) {
			listener.dispose();
			writeEvent({ type: 'chatError', requestId, error: error instanceof Error ? error.message : String(error) });
			res.end();
		}
	}

	private async _materializeAttachments(requestId: string, attachments: RemoteBridgeChatAttachment[]): Promise<vscode.Uri[]> {
		if (!attachments.length || !this._context.storageUri) return [];
		const directory = URI.joinPath(this._context.storageUri, 'remote-attachments', requestId);
		await vscode.workspace.fs.createDirectory(vscode.Uri.from(directory));
		const result: vscode.Uri[] = [];
		for (let index = 0; index < attachments.length; index++) {
			const attachment = attachments[index];
			if (!attachment.dataBase64 || !attachment.mimeType?.startsWith('image/')) continue;
			const data = Buffer.from(attachment.dataBase64, 'base64');
			if (data.byteLength > 8 * 1024 * 1024) throw new Error(`Attachment ${attachment.name || index + 1} exceeds 8 MiB`);
			const extension = attachment.mimeType.split('/')[1]?.replace(/[^a-z0-9]/gi, '') || 'png';
			const safeName = (attachment.name || `image-${index + 1}.${extension}`).replace(/[^a-z0-9._-]/gi, '_');
			const uri = vscode.Uri.from(URI.joinPath(directory, safeName));
			await vscode.workspace.fs.writeFile(uri, data);
			result.push(uri);
		}
		return result;
	}

	private _boundedInteger(raw: string | null, fallback: number, min: number, max: number): number {
		const parsed = raw === null ? fallback : Number.parseInt(raw, 10);
		return Number.isFinite(parsed) ? Math.min(max, Math.max(min, parsed)) : fallback;
	}

	private async _listProviders(): Promise<RemoteBridgeProviderInfo[]> {
		const models = await vscode.lm.selectChatModels({});
		const providers: RemoteBridgeProviderInfo[] = models.map(model => ({
			id: `vscode-lm:${model.vendor}:${model.id}`,
			name: `${model.vendor} ${model.name}`.trim(),
			type: 'vscode-lm',
			available: true,
			capabilities: ['chat', 'streaming', 'workspace-context'],
			notes: 'Uses VS Code Language Model API and current Copilot authorization.',
		}));

		providers.push(await this._probeCliProvider('codex', 'Codex CLI', ['chat', 'agent-loop', 'terminal-context']));
		providers.push(await this._probeCliProvider('opencode', 'OpenCode CLI', ['chat', 'agent-loop', 'terminal-context']));
		return providers;
	}

	private async _probeCliProvider(command: string, name: string, capabilities: string[]): Promise<RemoteBridgeProviderInfo> {
		const version = await new Promise<string | undefined>(resolve => {
			const child = execFile(command, ['--version'], { timeout: 2000, windowsHide: true }, (err, stdout, stderr) => {
				if (err) {
					resolve(undefined);
					return;
				}
				resolve((stdout || stderr).trim().split(/\r?\n/)[0]);
			});
			child.on('error', () => resolve(undefined));
		});
		return {
			id: `cli:${command}`,
			name,
			type: 'cli',
			available: Boolean(version),
			command,
			version,
			capabilities,
			notes: version ? 'Detected on PATH. Credentials and provider config stay managed by the CLI.' : 'Not found on PATH.',
		};
	}

	private async _listSessionPage(offset: number, limit: number, workspace?: string): Promise<RemoteBridgeSessionPage> {
		const visible = await this._getSessionIndex(workspace);
		const sessions = visible.slice(offset, offset + limit);
		return {
			sessions,
			total: visible.length,
			offset,
			limit,
			hasMore: offset + limit < visible.length,
			nextOffset: Math.min(visible.length, offset + sessions.length),
		};
	}

	private async _getSessionIndex(workspace?: string): Promise<RemoteBridgeSessionInfo[]> {
		const cacheKey = workspace?.trim().toLowerCase() || '*';
		const cached = this._sessionIndexCache.get(cacheKey);
		if (cached && cached.expiresAt > Date.now()) {
			return cached.sessions;
		}
		const inflight = this._sessionIndexInflight.get(cacheKey);
		if (inflight) {
			return inflight;
		}

		const load = this._buildSessionIndex(workspace);
		this._sessionIndexInflight.set(cacheKey, load);
		try {
			const sessions = await load;
			this._sessionIndexCache.set(cacheKey, { expiresAt: Date.now() + 30_000, sessions });
			return sessions;
		} finally {
			if (this._sessionIndexInflight.get(cacheKey) === load) {
				this._sessionIndexInflight.delete(cacheKey);
			}
		}
	}

	private async _buildSessionIndex(workspace?: string): Promise<RemoteBridgeSessionInfo[]> {
		const files = (await this._findSessionFiles()).filter(file => file.source === 'vscodeChatSession' && (!workspace
			|| file.workspaceName?.toLowerCase() === workspace
			|| file.workspaceFolder?.toLowerCase().includes(workspace)));
		const ranked = (await Promise.all(files.map(async file => {
			try {
				const stat = await fs.stat(file.filePath);
				return { file, updatedAt: stat.mtimeMs, size: stat.size };
			} catch {
				return undefined;
			}
		}))).filter((item): item is NonNullable<typeof item> => Boolean(item))
			.sort((a, b) => b.updatedAt - a.updatedAt);
		const sessions: RemoteBridgeSessionInfo[] = [];
		const activePaths = new Set(ranked.map(({ file }) => file.filePath));
		for (const [filePath] of this._sessionFileMetadataCache) {
			if (!activePaths.has(filePath)) {
				this._sessionFileMetadataCache.delete(filePath);
			}
		}

		// Session snapshots can be tens of megabytes each. Reading every file in
		// parallel creates a large allocation/JSON-parse wave that starves the
		// extension host (including WebSocket heartbeat responses). Process one
		// file at a time and yield between files, while reusing unchanged metadata.
		for (const { file, updatedAt, size } of ranked) {
			try {
				const cached = this._sessionFileMetadataCache.get(file.filePath);
				let session: RemoteBridgeSessionInfo | undefined;
				if (cached && cached.mtimeMs === updatedAt && cached.size === size) {
					session = cached.session;
				} else {
					session = await this._readSessionFile(file, false);
					if (session) {
						this._sessionFileMetadataCache.set(file.filePath, { mtimeMs: updatedAt, size, session });
					}
				}
				if (session && session.requestCount > 0) {
					sessions.push(session);
				}
			} catch (err) {
				this._logService.warn(`[RemoteBridge] Failed to read session ${file.filePath}: ${err instanceof Error ? err.message : String(err)}`);
			}
			await new Promise<void>(resolve => setImmediate(resolve));
		}
		return sessions;
	}

	private async _getSession(id: string): Promise<RemoteBridgeSessionInfo | undefined> {
		const activeResource = vscode.window.activeChatPanelSessionResource?.toString();
		const activeSnapshot = activeResource ? remoteChatState.get(activeResource) : undefined;
		if (activeResource && activeSnapshot?.messages.length && (activeResource === id || activeResource.endsWith(id) || activeResource.includes(id))) {
			return {
				id,
				title: activeSnapshot.messages.find(message => message.role === 'user')?.content.slice(0, 80) || id,
				source: 'vscodeChatSession',
				filePath: '',
				workspaceName: vscode.workspace.name,
				updatedAt: activeSnapshot.updatedAt,
				requestCount: activeSnapshot.messages.filter(message => message.role === 'user').length,
				messages: activeSnapshot.messages.filter(message => message.content.trim()),
			};
		}
		const files = (await this._findSessionFiles()).sort((a, b) => Number(b.source === 'vscodeChatSession') - Number(a.source === 'vscodeChatSession'));
		for (const file of files) {
			const fileId = path.basename(file.filePath).replace(/\.jsonl?$/i, '');
			if (fileId !== id) {
				continue;
			}
			try {
				return await this._readSessionFile(file, true);
			} catch (err) {
				this._logService.warn(`[RemoteBridge] Failed to read session ${file.filePath}: ${err instanceof Error ? err.message : String(err)}`);
				return undefined;
			}
		}
		return undefined;
	}

	private async _findSessionFiles(): Promise<Array<{ filePath: string; source: RemoteBridgeSessionInfo['source']; workspaceName?: string; workspaceFolder?: string }>> {
		const files: Array<{ filePath: string; source: RemoteBridgeSessionInfo['source']; workspaceName?: string; workspaceFolder?: string }> = [];
		const storageUri = this._context.storageUri;
		if (storageUri) {
			await this._pushJsonlFiles(URI.joinPath(storageUri, 'transcripts').fsPath, 'officialTranscript', files);
		}

		const appData = process.env.APPDATA || path.join(os.homedir(), 'AppData', 'Roaming');
		const workspaceStorage = path.join(appData, 'Code', 'User', 'workspaceStorage');
		try {
			const entries = await fs.readdir(workspaceStorage, { withFileTypes: true });
			for (const entry of entries) {
				if (!entry.isDirectory()) continue;
				const workspaceDir = path.join(workspaceStorage, entry.name);
				const workspaceFolder = await this._readWorkspaceFolder(workspaceDir);
				await this._pushJsonlFiles(path.join(workspaceDir, 'chatSessions'), 'vscodeChatSession', files, this._workspaceDisplayName(workspaceFolder), workspaceFolder);
			}
		} catch {
			// Missing workspace storage is valid in web/portable setups.
		}
		return files;
	}

	private async _pushJsonlFiles(dir: string, source: RemoteBridgeSessionInfo['source'], output: Array<{ filePath: string; source: RemoteBridgeSessionInfo['source']; workspaceName?: string; workspaceFolder?: string }>, workspaceName?: string, workspaceFolder?: string): Promise<void> {
		try {
			const entries = await fs.readdir(dir, { withFileTypes: true });
			for (const entry of entries) {
				if (entry.isFile() && (entry.name.endsWith('.jsonl') || entry.name.endsWith('.json'))) {
					output.push({ filePath: path.join(dir, entry.name), source, workspaceName, workspaceFolder });
				}
			}
		} catch {
			// Optional location.
		}
	}

	private async _readWorkspaceFolder(workspaceDir: string): Promise<string | undefined> {
		try {
			const raw = await fs.readFile(path.join(workspaceDir, 'workspace.json'), 'utf8');
			const json = JSON.parse(raw);
			return typeof json.folder === 'string' ? decodeURIComponent(json.folder.replace(/^file:\/\//, '')) : undefined;
		} catch {
			return undefined;
		}
	}

	private _workspaceDisplayName(folder?: string): string | undefined {
		if (!folder) return undefined;
		const normalized = folder.replace(/^\/+([a-zA-Z]:)/, '$1').replace(/\//g, path.sep);
		return path.basename(normalized) || normalized;
	}

	private async _readSessionFile(file: { filePath: string; source: RemoteBridgeSessionInfo['source']; workspaceName?: string; workspaceFolder?: string }, includeMessages: boolean): Promise<RemoteBridgeSessionInfo | undefined> {
		const stat = await fs.stat(file.filePath);
		const raw = await fs.readFile(file.filePath, 'utf8');
		const id = path.basename(file.filePath).replace(/\.jsonl?$/i, '');
		if (file.source === 'officialTranscript') {
			const messages = includeMessages ? this._readTranscriptMessages(raw) : undefined;
			return {
				id,
				title: messages?.find(m => m.role === 'user')?.content.slice(0, 80) || id,
				source: file.source,
				filePath: file.filePath,
				updatedAt: stat.mtimeMs,
				createdAt: stat.birthtimeMs,
				requestCount: messages?.filter(m => m.role === 'user').length || 0,
				messages,
			};
		}

		const state = file.filePath.endsWith('.jsonl') ? this._replayJsonlState(raw) : JSON.parse(raw);
		const requests = Array.isArray(state.requests) ? state.requests : [];
		const messages = includeMessages ? this._readVsCodeChatMessages(requests) : undefined;
		return {
			id: String(state.sessionId || id),
			title: typeof state.customTitle === 'string' ? state.customTitle : this._requestText(requests[0]).slice(0, 80) || id,
			source: file.source,
			filePath: file.filePath,
			workspaceName: file.workspaceName,
			workspaceFolder: file.workspaceFolder,
			updatedAt: stat.mtimeMs,
			createdAt: typeof state.creationDate === 'number' ? state.creationDate : stat.birthtimeMs,
			requestCount: requests.length,
			messages,
		};
	}

	private _readTranscriptMessages(raw: string): Array<{ id: string; role: 'user' | 'assistant' | 'tool'; content: string; timestamp: number }> {
		const messages: Array<{ id: string; role: 'user' | 'assistant' | 'tool'; content: string; timestamp: number }> = [];
		for (const line of raw.split(/\r?\n/)) {
			if (!line.trim()) continue;
			const entry = JSON.parse(line) as { type?: string; timestamp?: string; data?: Record<string, unknown> };
			const timestamp = entry.timestamp ? Date.parse(entry.timestamp) : Date.now();
			if (entry.type === 'user.message' && typeof entry.data?.content === 'string') {
				if (entry.data.content.trim()) messages.push({ id: `transcript:${messages.length}`, role: 'user', content: entry.data.content, timestamp });
			} else if (entry.type === 'assistant.message' && typeof entry.data?.content === 'string') {
				if (entry.data.content.trim()) messages.push({ id: `transcript:${messages.length}`, role: 'assistant', content: entry.data.content, timestamp });
			} else if (entry.type === 'tool.execution_complete') {
				const result = entry.data?.result as Record<string, unknown> | undefined;
				if (typeof result?.content === 'string') {
					if (result.content.trim()) messages.push({ id: `transcript:${messages.length}`, role: 'tool', content: result.content, timestamp });
				}
			}
		}
		return messages;
	}

	private _replayJsonlState(raw: string): Record<string, any> {
		let state: Record<string, any> = {};
		for (const line of raw.split(/\r?\n/)) {
			if (!line.trim()) continue;
			const item = JSON.parse(line);
			if (item.kind === 0 && item.v && typeof item.v === 'object') {
				state = { ...item.v };
			} else if (item.kind === 1 && Array.isArray(item.k)) {
				this._setDeepValue(state, item.k, item.v);
			} else if (item.kind === 2 && Array.isArray(item.k)) {
				this._appendDeepValue(state, item.k, item.v);
			}
		}
		return state;
	}

	private _appendDeepValue(target: Record<string, any>, keys: unknown[], value: unknown): void {
		let current: any = target;
		for (let i = 0; i < keys.length - 1; i++) {
			const key = keys[i] as string | number;
			const nextKey = keys[i + 1];
			if (!current[key] || typeof current[key] !== 'object') {
				current[key] = typeof nextKey === 'number' ? [] : {};
			}
			current = current[key];
		}
		const key = keys[keys.length - 1] as string | number;
		if (Array.isArray(current[key]) && Array.isArray(value)) {
			current[key].push(...value);
		} else {
			current[key] = value;
		}
	}

	private _setDeepValue(target: Record<string, any>, keys: unknown[], value: unknown): void {
		let current: any = target;
		for (let i = 0; i < keys.length - 1; i++) {
			const key = keys[i] as string | number;
			const nextKey = keys[i + 1];
			if (!current[key] || typeof current[key] !== 'object') {
				current[key] = typeof nextKey === 'number' ? [] : {};
			}
			current = current[key];
		}
		current[keys[keys.length - 1] as string | number] = value;
	}

	private _readVsCodeChatMessages(requests: unknown[]): RemoteBridgeSessionMessage[] {
		const messages: RemoteBridgeSessionMessage[] = [];
		for (const req of requests) {
			if (!req || typeof req !== 'object') continue;
			const request = req as Record<string, any>;
			const timestamp = typeof request.timestamp === 'number' ? request.timestamp : Date.now();
			const user = this._requestText(request);
			const requestId = typeof request.id === 'string' ? request.id : typeof request.requestId === 'string' ? request.requestId : `request:${messages.length}`;
			const modelId = typeof request.modelId === 'string' ? request.modelId : undefined;
			if (user) messages.push({ id: `${requestId}:user`, role: 'user', content: user, timestamp });
			const response = Array.isArray(request.response) ? request.response : [];
			let textBuffer = '';
			let partIndex = 0;
			const progressSeen = new Set<string>();
			const flushText = () => {
				const content = textBuffer.trim();
				if (content) messages.push({ id: `${requestId}:assistant:${partIndex++}`, role: 'assistant', content, timestamp: timestamp + partIndex, modelId, kind: 'message' });
				textBuffer = '';
			};
			for (const rawPart of response) {
				if (!rawPart || typeof rawPart !== 'object') continue;
				const part = rawPart as Record<string, any>;
				const kind = typeof part.kind === 'string' ? part.kind : '';
				const normalizedKind = kind.toLowerCase();
				const value = this._sessionPartText(part.value);
				if (kind === 'thinking') {
					flushText();
					if (value) messages.push({ id: `${requestId}:thinking:${partIndex++}`, role: 'assistant', content: value, timestamp: timestamp + partIndex, modelId, kind: 'thinking' });
				} else if (kind === 'progressTaskSerialized' || normalizedKind.includes('progress')) {
					flushText();
					const content = this._sessionPartText(part.content) || value;
					if (content && !this._isInternalChatNoise(content) && !progressSeen.has(content)) {
						progressSeen.add(content);
						messages.push({ id: `${requestId}:progress:${partIndex++}`, role: 'assistant', content, timestamp: timestamp + partIndex, modelId, kind: 'thinking' });
					}
				} else if (kind === 'textEditGroup') {
					flushText();
					const filePath = String(part.uri?.fsPath ?? part.uri?.path ?? '');
					const edits = Array.isArray(part.edits) ? part.edits.flat().filter(Boolean) as Array<Record<string, any>> : [];
					const output = edits.map(edit => {
						const range = edit.range as Record<string, unknown> | undefined;
						const start = range?.startLineNumber;
						const end = range?.endLineNumber;
						return `${start ? `@@ ${start}${end && end !== start ? `-${end}` : ''} @@\n` : ''}${String(edit.text ?? '')}`;
					}).join('\n\n').slice(0, 20_000);
					messages.push({
						id: `${requestId}:edit:${partIndex++}`, role: 'assistant',
						content: filePath ? `编辑 ${path.basename(filePath)}` : '编辑文件',
						timestamp: timestamp + partIndex, modelId, kind: 'tool', toolName: '编辑文件',
						toolStatus: part.done === false ? 'running' : 'completed', toolInput: filePath || undefined, toolOutput: output || undefined,
					});
				} else if (kind === 'todoList') {
					flushText();
					const todos = Array.isArray(part.todoList) ? part.todoList as Array<Record<string, unknown>> : [];
					const output = todos.map(todo => `[${todo.status === 'completed' ? 'x' : todo.status === 'in-progress' ? '>' : ' '}] ${String(todo.title ?? '')}`).join('\n');
					messages.push({ id: `${requestId}:todos:${partIndex++}`, role: 'assistant', content: '更新任务列表', timestamp: timestamp + partIndex, modelId, kind: 'tool', toolName: '任务列表', toolStatus: 'completed', toolOutput: output });
				} else if (normalizedKind.includes('tool')) {
					flushText();
					const toolPartIndex = partIndex++;
					const data = (part.toolSpecificData ?? part.value?.toolSpecificData ?? part.value ?? {}) as Record<string, unknown>;
					const toolName = String(part.toolName ?? data.toolName ?? part.toolId ?? part.name ?? 'Tool');
					const toolOutput = this._sessionToolText(data.output ?? data.result ?? part.output);
					const toolInput = this._sessionToolText(data.input ?? data.partialInput ?? data.commandLine ?? part.input);
					const complete = part.isComplete === true || data.isComplete === true;
					const failed = part.isError === true || data.isError === true;
					const content = this._sessionPartText(part.pastTenseMessage ?? part.invocationMessage) || value || toolOutput || toolName;
					messages.push({
						id: `${requestId}:tool:${String(part.toolCallId ?? data.toolCallId ?? toolPartIndex)}`,
						role: 'assistant', content, timestamp: timestamp + partIndex, modelId, kind: 'tool', toolName,
						toolStatus: failed ? 'error' : complete ? 'completed' : 'running', toolInput, toolOutput,
					});
				} else if (normalizedKind.includes('reference') || normalizedKind.includes('anchor') || normalizedKind.includes('citation')) {
					const target = part.value2 ?? part.value?.value ?? part.value;
					const uri = this._uriText(target);
					const label = String(part.title ?? part.value?.variableName ?? this._uriLabel(target) ?? uri ?? '引用');
					const snippet = typeof part.snippet === 'string' && part.snippet ? `\n\n\`\`\`\n${part.snippet}\n\`\`\`` : '';
					textBuffer += uri ? `\n\n> 引用：[${this._escapeMarkdown(label)}](${uri})${snippet}\n` : `\n\n> 引用：${label}${snippet}\n`;
				} else if (value && !this._isInternalChatNoise(value) && (!kind || normalizedKind.includes('markdown') || normalizedKind === 'text')) {
					textBuffer += value;
				}
			}
			flushText();
			if (!response.length) {
				const assistant = this._resultText(request.result);
				if (assistant) messages.push({ id: `${requestId}:assistant`, role: 'assistant', content: assistant, timestamp: timestamp + 1, modelId, kind: 'message' });
			}
		}
		return messages;
	}

	private _sessionPartText(value: unknown): string {
		if (typeof value === 'string') return value;
		if (Array.isArray(value)) return value.map(item => this._sessionPartText(item)).filter(Boolean).join('\n');
		if (value && typeof value === 'object') {
			const nested = value as Record<string, unknown>;
			return this._sessionPartText(nested.value ?? nested.text ?? nested.message);
		}
		return '';
	}

	private _sessionToolText(value: unknown): string | undefined {
		if (value === undefined || value === null) return undefined;
		const text = this._sessionPartText(value);
		if (text) return text.slice(0, 20_000);
		try { return JSON.stringify(value, null, 2).slice(0, 20_000); } catch { return String(value).slice(0, 20_000); }
	}

	private _isInternalChatNoise(value: string): boolean {
		return /ENOENT[^\n]*o200k_base\.tiktoken|github\.copilot-chat-[^\s\\/]+[\\/]dist[\\/]o200k_base\.tiktoken/i.test(value);
	}

	private _uriText(value: any): string | undefined {
		const target = value?.uri ?? value;
		if (!target) return undefined;
		if (typeof target === 'string') return target;
		if (typeof target.scheme === 'string' && typeof target.path === 'string') {
			const authority = typeof target.authority === 'string' && target.authority ? `//${target.authority}` : '';
			return `${target.scheme}:${authority}${target.path}`;
		}
		if (typeof target.toString === 'function') {
			const text = target.toString();
			return text && text !== '[object Object]' ? text : undefined;
		}
		return undefined;
	}

	private _uriLabel(value: any): string | undefined {
		const target = value?.uri ?? value;
		const valuePath = typeof target?.path === 'string' ? target.path : typeof target?.fsPath === 'string' ? target.fsPath : undefined;
		return valuePath?.split(/[\\/]/).filter(Boolean).pop() || valuePath;
	}

	private _escapeMarkdown(value: string): string {
		return value.replace(/[\\[\]]/g, '\\$&');
	}

	private _requestText(request: any): string {
		if (!request) return '';
		if (typeof request.message?.text === 'string') return request.message.text;
		if (Array.isArray(request.message?.parts)) {
			return request.message.parts.map((part: any) => typeof part.text === 'string' ? part.text : '').filter(Boolean).join('\n');
		}
		return typeof request.prompt === 'string' ? request.prompt : '';
	}

	private _resultText(result: any): string {
		const rounds = result?.metadata?.toolCallRounds;
		if (Array.isArray(rounds)) {
			return rounds.map((round: any) => typeof round.response === 'string' ? round.response.trim() : '').filter(Boolean).join('\n\n');
		}
		return typeof result?.errorDetails?.message === 'string' ? result.errorDetails.message : '';
	}

	private _parseTodoContext(value?: string): RemoteChatTodoItem[] {
		if (!value) return [];
		const items: RemoteChatTodoItem[] = [];
		const lines = value.split(/\r?\n/);
		for (let index = 0; index < lines.length; index++) {
			const line = lines[index];
			const match = line.match(/^\s*(?:[-*]\s*)?\[([ xX~>])\]\s*(.+)$/)
				?? line.match(/^\s*(?:[-*]|\d+[.)])\s*(?:\[)?(not-started|in-progress|completed|pending|active|done)(?:\])?\s*[:|-]\s*(.+)$/i);
			if (!match) continue;
			const marker = match[1].toLowerCase();
			items.push({
				id: items.length + 1,
				title: match[2].trim(),
				status: marker === 'x' || marker === 'completed' || marker === 'done'
					? 'completed'
					: marker === '~' || marker === '>' || marker === 'in-progress' || marker === 'active'
						? 'in-progress'
						: 'not-started',
			});
		}
		return items;
	}
}
