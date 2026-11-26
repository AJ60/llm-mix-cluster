import {
  ContextLengthOption,
  GgufModelPreset,
  HfCatalogModel,
  StarClusterPlan,
} from '../types/starCluster';

export const CURATED_HF_GGUF_CATALOG: HfCatalogModel[] = [
  {
    id: 'hf-qwen3-30b-a3b',
    hfRepo: 'Qwen/Qwen3-30B-A3B-GGUF',
    hfUrl: 'https://huggingface.co/Qwen/Qwen3-30B-A3B-GGUF',
    name: 'Qwen3-30B-A3B MoE',
    author: 'Qwen',
    architecture: 'MoE',
    totalParamsB: 30.5,
    activeParamsB: 3.3,
    layers: 48,
    defaultQuant: 'Q4_K_M',
    sizeMb: 18500,
    quantOptions: [
      { quant: 'IQ4_XS', sizeMb: 16200, bpw: 4.25 },
      { quant: 'Q4_K_M', sizeMb: 18500, bpw: 4.85 },
      { quant: 'Q6_K', sizeMb: 25100, bpw: 6.57 },
    ],
    contextLengthDefault: 16384,
    downloads: 482000,
    likes: 1940,
    tags: ['moe', 'reasoning', 'tool-use', 'fast'],
    summary: 'Sparse Mixture-of-Experts: only 3.3B parameters fire per token for 30B quality at tiny-model speed.',
    source: 'curated',
  },
  {
    id: 'hf-bitnet-1.58',
    hfRepo: 'microsoft/BitNet-b1.58-2B-4T-gguf',
    hfUrl: 'https://huggingface.co/microsoft/BitNet-b1.58-2B-4T-gguf',
    name: 'BitNet b1.58 Ternary',
    author: 'microsoft',
    architecture: 'Ternary',
    totalParamsB: 27.0,
    activeParamsB: 27.0,
    layers: 36,
    defaultQuant: 'TQ1_0',
    sizeMb: 6400,
    quantOptions: [
      { quant: 'TQ1_0', sizeMb: 6400, bpw: 1.69 },
      { quant: 'TQ2_0', sizeMb: 7900, bpw: 2.06 },
    ],
    contextLengthDefault: 8192,
    downloads: 315000,
    likes: 2410,
    tags: ['ternary', 'cpu', 'edge'],
    summary: 'Ultra-compact 1.58-bit ternary weights optimized for CPU AVX2/NEON LUT kernels with minimal VRAM.',
    source: 'curated',
  },
  {
    id: 'hf-qwen25-coder-32b',
    hfRepo: 'Qwen/Qwen2.5-Coder-32B-Instruct-GGUF',
    hfUrl: 'https://huggingface.co/Qwen/Qwen2.5-Coder-32B-Instruct-GGUF',
    name: 'Qwen2.5-Coder-32B-Instruct',
    author: 'Qwen',
    architecture: 'Dense',
    totalParamsB: 32.5,
    activeParamsB: 32.5,
    layers: 64,
    defaultQuant: 'Q4_K_M',
    sizeMb: 19800,
    quantOptions: [
      { quant: 'IQ4_XS', sizeMb: 17600, bpw: 4.25 },
      { quant: 'Q4_K_M', sizeMb: 19800, bpw: 4.85 },
      { quant: 'Q5_K_M', sizeMb: 23200, bpw: 5.7 },
    ],
    contextLengthDefault: 16384,
    downloads: 610000,
    likes: 2890,
    tags: ['coding', 'dense', 'hermes', 'opencode'],
    summary: 'Top-performing coding and agentic harness model for OpenCode, Hermes, Cline, and repo refactors.',
    source: 'curated',
  },
  {
    id: 'hf-qwen25-7b-instruct',
    hfRepo: 'Qwen/Qwen2.5-7B-Instruct-GGUF',
    hfUrl: 'https://huggingface.co/Qwen/Qwen2.5-7B-Instruct-GGUF',
    name: 'Qwen2.5-7B-Instruct',
    author: 'Qwen',
    architecture: 'Dense',
    totalParamsB: 7.6,
    activeParamsB: 7.6,
    layers: 28,
    defaultQuant: 'Q4_K_M',
    sizeMb: 4680,
    quantOptions: [
      { quant: 'Q4_K_M', sizeMb: 4680, bpw: 4.85 },
      { quant: 'Q6_K', sizeMb: 6250, bpw: 6.57 },
      { quant: 'Q8_0', sizeMb: 8100, bpw: 8.5 },
    ],
    contextLengthDefault: 16384,
    downloads: 940000,
    likes: 3120,
    tags: ['fast', 'single-node', 'chat', 'coding'],
    summary: 'Fits comfortably inside a single desktop GPU or modern phone node with long-context headroom.',
    source: 'curated',
  },
  {
    id: 'hf-deepseek-r1-14b',
    hfRepo: 'bartowski/DeepSeek-R1-Distill-Qwen-14B-GGUF',
    hfUrl: 'https://huggingface.co/bartowski/DeepSeek-R1-Distill-Qwen-14B-GGUF',
    name: 'DeepSeek-R1-Distill-14B',
    author: 'bartowski',
    architecture: 'Dense',
    totalParamsB: 14.8,
    activeParamsB: 14.8,
    layers: 48,
    defaultQuant: 'Q4_K_M',
    sizeMb: 8990,
    quantOptions: [
      { quant: 'Q4_K_M', sizeMb: 8990, bpw: 4.85 },
      { quant: 'Q5_K_M', sizeMb: 10510, bpw: 5.7 },
    ],
    contextLengthDefault: 16384,
    downloads: 528000,
    likes: 1850,
    tags: ['reasoning', 'dense', 'math'],
    summary: 'Chain-of-thought distilled 14B model ideal for balanced dual Windows + Linux node execution.',
    source: 'curated',
  },
  {
    id: 'hf-gemma3-12b',
    hfRepo: 'bartowski/google_gemma-3-12b-it-GGUF',
    hfUrl: 'https://huggingface.co/bartowski/google_gemma-3-12b-it-GGUF',
    name: 'Gemma-3-12B-IT',
    author: 'bartowski',
    architecture: 'Dense',
    totalParamsB: 12.2,
    activeParamsB: 12.2,
    layers: 48,
    defaultQuant: 'Q4_K_M',
    sizeMb: 7300,
    quantOptions: [
      { quant: 'Q4_K_M', sizeMb: 7300, bpw: 4.85 },
      { quant: 'Q8_0', sizeMb: 12900, bpw: 8.5 },
    ],
    contextLengthDefault: 16384,
    downloads: 412000,
    likes: 1640,
    tags: ['chat', 'fast', 'balanced'],
    summary: 'Compact high-precision instruction model tuned for crisp multi-turn reasoning & local tool calling.',
    source: 'curated',
  },
  {
    id: 'hf-llama33-70b',
    hfRepo: 'bartowski/Llama-3.3-70B-Instruct-GGUF',
    hfUrl: 'https://huggingface.co/bartowski/Llama-3.3-70B-Instruct-GGUF',
    name: 'Llama-3.3-70B-Instruct',
    author: 'bartowski',
    architecture: 'Dense',
    totalParamsB: 70.6,
    activeParamsB: 70.6,
    layers: 80,
    defaultQuant: 'Q4_K_M',
    sizeMb: 40500,
    quantOptions: [
      { quant: 'IQ3_M', sizeMb: 33200, bpw: 3.7 },
      { quant: 'Q4_K_M', sizeMb: 40500, bpw: 4.85 },
    ],
    contextLengthDefault: 8192,
    downloads: 785000,
    likes: 3490,
    tags: ['dense', 'reasoning', 'multi-node'],
    summary: 'Flagship 70B instruction model designed to span Linux + Windows + helper nodes in 1-Hop Star mode.',
    source: 'curated',
  },
  {
    id: 'hf-qwen3-8b',
    hfRepo: 'Qwen/Qwen3-8B-GGUF',
    hfUrl: 'https://huggingface.co/Qwen/Qwen3-8B-GGUF',
    name: 'Qwen3-8B',
    author: 'Qwen',
    architecture: 'Dense',
    totalParamsB: 8.2,
    activeParamsB: 8.2,
    layers: 36,
    defaultQuant: 'Q4_K_M',
    sizeMb: 5030,
    quantOptions: [
      { quant: 'Q4_K_M', sizeMb: 5030, bpw: 4.85 },
      { quant: 'Q8_0', sizeMb: 8710, bpw: 8.5 },
    ],
    contextLengthDefault: 32768,
    downloads: 690000,
    likes: 2210,
    tags: ['fast', 'single-node', 'thinking'],
    summary: 'Hybrid thinking/non-thinking 8B model with ultra-low latency on any desktop or laptop node.',
    source: 'curated',
  },
];

