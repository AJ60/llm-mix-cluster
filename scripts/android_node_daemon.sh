#!/system/bin/sh
# ==============================================================================
# scripts/android_node_daemon.sh
# Lightweight native Android worker daemon for Silica Cluster over USB ADB
# Runs directly in non-root Android shell on physical device
# ==============================================================================

MASTER_URL="${1:-http://127.0.0.1:3000}"
RAW_MODEL=$(getprop ro.product.model 2>/dev/null || echo "Android Device")
RAW_MANUF=$(getprop ro.product.manufacturer 2>/dev/null || echo "")
RAW_SERIAL=$(getprop ro.serialno 2>/dev/null || echo "worker")
CLEAN_MODEL=$(echo "${RAW_MODEL}" | tr -cd 'A-Za-z0-9_ -' | tr '_' ' ')
CLEAN_MANUF=$(echo "${RAW_MANUF}" | tr -cd 'A-Za-z0-9_ -' | tr '_' ' ')
LABEL="${CLEAN_MANUF} ${CLEAN_MODEL} (Android)"
NODE_ID="android-$(echo "${RAW_MANUF}-${RAW_MODEL}-${RAW_SERIAL}" | tr '[:upper:]' '[:lower:]' | tr -cd 'a-z0-9-' | cut -c1-28)"
INTERVAL=4

echo "=================================================================="
echo " Starting Silica Android Node Daemon on ${NODE_ID}"
echo " Target Master : ${MASTER_URL}"
echo " Tunnel        : USB-C ADB Reverse Tunnel"
echo "=================================================================="

# If native ggml-rpc-server exists, launch it in background with -c persistent tensor cache
if [ -f "/data/local/tmp/ggml-rpc-server" ]; then
    mkdir -p /data/local/tmp/silica_tensor_cache
    if ! pgrep -f "ggml-rpc-server" >/dev/null 2>&1; then
        echo "[*] Launching native ggml-rpc-server with persistent tensor cache (-c)..."
        (cd /data/local/tmp/silica_tensor_cache && \
         export XDG_CACHE_HOME=/data/local/tmp/silica_tensor_cache \
         export HOME=/data/local/tmp/silica_tensor_cache \
         export LD_LIBRARY_PATH=/data/local/tmp \
         export GGML_VK_VISIBLE_DEVICES= \
         nohup /data/local/tmp/ggml-rpc-server -H 0.0.0.0 -p 50055 -t 6 -c > /data/local/tmp/rpc55.log 2>&1 &)
    fi
fi

# Clean up any legacy silica_worker.dex if present
rm -f /data/local/tmp/silica_worker.dex 2>/dev/null
pkill -9 -f com.silica.cluster.WorkerNode 2>/dev/null || true

# Fallback toybox loop if dex is absent
MEM_TOTAL_KB=$(awk '/^MemTotal:/ {print $2}' /proc/meminfo 2>/dev/null || echo 3702708)
MEM_TOTAL_MB=$((MEM_TOTAL_KB / 1024))
BIG_CORES=4

while true; do
  MEM_AVAIL_KB=$(awk '/^MemAvailable:/ {print $2}' /proc/meminfo 2>/dev/null || echo 1395948)
  MEM_AVAIL_MB=$((MEM_AVAIL_KB / 1024))

  RAW_TEMP=$(dumpsys battery 2>/dev/null | grep -i 'temperature:' | awk '{print $2}')
  if [ -n "$RAW_TEMP" ] && [ "$RAW_TEMP" -gt 0 ] 2>/dev/null; then
    TEMP_C=$(echo "$RAW_TEMP" | awk '{printf "%.1f", $1 / 10.0}')
  else
    TEMP_C="31.5"
  fi

  BATTERY_LVL=$(dumpsys battery 2>/dev/null | grep -i 'level:' | awk '{print $2}')
  [ -z "$BATTERY_LVL" ] && BATTERY_LVL=85

  PAYLOAD="{\"id\":\"${NODE_ID}\",\"label\":\"${LABEL}\",\"platform\":\"Android\",\"osType\":\"android\",\"ip\":\"127.0.0.1\",\"rpcPort\":50052,\"linkType\":\"USB-C Tunnel\",\"totalRamMb\":${MEM_TOTAL_MB},\"availableRamMb\":${MEM_AVAIL_MB},\"vramMb\":0,\"computeTflops\":1.8,\"memBwGbps\":28,\"bigCores\":${BIG_CORES},\"tempC\":${TEMP_C},\"allowHttpRegistration\":true}"

  RESP=$(curl -s -X POST "${MASTER_URL}/api/cluster/nodes/register" \
    -H "Content-Type: application/json" \
    -d "$PAYLOAD" 2>/dev/null)

  OK=$(echo "$RESP" | grep -o '"ok":true')
  if [ -n "$OK" ]; then
    LAYERS=$(echo "$RESP" | grep -o '"assignedLayers":[0-9]*' | cut -d: -f2)
    echo "[Heartbeat] Node OK · Layers: ${LAYERS}L · Temp: ${TEMP_C}°C · Battery: ${BATTERY_LVL}%"
  else
    echo "[Heartbeat] Master waiting on ${MASTER_URL}..."
  fi

  sleep $INTERVAL
done
