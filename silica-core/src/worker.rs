//! Symmetric Multi-OS Worker Mode (`clusterd worker` / `clusterd --auto` fallback)
//! for Windows, Linux, and ARM64 Edge SBCs (Raspberry Pi, Orange Pi, Jetson):
//! - UDP :50050 Master auto-discovery (with optional `--master <ip:port>`)
//! - 8-second HTTP heartbeat + immediate unregister on SIGINT/SIGTERM
//! - Real OS thermal & safe RAM guard (pausing `rpc-server -c` if overheated)
//! - Spawns real stock `rpc-server -c -H <bind> -p 50052 -t <big_cores> -m <safe_cap_mb>`

use crate::discovery::{discover_master_udp, WorkerRegisterRequest};
use crate::hardware::probe_real_host_hardware;
use std::net::UdpSocket;
use std::process::{Child, Command};
use std::time::Duration;

pub struct WorkerCliOptions {
    pub master_override: Option<String>,
    pub bind_host: String,
    pub rpc_port: u16,
    pub rpc_binary: String,
}


fn local_ip_for_master(master_ip: &str) -> String {
    if let Ok(socket) = UdpSocket::bind("0.0.0.0:0") {
        if socket.connect(format!("{}:9", master_ip)).is_ok() {
            if let Ok(addr) = socket.local_addr() {
                let ip = addr.ip().to_string();
                if !ip.starts_with("127.") {
                    return ip;
                }
            }
        }
    }
    "127.0.0.1".to_string()
}

fn spawn_rpc(opts: &WorkerCliOptions, big_cores: u32, safe_cap_mb: u64) -> Option<Child> {
    Command::new(&opts.rpc_binary)
        .args([
            "-H", &opts.bind_host,
            "-p", &opts.rpc_port.to_string(),
            "-t", &big_cores.to_string(),
            "-m", &safe_cap_mb.to_string(),
            "-c",
        ])
        .spawn()
        .ok()
}

