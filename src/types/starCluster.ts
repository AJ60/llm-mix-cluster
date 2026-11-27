export type ThermalState = 'COOL' | 'WARN' | 'SHED_25' | 'EMERGENCY_OFFLOAD';

export type ClusterTierCode = 'S' | 'A' | 'B' | 'C';

export type ClusterMode = 'LIVE_CLUSTER' | 'LOCAL_DEVICE' | 'VIRTUAL_4NODE';

export type GgufArchitecture = 'MoE' | 'Ternary' | 'TurboQuant' | 'Looped' | 'Dense';

export type SpeculativeMode = 'off' | 'native_mtp' | 'prompt_lookup' | 'external_draft';

export interface ConnectedDeviceInfo {
  id: string;
  model: string;
  platform: 'Android' | 'Linux' | 'Windows' | 'Edge ARM' | 'WebGPU' | 'Other';
  transport: string;
  safeCapMb: number;
  totalRamMb?: number;
  availableRamMb?: number;
  vramMb?: number;
  vramFreeMb?: number;
  storageTotalMb?: number;
  storageFreeMb?: number;
  computeTflops?: number;
  rttMs?: number;
  pktLossPct?: number;
  bandwidthMbps?: number;
  tempC?: number;
  internalTempC?: number;
  batteryTempC?: number;
  cpuUsagePct?: number;
  gpuUsagePct?: number;
  bigCores?: number;
  ip?: string;
  rpcPort?: number;
  lastHeartbeatMs?: number;
  handshakeVerified?: boolean;
  rpcNativeReady?: boolean;
  vulkanSupported?: boolean;
  activeBackend?: string;
  isOnline?: boolean;
  isWorkerRunning?: boolean;
  workerStatus?: 'Running' | 'Not Installed' | 'Stopped' | 'Offline';
}

export interface NodeNetworkAudit {
  nodeId: string;
  label: string;
  role: 'Master' | 'Worker';
  ip: string;
  linkType: 'Local' | 'USB-C Tunnel' | 'USB RNDIS' | 'Ethernet 2.5G' | 'Wi-Fi Direct P2P' | 'Wi-Fi LAN' | 'WebGPU Browser';
  rttMs: number;
  bandwidthMbps: number;
  packetLossPct: number;
  status: 'Optimal' | 'Degraded' | 'Offline';
  portsReachable: {
    master3000?: boolean;
    rpc50052?: boolean;
    upstream8080?: boolean;
  };
}

export interface ClusterNetworkAuditReport {
  timestamp: string;
  hostIp: string;
  hostHostname: string;
  activeTransport: string;
  nodes: NodeNetworkAudit[];
  summary: {
    totalNodes: number;
    healthyNodes: number;
    clusterRating: string;
    avgRttMs: number;
    bottleneckNode?: string | null;
  };
}

export interface LiveHardwareProbe {
  platform: 'windows' | 'linux' | 'darwin';
  osLabel: string;
  hostname?: string;
  hostIp?: string;
  totalRamMb: number;
  availableRamMb: number;
  vramMb: number;
  gpuName?: string;
  cpuModel: string;
  cores: number;
  bigCores?: number;
  cpuTempC?: number;
  gpuTempC?: number;
  cpuUsagePct?: number;
  gpuUsagePct?: number;
  storageTotalMb?: number;
  storageFreeMb?: number;
  connectedDevices: ConnectedDeviceInfo[];
}

export type ModelPresetId =
  | 'small-1.5b'
  | 'moe-30b'
  | 'ternary-27b'
  | 'dense-32b'
  | 'dense-70b'
  | 'custom-hf';

export type ContextLengthOption = 2048 | 4096 | 8192 | 16384 | 32768 | 65536 | 131072 | 262144;
export type KvCacheQuantOption = 'f16' | 'q8_0' | 'q4_0';
export type BackendOsTarget = 'windows' | 'linux';

export type WindowsAccelerator = 'CUDA 12.6' | 'Vulkan 1.3' | 'DirectML' | 'CPU AVX2';
export type LinuxAccelerator = 'CUDA 12.6' | 'ROCm 6.3' | 'Vulkan 1.3' | 'CPU OpenBLAS';

export interface LayerMemoryProfile {
  layerIndex: number;
  attentionMb: number;
  ffnOrExpertMb: number;
  totalWeightMb: number;
  kvCacheMb: number;
  isMoeLayer: boolean;
}

