#!/usr/bin/env python3
"""
tests/qemu/guest_rpc_worker.py
Silica Cluster Alpine Linux / QEMU Guest RPC Worker Daemon (stdlib only).

Runs inside a QEMU Alpine Linux VM (reading `silica.*` kernel cmdline args)
or directly inside an isolated Linux namespace during local verification:
  1. Listens on TCP `RPC_PORT` and enforces the bit-exact 32-byte keyed
     `GGUF` / RPC v9 / `v1.0.0` binary handshake matching Rust `protocol.rs`,
     TypeScript `clusterAuth.ts`, and Android `RpcService.kt`.
  2. Sends periodic HTTP heartbeats to `MASTER_URL/api/cluster/nodes/register`.
  3. On SIGTERM/SIGINT, immediately calls `/api/cluster/nodes/unregister`
     so zero ghost nodes remain on the Master.
"""

import argparse
import json
import os
import shutil
import signal
import socket
import struct
import sys
import threading
import time
import urllib.error
import urllib.request

HANDSHAKE_CHALLENGE = b"GGUF_PROBE_V10"
REPLY_CHALLENGE_V10 = b"GGUF_REPLY_V10"
HANDSHAKE_MAGIC_V10 = b"NONC"
RPC_PROTOCOL_VERSION = 10
STOCK_VERSION_TAG = b"v1.0.0\x00\x00"
MASK_64 = (1 << 64) - 1


def rotl64(x: int, k: int) -> int:
    return (((x << k) & MASK_64) | (x >> (64 - k))) & MASK_64


def rotr64(x: int, k: int) -> int:
    return ((x >> k) | ((x << (64 - k)) & MASK_64)) & MASK_64


def compute_cluster_auth_token(secret: str, challenge: bytes = HANDSHAKE_CHALLENGE) -> bytes:
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


def constant_time_eq(a: bytes, b: bytes) -> bool:
    if len(a) != len(b):
        return False
    diff = 0
    for x, y in zip(a, b):
        diff |= x ^ y
    return diff == 0



def decode_v10_request(secret: str, frame: bytes) -> bytes | None:
    if len(frame) < 32 or frame[0:4] != HANDSHAKE_MAGIC_V10:
        return None
    nonce = frame[4:12]
    expected = HANDSHAKE_MAGIC_V10 + nonce + compute_cluster_auth_token(secret, HANDSHAKE_CHALLENGE + nonce)[:20]
    return nonce if constant_time_eq(frame[12:32], expected[12:32]) else None


def reply_mac_v10(secret: str, nonce: bytes, cap: int, cores: int, flags: int, status: int) -> bytes:
    data = REPLY_CHALLENGE_V10 + nonce + struct.pack("<I", cap) + bytes([cores]) + struct.pack("<H", flags) + bytes([status])
    token = compute_cluster_auth_token(secret, data)
    return bytes(token[i + 8] ^ token[i + 24] for i in range(8))


def encode_v10_reply(secret: str, nonce: bytes, cap: int, cores: int, flags: int, status: int) -> bytes:
    packed = (status & 0xFF) | ((flags & 0xFFFF) << 8) | ((cores & 0xFF) << 24)
    return b"GGUF" + struct.pack("<I", 10) + nonce + reply_mac_v10(secret, nonce, cap, cores, flags, status) + struct.pack("<II", cap, packed)


def encode_handshake_frame(safe_cap_mb: int, big_cores: int, flags: int, status: int = 0) -> bytes:
    return (
        b"GGUF"
        + struct.pack("<I", RPC_PROTOCOL_VERSION)
        + STOCK_VERSION_TAG
        + struct.pack("<IIII", safe_cap_mb, big_cores, flags, status)
    )


def recv_exact(conn: socket.socket, length: int) -> bytes:
    data = b""
    while len(data) < length:
        chunk = conn.recv(length - len(data))
        if not chunk:
            break
        data += chunk
    return data


def parse_proc_cmdline() -> dict[str, str]:
    values: dict[str, str] = {}
    try:
        with open("/proc/cmdline", "r", encoding="utf-8") as fh:
            for token in fh.read().strip().split():
                if token.startswith("silica.") and "=" in token:
                    k, v = token[len("silica.") :].split("=", 1)
                    values[k] = v
    except OSError:
        pass
    return values


def post_json(url: str, payload: dict, timeout: float = 4.0) -> tuple[int, dict]:
    data = json.dumps(payload).encode("utf-8")
    req = urllib.request.Request(
        url,
        data=data,
        headers={"Content-Type": "application/json", "Accept": "application/json"},
        method="POST",
    )
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            return resp.status, json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as err:
        raw = err.read().decode("utf-8", errors="replace")
        try:
            return err.code, json.loads(raw)
        except json.JSONDecodeError:
            return err.code, {"raw": raw}


