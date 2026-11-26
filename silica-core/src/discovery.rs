//! Live-only node discovery & verified worker registry:
//! - UDP `:50050` Master responder (`CLUSTERD_DISCOVER` -> `CLUSTERD_MASTER:port=3000;secret=...`)
//! - Multi-device ADB `-s <serial>` reverse/forward orchestration
//! - Strict Live-Only invariant: workers appear ONLINE only after verified TCP + 32-byte
//!   GGUF/RPC v9/v1.0.0 handshake AND an active heartbeat (< 30s TTL).

use crate::planner::{LinkTransport, LiveNodeInput, NodeOs};
use crate::protocol::probe_worker_handshake;
use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::net::{IpAddr, SocketAddr, UdpSocket};
use std::process::Command;
use std::sync::Arc;
use std::time::{Duration, Instant};
use tokio::sync::RwLock;

pub const DISCOVERY_UDP_PORT: u16 = 50050;
pub const WORKER_TTL_SECS: u64 = 30;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct WorkerRegisterRequest {
    #[serde(alias = "workerId")]
    pub id: String,
    pub label: String,
    #[serde(alias = "osType")]
    pub os_type: String,
    pub ip: String,
    #[serde(alias = "rpcPort")]
    pub rpc_port: Option<u16>,
    #[serde(alias = "linkType")]
    pub link_type: String,
    #[serde(alias = "totalRamMb", default)]
    pub total_ram_mb: f64,
    #[serde(alias = "availableRamMb", default)]
    pub available_ram_mb: f64,
    #[serde(alias = "vramTotalMb")]
    pub vram_total_mb: Option<f64>,
    #[serde(alias = "vramFreeMb")]
    pub vram_free_mb: Option<f64>,
    #[serde(alias = "bigCores")]
    pub big_cores: Option<u32>,
    #[serde(alias = "memBwGbps")]
    pub mem_bw_gbps: Option<f64>,
    #[serde(alias = "tempC")]
    pub temp_c: Option<f64>,
    #[serde(alias = "thermalHeadroom")]
    pub thermal_headroom: Option<f64>,
}

#[derive(Debug, Clone)]
pub struct VerifiedWorkerRecord {
    pub node: LiveNodeInput,
    pub last_seen: Instant,
}

#[derive(Clone)]
pub struct WorkerRegistry {
    inner: Arc<RwLock<HashMap<String, VerifiedWorkerRecord>>>,
    cluster_secret: String,
}

impl WorkerRegistry {
    pub fn new(cluster_secret: String) -> Self {
        Self {
            inner: Arc::new(RwLock::new(HashMap::new())),
            cluster_secret,
        }
    }

    /// Registers or refreshes a worker ONLY after verifying its 32-byte GGUF/RPC v9 handshake.
    pub async fn register_and_verify(&self, req: WorkerRegisterRequest) -> Result<LiveNodeInput, String> {
        let port = req.rpc_port.unwrap_or(50052);
        let ip: IpAddr = req
            .ip
            .parse()
            .map_err(|_| format!("Invalid worker IP address: {}", req.ip))?;
        let sock_addr = SocketAddr::new(ip, port);

        let secret = self.cluster_secret.clone();
        let (frame, rtt_ms) = tokio::task::spawn_blocking(move || {
            probe_worker_handshake(sock_addr, &secret, Duration::from_millis(1800))
        })
        .await
        .map_err(|e| format!("Handshake task panicked: {}", e))??;

        let os = match req.os_type.to_ascii_lowercase().as_str() {
            "windows" => NodeOs::Windows,
            "linux" => NodeOs::Linux,
            "edge-arm" | "rpi" | "sbc" => NodeOs::EdgeArm,
            _ => NodeOs::Android,
        };
        let transport = match req.link_type.to_ascii_lowercase().as_str() {
            s if s.contains("usb") && s.contains("rndis") => LinkTransport::UsbRndis,
            s if s.contains("usb") => LinkTransport::UsbC,
            s if s.contains("eth") => LinkTransport::Ethernet25G,
            _ => LinkTransport::WifiLan,
        };

        let node = LiveNodeInput {
            node_id: req.id.clone(),
            label: req.label,
            is_master: false,
            os,
            ip: req.ip,
            rpc_port: port,
            transport,
            total_ram_mb: req.total_ram_mb.max(frame.safe_cap_mb as f64 * 2.0),
            available_ram_mb: req.available_ram_mb.max(frame.safe_cap_mb as f64),
            vram_total_mb: req.vram_total_mb.unwrap_or(0.0),
            vram_free_mb: req.vram_free_mb.unwrap_or(0.0),
            mem_bw_gbps: req.mem_bw_gbps.unwrap_or(28.0),
            big_cores: req.big_cores.unwrap_or(frame.big_cores).max(1),
            rtt_p95_ms: rtt_ms,
            bandwidth_mbps: match transport {
                LinkTransport::UsbC | LinkTransport::UsbRndis => 480.0,
                LinkTransport::Ethernet25G => 2500.0,
                _ => 220.0,
            },
            pkt_loss_pct: 0.0,
            temp_c: req.temp_c.unwrap_or(34.0),
            thermal_headroom: req.thermal_headroom.unwrap_or(0.25),
            handshake_verified: true,
            heartbeat_age_s: 0,
        };

        let mut map = self.inner.write().await;
        map.insert(
            req.id,
            VerifiedWorkerRecord {
                node: node.clone(),
                last_seen: Instant::now(),
            },
        );
        Ok(node)
    }

