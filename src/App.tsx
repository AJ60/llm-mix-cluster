import { apiFetch, initApiTokenFromUrl } from './service/apiAuth';
import React, { useCallback, useEffect, useState } from 'react';
import {
  Activity,
  Boxes,
  MessageSquare,
  Moon,
  Network,
  PlugZap,
  Sun,
} from 'lucide-react';
import {
  BackendTuningConfig,
  EngineRuntimeStatus,
  GGUF_MODEL_PRESETS,
  GgufModelPreset,
  ClusterTuningAdvice,
  LiveHardwareProbe,
  RuntimeClusterSnapshot,
  StarClusterPlan,
} from './types/starCluster';
import { createDefaultBackendTuningConfig } from './domain/backendTuner';
import {
  ChatTurn,
  SseStreamStatus,
  clearChatStateCache,
  createDefaultSseStreamStatus,
  loadChatStateCache,
  saveChatStateCache,
} from './domain/chatStateCache';
import { StarClusterDashboard } from './components/StarClusterDashboard';
import { ModelsHubPage } from './components/ModelsHubPage';
import { ChatStatePersister, STABLE_CHAT_PERSISTER_KEY } from './components/ChatStatePersister';
import { ClusterVisualizerTab } from './components/ClusterVisualizerTab';
import { ApiHarnessConnectPage } from './components/ApiHarnessConnectPage';
import { ModelLoadConfigDrawer } from './components/ModelLoadConfigDrawer';
import { JoinWorkerPage } from './components/JoinWorkerPage';

type NavTab = 'chat' | 'visualizer' | 'cluster' | 'models' | 'connect';
const EMPTY_PLAN: StarClusterPlan = {
  tierCode: 'C',
  tierLabel: 'Waiting for coordinator',
  masterId: '',
  fitVerdict: 'Streaming',
  totalSafePoolMb: 0,
  totalVramPoolMb: 0,
  neededMb: 0,
  kvCacheTotalMb: 0,
  coveragePct: 0,
  overallThermalState: 'COOL',
  estimatedClusterTokPerSec: 0,
  activeParamsPerTokenB: 0,
  totalParamsB: 0,
  nodes: [],
  tensorSplit: [],
  tensorSplitLabel: '',
  tensorSplitCsv: '',
  useNoMmap: false,
  mmapStreaming: true,
  autoNkvo: false,
  cliArgs: [],
};


const THEME_STORAGE_KEY = 'silica.theme.v1';

function readInitialDarkTheme(): boolean {
  if (typeof window === 'undefined') return true;
  try {
    const saved = window.localStorage.getItem(THEME_STORAGE_KEY);
    if (saved === 'light') return false;
    if (saved === 'dark') return true;
  } catch {
    // ignore
  }
  return true;
}

initApiTokenFromUrl();

