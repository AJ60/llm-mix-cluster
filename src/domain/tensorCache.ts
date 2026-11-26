import fs from 'fs';
import path from 'path';
import os from 'os';

export interface LayerCacheVerificationResult {
  modelId: string;
  layerStart: number;
  layerEnd: number;
  totalLayers: number;
  cachedLayers: number[];
  missingLayers: number[];
  allCached: boolean;
  hitRatePct: number;
  cachedBytes: number;
  bandwidthSavedMb: number;
  tensorResidency: 'Flash Cache' | 'Partial Cache' | 'Network Stream Needed';
  estimatedLaunchTimeMs: number;
}

export interface TensorCacheStats {
  dir: string;
  totalFiles: number;
  totalBytes: number;
  totalMb: number;
  layerCount: number;
  hitCount: number;
  bandwidthSavedMb: number;
  enabled: boolean;
  statusLabel: string;
}

// In-memory session telemetry for cache hit tracking
let sessionCacheHits = 0;
let sessionBandwidthSavedBytes = 0;

export function resetTensorCacheStats(): void {
  sessionCacheHits = 0;
  sessionBandwidthSavedBytes = 0;
}

/**
 * Returns the deterministic local flash directory where layer tensors are persisted.
 * Aligns with Cake / Silica specification: ~/.cache/silica_tensor_cache or local .silica_tensor_cache
 */
export function getTensorCacheDir(customDir?: string): string {
  if (customDir && customDir.trim()) {
    const resolved = path.resolve(customDir.trim());
    if (!fs.existsSync(resolved)) {
      fs.mkdirSync(resolved, { recursive: true });
    }
    return resolved;
  }

  const envDir =
    process.env.SILICA_TENSOR_CACHE_DIR ||
    process.env.SILICA_CACHE_DIR ||
    process.env.XDG_CACHE_HOME;

  let baseDir: string;
  if (envDir && envDir.trim()) {
    baseDir = path.resolve(envDir.trim(), 'silica_tensor_cache');
  } else if (process.platform === 'win32' && process.env.LOCALAPPDATA) {
    baseDir = path.join(process.env.LOCALAPPDATA, 'silica', 'tensor_cache');
  } else {
    baseDir = path.join(os.homedir(), '.cache', 'silica_tensor_cache');
  }

  if (!fs.existsSync(baseDir)) {
    try {
      fs.mkdirSync(baseDir, { recursive: true });
    } catch {
      // Fallback to local working directory cache
      baseDir = path.resolve(process.cwd(), 'silica_tensor_cache');
      if (!fs.existsSync(baseDir)) {
        fs.mkdirSync(baseDir, { recursive: true });
      }
    }
  }

  return baseDir;
}

/**
 * Sanitizes model identifier into a clean filesystem token.
 */
export function sanitizeModelId(modelId: string): string {
  return modelId
    .replace(/^.*[/\\]/, '')
    .replace(/\.gguf$/i, '')
    .toLowerCase()
    .replace(/[^a-z0-9_-]+/g, '_')
    .slice(0, 64);
}

/**
 * Returns canonical file path for a cached layer shard.
 */
export function getLayerCacheFilePath(
  modelId: string,
  layerIdx: number,
  customDir?: string
): string {
  const dir = getTensorCacheDir(customDir);
  const safe = sanitizeModelId(modelId);
  return path.join(dir, `${safe}_layer_${layerIdx}.bin`);
}

/**
 * Scans candidate filename patterns on the local filesystem for a cached layer.
 * Supports standard Silica binary shards (.bin), GGUF shards (.gguf), and ggml-rpc cache layout.
 */
export function findCachedLayerFile(
  modelId: string,
  layerIdx: number,
  customDir?: string
): string | null {
  const dir = getTensorCacheDir(customDir);
  const safe = sanitizeModelId(modelId);

  const candidates = [
    path.join(dir, `${safe}_layer_${layerIdx}.bin`),
    path.join(dir, `${safe}_L${layerIdx}.bin`),
    path.join(dir, `${safe}.layer_${layerIdx}.gguf`),
    path.join(dir, `${safe}_shard_${layerIdx}.bin`),
    path.join(dir, safe, `layer_${layerIdx}.bin`),
    path.join(dir, `layer_${layerIdx}.bin`),
  ];

  for (const c of candidates) {
    try {
      if (fs.existsSync(c)) {
        const st = fs.statSync(c);
        if (st.isFile() && st.size > 0) {
          return c;
        }
      }
    } catch {
      // ignore
    }
  }

  return null;
}

