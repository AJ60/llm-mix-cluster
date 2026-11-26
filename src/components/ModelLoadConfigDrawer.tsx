import React, { useState } from 'react';
import {
  AlertTriangle,
  CheckCircle2,
  Eye,
  Loader2,
  Play,
  RotateCcw,
  SlidersHorizontal,
  Terminal,
  X,
} from 'lucide-react';
import {
  BackendTuningConfig,
  ClusterTuningAdvice,
  ContextLengthOption,
  EngineRuntimeStatus,
  GgufModelPreset,
  KvCacheQuantOption,
  SpeculativeMode,
  StarClusterPlan,
} from '../types/starCluster';

interface ModelLoadConfigDrawerProps {
  open: boolean;
  model: GgufModelPreset;
  config: BackendTuningConfig;
  plan: StarClusterPlan;
  tuningAdvice?: ClusterTuningAdvice | null;
  engineStatus?: EngineRuntimeStatus;
  onUpdateConfig: (patch: Partial<BackendTuningConfig>) => void;
  onApplyAndReload: (spawnLocalBinary: boolean) => Promise<{ ok: boolean; message: string }>;
  onResetDefaults: () => void;
  onClose: () => void;
}

const CONTEXT_OPTIONS: ContextLengthOption[] = [2048, 4096, 8192, 16384, 32768, 65536, 131072];
const KV_OPTIONS: KvCacheQuantOption[] = ['f16', 'q8_0', 'q4_0'];

