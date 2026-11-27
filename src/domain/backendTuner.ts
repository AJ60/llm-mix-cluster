import {
  BackendOsTarget,
  BackendTuningConfig,
  ContextLengthOption,
  GgufModelPreset,
  KvCacheQuantOption,
  PlannedStarNode,
  StarClusterPlan,
  TunedOsBackendProfile,
} from '../types/starCluster';

export const ALLOWED_STOCK_LLAMA_CPP_FLAGS = new Set([
  '-m',
  '--model',
  '--mmproj',
  '--alias',
  '--host',
  '--port',
  '-c',
  '--ctx-size',
  '-ngl',
  '--n-gpu-layers',
  '--tensor-split',
  '--rpc',
  '--jinja',
  '-fa',
  '--flash-attn',
  '-ctk',
  '--cache-type-k',
  '-ctv',
  '--cache-type-v',
  '--kv-unified',
  '-nkvo',
  '--slot-save-path',
  '-ot',
  '-t',
  '--threads',
  '--threads-batch',
  '-b',
  '--batch-size',
  '-ub',
  '--ubatch-size',
  '--cont-batching',
  '--cache-reuse',
  '-lm',
  '--mlock',
  '--model-draft',
  '--draft-max',
  '--lookup-cache-static',
  '--ctx-shift',
]);

export function validateStockLlamaFlags(args: string[]): { ok: boolean; invalidFlag?: string } {
  for (const token of args) {
    if (token.startsWith('-')) {
      const flagName = token.split(/\s+/)[0];
      if (!ALLOWED_STOCK_LLAMA_CPP_FLAGS.has(flagName)) {
        return { ok: false, invalidFlag: flagName };
      }
    }
  }
  return { ok: true };
}

export function isNativeTernaryQuant(quant?: string): boolean {
  return /^(TQ1|TQ2|BITNET)/i.test((quant || '').trim());
}

export function createDefaultBackendTuningConfig(
  model: GgufModelPreset
): BackendTuningConfig {
  const isNativeTernary = isNativeTernaryQuant(model.quant);
  const defaultCtx: ContextLengthOption =
    model.architecture === 'Ternary'
      ? (Math.min(model.contextLengthDefault ?? 4096, 4096) as ContextLengthOption)
      : (model.contextLengthDefault ?? 4096);

  return {
    windowsAccelerator: 'CUDA 12.6',
    linuxAccelerator: 'CUDA 12.6',
    contextLength: defaultCtx,
    kvCacheQuant: model.sizeMb > 12000 || model.totalParamsB >= 14 ? 'q8_0' : 'f16',
    enableFlashAttn: !isNativeTernary,
    moeCpuOffload: model.architecture === 'MoE',
    windowsNoMmap: model.sizeMb <= 20000,
    linuxMlock: false,
    linuxNumaInterleave: true,
    enableContextShift: true,
    contBatching: true,
    gpuOffloadLayers: null,
    manualTensorSplit: '',
    speculativeMode: model.hasNativeMtp ? 'native_mtp' : 'off',
    draftModelPath: '',
    draftMaxTokens: 8,
    cacheReuse: 256,
    kvUnified: true,
    noKvOffload: null, // auto (-nkvo on <=4GB GPUs at >=8K ctx or f16 KV)
    slotSavePath: '',
    forceNoMmap: null, // auto (>=1.15x pool margin)
    threads: null,
    batchSize: model.architecture === 'Ternary' || model.sizeMb >= 10000 ? 512 : 1024,
    ubatchSize: 256,
    enableJinja: true,
    mmprojPath: model.mmprojPath ?? '',
  };
}

export function estimateKvCacheMb(
  layers: number,
  contextLength: ContextLengthOption,
  kvQuant: KvCacheQuantOption,
  totalParamsB: number,
  headCountKv?: number,
  headDim = 128
): number {
  const bytesPerElem = kvQuant === 'f16' ? 2.0 : kvQuant === 'q8_0' ? 1.0625 : 0.5625;
  if (headCountKv && headCountKv > 0) {
    const exactMb = (layers * 2 * headCountKv * headDim * contextLength * bytesPerElem) / (1024 * 1024);
    return Math.max(64, Math.round(exactMb));
  }
  const gqaScale = totalParamsB >= 50 ? 0.55 : totalParamsB >= 20 ? 0.42 : 0.3;
  const mb = Math.round(((layers * contextLength * gqaScale * bytesPerElem) / 1024) * 0.42);
  return Math.max(64, mb);
}

