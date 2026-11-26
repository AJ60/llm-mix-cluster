import { describe, it, before, after } from 'node:test';
import assert from 'node:assert/strict';
import net, { type Server } from 'node:net';
import { startClusterd, type ClusterdHarness } from './helpers/clusterdHarness.ts';
import { decodeV10Request, encodeV10Reply } from '../domain/clusterAuth.ts';

describe('clusterd canonical HTTP API', () => {
  let harness: ClusterdHarness;

  before(async () => {
    harness = await startClusterd();
  });

  after(async () => {
    await harness.stop();
  });

  it('reports health, beacon and a camelCase state snapshot', async () => {
    const health = await (await fetch(`${harness.baseUrl}/health`)).json();
    assert.equal(health.status, 'ok');
    assert.equal(health.runtime, 'rust-clusterd');
    const beacon = await (await fetch(`${harness.baseUrl}/api/cluster/beacon`)).json();
    assert.equal(beacon.protocolVersion, 9);
    assert.equal(beacon.secret, undefined);
    const state = await (await fetch(`${harness.baseUrl}/api/cluster/state`)).json();
    assert.ok('activeModel' in state && 'plan' in state && 'tuningConfig' in state);
    assert.ok('contextLength' in state.tuningConfig);
  });

  it('stores, verifies and clears tensor cache layers', async () => {
    const base = `${harness.baseUrl}/api/cache`;
    const store = await (
      await fetch(`${base}/store-layer`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ modelId: 'test-model', layer: 0, dataB64: 'AQIDBA==' }),
      })
    ).json();
    assert.equal(store.ok, true);
    const verify = await (
      await fetch(`${base}/verify`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ modelId: 'test-model', start: 0, end: 1 }),
      })
    ).json();
    assert.deepEqual(verify.cached, [0]);
    assert.deepEqual(verify.missing, [1]);
    const stats = await (await fetch(`${base}/stats`)).json();
    assert.ok(stats.totalFiles >= 1);
    const clear = await (await fetch(`${base}/clear`, { method: 'POST' })).json();
    assert.equal(clear.ok, true);
  });

  it('records benchmarks and runs an offline benchmark', async () => {
    const run = await (
      await fetch(`${harness.baseUrl}/api/benchmarks/run`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ modelId: 'test-model', maxTokens: 16 }),
      })
    ).json();
    assert.equal(run.ok, true);
    assert.equal(run.result.generatedTokens, 16);
    const list = await (
      await fetch(`${harness.baseUrl}/api/benchmarks?modelId=test-model`)
    ).json();
    assert.ok(list.history.length >= 1);
  });

  it('exposes the 6-check diagnostic sentinel and Wi-Fi Direct helpers', async () => {
    const sentinel = await (await fetch(`${harness.baseUrl}/api/diagnostics/sentinel`)).json();
    assert.equal(sentinel.totalChecks, 6);
    const qr = await (await fetch(`${harness.baseUrl}/api/network/wifi-direct/qr`)).json();
    assert.equal(qr.qr.matrix21x21.length, 21);
    const connect = await (
      await fetch(`${harness.baseUrl}/api/network/wifi-direct/connect`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ rawPayload: qr.qr.rawPayload }),
      })
    ).json();
    assert.equal(connect.ok, true);
  });

  it('returns a JSON 404 for unknown API endpoints on any method', async () => {
    const res = await fetch(`${harness.baseUrl}/api/not-a-route`, { method: 'POST' });
    assert.equal(res.status, 404);
    const body = await res.json();
    assert.equal(body.ok, false);
  });

  it('verifies a 32-byte handshake and registers a live worker', async () => {
    const listener: Server = net.createServer((socket) => {
      socket.on('error', () => {});
      let replied = false;
      socket.on('data', (chunk: Buffer) => {
        if (replied || chunk.length < 32) return;
        const nonce = decodeV10Request('clusterd-default-hmac-key', new Uint8Array(chunk.subarray(0, 32)));
        if (!nonce) return;
        replied = true;
        socket.write(Buffer.from(encodeV10Reply('clusterd-default-hmac-key', nonce, 2048, 4, 0, 0)));
      });
    });
    await new Promise<void>((resolve) => listener.listen(0, '127.0.0.1', resolve));
    const address = listener.address();
    if (!address || typeof address === 'string') throw new Error('no port');
    try {
      const res = await fetch(`${harness.baseUrl}/api/cluster/nodes/register`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          id: 'node-test',
          label: 'Test Worker',
          osType: 'android',
          ip: '127.0.0.1',
          rpcPort: address.port,
          linkType: 'USB-C Tunnel',
          totalRamMb: 4096,
          availableRamMb: 2048,
        }),
      });
      assert.equal(res.status, 200);
      const body = await res.json();
      assert.equal(body.ok, true);
      assert.ok('assignedLayers' in body);
      const hw = await (await fetch(`${harness.baseUrl}/api/cluster/hardware`)).json();
      assert.ok(hw.connectedDevices.some((d: { id: string }) => d.id === 'node-test'));
    } finally {
      await new Promise<void>((resolve) => listener.close(() => resolve()));
    }
  });
});