export function App() {
  const [pathname, setPathname] = useState<string>(() =>
    typeof window !== 'undefined' ? window.location.pathname : '/'
  );
  const [activeTab, setActiveTab] = useState<NavTab>('chat');
  const [isDarkTheme, setIsDarkTheme] = useState<boolean>(readInitialDarkTheme);
  const [loadConfigOpen, setLoadConfigOpen] = useState<boolean>(false);

  useEffect(() => {
    const onPopState = () => {
      setPathname(window.location.pathname);
    };
    window.addEventListener('popstate', onPopState);
    return () => window.removeEventListener('popstate', onPopState);
  }, []);

  const [activeModel, setActiveModel] = useState<GgufModelPreset>(GGUF_MODEL_PRESETS[0]);
  const [availableModels, setAvailableModels] = useState<GgufModelPreset[]>(GGUF_MODEL_PRESETS);
  const [tuningConfig, setTuningConfig] = useState<BackendTuningConfig>(() =>
    createDefaultBackendTuningConfig(GGUF_MODEL_PRESETS[0])
  );
  const [liveHardware, setLiveHardware] = useState<LiveHardwareProbe | undefined>(undefined);
  const [engineStatus, setEngineStatus] = useState<EngineRuntimeStatus>({
    running: false,
    draining: false,
    activeTurns: 0,
    upstreamUrl: null,
    serverType: 'offline',
    hasMmproj: Boolean(GGUF_MODEL_PRESETS[0].mmprojPath),
    mmprojPath: GGUF_MODEL_PRESETS[0].mmprojPath ?? null,
    lastEvent: 'Engine offline — load a GGUF model or connect a local server',
    reloadProgressPct: 0,
  });
  const [upstreamUrl, setUpstreamUrl] = useState<string>('');
  const [serverPlan, setServerPlan] = useState<StarClusterPlan | null>(null);
  const [tuningAdvice, setTuningAdvice] = useState<ClusterTuningAdvice | null>(null);

  // State-caching layer persisting active chat turns & SSE stream status to localStorage
  const [chatMessages, setChatMessages] = useState<ChatTurn[]>(() => loadChatStateCache().messages);
  const [chatStreamStatus, setChatStreamStatus] = useState<SseStreamStatus>(
    () => loadChatStateCache().streamStatus
  );

  useEffect(() => {
    saveChatStateCache({ messages: chatMessages, streamStatus: chatStreamStatus });
  }, [chatMessages, chatStreamStatus]);

  const handleClearChatCache = useCallback(() => {
    setChatMessages([]);
    setChatStreamStatus(createDefaultSseStreamStatus(activeModel.name));
    clearChatStateCache();
  }, [activeModel.name]);

  const handleOpenLoadConfig = useCallback(() => {
    setLoadConfigOpen(true);
  }, []);

  useEffect(() => {
    const root = document.documentElement;
    root.classList.toggle('dark', isDarkTheme);
    try {
      window.localStorage.setItem(THEME_STORAGE_KEY, isDarkTheme ? 'dark' : 'light');
    } catch {
      // ignore
    }
  }, [isDarkTheme]);

  const applyServerSnapshot = useCallback((snap: RuntimeClusterSnapshot) => {
    if (snap.activeModel) setActiveModel(snap.activeModel);
    if (snap.tuningConfig) setTuningConfig(snap.tuningConfig);
    if (snap.engineStatus) setEngineStatus(snap.engineStatus);
    if (typeof snap.upstreamBaseUrl === 'string') setUpstreamUrl(snap.upstreamBaseUrl);
    if (snap.plan) setServerPlan(snap.plan as StarClusterPlan);
    if (snap.tuningAdvice) setTuningAdvice(snap.tuningAdvice);
  }, []);

  const refreshHardware = useCallback(async () => {
    try {
      const res = await apiFetch('/api/cluster/hardware');
      if (res.ok) {
        const hw = (await res.json()) as LiveHardwareProbe;
        setLiveHardware(hw);
      }
    } catch {
      // ignore transient network errors
    }
  }, []);

  const refreshState = useCallback(async () => {
    try {
      const [stateRes, hwRes, modelsRes] = await Promise.all([
        apiFetch('/api/cluster/state'),
        apiFetch('/api/cluster/hardware'),
        apiFetch('/api/models').catch(() => null),
      ]);
      if (stateRes.ok) {
        const snap = (await stateRes.json()) as RuntimeClusterSnapshot;
        applyServerSnapshot(snap);
      }
      if (hwRes.ok) {
        const hw = (await hwRes.json()) as LiveHardwareProbe;
        setLiveHardware(hw);
      }
      if (modelsRes && modelsRes.ok) {
        const mData = (await modelsRes.json()) as { models?: GgufModelPreset[] };
        if (mData.models && mData.models.length > 0) {
          setAvailableModels(mData.models);
        }
      }
    } catch {
      // ignore initial offline probe error
    }
  }, [applyServerSnapshot]);

  useEffect(() => {
    refreshState();
  }, [refreshState]);

  useEffect(() => {
    const timer = window.setInterval(() => {
      refreshState();
    }, 6000);
    const onFocusOrVisible = () => {
      if (document.visibilityState === 'visible') {
        refreshState();
      }
    };
    window.addEventListener('focus', onFocusOrVisible);
    document.addEventListener('visibilitychange', onFocusOrVisible);
    return () => {
      window.clearInterval(timer);
      window.removeEventListener('focus', onFocusOrVisible);
      document.removeEventListener('visibilitychange', onFocusOrVisible);
    };
  }, [refreshState]);

  const plan = serverPlan ?? EMPTY_PLAN;

  const handleApplyAndReload = useCallback(
    async (spawnLocalBinary: boolean): Promise<{ ok: boolean; message: string }> => {
      try {
        const res = await apiFetch('/api/engine/reload', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({
            model: activeModel,
            tuningConfig,
            spawnLocalBinary,
            binaryPath: tuningConfig.binaryPath?.trim() || undefined,
          }),
        });
        const data = (await res.json()) as {
          ok?: boolean;
          error?: string;
          message?: string;
          snapshot?: RuntimeClusterSnapshot;
        };
        if (data.snapshot) {
          applyServerSnapshot(data.snapshot);
        }
        if (!res.ok || data.ok === false) {
          return { ok: false, message: data.error || 'Model reload failed.' };
        }
        return {
          ok: true,
          message: data.message || 'Model configuration applied and validated.',
        };
      } catch (err) {
        return {
          ok: false,
          message: err instanceof Error ? err.message : 'Unable to reach coordinator.',
        };
      }
    },
    [activeModel, tuningConfig, applyServerSnapshot]
  );

  const handleStopEngine = useCallback(async (): Promise<{ ok: boolean; message: string }> => {
    try {
      const res = await apiFetch('/api/engine/stop', { method: 'POST' });
      const data = (await res.json()) as {
        ok?: boolean;
        message?: string;
        snapshot?: RuntimeClusterSnapshot;
      };
      if (data.snapshot) {
        applyServerSnapshot(data.snapshot);
      }
      return { ok: true, message: data.message || 'Engine stopped cleanly.' };
    } catch (err) {
      return {
        ok: false,
        message: err instanceof Error ? err.message : 'Unable to reach coordinator to stop engine.',
      };
    }
  }, [applyServerSnapshot]);

  const handleSelectModel = useCallback(
    async (model: GgufModelPreset) => {
      setActiveModel(model);
      const nextCfg: BackendTuningConfig = {
        ...createDefaultBackendTuningConfig(model),
        mmprojPath: model.mmprojPath ?? '',
      };
      setTuningConfig(nextCfg);
      try {
        const res = await apiFetch('/api/models/activate', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ model, reloadIfRunning: engineStatus?.running }),
        });
        if (res.ok) {
          const data = (await res.json()) as { snapshot?: RuntimeClusterSnapshot };
          if (data.snapshot) applyServerSnapshot(data.snapshot);
        }
        if (engineStatus?.running) {
          await handleApplyAndReload(true);
        }
      } catch {
        // keep optimistic local state
      }
    },
    [applyServerSnapshot, engineStatus?.running, handleApplyAndReload]
  );

  const handleUpdateConfig = useCallback((patch: Partial<BackendTuningConfig>) => {
    setTuningConfig((prev) => ({ ...prev, ...patch }));
  }, []);

  const handleAutoTune = useCallback(
    async (patch: Partial<BackendTuningConfig>): Promise<{ ok: boolean; message: string }> => {
      try {
        const res = await apiFetch('/api/cluster/tuning/auto-apply', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ ...patch, reloadIfRunning: engineStatus?.running }),
        });
        if (res.ok) {
          const data = (await res.json()) as { snapshot?: RuntimeClusterSnapshot; reloaded?: boolean };
          if (data.snapshot) applyServerSnapshot(data.snapshot);
          return {
            ok: true,
            message: data.reloaded
              ? 'Auto-Tune applied and engine reloaded with optimized flags.'
              : 'Auto-Tune applied to Master & worker budgets.',
          };
        }
      } catch {
        // fall through to optimistic local state
      }
      return { ok: true, message: 'Auto-Tune parameters updated locally.' };
    },
    [tuningConfig, engineStatus?.running, applyServerSnapshot]
  );

  const handleResetDefaults = useCallback(() => {
    setTuningConfig({
      ...createDefaultBackendTuningConfig(activeModel),
      mmprojPath: activeModel.mmprojPath ?? '',
    });
  }, [activeModel]);

  const handleUpstreamConnected = useCallback(
    async (url: string) => {
      setUpstreamUrl(url);
      await refreshState();
    },
    [refreshState]
  );

  const navItems: Array<{ id: NavTab; label: string; icon: React.ReactNode }> = [
    { id: 'chat', label: 'Chat', icon: <MessageSquare className="w-4 h-4" /> },
    { id: 'visualizer', label: 'Cluster Visualizer', icon: <Network className="w-4 h-4" /> },
    { id: 'cluster', label: 'Nodes & Lab', icon: <Activity className="w-4 h-4" /> },
    { id: 'models', label: 'Models', icon: <Boxes className="w-4 h-4" /> },
    { id: 'connect', label: 'Connect', icon: <PlugZap className="w-4 h-4" /> },
  ];

  const liveWorkerCount = plan.nodes.filter(
    (node) => node.role === 'Worker' && node.isConnected && (node.allocatedLayers ?? 0) > 0
  ).length;

  if (pathname === '/join' || pathname.startsWith('/join/')) {
    return (
      <JoinWorkerPage
        onBackToDashboard={() => {
          window.history.pushState({}, '', '/');
          setPathname('/');
          refreshHardware();
        }}
      />
    );
  }

  return (
    <div className="min-h-screen bg-[#F8FAFC] dark:bg-[#0B0F17] text-slate-900 dark:text-slate-100 transition-colors flex flex-col">
      {/* Strict 3-Zone Header Bar (responsive 2-row on <lg, single-row 3-zone on lg+) */}
      <header className="sticky top-0 z-30 border-b border-slate-200 dark:border-slate-800/90 bg-white/90 dark:bg-[#0B0F17]/90 backdrop-blur-md">
        <div className="max-w-7xl mx-auto px-4 sm:px-6 py-2 lg:py-0 lg:h-14 flex flex-wrap lg:flex-nowrap items-center justify-between gap-2 lg:gap-4">
          {/* Zone 1: Brand */}
          <div className="order-1 flex items-center gap-2.5 shrink-0 min-w-0">
            <div className="w-8 h-8 rounded-lg bg-emerald-600 dark:bg-emerald-500 flex items-center justify-center text-white font-semibold text-sm shadow-sm">
              SC
            </div>
            <div className="leading-tight">
              <div className="text-sm font-semibold tracking-tight text-slate-900 dark:text-slate-100 whitespace-nowrap">
                Silica Cluster
              </div>
              <div className="text-[11px] text-slate-500 dark:text-slate-400 font-mono-num whitespace-nowrap">
                {engineStatus.running ? 'Engine Online' : 'Engine Offline'} · {liveWorkerCount}{' '}
                {liveWorkerCount === 1 ? 'worker' : 'workers'}
              </div>
            </div>
          </div>

          {/* Zone 2: Primary Navigation */}
          <nav
            aria-label="Primary navigation"
            className="order-3 lg:order-2 w-full lg:w-auto flex items-center gap-1 bg-slate-100 dark:bg-[#111827] p-1 rounded-lg border border-slate-200 dark:border-slate-800 overflow-x-auto"
          >
            {navItems.map((item) => {
              const active = activeTab === item.id;
              const isChatStreamingInBg =
                item.id === 'chat' &&
                !active &&
                (chatStreamStatus.phase === 'streaming' || chatStreamStatus.phase === 'connecting');
              return (
                <button
                  key={item.id}
                  type="button"
                  onClick={() => setActiveTab(item.id)}
                  aria-current={active ? 'page' : undefined}
                  className={`inline-flex items-center gap-1.5 px-3 py-1.5 rounded-md text-xs font-medium whitespace-nowrap shrink-0 transition-colors ${
                    active
                      ? 'bg-white dark:bg-[#1E293B] text-slate-950 dark:text-white shadow-xs'
                      : 'text-slate-600 dark:text-slate-400 hover:text-slate-900 dark:hover:text-slate-200'
                  }`}
                >
                  {item.icon}
                  <span>{item.label}</span>
                  {isChatStreamingInBg && (
                    <span
                      aria-label="Active stream running"
                      className="w-1.5 h-1.5 rounded-full bg-emerald-400 animate-pulse"
                    />
                  )}
                </button>
              );
            })}
          </nav>

          {/* Zone 3: Compact Utilities */}
          <div className="order-2 lg:order-3 flex items-center gap-1.5 shrink-0">
            <button
              type="button"
              onClick={() => setIsDarkTheme((v) => !v)}
              aria-label={isDarkTheme ? 'Switch to light mode' : 'Switch to dark mode'}
              title={isDarkTheme ? 'Switch to light mode' : 'Switch to dark mode'}
              className="p-2 rounded-lg border border-slate-200 dark:border-slate-800 bg-white dark:bg-[#111827] text-slate-700 dark:text-slate-300 hover:bg-slate-50 dark:hover:bg-slate-800 transition-colors shrink-0"
            >
              {isDarkTheme ? <Sun className="w-4 h-4 text-amber-400" /> : <Moon className="w-4 h-4 text-slate-600" />}
            </button>
          </div>
        </div>
      </header>

      {/* Main Workspace Canvas */}
      <main className="flex-1 max-w-7xl w-full mx-auto px-4 sm:px-6 py-6">
        {/* Keep ChatTab mounted across tab switches inside a memoized container with a stable key */}
        <ChatStatePersister
          key={STABLE_CHAT_PERSISTER_KEY}
          visible={activeTab === 'chat'}
          activeModel={activeModel}
          plan={plan}
          engineStatus={engineStatus}
          upstreamUrl={upstreamUrl}
          onUpstreamConnected={handleUpstreamConnected}
          onEngineLiveDetected={refreshState}
          onOpenLoadConfig={handleOpenLoadConfig}
          onStopEngine={handleStopEngine}
          messages={chatMessages}
          onMessagesChange={setChatMessages}
          streamStatus={chatStreamStatus}
          onStreamStatusChange={setChatStreamStatus}
          onClearChatCache={handleClearChatCache}
        />

        {activeTab === 'visualizer' && (
          <ClusterVisualizerTab
            plan={plan}
            activeModel={activeModel}
            onRefresh={refreshHardware}
            onAutoBalance={() => handleApplyAndReload(true)}
            onStopAndFree={handleStopEngine}
          />
        )}

        {activeTab === 'cluster' && (
          <StarClusterDashboard
            activeModel={activeModel}
            availableModels={availableModels}
            onSelectModel={handleSelectModel}
            tuningAdvice={tuningAdvice}
            plan={plan}
            liveHardware={liveHardware}
            engineStatus={engineStatus}
            onRescanDevices={refreshHardware}
            onOpenModels={() => setActiveTab('models')}
            onOpenLoadConfig={() => setLoadConfigOpen(true)}
            onOpenChat={() => setActiveTab('chat')}
            onAutoTune={handleAutoTune}
            onApplyAndReload={handleApplyAndReload}
            onStopEngine={handleStopEngine}
          />
        )}

        {activeTab === 'models' && (
          <ModelsHubPage
            activeModel={activeModel}
            plan={plan}
            onActivateModel={handleSelectModel}
            onOpenLoadConfig={() => setLoadConfigOpen(true)}
          />
        )}

        {activeTab === 'connect' && (
          <ApiHarnessConnectPage
            activeModel={activeModel}
            liveHardware={liveHardware}
            engineStatus={engineStatus}
          />
        )}
      </main>

      {/* LM Studio / Unsloth Studio-Style Model Load Config & Hot-Reload Drawer */}
      <ModelLoadConfigDrawer
        open={loadConfigOpen}
        model={activeModel}
        config={tuningConfig}
        plan={plan}
        tuningAdvice={tuningAdvice}
        onUpdateConfig={handleUpdateConfig}
        onApplyAndReload={handleApplyAndReload}
        onResetDefaults={handleResetDefaults}
        onClose={() => setLoadConfigOpen(false)}
      />
    </div>
  );
}

export default App;
