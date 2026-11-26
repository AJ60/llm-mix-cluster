/**
 * Module 2: Distributed Cross-Node Speculative Decoding Coordinator
 *
 * Implements two-tiered speculative generation across heterogeneous nodes:
 *  - Draft Phase (Edge/Mobile Worker): small companion draft model emits K in [3, 8]
 *    candidate tokens with token-level probabilities p_draft(x).
 *  - Verification Phase (Master GPU): target model verifies all K candidates in a
 *    single batched forward pass producing p_target(x).
 *  - Exact Stochastic Speculative Acceptance (Leviathan et al. / Chen et al.):
 *      P(accept) = min(1, p_target(x) / p_draft(x))
 *    On first rejection, resamples from the normalized residual distribution:
 *      p_residual(x) = norm(max(0, p_target(x) - p_draft(x)))
 *    guaranteeing mathematical equivalence to standard autoregressive decoding.
 */

export interface TokenDistribution {
  probs: number[];
}

export interface DraftCandidate {
  tokenId: number;
  draftProb: number;
  draftDistribution: number[];
}

export interface SpeculativeStepResult {
  acceptedTokens: number[];
  acceptedCount: number;
  draftedCount: number;
  acceptanceRate: number;
  bonusTokenUsed: boolean;
}

export interface SpeculativeCoordinatorConfig {
  minK: number;
  maxK: number;
  initialK: number;
  networkRttMs: number;
  draftTokPerSec: number;
  verifyBatchLatencyMs: number;
}

export interface SpeculativeTelemetry {
  currentK: number;
  emaAcceptanceRate: number;
  totalDrafted: number;
  totalAccepted: number;
  effectiveSpeedup: number;
  networkOverheadMs: number;
}

