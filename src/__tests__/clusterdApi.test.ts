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
        body: JSON.stringify({ rawPayload: qr.qr.rawPayload, crc8Hex: qr.qr.crc8Hex }),
      })
    ).json();
    assert.equal(connect.ok, true);
    const rejected = await (
      await fetch(`${harness.baseUrl}/api/network/wifi-direct/connect`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ rawPayload: qr.qr.rawPayload, crc8Hex: 'FF' }),
      })
    ).json();
    assert.equal(rejected.ok, false);
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
      let pending = Buffer.alloc(0);
      socket.on('data', (chunk: Buffer) => {
        pending = Buffer.concat([pending, chunk]);
        if (!replied) {
          if (pending.length < 32) return;
          const nonce = decodeV10Request('clusterd-default-hmac-key', new Uint8Array(pending.subarray(0, 32)));
          if (!nonce) return;
          replied = true;
          socket.write(Buffer.from(encodeV10Reply('clusterd-default-hmac-key', nonce, 2048, 4, 0, 0)));
          pending = pending.subarray(32);
        }
        while (pending.length >= 8 && pending.subarray(0, 4).toString('ascii') === 'BWPR') {
          const length = pending.readUInt32LE(4);
          if (pending.length < 8 + length) return;
          const header = Buffer.alloc(8);
          header.write('BWOK', 0, 'ascii');
          header.writeUInt32LE(length, 4);
          socket.write(Buffer.concat([header, pending.subarray(8, 8 + length)]));
          pending = pending.subarray(8 + length);
        }
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
      assert.ok(body.recommendedConfig, 'register response must carry Rust worker advice');
      assert.equal(typeof body.recommendedConfig.recommendedThreads, 'number');
      assert.equal(typeof body.recommendedConfig.adaptiveLoadFactor, 'number');
      assert.ok(
        body.snapshot.plan === null || typeof body.snapshot.plan.effectiveContext === 'number',
        'plan must be null without a model or carry effective context'
      );
      const hw = await (await fetch(`${harness.baseUrl}/api/cluster/hardware`)).json();
      const registered = hw.connectedDevices.find((d: { id: string }) => d.id === 'node-test');
      assert.ok(registered);
      assert.equal(typeof registered.bandwidthMbps, 'number');
      assert.equal(typeof registered.storageTotalMb, 'number');
      assert.equal(typeof registered.rttMs, 'number');
    } finally {
      await new Promise<void>((resolve) => listener.close(() => resolve()));
    }
  });

  it('registers an authorized browser WebGPU worker without a TCP handshake', async () => {
    const res = await fetch(`${harness.baseUrl}/api/cluster/nodes/register`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        workerId: 'browser-test',
        label: 'Browser WebGPU Worker',
        osType: 'webgpu',
        workerKind: 'browser',
        linkType: 'WebGPU Browser',
        ip: '127.0.0.1',
        rpcPort: 0,
        totalRamMb: 4096,
        availableRamMb: 4096,
        vramFreeMb: 1024,
        vramTotalMb: 1024,
        computeTflops: 2.5,
        rttMs: 2.4,
        linkBandwidthMbps: 900,
      }),
    });
    assert.equal(res.status, 200);
    const body = await res.json();
    assert.equal(body.ok, true);
    assert.equal(body.node.transport, 'WebGPU');
    assert.equal(body.node.handshake_verified, false);
  });
});