export interface GgufModelPreset {
  id: string;
  name: string;
  quant: string;
  architecture: GgufArchitecture;
  sizeMb: number;
  layers: number;
  totalParamsB: number;
  activeParamsB: number;
  contextLengthDefault: ContextLengthOption;
  maxContextLength?: number;
  hfRepo: string;
  hfUrl: string;
  summary: string;
  tags?: string[];
  isLocal?: boolean;
  localPath?: string;
  // Extended GGUF model-card & vision fields
  rawArchitecture?: string;
  expertCount?: number;
  expertUsedCount?: number;
  headCount?: number;
  headCountKv?: number;
  embeddingLength?: number;
  hasChatTemplate?: boolean;
  hasNativeMtp?: boolean;
  vocabSize?: number;
  vocabHash?: string;
  mmprojPath?: string | null;
  mmprojSizeMb?: number;
  masterPinnedOverheadMb?: number;
  perLayerProfiles?: LayerMemoryProfile[];
}

export const GGUF_MODEL_PRESETS: GgufModelPreset[] = [
  {
    id: 'small-1.5b',
    name: 'Qwen2.5-1.5B-Instruct',
    quant: 'Q4_K_M',
    architecture: 'Dense',
    sizeMb: 1150,
    layers: 28,
    totalParamsB: 1.54,
    activeParamsB: 1.54,
    contextLengthDefault: 8192,
    maxContextLength: 32768,
    hfRepo: 'Qwen/Qwen2.5-1.5B-Instruct-GGUF',
    hfUrl: 'https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF',
    summary: '1.15 GB · 28 layers · Ultra-lightweight edge & multi-device model',
    tags: ['small', 'fast', 'edge'],
    hasChatTemplate: true,
    headCount: 12,
    headCountKv: 2,
    embeddingLength: 1536,
    vocabSize: 151936,
    vocabHash: 'a91f4d20c3b81e04',
  },
  {
    id: 'moe-30b',
    name: 'Qwen3-30B-A3B MoE',
    quant: 'Q4_K_M',
    architecture: 'MoE',
    sizeMb: 18500,
    layers: 48,
    totalParamsB: 30.5,
    activeParamsB: 3.3,
    contextLengthDefault: 32768,
    maxContextLength: 131072,
    hfRepo: 'Qwen/Qwen3-30B-A3B-GGUF',
    hfUrl: 'https://huggingface.co/Qwen/Qwen3-30B-A3B-GGUF',
    summary: '18.5 GB · 48 layers · 3.3B active / 30.5B total Sparse MoE',
    tags: ['moe', 'chat', 'reasoning'],
    expertCount: 128,
    expertUsedCount: 8,
    headCount: 32,
    headCountKv: 4,
    embeddingLength: 2048,
    hasChatTemplate: true,
    hasNativeMtp: true,
    vocabSize: 151936,
    vocabHash: 'a91f4d20c3b81e04',
  },
  {
    id: 'ternary-27b',
    name: 'BitNet b1.58 Ternary',
    quant: 'TQ1_0',
    architecture: 'Ternary',
    sizeMb: 6400,
    layers: 36,
    totalParamsB: 27.0,
    activeParamsB: 27.0,
    contextLengthDefault: 4096,
    maxContextLength: 65536,
    hfRepo: 'microsoft/BitNet-b1.58-2B-4T-gguf',
    hfUrl: 'https://huggingface.co/microsoft/BitNet-b1.58-2B-4T-gguf',
    summary: '6.4 GB · 36 layers · Compact 1.58-bit TQ1_0 weights',
    tags: ['ternary', 'edge', 'cpu'],
    hasChatTemplate: true,
    headCount: 32,
    headCountKv: 8,
    embeddingLength: 4096,
    vocabSize: 128256,
    vocabHash: '3e80b2917f01c41a',
  },
  {
    id: 'dense-32b',
    name: 'Qwen2.5-Coder-32B Dense',
    quant: 'Q4_K_M',
    architecture: 'Dense',
    sizeMb: 19800,
    layers: 64,
    totalParamsB: 32.5,
    activeParamsB: 32.5,
    contextLengthDefault: 32768,
    maxContextLength: 131072,
    hfRepo: 'Qwen/Qwen2.5-Coder-32B-Instruct-GGUF',
    hfUrl: 'https://huggingface.co/Qwen/Qwen2.5-Coder-32B-Instruct-GGUF',
    summary: '19.8 GB · 64 layers · Dense coding & tool-calling model',
    tags: ['coding', 'dense', 'tools'],
    hasChatTemplate: true,
    headCount: 40,
    headCountKv: 8,
    embeddingLength: 5120,
    vocabSize: 152064,
    vocabHash: '84c710f29a5e6b31',
  },
  {
    id: 'dense-70b',
    name: 'Llama-3.3-70B-Instruct',
    quant: 'Q4_K_M',
    architecture: 'Dense',
    sizeMb: 40500,
    layers: 80,
    totalParamsB: 70.6,
    activeParamsB: 70.6,
    contextLengthDefault: 16384,
    maxContextLength: 131072,
    hfRepo: 'bartowski/Llama-3.3-70B-Instruct-GGUF',
    hfUrl: 'https://huggingface.co/bartowski/Llama-3.3-70B-Instruct-GGUF',
    summary: '40.5 GB · 80 layers · Multi-node flagship cluster model',
    tags: ['dense', 'reasoning', 'multi-node'],
    hasChatTemplate: true,
    headCount: 64,
    headCountKv: 8,
    embeddingLength: 8192,
    vocabSize: 128256,
    vocabHash: '3e80b2917f01c41a',
  },
];

