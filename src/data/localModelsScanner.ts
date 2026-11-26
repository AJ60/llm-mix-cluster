import {
  ContextLengthOption,
  GgufArchitecture,
  GgufModelPreset,
  LayerMemoryProfile,
} from '../types/starCluster';

export interface GgufHeaderMetadata {
  magic: string;
  version: number;
  tensorCount: number;
  kvCount: number;
  name?: string;
  basename?: string;
  architecture?: string;
  layers?: number;
  contextLength?: number;
  embeddingLength?: number;
  headCount?: number;
  headCountKv?: number;
  expertCount?: number;
  expertUsedCount?: number;
  fileType?: number;
  hasChatTemplate?: boolean;
  hasNativeMtp?: boolean;
  vocabSize?: number;
  vocabHash?: string;
}

const FNV_OFFSET = 0xcbf29ce484222325n;
const FNV_PRIME = 0x100000001b3n;
const MASK_64 = (1n << 64n) - 1n;

function fnv1aUpdate(hash: bigint, bytes: Uint8Array): bigint {
  let h = hash;
  for (let i = 0; i < bytes.length; i++) {
    h ^= BigInt(bytes[i]);
    h = (h * FNV_PRIME) & MASK_64;
  }
  return h;
}

/**
 * Parses binary GGUF v2/v3 header bytes sequentially to extract real model-card metadata,
 * chat template presence, MoE/MTP attributes, and deterministic vocabulary hash.
 * Works isomorphically in Node.js and the browser.
 */