export interface ModelClusterFitEvaluation {
  fitStatus: 'Fast' | 'Best Fit' | 'Streaming' | 'Too Large';
  canLoadCleanly: boolean;
  headroomMb: number;
  coveragePct: number;
  estimatedTokPerSec: number;
  recommendedNodesCount: number;
}

export function evaluateModelAgainstCluster(
  model: HfCatalogModel,
  selectedSizeMb: number,
  plan: StarClusterPlan
): ModelClusterFitEvaluation {
  const poolMb = plan.totalSafePoolMb;
  const headroomMb = poolMb - selectedSizeMb;
  const coveragePct = Math.min(100, Math.round((poolMb / Math.max(1, selectedSizeMb)) * 100));

  let fitStatus: ModelClusterFitEvaluation['fitStatus'] = 'Too Large';
  if (poolMb >= selectedSizeMb * 1.2) {
    fitStatus = 'Fast';
  } else if (poolMb >= selectedSizeMb) {
    fitStatus = 'Best Fit';
  } else if (poolMb >= selectedSizeMb * 0.55) {
    fitStatus = 'Streaming';
  }

  const activeNodes = plan.nodes.filter((n) => n.effectiveCapMb > 0);
  const aggregateBw = activeNodes.reduce(
    (sum, n) => sum + (n.vramFreeMb > 0 ? Math.max(n.memBwGbps, 200) : n.memBwGbps),
    0
  );
  const activeBytesGb = (model.activeParamsB * 4.8) / 8;
  const rawTok = aggregateBw / Math.max(0.5, activeBytesGb);
  const speedPenalty = fitStatus === 'Streaming' ? 0.35 : fitStatus === 'Too Large' ? 0.15 : 0.85;
  const estimatedTokPerSec = Number(Math.max(0.8, Math.min(190, rawTok * speedPenalty)).toFixed(1));

  return {
    fitStatus,
    canLoadCleanly: fitStatus === 'Fast' || fitStatus === 'Best Fit',
    headroomMb,
    coveragePct,
    estimatedTokPerSec,
    recommendedNodesCount: activeNodes.length,
  };
}

