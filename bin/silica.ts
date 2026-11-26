#!/usr/bin/env node
import { spawn } from 'child_process';
import path from 'path';
import fs from 'fs';
import os from 'os';
import { fileURLToPath } from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));

function authHeaders(): Record<string, string> {
  const token = process.env.CLUSTER_API_TOKEN?.trim();
  return token ? { Authorization: `Bearer ${token}` } : {};
}
import {
  checkLayerRangeCached,
  clearTensorCache,
  getTensorCacheStats,
  hasLayerCached,
} from '../src/domain/tensorCache';

const USAGE = `
========================================================================
                     SILICA CLUSTER CLI (CAKE-STYLE)
========================================================================

Usage: silica <command> [options]

Commands:
  serve [model-path]   Start Silica Master gateway & web visualizer
                       Flags: --port <port> (default: 3000)
                              --cluster-key <secret>
                              --model <path-or-preset>

  run                  Start lightweight worker node daemon
                       Flags: --master <ip:port> (default: http://127.0.0.1:3000)
                              --port <port> (default: 50055)
                              --cluster-key <secret>
                              --name <worker-name>

  status               Inspect live cluster topology, memory & layer sharding
                       Flags: --url <master-url> (default: http://127.0.0.1:3000)

  cache <subcommand>   Inspect or manage local flash tensor cache
                       Subcommands: stats, verify <model> <start> <end>, clear

  test                 Run end-to-end cluster verification test suite

Examples:
  silica serve
  silica run --master 192.168.1.11:3000 --name "MacBook-Worker"
  silica status
  silica cache stats
========================================================================
`;

function parseArgs(args: string[]): Record<string, string | boolean> {
  const flags: Record<string, string | boolean> = {};
  for (let i = 0; i < args.length; i++) {
    const a = args[i];
    if (a.startsWith('--')) {
      const key = a.slice(2);
      const next = args[i + 1];
      if (next && !next.startsWith('--')) {
        flags[key] = next;
        i++;
      } else {
        flags[key] = true;
      }
    } else if (a.startsWith('-')) {
      const key = a.slice(1);
      const next = args[i + 1];
      if (next && !next.startsWith('-')) {
        flags[key] = next;
        i++;
      } else {
        flags[key] = true;
      }
    }
  }
  return flags;
}

async function handleServe(args: string[]) {
  const flags = parseArgs(args);
  const port = String(flags.port || '3000');
  const secret = String(flags['cluster-key'] || 'clusterd-default-hmac-key');
  const binaryCandidates = [
    path.resolve(__dirname, '..', 'silica-core', 'target', 'release', process.platform === 'win32' ? 'clusterd.exe' : 'clusterd'),
    path.resolve(__dirname, '..', 'silica-core', 'target', 'debug', process.platform === 'win32' ? 'clusterd.exe' : 'clusterd'),
  ];
  const binaryPath = binaryCandidates.find((candidate) => fs.existsSync(candidate));
  if (!binaryPath) {
    console.error('clusterd binary not found. Build it with: cd silica-core && cargo build --release');
    process.exit(1);
  }

  console.log(`[*] Starting Silica Cluster Master (Rust) on port ${port}...`);
  console.log(`[*] Cluster Auth Key: ${secret.slice(0, 8)}... (keyed token)`);
  console.log(`[*] Web GUI & API: http://localhost:${port}`);

  const child = spawn(binaryPath, ['--port', port], {
    stdio: 'inherit',
    env: {
      ...process.env,
      CLUSTER_SECRET: secret,
    },
  });

  child.on('exit', (code) => {
    process.exit(code ?? 0);
  });
}

