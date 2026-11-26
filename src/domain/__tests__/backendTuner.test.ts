import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import {
  buildTunedOsBackendProfile,
  createDefaultBackendTuningConfig,
  estimateKvCacheMb,
} from '../backendTuner.ts';
import { GGUF_MODEL_PRESETS } from '../../types/starCluster.ts';
import {
  buildStarClusterNodes,
  planStarCluster,
} from '../starClusterPlanner.ts';
import { createInitialTelemetryState } from '../../service/nodeClusterService.ts';

describe('backendTuner', () => {
  const moeModel = GGUF_MODEL_PRESETS.find((m) => m.architecture === 'MoE')!;
  const ternaryModel = GGUF_MODEL_PRESETS.find((m) => m.architecture === 'Ternary')!;
  const denseModel = GGUF_MODEL_PRESETS.find((m) => m.architecture === 'Dense')!;

  it('creates default backend config tailored to model architecture', () => {
    const moeConfig = createDefaultBackendTuningConfig(moeModel);
    assert.equal(moeConfig.moeCpuOffload, true);
    assert.equal(moeConfig.enableFlashAttn, true);

    const ternaryConfig = createDefaultBackendTuningConfig(ternaryModel);
    assert.equal(ternaryConfig.enableFlashAttn, false);
    assert.equal(ternaryConfig.moeCpuOffload, false);

    const denseConfig = createDefaultBackendTuningConfig(denseModel);
    assert.equal(denseConfig.moeCpuOffload, false);
    assert.equal(denseConfig.enableFlashAttn, true);
  });

  it('accurately estimates KV cache size across context and quantization', () => {
    const kv16 = estimateKvCacheMb(48, 16384, 'f16', 30.5);
    const kvQ8 = estimateKvCacheMb(48, 16384, 'q8_0', 30.5);
    const kvQ4 = estimateKvCacheMb(48, 16384, 'q4_0', 30.5);

    assert.ok(kv16 > kvQ8, 'f16 KV cache must be larger than q8_0');
    assert.ok(kvQ8 > kvQ4, 'q8_0 KV cache must be larger than q4_0');
    assert.ok(kvQ4 >= 64, 'minimum KV cache floor must be respected');
  });

  it('generates hardened Windows PowerShell launcher with safety reserves and firewall rules', () => {
    const telemetry = createInitialTelemetryState(false);
    const config = createDefaultBackendTuningConfig(moeModel);
    const nodes = buildStarClusterNodes(telemetry, 'VIRTUAL_4NODE', 0, config);
    const plan = planStarCluster(nodes, moeModel, undefined, config);

    const profile = buildTunedOsBackendProfile('windows', moeModel, plan, config);

    assert.equal(profile.os, 'windows');
    assert.equal(profile.scriptFileName, 'run-silica-win.ps1');
    assert.ok(profile.vramSafetyReserveMb >= 450, 'WDDM safety reserve must be >= 450 MB');
    assert.ok(profile.scriptContent.includes('OMP_WAIT_POLICY'), 'Must set OMP_WAIT_POLICY');
    assert.ok(profile.scriptContent.includes('GGML_CUDA_NO_PINNED'), 'Must cap WDDM pinned host allocations');
    assert.ok(profile.cliFlags.some((f) => f.includes('-ot ".ffn_.*_exps.=CPU"')), 'Must offload MoE experts when enabled');
  });

  it('generates hardened Linux Bash launcher with kernel NUMA and memory safeguards', () => {
    const telemetry = createInitialTelemetryState(false);
    const config = createDefaultBackendTuningConfig(moeModel);
    const nodes = buildStarClusterNodes(telemetry, 'VIRTUAL_4NODE', 0, config);
    const plan = planStarCluster(nodes, moeModel, undefined, config);

    const profile = buildTunedOsBackendProfile('linux', moeModel, plan, config);

    assert.equal(profile.os, 'linux');
    assert.equal(profile.scriptFileName, 'run-silica-linux.sh');
    assert.ok(profile.scriptContent.includes('GGML_CUDA_ENABLE_UNIFIED_MEMORY="0"'), 'Must block unified memory thrashing');
    assert.ok(profile.scriptContent.includes('numactl --interleave=all'), 'Must interleave NUMA channels');
    assert.ok(profile.scriptContent.includes('MALLOC_ARENA_MAX'), 'Must cap glibc heap fragmentation');
  });
});