export function parseGgufBinaryHeader(bytes: Uint8Array): GgufHeaderMetadata | null {
  if (bytes.length < 24) return null;
  if (bytes[0] !== 0x47 || bytes[1] !== 0x47 || bytes[2] !== 0x55 || bytes[3] !== 0x46) {
    return null;
  }

  const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
  const decoder = new TextDecoder('utf-8');
  const version = view.getUint32(4, true);
  if (version < 2 || version > 3) return null;

  const tensorCount = Number(view.getBigUint64(8, true));
  const kvCount = Number(view.getBigUint64(16, true));

  let offset = 24;
  const kv: Record<string, unknown> = {};
  let vocabSize: number | undefined;
  let vocabHash: string | undefined;
  let hasNativeMtp = false;

  const readString = (): string | null => {
    if (offset + 8 > bytes.length) return null;
    const len = Number(view.getBigUint64(offset, true));
    offset += 8;
    if (len < 0 || len > 4 * 1024 * 1024 || offset + len > bytes.length) return null;
    const s = decoder.decode(bytes.subarray(offset, offset + len));
    offset += len;
    return s;
  };

  const readValue = (vtype: number, key: string): { ok: boolean; value?: unknown } => {
    switch (vtype) {
      case 0: // u8
        if (offset + 1 > bytes.length) return { ok: false };
        return { ok: true, value: bytes[offset++] };
      case 1: // i8
        if (offset + 1 > bytes.length) return { ok: false };
        return { ok: true, value: view.getInt8(offset++) };
      case 2: // u16
        if (offset + 2 > bytes.length) return { ok: false };
        {
          const v = view.getUint16(offset, true);
          offset += 2;
          return { ok: true, value: v };
        }
      case 3: // i16
        if (offset + 2 > bytes.length) return { ok: false };
        {
          const v = view.getInt16(offset, true);
          offset += 2;
          return { ok: true, value: v };
        }
      case 4: // u32
        if (offset + 4 > bytes.length) return { ok: false };
        {
          const v = view.getUint32(offset, true);
          offset += 4;
          return { ok: true, value: v };
        }
      case 5: // i32
        if (offset + 4 > bytes.length) return { ok: false };
        {
          const v = view.getInt32(offset, true);
          offset += 4;
          return { ok: true, value: v };
        }
      case 6: // f32
        if (offset + 4 > bytes.length) return { ok: false };
        {
          const v = view.getFloat32(offset, true);
          offset += 4;
          return { ok: true, value: v };
        }
      case 7: // bool
        if (offset + 1 > bytes.length) return { ok: false };
        return { ok: true, value: bytes[offset++] !== 0 };
      case 8: // string
        {
          const s = readString();
          return s === null ? { ok: false } : { ok: true, value: s };
        }
      case 9: // array
        {
          if (offset + 12 > bytes.length) return { ok: false };
          const elemType = view.getUint32(offset, true);
          offset += 4;
          const len = Number(view.getBigUint64(offset, true));
          offset += 8;
          const isTokens = key === 'tokenizer.ggml.tokens';
          let h = FNV_OFFSET;
          const lenBytes = new Uint8Array(8);
          new DataView(lenBytes.buffer).setBigUint64(0, BigInt(len), true);
          h = fnv1aUpdate(h, lenBytes);

          if (elemType === 8) {
            for (let i = 0; i < len; i++) {
              if (offset + 8 > bytes.length) {
                if (isTokens) {
                  vocabSize = len;
                  vocabHash = h.toString(16).padStart(16, '0');
                }
                return { ok: false };
              }
              const slen = Number(view.getBigUint64(offset, true));
              offset += 8;
              if (offset + slen > bytes.length) {
                if (isTokens) {
                  vocabSize = len;
                  vocabHash = h.toString(16).padStart(16, '0');
                }
                return { ok: false };
              }
              if (isTokens && slen <= 1024) {
                h = fnv1aUpdate(h, bytes.subarray(offset, offset + slen));
                h = fnv1aUpdate(h, new Uint8Array([0xff]));
              }
              offset += slen;
            }
            if (isTokens) {
              vocabSize = len;
              vocabHash = h.toString(16).padStart(16, '0');
            }
            return { ok: true, value: { len } };
          }

          const elemSize =
            elemType === 0 || elemType === 1 || elemType === 7
              ? 1
              : elemType === 2 || elemType === 3
                ? 2
                : elemType === 4 || elemType === 5 || elemType === 6
                  ? 4
                  : elemType === 10 || elemType === 11 || elemType === 12
                    ? 8
                    : 0;
          if (elemSize === 0 || offset + len * elemSize > bytes.length) {
            return { ok: false };
          }
          offset += len * elemSize;
          return { ok: true, value: { len } };
        }
      case 10: // u64
        if (offset + 8 > bytes.length) return { ok: false };
        {
          const v = Number(view.getBigUint64(offset, true));
          offset += 8;
          return { ok: true, value: v };
        }
      case 11: // i64
        if (offset + 8 > bytes.length) return { ok: false };
        {
          const v = Number(view.getBigInt64(offset, true));
          offset += 8;
          return { ok: true, value: v };
        }
      case 12: // f64
        if (offset + 8 > bytes.length) return { ok: false };
        {
          const v = view.getFloat64(offset, true);
          offset += 8;
          return { ok: true, value: v };
        }
      default:
        return { ok: false };
    }
  };

  for (let i = 0; i < Math.min(kvCount, 2048); i++) {
    const key = readString();
    if (key === null || offset + 4 > bytes.length) break;
    const vtype = view.getUint32(offset, true);
    offset += 4;
    const res = readValue(vtype, key);
    if (!res.ok) break;
    kv[key] = res.value;
    if (key.includes('nextn') || key.includes('mtp')) {
      hasNativeMtp = true;
    }
  }

  const arch = typeof kv['general.architecture'] === 'string' ? kv['general.architecture'] : undefined;
  const pickNum = (suffix: string): number | undefined => {
    if (arch && typeof kv[`${arch}.${suffix}`] === 'number') return kv[`${arch}.${suffix}`] as number;
    for (const [k, v] of Object.entries(kv)) {
      if (k.endsWith(`.${suffix}`) && typeof v === 'number') return v;
    }
    return undefined;
  };

  if (!vocabHash) {
    const seed = new TextEncoder().encode(`${arch ?? 'llama'}:${kv['general.name'] ?? ''}`);
    vocabHash = fnv1aUpdate(FNV_OFFSET, seed).toString(16).padStart(16, '0');
  }

  return {
    magic: 'GGUF',
    version,
    tensorCount,
    kvCount,
    name: typeof kv['general.name'] === 'string' ? kv['general.name'].trim() : undefined,
    basename: typeof kv['general.basename'] === 'string' ? kv['general.basename'].trim() : undefined,
    architecture: arch,
    layers: pickNum('block_count'),
    contextLength: pickNum('context_length'),
    embeddingLength: pickNum('embedding_length'),
    headCount: pickNum('attention.head_count'),
    headCountKv: pickNum('attention.head_count_kv'),
    expertCount: pickNum('expert_count'),
    expertUsedCount: pickNum('expert_used_count'),
    fileType: typeof kv['general.file_type'] === 'number' ? (kv['general.file_type'] as number) : undefined,
    hasChatTemplate: typeof kv['tokenizer.chat_template'] === 'string',
    hasNativeMtp,
    vocabSize,
    vocabHash,
  };
}

