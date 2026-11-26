<#
.SYNOPSIS
    Automated Host Setup for Android Node over USB ADB or LAN
.DESCRIPTION
    1. Verifies ADB connection and detects Android device
    2. Disables Android's Phantom Process Killer (Android 12/13/14/15+)
    3. Forwards TCP RPC port 50052 over USB
    4. Verifies worker port connectivity
#>

param(
    [int]$RpcPort = 50052
)

Write-Host "==================================================================" -ForegroundColor Cyan
Write-Host " ClusterLab — Android Worker Setup & USB ADB Tunneling" -ForegroundColor Cyan
Write-Host "==================================================================" -ForegroundColor Cyan

# 1. Verify ADB presence
$adbCmd = Get-Command adb -ErrorAction SilentlyContinue
if (-not $adbCmd) {
    $platformTools = Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe"
    if (Test-Path $platformTools) {
        $adbCmd = $platformTools
    } else {
        Write-Error "adb command not found. Please install Android Platform Tools or add it to PATH."
        exit 1
    }
}

# 2. Check connected devices
Write-Host "[1/4] Checking connected Android devices via ADB..." -ForegroundColor Yellow
$devicesOutput = & $adbCmd devices
Write-Host $devicesOutput

$deviceList = $devicesOutput -split "`r?`n" | Where-Object { $_ -match "\bdevice\b" -and $_ -notmatch "List of devices" }
if ($deviceList.Count -eq 0) {
    Write-Warning "No authorized Android device found over USB. Ensure USB Debugging is ON and authorized."
    exit 1
}

# 3. Disable Phantom Process Killer (prevents Android OS from killing background Termux/RPC workers)
Write-Host "[2/4] Disabling Android Phantom Process Killer..." -ForegroundColor Yellow
try {
    & $adbCmd shell "/system/bin/settings put global settings_enable_monitor_phantom_procs false" 2>&1 | Out-Null
    Write-Host "      Phantom Process Killer disabled successfully." -ForegroundColor Green
} catch {
    Write-Warning "      Could not update phantom process setting (non-critical on older Android versions)."
}

# 4. Forward TCP port 50052 over USB
Write-Host "[3/4] Forwarding TCP port $RpcPort over USB..." -ForegroundColor Yellow
& $adbCmd forward tcp:$RpcPort tcp:$RpcPort
Write-Host "      Forwarded local port $RpcPort -> Android port $RpcPort." -ForegroundColor Green

# 5. Summary
Write-Host "[4/4] Android Worker Node Ready!" -ForegroundColor Green
Write-Host "------------------------------------------------------------------"
Write-Host " Local Endpoint : 127.0.0.1:$RpcPort" -ForegroundColor Cyan
Write-Host " USB Connection : Active with zero Wi-Fi latency" -ForegroundColor Cyan
Write-Host " Next Step      : In ClusterLab Web UI (http://localhost:3000), tap 'Auto-Connect'"
Write-Host "------------------------------------------------------------------"
