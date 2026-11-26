import { apiFetch } from '../service/apiAuth';
import React, { useState } from 'react';
import {
  Check,
  Copy,
  ExternalLink,
  Play,
  Terminal,
  Wifi,
} from 'lucide-react';
import {
  EngineRuntimeStatus,
  GgufModelPreset,
  LiveHardwareProbe,
} from '../types/starCluster';

interface ApiHarnessConnectPageProps {
  activeModel: GgufModelPreset;
  liveHardware?: LiveHardwareProbe;
  engineStatus?: EngineRuntimeStatus;
}

export const ApiHarnessConnectPage: React.FC<ApiHarnessConnectPageProps> = ({
  activeModel,
  liveHardware,
  engineStatus,
}) => {
  const [copiedId, setCopiedId] = useState<string | null>(null);
  const [probeResult, setProbeResult] = useState<string | null>(null);
  const [probing, setProbing] = useState(false);

  const origin =
    typeof window !== 'undefined' ? window.location.origin : `http://${liveHardware?.hostIp || '127.0.0.1'}:3000`;
  const v1Base = `${origin}/v1`;
  const joinUrl = `${origin}/join`;

  const copyText = async (id: string, text: string) => {
    try {
      await navigator.clipboard.writeText(text);
      setCopiedId(id);
      setTimeout(() => setCopiedId(null), 1600);
    } catch {
      // ignore
    }
  };

  const runLiveV1Check = async () => {
    setProbing(true);
    setProbeResult(null);
    try {
      const res = await apiFetch('/v1/models');
      const data = await res.json();
      setProbeResult(JSON.stringify(data, null, 2));
    } catch {
      setProbeResult('Error probing /v1/models');
    } finally {
      setProbing(false);
    }
  };

  const snippets = [
    {
      id: 'curl',
      title: 'cURL (Tools + Streaming + 10s SSE Keepalive)',
      code: `curl -N ${v1Base}/chat/completions \\
  -H "Content-Type: application/json" \\
  -d '{
    "model": "${activeModel.id}",
    "stream": true,
    "messages": [{"role": "user", "content": "Summarize this repository"}],
    "tools": [{"type": "function", "function": {"name": "read_file", "parameters": {"type": "object"}}}]
  }'`,
    },
    {
      id: 'aider',
      title: 'Aider / OpenCode / Cline / Continue',
      code: `OPENAI_API_BASE=${v1Base}
OPENAI_API_KEY=silica-local
MODEL=${activeModel.id}
# Supports --jinja delta.tool_calls, image_url vision payloads, and 10s SSE keepalives.`,
    },
    {
      id: 'openai-sdk',
      title: 'OpenAI TypeScript / Python SDK',
      code: `import OpenAI from "openai";
const client = new OpenAI({ baseURL: "${v1Base}", apiKey: "silica-local" });
const stream = await client.chat.completions.create({
  model: "${activeModel.id}",
  stream: true,
  messages: [{ role: "user", content: "Hello Silica Cluster" }],
});`,
    },
  ];

  return (
    <div className="space-y-5">
      <div className="rounded-xl border border-slate-200 dark:border-slate-800 bg-white dark:bg-[#111827] p-5 flex flex-col sm:flex-row sm:items-center justify-between gap-4">
        <div>
          <h1 className="text-lg font-semibold text-slate-900 dark:text-slate-100">
            Connect OpenAI-Compatible Agents & Edge Workers
          </h1>
          <p className="text-xs text-slate-500 dark:text-slate-400 mt-0.5">
            Full <code className="font-mono-num">--jinja</code> tool calling, multimodal{' '}
            <code className="font-mono-num">image_url</code> forwarding, separated{' '}
            <code className="font-mono-num">reasoning_content</code> and WebGPU browser workers.
          </p>
        </div>
        <span className="text-xs font-mono-num text-slate-600 dark:text-slate-300 shrink-0">
          {engineStatus?.running ? `Live · ${engineStatus.upstreamUrl}` : 'Engine Offline (HTTP 503)'}
        </span>
      </div>

      {/* Endpoint bar */}
      <div className="rounded-xl border border-slate-200 dark:border-slate-800 bg-white dark:bg-[#111827] p-4 flex flex-col sm:flex-row sm:items-center justify-between gap-3">
        <div className="space-y-0.5">
          <p className="text-xs text-slate-500 dark:text-slate-400">OpenAI Base URL</p>
          <p className="text-sm font-mono-num font-semibold text-slate-900 dark:text-slate-100">
            {v1Base}
          </p>
        </div>
        <div className="flex items-center gap-2 shrink-0">
          <button
            type="button"
            onClick={() => copyText('base', v1Base)}
            className="inline-flex items-center gap-1.5 px-3 py-2 rounded-lg border border-slate-200 dark:border-slate-700 text-xs font-medium text-slate-700 dark:text-slate-200 hover:bg-slate-50 dark:hover:bg-slate-800 whitespace-nowrap shrink-0"
          >
            {copiedId === 'base' ? <Check className="w-3.5 h-3.5 text-emerald-500" /> : <Copy className="w-3.5 h-3.5" />}
            {copiedId === 'base' ? 'Copied' : 'Copy Base URL'}
          </button>
          <button
            type="button"
            disabled={probing}
            onClick={runLiveV1Check}
            className="inline-flex items-center gap-1.5 px-3.5 py-2 rounded-lg bg-emerald-600 hover:bg-emerald-500 text-white text-xs font-medium whitespace-nowrap shrink-0"
          >
            <Play className="w-3.5 h-3.5" />
            {probing ? 'Testing...' : 'Test /v1/models'}
          </button>
        </div>
      </div>

      {probeResult && (
        <pre className="p-4 rounded-xl border border-slate-200 dark:border-slate-800 bg-slate-50 dark:bg-[#090D16] font-mono-num text-xs text-slate-700 dark:text-slate-300 overflow-x-auto max-h-64">
          {probeResult}
        </pre>
      )}

      {/* SDK Snippets */}
      <div className="grid grid-cols-1 lg:grid-cols-3 gap-4">
        {snippets.map((s) => (
          <div
            key={s.id}
            className="rounded-xl border border-slate-200 dark:border-slate-800 bg-white dark:bg-[#111827] p-4 flex flex-col gap-3"
          >
            <div className="flex items-center justify-between gap-2">
              <h2 className="text-xs font-semibold text-slate-900 dark:text-slate-100 flex items-center gap-1.5">
                <Terminal className="w-3.5 h-3.5 text-emerald-600 dark:text-emerald-400 shrink-0" />
                {s.title}
              </h2>
              <button
                type="button"
                onClick={() => copyText(s.id, s.code)}
                className="p-1.5 rounded-md text-slate-500 hover:text-slate-900 dark:text-slate-400 dark:hover:text-slate-100 hover:bg-slate-100 dark:hover:bg-slate-800 shrink-0"
                aria-label={`Copy ${s.title}`}
              >
                {copiedId === s.id ? <Check className="w-3.5 h-3.5 text-emerald-500" /> : <Copy className="w-3.5 h-3.5" />}
              </button>
            </div>
            <pre className="flex-1 p-3 rounded-lg border border-slate-200 dark:border-slate-800 bg-slate-50 dark:bg-[#090D16] font-mono-num text-[11px] text-slate-700 dark:text-slate-300 overflow-x-auto">
              {s.code}
            </pre>
          </div>
        ))}
      </div>

      {/* Edge Scaling: Zero-Install WebGPU Worker (/join) */}
      <section className="rounded-xl border border-slate-200 dark:border-slate-800 bg-white dark:bg-[#111827] p-5 space-y-5">
        <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-3 pb-4 border-b border-slate-100 dark:border-slate-800">
          <div>
            <h2 className="text-sm font-semibold text-slate-900 dark:text-slate-100 flex items-center gap-2">
              <Wifi className="w-4 h-4 text-emerald-600 dark:text-emerald-400" />
              Edge Scaling: Zero-Install WebGPU Worker
            </h2>
            <p className="text-xs text-slate-500 dark:text-slate-400 mt-0.5">
              Expand your cluster without installing binaries via <code className="font-mono-num">/join</code>.
            </p>
          </div>
          <div className="flex items-center gap-2 shrink-0">
            <button
              type="button"
              onClick={() => copyText('join-url', joinUrl)}
              className="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-lg border border-slate-200 dark:border-slate-700 text-xs font-medium text-slate-700 dark:text-slate-200 hover:bg-slate-50 dark:hover:bg-slate-800 whitespace-nowrap shrink-0"
            >
              {copiedId === 'join-url' ? <Check className="w-3.5 h-3.5 text-emerald-500" /> : <Copy className="w-3.5 h-3.5" />}
              {copiedId === 'join-url' ? 'Copied /join URL' : 'Copy /join URL'}
            </button>
            <a
              href="/join"
              className="inline-flex items-center gap-1.5 px-3.5 py-1.5 rounded-lg bg-slate-900 dark:bg-slate-100 text-white dark:text-slate-900 text-xs font-medium whitespace-nowrap shrink-0 hover:opacity-90 transition-opacity"
            >
              Open /join Worker
              <ExternalLink className="w-3.5 h-3.5" />
            </a>
          </div>
        </div>
        <p className="font-mono-num text-xs text-slate-600 dark:text-slate-300 break-all">{joinUrl}</p>
      </section>
    </div>
  );
};

