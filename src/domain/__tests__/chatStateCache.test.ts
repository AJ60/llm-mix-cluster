import test from 'node:test';
import assert from 'node:assert/strict';
import {
  CHAT_HISTORY_STORAGE_KEY,
  CHAT_SSE_STATUS_STORAGE_KEY,
  CHAT_STATE_CACHE_STORAGE_KEY,
  ChatTurn,
  StorageLike,
  clearChatStateCache,
  createDefaultSseStreamStatus,
  formatSseStreamBadge,
  loadChatStateCache,
  saveChatStateCache,
} from '../chatStateCache';

function createMemoryStorage(): StorageLike {
  const store = new Map<string, string>();
  return {
    getItem(key: string) {
      return store.get(key) ?? null;
    },
    setItem(key: string, value: string) {
      store.set(key, value);
    },
    removeItem(key: string) {
      store.delete(key);
    },
  };
}

test('persists and rehydrates chat messages and completed SSE stream status', () => {
  const storage = createMemoryStorage();
  const turns: ChatTurn[] = [
    { id: 'u-1', role: 'user', content: 'Hello cluster' },
    {
      id: 'a-1',
      role: 'assistant',
      content: 'Distributed layers ready.',
      ttftMs: 142,
      tokPerSec: 28.4,
      totalTokens: 16,
      isThinkingActive: true,
    },
  ];
  const streamStatus = {
    ...createDefaultSseStreamStatus('Qwen3-27B'),
    phase: 'completed' as const,
    activeTurnId: 'a-1',
    ttftMs: 142,
    tokPerSec: 28.4,
    totalTokens: 16,
  };

  saveChatStateCache({ messages: turns, streamStatus }, storage);
  assert.ok(storage.getItem(CHAT_STATE_CACHE_STORAGE_KEY));
  assert.ok(storage.getItem(CHAT_HISTORY_STORAGE_KEY));
  assert.ok(storage.getItem(CHAT_SSE_STATUS_STORAGE_KEY));

  const restored = loadChatStateCache(storage);
  assert.equal(restored.messages.length, 2);
  assert.equal(restored.messages[1].content, 'Distributed layers ready.');
  assert.equal(restored.messages[1].isThinkingActive, false);
  assert.equal(restored.streamStatus.phase, 'completed');
  assert.equal(restored.streamStatus.ttftMs, 142);
  assert.equal(restored.streamStatus.tokPerSec, 28.4);
  assert.equal(restored.streamStatus.totalTokens, 16);
});

test('marks orphaned in-flight SSE streams as interrupted on reload while keeping partial tokens', () => {
  const storage = createMemoryStorage();
  const turns: ChatTurn[] = [
    { id: 'u-2', role: 'user', content: 'Explain Cake sharding' },
    { id: 'a-2', role: 'assistant', content: 'Cake shards layers by' },
  ];
  const inFlight = {
    ...createDefaultSseStreamStatus('Llama-3'),
    phase: 'streaming' as const,
    activeTurnId: 'a-2',
    ttftMs: 95,
    tokPerSec: 31.2,
    totalTokens: 5,
    startedAt: 1000,
    updatedAt: 2000,
  };

  saveChatStateCache({ messages: turns, streamStatus: inFlight }, storage);
  const rawSaved = JSON.parse(storage.getItem(CHAT_STATE_CACHE_STORAGE_KEY)!);
  assert.equal(rawSaved.streamStatus.phase, 'streaming');

  const rehydrated = loadChatStateCache(storage);
  assert.equal(rehydrated.messages[1].content, 'Cake shards layers by');
  assert.equal(rehydrated.streamStatus.phase, 'aborted');
  assert.equal(rehydrated.streamStatus.ttftMs, 95);
  assert.equal(rehydrated.streamStatus.tokPerSec, 31.2);
  assert.equal(rehydrated.streamStatus.totalTokens, 5);
});

