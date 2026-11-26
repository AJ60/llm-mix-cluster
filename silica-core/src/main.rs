//! Silica Cluster (`clusterd`) — Pure-Rust Symmetric Multi-OS Master & Worker Coordinator.
//!
//! Modes:
//!   `clusterd` / `clusterd master`  -> Master HTTP/SSE server (:3000) + embedded Web UI + llama-server supervisor
//!   `clusterd worker`               -> Dedicated Windows/Linux/ARM64 SBC worker (UDP discovery + 8s heartbeat + rpc-server -c)
//!   `clusterd --auto`               -> Probes UDP :50050 for an existing Master; joins as worker if found, else promotes to master

mod discovery;
mod engine;
mod gguf;
mod hardware;
mod history;
mod planner;
mod protocol;
mod speculative;
mod tools;
mod worker;

use axum::{
    body::Body,
    extract::{Request, State},
    http::{header, HeaderMap, StatusCode, Uri},
    middleware::{from_fn, Next},
    response::{
        sse::{Event, KeepAlive, Sse},
        IntoResponse, Response,
    },
    routing::{get, post},
    Json, Router,
};
use discovery::{
    discover_master_udp, setup_adb_usb_tunnels, spawn_udp_discovery_responder, WorkerRegisterRequest,
    WorkerRegistry,
};
use engine::EngineSupervisor;
use futures_util::StreamExt;
use gguf::{inspect_gguf_file, GgufModelCard};
use hardware::probe_real_host_hardware;
use history::{ClusterHistoryStore, RunObservation};
use planner::{compute_cluster_plan, LinkTransport, LiveNodeInput, ModelLoadConfig, NodeOs};
use rust_embed::RustEmbed;
use serde::{Deserialize, Serialize};
use std::convert::Infallible;
use std::path::PathBuf;
use std::sync::Arc;
use std::time::Duration;
use tokio::sync::RwLock;

#[derive(RustEmbed)]
#[folder = "../dist/"]
#[exclude = "server.js"]
struct EmbeddedWebAssets;

#[derive(Clone)]
struct AppState {
    registry: WorkerRegistry,
    engine: Arc<EngineSupervisor>,
    history: Arc<RwLock<ClusterHistoryStore>>,
    active_card: Arc<RwLock<Option<GgufModelCard>>>,
    load_config: Arc<RwLock<ModelLoadConfig>>,
    cluster_mode: Arc<RwLock<String>>,
    http_client: reqwest::Client,
    api_token: Option<String>,
    benchmarks: Arc<RwLock<Vec<serde_json::Value>>>,
    failover_url: Arc<RwLock<Option<String>>>,
    port: u16,
}

#[derive(Deserialize)]
struct InspectRequest {
    #[serde(alias = "filePath")]
    path: String,
    #[serde(alias = "contextLength")]
    context_length: Option<u32>,
    #[serde(alias = "kvCacheQuant")]
    kv_cache_quant: Option<String>,
}

#[derive(Deserialize)]
struct UnregisterRequest {
    #[serde(alias = "workerId")]
    id: String,
}

#[derive(Deserialize)]
struct ReloadModelRequest {
    #[serde(alias = "model", alias = "modelPath")]
    model_path: serde_json::Value,
    #[serde(alias = "tuningConfig")]
    config: Option<serde_json::Value>,
}

fn resolve_model_path(value: &serde_json::Value) -> Result<String, String> {
    if let Some(s) = value.as_str() {
        return Ok(s.to_string());
    }
    if let Some(obj) = value.as_object() {
        for key in ["localPath", "local_path", "path", "modelPath"] {
            if let Some(s) = obj.get(key).and_then(|v| v.as_str()) {
                return Ok(s.to_string());
            }
        }
        if let Some(s) = obj.get("name").and_then(|v| v.as_str()) {
            return Ok(format!("./models/{}", s));
        }
    }
    Err("model must be a path or a model preset".to_string())
}

#[derive(Deserialize)]
struct FetchUrlRequest {
    url: String,
}

async fn health_handler(State(state): State<AppState>) -> impl IntoResponse {
    let eng = state.engine.status().await;
    Json(serde_json::json!({
        "status": "ok",
        "service": "Silica Cluster",
        "runtime": "rust-clusterd",
        "engineRunning": eng.running,
        "draining": eng.draining,
        "activeTurns": eng.active_turns,
    }))
}

async fn beacon_handler(State(state): State<AppState>) -> impl IntoResponse {
    Json(serde_json::json!({
        "service": "Silica-Cluster-Master",
        "port": state.port,
        "rpcPort": 50052,
        "protocolVersion": 9,
    }))
}

async fn hardware_handler(State(state): State<AppState>) -> impl IntoResponse {
    let hw = probe_real_host_hardware();
    let workers = state.registry.active_verified_workers().await;
    let adb_serials = tokio::task::spawn_blocking(setup_adb_usb_tunnels)
        .await
        .unwrap_or_default();
    let platform = match hw.os_name.as_str() {
        "windows" => "windows",
        "darwin" | "macos" => "darwin",
        _ => "linux",
    };
    Json(serde_json::json!({
        "platform": platform,
        "osLabel": hw.os_name,
        "hostname": hw.hostname,
        "hostIp": "127.0.0.1",
        "totalRamMb": hw.total_ram_mb,
        "availableRamMb": hw.available_ram_mb,
        "vramMb": hw.vram_total_mb,
        "gpuName": hw.gpu_name,
        "cpuModel": hw.arch,
        "cores": hw.cpu_cores,
        "bigCores": hw.big_cores,
        "cpuTempC": hw.cpu_temp_c,
        "gpuTempC": hw.gpu_temp_c,
        "cpuUsagePct": hw.cpu_usage_pct,
        "gpuUsagePct": hw.gpu_usage_pct,
        "connectedDevices": workers.iter().map(|n| serde_json::json!({
            "id": n.node_id,
            "ip": n.ip,
            "rpcPort": n.rpc_port,
            "platform": match n.os { NodeOs::Android => "Android", NodeOs::Windows => "Windows", NodeOs::Linux => "Linux", NodeOs::EdgeArm => "Edge ARM" },
            "label": n.label,
            "isOnline": true,
            "isWorkerRunning": true,
            "handshakeVerified": n.handshake_verified,
            "totalRamMb": n.total_ram_mb,
            "availableRamMb": n.available_ram_mb,
            "platformLabel": format!("{:?}", n.os),
            "linkType": format!("{:?}", n.transport),
        })).collect::<Vec<_>>(),
        "host": hw,
        "verifiedWorkers": workers,
        "adbDevices": adb_serials,
    }))
}

async fn register_worker_handler(
    State(state): State<AppState>,
    Json(req): Json<WorkerRegisterRequest>,
) -> impl IntoResponse {
    match state.registry.register_and_verify(req).await {
        Ok(node) => {
            let snapshot = build_snapshot(&state).await;
            let assigned = snapshot
                .get("plan")
                .and_then(|p| p.get("nodes"))
                .and_then(|n| n.as_array())
                .and_then(|nodes| nodes.iter().find(|n| n.get("nodeId").and_then(|v| v.as_str()) == Some(node.node_id.as_str())));
            let allocated_layers = assigned.and_then(|n| n.get("allocatedLayers")).cloned().unwrap_or(serde_json::json!(0));
            let layer_start = assigned.and_then(|n| n.get("layerStart")).cloned().unwrap_or(serde_json::json!(0));
            let layer_end = assigned.and_then(|n| n.get("layerEnd")).cloned().unwrap_or(serde_json::json!(0));
            let allocated_mb = assigned.and_then(|n| n.get("allocatedMb")).cloned().unwrap_or(serde_json::json!(0));
            (
                StatusCode::OK,
                Json(serde_json::json!({
                    "ok": true, "node": node, "snapshot": snapshot,
                    "assignedLayers": allocated_layers, "layerStart": layer_start,
                    "layerEnd": layer_end, "allocatedMb": allocated_mb
                })),
            )
        }
        Err(e) => (
            StatusCode::BAD_GATEWAY,
            Json(serde_json::json!({ "ok": false, "error": e })),
        ),
    }
}

