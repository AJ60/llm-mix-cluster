#!/usr/bin/env python3
import argparse
import json
import os
import pathlib
import shutil
import subprocess
import sys
import time
import urllib.error
import urllib.request
from datetime import datetime

ROOT = pathlib.Path(__file__).resolve().parent.parent
MASTER_PORT = 3000
BASE_URL = f"http://127.0.0.1:{MASTER_PORT}"
PACKAGE = "com.aistudio.clusterlab.kxmpzq"
ACTIVITY = f"{PACKAGE}/com.example.service.RpcControlActivity"
PROMPT = "Explain in one short sentence why distributed inference is useful."
CONFIGS = [
    {"name": "g16-q8-4k", "contextLength": 4096, "kvCacheQuant": "q8_0", "gpuOffloadLayers": 16, "threads": 8, "batchSize": 512, "ubatchSize": 256, "enableFlashAttn": True},
    {"name": "g16-q4-4k", "contextLength": 4096, "kvCacheQuant": "q4_0", "gpuOffloadLayers": 16, "threads": 8, "batchSize": 512, "ubatchSize": 256, "enableFlashAttn": True},
    {"name": "g16-f16-4k", "contextLength": 4096, "kvCacheQuant": "f16", "gpuOffloadLayers": 16, "threads": 8, "batchSize": 512, "ubatchSize": 256, "enableFlashAttn": True},
    {"name": "g8-q8-8k", "contextLength": 8192, "kvCacheQuant": "q8_0", "gpuOffloadLayers": 8, "threads": 8, "batchSize": 512, "ubatchSize": 256, "enableFlashAttn": True},
    {"name": "cpu-q8-4k", "contextLength": 4096, "kvCacheQuant": "q8_0", "gpuOffloadLayers": 0, "threads": 8, "batchSize": 512, "ubatchSize": 256, "enableFlashAttn": False},
    {"name": "g16-q8-16k", "contextLength": 16384, "kvCacheQuant": "q8_0", "gpuOffloadLayers": 16, "threads": 8, "batchSize": 512, "ubatchSize": 256, "enableFlashAttn": True},
]


def http_json(path, data=None, method=None, timeout=30):
    body = None
    headers = {"Accept": "application/json"}
    if data is not None:
        body = json.dumps(data).encode("utf-8")
        headers["Content-Type"] = "application/json"
    request = urllib.request.Request(BASE_URL + path, data=body, headers=headers, method=method)
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            raw = response.read().decode("utf-8")
            return response.status, json.loads(raw) if raw else {}
    except urllib.error.HTTPError as exc:
        raw = exc.read().decode("utf-8", errors="replace")
        try:
            return exc.code, json.loads(raw)
        except json.JSONDecodeError:
            return exc.code, {"raw": raw}
    except Exception as exc:
        return 0, {"error": str(exc)}


def healthy():
    status, _ = http_json("/health", timeout=2)
    return status == 200


def wait_master(timeout=20):
    deadline = time.time() + timeout
    while time.time() < deadline:
        if healthy():
            return True
        time.sleep(0.25)
    return False


def start_master():
    binary = ROOT / "silica-core" / "target" / "release" / ("clusterd.exe" if os.name == "nt" else "clusterd")
    if not binary.exists():
        raise RuntimeError(f"clusterd not built: {binary}")
    env = os.environ.copy()
    if not env.get("SILICA_LLAMA_SERVER_BINARY"):
        found = shutil.which("llama-server")
        if found:
            env["SILICA_LLAMA_SERVER_BINARY"] = found
    flags = getattr(subprocess, "CREATE_NEW_PROCESS_GROUP", 0) if os.name == "nt" else 0
    process = subprocess.Popen(
        [str(binary), "--port", str(MASTER_PORT), "--bind", "127.0.0.1"],
        cwd=str(ROOT),
        env=env,
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
        creationflags=flags,
    )
    if not wait_master():
        process.terminate()
        raise RuntimeError("clusterd did not become healthy")
    return process


def adb_devices():
    try:
        output = subprocess.run(["adb", "devices"], capture_output=True, text=True, check=True).stdout
    except Exception:
        return []
    return [
        line.split()[0]
        for line in output.splitlines()[1:]
        if line.strip().endswith("device")
    ]


def adb(serial, *args, check=True):
    result = subprocess.run(["adb", "-s", serial, *args], capture_output=True, text=True)
    if check and result.returncode != 0:
        raise RuntimeError(f"adb {' '.join(args)} failed on {serial}: {result.stderr.strip()}")
    return result.stdout.strip()


