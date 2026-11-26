#!/usr/bin/env bash
# ==============================================================================
# tests/qemu/boot_alpine_vm.sh
# Boots a headless QEMU x86_64 Alpine Linux VM with virtio-net user networking
# forwarding container port RPC_PORT -> guest port RPC_PORT.
# ==============================================================================
set -euo pipefail

VM_NAME="${VM_NAME:-qemu-alpine-worker}"
VM_MEMORY_MB="${VM_MEMORY_MB:-384}"
VM_CPUS="${VM_CPUS:-2}"
RPC_PORT="${RPC_PORT:-50052}"

WORKER_ID="${WORKER_ID:-$VM_NAME}"
WORKER_LABEL="${WORKER_LABEL:-QEMU Alpine Worker}"
WORKER_PLATFORM="${WORKER_PLATFORM:-Linux}"
WORKER_OS="${WORKER_OS:-linux}"
WORKER_LINK="${WORKER_LINK:-Ethernet 2.5G}"
WORKER_IP="${WORKER_IP:-127.0.0.1}"
MASTER_URL="${MASTER_URL:-http://10.0.2.2:3000}"
CLUSTER_SECRET="${CLUSTER_SECRET:-clusterd-default-hmac-key}"
HEARTBEAT_INTERVAL_SEC="${HEARTBEAT_INTERVAL_SEC:-6}"
TOTAL_RAM_MB="${TOTAL_RAM_MB:-8192}"
AVAILABLE_RAM_MB="${AVAILABLE_RAM_MB:-5600}"
VRAM_MB="${VRAM_MB:-0}"
BIG_CORES="${BIG_CORES:-4}"
TEMP_C="${TEMP_C:-42}"
ROGUE_MODE="${ROGUE_MODE:-0}"

KERNEL_PATH="/boot/vmlinuz-virt"
INITRD_PATH="/opt/silica-vm/alpine-worker-initrd.gz"

ACCEL_ARGS=(-accel tcg,thread=multi -cpu max)
if [ -e /dev/kvm ] && [ -r /dev/kvm ] && [ -w /dev/kvm ]; then
  ACCEL_ARGS=(-enable-kvm -cpu host)
  echo "[QEMU-BOOT] Hardware KVM acceleration enabled for ${VM_NAME}"
else
  echo "[QEMU-BOOT] /dev/kvm unavailable — running QEMU TCG software VM for ${VM_NAME}"
fi

# Pass worker configuration into Alpine guest cleanly via /proc/cmdline
CMDLINE="console=ttyS0 quiet panic=-1"
CMDLINE+=" silica.id=${WORKER_ID}"
CMDLINE+=" silica.label=${WORKER_LABEL// /_}"
CMDLINE+=" silica.platform=${WORKER_PLATFORM}"
CMDLINE+=" silica.os=${WORKER_OS}"
CMDLINE+=" silica.link=${WORKER_LINK// /_}"
CMDLINE+=" silica.ip=${WORKER_IP}"
CMDLINE+=" silica.rpc_port=${RPC_PORT}"
CMDLINE+=" silica.master=${MASTER_URL}"
CMDLINE+=" silica.secret=${CLUSTER_SECRET}"
CMDLINE+=" silica.hb=${HEARTBEAT_INTERVAL_SEC}"
CMDLINE+=" silica.ram=${TOTAL_RAM_MB}"
CMDLINE+=" silica.avail_ram=${AVAILABLE_RAM_MB}"
CMDLINE+=" silica.vram=${VRAM_MB}"
CMDLINE+=" silica.cores=${BIG_CORES}"
CMDLINE+=" silica.temp=${TEMP_C}"
CMDLINE+=" silica.rogue=${ROGUE_MODE}"

exec qemu-system-x86_64 \
  -name "${VM_NAME}" \
  -machine q35 \
  "${ACCEL_ARGS[@]}" \
  -smp "${VM_CPUS}" \
  -m "${VM_MEMORY_MB}" \
  -kernel "${KERNEL_PATH}" \
  -initrd "${INITRD_PATH}" \
  -append "${CMDLINE}" \
  -nographic \
  -no-reboot \
  -netdev "user,id=net0,hostfwd=tcp:0.0.0.0:${RPC_PORT}-:${RPC_PORT}" \
  -device "virtio-net-pci,netdev=net0"
