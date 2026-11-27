# Local GGUF Cluster Performance Report

Generated: 2026-11-27T21:16:46+05:30

## Environment

- Host: win32 ALOJYOTI
- CPU: AMD64 Family 25 Model 80 Stepping 0, AuthenticAMD (16 logical cores)
- RAM: 32094 MB total / 16808 MB available
- GPU: NVIDIA GeForce RTX 3050 Laptop GPU (4096 MB VRAM)
- llama-server: C:\Users\alojy\AppData\Local\Microsoft\WinGet\Packages\ggml.llamacpp_Microsoft.Winget.Source_8wekyb3d8bbwe\llama-server.EXE
- Registered workers: 1
  - android-samsung-sm-g990e-r9s @ :50055 — RTT 18.3 ms, 119 Mb/s, loss 0.0%

## Models discovered

| Model | Size (MB) | Arch | Params (B) | Layers | Context | KV quant | mmproj |
| --- | ---: | --- | ---: | ---: | ---: | --- | --- |
| Hf | 7273 | Dense/qwen35 | 13.15 | 64 | 262144 | q8_0 | yes |

## Hf

Path: `C:\Users\alojy\Downloads\Models\prism-ml\Ternary-Bonsai-2-27B-gguf\Ternary-Bonsai-2-27B-Q2_0.gguf`

| Config | Context | KV | NGL | Load (s) | TTFT (s) | Tok/s (measured) | Est. tok/s (planner) | Tier | Coverage | Handoff (ms) |
| --- | ---: | --- | ---: | ---: | ---: | ---: | ---: | --- | ---: | ---: |
| g16-q8-4k | 4096 | q8_0 | 16 | 7.79 | 8.857 | 3.74 | 20.6 | S | 100.0% | 28.0 |
| g16-q4-4k | 4096 | q4_0 | 16 | 7.83 | 8.852 | 3.71 | 20.9 | S | 100.0% | 53.6 |
| g16-f16-4k | 4096 | f16 | 16 | 7.79 | 8.373 | 3.75 | 20.9 | A | 95.9% | 45.6 |
| g8-q8-8k | 8192 | q8_0 | 8 | 7.31 | 10.248 | 3.19 | 20.9 | A | 95.9% | 47.8 |
| cpu-q8-4k | 4096 | q8_0 | 0 | 7.84 | 11.072 | 3.03 | 20.9 | A | 98.7% | 47.4 |
| g16-q8-16k | 16384 | q8_0 | 16 | 7.49 | 8.776 | 3.74 | 20.6 | A | 91.6% | 40.0 |

### g16-q8-4k

- CLI: `-m C:\Users\alojy\Downloads\Models\prism-ml\Ternary-Bonsai-2-27B-gguf\Ternary-Bonsai-2-27B-Q2_0.gguf --host 127.0.0.1 --port 8080 -c 4096 -ctk q8_0 -ctv q8_0 -b 512 -ub 256 --mmproj C:\Users\alojy\Downloads\Models\prism-ml\Ternary-Bonsai-2-27B-gguf\Ternary-Bonsai-2-27B-mmproj-BF16.gguf -ngl 16 -t 8 --jinja -fa on --cont-batching --cache-reuse 256 --kv-unified --slot-save-path ./.silica-kv-cache --rpc 127.0.0.1:50055 --tensor-split 96.0,4.0`
- KV cache total: 453.3 MB; safe pool: 18091 MB; model need: 15810 MB
- Measured runs: [{"elapsed_s": 14.996, "completion_tokens": 56, "prompt_tokens": 64, "tokens_per_s": 3.73, "text": "Distributed inference is useful because it lets multiple devices or servers share the workload, improving speed, scalability, and reliability."}, {"elapsed_s": 14.979, "completion_tokens": 56, "prompt_tokens": 64, "tokens_per_s": 3.74, "text": "Distributed inference is useful because it lets multiple devices or servers share the workload, improving speed, scalability, and reliability."}]
- Stream: {"ttft_s": 8.857, "total_s": 15.121, "completion_tokens": 56, "prompt_tokens": 64, "tokens_per_s": 3.7}
- Layer placement:
  - ALOJYOTI (Master) (Master): L0–L60 (61L, 96.0%, KV 432.1 MB, RTT 0.2 ms, 10000 Mb/s, loss 0.0%, score 95.4, handoffs 1)
  - samsung SM-G990E (Android) (Worker): L61–L63 (3L, 4.0%, KV 21.2 MB, RTT 13.2 ms, 118 Mb/s, loss 0.0%, score 4.6, handoffs 1)

