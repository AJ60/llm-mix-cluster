export interface ChatAttachment {
  name: string;
  dataUrl: string;
}

export interface ToolExecutionTrace {
  name: string;
  args: Record<string, unknown>;
  summary: string;
  detail?: string;
  isError?: boolean;
}

export interface ChatTurn {
  id: string;
  role: 'user' | 'assistant';
  content: string;
  reasoning?: string;
  isThinkingActive?: boolean;
  images?: ChatAttachment[];
  toolTraces?: ToolExecutionTrace[];
  ttftMs?: number;
  tokPerSec?: number;
  totalTokens?: number;
  isError?: boolean;
}

export type SseStreamPhase =
  | 'idle'
  | 'connecting'
  | 'streaming'
  | 'completed'
  | 'aborted'
  | 'error';

export interface SseStreamStatus {
  phase: SseStreamPhase;
  activeTurnId: string | null;
  modelName: string;
  ttftMs: number;
  tokPerSec: number;
  totalTokens: number;
  startedAt: number | null;
  updatedAt: number;
  errorMessage: string | null;
}

export interface ChatStateCacheSnapshot {
  version: 1;
  messages: ChatTurn[];
  streamStatus: SseStreamStatus;
  updatedAt: number;
}

export interface StorageLike {
  getItem(key: string): string | null;
  setItem(key: string, value: string): void;
  removeItem(key: string): void;
}

export const CHAT_STATE_CACHE_STORAGE_KEY = 'silica.chat.stateCache.v1';
export const CHAT_HISTORY_STORAGE_KEY = 'silica.chat.history.v1';
export const CHAT_SSE_STATUS_STORAGE_KEY = 'silica.chat.sseStatus.v1';

const VALID_PHASES: ReadonlySet<SseStreamPhase> = new Set([
  'idle',
  'connecting',
  'streaming',
  'completed',
  'aborted',
  'error',
]);

function getBrowserStorage(): StorageLike | null {
  if (typeof window === 'undefined') return null;
  try {
    return window.localStorage;
  } catch {
    return null;
  }
}

export function createDefaultSseStreamStatus(modelName = ''): SseStreamStatus {
  return {
    phase: 'idle',
    activeTurnId: null,
    modelName,
    ttftMs: 0,
    tokPerSec: 0,
    totalTokens: 0,
    startedAt: null,
    updatedAt: Date.now(),
    errorMessage: null,
  };
}

export function sanitizePersistedTurns(raw: unknown): ChatTurn[] {
  if (!Array.isArray(raw)) return [];
  return raw
    .filter(
      (t): t is ChatTurn =>
        Boolean(
          t &&
            typeof t === 'object' &&
            typeof (t as ChatTurn).id === 'string' &&
            ((t as ChatTurn).role === 'user' || (t as ChatTurn).role === 'assistant')
        )
    )
    .slice(-60)
    .map((t) => ({
      ...t,
      content: typeof t.content === 'string' ? t.content : '',
      isThinkingActive: false,
      images: Array.isArray(t.images)
        ? t.images.map((img) => ({
            name: typeof img?.name === 'string' ? img.name : 'attachment',
            dataUrl: '',
          }))
        : undefined,
    }));
}

