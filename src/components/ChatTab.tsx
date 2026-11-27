import { apiFetch } from '../service/apiAuth';
import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import {
  AlertCircle,
  Brain,
  Check,
  ChevronDown,
  ChevronUp,
  Copy,
  CornerDownLeft,
  Globe,
  ImagePlus,
  SlidersHorizontal,
  Sparkles,
  Square,
  Trash2,
  Wrench,
  X,
  Zap,
} from 'lucide-react';
import { EngineRuntimeStatus, GgufModelPreset, StarClusterPlan } from '../types/starCluster';
import { splitReasoningContent } from '../domain/reasoningParser';

import {
  ChatAttachment,
  ChatTurn,
  ToolExecutionTrace,
  SseStreamStatus,
  createDefaultSseStreamStatus,
  loadChatStateCache,
  saveChatStateCache,
  clearChatStateCache,
  formatSseStreamBadge,
} from '../domain/chatStateCache';

export interface ChatTabProps {
  activeModel: GgufModelPreset;
  plan: StarClusterPlan;
  engineStatus?: EngineRuntimeStatus;
  upstreamUrl: string;
  onUpstreamConnected?: (url: string) => void;
  onEngineLiveDetected?: () => void;
  onOpenLoadConfig?: () => void;
  onStopEngine?: () => Promise<{ ok: boolean; message: string }>;
  messages?: ChatTurn[];
  onMessagesChange?: React.Dispatch<React.SetStateAction<ChatTurn[]>>;
  streamStatus?: SseStreamStatus;
  onStreamStatusChange?: React.Dispatch<React.SetStateAction<SseStreamStatus>>;
  onClearChatCache?: () => void;
  isTabVisible?: boolean;
}

export const AGENT_TOOLS_SPEC = [
  {
    type: 'function',
    function: {
      name: 'web_search',
      description: 'Search public web knowledge for real-time or up-to-date information, news, or technical documentation.',
      parameters: {
        type: 'object',
        properties: { query: { type: 'string', description: 'The search query' } },
        required: ['query'],
      },
    },
  },
  {
    type: 'function',
    function: {
      name: 'fetch_url',
      description: 'Fetch and extract readable plain text content from a public web page URL (SSRF-protected).',
      parameters: {
        type: 'object',
        properties: { url: { type: 'string', description: 'Web page URL starting with http:// or https://' } },
        required: ['url'],
      },
    },
  },
  {
    type: 'function',
    function: {
      name: 'cluster_status',
      description: 'Query live Silica Cluster telemetry, node VRAM, RAM, layer sharding allocations, and link latencies.',
      parameters: {
        type: 'object',
        properties: {},
      },
    },
  },
];

const STARTER_PROMPTS = [
  'Explain how Cake sharding distributes transformer layers across devices.',
  'Check the live cluster status and node allocations.',
  'Write a Python script to stream tokens from an OpenAI-compatible endpoint.',
  'What is the difference between Dense and Sparse MoE models for edge inference?',
];

/**
 * Minimalist Code Block with syntax tag and 1-Click Copy
 */
const CodeBlock: React.FC<{ code: string; language?: string }> = ({ code, language }) => {
  const [copied, setCopied] = useState(false);

  const handleCopy = () => {
    navigator.clipboard.writeText(code);
    setCopied(true);
    setTimeout(() => setCopied(false), 2000);
  };

  return (
    <div className="relative my-3 rounded-xl overflow-hidden border border-slate-700/80 bg-[#080C14] shadow-sm">
      <div className="flex items-center justify-between px-3.5 py-1.5 bg-[#0D1524] border-b border-slate-700/60 text-[11px] font-mono text-slate-400">
        <span className="uppercase tracking-wider font-semibold text-slate-300">
          {language || 'code'}
        </span>
        <button
          type="button"
          onClick={handleCopy}
          className="inline-flex items-center gap-1 text-slate-400 hover:text-white transition-colors"
        >
          {copied ? <Check className="w-3.5 h-3.5 text-emerald-400" /> : <Copy className="w-3.5 h-3.5" />}
          <span>{copied ? 'Copied' : 'Copy'}</span>
        </button>
      </div>
      <pre className="p-3.5 text-xs font-mono text-slate-200 overflow-x-auto leading-relaxed whitespace-pre">
        <code>{code}</code>
      </pre>
    </div>
  );
};

/**
 * Robust Markdown parser for prose, inline code, bold, lists, and code blocks
 */