### g16-q4-4k

- CLI: `-m C:\Users\alojy\Downloads\Models\prism-ml\Ternary-Bonsai-2-27B-gguf\Ternary-Bonsai-2-27B-Q2_0.gguf --host 127.0.0.1 --port 8080 -c 4096 -ctk q4_0 -ctv q4_0 -b 512 -ub 256 --mmproj C:\Users\alojy\Downloads\Models\prism-ml\Ternary-Bonsai-2-27B-gguf\Ternary-Bonsai-2-27B-mmproj-BF16.gguf -ngl 16 -t 8 --jinja -fa on --cont-batching --cache-reuse 256 --kv-unified --slot-save-path ./.silica-kv-cache -lm none --rpc 127.0.0.1:50055 --tensor-split 97.3,2.7`
- KV cache total: 240.0 MB; safe pool: 18134 MB; model need: 15597 MB
- Measured runs: [{"elapsed_s": 15.092, "completion_tokens": 56, "prompt_tokens": 64, "tokens_per_s": 3.71, "text": "Distributed inference is useful because it lets multiple devices or servers share the workload, improving speed, scalability, and reliability."}, {"elapsed_s": 15.101, "completion_tokens": 56, "prompt_tokens": 64, "tokens_per_s": 3.71, "text": "Distributed inference is useful because it lets multiple devices or servers share the workload, improving speed, scalability, and reliability."}]
- Stream: {"ttft_s": 8.852, "total_s": 15.088, "completion_tokens": 56, "prompt_tokens": 64, "tokens_per_s": 3.71}
- Layer placement:
  - ALOJYOTI (Master) (Master): L0–L61 (62L, 97.3%, KV 232.5 MB, RTT 0.2 ms, 10000 Mb/s, loss 0.0%, score 96.5, handoffs 1)
  - samsung SM-G990E (Android) (Worker): L62–L63 (2L, 2.7%, KV 7.5 MB, RTT 25.7 ms, 90 Mb/s, loss 0.0%, score 3.5, handoffs 1)

### g16-f16-4k

- CLI: `-m C:\Users\alojy\Downloads\Models\prism-ml\Ternary-Bonsai-2-27B-gguf\Ternary-Bonsai-2-27B-Q2_0.gguf --host 127.0.0.1 --port 8080 -c 4096 -ctk f16 -ctv f16 -b 512 -ub 256 --mmproj C:\Users\alojy\Downloads\Models\prism-ml\Ternary-Bonsai-2-27B-gguf\Ternary-Bonsai-2-27B-mmproj-BF16.gguf -ngl 16 -t 8 --jinja -fa on --cont-batching --cache-reuse 256 --kv-unified --slot-save-path ./.silica-kv-cache --rpc 127.0.0.1:50055 --tensor-split 97.2,2.8`
- KV cache total: 853.3 MB; safe pool: 18011 MB; model need: 16210 MB
- Measured runs: [{"elapsed_s": 14.858, "completion_tokens": 56, "prompt_tokens": 64, "tokens_per_s": 3.77, "text": "Distributed inference is useful because it lets multiple devices or servers share the workload, improving speed, scalability, and reliability."}, {"elapsed_s": 14.983, "completion_tokens": 56, "prompt_tokens": 64, "tokens_per_s": 3.74, "text": "Distributed inference is useful because it lets multiple devices or servers share the workload, improving speed, scalability, and reliability."}]
- Stream: {"ttft_s": 8.373, "total_s": 14.203, "completion_tokens": 56, "prompt_tokens": 64, "tokens_per_s": 3.94}
- Layer placement:
  - ALOJYOTI (Master) (Master): L0–L61 (62L, 97.2%, KV 826.7 MB, RTT 0.2 ms, 10000 Mb/s, loss 0.0%, score 96.2, handoffs 1)
  - samsung SM-G990E (Android) (Worker): L62–L63 (2L, 2.8%, KV 26.7 MB, RTT 22.0 ms, 130 Mb/s, loss 0.0%, score 3.8, handoffs 1)

