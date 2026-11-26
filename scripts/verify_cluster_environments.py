#!/usr/bin/env python3
"""
scripts/verify_cluster_environments.py
Silica Cluster — Live Multi-VM / Isolated-Namespace End-to-End Verification Suite.

Spawns real OS-isolated virtual nodes using Linux namespaces (`unshare --uts --ipc --pid --fork`)
with distinct per-node virtual IPs (`127.0.10.2`..`127.0.10.5`, `127.0.10.9`), live TCP 32-byte
keyed GGUF/RPC v9 handshakes, and a live OpenAI-compatible upstream VM server.

Usage:
  python3 scripts/verify_cluster_environments.py [--base-url http://127.0.0.1:3000] [--hold-seconds 0]
"""

import argparse
import json
import os
import shutil
import signal
import socket
import struct
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request

DEFAULT_BASE_URL = "http://127.0.0.1:3000"
DEFAULT_SECRET = os.environ.get("CLUSTER_SECRET", "clusterd-default-hmac-key")
HANDSHAKE_CHALLENGE = b"GGUF_PROBE_V10"
REPLY_CHALLENGE_V10 = b"GGUF_REPLY_V10"
HANDSHAKE_MAGIC_V10 = b"NONC"
MASK_64 = (1 << 64) - 1


def rotl64(x: int, k: int) -> int:
    return (((x << k) & MASK_64) | (x >> (64 - k))) & MASK_64


def rotr64(x: int, k: int) -> int:
    return ((x >> k) | ((x << (64 - k)) & MASK_64)) & MASK_64


def compute_cluster_auth_token(secret: str, challenge: bytes = HANDSHAKE_CHALLENGE) -> bytes:
    """Bit-exact Python mirror of Rust/TS/Kotlin 32-byte keyed auth token."""
    state = [
        0x6A09E667F3BCC908,
        0xBB67AE8584CAA73B,
        0x3C6EF372FE94F82B,
        0xA54FF53A5F1D36F1,
    ]
    data = secret.encode("utf-8") + challenge
    for i, b in enumerate(data):
        lane = i & 3
        next_lane = (lane + 1) & 3
        mixed = rotl64((state[lane] + ((b * 0x9E3779B97F4A7C15) & MASK_64)) & MASK_64, 13)
        state[lane] = (mixed ^ state[next_lane]) & MASK_64

    for rnd in range(8):
        for lane in range(4):
            mul = (state[lane] * 0xBF58476D1CE4E5B9) & MASK_64
            state[lane] = rotr64((mul + rnd) & MASK_64, 17)

    return b"".join(struct.pack("<Q", w) for w in state)


# Embedded isolated VM worker daemon (runs inside `unshare --uts --ipc --pid --fork`)
VM_WORKER_DAEMON_CODE = r"""
import socket, struct, sys

host = sys.argv[1]
port = int(sys.argv[2])
secret = sys.argv[3]
safe_cap_mb = int(sys.argv[4])
big_cores = int(sys.argv[5])
flags = int(sys.argv[6])
rogue_mode = sys.argv[7] == "1"

MASK_64 = (1 << 64) - 1
def rotl64(x, k): return (((x << k) & MASK_64) | (x >> (64 - k))) & MASK_64
def rotr64(x, k): return ((x >> k) | ((x << (64 - k)) & MASK_64)) & MASK_64

def auth_token(sec, challenge):
    st = [0x6A09E667F3BCC908, 0xBB67AE8584CAA73B, 0x3C6EF372FE94F82B, 0xA54FF53A5F1D36F1]
    data = sec.encode("utf-8") + challenge
    for i, b in enumerate(data):
        l = i & 3
        nl = (l + 1) & 3
        st[l] = (rotl64((st[l] + ((b * 0x9E3779B97F4A7C15) & MASK_64)) & MASK_64, 13) ^ st[nl]) & MASK_64
    for r in range(8):
        for l in range(4):
            st[l] = rotr64((((st[l] * 0xBF58476D1CE4E5B9) & MASK_64) + r) & MASK_64, 17)
    return b"".join(struct.pack("<Q", w) for w in st)

def decode_v10_request(sec, frame):
    if len(frame) < 32 or frame[0:4] != b"NONC":
        return None
    nonce = frame[4:12]
    expected = b"NONC" + nonce + auth_token(sec, b"GGUF_PROBE_V10" + nonce)[:20]
    return nonce if expected[12:32] == frame[12:32] else None

def encode_v10_reply(sec, nonce, cap, cores, flags, status):
    data = b"GGUF_REPLY_V10" + nonce + struct.pack("<I", cap) + bytes([cores]) + struct.pack("<H", flags) + bytes([status])
    t = auth_token(sec, data)
    mac = bytes(t[i + 8] ^ t[i + 24] for i in range(8))
    packed = (status & 0xFF) | ((flags & 0xFFFF) << 8) | ((cores & 0xFF) << 24)
    return b"GGUF" + struct.pack("<I", 10) + nonce + mac + struct.pack("<II", cap, packed)
srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
srv.bind((host, port))
srv.listen(16)
print(f"READY {host}:{port}", flush=True)

while True:
    conn, _ = srv.accept()
    try:
        conn.settimeout(2.0)
        received = b""
        while len(received) < 32:
            chunk = conn.recv(32 - len(received))
            if not chunk:
                break
            received += chunk
        nonce = None if rogue_mode or len(received) < 32 else decode_v10_request(secret, received[:32])
        if nonce is None:
            conn.sendall(b"NOPE" + b"\x00" * 28)
        else:
            conn.sendall(encode_v10_reply(secret, nonce, safe_cap_mb, big_cores, flags, 0))
    except Exception:
        pass
    finally:
        conn.close()
"""

