# Silica Cluster — QEMU / Alpine Linux VM Test Suite

This directory provides reproducible multi-VM integration tests for Silica Cluster's **32-byte keyed `GGUF` / RPC v9 heartbeat protocol** and **binary GGUF v3 + companion `mmproj` model loading** against headless **QEMU/Alpine Linux 3.21 virtual machines**.

## Files
- `docker-compose.yaml` — Orchestrates `silica-master` (`172.29.0.10`), two verified QEMU/Alpine Linux worker VMs (`172.29.0.21`, `172.29.0.22`), one rogue unauthenticated QEMU/Alpine VM (`172.29.0.29`), and the Python `test-runner` container on an isolated bridge subnet (`172.29.0.0/24`).
- `qemu/Dockerfile.qemu-alpine` & `qemu/boot_alpine_vm.sh` — Builds and boots a minimal headless `qemu-system-x86_64` Alpine Linux 3.21 VM (automatically using KVM when `/dev/kvm` is present, or TCG software virtualization otherwise).
- `qemu/guest_rpc_worker.py` — Runs inside the Alpine Linux QEMU guest VM, implementing the bit-exact 32-byte `GGUF` / RPC v9 TCP handshake server, periodic HTTP heartbeat loop, and clean shutdown unregister.
- `test_cluster_node.py` — Unified test entry point + end-to-end multi-VM cluster lifecycle checks.
- `test_rpc_heartbeat.py` — Verifies RPC v9 beacon, 32-byte keyed TCP handshakes, periodic heartbeats, rogue VM rejection (`HTTP 502`), thermal overload shedding (`49.5°C` -> `0` layers), and immediate zero-ghost-node unregister.
- `test_gguf_model_loading.py` — Verifies binary GGUF v3 header parsing (Dense & MoE), companion `*mmproj*.gguf` auto-pairing, corrupt/missing file rejection, Zero-OOM sharding + stock `llama.cpp` CLI flags, draft vocab-hash mismatch rejection, and honest `HTTP 503 ENGINE_OFFLINE`.

## Running With QEMU / Alpine VMs (Docker Compose)
```bash
docker compose -f tests/docker-compose.yaml up --build --abort-on-container-exit
docker compose -f tests/docker-compose.yaml down -v
```

To enable hardware KVM acceleration inside the QEMU worker containers on a Linux KVM host, mount `/dev/kvm` into `qemu-alpine-worker-*` via a Compose override (`devices: ["/dev/kvm:/dev/kvm"]`). Without `/dev/kvm`, QEMU falls back cleanly to multi-threaded TCG.

## Running Locally Without Docker (Isolated Linux Namespaces)
When `CLUSTER_TEST_MODE=local` (the default), `cluster_test_lib.py` boots `qemu/guest_rpc_worker.py` inside isolated Linux namespaces (`unshare --uts --ipc --pid --fork`) with distinct per-node virtual IPs against a running Silica Master on `http://127.0.0.1:3000`:

```bash
python3 tests/test_cluster_node.py -v
```
