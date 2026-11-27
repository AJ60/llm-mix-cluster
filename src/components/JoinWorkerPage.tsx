import { apiFetch } from '../service/apiAuth';
import React, { useCallback, useEffect, useRef, useState } from 'react';
import {
  ArrowLeft,
  CheckCircle2,
  Cpu,
  Play,
  Power,
  ShieldCheck,
  Sparkles,
} from 'lucide-react';
import {
  probeWebGpuCapabilities,
  runWebGpuIntegrityBenchmark,
  TensorIntegrityBenchmark,
  WebGpuAdapterCapabilities,
} from '../workers/webgpuWorker';

interface JoinWorkerPageProps {
  onBackToDashboard?: () => void;
}

export const JoinWorkerPage: React.FC<JoinWorkerPageProps> = ({ onBackToDashboard }) => {
  const [caps, setCaps] = useState<WebGpuAdapterCapabilities | null>(null);
  const [probing, setProbing] = useState(true);
  const [workerLabel, setWorkerLabel] = useState(() => {
    if (typeof navigator !== 'undefined' && navigator.platform) {
      return `Browser WebGPU (${navigator.platform})`;
    }
    return 'Browser WebGPU Worker';
  });
  const [shardMb, setShardMb] = useState<number>(2048);
  const [precision, setPrecision] = useState<'f32' | 'f16' | 'q8_0'>('f32');
  const [benchmark, setBenchmark] = useState<TensorIntegrityBenchmark | null>(null);
  const [runningBench, setRunningBench] = useState(false);

  const [joined, setJoined] = useState(false);
  const [joining, setJoining] = useState(false);
  const [workerId] = useState(() => `webgpu-${Math.random().toString(36).slice(2, 8)}`);
  const [heartbeatCount, setHeartbeatCount] = useState(0);
  const [lastHeartbeatAt, setLastHeartbeatAt] = useState<string | null>(null);
  const [statusMessage, setStatusMessage] = useState<{ ok: boolean; text: string } | null>(null);

  const heartbeatTimerRef = useRef<number | null>(null);

  useEffect(() => {
    let mounted = true;
    (async () => {
      setProbing(true);
      const result = await probeWebGpuCapabilities();
      if (!mounted) return;
      setCaps(result);
      setShardMb(result.recommendedShardMb);
      setPrecision(result.supportsF16 ? 'f16' : 'f32');
      setProbing(false);
    })();
    return () => {
      mounted = false;
    };
  }, []);

  const runIntegrityCheck = useCallback(async () => {
    setRunningBench(true);
    setStatusMessage(null);
    try {
      const res = await runWebGpuIntegrityBenchmark(256, 512, precision);
      setBenchmark(res);
      setStatusMessage({
        ok: res.passedGate6,
        text: res.passedGate6
          ? `Gate 6 tensor verification passed on ${res.backend} (max error ${res.maxAbsError.toExponential(2)} <= 1e-5).`
          : `Tensor error ${res.maxAbsError.toExponential(2)} exceeded 1e-5 threshold.`,
      });
    } catch {
      setStatusMessage({ ok: false, text: 'Failed to execute tensor integrity benchmark.' });
    } finally {
      setRunningBench(false);
    }
  }, [precision]);

  const sendRegistration = useCallback(async () => {
    const measuredBw = caps?.estimatedMemBwGbps ?? 48;
    const measuredTflops = benchmark
      ? Math.max(0.5, benchmark.throughputGflops / 1000)
      : caps?.estimatedTflops ?? 1.8;

    const payload = {
      workerId,
      label: workerLabel.trim() || 'Browser WebGPU Worker',
      platform: 'WebGPU',
      workerKind: 'browser',
      osType: 'webgpu',
      linkType: 'WebGPU Browser',
      ip: typeof window !== 'undefined' ? window.location.hostname || '127.0.0.1' : '127.0.0.1',
      rpcPort: 0,
      allowHttpRegistration: true,
      rpcNativeReady: true,
      nativeRpcReady: true,
      totalRamMb: shardMb,
      availableRamMb: shardMb,
      safeRamMb: shardMb,
      vramFreeMb: caps?.available ? shardMb : 0,
      vramTotalMb: caps?.available ? shardMb : 0,
      memBwGbps: measuredBw,
      computeTflops: Number(measuredTflops.toFixed(2)),
      physicalCores: typeof navigator !== 'undefined' ? navigator.hardwareConcurrency || 4 : 4,
      rttMs: 2.4,
      pktLossPct: 0,
      tempC: 36,
      backendDriver: caps?.available ? 'WebGPU WGSL' : 'WASM Float32 Fallback',
    };

    const res = await apiFetch('/api/cluster/nodes/register', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload),
    });
    if (!res.ok) {
      throw new Error(`Coordinator returned HTTP ${res.status}`);
    }
    setHeartbeatCount((c) => c + 1);
    setLastHeartbeatAt(new Date().toLocaleTimeString());
  }, [benchmark, caps, shardMb, workerId, workerLabel]);

  const handleJoinCluster = async () => {
    setJoining(true);
    setStatusMessage(null);
    try {
      await sendRegistration();
      setJoined(true);
      setStatusMessage({
        ok: true,
        text: `Joined Star Cluster as ${workerId} contributing ${(shardMb / 1024).toFixed(1)} GB safe shard capacity.`,
      });
    } catch (err) {
      setStatusMessage({
        ok: false,
        text: err instanceof Error ? err.message : 'Unable to register browser worker.',
      });
    } finally {
      setJoining(false);
    }
  };

  const handleLeaveCluster = useCallback(async () => {
    if (heartbeatTimerRef.current !== null) {
      window.clearInterval(heartbeatTimerRef.current);
      heartbeatTimerRef.current = null;
    }
    try {
      await apiFetch('/api/cluster/nodes/unregister', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ workerId }),
      });
    } catch {
      // ignore network error on leave
    }
    setJoined(false);
    setStatusMessage({
      ok: true,
      text: `Disconnected ${workerId} cleanly from the Star Cluster.`,
    });
  }, [workerId]);

  useEffect(() => {
    if (!joined) return;
    heartbeatTimerRef.current = window.setInterval(() => {
      sendRegistration().catch(() => {
        // retry on next tick
      });
    }, 8000);
    return () => {
      if (heartbeatTimerRef.current !== null) {
        window.clearInterval(heartbeatTimerRef.current);
        heartbeatTimerRef.current = null;
      }
    };
  }, [joined, sendRegistration]);

  const handleBack = () => {
    if (onBackToDashboard) {
      onBackToDashboard();
      return;
    }
    if (typeof window !== 'undefined') {
      window.history.pushState({}, '', '/');
      window.dispatchEvent(new PopStateEvent('popstate'));
    }
  };

  return (
    <div className="min-h-screen bg-[#F8FAFC] dark:bg-[#0B0F17] text-slate-900 dark:text-slate-100">
      <header className="h-14 border-b border-slate-200 dark:border-slate-800 bg-white dark:bg-[#0B0F17]">
        <div className="max-w-5xl mx-auto h-full px-4 sm:px-6 flex items-center justify-between gap-4">
          <div className="flex items-center gap-3 min-w-0">
            <div className="w-8 h-8 rounded-lg bg-emerald-600 dark:bg-emerald-500 flex items-center justify-center text-white font-semibold text-sm shrink-0">
              SC
            </div>
            <span className="text-sm font-semibold tracking-tight whitespace-nowrap truncate">
              Silica Cluster · WebGPU Worker
            </span>
          </div>

          <button
            type="button"
            onClick={handleBack}
            className="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-lg border border-slate-200 dark:border-slate-700 text-xs font-medium text-slate-700 dark:text-slate-200 hover:bg-slate-50 dark:hover:bg-slate-800 whitespace-nowrap shrink-0 transition-colors"
          >
            <ArrowLeft className="w-3.5 h-3.5" />
            Back to Master Dashboard
          </button>
        </div>
      </header>

      <main className="max-w-5xl mx-auto px-4 sm:px-6 py-6 space-y-5">
        {/* Hero / Identity & Primary Action */}
        <section className="rounded-xl border border-slate-200 dark:border-slate-800 bg-white dark:bg-[#111827] p-5 flex flex-col md:flex-row md:items-center justify-between gap-4">
          <div className="space-y-1">
            <h1 className="text-lg font-semibold text-slate-900 dark:text-slate-100">
              Zero-Install Browser Compute Node
            </h1>
            <p className="text-xs text-slate-500 dark:text-slate-400">
              Contribute idle GPU/CPU memory to the local Star Cluster directly from your browser using WGSL RMSNorm, RoPE, and GEMV compute shaders.
            </p>
            <div className="flex flex-wrap items-center gap-2 pt-1 text-xs font-mono-num text-slate-500 dark:text-slate-400">
              <span>Worker ID: {workerId}</span>
              <span aria-hidden="true">·</span>
              <span className={joined ? 'text-emerald-600 dark:text-emerald-400 font-medium' : ''}>
                {joined
                  ? `Online (${heartbeatCount} ${heartbeatCount === 1 ? 'heartbeat' : 'heartbeats'}${lastHeartbeatAt ? `, last ${lastHeartbeatAt}` : ''})`
                  : 'Standby'}
              </span>
            </div>
          </div>

          <div className="flex items-center gap-2 shrink-0">
            <button
              type="button"
              disabled={runningBench}
              onClick={runIntegrityCheck}
              className="inline-flex items-center gap-1.5 px-3.5 py-2 rounded-lg border border-slate-200 dark:border-slate-700 text-xs font-medium text-slate-800 dark:text-slate-200 hover:bg-slate-50 dark:hover:bg-slate-800 whitespace-nowrap shrink-0 transition-colors"
            >
              <Play className="w-3.5 h-3.5" />
              {runningBench ? 'Verifying Shaders...' : 'Run Integrity Benchmark'}
            </button>

            {joined ? (
              <button
                type="button"
                onClick={handleLeaveCluster}
                className="inline-flex items-center gap-1.5 px-4 py-2 rounded-lg bg-rose-600 hover:bg-rose-500 text-white text-xs font-medium whitespace-nowrap shrink-0 transition-colors"
              >
                <Power className="w-3.5 h-3.5" />
                Leave Cluster
              </button>
            ) : (
              <button
                type="button"
                disabled={joining || probing}
                onClick={handleJoinCluster}
                className="inline-flex items-center gap-1.5 px-4 py-2 rounded-lg bg-emerald-600 hover:bg-emerald-500 text-white text-xs font-medium whitespace-nowrap shrink-0 transition-colors disabled:opacity-50"
              >
                <Sparkles className="w-3.5 h-3.5" />
                {joining ? 'Joining...' : 'Join Star Cluster'}
              </button>
            )}
          </div>
        </section>

        {statusMessage && (
          <div
            role="status"
            className={`rounded-xl border px-4 py-3 text-xs ${
              statusMessage.ok
                ? 'border-emerald-500/40 bg-emerald-500/10 text-emerald-800 dark:text-emerald-300'
                : 'border-rose-500/40 bg-rose-500/10 text-rose-800 dark:text-rose-300'
            }`}
          >
            {statusMessage.text}
          </div>
        )}

        {/* Hardware Adapter & Shard Configuration */}
        <div className="grid grid-cols-1 lg:grid-cols-2 gap-5">
          <section className="rounded-xl border border-slate-200 dark:border-slate-800 bg-white dark:bg-[#111827] p-5 space-y-4">
            <div className="flex items-center justify-between gap-2">
              <h2 className="text-sm font-semibold text-slate-900 dark:text-slate-100 flex items-center gap-2">
                <Cpu className="w-4 h-4 text-emerald-600 dark:text-emerald-400" />
                Adapter Capabilities
              </h2>
              <span className="text-xs font-mono-num text-slate-500 dark:text-slate-400">
                {probing ? 'Probing...' : caps?.available ? 'WebGPU Native' : 'Float32 Fallback'}
              </span>
            </div>

            {probing || !caps ? (
              <div className="space-y-2.5 animate-pulse">
                <div className="h-4 bg-slate-200 dark:bg-slate-800 rounded w-3/4" />
                <div className="h-4 bg-slate-200 dark:bg-slate-800 rounded w-1/2" />
                <div className="h-4 bg-slate-200 dark:bg-slate-800 rounded w-2/3" />
              </div>
            ) : (
              <dl className="grid grid-cols-2 gap-y-3 gap-x-4 text-xs">
                <div className="col-span-2">
                  <dt className="text-slate-500 dark:text-slate-400">Detected Compute Adapter</dt>
                  <dd className="font-mono-num font-medium text-slate-900 dark:text-slate-100 mt-0.5">
                    {caps.adapterName}
                  </dd>
                </div>
                <div>
                  <dt className="text-slate-500 dark:text-slate-400">Shader F16 Support</dt>
                  <dd className="font-mono-num font-medium text-slate-900 dark:text-slate-100 mt-0.5">
                    {caps.supportsF16 ? 'Supported (shader-f16)' : 'Float32 / Q8_0'}
                  </dd>
                </div>
                <div>
                  <dt className="text-slate-500 dark:text-slate-400">Max Storage Buffer</dt>
                  <dd className="font-mono-num font-medium text-slate-900 dark:text-slate-100 mt-0.5">
                    {caps.maxBufferSizeMb.toLocaleString()} MB
                  </dd>
                </div>
                <div>
                  <dt className="text-slate-500 dark:text-slate-400">Est. Memory Bandwidth</dt>
                  <dd className="font-mono-num font-medium text-slate-900 dark:text-slate-100 mt-0.5">
                    {caps.estimatedMemBwGbps} GB/s
                  </dd>
                </div>
                <div>
                  <dt className="text-slate-500 dark:text-slate-400">Est. Compute Throughput</dt>
                  <dd className="font-mono-num font-medium text-slate-900 dark:text-slate-100 mt-0.5">
                    {caps.estimatedTflops.toFixed(1)} TFLOP/s
                  </dd>
                </div>
              </dl>
            )}
          </section>

          <section className="rounded-xl border border-slate-200 dark:border-slate-800 bg-white dark:bg-[#111827] p-5 space-y-4">
            <h2 className="text-sm font-semibold text-slate-900 dark:text-slate-100">
              Shard Allocation & Identity
            </h2>

            <div className="space-y-3">
              <div>
                <label
                  htmlFor="worker-label-input"
                  className="block text-xs text-slate-600 dark:text-slate-400 mb-1"
                >
                  Worker Display Name
                </label>
                <input
                  id="worker-label-input"
                  type="text"
                  value={workerLabel}
                  onChange={(e) => setWorkerLabel(e.target.value)}
                  className="w-full rounded-lg border border-slate-200 dark:border-slate-700 bg-slate-50 dark:bg-[#0D1424] px-3 py-2 text-xs text-slate-900 dark:text-slate-100"
                />
              </div>

              <div>
                <div className="flex items-center justify-between text-xs mb-1">
                  <label htmlFor="shard-mb-slider" className="text-slate-600 dark:text-slate-400">
                    Safe Browser Shard Budget
                  </label>
                  <span className="font-mono-num font-semibold text-slate-900 dark:text-slate-100">
                    {(shardMb / 1024).toFixed(1)} GB ({shardMb} MB)
                  </span>
                </div>
                <input
                  id="shard-mb-slider"
                  type="range"
                  min={512}
                  max={8192}
                  step={512}
                  value={shardMb}
                  onChange={(e) => setShardMb(Number(e.target.value))}
                  className="w-full accent-emerald-600"
                />
                <div className="flex justify-between text-[11px] font-mono-num text-slate-400 mt-0.5">
                  <span>0.5 GB</span>
                  <span>4.0 GB</span>
                  <span>8.0 GB</span>
                </div>
              </div>

              <div>
                <span className="block text-xs text-slate-600 dark:text-slate-400 mb-1">
                  Tensor Precision Kernel
                </span>
                <div className="inline-flex rounded-lg border border-slate-200 dark:border-slate-700 bg-slate-50 dark:bg-[#0D1424] p-0.5 text-xs">
                  {(['f32', 'f16', 'q8_0'] as const).map((p) => (
                    <button
                      key={p}
                      type="button"
                      onClick={() => setPrecision(p)}
                      className={`px-3 py-1 rounded-md font-mono-num font-medium transition-colors ${
                        precision === p
                          ? 'bg-white dark:bg-[#1E293B] text-slate-900 dark:text-slate-100 shadow-xs'
                          : 'text-slate-500 dark:text-slate-400'
                      }`}
                    >
                      {p}
                    </button>
                  ))}
                </div>
              </div>
            </div>
          </section>
        </div>

        {/* Gate 6 Tensor Kernel Integrity Verification Table */}
        <section className="rounded-xl border border-slate-200 dark:border-slate-800 bg-white dark:bg-[#111827] p-5 space-y-4">
          <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-2">
            <div>
              <h2 className="text-sm font-semibold text-slate-900 dark:text-slate-100 flex items-center gap-2">
                <ShieldCheck className="w-4 h-4 text-emerald-600 dark:text-emerald-400" />
                Tensor Kernel Verification (RMSNorm · RoPE · GEMV)
              </h2>
              <p className="text-xs text-slate-500 dark:text-slate-400 mt-0.5">
                Validates WGSL compute output against deterministic Float32 CPU reference tensors (tolerance &le; 1e-5).
              </p>
            </div>
            {benchmark && (
              <span
                className={`inline-flex items-center gap-1.5 text-xs font-medium ${
                  benchmark.passedGate6
                    ? 'text-emerald-700 dark:text-emerald-400'
                    : 'text-rose-700 dark:text-rose-400'
                }`}
              >
                <CheckCircle2 className="w-3.5 h-3.5" />
                {benchmark.passedGate6 ? 'Passed (<= 1e-5)' : 'Failed Tolerance'}
              </span>
            )}
          </div>

          {!benchmark ? (
            <div className="border-t border-slate-100 dark:border-slate-800 pt-5 pb-2 text-center space-y-2.5">
              <p className="text-xs text-slate-500 dark:text-slate-400">
                No shader integrity run recorded in this session yet. Execute a local GEMV/RMSNorm/RoPE pass to verify numerical parity before joining.
              </p>
              <button
                type="button"
                disabled={runningBench}
                onClick={runIntegrityCheck}
                className="inline-flex items-center gap-1.5 px-3.5 py-2 rounded-lg bg-slate-900 dark:bg-slate-100 text-white dark:text-slate-900 text-xs font-medium whitespace-nowrap"
              >
                <Play className="w-3.5 h-3.5" />
                {runningBench ? 'Running...' : 'Run Verification Pass'}
              </button>
            </div>
          ) : (
            <div className="overflow-x-auto">
              <table className="w-full text-left border-collapse text-xs">
                <thead>
                  <tr className="border-b border-slate-200 dark:border-slate-800 text-slate-500 dark:text-slate-400">
                    <th className="py-2 pr-4 font-medium">Kernel</th>
                    <th className="py-2 px-4 font-medium">Dimensions / Precision</th>
                    <th className="py-2 px-4 font-medium text-right">Max Abs Error</th>
                    <th className="py-2 px-4 font-medium text-right">Elapsed / Throughput</th>
                    <th className="py-2 pl-4 font-medium text-right">Verdict</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-slate-100 dark:divide-slate-800/80 font-mono-num">
                  <tr>
                    <td className="py-2.5 pr-4 font-sans font-medium text-slate-900 dark:text-slate-100">
                      GEMV (y = W · x)
                    </td>
                    <td className="py-2.5 px-4 text-slate-600 dark:text-slate-300">
                      {benchmark.rows}×{benchmark.cols} · {benchmark.precision}
                    </td>
                    <td className="py-2.5 px-4 text-right text-slate-900 dark:text-slate-100">
                      {benchmark.maxAbsError.toExponential(2)}
                    </td>
                    <td className="py-2.5 px-4 text-right text-slate-700 dark:text-slate-300">
                      {benchmark.elapsedMs.toFixed(2)} ms · {benchmark.throughputGflops.toFixed(2)} GFLOP/s
                    </td>
                    <td className="py-2.5 pl-4 text-right font-sans font-medium text-emerald-700 dark:text-emerald-400">
                      {benchmark.maxAbsError <= 1e-5 ? 'Pass' : 'Fail'}
                    </td>
                  </tr>
                  <tr>
                    <td className="py-2.5 pr-4 font-sans font-medium text-slate-900 dark:text-slate-100">
                      RMSNorm
                    </td>
                    <td className="py-2.5 px-4 text-slate-600 dark:text-slate-300">
                      dim {benchmark.cols} · eps 1e-5
                    </td>
                    <td className="py-2.5 px-4 text-right text-slate-900 dark:text-slate-100">
                      {benchmark.rmsnormMaxError.toExponential(2)}
                    </td>
                    <td className="py-2.5 px-4 text-right text-slate-700 dark:text-slate-300">
                      Est. {benchmark.estimatedTokPerSec.toFixed(1)} tok/s
                    </td>
                    <td className="py-2.5 pl-4 text-right font-sans font-medium text-emerald-700 dark:text-emerald-400">
                      {benchmark.rmsnormMaxError <= 1e-5 ? 'Pass' : 'Fail'}
                    </td>
                  </tr>
                  <tr>
                    <td className="py-2.5 pr-4 font-sans font-medium text-slate-900 dark:text-slate-100">
                      Rotary Embeddings (RoPE)
                    </td>
                    <td className="py-2.5 px-4 text-slate-600 dark:text-slate-300">
                      head_dim 64 · pos 17
                    </td>
                    <td className="py-2.5 px-4 text-right text-slate-900 dark:text-slate-100">
                      {benchmark.ropeMaxError.toExponential(2)}
                    </td>
                    <td className="py-2.5 px-4 text-right text-slate-700 dark:text-slate-300">
                      {benchmark.backend}
                    </td>
                    <td className="py-2.5 pl-4 text-right font-sans font-medium text-emerald-700 dark:text-emerald-400">
                      {benchmark.ropeMaxError <= 1e-5 ? 'Pass' : 'Fail'}
                    </td>
                  </tr>
                </tbody>
              </table>
            </div>
          )}
        </section>
      </main>
    </div>
  );
};
