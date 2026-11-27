//! Rust port of the client tuning advisor: adaptive load factor, health score and per-worker auto-config.
//! Kept dependency-free (serde_json only) so it can ride inside the existing /api/cluster/state JSON.

use crate::gguf::GgufModelCard;
use crate::planner::{LiveNodeInput, ModelLoadConfig};
use serde_json::{json, Value};

const TEMP_THRESHOLD_C: f64 = 40.0;
const HOT_THRESHOLD_C: f64 = 48.0;
const CRITICAL_THRESHOLD_C: f64 = 52.0;

#[derive(Debug, Clone)]
pub struct AdaptiveLoadFactor {
    pub factor: f64,
    pub effective_temp_c: f64,
    pub capped: bool,
    pub cap: u32,
    pub band: &'static str,
    pub reason: String,
}

pub fn calculate_adaptive_load_factor(
    temp_c: f64,
    headroom: f64,
    base_concurrency: u32,
) -> AdaptiveLoadFactor {
    let base = base_concurrency.max(1);
    let effective = if temp_c > 0.0 && temp_c.is_finite() {
        temp_c
    } else {
        34.0
    };
    let rounded = (effective * 10.0).round() / 10.0;

    if effective < TEMP_THRESHOLD_C {
        return AdaptiveLoadFactor {
            factor: 1.0,
            effective_temp_c: rounded,
            capped: false,
            cap: base,
            band: "nominal",
            reason: format!(
                "Device {:.1}C < 40C - Adaptive Load Factor 1.00 (uncapped {} threads)",
                effective, base
            ),
        };
    }
    if effective >= CRITICAL_THRESHOLD_C || (headroom >= 1.0 && effective >= HOT_THRESHOLD_C) {
        return AdaptiveLoadFactor {
            factor: 0.0,
            effective_temp_c: rounded,
            capped: true,
            cap: 1,
            band: "critical_shed",
            reason: format!(
                "Critical heat {:.1}C (>=52C) - Adaptive Load Factor 0.00 (capped to 1 thread)",
                effective
            ),
        };
    }
    if effective > HOT_THRESHOLD_C {
        let progress = (effective - HOT_THRESHOLD_C) / (CRITICAL_THRESHOLD_C - HOT_THRESHOLD_C);
        let raw = (0.45 - progress * 0.25).clamp(0.2, 0.45);
        let factor = (raw * 100.0).round() / 100.0;
        let computed = (base as f64 * factor).floor().max(1.0) as u32;
        let cap = computed.min(if effective >= 50.0 { 1 } else { 2 });
        return AdaptiveLoadFactor {
            factor,
            effective_temp_c: rounded,
            capped: true,
            cap,
            band: "hot_throttle",
            reason: format!("Internal heat {:.1}C (>48C) - Adaptive Load Factor {:.2} (concurrency capped {} -> {})", effective, factor, base, cap),
        };
    }
    let progress = (effective - TEMP_THRESHOLD_C) / (HOT_THRESHOLD_C - TEMP_THRESHOLD_C);
    let penalty = if headroom >= 0.85 { 0.1 } else { 0.0 };
    let raw = (0.9 - progress * 0.45 - penalty).clamp(0.45, 0.9);
    let factor = (raw * 100.0).round() / 100.0;
    let mut cap = (base as f64 * factor).floor().max(1.0) as u32;
    if base > 1 && cap >= base {
        cap = base.saturating_sub(1).max(1);
    }
    AdaptiveLoadFactor {
        factor,
        effective_temp_c: rounded,
        capped: true,
        cap,
        band: "warm_cap",
        reason: format!(
            "Device {:.1}C >= 40C - Adaptive Load Factor {:.2} (concurrency capped {} -> {})",
            effective, factor, base, cap
        ),
    }
}

fn as_f64(value: Option<&Value>) -> f64 {
    value.and_then(Value::as_f64).unwrap_or(0.0)
}

fn as_u32(value: Option<&Value>, default: u32) -> u32 {
    value.and_then(Value::as_u64).unwrap_or(default as u64) as u32
}

fn push_item(
    items: &mut Vec<Value>,
    id: &str,
    severity: &str,
    title: impl Into<String>,
    detail: impl Into<String>,
    impact: &str,
) {
    items.push(json!({
        "id": id, "severity": severity, "title": title.into(), "detail": detail.into(), "impact": impact
    }));
}

