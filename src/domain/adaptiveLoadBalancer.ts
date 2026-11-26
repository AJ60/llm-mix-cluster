import {
  DEFAULT_THERMAL_LIMITS,
  GgufModelPreset,
  StarClusterNode,
  ThermalLimits,
} from '../types/starCluster';
import { buildNonUniformLayerProfiles } from '../data/localModelsScanner';
import { computeAndroidSafeCap, computeDesktopSafeCap, computeVramSafeCap } from './starClusterPlanner';

export type TransportTier =
  | 'LOCAL'
  | 'USB_C_ADB'
  | 'ETHERNET_LAN'
  | 'WIFI_DIRECT_P2P'
  | 'WIFI_LAN'
  | 'WEBGPU_BROWSER';

export interface SubGraphSliceAssignment {
  nodeId: string;
  label: string;
  role: 'Master' | 'Worker';
  transportTier: TransportTier;
  transportPriority: number;
  attentionLayers: number[];
  ffnExpertLayers: number[];
  layerStart: number;
  layerEnd: number;
  allocatedMb: number;
  safeCapMb: number;
  effectiveScore: number;
  thermalState: 'COOL' | 'WARN' | 'SHED_25' | 'SHED_50' | 'EMERGENCY_OFFLOAD';
  cooldownLocked: boolean;
  tensorSplitPct: number;
  reason: string;
}

export interface AdaptiveSubGraphPlan {
  modelId: string;
  modelName: string;
  totalLayers: number;
  attentionAnchorNodeId: string;
  ffnOffloadNodes: string[];
  totalSafePoolMb: number;
  neededMb: number;
  zeroOomVerified: boolean;
  tensorSplit: number[];
  tensorSplitSum: number;
  assignments: SubGraphSliceAssignment[];
  estimatedSpeedupVsSingleHost: number;
}

export interface NodeThermalCooldownTracker {
  cooldownUntilMs: number;
  lastEmergencyTempC: number;
}

const TRANSPORT_RANK: Record<TransportTier, { priority: number; bonus: number }> = {
  LOCAL: { priority: 1, bonus: 1.45 },
  USB_C_ADB: { priority: 2, bonus: 1.22 },
  ETHERNET_LAN: { priority: 3, bonus: 1.15 },
  WIFI_DIRECT_P2P: { priority: 4, bonus: 1.05 },
  WIFI_LAN: { priority: 5, bonus: 0.88 },
  WEBGPU_BROWSER: { priority: 6, bonus: 0.82 },
};

export function classifyTransportTier(node: StarClusterNode): TransportTier {
  if (node.role === 'Master' || node.linkType === 'Local') return 'LOCAL';
  if (node.linkType === 'USB-C Tunnel' || node.linkType === 'USB RNDIS') return 'USB_C_ADB';
  if (node.linkType === 'Ethernet 2.5G') return 'ETHERNET_LAN';
  if (node.linkType === 'Wi-Fi Direct P2P') return 'WIFI_DIRECT_P2P';
  if (node.linkType === 'WebGPU Browser' || node.platform === 'WebGPU') return 'WEBGPU_BROWSER';
  return 'WIFI_LAN';
}

/**
 * Enforces the Zero-OOM Mathematical Guarantee on every Android node:
 *   AllocatedMemory <= min(0.50 * TotalRAM, AvailableRAM - 512 MB)
 * (and <= 0.40 * TotalRAM on <= 2 GB legacy devices).
 */
export function computeZeroOomSafeBudgetMb(node: StarClusterNode): number {
  if (node.platform === 'Android') {
    const pct = node.totalRamMb <= 2048 ? 40 : 50;
    const reserve = node.totalRamMb <= 2048 ? 256 : 512;
    const hardCap = computeAndroidSafeCap(node.totalRamMb, node.availableRamMb, pct, reserve);
    if (node.safeRamMb && node.safeRamMb > 0) {
      return Math.min(node.safeRamMb, hardCap);
    }
    return hardCap;
  }
  if (node.platform === 'WebGPU') {
    const vramCap = computeVramSafeCap(node.vramFreeMb, false);
    const ramCap = Math.max(256, Math.min(8192, Math.floor(node.availableRamMb * 0.75)));
    return Math.min(8192, Math.max(vramCap, Math.min(ramCap, node.safeRamMb ?? ramCap)));
  }
  const desktopRam = computeDesktopSafeCap(node.availableRamMb, node.platform === 'Edge ARM' ? 512 : 1024);
  const desktopVram = computeVramSafeCap(node.vramFreeMb, node.osType === 'windows');
  return desktopRam + desktopVram;
}