function renderFormattedContent(text: string) {
  if (!text) return null;

  // Split by code blocks (handles both completed ```...``` and still-streaming open ```...)
  const parts = text.split(/(```[\s\S]*?```|```[\s\S]*$)/g);

  return parts.map((part, idx) => {
    if (part.startsWith('```')) {
      const isUnclosed = !part.endsWith('```') || part === '```';
      const cleanPart = isUnclosed ? `${part}\n` : part;
      const firstLineEnd = cleanPart.indexOf('\n');
      const lang = firstLineEnd > 3 ? cleanPart.slice(3, firstLineEnd).trim() : '';
      const code = firstLineEnd > 0
        ? cleanPart.slice(firstLineEnd + 1, isUnclosed ? undefined : -3)
        : cleanPart.slice(3, isUnclosed ? undefined : -3);

      return <CodeBlock key={idx} code={code.replace(/\n+$/, '')} language={lang} />;
    }

    // Render formatted markdown paragraphs
    const paragraphs = part.split(/\n\n+/);
    return (
      <div key={idx} className="space-y-2.5">
        {paragraphs.map((p, pIdx) => {
          const trimmed = p.trim();
          if (!trimmed) return null;

          // Header levels
          if (trimmed.startsWith('### ')) {
            return (
              <h4 key={pIdx} className="text-sm font-semibold text-slate-100 pt-2 pb-0.5">
                {renderInlineStyles(trimmed.slice(4))}
              </h4>
            );
          }
          if (trimmed.startsWith('## ')) {
            return (
              <h3 key={pIdx} className="text-base font-semibold text-white pt-2.5 pb-1">
                {renderInlineStyles(trimmed.slice(3))}
              </h3>
            );
          }
          if (trimmed.startsWith('# ')) {
            return (
              <h2 key={pIdx} className="text-lg font-bold text-white pt-3 pb-1">
                {renderInlineStyles(trimmed.slice(2))}
              </h2>
            );
          }

          // Bulleted or numbered lists
          const lines = p.split('\n');
          const isList = lines.every(
            (l) => /^\s*[-*•]\s+/.test(l) || /^\s*\d+\.\s+/.test(l) || l.trim() === ''
          );

          if (isList && lines.length > 0) {
            return (
              <ul key={pIdx} className="space-y-1.5 pl-4 list-disc text-slate-200">
                {lines
                  .filter((l) => l.trim().length > 0)
                  .map((line, lIdx) => (
                    <li key={lIdx} className="leading-relaxed">
                      {renderInlineStyles(line.replace(/^\s*[-*•\d.]+\s*/, ''))}
                    </li>
                  ))}
              </ul>
            );
          }

          return (
            <p key={pIdx} className="leading-relaxed text-slate-200 whitespace-pre-wrap">
              {renderInlineStyles(p)}
            </p>
          );
        })}
      </div>
    );
  });
}

/**
 * Inline style parser for `inline_code`, **bold**, and *italic*
 */
