/**
 * Self-learning Exponential Moving Average (EMA) history store persisted to `cluster_history.json`.
 * Mirrors `silica-core/src/history.rs` (α = 0.30).
 */

import { LearnedEmaProfile } from '../types/starCluster';

export const DEFAULT_EMA_ALPHA = 0.3;

export interface RunObservationInput {
  profileKey: string;
  observedTokPerSec: number;
  observedTtftMs: number;
  peakVramMb: number;
  batchSize: number;
  ubatchSize: number;
  usedNkvo: boolean;
  throttled: boolean;
  oomOrFail: boolean;
  nodeObservedEfficiency?: Record<string, number>;
  timestampEpochS?: number;
}

export interface ClusterHistoryDocument {
  version: number;
  profiles: Record<string, LearnedEmaProfile>;
}

export function createEmptyHistoryDocument(): ClusterHistoryDocument {
  return { version: 1, profiles: {} };
}

export function recordEmaObservation(
  doc: ClusterHistoryDocument,
  obs: RunObservationInput,
  alpha = DEFAULT_EMA_ALPHA
): LearnedEmaProfile {
  const nowS = obs.timestampEpochS ?? Math.floor(Date.now() / 1000);
  const existing = doc.profiles[obs.profileKey];

  const entry: LearnedEmaProfile = existing
    ? {
        ...existing,
        nodeEfficiency: { ...existing.nodeEfficiency },
      }
    : {
        runs: 0,
        emaTokPerSec: Math.max(0.1, obs.observedTokPerSec),
        emaTtftMs: Math.max(1, obs.observedTtftMs),
        emaPeakVramMb: Math.max(0, obs.peakVramMb),
        recommendedBatch: Math.max(128, Math.min(2048, obs.batchSize)),
        recommendedUbatch: Math.max(64, Math.min(512, obs.ubatchSize)),
        learnedKvOffload: Boolean(obs.oomOrFail),
        nodeEfficiency: {},
        throttleEvents: 0,
        oomEvents: 0,
        lastUpdatedEpochS: nowS,
      };

  entry.runs += 1;
  if (entry.runs === 1) {
    entry.emaTokPerSec = Math.max(0.1, obs.observedTokPerSec);
    entry.emaTtftMs = Math.max(1, obs.observedTtftMs);
    entry.emaPeakVramMb = Math.max(0, obs.peakVramMb);
  } else {
    entry.emaTokPerSec = Number(
      (alpha * obs.observedTokPerSec + (1 - alpha) * entry.emaTokPerSec).toFixed(3)
    );
    entry.emaTtftMs = Number(
      (alpha * obs.observedTtftMs + (1 - alpha) * entry.emaTtftMs).toFixed(1)
    );
    entry.emaPeakVramMb = Number(
      (alpha * obs.peakVramMb + (1 - alpha) * entry.emaPeakVramMb).toFixed(1)
    );
  }

  if (obs.throttled) {
    entry.throttleEvents += 1;
    entry.recommendedUbatch = Math.max(128, Math.floor(entry.recommendedUbatch / 2));
  } else if (obs.observedTokPerSec > entry.emaTokPerSec * 1.05 && !obs.oomOrFail) {
    entry.recommendedBatch = Math.max(128, Math.min(2048, obs.batchSize));
    entry.recommendedUbatch = Math.max(64, Math.min(512, obs.ubatchSize));
  }

  if (obs.oomOrFail) {
    entry.oomEvents += 1;
    entry.learnedKvOffload = true;
    entry.recommendedBatch = Math.max(256, Math.floor(entry.recommendedBatch / 2));
  }

  if (obs.nodeObservedEfficiency) {
    for (const [nodeId, rawEff] of Object.entries(obs.nodeObservedEfficiency)) {
      const clamped = Math.max(0.25, Math.min(2.0, rawEff));
      const prev = entry.nodeEfficiency[nodeId];
      entry.nodeEfficiency[nodeId] =
        prev !== undefined
          ? Number((alpha * clamped + (1 - alpha) * prev).toFixed(4))
          : clamped;
    }
  }

  entry.lastUpdatedEpochS = nowS;
  doc.version = 1;
  doc.profiles[obs.profileKey] = entry;
  return entry;
}
