//! Self-learning Exponential Moving Average (EMA) history store persisted in `cluster_history.json`.
//! Learns real node efficiency multipliers, optimal batch sizes, and thermal/OOM avoidance across runs.

use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::fs;
use std::path::{Path, PathBuf};

pub const DEFAULT_EMA_ALPHA: f64 = 0.30;

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct LearnedProfile {
    pub runs: u32,
    pub ema_tok_per_sec: f64,
    pub ema_ttft_ms: f64,
    pub ema_peak_vram_mb: f64,
    pub recommended_batch: u32,
    pub recommended_ubatch: u32,
    pub learned_kv_offload: bool,
    pub node_efficiency: HashMap<String, f64>,
    pub throttle_events: u32,
    pub oom_events: u32,
    pub last_updated_epoch_s: u64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct RunObservation {
    pub profile_key: String,
    pub observed_tok_per_sec: f64,
    pub observed_ttft_ms: f64,
    pub peak_vram_mb: f64,
    pub batch_size: u32,
    pub ubatch_size: u32,
    pub used_nkvo: bool,
    pub throttled: bool,
    pub oom_or_fail: bool,
    pub node_observed_efficiency: HashMap<String, f64>,
    pub timestamp_epoch_s: u64,
}

#[derive(Debug, Clone, Default, Serialize, Deserialize)]
pub struct ClusterHistoryStore {
    pub version: u32,
    pub profiles: HashMap<String, LearnedProfile>,
}

impl ClusterHistoryStore {
    pub fn load_or_default(path: &Path) -> Self {
        match fs::read_to_string(path) {
            Ok(raw) => serde_json::from_str(&raw).unwrap_or_else(|_| Self {
                version: 1,
                profiles: HashMap::new(),
            }),
            Err(_) => Self {
                version: 1,
                profiles: HashMap::new(),
            },
        }
    }

    pub fn save(&self, path: &Path) -> Result<(), String> {
        if let Some(parent) = path.parent() {
            if !parent.as_os_str().is_empty() {
                let _ = fs::create_dir_all(parent);
            }
        }
        let json = serde_json::to_string_pretty(self)
            .map_err(|e| format!("Serialize cluster_history.json failed: {}", e))?;
        fs::write(path, json).map_err(|e| format!("Write cluster_history.json failed: {}", e))
    }

    pub fn record_run(&mut self, obs: RunObservation) -> LearnedProfile {
        self.version = 1;
        let alpha = DEFAULT_EMA_ALPHA;
        let entry = self
            .profiles
            .entry(obs.profile_key.clone())
            .or_insert_with(|| LearnedProfile {
                runs: 0,
                ema_tok_per_sec: obs.observed_tok_per_sec.max(0.1),
                ema_ttft_ms: obs.observed_ttft_ms.max(1.0),
                ema_peak_vram_mb: obs.peak_vram_mb.max(0.0),
                recommended_batch: obs.batch_size.clamp(128, 2048),
                recommended_ubatch: obs.ubatch_size.clamp(64, 512),
                learned_kv_offload: obs.used_nkvo,
                node_efficiency: HashMap::new(),
                throttle_events: 0,
                oom_events: 0,
                last_updated_epoch_s: obs.timestamp_epoch_s,
            });

        entry.runs += 1;
        if entry.runs == 1 {
            entry.ema_tok_per_sec = obs.observed_tok_per_sec.max(0.1);
            entry.ema_ttft_ms = obs.observed_ttft_ms.max(1.0);
            entry.ema_peak_vram_mb = obs.peak_vram_mb.max(0.0);
        } else {
            entry.ema_tok_per_sec =
                alpha * obs.observed_tok_per_sec + (1.0 - alpha) * entry.ema_tok_per_sec;
            entry.ema_ttft_ms = alpha * obs.observed_ttft_ms + (1.0 - alpha) * entry.ema_ttft_ms;
            entry.ema_peak_vram_mb =
                alpha * obs.peak_vram_mb + (1.0 - alpha) * entry.ema_peak_vram_mb;
        }

        if obs.throttled {
            entry.throttle_events += 1;
            entry.recommended_ubatch = (entry.recommended_ubatch / 2).max(128);
        } else if obs.observed_tok_per_sec > entry.ema_tok_per_sec * 1.05 && !obs.oom_or_fail {
            entry.recommended_batch = obs.batch_size.clamp(128, 2048);
            entry.recommended_ubatch = obs.ubatch_size.clamp(64, 512);
        }

        entry.learned_kv_offload = obs.used_nkvo;

        if obs.oom_or_fail {
            entry.oom_events += 1;
            entry.learned_kv_offload = true;
            entry.recommended_batch = (entry.recommended_batch / 2).max(256);
        }

        for (node_id, eff) in obs.node_observed_efficiency {
            let clamped = eff.clamp(0.25, 2.0);
            entry
                .node_efficiency
                .entry(node_id)
                .and_modify(|prev| *prev = alpha * clamped + (1.0 - alpha) * *prev)
                .or_insert(clamped);
        }

        entry.last_updated_epoch_s = obs.timestamp_epoch_s;
        entry.clone()
    }

    pub fn default_path() -> PathBuf {
        PathBuf::from("cluster_history.json")
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn ema_converges_and_enables_kv_offload_after_oom() {
        let mut store = ClusterHistoryStore::default();
        let mut eff1 = HashMap::new();
        eff1.insert("master".to_string(), 1.0);
        eff1.insert("android-1".to_string(), 0.8);

        let p1 = store.record_run(RunObservation {
            profile_key: "qwen3moe:2nodes".into(),
            observed_tok_per_sec: 20.0,
            observed_ttft_ms: 400.0,
            peak_vram_mb: 3400.0,
            batch_size: 1024,
            ubatch_size: 256,
            used_nkvo: false,
            throttled: false,
            oom_or_fail: false,
            node_observed_efficiency: eff1,
            timestamp_epoch_s: 1000,
        });
        assert!((p1.ema_tok_per_sec - 20.0).abs() < 1e-6);
        assert!(!p1.learned_kv_offload);

        let mut eff2 = HashMap::new();
        eff2.insert("android-1".to_string(), 1.2);
        let p2 = store.record_run(RunObservation {
            profile_key: "qwen3moe:2nodes".into(),
            observed_tok_per_sec: 30.0,
            observed_ttft_ms: 300.0,
            peak_vram_mb: 3900.0,
            batch_size: 1024,
            ubatch_size: 256,
            used_nkvo: false,
            throttled: false,
            oom_or_fail: true,
            node_observed_efficiency: eff2,
            timestamp_epoch_s: 1100,
        });
        // 0.3 * 30 + 0.7 * 20 = 23.0
        assert!((p2.ema_tok_per_sec - 23.0).abs() < 1e-6);
        assert!(p2.learned_kv_offload);
        assert_eq!(p2.recommended_batch, 512);
        // 0.3 * 1.2 + 0.7 * 0.8 = 0.92
        assert!((p2.node_efficiency["android-1"] - 0.92).abs() < 1e-6);
    }
}