export function sanitizePersistedStreamStatus(
  raw: unknown,
  hydrateOnBoot = true
): SseStreamStatus {
  const fallback = createDefaultSseStreamStatus();
  if (!raw || typeof raw !== 'object') return fallback;

  const candidate = raw as Partial<SseStreamStatus>;
  const rawPhase: SseStreamPhase =
    candidate.phase && VALID_PHASES.has(candidate.phase) ? candidate.phase : 'idle';

  // On a fresh page load there is no live in-memory ReadableStream, so mark an
  // orphaned in-flight stream cleanly as interrupted/aborted while keeping tokens & metrics.
  const interruptedOnReload =
    hydrateOnBoot && (rawPhase === 'connecting' || rawPhase === 'streaming');

  return {
    phase: interruptedOnReload ? 'aborted' : rawPhase,
    activeTurnId: typeof candidate.activeTurnId === 'string' ? candidate.activeTurnId : null,
    modelName: typeof candidate.modelName === 'string' ? candidate.modelName : '',
    ttftMs: typeof candidate.ttftMs === 'number' && Number.isFinite(candidate.ttftMs) ? candidate.ttftMs : 0,
    tokPerSec:
      typeof candidate.tokPerSec === 'number' && Number.isFinite(candidate.tokPerSec)
        ? candidate.tokPerSec
        : 0,
    totalTokens:
      typeof candidate.totalTokens === 'number' && Number.isFinite(candidate.totalTokens)
        ? candidate.totalTokens
        : 0,
    startedAt:
      typeof candidate.startedAt === 'number' && Number.isFinite(candidate.startedAt)
        ? candidate.startedAt
        : null,
    updatedAt:
      typeof candidate.updatedAt === 'number' && Number.isFinite(candidate.updatedAt)
        ? candidate.updatedAt
        : Date.now(),
    errorMessage: interruptedOnReload
      ? candidate.errorMessage || 'Stream interrupted before completion'
      : typeof candidate.errorMessage === 'string'
        ? candidate.errorMessage
        : null,
  };
}

export function loadChatStateCache(storage: StorageLike | null = getBrowserStorage()): ChatStateCacheSnapshot {
  const fallback: ChatStateCacheSnapshot = {
    version: 1,
    messages: [],
    streamStatus: createDefaultSseStreamStatus(),
    updatedAt: Date.now(),
  };
  if (!storage) return fallback;

  try {
    const unifiedRaw = storage.getItem(CHAT_STATE_CACHE_STORAGE_KEY);
    if (unifiedRaw) {
      const parsed = JSON.parse(unifiedRaw) as Partial<ChatStateCacheSnapshot>;
      const messages = sanitizePersistedTurns(parsed?.messages);
      const streamStatus = sanitizePersistedStreamStatus(parsed?.streamStatus, true);
      return {
        version: 1,
        messages,
        streamStatus,
        updatedAt:
          typeof parsed?.updatedAt === 'number' && Number.isFinite(parsed.updatedAt)
            ? parsed.updatedAt
            : streamStatus.updatedAt,
      };
    }

    const legacyHistoryRaw = storage.getItem(CHAT_HISTORY_STORAGE_KEY);
    const legacyStatusRaw = storage.getItem(CHAT_SSE_STATUS_STORAGE_KEY);
    const messages = legacyHistoryRaw ? sanitizePersistedTurns(JSON.parse(legacyHistoryRaw)) : [];
    const streamStatus = legacyStatusRaw
      ? sanitizePersistedStreamStatus(JSON.parse(legacyStatusRaw), true)
      : createDefaultSseStreamStatus();
    return {
      version: 1,
      messages,
      streamStatus,
      updatedAt: streamStatus.updatedAt,
    };
  } catch {
    return fallback;
  }
}

export function saveChatStateCache(
  input: { messages: ChatTurn[]; streamStatus: SseStreamStatus },
  storage: StorageLike | null = getBrowserStorage()
): void {
  if (!storage) return;
  try {
    const sanitizedMessages = sanitizePersistedTurns(input.messages);
    const sanitizedStatus = sanitizePersistedStreamStatus(input.streamStatus, false);
    const snapshot: ChatStateCacheSnapshot = {
      version: 1,
      messages: sanitizedMessages,
      streamStatus: sanitizedStatus,
      updatedAt: Date.now(),
    };
    storage.setItem(CHAT_STATE_CACHE_STORAGE_KEY, JSON.stringify(snapshot));
    storage.setItem(CHAT_HISTORY_STORAGE_KEY, JSON.stringify(sanitizedMessages));
    storage.setItem(CHAT_SSE_STATUS_STORAGE_KEY, JSON.stringify(sanitizedStatus));
  } catch {
    // ignore storage quota errors
  }
}

export function clearChatStateCache(storage: StorageLike | null = getBrowserStorage()): void {
  if (!storage) return;
  try {
    storage.removeItem(CHAT_STATE_CACHE_STORAGE_KEY);
    storage.removeItem(CHAT_HISTORY_STORAGE_KEY);
    storage.removeItem(CHAT_SSE_STATUS_STORAGE_KEY);
  } catch {
    // ignore
  }
}

