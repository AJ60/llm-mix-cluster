# ClusterLab — Multi-Platform Node Setup Guide
**Author:** AJ60  
**Supported Platforms:** Windows 10/11, Linux (x86_64 / aarch64), Android (ARM64)

This guide documents the setup, tuning, and network pairing procedures for **Windows**, **Linux**, and **Android** nodes in a 1-Hop Star GGUF Cluster.

---

## 1. Architecture Overview

In ClusterLab, the cluster operates as a **1-Hop Star Topology**:
- **Master Node (Coordinator)**: Runs the ClusterLab web application, exposes the OpenAI API (`:3000` / `:8080`), manages the global KV cache, and schedules layers across all active workers. Typically hosted on your primary Windows or Linux machine.
- **Worker Nodes**: Execute designated model tensor layers (`L0..L23`, `L24..L47`) via `rpc-server` listening on TCP port `50052`. Can be another Windows PC, a Linux server/Raspberry Pi, or an Android phone.

```
                           +---------------------------+
                           |   ClusterLab Master       |
                           |   (Windows / Linux PC)    |
                           |   Web UI: http://:3000    |
                           |   OpenAI API: http://:8080|
                           +-------------+-------------+
                                         |
               +-------------------------+-------------------------+
               | (LAN / 10 GbE)          | (Wi-Fi 6 / 1 GbE)       | (USB ADB / 480 Mbps+)
               v                         v                         v
     +-------------------+     +-------------------+     +-------------------+
     |   Linux Worker    |     |  Windows Worker   |     |  Android Worker   |
     |   Ubuntu / Debian |     |   Win 10/11       |     |  Snapdragon / Dim |
     |   Port 50052      |     |   Port 50052      |     |  Port 50052       |
     +-------------------+     +-------------------+     +-------------------+
```

---

## 2. Windows 10/11 Setup

### Prerequisites
- Windows 10 (21H2+) or Windows 11
- PowerShell 5.1+ or PowerShell 7
- Optional GPU Accelerators: NVIDIA CUDA 12.x, Vulkan 1.3, or DirectML

### Automated Setup
Run the setup script from PowerShell:
```powershell
.\scripts\setup_windows.ps1
```

### What the Windows Setup Configures:
1. **Firewall Rules**: Adds inbound rules to Windows Defender Firewall for ports `8080` (OpenAI), `50052` (RPC Worker), and UDP `50050` (Auto-discovery).
2. **WDDM VRAM Safeguard**: On GPUs with $\le$ 8 GB VRAM, automatically reserves 450 MB VRAM to prevent Desktop Window Manager (DWM) stuttering and TDR driver resets.
3. **Process Priority & Core Contention**:
   - Launches `llama-server.exe` with `PriorityClass = High` to prevent Windows 11 Efficiency Mode from throttling worker threads.
   - Sets `OMP_WAIT_POLICY=PASSIVE` to prevent OpenMP spin-wait loops from starving UI threads.
4. **MoE CPU Offloading**: Automatically applies `-ot ".ffn_.*_exps.=CPU"` for sparse MoE models (e.g. Qwen3-30B-A3B) to keep active attention layers hot in GPU VRAM while streaming expert weights from system DDR4/DDR5 RAM.
5. **Live Script Generator**: You can download your current customized PowerShell launcher directly from the Web UI or via:
   ```powershell
   Invoke-RestMethod -Uri "http://localhost:3000/api/backends/script?os=windows" -OutFile "run-cluster-win.ps1"
   .\run-cluster-win.ps1
   ```

---

## 3. Linux Workstation & Server Setup

### Prerequisites
- Ubuntu 22.04+, Debian 12+, Arch, or RHEL 9+
- GCC / Clang, CMake, Ninja, and OpenBLAS
- Optional GPU Accelerators: NVIDIA CUDA 12.x, AMD ROCm 6.3, or Vulkan 1.3

### Automated Setup
Run the setup script:
```bash
chmod +x ./scripts/setup_linux.sh
./scripts/setup_linux.sh [master|worker] [port]
```

### What the Linux Setup Configures:
1. **Memory Ceiling Safeguard**: Computes `MemAvailable - 1024 MB` to ensure the Linux OOM-killer is never triggered.
2. **Unified Memory Thrashing Prevention**: Sets `export GGML_CUDA_ENABLE_UNIFIED_MEMORY=0` to block silent, performance-degrading PCIe page swapping.
3. **NUMA Interleaving**: Prepends `numactl --interleave=all` for multi-socket or multi-channel CPU architectures (e.g. AMD Threadripper, EPYC, Intel Xeon).
4. **Transparent HugePages**: Configures `echo madvise > /sys/kernel/mm/transparent_hugepage/enabled` for zero-copy GGUF shard memory mapping.
5. **Memory Locking**: Adds `--mlock` flag when RAM headroom is abundant to prevent kernel page-cache eviction.
6. **Live Script Generator**:
   ```bash
   curl -s "http://localhost:3000/api/backends/script?os=linux" > run-cluster-linux.sh
   bash run-cluster-linux.sh
   ```