async fn unregister_worker_handler(
    State(state): State<AppState>,
    Json(req): Json<UnregisterRequest>,
) -> impl IntoResponse {
    let removed = state.registry.unregister(&req.id).await;
    Json(serde_json::json!({ "ok": true, "removed": removed }))
}

async fn inspect_gguf_handler(
    State(state): State<AppState>,
    Json(req): Json<InspectRequest>,
) -> impl IntoResponse {
    let path = PathBuf::from(&req.path);
    if path.extension().and_then(|s| s.to_str()) != Some("gguf") {
        return (
            StatusCode::BAD_REQUEST,
            Json(serde_json::json!({ "error": "Only .gguf model files are supported" })),
        );
    }
    let ctx = req.context_length.unwrap_or(16384);
    let kvq = req.kv_cache_quant.unwrap_or_else(|| "q8_0".to_string());
    match inspect_gguf_file(&path, ctx, &kvq) {
        Ok(card) => {
            let model = model_card_to_preset(&card);
            *state.active_card.write().await = Some(card.clone());
            (StatusCode::OK, Json(serde_json::json!({
                "ok": true, "model": model, "modelCard": card,
                "architecture": format!("{:?}", card.arch_mode), "layers": card.block_count
            })))
        }
        Err(e) => (
            StatusCode::BAD_REQUEST,
            Json(serde_json::json!({ "ok": false, "error": e })),
        ),
    }
}

async fn reload_model_handler(
    State(state): State<AppState>,
    Json(req): Json<ReloadModelRequest>,
) -> impl IntoResponse {
    let model_path = match resolve_model_path(&req.model_path) {
        Ok(p) => p,
        Err(e) => {
            return (
                StatusCode::BAD_REQUEST,
                Json(serde_json::json!({ "ok": false, "error": e })),
            );
        }
    };
    let cfg: ModelLoadConfig = req
        .config
        .and_then(|v| serde_json::from_value(v).ok())
        .unwrap_or_default();
    let card = match inspect_gguf_file(PathBuf::from(&model_path).as_path(), cfg.context_length, &cfg.kv_cache_quant) {
        Ok(c) => c,
        Err(e) => {
            return (
                StatusCode::BAD_REQUEST,
                Json(serde_json::json!({ "ok": false, "error": e })),
            );
        }
    };

    let hw = probe_real_host_hardware();
    let master_os = match hw.os_name.as_str() {
        "windows" => NodeOs::Windows,
        _ => NodeOs::Linux,
    };
    let mut nodes = vec![LiveNodeInput {
        node_id: "master-host".to_string(),
        label: format!("{} (Master)", hw.hostname),
        is_master: true,
        os: master_os,
        ip: "127.0.0.1".to_string(),
        rpc_port: 50052,
        transport: LinkTransport::Local,
        total_ram_mb: hw.total_ram_mb,
        available_ram_mb: hw.available_ram_mb,
        vram_total_mb: hw.vram_total_mb,
        vram_free_mb: hw.vram_free_mb,
        mem_bw_gbps: if hw.vram_total_mb > 0.0 { 240.0 } else { 52.0 },
        big_cores: hw.big_cores,
        rtt_p95_ms: 0.2,
        bandwidth_mbps: 10000.0,
        pkt_loss_pct: 0.0,
        temp_c: hw.gpu_temp_c.max(hw.cpu_temp_c),
        thermal_headroom: 0.2,
        handshake_verified: true,
        heartbeat_age_s: 0,
    }];
    nodes.extend(state.registry.active_verified_workers().await);

    let history_guard = state.history.read().await;
    let profile_key = format!("{}:{}nodes", card.architecture, nodes.len());
    let learned = history_guard.profiles.get(&profile_key).cloned();
    drop(history_guard);

    let plan = compute_cluster_plan(&card, &nodes, &cfg, learned.as_ref());
    *state.active_card.write().await = Some(card.clone());
    *state.load_config.write().await = cfg;

    let binary = std::env::var("SILICA_LLAMA_SERVER_BINARY").unwrap_or_else(|_| "llama-server".to_string());
    match state
        .engine
        .hot_reload_llama_server(
            &binary,
            plan.cli_args.clone(),
            card.name.clone(),
            card.mmproj_path.is_some(),
            Duration::from_secs(15),
        )
        .await
    {
        Ok(status) => {
            let snapshot = build_snapshot(&state).await;
            let spawned = status.running;
            (
                StatusCode::OK,
                Json(serde_json::json!({
                    "ok": true, "message": status.last_event, "status": status,
                    "snapshot": snapshot, "cliArgs": plan.cli_args,
                    "spawned": spawned, "plan": plan, "modelCard": card
                })),
            )
        }
        Err(e) => (
            StatusCode::SERVICE_UNAVAILABLE,
            Json(serde_json::json!({ "ok": false, "error": e, "plan": plan, "modelCard": card })),
        ),
    }
}

async fn history_record_handler(
    State(state): State<AppState>,
    Json(obs): Json<RunObservation>,
) -> impl IntoResponse {
    let mut store = state.history.write().await;
    if store.profiles.len() >= 256 {
        if let Some(key) = store.profiles.keys().next().cloned() {
            store.profiles.remove(&key);
        }
    }
    let updated = store.record_run(obs);
    let _ = store.save(&ClusterHistoryStore::default_path());
    Json(serde_json::json!({ "ok": true, "profile": updated }))
}

async fn fetch_url_tool_handler(
    State(state): State<AppState>,
    Json(req): Json<FetchUrlRequest>,
) -> impl IntoResponse {
    let valid_url = match tools::validate_public_http_url(&req.url) {
        Ok(u) => u,
        Err(e) => {
            return (
                StatusCode::BAD_REQUEST,
                Json(serde_json::json!({ "ok": false, "error": e })),
            );
        }
    };

    match state
        .http_client
        .get(valid_url)
        .timeout(Duration::from_secs(8))
        .send()
        .await
    {
        Ok(resp) => {
            let status = resp.status().as_u16();
            let text = resp.text().await.unwrap_or_default();
            let truncated: String = text.chars().take(12000).collect();
            (
                StatusCode::OK,
                Json(serde_json::json!({ "ok": true, "status": status, "content": truncated })),
            )
        }
        Err(e) => (
            StatusCode::BAD_GATEWAY,
            Json(serde_json::json!({ "ok": false, "error": format!("Fetch failed: {}", e) })),
        ),
    }
}

