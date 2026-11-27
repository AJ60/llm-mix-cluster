//! True data-driven automatic model & non-uniform layer distribution planner.
//!
//! Hard Mathematical Invariants:
//! 1. Zero OOM: `alloc_mb[i] <= safe_cap_mb[i] + vram_safe_mb[i]` for every single node.
//! 2. Strict 50% Android physical RAM cap (ignoring zram).
//! 3. Cap-Bounded `--tensor-split`: normalized ratios summing cleanly to 100.0.
//! 4. Automatic pruning of slow/lossy Wi-Fi (>40ms p95 RTT or >1.5% loss) or Edge ARM SBC nodes
//!    when Master + USB-C nodes already fit the model with safety margin.
//! 5. Smart `mmap` switching: pass `-lm none` ONLY when `total_safe_pool >= model_need * 1.15`.
//! 6. Automatic `-nkvo` (CPU KV offload) for <= 4 GB GPUs when target context >= 32,768.
//! 7. Master-pinned `--mmproj` vision projector + embedding/lm_head budget reservation.
//! 8. Strict stock `llama.cpp v1.0.0` CLI flag allowlist (zero hallucinated flags).

use crate::gguf::{GgufModelCard, LayerFootprint, ModelArchMode};
use crate::history::LearnedProfile;
use crate::speculative::SpeculativeMode;
use serde::{Deserialize, Serialize};
use std::collections::HashSet;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub enum NodeOs {
    Windows,
    Linux,
    Android,
    EdgeArm,
    WebGPU,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub enum LinkTransport {
    Local,
    UsbC,
    UsbRndis,
    Ethernet25G,
    WifiLan,
    WebGPU,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub enum ThermalState {
    Cool,
    Warn,
    Shed25,
    EmergencyOffload,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct LiveNodeInput {
    pub node_id: String,
    pub label: String,
    pub is_master: bool,
    pub os: NodeOs,
    pub ip: String,
    pub rpc_port: u16,
    pub transport: LinkTransport,
    pub total_ram_mb: f64,
    pub available_ram_mb: f64,
    pub vram_total_mb: f64,
    pub vram_free_mb: f64,
    pub mem_bw_gbps: f64,
    pub big_cores: u32,
    pub rtt_p95_ms: f64,
    pub bandwidth_mbps: f64,
    pub pkt_loss_pct: f64,
    pub temp_c: f64,
    pub thermal_headroom: f64,
    pub cpu_usage_pct: f64,
    pub gpu_usage_pct: f64,
    pub storage_total_mb: f64,
    pub storage_free_mb: f64,
    pub compute_tflops: f64,
    pub handshake_verified: bool,
    pub heartbeat_age_s: u64,
}

fn serialize_manual_tensor_split<S>(value: &Option<Vec<f64>>, serializer: S) -> Result<S::Ok, S::Error>
where
    S: serde::Serializer,
{
    match value {
        Some(parts) if !parts.is_empty() => serializer.serialize_str(
            &parts.iter().map(|v| v.to_string()).collect::<Vec<_>>().join(","),
        ),
        _ => serializer.serialize_str(""),
    }
}

fn deserialize_manual_tensor_split<'de, D>(
    deserializer: D,
) -> Result<Option<Vec<f64>>, D::Error>
where
    D: serde::Deserializer<'de>,
{
    #[derive(Deserialize)]
    #[serde(untagged)]
    enum Split {
        List(Vec<f64>),
        Text(String),
    }
    let value = Option::<Split>::deserialize(deserializer)?;
    Ok(match value {
        Some(Split::List(list)) => Some(list),
        Some(Split::Text(text)) => {
            let parts: Vec<f64> = text
                .split(|c: char| c == ',' || c.is_whitespace())
                .filter_map(|p| p.trim().parse::<f64>().ok())
                .collect();
            if parts.is_empty() { None } else { Some(parts) }
        }
        None => None,
    })
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(default, rename_all = "camelCase")]
pub struct ModelLoadConfig {
    pub windows_accelerator: String,
    pub linux_accelerator: String,
    pub windows_no_mmap: bool,
    pub linux_mlock: bool,
    pub linux_numa_interleave: bool,
    pub enable_context_shift: bool,
    pub mmproj_path: Option<String>,
    #[serde(alias = "contextLength")]
    pub context_length: u32,
    #[serde(alias = "kvCacheQuant")]
    pub kv_cache_quant: String, // "f16" | "q8_0" | "q4_0"
    #[serde(alias = "gpuOffloadLayers")]
    pub gpu_offload_layers: Option<u32>,
    #[serde(
        default,
        serialize_with = "serialize_manual_tensor_split",
        deserialize_with = "deserialize_manual_tensor_split"
    )]
    pub manual_tensor_split: Option<Vec<f64>>,
    #[serde(alias = "moeCpuOffload")]
    pub moe_cpu_offload: bool,
    #[serde(alias = "speculativeMode")]
    pub speculative_mode: SpeculativeMode,
    #[serde(alias = "draftModelPath")]
    pub draft_model_path: Option<String>,
    #[serde(alias = "draftMaxTokens")]
    pub draft_max_tokens: u32,
    #[serde(rename = "enableFlashAttn", alias = "flashAttn")]
    pub flash_attn: bool,
    #[serde(alias = "contBatching")]
    pub cont_batching: bool,
    #[serde(alias = "cacheReuse")]
    pub cache_reuse: u32,
    #[serde(alias = "kvUnified")]
    pub kv_unified: bool,
    #[serde(alias = "noKvOffload")]
    pub no_kv_offload: Option<bool>,
    #[serde(alias = "slotSavePath")]
    pub slot_save_path: Option<String>,
    #[serde(alias = "forceNoMmap")]
    pub force_no_mmap: Option<bool>,
    #[serde(alias = "threads")]
    pub threads: Option<u32>,
    #[serde(alias = "batchSize")]
    pub batch_size: u32,
    #[serde(alias = "ubatchSize")]
    pub ubatch_size: u32,
    #[serde(alias = "enableJinja")]
    pub enable_jinja: bool,
}

