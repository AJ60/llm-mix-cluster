import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import {
  computeAndroidSafeCap,
  computeDesktopSafeCap,
  computeVramSafeCap,
  evaluateNodeThermal,
  planStarCluster,
  scoreClusterNode,
  buildStarClusterNodes,
} from '../starClusterPlanner.ts';
import { GGUF_MODEL_PRESETS, StarClusterNode, GgufModelPreset, BackendTuningConfig } from '../../types/starCluster.ts';
import { createDefaultBackendTuningConfig } from '../backendTuner.ts';
import { analyzeClusterTuning, calculateAdaptiveLoadFactor } from '../tuningAdvisor.ts';
import { createInitialTelemetryState } from '../../service/nodeClusterService.ts';

describe('starClusterPlanner', () => {
  const model = GGUF_MODEL_PRESETS[0]; // Qwen3-30B-A3B MoE
  const config = createDefaultBackendTuningConfig(model);

  it('bounds Android RAM allocation safely to prevent LMK kills', () => {
    // 8 GB Android phone with 4 GB available
    const safeCap = computeAndroidSafeCap(8192, 4096, 50, 512);
    assert.ok(safeCap <= 8192 * 0.5, 'Must not exceed 50% total RAM');
    assert.ok(safeCap <= 4096 - 512, 'Must reserve at least 512 MB available headroom');
    assert.ok(safeCap >= 256, 'Must respect minimum floor');
  });

  it('enforces Desktop RAM and VRAM safety reserves', () => {
    const desktopCap = computeDesktopSafeCap(16384, 1024);
    assert.equal(desktopCap, 15360);

    // Windows VRAM reserves max(512 MB, 12%) for WDDM compositor safety
    const vram8Gb = computeVramSafeCap(8192, true);
    assert.ok(vram8Gb <= 8192 - 512);

    // Linux/Vulkan VRAM reserves max(256 MB, 8%)
    const vram16Gb = computeVramSafeCap(16384, false);
    assert.equal(vram16Gb, 16384 - Math.floor(16384 * 0.08));
  });

  it('calculates comparative node throughput scores', () => {
    const telemetry = createInitialTelemetryState(false);
    const nodes = buildStarClusterNodes(telemetry, 'VIRTUAL_4NODE', 0, config);
    const master = nodes.find((n) => n.role === 'Master')!;
    const worker = nodes.find((n) => n.platform === 'Android')!;

    const masterScore = scoreClusterNode(master);
    const workerScore = scoreClusterNode(worker);

    assert.ok(masterScore > 0);
    assert.ok(workerScore > 0);
    assert.ok(masterScore > workerScore, 'Master desktop node should outscore phone node');
  });

  it('evaluates thermal states and triggers progressive load shedding', () => {
    const telemetry = createInitialTelemetryState(false);
    const nodes = buildStarClusterNodes(telemetry, 'VIRTUAL_4NODE', 0, config);
    const worker = nodes.find((n) => n.platform === 'Android')!;

    // Test cool state (< 42C)
    const coolStatus = evaluateNodeThermal({ ...worker, tempC: 38.0 });
    assert.equal(coolStatus.state, 'COOL');
    assert.equal(coolStatus.capacityScale, 1.0);

    // Test warn state (>= 42C, < 45C)
    const warnStatus = evaluateNodeThermal({ ...worker, tempC: 43.0 });
    assert.equal(warnStatus.state, 'WARN');
    assert.equal(warnStatus.capacityScale, 0.9);

    // Test shed state (>= 45C, < 48C)
    const shedStatus = evaluateNodeThermal({ ...worker, tempC: 46.0 });
    assert.equal(shedStatus.state, 'SHED_25');
    assert.equal(shedStatus.capacityScale, 0.75);

    // Test emergency offload state (>= 48C)
    const critStatus = evaluateNodeThermal({ ...worker, tempC: 49.0 });
    assert.equal(critStatus.state, 'EMERGENCY_OFFLOAD');
    assert.equal(critStatus.capacityScale, 0);
  });

  it('plans star cluster with contiguous layer spans and valid pool allocation', () => {
    const telemetry = createInitialTelemetryState(false);
    const nodes = buildStarClusterNodes(telemetry, 'VIRTUAL_4NODE', 0, config);
    const plan = planStarCluster(nodes, model, undefined, config);

    assert.ok(plan.totalSafePoolMb > 0, 'Safe pool must be non-zero');
    assert.ok(plan.neededMb > 0, 'Model memory needed must be non-zero');
    assert.ok(['Fast', 'Best Fit', 'Streaming', 'Too Large'].includes(plan.fitVerdict));

    const activeNodes = plan.nodes.filter((n) => n.allocatedLayers > 0);
    assert.ok(activeNodes.length >= 1, 'At least one node must receive layers');

    // Verify layer continuity across nodes
    let expectedNextLayer = 0;
    for (const node of activeNodes) {
      assert.equal(node.layerStart, expectedNextLayer);
      assert.ok(node.layerEnd >= node.layerStart);
      expectedNextLayer = node.layerEnd + 1;
    }
    assert.equal(expectedNextLayer, model.layers, 'All model layers must be covered');
  });

  it('safely balances GPU offload layers (-ngl) and device --tensor-split under manual split without VRAM overflow', () => {
    const master: StarClusterNode = {
      nodeId: 'master-pc',
      label: 'Windows Master RTX 3050',
      role: 'Master',
      platform: 'Desktop',
      osType: 'windows',
      ip: '127.0.0.1',
      rpcPort: 50052,
      backendDriver: 'NVIDIA RTX 3050 Laptop',
      linkType: 'Local',
      totalRamMb: 32768,
      availableRamMb: 24000,
      vramFreeMb: 4096,
      vramTotalMb: 4096,
      memBwGbps: 195.0,
      computeTflops: 9.0,
      physicalCores: 8,
      rttMs: 0.1,
      pktLossPct: 0,
      bandwidthMbps: 10000,
      tempC: 42,
      cpuUsagePct: 15,
      gpuUsagePct: 5,
      handshakeVerified: true,
      heartbeatAgeS: 0,
      isConnected: true,
    };

    const worker: StarClusterNode = {
      nodeId: 'android-worker',
      label: 'Galaxy Tab A9+',
      role: 'Worker',
      platform: 'Android',
      osType: 'android',
      ip: '192.168.1.8',
      rpcPort: 50055,
      backendDriver: 'ARM NEON / Vulkan',
      linkType: 'Wi-Fi LAN',
      totalRamMb: 8192,
      availableRamMb: 4000,
      vramFreeMb: 0,
      vramTotalMb: 0,
      memBwGbps: 28.0,
      computeTflops: 1.8,
      physicalCores: 4,
      rttMs: 45.0,
      pktLossPct: 0,
      bandwidthMbps: 220,
      tempC: 32,
      cpuUsagePct: 10,
      gpuUsagePct: 0,
      handshakeVerified: true,
      heartbeatAgeS: 0,
      isConnected: true,
    };

    const moe35b: GgufModelPreset = {
      id: 'moe-35b',
      name: 'Qwen 35B MoE',
      quant: 'IQ4_XS',
      architecture: 'MoE',
      sizeMb: 17281,
      layers: 41,
      totalParamsB: 35,
      activeParamsB: 5.2,
      contextLengthDefault: 4096,
      maxContextLength: 262144,
      hfRepo: 'local/moe-35b',
      hfUrl: 'file://model.gguf',
      summary: '35B MoE',
      tags: ['moe'],
    };

    const manualConfig: BackendTuningConfig = {
      ...config,
      manualTensorSplit: '85, 15',
      contextLength: 4096,
    };

    const plan = planStarCluster([master, worker], moe35b, undefined, manualConfig);

    // Master gets 35 layers (85%), Worker gets 6 layers (15%)
    const plannedMaster = plan.nodes.find((n) => n.role === 'Master')!;
    const plannedWorker = plan.nodes.find((n) => n.role === 'Worker')!;
    assert.equal(plannedMaster.allocatedLayers, 35);
    assert.equal(plannedWorker.allocatedLayers, 6);

    // Master dedicated VRAM only holds 8 layers safely (no 15GB Shared Memory spillover)
    assert.equal(plannedMaster.nglLayers, 8);

    // CLI -ngl must be 14 (8 Master GPU + 6 Worker RPC)
    const nglIdx = plan.cliArgs.indexOf('-ngl');
    assert.ok(nglIdx >= 0);
    assert.equal(plan.cliArgs[nglIdx + 1], '14');

    // CLI --tensor-split must follow llama.cpp backend order [RPC0, CUDA0] -> [Worker (6), Master GPU (8)] = 42.9,57.1
    const tsIdx = plan.cliArgs.indexOf('--tensor-split');
    assert.ok(tsIdx >= 0);
    assert.equal(plan.cliArgs[tsIdx + 1], '42.9,57.1');

    // User-facing tensorSplit array must reflect overall 85% / 15% allocation
    assert.deepEqual(plan.tensorSplit, [85.0, 15.0]);

    // autoNkvo must be enabled to prevent KV cache from overflowing 4GB VRAM
    assert.equal(plan.autoNkvo, true);
    assert.ok(plan.cliArgs.includes('-nkvo'));
  });

  it('offloads all MoE Attention layers to 4GB VRAM with q8_0 KV and keeps Android CPU worker in cool Standby when Master fits solo', () => {
    const master3050: StarClusterNode = {
      nodeId: 'master-rtx3050',
      label: 'Windows Master (RTX 3050 4GB)',
      role: 'Master',
      platform: 'Desktop',
      osType: 'windows',
      ip: '127.0.0.1',
      rpcPort: 0,
      linkType: 'Local',
      totalRamMb: 32768,
      availableRamMb: 26000,
      vramTotalMb: 4096,
      vramFreeMb: 3960,
      memBwGbps: 224,
      rttMs: 0.1,
      pktLossPct: 0,
      tempC: 48,
      isConnected: true,
      backendDriver: 'CUDA 12.6',
      computeTflops: 8.9,
      physicalCores: 8,
    };
    const androidCpuPhone: StarClusterNode = {
      nodeId: 'android-s21fe',
      label: 'samsung SM-G990E (USB)',
      role: 'Worker',
      platform: 'Android',
      osType: 'android',
      ip: '127.0.0.1',
      rpcPort: 50055,
      linkType: 'USB-C Tunnel',
      totalRamMb: 7400,
      availableRamMb: 4200,
      vramTotalMb: 0,
      vramFreeMb: 0,
      memBwGbps: 38,
      rttMs: 2.5,
      pktLossPct: 0,
      tempC: 35,
      isConnected: true,
      backendDriver: 'CPU NEON',
      computeTflops: 0.4,
      physicalCores: 8,
      rpcNativeReady: true,
    };
    const moe35b = {
      ...GGUF_MODEL_PRESETS.find((m) => m.architecture === 'MoE')!,
      id: 'cyber-tiel-35b-a3b-iq4xs',
      name: 'Cyber-Tiel-Coder-35B-A3B-IQ4_XS',
      architecture: 'MoE' as const,
      sizeMb: 17281,
      layers: 41,
      totalParamsB: 35.5,
      activeParamsB: 3.0,
      headCount: 32,
      headCountKv: 4,
      embeddingLength: 2048,
    };

    const plan = planStarCluster([master3050, androidCpuPhone], moe35b, undefined, {
      ...createDefaultBackendTuningConfig(moe35b),
      contextLength: 4096,
      kvCacheQuant: 'q8_0',
      moeCpuOffload: true,
    });

    const masterPlanned = plan.nodes.find((n) => n.nodeId === 'master-rtx3050')!;
    const phonePlanned = plan.nodes.find((n) => n.nodeId === 'android-s21fe')!;

    // Android CPU worker receives 1 lightweight layer over fast USB-C tunnel to participate in compute safely
    assert.equal(phonePlanned.allocatedLayers, 1);
    assert.equal(masterPlanned.allocatedLayers, 40);

    // Master GPU offloads Attention layers + sparse experts on CPU
    const nglIdx = plan.cliArgs.indexOf('-ngl');
    assert.ok(nglIdx >= 0);
    assert.ok(Number(plan.cliArgs[nglIdx + 1]) >= 40);
    assert.ok(plan.cliArgs.includes('-ot'));
    assert.ok(plan.cliArgs.includes('.ffn_.*_exps.=CPU'));

    // With q8_0 KV at 4096 ctx, KV stays on GPU (no -nkvo slowdown)
    assert.equal(plan.autoNkvo, false);
    assert.ok(!plan.cliArgs.includes('-nkvo'));
  });

  it('optimizes Ternary Q2_0 GPU offload, explicit threads, reduced context, and Android worker sharding without 4GB VRAM overflow', () => {
    const master3050: StarClusterNode = {
      nodeId: 'master-rtx3050',
      label: 'RTX 3050 Laptop Master',
      role: 'Master',
      platform: 'Desktop',
      osType: 'windows',
      ip: '127.0.0.1',
      rpcPort: 0,
      totalRamMb: 32051,
      availableRamMb: 18000,
      vramTotalMb: 4096,
      vramFreeMb: 4096,
      memBwGbps: 192,
      linkType: 'Local',
      rttMs: 0.1,
      pktLossPct: 0,
      tempC: 45,
      isConnected: true,
      backendDriver: 'CUDA',
      computeTflops: 9.0,
      physicalCores: 8,
    };
    const coolAndroidWorker: StarClusterNode = {
      nodeId: 'android-usb-phone',
      label: 'Galaxy S22 (Android)',
      role: 'Worker',
      platform: 'Android',
      osType: 'android',
      ip: '127.0.0.1',
      rpcPort: 50055,
      totalRamMb: 8192,
      availableRamMb: 4600,
      vramTotalMb: 0,
      vramFreeMb: 0,
      memBwGbps: 38,
      linkType: 'USB-C Tunnel',
      rttMs: 1.5,
      pktLossPct: 0,
      tempC: 34,
      isConnected: true,
      backendDriver: 'CPU NEON',
      computeTflops: 2.2,
      physicalCores: 8,
      rpcNativeReady: true,
    };

    const ternaryQ2: GgufModelPreset = {
      id: 'ternary-bonsai-2-27b-q2',
      name: 'Ternary Bonsai 2 27B',
      architecture: 'Ternary',
      totalParamsB: 27,
      activeParamsB: 27,
      layers: 62,
      sizeMb: 8400,
      quant: 'Q2_0',
      contextLengthDefault: 4096,
      headCount: 32,
      headCountKv: 8,
      embeddingLength: 4096,
      hfRepo: 'test/ternary-bonsai-2-27b-gguf',
      hfUrl: 'https://huggingface.co/test/ternary-bonsai-2-27b-gguf',
      summary: 'Ternary 27B Q2_0',
    };

    const cfgQ2 = createDefaultBackendTuningConfig(ternaryQ2);
    assert.equal(cfgQ2.contextLength, 4096, 'Ternary default context must be 4096 for fast TTFT');
    assert.equal(cfgQ2.enableFlashAttn, true, 'Standard Q2_0 quant on Ternary must enable FlashAttention');
    assert.equal(cfgQ2.batchSize, 512, 'Ternary batch size should default to 512 to lower scratch VRAM');

    const planQ2 = planStarCluster([master3050, coolAndroidWorker], ternaryQ2, undefined, cfgQ2);
    const phonePlanned = planQ2.nodes.find((n) => n.nodeId === 'android-usb-phone')!;
    const masterPlanned = planQ2.nodes.find((n) => n.nodeId === 'master-rtx3050')!;

    // Cool Android worker must actively participate (>= 3 layers), not sit at 0L
    assert.ok(phonePlanned.allocatedLayers >= 3, `Expected >=3 Android layers, got ${phonePlanned.allocatedLayers}`);
    assert.equal(masterPlanned.allocatedLayers + phonePlanned.allocatedLayers, 62);

    // Explicit physical thread count (-t and --threads-batch) must always be emitted
    const tIdx = planQ2.cliArgs.indexOf('-t');
    const tbIdx = planQ2.cliArgs.indexOf('--threads-batch');
    assert.ok(tIdx >= 0 && Number(planQ2.cliArgs[tIdx + 1]) === 8);
    assert.ok(tbIdx >= 0 && Number(planQ2.cliArgs[tbIdx + 1]) === 8);

    // FlashAttention + quantized V cache enabled for standard Q2_0
    assert.ok(planQ2.cliArgs.includes('-fa') && planQ2.cliArgs[planQ2.cliArgs.indexOf('-fa') + 1] === 'on');
    assert.equal(planQ2.cliArgs[planQ2.cliArgs.indexOf('-ctv') + 1], cfgQ2.kvCacheQuant);

    // Master GPU layers must stay safely inside 4 GB VRAM after CUDA scratch & KV reserve
    assert.ok(masterPlanned.nglLayers > 0 && masterPlanned.nglLayers <= 24);

    // Native TQ1_0 quant must disable FlashAttention and force -ctv f16 safely
    const ternaryNative: GgufModelPreset = { ...ternaryQ2, id: 'ternary-tq1', quant: 'TQ1_0' };
    const cfgNative = createDefaultBackendTuningConfig(ternaryNative);
    assert.equal(cfgNative.enableFlashAttn, false);
    const planNative = planStarCluster([master3050, coolAndroidWorker], ternaryNative, undefined, cfgNative);
    assert.ok(!planNative.cliArgs.includes('-fa'));
    assert.equal(planNative.cliArgs[planNative.cliArgs.indexOf('-ctv') + 1], 'f16');
  });

  it('analyzeClusterTuning flags oversized context, unpinned threads, disabled FA, and generates thermal-aware Android worker configs', () => {
    const master3050: StarClusterNode = {
      nodeId: 'master-rtx3050',
      label: 'RTX 3050 Laptop Master',
      role: 'Master',
      platform: 'Desktop',
      osType: 'windows',
      ip: '127.0.0.1',
      rpcPort: 0,
      totalRamMb: 32051,
      availableRamMb: 18000,
      vramTotalMb: 4096,
      vramFreeMb: 4096,
      memBwGbps: 192,
      linkType: 'Local',
      rttMs: 0.1,
      pktLossPct: 0,
      tempC: 44,
      isConnected: true,
      backendDriver: 'CUDA',
      computeTflops: 9.0,
      physicalCores: 8,
    };
    const coolPhone: StarClusterNode = {
      nodeId: 'android-cool',
      label: 'Cool Phone',
      role: 'Worker',
      platform: 'Android',
      osType: 'android',
      ip: '127.0.0.1',
      rpcPort: 50055,
      totalRamMb: 8192,
      availableRamMb: 4800,
      vramTotalMb: 0,
      vramFreeMb: 0,
      memBwGbps: 38,
      linkType: 'USB-C Tunnel',
      rttMs: 1.5,
      pktLossPct: 0,
      tempC: 33,
      isConnected: true,
      backendDriver: 'CPU NEON',
      computeTflops: 2.2,
      physicalCores: 8,
      rpcNativeReady: true,
    };
    const warmPhone: StarClusterNode = {
      ...coolPhone,
      nodeId: 'android-warm',
      label: 'Warm Phone',
      tempC: 43,
    };

    const ternaryQ2: GgufModelPreset = {
      id: 'ternary-bonsai-2-27b-q2',
      name: 'Ternary Bonsai 2 27B',
      architecture: 'Ternary',
      totalParamsB: 27,
      activeParamsB: 27,
      layers: 62,
      sizeMb: 8400,
      quant: 'Q2_0',
      contextLengthDefault: 4096,
      headCount: 32,
      headCountKv: 8,
      embeddingLength: 4096,
      hfRepo: 'test/ternary-bonsai-2-27b-gguf',
      hfUrl: 'https://huggingface.co/test/ternary-bonsai-2-27b-gguf',
      summary: 'Ternary 27B Q2_0',
    };

    const badConfig: BackendTuningConfig = {
      ...createDefaultBackendTuningConfig(ternaryQ2),
      contextLength: 16384,
      threads: null,
      enableFlashAttn: false,
      batchSize: 1024,
    };

    const plan = planStarCluster([master3050, coolPhone, warmPhone], ternaryQ2, undefined, badConfig);
    const advice = analyzeClusterTuning(plan, ternaryQ2, badConfig);

    assert.ok(advice.healthScore < 70);
    assert.equal(advice.autoTunePatch.contextLength, 4096);
    assert.equal(advice.autoTunePatch.threads, 8);
    assert.equal(advice.autoTunePatch.enableFlashAttn, true);
    assert.ok(advice.items.some((i) => i.id === 'reduce-context-ttft'));
    assert.ok(advice.items.some((i) => i.id === 'enable-ternary-fa'));
    assert.ok(advice.workerConfigs['android-cool'].recommendedThreads >= 5);
    assert.equal(advice.workerConfigs['android-cool'].adaptiveLoadFactor, 1.0);
    assert.equal(advice.workerConfigs['android-cool'].concurrencyCapped, false);
    assert.equal(advice.workerConfigs['android-warm'].recommendedThreads, 2);
    assert.equal(advice.workerConfigs['android-warm'].concurrencyCapped, true);
    assert.ok(advice.workerConfigs['android-warm'].adaptiveLoadFactor < 1.0);
    assert.ok(advice.clusterAdaptiveLoadFactor < 1.0);
  });

  it('calculateAdaptiveLoadFactor and analyzeClusterTuning boost priority/concurrency only below 40C and shed layers above 48C', () => {
    // 1. Strictly below 40.0°C (39.8°C, 39.9°C) -> ALF 1.0, uncapped concurrency
    const cool = calculateAdaptiveLoadFactor({ tempC: 36.5, internalTempC: 39.8, batteryTempC: 35.0 }, 6);
    assert.equal(cool.adaptiveLoadFactor, 1.0);
    assert.equal(cool.concurrencyCapped, false);
    assert.equal(cool.concurrencyCap, 6);
    assert.equal(cool.band, 'nominal');

    const sub40 = calculateAdaptiveLoadFactor({ tempC: 39.9 }, 6);
    assert.equal(sub40.adaptiveLoadFactor, 1.0);
    assert.equal(sub40.concurrencyCapped, false);
    assert.equal(sub40.concurrencyCap, 6);
    assert.equal(sub40.band, 'nominal');

    // 2. At exact 40.0°C -> NOT below 40°C, so concurrency must be capped and priority never boosted
    const boundary40 = calculateAdaptiveLoadFactor({ tempC: 40.0 }, 6);
    assert.equal(boundary40.band, 'warm_cap');
    assert.equal(boundary40.concurrencyCapped, true);
    assert.ok(boundary40.adaptiveLoadFactor < 1.0);
    assert.ok(boundary40.concurrencyCap < 6);

    // 3. Above 40.0°C -> ALF < 1.0 and concurrency strictly capped below baseConcurrency
    const warm41 = calculateAdaptiveLoadFactor({ tempC: 38.0, internalTempC: 41.0 }, 6);
    assert.equal(warm41.effectiveTempC, 41.0);
    assert.equal(warm41.concurrencyCapped, true);
    assert.ok(warm41.adaptiveLoadFactor < 1.0 && warm41.adaptiveLoadFactor > 0.7);
    assert.ok(warm41.concurrencyCap < 6);

    // 4. Monotonic reduction as heat climbs from 41°C -> 45°C -> 49.5°C -> 52.5°C
    const warm45 = calculateAdaptiveLoadFactor({ tempC: 45.0 }, 6);
    const hot49 = calculateAdaptiveLoadFactor({ tempC: 49.5 }, 6);
    const crit52 = calculateAdaptiveLoadFactor({ tempC: 52.5 }, 6);

    assert.ok(warm45.adaptiveLoadFactor < warm41.adaptiveLoadFactor);
    assert.ok(hot49.adaptiveLoadFactor < warm45.adaptiveLoadFactor);
    assert.equal(hot49.band, 'hot_throttle');
    assert.ok(hot49.concurrencyCap <= 2);

    assert.equal(crit52.band, 'critical_shed');
    assert.equal(crit52.adaptiveLoadFactor, 0.0);
    assert.equal(crit52.concurrencyCap, 1);

    // 5. End-to-end cluster tuning + planner check: <40C gets high priority & higher concurrency; >48C sheds layers automatically
    const master3050: StarClusterNode = {
      nodeId: 'master-rtx3050',
      label: 'RTX 3050 Master',
      role: 'Master',
      platform: 'Desktop',
      osType: 'windows',
      ip: '127.0.0.1',
      rpcPort: 50052,
      totalRamMb: 16384,
      availableRamMb: 12288,
      vramTotalMb: 4096,
      vramFreeMb: 4096,
      memBwGbps: 224,
      linkType: 'Local',
      rttMs: 0.1,
      pktLossPct: 0,
      tempC: 54,
      isConnected: true,
      backendDriver: 'CUDA 12.6',
      computeTflops: 9.1,
      physicalCores: 8,
    };
    const ternaryQ2 = GGUF_MODEL_PRESETS.find((m) => m.architecture === 'Ternary')!;
    const coolWorker: StarClusterNode = {
      nodeId: 'android-cool-sub40',
      label: 'Cool Phone',
      role: 'Worker',
      platform: 'Android',
      osType: 'android',
      ip: '127.0.0.1',
      rpcPort: 50055,
      totalRamMb: 8192,
      availableRamMb: 4800,
      vramTotalMb: 0,
      vramFreeMb: 0,
      memBwGbps: 38,
      linkType: 'USB-C Tunnel',
      rttMs: 1.5,
      pktLossPct: 0,
      tempC: 36.0,
      isConnected: true,
      backendDriver: 'CPU NEON',
      computeTflops: 2.2,
      physicalCores: 8,
      rpcNativeReady: true,
    };
    const exact40Worker: StarClusterNode = {
      ...coolWorker,
      nodeId: 'android-exact-40',
      label: 'Boundary40 Phone',
      tempC: 40.0,
    };
    const over48Worker: StarClusterNode = {
      ...coolWorker,
      nodeId: 'android-over-48',
      label: 'Hot49 Phone',
      tempC: 48.5,
    };

    const plan48 = planStarCluster([master3050, coolWorker, exact40Worker, over48Worker], ternaryQ2);
    const advice48 = analyzeClusterTuning(plan48, ternaryQ2, createDefaultBackendTuningConfig(ternaryQ2));

    assert.equal(advice48.workerConfigs['android-cool-sub40'].threadPriority, 'high');
    assert.equal(advice48.workerConfigs['android-cool-sub40'].concurrencyCapped, false);
    assert.equal(advice48.workerConfigs['android-cool-sub40'].recommendedThreads, 6);

    assert.notEqual(advice48.workerConfigs['android-exact-40'].threadPriority, 'high');
    assert.equal(advice48.workerConfigs['android-exact-40'].concurrencyCapped, true);
    assert.ok(advice48.workerConfigs['android-exact-40'].recommendedThreads < 6);

    const plannedHot = plan48.nodes.find((n) => n.nodeId === 'android-over-48')!;
    assert.equal(plannedHot.allocatedLayers, 0);
    assert.equal(plannedHot.thermalStatus.state, 'EMERGENCY_OFFLOAD');
    assert.equal(advice48.workerConfigs['android-over-48'].threadPriority, 'low');
    assert.ok(advice48.workerConfigs['android-over-48'].recommendedThreads <= 2);
    assert.ok(advice48.items.some((i) => i.id === 'adaptive-load-cap-android-over-48' && i.severity === 'critical'));
  });
});