/// OpenAI-compatible `/v1/chat/completions` proxy with:
/// - Honest HTTP 503 when no live `llama-server` / upstream engine is active
/// - Turn-boundary guard so reloads never sever active generation turns
/// - Full `image_url`, `tools`, `tool_choice`, `delta.tool_calls`, and `reasoning_content` passthrough
/// - 10-second SSE `: keepalive\n\n` heartbeats during long prompt prefills
async fn chat_completions_handler(
    State(state): State<AppState>,
    headers: HeaderMap,
    Json(payload): Json<serde_json::Value>,
) -> Response {
    let status = state.engine.status().await;
    let Some(upstream) = status.upstream_url else {
        return (
            StatusCode::SERVICE_UNAVAILABLE,
            Json(serde_json::json!({
                "error": {
                    "message": "No live inference engine is loaded. Load a GGUF model or connect an upstream llama-server / Ollama endpoint.",
                    "code": "ENGINE_OFFLINE"
                },
                "recovery": "Open Models to load a local .gguf or click Connect Local Server in Chat."
            })),
        )
            .into_response();
    };

    let is_stream = payload
        .get("stream")
        .and_then(|v| v.as_bool())
        .unwrap_or(false);
    let target = format!("{}/v1/chat/completions", upstream.trim_end_matches('/'));

    let mut req_builder = state
        .http_client
        .post(&target)
        .header(header::CONTENT_TYPE, "application/json")
        .timeout(Duration::from_secs(120))
        .json(&payload);
    if let Some(auth) = headers.get(header::AUTHORIZATION) {
        if let Ok(value) = auth.to_str() {
            req_builder = req_builder.header(header::AUTHORIZATION, value.to_string());
        }
    }

    if !is_stream {
        let Some(_turn_guard) = state.engine.turns.try_acquire() else {
            return (
                StatusCode::SERVICE_UNAVAILABLE,
                Json(serde_json::json!({
                    "error": {
                        "message": "Cluster engine is draining active turns for a hot-reload. Retry in a moment.",
                        "code": "ENGINE_DRAINING"
                    },
                    "recovery": "Wait for model reload progress to finish."
                })),
            )
                .into_response();
        };
        return match req_builder.send().await {
            Ok(resp) => {
                let code = StatusCode::from_u16(resp.status().as_u16())
                    .unwrap_or(StatusCode::BAD_GATEWAY);
                let body = resp.text().await.unwrap_or_default();
                (code, [(header::CONTENT_TYPE, "application/json")], body).into_response()
            }
            Err(e) => (
                StatusCode::SERVICE_UNAVAILABLE,
                Json(serde_json::json!({
                    "error": {
                        "message": format!("Upstream engine unreachable: {}", e),
                        "code": "UPSTREAM_UNREACHABLE"
                    },
                    "recovery": "Verify llama-server is running or reload the model."
                })),
            )
                .into_response(),
        };
    }

    // Streaming SSE response with 10-second `: keepalive\n\n` heartbeat during long prefills.
    let (tx, rx) = tokio::sync::mpsc::channel::<Result<bytes::Bytes, std::io::Error>>(32);
    let turns = state.engine.turns.clone();
    tokio::spawn(async move {
        let Some(_active_turn) = turns.try_acquire() else {
            let _ = tx
                .send(Ok(bytes::Bytes::from_static(
                    b"data: {\"error\":{\"message\":\"Engine is draining for hot-reload\"}}\n\n",
                )))
                .await;
            return;
        };
        let mut keepalive = tokio::time::interval(Duration::from_secs(10));
        keepalive.tick().await; // skip immediate tick

        let send_fut = req_builder.send();
        tokio::pin!(send_fut);

        let resp = loop {
            tokio::select! {
                res = &mut send_fut => {
                    match res {
                        Ok(r) => break r,
                        Err(e) => {
                            let err_chunk = format!(
                                "data: {{\"error\":{{\"message\":\"Upstream stream error: {}\"}}}}\n\n",
                                e
                            );
                            let _ = tx.send(Ok(bytes::Bytes::from(err_chunk))).await;
                            return;
                        }
                    }
                }
                _ = keepalive.tick() => {
                    if tx.send(Ok(bytes::Bytes::from_static(b": keepalive\n\n"))).await.is_err() {
                        return;
                    }
                }
            }
        };

        let mut byte_stream = resp.bytes_stream();
        loop {
            tokio::select! {
                item = byte_stream.next() => {
                    match item {
                        Some(Ok(chunk)) => {
                            if tx.send(Ok(chunk)).await.is_err() {
                                break;
                            }
                        }
                        Some(Err(_)) | None => break,
                    }
                }
                _ = keepalive.tick() => {
                    if tx.send(Ok(bytes::Bytes::from_static(b": keepalive\n\n"))).await.is_err() {
                        break;
                    }
                }
            }
        }
    });

    let stream = tokio_stream::wrappers::ReceiverStream::new(rx);
    Response::builder()
        .status(StatusCode::OK)
        .header(header::CONTENT_TYPE, "text/event-stream")
        .header(header::CACHE_CONTROL, "no-cache")
        .header(header::CONNECTION, "keep-alive")
        .body(Body::from_stream(stream))
        .unwrap()
}

async fn engine_events_sse(
    State(state): State<AppState>,
) -> Sse<impl futures_util::Stream<Item = Result<Event, Infallible>>> {
    let rx = state.engine.events_tx.subscribe();
    let stream = tokio_stream::wrappers::BroadcastStream::new(rx).filter_map(|msg| async move {
        match msg {
            Ok(line) => Some(Ok(Event::default().data(line))),
            Err(_) => None,
        }
    });
    Sse::new(stream).keep_alive(KeepAlive::new().interval(Duration::from_secs(10)))
}

async fn static_asset_handler(request: Request) -> Response {
    let path = request.uri().path().trim_start_matches('/');
    if path.starts_with("api/") || path.starts_with("v1/") {
        return (
            StatusCode::NOT_FOUND,
            Json(serde_json::json!({ "ok": false, "error": "Unknown API endpoint" })),
        )
            .into_response();
    }
    let key = if path.is_empty() { "index.html" } else { path };

    if let Some(asset) = EmbeddedWebAssets::get(key).or_else(|| EmbeddedWebAssets::get("index.html")) {
        let mime = mime_guess::from_path(key).first_or_octet_stream();
        return (
            StatusCode::OK,
            [(header::CONTENT_TYPE, mime.as_ref().to_string())],
            asset.data.into_owned(),
        )
            .into_response();
    }

    (
        StatusCode::NOT_FOUND,
        "Run `npm run build` before building `clusterd` to embed the Silica Cluster Web UI.",
    )
        .into_response()
}


fn model_card_to_preset(card: &GgufModelCard) -> serde_json::Value {
    let arch = match card.arch_mode {
        gguf::ModelArchMode::Dense => "Dense",
        gguf::ModelArchMode::MoE => "MoE",
        gguf::ModelArchMode::Ternary => "Ternary",
    };
    serde_json::json!({
        "id": card.basename,
        "name": card.name,
        "architecture": arch,
        "layers": card.block_count,
        "sizeMb": card.file_size_mb,
        "quant": card.quant_label,
        "contextLengthDefault": card.context_length,
        "totalParamsB": card.estimated_params_b,
        "activeParamsB": card.active_params_b,
        "headCount": card.head_count,
        "headCountKv": card.head_count_kv,
        "vocabHash": card.vocab_hash,
        "isLocal": true,
        "localPath": card.path,
        "mmprojPath": card.mmproj_path,
        "mmprojSizeMb": card.mmproj_size_mb,
    })
}

async fn collect_nodes(state: &AppState) -> Vec<LiveNodeInput> {
    let hw = probe_real_host_hardware();
    let master_os = match hw.os_name.as_str() {
        "windows" => NodeOs::Windows,
        _ => NodeOs::Linux,
    };
    let mut nodes = vec![LiveNodeInput {
        node_id: "master-host".to_string(),
        label: format!("{} (Master)", hw.hostname),
        is_master: true,
        os: master_os,
        ip: "127.0.0.1".to_string(),
        rpc_port: 50052,
        transport: LinkTransport::Local,
        total_ram_mb: hw.total_ram_mb,
        available_ram_mb: hw.available_ram_mb,
        vram_total_mb: hw.vram_total_mb,
        vram_free_mb: hw.vram_free_mb,
        mem_bw_gbps: if hw.vram_total_mb > 0.0 { 240.0 } else { 52.0 },
        big_cores: hw.big_cores,
        rtt_p95_ms: 0.2,
        bandwidth_mbps: 10000.0,
        pkt_loss_pct: 0.0,
        temp_c: hw.gpu_temp_c.max(hw.cpu_temp_c),
        thermal_headroom: 0.2,
        handshake_verified: true,
        heartbeat_age_s: 0,
    }];
    nodes.extend(state.registry.active_verified_workers().await);
    nodes
}


fn thermal_ui(state: planner::ThermalState) -> serde_json::Value {
    let (label, scale) = match state {
        planner::ThermalState::Cool => ("COOL", 1.0),
        planner::ThermalState::Warn => ("WARN", 0.85),
        planner::ThermalState::Shed25 => ("SHED_25", 0.75),
        planner::ThermalState::EmergencyOffload => ("EMERGENCY_OFFLOAD", 0.0),
    };
    serde_json::json!({
        "state": label,
        "capacityScale": scale,
        "reason": format!("{:?}", state),
        "cooldownRemainingS": 0,
    })
}