# Embedded isolated OpenAI-compatible upstream VM server (simulates llama-server on its own VM IP)
VM_UPSTREAM_SERVER_CODE = r"""
import http.server, json, sys

host = sys.argv[1]
port = int(sys.argv[2])

class Handler(http.server.BaseHTTPRequestHandler):
    def log_message(self, format, *args):
        return

    def do_GET(self):
        if self.path == "/v1/models":
            body = json.dumps({
                "object": "list",
                "data": [{"id": "qwen3-30b-a3b-vm", "object": "model", "owned_by": "silica-vm-cluster"}]
            }).encode("utf-8")
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            return
        self.send_response(404)
        self.end_headers()

    def do_POST(self):
        if self.path == "/v1/chat/completions":
            length = int(self.headers.get("Content-Length", "0"))
            raw = self.rfile.read(length).decode("utf-8")
            payload = json.loads(raw) if raw else {}
            has_tools = bool(payload.get("tools"))
            has_image = False
            for m in payload.get("messages", []):
                if isinstance(m.get("content"), list):
                    for part in m["content"]:
                        if isinstance(part, dict) and part.get("type") == "image_url":
                            has_image = True
            if payload.get("stream"):
                self.send_response(200)
                self.send_header("Content-Type", "text/event-stream")
                self.send_header("Cache-Control", "no-cache")
                self.end_headers()
                chunk1 = {
                    "id": "chatcmpl-vm-live",
                    "object": "chat.completion.chunk",
                    "choices": [{
                        "index": 0,
                        "delta": {
                            "reasoning_content": f"Verified VM vision={has_image}, tools={has_tools}.",
                            "tool_calls": [{
                                "index": 0,
                                "id": "call_vm_1",
                                "type": "function",
                                "function": {"name": "cluster_probe", "arguments": '{"status":"ok"}'}
                            }] if has_tools else None
                        },
                        "finish_reason": None
                    }]
                }
                chunk2 = {
                    "id": "chatcmpl-vm-live",
                    "object": "chat.completion.chunk",
                    "choices": [{
                        "index": 0,
                        "delta": {"content": "Multi-VM inference pipeline online."},
                        "finish_reason": "stop"
                    }]
                }
                self.wfile.write(f"data: {json.dumps(chunk1)}\n\n".encode("utf-8"))
                self.wfile.flush()
                self.wfile.write(f"data: {json.dumps(chunk2)}\n\n".encode("utf-8"))
                self.wfile.flush()
                self.wfile.write(b"data: [DONE]\n\n")
                self.wfile.flush()
                return
            body = json.dumps({
                "id": "chatcmpl-vm-live",
                "object": "chat.completion",
                "choices": [{
                    "index": 0,
                    "message": {"role": "assistant", "content": "Multi-VM inference pipeline online."},
                    "finish_reason": "stop"
                }]
            }).encode("utf-8")
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            return
        self.send_response(404)
        self.end_headers()

srv = http.server.ThreadingHTTPServer((host, port), Handler)
print(f"READY {host}:{port}", flush=True)
srv.serve_forever()
"""