export interface ThermalLimits {
  androidWarnC: number;
  androidShedC: number;
  androidCritC: number;
  cpuWarnC: number;
  cpuShedC: number;
  cpuCritC: number;
  gpuWarnC: number;
  gpuShedC: number;
  gpuCritC: number;
  cooldownHysteresisC: number;
  cooldownHoldSecs: number;
}

export const DEFAULT_THERMAL_LIMITS: ThermalLimits = {
  androidWarnC: 42.0,
  androidShedC: 45.0,
  androidCritC: 48.0,
  cpuWarnC: 75.0,
  cpuShedC: 82.0,
  cpuCritC: 88.0,
  gpuWarnC: 78.0,
  gpuShedC: 83.0,
  gpuCritC: 87.0,
  cooldownHysteresisC: 5.0,
  cooldownHoldSecs: 30,
};

export interface ThermalStatus {
  state: ThermalState;
  capacityScale: number;
  reason: string;
  cooldownRemainingS: number;
}

export type NodeOsType = 'windows' | 'linux' | 'android' | 'edge-arm' | 'webgpu';

export interface StarClusterNode {
  nodeId: string;
  label: string;
  role: 'Master' | 'Worker';
  platform: 'Desktop' | 'Android' | 'Edge ARM' | 'WebGPU';
  osType: NodeOsType;
  ip: string;
  rpcPort?: number;
  backendDriver: string;
  linkType: 'Local' | 'USB-C Tunnel' | 'USB RNDIS' | 'Ethernet 2.5G' | 'Wi-Fi Direct P2P' | 'Wi-Fi LAN' | 'WebGPU Browser';
  totalRamMb: number;
  availableRamMb: number;
  safeRamMb?: number;
  vramFreeMb: number;
  vramTotalMb?: number;
  batteryDischargePctPerHr?: number;
  memBwGbps: number;
  computeTflops: number;
  physicalCores: number;
  rttMs: number;
  pktLossPct: number;
  bandwidthMbps?: number;
  tempC: number;
  cpuUsagePct?: number;
  gpuUsagePct?: number;
  storageTotalMb?: number;
  storageFreeMb?: number;
  handshakeVerified?: boolean;
  rpcNativeReady?: boolean;
  heartbeatAgeS?: number;
  isConnected: boolean;
  statusNote?: string;
}

export interface PlannedStarNode extends StarClusterNode {
  safeRamMb: number;
  safeVramMb: number;
  effectiveCapMb: number;
  score: number;
  thermalStatus: ThermalStatus;
  allocatedMb: number;
  allocatedLayers: number;
  layerStart: number;
  layerEnd: number;
  activeParamsB: number;
  totalParamsB: number;
  kvCacheMb: number;
  estimatedTokPerSec: number;
  nglLayers: number;
  tuningFlags: string[];
  sharePct: number;
  pruned?: boolean;
  pruneReason?: string;
  statusNote: string;
  placementScore?: number;
  handoffCount?: number;
  estimatedHandoffMs?: number;
}

export type FitVerdict = 'Fast' | 'Best Fit' | 'Streaming';

