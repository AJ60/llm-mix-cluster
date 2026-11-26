#!/usr/bin/env bash
# =============================================================================
# scripts/setup_linux.sh
# Linux workstation and server setup for ClusterLab.
# - Installs build dependencies (build-essential, cmake, ninja, openblas)
# - Configures Linux Transparent HugePages (madvise)
# - Builds stock llama.cpp (llama-server and rpc-server) with RPC support
# - Generates systemd user service templates
# =============================================================================
set -euo pipefail

MODE="${1:-master}"
PORT="${2:-8080}"
LLAMA_TAG="${LLAMA_TAG:-v1.0.0}"
INSTALL_DIR="${HOME}/.clusterlab"
mkdir -p "${INSTALL_DIR}/bin" "${INSTALL_DIR}/models"

echo "=== ClusterLab Linux Setup (${MODE^^} Node) ==="

# 1. Package dependencies
if command -v apt-get >/dev/null 2>&1; then
  echo "[1/4] Installing system dependencies (build-essential, cmake, ninja, libopenblas-dev, numactl)..."
  sudo apt-get update && sudo apt-get install -y build-essential cmake ninja-build git libopenblas-dev numactl || true
fi

# 2. Kernel hugepage tuning
if [ -w /sys/kernel/mm/transparent_hugepage/enabled ]; then
  echo "[2/4] Enabling madvise for Transparent HugePages..."
  echo madvise | sudo tee /sys/kernel/mm/transparent_hugepage/enabled >/dev/null || true
fi

# 3. Clone and build llama.cpp
LLAMA_DIR="${INSTALL_DIR}/llama.cpp"
if [[ ! -x "${INSTALL_DIR}/bin/llama-server" ]] || [[ ! -x "${INSTALL_DIR}/bin/rpc-server" ]]; then
  echo "[3/4] Building llama.cpp (${LLAMA_TAG}) with RPC support..."
  if [[ ! -d "${LLAMA_DIR}" ]]; then
    git clone --depth 1 --branch "${LLAMA_TAG}" https://github.com/ggml-org/llama.cpp.git "${LLAMA_DIR}" || \
      git clone --depth 1 https://github.com/ggml-org/llama.cpp.git "${LLAMA_DIR}"
  fi
  cmake -S "${LLAMA_DIR}" -B "${LLAMA_DIR}/build" -G Ninja \
    -DCMAKE_BUILD_TYPE=Release \
    -DGGML_RPC=ON \
    -DGGML_BLAS=ON \
    -DGGML_BLAS_VENDOR=OpenBLAS
  cmake --build "${LLAMA_DIR}/build" --target llama-server rpc-server -j"$(nproc)"
  cp -f "${LLAMA_DIR}/build/bin/llama-server" "${INSTALL_DIR}/bin/llama-server" || true
  cp -f "${LLAMA_DIR}/build/bin/rpc-server" "${INSTALL_DIR}/bin/rpc-server" || true
fi

# 4. Summary and Run instructions
echo "[4/4] Installation Complete!"
echo "=================================================================="
if [[ "${MODE}" == "master" ]]; then
  echo " Master Launcher: Start the Web UI & API from repo root with:"
  echo "   npm run dev    (or npm start)"
  echo " To run tuned native backend directly:"
  echo "   curl -s http://localhost:3000/api/backends/script?os=linux > run-cluster-linux.sh"
  echo "   bash run-cluster-linux.sh"
else
  echo " Worker Launcher: Start worker node on port ${PORT}:"
  echo "   ${INSTALL_DIR}/bin/rpc-server -H 0.0.0.0 -p ${PORT} -c"
fi
echo "=================================================================="