def main():
    parser = argparse.ArgumentParser(description="Silica QEMU/Alpine Guest RPC Worker")
    parser.add_argument("--from-proc-cmdline", action="store_true")
    parser.add_argument("--bind", default="0.0.0.0")
    parser.add_argument("--ip", default="127.0.0.1")
    parser.add_argument("--rpc-port", type=int, default=50052)
    parser.add_argument("--id", default="qemu-alpine-worker")
    parser.add_argument("--label", default="QEMU Alpine Worker")
    parser.add_argument("--platform", default="Linux")
    parser.add_argument("--os-type", default="linux")
    parser.add_argument("--link-type", default="Ethernet 2.5G")
    parser.add_argument("--master-url", default="")
    parser.add_argument("--secret", default="clusterd-default-hmac-key")
    parser.add_argument("--heartbeat-sec", type=float, default=6.0)
    parser.add_argument("--total-ram-mb", type=int, default=8192)
    parser.add_argument("--available-ram-mb", type=int, default=5600)
    parser.add_argument("--vram-mb", type=int, default=0)
    parser.add_argument("--big-cores", type=int, default=4)
    parser.add_argument("--temp-c", type=float, default=40.0)
    parser.add_argument("--rogue", action="store_true")
    args = parser.parse_args()

    if args.from_proc_cmdline:
        kv = parse_proc_cmdline()
        args.id = kv.get("id", args.id)
        args.label = kv.get("label", args.label).replace("_", " ")
        args.platform = kv.get("platform", args.platform)
        args.os_type = kv.get("os", args.os_type)
        args.link_type = kv.get("link", args.link_type).replace("_", " ")
        args.ip = kv.get("ip", args.ip)
        args.rpc_port = int(kv.get("rpc_port", args.rpc_port))
        args.master_url = kv.get("master", args.master_url)
        args.secret = kv.get("secret", args.secret)
        args.heartbeat_sec = float(kv.get("hb", args.heartbeat_sec))
        args.total_ram_mb = int(kv.get("ram", args.total_ram_mb))
        args.available_ram_mb = int(kv.get("avail_ram", args.available_ram_mb))
        args.vram_mb = int(kv.get("vram", args.vram_mb))
        args.big_cores = int(kv.get("cores", args.big_cores))
        args.temp_c = float(kv.get("temp", args.temp_c))
        args.rogue = kv.get("rogue", "0") == "1"

    safe_cap_mb = (
        int(min(args.total_ram_mb * 0.5, args.available_ram_mb * 0.8))
        if args.platform.lower() == "android"
        else max(512, int(args.available_ram_mb * 0.82))
    )
    flags = (1 << 6) | (1 << 1) if args.platform.lower() == "android" else (1 << 5)

    stop_event = threading.Event()

    srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind((args.bind, args.rpc_port))
    srv.listen(32)
    srv.settimeout(0.5)

    def handle_signal(_signum, _frame):
        stop_event.set()

    signal.signal(signal.SIGTERM, handle_signal)
    signal.signal(signal.SIGINT, handle_signal)

    def send_heartbeat():
        if not args.master_url or args.rogue or args.heartbeat_sec <= 0:
            return
        payload = {
            "id": args.id,
            "label": args.label,
            "platform": args.platform,
            "osType": args.os_type,
            "ip": args.ip,
            "rpcPort": args.rpc_port,
            "linkType": args.link_type,
            "totalRamMb": args.total_ram_mb,
            "availableRamMb": args.available_ram_mb,
            "vramMb": args.vram_mb,
            "bigCores": args.big_cores,
            "tempC": args.temp_c,
            "cpuUsagePct": 12,
            "gpuUsagePct": 8 if args.vram_mb > 0 else 0,
            "computeTflops": 2.2,
            "storageTotalMb": shutil.disk_usage("/").total / (1024 * 1024),
            "storageFreeMb": shutil.disk_usage("/").free / (1024 * 1024),
        }
        try:
            st, _ = post_json(f"{args.master_url.rstrip('/')}/api/cluster/nodes/register", payload)
            print(f"[WORKER:{args.id}] heartbeat -> HTTP {st}", flush=True)
        except Exception as exc:
            print(f"[WORKER:{args.id}] heartbeat error: {exc}", flush=True)

    def heartbeat_loop():
        # Brief initial pause so TCP socket is accepting before Master probes back
        time.sleep(0.2)
        while not stop_event.is_set():
            send_heartbeat()
            stop_event.wait(args.heartbeat_sec)

    hb_thread = threading.Thread(target=heartbeat_loop, daemon=True)
    if args.master_url and args.heartbeat_sec > 0 and not args.rogue:
        hb_thread.start()

    print(f"READY {args.bind}:{args.rpc_port} id={args.id} rogue={int(args.rogue)}", flush=True)

    try:
        while not stop_event.is_set():
            try:
                conn, _ = srv.accept()
            except socket.timeout:
                continue
            except OSError:
                break
            try:
                conn.settimeout(2.0)
                received = b""
                while len(received) < 32:
                    chunk = conn.recv(32 - len(received))
                    if not chunk:
                        break
                    received += chunk
                nonce = None if args.rogue or len(received) < 32 else decode_v10_request(args.secret, received[:32])
                if nonce is None:
                    conn.sendall(b"NOPE" + b"\x00" * 28)
                else:
                    status = 2 if args.temp_c >= 48.0 else (1 if args.temp_c >= 43.0 else 0)
                    conn.sendall(encode_v10_reply(args.secret, nonce, safe_cap_mb, args.big_cores, flags, status))
                    header = recv_exact(conn, 8)
                    if len(header) == 8 and header[0:4] == b"BWPR":
                        length = struct.unpack("<I", header[4:8])[0]
                        if 0 < length <= 4 * 1024 * 1024:
                            payload = recv_exact(conn, length)
                            if len(payload) == length:
                                conn.sendall(b"BWOK" + struct.pack("<I", length) + payload)
            except Exception:
                pass
            finally:
                conn.close()
    finally:
        srv.close()
        if args.master_url and not args.rogue:
            try:
                post_json(
                    f"{args.master_url.rstrip('/')}/api/cluster/nodes/unregister",
                    {"id": args.id},
                    timeout=2.0,
                )
            except Exception:
                pass


if __name__ == "__main__":
    main()
