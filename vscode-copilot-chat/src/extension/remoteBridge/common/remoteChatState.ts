/*---------------------------------------------------------------------------------------------
 *  Copyright (c) Microsoft Corporation. All rights reserved.
 *  Licensed under the MIT License. See License.txt in the project root for license information.
 *--------------------------------------------------------------------------------------------*/

import type * as vscode from 'vscode';
import {
	ChatResponseAnchorPart,
	ChatResponseCodeCitationPart,
	ChatResponseFileTreePart,
	ChatResponseMarkdownPart,
	ChatResponseMarkdownWithVulnerabilitiesPart,
	ChatResponseProgressPart,
	ChatResponseProgressPart2,
	ChatResponseReferencePart,
	ChatResponseReferencePart2,
	ChatResponseThinkingProgressPart,
	ChatResponseWarningPart,
	ChatToolInvocationPart,
} from '../../../vscodeTypes';

export interface RemoteChatTodoItem {
	id: number;
	title: string;
	status: 'not-started' | 'in-progress' | 'completed';
}

export interface RemoteChatUsage {
	promptTokens: number;
	completionTokens: number;
	outputBuffer?: number;
	maxInputTokens: number;
}

export interface RemoteChatSessionSnapshot {
	sessionResource: string;
	requestId?: string;
	modelId?: string;
	modelName?: string;
	reasoningEffort?: string;
	maxInputTokens?: number;
	status: 'idle' | 'running' | 'completed' | 'cancelled' | 'error';
	usage?: RemoteChatUsage;
	todos: RemoteChatTodoItem[];
	messages: Array<{ id: string; role: 'user' | 'assistant'; content: string; timestamp: number; kind?: 'message' | 'thinking' | 'tool'; toolName?: string; toolStatus?: 'running' | 'completed' | 'error'; toolInput?: string; toolOutput?: string }>;
	updatedAt: number;
}

export type RemoteChatEvent =
	| { type: 'activeSessionChanged'; sessionResource?: string }
	| { type: 'chatStart'; sessionResource: string; requestId: string; prompt: string; modelId: string; modelName: string; reasoningEffort?: string; maxInputTokens: number }
	| { type: 'chatDelta'; sessionResource: string; requestId: string; delta: string }
	| { type: 'chatThinkingDelta'; sessionResource: string; requestId: string; delta: string; thinkingId?: string; done?: boolean }
	| { type: 'chatUsage'; sessionResource: string; requestId: string; usage: RemoteChatUsage }
	| { type: 'todoUpdated'; sessionResource: string; requestId: string; items: RemoteChatTodoItem[] }
	| { type: 'toolUpdated'; sessionResource: string; requestId: string; toolCallId: string; toolName: string; status: 'running' | 'completed' | 'error'; message?: string; input?: string; output?: string }
	| { type: 'chatDone'; sessionResource: string; requestId: string }
	| { type: 'chatError'; sessionResource: string; requestId: string; error: string }
	| { type: 'chatCancelled'; sessionResource: string; requestId: string };

class RemoteChatState {
	private readonly _listeners = new Set<(event: RemoteChatEvent) => void>();
	private readonly _sessions = new Map<string, RemoteChatSessionSnapshot>();
	private readonly _nextModelConfiguration = new Map<string, Record<string, unknown>>();

	listen(listener: (event: RemoteChatEvent) => void): vscode.Disposable {
		this._listeners.add(listener);
		return { dispose: () => this._listeners.delete(listener) };
	}

	get(sessionResource: string): RemoteChatSessionSnapshot | undefined {
		return this._sessions.get(sessionResource);
	}

	setNextModelConfiguration(sessionResource: string, configuration: Record<string, unknown>): void {
		this._nextModelConfiguration.set(sessionResource, configuration);
	}

	consumeNextModelConfiguration(sessionResource: string): Record<string, unknown> | undefined {
		const configuration = this._nextModelConfiguration.get(sessionResource);
		this._nextModelConfiguration.delete(sessionResource);
		return configuration;
	}

