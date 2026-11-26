#!/usr/bin/env python3
"""
tests/cluster_test_lib.py
Shared stdlib test utilities for Silica Cluster QEMU/Alpine VM and local
namespace verification suites.
"""

from dataclasses import dataclass
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
from pathlib import Path

TESTS_DIR = Path(__file__).resolve().parent
GUEST_WORKER_SCRIPT = TESTS_DIR / "qemu" / "guest_rpc_worker.py"

HANDSHAKE_CHALLENGE = b"GGUF_PROBE_V10"
REPLY_CHALLENGE_V10 = b"GGUF_REPLY_V10"
HANDSHAKE_MAGIC_V10 = b"NONC"
RPC_PROTOCOL_VERSION = 10
MASK_64 = (1 << 64) - 1


def rotl64(x: int, k: int) -> int:
    return (((x << k) & MASK_64) | (x >> (64 - k))) & MASK_64


def rotr64(x: int, k: int) -> int:
    return ((x >> k) | ((x << (64 - k)) & MASK_64)) & MASK_64


def compute_cluster_auth_token(secret: str, challenge: bytes = HANDSHAKE_CHALLENGE) -> bytes:
    """Bit-exact Python mirror of Rust `protocol.rs` & TS `clusterAuth.ts`."""
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



def encode_v10_request(secret: str, nonce: bytes) -> bytes:
    token = compute_cluster_auth_token(secret, HANDSHAKE_CHALLENGE + nonce)
    return HANDSHAKE_MAGIC_V10 + nonce + token[:20]


def decode_v10_request(secret: str, frame: bytes) -> bytes | None:
    if len(frame) < 32 or frame[0:4] != HANDSHAKE_MAGIC_V10:
        return None
    nonce = frame[4:12]
    expected = encode_v10_request(secret, nonce)
    return nonce if hmac_equal(frame[12:32], expected[12:32]) else None


def reply_mac_v10(secret: str, nonce: bytes, cap: int, cores: int, flags: int, status: int) -> bytes:
    data = REPLY_CHALLENGE_V10 + nonce + struct.pack("<I", cap) + bytes([cores]) + struct.pack("<H", flags) + bytes([status])
    token = compute_cluster_auth_token(secret, data)
    return bytes(token[i + 8] ^ token[i + 24] for i in range(8))


def encode_v10_reply(secret: str, nonce: bytes, cap: int, cores: int, flags: int, status: int) -> bytes:
    packed = (status & 0xFF) | ((flags & 0xFFFF) << 8) | ((cores & 0xFF) << 24)
    return b"GGUF" + struct.pack("<I", 10) + nonce + reply_mac_v10(secret, nonce, cap, cores, flags, status) + struct.pack("<II", cap, packed)


def decode_v10_reply(secret: str, frame: bytes, nonce: bytes) -> dict | None:
    if len(frame) < 32 or frame[0:4] != b"GGUF" or struct.unpack("<I", frame[4:8])[0] != 10:
        return None
    if frame[8:16] != nonce:
        return None
    cap, packed = struct.unpack("<II", frame[24:32])
    status = packed & 0xFF
    flags = (packed >> 8) & 0xFFFF
    cores = (packed >> 24) & 0xFF
    if not hmac_equal(frame[16:24], reply_mac_v10(secret, nonce, cap, cores, flags, status)):
        return None
    return {
        "rpc_version": 10,
        "safe_cap_mb": cap,
        "big_cores": cores,
        "flags": flags,
        "status": {0: "READY", 1: "THROTTLED", 2: "SHED_LAYERS"}.get(status, "SHED_LAYERS"),
    }


def hmac_equal(a: bytes, b: bytes) -> bool:
    if len(a) != len(b):
        return False
    diff = 0
    for x, y in zip(a, b):
        diff |= x ^ y
    return diff == 0


def decode_gguf_handshake_frame(raw: bytes) -> dict | None:
    if len(raw) < 32 or raw[0:4] != b"GGUF":
        return None
    rpc_version = struct.unpack("<I", raw[4:8])[0]
    if rpc_version != RPC_PROTOCOL_VERSION:
        return None
    version_tag = raw[8:16].rstrip(b"\x00").decode("utf-8", errors="replace")
    safe_cap_mb, big_cores, flags, status_code = struct.unpack("<IIII", raw[16:32])
    status_map = {0: "READY", 1: "THROTTLED", 2: "SHED_LAYERS"}
    return {
        "rpc_version": rpc_version,
        "version_tag": version_tag,
        "safe_cap_mb": safe_cap_mb,
        "big_cores": big_cores,
        "flags": flags,
        "status": status_map.get(status_code, "SHED_LAYERS"),
    }


