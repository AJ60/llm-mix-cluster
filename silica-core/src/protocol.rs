//! Bit-exact 32-byte GGUF / RPC v9 / v1.0.0 handshake & keyed authentication protocol.
//! Matches Android Kotlin `RpcService.computeClusterAuthToken` and `build32ByteGgufHandshake`.

use std::io::{Read, Write};
use std::path::Path;
use std::net::{SocketAddr, TcpStream};
use std::time::{Duration, Instant};

pub const HANDSHAKE_CHALLENGE: &[u8] = b"GGUF_PROBE_V9";
pub const RPC_PROTOCOL_VERSION: u32 = 9;
pub const STOCK_VERSION_TAG: &[u8; 8] = b"v1.0.0\0\0";

pub const FLAG_VULKAN_FP16: u32 = 1 << 0;
pub const FLAG_ARM_DOTPROD: u32 = 1 << 1;
pub const FLAG_ELF_16KB_ALIGNED: u32 = 1 << 2;
pub const FLAG_SBC_CORTEX_A72: u32 = 1 << 3;
pub const FLAG_PLATFORM_WINDOWS: u32 = 1 << 4;
pub const FLAG_PLATFORM_LINUX: u32 = 1 << 5;
pub const FLAG_PLATFORM_ANDROID: u32 = 1 << 6;
pub const FLAG_PLATFORM_MACOS: u32 = 1 << 7;
pub const FLAG_LEGACY_HARDWARE: u32 = 1 << 8;
pub const FLAG_LEGACY_GRAPHICS: u32 = 1 << 9;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum WorkerHandshakeStatus {
    Ready = 0,
    Throttled = 1,
    ShedLayers = 2,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct GgufHandshakeFrame {
    pub rpc_version: u32,
    pub version_tag: String,
    pub safe_cap_mb: u32,
    pub big_cores: u32,
    pub flags: u32,
    pub status: WorkerHandshakeStatus,
}

/// Bit-exact Rust reference implementation of the 32-byte keyed auth token.
pub fn compute_cluster_auth_token(secret: &str, challenge: &[u8]) -> [u8; 32] {
    let mut state: [u64; 4] = [
        0x6a09e667f3bcc908u64,
        0xbb67ae8584caa73bu64,
        0x3c6ef372fe94f82bu64,
        0xa54ff53a5f1d36f1u64,
    ];

    let secret_bytes = secret.as_bytes();
    let total_len = secret_bytes.len() + challenge.len();
    for i in 0..total_len {
        let b = if i < secret_bytes.len() {
            secret_bytes[i]
        } else {
            challenge[i - secret_bytes.len()]
        } as u64;
        let lane = i & 3;
        let next_lane = (lane + 1) & 3;
        let mixed = state[lane]
            .wrapping_add(b.wrapping_mul(0x9e3779b97f4a7c15u64))
            .rotate_left(13);
        state[lane] = mixed ^ state[next_lane];
    }

    for round in 0u64..8u64 {
        for lane in 0..4 {
            state[lane] = state[lane]
                .wrapping_mul(0xbf58476d1ce4e5b9u64)
                .wrapping_add(round)
                .rotate_right(17);
        }
    }

    let mut out = [0u8; 32];
    for (idx, word) in state.iter().enumerate() {
        out[idx * 8..(idx + 1) * 8].copy_from_slice(&word.to_le_bytes());
    }
    out
}


pub const HANDSHAKE_CHALLENGE_V10: &[u8] = b"GGUF_PROBE_V10";
pub const REPLY_CHALLENGE_V10: &[u8] = b"GGUF_REPLY_V10";
pub const HANDSHAKE_MAGIC_V10: &[u8; 4] = b"NONC";
pub const HANDSHAKE_VERSION_V10: u32 = 10;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct V10Request {
    pub nonce: [u8; 8],
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct V10Reply {
    pub nonce: [u8; 8],
    pub safe_cap_mb: u32,
    pub big_cores: u8,
    pub flags: u16,
    pub status: WorkerHandshakeStatus,
}

pub fn random_nonce() -> [u8; 8] {
    let mut nonce = [0u8; 8];
    if getrandom::getrandom(&mut nonce).is_err() {
        let seed = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .map(|d| d.as_nanos() as u64)
            .unwrap_or(0);
        nonce.copy_from_slice(&seed.to_le_bytes());
    }
    nonce
}

fn v10_challenge(prefix: &[u8], nonce: &[u8; 8]) -> Vec<u8> {
    let mut data = Vec::with_capacity(prefix.len() + 8);
    data.extend_from_slice(prefix);
    data.extend_from_slice(nonce);
    data
}

pub fn encode_v10_request(secret: &str, nonce: &[u8; 8]) -> [u8; 32] {
    let token = compute_cluster_auth_token(secret, &v10_challenge(HANDSHAKE_CHALLENGE_V10, nonce));
    let mut out = [0u8; 32];
    out[0..4].copy_from_slice(HANDSHAKE_MAGIC_V10);
    out[4..12].copy_from_slice(nonce);
    out[12..32].copy_from_slice(&token[0..20]);
    out
}

pub fn decode_v10_request(secret: &str, frame: &[u8; 32]) -> Option<V10Request> {
    if &frame[0..4] != HANDSHAKE_MAGIC_V10 {
        return None;
    }
    let mut nonce = [0u8; 8];
    nonce.copy_from_slice(&frame[4..12]);
    let expected = encode_v10_request(secret, &nonce);
    let mut diff = 0u8;
    for i in 12..32 {
        diff |= expected[i] ^ frame[i];
    }
    if diff != 0 { return None; }
    Some(V10Request { nonce })
}

fn reply_mac(
    secret: &str,
    nonce: &[u8; 8],
    safe_cap_mb: u32,
    big_cores: u8,
    flags: u16,
    status: WorkerHandshakeStatus,
) -> [u8; 8] {
    let mut data = v10_challenge(REPLY_CHALLENGE_V10, nonce);
    data.extend_from_slice(&safe_cap_mb.to_le_bytes());
    data.extend_from_slice(&[big_cores]);
    data.extend_from_slice(&flags.to_le_bytes());
    data.extend_from_slice(&[status as u8]);
    let token = compute_cluster_auth_token(secret, &data);
    let mut out = [0u8; 8];
    for i in 0..8 {
        out[i] = token[i + 8] ^ token[i + 24];
    }
    out
}

pub fn encode_v10_reply(
    secret: &str,
    nonce: &[u8; 8],
    safe_cap_mb: u32,
    big_cores: u8,
    flags: u16,
    status: WorkerHandshakeStatus,
) -> [u8; 32] {
    let mut out = [0u8; 32];
    out[0..4].copy_from_slice(b"GGUF");
    out[4..8].copy_from_slice(&HANDSHAKE_VERSION_V10.to_le_bytes());
    out[8..16].copy_from_slice(nonce);
    out[16..24].copy_from_slice(&reply_mac(secret, nonce, safe_cap_mb, big_cores, flags, status));
    out[24..28].copy_from_slice(&safe_cap_mb.to_le_bytes());
    let packed = (status as u32) | ((flags as u32) << 8) | ((big_cores as u32) << 24);
    out[28..32].copy_from_slice(&packed.to_le_bytes());
    out
}

pub fn verify_v10_reply(secret: &str, frame: &[u8; 32], expected_nonce: &[u8; 8]) -> Option<V10Reply> {
    if &frame[0..4] != b"GGUF" { return None; }
    if u32::from_le_bytes(frame[4..8].try_into().ok()?) != HANDSHAKE_VERSION_V10 { return None; }
    let mut nonce = [0u8; 8];
    nonce.copy_from_slice(&frame[8..16]);
    if &nonce != expected_nonce { return None; }
    let safe_cap_mb = u32::from_le_bytes(frame[24..28].try_into().ok()?);
    let packed = u32::from_le_bytes(frame[28..32].try_into().ok()?);
    let status_bits = packed & 0xff;
    let status = match status_bits {
        0 => WorkerHandshakeStatus::Ready,
        1 => WorkerHandshakeStatus::Throttled,
        2 => WorkerHandshakeStatus::ShedLayers,
        _ => return None,
    };
    let expected_mac = reply_mac(
        secret,
        expected_nonce,
        safe_cap_mb,
        ((packed >> 24) & 0xff) as u8,
        ((packed >> 8) & 0xffff) as u16,
        status,
    );
    let mut diff = 0u8;
    for i in 0..8 {
        diff |= expected_mac[i] ^ frame[i + 16];
    }
    if diff != 0 { return None; }
    Some(V10Reply {
        nonce,
        safe_cap_mb,
        big_cores: ((packed >> 24) & 0xff) as u8,
        flags: ((packed >> 8) & 0xffff) as u16,
        status,
    })
}

/// Constant-time 32-byte verification.
pub fn verify_cluster_auth_token(secret: &str, received: &[u8]) -> bool {
    if received.len() < 32 {
        return false;
    }
    let expected = compute_cluster_auth_token(secret, HANDSHAKE_CHALLENGE);
    let mut diff: u8 = 0;
    for i in 0..32 {
        diff |= expected[i] ^ received[i];
    }
    diff == 0
}

pub fn encode_handshake_frame(
    safe_cap_mb: u32,
    big_cores: u32,
    flags: u32,
    status: WorkerHandshakeStatus,
) -> [u8; 32] {
    let mut buf = [0u8; 32];
    buf[0..4].copy_from_slice(b"GGUF");
    buf[4..8].copy_from_slice(&RPC_PROTOCOL_VERSION.to_le_bytes());
    buf[8..16].copy_from_slice(STOCK_VERSION_TAG);
    buf[16..20].copy_from_slice(&safe_cap_mb.to_le_bytes());
    buf[20..24].copy_from_slice(&big_cores.to_le_bytes());
    buf[24..28].copy_from_slice(&flags.to_le_bytes());
    buf[28..32].copy_from_slice(&(status as u32).to_le_bytes());
    buf
}

pub fn decode_handshake_frame(raw: &[u8; 32]) -> Result<GgufHandshakeFrame, String> {
    if &raw[0..4] != b"GGUF" {
        return Err("Invalid handshake magic (expected GGUF)".to_string());
    }
    let rpc_version = u32::from_le_bytes(raw[4..8].try_into().unwrap());
    if rpc_version != RPC_PROTOCOL_VERSION {
        return Err(format!(
            "Unsupported RPC protocol version {} (expected {})",
            rpc_version, RPC_PROTOCOL_VERSION
        ));
    }
    let version_tag = String::from_utf8_lossy(&raw[8..16])
        .trim_end_matches('\0')
        .to_string();
    let safe_cap_mb = u32::from_le_bytes(raw[16..20].try_into().unwrap());
    let big_cores = u32::from_le_bytes(raw[20..24].try_into().unwrap());
    let flags = u32::from_le_bytes(raw[24..28].try_into().unwrap());
    let status = match u32::from_le_bytes(raw[28..32].try_into().unwrap()) {
        0 => WorkerHandshakeStatus::Ready,
        1 => WorkerHandshakeStatus::Throttled,
        _ => WorkerHandshakeStatus::ShedLayers,
    };
    Ok(GgufHandshakeFrame {
        rpc_version,
        version_tag,
        safe_cap_mb,
        big_cores,
        flags,
        status,
    })
}

/// Performs a live TCP + 32-byte keyed GGUF/RPC v9/v1.0.0 handshake probe against a worker.
pub fn probe_worker_handshake(
    addr: SocketAddr,
    secret: &str,
    timeout: Duration,
) -> Result<(GgufHandshakeFrame, f64), String> {
    let start = Instant::now();
    let nonce = random_nonce();

    // Stage 1: native ggml-rpc v7 HELLO is only a liveness probe and never authenticates.
    {
        let mut hello = [0u8; 33];
        hello[0] = 14;
        hello[1..9].copy_from_slice(&24u64.to_le_bytes());
        hello[9] = 7;
        if let Ok(mut native) = TcpStream::connect_timeout(&addr, timeout) {
            let _ = native.set_read_timeout(Some(Duration::from_millis(200)));
            let _ = native.set_write_timeout(Some(timeout));
            let _ = native.write_all(&hello);
            let _ = native.flush();
        }
    }

    // Stage 2: v10 nonce-bound 32-byte request, verified reply.
    let request = encode_v10_request(secret, &nonce);
    let mut stream = TcpStream::connect_timeout(&addr, timeout)
        .map_err(|e| format!("TCP connect failed to {}: {}", addr, e))?;
    let _ = stream.set_read_timeout(Some(timeout));
    let _ = stream.set_write_timeout(Some(timeout));
    stream
        .write_all(&request)
        .map_err(|e| format!("Failed writing v10 handshake: {}", e))?;
    let _ = stream.flush();

    let mut reply = [0u8; 32];
    stream
        .read_exact(&mut reply)
        .map_err(|e| format!("Failed reading v10 handshake reply: {}", e))?;
    let rtt_ms = start.elapsed().as_secs_f64() * 1000.0;
    let decoded = verify_v10_reply(secret, &reply, &nonce)
        .ok_or_else(|| "Worker replied with invalid or replayed v10 handshake frame".to_string())?;
    Ok((
        GgufHandshakeFrame {
            rpc_version: HANDSHAKE_VERSION_V10,
            version_tag: String::from_utf8_lossy(&STOCK_VERSION_TAG[0..6]).into_owned(),
            safe_cap_mb: decoded.safe_cap_mb,
            big_cores: decoded.big_cores as u32,
            flags: decoded.flags as u32,
            status: decoded.status,
        },
        rtt_ms,
    ))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn auth_token_is_deterministic_and_verifies() {
        let token_a = compute_cluster_auth_token("clusterd-default-hmac-key", HANDSHAKE_CHALLENGE);
        let token_b = compute_cluster_auth_token("clusterd-default-hmac-key", HANDSHAKE_CHALLENGE);
        assert_eq!(token_a, token_b);
        assert!(verify_cluster_auth_token("clusterd-default-hmac-key", &token_a));
        assert!(!verify_cluster_auth_token("wrong-secret", &token_a));
    }


    #[test]
    fn golden_v10_vectors_match_python_reference() {
        let path = Path::new("../tests/fixtures/handshake_v10_vectors.json");
        let raw = std::fs::read_to_string(path).expect("golden vector fixture");
        let doc: serde_json::Value = serde_json::from_str(&raw).expect("valid fixture");
        for vector in doc.get("vectors").and_then(|v| v.as_array()).expect("vectors") {
            let secret = vector["secret"].as_str().expect("secret");
            let nonce_hex = vector["nonce_hex"].as_str().expect("nonce");
            let nonce = hex_to_bytes(nonce_hex).expect("nonce bytes");
            let mut nonce_arr = [0u8; 8];
            nonce_arr.copy_from_slice(&nonce);
            let request = hex_to_bytes(vector["request_hex"].as_str().expect("request")).expect("request bytes");
            let mut request_arr = [0u8; 32];
            request_arr.copy_from_slice(&request);
            assert_eq!(decode_v10_request(secret, &request_arr).expect("valid request").nonce, nonce_arr);
            assert_eq!(encode_v10_request(secret, &nonce_arr)[..], request_arr[..]);
            let reply = hex_to_bytes(vector["reply_hex"].as_str().expect("reply")).expect("reply bytes");
            let mut reply_arr = [0u8; 32];
            reply_arr.copy_from_slice(&reply);
            let decoded = verify_v10_reply(secret, &reply_arr, &nonce_arr).expect("valid reply");
            assert_eq!(decoded.safe_cap_mb, 4096);
            assert_eq!(decoded.big_cores, 8);
            assert_eq!(encode_v10_reply(secret, &nonce_arr, 4096, 8, decoded.flags as u16, decoded.status)[..], reply_arr[..]);
        }
    }

    fn hex_to_bytes(value: &str) -> Option<Vec<u8>> {
        if value.len() % 2 != 0 { return None; }
        (0..value.len())
            .step_by(2)
            .map(|i| u8::from_str_radix(&value[i..i + 2], 16).ok())
            .collect()
    }

    #[test]
    fn v10_roundtrip_and_replay_rejection() {
        let secret = "clusterd-default-hmac-key";
        let nonce = [7u8; 8];
        let request = encode_v10_request(secret, &nonce);
        assert_eq!(decode_v10_request(secret, &request).unwrap().nonce, nonce);
        assert!(decode_v10_request("wrong-secret", &request).is_none());

        let reply = encode_v10_reply(secret, &nonce, 4096, 8, FLAG_PLATFORM_ANDROID as u16, WorkerHandshakeStatus::Ready);
        let decoded = verify_v10_reply(secret, &reply, &nonce).expect("valid reply");
        assert_eq!(decoded.safe_cap_mb, 4096);
        assert_eq!(decoded.big_cores, 8);
        assert_eq!(decoded.status, WorkerHandshakeStatus::Ready);
        assert!(verify_v10_reply(secret, &reply, &[9u8; 8]).is_none());
        assert!(verify_v10_reply("wrong-secret", &reply, &nonce).is_none());

        let mut tampered = reply;
        tampered[24] ^= 1;
        assert!(verify_v10_reply(secret, &tampered, &nonce).is_none());
    }

    #[test]
    fn handshake_frame_roundtrips() {
        let encoded = encode_handshake_frame(
            2048,
            4,
            FLAG_PLATFORM_ANDROID | FLAG_ARM_DOTPROD | FLAG_ELF_16KB_ALIGNED,
            WorkerHandshakeStatus::Ready,
        );
        let decoded = decode_handshake_frame(&encoded).expect("valid frame");
        assert_eq!(decoded.rpc_version, 9);
        assert_eq!(decoded.version_tag, "v1.0.0");
        assert_eq!(decoded.safe_cap_mb, 2048);
        assert_eq!(decoded.big_cores, 4);
        assert_eq!(decoded.status, WorkerHandshakeStatus::Ready);
    }
}
