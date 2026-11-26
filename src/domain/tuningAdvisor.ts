import {
  AdaptiveLoadFactorResult,
  BackendTuningConfig,
  ClusterTuningAdvice,
  GgufModelPreset,
  LiveHardwareProbe,
  StarClusterPlan,
  TemperatureMetricsInput,
  TuningAdviceItem,
  WorkerAutoConfigRecommendation,
} from '../types/starCluster';
import { isNativeTernaryQuant } from './backendTuner';

export const ADAPTIVE_LOAD_TEMP_THRESHOLD_C = 40.0;
export const ADAPTIVE_LOAD_HOT_THRESHOLD_C = 48.0;
export const ADAPTIVE_LOAD_CRITICAL_THRESHOLD_C = 52.0;

/**
 * Ingests device temperature metrics (skin/SoC internal/battery sensors + optional ADPF headroom)
 * and calculates an Adaptive Load Factor in [0.0, 1.0] that strictly caps worker concurrency
 * whenever effective device temperature reaches or exceeds 40°C (uncapped only below 40°C).
 */
export function calculateAdaptiveLoadFactor(
  metrics: TemperatureMetricsInput,
  baseConcurrency: number
): AdaptiveLoadFactorResult {
  const safeBase = Math.max(1, Math.round(baseConcurrency));
  const candidates = [metrics.tempC, metrics.internalTempC, metrics.batteryTempC].filter(
    (v): v is number => typeof v === 'number' && Number.isFinite(v) && v > 0
  );
  const effectiveTempC = candidates.length > 0 ? Math.max(...candidates) : 34.0;
  const headroom =
    typeof metrics.thermalHeadroom === 'number' && Number.isFinite(metrics.thermalHeadroom)
      ? metrics.thermalHeadroom
      : 0;

  // Nominal thermal envelope (strictly < 40°C): full uncapped concurrency
  if (effectiveTempC < ADAPTIVE_LOAD_TEMP_THRESHOLD_C) {
    return {
      adaptiveLoadFactor: 1.0,
      effectiveTempC: Number(effectiveTempC.toFixed(1)),
      concurrencyCapped: false,
      concurrencyCap: safeBase,
      baseConcurrency: safeBase,
      band: 'nominal',
      reason: `Device ${effectiveTempC.toFixed(1)}°C < 40.0°C — Adaptive Load Factor 1.00 (uncapped ${safeBase} threads)`,
    };
  }

  // Critical thermal ceiling (>= 52°C or ADPF saturation above 48°C): shed concurrency to minimum safe floor
  if (
    effectiveTempC >= ADAPTIVE_LOAD_CRITICAL_THRESHOLD_C ||
    (headroom >= 1.0 && effectiveTempC >= ADAPTIVE_LOAD_HOT_THRESHOLD_C)
  ) {
    return {
      adaptiveLoadFactor: 0.0,
      effectiveTempC: Number(effectiveTempC.toFixed(1)),
      concurrencyCapped: true,
      concurrencyCap: 1,
      baseConcurrency: safeBase,
      band: 'critical_shed',
      reason: `Critical heat ${effectiveTempC.toFixed(1)}°C (>=52°C) — Adaptive Load Factor 0.00 (capped to 1 thread / layer shed)`,
    };
  }

  // Hot throttle band (> 48°C .. < 52°C): aggressive decay 0.45 -> 0.20
  if (effectiveTempC > ADAPTIVE_LOAD_HOT_THRESHOLD_C) {
    const progress = (effectiveTempC - ADAPTIVE_LOAD_HOT_THRESHOLD_C) /
      (ADAPTIVE_LOAD_CRITICAL_THRESHOLD_C - ADAPTIVE_LOAD_HOT_THRESHOLD_C);
    const rawFactor = Math.max(0.2, Math.min(0.45, 0.45 - progress * 0.25));
    const adaptiveLoadFactor = Number(rawFactor.toFixed(2));
    const computedCap = Math.max(1, Math.floor(safeBase * adaptiveLoadFactor));
    const concurrencyCap = Math.min(effectiveTempC >= 50.0 ? 1 : 2, computedCap);
    return {
      adaptiveLoadFactor,
      effectiveTempC: Number(effectiveTempC.toFixed(1)),
      concurrencyCapped: true,
      concurrencyCap,
      baseConcurrency: safeBase,
      band: 'hot_throttle',
      reason: `Internal heat ${effectiveTempC.toFixed(1)}°C (>48°C) — Adaptive Load Factor ${adaptiveLoadFactor.toFixed(2)} (concurrency capped ${safeBase} → ${concurrencyCap})`,
    };
  }

  // Warm cap band (>= 40°C .. <= 48°C): smooth linear decay 0.90 -> 0.45, strictly capping concurrency below base
  const warmProgress =
    (effectiveTempC - ADAPTIVE_LOAD_TEMP_THRESHOLD_C) /
    (ADAPTIVE_LOAD_HOT_THRESHOLD_C - ADAPTIVE_LOAD_TEMP_THRESHOLD_C);
  const headroomPenalty = headroom >= 0.85 ? 0.1 : 0;
  const rawFactor = Math.max(0.45, Math.min(0.9, 0.9 - warmProgress * 0.45 - headroomPenalty));
  const adaptiveLoadFactor = Number(rawFactor.toFixed(2));
  let concurrencyCap = Math.max(1, Math.floor(safeBase * adaptiveLoadFactor));
  if (safeBase > 1 && concurrencyCap >= safeBase) {
    concurrencyCap = Math.max(1, safeBase - 1);
  }

  return {
    adaptiveLoadFactor,
    effectiveTempC: Number(effectiveTempC.toFixed(1)),
    concurrencyCapped: true,
    concurrencyCap,
    baseConcurrency: safeBase,
    band: 'warm_cap',
    reason: `Device ${effectiveTempC.toFixed(1)}°C >= 40.0°C — Adaptive Load Factor ${adaptiveLoadFactor.toFixed(2)} (concurrency capped ${safeBase} → ${concurrencyCap})`,
  };
}