---

## 4. Android Phone & Tablet Setup (Snapdragon / Dimensity / Tensor)

Android devices can join the cluster via **two complementary methods**:

### Option A: Zero-Install Browser Web Worker (Fastest)
1. Ensure your phone is on the same local Wi-Fi network as the Master PC.
2. Open Chrome or Firefox on Android and navigate to:
   ```
   http://<MASTER_LOCAL_IP>:3000
   ```
3. Tap **Auto-Connect** or scan the 21×21 QR code displayed on the Master screen.
4. The WebGL / WASM worker will calibrate compute speed and allocate a safe RAM slice ($\le 50\%$ available RAM).

---

### Option B: Native High-Performance Termux Worker (CLI Worker)

This method compiles and runs the stock native `rpc-server` directly on Android's ARM64 CPU cores with zero root required.

#### Step 1: Install Termux
Install **Termux** from [F-Droid](https://f-droid.org/en/packages/com.termux/) (do not use Google Play Store version as it is deprecated).

#### Step 2: Run Setup in Termux
Inside Termux, run:
```bash
# Clone the repository or copy the script
pkg install -y git
git clone https://github.com/AJ60/llm-mix-cluster.git
cd llm-mix-cluster
chmod +x ./scripts/setup_termux_android.sh
./scripts/setup_termux_android.sh
```

---

### Option C: Standalone Native Android APK (Android Studio Project in `app/`)

The repository contains a complete, production-ready **Jetpack Compose Android application** located in the [app/](file:///c:/Users/alojy/OneDrive/Desktop/Clustering/app/) folder.

#### Key Features of the Native Android App:
- **Foreground Worker Service (`RpcService.kt`)**: Launches `librpc_server_exec.so` inside a `FOREGROUND_SERVICE_DATA_SYNC` lifecycle, holding a `PARTIAL_WAKE_LOCK` and Wi-Fi multicast lock so Android's OS never pauses execution while the screen is off or in background.
- **Hardware Acceleration Support**: Supports Vulkan 1.3 Compute (`VK_KHR_16bit_storage`), `ARMv8.2-A + dotprod` (`i8mm`), and baseline NEON fallbacks.
- **16 KB ELF Page Alignment**: Fully compatible with Android 15 & Android 16 (API 28..36).
- **Built-in Camera QR Scanner**: Point the phone camera at the PC screen's 21×21 QR code for instantaneous pairing.
- **LMK & Thermal Watchdog**: Auto-sheds layers if device temperature approaches $45^\circ\text{C}$ or if memory exceeds $50\%$ physical RAM.

#### How to Build the APK in Android Studio:
1. Open **Android Studio** (Ladybug or newer).
2. Choose **Open Project** and select this repository root directory (`ClusterLab`).
3. Allow Gradle to sync.
4. Connect your Android device or select an emulator.
5. Click **Run 'app'** (`Shift + F10`) or **Build -> Build Bundle(s) / APK(s) -> Build APK(s)**.
6. The compiled APK will be output to `app/build/outputs/apk/debug/app-debug.apk`.

#### What the Termux Setup Does:
- **16 KB ELF Page Alignment**: Compiles with `-Wl,-z,max-page-size=16384` for full compatibility with Android 15 and Android 16 (API 35/36).
- **ARMv8.2-A + dotprod**: Auto-detects `dotprod` / `asimddp` instructions for accelerated quantized integer math.
- **Big Core Pinning**: Probes `/sys/devices/system/cpu/` to run worker threads exclusively on the high-frequency Performance/Prime cores.
- **Strict RAM Bounding**: Implements strict `SAFE_CAP_MB <= 50%` physical RAM (with a 512 MB safety floor) to prevent Android LowMemoryKiller (LMK) eviction.
- **Thermal Shedding Integration**: The Master monitors node temperatures in real-time:
  - `< 42°C`: Normal operation (100% layer load)
  - `42°C - 45°C`: Warning notification
  - `≥ 45°C`: **SHED_25** (automatically sheds 25% of layers to cooler desktop nodes)
  - `≥ 48°C`: **EMERGENCY_OFFLOAD** (immediately sheds 100% of layers to prevent thermal throttling).

---

## 5. Zero-Latency USB ADB Tunneling for Android

If you connect your Android phone to your PC via a USB-C cable:

1. Enable **USB Debugging** on the Android device:
   - *Settings -> About Phone -> Tap "Build Number" 7 times.*
   - *Settings -> System -> Developer Options -> Turn ON "USB Debugging".*
2. Run the ADB forwarder script on your Windows Master PC:
   ```powershell
   .\scripts\setup_android.ps1
   ```
3. This runs:
   ```bash
   adb shell settings put global settings_enable_monitor_phantom_procs false
   adb forward tcp:50052 tcp:50052
   ```
4. Now, all network traffic to the Android worker travels over the high-speed USB bus at **0.2 ms RTT latency** with zero Wi-Fi packet drops!