def start_workers():
    serials = adb_devices()[:2]
    for index, serial in enumerate(serials):
        port = 50055 + index
        adb(serial, "reverse", f"tcp:{MASTER_PORT}", f"tcp:{MASTER_PORT}")
        adb(serial, "forward", f"tcp:{port}", f"tcp:{port}")
        adb(serial, "shell", "am", "force-stop", PACKAGE)
        adb(
            serial,
            "shell",
            "am",
            "start",
            "-n",
            ACTIVITY,
            "--ei",
            "extra_rpc_port",
            str(port),
            "--ez",
            "extra_force_embedded_rpc",
            "true",
        )
    return serials


def stop_workers(serials):
    for serial in serials:
        adb(serial, "shell", "am", "force-stop", PACKAGE, check=False)


def worker_count():
    status, payload = http_json("/api/cluster/hardware", timeout=5)
    if status != 200:
        return 0, []
    workers = payload.get("verifiedWorkers", [])
    return len(workers), [
        {
            "node_id": worker.get("node_id"),
            "rpc_port": worker.get("rpc_port"),
            "rtt_p95_ms": worker.get("rtt_p95_ms"),
            "bandwidth_mbps": worker.get("bandwidth_mbps"),
            "pkt_loss_pct": worker.get("pkt_loss_pct"),
        }
        for worker in workers
    ]


def wait_workers(expected, timeout=60):
    deadline = time.time() + timeout
    while time.time() < deadline:
        count, workers = worker_count()
        if count >= expected:
            return workers
        time.sleep(1)
    return worker_count()[1]


def discover_models(extra_dirs):
    directories = [ROOT / "models", pathlib.Path.home() / "Downloads" / "Models", pathlib.Path.home() / "Models"]
    directories.extend(pathlib.Path(path) for path in extra_dirs)
    models = []
    seen = set()
    for directory in directories:
        if not directory.exists():
            continue
        for path in directory.rglob("*.gguf"):
            name = path.name.lower()
            if name.startswith("ggml-vocab-") or "mmproj" in name:
                continue
            key = str(path.resolve()).lower()
            if key in seen:
                continue
            seen.add(key)
            models.append(path)
    return sorted(models, key=lambda path: path.stat().st_size, reverse=True)


def wait_upstream(timeout=120):
    deadline = time.time() + timeout
    last_error = ""
    while time.time() < deadline:
        try:
            with urllib.request.urlopen("http://127.0.0.1:8080/health", timeout=2) as response:
                if response.status == 200:
                    return True
        except Exception as exc:
            last_error = str(exc)
        time.sleep(0.5)
    raise RuntimeError(f"llama-server did not become healthy: {last_error}")


def chat_once(prompt, max_tokens=64):
    payload = {
        "model": "local",
        "messages": [{"role": "user", "content": prompt}],
        "max_tokens": max_tokens,
        "temperature": 0,
    }
    request = urllib.request.Request(
        BASE_URL + "/v1/chat/completions",
        data=json.dumps(payload).encode("utf-8"),
        headers={"Content-Type": "application/json", "Accept": "application/json"},
        method="POST",
    )
    start = time.perf_counter()
    with urllib.request.urlopen(request, timeout=180) as response:
        result = json.loads(response.read().decode("utf-8"))
    elapsed = time.perf_counter() - start
    usage = result.get("usage") or {}
    completion_tokens = int(usage.get("completion_tokens", 0))
    prompt_tokens = int(usage.get("prompt_tokens", 0))
    return {
        "elapsed_s": round(elapsed, 3),
        "completion_tokens": completion_tokens,
        "prompt_tokens": prompt_tokens,
        "tokens_per_s": round(completion_tokens / elapsed, 2) if elapsed > 0 else 0.0,
        "text": (result.get("choices") or [{}])[0].get("message", {}).get("content", ""),
    }