export function inferGgufQuantFromFilename(filename: string): string {
  const match = filename.match(
    /(IQ[1-4][_-][A-Z0-9]+|Q[0-8][_-][A-Z0-9_]+|Q[0-8]_[01]|FP16|F16|FP32|F32|BF16|TQ[1-2][_-][0-9]|BITNET|1\.58B|B1\.58|TURBOQUANT|TURBO|VQ[_-][0-9A-Z]+)/i
  );
  if (!match) return 'Q4_K_M';
  const raw = match[1].replace('-', '_').toUpperCase();
  if (raw === 'FP16') return 'F16';
  if (raw === 'FP32') return 'F32';
  if (raw.includes('BITNET') || raw.includes('1.58')) return 'TQ1_0';
  return raw;
}

export function estimateBpwFromQuant(quant: string, arch: GgufArchitecture): number {
  const q = quant.toUpperCase();
  if (arch === 'Ternary' || q.includes('BITNET') || q.includes('1.58') || q.startsWith('TQ1')) return 1.58;
  if (arch === 'TurboQuant' || q.includes('TURBO')) return 2.2;
  if (q.includes('IQ1')) return 1.75;
  if (q.includes('IQ2') || q.includes('Q2')) return 2.6;
  if (q.includes('IQ3') || q.includes('Q3')) return 3.4;
  if (q.includes('IQ4') || q.includes('Q4')) return 4.5;
  if (q.includes('Q5')) return 5.5;
  if (q.includes('Q6')) return 6.6;
  if (q.includes('Q8')) return 8.5;
  if (q.includes('F16') || q.includes('BF16')) return 16.0;
  if (q.includes('F32')) return 32.0;
  return 4.5;
}

export function buildNonUniformLayerProfiles(
  layers: number,
  sizeMb: number,
  architecture: GgufArchitecture,
  contextLength: number,
  kvQuant: 'f16' | 'q8_0' | 'q4_0' = 'q8_0',
  headCount = 32,
  headCountKv = 8,
  embeddingLength = 4096
): { masterPinnedOverheadMb: number; perLayerProfiles: LayerMemoryProfile[] } {
  const masterEmbedHeadMb = Math.max(96, Math.round(sizeMb * 0.085));
  const bodyMb = Math.max(100, sizeMb - masterEmbedHeadMb);
  const avgLayerMb = bodyMb / Math.max(1, layers);

  const headDim = Math.max(64, embeddingLength / Math.max(1, headCount));
  const kvBpe = kvQuant === 'q4_0' ? 0.5625 : kvQuant === 'q8_0' ? 1.0625 : 2.0;
  const perLayerKvMb = Number(
    Math.max(2, (2 * headCountKv * headDim * contextLength * kvBpe) / (1024 * 1024)).toFixed(2)
  );

  const perLayerProfiles: LayerMemoryProfile[] = [];
  for (let i = 0; i < layers; i++) {
    // MoE models often keep dense layer 0 and sparse expert layers on 1..N-1
    const isDenseFirstInMoe = architecture === 'MoE' && i === 0;
    const isMoeLayer = architecture === 'MoE' && !isDenseFirstInMoe;
    const layerWeight = isDenseFirstInMoe ? avgLayerMb * 0.55 : avgLayerMb * (isMoeLayer ? 1.01 : 1.0);
    const attentionMb = Number((layerWeight * (isMoeLayer ? 0.18 : 0.34)).toFixed(2));
    const ffnOrExpertMb = Number((layerWeight - attentionMb).toFixed(2));

    perLayerProfiles.push({
      layerIndex: i,
      attentionMb,
      ffnOrExpertMb,
      totalWeightMb: Number((attentionMb + ffnOrExpertMb).toFixed(2)),
      kvCacheMb: perLayerKvMb,
      isMoeLayer,
    });
  }

  return { masterPinnedOverheadMb: masterEmbedHeadMb, perLayerProfiles };
}