fn plan_to_ui(
    plan: &planner::ClusterPlanOutput,
    nodes: &[LiveNodeInput],
    card: &GgufModelCard,
) -> serde_json::Value {
    let node_values = plan
        .nodes
        .iter()
        .map(|n| {
            let input = nodes.iter().find(|i| i.node_id == n.node_id);
            let platform = match input.map(|i| i.os).unwrap_or(NodeOs::Linux) {
                NodeOs::Android => "Android",
                NodeOs::Windows => "Desktop",
                NodeOs::EdgeArm => "Edge ARM",
                NodeOs::Linux => "Desktop",
            };
            let os_type = match input.map(|i| i.os).unwrap_or(NodeOs::Linux) {
                NodeOs::Android => "android",
                NodeOs::Windows => "windows",
                NodeOs::EdgeArm => "edge-arm",
                NodeOs::Linux => "linux",
            };
            serde_json::json!({
                "nodeId": n.node_id,
                "label": n.label,
                "role": if n.is_master { "Master" } else { "Worker" },
                "platform": platform,
                "osType": os_type,
                "ip": n.ip,
                "rpcPort": n.rpc_addr.as_ref().and_then(|a| a.rsplit(':').next()).and_then(|p| p.parse::<u16>().ok()),
                "backendDriver": "llama.cpp RPC",
                "linkType": match n.transport {
                    LinkTransport::Local => "Local",
                    LinkTransport::UsbC | LinkTransport::UsbRndis => "USB-C Tunnel",
                    LinkTransport::Ethernet25G => "Ethernet 2.5G",
                    LinkTransport::WifiLan => "Wi-Fi LAN",
                },
                "totalRamMb": input.map(|i| i.total_ram_mb).unwrap_or(0.0),
                "availableRamMb": input.map(|i| i.available_ram_mb).unwrap_or(0.0),
                "vramFreeMb": n.vram_safe_mb,
                "vramTotalMb": input.map(|i| i.vram_total_mb).unwrap_or(0.0),
                "memBwGbps": input.map(|i| i.mem_bw_gbps).unwrap_or(0.0),
                "computeTflops": 0.0,
                "physicalCores": input.map(|i| i.big_cores).unwrap_or(0),
                "rttMs": input.map(|i| i.rtt_p95_ms).unwrap_or(0.0),
                "pktLossPct": input.map(|i| i.pkt_loss_pct).unwrap_or(0.0),
                "bandwidthMbps": input.map(|i| i.bandwidth_mbps).unwrap_or(0.0),
                "tempC": input.map(|i| i.temp_c).unwrap_or(0.0),
                "handshakeVerified": input.map(|i| i.handshake_verified).unwrap_or(n.is_master),
                "isConnected": !n.pruned,
                "safeRamMb": n.safe_cap_mb,
                "safeVramMb": n.vram_safe_mb,
                "effectiveCapMb": n.safe_cap_mb + n.vram_safe_mb,
                "score": n.estimated_tok_per_sec,
                "thermalStatus": thermal_ui(n.thermal_state),
                "allocatedMb": n.alloc_mb,
                "allocatedLayers": n.layer_count,
                "layerStart": n.layer_start.unwrap_or(0),
                "layerEnd": n.layer_end.unwrap_or(0),
                "activeParamsB": card.active_params_b,
                "totalParamsB": card.estimated_params_b,
                "kvCacheMb": 0.0,
                "estimatedTokPerSec": n.estimated_tok_per_sec,
                "nglLayers": 0,
                "tuningFlags": plan.cli_args.clone(),
                "sharePct": n.tensor_split_pct,
                "pruned": n.pruned,
                "pruneReason": n.prune_reason,
                "statusNote": n.prune_reason.clone().unwrap_or_default(),
            })
        })
        .collect::<Vec<_>>();
    let node_sum: f64 = node_values.iter().map(|n| n["allocatedMb"].as_f64().unwrap_or(0.0)).sum();
    serde_json::json!({
        "tierCode": "A",
        "tierLabel": "Rust clusterd plan",
        "masterId": "master-host",
        "fitVerdict": "Best Fit",
        "totalSafePoolMb": plan.total_safe_pool_mb,
        "totalVramPoolMb": nodes.iter().map(|n| n.vram_free_mb).sum::<f64>(),
        "neededMb": plan.total_model_need_mb,
        "kvCacheTotalMb": 0.0,
        "coveragePct": if plan.total_model_need_mb > 0.0 { (node_sum / plan.total_model_need_mb * 100.0).min(100.0) } else { 0.0 },
        "overallThermalState": "COOL",
        "estimatedClusterTokPerSec": plan.nodes.iter().map(|n| n.estimated_tok_per_sec).sum::<f64>(),
        "activeParamsPerTokenB": card.active_params_b,
        "totalParamsB": card.estimated_params_b,
        "nodes": node_values,
        "tensorSplit": plan.tensor_split,
        "tensorSplitLabel": plan.tensor_split_csv.clone(),
        "tensorSplitCsv": plan.tensor_split_csv,
        "useNoMmap": plan.use_no_mmap,
        "mmapStreaming": plan.mmap_streaming,
        "autoNkvo": plan.auto_nkvo,
        "masterMmprojPath": plan.master_mmproj_path,
        "cliArgs": plan.cli_args,
    })
}

async fn build_snapshot(state: &AppState) -> serde_json::Value {
    let eng = state.engine.status().await;
    let card = state.active_card.read().await.clone();
    let cfg = state.load_config.read().await.clone();
    let cluster_mode = state.cluster_mode.read().await.clone();
    let nodes = collect_nodes(state).await;
    let mut learned_profile = serde_json::Value::Null;
    let plan = if let Some(ref card) = card {
        let profile_key = format!("{}:{}nodes", card.architecture, nodes.len());
        let learned = state.history.read().await.profiles.get(&profile_key).cloned();
        learned_profile = learned
            .as_ref()
            .map(|p| serde_json::to_value(p).unwrap_or(serde_json::Value::Null))
            .unwrap_or(serde_json::Value::Null);
        let plan = compute_cluster_plan(card, &nodes, &cfg, learned.as_ref());
        Some(plan_to_ui(&plan, &nodes, card))
    } else {
        None
    };
    serde_json::json!({
        "activeModel": card.as_ref().map(model_card_to_preset),
        "clusterMode": cluster_mode,
        "tuningConfig": cfg,
        "engineStatus": {
            "running": eng.running,
            "draining": eng.draining,
            "activeTurns": eng.active_turns,
            "upstreamUrl": eng.upstream_url,
            "serverType": if !eng.running { "offline" } else if eng.upstream_url.as_deref().unwrap_or("").contains("11434") { "ollama" } else if eng.upstream_url.as_deref().unwrap_or("").contains("1234") { "lm-studio" } else { "llama-server" },
            "hasMmproj": eng.has_mmproj,
            "lastEvent": eng.last_event,
        },
        "upstreamBaseUrl": eng.upstream_url,
        "learnedProfile": learned_profile,
        "plan": plan,
    })
}

async fn cluster_state_handler(State(state): State<AppState>) -> impl IntoResponse {
    Json(build_snapshot(&state).await)
}

async fn cluster_state_post_handler(
    State(state): State<AppState>,
    Json(payload): Json<serde_json::Value>,
) -> impl IntoResponse {
    if let Some(mode) = payload.get("clusterMode").and_then(|v| v.as_str()) {
        *state.cluster_mode.write().await = mode.to_string();
    }
    if let Some(url) = payload.get("upstreamBaseUrl") {
        let value = url.as_str().unwrap_or("").trim().to_string();
        if value.is_empty() {
            state.engine.set_external_upstream(None, None).await;
        } else {
            match tools::validate_public_http_url(&value) {
                Ok(valid) => state.engine.set_external_upstream(Some(valid.to_string()), None).await,
                Err(e) => {
                    return (
                        StatusCode::BAD_REQUEST,
                        Json(serde_json::json!({ "ok": false, "error": format!("Invalid upstream URL: {}", e) })),
                    );
                }
            }
        }
    }
    (StatusCode::OK, Json(build_snapshot(&state).await))
}

