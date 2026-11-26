<#
.SYNOPSIS
    Automated Windows Setup & Firewall Configuration for ClusterLab
.DESCRIPTION
    1. Verifies Windows Defender Firewall rules for ports 8080, 50050-50052
    2. Auto-detects adb.exe for USB Android node forwarding
    3. Fetches live tuned PowerShell launch script from ClusterLab server
#>

param(
    [int]$MasterPort = 8080,
    [int]$RpcPort = 50052,
    [string]$ServerUrl = "http://localhost:3000"
)

$ErrorActionPreference = "Stop"

Write-Host "==================================================================" -ForegroundColor Cyan
Write-Host " ClusterLab — Windows 10/11 Master & Worker Setup" -ForegroundColor Cyan
Write-Host "==================================================================" -ForegroundColor Cyan

# 1. Firewall rules
Write-Host "[1/3] Provisioning Windows Defender Firewall Rules..." -ForegroundColor Yellow
$prevEap = $ErrorActionPreference
$ErrorActionPreference = "Continue"
try {
    netsh advfirewall firewall add rule name="ClusterLab-OpenAI" dir=in action=allow protocol=TCP localport="$MasterPort,$RpcPort,3000" 2>&1 | Out-Null
    netsh advfirewall firewall add rule name="ClusterLab-UDP" dir=in action=allow protocol=UDP localport="50050" 2>&1 | Out-Null
    Write-Host "      Firewall rules active for TCP $MasterPort, $RpcPort, 3000 and UDP 50050." -ForegroundColor Green
} catch {
    Write-Warning "      Could not add firewall rules. If connecting across LAN, run PowerShell as Administrator."
} finally {
    $ErrorActionPreference = $prevEap
}

# 2. Check for ADB
Write-Host "[2/3] Checking ADB for Android phone tunneling..." -ForegroundColor Yellow
$adb = Get-Command adb -ErrorAction SilentlyContinue
if ($adb) {
    Write-Host "      adb detected at $($adb.Source)" -ForegroundColor Green
    & $adb forward tcp:$RpcPort tcp:$RpcPort 2>&1 | Out-Null
    Write-Host "      Forwarded USB port $RpcPort -> Android port $RpcPort." -ForegroundColor Green
} else {
    Write-Host "      adb not found. Android nodes can still join over Wi-Fi." -ForegroundColor DarkGray
}

# 3. Fetch Tuned Runner Script from Server if available
Write-Host "[3/3] Checking ClusterLab API server at $ServerUrl..." -ForegroundColor Yellow
try {
    $health = Invoke-RestMethod -Uri "$ServerUrl/health" -TimeoutSec 2
    Write-Host "      Connected to ClusterLab server! (Active Model: $($health.activeModel))" -ForegroundColor Green
    $script = Invoke-RestMethod -Uri "$ServerUrl/api/backends/script?os=windows" -TimeoutSec 3
    Set-Content -Path ".\run-cluster-win.ps1" -Value $script -Encoding UTF8
    Write-Host "      Downloaded live tuned runner script -> .\run-cluster-win.ps1" -ForegroundColor Green
} catch {
    Write-Host "      Server not running yet. Start it with: npm run dev" -ForegroundColor DarkGray
}

Write-Host "=================================================================="
Write-Host " Setup Complete! Run: .\run-cluster-win.ps1 to start the backend." -ForegroundColor Cyan
Write-Host "=================================================================="