def chat_stream(prompt, max_tokens=64):
    payload = {
        "model": "local",
        "messages": [{"role": "user", "content": prompt}],
        "max_tokens": max_tokens,
        "temperature": 0,
        "stream": True,
        "stream_options": {"include_usage": True},
    }
    request = urllib.request.Request(
        BASE_URL + "/v1/chat/completions",
        data=json.dumps(payload).encode("utf-8"),
        headers={"Content-Type": "application/json", "Accept": "text/event-stream"},
        method="POST",
    )
    start = time.perf_counter()
    first_token_s = None
    completion_tokens = 0
    prompt_tokens = 0
    with urllib.request.urlopen(request, timeout=180) as response:
        for raw_line in response:
            line = raw_line.decode("utf-8", errors="replace").strip()
            if not line.startswith("data:"):
                continue
            data = line[5:].strip()
            if data == "[DONE]":
                break
            try:
                chunk = json.loads(data)
            except json.JSONDecodeError:
                continue
            choices = chunk.get("choices") or []
            if choices:
                delta = choices[0].get("delta") or {}
                if first_token_s is None and delta.get("content"):
                    first_token_s = time.perf_counter() - start
            usage = chunk.get("usage") or {}
            completion_tokens = max(completion_tokens, int(usage.get("completion_tokens", 0)))
            prompt_tokens = max(prompt_tokens, int(usage.get("prompt_tokens", 0)))
    total_s = time.perf_counter() - start
    return {
        "ttft_s": round(first_token_s, 3) if first_token_s is not None else None,
        "total_s": round(total_s, 3),
        "completion_tokens": completion_tokens,
        "prompt_tokens": prompt_tokens,
        "tokens_per_s": round(completion_tokens / total_s, 2) if total_s > 0 else 0.0,
    }


def inspect_model(path):
    status, payload = http_json(
        "/api/models/inspect",
        {"filePath": str(path), "contextLength": 4096, "kvCacheQuant": "q8_0"},
        method="POST",
        timeout=60,
    )
    if status != 200:
        return {"error": payload}
    card = payload.get("modelCard") or {}
    return {
        "path": str(path),
        "size_mb": round(path.stat().st_size / (1024 * 1024), 1),
        "name": card.get("name"),
        "architecture": card.get("architecture"),
        "arch_mode": card.get("arch_mode"),
        "quant": card.get("quant_label"),
        "layers": card.get("block_count"),
        "params_b": card.get("estimated_params_b"),
        "active_params_b": card.get("active_params_b"),
        "native_context": card.get("context_length"),
        "mmproj": bool(card.get("mmproj_path")),
    }


def benchmark_config(model_path, config):
    http_json("/api/engine/stop", {}, method="POST", timeout=30)
    payload = {"model": str(model_path), "config": config}
    started = time.perf_counter()
    status, reload_result = http_json("/api/engine/reload", payload, method="POST", timeout=60)
    if status != 200 or not reload_result.get("ok"):
        return {"config": config["name"], "error": reload_result}
    try:
        wait_upstream()
    except Exception as exc:
        return {"config": config["name"], "error": str(exc)}
    load_s = round(time.perf_counter() - started, 2)
    state_status, state = http_json("/api/cluster/state", timeout=10)
    plan = state.get("plan") or {}
    try:
        chat_once(PROMPT, max_tokens=32)
        measured = [chat_once(PROMPT, max_tokens=64) for _ in range(2)]
        streamed = chat_stream(PROMPT, max_tokens=64)
        error = None
    except Exception as exc:
        measured = []
        streamed = {}
        error = str(exc)
    total_completion = sum(item["completion_tokens"] for item in measured)
    total_elapsed = sum(item["elapsed_s"] for item in measured)
    return {
        "config": config["name"],
        "settings": config,
        "load_s": load_s,
        "measured": measured,
        "stream": streamed,
        "tokens_per_s": round(total_completion / total_elapsed, 2) if total_elapsed > 0 else 0.0,
        "error": error,
        "plan": {
            "tier": plan.get("tierCode"),
            "fit": plan.get("fitVerdict"),
            "coverage_pct": plan.get("coveragePct"),
            "effective_context": plan.get("effectiveContext"),
            "kv_cache_total_mb": plan.get("kvCacheTotalMb"),
            "total_safe_pool_mb": plan.get("totalSafePoolMb"),
            "needed_mb": plan.get("neededMb"),
            "estimated_tok_per_s": plan.get("estimatedClusterTokPerSec"),
            "estimated_handoff_ms": plan.get("estimatedHandoffMs"),
            "use_no_mmap": plan.get("useNoMmap"),
            "cli_args": plan.get("cliArgs"),
            "nodes": [
                {
                    "label": node.get("label"),
                    "role": node.get("role"),
                    "allocated_layers": node.get("allocatedLayers"),
                    "layer_start": node.get("layerStart"),
                    "layer_end": node.get("layerEnd"),
                    "share_pct": node.get("sharePct"),
                    "kv_cache_mb": node.get("kvCacheMb"),
                    "rtt_ms": node.get("rttMs"),
                    "bandwidth_mbps": node.get("bandwidthMbps"),
                    "pkt_loss_pct": node.get("pktLossPct"),
                    "placement_score": node.get("placementScore"),
                    "handoff_count": node.get("handoffCount"),
                }
                for node in plan.get("nodes", [])
            ],
        },
    }


