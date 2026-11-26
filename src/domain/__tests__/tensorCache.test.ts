import { test, describe, beforeEach, afterEach } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'fs';
import path from 'path';
import os from 'os';
import {
  hasLayerCached,
  checkLayerRangeCached,
  putLayerCacheData,
  readLayerCacheData,
  verifyOrStreamLayerShard,
  getTensorCacheStats,
  clearTensorCache,
  resetTensorCacheStats,
  sanitizeModelId,
} from '../tensorCache';

describe('TensorCache: Local File-System Verification & -c Cache Flags', () => {
  let tempCacheDir: string;

  beforeEach(() => {
    tempCacheDir = fs.mkdtempSync(path.join(os.tmpdir(), 'silica_test_cache_'));
    resetTensorCacheStats();
  });

  afterEach(() => {
    try {
      if (fs.existsSync(tempCacheDir)) {
        fs.rmSync(tempCacheDir, { recursive: true, force: true });
      }
    } catch {
      // ignore
    }
  });

  test('sanitizeModelId generates clean filesystem tokens', () => {
    assert.equal(
      sanitizeModelId('Cyber-Tiel-Coder-35B-A3B-MTP-UD-IQ4_XS.gguf'),
      'cyber-tiel-coder-35b-a3b-mtp-ud-iq4_xs'
    );
    assert.equal(
      sanitizeModelId('C:\\Users\\model\\qwen2.5-7b-instruct-q4_k_m.gguf'),
      'qwen2_5-7b-instruct-q4_k_m'
    );
  });

  test('hasLayerCached returns false on initial cache miss', () => {
    const isCached = hasLayerCached('test-model-35b', 0, tempCacheDir);
    assert.equal(isCached, false);
  });

  test('putLayerCacheData saves layer bytes and enables hasLayerCached to return true', () => {
    const mockWeights = Buffer.from('FAKE_GGML_LAYER_TENSOR_WEIGHTS_FOR_TESTING');
    const saved = putLayerCacheData('test-model-35b', 4, mockWeights, tempCacheDir);

    assert.equal(saved.bytes, mockWeights.length);
    assert.ok(fs.existsSync(saved.filePath));

    // Now hasLayerCached must report true
    const isCached = hasLayerCached('test-model-35b', 4, tempCacheDir);
    assert.equal(isCached, true);

    // Reading data must match
    const readBack = readLayerCacheData('test-model-35b', 4, tempCacheDir);
    assert.ok(readBack);
    assert.equal(readBack.toString(), mockWeights.toString());
  });

  test('checkLayerRangeCached identifies partial and fully cached layer spans', () => {
    const mockData = Buffer.alloc(1024 * 1024, 0x42); // 1 MB per layer
    // Seed layers 2 and 3 out of [1..4]
    putLayerCacheData('my-sparse-moe', 2, mockData, tempCacheDir);
    putLayerCacheData('my-sparse-moe', 3, mockData, tempCacheDir);

    const partial = checkLayerRangeCached('my-sparse-moe', 1, 4, tempCacheDir);
    assert.equal(partial.totalLayers, 4);
    assert.deepEqual(partial.cachedLayers, [2, 3]);
    assert.deepEqual(partial.missingLayers, [1, 4]);
    assert.equal(partial.allCached, false);
    assert.equal(partial.hitRatePct, 50);
    assert.equal(partial.tensorResidency, 'Partial Cache');
    assert.equal(partial.bandwidthSavedMb, 2);

    // Seed missing layers 1 and 4
    putLayerCacheData('my-sparse-moe', 1, mockData, tempCacheDir);
    putLayerCacheData('my-sparse-moe', 4, mockData, tempCacheDir);

    const full = checkLayerRangeCached('my-sparse-moe', 1, 4, tempCacheDir);
    assert.equal(full.allCached, true);
    assert.equal(full.hitRatePct, 100);
    assert.equal(full.tensorResidency, 'Flash Cache');
    assert.deepEqual(full.missingLayers, []);
    assert.ok(full.estimatedLaunchTimeMs < 200, 'Instant launch < 200ms when fully cached');
  });

  test('verifyOrStreamLayerShard eliminates redundant network transfer on cache hit', async () => {
    let networkTransferInvoked = false;
    const streamSource = async () => {
      networkTransferInvoked = true;
      return Buffer.from('STREAMED_OVER_NETWORK_RAW_WEIGHTS');
    };

    // First run: Cache miss -> streams over network
    const firstRun = await verifyOrStreamLayerShard({
      modelId: 'cyber-tiel-35b',
      layerIdx: 12,
      streamSource,
      customDir: tempCacheDir,
    });
    assert.equal(firstRun.fromCache, false);
    assert.equal(networkTransferInvoked, true);

    // Second run: Cache hit -> MUST NOT invoke streamSource
    networkTransferInvoked = false;
    const secondRun = await verifyOrStreamLayerShard({
      modelId: 'cyber-tiel-35b',
      layerIdx: 12,
      streamSource,
      customDir: tempCacheDir,
    });
    assert.equal(secondRun.fromCache, true);
    assert.equal(networkTransferInvoked, false, 'Redundant streaming transfer MUST be eliminated');
    assert.equal(secondRun.bytes, firstRun.bytes);
  });

  test('getTensorCacheStats and clearTensorCache manage local storage lifecycle', () => {
    const chunk = Buffer.alloc(512 * 1024, 0x11);
    putLayerCacheData('model-a', 0, chunk, tempCacheDir);
    putLayerCacheData('model-a', 1, chunk, tempCacheDir);

    const stats = getTensorCacheStats(tempCacheDir);
    assert.equal(stats.layerCount, 2);
    assert.ok(stats.totalMb >= 1.0);
    assert.ok(stats.statusLabel.includes('Active'));

    const cleared = clearTensorCache(tempCacheDir);
    assert.ok(cleared.deletedFiles >= 2);
    assert.ok(cleared.freedBytes >= 1024 * 1024);

    const afterStats = getTensorCacheStats(tempCacheDir);
    assert.equal(afterStats.layerCount, 0);
    assert.equal(afterStats.totalBytes, 0);
  });
});
