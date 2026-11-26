#!/usr/bin/env bash
# =============================================================================
# scripts/setup_termux_android.sh
# Non-root Termux setup and runtime launcher for Android 10..16 devices.
# - Builds stock llama.cpp rpc-server natively in Termux (ARMv8.2-A + dotprod)
# - Enforces 16 KB ELF page alignment for Android 15/16 (API 35/36)
# - Acquires termux-wake-lock to prevent CPU sleep during inference
# - Implements <= 50% physical RAM cap with 512 MB reserve to prevent LMK kills
# =============================================================================
set -euo pipefail

RPC_PORT="${RPC_PORT:-50052}"
LLAMA_TAG="${LLAMA_TAG:-v1.0.0}"
WORK_DIR="${HOME}/.clusterlab-worker"
mkdir -p "${WORK_DIR}"

echo "=== ClusterLab Android Termux Worker Setup ==="

# 1. Install toolchain
if command -v pkg >/dev/null 2>&1; then
  echo "[1/4] Installing Termux toolchain (clang, cmake, ninja, git, termux-api)..."
  pkg update -y
  pkg install -y clang cmake ninja git termux-api || true
fi

# 2. Acquire wake lock
if command -v termux-wake-lock >/dev/null 2>&1; then
  echo "[2/4] Acquiring Termux Wake Lock..."
  termux-wake-lock
fi

# 3. Calculate safe RAM ceiling (<= 50% physical RAM, reserving at least 512 MB free)
MEM_TOTAL_KB="$(awk '/^MemTotal:/ {print $2}' /proc/meminfo || echo 4194304)"
MEM_AVAIL_KB="$(awk '/^MemAvailable:/ {print $2}' /proc/meminfo || echo 2097152)"
MEM_TOTAL_MB=$(( MEM_TOTAL_KB / 1024 ))
MEM_AVAIL_MB=$(( MEM_AVAIL_KB / 1024 ))
HALF_TOTAL_MB=$(( MEM_TOTAL_MB / 2 ))
AVAIL_MINUS_512=$(( MEM_AVAIL_MB > 768 ? MEM_AVAIL_MB - 512 : 256 ))
SAFE_CAP_MB=$(( HALF_TOTAL_MB < AVAIL_MINUS_512 ? HALF_TOTAL_MB : AVAIL_MINUS_512 ))

# Detect Big Cores (>= 80% max frequency)
detect_big_cores() {
  local max_freq=0
  local count=0
  for f in /sys/devices/system/cpu/cpu[0-9]*/cpufreq/cpuinfo_max_freq; do
    [[ -r "$f" ]] || continue
    local cur
    cur="$(cat "$f" 2>/dev/null || echo 0)"
    if (( cur > max_freq )); then
      max_freq="$cur"
    fi
  done
  if (( max_freq > 0 )); then
    local threshold=$(( max_freq * 80 / 100 ))
    for f in /sys/devices/system/cpu/cpu[0-9]*/cpufreq/cpuinfo_max_freq; do
      [[ -r "$f" ]] || continue
      local cur
      cur="$(cat "$f" 2>/dev/null || echo 0)"
      if (( cur >= threshold )); then
        count=$(( count + 1 ))
      fi
    done
  fi
  if (( count <= 0 )); then
    count="$(nproc)"
  fi
  echo "${count}"
}

BIG_CORES="$(detect_big_cores)"
HAS_DOTPROD="$(grep -iE 'Features.*(asimddp|dotprod)' /proc/cpuinfo || true)"
CPU_MARCH="-march=armv8-a"
if [[ -n "${HAS_DOTPROD}" ]]; then
  CPU_MARCH="-march=armv8.2-a+dotprod"
fi

echo "[3/4] Hardware Specs: Total=${MEM_TOTAL_MB}MB, SafeCap=${SAFE_CAP_MB}MB, Cores=${BIG_CORES} (${CPU_MARCH})"

# 4. Clone & build llama.cpp rpc-server if not already compiled
LLAMA_DIR="${WORK_DIR}/llama.cpp"
if [[ ! -x "${LLAMA_DIR}/build/bin/rpc-server" ]]; then
  echo "[4/4] Building llama.cpp rpc-server (${LLAMA_TAG}) with 16 KB page alignment..."
  if [[ ! -d "${LLAMA_DIR}" ]]; then
    git clone --depth 1 --branch "${LLAMA_TAG}" https://github.com/ggml-org/llama.cpp.git "${LLAMA_DIR}" || \
      git clone --depth 1 https://github.com/ggml-org/llama.cpp.git "${LLAMA_DIR}"
  fi
  cmake -S "${LLAMA_DIR}" -B "${LLAMA_DIR}/build" -G Ninja \
    -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_C_FLAGS="${CPU_MARCH} -O3" \
    -DCMAKE_CXX_FLAGS="${CPU_MARCH} -O3" \
    -DCMAKE_EXE_LINKER_FLAGS="-Wl,-z,max-page-size=16384" \
    -DGGML_RPC=ON \
    -DGGML_CPU_ALL_VARIANTS=ON
  cmake --build "${LLAMA_DIR}/build" --target rpc-server -j"${BIG_CORES}"
fi

echo "=================================================================="
echo " Android RPC Worker listening on 0.0.0.0:${RPC_PORT}"
echo " Connect from PC via USB ADB (scripts/setup_android.ps1) or Wi-Fi"
echo "=================================================================="
exec "${LLAMA_DIR}/build/bin/rpc-server" \
  -H 0.0.0.0 \
  -p "${RPC_PORT}" \
  -t "${BIG_CORES}" \
  -m "${SAFE_CAP_MB}" \
  -c