export function catalogModelToPreset(
  item: HfCatalogModel,
  chosenQuant?: string
): GgufModelPreset {
  const variant =
    item.quantOptions.find((q) => q.quant === chosenQuant) ??
    item.quantOptions[0] ?? { quant: item.defaultQuant, sizeMb: item.sizeMb, bpw: 4.8 };

  const ctx: ContextLengthOption = item.contextLengthDefault || 8192;
  return {
    id: item.id,
    name: item.name,
    quant: variant.quant,
    architecture: item.architecture,
    sizeMb: variant.sizeMb,
    layers: item.layers,
    totalParamsB: item.totalParamsB,
    activeParamsB: item.activeParamsB,
    contextLengthDefault: ctx,
    hfRepo: item.hfRepo,
    hfUrl: item.hfUrl,
    summary: `${(variant.sizeMb / 1024).toFixed(1)} GB · ${item.layers}L · ${item.activeParamsB}B active / ${item.totalParamsB}B total`,
    tags: item.tags,
  };
}

export function inferHfModelMetadata(raw: {
  id: string;
  downloads?: number;
  likes?: number;
  tags?: string[];
}): HfCatalogModel {
  const repoId = raw.id || 'community/model-GGUF';
  const parts = repoId.split('/');
  const author = parts[0] || 'huggingface';
  const rawName = (parts[1] || repoId).replace(/-GGUF$/i, '');

  const paramMatch = repoId.match(/(\d+(?:\.\d+)?)\s*[Bb]/);
  const totalParamsB = paramMatch ? Math.min(405, Math.max(0.5, parseFloat(paramMatch[1]))) : 8;
  const moeActiveMatch = repoId.match(/A(\d+(?:\.\d+)?)[Bb]/i);
  const hasMoeTag = (raw.tags ?? []).some((t) => /moe/i.test(t));
  const isMoE = Boolean(moeActiveMatch) || /moe|mixtral|deepseek-v3/i.test(repoId) || hasMoeTag;
  const isTernary = /bitnet|1\.58|ternary/i.test(repoId) || (raw.tags ?? []).some((t) => /ternary|bitnet/i.test(t));

  const activeParamsB = moeActiveMatch
    ? parseFloat(moeActiveMatch[1])
    : isMoE
      ? Number((totalParamsB * 0.22).toFixed(1))
      : totalParamsB;

  const sizeMb = isTernary
    ? Math.round(totalParamsB * 240)
    : Math.round(totalParamsB * 610);
  const layers = Math.max(24, Math.min(96, Math.round(totalParamsB <= 9 ? 32 : totalParamsB <= 35 ? 56 : 80)));

  return {
    id: `hf-live-${repoId.replace(/[^a-z0-9]/gi, '-').toLowerCase()}`,
    hfRepo: repoId,
    hfUrl: `https://huggingface.co/${repoId}`,
    name: rawName,
    author,
    architecture: isTernary ? 'Ternary' : isMoE ? 'MoE' : 'Dense',
    totalParamsB,
    activeParamsB,
    layers,
    defaultQuant: isTernary ? 'TQ1_0' : 'Q4_K_M',
    sizeMb,
    quantOptions: [
      { quant: isTernary ? 'TQ1_0' : 'Q4_K_M', sizeMb, bpw: isTernary ? 1.69 : 4.85 },
      { quant: 'Q6_K', sizeMb: Math.round(sizeMb * 1.32), bpw: 6.57 },
    ],
    contextLengthDefault: 8192,
    downloads: raw.downloads ?? 0,
    likes: raw.likes ?? 0,
    tags: (raw.tags ?? []).slice(0, 5),
    summary: `Live Hugging Face GGUF repository · ~${(sizeMb / 1024).toFixed(1)} GB (${isTernary ? 'TQ1_0' : 'Q4_K_M'})`,
    source: 'huggingface',
  };
}
