# AGENTS.md — Silica Cluster / ClusterLab

Five work areas, each with its own verification gate:
- **Canonical engine**: `silica-core/` → `clusterd` (Rust/axum master + worker, port 3000, embedded UI from `../dist/`).
- **Web UI**: React 19/Vite in `src/`, embedded into `clusterd` via `rust-embed`. UI consumes server `plan`/`tuningAdvice`; `src/domain/starClusterPlanner.ts` and `tuningAdvisor.ts` are legacy test/CLI code, not the runtime planner.
- Node API tests spawn `clusterd` via `src/__tests__/helpers/clusterdHarness.ts`; it prefers `silica-core/target/release/clusterd`, then debug. Rebuild the binary you changed before `npm test` — a stale release binary silently invalidates API tests (CI builds debug with `cargo build --manifest-path silica-core/Cargo.toml`).
- **Android worker**: `app/` (Kotlin Compose service + CMake/NDK Vulkan probe libraries).
- **Python E2E**: `tests/` (namespace/QEMU multi-VM cluster verification).

## Commands (source of truth: package.json, ci.yml)
- `npm install`; `npm run dev` → builds client then runs `clusterd` (Rust master, port 3000)
- `npm run lint` → `tsc --noEmit`; `npm test` → `tsx --test` over 8 explicit test files (43 tests). Focused run: `npx tsx --test src/__tests__/clusterdApi.test.ts`.
- `npm run build` → client only (`tsc -b && vite build`); Rust embeds `dist/` at compile time
- `npm run silica` → `tsx bin/silica.ts` CLI (`serve|run|status|cache|test`)
- Rust: `npm run build:client && cd silica-core && cargo build --release && cargo test`; focused: `cargo test <filter>`. `rust-embed` requires `dist/` even for `cargo test`.
- Android: set `ANDROID_HOME`, then `./gradlew :app:compileDebugKotlin :app:externalNativeBuildDebug :app:testDebugUnitTest` (`gradlew.bat` on Windows; NDK 26.1.10909125, CMake 3.22.1, arm64-v8a only). APK: `:app:assembleDebug`.
- Python E2E needs a live master on `http://127.0.0.1:3000`: `python3 -m unittest discover -s tests -p "test_*.py" -v`; default `CLUSTER_TEST_MODE=local` uses `unshare` (Linux only)
- Native probe C++: `g++ -std=c++17 -Wall -Wextra -Werror app/src/main/cpp/rpc_server_test.cpp -ldl -pthread -o /tmp/rpc_server_test && /tmp/rpc_server_test`

## Gotchas
- `vite.config.ts` sets `hmr: false`, `ws: false`, `strictPort: true` → restart the server after editing `src/`; ignore `ws://127.0.0.1:24678` refused in browser logs.
- `tsconfig.json` includes only `src`; Node tests under `src/__tests__` are typechecked, `bin/silica.ts` is not unless checked explicitly.
- `clusterd` binds `127.0.0.1` by default. Exposing it with `--bind 0.0.0.0` requires `CLUSTER_API_TOKEN`; API routes then require `Authorization: Bearer <token>` (health/beacon remain public).
- `rust-embed` compiles `../dist/` at build time — build the client before `cargo build`.
- `CLUSTER_SECRET` defaults to `clusterd-default-hmac-key` and drives the nonce-bound 32-byte `GGUF_PROBE_V10` handshake (32-byte request: `NONC`+u64 nonce+20-byte MAC; reply carries nonce echo, capability MAC and packed telemetry). Golden vectors live in `tests/fixtures/handshake_v10_vectors.json`. Rust `clusterd` deliberately does not broadcast the secret (HTTP beacon or UDP discovery) and does not enable permissive CORS; keep it that way.
- Link quality: `probe_worker_link_quality` takes 3 v10 handshake samples (RTT p95 + loss) and a separate optional `BWPR`/`BWOK` 256 KB echo for throughput. The 32-byte golden frames are untouched; unsupported workers fall back to transport-derived bandwidth estimates.
- Context: stock llama.cpp cannot shard context. KV cache is per-layer and charged to the node owning that layer; Rust picks `effectiveContext` from the fast-tier memory pool and `-c` uses it. Do not describe this as a split context window.
- Server writes `cluster_history.json` to cwd and uses `.silica-kv-cache/`; both gitignored.
- Android `app/build.gradle.kts` signing configs carry "do not remove/modify" AI Studio comments and require a root `debug.keystore` (copy `~/.android/debug.keystore` if missing). `.env` feeds the Secrets plugin; keys with empty values must be in its `ignoreList` or BuildConfig generation fails.
- Android native `rpc_server_exec*.so` now listens and answers the v10 handshake; ggml RPC/Vulkan backends are opt-in with `-DSILICA_WITH_GGML=ON` (FetchContent of llama.cpp v1.0.0). Full RPC opcode dispatch/inference is not wired yet — use `EXTRA_FORCE_EMBEDDED_RPC` for handshake-only debugging.
- ADB/headless start uses the exported `RpcControlActivity` (`am start -n <pkg>/com.example.service.RpcControlActivity`); `RpcService` itself is not exported. The activity passes `EXTRA_FORCE_EMBEDDED_RPC` for debug/handshake-only testing; real inference still needs a native RPC worker.
- UI accepts the LAN bearer token via `?apiToken=<token>` (stored in `sessionStorage`); all UI fetches go through `src/service/apiAuth.ts`. CLI/workers read `CLUSTER_API_TOKEN` from env.
- Two-device verification: `python3 scripts/verify_two_android_nodes.py --install` attaches the first two `adb` devices, assigns host ports 50055/50056 via `--ei extra_rpc_port`, waits for TCP readiness, and asserts both workers register. Verified serials: `RZCT810AMVV`→50055, `ab52c291`→50056; a transient `probe retry` is benign.
- Verified on local hardware (2026-11-27): RTX 3050 host + Samsung SM-G990E / Android 16 (Mali-G78, Vulkan 1.1, ASIMDDP but no i8mm) and Redmi Note 7 Pro / API 29 (Adreno 612, Vulkan 1.1). Both register over `adb reverse/forward`; Vulkan 16-bit path correctly falls back to ARM NEON.
- Wire agreements to keep in sync: camelCase register/inspect/reload DTOs; v10 bytes and golden vectors; `SpeculativeMode` snake_case; GGUF preset fields from `GgufModelCard`. New register fields: `storageTotalMb`, `storageFreeMb`, `cpuUsagePct`, `gpuUsagePct`, `computeTflops`, `linkBandwidthMbps`, `pktLossPct`. `/api/cluster/state` carries `plan` (`effectiveContext`, `kvCacheTotalMb`, per-node `placementScore`, `handoffCount`, `estimatedHandoffMs`) and `tuningAdvice`; register reply carries `recommendedConfig`.
- Trust `package.json`, `ci.yml`, `protocol.rs` over prose: `README.md` and `tests/README.md` still contain stale v9/Express/HMR paragraphs; `DEPLOYMENT_GUIDE.md` and `docs/` are stale pre-v2. No root `Dockerfile` exists (only `tests/Dockerfile.*`).