	start(request: vscode.ChatRequest, history: readonly (vscode.ChatRequestTurn | vscode.ChatResponseTurn)[]): void {
		const sessionResource = request.sessionResource.toString();
		const previous = this._sessions.get(sessionResource)?.messages;
		const messages = previous?.length ? previous.map(message => ({ ...message })) : this._historyMessages(history);
		messages.push({ id: `${request.id}:user`, role: 'user', content: request.prompt, timestamp: Date.now() });
		messages.push({ id: `${request.id}:assistant`, role: 'assistant', content: '', timestamp: Date.now() + 1 });
		const snapshot: RemoteChatSessionSnapshot = {
			sessionResource,
			requestId: request.id,
			modelId: request.model.id,
			modelName: request.model.name,
			reasoningEffort: typeof request.modelConfiguration?.reasoningEffort === 'string' ? request.modelConfiguration.reasoningEffort : undefined,
			maxInputTokens: request.model.maxInputTokens,
			status: 'running',
			usage: undefined,
			todos: this._sessions.get(sessionResource)?.todos ?? [],
			messages,
			updatedAt: Date.now(),
		};
		this._sessions.set(sessionResource, snapshot);
		this._emit({
			type: 'chatStart', sessionResource, requestId: request.id, modelId: request.model.id,
			prompt: request.prompt, modelName: request.model.name, reasoningEffort: snapshot.reasoningEffort, maxInputTokens: request.model.maxInputTokens
		});
	}

