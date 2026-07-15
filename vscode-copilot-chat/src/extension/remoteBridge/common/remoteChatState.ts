/*---------------------------------------------------------------------------------------------
 *  Copyright (c) Microsoft Corporation. All rights reserved.
 *  Licensed under the MIT License. See License.txt in the project root for license information.
 *--------------------------------------------------------------------------------------------*/

import type * as vscode from 'vscode';
import { ChatResponseMarkdownPart, ChatResponseMarkdownWithVulnerabilitiesPart, ChatResponseThinkingProgressPart, ChatToolInvocationPart } from '../../../vscodeTypes';

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
	messages: Array<{ id: string; role: 'user' | 'assistant'; content: string; timestamp: number; kind?: 'message' | 'tool'; toolName?: string; toolStatus?: 'running' | 'completed' | 'error'; toolInput?: string; toolOutput?: string }>;
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
		const messages = this._historyMessages(history);
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
		if (part instanceof ChatResponseMarkdownPart || part instanceof ChatResponseMarkdownWithVulnerabilitiesPart) {
			const value = typeof part.value === 'string' ? part.value : part.value.value;
			if (value) {
				const snapshot = this._sessions.get(sessionResource);
				const messages = snapshot?.messages.slice() ?? [];
				const index = messages.findIndex(message => message.id === `${requestId}:assistant`);
				if (index >= 0) messages[index] = { ...messages[index], content: messages[index].content + value };
				this._update(sessionResource, { messages });
				this._emit({ type: 'chatDelta', sessionResource, requestId, delta: value });
			}
		} else if (part instanceof ChatResponseThinkingProgressPart) {
			const value = Array.isArray(part.value) ? part.value.join('\n') : part.value;
			const done = part.metadata?.vscodeReasoningDone === true;
			if (value || done) {
				this._emit({ type: 'chatThinkingDelta', sessionResource, requestId, delta: value, thinkingId: part.id, done });
			}
		} else if (part instanceof ChatToolInvocationPart) {
			const data = part.toolSpecificData as ({ todoList?: Array<{ id: number; title: string; status: number }> } & Record<string, unknown>) | undefined;
			if (Array.isArray(data?.todoList)) {
				const items = data.todoList.map(item => ({
					id: item.id,
					title: item.title,
					status: item.status === 3 ? 'completed' as const : item.status === 2 ? 'in-progress' as const : 'not-started' as const,
				}));
				this.todos(sessionResource, requestId, items);
			}
			const messageValue = part.isComplete ? part.pastTenseMessage : part.invocationMessage;
			const toolEvent = {
				type: 'toolUpdated',
				sessionResource,
				requestId,
				toolCallId: part.toolCallId,
				toolName: part.toolName,
				status: part.isError ? 'error' : part.isComplete ? 'completed' : 'running',
				message: typeof messageValue === 'string' ? messageValue : messageValue?.value,
				input: this._toolText(data?.input ?? data?.partialInput ?? data?.commandLine),
				output: this._toolText(data?.output ?? data?.result),
			} as const;
			const snapshot = this._sessions.get(sessionResource);
			const messages = snapshot?.messages.slice() ?? [];
			const toolMessage = {
				id: `${requestId}:tool:${part.toolCallId}`,
				role: 'assistant' as const,
				content: toolEvent.message || toolEvent.output || part.toolName,
				timestamp: Date.now(),
				kind: 'tool' as const,
				toolName: part.toolName,
				toolStatus: toolEvent.status,
				toolInput: toolEvent.input,
				toolOutput: toolEvent.output,
			};
			const index = messages.findIndex(message => message.id === toolMessage.id);
			if (index >= 0) messages[index] = toolMessage; else messages.splice(Math.max(0, messages.length - 1), 0, toolMessage);
			this._update(sessionResource, { messages });
			this._emit(toolEvent);
		}
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
		if (value === undefined || value === null) return undefined;
		if (typeof value === 'string') return value.replace(/\x1b\[[0-?]*[ -\/]*[@-~]/g, '').slice(0, 20_000);
		if (value instanceof Uint8Array) return new TextDecoder().decode(value).slice(0, 20_000);
		if (Array.isArray(value)) return value.map(item => this._toolText(item)).filter(Boolean).join('\n').slice(0, 20_000);
		if (typeof value === 'object') {
			const record = value as Record<string, unknown>;
			if (typeof record.text === 'string') return this._toolText(record.text);
			if (typeof record.value === 'string') return this._toolText(record.value);
			if (record.data instanceof Uint8Array) return this._toolText(record.data);
			if (record.commandLine) return this._toolText(record.commandLine);
			if (typeof record.original === 'string') return this._toolText(record.toolEdited ?? record.userEdited ?? record.original);
		}
		try { return JSON.stringify(value, null, 2).slice(0, 20_000); } catch { return String(value).slice(0, 20_000); }
	}

	private _historyMessages(history: readonly (vscode.ChatRequestTurn | vscode.ChatResponseTurn)[]): Array<{ id: string; role: 'user' | 'assistant'; content: string; timestamp: number }> {
		const messages: Array<{ id: string; role: 'user' | 'assistant'; content: string; timestamp: number }> = [];
		for (let index = 0; index < history.length; index++) {
			const turn = history[index] as unknown as { id?: string; prompt?: string; response?: readonly unknown[] };
			if (typeof turn.prompt === 'string' && turn.prompt.trim()) {
				messages.push({ id: turn.id || `history:${index}:user`, role: 'user', content: turn.prompt, timestamp: index * 2 });
			} else if (Array.isArray(turn.response)) {
				const content = turn.response.map(part => {
					const value = (part as { value?: unknown }).value;
					if (typeof value === 'string') return value;
					if (value && typeof value === 'object' && typeof (value as { value?: unknown }).value === 'string') return (value as { value: string }).value;
					return '';
				}).filter(Boolean).join('\n').trim();
				if (content) messages.push({ id: turn.id || `history:${index}:assistant`, role: 'assistant', content, timestamp: index * 2 + 1 });
			}
		}
		return messages;
	}
}

export const remoteChatState = new RemoteChatState();