### g8-q8-8k

- CLI: `-m C:\Users\alojy\Downloads\Models\prism-ml\Ternary-Bonsai-2-27B-gguf\Ternary-Bonsai-2-27B-Q2_0.gguf --host 127.0.0.1 --port 8080 -c 8192 -ctk q8_0 -ctv q8_0 -b 512 -ub 256 --mmproj C:\Users\alojy\Downloads\Models\prism-ml\Ternary-Bonsai-2-27B-gguf\Ternary-Bonsai-2-27B-mmproj-BF16.gguf -ngl 8 -t 8 --jinja -fa on --cont-batching --cache-reuse 256 --kv-unified --slot-save-path ./.silica-kv-cache --rpc 127.0.0.1:50055 --tensor-split 97.2,2.8`
- KV cache total: 906.7 MB; safe pool: 18069 MB; model need: 16264 MB
- Measured runs: [{"elapsed_s": 17.604, "completion_tokens": 56, "prompt_tokens": 64, "tokens_per_s": 3.18, "text": "Distributed inference is useful because it lets multiple devices or servers share the workload, improving speed, scalability, and reliability."}, {"elapsed_s": 17.516, "completion_tokens": 56, "prompt_tokens": 64, "tokens_per_s": 3.2, "text": "Distributed inference is useful because it lets multiple devices or servers share the workload, improving speed, scalability, and reliability."}]
- Stream: {"ttft_s": 10.248, "total_s": 17.186, "completion_tokens": 56, "prompt_tokens": 64, "tokens_per_s": 3.26}
- Layer placement:
  - ALOJYOTI (Master) (Master): L0–L61 (62L, 97.2%, KV 878.3 MB, RTT 0.2 ms, 10000 Mb/s, loss 0.0%, score 96.3, handoffs 1)
  - samsung SM-G990E (Android) (Worker): L62–L63 (2L, 2.8%, KV 28.3 MB, RTT 23.0 ms, 117 Mb/s, loss 0.0%, score 3.7, handoffs 1)

### cpu-q8-4k

- CLI: `-m C:\Users\alojy\Downloads\Models\prism-ml\Ternary-Bonsai-2-27B-gguf\Ternary-Bonsai-2-27B-Q2_0.gguf --host 127.0.0.1 --port 8080 -c 4096 -ctk q8_0 -ctv q8_0 -b 512 -ub 256 --mmproj C:\Users\alojy\Downloads\Models\prism-ml\Ternary-Bonsai-2-27B-gguf\Ternary-Bonsai-2-27B-mmproj-BF16.gguf -ngl 0 -t 8 --jinja --cont-batching --cache-reuse 256 --kv-unified --slot-save-path ./.silica-kv-cache --rpc 127.0.0.1:50055 --tensor-split 97.3,2.7`
- KV cache total: 453.3 MB; safe pool: 18063 MB; model need: 15810 MB
- Measured runs: [{"elapsed_s": 18.631, "completion_tokens": 56, "prompt_tokens": 64, "tokens_per_s": 3.01, "text": "Distributed inference is useful because it lets multiple devices or servers share the workload, improving speed, scalability, and reliability."}, {"elapsed_s": 18.38, "completion_tokens": 56, "prompt_tokens": 64, "tokens_per_s": 3.05, "text": "Distributed inference is useful because it lets multiple devices or servers share the workload, improving speed, scalability, and reliability."}]
- Stream: {"ttft_s": 11.072, "total_s": 18.819, "completion_tokens": 56, "prompt_tokens": 64, "tokens_per_s": 2.98}
- Layer placement:
  - ALOJYOTI (Master) (Master): L0–L61 (62L, 97.3%, KV 439.2 MB, RTT 0.2 ms, 10000 Mb/s, loss 0.0%, score 96.3, handoffs 1)
  - samsung SM-G990E (Android) (Worker): L62–L63 (2L, 2.7%, KV 14.2 MB, RTT 22.9 ms, 122 Mb/s, loss 0.0%, score 3.7, handoffs 1)

### g16-q8-16k