export const ModelLoadConfigDrawer: React.FC<ModelLoadConfigDrawerProps> = ({
  open,
  model,
  config,
  plan,
  tuningAdvice,
  engineStatus,
  onUpdateConfig,
  onApplyAndReload,
  onResetDefaults,
  onClose,
}) => {
  const [reloading, setReloading] = useState(false);
  const [reloadResult, setReloadResult] = useState<{ ok: boolean; message: string } | null>(null);

  if (!open) return null;

  const handshakeOnlyWorkers = plan.nodes.filter(
    (n) => n.role === 'Worker' && n.rpcNativeReady === false
  );
  const missingLocalFile = !model.isLocal || !model.localPath;

  const handleReload = async () => {
    setReloading(true);
    setReloadResult(null);
    try {
      const res = await onApplyAndReload(true);
      setReloadResult(res);
    } finally {
      setReloading(false);
    }
  };

  return (
    <div
      className="fixed inset-0 z-50 flex justify-end bg-slate-950/50 backdrop-blur-[2px]"
      role="dialog"
      aria-modal="true"
      aria-label="Advanced Model Load Config and Reload"
    >
      <div className="w-full max-w-xl h-full bg-white dark:bg-[#0E1525] border-l border-slate-200 dark:border-slate-800 flex flex-col">
        {/* Drawer Header */}
        <div className="px-5 py-4 border-b border-slate-200 dark:border-slate-800 flex items-center justify-between gap-3">
          <div>
            <p className="text-xs font-medium text-emerald-700 dark:text-emerald-400 flex items-center gap-1.5">
              <SlidersHorizontal className="w-3.5 h-3.5" />
              Model Load Config & Hot-Reload
            </p>
            <h2 className="text-base font-semibold text-slate-900 dark:text-slate-100 mt-0.5">
              {model.name}
            </h2>
          </div>
          <button
            type="button"
            onClick={onClose}
            aria-label="Close model load config"
            className="p-1.5 rounded-lg text-slate-500 hover:text-slate-900 dark:text-slate-400 dark:hover:text-slate-100 hover:bg-slate-100 dark:hover:bg-slate-800"
          >
            <X className="w-4 h-4" />
          </button>
        </div>

        {/* Drawer Body */}
        <div className="flex-1 overflow-y-auto p-5 space-y-5 text-xs">
          {/* Auto-detected GGUF Model Card */}
          <div className="rounded-lg border border-slate-200 dark:border-slate-800 bg-slate-50 dark:bg-[#111827] p-3.5 space-y-1.5">
            <div className="flex items-center justify-between">
              <span className="font-semibold text-slate-900 dark:text-slate-100">
                GGUF Model-Card Inspection
              </span>
              <span className="font-mono-num text-emerald-700 dark:text-emerald-400">
                {model.architecture} · {model.quant}
              </span>
            </div>
            <div className="grid grid-cols-2 gap-x-4 gap-y-1 font-mono-num text-slate-600 dark:text-slate-400">
              <span>Layers: {model.layers}</span>
              <span>Params: {model.activeParamsB}B / {model.totalParamsB}B</span>
              <span>Vocab Hash: {model.vocabHash ? model.vocabHash.slice(0, 10) : 'auto'}</span>
              <span>Template: {model.hasChatTemplate !== false ? 'Jinja ready' : 'Default'}</span>
            </div>
            {(plan.masterMmprojPath || config.mmprojPath) && (
              <p className="pt-1 text-emerald-700 dark:text-emerald-400 flex items-center gap-1.5 font-mono-num">
                <Eye className="w-3.5 h-3.5 shrink-0" />
                Master-pinned --mmproj: {config.mmprojPath || plan.masterMmprojPath}
              </p>
            )}
          </div>

          {(() => {
            const advice = tuningAdvice;
            if (!advice?.hasActionableChanges) return null;
            return (
              <div className="rounded-lg border border-amber-500/40 bg-amber-500/10 p-3 space-y-2">
                <div className="flex items-center justify-between gap-2">
                  <span className="font-semibold text-amber-800 dark:text-amber-300 flex items-center gap-1.5">
                    <AlertTriangle className="w-3.5 h-3.5 shrink-0" />
                    Tuning Advisor (Score {advice.healthScore}/100)
                  </span>
                  <button
                    type="button"
                    onClick={() => onUpdateConfig(advice.autoTunePatch)}
                    className="px-2.5 py-1 rounded-md bg-emerald-600 hover:bg-emerald-500 text-white font-semibold text-[11px] transition-colors"
                  >
                    Apply Optimal Flags
                  </button>
                </div>
                <ul className="space-y-1 text-[11px] text-amber-900 dark:text-amber-200/90">
                  {advice.items
                    .filter((i) => i.severity !== 'optimal')
                    .map((item) => (
                      <li key={item.id}>
                        <strong>{item.title}</strong> — {item.impact}
                      </li>
                    ))}
                </ul>
              </div>
            );
          })()}

          {/* llama-server Binary Path & Worker Offload Readiness */}
          <div className="space-y-2.5">
            <label className="space-y-1.5 block">
              <span className="font-medium text-slate-700 dark:text-slate-300 flex items-center justify-between gap-2">
                <span className="inline-flex items-center gap-1.5">
                  <Terminal className="w-3.5 h-3.5 text-slate-500" />
                  llama-server Binary Path
                </span>
                <span className="font-mono-num text-[11px] text-slate-500 dark:text-slate-400">
                  {engineStatus?.detectedLlamaBin ? 'Auto-detected' : 'Not in PATH'}
                </span>
              </span>
              <input
                type="text"
                placeholder={
                  engineStatus?.detectedLlamaBin ||
                  'Auto (or full path e.g. C:\\llama.cpp\\llama-server.exe)'
                }
                value={config.binaryPath ?? ''}
                onChange={(e) => onUpdateConfig({ binaryPath: e.target.value })}
                className="w-full rounded-lg border border-slate-200 dark:border-slate-700 bg-white dark:bg-[#111827] px-3 py-2 font-mono-num text-slate-900 dark:text-slate-100"
              />
            </label>

            {missingLocalFile && (
              <div className="rounded-lg border border-amber-500/40 bg-amber-500/10 p-2.5 text-amber-800 dark:text-amber-300 flex items-start gap-2">
                <AlertTriangle className="w-4 h-4 shrink-0 mt-0.5" />
                <span>
                  This is a catalog preset with no local file path. Use <strong>Browse GGUF</strong>{' '}
                  to select a local <code className="font-mono-num">.gguf</code> file before loading.
                </span>
              </div>
            )}

            {handshakeOnlyWorkers.length > 0 && (
              <div className="rounded-lg border border-amber-500/40 bg-amber-500/10 p-2.5 text-amber-800 dark:text-amber-300 flex items-start gap-2">
                <AlertTriangle className="w-4 h-4 shrink-0 mt-0.5" />
                <span>
                  {handshakeOnlyWorkers.map((w) => w.label).join(', ')} is connected in handshake-only
                  mode (missing native <code className="font-mono-num">librpc_server_exec.so</code>).
                  To offload layers to it, bundle native <code className="font-mono-num">rpc-server</code>{' '}
                  in the APK or run <code className="font-mono-num">rpc-server -H 0.0.0.0 -p 50052 -c</code>{' '}
                  in Termux.
                </span>
              </div>
            )}
          </div>

          {/* Context Length & KV Cache */}
          <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
            <label className="space-y-1.5 block">
              <span className="font-medium text-slate-700 dark:text-slate-300">
                Context Length (-c)
              </span>
              <select
                value={config.contextLength}
                onChange={(e) =>
                  onUpdateConfig({ contextLength: Number(e.target.value) as ContextLengthOption })
                }
                className="w-full rounded-lg border border-slate-200 dark:border-slate-700 bg-white dark:bg-[#111827] px-3 py-2 font-mono-num text-slate-900 dark:text-slate-100"
              >
                {CONTEXT_OPTIONS.map((ctx) => (
                  <option key={ctx} value={ctx}>
                    {ctx.toLocaleString()} tokens
                  </option>
                ))}
              </select>
            </label>

            <label className="space-y-1.5 block">
              <span className="font-medium text-slate-700 dark:text-slate-300">
                KV Cache Quant (-ctk / -ctv)
              </span>
              <select
                value={config.kvCacheQuant}
                onChange={(e) =>
                  onUpdateConfig({ kvCacheQuant: e.target.value as KvCacheQuantOption })
                }
                className="w-full rounded-lg border border-slate-200 dark:border-slate-700 bg-white dark:bg-[#111827] px-3 py-2 font-mono-num text-slate-900 dark:text-slate-100"
              >
                {KV_OPTIONS.map((q) => (
                  <option key={q} value={q}>
                    {q.toUpperCase()}
                  </option>
                ))}
              </select>
            </label>
          </div>

          {/* GPU Offload & Manual Tensor Split */}
          <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
            <label className="space-y-1.5 block">
              <span className="font-medium text-slate-700 dark:text-slate-300">
                GPU Offload Layers (-ngl)
              </span>
              <input
                type="number"
                min={0}
                max={model.layers}
                placeholder={`Auto (${plan.nodes[0]?.nglLayers ?? 0})`}
                value={config.gpuOffloadLayers ?? ''}
                onChange={(e) =>
                  onUpdateConfig({
                    gpuOffloadLayers: e.target.value === '' ? null : Number(e.target.value),
                  })
                }
                className="w-full rounded-lg border border-slate-200 dark:border-slate-700 bg-white dark:bg-[#111827] px-3 py-2 font-mono-num text-slate-900 dark:text-slate-100"
              />
              <span className="text-[11px] text-slate-500 dark:text-slate-400 block pt-0.5">
                Safe VRAM Cap: {plan.nodes[0]?.nglLayers ?? 8}L max (prevents Windows WDDM Shared Memory spill)
              </span>
            </label>

            <label className="space-y-1.5 block">
              <span className="font-medium text-slate-700 dark:text-slate-300">
                Manual --tensor-split (optional)
              </span>
              <input
                type="text"
                placeholder={`Auto (${plan.tensorSplitCsv || '100.0'})`}
                value={config.manualTensorSplit ?? ''}
                onChange={(e) => onUpdateConfig({ manualTensorSplit: e.target.value })}
                className="w-full rounded-lg border border-slate-200 dark:border-slate-700 bg-white dark:bg-[#111827] px-3 py-2 font-mono-num text-slate-900 dark:text-slate-100"
              />
            </label>
          </div>

          {/* Speculative Decoding */}
          <div className="space-y-3 pt-2 border-t border-slate-200 dark:border-slate-800">
            <label className="space-y-1.5 block">
              <span className="font-medium text-slate-700 dark:text-slate-300">
                Speculative Decoding Mode
              </span>
              <select
                value={config.speculativeMode ?? 'off'}
                onChange={(e) =>
                  onUpdateConfig({ speculativeMode: e.target.value as SpeculativeMode })
                }
                className="w-full rounded-lg border border-slate-200 dark:border-slate-700 bg-white dark:bg-[#111827] px-3 py-2 text-slate-900 dark:text-slate-100"
              >
                <option value="off">Off</option>
                <option value="native_mtp">Native MTP (NextN)</option>
                <option value="prompt_lookup">Pure-Rust Prompt Lookup (N-gram)</option>
                <option value="external_draft">External Draft Model (--model-draft + Vocab Check)</option>
              </select>
            </label>

            {config.speculativeMode === 'external_draft' && (
              <div className="grid grid-cols-1 sm:grid-cols-3 gap-3">
                <label className="sm:col-span-2 space-y-1 block">
                  <span className="text-slate-600 dark:text-slate-400">Draft GGUF Path</span>
                  <input
                    type="text"
                    placeholder="./models/qwen2.5-0.5b-draft.gguf"
                    value={config.draftModelPath ?? ''}
                    onChange={(e) => onUpdateConfig({ draftModelPath: e.target.value })}
                    className="w-full rounded-lg border border-slate-200 dark:border-slate-700 bg-white dark:bg-[#111827] px-3 py-1.5 font-mono-num text-slate-900 dark:text-slate-100"
                  />
                </label>
                <label className="space-y-1 block">
                  <span className="text-slate-600 dark:text-slate-400">--draft-max</span>
                  <input
                    type="number"
                    min={1}
                    max={32}
                    value={config.draftMaxTokens ?? 8}
                    onChange={(e) => onUpdateConfig({ draftMaxTokens: Number(e.target.value) })}
                    className="w-full rounded-lg border border-slate-200 dark:border-slate-700 bg-white dark:bg-[#111827] px-3 py-1.5 font-mono-num text-slate-900 dark:text-slate-100"
                  />
                </label>
              </div>
            )}
          </div>

          {/* Long-Agent & Vision Paths */}
          <div className="grid grid-cols-1 sm:grid-cols-2 gap-4 pt-2 border-t border-slate-200 dark:border-slate-800">
            <label className="space-y-1.5 block">
              <span className="font-medium text-slate-700 dark:text-slate-300">
                Vision Projector (--mmproj, Master-pinned)
              </span>
              <input
                type="text"
                placeholder="Auto-detected *mmproj*.gguf"
                value={config.mmprojPath ?? ''}
                onChange={(e) => onUpdateConfig({ mmprojPath: e.target.value })}
                className="w-full rounded-lg border border-slate-200 dark:border-slate-700 bg-white dark:bg-[#111827] px-3 py-2 font-mono-num text-slate-900 dark:text-slate-100"
              />
            </label>

            <label className="space-y-1.5 block">
              <span className="font-medium text-slate-700 dark:text-slate-300">
                Persistent KV Prefix Cache (--slot-save-path)
              </span>
              <input
                type="text"
                placeholder="./.silica-kv-cache"
                value={config.slotSavePath ?? './.silica-kv-cache'}
                onChange={(e) => onUpdateConfig({ slotSavePath: e.target.value })}
                className="w-full rounded-lg border border-slate-200 dark:border-slate-700 bg-white dark:bg-[#111827] px-3 py-2 font-mono-num text-slate-900 dark:text-slate-100"
              />
            </label>
          </div>

          {/* Threads, Batch, UBatch, Prefix Cache */}
          <div className="grid grid-cols-2 sm:grid-cols-4 gap-3">
            <label className="space-y-1 block">
              <span className="text-slate-600 dark:text-slate-400">Threads (-t)</span>
              <input
                type="number"
                min={1}
                max={64}
                placeholder="Auto"
                value={config.threads ?? ''}
                onChange={(e) =>
                  onUpdateConfig({ threads: e.target.value === '' ? null : Number(e.target.value) })
                }
                className="w-full rounded-lg border border-slate-200 dark:border-slate-700 bg-white dark:bg-[#111827] px-2.5 py-1.5 font-mono-num text-slate-900 dark:text-slate-100"
              />
            </label>
            <label className="space-y-1 block">
              <span className="text-slate-600 dark:text-slate-400">Batch (-b)</span>
              <input
                type="number"
                min={128}
                max={4096}
                step={128}
                value={config.batchSize ?? 1024}
                onChange={(e) => onUpdateConfig({ batchSize: Number(e.target.value) })}
                className="w-full rounded-lg border border-slate-200 dark:border-slate-700 bg-white dark:bg-[#111827] px-2.5 py-1.5 font-mono-num text-slate-900 dark:text-slate-100"
              />
            </label>
            <label className="space-y-1 block">
              <span className="text-slate-600 dark:text-slate-400">UBatch (-ub)</span>
              <input
                type="number"
                min={64}
                max={1024}
                step={64}
                value={config.ubatchSize ?? 256}
                onChange={(e) => onUpdateConfig({ ubatchSize: Number(e.target.value) })}
                className="w-full rounded-lg border border-slate-200 dark:border-slate-700 bg-white dark:bg-[#111827] px-2.5 py-1.5 font-mono-num text-slate-900 dark:text-slate-100"
              />
            </label>
            <label className="space-y-1 block">
              <span className="text-slate-600 dark:text-slate-400">--cache-reuse</span>
              <input
                type="number"
                min={0}
                max={2048}
                step={64}
                value={config.cacheReuse ?? 256}
                onChange={(e) => onUpdateConfig({ cacheReuse: Number(e.target.value) })}
                className="w-full rounded-lg border border-slate-200 dark:border-slate-700 bg-white dark:bg-[#111827] px-2.5 py-1.5 font-mono-num text-slate-900 dark:text-slate-100"
              />
            </label>
          </div>

          {/* Runtime Feature Checkboxes */}
          <div className="grid grid-cols-1 sm:grid-cols-2 gap-2.5 pt-2 border-t border-slate-200 dark:border-slate-800">
            {[
              {
                label: 'FlashAttention (-fa on)',
                checked: config.enableFlashAttn,
                onChange: (v: boolean) => onUpdateConfig({ enableFlashAttn: v }),
              },
              {
                label: 'Jinja Tool Calling (--jinja)',
                checked: config.enableJinja !== false,
                onChange: (v: boolean) => onUpdateConfig({ enableJinja: v }),
              },
              {
                label: 'Continuous Batching (--cont-batching)',
                checked: config.contBatching !== false,
                onChange: (v: boolean) => onUpdateConfig({ contBatching: v }),
              },
              {
                label: 'Unified KV Buffer (--kv-unified)',
                checked: config.kvUnified !== false,
                onChange: (v: boolean) => onUpdateConfig({ kvUnified: v }),
              },
              {
                label: 'CPU KV Offload (-nkvo · auto on <=4GB)',
                checked: config.noKvOffload ?? plan.autoNkvo,
                onChange: (v: boolean) => onUpdateConfig({ noKvOffload: v }),
              },
              {
                label: 'MoE Experts on CPU (-ot .ffn_.*_exps.=CPU)',
                checked: config.moeCpuOffload,
                onChange: (v: boolean) => onUpdateConfig({ moeCpuOffload: v }),
              },
              {
                label: `Disable mmap (--no-mmap · auto ${plan.useNoMmap ? 'ON >=1.15x' : 'OFF <1.15x'})`,
                checked: config.forceNoMmap ?? plan.useNoMmap,
                onChange: (v: boolean) => onUpdateConfig({ forceNoMmap: v }),
              },
            ].map((item) => (
              <label
                key={item.label}
                className="flex items-center gap-2.5 px-3 py-2 rounded-lg border border-slate-200 dark:border-slate-800 cursor-pointer hover:bg-slate-50 dark:hover:bg-slate-800/50"
              >
                <input
                  type="checkbox"
                  checked={item.checked}
                  onChange={(e) => item.onChange(e.target.checked)}
                  className="rounded border-slate-300 text-emerald-600 focus:ring-emerald-500"
                />
                <span className="text-slate-700 dark:text-slate-200">{item.label}</span>
              </label>
            ))}
          </div>

          {/* Generated Verified Stock CLI Preview */}
          <div className="space-y-1.5">
            <span className="font-medium text-slate-700 dark:text-slate-300">
              Verified Stock llama.cpp v1.0.0 Launch Flags
            </span>
            <pre className="p-3 rounded-lg border border-slate-200 dark:border-slate-800 bg-slate-50 dark:bg-[#090D16] font-mono-num text-[11px] text-slate-700 dark:text-slate-300 overflow-x-auto whitespace-pre-wrap break-all">
              llama-server {plan.cliArgs.join(' ')}
            </pre>
          </div>
        </div>

        {reloadResult && (
          <div className="px-5 py-2.5 border-t border-slate-200 dark:border-slate-800 bg-slate-50/70 dark:bg-[#0B1120]">
            <div
              role={reloadResult.ok ? 'status' : 'alert'}
              className={`rounded-lg border p-3 text-xs flex items-start gap-2 ${
                reloadResult.ok
                  ? 'border-emerald-500/40 bg-emerald-500/10 text-emerald-800 dark:text-emerald-300'
                  : 'border-rose-500/40 bg-rose-500/10 text-rose-800 dark:text-rose-300'
              }`}
            >
              {reloadResult.ok ? (
                <CheckCircle2 className="w-4 h-4 shrink-0 mt-0.5" />
              ) : (
                <AlertTriangle className="w-4 h-4 shrink-0 mt-0.5" />
              )}
              <span className="break-words">{reloadResult.message}</span>
            </div>
          </div>
        )}

        {/* Footer Actions */}
        <div className="px-5 py-3.5 border-t border-slate-200 dark:border-slate-800 flex items-center justify-between gap-3">
          <button
            type="button"
            onClick={onResetDefaults}
            className="inline-flex items-center gap-1.5 px-3 py-2 rounded-lg border border-slate-200 dark:border-slate-700 text-xs font-medium text-slate-600 dark:text-slate-300 hover:bg-slate-50 dark:hover:bg-slate-800"
          >
            <RotateCcw className="w-3.5 h-3.5" />
            Auto-Optimal Defaults
          </button>
          <button
            type="button"
            disabled={reloading}
            onClick={handleReload}
            className="inline-flex items-center gap-2 px-4 py-2 rounded-lg bg-emerald-600 hover:bg-emerald-500 disabled:opacity-60 text-white text-xs font-semibold transition-colors"
          >
            {reloading ? (
              <>
                <Loader2 className="w-3.5 h-3.5 animate-spin" />
                Draining & Reloading...
              </>
            ) : (
              <>
                <Play className="w-3.5 h-3.5" />
                Apply & Reload Model
              </>
            )}
          </button>
        </div>
      </div>
    </div>
  );
};