    /// Immediately removes a worker (invoked by Android onDestroy/onTaskRemoved or Linux/Win SIGTERM).
    pub async fn unregister(&self, node_id: &str) -> bool {
        let mut map = self.inner.write().await;
        map.remove(node_id).is_some()
    }

    /// Returns ONLY verified ONLINE workers whose heartbeat age < 30s (evicting stale entries).
    pub async fn active_verified_workers(&self) -> Vec<LiveNodeInput> {
        let now = Instant::now();
        let mut map = self.inner.write().await;
        map.retain(|_, rec| now.duration_since(rec.last_seen).as_secs() < WORKER_TTL_SECS);
        map.values()
            .map(|rec| {
                let mut n = rec.node.clone();
                n.heartbeat_age_s = now.duration_since(rec.last_seen).as_secs();
                n
            })
            .collect()
    }
}

/// Spawns the non-blocking UDP `:50050` Master discovery beacon listener.
pub fn spawn_udp_discovery_responder(http_port: u16) {
    tokio::task::spawn_blocking(move || {
        let Ok(sock) = UdpSocket::bind(("0.0.0.0", DISCOVERY_UDP_PORT)) else {
            tracing::warn!("UDP :{} busy; discovery responder skipped", DISCOVERY_UDP_PORT);
            return;
        };
        let _ = sock.set_broadcast(true);
        let mut buf = [0u8; 512];
        loop {
            let Ok((len, peer)) = sock.recv_from(&mut buf) else {
                continue;
            };
            let msg = String::from_utf8_lossy(&buf[..len]).trim().to_string();
            if msg == "CLUSTERD_DISCOVER" {
                let reply = format!("CLUSTERD_MASTER:port={}", http_port);
                let _ = sock.send_to(reply.as_bytes(), peer);
            }
        }
    });
}

/// Probes LAN/loopback on UDP `:50050` for an existing Master (used by `clusterd worker` & `--auto`).
pub fn discover_master_udp(timeout_ms: u64) -> Option<(String, u16, String)> {
    let sock = UdpSocket::bind("0.0.0.0:0").ok()?;
    let _ = sock.set_broadcast(true);
    let _ = sock.set_read_timeout(Some(Duration::from_millis(timeout_ms)));
    let payload = b"CLUSTERD_DISCOVER";
    for target in ["255.255.255.255", "127.0.0.1"] {
        let _ = sock.send_to(payload, format!("{}:{}", target, DISCOVERY_UDP_PORT));
    }
    let mut buf = [0u8; 512];
    let (len, peer) = sock.recv_from(&mut buf).ok()?;
    let reply = String::from_utf8_lossy(&buf[..len]);
    if !reply.starts_with("CLUSTERD_MASTER:") {
        return None;
    }
    let mut port = 3000u16;
    let mut secret = "clusterd-default-hmac-key".to_string();
    for kv in reply.trim_start_matches("CLUSTERD_MASTER:").split(';') {
        if let Some((k, v)) = kv.split_once('=') {
            match k.trim() {
                "port" | "master_port" => {
                    if let Ok(p) = v.trim().parse::<u16>() {
                        port = p;
                    }
                }
                "secret" => secret = v.trim().to_string(),
                _ => {}
            }
        }
    }
    Some((peer.ip().to_string(), port, secret))
}

/// Configures per-serial ADB USB-C reverse (`:3000`) and forward (`:50052`) tunnels cleanly.
pub fn setup_adb_usb_tunnels() -> Vec<String> {
    let Ok(out) = Command::new("adb").args(["devices", "-l"]).output() else {
        return Vec::new();
    };
    if !out.status.success() {
        return Vec::new();
    }
    let stdout = String::from_utf8_lossy(&out.stdout);
    let mut serials = Vec::new();
    for line in stdout.lines().skip(1) {
        let parts: Vec<&str> = line.split_whitespace().collect();
        if parts.len() >= 2 && parts[1] == "device" {
            let serial = parts[0].to_string();
            let _ = Command::new("adb")
                .args(["-s", &serial, "reverse", "tcp:3000", "tcp:3000"])
                .output();
            let _ = Command::new("adb")
                .args(["-s", &serial, "forward", "tcp:50052", "tcp:50052"])
                .output();
            serials.push(serial);
        }
    }
    serials
}