pub fn analyze(
    plan: &Value,
    card: &GgufModelCard,
    cfg: &ModelLoadConfig,
    nodes: &[LiveNodeInput],
) -> Value {
    let plan_nodes = plan
        .get("nodes")
        .and_then(Value::as_array)
        .cloned()
        .unwrap_or_default();
    let master_node = plan_nodes
        .iter()
        .find(|n| n.get("role").and_then(Value::as_str) == Some("Master"))
        .or_else(|| plan_nodes.first());
    let workers: Vec<&Value> = plan_nodes
        .iter()
        .filter(|n| n.get("role").and_then(Value::as_str) == Some("Worker"))
        .collect();

    let master_vram_mb = as_f64(master_node.and_then(|n| n.get("vramTotalMb")))
        .max(as_f64(master_node.and_then(|n| n.get("vramFreeMb"))));
    let master_cores = as_u32(master_node.and_then(|n| n.get("physicalCores")), 6).max(2);
    let is_ternary = card.quant_label.starts_with("TQ");
    let mut penalty = 0i32;
    let mut items: Vec<Value> = Vec::new();
    let mut patch = serde_json::Map::new();

    // 1. Context / TTFT bottleneck.
    if (card.arch_mode == crate::gguf::ModelArchMode::Ternary || card.estimated_params_b >= 20.0)
        && cfg.context_length > 4096
    {
        penalty += if master_vram_mb > 0.0 && master_vram_mb <= 4300.0 {
            28
        } else {
            16
        };
        patch.insert("contextLength".into(), json!(4096));
        push_item(
            &mut items,
            "reduce-context-ttft",
            if master_vram_mb > 0.0 && master_vram_mb <= 4300.0 { "critical" } else { "warning" },
            format!("Reduce Context (-c {} -> 4,096) for faster TTFT", cfg.context_length),
            format!(
                "{} ({:.1}B) at {} tokens needs {} MB KV cache; reducing context cuts TTFT and VRAM pressure.",
                card.name, card.estimated_params_b, cfg.context_length,
                as_f64(plan.get("kvCacheTotalMb"))
            ),
            "Faster TTFT and lower VRAM pressure",
        );
    }

    // 2. Explicit physical thread count.
    let master_os = master_node
        .and_then(|n| n.get("osType"))
        .and_then(Value::as_str)
        .unwrap_or("linux");
    let recommended_threads = master_cores
        .min(if master_os == "windows" { 12 } else { 16 })
        .max(2);
    let cfg_threads = cfg.threads.unwrap_or(0);
    if cfg_threads == 0 || cfg_threads > master_cores * 3 / 2 || cfg_threads < 2 {
        penalty += 14;
        patch.insert("threads".into(), json!(recommended_threads));
        push_item(
            &mut items,
            "pin-physical-threads",
            "warning",
            format!("Pin CPU Threads (-t {}) to physical cores", recommended_threads),
            format!("Unpinned or oversubscribed threads cause SMT contention. Pinning to {} physical cores maximizes memory bandwidth.", recommended_threads),
            "+25-45% token generation on hybrid CPU+GPU layers",
        );
    }

    // 3. Ternary non-native flash attention / KV quant.
    if card.arch_mode == crate::gguf::ModelArchMode::Ternary
        && !is_ternary
        && !cfg.flash_attn
        && master_vram_mb > 0.0
    {
        penalty += 22;
        patch.insert("enableFlashAttn".into(), json!(true));
        patch.insert("kvCacheQuant".into(), json!("q8_0"));
        push_item(
            &mut items,
            "enable-ternary-fa",
            "critical",
            format!("Enable FlashAttention for {} Ternary GPU offload", card.quant_label),
            format!("{} uses standard {} weights, not native TQ1_0; FlashAttention unlocks quantized Q8_0 KV on CUDA.", card.name, card.quant_label),
            "Eliminates GPU stall and unlocks CUDA attention kernels",
        );
    }

    // 4. 4 GB VRAM headroom: manual ngl and batch size.
    if master_vram_mb > 0.0 && master_vram_mb <= 4300.0 {
        let safe_ngl = as_u32(master_node.and_then(|n| n.get("nglLayers")), 0);
        if let Some(manual) = cfg.gpu_offload_layers {
            if manual > safe_ngl {
                penalty += 30;
                patch.insert("gpuOffloadLayers".into(), Value::Null);
                push_item(
                    &mut items,
                    "clamp-vram-ngl",
                    "critical",
                    format!("Manual -ngl {} exceeds 4 GB VRAM ({}L safe max)", manual, safe_ngl),
                    "Exceeding dedicated VRAM spills weights into shared memory over PCIe, collapsing GPU utilization.",
                    "Prevents WDDM PCIe thrashing",
                );
            }
        }
        if cfg.batch_size > 512
            && (card.file_size_mb >= 8000.0
                || card.arch_mode == crate::gguf::ModelArchMode::Ternary)
        {
            penalty += 10;
            patch.insert("batchSize".into(), json!(512));
            patch.insert("ubatchSize".into(), json!(256));
            push_item(
                &mut items,
                "tune-batch-4gb",
                "warning",
                "Tune Prompt Batch (-b 512 -ub 256) for 4 GB VRAM scratch budget",
                "Lower batch scratch leaves VRAM room for extra GPU layers and avoids prompt-eval stalls.",
                "Smoother TTFT and lower peak VRAM",
            );
        }
    }

    // 5. Per-worker adaptive configs.
    let mut cluster_alf = 1.0f64;
    let mut worker_configs = serde_json::Map::new();
    for plan_node in &workers {
        let node_id = plan_node
            .get("nodeId")
            .and_then(Value::as_str)
            .unwrap_or("")
            .to_string();
        let label = plan_node
            .get("label")
            .and_then(Value::as_str)
            .unwrap_or(&node_id)
            .to_string();
        let is_android = plan_node.get("osType").and_then(Value::as_str) == Some("android");
        let input = nodes.iter().find(|n| n.node_id == node_id);
        let temp_c = as_f64(plan_node.get("tempC")).max(input.map(|n| n.temp_c).unwrap_or(0.0));
        let headroom = input.map(|n| n.thermal_headroom).unwrap_or(0.0);
        let raw_cores = as_u32(plan_node.get("physicalCores"), 4).max(2);
        let base = if is_android {
            raw_cores.min(6).max(2)
        } else {
            raw_cores.min(12).max(2)
        };
        let alf = calculate_adaptive_load_factor(temp_c, headroom, base);
        cluster_alf = cluster_alf.min(alf.factor);

        let thermal_policy = if alf.effective_temp_c >= 41.5 {
            "cooldown"
        } else if alf.effective_temp_c >= 39.0 {
            "balanced"
        } else {
            "performance"
        };
        let over_hot = alf.effective_temp_c > HOT_THRESHOLD_C;
        let critical = alf.effective_temp_c >= CRITICAL_THRESHOLD_C;
        let allocated_layers = as_u32(plan_node.get("allocatedLayers"), 0);
        let (recommended_threads, safe_ram_pct, context_tokens, load_scale, priority, prefer_gpu) =
            if is_android {
                let policy_threads = if critical {
                    1
                } else if over_hot {
                    if alf.effective_temp_c >= 50.0 {
                        1
                    } else {
                        2
                    }
                } else {
                    match thermal_policy {
                        "cooldown" => 2,
                        "balanced" => base.saturating_sub(2).max(2),
                        _ => base,
                    }
                };
                let threads = if alf.capped {
                    policy_threads.min(alf.cap)
                } else {
                    policy_threads
                };
                let ram_pct = if as_u32(plan_node.get("totalRamMb"), 0) <= 2048 {
                    40
                } else if critical {
                    25
                } else if over_hot {
                    35
                } else {
                    match thermal_policy {
                        "cooldown" => 40,
                        "balanced" => 35,
                        _ => 50,
                    }
                };
                let ctx = if critical {
                    512
                } else if over_hot {
                    1024
                } else {
                    match thermal_policy {
                        "cooldown" => 2048,
                        "balanced" => 3072,
                        _ => {
                            if as_u32(plan_node.get("totalRamMb"), 0) <= 3072 {
                                2048
                            } else {
                                4096
                            }
                        }
                    }
                };
                let policy_scale: f64 = if critical {
                    0.0
                } else if over_hot {
                    if alf.effective_temp_c >= 50.0 {
                        0.25
                    } else {
                        0.5
                    }
                } else {
                    match thermal_policy {
                        "cooldown" => 0.7,
                        "balanced" => 0.85,
                        _ => 1.0,
                    }
                };
                let driver = plan_node
                    .get("backendDriver")
                    .and_then(Value::as_str)
                    .unwrap_or("")
                    .to_ascii_lowercase();
                let gpu_ok = driver.contains("vulkan")
                    && !driver.contains("neon")
                    && !driver.contains("cpu");
                (
                    threads,
                    ram_pct,
                    ctx,
                    (policy_scale.min(alf.factor) * 100.0).round() / 100.0,
                    if alf.effective_temp_c < 40.0 && thermal_policy == "performance" {
                        "high"
                    } else if over_hot || thermal_policy == "cooldown" {
                        "low"
                    } else {
                        "normal"
                    },
                    thermal_policy != "cooldown" && !over_hot && gpu_ok,
                )
            } else {
                let threads = if alf.capped { base.min(alf.cap) } else { base };
                let floor = if alf.band == "critical_shed" {
                    25.0
                } else if alf.band == "hot_throttle" {
                    35.0
                } else {
                    50.0
                };
                let ram_pct = if alf.capped {
                    (75.0 * alf.factor).round().max(floor) as u32
                } else {
                    75
                };
                let ctx = if alf.band == "hot_throttle" || alf.band == "critical_shed" {
                    cfg.context_length.min(2048)
                } else {
                    cfg.context_length
                };
                (
                    threads,
                    ram_pct,
                    ctx,
                    alf.factor,
                    if alf.capped { "low" } else { "normal" },
                    as_f64(plan_node.get("vramTotalMb")) > 0.0 && alf.band != "critical_shed",
                )
            };

        if alf.capped {
            penalty += if over_hot {
                14
            } else if is_android {
                6
            } else {
                5
            };
            push_item(
                &mut items,
                &format!("adaptive-load-cap-{}", node_id),
                if over_hot { "critical" } else { "warning" },
                format!(
                    "{} Adaptive Load Factor {:.2} ({:.1}C >= 40C)",
                    label, alf.factor, alf.effective_temp_c
                ),
                alf.reason.clone(),
                &format!(
                    "Caps worker concurrency to {}/{} threads",
                    recommended_threads, base
                ),
            );
        } else if allocated_layers == 0 && !over_hot {
            penalty += 15;
            push_item(
                &mut items,
                &format!("worker-idle-{}", node_id),
                "warning",
                format!("{} has 0L active in RAM Residency", label),
                format!(
                    "Worker is connected but idle; auto-config can assign layers and {} threads.",
                    recommended_threads
                ),
                "Activates phone compute",
            );
        }

        worker_configs.insert(
            node_id.clone(),
            json!({
                "nodeId": node_id,
                "label": label,
                "recommendedThreads": recommended_threads,
                "recommendedSafeRamPct": safe_ram_pct,
                "recommendedContextTokens": context_tokens,
                "loadAllocationScale": load_scale,
                "adaptiveLoadFactor": (alf.factor * 100.0).round() / 100.0,
                "concurrencyCap": alf.cap,
                "concurrencyCapped": alf.capped,
                "effectiveTempC": alf.effective_temp_c,
                "threadPriority": priority,
                "preferGpu": prefer_gpu,
                "assignedLayers": allocated_layers,
                "layerStart": as_u32(plan_node.get("layerStart"), 0),
                "layerEnd": as_u32(plan_node.get("layerEnd"), 0),
                "allocatedMb": as_u32(plan_node.get("allocatedMb"), 0),
                "thermalPolicy": thermal_policy,
                "reason": alf.reason,
            }),
        );
    }

    if items.is_empty() {
        push_item(
            &mut items,
            "cluster-optimal",
            "optimal",
            "Cluster tuning is optimal for current hardware",
            format!(
                "Master ({}L GPU, -t {}, -c {}) and {} worker(s) are balanced within safe thermal & VRAM budgets (ALF {:.2}).",
                as_u32(master_node.and_then(|n| n.get("nglLayers")), 0),
                recommended_threads,
                cfg.context_length,
                workers.len(),
                cluster_alf
            ),
            "Peak sustained tok/s and fast TTFT",
        );
    }

    let health_score = (100 - penalty).clamp(15, 100);
    let has_actionable = !patch.is_empty();
    let summary = if has_actionable {
        format!(
            "Detected {} tuning bottleneck(s) - apply Auto-Tune to boost tok/s and cut TTFT.",
            items
                .iter()
                .filter(|i| i.get("severity").and_then(Value::as_str) != Some("optimal"))
                .count()
        )
    } else if cluster_alf < 1.0 {
        format!(
            "Adaptive Load Factor {:.2} active - capping warm worker concurrency above 40C.",
            cluster_alf
        )
    } else {
        "All VRAM, thread, context, and worker thermal parameters are tuned cleanly.".to_string()
    };

    json!({
        "healthScore": health_score,
        "summary": summary,
        "items": items,
        "autoTunePatch": Value::Object(patch),
        "workerConfigs": Value::Object(worker_configs),
        "clusterAdaptiveLoadFactor": (cluster_alf * 100.0).round() / 100.0,
        "hasActionableChanges": has_actionable,
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn adaptive_load_factor_bands_match_ts() {
        let nominal = calculate_adaptive_load_factor(34.0, 0.0, 6);
        assert_eq!(nominal.factor, 1.0);
        assert!(!nominal.capped);
        let warm = calculate_adaptive_load_factor(44.0, 0.0, 6);
        assert!(warm.factor > 0.45 && warm.factor < 0.9);
        assert!(warm.capped);
        let hot = calculate_adaptive_load_factor(50.0, 0.0, 6);
        assert_eq!(hot.band, "hot_throttle");
        let critical = calculate_adaptive_load_factor(53.0, 0.0, 6);
        assert_eq!(critical.factor, 0.0);
        assert_eq!(critical.cap, 1);
    }
}