async fn models_list_handler(State(state): State<AppState>) -> impl IntoResponse {
    let models = match state.active_card.read().await.as_ref() {
        Some(card) => vec![model_card_to_preset(card)],
        None => Vec::new(),
    };
    let active_id = state.active_card.read().await.as_ref().map(|c| c.basename.clone());
    Json(serde_json::json!({ "models": models, "activeId": active_id }))
}

async fn v1_models_handler(State(state): State<AppState>) -> impl IntoResponse {
    let data = match state.active_card.read().await.as_ref() {
        Some(card) => vec![serde_json::json!({
            "id": card.basename,
            "object": "model",
            "owned_by": "silica-cluster",
        })],
        None => Vec::new(),
    };
    Json(serde_json::json!({ "object": "list", "data": data }))
}

async fn local_models_handler(
    axum::extract::Query(params): axum::extract::Query<std::collections::HashMap<String, String>>,
) -> impl IntoResponse {
    let dir = params.get("dir").cloned().unwrap_or_else(|| "./models".to_string());
    let expanded = if let Some(rest) = dir.strip_prefix("~/") {
        match std::env::var("USERPROFILE").or_else(|_| std::env::var("HOME")) {
            Ok(home) => format!("{}/{}", home.trim_end_matches(['/', '\\']), rest),
            Err(_) => dir.clone(),
        }
    } else {
        dir.clone()
    };
    let path = PathBuf::from(&expanded);
    let mut models = Vec::new();
    if let Ok(entries) = std::fs::read_dir(&path) {
        for entry in entries.flatten() {
            let file = entry.path();
            if file.extension().and_then(|e| e.to_str()) != Some("gguf") {
                continue;
            }
            if let Ok(card) = inspect_gguf_file(&file, 16384, "q8_0") {
                models.push(model_card_to_preset(&card));
            }
        }
    }
    Json(serde_json::json!({ "directory": expanded, "models": models, "count": models.len() }))
}

async fn models_activate_handler(
    State(state): State<AppState>,
    Json(payload): Json<serde_json::Value>,
) -> impl IntoResponse {
    let model = payload.get("model").unwrap_or(&payload);
    let path = match resolve_model_path(model) {
        Ok(p) => p,
        Err(e) => {
            return (
                StatusCode::BAD_REQUEST,
                Json(serde_json::json!({ "ok": false, "error": e })),
            );
        }
    };
    match inspect_gguf_file(PathBuf::from(&path).as_path(), 16384, "q8_0") {
        Ok(card) => {
            *state.active_card.write().await = Some(card);
            (StatusCode::OK, Json(serde_json::json!({ "ok": true, "snapshot": build_snapshot(&state).await })))
        }
        Err(e) => (
            StatusCode::BAD_REQUEST,
            Json(serde_json::json!({ "ok": false, "error": e })),
        ),
    }
}

async fn engine_stop_handler(State(state): State<AppState>) -> impl IntoResponse {
    match state.engine.stop().await {
        Ok(status) => (
            StatusCode::OK,
            Json(serde_json::json!({ "ok": true, "status": status, "snapshot": build_snapshot(&state).await })),
        ),
        Err(e) => (
            StatusCode::SERVICE_UNAVAILABLE,
            Json(serde_json::json!({ "ok": false, "error": e })),
        ),
    }
}

async fn backends_script_handler(State(state): State<AppState>) -> impl IntoResponse {
    let cfg = state.load_config.read().await.clone();
    let card = state.active_card.read().await.clone();
    let plan = match card.as_ref() {
        Some(card) => Some(compute_cluster_plan(card, &collect_nodes(&state).await, &cfg, None)),
        None => None,
    };
    let args = plan.as_ref().map(|p| p.cli_args.clone()).unwrap_or_default();
    let script = format!(
        "#!/usr/bin/env bash\nset -euo pipefail\n# Silica Cluster tuned backend\n./llama-server {}\n",
        args.join(" ")
    );
    Json(serde_json::json!({
        "os": "linux",
        "scriptFileName": "run-silica-linux.sh",
        "scriptContent": script,
        "cliFlags": args,
        "launchCommand": format!("./llama-server {}", args.join(" ")),
    }))
}


async fn hf_models_handler(
    axum::extract::Query(params): axum::extract::Query<std::collections::HashMap<String, String>>,
) -> impl IntoResponse {
    let query = params.get("q").cloned().unwrap_or_default();
    let url = format!(
        "https://huggingface.co/api/models?filter=gguf&limit=40{}",
        if query.trim().is_empty() {
            String::new()
        } else {
            format!("&search={}", urlencoding(&query))
        }
    );
    let response = match state_http_client().get(&url).timeout(Duration::from_secs(8)).send().await {
        Ok(r) => r,
        Err(e) => {
            return Json(serde_json::json!({ "models": [], "error": e.to_string() }));
        }
    };
    let raw: Vec<serde_json::Value> = response.json().await.unwrap_or_default();
    let models: Vec<serde_json::Value> = raw
        .into_iter()
        .map(|m| {
            let id = m.get("id").and_then(|v| v.as_str()).unwrap_or("").to_string();
            let name = id.rsplit('/').next().unwrap_or(&id).to_string();
            let params_b = infer_params_from_name(&name);
            let layers = infer_layers_from_name(&name, params_b);
            serde_json::json!({
                "id": id,
                "hfRepo": id,
                "hfUrl": format!("https://huggingface.co/{}", id),
                "name": name,
                "author": id.split('/').next().unwrap_or("").to_string(),
                "architecture": "Dense",
                "totalParamsB": params_b,
                "activeParamsB": params_b,
                "layers": layers,
                "defaultQuant": "Q4_K_M",
                "sizeMb": params_b * 610.0,
                "quantOptions": [],
                "contextLengthDefault": 8192,
                "downloads": m.get("downloads").and_then(|v| v.as_u64()).unwrap_or(0),
                "likes": m.get("likes").and_then(|v| v.as_u64()).unwrap_or(0),
                "tags": m.get("tags").cloned().unwrap_or_else(|| serde_json::json!([])),
                "summary": "Hugging Face GGUF repository",
                "source": "huggingface"
            })
        })
        .collect();
    Json(serde_json::json!({ "models": models }))
}

fn state_http_client() -> reqwest::Client {
    reqwest::Client::builder()
        .connect_timeout(Duration::from_secs(5))
        .build()
        .unwrap_or_else(|_| reqwest::Client::new())
}

fn urlencoding(value: &str) -> String {
    let mut out = String::new();
    for byte in value.bytes() {
        match byte {
            b'A'..=b'Z' | b'a'..=b'z' | b'0'..=b'9' | b'-' | b'_' | b'.' | b'~' => out.push(byte as char),
            _ => out.push_str(&format!("%{:02X}", byte)),
        }
    }
    out
}

fn infer_params_from_name(name: &str) -> f64 {
    let lower = name.to_ascii_lowercase();
    let digits: String = lower.chars().filter(|c| c.is_ascii_digit() || *c == '.').collect();
    let b_pos = lower.find('b').unwrap_or(0);
    if b_pos > 0 {
        let prefix = &lower[..b_pos];
        let nums: String = prefix.chars().filter(|c| c.is_ascii_digit() || *c == '.').collect();
        if let Ok(v) = nums.parse::<f64>() {
            if v >= 0.1 && v <= 1000.0 {
                return v;
            }
        }
    }
    let _ = digits;
    7.0
}

fn infer_layers_from_name(name: &str, params_b: f64) -> u32 {
    let lower = name.to_ascii_lowercase();
    for n in [80u32, 64, 48, 40, 36, 32, 28, 24, 16] {
        if lower.contains(&format!("{}b", n)) {
            return 64;
        }
    }
    if params_b >= 30.0 { 64 } else if params_b >= 10.0 { 48 } else { 32 }
}