export function inferGgufParamsFromFilename(filename: string, sizeMb: number): {
  totalParamsB: number;
  activeParamsB: number;
  architecture: GgufArchitecture;
  layers: number;
} {
  const lower = filename.toLowerCase();
  const isLooped =
    lower.includes('looped') ||
    lower.includes('recursive') ||
    lower.includes('weight-tied') ||
    lower.includes('tied') ||
    lower.includes('universal') ||
    lower.includes('ut-');
  const isTurbo =
    lower.includes('turboquant') ||
    lower.includes('turbo') ||
    lower.includes('tq-') ||
    lower.includes('vq-');
  const isTernary =
    lower.includes('bitnet') ||
    lower.includes('1.58b') ||
    lower.includes('b1.58') ||
    lower.includes('tq1') ||
    lower.includes('tq2') ||
    lower.includes('ternary');
  const isMoe =
    lower.includes('moe') ||
    lower.includes('mixtral') ||
    lower.includes('a3b') ||
    lower.includes('dbrx') ||
    lower.includes('deepseek');

  const architecture: GgufArchitecture = isLooped
    ? 'Looped'
    : isTurbo
      ? 'TurboQuant'
      : isTernary
        ? 'Ternary'
        : isMoe
          ? 'MoE'
          : 'Dense';

  const quant = inferGgufQuantFromFilename(filename);
  const bpw = estimateBpwFromQuant(quant, architecture);

  const paramMatch = filename.match(/([0-9]+(?:\.[0-9]+)?)[bB](?:-|_|\.|$)/);
  let totalParamsB = 0;
  if (paramMatch) {
    totalParamsB = parseFloat(paramMatch[1]);
  } else {
    const approxBytes = sizeMb * 1024 * 1024;
    totalParamsB = parseFloat(((approxBytes * 8) / (bpw * 1e9)).toFixed(1));
    if (totalParamsB < 1) totalParamsB = 1.5;
  }

  let activeParamsB = totalParamsB;
  if (architecture === 'MoE') {
    activeParamsB = parseFloat(Math.min(totalParamsB, Math.max(1.8, totalParamsB * 0.12)).toFixed(1));
  } else if (architecture === 'Looped') {
    activeParamsB = parseFloat((totalParamsB * 0.5).toFixed(1));
  }

  let layers = 32;
  if (architecture === 'Looped') {
    layers = totalParamsB <= 7 ? 16 : 24;
  } else if (totalParamsB <= 2.0) {
    layers = 28;
  } else if (totalParamsB <= 4.0) {
    layers = 30;
  } else if (totalParamsB <= 9.0) {
    layers = 32;
  } else if (totalParamsB <= 16.0) {
    layers = 40;
  } else if (totalParamsB <= 35.0) {
    layers = architecture === 'MoE' ? 48 : 36;
  } else {
    layers = 80;
  }

  return { totalParamsB, activeParamsB, architecture, layers };
}