async function handleRun(args: string[]) {
  const flags = parseArgs(args);
  const master = String(flags.master || 'http://127.0.0.1:3000');
  const port = Number(flags.port || 50055);
  const name = String(flags.name || `Node-${process.platform}-${process.pid}`);
  const secret = String(flags['cluster-key'] || 'clusterd-default-hmac-key');

  console.log(`========================================================================`);
  console.log(`[*] Starting Silica Worker Daemon: ${name}`);
  console.log(`[*] Target Master Hub: ${master}`);
  console.log(`[*] RPC Listener     : 0.0.0.0:${port} (-c persistent tensor cache enabled)`);
  console.log(`========================================================================`);

  // Detect local ggml-rpc-server binary
  const candidates = [
    path.resolve(__dirname, '..', 'llama-b11429', 'ggml-rpc-server.exe'),
    path.resolve(__dirname, '..', 'llama-b11429', 'ggml-rpc-server'),
    'ggml-rpc-server',
    'rpc-server',
  ];

  let foundBin: string | null = null;
  for (const c of candidates) {
    if (fs.existsSync(c)) {
      foundBin = c;
      break;
    }
  }

  const cacheStats = getTensorCacheStats();
  console.log(`[Cache] Persistent tensor cache: ${cacheStats.dir}`);
  console.log(`[Cache] Pre-cached shards: ${cacheStats.layerCount} (${cacheStats.totalMb} MB)`);

  let rpcChild: ReturnType<typeof spawn> | null = null;
  if (foundBin) {
    console.log(`[*] Launching native ${path.basename(foundBin)} on port ${port} with -c cache...`);
    rpcChild = spawn(foundBin, ['-H', '0.0.0.0', '-p', String(port), '-t', '4', '-c'], {
      cwd: cacheStats.dir,
      stdio: 'inherit',
    });
  } else {
    console.log(`[!] Native ggml-rpc-server not found on PATH — running in standby registration mode.`);
  }

  // Register with Master
  const registerPayload = {
    id: `worker-${name.toLowerCase().replace(/[^a-z0-9]+/g, '-')}`,
    label: name,
    platform: process.platform === 'win32' ? 'Windows' : 'Linux',
    osType: process.platform,
    ip: '127.0.0.1',
    rpcPort: port,
    linkType: 'Local',
    totalRamMb: Math.round(os.totalmem() / (1024 * 1024)),
    availableRamMb: Math.round(os.freemem() / (1024 * 1024)),
    vramMb: 0,
    computeTflops: 2.5,
    memBwGbps: 38,
    bigCores: Math.max(2, Math.floor(os.cpus().length / 2)),
    tempC: 35,
    cachedBytes: cacheStats.totalBytes,
    cachedShards: cacheStats.layerCount,
    cacheHits: cacheStats.hitCount,
    cacheStatus: cacheStats.statusLabel,
  };

  try {
    const regUrl = `${master.replace(/\/+$/, '')}/api/cluster/nodes/register`;
    const res = await fetch(regUrl, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', ...authHeaders() },
      body: JSON.stringify(registerPayload),
    });
    if (res.ok) {
      console.log(`[OK] Registered successfully with Master at ${master}`);
    }
  } catch (err) {
    console.log(`[!] Note: Master at ${master} not yet reachable; worker listening on port ${port}.`);
  }

  process.on('SIGINT', () => {
    console.log('\n[*] Shutting down worker daemon...');
    rpcChild?.kill('SIGTERM');
    process.exit(0);
  });
}