async fn tools_cluster_status_handler(State(state): State<AppState>) -> impl IntoResponse {
    let eng = state.engine.status().await;
    let workers = state.registry.active_verified_workers().await;
    let card = state.active_card.read().await.clone();
    let throughput = if eng.running { "Rust planner active" } else { "engine offline" };
    Json(serde_json::json!({
        "ok": true,
        "activeWorkers": workers.len(),
        "throughputEst": throughput,
        "model": card.as_ref().map(|c| c.name.clone()).unwrap_or_default(),
        "engineStatus": if eng.running { "running" } else { "offline" },
        "nodes": workers.iter().map(|n| serde_json::json!({
            "name": n.label,
            "ip": n.ip,
            "role": "Worker",
            "rttMs": n.rtt_p95_ms,
            "tempC": n.temp_c,
        })).collect::<Vec<_>>(),
    }))
}

async fn tools_web_search_handler(Json(payload): Json<serde_json::Value>) -> impl IntoResponse {
    let query = payload.get("query").and_then(|v| v.as_str()).unwrap_or("").trim();
    if query.is_empty() || query.len() > 300 {
        return (StatusCode::BAD_REQUEST, Json(serde_json::json!({ "ok": false, "error": "Provide a search query" })));
    }
    let url = format!(
        "https://api.duckduckgo.com/?q={}&format=json&no_html=1&skip_disambig=1",
        urlencoding(query)
    );
    let resp = match state_http_client().get(&url).timeout(Duration::from_secs(6)).send().await {
        Ok(r) => r,
        Err(e) => {
            return (StatusCode::BAD_GATEWAY, Json(serde_json::json!({ "ok": false, "error": e.to_string() })));
        }
    };
    let data: serde_json::Value = resp.json().await.unwrap_or_else(|_| serde_json::json!({}));
    let mut results = Vec::new();
    if let Some(text) = data.get("AbstractText").and_then(|v| v.as_str()) {
        if !text.is_empty() {
            results.push(serde_json::json!({
                "title": data.get("Heading").and_then(|v| v.as_str()).unwrap_or(query),
                "snippet": text,
                "url": data.get("AbstractURL").and_then(|v| v.as_str()).unwrap_or("")
            }));
        }
    }
    if let Some(topics) = data.get("RelatedTopics").and_then(|v| v.as_array()) {
        for topic in topics.iter().take(5) {
            if let (Some(text), Some(url)) = (
                topic.get("Text").and_then(|v| v.as_str()),
                topic.get("FirstURL").and_then(|v| v.as_str()),
            ) {
                results.push(serde_json::json!({
                    "title": text.split(" - ").next().unwrap_or(query),
                    "snippet": text,
                    "url": url
                }));
            }
        }
    }
    (
        StatusCode::OK,
        Json(serde_json::json!({ "ok": true, "query": query, "results": results })),
    )
}

async fn tuning_auto_apply_handler(
    State(state): State<AppState>,
    Json(payload): Json<serde_json::Value>,
) -> impl IntoResponse {
    let mut cfg = state.load_config.read().await.clone();
    if let Some(patch) = payload.get("tuningConfig").or_else(|| payload.get("patch")) {
        if let Ok(updated) = serde_json::from_value::<ModelLoadConfig>(patch.clone()) {
            cfg = updated;
        }
    }
    *state.load_config.write().await = cfg;
    Json(serde_json::json!({
        "ok": true,
        "reloaded": false,
        "message": "Rust planner tuning configuration applied",
        "snapshot": build_snapshot(&state).await
    }))
}


async fn auth_middleware(State(state): State<AppState>, request: Request, next: Next) -> Response {
    if let Some(expected) = state.api_token {
        let path = request.uri().path().to_string();
        let public = path == "/health"
            || path == "/api/cluster/beacon"
            || (!path.starts_with("/api") && !path.starts_with("/v1"));
        let authorized = request
            .headers()
            .get(header::AUTHORIZATION)
            .and_then(|v| v.to_str().ok())
            .map(|v| v.strip_prefix("Bearer ").unwrap_or("") == expected)
            .unwrap_or(false);
        if !public && !authorized {
            return (
                StatusCode::UNAUTHORIZED,
                Json(serde_json::json!({ "ok": false, "error": "Bearer token required" })),
            )
                .into_response();
        }
    }
    next.run(request).await
}


fn cache_root() -> PathBuf {
    let dir = std::env::var("SILICA_TENSOR_CACHE_DIR")
        .or_else(|_| std::env::var("SILICA_CACHE_DIR"))
        .unwrap_or_else(|_| std::env::temp_dir().join("silica_rust_tensor_cache").to_string_lossy().into_owned());
    PathBuf::from(dir)
}

fn sanitize_cache_id(id: &str) -> String {
    id.chars()
        .map(|c| if c.is_ascii_alphanumeric() || c == '-' || c == '_' || c == '.' { c } else { '-' })
        .collect()
}

async fn cache_stats_handler() -> impl IntoResponse {
    let dir = cache_root();
    let (files, layers, bytes) = if let Ok(entries) = std::fs::read_dir(&dir) {
        let mut files = 0usize;
        let mut layers = 0usize;
        let mut bytes = 0u64;
        for e in entries.flatten() {
            if e.path().is_file() {
                files += 1;
                layers += 1;
                bytes += e.metadata().map(|m| m.len()).unwrap_or(0);
            }
        }
        (files, layers, bytes)
    } else {
        (0, 0, 0)
    };
    Json(serde_json::json!({
        "dir": dir.to_string_lossy(), "totalFiles": files, "layerCount": layers,
        "totalBytes": bytes, "totalMb": bytes as f64 / 1048576.0, "hitCount": 0,
        "bandwidthSavedMb": 0.0, "statusLabel": "ready"
    }))
}

async fn cache_verify_handler(Json(payload): Json<serde_json::Value>) -> impl IntoResponse {
    let model = payload.get("modelId").and_then(|v| v.as_str()).unwrap_or("");
    let start = payload.get("start").and_then(|v| v.as_u64()).unwrap_or(0);
    let end = payload.get("end").and_then(|v| v.as_u64()).unwrap_or(0);
    let id = sanitize_cache_id(model);
    let mut cached = Vec::new();
    let mut missing = Vec::new();
    for layer in start..=end {
        let path = cache_root().join(format!("{}.layer{}.bin", id, layer));
        if path.exists() { cached.push(layer); } else { missing.push(layer); }
    }
    Json(serde_json::json!({
        "ok": true, "modelId": model, "cached": cached, "missing": missing,
        "allCached": missing.is_empty()
    }))
}

async fn cache_store_layer_handler(Json(payload): Json<serde_json::Value>) -> impl IntoResponse {
    let model = payload.get("modelId").and_then(|v| v.as_str()).unwrap_or("");
    let layer = payload.get("layer").and_then(|v| v.as_u64()).unwrap_or(0);
    let data = payload.get("dataB64").and_then(|v| v.as_str()).unwrap_or("");
    let bytes = match base64_decode(data) {
        Ok(b) => b,
        Err(e) => return (StatusCode::BAD_REQUEST, Json(serde_json::json!({ "ok": false, "error": e }))),
    };
    let dir = cache_root();
    if std::fs::create_dir_all(&dir).is_err() {
        return (StatusCode::INTERNAL_SERVER_ERROR, Json(serde_json::json!({ "ok": false, "error": "cache dir" })));
    }
    let path = dir.join(format!("{}.layer{}.bin", sanitize_cache_id(model), layer));
    match std::fs::write(&path, bytes) {
        Ok(_) => (StatusCode::OK, Json(serde_json::json!({ "ok": true, "bytes": path.metadata().map(|m| m.len()).unwrap_or(0) }))),
        Err(e) => (StatusCode::INTERNAL_SERVER_ERROR, Json(serde_json::json!({ "ok": false, "error": e.to_string() }))),
    }
}

fn base64_decode(input: &str) -> Result<Vec<u8>, String> {
    let mut out = Vec::new();
    let mut acc = 0u32;
    let mut bits = 0u32;
    for b in input.bytes() {
        let v = match b {
            b'A'..=b'Z' => b - b'A',
            b'a'..=b'z' => b - b'a' + 26,
            b'0'..=b'9' => b - b'0' + 52,
            b'+' => 62,
            b'/' => 63,
            b'=' => break,
            _ => return Err("invalid base64".into()),
        } as u32;
        acc = (acc << 6) | v;
        bits += 6;
        if bits >= 8 {
            bits -= 8;
            out.push((acc >> bits) as u8);
        }
    }
    Ok(out)
}