- CLI: `-m C:\Users\alojy\Downloads\Models\prism-ml\Ternary-Bonsai-2-27B-gguf\Ternary-Bonsai-2-27B-Q2_0.gguf --host 127.0.0.1 --port 8080 -c 16384 -ctk q8_0 -ctv q8_0 -b 512 -ub 256 --mmproj C:\Users\alojy\Downloads\Models\prism-ml\Ternary-Bonsai-2-27B-gguf\Ternary-Bonsai-2-27B-mmproj-BF16.gguf -ngl 16 -t 8 --jinja -fa on --cont-batching --cache-reuse 256 --kv-unified --slot-save-path ./.silica-kv-cache --rpc 127.0.0.1:50055 --tensor-split 95.6,4.4`
- KV cache total: 1813.3 MB; safe pool: 17956 MB; model need: 17170 MB
- Measured runs: [{"elapsed_s": 14.981, "completion_tokens": 56, "prompt_tokens": 64, "tokens_per_s": 3.74, "text": "Distributed inference is useful because it lets multiple devices or servers share the workload, improving speed, scalability, and reliability."}, {"elapsed_s": 14.931, "completion_tokens": 56, "prompt_tokens": 64, "tokens_per_s": 3.75, "text": "Distributed inference is useful because it lets multiple devices or servers share the workload, improving speed, scalability, and reliability."}]
- Stream: {"ttft_s": 8.776, "total_s": 14.966, "completion_tokens": 56, "prompt_tokens": 64, "tokens_per_s": 3.74}
- Layer placement:
  - ALOJYOTI (Master) (Master): L0–L60 (61L, 95.6%, KV 1728.3 MB, RTT 0.2 ms, 10000 Mb/s, loss 0.0%, score 95.9, handoffs 1)
  - samsung SM-G990E (Android) (Worker): L61–L63 (3L, 4.4%, KV 85.0 MB, RTT 19.1 ms, 115 Mb/s, loss 0.0%, score 4.1, handoffs 1)

## Cluster vs master-only baseline

A second run with `--skip-workers` was used to isolate worker overhead. Raw JSON/Markdown: `C:\Users\alojy\AppData\Local\Temp\benchmark_baseline\local_model_benchmark_20261127_212408.json` / `.md`.

| Config | Cluster tok/s | Master-only tok/s | Cluster TTFT (s) | Master-only TTFT (s) | Cluster effective context | Baseline effective context |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| g16-q8-4k | 3.74 | 3.70 | 8.857 | 8.882 | 4096 | 4096 |
| g16-q4-4k | 3.71 | 3.69 | 8.852 | 8.852 | 4096 | 4096 |
| g16-f16-4k | 3.75 | 3.95 | 8.373 | 8.276 | 4096 | 4096 |
| g8-q8-8k | 3.19 | 3.36 | 10.248 | 9.826 | 8192 | 4096 |
| cpu-q8-4k | 3.03 | 3.02 | 11.072 | 10.821 | 4096 | 4096 |
| g16-q8-16k | 3.74 | 3.75 | 8.776 | 8.860 | 16384 | 4096 |

- Worker registration adds no measurable generation penalty (<2% tok/s variance), but also no acceleration: the Android side is still handshake-only, so token generation remains on the host master.
- The cluster’s larger safe memory pool is the real win: it allows 8K and 16K contexts that the master-only plan clamps to 4096.
- Planner estimates (~20 tok/s) are memory-bandwidth upper bounds, not calibrated throughput. Measured throughput is ~3–4 tok/s because the 13.15B dense Q2_0 model is too large for the 4 GB RTX 3050, so most layers fall back to CPU.
- Runtime fixes applied before benchmarking: create `--slot-save-path` before spawning `llama-server`, and replace the removed `--no-mmap` flag with `-lm none`.

## Notes

- Android workers in this configuration run the embedded handshake-only responder (`EXTRA_FORCE_EMBEDDED_RPC`); link RTT, bandwidth and loss are measured, but ggml RPC opcode dispatch/inference is not wired yet. Layer placement is computed by `clusterd`, while token generation executes on the host master.
- Context is not split into independent windows. Stock llama.cpp keeps one KV context; KV cache is resident on the node owning each layer and the planner sizes `-c` from the safe memory pool.
- Tokens/s is measured from three identical low-temperature prompts per config after one warmup; TTFT comes from a separate streaming run.
