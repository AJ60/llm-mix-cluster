# AGENTS.md — Silica Cluster / ClusterLab

Four subsystems, each with its own toolchain:
- **Canonical engine**: `silica-core/` → `clusterd` (Rust/axum master + worker, port 3000, embedded UI from `../dist/`).
- **Web UI**: React 19/Vite in `src/`, embedded into `clusterd` via `rust-embed`. `src/domain/` holds pure planning/tuning/auth logic shared with tests.
- Node API tests spawn `silica-core/target/debug/clusterd` directly via `src/__tests__/helpers/clusterdHarness.ts`; build with `cargo build --manifest-path silica-core/Cargo.toml` before `npm test` (CI does this).
- **Android worker**: `app/` (Kotlin Compose service + CMake/NDK Vulkan probe libraries).
- **Python E2E**: `tests/` (namespace/QEMU multi-VM cluster verification).

## Commands (source of truth: package.json, ci.yml)
- `npm install`; `npm run dev` → builds client then runs `clusterd` (Rust master, port 3000)
- `npm run lint` → `tsc --noEmit`; `npm test` → `tsx --test` over 8 explicit test files (43 tests, using `clusterdHarness.ts`)
- `npm run build` → client only (`tsc -b && vite build`); Rust embeds `dist/` at compile time
- `npm run silica` → `tsx bin/silica.ts` CLI (`serve|run|status|cache|test`)
- Rust: `npm run build:client && cd silica-core && cargo build --release && cargo test`
- Android: set `ANDROID_HOME`, then `./gradlew :app:externalNativeBuildDebug :app:testDebugUnitTest` (NDK 26.1.10909125, CMake 3.22.1, arm64-v8a only)
- Python E2E needs a live master on `http://127.0.0.1:3000`: `python3 -m unittest discover -s tests -p "test_*.py" -v`; default `CLUSTER_TEST_MODE=local` uses `unshare` (Linux only)
- Native probe C++: `g++ -std=c++17 -Wall -Wextra -Werror app/src/main/cpp/rpc_server_test.cpp -ldl -pthread -o /tmp/rpc_server_test && /tmp/rpc_server_test`

## Gotchas
- `vite.config.ts` sets `hmr: false`, `ws: false`, `strictPort: true` → restart the server after editing `src/`; ignore `ws://127.0.0.1:24678` refused in browser logs.
- `tsconfig.json` includes only `src`; Node tests under `src/__tests__` are typechecked, `bin/silica.ts` is not unless checked explicitly.
- `clusterd` binds `127.0.0.1` by default. Exposing it with `--bind 0.0.0.0` requires `CLUSTER_API_TOKEN`; API routes then require `Authorization: Bearer <token>` (health/beacon remain public).
- `rust-embed` compiles `../dist/` at build time — build the client before `cargo build`.
- `CLUSTER_SECRET` defaults to `clusterd-default-hmac-key` and drives the nonce-bound 32-byte `GGUF_PROBE_V10` handshake (32-byte request: `NONC`+u64 nonce+20-byte MAC; reply carries nonce echo, capability MAC and packed telemetry). Golden vectors live in `tests/fixtures/handshake_v10_vectors.json`. Rust `clusterd` deliberately does not broadcast the secret (HTTP beacon or UDP discovery) and does not enable permissive CORS; keep it that way.
- Server writes `cluster_history.json` to cwd and uses `.silica-kv-cache/`; both gitignored.
- Android `app/build.gradle.kts` signing configs carry "do not remove/modify" AI Studio comments and require a root `debug.keystore` (copy `~/.android/debug.keystore` if missing). `.env` feeds the Secrets plugin; keys with empty values must be in its `ignoreList` or BuildConfig generation fails.
- Android native `rpc_server_exec*.so` now listens and answers the v10 handshake; ggml RPC/Vulkan backends are opt-in with `-DSILICA_WITH_GGML=ON` (FetchContent of llama.cpp v1.0.0). Full RPC opcode dispatch/inference is not wired yet — use `EXTRA_FORCE_EMBEDDED_RPC` for handshake-only debugging.
- ADB/headless start uses the exported `RpcControlActivity` (`am start -n <pkg>/com.example.service.RpcControlActivity`); `RpcService` itself is not exported. The activity passes `EXTRA_FORCE_EMBEDDED_RPC` for debug/handshake-only testing; real inference still needs a native RPC worker.
- UI accepts the LAN bearer token via `?apiToken=<token>` (stored in `sessionStorage`); all UI fetches go through `src/service/apiAuth.ts`. CLI/workers read `CLUSTER_API_TOKEN` from env.
- Two-device verification: `python3 scripts/verify_two_android_nodes.py --install` attaches the first two `adb` devices, assigns host ports 50055/50056 via `--ei extra_rpc_port`, and asserts both workers register with `clusterd`.
- Verified on local hardware (2026-11-26): RTX 3050 host + Samsung SM-G990E / Android 16 (Mali-G78, Vulkan 1.1, ASIMDDP but no i8mm) — device registered over `adb reverse/forward` and appeared in the Rust cluster plan; Vulkan 16-bit path correctly fell back to ARM NEON.
- Rust and TS wire agreements to keep in sync: camelCase register/inspect/reload DTOs, two-stage ggml HELLO + 32-byte token probe, `SpeculativeMode` snake_case, GGUF preset fields from `GgufModelCard`.
- `README.md` is authoritative for features; `DEPLOYMENT_GUIDE.md` and `docs/` contain stale pre-v2 narratives. No root `Dockerfile` exists (only `tests/Dockerfile.*`).