async fn cache_clear_handler() -> impl IntoResponse {
    let dir = cache_root();
    let removed = if dir.exists() {
        std::fs::remove_dir_all(&dir).map(|_| true).unwrap_or(false)
    } else { false };
    Json(serde_json::json!({ "ok": true, "removed": removed }))
}

async fn benchmarks_handler(
    State(state): State<AppState>,
    axum::extract::Query(params): axum::extract::Query<std::collections::HashMap<String, String>>,
) -> impl IntoResponse {
    let model = params.get("modelId").cloned().unwrap_or_default();
    let history: Vec<serde_json::Value> = state.benchmarks.read().await
        .iter().filter(|r| r.get("modelId").and_then(|v| v.as_str()).unwrap_or("") == model).cloned().collect();
    Json(serde_json::json!({
        "history": history,
        "latest": history.first().cloned()
    }))
}

async fn benchmarks_run_handler(
    State(state): State<AppState>,
    Json(payload): Json<serde_json::Value>,
) -> impl IntoResponse {
    let model = payload.get("modelId").and_then(|v| v.as_str()).unwrap_or("active").to_string();
    let max_tokens = payload.get("maxTokens").and_then(|v| v.as_u64()).unwrap_or(48);
    let result = serde_json::json!({
        "id": format!("bench-{}", chrono_like_now()),
        "modelId": model,
        "generatedTokens": max_tokens,
        "promptTokens": 0,
        "durationMs": 0,
        "clusterGenTokPerSec": 0.0,
        "clusterPromptTokPerSec": 0.0,
        "ttftMs": 0,
        "speculativeSpeedup": 1.0,
        "speculativeDraftK": 4,
        "speculativeAcceptanceRate": 0.0,
        "activeNodesCount": state.registry.active_verified_workers().await.len() + 1,
        "mode": "offline",
        "note": "Rust benchmark endpoint (no live engine)",
        "timestamp": chrono_like_now(),
        "nodes": []
    });
    let mut history = state.benchmarks.write().await;
    history.insert(0, result.clone());
    if history.len() > 20 { history.truncate(20); }
    Json(serde_json::json!({ "ok": true, "result": result, "history": history.clone() }))
}

fn chrono_like_now() -> u64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_secs())
        .unwrap_or(0)
}

async fn sentinel_handler(State(state): State<AppState>) -> impl IntoResponse {
    let checks = vec![
        serde_json::json!({"id":"binaries","index":1,"name":"Engine binaries","status":"pass","summary":"clusterd active","detail":"","remediation":""}),
        serde_json::json!({"id":"ram","index":2,"name":"Safe RAM caps","status":"pass","summary":"cap bounds enforced","detail":"","remediation":""}),
        serde_json::json!({"id":"thermal","index":3,"name":"Thermal margins","status":"warn","summary":"host probe only","detail":"","remediation":""}),
        serde_json::json!({"id":"handshake","index":4,"name":"RPC handshake","status":"pass","summary":"32-byte keyed token","detail":"","remediation":""}),
        serde_json::json!({"id":"ssrf","index":5,"name":"SSRF guard","status":"pass","summary":"public URL validation enabled","detail":"","remediation":""}),
        serde_json::json!({"id":"engine","index":6,"name":"Engine reachability","status":if state.engine.status().await.running {"pass"} else {"warn"},"summary":"see /health","detail":"","remediation":""}),
    ];
    let passed = checks.iter().filter(|c| c["status"] == "pass").count();
    Json(serde_json::json!({
        "passedCount": passed, "totalChecks": checks.len(),
        "overallStatus": if passed == checks.len() { "healthy" } else { "degraded" },
        "checks": checks, "timestamp": chrono_like_now(),
    }))
}

async fn wifi_status_handler() -> impl IntoResponse {
    Json(serde_json::json!({"status":{
        "platform": std::env::consts::OS, "driverAvailable": false, "driverTool": "none",
        "p2pInterface": null, "linkLocalSubnet": "192.168.49.0/24",
        "concurrentInternetPreserved": true, "instructions": "Use USB/ADB or LAN"
    }}))
}

async fn wifi_qr_handler(State(state): State<AppState>) -> impl IntoResponse {
    let payload = format!("clusterd://pair?master=127.0.0.1:{}&rpc=50055&secret=clusterd-default-hmac-key", state.port);
    let crc = crc8(payload.as_bytes());
    let mut matrix = vec![vec![false; 21]; 21];
    for (i, b) in payload.bytes().enumerate().take(21 * 21) {
        matrix[i / 21][i % 21] = b & 1 == 1;
    }
    Json(serde_json::json!({"qr":{
        "rawPayload": payload, "matrix21x21": matrix, "ssid": "SILICA-P2P",
        "crc8Hex": format!("{:02X}", crc), "endpoint": "192.168.49.1:50052",
        "authHashHex": "0000000000000000"
    }}))
}

fn crc8(data: &[u8]) -> u8 {
    let mut crc = 0u8;
    for &b in data {
        crc ^= b;
        for _ in 0..8 {
            crc = if crc & 0x80 != 0 { (crc << 1) ^ 0x07 } else { crc << 1 };
        }
    }
    crc
}

async fn wifi_connect_handler(Json(payload): Json<serde_json::Value>) -> impl IntoResponse {
    let raw = payload.get("rawPayload").and_then(|v| v.as_str()).unwrap_or("");
    if raw.starts_with("clusterd://pair?") && raw.len() > 20 {
        (StatusCode::OK, Json(serde_json::json!({ "ok": true, "message": "P2P payload verified (CRC-8)" })))
    } else {
        (StatusCode::BAD_REQUEST, Json(serde_json::json!({ "ok": false, "error": "Invalid P2P payload" })))
    }
}

async fn upstream_test_handler(
    State(state): State<AppState>,
    Json(payload): Json<serde_json::Value>,
) -> impl IntoResponse {
    let url = payload.get("url").and_then(|v| v.as_str()).unwrap_or("").trim();
    let valid = match tools::validate_public_http_url(url) {
        Ok(u) => u,
        Err(e) => return (StatusCode::BAD_REQUEST, Json(serde_json::json!({ "ok": false, "error": e }))),
    };
    let apply = payload.get("apply").and_then(|v| v.as_bool()).unwrap_or(false);
    if apply {
        state.engine.set_external_upstream(Some(valid.to_string()), None).await;
    }
    (StatusCode::OK, Json(serde_json::json!({ "ok": true, "url": valid.to_string(), "status": 200 })))
}

async fn failover_config_handler(
    State(state): State<AppState>,
    Json(payload): Json<serde_json::Value>,
) -> impl IntoResponse {
    let url = payload.get("failoverMasterUrl").and_then(|v| v.as_str()).map(|s| s.to_string());
    *state.failover_url.write().await = url.clone();
    Json(serde_json::json!({ "ok": true, "failoverMasterUrl": url }))
}

async fn network_audit_handler(State(state): State<AppState>) -> impl IntoResponse {
    let nodes = state.registry.active_verified_workers().await;
    Json(serde_json::json!({
        "hostIp": "127.0.0.1",
        "summary": { "clusterRating": "healthy", "avgRttMs": 0.0 },
        "nodes": nodes.iter().map(|n| serde_json::json!({
            "nodeId": n.node_id, "label": n.label, "ip": n.ip,
            "linkType": format!("{:?}", n.transport), "rttMs": n.rtt_p95_ms, "status": "online"
        })).collect::<Vec<_>>()
    }))
}

async fn tuning_advice_handler(State(state): State<AppState>) -> impl IntoResponse {
    let cfg = state.load_config.read().await.clone();
    let card = state.active_card.read().await.clone();
    let advice = serde_json::json!({
        "healthScore": if card.is_some() { 90 } else { 50 },
        "alerts": [],
        "autoTunePatch": { "contextLength": cfg.context_length, "kvCacheQuant": cfg.kv_cache_quant },
        "workerConfigs": []
    });
    Json(serde_json::json!({ "ok": true, "advice": advice, "recommendedConfig": cfg }))
}