/** Deterministic Mulberry32 PRNG for reproducible token-fidelity verification (Gate 3). */
export function createSeededRng(seed: number): () => number {
  let state = seed >>> 0;
  return () => {
    state = (state + 0x6d2b79f5) >>> 0;
    let t = Math.imul(state ^ (state >>> 15), 1 | state);
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

export function applyTemperatureAndTopP(
  logits: number[],
  temperature: number,
  topP: number
): number[] {
  const n = logits.length;
  if (n === 0) return [];
  const temp = Math.max(1e-4, temperature);

  let maxLogit = -Infinity;
  for (let i = 0; i < n; i++) {
    const scaled = logits[i] / temp;
    if (scaled > maxLogit) maxLogit = scaled;
  }

  const exps = new Array<number>(n);
  let sum = 0;
  for (let i = 0; i < n; i++) {
    const e = Math.exp(logits[i] / temp - maxLogit);
    exps[i] = e;
    sum += e;
  }

  const probs = exps.map((e) => e / (sum || 1));

  if (topP >= 0.9999) return probs;

  // Nucleus (top-p) filtering preserving probability order
  const indexed = probs.map((p, idx) => ({ p, idx })).sort((a, b) => b.p - a.p);
  const filtered = new Array<number>(n).fill(0);
  let cumulative = 0;
  let keptSum = 0;
  for (const item of indexed) {
    filtered[item.idx] = item.p;
    keptSum += item.p;
    cumulative += item.p;
    if (cumulative >= topP) break;
  }
  return filtered.map((p) => p / (keptSum || 1));
}

export function sampleFromDistribution(probs: number[], rng: () => number): number {
  const r = rng();
  let acc = 0;
  for (let i = 0; i < probs.length; i++) {
    acc += probs[i];
    if (r <= acc) return i;
  }
  return Math.max(0, probs.length - 1);
}

/**
 * Exact speculative verification kernel:
 *   P(accept_i) = min(1, P_target(x_i) / P_draft(x_i))
 * When rejected at position i, samples correction token from norm(max(0, P_target - P_draft)).
 * When all K drafts are accepted, samples 1 bonus token from the (K+1)-th target distribution.
 */
export function verifySpeculativeBatch(
  drafts: DraftCandidate[],
  targetDistributions: number[][],
  rng: () => number
): SpeculativeStepResult {
  const acceptedTokens: number[] = [];
  let acceptedCount = 0;
  let bonusTokenUsed = false;

  for (let i = 0; i < drafts.length; i++) {
    const cand = drafts[i];
    const pTargetDist = targetDistributions[i];
    const pTarget = pTargetDist[cand.tokenId] ?? 0;
    const pDraft = Math.max(1e-12, cand.draftProb);

    const acceptProb = Math.min(1, pTarget / pDraft);
    const u = rng();

    if (u <= acceptProb) {
      acceptedTokens.push(cand.tokenId);
      acceptedCount += 1;
    } else {
      // Sample exact correction token from residual distribution max(0, P_target - P_draft)
      const residual = pTargetDist.map((pt, idx) =>
        Math.max(0, pt - (cand.draftDistribution[idx] ?? 0))
      );
      const resSum = residual.reduce((a, b) => a + b, 0);
      const normalized =
        resSum > 1e-12 ? residual.map((v) => v / resSum) : pTargetDist;
      const corrected = sampleFromDistribution(normalized, rng);
      acceptedTokens.push(corrected);
      return {
        acceptedTokens,
        acceptedCount,
        draftedCount: drafts.length,
        acceptanceRate: drafts.length > 0 ? acceptedCount / drafts.length : 0,
        bonusTokenUsed: false,
      };
    }
  }

  // All K candidates accepted -> emit 1 bonus token from final target distribution
  if (targetDistributions.length > drafts.length) {
    const bonusDist = targetDistributions[drafts.length];
    acceptedTokens.push(sampleFromDistribution(bonusDist, rng));
    bonusTokenUsed = true;
  }

  return {
    acceptedTokens,
    acceptedCount,
    draftedCount: drafts.length,
    acceptanceRate: drafts.length > 0 ? acceptedCount / drafts.length : 1,
    bonusTokenUsed,
  };
}

/**
 * Computes theoretical & observed effective speedup:
 *   Effective Speedup = (alpha * K + 1) / (1 + T_verify / T_draft_cycle)
 * accounting for inter-node link RTT.
 */
export function computeSpeculativeSpeedup(params: {
  acceptanceRate: number;
  draftK: number;
  verifyLatencyMs: number;
  draftTokenLatencyMs: number;
  rttMs: number;
}): number {
  const alpha = Math.max(0, Math.min(1, params.acceptanceRate));
  const k = Math.max(1, Math.min(16, params.draftK));
  // Expected tokens emitted per verification cycle (geometric series bounded by K + 1)
  const expectedTokens =
    alpha >= 0.999 ? k + 1 : (1 - Math.pow(alpha, k + 1)) / (1 - alpha);
  const tDraftTotal = k * Math.max(1, params.draftTokenLatencyMs) + Math.max(0.1, params.rttMs);
  const tVerify = Math.max(1, params.verifyLatencyMs);
  // Compare against autoregressive baseline of 1 token per T_verify
  const cycleCostRatio = (tDraftTotal + tVerify) / tVerify;
  return Number(Math.max(1.0, expectedTokens / Math.max(0.25, cycleCostRatio)).toFixed(2));
}

/**
 * Adaptive Draft-Length Coordinator: dynamically tunes K in [3, 8]
 * based on observed acceptance rate (alpha) and link RTT.
 */
export class DistributedSpeculativeCoordinator {
  private currentK: number;
  private emaAlpha: number;
  private totalDrafted = 0;
  private totalAccepted = 0;
  private readonly cfg: SpeculativeCoordinatorConfig;

  constructor(config?: Partial<SpeculativeCoordinatorConfig>) {
    this.cfg = {
      minK: config?.minK ?? 3,
      maxK: config?.maxK ?? 8,
      initialK: config?.initialK ?? 4,
      networkRttMs: config?.networkRttMs ?? 1.2,
      draftTokPerSec: config?.draftTokPerSec ?? 25.0,
      verifyBatchLatencyMs: config?.verifyBatchLatencyMs ?? 72.0,
    };
    this.currentK = Math.max(this.cfg.minK, Math.min(this.cfg.maxK, this.cfg.initialK));
    this.emaAlpha = 0.78;
  }

  public getDraftK(): number {
    return this.currentK;
  }

  public recordStep(step: SpeculativeStepResult, observedRttMs?: number): SpeculativeTelemetry {
    const rtt = observedRttMs ?? this.cfg.networkRttMs;
    this.totalDrafted += step.draftedCount;
    this.totalAccepted += step.acceptedCount;
    this.emaAlpha = 0.7 * this.emaAlpha + 0.3 * step.acceptanceRate;

    // Adapt K: high acceptance + higher RTT favors deeper speculative batches; low acceptance shrinks K
    if (this.emaAlpha >= 0.82 && rtt <= 15) {
      this.currentK = Math.min(this.cfg.maxK, this.currentK + 1);
    } else if (this.emaAlpha < 0.52) {
      this.currentK = Math.max(this.cfg.minK, this.currentK - 1);
    }

    const draftLatencyMs = 1000 / Math.max(1, this.cfg.draftTokPerSec);
    const speedup = computeSpeculativeSpeedup({
      acceptanceRate: this.emaAlpha,
      draftK: this.currentK,
      verifyLatencyMs: this.cfg.verifyBatchLatencyMs,
      draftTokenLatencyMs: draftLatencyMs,
      rttMs: rtt,
    });

    return {
      currentK: this.currentK,
      emaAcceptanceRate: Number(this.emaAlpha.toFixed(3)),
      totalDrafted: this.totalDrafted,
      totalAccepted: this.totalAccepted,
      effectiveSpeedup: speedup,
      networkOverheadMs: Number(rtt.toFixed(2)),
    };
  }

  public getTelemetry(): SpeculativeTelemetry {
    const draftLatencyMs = 1000 / Math.max(1, this.cfg.draftTokPerSec);
    return {
      currentK: this.currentK,
      emaAcceptanceRate: Number(this.emaAlpha.toFixed(3)),
      totalDrafted: this.totalDrafted,
      totalAccepted: this.totalAccepted,
      effectiveSpeedup: computeSpeculativeSpeedup({
        acceptanceRate: this.emaAlpha,
        draftK: this.currentK,
        verifyLatencyMs: this.cfg.verifyBatchLatencyMs,
        draftTokenLatencyMs: draftLatencyMs,
        rttMs: this.cfg.networkRttMs,
      }),
      networkOverheadMs: Number(this.cfg.networkRttMs.toFixed(2)),
    };
  }
}