export function buildTunedOsBackendProfile(
  os: BackendOsTarget,
  model: GgufModelPreset,
  plan: StarClusterPlan,
  config: BackendTuningConfig
): TunedOsBackendProfile {
  const isWindows = os === 'windows';
  const targetNode: PlannedStarNode | undefined =
    plan.nodes.find((n) => n.osType === os) ??
    plan.nodes.find((n) => n.role === 'Master') ??
    plan.nodes[0];

  const vramFreeMb = targetNode?.safeVramMb ?? 0;
  const ramFreeMb = targetNode?.safeRamMb ?? 8192;
  const physicalCores = targetNode?.physicalCores ?? 8;
  const recommendedThreads =
    config.threads && config.threads > 0
      ? config.threads
      : Math.max(2, Math.min(16, isWindows ? Math.min(physicalCores, 12) : physicalCores));

  const accelerator = isWindows ? config.windowsAccelerator : config.linuxAccelerator;
  const usesGpu = !accelerator.startsWith('CPU');
  const kvCacheMb = plan.kvCacheTotalMb;

  const isNativeTernary = isNativeTernaryQuant(model.quant);
  const mbPerLayer = model.sizeMb / Math.max(1, model.layers);
  const effectiveLayerMb =
    model.architecture === 'Ternary'
      ? mbPerLayer * (isNativeTernary ? 1.18 : 1.06)
      : mbPerLayer;
  const wddmOrDriverReserve = isWindows ? 512 : 256;
  const effectiveVramKv = plan.autoNkvo ? 0 : Math.min(kvCacheMb, 1536);
  const mmprojReserve = model.mmprojSizeMb ?? 0;
  const gpuBudgetMb = usesGpu
    ? Math.max(0, vramFreeMb - effectiveVramKv - mmprojReserve - wddmOrDriverReserve)
    : 0;
  const autoNgl =
    model.architecture === 'Ternary' && !usesGpu
      ? 0
      : Math.min(model.layers, Math.max(0, Math.floor(gpuBudgetMb / Math.max(1, effectiveLayerMb))));
  const gpuOffloadLayers =
    config.gpuOffloadLayers !== null && config.gpuOffloadLayers !== undefined
      ? Math.max(0, Math.min(model.layers, config.gpuOffloadLayers))
      : autoNgl;

  const batchSize = config.batchSize ?? (usesGpu ? (model.architecture === 'Ternary' ? 512 : 1024) : 512);
  const ubatchSize = config.ubatchSize ?? 256;

  const workerRpcAddrs = plan.nodes
    .filter((n) => n.role === 'Worker' && n.allocatedLayers > 0 && !n.pruned)
    .map((n) => `${n.ip || '127.0.0.1'}:${n.rpcPort || 50052}`);

  const modelFileSlug = `${model.name.toLowerCase().replace(/[^a-z0-9.-]+/g, '-')}.${model.quant}.gguf`;
  const targetModelArg = model.isLocal && model.localPath ? model.localPath : `./models/${modelFileSlug}`;
  const effectiveMmproj = config.mmprojPath?.trim() || model.mmprojPath || '';
  const useFlashAttn = Boolean(config.enableFlashAttn && usesGpu && !isNativeTernary);
  // Quantized V-cache requires FlashAttention in stock llama.cpp; keep V-cache f16 when FA is off.
  const effectiveCtv: KvCacheQuantOption = useFlashAttn ? config.kvCacheQuant : 'f16';

  const cliFlags: string[] = [
    `-m "${targetModelArg}"`,
    `--host 0.0.0.0`,
    `--port 8080`,
    `-c ${config.contextLength}`,
    `-t ${recommendedThreads}`,
    `--threads-batch ${recommendedThreads}`,
    `-ngl ${gpuOffloadLayers}`,
    `-b ${batchSize}`,
    `-ub ${ubatchSize}`,
    `-ctk ${config.kvCacheQuant}`,
    `-ctv ${effectiveCtv}`,
  ];

  if (effectiveMmproj) {
    cliFlags.push(`--mmproj "${effectiveMmproj}"`);
  }
  if (config.enableJinja !== false) {
    cliFlags.push('--jinja');
  }
  if (useFlashAttn) {
    cliFlags.push('-fa on');
  }
  if (config.kvUnified !== false) {
    cliFlags.push('--kv-unified');
  }
  if (plan.autoNkvo) {
    cliFlags.push('-nkvo');
  }
  if (config.slotSavePath && config.slotSavePath.trim().length > 0) {
    cliFlags.push(`--slot-save-path "${config.slotSavePath.trim()}"`);
  }
  if (config.moeCpuOffload && model.architecture === 'MoE') {
    cliFlags.push('-ot ".ffn_.*_exps.=CPU"');
  }
  if (plan.useNoMmap) {
    cliFlags.push('-lm', 'none');
  }
  if (workerRpcAddrs.length > 0) {
    cliFlags.push(`--rpc ${workerRpcAddrs.join(',')}`);
  }
  if (plan.tensorSplit.length > 1) {
    cliFlags.push(`--tensor-split ${plan.tensorSplitCsv}`);
  }
  if (config.contBatching !== false) {
    cliFlags.push('--cont-batching');
  }
  if ((config.cacheReuse ?? 256) > 0) {
    cliFlags.push(`--cache-reuse ${config.cacheReuse ?? 256}`);
  }
  if (config.speculativeMode === 'external_draft' && config.draftModelPath?.trim()) {
    cliFlags.push(`--model-draft "${config.draftModelPath.trim()}"`);
    cliFlags.push(`--draft-max ${config.draftMaxTokens ?? 8}`);
  }

  const envVars: Array<{ key: string; value: string; reason: string }> = isWindows
    ? [
        {
          key: 'GGML_CUDA_NO_PINNED',
          value: ramFreeMb < 16384 ? '1' : '0',
          reason: 'Prevents WDDM pinned host allocation pressure on <= 16 GB Windows hosts',
        },
        {
          key: 'OMP_WAIT_POLICY',
          value: 'PASSIVE',
          reason: 'Prevents OpenMP spin-wait contention with WDDM compositor',
        },
      ]
    : [
        {
          key: 'GGML_CUDA_ENABLE_UNIFIED_MEMORY',
          value: '0',
          reason: 'Enforces strict VRAM cap without slow PCIe thrashing',
        },
        {
          key: 'MALLOC_ARENA_MAX',
          value: '2',
          reason: 'Reduces glibc heap fragmentation during multi-node shard loading',
        },
      ];

  const binaryName = isWindows ? '.\\bin\\llama-server.exe' : './bin/llama-server';
  const launchCommand = isWindows
    ? `${binaryName} ${cliFlags.join(' ')}`
    : `${config.linuxNumaInterleave ? 'numactl --interleave=all ' : ''}${binaryName} ${cliFlags.join(' ')}`;

  const scriptFileName = isWindows ? 'run-silica-win.ps1' : 'run-silica-linux.sh';

  const scriptContent = isWindows
    ? [
        `# Silica Cluster Windows Tuned Backend (${model.name})`,
        `# Stock llama.cpp v1.0.0 | Accelerator: ${accelerator} | WDDM Guard: -${wddmOrDriverReserve} MB`,
        `$ErrorActionPreference = "Stop"`,
        ...envVars.map((e) => `$env:${e.key} = "${e.value}"  # ${e.reason}`),
        ``,
        `# Or launch via pure-Rust clusterd master: .\\clusterd.exe master`,
        `$proc = Start-Process -FilePath "${binaryName}" -ArgumentList '${cliFlags.join(' ')}' -PassThru -NoNewWindow`,
        `$proc.PriorityClass = [System.Diagnostics.ProcessPriorityClass]::High`,
        `Write-Host "Silica Cluster OpenAI API running at http://127.0.0.1:8080/v1 (PID $($proc.Id))"`,
        `$proc.WaitForExit()`,
      ].join('\n')
    : [
        `#!/usr/bin/env bash`,
        `# Silica Cluster Linux Tuned Backend (${model.name})`,
        `# Stock llama.cpp v1.0.0 | Accelerator: ${accelerator}`,
        `set -euo pipefail`,
        ``,
        ...envVars.map((e) => `export ${e.key}="${e.value}" # ${e.reason}`),
        ``,
        `# Or launch via pure-Rust clusterd: ./clusterd --auto`,
        `exec ${launchCommand}`,
      ].join('\n');

  const optimizations: string[] = [
    plan.useNoMmap
      ? `Safe RAM pool >= 1.15x model need -> --no-mmap active for zero page-fault latency.`
      : `Pool below 1.15x safety margin -> mmap streaming active to guarantee zero OOM.`,
    plan.autoNkvo
      ? `CPU KV offload (-nkvo) active to protect <= 4 GB VRAM at ${config.contextLength.toLocaleString()} context.`
      : `Unified KV cache (--kv-unified, ${config.kvCacheQuant.toUpperCase()}, ${kvCacheMb} MB) with persistent prefix slots.`,
    effectiveMmproj
      ? `Vision projector (--mmproj) pinned strictly on Master host.`
      : `Stock --jinja tool-calling & reasoning_content streaming enabled.`,
  ];

  return {
    os,
    title: isWindows ? 'Windows Native Backend (llama.cpp v1.0.0)' : 'Linux Native Backend (llama.cpp v1.0.0)',
    accelerator,
    recommendedThreads,
    gpuOffloadLayers,
    vramSafetyReserveMb: wddmOrDriverReserve,
    ramSafetyReserveMb: 1024,
    batchSize,
    ubatchSize,
    kvCacheMb,
    contextShiftEnabled: config.enableContextShift !== false,
    expectedTokPerSec: plan.estimatedClusterTokPerSec,
    envVars,
    cliFlags,
    launchCommand,
    scriptFileName,
    scriptContent,
    optimizations,
  };
}