export interface StarClusterPlan {
  tierCode: ClusterTierCode;
  tierLabel: string;
  masterId: string;
  fitVerdict: FitVerdict;
  totalSafePoolMb: number;
  totalVramPoolMb: number;
  neededMb: number;
  kvCacheTotalMb: number;
  effectiveContext?: number;
  estimatedHandoffMs?: number;
  coveragePct: number;
  overallThermalState: ThermalState;
  estimatedClusterTokPerSec: number;
  activeParamsPerTokenB: number;
  totalParamsB: number;
  nodes: PlannedStarNode[];
  tensorSplit: number[];
  tensorSplitLabel: string;
  tensorSplitCsv: string;
  useNoMmap: boolean;
  mmapStreaming: boolean;
  autoNkvo: boolean;
  masterMmprojPath?: string | null;
  cliArgs: string[];
  emaLearnedRuns?: number;
}

export interface BackendTuningConfig {
  windowsAccelerator: WindowsAccelerator;
  linuxAccelerator: LinuxAccelerator;
  contextLength: ContextLengthOption;
  kvCacheQuant: KvCacheQuantOption;
  enableFlashAttn: boolean;
  moeCpuOffload: boolean;
  windowsNoMmap: boolean;
  linuxMlock: boolean;
  linuxNumaInterleave: boolean;
  enableContextShift?: boolean;
  contBatching?: boolean;
  // LM Studio / Unsloth / Long-Agent controls
  gpuOffloadLayers?: number | null;
  manualTensorSplit?: string;
  speculativeMode?: SpeculativeMode;
  draftModelPath?: string;
  draftMaxTokens?: number;
  cacheReuse?: number;
  kvUnified?: boolean;
  noKvOffload?: boolean | null; // null = auto (-nkvo on <=4GB GPUs at >=32K ctx)
  slotSavePath?: string;
  forceNoMmap?: boolean | null; // null = smart >=1.15x switch
  threads?: number | null;
  batchSize?: number;
  ubatchSize?: number;
  enableJinja?: boolean;
  mmprojPath?: string;
  binaryPath?: string;
}

export interface TunedOsBackendProfile {
  os: BackendOsTarget;
  title: string;
  accelerator: string;
  recommendedThreads: number;
  gpuOffloadLayers: number;
  vramSafetyReserveMb: number;
  contextShiftEnabled?: boolean;
  ramSafetyReserveMb: number;
  batchSize: number;
  ubatchSize: number;
  kvCacheMb: number;
  expectedTokPerSec: number;
  envVars: Array<{ key: string; value: string; reason: string }>;
  cliFlags: string[];
  launchCommand: string;
  scriptFileName: string;
  scriptContent: string;
  optimizations: string[];
}

export interface QuantVariantOption {
  quant: string;
  sizeMb: number;
  bpw: number;
}

export interface HfCatalogModel {
  id: string;
  hfRepo: string;
  hfUrl: string;
  name: string;
  author: string;
  architecture: GgufArchitecture;
  totalParamsB: number;
  activeParamsB: number;
  layers: number;
  defaultQuant: string;
  sizeMb: number;
  quantOptions: QuantVariantOption[];
  contextLengthDefault: ContextLengthOption;
  downloads: number;
  likes: number;
  tags: string[];
  summary: string;
  source: 'curated' | 'huggingface';
}

export interface EngineRuntimeStatus {
  running: boolean;
  draining: boolean;
  activeTurns: number;
  upstreamUrl: string | null;
  serverType: 'llama-server' | 'ollama' | 'lm-studio' | 'offline';
  hasMmproj: boolean;
  mmprojPath: string | null;
  lastEvent: string;
  lastError?: string | null;
  detectedLlamaBin?: string | null;
  reloadProgressPct: number;
}

export interface LearnedEmaProfile {
  runs: number;
  emaTokPerSec: number;
  emaTtftMs: number;
  emaPeakVramMb: number;
  recommendedBatch: number;
  recommendedUbatch: number;
  learnedKvOffload: boolean;
  nodeEfficiency: Record<string, number>;
  throttleEvents: number;
  oomEvents: number;
  lastUpdatedEpochS: number;
}

export interface RuntimeClusterSnapshot {
  activeModel: GgufModelPreset;
  clusterMode: ClusterMode;
  tuningConfig: BackendTuningConfig;
  plan: StarClusterPlan;
  upstreamBaseUrl: string;
  engineStatus?: EngineRuntimeStatus;
  learnedProfile?: LearnedEmaProfile | null;
  tuningAdvice?: ClusterTuningAdvice | null;
  updatedAt: string;
}