def probe_tcp_rpc_handshake(host: str, port: int, secret: str, timeout: float = 3.0) -> tuple[bool, dict | None, float]:
    """Performs a live TCP + 32-byte keyed nonce-bound GGUF/RPC v10 handshake against a VM worker."""
    start = time.perf_counter()
    nonce = os.urandom(8)
    with socket.create_connection((host, port), timeout=timeout) as sock:
        sock.settimeout(timeout)
        sock.sendall(encode_v10_request(secret, nonce))
        reply = b""
        while len(reply) < 32:
            chunk = sock.recv(32 - len(reply))
            if not chunk:
                break
            reply += chunk
    rtt_ms = (time.perf_counter() - start) * 1000.0
    decoded = decode_v10_reply(secret, reply, nonce)
    return (decoded is not None), decoded, rtt_ms


def http_json(base_url: str, path: str, data=None, method: str = "GET", timeout: float = 8.0) -> tuple[int, dict]:
    url = f"{base_url.rstrip('/')}{path}"
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


def build_synthetic_gguf_v3(
    arch: str,
    name: str,
    blocks: int,
    context_length: int = 32768,
    experts: int | None = None,
    tokens: tuple[str, ...] = ("<bos>", "silica", "alpine", "vm"),
    chat_template: str = "{% for m in messages %}{{ m.content }}{% endfor %}",
) -> bytes:
    """Constructs a valid GGUF v3 binary header matching llama.cpp & Silica parser."""
    kv_count = 6 + (1 if experts is not None else 0)
    parts = [
        b"GGUF",
        struct.pack("<I", 3),  # GGUF version 3
        struct.pack("<Q", 0),  # tensor_count
        struct.pack("<Q", kv_count),
    ]

    def push_str(s: str):
        encoded = s.encode("utf-8")
        parts.append(struct.pack("<Q", len(encoded)))
        parts.append(encoded)

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
    parts.append(struct.pack("<I", context_length))

    push_str("tokenizer.chat_template")
    parts.append(struct.pack("<I", 8))
    push_str(chat_template)

    push_str("tokenizer.ggml.tokens")
    parts.append(struct.pack("<IIQ", 9, 8, len(tokens)))
    for tok in tokens:
        push_str(tok)

    if experts is not None:
        push_str(f"{arch}.expert_count")
        parts.append(struct.pack("<I", 4))
        parts.append(struct.pack("<I", experts))

    return b"".join(parts)


@dataclass
class VmWorkerEndpoint:
    worker_id: str
    label: str
    platform: str
    os_type: str
    link_type: str
    host: str
    port: int
    total_ram_mb: int
    available_ram_mb: int
    vram_mb: int
    big_cores: int
    temp_c: float
    rogue: bool = False


