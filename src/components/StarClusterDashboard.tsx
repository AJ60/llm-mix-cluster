import React, { useMemo, useState } from 'react';
import {
  Cpu,
  Laptop,
  Loader2,
  MessageSquare,
  Play,
  RefreshCw,
  Smartphone,
  Sparkles,
  Square,
  Zap,
} from 'lucide-react';
import {
  BackendTuningConfig,
  EngineRuntimeStatus,
  GGUF_MODEL_PRESETS,
  GgufModelPreset,
  LiveHardwareProbe,
  PlannedStarNode,
  StarClusterPlan,
} from '../types/starCluster';
import { isSameAndroidWorker } from '../domain/starClusterPlanner';
import { analyzeClusterTuning } from '../domain/tuningAdvisor';


interface StarClusterDashboardProps {
  activeModel: GgufModelPreset;
  availableModels?: GgufModelPreset[];
  onSelectModel: (model: GgufModelPreset) => void;
  tuningConfig: BackendTuningConfig;
  plan: StarClusterPlan;
  liveHardware?: LiveHardwareProbe;
  engineStatus?: EngineRuntimeStatus;
  onRescanDevices: () => void;
  onOpenModels: () => void;
  onOpenLoadConfig: () => void;
  onOpenChat: () => void;
  onAutoTune?: (patch: Partial<BackendTuningConfig>) => Promise<{ ok: boolean; message: string }>;
  onApplyAndReload?: (spawnLocalBinary: boolean) => Promise<{ ok: boolean; message: string }>;
  onStopEngine?: () => Promise<{ ok: boolean; message: string }>;
}