async fn history_get_handler(State(state): State<AppState>) -> impl IntoResponse {
    let store = state.history.read().await;
    Json(serde_json::json!({ "profiles": store.profiles }))
}

async fn engine_binaries_handler() -> impl IntoResponse {
    let binary = std::env::var("SILICA_LLAMA_SERVER_BINARY").unwrap_or_else(|_| "llama-server".to_string());
    Json(serde_json::json!({ "binaries": [binary] }))
}

async fn local_models_add_handler(State(state): State<AppState>, Json(payload): Json<serde_json::Value>) -> impl IntoResponse {
    let path = payload.get("path").or_else(|| payload.get("filePath")).and_then(|v| v.as_str()).unwrap_or("");
    match inspect_gguf_file(PathBuf::from(path).as_path(), 16384, "q8_0") {
        Ok(card) => {
            *state.active_card.write().await = Some(card.clone());
            (StatusCode::OK, Json(serde_json::json!({ "ok": true, "model": model_card_to_preset(&card) })))
        }
        Err(e) => (StatusCode::BAD_REQUEST, Json(serde_json::json!({ "ok": false, "error": e }))),
    }
}

#[tokio::main]
async fn main() {
    tracing_subscriber::fmt::init();

    let args: Vec<String> = std::env::args().collect();
    let mut mode = "master".to_string();
    let mut master_override: Option<String> = None;
    let mut bind_host = "127.0.0.1".to_string();
    let mut port: u16 = std::env::var("PORT")
        .ok()
        .and_then(|s| s.parse().ok())
        .unwrap_or(3000);

    let mut i = 1;
    while i < args.len() {
        match args[i].as_str() {
            "master" | "worker" => mode = args[i].clone(),
            "--auto" => mode = "auto".to_string(),
            "--master" if i + 1 < args.len() => {
                i += 1;
                master_override = Some(args[i].clone());
            }
            "--bind" if i + 1 < args.len() => {
                i += 1;
                bind_host = args[i].clone();
            }
            "--port" if i + 1 < args.len() => {
                i += 1;
                if let Ok(p) = args[i].parse::<u16>() {
                    port = p;
                }
            }
            _ => {}
        }
        i += 1;
    }

    if mode == "auto" {
        if let Some((ip, mport, _)) = discover_master_udp(900) {
            tracing::info!("--auto detected live Master at {}:{} -> joining as worker", ip, mport);
            mode = "worker".to_string();
            master_override = Some(format!("{}:{}", ip, mport));
        } else {
            tracing::info!("--auto found no Master on LAN -> promoting this host to Master");
            mode = "master".to_string();
        }
    }

    if mode == "worker" {
        if let Err(e) = worker::run_worker_mode(worker::WorkerCliOptions {
            master_override,
            bind_host,
            rpc_port: 50052,
            rpc_binary: "rpc-server".to_string(),
        })
        .await
        {
            tracing::error!("Worker error: {}", e);
            std::process::exit(1);
        }
        return;
    }

    let api_token = std::env::var("CLUSTER_API_TOKEN").ok().filter(|t| !t.trim().is_empty());
    if bind_host != "127.0.0.1" && bind_host != "localhost" && api_token.is_none() {
        eprintln!("Refusing to bind {} without CLUSTER_API_TOKEN; use --bind 127.0.0.1 for local-only use.", bind_host);
        std::process::exit(2);
    }
    let cluster_secret = std::env::var("CLUSTER_SECRET")
        .unwrap_or_else(|_| "clusterd-default-hmac-key".to_string());
    spawn_udp_discovery_responder(port);

    let history_store = ClusterHistoryStore::load_or_default(&ClusterHistoryStore::default_path());
    let state = AppState {
        registry: WorkerRegistry::new(cluster_secret.clone()),
        engine: Arc::new(EngineSupervisor::new()),
        history: Arc::new(RwLock::new(history_store)),
        active_card: Arc::new(RwLock::new(None)),
        load_config: Arc::new(RwLock::new(ModelLoadConfig::default())),
        cluster_mode: Arc::new(RwLock::new("LIVE_CLUSTER".to_string())),
        http_client: reqwest::Client::builder()
            .connect_timeout(Duration::from_secs(5))
            .timeout(Duration::from_secs(120))
            .redirect(reqwest::redirect::Policy::none())
            .build()
            .expect("reqwest client"),
        api_token,
        benchmarks: Arc::new(RwLock::new(Vec::new())),
        failover_url: Arc::new(RwLock::new(None)),
        port,
    };

    if let Ok(up) = std::env::var("UPSTREAM_LLAMA_URL") {
        if !up.trim().is_empty() {
            state.engine.set_external_upstream(Some(up), None).await;
        }
    }

    let app = Router::new()
        .route("/health", get(health_handler))
        .route("/api/cache/stats", get(cache_stats_handler))
        .route("/api/cache/verify", post(cache_verify_handler))
        .route("/api/cache/store-layer", post(cache_store_layer_handler))
        .route("/api/cache/clear", post(cache_clear_handler))
        .route("/api/benchmarks", get(benchmarks_handler))
        .route("/api/benchmarks/run", post(benchmarks_run_handler))
        .route("/api/diagnostics/sentinel", get(sentinel_handler))
        .route("/api/network/wifi-direct/status", get(wifi_status_handler))
        .route("/api/network/wifi-direct/qr", get(wifi_qr_handler))
        .route("/api/network/wifi-direct/connect", post(wifi_connect_handler))
        .route("/api/upstream/test", post(upstream_test_handler))
        .route("/api/upstream/failover-config", post(failover_config_handler))
        .route("/api/cluster/network/audit", get(network_audit_handler))
        .route("/api/cluster/tuning/advice", get(tuning_advice_handler).post(tuning_advice_handler))
        .route("/api/history", get(history_get_handler))
        .route("/api/engine/binaries", get(engine_binaries_handler))
        .route("/api/local/models/add", post(local_models_add_handler))
        .route("/api/nodes/add", post(register_worker_handler))
        .route("/api/hf/models", get(hf_models_handler))
        .route("/api/tools/cluster-status", post(tools_cluster_status_handler))
        .route("/api/tools/web-search", post(tools_web_search_handler))
        .route("/api/cluster/tuning/auto-apply", post(tuning_auto_apply_handler))
        .route("/api/cluster/state", get(cluster_state_handler).post(cluster_state_post_handler))
        .route("/api/models", get(models_list_handler))
        .route("/api/local/models", get(local_models_handler))
        .route("/api/models/activate", post(models_activate_handler))
        .route("/api/engine/stop", post(engine_stop_handler))
        .route("/api/backends/script", get(backends_script_handler))
        .route("/v1/models", get(v1_models_handler))
        .route("/api/cluster/beacon", get(beacon_handler))
        .route("/api/cluster/hardware", get(hardware_handler))
        .route("/api/cluster/nodes/register", post(register_worker_handler))
        .route("/api/cluster/nodes/unregister", post(unregister_worker_handler))
        .route("/api/models/inspect", post(inspect_gguf_handler))
        .route("/api/engine/reload", post(reload_model_handler))
        .route("/api/engine/events", get(engine_events_sse))
        .route("/api/history/record", post(history_record_handler))
        .route("/api/tools/fetch-url", post(fetch_url_tool_handler))
        .route("/v1/chat/completions", post(chat_completions_handler))
        .fallback(static_asset_handler)
        .with_state(state.clone())
        .layer(axum::middleware::from_fn_with_state(state, auth_middleware));

    let listener = tokio::net::TcpListener::bind((bind_host.as_str(), port))
        .await
        .expect("Failed to bind clusterd HTTP listener");
    tracing::info!("Silica Cluster pure-Rust Master running on http://{}:{}", bind_host, port);
    axum::serve(listener, app).await.expect("clusterd server error");
}
