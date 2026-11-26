# Changelog

All notable changes to **ClusterLab** are documented in this file.

## [2.1.0] - 2026-11-22

**Author:** AJ60

### Added & Enhanced (Multi-VM End-to-End Simulation & Network Resilience)
- **Multi-VM End-to-End Verification Suite (`scripts/verify_cluster_environments.py`):**
  - Added a 9-stage automated integration test suite that spawns isolated virtual nodes with distinct loopback IPs simulating real-world network links.
  - **Ethernet 2.5G (Linux Workstation VM):** Evaluated at $2{,}500\text{ Mbps}$, $0.35\text{ ms}$ RTT for heavy layer compute.
  - **USB-C ADB Reverse Tunnel (Android Mobile VM):** Evaluated at $480\text{ Mbps}$, $0.52\text{ ms}$ RTT with strict $\le 50\%$ safe RAM cap and critical thermal shedding at $49.5^\circ\text{C}$ to $0\text{L}$.
  - **Wi-Fi 5 802.11ac (Windows Worker VM):** Evaluated at $866\text{ Mbps}$, $14.8\text{ ms}$ with dynamic jitter/loss auto-pruning ($>40\text{ ms}$ RTT / $>1.5\%$ packet loss).
  - **32-Byte Keyed RPC v9 Handshake (`GGUF_PROBE_V9`):** Live TCP handshake verification rejecting rogue nodes with HTTP 502.
  - **Auto-Boot & Graceful Teardown:** Automatically detects if the Master server is running on `:3000` and boots/shuts down a production instance cleanly.
- **Cross-Platform Test Command Support:**
  - Removed shell-specific environment variable prefixes in `package.json` for seamless cross-platform execution on Windows, Linux, and macOS.
- **Android App Updates (`app/`):**
  - Updated Robolectric SDK compatibility and theme adaptivity for light/dark modes.

---

## [2.0.0] - 2026-11-19

**Author:** AJ60

### Changed & Added (React + TypeScript + Vite Rewrite & 1-Hop Star Simplification)
- **Simplified 1-Hop Star GGUF Cluster Home Dashboard (`StarClusterDashboard`):**
  - Replaced the complex 2D graph landing screen with a user-friendly **1-Hop Star GGUF Cluster** dashboard aligned with `AJ60/clustering`.
  - Added 4 quantized GGUF model presets (`Qwen3-30B-A3B MoE`, `BitNet b1.58 Ternary`, `Qwen2.5-32B Dense`, `Llama-3.3-70B Dense`) and a `This Device` vs `4-Node Cluster Test` segmented switch.
  - Implemented cap-bounded layer allocation (`computeAndroidSafeCap` `<= 50%`, `computeDesktopSafeCap`, `computeVramSafeCap`), proportional node scoring, fit verdicts (`Fast`, `Best Fit`, `Streaming`), interactive SVG Star Topology, and clickable allocation table (`src/domain/starClusterPlanner.ts`).
  - Integrated **Thermal Guard** (`COOL`, `WARN`, `SHED_25`, `EMERGENCY_OFFLOAD`) with anti-flapping hysteresis.
- **3-Card Streamlined Default Layout:** Organized everyday controls into **Connect** (Auto-Connect, 21×21 QR scanner, Start/Stop Sharing), **Health** (calibrated speed, Safe RAM Cap, WebGL/WASM backend, battery/thermal check, Re-Tune Speed), and **Troubleshooter** (6-check Auto-Diagnosis with 1-click auto-fixes).
- **Developer Mode Gate (`2D Lab (Dev)`):** Moved the entire interactive 2D K-Means & DBSCAN Lab (canvas, Voronoi/trails, 6 presets, Session History, Batch Queue, CSV Manager, Vector PDF 1.4 export, and Coach-Mark tour) plus USB/LAN bind switches, thermal/memory stress buttons, and raw event logs behind **Developer Mode** in Settings.
- **Cross-Platform Web Stack:** Built on React 19, TypeScript 5.8 (`strict`, `noUnusedLocals`, `noUnusedParameters`), Vite 6, Tailwind CSS v4, and `Plus Jakarta Sans` + `JetBrains Mono` typography.

---

## [1.0.0] - 2026-11-19 (Prior Native Release)

**Author:** AJ60

### Added
- Original Android Jetpack Compose + Material 3 2D Clustering Lab, Room/DataStore persistence, vector PDF export, `clusterd` Rust orchestrator, `clusterd_ruby` zero-config daemon, and legacy hardware survival safeguards.