export const StarClusterDashboard: React.FC<StarClusterDashboardProps> = ({
  activeModel,
  availableModels,
  onSelectModel,
  tuningConfig,
  plan,
  liveHardware,
  engineStatus,
  onRescanDevices,
  onOpenModels,
  onOpenLoadConfig,
  onOpenChat,
  onAutoTune,
  onApplyAndReload,
  onStopEngine,
}) => {
  const [reloadingEngine, setReloadingEngine] = useState(false);
  const [autoTuning, setAutoTuning] = useState(false);
  const [stoppingEngine, setStoppingEngine] = useState(false);
  const [engineMsg, setEngineMsg] = useState<{ ok: boolean; text: string } | null>(null);
  const tuningAdvice = useMemo(
    () => analyzeClusterTuning(plan, activeModel, tuningConfig, liveHardware),
    [plan, activeModel, tuningConfig, liveHardware]
  );

  const handleAutoTuneClick = async () => {
    if (!onAutoTune) return;
    setAutoTuning(true);
    setEngineMsg(null);
    try {
      const res = await onAutoTune(tuningAdvice.autoTunePatch);
      setEngineMsg({ ok: res.ok, text: res.message });
    } finally {
      setAutoTuning(false);
    }
  };

  const modelChoices = useMemo(() => {
    const list = availableModels && availableModels.length > 0 ? availableModels : GGUF_MODEL_PRESETS;
    const exists = list.some((p) => p.id === activeModel.id);
    return exists ? list : [activeModel, ...list];
  }, [availableModels, activeModel]);

  const rawWorkers = plan.nodes.filter(
    (n) => n.role === 'Worker' && n.isConnected && (n.allocatedLayers ?? 0) > 0
  );
  const liveWorkers = useMemo(() => {
    const deduped: PlannedStarNode[] = [];
    for (const w of rawWorkers) {
      const isDup = deduped.some((existing) =>
        isSameAndroidWorker(existing, w) || (existing.ip === w.ip && existing.rpcPort === w.rpcPort)
      );
      if (!isDup) deduped.push(w);
    }
    return deduped;
  }, [rawWorkers]);
  const masterNode = plan.nodes.find((n) => n.role === 'Master') ?? plan.nodes[0];
  const isEngineLive = Boolean(engineStatus?.running);

  const handleStartOrReload = async () => {
    if (!onApplyAndReload) {
      onOpenLoadConfig();
      return;
    }
    setReloadingEngine(true);
    setEngineMsg(null);
    try {
      const res = await onApplyAndReload(true);
      setEngineMsg({ ok: res.ok, text: res.message });
    } finally {
      setReloadingEngine(false);
    }
  };

  const handleStop = async () => {
    if (!onStopEngine) return;
    setStoppingEngine(true);
    setEngineMsg(null);
    try {
      const res = await onStopEngine();
      setEngineMsg({ ok: res.ok, text: res.message });
    } finally {
      setStoppingEngine(false);
    }
  };

  return (
    <div className="space-y-5">
      {/* Primary Toolbar: Mode Switcher & Quick Controls */}
      <div className="rounded-xl border border-slate-200 dark:border-slate-800 bg-white dark:bg-[#111827] p-3 flex flex-wrap items-center justify-between gap-3">
        {/* Quick Actions */}
        <div className="flex items-center gap-2">
          <button
            type="button"
            onClick={onRescanDevices}
            title="Scan for connected phones, ADB USB tunnels, and local hardware"
            className="inline-flex items-center gap-1.5 px-2.5 py-1.5 rounded-lg border border-slate-200 dark:border-slate-700 text-xs font-medium text-slate-700 dark:text-slate-200 hover:bg-slate-50 dark:hover:bg-slate-800 transition-colors"
          >
            <RefreshCw className="w-3.5 h-3.5" />
            <span className="hidden sm:inline">Rescan</span>
          </button>

        </div>
      </div>

      {engineMsg && (
        <div
          className={`rounded-xl border p-3 text-xs flex items-center justify-between gap-3 ${
            engineMsg.ok
              ? 'border-emerald-500/40 bg-emerald-500/10 text-emerald-800 dark:text-emerald-300'
              : 'border-rose-500/40 bg-rose-500/10 text-rose-800 dark:text-rose-300'
          }`}
        >
          <span>{engineMsg.text}</span>
          <button
            type="button"
            onClick={() => setEngineMsg(null)}
            className="text-[11px] underline opacity-75 hover:opacity-100"
          >
            Dismiss
          </button>
        </div>
      )}

      {/* Cluster Tuning Advisor & Worker Auto-Config Card */}
      <div className="rounded-xl border border-slate-200 dark:border-slate-800 bg-white dark:bg-[#111827] p-4 space-y-3">
        <div className="flex flex-wrap items-center justify-between gap-3">
          <div className="flex items-center gap-2.5">
            <Zap
              className={`w-4 h-4 ${
                tuningAdvice.hasActionableChanges ? 'text-amber-500' : 'text-emerald-500'
              }`}
            />
            <div>
              <div className="flex items-center gap-2">
                <h3 className="text-xs font-semibold text-slate-900 dark:text-slate-100">
                  Cluster Tuning Advisor & Worker Auto-Config
                </h3>
                <span
                  className={`font-mono-num text-[11px] font-semibold px-2 py-0.5 rounded-md ${
                    tuningAdvice.healthScore >= 85
                      ? 'bg-emerald-500/15 text-emerald-700 dark:text-emerald-300'
                      : tuningAdvice.healthScore >= 60
                        ? 'bg-amber-500/15 text-amber-700 dark:text-amber-300'
                        : 'bg-rose-500/15 text-rose-700 dark:text-rose-300'
                  }`}
                >
                  Score {tuningAdvice.healthScore}/100
                </span>
                <span
                  className={`font-mono-num text-[11px] font-semibold px-2 py-0.5 rounded-md ${
                    tuningAdvice.clusterAdaptiveLoadFactor >= 1.0
                      ? 'bg-sky-500/15 text-sky-700 dark:text-sky-300'
                      : tuningAdvice.clusterAdaptiveLoadFactor >= 0.5
                        ? 'bg-amber-500/15 text-amber-700 dark:text-amber-300'
                        : 'bg-rose-500/15 text-rose-700 dark:text-rose-300'
                  }`}
                >
                  ALF {tuningAdvice.clusterAdaptiveLoadFactor.toFixed(2)}
                </span>
              </div>
              <p className="text-[11px] text-slate-500 dark:text-slate-400 mt-0.5">
                {tuningAdvice.summary}
              </p>
            </div>
          </div>

          {tuningAdvice.hasActionableChanges && onAutoTune && (
            <button
              type="button"
              disabled={autoTuning}
              onClick={handleAutoTuneClick}
              className="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-lg bg-emerald-600 hover:bg-emerald-500 disabled:opacity-50 text-white text-xs font-semibold transition-colors"
            >
              {autoTuning ? (
                <Loader2 className="w-3.5 h-3.5 animate-spin" />
              ) : (
                <Sparkles className="w-3.5 h-3.5" />
              )}
              <span>Auto-Tune & Apply</span>
            </button>
          )}
        </div>

        <div className="grid grid-cols-1 md:grid-cols-2 gap-2.5">
          {tuningAdvice.items.slice(0, 4).map((item) => (
            <div
              key={item.id}
              className={`rounded-lg border p-2.5 text-xs space-y-1 ${
                item.severity === 'critical'
                  ? 'border-rose-500/40 bg-rose-500/5'
                  : item.severity === 'warning'
                    ? 'border-amber-500/40 bg-amber-500/5'
                    : 'border-emerald-500/30 bg-emerald-500/5'
              }`}
            >
              <div className="font-semibold text-slate-900 dark:text-slate-100">{item.title}</div>
              <div className="text-[11px] text-slate-600 dark:text-slate-400 leading-relaxed">
                {item.detail}
              </div>
              <div className="text-[11px] font-mono-num text-emerald-700 dark:text-emerald-400">
                Impact: {item.impact}
              </div>
            </div>
          ))}
        </div>
      </div>

      {/* ========================================================================= */}
      {/* SIMPLE MODE (LM Studio / Ollama Friendly Experience for Non-Tech Users)   */}
      {/* ========================================================================= */}
      <div className="space-y-4">
          {/* Main Hero Card: Selected Model + 1-Click Engine Runner */}
          <div className="rounded-2xl border border-slate-200 dark:border-slate-800 bg-white dark:bg-[#111827] p-6 shadow-xs">
            <div className="flex flex-col lg:flex-row lg:items-center justify-between gap-6">
              {/* Left Column: Model Info & Selector */}
              <div className="space-y-3 max-w-xl">
                <div className="flex flex-wrap items-center gap-2">
                  <span className="px-2.5 py-0.5 rounded-full text-[11px] font-semibold bg-emerald-500/15 text-emerald-700 dark:text-emerald-300">
                    {activeModel.architecture} · {activeModel.quant}
                  </span>
                  <span className="px-2.5 py-0.5 rounded-full text-[11px] font-medium bg-slate-100 dark:bg-slate-800 text-slate-600 dark:text-slate-300">
                    {(activeModel.sizeMb / 1024).toFixed(1)} GB Weights
                  </span>
                  <span className="px-2.5 py-0.5 rounded-full text-[11px] font-medium bg-slate-100 dark:bg-slate-800 text-slate-600 dark:text-slate-300">
                    {activeModel.layers} Layers
                  </span>
                </div>

                <div>
                  <h2 className="text-xl font-bold tracking-tight text-slate-900 dark:text-slate-100">
                    {activeModel.name}
                  </h2>
                  <p className="text-xs text-slate-500 dark:text-slate-400 mt-1">
                    {activeModel.summary || 'High-performance local LLM configured for distributed inference.'}
                  </p>
                </div>

                {/* Model Selector Dropdown */}
                <div className="flex flex-wrap items-center gap-2 pt-1 max-w-full">
                  <select
                    id="simple-model-select"
                    value={activeModel.id}
                    onChange={(e) => {
                      const found = modelChoices.find((m) => m.id === e.target.value);
                      if (found) onSelectModel(found);
                    }}
                    className="w-full sm:w-auto max-w-full min-w-0 rounded-lg border border-slate-200 dark:border-slate-700 bg-slate-50 dark:bg-[#0D1424] px-3 py-2 text-xs font-semibold text-slate-900 dark:text-slate-100 focus:outline-none focus:ring-2 focus:ring-emerald-500"
                  >
                    {modelChoices.map((m) => (
                      <option key={m.id} value={m.id}>
                        {m.name} ({(m.sizeMb / 1024).toFixed(1)} GB · {m.quant})
                      </option>
                    ))}
                  </select>

                  <button
                    type="button"
                    onClick={onOpenModels}
                    className="px-3 py-2 rounded-lg border border-slate-200 dark:border-slate-700 text-xs font-medium text-slate-700 dark:text-slate-300 hover:bg-slate-50 dark:hover:bg-slate-800 transition-colors whitespace-nowrap shrink-0"
                  >
                    Browse Models
                  </button>
                </div>
              </div>

              {/* Right Column: Engine Status & 1-Click Launch Button */}
              <div className="flex flex-col items-start lg:items-end justify-between gap-4 shrink-0 bg-slate-50 dark:bg-[#0D1424] p-5 rounded-xl border border-slate-100 dark:border-slate-800/80 w-full lg:w-auto sm:min-w-[280px]">
                <div className="space-y-1">
                  <div className="flex items-center gap-2">
                    <span
                      className={`w-2.5 h-2.5 rounded-full ${
                        isEngineLive
                          ? 'bg-emerald-500 animate-pulse'
                          : 'bg-slate-400 dark:bg-slate-600'
                      }`}
                    />
                    <span className="text-sm font-semibold text-slate-900 dark:text-slate-100">
                      {isEngineLive ? 'Engine Live & Serving' : 'Engine Ready to Start'}
                    </span>
                  </div>
                  <p className="text-xs text-slate-500 dark:text-slate-400 font-mono-num">
                    {isEngineLive
                      ? `http://127.0.0.1:8080 · ~${plan.estimatedClusterTokPerSec} tok/s`
                      : `Cluster Pool: ${(plan.totalSafePoolMb / 1024).toFixed(1)} GB Available`}
                  </p>
                </div>

                <div className="flex flex-wrap items-center gap-2 w-full">
                  {isEngineLive ? (
                    <>
                      <button
                        type="button"
                        onClick={onOpenChat}
                        className="flex-1 inline-flex items-center justify-center gap-2 px-4 py-2.5 rounded-xl bg-emerald-600 hover:bg-emerald-500 text-white text-xs font-semibold shadow-xs transition-colors"
                      >
                        <MessageSquare className="w-4 h-4" />
                        Chat Now
                      </button>
                      <button
                        type="button"
                        disabled={stoppingEngine}
                        onClick={handleStop}
                        className="inline-flex items-center justify-center gap-1.5 px-3 py-2.5 rounded-xl border border-rose-500/40 text-rose-600 dark:text-rose-400 hover:bg-rose-500/10 text-xs font-medium transition-colors"
                        title="Stop background inference engine"
                      >
                        {stoppingEngine ? <Loader2 className="w-4 h-4 animate-spin" /> : <Square className="w-3.5 h-3.5 fill-current" />}
                        Stop
                      </button>
                    </>
                  ) : (
                    <button
                      type="button"
                      disabled={reloadingEngine}
                      onClick={handleStartOrReload}
                      className="w-full inline-flex items-center justify-center gap-2 px-5 py-3 rounded-xl bg-emerald-600 hover:bg-emerald-500 disabled:opacity-60 text-white text-sm font-bold shadow-xs transition-colors"
                    >
                      {reloadingEngine ? (
                        <>
                          <Loader2 className="w-4 h-4 animate-spin" />
                          Starting Engine...
                        </>
                      ) : (
                        <>
                          <Play className="w-4 h-4 fill-current" />
                          Start Engine & Chat
                        </>
                      )}
                    </button>
                  )}
                </div>
              </div>
            </div>
          </div>

          {/* Simple Hardware Distribution Overview */}
          <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
            {/* Devices in Compute Pool */}
            <div className="rounded-xl border border-slate-200 dark:border-slate-800 bg-white dark:bg-[#111827] p-5 space-y-4">
              <div className="flex items-center justify-between">
                <div className="flex items-center gap-2">
                  <Cpu className="w-4 h-4 text-emerald-500" />
                  <h3 className="text-sm font-semibold text-slate-900 dark:text-slate-100">
                    Hardware Compute Cluster
                  </h3>
                </div>
                <span className="text-xs font-medium text-slate-500 dark:text-slate-400">
                  {1 + liveWorkers.length} {1 + liveWorkers.length === 1 ? 'device' : 'devices'} active
                </span>
              </div>

              {/* Connected Devices List */}
              <div className="space-y-2.5">
                {/* Master PC */}
                <div className="flex items-center justify-between p-3 rounded-lg border border-slate-100 dark:border-slate-800 bg-slate-50 dark:bg-[#0D1424]">
                  <div className="flex items-center gap-3">
                    <div className="w-9 h-9 rounded-lg bg-emerald-500/10 text-emerald-600 dark:text-emerald-400 flex items-center justify-center">
                      <Laptop className="w-5 h-5" />
                    </div>
                    <div>
                      <p className="text-xs font-semibold text-slate-900 dark:text-slate-100">
                        {masterNode?.label || 'Computer Host (Master)'}
                      </p>
                      <p className="text-[11px] text-slate-500 dark:text-slate-400">
                        {masterNode?.backendDriver || 'Primary GPU / CPU'} · Layers L{masterNode?.layerStart}-L{masterNode?.layerEnd}
                      </p>
                    </div>
                  </div>
                  <div className="text-right">
                    <span className="text-xs font-bold text-emerald-600 dark:text-emerald-400">
                      {masterNode?.sharePct || 100}%
                    </span>
                    <p className="text-[10px] text-slate-400">compute</p>
                  </div>
                </div>

                {/* Android / Connected Workers */}
                {liveWorkers.map((w) => (
                  <div
                    key={w.nodeId}
                    className="flex items-center justify-between p-3 rounded-lg border border-slate-100 dark:border-slate-800 bg-slate-50 dark:bg-[#0D1424]"
                  >
                    <div className="flex items-center gap-3">
                      <div className="w-9 h-9 rounded-lg bg-sky-500/10 text-sky-600 dark:text-sky-400 flex items-center justify-center">
                        <Smartphone className="w-5 h-5" />
                      </div>
                      <div>
                        <p className="text-xs font-semibold text-slate-900 dark:text-slate-100">
                          {w.label.replace(/\s*\((Master|Worker|Android)\)/gi, '')}
                        </p>
                        <p className="text-[11px] text-slate-500 dark:text-slate-400">
                          {w.linkType} · Layers L{w.layerStart}–L{w.layerEnd} (Safe Mode)
                        </p>
                      </div>
                    </div>
                    <div className="text-right">
                      <span className="text-xs font-bold text-sky-600 dark:text-sky-400">
                        {w.sharePct}%
                      </span>
                      <p className="text-[10px] text-slate-400">compute</p>
                    </div>
                  </div>
                ))}
              </div>

              {/* Compute Split Bar */}
              <div className="space-y-1.5 pt-1">
                <div className="h-2 w-full rounded-full bg-slate-100 dark:bg-slate-800 overflow-hidden flex">
                  {plan.nodes.filter(n => n.allocatedLayers > 0).map((n, i) => (
                    <div
                      key={n.nodeId}
                      style={{ width: `${n.sharePct}%` }}
                      className={`h-full ${i === 0 ? 'bg-emerald-500' : 'bg-sky-500'}`}
                      title={`${n.label}: ${n.sharePct}%`}
                    />
                  ))}
                </div>
                <p className="text-[11px] text-slate-500 dark:text-slate-400">
                  {liveWorkers.length > 0
                    ? `Your phone offloads ${liveWorkers.reduce((acc, w) => acc + w.allocatedLayers, 0)} layers, freeing ${liveWorkers.reduce((acc, w) => acc + w.allocatedMb, 0)} MB memory on your PC.`
                    : '100% of weights running on your computer. Plug in a phone via USB to distribute compute and speed up larger models.'}
                </p>
              </div>
            </div>

            {/* Non-Tech Quick Connect Helper */}
            <div className="rounded-xl border border-slate-200 dark:border-slate-800 bg-white dark:bg-[#111827] p-5 space-y-4">
              <div className="flex items-center justify-between">
                <div className="flex items-center gap-2">
                  <Zap className="w-4 h-4 text-amber-500" />
                  <h3 className="text-sm font-semibold text-slate-900 dark:text-slate-100">
                    Connect Any Device
                  </h3>
                </div>
                <button
                  type="button"
                  onClick={onRescanDevices}
                  className="text-xs text-emerald-600 dark:text-emerald-400 hover:underline font-medium"
                >
                  Rescan USB
                </button>
              </div>

              <div className="space-y-3 text-xs">
                <div className="p-3 rounded-lg border border-slate-100 dark:border-slate-800 bg-slate-50 dark:bg-[#0D1424] space-y-1">
                  <p className="font-semibold text-slate-900 dark:text-slate-100 flex items-center gap-1.5">
                    <Smartphone className="w-3.5 h-3.5 text-slate-500" />
                    Android Phone / Tablet (USB-C)
                  </p>
                  <p className="text-slate-500 dark:text-slate-400">
                    Plug in via USB-C cable with USB Debugging enabled. Silica Cluster auto-configures port forwarding in 2 seconds.
                  </p>
                </div>

                <div className="p-3 rounded-lg border border-slate-100 dark:border-slate-800 bg-slate-50 dark:bg-[#0D1424] space-y-1">
                  <p className="font-semibold text-slate-900 dark:text-slate-100 flex items-center gap-1.5">
                    <Laptop className="w-3.5 h-3.5 text-slate-500" />
                    Another Computer (Wi-Fi or LAN)
                  </p>
                  <p className="text-slate-500 dark:text-slate-400">
                    Open Silica Cluster on your other laptop on the same network or run the worker CLI. It joins automatically.
                  </p>
                </div>
              </div>

            </div>
          </div>
        </div>
    </div>
  );
};

