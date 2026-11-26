# ==============================================================================
# scripts/setup_android_node.ps1
# 1-Click Android Worker Node Setup & Registration for Silica Cluster
# ==============================================================================

Write-Host "==========================================================" -ForegroundColor Cyan
Write-Host "  Silica Cluster - Android Worker Node Automatic Setup    " -ForegroundColor Cyan
Write-Host "==========================================================" -ForegroundColor Cyan

# 1. Check ADB connection
$rawDevices = adb devices | Select-String "\bdevice\b"
if (-not $rawDevices) {
    Write-Host "[ERROR] No authorized Android device connected via USB ADB!" -ForegroundColor Red
    Write-Host "Please connect your Android phone with USB debugging enabled." -ForegroundColor Yellow
    exit 1
}

$devIndex = 0
foreach ($dev in $rawDevices) {
    $line = $dev.Line.Trim()
    $serial = ($line -split "\s+")[0].Trim()
    $hostPort = 50052 + $devIndex
    Write-Host "[OK] Configuring Android device ($($devIndex + 1)/$($rawDevices.Count)): $serial (Host Port: $hostPort)" -ForegroundColor Green

    # 2. Clean up any rogue legacy dex workers and ensure official companion app is running
    adb -s $serial shell "pkill -9 -f com.silica.cluster.WorkerNode 2>/dev/null; rm -f /data/local/tmp/silica_worker.dex 2>/dev/null" | Out-Null
    adb -s $serial shell "am start -n com.aistudio.clusterlab.kxmpzq/com.example.service.RpcControlActivity" | Out-Null

    # 3. Configure reverse tunnel for phone -> Master heartbeats
    adb -s $serial reverse tcp:3000 tcp:3000 2>$null | Out-Null

    # 4. Check if the Android Companion App (RpcService) or native RPC is already running
    $probePorts = @("50055", "50052", "50054")
    $alreadyListening = ""
    foreach ($p in $probePorts) {
        $check = (adb -s $serial shell "toybox netstat -tuln 2>/dev/null | toybox grep ':$p ' || true" 2>$null | Out-String).Trim()
        if ($check) {
            $alreadyListening = $p
            break
        }
    }

    if ($alreadyListening) {
        Write-Host "  [OK] Detected active Silica RPC server on phone port $alreadyListening" -ForegroundColor Green
        $boundPort = $alreadyListening
    } else {
        # 5. If no active companion app worker is running, launch native standalone daemon with -c cache
        $hasNative = (adb -s $serial shell "[ -f /data/local/tmp/ggml-rpc-server ] && echo 1" 2>$null | Out-String).Trim()
        if ($hasNative -eq "1") {
            Write-Host "  [*] Launching native ggml-rpc-server on port 50055 (Qualcomm-safe, -c flash cache)..." -ForegroundColor Cyan
            adb -s $serial shell "mkdir -p /data/local/tmp/silica_tensor_cache; cd /data/local/tmp/silica_tensor_cache; nohup /system/bin/sh -c 'export XDG_CACHE_HOME=/data/local/tmp/silica_tensor_cache; export HOME=/data/local/tmp/silica_tensor_cache; export LD_LIBRARY_PATH=/data/local/tmp; export GGML_VK_VISIBLE_DEVICES=; exec /data/local/tmp/ggml-rpc-server -H 0.0.0.0 -p 50055 -t 6 -c' > /data/local/tmp/rpc55.log 2>&1 &"
            $boundPort = "50055"
        } else {
            # Start RpcService companion app on port 50055
            adb -s $serial shell "am start -n com.aistudio.clusterlab.kxmpzq/com.example.service.RpcControlActivity" | Out-Null
            $boundPort = "50055"
        }
    }
    Write-Host "  [*] Forwarding PC port $hostPort -> Phone port $boundPort..." -ForegroundColor Cyan
    adb -s $serial forward "tcp:$hostPort" "tcp:$boundPort" 2>$null | Out-Null
    $devIndex++
}

# 8. Verify 32-byte GGUF RPC v9 handshake probe from PC
Write-Host "[*] Verifying 32-byte GGUF RPC v9 handshake over USB tunnel..." -ForegroundColor Cyan
$verifyCmd = 'const net = require("net"); const s = net.createConnection({host:"127.0.0.1",port:50052}, () => { s.write(Buffer.alloc(32)); }); s.on("data", d => { if (d.length >= 32 && d.toString("ascii", 0, 4) === "GGUF") { console.log("GGUF_RPC_V9_VERIFIED: safeCap=" + d.readUInt32LE(16) + "MB, cores=" + d.readUInt32LE(20)); s.destroy(); process.exit(0); } }); setTimeout(() => process.exit(1), 2500);'
$probeResult = node -e $verifyCmd 2>$null

if ($probeResult) {
    Write-Host "[SUCCESS] Android Node is ONLINE and VERIFIED!" -ForegroundColor Green
    Write-Host "$probeResult" -ForegroundColor Yellow
} else {
    Write-Host "[WARN] Port forward active; waiting for worker to register with Master." -ForegroundColor Yellow
}

Write-Host "==========================================================" -ForegroundColor Cyan
Write-Host "Android node is ready to offload layers." -ForegroundColor Green
Write-Host "==========================================================" -ForegroundColor Cyan