	part(sessionResource: string, requestId: string, part: unknown): void {
		const record = part && typeof part === 'object' ? part as Record<string, any> : undefined;
		const partName = record?.constructor?.name || '';
		if (part instanceof ChatResponseMarkdownPart || part instanceof ChatResponseMarkdownWithVulnerabilitiesPart || partName === 'ChatResponseMarkdownPart' || partName === 'ChatResponseMarkdownWithVulnerabilitiesPart') {
			const value = typeof record?.value === 'string' ? record.value : record?.value?.value;
			this._appendAssistant(sessionResource, requestId, value);
		} else if (part instanceof ChatResponseThinkingProgressPart || partName === 'ChatResponseThinkingProgressPart') {
			const value = Array.isArray(record?.value) ? record.value.join('\n') : record?.value;
			const done = record?.metadata?.vscodeReasoningDone === true;
			if (value || done) {
				this._upsertThinking(sessionResource, requestId, record?.id || 'default', value || '', done);
				this._emit({ type: 'chatThinkingDelta', sessionResource, requestId, delta: value, thinkingId: record?.id, done });
			}
		} else if (part instanceof ChatToolInvocationPart || partName === 'ChatToolInvocationPart' || (record && typeof record.toolCallId === 'string' && typeof record.toolName === 'string')) {
			const data = record?.toolSpecificData as ({ todoList?: Array<{ id: number; title: string; status: number }> } & Record<string, unknown>) | undefined;
			if (Array.isArray(data?.todoList)) {
				const items = data.todoList.map(item => ({
					id: item.id,
					title: item.title,
					status: item.status === 3 ? 'completed' as const : item.status === 2 ? 'in-progress' as const : 'not-started' as const,
				}));
				this.todos(sessionResource, requestId, items);
			}
			const messageValue = record?.isComplete ? record?.pastTenseMessage : record?.invocationMessage;
			const toolEvent = {
				type: 'toolUpdated',
				sessionResource,
				requestId,
				toolCallId: record!.toolCallId,
				toolName: record!.toolName,
				status: record?.isError ? 'error' : record?.isComplete ? 'completed' : 'running',
				message: typeof messageValue === 'string' ? messageValue : messageValue?.value,
				input: this._toolText(data?.input ?? data?.partialInput ?? data?.commandLine),
				output: this._toolText(data?.output ?? data?.result),
			} as const;
			const snapshot = this._sessions.get(sessionResource);
			const messages = snapshot?.messages.slice() ?? [];
			const toolMessage = {
				id: `${requestId}:tool:${record!.toolCallId}`,
				role: 'assistant' as const,
				content: toolEvent.message || toolEvent.output || record!.toolName,
				timestamp: Date.now(),
				kind: 'tool' as const,
				toolName: record!.toolName,
				toolStatus: toolEvent.status,
				toolInput: toolEvent.input,
				toolOutput: toolEvent.output,
			};
			const index = messages.findIndex(message => message.id === toolMessage.id);
			if (index >= 0) messages[index] = toolMessage; else messages.splice(Math.max(0, messages.length - 1), 0, toolMessage);
			this._update(sessionResource, { messages });
			this._emit(toolEvent);
		} else if (part instanceof ChatResponseAnchorPart || partName === 'ChatResponseAnchorPart') {
			const target = record?.value2 ?? record?.value;
			const uri = this._uriText(target);
			const title = String(record?.title || this._uriLabel(target) || uri || '引用');
			this._appendAssistant(sessionResource, requestId, uri ? `[${this._escapeMarkdown(title)}](${uri})` : title);
		} else if (part instanceof ChatResponseReferencePart || part instanceof ChatResponseReferencePart2 || partName === 'ChatResponseReferencePart' || partName === 'ChatResponseReferencePart2') {
			const raw = record?.value?.value ?? record?.value;
			const uri = this._uriText(raw);
			const label = String(record?.value?.variableName || this._uriLabel(raw) || uri || raw || '引用');
			const text = uri ? `\n\n> 引用：[${this._escapeMarkdown(label)}](${uri})\n` : `\n\n> 引用：${label}\n`;
			this._appendAssistant(sessionResource, requestId, text);
		} else if (part instanceof ChatResponseCodeCitationPart || partName === 'ChatResponseCodeCitationPart') {
			const uri = this._uriText(record?.value);
			const label = this._uriLabel(record?.value) || uri || '代码引用';
			const snippet = typeof record?.snippet === 'string' && record.snippet ? `\n\n\`\`\`\n${record.snippet}\n\`\`\`` : '';
			this._appendAssistant(sessionResource, requestId, `\n\n> 代码引用：[${this._escapeMarkdown(label)}](${uri})${record?.license ? ` · ${record.license}` : ''}${snippet}\n`);
		} else if (part instanceof ChatResponseFileTreePart || partName === 'ChatResponseFileTreePart') {
			const files = Array.isArray(record?.value) ? record.value : [];
			const tree = files.map((item: any) => `- ${this._uriLabel(item?.uri ?? item) || item?.name || String(item)}`).join('\n');
			this._appendAssistant(sessionResource, requestId, tree ? `\n${tree}\n` : '');
		} else if (part instanceof ChatResponseWarningPart || partName === 'ChatResponseWarningPart') {
			const value = typeof record?.value === 'string' ? record.value : record?.value?.value;
			this._appendAssistant(sessionResource, requestId, value && !this._isInternalChatNoise(value) ? `\n\n> ⚠️ ${value}\n` : '');
		} else if (part instanceof ChatResponseProgressPart || part instanceof ChatResponseProgressPart2 || partName === 'ChatResponseProgressPart' || partName === 'ChatResponseProgressPart2') {
			const value = typeof record?.value === 'string' ? record.value : '';
			if (value) {
				this._upsertThinking(sessionResource, requestId, `progress:${value.slice(0, 40)}`, value, true);
				this._emit({ type: 'chatThinkingDelta', sessionResource, requestId, delta: value, thinkingId: `progress:${value.slice(0, 40)}`, done: true });
			}
		}
	}

	private _appendAssistant(sessionResource: string, requestId: string, value: string): void {
		if (!value) return;
		const snapshot = this._sessions.get(sessionResource);
		const messages = snapshot?.messages.slice() ?? [];
		const index = messages.findIndex(message => message.id === `${requestId}:assistant`);
		if (index >= 0) messages[index] = { ...messages[index], content: messages[index].content + value };
		this._update(sessionResource, { messages });
		this._emit({ type: 'chatDelta', sessionResource, requestId, delta: value });
	}