pub async fn run_worker_mode(opts: WorkerCliOptions) -> Result<(), String> {
    let hw = probe_real_host_hardware();
    let node_id = format!(
        "{}-{}-worker",
        hw.os_name,
        hw.hostname
            .to_ascii_lowercase()
            .chars()
            .filter(|c| c.is_ascii_alphanumeric() || *c == '-')
            .collect::<String>()
    );

    let (master_ip, master_port) = if let Some(ref override_addr) = opts.master_override {
        let mut parts = override_addr.splitn(2, ':');
        let ip = parts.next().unwrap_or("127.0.0.1").to_string();
        let port = parts
            .next()
            .and_then(|p| p.parse::<u16>().ok())
            .unwrap_or(3000);
        (ip, port)
    } else if let Some((ip, port, _secret)) = discover_master_udp(1200) {
        tracing::info!("Discovered Silica Master at {}:{} via UDP :50050", ip, port);
        (ip, port)
    } else {
        return Err(
            "No Silica Master found via UDP :50050. Pass --master <ip:port> or run `clusterd --auto`."
                .to_string(),
        );
    };

    let safe_cap_mb = ((hw.available_ram_mb * 0.80).min(hw.total_ram_mb * 0.72)).max(256.0) as u64;
    let mut rpc_child = spawn_rpc(&opts, hw.big_cores, safe_cap_mb);
    let mut paused = false;

    if rpc_child.is_some() {
        tracing::info!(
            "Spawned {} on {}:{} (-t {} -m {}MB -c)",
            opts.rpc_binary,
            opts.bind_host,
            opts.rpc_port,
            hw.big_cores,
            safe_cap_mb
        );
    } else {
        tracing::warn!(
            "Binary `{}` not found on PATH; continuing heartbeat telemetry in standby",
            opts.rpc_binary
        );
    }

    let client = reqwest::Client::builder()
        .timeout(Duration::from_secs(4))
        .build()
        .map_err(|e| format!("HTTP client error: {}", e))?;

    let master_base = format!("http://{}:{}", master_ip, master_port);
    let api_token = std::env::var("CLUSTER_API_TOKEN").ok().filter(|t| !t.trim().is_empty());
    let advertise_ip = if opts.bind_host == "0.0.0.0" {
        local_ip_for_master(&master_ip)
    } else {
        opts.bind_host.clone()
    };
    let mut interval = tokio::time::interval(Duration::from_secs(8));

    #[cfg(unix)]
    let term_future = async {
        match tokio::signal::unix::signal(tokio::signal::unix::SignalKind::terminate()) {
            Ok(mut stream) => { let _ = stream.recv().await; }
            Err(e) => tracing::warn!("Cannot install SIGTERM handler: {}", e),
        }
    };
    #[cfg(not(unix))]
    let term_future = std::future::pending::<()>();
    tokio::pin!(term_future);

    loop {
        tokio::select! {
            _ = &mut term_future => {
                tracing::info!("SIGTERM received — unregistering worker {}", node_id);
                let mut req = client.post(format!("{}/api/cluster/nodes/unregister", master_base))
                    .json(&serde_json::json!({ "id": node_id }));
                if let Some(ref token) = api_token { req = req.bearer_auth(token); }
                let _ = req.send().await;
                if let Some(mut c) = rpc_child.take() { let _ = c.kill(); let _ = c.wait(); }
                break;
            }
            _ = tokio::signal::ctrl_c() => {
                tracing::info!("Shutdown signal received — unregistering worker {} immediately", node_id);
                let mut req = client
                    .post(format!("{}/api/cluster/nodes/unregister", master_base))
                    .json(&serde_json::json!({ "id": node_id }));
                if let Some(ref token) = api_token { req = req.bearer_auth(token); }
                let _ = req.send().await;
                if let Some(mut c) = rpc_child.take() {
                    let _ = c.kill();
                    let _ = c.wait();
                }
                break;
            }
            _ = interval.tick() => {
                let snap = probe_real_host_hardware();
                let thermal_peak = snap.cpu_temp_c.max(snap.gpu_temp_c);
                // Thermal guard: pause rpc-server child if >= 85°C
                if thermal_peak >= 85.0 && rpc_child.is_some() {
                    tracing::warn!("Thermal guard ({:.1}°C) pausing rpc-server worker", thermal_peak);
                    if let Some(mut c) = rpc_child.take() {
                        let _ = c.kill();
                        let _ = c.wait();
                    }
                    paused = true;
                } else if paused && thermal_peak < 78.0 {
                    tracing::info!("Thermal guard cleared ({:.1}°C) — respawning rpc-server worker", thermal_peak);
                    rpc_child = spawn_rpc(&opts, snap.big_cores, safe_cap_mb);
                    paused = rpc_child.is_none();
                }

                let os_tag = if snap.is_raspberry_pi || snap.arch == "aarch64" {
                    "edge-arm"
                } else {
                    snap.os_name.as_str()
                };

                let payload = WorkerRegisterRequest {
                    id: node_id.clone(),
                    label: format!("{} ({})", snap.hostname, snap.os_name),
                    os_type: os_tag.to_string(),
                    ip: advertise_ip.clone(),
                    rpc_port: Some(opts.rpc_port),
                    link_type: if advertise_ip.starts_with("127.") { "USB-C Tunnel".to_string() } else { "Wi-Fi LAN".to_string() },
                    total_ram_mb: snap.total_ram_mb,
                    available_ram_mb: snap.available_ram_mb,
                    vram_total_mb: Some(snap.vram_total_mb),
                    vram_free_mb: Some(snap.vram_free_mb),
                    big_cores: Some(snap.big_cores),
                    mem_bw_gbps: Some(if snap.vram_total_mb > 0.0 { 180.0 } else { 36.0 }),
                    temp_c: Some(thermal_peak),
                    thermal_headroom: Some((thermal_peak / 95.0).clamp(0.1, 1.0)),
                    cpu_usage_pct: Some(snap.cpu_usage_pct),
                    gpu_usage_pct: Some(snap.gpu_usage_pct),
                    compute_tflops: Some(if snap.vram_total_mb > 0.0 { 24.0 } else { 7.8 }),
                    storage_total_mb: Some(snap.storage_total_mb),
                    storage_free_mb: Some(snap.storage_free_mb),
                    link_bandwidth_mbps: None,
                    pkt_loss_pct: None,
                    worker_kind: None,
                    rtt_ms: None,
                };

                let mut req = client
                    .post(format!("{}/api/cluster/nodes/register", master_base))
                    .json(&payload);
                if let Some(ref token) = api_token { req = req.bearer_auth(token); }
                match req.send().await {
                    Ok(resp) if !resp.status().is_success() => {
                        let status = resp.status();
                        let body = resp.text().await.unwrap_or_default();
                        tracing::warn!("Worker registration rejected by master: {} {}", status, body);
                    }
                    Err(e) => tracing::warn!("Worker registration failed: {}", e),
                    _ => {}
                }
            }
        }
    }

    Ok(())
}