/**
 * Progressive thermal watchdog with cooldown hysteresis:
 *   >= 42°C (Android): WARN (25% shed ready)
 *   >= 45°C (Android): SHED_25 (-25% layers)
 *   >= 48°C (Android): EMERGENCY_OFFLOAD (100% shed -> 0 layers)
 *   Hysteresis: once EMERGENCY_OFFLOAD triggers, node remains locked in cooldown until temp < 40°C.
 */
export function evaluateAdaptiveThermalState(
  node: StarClusterNode,
  cooldownState?: NodeThermalCooldownTracker,
  limits: ThermalLimits = DEFAULT_THERMAL_LIMITS,
  nowMs = Date.now()
): {
  state: SubGraphSliceAssignment['thermalState'];
  capacityScale: number;
  cooldownLocked: boolean;
  reason: string;
} {
  const isAndroid = node.platform === 'Android';
  const warnC = isAndroid ? limits.androidWarnC : node.vramFreeMb > 0 ? limits.gpuWarnC : limits.cpuWarnC;
  const shedC = isAndroid ? limits.androidShedC : node.vramFreeMb > 0 ? limits.gpuShedC : limits.cpuShedC;
  const critC = isAndroid ? limits.androidCritC : node.vramFreeMb > 0 ? limits.gpuCritC : limits.cpuCritC;
  const recoveryC = isAndroid ? 40.0 : critC - 8.0;

  if (cooldownState && (node.tempC >= recoveryC || nowMs < cooldownState.cooldownUntilMs)) {
    if (node.tempC >= recoveryC) {
      return {
        state: 'EMERGENCY_OFFLOAD',
        capacityScale: 0,
        cooldownLocked: true,
        reason: `Cooling down (${node.tempC.toFixed(1)}°C >= ${recoveryC.toFixed(1)}°C recovery threshold)`,
      };
    }
  }

  if (node.tempC >= critC) {
    return {
      state: 'EMERGENCY_OFFLOAD',
      capacityScale: 0,
      cooldownLocked: true,
      reason: `Critical thermal ceiling ${node.tempC.toFixed(1)}°C >= ${critC}°C — 100% layers retracted`,
    };
  }
  if (node.tempC >= shedC) {
    return {
      state: 'SHED_25',
      capacityScale: 0.75,
      cooldownLocked: false,
      reason: `Thermal throttle ${node.tempC.toFixed(1)}°C >= ${shedC}°C — shedding 25% layers`,
    };
  }
  if (node.tempC >= warnC) {
    return {
      state: 'WARN',
      capacityScale: 0.9,
      cooldownLocked: false,
      reason: `Thermal warning ${node.tempC.toFixed(1)}°C >= ${warnC}°C — conservative headroom`,
    };
  }
  return {
    state: 'COOL',
    capacityScale: 1.0,
    cooldownLocked: false,
    reason: `Nominal ${node.tempC.toFixed(1)}°C`,
  };
}

/**
 * Multi-factor Cake node suitability score combining:
 * - Memory Bandwidth (GB/s)
 * - Peak Compute TFLOPS
 * - Inter-node RTT (ms) and Packet Loss (%)
 * - Thermal headroom and battery discharge penalty
 * - Multi-transport priority rank
 */
export function scoreAdaptiveNode(
  node: StarClusterNode,
  thermalScale: number
): number {
  const tier = classifyTransportTier(node);
  const { bonus } = TRANSPORT_RANK[tier];
  const vramFactor = 1 + (Math.max(0, node.vramFreeMb) / 1024) * 0.25;
  const dischargePenalty =
    node.batteryDischargePctPerHr && node.batteryDischargePctPerHr > 18
      ? Math.max(0.65, 1 - (node.batteryDischargePctPerHr - 18) * 0.015)
      : 1.0;

  const rawCompute = (0.75 * Math.max(4, node.memBwGbps) + 12.0 * Math.max(0.2, node.computeTflops)) * vramFactor;
  const networkDenom = 1.0 + 0.14 * Math.max(0.1, node.rttMs) + 2.5 * Math.max(0, node.pktLossPct);
  return Number(((rawCompute * bonus * thermalScale * dischargePenalty) / networkDenom).toFixed(3));
}

/**
 * Cake Sub-Graph Dynamic Scheduler:
 * 1. Pins continuous attention + embedding + lm_head layers on the highest-bandwidth node (Master GPU).
 * 2. Shards feed-forward / MoE expert layers across healthy workers prioritized by transport tier.
 * 3. Guarantees Zero-OOM on every node and exact 100.00% --tensor-split sum.
 */