	private _isInternalChatNoise(value: string): boolean {
		return /ENOENT[^\n]*o200k_base\.tiktoken|github\.copilot-chat-[^\s\\/]+[\\/]dist[\\/]o200k_base\.tiktoken/i.test(value);
	}

	private _upsertThinking(sessionResource: string, requestId: string, thinkingId: string, value: string, done: boolean): void {
		const snapshot = this._sessions.get(sessionResource);
		const messages = snapshot?.messages.slice() ?? [];
		const id = `${requestId}:thinking:${thinkingId}`;
		const index = messages.findIndex(message => message.id === id);
		const existing = index >= 0 ? messages[index] : undefined;
		const thinking = {
			id, role: 'assistant' as const, content: (existing?.content || '') + value,
			timestamp: existing?.timestamp || Date.now(), kind: 'thinking' as const,
			toolName: '思考过程', toolStatus: done ? 'completed' as const : 'running' as const,
		};
		if (index >= 0) messages[index] = thinking; else messages.splice(Math.max(0, messages.length - 1), 0, thinking);
		this._update(sessionResource, { messages });
	}

	usage(sessionResource: string, requestId: string, usage: vscode.ChatResultUsage, maxInputTokens: number): void {
		const normalized: RemoteChatUsage = {
			promptTokens: usage.promptTokens,
			completionTokens: usage.completionTokens,
			outputBuffer: usage.outputBuffer,
			maxInputTokens,
		};
		this._update(sessionResource, { usage: normalized });
		this._emit({ type: 'chatUsage', sessionResource, requestId, usage: normalized });
	}

	todos(sessionResource: string, requestId: string, items: RemoteChatTodoItem[]): void {
		this._update(sessionResource, { todos: items });
		this._emit({ type: 'todoUpdated', sessionResource, requestId, items });
	}

	finish(sessionResource: string, requestId: string, status: 'completed' | 'cancelled' | 'error', error?: string): void {
		this._update(sessionResource, { status });
		this._emit(status === 'completed'
			? { type: 'chatDone', sessionResource, requestId }
			: status === 'cancelled'
				? { type: 'chatCancelled', sessionResource, requestId }
				: { type: 'chatError', sessionResource, requestId, error: error || 'Chat request failed' });
	}

	activeSessionChanged(sessionResource?: string): void {
		this._emit({ type: 'activeSessionChanged', sessionResource });
	}

	private _update(sessionResource: string, update: Partial<RemoteChatSessionSnapshot>): void {
		const existing = this._sessions.get(sessionResource) ?? { sessionResource, status: 'idle' as const, todos: [], messages: [], updatedAt: Date.now() };
		this._sessions.set(sessionResource, { ...existing, ...update, updatedAt: Date.now() });
	}

	private _emit(event: RemoteChatEvent): void {
		for (const listener of this._listeners) {
			try { listener(event); } catch { }
		}
	}

