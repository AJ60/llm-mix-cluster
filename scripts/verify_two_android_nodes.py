#!/usr/bin/env python3
"""Verify two Android workers simultaneously against a live Rust clusterd master."""

import argparse
import os
import pathlib
import shutil
import socket
import subprocess
import sys
import tempfile
import time
import urllib.request

ROOT = pathlib.Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "tests"))

from cluster_test_lib import probe_tcp_rpc_handshake  # noqa: E402

PACKAGE = "com.aistudio.clusterlab.kxmpzq"
ACTIVITY = f"{PACKAGE}/com.example.service.RpcControlActivity"
SECRET = "clusterd-default-hmac-key"
PORT_BASE = 50055
MASTER_PORT = 3000


def adb(serial: str, *args: str, check: bool = True) -> str:
    cmd = [shutil.which("adb") or "adb", "-s", serial, *args]
    result = subprocess.run(cmd, capture_output=True, text=True)
    if check and result.returncode != 0:
        raise RuntimeError(f"adb {' '.join(args)} failed on {serial}: {result.stderr.strip()}")
    return result.stdout.strip()


def devices() -> list[str]:
    out = subprocess.run(
        [shutil.which("adb") or "adb", "devices"], capture_output=True, text=True, check=True
    ).stdout
    return [
        line.split()[0]
        for line in out.splitlines()[1:]
        if line.strip().endswith("device")
    ]


def wait_health(base_url: str, timeout: float = 15.0) -> None:
    deadline = time.time() + timeout
    while time.time() < deadline:
        try:
            with urllib.request.urlopen(f"{base_url}/health", timeout=1) as response:
                if response.status == 200:
                    return
        except Exception:
            time.sleep(0.2)
    raise RuntimeError(f"master at {base_url} not healthy")


def wait_tcp_port(port: int, timeout: float = 15.0) -> None:
    deadline = time.time() + timeout
    while time.time() < deadline:
        try:
            with socket.create_connection(("127.0.0.1", port), timeout=0.5):
                return
        except OSError:
            time.sleep(0.2)
    raise RuntimeError(f"port {port} never started listening")


def wait_workers(base_url: str, expected: int, timeout: float = 90.0) -> dict:
    deadline = time.time() + timeout
    last = {}
    while time.time() < deadline:
        try:
            with urllib.request.urlopen(f"{base_url}/api/cluster/hardware", timeout=2) as response:
                last = __import__("json").loads(response.read().decode())
            if len(last.get("verifiedWorkers", [])) >= expected:
                return last
        except Exception:
            pass
        time.sleep(1.0)
    raise RuntimeError(f"only {len(last.get('verifiedWorkers', []))} workers verified: {last}")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--install", action="store_true", help="install debug APK on both devices")
    parser.add_argument("--apk", default=str(ROOT / "app/build/outputs/apk/debug/app-debug.apk"))
    parser.add_argument("--master-url", default=f"http://127.0.0.1:{MASTER_PORT}")
    args = parser.parse_args()

    serials = devices()
    if len(serials) < 2:
        print(f"Need two attached devices, found {serials}", file=sys.stderr)
        return 1
    serials = serials[:2]

    master_proc = None
    if not pathlib.Path(args.apk).exists():
        print(f"APK not found: {args.apk}", file=sys.stderr)
        return 1

    try:
        urllib.request.urlopen(f"{args.master_url}/health", timeout=1)
        print(f"Using existing master at {args.master_url}")
    except Exception:
        binary = next(
            (
                p
                for p in (
                    ROOT / "silica-core/target/release/clusterd.exe",
                    ROOT / "silica-core/target/debug/clusterd.exe",
                    ROOT / "silica-core/target/release/clusterd",
                    ROOT / "silica-core/target/debug/clusterd",
                )
                if p.exists()
            ),
            None,
        )
        if binary is None:
            print("Build clusterd first: cargo build --manifest-path silica-core/Cargo.toml", file=sys.stderr)
            return 1
        cwd = tempfile.mkdtemp(prefix="silica-two-node-")
        env = os.environ.copy()
        env["CLUSTER_SECRET"] = SECRET
        master_proc = subprocess.Popen(
            [str(binary), "--port", str(MASTER_PORT), "--bind", "127.0.0.1"],
            cwd=cwd,
            env=env,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
        )
        time.sleep(1.0)
    wait_health(args.master_url)

    try:
        for index, serial in enumerate(serials):
            host_port = PORT_BASE + index
            print(f"[{serial}] reverse :{MASTER_PORT}, forward {host_port} -> {host_port}")
            adb(serial, "reverse", f"tcp:{MASTER_PORT}", f"tcp:{MASTER_PORT}")
            adb(serial, "forward", f"tcp:{host_port}", f"tcp:{host_port}")
            if args.install:
                adb(serial, "install", "-r", args.apk)
            adb(serial, "shell", "am", "force-stop", PACKAGE)
            adb(
                serial,
                "shell",
                "am",
                "start",
                "-n",
                ACTIVITY,
                "--ei",
                "extra_rpc_port",
                str(host_port),
                "--ez",
                "extra_force_embedded_rpc",
                "true",
            )

        time.sleep(1.5)
        for index in range(len(serials)):
            host_port = PORT_BASE + index
            wait_tcp_port(host_port)
            deadline = time.time() + 60.0
            while True:
                try:
                    ok, frame, rtt = probe_tcp_rpc_handshake("127.0.0.1", host_port, SECRET, timeout=4.0)
                except (OSError, TimeoutError) as exc:
                    ok, frame, rtt = False, None, 0.0
                    print(f"port {host_port}: probe retry ({exc})")
                if ok:
                    print(f"port {host_port}: v10 handshake OK ({rtt:.1f}ms, cap={frame['safe_cap_mb']}MB)")
                    break
                if time.time() > deadline:
                    raise RuntimeError(f"port {host_port} never answered v10 handshake")
                time.sleep(1.0)

        state = wait_workers(args.master_url, len(serials))
        print(f"master verified {len(state['verifiedWorkers'])} workers:")
        for worker in state["verifiedWorkers"]:
            print(f"  {worker['node_id']} @ {worker['ip']}:{worker['rpc_port']}")
        return 0
    finally:
        for serial in serials:
            adb(serial, "shell", "am", "force-stop", PACKAGE, check=False)
            adb(serial, "forward", "--remove", f"tcp:{PORT_BASE + serials.index(serial)}", check=False)
            adb(serial, "reverse", "--remove", f"tcp:{MASTER_PORT}", check=False)
        if master_proc is not None:
            master_proc.terminate()
            try:
                master_proc.wait(timeout=5)
            except subprocess.TimeoutExpired:
                master_proc.kill()


if __name__ == "__main__":
    sys.exit(main())