def http_json(base_url: str, path: str, data=None, method="GET", timeout=8):
    url = f"{base_url}{path}"
    headers = {"Accept": "application/json"}
    body_bytes = None
    if data is not None:
        headers["Content-Type"] = "application/json"
        body_bytes = json.dumps(data).encode("utf-8")
    req = urllib.request.Request(url, data=body_bytes, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            return resp.status, json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as err:
        raw = err.read().decode("utf-8", errors="replace")
        try:
            return err.code, json.loads(raw)
        except json.JSONDecodeError:
            return err.code, {"raw": raw}


def http_sse(base_url: str, path: str, data: dict, timeout=12):
    url = f"{base_url}{path}"
    headers = {"Content-Type": "application/json", "Accept": "text/event-stream"}
    req = urllib.request.Request(
        url, data=json.dumps(data).encode("utf-8"), headers=headers, method="POST"
    )
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        return resp.status, resp.read().decode("utf-8", errors="replace")


def build_synthetic_gguf(arch: str, name: str, blocks: int, experts: int) -> bytes:
    parts = [b"GGUF", struct.pack("<I", 3), struct.pack("<Q", 0), struct.pack("<Q", 7)]

    def push_str(s: str):
        b = s.encode("utf-8")
        parts.append(struct.pack("<Q", len(b)))
        parts.append(b)

    push_str("general.architecture")
    parts.append(struct.pack("<I", 8))
    push_str(arch)

    push_str("general.name")
    parts.append(struct.pack("<I", 8))
    push_str(name)

    push_str(f"{arch}.block_count")
    parts.append(struct.pack("<I", 4))
    parts.append(struct.pack("<I", blocks))

    push_str(f"{arch}.context_length")
    parts.append(struct.pack("<I", 4))
    parts.append(struct.pack("<I", 65536))

    push_str("tokenizer.chat_template")
    parts.append(struct.pack("<I", 8))
    push_str("{% for m in messages %}{{ m.content }}{% endfor %}")

    push_str("tokenizer.ggml.tokens")
    parts.append(struct.pack("<IIQ", 9, 8, 3))
    for tok in ("<bos>", "silica", "cluster"):
        push_str(tok)

    push_str(f"{arch}.expert_count")
    parts.append(struct.pack("<I", 4))
    parts.append(struct.pack("<I", experts))
    return b"".join(parts)


def spawn_isolated_vm(vm_name: str, python_code: str, args: list[str], use_unshare: bool):
    if use_unshare:
        cmd = [
            "unshare",
            "--uts",
            "--ipc",
            "--pid",
            "--fork",
            "sh",
            "-c",
            'hostname "$1" 2>/dev/null || true; shift; code="$1"; shift; exec python3 -c "$code" "$@"',
            "vm-init",
            vm_name,
            python_code,
            *args,
        ]
    else:
        cmd = [sys.executable, "-c", python_code, *args]

    proc = subprocess.Popen(
        cmd,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
    )
    deadline = time.time() + 5.0
    while time.time() < deadline:
        line = proc.stdout.readline().strip() if proc.stdout else ""
        if line.startswith("READY "):
            return proc
        if proc.poll() is not None:
            err = proc.stderr.read() if proc.stderr else ""
            raise RuntimeError(f"VM {vm_name} exited early ({proc.returncode}): {err}")
        time.sleep(0.05)
    raise RuntimeError(f"Timed out waiting for VM {vm_name} to become READY")


def stop_proc(proc: subprocess.Popen):
    if proc.poll() is None:
        try:
            proc.terminate()
        except Exception:
            pass
        try:
            proc.wait(timeout=2)
        except subprocess.TimeoutExpired:
            try:
                proc.kill()
            except Exception:
                pass


def run_suite(base_url: str, hold_seconds: float):
    unshare_bin = shutil.which("unshare")
    use_unshare = False
    if unshare_bin:
        probe = subprocess.run(
            ["unshare", "--uts", "--ipc", "--pid", "--fork", "true"],
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
        )
        use_unshare = probe.returncode == 0

    isolation_mode = (
        "Linux Namespace VMs (unshare --uts --ipc --pid --fork + per-VM IPs)"
        if use_unshare
        else "Isolated OS Subprocess VMs (per-VM IPs)"
    )

    print("=" * 78)
    print(" Silica Cluster — Multi-VM Live Integration & Fault-Injection Test Suite")
    print(" Simulated Scenarios: Ethernet 2.5G (Linux), USB-C Tunnel (Android), Wi-Fi 5 (Windows)")
    print(f" Target Master : {base_url}")
    print(f" VM Isolation  : {isolation_mode}")
    print("=" * 78)

    procs: list[subprocess.Popen] = []
    registered_ids: list[str] = []
    master_proc: subprocess.Popen | None = None
    tmp_dir = tempfile.mkdtemp(prefix="silica-vm-gguf-")

    try:
        # ------------------------------------------------------------------
        # Pre-Flight: Ensure Master Server is Live (Auto-Start if needed)
        # ------------------------------------------------------------------
        master_already_up = False
        try:
            st, _ = http_json(base_url, "/health", timeout=1.0)
            if st == 200:
                master_already_up = True
                print(f"[Master] Detected existing running Master at {base_url}")
        except Exception:
            master_already_up = False

        if not master_already_up:
            print(f"[Master] No running Master detected on {base_url}. Auto-starting Silica Master...")
            root_dir = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
            clusterd = os.path.join(root_dir, "silica-core", "target", "debug", "clusterd.exe")
            if not os.path.exists(clusterd):
                clusterd = os.path.join(root_dir, "silica-core", "target", "debug", "clusterd")
            if not os.path.exists(clusterd):
                print("[Master] clusterd not built; run `cd silica-core && cargo build` first")
                return 1
            env = os.environ.copy()
            cmd = [clusterd, "--port", str(base_url.rsplit(":", 1)[-1] or 3000)]
            master_proc = subprocess.Popen(
                cmd,
                cwd=root_dir,
                env=env,
                stdout=subprocess.PIPE,
                stderr=subprocess.STDOUT,
                text=True,
            )
            deadline = time.time() + 15.0
            while time.time() < deadline:
                try:
                    st, _ = http_json(base_url, "/health", timeout=1.0)
                    if st == 200:
                        print(f"  [BOOT] Master server online and verified at {base_url} (PID {master_proc.pid})")
                        break
                except Exception:
                    pass
                if master_proc.poll() is not None:
                    out = master_proc.stdout.read() if master_proc.stdout else ""
                    raise RuntimeError(f"Master server exited unexpectedly ({master_proc.returncode}):\n{out}")
                time.sleep(0.4)

        # ------------------------------------------------------------------
        # Stage 1: Health, Beacon & Honest Offline 503 Gate
        # ------------------------------------------------------------------
        print("\n[Stage 1/9] Master Health, Beacon (RPC v9), & Honest HTTP 503 Offline Gate")
        st, health = http_json(base_url, "/health")
        assert st == 200 and health.get("service") == "Silica Cluster", f"Bad /health: {st} {health}"
        st, beacon = http_json(base_url, "/api/cluster/beacon")
        assert st == 200 and beacon.get("protocolVersion") == 9, f"Bad /beacon: {st} {beacon}"

        # Ensure upstream is disconnected so offline 503 check is deterministic
        http_json(base_url, "/api/cluster/state", {"upstreamBaseUrl": ""}, method="POST")
        st, offline_chat = http_json(
            base_url,
            "/v1/chat/completions",
            {"model": "small-1.5b", "messages": [{"role": "user", "content": "ping"}]},
            method="POST",
        )
        assert st == 503 and offline_chat.get("error", {}).get("code") == "ENGINE_OFFLINE", (
            f"Expected HTTP 503 ENGINE_OFFLINE, got {st}: {offline_chat}"
        )
        print("  [PASS] Master online, RPC v9 beacon active, /v1/chat/completions honestly returns HTTP 503.")

        # ------------------------------------------------------------------
        # Stage 2: Boot 4 Isolated Worker VMs + Live 32-Byte Keyed TCP Handshakes
        # ------------------------------------------------------------------
        print("\n[Stage 2/9] Booting 4 Isolated Worker VMs & Live 32-Byte Keyed TCP Handshakes")
        vm_specs = [
            {
                "id": "vm-linux-ethernet",
                "label": "Ubuntu-24.04 Linux Workstation (Ethernet 2.5G)",
                "platform": "Linux",
                "osType": "linux",
                "ip": "127.0.10.2",
                "rpcPort": 50061,
                "linkType": "Ethernet 2.5G",
                "totalRamMb": 16384,
                "availableRamMb": 12288,
                "vramMb": 8192,
                "computeTflops": 18.5,
                "memBwGbps": 288,
                "bigCores": 8,
                "rttMs": 0.35,
                "pktLossPct": 0.0,
                "tempC": 48,
                "cpuUsagePct": 14,
                "gpuUsagePct": 12,
                "flags": (1 << 5) | (1 << 0),
                "rogue": False,
            },
            {
                "id": "vm-android-usb",
                "label": "Android-15 Phone (USB-C ADB Tunnel)",
                "platform": "Android",
                "osType": "android",
                "ip": "127.0.10.3",
                "rpcPort": 50062,
                "linkType": "USB-C Tunnel",
                "totalRamMb": 12288,
                "availableRamMb": 7800,
                "vramMb": 0,
                "computeTflops": 3.2,
                "memBwGbps": 54,
                "bigCores": 4,
                "rttMs": 0.52,
                "pktLossPct": 0.0,
                "tempC": 34,
                "cpuUsagePct": 16,
                "gpuUsagePct": 0,
                "flags": (1 << 6) | (1 << 1) | (1 << 2),
                "rogue": False,
            },
            {
                "id": "vm-windows-wifi5",
                "label": "Windows-11 Worker (Wi-Fi 5 802.11ac)",
                "platform": "Windows",
                "osType": "windows",
                "ip": "127.0.10.4",
                "rpcPort": 50063,
                "linkType": "Wi-Fi LAN",
                "totalRamMb": 12288,
                "availableRamMb": 8192,
                "vramMb": 2048,
                "computeTflops": 4.5,
                "memBwGbps": 84,
                "bigCores": 6,
                "rttMs": 14.8,
                "pktLossPct": 0.2,
                "tempC": 50,
                "cpuUsagePct": 20,
                "gpuUsagePct": 5,
                "flags": (1 << 5) | (1 << 0),
                "rogue": False,
            },
            {
                "id": "vm-rogue-unauth",
                "label": "Rogue VM (Wi-Fi Spoof)",
                "platform": "Linux",
                "osType": "linux",
                "ip": "127.0.10.5",
                "rpcPort": 50064,
                "linkType": "Wi-Fi LAN",
                "totalRamMb": 8192,
                "availableRamMb": 4096,
                "vramMb": 0,
                "computeTflops": 2.0,
                "memBwGbps": 24,
                "bigCores": 4,
                "tempC": 40,
                "cpuUsagePct": 5,
                "gpuUsagePct": 0,
                "flags": 0,
                "rogue": True,
            },
        ]

        for vm in vm_specs:
            p = spawn_isolated_vm(
                vm["id"],
                VM_WORKER_DAEMON_CODE,
                [
                    vm["ip"],
                    str(vm["rpcPort"]),
                    DEFAULT_SECRET,
                    str(int(vm["availableRamMb"] * 0.75)),
                    str(vm["bigCores"]),
                    str(vm["flags"]),
                    "1" if vm["rogue"] else "0",
                ],
                use_unshare,
            )
            procs.append(p)
            print(f"  [BOOT] {vm['id']} ({vm['platform']} over {vm['linkType']}) listening on {vm['ip']}:{vm['rpcPort']} (PID {p.pid})")

        # Register the 3 legitimate VMs (real TCP 32-byte handshake enforced by clusterd!)
        for vm in vm_specs[:3]:
            reg_payload = {k: v for k, v in vm.items() if k not in ("flags", "rogue")}
            st, res = http_json(base_url, "/api/cluster/nodes/register", reg_payload, method="POST")
            assert st == 200 and res.get("ok") is True, f"Legitimate VM {vm['id']} failed handshake: {st} {res}"
            registered_ids.append(vm["id"])
            print(f"  [PASS] {vm['id']} ({vm['ip']}:{vm['rpcPort']}) verified via 32-byte GGUF/RPC v9 handshake.")

        # Register the rogue VM — MUST fail with HTTP 502!
        rogue_payload = {k: v for k, v in vm_specs[3].items() if k not in ("flags", "rogue")}
        st, rogue_res = http_json(base_url, "/api/cluster/nodes/register", rogue_payload, method="POST")
        assert st == 502 and rogue_res.get("ok") is False, f"Rogue VM should be rejected with 502, got {st} {rogue_res}"
        print(f"  [PASS] Rogue VM ({vm_specs[3]['ip']}) strictly rejected with HTTP 502: {rogue_res.get('error')}")

        # ------------------------------------------------------------------
        # Stage 3: Heterogeneous 4-Node Multi-VM Auto-Sharding (`Qwen3-30B-A3B MoE`)
        # ------------------------------------------------------------------
        print("\n[Stage 3/9] Multi-VM Auto-Sharding & Mathematical Invariants (Ethernet + USB + Wi-Fi 5)")
        st, act = http_json(base_url, "/api/models/activate", {"modelId": "moe-30b"}, method="POST")
        assert st == 200 and act.get("ok") is True, f"Model activate failed: {st} {act}"
        plan = act["snapshot"]["plan"]
        nodes = plan["nodes"]
        vm_nodes = [n for n in nodes if n["nodeId"].startswith("vm-")]
        assert len(vm_nodes) == 3, f"Expected 3 live worker VMs, got {len(vm_nodes)}"

        split_sum = sum(plan["tensorSplit"])
        assert abs(split_sum - 100.0) < 0.06, f"Expected --tensor-split sum ~100.0, got {split_sum}"

        total_assigned_layers = 0
        for n in nodes:
            cap = n["safeRamMb"] + n["safeVramMb"]
            assert n["allocatedMb"] <= cap + 1, f"Zero-OOM violation on {n['nodeId']}: {n['allocatedMb']} > {cap}"
            if n["platform"] == "Android":
                assert n["safeRamMb"] <= n["totalRamMb"] * 0.5 + 1, (
                    f"Android >50% RAM cap violated: {n['safeRamMb']} > {n['totalRamMb'] * 0.5}"
                )
            total_assigned_layers += n["allocatedLayers"]
            span = f"L{n['layerStart']}-L{n['layerEnd']}" if n["allocatedLayers"] > 0 else f"PRUNED/0L ({n.get('statusNote', '')})"
            tok_s = n.get("estimatedTokPerSec", n.get("estTokPerSec", n.get("tokPerSec", 0)))
            print(
                f"  [NODE] {n['label']:<40} {n['ip']:<12} {span:<28} "
                f"{n['allocatedMb']:>5} / {cap:<5} MB  ({tok_s} tok/s)"
            )

        assert total_assigned_layers == act["snapshot"]["activeModel"]["layers"], (
            f"Layer count mismatch: assigned {total_assigned_layers} != {act['snapshot']['activeModel']['layers']}"
        )

        st, audit = http_json(base_url, "/api/cluster/network/audit")
        assert st == 200 and audit["summary"]["totalNodes"] >= 4, f"Network audit mismatch: {audit}"
        print(
            f"  [PASS] 4-VM cluster verified: {total_assigned_layers} layers contiguous across Ethernet, USB & Wi-Fi 5, "
            f"split={plan['tensorSplit']}, rating={audit['summary']['clusterRating']}."
        )

        # ------------------------------------------------------------------
        # Stage 4: Real-World Wi-Fi 5 Jitter / Latency Spike Dynamic Auto-Pruning
        # ------------------------------------------------------------------
        print("\n[Stage 4/9] Real-World Wi-Fi 5 Jitter / Latency Spike (48ms RTT, 2.5% loss) Auto-Pruning")
        # Activate Small-1.5B (28 layers) where Master + Ethernet + USB comfortably fit
        st, small_act = http_json(base_url, "/api/models/activate", {"modelId": "small-1.5b"}, method="POST")
        assert st == 200 and small_act.get("ok") is True

        degraded_wifi = {k: v for k, v in vm_specs[2].items() if k not in ("flags", "rogue")}
        degraded_wifi["rttMs"] = 48.0
        degraded_wifi["pktLossPct"] = 2.5
        st, deg_res = http_json(base_url, "/api/cluster/nodes/register", degraded_wifi, method="POST")
        assert st == 200 and deg_res.get("ok") is True
        deg_plan = deg_res["snapshot"]["plan"]
        wifi_node = next(n for n in deg_plan["nodes"] if n["nodeId"] == "vm-windows-wifi5")
        assert wifi_node["pruned"] is True, f"Degraded Wi-Fi 5 node should be pruned, got pruned={wifi_node['pruned']}"
        assert wifi_node["allocatedLayers"] == 0, f"Pruned Wi-Fi 5 node must have 0 layers, got {wifi_node['allocatedLayers']}"
        small_layers = sum(n["allocatedLayers"] for n in deg_plan["nodes"])
        assert small_layers == deg_res["snapshot"]["activeModel"]["layers"]
        print(f"  [PASS] Degraded Wi-Fi 5 node auto-pruned ({wifi_node['statusNote']}); all {small_layers}L preserved on Ethernet & USB.")

        # Recover Wi-Fi 5 back to healthy state and switch back to MoE 30B
        recovered_wifi = {k: v for k, v in vm_specs[2].items() if k not in ("flags", "rogue")}
        st, rec_res = http_json(base_url, "/api/cluster/nodes/register", recovered_wifi, method="POST")
        assert st == 200 and rec_res.get("ok") is True
        st, act = http_json(base_url, "/api/models/activate", {"modelId": "moe-30b"}, method="POST")
        assert st == 200 and act.get("ok") is True

        # Optional hold for live browser inspection while VMs are active
        if hold_seconds > 0:
            print(f"\n[HOLD] Keeping 4-VM cluster live for {hold_seconds:.1f}s for live browser inspection...")
            time.sleep(hold_seconds)

        # ------------------------------------------------------------------
        # Stage 5: Mobile Thermal Overload Shedding (49.5°C on Android USB -> 0 Layers)
        # ------------------------------------------------------------------
        print("\n[Stage 5/9] Mobile Thermal Overload Shedding (49.5°C on Android USB -> 0 Layers)")
        hot_android = {k: v for k, v in vm_specs[1].items() if k not in ("flags", "rogue")}
        hot_android["tempC"] = 49.5
        st, hot_res = http_json(base_url, "/api/cluster/nodes/register", hot_android, method="POST")
        assert st == 200 and hot_res.get("ok") is True
        hot_plan = hot_res["snapshot"]["plan"]
        android_node = next(n for n in hot_plan["nodes"] if n["nodeId"] == "vm-android-usb")
        assert android_node["allocatedLayers"] == 0, f"Overheated Android VM must shed to 0L, got {android_node['allocatedLayers']}"
        assert android_node["thermalStatus"]["state"] == "EMERGENCY_OFFLOAD"
        preserved_layers = sum(n["allocatedLayers"] for n in hot_plan["nodes"])
        assert preserved_layers == hot_res["snapshot"]["activeModel"]["layers"]
        print(f"  [PASS] Android VM shed to 0L ({android_node['thermalStatus']['state']}); all {preserved_layers}L absorbed by Linux Ethernet & Windows Master.")

        # Restore Android phone temperature back to normal (34°C)
        cool_android = {k: v for k, v in vm_specs[1].items() if k not in ("flags", "rogue")}
        cool_android["tempC"] = 34.0
        st, cool_res = http_json(base_url, "/api/cluster/nodes/register", cool_android, method="POST")
        assert st == 200 and cool_res.get("ok") is True

        # ------------------------------------------------------------------
        # Stage 6: Binary GGUF v3 + Companion mmproj Inspection & Hot-Reload
        # ------------------------------------------------------------------
        print("\n[Stage 6/9] Binary GGUF v3 Header + Companion mmproj Pairing & Hot-Reload")
        gguf_path = os.path.join(tmp_dir, "qwen3-vl-30b-q4_k_m.gguf")
        mmproj_path = os.path.join(tmp_dir, "qwen3-mmproj-f16.gguf")
        with open(gguf_path, "wb") as f:
            f.write(build_synthetic_gguf("qwen3moe", "Qwen3-VL-30B-A3B", 48, 128))
        with open(mmproj_path, "wb") as f:
            f.write(b"\x00" * (2 * 1024 * 1024))

        st, inspected = http_json(base_url, "/api/models/inspect", {"filePath": gguf_path}, method="POST")
        assert st == 200 and inspected.get("ok") is True, f"GGUF inspect failed: {st} {inspected}"
        model_card = inspected["model"]
        assert model_card["architecture"] == "MoE" and model_card["layers"] == 48
        assert model_card.get("mmprojPath") and "mmproj" in model_card["mmprojPath"]

        st, reloaded = http_json(base_url, "/api/engine/reload", {"model": model_card}, method="POST")
        assert st == 200 and reloaded.get("ok") is True, f"Engine reload failed: {st} {reloaded}"
        cli_args = reloaded["cliArgs"]
        assert "--mmproj" in cli_args and "--jinja" in cli_args and "--kv-unified" in cli_args
        print(f"  [PASS] GGUF v3 parsed ({model_card['layers']}L MoE), mmproj auto-paired, {len(cli_args)} stock flags validated.")

        # ------------------------------------------------------------------
        # Stage 7: Live Isolated Upstream VM (`image_url` + `--jinja` tools + `reasoning_content`)
        # ------------------------------------------------------------------
        print("\n[Stage 7/9] Live Upstream VM Streaming Proxy (Vision image_url + Tools + Reasoning)")
        upstream_vm = spawn_isolated_vm(
            "vm-upstream-llama", VM_UPSTREAM_SERVER_CODE, ["127.0.10.9", "18080"], use_unshare
        )
        procs.append(upstream_vm)

        st, up_test = http_json(
            base_url,
            "/api/upstream/test",
            {"url": "http://127.0.10.9:18080", "apply": True},
            method="POST",
        )
        assert st == 200 and up_test.get("ok") is True, f"Upstream connect failed: {st} {up_test}"

        st, sse_body = http_sse(
            base_url,
            "/v1/chat/completions",
            {
                "model": "qwen3-30b-a3b-vm",
                "stream": True,
                "tools": [{"type": "function", "function": {"name": "cluster_probe", "parameters": {"type": "object"}}}],
                "messages": [
                    {
                        "role": "user",
                        "content": [
                            {"type": "text", "text": "Inspect this VM diagram"},
                            {"type": "image_url", "image_url": {"url": "data:image/png;base64,iVBORw0KGgo="}},
                        ],
                    }
                ],
            },
        )
        assert st == 200
        assert "Verified VM vision=True, tools=True." in sse_body, f"Missing vision/tools reasoning in SSE: {sse_body}"
        assert "tool_calls" in sse_body and "cluster_probe" in sse_body
        assert "Multi-VM inference pipeline online." in sse_body and "[DONE]" in sse_body
        print("  [PASS] End-to-end SSE stream verified: image_url, --jinja tool_calls, and reasoning_content intact.")

        # ------------------------------------------------------------------
        # Stage 8: SSRF Security Guard Against Internal VM IPs & Cloud Metadata
        # ------------------------------------------------------------------
        print("\n[Stage 8/9] SSRF Guard Blocking Internal VM / Cloud Metadata IPs")
        for blocked_url in (
            "http://127.0.10.9:18080/v1/models",
            "http://169.254.169.254/latest/meta-data/",
            "http://10.0.0.1/admin",
        ):
            st, resp = http_json(base_url, "/api/tools/fetch-url", {"url": blocked_url}, method="POST")
            assert st == 400 and resp.get("ok") is False, f"SSRF failed to block {blocked_url}: {st} {resp}"
        print("  [PASS] All loopback, private RFC1918, and cloud metadata VM URLs blocked with HTTP 400.")

        # ------------------------------------------------------------------
        # Stage 9: Clean VM Teardown & Zero Ghost Nodes
        # ------------------------------------------------------------------
        print("\n[Stage 9/9] Clean Multi-VM Unregister, Upstream Disconnect & Zero Ghost Nodes")
        for wid in list(registered_ids):
            st, unreg = http_json(base_url, "/api/cluster/nodes/unregister", {"id": wid}, method="POST")
            assert st == 200 and unreg.get("removed") is True, f"Failed unregistering {wid}: {st} {unreg}"
            registered_ids.remove(wid)

        http_json(base_url, "/api/cluster/state", {"upstreamBaseUrl": ""}, method="POST")
        http_json(base_url, "/api/models/activate", {"modelId": "small-1.5b"}, method="POST")

        st, final_state = http_json(base_url, "/api/cluster/state")
        vm_nodes_remaining = [n for n in final_state["plan"]["nodes"] if n["nodeId"].startswith("vm-")]
        assert st == 200 and len(vm_nodes_remaining) == 0, (
            f"Expected 0 simulated VM worker nodes after teardown, got {len(vm_nodes_remaining)}: {vm_nodes_remaining}"
        )
        st, final_chat = http_json(
            base_url,
            "/v1/chat/completions",
            {"model": "small-1.5b", "messages": [{"role": "user", "content": "check"}]},
            method="POST",
        )
        assert st == 503
        print("  [PASS] Zero ghost workers remain; Master cleanly returned to single-host HTTP 503 offline state.")

        print("\n" + "=" * 78)
        print(" ALL 9 MULTI-VM ISOLATED INTEGRATION STAGES PASSED (0 FAILURES)")
        print("=" * 78)

    finally:
        # Ensure no workers or subprocesses leak even on failure
        for wid in registered_ids:
            try:
                http_json(base_url, "/api/cluster/nodes/unregister", {"id": wid}, method="POST")
            except Exception:
                pass
        try:
            http_json(base_url, "/api/cluster/state", {"upstreamBaseUrl": ""}, method="POST")
        except Exception:
            pass
        for p in procs:
            stop_proc(p)
        if master_proc:
            print("[Master] Stopping auto-spawned Master server...")
            stop_proc(master_proc)
        shutil.rmtree(tmp_dir, ignore_errors=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Silica Cluster Multi-VM Live Integration Suite")
    parser.add_argument("--base-url", default=DEFAULT_BASE_URL, help="Silica Master HTTP URL")
    parser.add_argument(
        "--hold-seconds",
        type=float,
        default=0.0,
        help="Optional pause while 4-VM cluster is active for live UI screenshots",
    )
    args = parser.parse_args()
    try:
        run_suite(args.base_url.rstrip("/"), args.hold_seconds)
    except Exception as exc:
        import traceback
        traceback.print_exc()
        print(f"\n[FAIL] Multi-VM test failed: {exc}", file=sys.stderr)
        sys.exit(1)
