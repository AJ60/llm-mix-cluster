# Silica Cluster — Step-by-Step Deployment Guide

This guide covers deploying the **Silica Cluster Hybrid Trinity** (Master Coordinator, Android ARM64 RPC Worker + Standalone Offline Mode, Wi-Fi Direct P2P Mesh, and Zero-Install WebGPU Browser Workers).

---

## 1. Prerequisites

| Component | Requirement |
| :--- | :--- |
| **Master Host (Windows / Linux / macOS)** | Node.js 20+, `llama-server` (built with `-DGGML_RPC=ON`), optional Rust 1.80+ for `silica-core` |
| **Android Worker** | Android 10+ (ARM64-v8a), Android NDK r27+ (for 16KB page-aligned native binaries), ADB |
| **Browser Worker (`/join`)** | Chrome 113+, Edge 113+, Safari 18+, or any modern browser (automatic Float32 fallback if WebGPU is absent) |

---

## 2. Building & Launching the Master Coordinator

1. **Install dependencies & build the application**:
   ```bash
   npm install
   npm run build
   ```
2. **Place local `.gguf` models** in `./models/` (or scan `~/Downloads`, `~/.cache/lm-studio/models`, `~/.ollama/models` from the **Models** tab). Companion `*mmproj*.gguf` vision projectors in the same folder are automatically paired and pinned to Master.
3. **Ensure `llama-server` (with RPC support) is on `PATH` or configured in Load Config**:
   ```bash
   cmake -B build -DGGML_RPC=ON -DGGML_VULKAN=ON   # or -DGGML_CUDA=ON
   cmake --build build --target llama-server rpc-server -j
   ```
4. **Start the coordinator on port `3000`**:
   ```bash
   npm run dev        # development (tsx server.ts)
   # or production:
   npm run start      # node dist/server.js
   ```
5. **Run the built-in 6-Check Diagnostic Sentinel**:
   - Open `http://localhost:3000` -> **Connect** tab -> **Run 6-Check Sentinel**, or query `GET /api/diagnostics/sentinel`.

---

## 3. Building & Deploying the Android Worker (16KB Page-Aligned)

1. **Cross-compile `rpc-server` for `arm64-v8a` with 16KB ELF alignment (Android 15+ compliant)**:
   ```bash
   cmake -B build-android \
     -DCMAKE_TOOLCHAIN_FILE=$ANDROID_NDK_HOME/build/cmake/android.toolchain.cmake \
     -DANDROID_ABI=arm64-v8a \
     -DANDROID_PLATFORM=android-29 \
     -DGGML_RPC=ON \
     -DCMAKE_SHARED_LINKER_FLAGS="-Wl,-z,max-page-size=16384" \
     -DCMAKE_EXE_LINKER_FLAGS="-Wl,-z,max-page-size=16384"
   cmake --build build-android --target rpc-server -j
   ```
2. **Bundle inside `jniLibs/arm64-v8a/librpc_server_exec.so`** so Android unpacks it with execute permissions inside `nativeLibraryDir`, then build the APK:
   ```bash
   ./gradlew assembleRelease
   adb install -r app/build/outputs/apk/release/app-release.apk
   ```
3. **Pair over USB-C ADB Tunnel (lowest latency — Priority Tier 1)**:
   ```bash
   adb reverse tcp:3000 tcp:3000
   adb forward tcp:50052 tcp:50052
   ```
   Or run the bundled setup scripts (`./setup_android_node.sh` on Linux/macOS or `.\setup_android_node.ps1` on Windows).
4. **Zero-OOM Memory Guard**:
   - `NodeAutoCalibrator.kt` and `RpcService.kt` automatically enforce:
     $$\text{SafeCap} \le \min(0.50 \times \text{TotalRAM},\ \text{AvailableRAM} - 512\text{ MB})$$
     (or $40\%$ with a $256\text{ MB}$ reserve on $\le 2\text{ GB}$ devices), preventing Android LMKD kills.

---

## 4. Pairing via 5GHz Wi-Fi Direct P2P (Router-Free Offline Mode)

1. Open the **Connect** tab on Master to view the **21×21 CRC-8 Pairing Matrix** (`SILICA_P2P_V1` payload).
2. On the Android companion app, enable **Wi-Fi Direct Mesh** (`WifiDirectMeshManager.kt`), which starts an autonomous Group Owner on link-local `192.168.49.1:50052`.
3. Scan or verify the payload — CRC-8 integrity and 32-byte HMAC-SHA256 authentication are checked before accepting the link.

---

## 5. Scaling with Zero-Install WebGPU Browser Workers (`/join`)

1. From any laptop, tablet, or secondary desktop on the same network, open:
   ```
   http://<MASTER_LAN_IP>:3000/join
   ```
2. Click **Run Integrity Benchmark** to verify WGSL `GEMV`, `RMSNorm`, and `RoPE` numerical parity ($\text{max error} \le 10^{-5}$).
3. Adjust the **Safe Browser Shard Budget** slider (`0.5 GB – 8.0 GB`) and click **Join Star Cluster**. The browser automatically registers and sends heartbeats to the Master pool.

---

## 6. Standalone Private Mobile Mode (Offline 1B–3B GGUF)

- On Android, switch to **Standalone Mode** (`StandaloneInferenceEngine.kt`) to run local 1B–3B GGUF models directly in app-private storage with zero network sockets, LMKD RAM protection, and local SQLite chat history.

---

## 7. Running Full Verification Suites

```bash
npm run lint
npm run build
npm test
python3 tests/test_cluster_node.py -v
```