export function formatSseStreamBadge(status: SseStreamStatus): {
  label: string;
  tone: 'idle' | 'live' | 'ok' | 'warn' | 'error';
} {
  switch (status.phase) {
    case 'connecting':
      return { label: 'Connecting', tone: 'live' };
    case 'streaming':
      return {
        label: status.tokPerSec > 0 ? `Streaming (${status.tokPerSec} tok/s)` : 'Streaming',
        tone: 'live',
      };
    case 'completed':
      return { label: 'Completed', tone: 'ok' };
    case 'aborted':
      return { label: 'Stopped', tone: 'warn' };
    case 'error':
      return { label: 'Error', tone: 'error' };
    case 'idle':
    default:
      return { label: 'Idle', tone: 'idle' };
  }
}

export const STABLE_CHAT_PERSISTER_KEY = 'silica-chat-state-persister';

export interface PersistedChatComparableProps {
  activeModel: {
    id: string;
    name: string;
    architecture?: string;
    quant?: string;
  };
  plan: {
    totalSafePoolMb: number;
    nodes: Array<{
      role: string;
      isConnected: boolean;
      allocatedMb?: number;
      rttMs?: number;
    }>;
  };
  engineStatus?: {
    running: boolean;
    serverType?: string;
  };
  upstreamUrl: string;
  messages?: ChatTurn[];
  streamStatus?: SseStreamStatus;
  onUpstreamConnected?: unknown;
  onEngineLiveDetected?: unknown;
  onOpenLoadConfig?: unknown;
  onStopEngine?: unknown;
  onMessagesChange?: unknown;
  onStreamStatusChange?: unknown;
  onClearChatCache?: unknown;
}

function summarizePlanForChat(plan: PersistedChatComparableProps['plan']): string {
  let totalAllocated = 0;
  let connectedWorkers = 0;
  let rttSum = 0;
  for (const node of plan.nodes) {
    totalAllocated += node.allocatedMb || 0;
    if (node.role === 'Worker' && node.isConnected) {
      connectedWorkers += 1;
      rttSum += node.rttMs || 0.8;
    }
  }
  const avgRtt = connectedWorkers === 0 ? 0.5 : Number((rttSum / connectedWorkers).toFixed(1));
  return `${plan.totalSafePoolMb}|${totalAllocated}|${connectedWorkers}|${avgRtt}`;
}

/**
 * Pure comparator for the memoized ChatStatePersister inner container.
 * Ignores tab visibility completely and deduplicates periodic polling snapshots
 * when chat messages, SSE stream status, active model metadata, and cluster metrics
 * remain identical so chat turns never re-render during tab switches or background polls.
 */
export function arePersistedChatPropsEqual(
  prev: PersistedChatComparableProps,
  next: PersistedChatComparableProps
): boolean {
  if (prev.messages !== next.messages) return false;
  if (prev.streamStatus !== next.streamStatus) return false;
  if (prev.upstreamUrl !== next.upstreamUrl) return false;
  if (prev.activeModel.id !== next.activeModel.id) return false;
  if (prev.activeModel.name !== next.activeModel.name) return false;
  if (prev.activeModel.architecture !== next.activeModel.architecture) return false;
  if (prev.activeModel.quant !== next.activeModel.quant) return false;
  if (Boolean(prev.engineStatus?.running) !== Boolean(next.engineStatus?.running)) return false;
  if (prev.engineStatus?.serverType !== next.engineStatus?.serverType) return false;
  if (summarizePlanForChat(prev.plan) !== summarizePlanForChat(next.plan)) return false;
  if (prev.onUpstreamConnected !== next.onUpstreamConnected) return false;
  if (prev.onEngineLiveDetected !== next.onEngineLiveDetected) return false;
  if (prev.onOpenLoadConfig !== next.onOpenLoadConfig) return false;
  if (prev.onStopEngine !== next.onStopEngine) return false;
  if (prev.onMessagesChange !== next.onMessagesChange) return false;
  if (prev.onStreamStatusChange !== next.onStreamStatusChange) return false;
  if (prev.onClearChatCache !== next.onClearChatCache) return false;
  return true;
}