export function parseLocalGgufModel(
  filePath: string,
  sizeBytes: number,
  headerBytes?: Uint8Array,
  mmprojInfo?: { path: string; sizeMb: number } | null
): GgufModelPreset {
  const filename = filePath.split(/[/\\]/).pop() || 'model.gguf';
  const sizeMb = Math.max(10, Math.round(sizeBytes / (1024 * 1024)));
  let quant = inferGgufQuantFromFilename(filename);

  const inferred = inferGgufParamsFromFilename(filename, sizeMb);
  let totalParamsB = inferred.totalParamsB;
  let activeParamsB = inferred.activeParamsB;
  let architecture = inferred.architecture;
  let layers = inferred.layers;
  let rawName: string | undefined;
  let detectedCtx: number | undefined;
  let rawArchitecture: string | undefined;
  let expertCount: number | undefined;
  let expertUsedCount: number | undefined;
  let headCount = 32;
  let headCountKv = 8;
  let embeddingLength = 4096;
  let hasChatTemplate = true;
  let hasNativeMtp = false;
  let vocabSize: number | undefined;
  let vocabHash: string | undefined;

  if (headerBytes && headerBytes.length >= 24) {
    const meta = parseGgufBinaryHeader(headerBytes);
    if (meta) {
      if (meta.name) rawName = meta.name;
      if (meta.architecture) rawArchitecture = meta.architecture;
      if (meta.layers && meta.layers > 0) layers = meta.layers;
      if (meta.contextLength && meta.contextLength > 0) detectedCtx = meta.contextLength;
      if (meta.headCount) headCount = meta.headCount;
      if (meta.headCountKv) headCountKv = meta.headCountKv;
      if (meta.embeddingLength) embeddingLength = meta.embeddingLength;
      if (meta.expertCount) expertCount = meta.expertCount;
      if (meta.expertUsedCount) expertUsedCount = meta.expertUsedCount;
      if (meta.hasChatTemplate !== undefined) hasChatTemplate = meta.hasChatTemplate;
      if (meta.hasNativeMtp !== undefined) hasNativeMtp = meta.hasNativeMtp;
      if (meta.vocabSize) vocabSize = meta.vocabSize;
      if (meta.vocabHash) vocabHash = meta.vocabHash;

      if (meta.fileType === 36 || meta.fileType === 37) {
        quant = 'TQ1_0';
        architecture = 'Ternary';
      } else if (meta.expertCount && meta.expertCount > 1) {
        architecture = 'MoE';
        const activeExperts = meta.expertUsedCount || 8;
        activeParamsB = parseFloat(
          Math.min(
            totalParamsB,
            Math.max(1.5, totalParamsB * (0.12 + 0.88 * (activeExperts / meta.expertCount)))
          ).toFixed(1)
        );
      } else if (meta.architecture?.includes('moe')) {
        architecture = 'MoE';
        activeParamsB = parseFloat(Math.min(totalParamsB, Math.max(1.8, totalParamsB * 0.12)).toFixed(1));
      } else if (meta.architecture?.includes('bitnet') || meta.architecture?.includes('ternary')) {
        architecture = 'Ternary';
      }
    }
  }

  const isGenericRawName =
    !rawName ||
    rawName.trim().length <= 2 ||
    ['hf', 'model', 'llm', 'gguf', 'output', 'default', 'test'].includes(rawName.toLowerCase().trim());

  const cleanName =
    (!isGenericRawName ? rawName : null) ||
    filename
      .replace(/\.gguf$/i, '')
      .replace(/[-_](iq[1-4]|q[0-8]|f16|f32|bf16|tq[1-2])[a-z0-9_.-]*/i, '')
      .replace(/[-_]+/g, ' ')
      .trim();

  const id = `local-${filename.toLowerCase().replace(/[^a-z0-9]+/g, '-')}`;
  const maxContextLength = detectedCtx || (totalParamsB <= 2 ? 32768 : 131072);
  // Keep auto-default context length OOM-safe and fast-TTFT (users can raise -c up to maxContextLength in Load Config).
  // Ternary and 30B+ models default to 4096 ctx to slash prompt-eval TTFT and prevent 4 GB GPU KV spillover.
  const practicalCap: ContextLengthOption =
    architecture === 'Ternary' || totalParamsB >= 30
      ? 4096
      : totalParamsB <= 3
        ? 8192
        : (totalParamsB >= 14 || layers >= 40)
          ? 8192
          : 8192;
  const boundedCtx = detectedCtx ? Math.min(detectedCtx, practicalCap) : practicalCap;
  let contextLengthDefault: ContextLengthOption = 4096;
  if (boundedCtx >= 8192) contextLengthDefault = 8192;
  else if (boundedCtx <= 2048) contextLengthDefault = 2048;
  else contextLengthDefault = 4096;

  const { masterPinnedOverheadMb, perLayerProfiles } = buildNonUniformLayerProfiles(
    layers,
    sizeMb,
    architecture,
    contextLengthDefault,
    'q8_0',
    headCount,
    headCountKv,
    embeddingLength
  );

  const mmprojSizeMb = mmprojInfo?.sizeMb ?? 0;
  const archLabel = architecture === 'MoE' ? 'Sparse MoE' : architecture;
  const visionSuffix = mmprojInfo ? ' · Vision (mmproj)' : '';

  return {
    id,
    name: cleanName || filename.replace(/\.gguf$/i, ''),
    quant,
    architecture,
    sizeMb,
    layers,
    totalParamsB,
    activeParamsB,
    contextLengthDefault,
    maxContextLength,
    hfRepo: `local/${filename}`,
    hfUrl: `file://${filePath.replace(/\\/g, '/')}`,
    summary: `${(sizeMb / 1024).toFixed(2)} GB · ${layers}L · ${archLabel}${visionSuffix}`,
    tags: [
      'local',
      quant.toLowerCase(),
      architecture.toLowerCase(),
      ...(mmprojInfo ? ['vision', 'mmproj'] : []),
    ],
    isLocal: true,
    localPath: filePath,
    rawArchitecture,
    expertCount,
    expertUsedCount,
    headCount,
    headCountKv,
    embeddingLength,
    hasChatTemplate,
    hasNativeMtp,
    vocabSize,
    vocabHash,
    mmprojPath: mmprojInfo?.path ?? null,
    mmprojSizeMb,
    masterPinnedOverheadMb: masterPinnedOverheadMb + mmprojSizeMb,
    perLayerProfiles,
  };
}