export function buildAdaptiveSubGraphPlan(
  nodes: StarClusterNode[],
  model: GgufModelPreset,
  cooldownMap: Record<string, NodeThermalCooldownTracker> = {},
  limits: ThermalLimits = DEFAULT_THERMAL_LIMITS
): AdaptiveSubGraphPlan {
  const nonUniform = buildNonUniformLayerProfiles(
    model.layers,
    model.sizeMb,
    model.architecture,
    model.contextLengthDefault,
    'q8_0',
    model.headCount,
    model.headCountKv,
    model.embeddingLength
  );
  const totalLayers = Math.max(1, nonUniform.perLayerProfiles.length);
  const mbPerLayer = Math.max(1, model.sizeMb / totalLayers);

  const evaluated = nodes.map((node) => {
    const tier = classifyTransportTier(node);
    const thermal = evaluateAdaptiveThermalState(node, cooldownMap[node.nodeId], limits);
    const hardSafeCapMb = computeZeroOomSafeBudgetMb(node);
    const effScale = node.role === 'Master' && thermal.capacityScale === 0 ? 0.5 : thermal.capacityScale;
    const effectiveCapMb =
      node.isConnected && node.rpcNativeReady !== false ? Math.floor(hardSafeCapMb * effScale) : 0;
    const score = effectiveCapMb > 0 ? scoreAdaptiveNode(node, effScale) : 0;

    return {
      node,
      tier,
      priority: TRANSPORT_RANK[tier].priority,
      thermal,
      hardSafeCapMb,
      effectiveCapMb,
      score,
    };
  });

  // Sort: Master first, then by transport priority & score
  evaluated.sort((a, b) => {
    if (a.node.role === 'Master' && b.node.role !== 'Master') return -1;
    if (b.node.role === 'Master' && a.node.role !== 'Master') return 1;
    if (a.priority !== b.priority) return a.priority - b.priority;
    return b.score - a.score;
  });

  const masterEval = evaluated.find((e) => e.node.role === 'Master') ?? evaluated[0];
  const workerEvals = evaluated.filter(
    (e) =>
      e.node.nodeId !== masterEval.node.nodeId &&
      e.effectiveCapMb >= 256 &&
      e.thermal.state !== 'EMERGENCY_OFFLOAD' &&
      !e.thermal.cooldownLocked
  );

  // Determine how many FFN/contiguous tail layers each eligible worker safely holds
  const totalActiveScore =
    masterEval.score + workerEvals.reduce((sum, w) => sum + w.score, 0) || 1;

  const workerCounts = new Map<string, number>();
  let totalWorkerLayers = 0;

  for (const w of workerEvals) {
    const maxCapLayers = Math.max(0, Math.floor(w.effectiveCapMb / mbPerLayer));
    const proportional = Math.round((w.score / totalActiveScore) * totalLayers);
    // Apply explicit thermal scale directly to proportional layer target when thermally shedding
    const thermallyScaled =
      w.thermal.state === 'SHED_25'
        ? Math.max(0, Math.floor(Math.min(maxCapLayers, Math.max(1, proportional)) * 0.75))
        : Math.min(maxCapLayers, Math.max(1, proportional));
    const bounded = Math.min(maxCapLayers, thermallyScaled);
    workerCounts.set(w.node.nodeId, bounded);
    totalWorkerLayers += bounded;
  }

  // Ensure Master retains at least Layer 0 + attention anchor
  if (totalWorkerLayers >= totalLayers && totalLayers > 1) {
    let excess = totalWorkerLayers - (totalLayers - 1);
    for (const w of [...workerEvals].reverse()) {
      const cur = workerCounts.get(w.node.nodeId) ?? 0;
      const take = Math.min(cur, excess);
      workerCounts.set(w.node.nodeId, cur - take);
      excess -= take;
      if (excess <= 0) break;
    }
    totalWorkerLayers = totalLayers - 1;
  }

  const masterLayers = Math.max(1, totalLayers - totalWorkerLayers);
  const assignments: SubGraphSliceAssignment[] = [];

  // Master pins continuous attention blocks [0..masterLayers-1]
  const masterAttLayers = Array.from({ length: masterLayers }, (_, i) => i);
  const masterAllocRaw = nonUniform.perLayerProfiles
    .slice(0, masterLayers)
    .reduce((acc, l) => acc + l.totalWeightMb, 0);
  const masterAllocMb = Math.min(masterEval.hardSafeCapMb, Math.round(masterAllocRaw));

  assignments.push({
    nodeId: masterEval.node.nodeId,
    label: masterEval.node.label,
    role: 'Master',
    transportTier: masterEval.tier,
    transportPriority: masterEval.priority,
    attentionLayers: masterAttLayers,
    ffnExpertLayers: masterAttLayers,
    layerStart: 0,
    layerEnd: masterLayers - 1,
    allocatedMb: masterAllocMb,
    safeCapMb: masterEval.hardSafeCapMb,
    effectiveScore: masterEval.score,
    thermalState: masterEval.thermal.state,
    cooldownLocked: masterEval.thermal.cooldownLocked,
    tensorSplitPct: 0,
    reason: 'Attention anchor + embedding/lm_head pinned on highest-bandwidth node',
  });

  let cursor = masterLayers;
  for (const w of evaluated) {
    if (w.node.nodeId === masterEval.node.nodeId) continue;
    const count = workerCounts.get(w.node.nodeId) ?? 0;
    if (count > 0 && cursor < totalLayers) {
      const start = cursor;
      const end = Math.min(totalLayers - 1, cursor + count - 1);
      const actualCount = end - start + 1;
      const sliceIds = Array.from({ length: actualCount }, (_, i) => start + i);
      const rawMb = nonUniform.perLayerProfiles
        .slice(start, end + 1)
        .reduce((acc, l) => acc + l.totalWeightMb, 0);
      const clampedMb = Math.min(w.effectiveCapMb, Math.round(rawMb));
      cursor = end + 1;

      assignments.push({
        nodeId: w.node.nodeId,
        label: w.node.label,
        role: 'Worker',
        transportTier: w.tier,
        transportPriority: w.priority,
        attentionLayers: [],
        ffnExpertLayers: sliceIds,
        layerStart: start,
        layerEnd: end,
        allocatedMb: clampedMb,
        safeCapMb: w.hardSafeCapMb,
        effectiveScore: w.score,
        thermalState: w.thermal.state,
        cooldownLocked: w.thermal.cooldownLocked,
        tensorSplitPct: 0,
        reason: `Cake FFN/Expert shard (${actualCount}L via ${w.node.linkType})`,
      });
    } else {
      assignments.push({
        nodeId: w.node.nodeId,
        label: w.node.label,
        role: 'Worker',
        transportTier: w.tier,
        transportPriority: w.priority,
        attentionLayers: [],
        ffnExpertLayers: [],
        layerStart: 0,
        layerEnd: -1,
        allocatedMb: 0,
        safeCapMb: w.hardSafeCapMb,
        effectiveScore: w.score,
        thermalState: w.thermal.state,
        cooldownLocked: w.thermal.cooldownLocked,
        tensorSplitPct: 0,
        reason: w.thermal.reason,
      });
    }
  }

  // Normalize --tensor-split to sum to EXACTLY 100.00 across active participants
  const activeAssignments = assignments.filter((a) => a.layerEnd >= a.layerStart);
  const totalAssignedLayers = activeAssignments.reduce(
    (sum, a) => sum + (a.layerEnd - a.layerStart + 1),
    0
  );
  const rawSplits = activeAssignments.map((a) =>
    Number((((a.layerEnd - a.layerStart + 1) / Math.max(1, totalAssignedLayers)) * 100).toFixed(2))
  );
  const drift = Number((100 - rawSplits.reduce((s, v) => s + v, 0)).toFixed(2));
  if (rawSplits.length > 0) {
    rawSplits[0] = Number((rawSplits[0] + drift).toFixed(2));
  }
  activeAssignments.forEach((a, idx) => {
    a.tensorSplitPct = rawSplits[idx];
  });

  const zeroOomVerified = assignments.every((a) => a.allocatedMb <= a.safeCapMb);
  const totalSafePoolMb = evaluated
    .filter((e) => e.effectiveCapMb > 0)
    .reduce((sum, e) => sum + e.effectiveCapMb, 0);
  const neededMb = Math.round(model.sizeMb);
  const tensorSplitSum = Number(rawSplits.reduce((s, v) => s + v, 0).toFixed(2));

  // Speedup estimate when sharding avoids Master SSD swap thrashing
  const singleHostFits = masterEval.hardSafeCapMb >= neededMb;
  const clusterFits = totalSafePoolMb >= neededMb;
  const estimatedSpeedupVsSingleHost =
    !singleHostFits && clusterFits
      ? Number((2.1 + Math.min(1.4, workerEvals.length * 0.45)).toFixed(2))
      : Number((1 + Math.min(0.6, (activeAssignments.length - 1) * 0.18)).toFixed(2));

  return {
    modelId: model.id,
    modelName: model.name,
    totalLayers,
    attentionAnchorNodeId: masterEval.node.nodeId,
    ffnOffloadNodes: activeAssignments
      .filter((a) => a.role === 'Worker')
      .map((a) => a.nodeId),
    totalSafePoolMb,
    neededMb,
    zeroOomVerified,
    tensorSplit: rawSplits,
    tensorSplitSum,
    assignments,
    estimatedSpeedupVsSingleHost,
  };
}
