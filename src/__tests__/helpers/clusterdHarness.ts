import { spawn, type ChildProcess } from 'node:child_process';
import fs from 'node:fs';
import net from 'node:net';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const root = path.resolve(__dirname, '..', '..', '..');

export interface ClusterdHarness {
  baseUrl: string;
  cwd: string;
  stop: () => Promise<void>;
}

function freePort(): Promise<number> {
  return new Promise((resolve, reject) => {
    const server = net.createServer();
    server.once('error', reject);
    server.listen(0, '127.0.0.1', () => {
      const address = server.address();
      if (!address || typeof address === 'string') {
        server.close();
        reject(new Error('no ephemeral port'));
        return;
      }
      server.close(() => resolve(address.port));
    });
  });
}

export async function startClusterd(extraEnv: Record<string, string> = {}): Promise<ClusterdHarness> {
  const candidates = [
    path.join(root, 'silica-core', 'target', 'release', process.platform === 'win32' ? 'clusterd.exe' : 'clusterd'),
    path.join(root, 'silica-core', 'target', 'debug', process.platform === 'win32' ? 'clusterd.exe' : 'clusterd'),
  ];
  const binary = candidates.find((candidate) => fs.existsSync(candidate));
  if (!binary) {
    throw new Error('clusterd not built; run `cd silica-core && cargo build`');
  }
  const port = await freePort();
  const cwd = fs.mkdtempSync(path.join(os.tmpdir(), 'silica-clusterd-test-'));
  const child: ChildProcess = spawn(binary, ['--port', String(port), '--bind', '127.0.0.1'], {
    cwd,
    env: {
      ...process.env,
      CLUSTER_SECRET: 'clusterd-default-hmac-key',
      SILICA_TENSOR_CACHE_DIR: path.join(cwd, 'cache'),
      ...extraEnv,
    },
    stdio: ['ignore', 'pipe', 'pipe'],
  });
  let stderr = '';
  child.stderr?.on('data', (chunk: Buffer) => {
    stderr += chunk.toString();
  });
  const baseUrl = `http://127.0.0.1:${port}`;
  const deadline = Date.now() + 15_000;
  while (Date.now() < deadline) {
    if (child.exitCode !== null) {
      throw new Error(`clusterd exited (${child.exitCode}): ${stderr.slice(-2000)}`);
    }
    try {
      const res = await fetch(`${baseUrl}/health`);
      if (res.ok) {
        return {
          baseUrl,
          cwd,
          stop: async () => {
            child.kill('SIGTERM');
            await new Promise<void>((resolve) => {
              if (child.exitCode !== null) return resolve();
              child.once('exit', () => resolve());
              setTimeout(() => {
                child.kill('SIGKILL');
                resolve();
              }, 3000);
            });
            fs.rmSync(cwd, { recursive: true, force: true });
          },
        };
      }
    } catch {
      // not ready
    }
    await new Promise((resolve) => setTimeout(resolve, 100));
  }
  child.kill('SIGKILL');
  throw new Error(`clusterd did not become ready: ${stderr.slice(-2000)}`);
}