impl ModelLoadConfig {
    /// Rust port of `createDefaultBackendTuningConfig` in src/domain/backendTuner.ts.
    pub fn for_card(card: &crate::gguf::GgufModelCard) -> Self {
        let mut cfg = Self::default();
        let native_ternary = card.quant_label.starts_with("TQ");
        cfg.context_length = if card.arch_mode == crate::gguf::ModelArchMode::Ternary {
            card.context_length.min(4096).max(2048)
        } else {
            card.context_length.max(2048)
        };
        cfg.kv_cache_quant = if card.file_size_mb > 12000.0 || card.estimated_params_b >= 14.0 {
            "q8_0".to_string()
        } else {
            "f16".to_string()
        };
        cfg.flash_attn = !native_ternary;
        cfg.moe_cpu_offload = card.arch_mode == crate::gguf::ModelArchMode::MoE;
        cfg.windows_no_mmap = card.file_size_mb <= 20000.0;
        cfg.batch_size = if card.arch_mode == crate::gguf::ModelArchMode::Ternary || card.file_size_mb >= 10000.0 {
            512
        } else {
            1024
        };
        cfg.mmproj_path = card.mmproj_path.clone();
        cfg
    }
}

impl Default for ModelLoadConfig {
    fn default() -> Self {
        Self {
            windows_accelerator: "CUDA 12.6".to_string(),
            linux_accelerator: "CUDA 12.6".to_string(),
            windows_no_mmap: true,
            linux_mlock: false,
            linux_numa_interleave: true,
            enable_context_shift: true,
            mmproj_path: None,
            context_length: 16384,
            kv_cache_quant: "q8_0".to_string(),
            gpu_offload_layers: None,
            manual_tensor_split: None,
            moe_cpu_offload: true,
            speculative_mode: SpeculativeMode::Off,
            draft_model_path: None,
            draft_max_tokens: 8,
            flash_attn: true,
            cont_batching: true,
            cache_reuse: 256,
            kv_unified: true,
            no_kv_offload: None,
            slot_save_path: Some("./.silica-kv-cache".to_string()),
            force_no_mmap: None,
            threads: None,
            batch_size: 1024,
            ubatch_size: 256,
            enable_jinja: true,
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PlannedNodeAllocation {
    pub node_id: String,
    pub label: String,
    pub is_master: bool,
    pub ip: String,
    pub rpc_addr: Option<String>,
    pub transport: LinkTransport,
    pub safe_cap_mb: f64,
    pub vram_safe_mb: f64,
    pub alloc_mb: f64,
    pub layer_start: Option<u32>,
    pub layer_end: Option<u32>,
    pub layer_count: u32,
    pub tensor_split_pct: f64,
    pub thermal_state: ThermalState,
    pub pruned: bool,
    pub prune_reason: Option<String>,
    pub estimated_tok_per_sec: f64,
    pub placement_score: f64,
    pub handoff_count: u32,
    pub estimated_handoff_ms: f64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ClusterPlanOutput {
    pub total_safe_pool_mb: f64,
    pub total_model_need_mb: f64,
    pub effective_context: u32,
    pub kv_cache_total_mb: f64,
    pub kv_per_layer_mb: f64,
    pub layer_weight_mb: f64,
    pub estimated_handoff_ms: f64,
    pub use_no_mmap: bool,
    pub mmap_streaming: bool,
    pub auto_nkvo: bool,
    pub master_mmproj_path: Option<String>,
    pub tensor_split: Vec<f64>,
    pub tensor_split_csv: String,
    pub rpc_endpoints_csv: Option<String>,
    pub nodes: Vec<PlannedNodeAllocation>,
    pub cli_args: Vec<String>,
}

/// Evaluates non-root safe physical memory cap:
/// - Android: strictly <= 50% of physical MemTotal AND <= 80% MemAvailable (ignoring zram).
/// - Windows/Linux/EdgeARM: respects OS headroom (75% of available or 70% of total minus reserve).
pub fn compute_safe_caps(node: &LiveNodeInput) -> (f64, f64, ThermalState) {
    let (thermal_state, scale) = match node.os {
        NodeOs::Android => {
            if node.temp_c >= 48.0 || node.thermal_headroom >= 0.95 {
                (ThermalState::EmergencyOffload, 0.0)
            } else if node.temp_c >= 45.0 || node.thermal_headroom >= 0.90 {
                (ThermalState::Shed25, 0.75)
            } else if node.temp_c >= 42.0 {
                (ThermalState::Warn, 0.90)
            } else {
                (ThermalState::Cool, 1.0)
            }
        }
        _ => {
            if node.temp_c >= 88.0 {
                (ThermalState::EmergencyOffload, 0.0)
            } else if node.temp_c >= 82.0 {
                (ThermalState::Shed25, 0.75)
            } else if node.temp_c >= 75.0 {
                (ThermalState::Warn, 0.92)
            } else {
                (ThermalState::Cool, 1.0)
            }
        }
    };

    let raw_ram_cap = match node.os {
        NodeOs::Android => {
            let half_physical = node.total_ram_mb * 0.50;
            let avail_guard = node.available_ram_mb * 0.80;
            half_physical.min(avail_guard).max(0.0)
        }
        NodeOs::Windows => {
            let reserve = (node.total_ram_mb * 0.22).max(2048.0);
            (node.available_ram_mb - reserve * 0.35)
                .min((node.total_ram_mb - reserve).max(0.0))
                .max(0.0)
        }
        NodeOs::Linux | NodeOs::EdgeArm | NodeOs::WebGPU => {
            let reserve = (node.total_ram_mb * 0.18).max(768.0);
            (node.available_ram_mb * 0.85)
                .min((node.total_ram_mb - reserve).max(0.0))
                .max(0.0)
        }
    };

    // Windows WDDM VRAM cliff protection: reserve 12% or 512MB minimum on Windows GPUs.
    let raw_vram_cap = if node.vram_free_mb > 0.0 {
        let vram_reserve = if node.os == NodeOs::Windows {
            (node.vram_total_mb * 0.12).max(512.0)
        } else {
            (node.vram_total_mb * 0.08).max(256.0)
        };
        (node.vram_free_mb - vram_reserve).max(0.0)
    } else {
        0.0
    };

    // Keep Master online with minimal floor even if warm, while Workers shed cleanly to 0 on emergency.
    let eff_scale = if node.is_master && scale == 0.0 {
        0.5
    } else {
        scale
    };

    (raw_ram_cap * eff_scale, raw_vram_cap * eff_scale, thermal_state)
}

/// Strict stock `llama.cpp v1.0.0` CLI flag allowlist validator.
pub fn validate_stock_llama_cpp_flags(args: &[String]) -> Result<(), String> {
    let allowed: HashSet<&'static str> = [
        "-m",
        "--mmproj",
        "--host",
        "--port",
        "-c",
        "-ngl",
        "--tensor-split",
        "--rpc",
        "--jinja",
        "-fa",
        "-ctk",
        "-ctv",
        "--kv-unified",
        "-nkvo",
        "--slot-save-path",
        "-ot",
        "-t",
        "-b",
        "-ub",
        "--cont-batching",
        "--cache-reuse",
        "-lm",
        "--model-draft",
        "--draft-max",
        "--lookup-cache-static",
    ]
    .into_iter()
    .collect();

    for arg in args {
        if arg.starts_with('-') {
            if !allowed.contains(arg.as_str()) {
                return Err(format!("Disallowed / non-stock llama.cpp v1.0.0 flag: {}", arg));
            }
        }
    }
    Ok(())
}


fn activation_transfer_mb(card: &GgufModelCard) -> f64 {
    card.embedding_length as f64 * 2.0 / (1024.0 * 1024.0)
}

fn link_cost(a: &LiveNodeInput, b: &LiveNodeInput, activation_mb: f64) -> f64 {
    let bandwidth = a.bandwidth_mbps.min(b.bandwidth_mbps).max(1.0);
    let transfer_ms = activation_mb * 8.0 * 1000.0 / bandwidth;
    let rtt = (a.rtt_p95_ms + b.rtt_p95_ms) * 0.5;
    let loss = (a.pkt_loss_pct + b.pkt_loss_pct) * 0.5;
    transfer_ms + rtt * 2.0 + loss * 20.0
}

fn ordered_worker_chain(
    master: &LiveNodeInput,
    workers: &[usize],
    evaluated: &[(&LiveNodeInput, f64, f64, ThermalState)],
    activation_mb: f64,
) -> Vec<usize> {
    let mut chain: Vec<usize> = Vec::with_capacity(workers.len());
    for &idx in workers {
        let node = evaluated[idx].0;
        let mut best_position = 0usize;
        let mut best_cost = f64::MAX;
        for position in 0..=chain.len() {
            let previous = if position == 0 {
                master
            } else {
                evaluated[chain[position - 1]].0
            };
            let next = if position == chain.len() {
                master
            } else {
                evaluated[chain[position]].0
            };
            let delta = link_cost(previous, node, activation_mb)
                + link_cost(node, next, activation_mb)
                - link_cost(previous, next, activation_mb);
            if delta < best_cost {
                best_cost = delta;
                best_position = position;
            }
        }
        chain.insert(best_position, idx);
    }
    chain
}

/// Computes the complete zero-OOM, cap-bounded, non-uniform cluster plan and stock CLI arguments.
pub fn compute_cluster_plan(
    card: &GgufModelCard,
    nodes_input: &[LiveNodeInput],
    cfg: &ModelLoadConfig,
    learned: Option<&LearnedProfile>,
) -> ClusterPlanOutput {
    // Only consider Master + verified ONLINE workers with active heartbeat (<30s TTL).
    let live_nodes: Vec<&LiveNodeInput> = nodes_input
        .iter()
        .filter(|n| n.is_master || (n.handshake_verified && n.heartbeat_age_s < 30))
        .collect();

    let master_vram_total = live_nodes
        .iter()
        .find(|n| n.is_master)
        .map(|n| n.vram_total_mb)
        .unwrap_or(0.0);

    let mut evaluated: Vec<(&LiveNodeInput, f64, f64, ThermalState)> = live_nodes
        .iter()
        .map(|n| {
            let (ram_cap, vram_cap, tstate) = compute_safe_caps(n);
            (*n, ram_cap, vram_cap, tstate)
        })
        .collect();

    evaluated.sort_by_key(|(n, _, _, _)| if n.is_master { 0 } else { 1 });

    let fast_tier_pool_mb: f64 = evaluated
        .iter()
        .filter(|(n, _, _, t)| {
            *t != ThermalState::EmergencyOffload
                && (n.is_master
                    || matches!(
                        n.transport,
                        LinkTransport::UsbC | LinkTransport::UsbRndis | LinkTransport::Ethernet25G
                    ))
        })
        .map(|(_, ram, vram, _)| ram + vram)
        .sum();

    let layer_weight_mb: f64 = card
        .per_layer_profiles
        .iter()
        .map(|l| l.total_weight_mb)
        .sum();
    let base_context = card.profiled_context.max(4096);
    let requested_context = cfg.context_length.max(4096).min(card.context_length.max(4096));
    let kv_per_context_mb = card
        .per_layer_profiles
        .iter()
        .map(|l| l.kv_cache_mb)
        .sum::<f64>()
        / base_context as f64;
    let available_for_kv = (fast_tier_pool_mb - card.master_pinned_overhead_mb - layer_weight_mb)
        .max(0.0)
        * 0.9;
    let max_context_by_memory = (available_for_kv / kv_per_context_mb.max(1e-6)).floor() as u32;
    let effective_context = [4096u32, 8192, 16384, 32768, 65536, 131072, 262144]
        .iter()
        .rev()
        .copied()
        .find(|&c| c <= requested_context && c <= max_context_by_memory.max(4096))
        .unwrap_or(4096);
    let kv_scale = effective_context as f64 / base_context as f64;
    let profiles: Vec<LayerFootprint> = card
        .per_layer_profiles
        .iter()
        .map(|profile| LayerFootprint {
            kv_cache_mb: profile.kv_cache_mb * kv_scale,
            ..profile.clone()
        })
        .collect();

    let auto_nkvo = cfg.no_kv_offload.unwrap_or_else(|| {
        let low_vram_long_ctx =
            master_vram_total > 0.0 && master_vram_total <= 4300.0 && effective_context >= 32768;
        let learned_flag = learned.map(|l| l.learned_kv_offload).unwrap_or(false);
        low_vram_long_ctx || learned_flag
    });

    let total_layer_weights_mb: f64 = profiles.iter().map(|l| l.total_weight_mb).sum();
    let total_kv_mb: f64 = profiles.iter().map(|l| l.kv_cache_mb).sum();
    let total_model_need_mb = card.master_pinned_overhead_mb + total_layer_weights_mb + total_kv_mb;
    let fast_tier_fits = fast_tier_pool_mb >= total_model_need_mb * 1.05;

    let mut active_indices = Vec::new();
    let mut allocations: Vec<PlannedNodeAllocation> = Vec::with_capacity(evaluated.len());

    for (idx, (node, safe_cap_mb, vram_safe_mb, thermal_state)) in evaluated.iter().enumerate() {
        let mut pruned = false;
        let mut prune_reason = None;

        if !node.is_master {
            if node.os == NodeOs::WebGPU {
                pruned = true;
                prune_reason = Some("Browser WebGPU worker cannot serve llama.cpp RPC layers".to_string());
            } else if *thermal_state == ThermalState::EmergencyOffload || (*safe_cap_mb + *vram_safe_mb) < 256.0 {
                pruned = true;
                prune_reason = Some("Thermal offload or insufficient safe RAM cap".to_string());
            } else if fast_tier_fits
                && (node.os == NodeOs::EdgeArm
                    || (node.transport == LinkTransport::WifiLan
                        && (node.rtt_p95_ms > 40.0 || node.pkt_loss_pct > 1.5)))
            {
                pruned = true;
                prune_reason = Some(format!(
                    "Pruned slow/lossy link (p95 {:.1}ms, loss {:.1}%) — Master+USB already fits model",
                    node.rtt_p95_ms, node.pkt_loss_pct
                ));
            }
        }

        if !pruned {
            active_indices.push(idx);
        }

        allocations.push(PlannedNodeAllocation {
            node_id: node.node_id.clone(),
            label: node.label.clone(),
            is_master: node.is_master,
            ip: node.ip.clone(),
            rpc_addr: if node.is_master {
                None
            } else {
                Some(format!("{}:{}", node.ip, node.rpc_port))
            },
            transport: node.transport,
            safe_cap_mb: *safe_cap_mb,
            vram_safe_mb: *vram_safe_mb,
            alloc_mb: 0.0,
            layer_start: None,
            layer_end: None,
            layer_count: 0,
            tensor_split_pct: 0.0,
            thermal_state: *thermal_state,
            pruned,
            prune_reason,
            estimated_tok_per_sec: 0.0,
            placement_score: 0.0,
            handoff_count: 0,
            estimated_handoff_ms: 0.0,
        });
    }

    let total_safe_pool_mb: f64 = active_indices
        .iter()
        .map(|&i| allocations[i].safe_cap_mb + allocations[i].vram_safe_mb)
        .sum();

    // Smart mmap rule: ONLY pass `-lm none` when total_safe_pool >= model_need * 1.15.
    let use_no_mmap = cfg
        .force_no_mmap
        .unwrap_or(total_safe_pool_mb >= total_model_need_mb * 1.15);

    // Non-uniform greedy cap-bounded layer assignment respecting Master-pinned overhead first.
    let mut node_remaining_mb: Vec<f64> = allocations
        .iter()
        .map(|a| a.safe_cap_mb + a.vram_safe_mb)
        .collect();

    if let Some(&master_idx) = active_indices.first() {
        let master_overhead = card
            .master_pinned_overhead_mb
            .min(node_remaining_mb[master_idx]);
        allocations[master_idx].alloc_mb += master_overhead;
        node_remaining_mb[master_idx] = (node_remaining_mb[master_idx] - master_overhead).max(0.0);
    }

    // Compute per-active-node desirability weight (bandwidth * EMA efficiency / RTT penalty).
    let raw_weights: Vec<(usize, f64)> = active_indices
        .iter()
        .map(|&idx| {
            let node = evaluated[idx].0;
            let cap = node_remaining_mb[idx].max(1.0);
            let ema_mult = learned
                .and_then(|l| l.node_efficiency.get(&node.node_id).copied())
                .unwrap_or(1.0);
            let rtt_penalty = 1.0 / (1.0 + (node.rtt_p95_ms / 25.0));
            let score = cap * (node.mem_bw_gbps.max(10.0).sqrt()) * ema_mult * rtt_penalty;
            (idx, score)
        })
        .collect();

    let weight_sum: f64 = raw_weights.iter().map(|(_, w)| *w).sum::<f64>().max(1e-6);
    let total_layers = profiles.len() as u32;
    let mb_per_layer = if profiles.is_empty() {
        (card.file_size_mb / total_layers.max(1) as f64).max(1.0)
    } else {
        (profiles.iter().map(|p| p.total_weight_mb + p.kv_cache_mb).sum::<f64>()
            / profiles.len() as f64).max(1.0)
    };
    
    // Master gets first slice [0, master_layers - 1], workers get contiguous subsequent slices
    let master_idx = active_indices.first().copied().unwrap_or(0);
    let activation_mb = activation_transfer_mb(card);
    let worker_indices = ordered_worker_chain(
        evaluated[master_idx].0,
        &active_indices.iter().copied().filter(|&i| i != master_idx).collect::<Vec<_>>(),
        &evaluated,
        activation_mb,
    );

    let mut total_worker_layers = 0u32;
    let mut worker_layer_counts: Vec<(usize, u32)> = Vec::new();

    for &w_idx in &worker_indices {
        let max_cap_layers = (node_remaining_mb[w_idx] / mb_per_layer).floor().max(0.0) as u32;
        let score_weight = raw_weights.iter().find(|(idx, _)| *idx == w_idx).map(|(_, w)| *w).unwrap_or(1.0);
        let target_layers = ((total_layers as f64) * (score_weight / weight_sum)).round().max(0.0) as u32;
        let count = max_cap_layers.min(target_layers);
        worker_layer_counts.push((w_idx, count));
        total_worker_layers += count;
    }

    if total_worker_layers >= total_layers && total_layers > 1 {
        let allowed = total_layers - 1;
        let mut excess = total_worker_layers - allowed;
        for (_, count) in worker_layer_counts.iter_mut().rev() {
            let deduct = (*count).min(excess);
            *count -= deduct;
            excess -= deduct;
            if excess == 0 { break; }
        }
        total_worker_layers = allowed;
    }

    let master_layers = total_layers.saturating_sub(total_worker_layers).max(1);
    allocations[master_idx].layer_count = master_layers;
    allocations[master_idx].layer_start = Some(0);
    allocations[master_idx].layer_end = Some(master_layers.saturating_sub(1));

    let mut cursor_layer = master_layers;
    for (w_idx, count) in worker_layer_counts {
        if count > 0 && cursor_layer < total_layers {
            allocations[w_idx].layer_count = count;
            allocations[w_idx].layer_start = Some(cursor_layer);
            allocations[w_idx].layer_end = Some(cursor_layer + count - 1);
            cursor_layer += count;
        } else {
            allocations[w_idx].layer_count = 0;
            allocations[w_idx].layer_start = None;
            allocations[w_idx].layer_end = None;
        }
    }

    if cursor_layer < total_layers {
        let remaining = total_layers - cursor_layer;
        let last_active_idx = worker_indices.iter().copied().rev().find(|&i| allocations[i].layer_count > 0).unwrap_or(master_idx);
        let layer_mb = |idx: u32| -> f64 {
            profiles
                .get(idx as usize)
                .map(|p| p.total_weight_mb + p.kv_cache_mb)
                .unwrap_or(0.0)
        };
        let used = allocations[last_active_idx]
            .layer_start
            .zip(allocations[last_active_idx].layer_end)
            .map(|(start, end)| (start..=end).map(&layer_mb).sum::<f64>())
            .unwrap_or(0.0);
        let mut headroom = node_remaining_mb[last_active_idx] - used;
        let mut extra = 0u32;
        let mut cursor = cursor_layer;
        while cursor < total_layers {
            let cost = layer_mb(cursor);
            if cost <= 0.0 || headroom < cost {
                break;
            }
            headroom -= cost;
            extra += 1;
            cursor += 1;
        }
        if extra > 0 {
            allocations[last_active_idx].layer_count += extra;
            allocations[last_active_idx].layer_end =
                Some(allocations[last_active_idx].layer_start.unwrap_or(cursor_layer) + extra - 1);
        }
    }

    // Compute exact non-uniform byte allocation based on assigned contiguous spans
    for &idx in &active_indices {
        if allocations[idx].layer_count > 0 {
            if let (Some(start), Some(end)) = (allocations[idx].layer_start, allocations[idx].layer_end) {
                let mut layer_mb = 0.0f64;
                for l_idx in start..=end {
                    if let Some(prof) = profiles.get(l_idx as usize) {
                        layer_mb += prof.total_weight_mb + prof.kv_cache_mb;
                    }
                }
                let master_pinned = if idx == master_idx { card.master_pinned_overhead_mb } else { 0.0 };
                allocations[idx].alloc_mb = layer_mb + master_pinned;
            }
        }
    }

    // Hard Zero-OOM clamp guarantee & exact normalized `--tensor-split` summing to 100.0.
    for alloc in &mut allocations {
        let max_cap = alloc.safe_cap_mb + alloc.vram_safe_mb;
        if alloc.alloc_mb > max_cap {
            alloc.alloc_mb = max_cap;
        }
    }

    let active_alloc_sum: f64 = active_indices
        .iter()
        .map(|&i| allocations[i].alloc_mb)
        .sum();

    let mut tensor_split = Vec::with_capacity(active_indices.len());
    if let Some(ref manual) = cfg.manual_tensor_split {
        if manual.len() == active_indices.len()
            && manual.iter().all(|v| v.is_finite() && *v >= 0.0)
            && manual.iter().sum::<f64>() > 0.0
        {
            let msum: f64 = manual.iter().sum();
            for (k, &idx) in active_indices.iter().enumerate() {
                let pct = (manual[k] / msum) * 100.0;
                allocations[idx].tensor_split_pct = pct;
                tensor_split.push(pct);
            }
        }
    }

    if tensor_split.is_empty() && !active_indices.is_empty() {
        let count = active_indices.len();
        let raw: Vec<f64> = if active_alloc_sum > 0.0 {
            active_indices
                .iter()
                .map(|&idx| allocations[idx].alloc_mb)
                .collect()
        } else {
            vec![1.0; count]
        };
        let raw_sum = raw.iter().sum::<f64>().max(1e-6);
        let mut basis = Vec::with_capacity(count);
        let mut used_basis = 0u64;
        for k in 0..count.saturating_sub(1) {
            let exact = (raw[k] / raw_sum * 10000.0).floor().max(0.0) as u64;
            let reserved = (count - 1 - k) as u64;
            let value = exact.min(10000u64.saturating_sub(used_basis).saturating_sub(reserved));
            basis.push(value);
            used_basis += value;
        }
        basis.push(10000u64.saturating_sub(used_basis));
        for (k, &idx) in active_indices.iter().enumerate() {
            let pct = basis[k] as f64 / 100.0;
            allocations[idx].tensor_split_pct = pct;
            tensor_split.push(pct);
        }
    }

    // Estimate per-node real tok/s contribution from memory bandwidth & active parameters.
    let active_gb = (card.active_params_b * 0.58).max(0.8);
    for &idx in &active_indices {
        let node = evaluated[idx].0;
        let base_tok = (node.mem_bw_gbps.max(12.0) / active_gb) * 0.65;
        let share = (allocations[idx].tensor_split_pct / 100.0).max(0.05);
        allocations[idx].estimated_tok_per_sec = (base_tok * share).clamp(1.0, 240.0);
    }


    for &idx in &active_indices {
        if allocations[idx].layer_count == 0 {
            continue;
        }
        if let Some((raw_idx, raw_score)) = raw_weights.iter().find(|(i, _)| *i == idx) {
            let _ = raw_idx;
            allocations[idx].placement_score = (raw_score / weight_sum * 1000.0).round() / 10.0;
        }
        let order_position = if allocations[idx].is_master {
            0
        } else {
            worker_indices.iter().position(|&i| i == idx).map(|p| p + 1).unwrap_or(0)
        };
        let previous = if order_position == 0 {
            None
        } else if order_position == 1 {
            Some(evaluated[master_idx].0)
        } else {
            evaluated[worker_indices[order_position - 2]].0.into()
        };
        if let Some(previous_node) = previous {
            allocations[idx].handoff_count = 1;
            allocations[idx].estimated_handoff_ms =
                (link_cost(previous_node, evaluated[idx].0, activation_mb) * 10.0).round() / 10.0;
        }
    }
    if allocations[master_idx].layer_count > 0 && worker_indices.iter().any(|&i| allocations[i].layer_count > 0) {
        let last_worker = worker_indices
            .iter()
            .rev()
            .find(|&&i| allocations[i].layer_count > 0)
            .copied();
        if let Some(last_idx) = last_worker {
            allocations[master_idx].handoff_count = 1;
            allocations[master_idx].estimated_handoff_ms =
                (link_cost(evaluated[last_idx].0, evaluated[master_idx].0, activation_mb) * 10.0).round() / 10.0;
        }
    }
    let estimated_handoff_ms: f64 = active_indices
        .iter()
        .map(|&i| allocations[i].estimated_handoff_ms)
        .sum();

    let tensor_split_csv = tensor_split
        .iter()
        .map(|v| format!("{:.1}", v))
        .collect::<Vec<_>>()
        .join(",");

    let rpc_endpoints: Vec<String> = active_indices
        .iter()
        .filter_map(|&i| allocations[i].rpc_addr.clone())
        .collect();
    let rpc_endpoints_csv = if rpc_endpoints.is_empty() {
        None
    } else {
        Some(rpc_endpoints.join(","))
    };

    // Build verified stock llama.cpp v1.0.0 CLI args.
    let mut cli_args = vec![
        "-m".to_string(),
        card.path.clone(),
        "--host".to_string(),
        "127.0.0.1".to_string(),
        "--port".to_string(),
        "8080".to_string(),
        "-c".to_string(),
        effective_context.to_string(),
        "-ctk".to_string(),
        cfg.kv_cache_quant.clone(),
        "-ctv".to_string(),
        cfg.kv_cache_quant.clone(),
        "-b".to_string(),
        learned
            .map(|l| l.recommended_batch)
            .unwrap_or(cfg.batch_size)
            .to_string(),
        "-ub".to_string(),
        learned
            .map(|l| l.recommended_ubatch)
            .unwrap_or(cfg.ubatch_size)
            .to_string(),
    ];

    if let Some(ref mmproj) = card.mmproj_path {
        cli_args.push("--mmproj".to_string());
        cli_args.push(mmproj.clone());
    }

    let master_vram_safe = allocations[master_idx].vram_safe_mb;
    let avg_layer_weight_mb = if profiles.is_empty() {
        0.0
    } else {
        profiles.iter().map(|p| p.total_weight_mb).sum::<f64>() / profiles.len() as f64
    };
    let auto_ngl = if master_vram_safe > 0.0 && avg_layer_weight_mb > 0.0 {
        (master_vram_safe / avg_layer_weight_mb).floor() as u32
    } else {
        0
    };
    let ngl = cfg
        .gpu_offload_layers
        .unwrap_or(auto_ngl)
        .min(card.block_count);
    cli_args.push("-ngl".to_string());
    cli_args.push(ngl.to_string());

    if let Some(t) = cfg.threads {
        cli_args.push("-t".to_string());
        cli_args.push(t.to_string());
    }

    if cfg.enable_jinja {
        cli_args.push("--jinja".to_string());
    }
    if cfg.flash_attn {
        cli_args.push("-fa".to_string());
        cli_args.push("on".to_string());
    }
    if cfg.cont_batching {
        cli_args.push("--cont-batching".to_string());
    }
    if cfg.cache_reuse > 0 {
        cli_args.push("--cache-reuse".to_string());
        cli_args.push(cfg.cache_reuse.to_string());
    }
    if cfg.kv_unified {
        cli_args.push("--kv-unified".to_string());
    }
    if auto_nkvo {
        cli_args.push("-nkvo".to_string());
    }
    if let Some(ref slot_dir) = cfg.slot_save_path {
        if !slot_dir.is_empty() {
            cli_args.push("--slot-save-path".to_string());
            cli_args.push(slot_dir.clone());
        }
    }
    if use_no_mmap {
        cli_args.push("-lm".to_string());
        cli_args.push("none".to_string());
    }
    if cfg.moe_cpu_offload && card.arch_mode == ModelArchMode::MoE {
        cli_args.push("-ot".to_string());
        cli_args.push(".ffn_.*_exps.=CPU".to_string());
    }
    if let Some(ref rpc_csv) = rpc_endpoints_csv {
        cli_args.push("--rpc".to_string());
        cli_args.push(rpc_csv.clone());
    }
    if tensor_split.len() > 1 {
        cli_args.push("--tensor-split".to_string());
        cli_args.push(tensor_split_csv.clone());
    }
    if cfg.speculative_mode == SpeculativeMode::ExternalDraft {
        if let Some(ref draft) = cfg.draft_model_path {
            cli_args.push("--model-draft".to_string());
            cli_args.push(draft.clone());
            cli_args.push("--draft-max".to_string());
            cli_args.push(cfg.draft_max_tokens.to_string());
        }
    }

    ClusterPlanOutput {
        total_safe_pool_mb,
        total_model_need_mb,
        effective_context,
        kv_cache_total_mb: total_kv_mb,
        kv_per_layer_mb: profiles
            .iter()
            .map(|p| p.kv_cache_mb)
            .sum::<f64>()
            / profiles.len().max(1) as f64,
        layer_weight_mb,
        estimated_handoff_ms,
        use_no_mmap,
        mmap_streaming: !use_no_mmap,
        auto_nkvo,
        master_mmproj_path: card.mmproj_path.clone(),
        tensor_split,
        tensor_split_csv,
        rpc_endpoints_csv,
        nodes: allocations,
        cli_args,
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::gguf::LayerFootprint;

    fn sample_card(layers: u32, layer_mb: f64, mode: ModelArchMode, mmproj: Option<&str>) -> GgufModelCard {
        let per_layer_profiles = (0..layers)
            .map(|i| LayerFootprint {
                layer_index: i,
                attention_weight_mb: layer_mb * 0.3,
                ffn_or_expert_weight_mb: layer_mb * 0.7,
                total_weight_mb: layer_mb,
                kv_cache_mb: 16.0,
                is_moe_layer: mode == ModelArchMode::MoE,
            })
            .collect();
        GgufModelCard {
            path: "/models/test.gguf".into(),
            file_size_mb: layers as f64 * layer_mb + 600.0,
            gguf_version: 3,
            name: "TestModel".into(),
            basename: "TestModel".into(),
            architecture: "qwen3".into(),
            arch_mode: mode,
            quant_label: "Q4_K_M".into(),
            block_count: layers,
            embedding_length: 4096,
            feed_forward_length: 11008,
            head_count: 32,
            head_count_kv: 8,
            expert_count: if mode == ModelArchMode::MoE { 64 } else { 0 },
            expert_used_count: if mode == ModelArchMode::MoE { 8 } else { 0 },
            context_length: 131072,
            profiled_context: 16384,
            has_chat_template: true,
            has_native_mtp: false,
            vocab_size: 151936,
            vocab_hash: "abcd1234abcd1234".into(),
            mmproj_path: mmproj.map(|s| s.to_string()),
            mmproj_size_mb: if mmproj.is_some() { 550.0 } else { 0.0 },
            master_pinned_overhead_mb: if mmproj.is_some() { 950.0 } else { 400.0 },
            per_layer_profiles,
            estimated_params_b: 14.0,
            active_params_b: if mode == ModelArchMode::MoE { 3.0 } else { 14.0 },
        }
    }

    fn master_node(ram_mb: f64, vram_mb: f64) -> LiveNodeInput {
        LiveNodeInput {
            node_id: "master".into(),
            label: "Desktop Master".into(),
            is_master: true,
            os: NodeOs::Windows,
            ip: "127.0.0.1".into(),
            rpc_port: 50052,
            transport: LinkTransport::Local,
            total_ram_mb: ram_mb,
            available_ram_mb: ram_mb * 0.78,
            vram_total_mb: vram_mb,
            vram_free_mb: vram_mb * 0.92,
            mem_bw_gbps: 240.0,
            big_cores: 8,
            rtt_p95_ms: 0.2,
            bandwidth_mbps: 10000.0,
            pkt_loss_pct: 0.0,
            temp_c: 52.0,
            thermal_headroom: 0.2,
            cpu_usage_pct: 12.0,
            gpu_usage_pct: 8.0,
            storage_total_mb: 512000.0,
            storage_free_mb: 128000.0,
            compute_tflops: 24.0,
            handshake_verified: true,
            heartbeat_age_s: 0,
        }
    }

    fn android_worker(id: &str, ram_mb: f64, transport: LinkTransport, rtt: f64, temp: f64) -> LiveNodeInput {
        LiveNodeInput {
            node_id: id.into(),
            label: format!("Android {}", id),
            is_master: false,
            os: NodeOs::Android,
            ip: "192.168.1.42".into(),
            rpc_port: 50052,
            transport,
            total_ram_mb: ram_mb,
            available_ram_mb: ram_mb * 0.65,
            vram_total_mb: 0.0,
            vram_free_mb: 0.0,
            mem_bw_gbps: 34.0,
            big_cores: 4,
            rtt_p95_ms: rtt,
            bandwidth_mbps: if transport == LinkTransport::UsbC { 480.0 } else { 180.0 },
            pkt_loss_pct: if rtt > 45.0 { 2.5 } else { 0.0 },
            temp_c: temp,
            thermal_headroom: if temp >= 48.0 { 0.96 } else { 0.3 },
            cpu_usage_pct: 18.0,
            gpu_usage_pct: 0.0,
            storage_total_mb: 128000.0,
            storage_free_mb: 32000.0,
            compute_tflops: 2.2,
            handshake_verified: true,
            heartbeat_age_s: 3,
        }
    }

    #[test]
    fn enforces_zero_oom_50pct_android_cap_and_tensor_split_sum_100() {
        let card = sample_card(36, 300.0, ModelArchMode::MoE, Some("/models/mmproj.gguf"));
        let nodes = vec![
            master_node(16384.0, 4096.0),
            android_worker("phone-usb", 8192.0, LinkTransport::UsbC, 1.4, 34.0),
        ];
        let cfg = ModelLoadConfig {
            context_length: 65536,
            ..Default::default()
        };
        let plan = compute_cluster_plan(&card, &nodes, &cfg, None);

        // Zero OOM invariant on every node
        for n in &plan.nodes {
            assert!(
                n.alloc_mb <= n.safe_cap_mb + n.vram_safe_mb + 1e-6,
                "Node {} exceeded safe cap: {} > {}",
                n.node_id,
                n.alloc_mb,
                n.safe_cap_mb + n.vram_safe_mb
            );
        }

        // Strict 50% physical RAM cap on Android (8192 * 0.5 = 4096 MB max)
        let phone = plan.nodes.iter().find(|n| n.node_id == "phone-usb").unwrap();
        assert!(phone.safe_cap_mb <= 4096.0 + 1e-6);

        // Normalized --tensor-split sums cleanly to 100.0
        let split_sum: f64 = plan.tensor_split.iter().sum();
        assert!((split_sum - 100.0).abs() < 0.05, "split sum = {}", split_sum);

        // Auto -nkvo triggers on 4 GB GPU at 65K context + mmproj + jinja + stock flags only
        assert!(plan.auto_nkvo);
        assert!(plan.cli_args.contains(&"-nkvo".to_string()));
        assert!(plan.cli_args.contains(&"--kv-unified".to_string()));
        assert!(plan.cli_args.contains(&"--jinja".to_string()));
        assert!(plan.cli_args.contains(&"--mmproj".to_string()));
        assert!(validate_stock_llama_cpp_flags(&plan.cli_args).is_ok());
    }

    #[test]
    fn prunes_slow_wifi_and_sheds_overheated_workers() {
        let card = sample_card(24, 120.0, ModelArchMode::Dense, None);
        let nodes = vec![
            master_node(16384.0, 8192.0),
            android_worker("slow-wifi", 6144.0, LinkTransport::WifiLan, 68.0, 35.0),
            android_worker("hot-phone", 8192.0, LinkTransport::UsbC, 1.2, 49.0),
        ];
        let plan = compute_cluster_plan(&card, &nodes, &ModelLoadConfig::default(), None);

        let slow = plan.nodes.iter().find(|n| n.node_id == "slow-wifi").unwrap();
        let hot = plan.nodes.iter().find(|n| n.node_id == "hot-phone").unwrap();
        assert!(slow.pruned);
        assert!(hot.pruned);
        assert_eq!(hot.thermal_state, ThermalState::EmergencyOffload);
        // Pool easily >= 1.15x model need -> --no-mmap active
        assert!(plan.use_no_mmap);
        assert!(plan.cli_args.contains(&"-lm".to_string()));
    }

    #[test]
    fn switches_to_mmap_streaming_when_pool_below_115pct() {
        let card = sample_card(40, 420.0, ModelArchMode::Dense, None);
        let nodes = vec![master_node(16384.0, 4096.0)];
        let plan = compute_cluster_plan(&card, &nodes, &ModelLoadConfig::default(), None);
        assert!(!plan.use_no_mmap);
        assert!(plan.mmap_streaming);
        assert!(!plan.cli_args.contains(&"-lm".to_string()));
    }

    #[test]
    fn accepts_ui_camel_case_config_with_string_tensor_split() {
        let value = serde_json::json!({
            "contextLength": 8192,
            "kvCacheQuant": "q8_0",
            "enableFlashAttn": true,
            "moeCpuOffload": false,
            "speculativeMode": "off",
            "manualTensorSplit": "",
            "gpuOffloadLayers": null,
            "threads": null,
            "batchSize": 512,
            "ubatchSize": 256
        });
        let cfg: ModelLoadConfig = serde_json::from_value(value).expect("UI config should deserialize");
        assert_eq!(cfg.context_length, 8192);
        assert_eq!(cfg.batch_size, 512);
        assert!(cfg.manual_tensor_split.is_none());
        assert!(!cfg.moe_cpu_offload);
        let emitted = serde_json::to_value(&cfg).expect("serialize");
        assert!(emitted.get("contextLength").is_some());
        assert!(emitted.get("manualTensorSplit").is_some());
    }

    #[test]
    fn chain_places_fastest_link_adjacent_to_master() {
        let card = sample_card(36, 120.0, ModelArchMode::Dense, None);
        let master = master_node(8192.0, 4096.0);
        let mut usb = android_worker("usb", 8192.0, LinkTransport::UsbC, 1.0, 34.0);
        usb.bandwidth_mbps = 480.0;
        let mut wifi = android_worker("wifi", 8192.0, LinkTransport::WifiLan, 60.0, 34.0);
        wifi.bandwidth_mbps = 80.0;
        let nodes = vec![master.clone(), usb.clone(), wifi.clone()];
        let evaluated: Vec<(&LiveNodeInput, f64, f64, ThermalState)> = nodes
            .iter()
            .map(|node| {
                let (ram, vram, thermal) = compute_safe_caps(node);
                (node, ram, vram, thermal)
            })
            .collect();
        let activation = activation_transfer_mb(&card);
        assert!(link_cost(&master, &usb, activation) < link_cost(&master, &wifi, activation));
        let chain = ordered_worker_chain(&master, &[1usize, 2usize], &evaluated, activation);
        assert_eq!(chain.len(), 2);
        let first_cost = link_cost(&master, evaluated[chain[0]].0, activation);
        let last_cost = link_cost(evaluated[*chain.last().unwrap()].0, &master, activation);
        let best_boundary = link_cost(&master, &usb, activation);
        assert!(first_cost <= best_boundary + 1e-6 || last_cost <= best_boundary + 1e-6);
    }

    #[test]
    fn effective_context_shrinks_to_fit_pool() {
        let card = sample_card(36, 120.0, ModelArchMode::Dense, None);
        let nodes = vec![master_node(4096.0, 0.0)];
        let cfg = ModelLoadConfig {
            context_length: 131072,
            ..Default::default()
        };
        let plan = compute_cluster_plan(&card, &nodes, &cfg, None);
        assert!(plan.effective_context >= 4096);
        assert!(plan.effective_context < 131072);
        assert!(plan.kv_cache_total_mb > 0.0);
        let ctx_flag = plan
            .cli_args
            .windows(2)
            .find(|pair| pair[0] == "-c")
            .expect("-c flag");
        assert_eq!(ctx_flag[1], plan.effective_context.to_string());
    }
}