async function handleStatus(args: string[]) {
  const flags = parseArgs(args);
  const master = String(flags.url || 'http://127.0.0.1:3000');

  try {
    const res = await fetch(`${master.replace(/\/+$/, '')}/api/cluster/state`, { headers: authHeaders() });
    if (!res.ok) {
      console.error(`Error: Master returned HTTP ${res.status}`);
      process.exit(1);
    }
    const data = (await res.json()) as {
      activeModel?: { name?: string } | null;
      engineStatus?: { running?: boolean } | null;
      plan?: {
        nodes?: Array<{
          label?: string;
          role?: string;
          allocatedLayers?: number;
          safeRamMb?: number;
          linkType?: string;
          rttMs?: number;
          tempC?: number;
          statusNote?: string;
        }>;
        estimatedClusterTokPerSec?: number;
      } | null;
    };
    const nodes = data.plan?.nodes ?? [];

    console.log('\n========================================================================');
    console.log(` SILICA CLUSTER STATUS`);
    console.log(` Model         : ${data.activeModel?.name ?? 'none'}`);
    console.log(` Engine State  : ${(data.engineStatus?.running ? 'running' : 'offline').toUpperCase()}`);
    console.log(` Active Workers: ${nodes.filter((n) => n.role === 'Worker').length}`);
    console.log(` Throughput Est: ${(data.plan?.estimatedClusterTokPerSec ?? 0).toFixed(1)} tok/s`);
    console.log('========================================================================\n');

    console.log(
      'Node'.padEnd(25) +
        'Role'.padEnd(10) +
        'Allocated'.padEnd(22) +
        'Safe Cap'.padEnd(14) +
        'Link'.padEnd(16) +
        'RTT'.padEnd(10) +
        'Temp'.padEnd(8) +
        'Status'
    );
    console.log('-'.repeat(110));

    for (const n of nodes) {
      console.log(
        String(n.label ?? '').slice(0, 23).padEnd(25) +
          String(n.role ?? '').padEnd(10) +
          String(n.allocatedLayers ?? 0).padEnd(22) +
          String(Math.round(n.safeRamMb ?? 0)).padEnd(14) +
          String(n.linkType ?? '').padEnd(16) +
          String(n.rttMs ?? 0).padEnd(10) +
          String(n.tempC ?? 0).padEnd(8) +
          String(n.statusNote ?? '')
      );
    }
    console.log('\n');
  } catch (err) {
    console.error(`Failed to connect to Master at ${master}: ${err instanceof Error ? err.message : err}`);
    process.exit(1);
  }
}

async function handleCache(args: string[]) {
  const sub = args[0] || 'stats';
  if (sub === 'stats') {
    const s = getTensorCacheStats();
    console.log('\n--- Silica Tensor Cache Statistics ---');
    console.log(`Directory       : ${s.dir}`);
    console.log(`Total Files     : ${s.totalFiles}`);
    console.log(`Cached Shards   : ${s.layerCount}`);
    console.log(`Storage Size    : ${s.totalMb} MB (${s.totalBytes.toLocaleString()} bytes)`);
    console.log(`Cache Hits      : ${s.hitCount}`);
    console.log(`Bandwidth Saved : ${s.bandwidthSavedMb} MB`);
    console.log(`Status          : ${s.statusLabel}\n`);
  } else if (sub === 'clear') {
    const c = clearTensorCache();
    console.log(`[Cache] Cleared ${c.deletedFiles} cached files (${(c.freedBytes / (1024 * 1024)).toFixed(2)} MB freed).`);
  } else if (sub === 'verify') {
    const model = args[1] || 'model';
    const start = Number(args[2] || 0);
    const end = Number(args[3] || 10);
    const v = checkLayerRangeCached(model, start, end);
    console.log(`\n--- Layer Cache Verification: ${model} [L${start}..L${end}] ---`);
    console.log(`All Cached      : ${v.allCached ? 'YES (0 MB transfer needed)' : 'NO'}`);
    console.log(`Hit Rate        : ${v.hitRatePct}% (${v.cachedLayers.length}/${v.totalLayers} layers)`);
    console.log(`Residency       : ${v.tensorResidency}`);
    console.log(`Est. Launch Time: ${v.estimatedLaunchTimeMs} ms\n`);
  } else {
    console.log('Usage: silica cache [stats | clear | verify <model> <start> <end>]');
  }
}

async function main() {
  const [cmd, ...rest] = process.argv.slice(2);

  switch (cmd) {
    case 'serve':
      await handleServe(rest);
      break;
    case 'run':
      await handleRun(rest);
      break;
    case 'status':
      await handleStatus(rest);
      break;
    case 'cache':
      await handleCache(rest);
      break;
    case 'test': {
      const child = spawn('npm', ['test'], { stdio: 'inherit', shell: true });
      child.on('exit', (c) => process.exit(c ?? 0));
      break;
    }
    default:
      console.log(USAGE);
      break;
  }
}

main().catch((err) => {
  console.error('Fatal CLI Error:', err);
  process.exit(1);
});