/**
 * Inspects the active cluster plan, model architecture/quant, Master VRAM/CPU,
 * and connected worker telemetry (including live temperature sensors) to generate
 * actionable tuning recommendations, Adaptive Load Factors, and per-worker auto-configs.
 */
export function analyzeClusterTuning(
  plan: StarClusterPlan,
  model: GgufModelPreset,
  config: BackendTuningConfig,
  liveHardware?: LiveHardwareProbe,
  workerTempMetrics?: Record<string, TemperatureMetricsInput>
): ClusterTuningAdvice {
  const items: TuningAdviceItem[] = [];
  const autoTunePatch: Partial<BackendTuningConfig> = {};
  const workerConfigs: Record<string, WorkerAutoConfigRecommendation> = {};

  const master = plan.nodes.find((n) => n.role === 'Master') ?? plan.nodes[0];
  const workers = plan.nodes.filter((n) => n.role === 'Worker' && n.isConnected);
  const masterVramMb = master?.vramTotalMb ?? master?.vramFreeMb ?? liveHardware?.vramMb ?? 0;
  const masterPhysCores = Math.max(2, master?.physicalCores ?? liveHardware?.bigCores ?? 6);
  const isNativeTernary = isNativeTernaryQuant(model.quant);

  let penalty = 0;

  // 1. Context length & TTFT bottleneck check (e.g. 27B Ternary / 4 GB VRAM at >= 8192 ctx)
  if ((model.architecture === 'Ternary' || model.totalParamsB >= 20) && config.contextLength > 4096) {
    penalty += masterVramMb > 0 && masterVramMb <= 4300 ? 28 : 16;
    const patch: Partial<BackendTuningConfig> = { contextLength: 4096 };
    Object.assign(autoTunePatch, patch);
    items.push({
      id: 'reduce-context-ttft',
      severity: masterVramMb > 0 && masterVramMb <= 4300 ? 'critical' : 'warning',
      title: `Reduce Context (-c ${config.contextLength.toLocaleString()} → 4,096) for faster TTFT`,
      detail: `${model.name} (${model.totalParamsB}B) allocates ${plan.kvCacheTotalMb} MB of KV cache at ${config.contextLength.toLocaleString()} tokens, slowing prompt eval and crowding ${masterVramMb > 0 ? `${Math.round(masterVramMb / 1024)} GB` : 'host'} VRAM.`,
      impact: '2–4× faster TTFT & lower VRAM pressure',
      patch,
    });
  }

  // 2. Explicit physical CPU thread count check
  const recommendedMasterThreads = Math.max(
    2,
    Math.min(master?.osType === 'windows' ? 12 : 16, masterPhysCores)
  );
  if (!config.threads || config.threads > masterPhysCores * 1.5 || config.threads < 2) {
    penalty += 14;
    const patch: Partial<BackendTuningConfig> = { threads: recommendedMasterThreads };
    Object.assign(autoTunePatch, patch);
    items.push({
      id: 'pin-physical-threads',
      severity: 'warning',
      title: `Pin CPU Threads (-t ${recommendedMasterThreads}) to physical cores`,
      detail: `Unpinned or oversubscribed threads cause SMT contention on CPU-offloaded layers. Pinning to ${recommendedMasterThreads} physical cores maximizes memory bandwidth.`,
      impact: '+25–45% token generation speed on hybrid CPU+GPU layers',
      patch,
    });
  }

  // 3. Ternary GPU offload + FlashAttention / KV quant mismatch check
  if (model.architecture === 'Ternary' && !isNativeTernary && !config.enableFlashAttn && masterVramMb > 0) {
    penalty += 22;
    const patch: Partial<BackendTuningConfig> = {
      enableFlashAttn: true,
      kvCacheQuant: 'q8_0',
    };
    Object.assign(autoTunePatch, patch);
    items.push({
      id: 'enable-ternary-fa',
      severity: 'critical',
      title: `Enable FlashAttention (-fa on) for ${model.quant} Ternary GPU offload`,
      detail: `${model.name} uses standard ${model.quant} weights, not native TQ1_0. Enabling FlashAttention allows quantized Q8_0 KV cache directly on CUDA instead of slow CPU fallback.`,
      impact: 'Eliminates 0% GPU stall & unlocks CUDA attention kernels',
      patch,
    });
  }

  // 4. Dedicated 4 GB VRAM headroom & batch size check
  if (masterVramMb > 0 && masterVramMb <= 4300) {
    const manualNgl = config.gpuOffloadLayers;
    const safeMasterNgl = master?.nglLayers ?? 0;
    if (manualNgl !== null && manualNgl !== undefined && manualNgl > safeMasterNgl) {
      penalty += 30;
      const patch: Partial<BackendTuningConfig> = { gpuOffloadLayers: null };
      Object.assign(autoTunePatch, patch);
      items.push({
        id: 'clamp-vram-ngl',
        severity: 'critical',
        title: `Manual -ngl ${manualNgl} exceeds ${Math.round(masterVramMb / 1024)} GB Dedicated VRAM (${safeMasterNgl}L safe max)`,
        detail: `Exceeding dedicated VRAM spills weights into Windows WDDM Shared GPU Memory over PCIe, collapsing GPU utilization to ~0% and throughput below 1 tok/s.`,
        impact: 'Prevents WDDM PCIe thrashing & restores full CUDA speed',
        patch,
      });
    }

    if ((config.batchSize ?? 1024) > 512 && (model.sizeMb >= 8000 || model.architecture === 'Ternary')) {
      penalty += 10;
      const patch: Partial<BackendTuningConfig> = { batchSize: 512, ubatchSize: 256 };
      Object.assign(autoTunePatch, patch);
      items.push({
        id: 'tune-batch-4gb',
        severity: 'warning',
        title: 'Tune Prompt Batch (-b 512 -ub 256) for 4 GB VRAM scratch budget',
        detail: 'Lower batch scratch leaves dedicated VRAM room for extra GPU layers and prevents prompt-eval allocation stalls.',
        impact: 'Smoother TTFT & lower peak VRAM spikes',
        patch,
      });
    }
  }

  // 5. Per-worker auto-configuration with temperature metric ingestion & Adaptive Load Factor (>40°C concurrency cap)
  let clusterAdaptiveLoadFactor = 1.0;
  for (const w of workers) {
    const isAndroid = w.osType === 'android';
    const hwMatch = liveHardware?.connectedDevices?.find(
      (d) => d.id === w.nodeId || (d.ip === w.ip && d.rpcPort === w.rpcPort)
    );
    const overrideMetrics = workerTempMetrics?.[w.nodeId];
    const ingestedMetrics: TemperatureMetricsInput = {
      tempC: overrideMetrics?.tempC ?? w.tempC ?? hwMatch?.tempC ?? 34,
      internalTempC: overrideMetrics?.internalTempC ?? hwMatch?.internalTempC,
      batteryTempC: overrideMetrics?.batteryTempC ?? hwMatch?.batteryTempC,
      thermalHeadroom: overrideMetrics?.thermalHeadroom,
      isMobile: isAndroid,
    };

    const rawCores = Math.max(2, w.physicalCores || 4);
    const baseConcurrency = isAndroid
      ? Math.max(4, Math.min(6, rawCores >= 6 ? 6 : rawCores + 2))
      : Math.max(2, Math.min(12, rawCores));

    const alf = calculateAdaptiveLoadFactor(ingestedMetrics, baseConcurrency);
    const tempC = alf.effectiveTempC;
    clusterAdaptiveLoadFactor = Math.min(clusterAdaptiveLoadFactor, alf.adaptiveLoadFactor);

    const thermalPolicy: WorkerAutoConfigRecommendation['thermalPolicy'] =
      tempC >= 41.5 ? 'cooldown' : tempC >= 39.0 ? 'balanced' : 'performance';

    let recommendedThreads: number;
    let recommendedSafeRamPct: number;
    let recommendedContextTokens: number;
    let loadAllocationScale: number;
    let threadPriority: 'high' | 'normal' | 'low';
    let preferGpu: boolean;
    let reason: string;

    if (isAndroid) {
      // Dynamic thermal-aware Android scaling policy combined with Adaptive Load Factor concurrency cap:
      // <40°C: ALF=1.0, boosted priority (high), higher concurrency threads, 4096 ctx, 100% load.
      // >=40°C: ALF strictly caps worker concurrency & lowers priority; >48°C internal heat throttles further & sheds layers.
      const isOver48 = tempC > ADAPTIVE_LOAD_HOT_THRESHOLD_C;
      const isCritical52 = tempC >= ADAPTIVE_LOAD_CRITICAL_THRESHOLD_C;
      const policyThreads = isCritical52
        ? 1
        : isOver48
          ? Math.max(1, tempC >= 50.0 ? 1 : 2)
          : thermalPolicy === 'cooldown'
            ? 2
            : thermalPolicy === 'balanced'
              ? Math.max(2, baseConcurrency - 2)
              : baseConcurrency;
      recommendedThreads = alf.concurrencyCapped
        ? Math.min(policyThreads, alf.concurrencyCap)
        : policyThreads;
      recommendedSafeRamPct =
        w.totalRamMb <= 2048
          ? 40
          : isCritical52
            ? 25
            : isOver48
              ? 35
              : thermalPolicy === 'cooldown'
                ? 40
                : 50;
      recommendedContextTokens = isCritical52
        ? 512
        : isOver48
          ? 1024
          : thermalPolicy === 'cooldown'
            ? 2048
            : thermalPolicy === 'balanced'
              ? 3072
              : w.totalRamMb <= 3072
                ? 2048
                : 4096;
      const policyLoadScale = isCritical52
        ? 0.0
        : isOver48
          ? tempC >= 50.0
            ? 0.25
            : 0.5
          : thermalPolicy === 'cooldown'
            ? 0.7
            : thermalPolicy === 'balanced'
              ? 0.85
              : 1.0;
      loadAllocationScale = Number(Math.min(policyLoadScale, alf.adaptiveLoadFactor).toFixed(2));
      threadPriority =
        tempC < 40.0 && thermalPolicy === 'performance'
          ? 'high'
          : isOver48 || thermalPolicy === 'cooldown'
            ? 'low'
            : 'normal';
      const driverLower = (w.backendDriver || '').toLowerCase();
      const hasVerifiedVulkan16Bit =
        driverLower.includes('vulkan') && !driverLower.includes('neon') && !driverLower.includes('cpu');
      preferGpu = thermalPolicy !== 'cooldown' && !isOver48 && hasVerifiedVulkan16Bit;
      reason = isOver48
        ? `Internal heat ${tempC.toFixed(1)}°C (>48°C) — ALF ${alf.adaptiveLoadFactor.toFixed(2)}, capping concurrency to ${recommendedThreads} thread(s), ${recommendedContextTokens} ctx & shedding layers`
        : alf.concurrencyCapped
          ? `Thermal cap (${tempC.toFixed(1)}°C >= 40°C) — ALF ${alf.adaptiveLoadFactor.toFixed(2)} capping concurrency ${baseConcurrency} → ${recommendedThreads} threads (${recommendedContextTokens} ctx)`
          : thermalPolicy === 'performance'
            ? `Cool (${tempC.toFixed(1)}°C < 40°C) — ALF 1.00, boosted priority, ${recommendedThreads} perf threads (${hasVerifiedVulkan16Bit ? 'Vulkan 16-bit GPU' : 'Optimized NEON CPU'}), ${recommendedContextTokens} ctx + ${w.allocatedLayers}L shard`
            : `Warm (${tempC.toFixed(1)}°C) — ALF ${alf.adaptiveLoadFactor.toFixed(2)}, balanced ${recommendedThreads} threads, ${recommendedContextTokens} ctx`;

      if (alf.concurrencyCapped) {
        penalty += isOver48 ? 14 : 6;
        items.push({
          id: `adaptive-load-cap-${w.nodeId}`,
          severity: isOver48 ? 'critical' : 'warning',
          title: `${w.label} Adaptive Load Factor ${alf.adaptiveLoadFactor.toFixed(2)} (${tempC.toFixed(1)}°C >= 40°C)`,
          detail: alf.reason,
          impact: isOver48
            ? `Sheds offloaded layers & caps worker concurrency to ${recommendedThreads}/${baseConcurrency} threads`
            : `Caps worker concurrency to ${recommendedThreads}/${baseConcurrency} threads to hold thermal stability`,
        });
      } else if (w.allocatedLayers === 0 && !w.pruned && !isOver48) {
        penalty += 15;
        items.push({
          id: `worker-idle-${w.nodeId}`,
          severity: 'warning',
          title: `${w.label} has 0L active in RAM Residency`,
          detail: `Worker is connected (${w.safeRamMb} MB safe cap, ${tempC.toFixed(1)}°C) but currently idle. Auto-configure will assign active layers and ${recommendedThreads} threads.`,
          impact: 'Activates phone compute & updates RAM Residency live',
        });
      } else if ((w.cpuUsagePct ?? 0) < 25 && thermalPolicy === 'performance' && w.allocatedLayers > 0) {
        items.push({
          id: `worker-boost-${w.nodeId}`,
          severity: 'optimal',
          title: `${w.label} auto-configured for ${w.allocatedLayers}L @ ${recommendedThreads} threads (ALF 1.00)`,
          detail: reason,
          impact: 'Higher mobile CPU/GPU utilization without overheating',
        });
      }
    } else {
      recommendedThreads = alf.concurrencyCapped
        ? Math.min(baseConcurrency, alf.concurrencyCap)
        : baseConcurrency;
      recommendedSafeRamPct = alf.concurrencyCapped ? Math.max(50, Math.round(75 * alf.adaptiveLoadFactor)) : 75;
      recommendedContextTokens = alf.band === 'hot_throttle' || alf.band === 'critical_shed'
        ? Math.min(config.contextLength, 2048)
        : config.contextLength;
      loadAllocationScale = alf.adaptiveLoadFactor;
      threadPriority = alf.concurrencyCapped ? 'low' : 'normal';
      preferGpu = (w.vramTotalMb ?? 0) > 0 && alf.band !== 'critical_shed';
      reason = alf.concurrencyCapped
        ? `Desktop/Edge worker ${tempC.toFixed(1)}°C > 40°C — ALF ${alf.adaptiveLoadFactor.toFixed(2)} capping concurrency ${baseConcurrency} → ${recommendedThreads} threads`
        : `Desktop/Edge worker configured for ${recommendedThreads} threads (${w.allocatedLayers}L, ALF 1.00)`;

      if (alf.concurrencyCapped) {
        penalty += alf.band === 'critical_shed' ? 12 : 5;
        items.push({
          id: `adaptive-load-cap-${w.nodeId}`,
          severity: alf.band === 'critical_shed' || alf.band === 'hot_throttle' ? 'critical' : 'warning',
          title: `${w.label} Adaptive Load Factor ${alf.adaptiveLoadFactor.toFixed(2)} (${tempC.toFixed(1)}°C > 40°C)`,
          detail: alf.reason,
          impact: `Caps worker concurrency to ${recommendedThreads}/${baseConcurrency} threads`,
        });
      }
    }

    workerConfigs[w.nodeId] = {
      nodeId: w.nodeId,
      label: w.label,
      recommendedThreads,
      recommendedSafeRamPct,
      recommendedContextTokens,
      loadAllocationScale,
      adaptiveLoadFactor: alf.adaptiveLoadFactor,
      concurrencyCap: alf.concurrencyCap,
      concurrencyCapped: alf.concurrencyCapped,
      effectiveTempC: alf.effectiveTempC,
      threadPriority,
      preferGpu,
      assignedLayers: w.allocatedLayers,
      layerStart: w.layerStart,
      layerEnd: w.layerEnd,
      allocatedMb: w.allocatedMb,
      thermalPolicy,
      reason,
    };
  }

  if (items.length === 0) {
    items.push({
      id: 'cluster-optimal',
      severity: 'optimal',
      title: 'Cluster tuning is optimal for current hardware',
      detail: `Master (${master?.nglLayers ?? 0}L GPU, -t ${recommendedMasterThreads}, -c ${config.contextLength.toLocaleString()}) and ${workers.length} worker(s) are balanced within safe thermal & VRAM budgets (ALF ${clusterAdaptiveLoadFactor.toFixed(2)}).`,
      impact: 'Peak sustained tok/s & fast TTFT',
    });
  }

  const healthScore = Math.max(15, Math.min(100, 100 - penalty));
  const hasActionableChanges = Object.keys(autoTunePatch).length > 0;
  const summary = hasActionableChanges
    ? `Detected ${items.filter((i) => i.severity !== 'optimal').length} tuning bottleneck(s) — apply 1-click Auto-Tune to boost tok/s & cut TTFT.`
    : clusterAdaptiveLoadFactor < 1.0
      ? `Adaptive Load Factor ${clusterAdaptiveLoadFactor.toFixed(2)} active — capping warm worker concurrency above 40°C.`
      : 'All VRAM, thread, context, and worker thermal parameters are tuned cleanly.';

  return {
    healthScore,
    summary,
    items,
    autoTunePatch,
    workerConfigs,
    clusterAdaptiveLoadFactor: Number(clusterAdaptiveLoadFactor.toFixed(2)),
    hasActionableChanges,
  };
}