/**
 * Verifies whether a specific layer tensor is already resident in local flash cache.
 * Before streaming, checking this function eliminates redundant multi-hundred-MB network transfers.
 */
export function hasLayerCached(
  modelId: string,
  layerIdx: number,
  customDir?: string
): boolean {
  const file = findCachedLayerFile(modelId, layerIdx, customDir);
  if (!file) return false;

  try {
    const st = fs.statSync(file);
    if (st.size > 0) {
      sessionCacheHits++;
      sessionBandwidthSavedBytes += st.size;
      return true;
    }
  } catch {
    // ignore
  }

  return false;
}

/**
 * Verifies contiguous layer spans [layerStart..layerEnd] across local flash storage.
 * Evaluates whether 0 network re-transfers are required for instant <200ms node launch.
 */
export function checkLayerRangeCached(
  modelId: string,
  layerStart: number,
  layerEnd: number,
  customDir?: string
): LayerCacheVerificationResult {
  if (layerEnd < layerStart || layerStart < 0) {
    return {
      modelId,
      layerStart,
      layerEnd,
      totalLayers: 0,
      cachedLayers: [],
      missingLayers: [],
      allCached: true,
      hitRatePct: 100,
      cachedBytes: 0,
      bandwidthSavedMb: 0,
      tensorResidency: 'Flash Cache',
      estimatedLaunchTimeMs: 50,
    };
  }

  const totalLayers = layerEnd - layerStart + 1;
  const cachedLayers: number[] = [];
  const missingLayers: number[] = [];
  let cachedBytes = 0;

  for (let idx = layerStart; idx <= layerEnd; idx++) {
    const file = findCachedLayerFile(modelId, idx, customDir);
    if (file) {
      cachedLayers.push(idx);
      try {
        cachedBytes += fs.statSync(file).size;
      } catch {
        // ignore
      }
    } else {
      missingLayers.push(idx);
    }
  }

  const allCached = missingLayers.length === 0;
  const hitRatePct = totalLayers > 0 ? Math.round((cachedLayers.length / totalLayers) * 100) : 100;
  const bandwidthSavedMb = Math.round((cachedBytes / (1024 * 1024)) * 10) / 10;

  let tensorResidency: LayerCacheVerificationResult['tensorResidency'] = 'Flash Cache';
  if (missingLayers.length === totalLayers) {
    tensorResidency = 'Network Stream Needed';
  } else if (missingLayers.length > 0) {
    tensorResidency = 'Partial Cache';
  }

  // Instant launch (<200ms) when 100% cached; network transfer latency estimate otherwise
  const estimatedLaunchTimeMs = allCached
    ? Math.min(180, Math.max(30, totalLayers * 8))
    : Math.max(400, missingLayers.length * 250);

  return {
    modelId,
    layerStart,
    layerEnd,
    totalLayers,
    cachedLayers,
    missingLayers,
    allCached,
    hitRatePct,
    cachedBytes,
    bandwidthSavedMb,
    tensorResidency,
    estimatedLaunchTimeMs,
  };
}

/**
 * Atomically writes streamed layer weights to local flash storage and registers shard metadata.
 */
export function putLayerCacheData(
  modelId: string,
  layerIdx: number,
  data: Buffer | Uint8Array,
  customDir?: string
): { filePath: string; bytes: number } {
  const filePath = getLayerCacheFilePath(modelId, layerIdx, customDir);
  const tempPath = `${filePath}.tmp.${Date.now()}`;

  fs.writeFileSync(tempPath, data);
  fs.renameSync(tempPath, filePath);

  // Write compact metadata manifest
  const dir = path.dirname(filePath);
  const safe = sanitizeModelId(modelId);
  const metaPath = path.join(dir, `${safe}_layer_${layerIdx}.meta`);
  const metaContent = [
    `modelId=${modelId}`,
    `layerIdx=${layerIdx}`,
    `bytes=${data.length}`,
    `cachedAt=${new Date().toISOString()}`,
    `source=Silica-Stream-Engine`,
  ].join('\n');

  try {
    fs.writeFileSync(metaPath, metaContent, 'utf8');
  } catch {
    // non-fatal
  }

  return { filePath, bytes: data.length };
}