export interface NodeBenchmarkEntry {
  nodeId: string;
  label: string;
  role: 'Master' | 'Worker';
  platform: string;
  transport: string;
  rttMs: number;
  layersAssigned: number;
  layerRange: string;
  allocatedMb: number;
  safeCapMb: number;
  promptEvalTokPerSec: number;
  generationTokPerSec: number;
  ttftMs: number;
  bandwidthGbps: number;
  status: 'Measured Live' | 'Hardware Micro-Bench' | 'Pruned';
}

export interface ClusterBenchmarkResult {
  id: string;
  timestamp: string;
  modelId: string;
  modelName: string;
  quant: string;
  architecture: GgufArchitecture;
  mode: 'live_engine' | 'hardware_microbench';
  promptTokens: number;
  generatedTokens: number;
  durationMs: number;
  ttftMs: number;
  clusterPromptTokPerSec: number;
  clusterGenTokPerSec: number;
  speculativeSpeedup: number;
  speculativeDraftK: number;
  speculativeAcceptanceRate: number;
  activeNodesCount: number;
  nodes: NodeBenchmarkEntry[];
  note: string;
}

export interface DiagnosticCheckItem {
  id: string;
  index: number;
  name: string;
  status: 'pass' | 'warn' | 'fail';
  summary: string;
  detail: string;
  remediation?: string;
}

export interface DiagnosticSentinelReport {
  timestamp: string;
  overallStatus: 'healthy' | 'degraded' | 'critical';
  passedCount: number;
  totalChecks: number;
  checks: DiagnosticCheckItem[];
}

export interface WifiDirectQrPayload {
  version: 'SILICA_P2P_V1';
  ssid: string;
  passphrase: string;
  endpoint: string;
  authHashHex: string;
  crc8Hex: string;
  rawPayload: string;
  matrix21x21: boolean[][];
}

export interface TuningAdviceItem {
  id: string;
  severity: 'critical' | 'warning' | 'optimal';
  title: string;
  detail: string;
  impact: string;
  patch?: Partial<BackendTuningConfig>;
}

export interface TemperatureMetricsInput {
  tempC: number;
  internalTempC?: number;
  batteryTempC?: number;
  thermalHeadroom?: number;
  isMobile?: boolean;
}

export interface AdaptiveLoadFactorResult {
  /** Normalized concurrency multiplier in [0.0, 1.0]: 1.0 at <=40°C, strictly capped above 40°C. */
  adaptiveLoadFactor: number;
  /** Peak effective temperature (°C) across ingested device/internal/battery sensors. */
  effectiveTempC: number;
  /** True whenever effectiveTempC > 40.0°C and worker concurrency is actively capped. */
  concurrencyCapped: boolean;
  /** Maximum worker concurrency (threads) permitted after applying Adaptive Load Factor. */
  concurrencyCap: number;
  /** Uncapped base worker concurrency before thermal scaling. */
  baseConcurrency: number;
  /** Thermal scaling band. */
  band: 'nominal' | 'warm_cap' | 'hot_throttle' | 'critical_shed';
  /** Human-readable explanation of the calculated Adaptive Load Factor. */
  reason: string;
}

export interface WorkerAutoConfigRecommendation {
  nodeId: string;
  label: string;
  recommendedThreads: number;
  recommendedSafeRamPct: number;
  recommendedContextTokens?: number;
  loadAllocationScale?: number;
  adaptiveLoadFactor: number;
  concurrencyCap: number;
  concurrencyCapped: boolean;
  effectiveTempC: number;
  threadPriority?: 'high' | 'normal' | 'low';
  preferGpu: boolean;
  assignedLayers: number;
  layerStart: number;
  layerEnd: number;
  allocatedMb: number;
  thermalPolicy: 'performance' | 'balanced' | 'cooldown';
  reason: string;
}

export interface ClusterTuningAdvice {
  healthScore: number;
  summary: string;
  items: TuningAdviceItem[];
  autoTunePatch: Partial<BackendTuningConfig>;
  workerConfigs: Record<string, WorkerAutoConfigRecommendation>;
  clusterAdaptiveLoadFactor: number;
  hasActionableChanges: boolean;
}