def markdown_report(report):
    lines = []
    lines.append("# Local GGUF Cluster Performance Report")
    lines.append("")
    lines.append(f"Generated: {report['generated_at']}")
    lines.append("")
    lines.append("## Environment")
    lines.append("")
    lines.append(f"- Host: {report['environment']['platform']} {report['environment']['machine']}")
    lines.append(f"- CPU: {report['environment']['processor']} ({report['environment']['cores']} logical cores)")
    lines.append(f"- RAM: {report['environment']['ram_total_mb']:.0f} MB total / {report['environment']['ram_available_mb']:.0f} MB available")
    lines.append(f"- GPU: {report['environment']['gpu_name']} ({report['environment']['vram_total_mb']:.0f} MB VRAM)")
    lines.append(f"- llama-server: {report['environment']['llama_server']}")
    lines.append(f"- Registered workers: {len(report['workers'])}")
    for worker in report["workers"]:
        lines.append(f"  - {worker['node_id']} @ :{worker['rpc_port']} — RTT {worker['rtt_p95_ms']:.1f} ms, {worker['bandwidth_mbps']:.0f} Mb/s, loss {worker['pkt_loss_pct']:.1f}%")
    lines.append("")
    lines.append("## Models discovered")
    lines.append("")
    lines.append("| Model | Size (MB) | Arch | Params (B) | Layers | Context | KV quant | mmproj |")
    lines.append("| --- | ---: | --- | ---: | ---: | ---: | --- | --- |")
    for model in report["models"]:
        for result in report["results"]:
            if result["model"]["path"] == model["path"]:
                first_settings = result["configs"][0]["settings"] if result["configs"] else {}
                lines.append(
                    f"| {model['name'] or pathlib.Path(model['path']).name} | {model['size_mb']:.0f} | "
                    f"{model['arch_mode']}/{model['architecture']} | {model['params_b']:.2f} | {model['layers']} | "
                    f"{model['native_context']} | {first_settings.get('kvCacheQuant', '-')} | {'yes' if model['mmproj'] else 'no'} |"
                )
                break
    lines.append("")
    for result in report["results"]:
        model = result["model"]
        lines.append(f"## {model['name'] or pathlib.Path(model['path']).name}")
        lines.append("")
        lines.append(f"Path: `{model['path']}`")
        lines.append("")
        lines.append("| Config | Context | KV | NGL | Load (s) | TTFT (s) | Tok/s (measured) | Est. tok/s (planner) | Tier | Coverage | Handoff (ms) |")
        lines.append("| --- | ---: | --- | ---: | ---: | ---: | ---: | ---: | --- | ---: | ---: |")
        for item in result["configs"]:
            if item.get("error"):
                lines.append(f"| {item['config']} | - | - | - | - | - | ERROR | - | - | - | - |")
                continue
            settings = item["settings"]
            stream = item.get("stream") or {}
            lines.append(
                f"| {item['config']} | {item['plan']['effective_context']} | {settings['kvCacheQuant']} | "
                f"{settings['gpuOffloadLayers']} | {item['load_s']} | {stream.get('ttft_s', '-')} | "
                f"{item['tokens_per_s']} | {item['plan']['estimated_tok_per_s']:.1f} | {item['plan']['tier']} | "
                f"{item['plan']['coverage_pct']:.1f}% | {item['plan']['estimated_handoff_ms']:.1f} |"
            )
        lines.append("")
        for item in result["configs"]:
            lines.append(f"### {item['config']}")
            lines.append("")
            if item.get("error"):
                lines.append(f"FAILED: {item['error']}")
                lines.append("")
                continue
            lines.append(f"- CLI: `{' '.join(item['plan']['cli_args'])}`")
            lines.append(f"- KV cache total: {item['plan']['kv_cache_total_mb']:.1f} MB; safe pool: {item['plan']['total_safe_pool_mb']:.0f} MB; model need: {item['plan']['needed_mb']:.0f} MB")
            lines.append(f"- Measured runs: {json.dumps(item['measured'])}")
            lines.append(f"- Stream: {json.dumps(item['stream'])}")
            lines.append("- Layer placement:")
            for node in item["plan"]["nodes"]:
                lines.append(
                    f"  - {node['label']} ({node['role']}): L{node['layer_start']}–L{node['layer_end']} "
                    f"({node['allocated_layers']}L, {node['share_pct']:.1f}%, KV {node['kv_cache_mb']:.1f} MB, "
                    f"RTT {node['rtt_ms']:.1f} ms, {node['bandwidth_mbps']:.0f} Mb/s, loss {node['pkt_loss_pct']:.1f}%, "
                    f"score {node['placement_score']:.1f}, handoffs {node['handoff_count']})"
                )
            lines.append("")
    lines.append("## Notes")
    lines.append("")
    lines.append("- Android workers in this configuration run the embedded handshake-only responder (`EXTRA_FORCE_EMBEDDED_RPC`); link RTT, bandwidth and loss are measured, but ggml RPC opcode dispatch/inference is not wired yet. Layer placement is computed by `clusterd`, while token generation executes on the host master.")
    lines.append("- Context is not split into independent windows. Stock llama.cpp keeps one KV context; KV cache is resident on the node owning each layer and the planner sizes `-c` from the safe memory pool.")
    lines.append("- Tokens/s is measured from three identical low-temperature prompts per config after one warmup; TTFT comes from a separate streaming run.")
    lines.append("")
    return "\n".join(lines)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--model-dir", action="append", default=[])
    parser.add_argument("--output-dir", default=str(ROOT / "reports"))
    parser.add_argument("--skip-workers", action="store_true")
    args = parser.parse_args()

    output_dir = pathlib.Path(args.output_dir)
    output_dir.mkdir(parents=True, exist_ok=True)

    master_process = None
    serials = []
    try:
        if not wait_master(timeout=2):
            master_process = start_master()
        http_json("/api/engine/stop", {}, method="POST", timeout=30)
        if not args.skip_workers:
            serials = start_workers()
            workers = wait_workers(len(serials))
        else:
            workers = []

        models = discover_models(args.model_dir)
        if not models:
            print("No loadable local GGUF models found", file=sys.stderr)
            return 1
        results = []
        for index, model_path in enumerate(models):
            print(f"[{index + 1}/{len(models)}] {model_path.name}", flush=True)
            info = inspect_model(model_path)
            configs = []
            for config in CONFIGS:
                print(f"  {config['name']}...", flush=True)
                try:
                    configs.append(benchmark_config(model_path, config))
                except Exception as exc:
                    configs.append({"config": config["name"], "error": str(exc)})
            results.append({"model": info, "configs": configs})

        hardware = http_json("/api/cluster/hardware", timeout=10)[1]
        host = hardware.get("host") or {}
        uname = os.uname() if hasattr(os, "uname") else None
        report = {
            "generated_at": datetime.now().astimezone().isoformat(timespec="seconds"),
            "environment": {
                "platform": sys.platform,
                "machine": os.environ.get("COMPUTERNAME") or (uname.nodename if uname else "unknown"),
                "processor": os.environ.get("PROCESSOR_IDENTIFIER") or "unknown",
                "cores": os.cpu_count() or 0,
                "ram_total_mb": host.get("total_ram_mb", 0),
                "ram_available_mb": host.get("available_ram_mb", 0),
                "gpu_name": host.get("gpu_name") or "none",
                "vram_total_mb": host.get("vram_total_mb", 0),
                "llama_server": shutil.which("llama-server") or os.environ.get("SILICA_LLAMA_SERVER_BINARY") or "unknown",
            },
            "workers": workers,
            "models": [result["model"] for result in results],
            "results": results,
        }
        stamp = datetime.now().strftime("%Y%m%d_%H%M%S")
        json_path = output_dir / f"local_model_benchmark_{stamp}.json"
        md_path = output_dir / f"local_model_benchmark_{stamp}.md"
        json_path.write_text(json.dumps(report, indent=2), encoding="utf-8")
        md_path.write_text(markdown_report(report), encoding="utf-8")
        print(f"JSON: {json_path}")
        print(f"Markdown: {md_path}")
        return 0
    finally:
        http_json("/api/engine/stop", {}, method="POST", timeout=10)
        stop_workers(serials)
        if master_process is not None:
            master_process.terminate()
            try:
                master_process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                master_process.kill()


if __name__ == "__main__":
    sys.exit(main())