test('strips oversized attachment dataUrls, honors legacy history fallback, and clears cleanly', () => {
  const storage = createMemoryStorage();
  saveChatStateCache(
    {
      messages: [
        {
          id: 'u-img',
          role: 'user',
          content: 'Inspect this screenshot',
          images: [{ name: 'screen.png', dataUrl: 'data:image/png;base64,HUGEDATA' }],
        },
      ],
      streamStatus: createDefaultSseStreamStatus('VL-Model'),
    },
    storage
  );

  const restored = loadChatStateCache(storage);
  assert.equal(restored.messages[0].images?.[0].name, 'screen.png');
  assert.equal(restored.messages[0].images?.[0].dataUrl, '');

  clearChatStateCache(storage);
  assert.equal(storage.getItem(CHAT_STATE_CACHE_STORAGE_KEY), null);
  assert.equal(storage.getItem(CHAT_HISTORY_STORAGE_KEY), null);
  assert.equal(storage.getItem(CHAT_SSE_STATUS_STORAGE_KEY), null);

  storage.setItem(
    CHAT_HISTORY_STORAGE_KEY,
    JSON.stringify([{ id: 'u-legacy', role: 'user', content: 'Legacy prompt' }])
  );
  const legacyRestored = loadChatStateCache(storage);
  assert.equal(legacyRestored.messages.length, 1);
  assert.equal(legacyRestored.messages[0].content, 'Legacy prompt');
  assert.equal(legacyRestored.streamStatus.phase, 'idle');
});

test('formats SSE stream status badges cleanly', () => {
  assert.deepEqual(formatSseStreamBadge(createDefaultSseStreamStatus()), {
    label: 'Idle',
    tone: 'idle',
  });
  assert.deepEqual(
    formatSseStreamBadge({
      ...createDefaultSseStreamStatus(),
      phase: 'streaming',
      tokPerSec: 24.5,
    }),
    {
      label: 'Streaming (24.5 tok/s)',
      tone: 'live',
    }
  );
  assert.deepEqual(
    formatSseStreamBadge({ ...createDefaultSseStreamStatus(), phase: 'completed' }),
    { label: 'Completed', tone: 'ok' }
  );
  assert.deepEqual(
    formatSseStreamBadge({ ...createDefaultSseStreamStatus(), phase: 'error' }),
    { label: 'Error', tone: 'error' }
  );
});

import {
  PersistedChatComparableProps,
  STABLE_CHAT_PERSISTER_KEY,
  arePersistedChatPropsEqual,
} from '../chatStateCache';

test('arePersistedChatPropsEqual skips re-renders on tab switches or cloned snapshots and updates on turn/stream changes', () => {
  assert.ok(STABLE_CHAT_PERSISTER_KEY.length > 0);
  const noop = () => {};
  const messages: ChatTurn[] = [{ id: 'u-1', role: 'user', content: 'Hello' }];
  const streamStatus = createDefaultSseStreamStatus('Qwen3-27B');

  const baseProps: PersistedChatComparableProps = {
    activeModel: { id: 'qwen', name: 'Qwen3-27B', architecture: 'Dense', quant: 'Q4_K_M' },
    plan: {
      totalSafePoolMb: 8192,
      nodes: [{ role: 'Worker', isConnected: true, allocatedMb: 2048, rttMs: 1.2 }],
    },
    engineStatus: { running: true, serverType: 'llama-server' },
    upstreamUrl: 'http://127.0.0.1:8080',
    messages,
    streamStatus,
    onOpenLoadConfig: noop,
    onStopEngine: noop,
    onMessagesChange: noop,
    onStreamStatusChange: noop,
    onClearChatCache: noop,
  };

  // Cloned plan and engineStatus objects with identical metrics must not trigger re-render
  const clonedSnapshotProps: PersistedChatComparableProps = {
    ...baseProps,
    activeModel: { ...baseProps.activeModel },
    plan: {
      totalSafePoolMb: 8192,
      nodes: [{ role: 'Worker', isConnected: true, allocatedMb: 2048, rttMs: 1.2 }],
    },
    engineStatus: { running: true, serverType: 'llama-server' },
  };
  assert.equal(arePersistedChatPropsEqual(baseProps, clonedSnapshotProps), true);

  // Adding or updating a chat turn MUST trigger update
  const updatedTurnProps: PersistedChatComparableProps = {
    ...clonedSnapshotProps,
    messages: [...messages, { id: 'a-1', role: 'assistant', content: 'Hi' }],
  };
  assert.equal(arePersistedChatPropsEqual(baseProps, updatedTurnProps), false);

  // Updating stream status MUST trigger update
  const updatedStreamProps: PersistedChatComparableProps = {
    ...clonedSnapshotProps,
    streamStatus: { ...streamStatus, phase: 'streaming' },
  };
  assert.equal(arePersistedChatPropsEqual(baseProps, updatedStreamProps), false);
});