/**
 * Reads local cached layer data if resident.
 */
export function readLayerCacheData(
  modelId: string,
  layerIdx: number,
  customDir?: string
): Buffer | null {
  const file = findCachedLayerFile(modelId, layerIdx, customDir);
  if (!file) return null;

  try {
    const data = fs.readFileSync(file);
    sessionCacheHits++;
    sessionBandwidthSavedBytes += data.length;
    return data;
  } catch {
    return null;
  }
}

/**
 * Pre-streaming execution hook: checks hasLayerCached(modelId, layerIdx) before initiating network transfer.
 * Eliminates redundant streaming when shard is already present in persistent flash cache.
 */
export async function verifyOrStreamLayerShard(params: {
  modelId: string;
  layerIdx: number;
  streamSource: () => Promise<Buffer | Uint8Array>;
  customDir?: string;
  onHit?: (layerIdx: number, filePath: string, sizeBytes: number) => void;
  onMiss?: (layerIdx: number) => void;
}): Promise<{ fromCache: boolean; filePath: string; bytes: number }> {
  const { modelId, layerIdx, streamSource, customDir, onHit, onMiss } = params;

  // 1. Verify existing flash cache first
  const existingFile = findCachedLayerFile(modelId, layerIdx, customDir);
  if (existingFile) {
    try {
      const st = fs.statSync(existingFile);
      if (st.size > 0) {
        sessionCacheHits++;
        sessionBandwidthSavedBytes += st.size;
        onHit?.(layerIdx, existingFile, st.size);
        return {
          fromCache: true,
          filePath: existingFile,
          bytes: st.size,
        };
      }
    } catch {
      // continue to stream if read failed
    }
  }

  // 2. Cache miss: trigger streaming transfer from Master
  onMiss?.(layerIdx);
  const data = await streamSource();

  // 3. Persist to local flash cache for 0-transfer subsequent runs
  const saved = putLayerCacheData(modelId, layerIdx, data, customDir);
  return {
    fromCache: false,
    filePath: saved.filePath,
    bytes: saved.bytes,
  };
}

/**
 * Retrieves aggregate statistics across the persistent tensor cache.
 */
export function getTensorCacheStats(customDir?: string): TensorCacheStats {
  const dir = getTensorCacheDir(customDir);
  let totalBytes = 0;
  let totalFiles = 0;
  let shardCount = 0;

  try {
    if (fs.existsSync(dir)) {
      const entries = fs.readdirSync(dir);
      for (const entry of entries) {
        const full = path.join(dir, entry);
        try {
          const st = fs.statSync(full);
          if (st.isFile()) {
            totalFiles++;
            totalBytes += st.size;
            if (entry.endsWith('.bin') || entry.endsWith('.gguf')) {
              shardCount++;
            }
          }
        } catch {
          // ignore
        }
      }
    }
  } catch {
    // ignore
  }

  const totalMb = Math.round((totalBytes / (1024 * 1024)) * 10) / 10;
  const savedMb = Math.round((sessionBandwidthSavedBytes / (1024 * 1024)) * 10) / 10;

  let statusLabel = 'Ready (auto-caches layer shards on first run)';
  if (shardCount > 0) {
    statusLabel = `Active (${totalMb} MB • ${shardCount} cached shards)`;
  }

  return {
    dir,
    totalFiles,
    totalBytes,
    totalMb,
    layerCount: shardCount,
    hitCount: sessionCacheHits,
    bandwidthSavedMb: savedMb,
    enabled: true,
    statusLabel,
  };
}

/**
 * Clears local tensor cache to free flash storage.
 */
export function clearTensorCache(customDir?: string): { deletedFiles: number; freedBytes: number } {
  const dir = getTensorCacheDir(customDir);
  let deletedFiles = 0;
  let freedBytes = 0;

  try {
    if (fs.existsSync(dir)) {
      const entries = fs.readdirSync(dir);
      for (const entry of entries) {
        const full = path.join(dir, entry);
        try {
          const st = fs.statSync(full);
          if (st.isFile()) {
            freedBytes += st.size;
            fs.unlinkSync(full);
            deletedFiles++;
          }
        } catch {
          // ignore
        }
      }
    }
  } catch {
    // ignore
  }

  sessionCacheHits = 0;
  sessionBandwidthSavedBytes = 0;

  return { deletedFiles, freedBytes };
}
