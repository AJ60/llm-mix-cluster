import { apiFetch } from '../service/apiAuth';
import React, { useEffect, useState } from 'react';
import {
  CheckCircle2,
  Eye,
  FolderOpen,
  HardDrive,
  Search,
  SlidersHorizontal,
  Upload,
} from 'lucide-react';
import {
  GgufModelPreset,
  HfCatalogModel,
  StarClusterPlan,
} from '../types/starCluster';
import { catalogModelToPreset, CURATED_HF_GGUF_CATALOG } from '../data/hfModelsCatalog';
import { parseLocalGgufModel } from '../data/localModelsScanner';

interface ModelsHubPageProps {
  activeModel: GgufModelPreset;
  plan: StarClusterPlan;
  onActivateModel: (model: GgufModelPreset) => Promise<void> | void;
  onOpenLoadConfig: () => void;
}

const UNIVERSAL_SCAN_PRESETS = [
  { label: './models', path: './models' },
  { label: 'Hermes Models', path: '~/AppData/Local/hermes/models' },
  { label: 'Downloads', path: '~/Downloads' },
  { label: 'LM Studio Default', path: '~/.cache/lm-studio/models' },
  { label: 'Ollama Models', path: '~/.ollama/models' },
];

export const ModelsHubPage: React.FC<ModelsHubPageProps> = ({
  activeModel,
  plan,
  onActivateModel,
  onOpenLoadConfig,
}) => {
  const [sourceTab, setSourceTab] = useState<'local' | 'huggingface'>('local');
  const [scanDir, setScanDir] = useState('./models');
  const [localModels, setLocalModels] = useState<GgufModelPreset[]>([]);
  const [scanning, setScanning] = useState(false);
  const [manualFilePath, setManualFilePath] = useState('');
  const [statusMsg, setStatusMsg] = useState<{ ok: boolean; text: string } | null>(null);

  const [hfQuery, setHfQuery] = useState('');
  const [hfModels, setHfModels] = useState<HfCatalogModel[]>(CURATED_HF_GGUF_CATALOG);
  const [searchingHf, setSearchingHf] = useState(false);

  const scanLocalDirectory = async (dir = scanDir) => {
    setScanning(true);
    setStatusMsg(null);
    try {
      const res = await apiFetch(`/api/local/models?dir=${encodeURIComponent(dir)}`);
      if (res.ok) {
        const data = (await res.json()) as { directory: string; models: GgufModelPreset[] };
        setLocalModels(data.models);
        setStatusMsg({
          ok: true,
          text:
            data.models.length > 0
              ? `Found ${data.models.length} GGUF model(s) in ${data.directory}`
              : `No .gguf files found in ${data.directory}. Pick a file directly or enter a full path below.`,
        });
      }
    } catch {
      setStatusMsg({ ok: false, text: 'Unable to scan local directory.' });
    } finally {
      setScanning(false);
    }
  };

  useEffect(() => {
    scanLocalDirectory('./models');
  }, []);

  const handleInspectManualPath = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!manualFilePath.trim()) return;
    setStatusMsg(null);
    try {
      const res = await apiFetch('/api/models/inspect', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ filePath: manualFilePath.trim() }),
      });
      const data = (await res.json()) as { ok?: boolean; model?: GgufModelPreset; error?: string };
      if (!res.ok || !data.model) {
        setStatusMsg({ ok: false, text: data.error || 'Could not inspect GGUF file' });
        return;
      }
      setLocalModels((prev) => [data.model!, ...prev.filter((m) => m.id !== data.model!.id)]);
      await onActivateModel(data.model);
      setStatusMsg({
        ok: true,
        text: `Parsed ${data.model.name} (${data.model.architecture} · ${data.model.layers}L${data.model.mmprojPath ? ' · Companion mmproj paired' : ''})`,
      });
      setManualFilePath('');
    } catch {
      setStatusMsg({ ok: false, text: 'Failed to contact server for GGUF inspection.' });
    }
  };

  const handleBrowserFilePick = async (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (!file) return;
    try {
      const slice = file.slice(0, Math.min(file.size, 131072));
      const buf = new Uint8Array(await slice.arrayBuffer());
      const parsed = parseLocalGgufModel(file.name, file.size, buf, null);
      setLocalModels((prev) => [parsed, ...prev.filter((m) => m.id !== parsed.id)]);
      await onActivateModel(parsed);
      setStatusMsg({
        ok: true,
        text: `Inspected binary GGUF header for ${parsed.name} (${parsed.architecture} · ${parsed.layers}L · vocab ${parsed.vocabHash?.slice(0, 8) || 'ok'})`,
      });
    } catch {
      setStatusMsg({ ok: false, text: 'Could not read binary GGUF header from selected file.' });
    }
  };

  const handleSearchHf = async (e: React.FormEvent) => {
    e.preventDefault();
    setSearchingHf(true);
    try {
      const res = await apiFetch(`/api/hf/models?q=${encodeURIComponent(hfQuery.trim())}`);
      if (res.ok) {
        const data = (await res.json()) as { models: HfCatalogModel[] };
        setHfModels(data.models);
      }
    } finally {
      setSearchingHf(false);
    }
  };

  const poolGb = (plan.totalSafePoolMb / 1024).toFixed(1);

  return (
    <div className="space-y-5">
      {/* Header & Source Switcher */}
      <div className="rounded-xl border border-slate-200 dark:border-slate-800 bg-white dark:bg-[#111827] p-5 flex flex-col md:flex-row md:items-center justify-between gap-4">
        <div>
          <h1 className="text-lg font-semibold text-slate-900 dark:text-slate-100">
            Models & GGUF Inspector
          </h1>
          <p className="text-xs text-slate-500 dark:text-slate-400 mt-0.5">
            Point to any <code className="font-mono-num">.gguf</code> to parse its binary header, auto-pair{' '}
            <code className="font-mono-num">*mmproj*</code> on Master, and compute zero-OOM layer sharding for your{' '}
            <span className="font-mono-num text-slate-800 dark:text-slate-200">{poolGb} GB</span> safe pool.
          </p>
        </div>

        <div className="flex items-center gap-2 shrink-0">
          <div className="inline-flex rounded-lg border border-slate-200 dark:border-slate-700 bg-slate-50 dark:bg-[#0D1424] p-0.5 text-xs">
            <button
              type="button"
              onClick={() => setSourceTab('local')}
              className={`px-3 py-1.5 rounded-md font-medium transition-colors ${
                sourceTab === 'local'
                  ? 'bg-white dark:bg-[#1A2438] text-slate-900 dark:text-slate-100 shadow-xs'
                  : 'text-slate-600 dark:text-slate-400'
              }`}
            >
              Local GGUF
            </button>
            <button
              type="button"
              onClick={() => setSourceTab('huggingface')}
              className={`px-3 py-1.5 rounded-md font-medium transition-colors ${
                sourceTab === 'huggingface'
                  ? 'bg-white dark:bg-[#1A2438] text-slate-900 dark:text-slate-100 shadow-xs'
                  : 'text-slate-600 dark:text-slate-400'
              }`}
            >
              Hugging Face
            </button>
          </div>

          <button
            type="button"
            onClick={onOpenLoadConfig}
            className="inline-flex items-center gap-1.5 px-3 py-2 rounded-lg bg-emerald-600 hover:bg-emerald-500 text-white text-xs font-medium transition-colors"
          >
            <SlidersHorizontal className="w-3.5 h-3.5" />
            Load Config
          </button>
        </div>
      </div>

      {/* Active Model Card Summary */}
      <div className="rounded-xl border border-emerald-500/40 bg-emerald-500/[0.05] dark:bg-emerald-500/[0.07] p-4 flex flex-col sm:flex-row sm:items-center justify-between gap-3">
        <div className="space-y-1">
          <p className="text-xs font-medium text-emerald-700 dark:text-emerald-400">
            Active Model
          </p>
          <p className="text-sm font-semibold text-slate-900 dark:text-slate-100">
            {activeModel.name} · {activeModel.quant} · {activeModel.architecture}
          </p>
          <p className="text-xs font-mono-num text-slate-600 dark:text-slate-300">
            {(activeModel.sizeMb / 1024).toFixed(2)} GB · {activeModel.layers} layers ·{' '}
            {activeModel.activeParamsB}B active / {activeModel.totalParamsB}B total
            {activeModel.vocabHash ? ` · vocab ${activeModel.vocabHash.slice(0, 10)}` : ''}
            {activeModel.mmprojPath ? ' · Vision mmproj paired' : ''}
          </p>
        </div>
        <button
          type="button"
          onClick={onOpenLoadConfig}
          className="px-3.5 py-2 rounded-lg border border-emerald-600/40 bg-white dark:bg-[#111827] text-xs font-medium text-emerald-700 dark:text-emerald-400 hover:bg-emerald-50 dark:hover:bg-emerald-950/40 shrink-0 whitespace-nowrap"
        >
          Tune & Hot-Reload
        </button>
      </div>

      {statusMsg && (
        <div
          className={`rounded-lg border px-4 py-2.5 text-xs ${
            statusMsg.ok
              ? 'border-emerald-500/40 bg-emerald-500/10 text-emerald-800 dark:text-emerald-300'
              : 'border-rose-500/40 bg-rose-500/10 text-rose-800 dark:text-rose-300'
          }`}
        >
          {statusMsg.text}
        </div>
      )}

      {sourceTab === 'local' ? (
        <div className="space-y-4">
          {/* Universal OS-agnostic Directory Scan + Direct File Inspect */}
          <div className="rounded-xl border border-slate-200 dark:border-slate-800 bg-white dark:bg-[#111827] p-5 space-y-4">
            <div className="flex flex-wrap items-center gap-2 text-xs">
              <span className="text-slate-500 dark:text-slate-400 font-medium">Quick folders:</span>
              {UNIVERSAL_SCAN_PRESETS.map((preset) => (
                <button
                  key={preset.label}
                  type="button"
                  onClick={() => {
                    setScanDir(preset.path);
                    scanLocalDirectory(preset.path);
                  }}
                  className="px-2.5 py-1 rounded-md border border-slate-200 dark:border-slate-700 text-slate-700 dark:text-slate-300 hover:bg-slate-50 dark:hover:bg-slate-800 font-mono-num"
                >
                  {preset.label}
                </button>
              ))}
            </div>

            <div className="grid grid-cols-1 lg:grid-cols-2 gap-3">
              <div className="flex gap-2">
                <input
                  type="text"
                  aria-label="Local GGUF folder path"
                  value={scanDir}
                  onChange={(e) => setScanDir(e.target.value)}
                  placeholder="./models"
                  className="flex-1 rounded-lg border border-slate-200 dark:border-slate-700 bg-slate-50 dark:bg-[#0D1424] px-3 py-2 text-xs font-mono-num text-slate-900 dark:text-slate-100"
                />
                <button
                  type="button"
                  disabled={scanning}
                  onClick={() => scanLocalDirectory(scanDir)}
                  className="inline-flex items-center gap-1.5 px-3.5 py-2 rounded-lg border border-slate-200 dark:border-slate-700 text-xs font-medium text-slate-800 dark:text-slate-200 hover:bg-slate-50 dark:hover:bg-slate-800"
                >
                  <FolderOpen className="w-3.5 h-3.5" />
                  {scanning ? 'Scanning...' : 'Scan Folder'}
                </button>
              </div>

              <form onSubmit={handleInspectManualPath} className="flex gap-2">
                <input
                  type="text"
                  aria-label="Full path to local .gguf file"
                  value={manualFilePath}
                  onChange={(e) => setManualFilePath(e.target.value)}
                  placeholder="/path/to/model.gguf (auto-pairs *mmproj*)"
                  className="flex-1 rounded-lg border border-slate-200 dark:border-slate-700 bg-slate-50 dark:bg-[#0D1424] px-3 py-2 text-xs font-mono-num text-slate-900 dark:text-slate-100"
                />
                <button
                  type="submit"
                  className="px-3.5 py-2 rounded-lg bg-slate-900 dark:bg-slate-100 text-white dark:text-slate-900 text-xs font-medium whitespace-nowrap"
                >
                  Inspect GGUF
                </button>
              </form>
            </div>

            <div className="pt-2 border-t border-slate-100 dark:border-slate-800 flex items-center justify-between flex-wrap gap-2">
              <span className="text-xs text-slate-500 dark:text-slate-400">
                Or pick a local <code className="font-mono-num">.gguf</code> file directly in your browser to inspect its binary header immediately:
              </span>
              <label className="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-lg border border-slate-200 dark:border-slate-700 text-xs font-medium text-slate-700 dark:text-slate-200 hover:bg-slate-50 dark:hover:bg-slate-800 cursor-pointer">
                <Upload className="w-3.5 h-3.5" />
                Pick .gguf Header
                <input
                  type="file"
                  accept=".gguf"
                  onChange={handleBrowserFilePick}
                  className="hidden"
                />
              </label>
            </div>
          </div>

          {/* Discovered Local Models Grid */}
          {localModels.length > 0 ? (
            <div className="grid grid-cols-1 md:grid-cols-2 xl:grid-cols-3 gap-3">
              {localModels.map((model) => {
                const isActive = activeModel.id === model.id;
                return (
                  <div
                    key={model.id}
                    className={`rounded-xl border p-4 bg-white dark:bg-[#111827] flex flex-col justify-between gap-3 ${
                      isActive
                        ? 'border-emerald-500/70'
                        : 'border-slate-200 dark:border-slate-800'
                    }`}
                  >
                    <div>
                      <div className="flex items-center justify-between gap-2 text-xs text-slate-500 dark:text-slate-400">
                        <span className="inline-flex items-center gap-1.5 font-mono-num">
                          <HardDrive className="w-3.5 h-3.5" />
                          {model.quant} · {model.architecture}
                        </span>
                        {model.mmprojPath && (
                          <span className="inline-flex items-center gap-1 text-emerald-700 dark:text-emerald-400">
                            <Eye className="w-3.5 h-3.5" />
                            Vision
                          </span>
                        )}
                      </div>
                      <h3 className="text-sm font-semibold text-slate-900 dark:text-slate-100 mt-1.5">
                        {model.name}
                      </h3>
                      <p className="text-xs font-mono-num text-slate-500 dark:text-slate-400 mt-1">
                        {(model.sizeMb / 1024).toFixed(2)} GB · {model.layers} layers ·{' '}
                        {model.contextLengthDefault.toLocaleString()} ctx
                      </p>
                    </div>
                    <button
                      type="button"
                      onClick={() => onActivateModel(model)}
                      className={`w-full py-2 rounded-lg text-xs font-medium transition-colors ${
                        isActive
                          ? 'bg-emerald-500/15 text-emerald-700 dark:text-emerald-400'
                          : 'bg-slate-900 dark:bg-slate-100 text-white dark:text-slate-900 hover:opacity-90'
                      }`}
                    >
                      {isActive ? 'Active on Cluster' : 'Load on Cluster'}
                    </button>
                  </div>
                );
              })}
            </div>
          ) : (
            <div className="rounded-xl border border-dashed border-slate-300 dark:border-slate-700 p-8 text-center text-xs text-slate-500 dark:text-slate-400">
              No local GGUF models found in <code className="font-mono-num">{scanDir}</code>. Switch to{' '}
              <button
                type="button"
                onClick={() => setSourceTab('huggingface')}
                className="text-emerald-700 dark:text-emerald-400 font-medium underline"
              >
                Hugging Face
              </button>{' '}
              or pick a local <code className="font-mono-num">.gguf</code> file above.
            </div>
          )}
        </div>
      ) : (
        <div className="space-y-4">
          <form onSubmit={handleSearchHf} className="flex gap-2">
            <div className="relative flex-1">
              <Search className="w-4 h-4 text-slate-400 absolute left-3 top-1/2 -translate-y-1/2" />
              <input
                type="text"
                aria-label="Search Hugging Face GGUF models"
                value={hfQuery}
                onChange={(e) => setHfQuery(e.target.value)}
                placeholder="Search Hugging Face GGUF models (e.g. Qwen3, Llama-3.3, BitNet, Gemma)..."
                className="w-full rounded-lg border border-slate-200 dark:border-slate-700 bg-white dark:bg-[#111827] pl-9 pr-3 py-2 text-xs text-slate-900 dark:text-slate-100"
              />
            </div>
            <button
              type="submit"
              disabled={searchingHf}
              className="px-4 py-2 rounded-lg bg-emerald-600 hover:bg-emerald-500 text-white text-xs font-medium"
            >
              {searchingHf ? 'Searching...' : 'Search'}
            </button>
          </form>

          <div className="grid grid-cols-1 md:grid-cols-2 xl:grid-cols-3 gap-3">
            {hfModels.map((item) => {
              const isActive = activeModel.id === item.id;
              const fitsPool = item.sizeMb <= plan.totalSafePoolMb;
              return (
                <div
                  key={item.id}
                  className={`rounded-xl border p-4 bg-white dark:bg-[#111827] flex flex-col justify-between gap-3 ${
                    isActive
                      ? 'border-emerald-500/70'
                      : 'border-slate-200 dark:border-slate-800'
                  }`}
                >
                  <div>
                    <div className="flex items-center justify-between text-xs text-slate-500 dark:text-slate-400 font-mono-num">
                      <span>{item.architecture} · {item.defaultQuant}</span>
                      <span className={fitsPool ? 'text-emerald-700 dark:text-emerald-400' : 'text-amber-600 dark:text-amber-400'}>
                        {fitsPool ? 'Fits Pool' : 'mmap stream'}
                      </span>
                    </div>
                    <h3 className="text-sm font-semibold text-slate-900 dark:text-slate-100 mt-1">
                      {item.name}
                    </h3>
                    <p className="text-xs text-slate-500 dark:text-slate-400 mt-1 line-clamp-2">
                      {item.summary}
                    </p>
                    <p className="text-xs font-mono-num text-slate-600 dark:text-slate-300 mt-2">
                      {(item.sizeMb / 1024).toFixed(1)} GB · {item.layers}L · {item.activeParamsB}B active
                    </p>
                  </div>
                  <button
                    type="button"
                    onClick={() => onActivateModel(catalogModelToPreset(item))}
                    className={`w-full py-2 rounded-lg text-xs font-medium inline-flex items-center justify-center gap-1.5 transition-colors ${
                      isActive
                        ? 'bg-emerald-500/15 text-emerald-700 dark:text-emerald-400'
                        : 'bg-slate-900 dark:bg-slate-100 text-white dark:text-slate-900 hover:opacity-90'
                    }`}
                  >
                    {isActive && <CheckCircle2 className="w-3.5 h-3.5" />}
                    {isActive ? 'Active on Cluster' : 'Select & Auto-Configure'}
                  </button>
                </div>
              );
            })}
          </div>
        </div>
      )}
    </div>
  );
};