class ClusterVmTestHarness:
    """
    Dual-mode cluster VM fixture:
      - In `qemu` mode (Docker Compose): targets the live QEMU/Alpine Linux VMs
        (`QEMU_WORKER_1_ADDR`, `QEMU_WORKER_2_ADDR`, `QEMU_ROGUE_ADDR`).
      - In `local` mode (direct execution): spawns `tests/qemu/guest_rpc_worker.py`
        inside isolated Linux namespaces (`unshare --uts --ipc --pid --fork`)
        bound to distinct virtual loopback IPs (`127.0.20.21`, `.22`, `.29`).
    """

    def __init__(self):
        self.mode = os.environ.get("CLUSTER_TEST_MODE", "local").strip().lower()
        self.master_url = os.environ.get("SILICA_MASTER_URL", "http://127.0.0.1:3000").rstrip("/")
        self.cluster_secret = os.environ.get("CLUSTER_SECRET", "clusterd-default-hmac-key")
        self.models_dir_env = os.environ.get("SILICA_MODELS_DIR", "").strip()
        self._tmp_models_dir: str | None = None
        self._procs: list[subprocess.Popen] = []
        self._registered_ids: set[str] = set()
        self._use_unshare = self._detect_unshare()

        if self.mode == "qemu":
            w1_h, w1_p = self._parse_host_port(os.environ.get("QEMU_WORKER_1_ADDR", "172.29.0.21:50052"))
            w2_h, w2_p = self._parse_host_port(os.environ.get("QEMU_WORKER_2_ADDR", "172.29.0.22:50052"))
            rg_h, rg_p = self._parse_host_port(os.environ.get("QEMU_ROGUE_ADDR", "172.29.0.29:50052"))
            self.worker_1 = VmWorkerEndpoint(
                "qemu-alpine-gpu-1", "QEMU-Alpine-3.21-Worker-1", "Linux", "linux",
                "Ethernet 2.5G", w1_h, w1_p, 16384, 12800, 8192, 8, 48.0, rogue=False,
            )
            self.worker_2 = VmWorkerEndpoint(
                "qemu-alpine-mobile-2", "QEMU-Alpine-3.21-Worker-2", "Android", "android",
                "USB-C Tunnel", w2_h, w2_p, 8192, 5600, 0, 4, 35.0, rogue=False,
            )
            self.rogue_worker = VmWorkerEndpoint(
                "qemu-alpine-rogue-unauth", "QEMU-Alpine-Rogue-VM", "Linux", "linux",
                "Wi-Fi LAN", rg_h, rg_p, 4096, 2048, 0, 2, 42.0, rogue=True,
            )
        else:
            self.worker_1 = VmWorkerEndpoint(
                "qemu-alpine-gpu-1", "Alpine-Namespace-Worker-1", "Linux", "linux",
                "Ethernet 2.5G", "127.0.20.21", 50152, 16384, 12800, 8192, 8, 48.0, rogue=False,
            )
            self.worker_2 = VmWorkerEndpoint(
                "qemu-alpine-mobile-2", "Alpine-Namespace-Worker-2", "Android", "android",
                "USB-C Tunnel", "127.0.20.22", 50153, 8192, 5600, 0, 4, 35.0, rogue=False,
            )
            self.rogue_worker = VmWorkerEndpoint(
                "qemu-alpine-rogue-unauth", "Alpine-Namespace-Rogue", "Linux", "linux",
                "Wi-Fi LAN", "127.0.20.29", 50159, 4096, 2048, 0, 2, 42.0, rogue=True,
            )

    @staticmethod
    def _parse_host_port(addr: str) -> tuple[str, int]:
        host, port = addr.rsplit(":", 1)
        return host, int(port)

    @staticmethod
    def _detect_unshare() -> bool:
        if not shutil.which("unshare"):
            return False
        res = subprocess.run(
            ["unshare", "--uts", "--ipc", "--pid", "--fork", "true"],
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
        )
        return res.returncode == 0

    @property
    def models_dir(self) -> Path:
        if self.models_dir_env:
            p = Path(self.models_dir_env)
            p.mkdir(parents=True, exist_ok=True)
            return p
        if not self._tmp_models_dir:
            self._tmp_models_dir = tempfile.mkdtemp(prefix="silica-test-models-")
        return Path(self._tmp_models_dir)

    def start(self):
        # Verify Master is reachable before booting workers
        deadline = time.time() + 15.0
        while time.time() < deadline:
            try:
                st, _ = http_json(self.master_url, "/health", timeout=2.0)
                if st == 200:
                    break
            except Exception:
                time.sleep(0.25)
        else:
            raise RuntimeError(f"Silica Master unreachable at {self.master_url}/health")

        if self.mode != "qemu":
            for ep in (self.worker_1, self.worker_2, self.rogue_worker):
                self._spawn_local_worker(ep)
        else:
            # Wait for all QEMU/Alpine guest RPC ports to accept TCP connections
            for ep in (self.worker_1, self.worker_2, self.rogue_worker):
                self._wait_tcp(ep.host, ep.port, timeout=30.0)

    def _spawn_local_worker(self, ep: VmWorkerEndpoint):
        worker_args = [
            sys.executable,
            "-u",
            str(GUEST_WORKER_SCRIPT),
            "--bind", ep.host,
            "--ip", ep.host,
            "--rpc-port", str(ep.port),
            "--id", ep.worker_id,
            "--label", ep.label,
            "--platform", ep.platform,
            "--os-type", ep.os_type,
            "--link-type", ep.link_type,
            "--master-url", self.master_url,
            "--secret", "wrong-untrusted-secret" if ep.rogue else self.cluster_secret,
            "--heartbeat-sec", "0" if ep.rogue else "2.0",
            "--total-ram-mb", str(ep.total_ram_mb),
            "--available-ram-mb", str(ep.available_ram_mb),
            "--vram-mb", str(ep.vram_mb),
            "--big-cores", str(ep.big_cores),
            "--temp-c", str(ep.temp_c),
        ]
        if ep.rogue:
            worker_args.append("--rogue")

        if self._use_unshare:
            cmd = [
                "unshare", "--uts", "--ipc", "--pid", "--fork",
                "sh", "-c",
                'hostname "$1" 2>/dev/null || true; shift; exec "$@"',
                "vm-init",
                ep.worker_id,
                *worker_args,
            ]
        else:
            cmd = worker_args

        proc = subprocess.Popen(
            cmd,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
            start_new_session=True,
        )
        self._procs.append(proc)
        if not ep.rogue:
            self._registered_ids.add(ep.worker_id)

        deadline = time.time() + 6.0
        while time.time() < deadline:
            line = proc.stdout.readline().strip() if proc.stdout else ""
            if line.startswith("READY "):
                return
            if proc.poll() is not None:
                err = proc.stderr.read() if proc.stderr else ""
                raise RuntimeError(f"Worker {ep.worker_id} exited early ({proc.returncode}): {err}")
            time.sleep(0.05)
        raise RuntimeError(f"Timed out waiting for worker {ep.worker_id} READY")

    @staticmethod
    def _wait_tcp(host: str, port: int, timeout: float = 20.0):
        deadline = time.time() + timeout
        while time.time() < deadline:
            try:
                with socket.create_connection((host, port), timeout=1.5):
                    return
            except OSError:
                time.sleep(0.4)
        raise RuntimeError(f"Timed out waiting for QEMU VM TCP endpoint {host}:{port}")

    def register_worker(self, ep: VmWorkerEndpoint, temp_c: float | None = None) -> tuple[int, dict]:
        payload = {
            "id": ep.worker_id,
            "label": ep.label,
            "platform": ep.platform,
            "osType": ep.os_type,
            "ip": ep.host,
            "rpcPort": ep.port,
            "linkType": ep.link_type,
            "totalRamMb": ep.total_ram_mb,
            "availableRamMb": ep.available_ram_mb,
            "vramMb": ep.vram_mb,
            "bigCores": ep.big_cores,
            "tempC": ep.temp_c if temp_c is None else temp_c,
        }
        st, body = http_json(self.master_url, "/api/cluster/nodes/register", payload, method="POST")
        if st == 200 and body.get("ok"):
            self._registered_ids.add(ep.worker_id)
        return st, body

    def unregister_worker(self, worker_id: str) -> tuple[int, dict]:
        st, body = http_json(
            self.master_url,
            "/api/cluster/nodes/unregister",
            {"id": worker_id},
            method="POST",
        )
        self._registered_ids.discard(worker_id)
        return st, body

    def stop(self):
        for proc in self._procs:
            if proc.poll() is None:
                try:
                    os.killpg(os.getpgid(proc.pid), signal.SIGTERM)
                except Exception:
                    proc.terminate()
                try:
                    proc.wait(timeout=3)
                except subprocess.TimeoutExpired:
                    try:
                        os.killpg(os.getpgid(proc.pid), signal.SIGKILL)
                    except Exception:
                        proc.kill()
            for stream in (proc.stdout, proc.stderr):
                if stream is not None:
                    try:
                        stream.close()
                    except Exception:
                        pass
        self._procs.clear()

        if self.mode != "qemu":
            for wid in list(self._registered_ids):
                try:
                    self.unregister_worker(wid)
                except Exception:
                    pass
            try:
                http_json(self.master_url, "/api/cluster/state", {"upstreamBaseUrl": ""}, method="POST")
                http_json(self.master_url, "/api/models/activate", {"modelId": "small-1.5b"}, method="POST")
            except Exception:
                pass

        if self._tmp_models_dir:
            shutil.rmtree(self._tmp_models_dir, ignore_errors=True)
            self._tmp_models_dir = None