	private _toolText(value: unknown): string | undefined {
		const limit = 200_000;
		if (value === undefined || value === null) return undefined;
		if (typeof value === 'string') return value.replace(/\x1b\[[0-?]*[ -\/]*[@-~]/g, '').slice(0, limit);
		if (value instanceof Uint8Array) return new TextDecoder().decode(value).slice(0, limit);
		if (Array.isArray(value)) return value.map(item => this._toolText(item)).filter(Boolean).join('\n').slice(0, limit);
		if (typeof value === 'object') {
			const record = value as Record<string, unknown>;
			if (typeof record.text === 'string') return this._toolText(record.text);
			if (typeof record.value === 'string') return this._toolText(record.value);
			if (record.data instanceof Uint8Array) return this._toolText(record.data);
			if (record.commandLine) return this._toolText(record.commandLine);
			if (record.output) return this._toolText(record.output);
			if (record.result) return this._toolText(record.result);
			if (typeof record.original === 'string') return this._toolText(record.toolEdited ?? record.userEdited ?? record.original);
		}
		try { return JSON.stringify(value, null, 2).slice(0, limit); } catch { return String(value).slice(0, limit); }
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
		const path = typeof target?.path === 'string' ? target.path : typeof target?.fsPath === 'string' ? target.fsPath : undefined;
		if (!path) return undefined;
		return path.split(/[\\/]/).filter(Boolean).pop() || path;
	}

	private _escapeMarkdown(value: string): string {
		return value.replace(/[\\[\]]/g, '\\$&');
	}

	private _historyMessages(history: readonly (vscode.ChatRequestTurn | vscode.ChatResponseTurn)[]): RemoteChatSessionSnapshot['messages'] {
		const messages: RemoteChatSessionSnapshot['messages'] = [];
		for (let index = 0; index < history.length; index++) {
			const turn = history[index] as unknown as { id?: string; prompt?: string; response?: readonly unknown[] };
			if (typeof turn.prompt === 'string' && turn.prompt.trim()) {
				messages.push({ id: turn.id || `history:${index}:user`, role: 'user', content: turn.prompt, timestamp: index * 2 });
			} else if (Array.isArray(turn.response)) {
				const turnId = turn.id || `history:${index}`;
				let content = '';
				for (let partIndex = 0; partIndex < turn.response.length; partIndex++) {
					const part = turn.response[partIndex] as Record<string, any>;
					const name = part?.constructor?.name || '';
					const value = part?.value;
					if (name.includes('Markdown') || name === 'ChatResponseWarningPart') {
						const text = typeof value === 'string' ? value : value?.value;
						if (text) content += name === 'ChatResponseWarningPart' ? `\n\n> ⚠️ ${text}\n` : text;
					} else if (name === 'ChatResponseAnchorPart') {
						const target = part.value2 ?? part.value;
						const uri = this._uriText(target);
						const label = String(part.title || this._uriLabel(target) || uri || '引用');
						content += uri ? `[${this._escapeMarkdown(label)}](${uri})` : label;
					} else if (name === 'ChatResponseReferencePart' || name === 'ChatResponseReferencePart2') {
						const target = part.value?.value ?? part.value;
						const uri = this._uriText(target);
						const label = String(part.value?.variableName || this._uriLabel(target) || uri || target || '引用');
						content += uri ? `\n\n> 引用：[${this._escapeMarkdown(label)}](${uri})\n` : `\n\n> 引用：${label}\n`;
					} else if (name === 'ChatResponseThinkingProgressPart' || name === 'ChatResponseProgressPart' || name === 'ChatResponseProgressPart2') {
						const text = Array.isArray(value) ? value.join('\n') : String(value || '');
						if (text) messages.push({ id: `${turnId}:thinking:${part.id || partIndex}`, role: 'assistant', content: text, timestamp: index * 2 + partIndex / 100, kind: 'thinking', toolName: '思考过程', toolStatus: 'completed' });
					} else if (name === 'ChatToolInvocationPart' || (typeof part?.toolCallId === 'string' && typeof part?.toolName === 'string')) {
						const data = part.toolSpecificData as Record<string, unknown> | undefined;
						const messageValue = part.isComplete ? part.pastTenseMessage : part.invocationMessage;
						const message = typeof messageValue === 'string' ? messageValue : messageValue?.value;
						messages.push({
							id: `${turnId}:tool:${part.toolCallId || partIndex}`, role: 'assistant',
							content: message || part.toolName || '工具调用', timestamp: index * 2 + partIndex / 100,
							kind: 'tool', toolName: part.toolName || '工具调用',
							toolStatus: part.isError ? 'error' : 'completed',
							toolInput: this._toolText(data?.input ?? data?.partialInput ?? data?.commandLine),
							toolOutput: this._toolText(data?.output ?? data?.result),
						});
					} else if (typeof value === 'string') {
						content += value;
					}
				}
				content = content.trim();
				if (content) messages.push({ id: turn.id || `history:${index}:assistant`, role: 'assistant', content, timestamp: index * 2 + 1 });
			}
		}
		return messages;
	}
}

export const remoteChatState = new RemoteChatState();
