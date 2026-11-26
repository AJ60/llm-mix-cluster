#!/usr/bin/env bash
# ==============================================================================
# scripts/setup_android_node.sh
# 1-Click Android Worker Node Setup & Registration for Silica Cluster
# ==============================================================================
set -e

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
echo "=========================================================="
echo "  Silica Cluster - Android Worker Node Automatic Setup    "
echo "=========================================================="

SERIAL=$(adb devices | awk '/device$/ {print $1; exit}')
if [ -z "$SERIAL" ]; then
    echo "[ERROR] No authorized Android device connected via USB ADB!"
    exit 1
fi
echo "[OK] Detected authorized Android device: $SERIAL"

echo "[*] Cleaning up rogue legacy workers..."
adb -s "$SERIAL" shell "pkill -9 -f com.silica.cluster.WorkerNode 2>/dev/null; rm -f /data/local/tmp/silica_worker.dex 2>/dev/null" || true
adb -s "$SERIAL" shell "am start -n com.aistudio.clusterlab.kxmpzq/com.example.service.RpcControlActivity" || true

echo "[*] Configuring ADB port tunnels (RPC: 50052->50055, HTTP: 3000)..."
adb -s "$SERIAL" reverse tcp:3000 tcp:3000 > /dev/null || true
adb -s "$SERIAL" forward tcp:50052 tcp:50055 > /dev/null || true
adb -s "$SERIAL" forward tcp:50055 tcp:50055 > /dev/null || true

echo "[*] Android companion node is running in background on device."
echo "=========================================================="
