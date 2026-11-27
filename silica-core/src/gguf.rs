//! Real binary GGUF v2/v3 header & tensor-table parser, model-card inspector,
//! companion `*mmproj*.gguf` auto-detector, non-uniform per-layer memory profiler,
//! and deterministic vocabulary hash calculator for speculative draft validation.

use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::fs::{self, File};
use std::io::{BufReader, Read, Seek, SeekFrom};
use std::path::{Path, PathBuf};

const GGUF_MAGIC: &[u8; 4] = b"GGUF";

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub enum ModelArchMode {
    Dense,
    MoE,
    Ternary,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct LayerFootprint {
    pub layer_index: u32,
    pub attention_weight_mb: f64,
    pub ffn_or_expert_weight_mb: f64,
    pub total_weight_mb: f64,
    pub kv_cache_mb: f64,
    pub is_moe_layer: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct GgufModelCard {
    pub path: String,
    pub file_size_mb: f64,
    pub gguf_version: u32,
    pub name: String,
    pub basename: String,
    pub architecture: String,
    pub arch_mode: ModelArchMode,
    pub quant_label: String,
    pub block_count: u32,
    pub embedding_length: u32,
    pub feed_forward_length: u32,
    pub head_count: u32,
    pub head_count_kv: u32,
    pub expert_count: u32,
    pub expert_used_count: u32,
    pub context_length: u32,
    pub profiled_context: u32,
    pub has_chat_template: bool,
    pub has_native_mtp: bool,
    pub vocab_size: u32,
    pub vocab_hash: String,
    pub mmproj_path: Option<String>,
    pub mmproj_size_mb: f64,
    pub master_pinned_overhead_mb: f64,
    pub per_layer_profiles: Vec<LayerFootprint>,
    pub estimated_params_b: f64,
    pub active_params_b: f64,
}

#[derive(Debug, Clone)]
enum GgufValue {
    U8(u8),
    I8(i8),
    U16(u16),
    I16(i16),
    U32(u32),
    I32(i32),
    F32(f32),
    Bool(bool),
    Str(String),
    U64(u64),
    I64(i64),
    F64(f64),
    ArraySummary { len: u64, hash: u64 },
}

fn read_u8<R: Read>(r: &mut R) -> std::io::Result<u8> {
    let mut b = [0u8; 1];
    r.read_exact(&mut b)?;
    Ok(b[0])
}
fn read_u16<R: Read>(r: &mut R) -> std::io::Result<u16> {
    let mut b = [0u8; 2];
    r.read_exact(&mut b)?;
    Ok(u16::from_le_bytes(b))
}
fn read_u32<R: Read>(r: &mut R) -> std::io::Result<u32> {
    let mut b = [0u8; 4];
    r.read_exact(&mut b)?;
    Ok(u32::from_le_bytes(b))
}
fn read_u64<R: Read>(r: &mut R) -> std::io::Result<u64> {
    let mut b = [0u8; 8];
    r.read_exact(&mut b)?;
    Ok(u64::from_le_bytes(b))
}
fn read_string<R: Read>(r: &mut R) -> std::io::Result<String> {
    let len = read_u64(r)? as usize;
    if len > 4 * 1024 * 1024 {
        return Err(std::io::Error::new(
            std::io::ErrorKind::InvalidData,
            "GGUF string exceeds 4 MiB bound",
        ));
    }
    let mut buf = vec![0u8; len];
    r.read_exact(&mut buf)?;
    Ok(String::from_utf8_lossy(&buf).into_owned())
}

fn fnv1a_update(mut hash: u64, bytes: &[u8]) -> u64 {
    for &b in bytes {
        hash ^= b as u64;
        hash = hash.wrapping_mul(0x100000001b3);
    }
    hash
}

fn read_value<R: Read + Seek>(
    r: &mut R,
    vtype: u32,
    hash_strings: bool,
) -> std::io::Result<GgufValue> {
    match vtype {
        0 => Ok(GgufValue::U8(read_u8(r)?)),
        1 => Ok(GgufValue::I8(read_u8(r)? as i8)),
        2 => Ok(GgufValue::U16(read_u16(r)?)),
        3 => Ok(GgufValue::I16(read_u16(r)? as i16)),
        4 => Ok(GgufValue::U32(read_u32(r)?)),
        5 => Ok(GgufValue::I32(read_u32(r)? as i32)),
        6 => Ok(GgufValue::F32(f32::from_bits(read_u32(r)?))),
        7 => Ok(GgufValue::Bool(read_u8(r)? != 0)),
        8 => Ok(GgufValue::Str(read_string(r)?)),
        9 => {
            let elem_type = read_u32(r)?;
            let len = read_u64(r)?;
            let mut h = 0xcbf29ce484222325u64;
            h = fnv1a_update(h, &len.to_le_bytes());
            match elem_type {
                8 => {
                    for _ in 0..len {
                        let slen = read_u64(r)?;
                        if hash_strings && slen <= 1024 {
                            let mut sbuf = vec![0u8; slen as usize];
                            r.read_exact(&mut sbuf)?;
                            h = fnv1a_update(h, &sbuf);
                            h = fnv1a_update(h, &[0xff]);
                        } else {
                            r.seek(SeekFrom::Current(slen as i64))?;
                        }
                    }
                }
                0 | 1 | 7 => {
                    r.seek(SeekFrom::Current(len as i64))?;
                }
                2 | 3 => {
                    r.seek(SeekFrom::Current((len * 2) as i64))?;
                }
                4 | 5 | 6 => {
                    r.seek(SeekFrom::Current((len * 4) as i64))?;
                }
                10 | 11 | 12 => {
                    r.seek(SeekFrom::Current((len * 8) as i64))?;
                }
                _ => {
                    return Err(std::io::Error::new(
                        std::io::ErrorKind::InvalidData,
                        format!("Unsupported GGUF array element type {}", elem_type),
                    ));
                }
            }
            Ok(GgufValue::ArraySummary { len, hash: h })
        }
        10 => Ok(GgufValue::U64(read_u64(r)?)),
        11 => Ok(GgufValue::I64(read_u64(r)? as i64)),
        12 => Ok(GgufValue::F64(f64::from_bits(read_u64(r)?))),
        _ => Err(std::io::Error::new(
            std::io::ErrorKind::InvalidData,
            format!("Unsupported GGUF value type {}", vtype),
        )),
    }
}

/// Bytes per element (average) for standard GGML tensor types.
fn ggml_type_bytes_per_elem(t: u32) -> (f64, &'static str) {
    match t {
        0 => (4.0, "F32"),
        1 => (2.0, "F16"),
        2 => (18.0 / 32.0, "Q4_0"),
        3 => (20.0 / 32.0, "Q4_1"),
        6 => (22.0 / 32.0, "Q5_0"),
        7 => (24.0 / 32.0, "Q5_1"),
        8 => (34.0 / 32.0, "Q8_0"),
        10 => (84.0 / 256.0, "Q2_K"),
        11 => (110.0 / 256.0, "Q3_K"),
        12 => (144.0 / 256.0, "Q4_K_M"),
        13 => (176.0 / 256.0, "Q5_K_M"),
        14 => (210.0 / 256.0, "Q6_K"),
        15 => (292.0 / 256.0, "Q8_K"),
        16 => (66.0 / 256.0, "IQ2_XXS"),
        19 => (136.0 / 256.0, "IQ4_NL"),
        30 => (2.0, "BF16"),
        34 => (54.0 / 256.0, "TQ1_0"),
        35 => (66.0 / 256.0, "TQ2_0"),
        other => {
            tracing::warn!("Unknown GGML tensor type {} — using conservative 0.5625 B/element estimate", other);
            (0.5625, "Q4_K_M")
        }
    }
}

fn file_type_to_quant_label(ft: u32) -> &'static str {
    match ft {
        0 => "F32",
        1 => "F16",
        2 => "Q4_0",
        3 => "Q4_1",
        7 => "Q8_0",
        10 => "Q2_K",
        12 => "Q3_K_M",
        15 => "Q4_K_M",
        17 => "Q5_K_M",
        18 => "Q6_K",
        36 => "TQ1_0",
        37 => "TQ2_0",
        _ => {
            tracing::warn!("Unknown GGUF file_type {} — reporting Q4_K_M estimate", ft);
            "Q4_K_M"
        }
    }
}

fn kv_bytes_per_elem(kv_quant: &str) -> f64 {
    match kv_quant.to_ascii_lowercase().as_str() {
        "q4_0" | "q4_1" => 0.5625,
        "q8_0" => 1.0625,
        _ => 2.0,
    }
}

/// Auto-detects companion `*mmproj*.gguf` in the same or parent directory.
pub fn find_companion_mmproj(model_path: &Path) -> Option<(PathBuf, f64)> {
    let model_name = model_path
        .file_name()
        .and_then(|s| s.to_str())
        .unwrap_or("")
        .to_ascii_lowercase();
    if model_name.contains("mmproj") {
        return None;
    }

    let mut search_dirs = Vec::new();
    if let Some(dir) = model_path.parent() {
        search_dirs.push(dir.to_path_buf());
        if let Some(parent) = dir.parent() {
            search_dirs.push(parent.to_path_buf());
        }
    }

    let stem_prefix: String = model_name
        .split(|c: char| c == '-' || c == '_' || c == '.')
        .next()
        .unwrap_or("")
        .to_string();

    let mut best: Option<(i32, PathBuf, f64)> = None;
    for dir in search_dirs {
        let Ok(entries) = fs::read_dir(&dir) else {
            continue;
        };
        for entry in entries.flatten() {
            let path = entry.path();
            let Some(fname) = path.file_name().and_then(|s| s.to_str()) else {
                continue;
            };
            let lower = fname.to_ascii_lowercase();
            if lower.ends_with(".gguf") && lower.contains("mmproj") {
                let size_mb = entry
                    .metadata()
                    .map(|m| m.len() as f64 / (1024.0 * 1024.0))
                    .unwrap_or(0.0);
                let mut score = 10;
                if !stem_prefix.is_empty() && lower.contains(&stem_prefix) {
                    score += 50;
                }
                if path.parent() == model_path.parent() {
                    score += 20;
                }
                if best.as_ref().map(|b| score > b.0).unwrap_or(true) {
                    best = Some((score, path, size_mb));
                }
            }
        }
    }
    best.map(|(_, p, mb)| (p, mb))
}

/// Parses a binary GGUF v2/v3 file header + tensor table to construct a full ModelCard
/// with non-uniform per-layer memory footprints and auto-paired companion mmproj.
pub fn inspect_gguf_file(
    path: &Path,
    target_context: u32,
    kv_quant: &str,
) -> Result<GgufModelCard, String> {
    let meta = fs::metadata(path).map_err(|e| format!("Cannot stat {}: {}", path.display(), e))?;
    let file_size_mb = meta.len() as f64 / (1024.0 * 1024.0);
    let file = File::open(path).map_err(|e| format!("Cannot open {}: {}", path.display(), e))?;
    let mut reader = BufReader::new(file);

    let mut magic = [0u8; 4];
    reader
        .read_exact(&mut magic)
        .map_err(|e| format!("Failed reading GGUF magic: {}", e))?;
    if &magic != GGUF_MAGIC {
        return Err("Not a valid GGUF binary file (bad magic)".to_string());
    }

    let version = read_u32(&mut reader).map_err(|e| format!("Bad GGUF version: {}", e))?;
    if !(2..=3).contains(&version) {
        return Err(format!("Unsupported GGUF version {}", version));
    }

    let tensor_count = read_u64(&mut reader).map_err(|e| format!("Bad tensor_count: {}", e))?;
    let kv_count = read_u64(&mut reader).map_err(|e| format!("Bad kv_count: {}", e))?;

    let mut kv_map: HashMap<String, GgufValue> = HashMap::new();
    for _ in 0..kv_count.min(4096) {
        let key = read_string(&mut reader).map_err(|e| format!("Bad KV key: {}", e))?;
        let vtype = read_u32(&mut reader).map_err(|e| format!("Bad KV type for {}: {}", key, e))?;
        let hash_tokens = key == "tokenizer.ggml.tokens";
        let val = read_value(&mut reader, vtype, hash_tokens)
            .map_err(|e| format!("Bad KV value for {}: {}", key, e))?;
        kv_map.insert(key, val);
    }

    let get_str = |k: &str| -> Option<String> {
        match kv_map.get(k) {
            Some(GgufValue::Str(s)) => Some(s.clone()),
            _ => None,
        }
    };
    let get_u32 = |k: &str| -> Option<u32> {
        match kv_map.get(k) {
            Some(GgufValue::U32(v)) => Some(*v),
            Some(GgufValue::I32(v)) => Some((*v).max(0) as u32),
            Some(GgufValue::U64(v)) => u32::try_from(*v).ok(),
            _ => None,
        }
    };

    let arch = get_str("general.architecture").unwrap_or_else(|| {
        kv_map
            .keys()
            .find_map(|k| k.strip_suffix(".block_count").map(|s| s.to_string()))
            .unwrap_or_else(|| "unknown".to_string())
    });
    let default_name = path
        .file_stem()
        .and_then(|s| s.to_str())
        .unwrap_or("custom-model")
        .to_string();
    let name = get_str("general.name").unwrap_or_else(|| default_name.clone());
    let basename = get_str("general.basename").unwrap_or_else(|| name.clone());

    let block_count = get_u32(&format!("{}.block_count", arch))
        .unwrap_or(32)
        .clamp(1, 4096);
    let embedding_length = get_u32(&format!("{}.embedding_length", arch)).unwrap_or(4096);
    let feed_forward_length = get_u32(&format!("{}.feed_forward_length", arch)).unwrap_or(11008);
    let head_count = get_u32(&format!("{}.attention.head_count", arch))
        .unwrap_or(32)
        .clamp(1, 1024);
    let head_count_kv = get_u32(&format!("{}.attention.head_count_kv", arch))
        .unwrap_or(head_count)
        .max(1);
    let expert_count = get_u32(&format!("{}.expert_count", arch)).unwrap_or(0);
    let expert_used_count = get_u32(&format!("{}.expert_used_count", arch)).unwrap_or(0);
    let context_length = get_u32(&format!("{}.context_length", arch)).unwrap_or(8192);
    let has_chat_template = kv_map.contains_key("tokenizer.chat_template");
    let has_native_mtp = get_u32(&format!("{}.nextn_predict_layers", arch)).unwrap_or(0) > 0
        || kv_map.keys().any(|k| k.contains("nextn") || k.contains("mtp"));

    let (vocab_size, vocab_hash) = match kv_map.get("tokenizer.ggml.tokens") {
        Some(GgufValue::ArraySummary { len, hash }) => (*len as u32, format!("{:016x}", hash)),
        _ => (32000, format!("{:016x}", fnv1a_update(0xcbf29ce484222325, arch.as_bytes()))),
    };

    let mut quant_label = get_u32("general.file_type")
        .map(file_type_to_quant_label)
        .unwrap_or("Q4_K_M")
        .to_string();

    // Parse tensor table for exact non-uniform per-layer byte sizes.
    let mut layer_attn_bytes = vec![0.0f64; block_count as usize];
    let mut layer_ffn_bytes = vec![0.0f64; block_count as usize];
    let mut master_pinned_bytes = 0.0f64;
    let mut saw_ternary_tensor = false;
    let mut parsed_tensors = 0u64;

    for _ in 0..tensor_count.min(32768) {
        let Ok(tname) = read_string(&mut reader) else {
            break;
        };
        let Ok(ndims) = read_u32(&mut reader) else {
            break;
        };
        let mut elems: u64 = 1;
        for _ in 0..ndims.min(8) {
            let Ok(d) = read_u64(&mut reader) else {
                break;
            };
            elems = elems.saturating_mul(d);
        }
        let Ok(ttype) = read_u32(&mut reader) else {
            break;
        };
        let Ok(_offset) = read_u64(&mut reader) else {
            break;
        };
        parsed_tensors += 1;

        let (bpe, tlabel) = ggml_type_bytes_per_elem(ttype);
        if ttype == 34 || ttype == 35 {
            saw_ternary_tensor = true;
            quant_label = tlabel.to_string();
        }
        let tensor_bytes = elems as f64 * bpe;

        if let Some(rest) = tname.strip_prefix("blk.") {
            if let Some((idx_str, suffix)) = rest.split_once('.') {
                if let Ok(idx) = idx_str.parse::<usize>() {
                    if idx < block_count as usize {
                        if suffix.contains("ffn") || suffix.contains("exp") {
                            layer_ffn_bytes[idx] += tensor_bytes;
                        } else {
                            layer_attn_bytes[idx] += tensor_bytes;
                        }
                        continue;
                    }
                }
            }
        }
        // Embedding, lm_head/output, and final norm tensors stay pinned on Master.
        master_pinned_bytes += tensor_bytes;
    }

    let arch_mode = if saw_ternary_tensor || quant_label.starts_with("TQ") || arch.contains("bitnet") {
        ModelArchMode::Ternary
    } else if expert_count > 1 {
        ModelArchMode::MoE
    } else {
        ModelArchMode::Dense
    };

    let (mmproj_path, mmproj_size_mb) = match find_companion_mmproj(path) {
        Some((p, mb)) => (Some(p.to_string_lossy().into_owned()), mb),
        None => (None, 0.0),
    };

    // Exact per-layer GQA KV cache formula: 2 (K+V) * head_count_kv * head_dim * ctx * kv_bytes
    let head_dim = if head_count > 0 {
        embedding_length as f64 / head_count as f64
    } else {
        128.0
    };
    let kv_bpe = kv_bytes_per_elem(kv_quant);
    let eff_ctx = target_context.min(context_length.max(4096)) as f64;
    let per_layer_kv_mb =
        (2.0 * head_count_kv as f64 * head_dim * eff_ctx * kv_bpe) / (1024.0 * 1024.0);

    let fallback_layer_mb = (file_size_mb * 0.90) / block_count as f64;
    let mut per_layer_profiles = Vec::with_capacity(block_count as usize);
    let exact_tensor_table = kv_count <= 4096 && tensor_count <= 32768 && parsed_tensors == tensor_count;
    for i in 0..block_count as usize {
        let (attn_mb, ffn_mb) = if exact_tensor_table && (layer_attn_bytes[i] + layer_ffn_bytes[i]) > 0.0 {
            (
                layer_attn_bytes[i] / (1024.0 * 1024.0),
                layer_ffn_bytes[i] / (1024.0 * 1024.0),
            )
        } else if arch_mode == ModelArchMode::MoE {
            (fallback_layer_mb * 0.18, fallback_layer_mb * 0.82)
        } else {
            (fallback_layer_mb * 0.34, fallback_layer_mb * 0.66)
        };
        per_layer_profiles.push(LayerFootprint {
            layer_index: i as u32,
            attention_weight_mb: attn_mb,
            ffn_or_expert_weight_mb: ffn_mb,
            total_weight_mb: attn_mb + ffn_mb,
            kv_cache_mb: per_layer_kv_mb,
            is_moe_layer: arch_mode == ModelArchMode::MoE && ffn_mb > attn_mb * 1.5,
        });
    }

    let master_pinned_overhead_mb = if parsed_tensors > 0 && master_pinned_bytes > 0.0 {
        (master_pinned_bytes / (1024.0 * 1024.0)) + mmproj_size_mb
    } else {
        (file_size_mb * 0.08).max(96.0) + mmproj_size_mb
    };

    let estimated_params_b = ((file_size_mb * 1024.0 * 1024.0) / 0.58) / 1e9;
    let active_params_b = if expert_count > 1 && expert_used_count > 0 {
        estimated_params_b * (0.12 + 0.88 * (expert_used_count as f64 / expert_count as f64))
    } else {
        estimated_params_b
    };

    Ok(GgufModelCard {
        path: path.to_string_lossy().into_owned(),
        file_size_mb,
        gguf_version: version,
        name,
        basename,
        architecture: arch,
        arch_mode,
        quant_label,
        block_count,
        embedding_length,
        feed_forward_length,
        head_count,
        head_count_kv,
        expert_count,
        expert_used_count,
        context_length,
        profiled_context: target_context,
        has_chat_template,
        has_native_mtp,
        vocab_size,
        vocab_hash,
        mmproj_path,
        mmproj_size_mb,
        master_pinned_overhead_mb,
        per_layer_profiles,
        estimated_params_b,
        active_params_b,
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::Write;

    fn write_gguf_str(buf: &mut Vec<u8>, s: &str) {
        buf.extend_from_slice(&(s.len() as u64).to_le_bytes());
        buf.extend_from_slice(s.as_bytes());
    }

    fn build_synthetic_gguf(
        arch: &str,
        blocks: u32,
        experts: u32,
        tensor_type: u32,
        tokens: &[&str],
    ) -> Vec<u8> {
        let mut buf = Vec::new();
        buf.extend_from_slice(b"GGUF");
        buf.extend_from_slice(&3u32.to_le_bytes()); // v3
        // 3 tensors: token_embd + blk.0.attn_q + blk.0.ffn_gate_exps
        buf.extend_from_slice(&3u64.to_le_bytes());
        let kv_count: u64 = if experts > 0 { 8 } else { 7 };
        buf.extend_from_slice(&kv_count.to_le_bytes());

        // 1. general.architecture
        write_gguf_str(&mut buf, "general.architecture");
        buf.extend_from_slice(&8u32.to_le_bytes());
        write_gguf_str(&mut buf, arch);

        // 2. general.name
        write_gguf_str(&mut buf, "general.name");
        buf.extend_from_slice(&8u32.to_le_bytes());
        write_gguf_str(&mut buf, "Test-Model-GGUF");

        // 3. block_count
        write_gguf_str(&mut buf, &format!("{}.block_count", arch));
        buf.extend_from_slice(&4u32.to_le_bytes());
        buf.extend_from_slice(&blocks.to_le_bytes());

        // 4. context_length
        write_gguf_str(&mut buf, &format!("{}.context_length", arch));
        buf.extend_from_slice(&4u32.to_le_bytes());
        buf.extend_from_slice(&32768u32.to_le_bytes());

        // 5. head_count_kv
        write_gguf_str(&mut buf, &format!("{}.attention.head_count_kv", arch));
        buf.extend_from_slice(&4u32.to_le_bytes());
        buf.extend_from_slice(&8u32.to_le_bytes());

        // 6. chat_template
        write_gguf_str(&mut buf, "tokenizer.chat_template");
        buf.extend_from_slice(&8u32.to_le_bytes());
        write_gguf_str(&mut buf, "{% for m in messages %}{{ m.content }}{% endfor %}");

        // 7. tokenizer.ggml.tokens
        write_gguf_str(&mut buf, "tokenizer.ggml.tokens");
        buf.extend_from_slice(&9u32.to_le_bytes()); // array
        buf.extend_from_slice(&8u32.to_le_bytes()); // string elem
        buf.extend_from_slice(&(tokens.len() as u64).to_le_bytes());
        for t in tokens {
            write_gguf_str(&mut buf, t);
        }

        if experts > 0 {
            write_gguf_str(&mut buf, &format!("{}.expert_count", arch));
            buf.extend_from_slice(&4u32.to_le_bytes());
            buf.extend_from_slice(&experts.to_le_bytes());
        }

        // Tensors
        for (name, d0, d1) in [
            ("token_embd.weight", 4096u64, 32000u64),
            ("blk.0.attn_q.weight", 4096u64, 4096u64),
            ("blk.0.ffn_gate_exps.weight", 4096u64, 16384u64),
        ] {
            write_gguf_str(&mut buf, name);
            buf.extend_from_slice(&2u32.to_le_bytes());
            buf.extend_from_slice(&d0.to_le_bytes());
            buf.extend_from_slice(&d1.to_le_bytes());
            buf.extend_from_slice(&tensor_type.to_le_bytes());
            buf.extend_from_slice(&0u64.to_le_bytes());
        }
        buf
    }

    #[test]
    fn parses_moe_header_non_uniform_layers_and_pairs_mmproj() {
        let dir = std::env::temp_dir().join("silica_gguf_test_moe");
        let _ = fs::remove_dir_all(&dir);
        fs::create_dir_all(&dir).unwrap();

        let model_path = dir.join("qwen3-vl-30b-q4_k_m.gguf");
        let mmproj_path = dir.join("qwen3-mmproj-f16.gguf");
        let bytes = build_synthetic_gguf("qwen3moe", 48, 128, 12, &["<bos>", "hello", "world"]);
        fs::File::create(&model_path).unwrap().write_all(&bytes).unwrap();
        fs::File::create(&mmproj_path).unwrap().write_all(&vec![0u8; 2 * 1024 * 1024]).unwrap();

        let card = inspect_gguf_file(&model_path, 32768, "q8_0").expect("should parse GGUF");
        assert_eq!(card.architecture, "qwen3moe");
        assert_eq!(card.arch_mode, ModelArchMode::MoE);
        assert_eq!(card.block_count, 48);
        assert_eq!(card.expert_count, 128);
        assert!(card.has_chat_template);
        assert_eq!(card.vocab_size, 3);
        assert!(card.mmproj_path.as_deref().unwrap_or("").contains("mmproj"));
        assert!(card.mmproj_size_mb >= 1.9);
        assert!(card.master_pinned_overhead_mb > card.mmproj_size_mb);
        assert_eq!(card.per_layer_profiles.len(), 48);
        assert!(card.per_layer_profiles[0].ffn_or_expert_weight_mb > card.per_layer_profiles[0].attention_weight_mb);

        let _ = fs::remove_dir_all(&dir);
    }

    #[test]
    fn infers_architecture_from_metadata_when_general_architecture_is_missing() {
        let dir = std::env::temp_dir().join("silica_gguf_arch_infer_test");
        let _ = fs::remove_dir_all(&dir);
        fs::create_dir_all(&dir).unwrap();

        let mut bytes = build_synthetic_gguf("qwen3moe", 8, 0, 1, &["a", "b"]);
        let needle = b"general.architecture";
        if let Some(pos) = bytes.windows(needle.len()).position(|w| w == needle) {
            bytes[pos + needle.len() - 1] = b'X';
        }
        let path = dir.join("arch-infer.gguf");
        fs::write(&path, &bytes).unwrap();

        let card = inspect_gguf_file(&path, 4096, "q8_0").expect("should parse");
        assert_eq!(card.architecture, "qwen3moe");
        assert_eq!(card.block_count, 8);

        let _ = fs::remove_dir_all(&dir);
    }

    #[test]
    fn detects_ternary_tq1_0_and_vocab_hash_changes() {
        let dir = std::env::temp_dir().join("silica_gguf_test_tq1");
        let _ = fs::remove_dir_all(&dir);
        fs::create_dir_all(&dir).unwrap();

        let p1 = dir.join("bitnet-tq1.gguf");
        let p2 = dir.join("bitnet-other-vocab.gguf");
        fs::write(&p1, build_synthetic_gguf("bitnet", 36, 0, 34, &["a", "b", "c"])).unwrap();
        fs::write(&p2, build_synthetic_gguf("bitnet", 36, 0, 34, &["x", "y", "z"])).unwrap();

        let c1 = inspect_gguf_file(&p1, 8192, "f16").unwrap();
        let c2 = inspect_gguf_file(&p2, 8192, "f16").unwrap();
        assert_eq!(c1.arch_mode, ModelArchMode::Ternary);
        assert_eq!(c1.quant_label, "TQ1_0");
        assert_ne!(c1.vocab_hash, c2.vocab_hash);

        let _ = fs::remove_dir_all(&dir);
    }
}