function renderInlineStyles(str: string): React.ReactNode {
  const tokens = str.split(/(`[^`]+`|\*\*[^*]+\*\*|\*[^*]+\*)/g);
  return tokens.map((t, idx) => {
    if (t.startsWith('`') && t.endsWith('`') && t.length > 1) {
      return (
        <code
          key={idx}
          className="px-1.5 py-0.5 rounded-md bg-slate-800 text-sky-300 font-mono text-[12px] border border-slate-700/60"
        >
          {t.slice(1, -1)}
        </code>
      );
    }
    if (t.startsWith('**') && t.endsWith('**') && t.length > 3) {
      return (
        <strong key={idx} className="font-semibold text-white">
          {t.slice(2, -2)}
        </strong>
      );
    }
    if (t.startsWith('*') && t.endsWith('*') && t.length > 2) {
      return (
        <em key={idx} className="italic text-slate-300">
          {t.slice(1, -1)}
        </em>
      );
    }
    return t;
  });
}

interface ChatTurnRowProps {
  turn: ChatTurn;
  modelName: string;
  showThink: boolean;
  showTools: boolean;
  showGeneratingPlaceholder: boolean;
  onToggleReasoning: (turnId: string, nextOpen: boolean) => void;
  onToggleTools: (turnId: string, nextOpen: boolean) => void;
}

const ChatTurnRow: React.FC<ChatTurnRowProps> = React.memo(
  ({
    turn,
    modelName,
    showThink,
    showTools,
    showGeneratingPlaceholder,
    onToggleReasoning,
    onToggleTools,
  }) => {
    return (
      <div
        data-testid="chat-turn"
        data-turn-id={turn.id}
        data-role={turn.role}
        className={`w-full flex flex-col ${turn.role === 'user' ? 'items-end' : 'items-start'}`}
      >
        <div
          className={`rounded-2xl text-xs sm:text-sm space-y-3 transition-all ${
            turn.role === 'user'
              ? 'max-w-[85%] sm:max-w-[75%] px-4 py-3 bg-emerald-600 text-white shadow-md'
              : turn.isError
                ? 'w-full max-w-4xl p-4 sm:p-5 border border-rose-500/40 bg-rose-500/10 text-rose-300'
                : 'w-full max-w-4xl p-4 sm:p-5 border border-slate-800/90 bg-[#0F172A]/90 text-slate-100 shadow-sm'
          }`}
        >
          {/* Assistant Message Header Pill */}
          {turn.role === 'assistant' && (
            <div className="flex items-center justify-between pb-2 border-b border-slate-800/60 text-[11px] text-slate-400">
              <span className="inline-flex items-center gap-1.5 font-medium text-slate-300">
                <Sparkles className="w-3.5 h-3.5 text-emerald-400" />
                <span>{modelName}</span>
              </span>
              {turn.tokPerSec ? (
                <span className="font-mono text-emerald-400 font-semibold">
                  {turn.tokPerSec} tok/s
                </span>
              ) : null}
            </div>
          )}

          {/* User Attached Images */}
          {turn.images && turn.images.length > 0 && (
            <div className="flex flex-wrap gap-2">
              {turn.images.map((img, i) => (
                <img
                  key={i}
                  src={img.dataUrl}
                  alt={img.name}
                  className="h-24 w-auto rounded-lg border border-white/20 object-cover"
                />
              ))}
            </div>
          )}

          {/* Reasoning Trace Collapsible */}
          {turn.reasoning && (
            <div className="rounded-xl border border-slate-800 bg-[#080C14] overflow-hidden">
              <button
                type="button"
                onClick={() => onToggleReasoning(turn.id, !showThink)}
                className="w-full px-3.5 py-2 flex items-center justify-between text-xs font-medium text-slate-300 hover:text-white transition-colors bg-[#0D1524]/70"
              >
                <span className="inline-flex items-center gap-2">
                  <Brain
                    className={`w-3.5 h-3.5 ${
                      turn.isThinkingActive ? 'text-sky-400 animate-pulse' : 'text-slate-400'
                    }`}
                  />
                  <span>{turn.isThinkingActive ? 'Thinking...' : 'Reasoning Trace'}</span>
                  <span className="text-[10px] px-1.5 py-0.5 rounded-md bg-slate-800 text-slate-400 font-mono">
                    {turn.reasoning.split(/\s+/).filter(Boolean).length} tokens
                  </span>
                </span>
                {showThink ? (
                  <ChevronUp className="w-3.5 h-3.5 text-slate-400" />
                ) : (
                  <ChevronDown className="w-3.5 h-3.5 text-slate-400" />
                )}
              </button>
              {showThink && (
                <pre className="p-3.5 text-xs font-mono text-slate-300 whitespace-pre-wrap break-words leading-relaxed border-t border-slate-800/80 max-h-64 overflow-y-auto select-text">
                  {turn.reasoning}
                </pre>
              )}
            </div>
          )}

          {/* Agentic Tools Execution Cards */}
          {turn.toolTraces && turn.toolTraces.length > 0 && (
            <div className="rounded-xl border border-sky-500/30 bg-sky-950/20 overflow-hidden">
              <button
                type="button"
                onClick={() => onToggleTools(turn.id, !showTools)}
                className="w-full px-3.5 py-2 flex items-center justify-between text-xs font-medium text-sky-300 hover:text-sky-200 transition-colors bg-sky-950/40"
              >
                <span className="inline-flex items-center gap-2">
                  <Wrench className="w-3.5 h-3.5 text-sky-400" />
                  <span>Agent Tools Executed ({turn.toolTraces.length})</span>
                </span>
                {showTools ? (
                  <ChevronUp className="w-3.5 h-3.5 text-sky-400" />
                ) : (
                  <ChevronDown className="w-3.5 h-3.5 text-sky-400" />
                )}
              </button>
              {showTools && (
                <div className="p-3 space-y-2 border-t border-sky-500/20 text-xs">
                  {turn.toolTraces.map((trace, tIdx) => (
                    <div
                      key={tIdx}
                      className="p-2 rounded-lg border border-slate-800 bg-[#080C14] space-y-1 font-mono text-[11px]"
                    >
                      <div className="flex items-center justify-between text-sky-400 font-semibold">
                        <span>{trace.summary}</span>
                        {trace.isError && <span className="text-rose-400 font-normal">Failed</span>}
                      </div>
                      {trace.detail && (
                        <pre className="text-slate-400 max-h-32 overflow-y-auto whitespace-pre-wrap break-words text-[10px] pt-1">
                          {trace.detail}
                        </pre>
                      )}
                    </div>
                  ))}
                </div>
              )}
            </div>
          )}

          {/* Content with Markdown code blocks */}
          <div className="leading-relaxed break-words">
            {turn.isError ? (
              <div className="flex items-center gap-2 text-rose-300">
                <AlertCircle className="w-4 h-4 shrink-0" />
                <span>{turn.content}</span>
              </div>
            ) : (
              renderFormattedContent(
                turn.content || (showGeneratingPlaceholder ? 'Generating response...' : '')
              )
            )}
          </div>

          {/* Individual Turn Performance Stamp */}
          {turn.role === 'assistant' && !turn.isError && (turn.ttftMs || turn.tokPerSec) && (
            <div className="text-[11px] font-mono text-slate-400 pt-2 border-t border-slate-800/80 flex items-center gap-3 flex-wrap">
              {turn.ttftMs ? (
                <span>
                  TTFT:{' '}
                  <strong className="text-emerald-400 font-semibold">{turn.ttftMs} ms</strong>
                </span>
              ) : null}
              {turn.ttftMs && turn.tokPerSec ? <span className="text-slate-600">·</span> : null}
              {turn.tokPerSec ? (
                <span>
                  Speed:{' '}
                  <strong className="text-sky-400 font-semibold">{turn.tokPerSec} tok/s</strong>
                </span>
              ) : null}
              {turn.totalTokens ? (
                <>
                  <span className="text-slate-600">·</span>
                  <span>
                    Tokens:{' '}
                    <strong className="text-amber-400 font-semibold">{turn.totalTokens}</strong>
                  </span>
                </>
              ) : null}
            </div>
          )}
        </div>
      </div>
    );
  }
);

ChatTurnRow.displayName = 'ChatTurnRow';

export const ChatTab: React.FC<ChatTabProps> = ({
  activeModel,
  plan,
  engineStatus,
  upstreamUrl,
  onEngineLiveDetected,
  onOpenLoadConfig,
  onStopEngine,
  messages: controlledMessages,
  onMessagesChange,
  streamStatus: controlledStreamStatus,
  onStreamStatusChange,
  onClearChatCache,
  isTabVisible = true,
}) => {
  const [internalMessages, setInternalMessages] = useState<ChatTurn[]>(
    () => loadChatStateCache().messages
  );
  const [internalStreamStatus, setInternalStreamStatus] = useState<SseStreamStatus>(
    () => loadChatStateCache().streamStatus
  );

  const messages = controlledMessages ?? internalMessages;
  const setMessages = onMessagesChange ?? setInternalMessages;
  const streamStatus = controlledStreamStatus ?? internalStreamStatus;
  const setStreamStatus = onStreamStatusChange ?? setInternalStreamStatus;
  const sending = streamStatus.phase === 'connecting' || streamStatus.phase === 'streaming';

  const [input, setInput] = useState('');
  const [attachments, setAttachments] = useState<ChatAttachment[]>([]);
  const [agentToolsEnabled, setAgentToolsEnabled] = useState<boolean>(true);
  const [expandedReasoning, setExpandedReasoning] = useState<Record<string, boolean>>({});
  const [expandedTools, setExpandedTools] = useState<Record<string, boolean>>({});
  const [liveMetrics, setLiveMetrics] = useState<{
    ttftMs: number;
    tokPerSec: number;
    ramPct: number;
    rttMs: number;
    totalTokens: number;
  }>(() => ({
    ttftMs: streamStatus.ttftMs || 0,
    tokPerSec: streamStatus.tokPerSec || 0,
    ramPct: 0,
    rttMs: 0.5,
    totalTokens: streamStatus.totalTokens || 0,
  }));

  const abortControllerRef = useRef<AbortController | null>(null);
  const chatRootRef = useRef<HTMLDivElement | null>(null);
  const chatBottomRef = useRef<HTMLDivElement | null>(null);
  const fileInputRef = useRef<HTMLInputElement | null>(null);
  const messagesRef = useRef<ChatTurn[]>(messages);
  messagesRef.current = messages;

  const isLive = Boolean(engineStatus?.running || upstreamUrl);

  // Compute live cluster RAM usage percentage
  const totalAllocatedMb = plan.nodes.reduce((acc, n) => acc + (n.allocatedMb || 0), 0);
  const totalPoolMb = plan.totalSafePoolMb || 1;
  const currentRamPct = Math.min(100, Math.round((totalAllocatedMb / totalPoolMb) * 100));

  // Avg link RTT across connected workers
  const avgRtt = useMemo(() => {
    const workers = plan.nodes.filter((n) => n.role === 'Worker' && n.isConnected);
    if (workers.length === 0) return 0.5;
    return Number((workers.reduce((a, b) => a + (b.rttMs || 0.8), 0) / workers.length).toFixed(1));
  }, [plan.nodes]);

  useEffect(() => {
    setLiveMetrics((prev) => ({
      ...prev,
      ramPct: currentRamPct,
      rttMs: avgRtt,
    }));
  }, [currentRamPct, avgRtt]);

  // Fallback persistence when rendered uncontrolled outside App.tsx
  useEffect(() => {
    if (!onMessagesChange || !onStreamStatusChange) {
      saveChatStateCache({ messages, streamStatus });
    }
  }, [messages, streamStatus, onMessagesChange, onStreamStatusChange]);

  useEffect(() => {
    const el = chatRootRef.current;
    if (isTabVisible && el && el.offsetParent !== null) {
      chatBottomRef.current?.scrollIntoView({ behavior: 'smooth' });
    }
  }, [messages, sending, isTabVisible]);

  const handleToggleReasoning = useCallback((turnId: string, nextOpen: boolean) => {
    setExpandedReasoning((prev) => ({ ...prev, [turnId]: nextOpen }));
  }, []);

  const handleToggleTools = useCallback((turnId: string, nextOpen: boolean) => {
    setExpandedTools((prev) => ({ ...prev, [turnId]: nextOpen }));
  }, []);

  const handleImageSelect = (e: React.ChangeEvent<HTMLInputElement>) => {
    const files = e.target.files;
    if (!files) return;
    Array.from(files).forEach((file) => {
      const reader = new FileReader();
      reader.onload = () => {
        if (typeof reader.result === 'string') {
          setAttachments((prev) => [...prev, { name: file.name, dataUrl: reader.result as string }]);
        }
      };
      reader.readAsDataURL(file);
    });
    e.target.value = '';
  };

  const handleClearHistory = () => {
    if (abortControllerRef.current) {
      abortControllerRef.current.abort();
      abortControllerRef.current = null;
    }
    if (onClearChatCache) {
      onClearChatCache();
    } else {
      setMessages([]);
      setStreamStatus(createDefaultSseStreamStatus(activeModel.name));
      clearChatStateCache();
    }
    setLiveMetrics((prev) => ({ ...prev, ttftMs: 0, tokPerSec: 0, totalTokens: 0 }));
  };

  const handleStopGeneration = () => {
    if (abortControllerRef.current) {
      abortControllerRef.current.abort();
      abortControllerRef.current = null;
    }
    setStreamStatus((prev) => ({
      ...prev,
      phase: 'aborted',
      updatedAt: Date.now(),
      errorMessage: 'Generation stopped',
    }));
  };

  /**
   * Executes client-side agentic tools against the Master server endpoints
   */
  async function executeAgentTool(name: string, argsStr: string): Promise<ToolExecutionTrace> {
    let parsedArgs: Record<string, unknown> = {};
    try {
      parsedArgs = JSON.parse(argsStr || '{}') as Record<string, unknown>;
    } catch {
      // ignore
    }

    if (name === 'cluster_status') {
      try {
        const res = await apiFetch('/api/tools/cluster-status', { method: 'POST' });
        const data = await res.json();
        const workerCount = data.activeWorkers ?? 0;
        return {
          name,
          args: parsedArgs,
          summary: `Queried cluster telemetry: ${workerCount} active worker(s) · ${data.throughputEst}`,
          detail: JSON.stringify(data, null, 2),
        };
      } catch (err) {
        return {
          name,
          args: parsedArgs,
          summary: 'cluster_status query failed',
          detail: err instanceof Error ? err.message : String(err),
          isError: true,
        };
      }
    }

    if (name === 'web_search') {
      const query = String(parsedArgs.query || '');
      try {
        const res = await apiFetch('/api/tools/web-search', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ query }),
        });
        const data = await res.json();
        const count = data.results?.length ?? 0;
        return {
          name,
          args: parsedArgs,
          summary: `web_search("${query}") -> ${count} result(s)`,
          detail: JSON.stringify(data.results || [], null, 2),
        };
      } catch (err) {
        return {
          name,
          args: parsedArgs,
          summary: `web_search("${query}") failed`,
          detail: err instanceof Error ? err.message : String(err),
          isError: true,
        };
      }
    }

    if (name === 'fetch_url') {
      const url = String(parsedArgs.url || '');
      try {
        const res = await apiFetch('/api/tools/fetch-url', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ url }),
        });
        const data = await res.json();
        return {
          name,
          args: parsedArgs,
          summary: data.ok ? `fetch_url("${url}") -> HTTP ${data.status}` : `fetch_url blocked: ${data.error}`,
          detail: data.content ? String(data.content).slice(0, 800) : data.error,
          isError: !data.ok,
        };
      } catch (err) {
        return {
          name,
          args: parsedArgs,
          summary: `fetch_url("${url}") failed`,
          detail: err instanceof Error ? err.message : String(err),
          isError: true,
        };
      }
    }

    return {
      name,
      args: parsedArgs,
      summary: `${name}(...) executed`,
    };
  }

  const handleSendMessage = async (customPrompt?: string) => {
    const promptText = (customPrompt ?? input).trim();
    if (!promptText && attachments.length === 0) return;
    if (sending) return;

    const userTurnId = `u-${Date.now()}`;
    const assistantTurnId = `a-${Date.now() + 1}`;
    const userImages = [...attachments];

    const priorMessages = messagesRef.current;

    setMessages((prev) => [
      ...prev,
      {
        id: userTurnId,
        role: 'user',
        content: promptText,
        images: userImages.length > 0 ? userImages : undefined,
      },
      {
        id: assistantTurnId,
        role: 'assistant',
        content: '',
      },
    ]);

    setInput('');
    setAttachments([]);
    setStreamStatus({
      phase: 'connecting',
      activeTurnId: assistantTurnId,
      modelName: activeModel.name,
      ttftMs: 0,
      tokPerSec: 0,
      totalTokens: 0,
      startedAt: Date.now(),
      updatedAt: Date.now(),
      errorMessage: null,
    });

    const controller = new AbortController();
    abortControllerRef.current = controller;
    const requestStart = performance.now();
    let firstTokenTime: number | null = null;
    let tokenCount = 0;

    try {
      const payloadMessages = [
        ...priorMessages.map((m) => {
          if (m.images && m.images.length > 0) {
            return {
              role: m.role,
              content: [
                { type: 'text', text: m.content },
                ...m.images.map((img) => ({
                  type: 'image_url',
                  image_url: { url: img.dataUrl },
                })),
              ],
            };
          }
          return { role: m.role, content: m.content };
        }),
        {
          role: 'user',
          content:
            userImages.length > 0
              ? [
                  { type: 'text', text: promptText },
                  ...userImages.map((img) => ({
                    type: 'image_url',
                    image_url: { url: img.dataUrl },
                  })),
                ]
              : promptText,
        },
      ];

      const res = await apiFetch('/v1/chat/completions', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          model: activeModel.name || activeModel.id,
          messages: payloadMessages,
          stream: true,
          temperature: 0.2,
          ...(agentToolsEnabled ? { tools: AGENT_TOOLS_SPEC, tool_choice: 'auto' } : {}),
        }),
        signal: controller.signal,
      });

      if (!res.ok) {
        const errJson = await res.json().catch(() => ({}));
        throw new Error(errJson.error?.message || `HTTP ${res.status}: Engine failed`);
      }

      if (!engineStatus?.running) {
        onEngineLiveDetected?.();
      }

      if (!res.body) {
        throw new Error('No streaming response body received from engine');
      }

      setStreamStatus((prev) => ({
        ...prev,
        phase: 'streaming',
        updatedAt: Date.now(),
      }));

      const reader = res.body.getReader();
      const decoder = new TextDecoder();
      let rawAnswer = '';
      let rawReasoning = '';
      let buffer = '';
      const toolCallsAccum: Record<number, { id: string; name: string; args: string }> = {};

      sse: while (true) {
        const { value, done } = await reader.read();
        if (done) break;

        buffer += decoder.decode(value, { stream: true });
        const lines = buffer.split('\n');
        buffer = lines.pop() || '';

        for (const line of lines) {
          const trimmed = line.trim();
          if (!trimmed || trimmed.startsWith(':')) continue;
          if (trimmed === 'data: [DONE]') break sse;
          if (trimmed.startsWith('data: ')) {
            try {
              const parsed = JSON.parse(trimmed.slice(6));
              const delta = parsed.choices?.[0]?.delta;
              const chunk = delta?.content || '';
              const reasoningChunk = delta?.reasoning_content || '';
              const usageTokens = parsed.usage?.completion_tokens as number | undefined;

              if (delta?.tool_calls) {
                for (const tc of delta.tool_calls) {
                  const idx = tc.index ?? 0;
                  if (!toolCallsAccum[idx]) {
                    toolCallsAccum[idx] = { id: tc.id || `call_${idx}`, name: '', args: '' };
                  }
                  if (tc.function?.name) toolCallsAccum[idx].name += tc.function.name;
                  if (tc.function?.arguments) toolCallsAccum[idx].args += tc.function.arguments;
                }
              }

              if (chunk || reasoningChunk) {
                if (firstTokenTime === null) {
                  firstTokenTime = performance.now();
                  const ttft = Math.round(firstTokenTime - requestStart);
                  setLiveMetrics((prev) => ({ ...prev, ttftMs: ttft }));
                }
                if (typeof usageTokens === 'number' && usageTokens > 0) {
                  tokenCount = usageTokens;
                } else {
                  tokenCount += 1;
                }

                if (reasoningChunk) {
                  rawReasoning += reasoningChunk;
                }
                if (chunk) {
                  rawAnswer += chunk;
                }

                const { reasoning, answer, isThinkingOpen } = splitReasoningContent(rawAnswer, rawReasoning);
                const elapsedSec = (performance.now() - firstTokenTime) / 1000;
                const tokPerSec = elapsedSec > 0 ? Number((tokenCount / elapsedSec).toFixed(1)) : 0;
                const currentTtft = Math.round((firstTokenTime ?? performance.now()) - requestStart);

                setLiveMetrics((prev) => ({
                  ...prev,
                  ttftMs: currentTtft,
                  tokPerSec,
                  totalTokens: tokenCount,
                }));

                setStreamStatus((prev) => ({
                  ...prev,
                  phase: 'streaming',
                  ttftMs: currentTtft,
                  tokPerSec,
                  totalTokens: tokenCount,
                  updatedAt: Date.now(),
                }));

                setMessages((prev) =>
                  prev.map((turn) =>
                    turn.id === assistantTurnId
                      ? {
                          ...turn,
                          content: answer,
                          reasoning: reasoning || undefined,
                          isThinkingActive: isThinkingOpen || (Boolean(reasoning) && !answer),
                          ttftMs: currentTtft,
                          tokPerSec,
                          totalTokens: tokenCount,
                        }
                      : turn
                  )
                );
              }
            } catch {
              // ignore malformed SSE chunk
            }
          }
        }
      }

      // Check for and execute any agentic tool calls
      const pendingTools = Object.values(toolCallsAccum).filter((t) => t.name);
      if (agentToolsEnabled && pendingTools.length > 0) {
        const traces: ToolExecutionTrace[] = [];
        for (const tc of pendingTools) {
          const trace = await executeAgentTool(tc.name, tc.args);
          traces.push(trace);
        }

        setMessages((prev) =>
          prev.map((turn) =>
            turn.id === assistantTurnId
              ? {
                  ...turn,
                  toolTraces: traces,
                }
              : turn
          )
        );

        // Multi-Turn Agentic Synthesis Loop:
        // Feed tool results back so the model answers grounded in the tool observations
        try {
          const followUpMessages = [
            ...payloadMessages,
            {
              role: 'assistant',
              content: rawAnswer || null,
              tool_calls: pendingTools.map((t) => ({
                id: t.id,
                type: 'function',
                function: { name: t.name, arguments: t.args },
              })),
            },
            ...traces.map((tr, idx) => ({
              role: 'tool',
              tool_call_id: pendingTools[idx]?.id || `call_${idx}`,
              name: tr.name,
              content: tr.detail || tr.summary,
            })),
          ];

          const followUpRes = await apiFetch('/v1/chat/completions', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
              model: activeModel.name || activeModel.id,
              messages: followUpMessages,
              stream: true,
              temperature: 0.2,
            }),
            signal: controller.signal,
          });

          if (followUpRes.ok && followUpRes.body) {
            const fReader = followUpRes.body.getReader();
            let fBuffer = '';
            let synthAnswer = rawAnswer ? `${rawAnswer}\n\n` : '';

            while (true) {
              const { value, done } = await fReader.read();
              if (done) break;

              fBuffer += decoder.decode(value, { stream: true });
              const fLines = fBuffer.split('\n');
              fBuffer = fLines.pop() || '';

              for (const line of fLines) {
                const tr = line.trim();
                if (!tr || tr.startsWith(':') || tr === 'data: [DONE]') continue;
                if (tr.startsWith('data: ')) {
                  try {
                    const parsed = JSON.parse(tr.slice(6));
                    const d = parsed.choices?.[0]?.delta;
                    if (d?.content) {
                      synthAnswer += d.content;
                      tokenCount += 1;
                      const elapsedSec = (performance.now() - (firstTokenTime ?? requestStart)) / 1000;
                      const tokPerSec = elapsedSec > 0 ? Number((tokenCount / elapsedSec).toFixed(1)) : 0;

                      setLiveMetrics((prev) => ({
                        ...prev,
                        tokPerSec,
                        totalTokens: tokenCount,
                      }));

                      setMessages((prev) =>
                        prev.map((turn) =>
                          turn.id === assistantTurnId
                            ? {
                                ...turn,
                                content: synthAnswer,
                                totalTokens: tokenCount,
                                tokPerSec,
                              }
                            : turn
                        )
                      );
                    }
                  } catch {
                    // ignore
                  }
                }
              }
            }
          }
        } catch {
          // Keep initial answer and tool traces intact if follow-up synthesis fails
        }
      }
      setStreamStatus((prev) => ({
        ...prev,
        phase: 'completed',
        updatedAt: Date.now(),
        errorMessage: null,
      }));
    } catch (err: unknown) {
      if ((err as Error)?.name === 'AbortError') {
        setStreamStatus((prev) => ({
          ...prev,
          phase: 'aborted',
          updatedAt: Date.now(),
          errorMessage: 'Generation stopped',
        }));
      } else {
        const errMsg = (err as Error)?.message || 'Generation failed';
        setMessages((prev) =>
          prev.map((turn) =>
            turn.id === assistantTurnId
              ? {
                  ...turn,
                  content: `Error: ${errMsg}`,
                  isError: true,
                }
              : turn
          )
        );
        setStreamStatus((prev) => ({
          ...prev,
          phase: 'error',
          updatedAt: Date.now(),
          errorMessage: errMsg,
        }));
      }
    } finally {
      abortControllerRef.current = null;
    }
  };

  const sseBadge = formatSseStreamBadge(streamStatus);

  return (
    <div
      ref={chatRootRef}
      data-testid="chat-view-root"
      className="flex flex-col h-[calc(100vh-8.5rem)] max-w-5xl mx-auto space-y-3"
    >
      {/* Top Engine Banner */}
      <div className="flex flex-wrap items-center justify-between gap-2 px-4 py-2.5 rounded-xl border border-slate-800 bg-[#0F172A]/90 backdrop-blur-md shadow-xs">
        <div className="flex items-center gap-2.5 min-w-0">
          <div className="w-2.5 h-2.5 rounded-full bg-emerald-500 animate-pulse shrink-0" />
          <div className="flex items-center gap-2 min-w-0">
            <span className="text-xs font-semibold text-slate-200 truncate">
              {activeModel.name}
            </span>
            <span className="text-[10px] px-2 py-0.5 rounded-full bg-emerald-500/15 border border-emerald-500/30 text-emerald-400 font-mono whitespace-nowrap shrink-0">
              {activeModel.architecture || 'LLM'} · {activeModel.quant || 'Q4_K_M'}
            </span>
          </div>
        </div>

        <div className="flex items-center gap-2 text-xs shrink-0">
          {onOpenLoadConfig && (
            <button
              type="button"
              onClick={onOpenLoadConfig}
              className="px-2.5 py-1 rounded-lg border border-slate-700 bg-slate-800/80 hover:bg-slate-700 text-slate-300 transition-colors inline-flex items-center gap-1.5"
            >
              <SlidersHorizontal className="w-3.5 h-3.5" />
              <span>Config</span>
            </button>
          )}
          {engineStatus?.running && onStopEngine && (
            <button
              type="button"
              onClick={onStopEngine}
              className="px-2.5 py-1 rounded-lg border border-rose-500/40 bg-rose-500/10 hover:bg-rose-500/20 text-rose-300 transition-colors inline-flex items-center gap-1.5"
            >
              <Square className="w-3 h-3 fill-rose-300" />
              <span>Stop Engine</span>
            </button>
          )}
          <button
            type="button"
            onClick={handleClearHistory}
            title="Clear Chat History"
            className="p-1.5 rounded-lg border border-slate-700 bg-slate-800/80 hover:bg-slate-700 text-slate-400 hover:text-white transition-colors"
          >
            <Trash2 className="w-3.5 h-3.5" />
          </button>
        </div>
      </div>

      {/* Main Chat Thread */}
      <div className="flex-1 rounded-2xl border border-slate-800/90 bg-[#0B0F17] flex flex-col overflow-hidden shadow-inner">
        <div className="flex-1 p-4 sm:p-6 space-y-5 overflow-y-auto">
          {messages.length === 0 ? (
            <div className="h-full min-h-[320px] flex flex-col items-center justify-center text-center max-w-lg mx-auto space-y-4">
              <div className="w-12 h-12 rounded-2xl bg-emerald-500/10 border border-emerald-500/30 flex items-center justify-center text-emerald-400 shadow-lg">
                <Zap className="w-6 h-6" />
              </div>
              <div className="space-y-1">
                <h3 className="text-sm font-semibold text-slate-200">
                  {isLive ? `Ready to Infer on ${activeModel.name}` : 'Silica Cluster Engine Offline'}
                </h3>
                <p className="text-xs text-slate-400 max-w-sm">
                  {isLive
                    ? 'Distributed layer streaming with zero-OOM memory caps, persistent flash tensor caching, and agentic tools.'
                    : 'Connect or launch the engine to begin high-speed distributed inference.'}
                </p>
              </div>

              {/* Starter Prompt Chips */}
              <div className="grid grid-cols-1 sm:grid-cols-2 gap-2 pt-2 w-full text-left">
                {STARTER_PROMPTS.map((prompt, i) => (
                  <button
                    key={i}
                    type="button"
                    onClick={() => handleSendMessage(prompt)}
                    className="p-2.5 rounded-xl border border-slate-800 bg-[#0F172A]/70 hover:border-emerald-500/40 hover:bg-[#1E293B] text-xs text-slate-300 transition-all text-left group"
                  >
                    <span className="line-clamp-2">{prompt}</span>
                  </button>
                ))}
              </div>
            </div>
          ) : (
            messages.map((turn) => {
              const defaultThinkOpen = turn.isThinkingActive || !turn.content;
              const showThink = expandedReasoning[turn.id] ?? defaultThinkOpen;
              const showTools = expandedTools[turn.id] ?? true;

              return (
                <ChatTurnRow
                  key={turn.id}
                  turn={turn}
                  modelName={activeModel.name}
                  showThink={showThink}
                  showTools={showTools}
                  showGeneratingPlaceholder={sending && turn.role === 'assistant' && !turn.content}
                  onToggleReasoning={handleToggleReasoning}
                  onToggleTools={handleToggleTools}
                />
              );
            })
          )}
          <div ref={chatBottomRef} />
        </div>

        {/* Real-Time Cake Performance Status Bar */}
        <div className="px-4 py-2 bg-[#090D16] border-t border-slate-800 flex items-center justify-between text-[11px] font-mono text-slate-400 overflow-x-auto gap-4">
          <div className="flex items-center gap-4 shrink-0">
            <span
              data-testid="chat-sse-status"
              data-phase={streamStatus.phase}
              className="inline-flex items-center gap-1.5 text-slate-300"
            >
              <span
                className={`w-1.5 h-1.5 rounded-full ${
                  sseBadge.tone === 'live'
                    ? 'bg-emerald-400 animate-pulse'
                    : sseBadge.tone === 'ok'
                      ? 'bg-emerald-400'
                      : sseBadge.tone === 'warn'
                        ? 'bg-amber-400'
                        : sseBadge.tone === 'error'
                          ? 'bg-rose-400'
                          : 'bg-slate-500'
                }`}
              />
              <span>SSE:</span>
              <strong
                className={`font-semibold ${
                  sseBadge.tone === 'live' || sseBadge.tone === 'ok'
                    ? 'text-emerald-400'
                    : sseBadge.tone === 'warn'
                      ? 'text-amber-300'
                      : sseBadge.tone === 'error'
                        ? 'text-rose-400'
                        : 'text-slate-300'
                }`}
              >
                {sseBadge.label}
              </strong>
            </span>
            <span className="text-slate-300">
              TTFT:{' '}
              <strong className="text-emerald-400 font-semibold">
                {liveMetrics.ttftMs > 0 ? `${liveMetrics.ttftMs} ms` : '—'}
              </strong>
            </span>
            <span className="text-slate-300">
              Speed:{' '}
              <strong className="text-sky-400 font-semibold">
                {liveMetrics.tokPerSec > 0 ? `${liveMetrics.tokPerSec} tok/s` : '—'}
              </strong>
            </span>
            <span className="text-slate-300">
              Tokens:{' '}
              <strong className="text-amber-400 font-semibold">
                {liveMetrics.totalTokens > 0 ? liveMetrics.totalTokens : '—'}
              </strong>
            </span>
          </div>

          <div className="flex items-center gap-4 shrink-0">
            <span className="text-slate-300">
              Cluster RAM:{' '}
              <strong className="text-slate-200 font-semibold">{liveMetrics.ramPct}%</strong>
            </span>
            <span className="text-slate-300">
              Link RTT:{' '}
              <strong className="text-emerald-400 font-semibold">{liveMetrics.rttMs} ms</strong>
            </span>
          </div>
        </div>

        {/* Input Bar */}
        <div className="p-3 bg-[#0D1524] border-t border-slate-800 space-y-2">
          {attachments.length > 0 && (
            <div className="flex items-center gap-2 pb-1 border-b border-slate-800 overflow-x-auto">
              {attachments.map((att, idx) => (
                <div key={idx} className="relative group shrink-0">
                  <img
                    src={att.dataUrl}
                    alt={att.name}
                    className="h-14 w-14 rounded-lg object-cover border border-slate-700"
                  />
                  <button
                    type="button"
                    onClick={() => setAttachments((prev) => prev.filter((_, i) => i !== idx))}
                    className="absolute -top-1 -right-1 p-0.5 rounded-full bg-rose-600 text-white"
                  >
                    <X className="w-3 h-3" />
                  </button>
                </div>
              ))}
            </div>
          )}

          <div className="flex items-center justify-between text-xs text-slate-400 px-1">
            <button
              type="button"
              onClick={() => setAgentToolsEnabled((v) => !v)}
              className={`inline-flex items-center gap-1.5 px-2.5 py-1 rounded-full border text-[11px] font-medium transition-colors ${
                agentToolsEnabled
                  ? 'border-sky-500/40 bg-sky-500/15 text-sky-300'
                  : 'border-slate-800 bg-slate-900 text-slate-400 hover:text-slate-300'
              }`}
            >
              <Globe className="w-3 h-3" />
              <span>Agent Tools: {agentToolsEnabled ? 'ON' : 'OFF'}</span>
            </button>

            <span className="text-[11px] text-slate-400 hidden sm:inline">
              Shift + Enter for new line
            </span>
          </div>

          <form
            onSubmit={(e) => {
              e.preventDefault();
              handleSendMessage();
            }}
            className="flex items-center gap-2"
          >
            <input
              type="file"
              ref={fileInputRef}
              onChange={handleImageSelect}
              accept="image/*"
              multiple
              className="hidden"
            />
            <button
              type="button"
              onClick={() => fileInputRef.current?.click()}
              title="Attach Image (--mmproj)"
              className="p-2.5 rounded-xl border border-slate-700 bg-slate-800 text-slate-400 hover:text-white hover:bg-slate-700 transition-colors"
            >
              <ImagePlus className="w-4 h-4" />
            </button>

            <input
              type="text"
              value={input}
              onChange={(e) => setInput(e.target.value)}
              placeholder={
                isLive
                  ? agentToolsEnabled
                    ? 'Ask anything (Web Search, URL Fetch & Cluster Tools enabled)...'
                    : 'Type a message (Enter to send)...'
                  : 'Engine offline — load model to chat'
              }
              disabled={!isLive && !upstreamUrl}
              className="flex-1 bg-[#080C14] border border-slate-700/80 rounded-xl px-4 py-2.5 text-xs sm:text-sm text-slate-100 placeholder-slate-500 focus:outline-hidden focus:border-emerald-500 focus:ring-1 focus:ring-emerald-500 transition-colors disabled:opacity-50"
            />

            {sending ? (
              <button
                type="button"
                onClick={handleStopGeneration}
                className="px-4 py-2.5 rounded-xl bg-rose-600 hover:bg-rose-500 text-white text-xs font-medium inline-flex items-center gap-1.5 transition-colors shadow-sm"
              >
                <Square className="w-3.5 h-3.5 fill-white" />
                <span>Stop</span>
              </button>
            ) : (
              <button
                type="submit"
                disabled={(!input.trim() && attachments.length === 0) || (!isLive && !upstreamUrl)}
                className="px-4 py-2.5 rounded-xl bg-emerald-600 hover:bg-emerald-500 disabled:opacity-40 disabled:hover:bg-emerald-600 text-white text-xs font-medium inline-flex items-center gap-1.5 transition-colors shadow-sm"
              >
                <CornerDownLeft className="w-3.5 h-3.5" />
                <span>Send</span>
              </button>
            )}
          </form>
        </div>
      </div>
    </div>
  );
};
