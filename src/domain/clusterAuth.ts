/**
 * Bit-exact TypeScript mirror of Rust `silica-core::protocol::compute_cluster_auth_token`
 * and Android Kotlin `RpcService.computeClusterAuthToken` for 32-byte keyed GGUF/RPC v9 handshakes.
 */

export const HANDSHAKE_CHALLENGE = new TextEncoder().encode('GGUF_PROBE_V10');
export const REPLY_CHALLENGE = new TextEncoder().encode('GGUF_REPLY_V10');
export const HANDSHAKE_MAGIC_V10 = new TextEncoder().encode('NONC');
export const RPC_PROTOCOL_VERSION = 10;

const MASK_64 = (1n << 64n) - 1n;

function rotl64(x: bigint, k: bigint): bigint {
  return (((x << k) & MASK_64) | (x >> (64n - k))) & MASK_64;
}

function rotr64(x: bigint, k: bigint): bigint {
  return ((x >> k) | ((x << (64n - k)) & MASK_64)) & MASK_64;
}

export function computeClusterAuthToken(
  secret: string,
  challenge: Uint8Array = HANDSHAKE_CHALLENGE
): Uint8Array {
  const state = [
    0x6a09e667f3bcc908n,
    0xbb67ae8584caa73bn,
    0x3c6ef372fe94f82bn,
    0xa54ff53a5f1d36f1n,
  ];
  const secretBytes = new TextEncoder().encode(secret);
  const totalLen = secretBytes.length + challenge.length;

  for (let i = 0; i < totalLen; i++) {
    const b = BigInt(i < secretBytes.length ? secretBytes[i] : challenge[i - secretBytes.length]);
    const lane = i & 3;
    const nextLane = (lane + 1) & 3;
    const mixed = rotl64((state[lane] + ((b * 0x9e3779b97f4a7c15n) & MASK_64)) & MASK_64, 13n);
    state[lane] = (mixed ^ state[nextLane]) & MASK_64;
  }

  for (let round = 0n; round < 8n; round++) {
    for (let lane = 0; lane < 4; lane++) {
      const mul = (state[lane] * 0xbf58476d1ce4e5b9n) & MASK_64;
      state[lane] = rotr64((mul + round) & MASK_64, 17n);
    }
  }

  const out = new Uint8Array(32);
  const view = new DataView(out.buffer);
  for (let idx = 0; idx < 4; idx++) {
    view.setBigUint64(idx * 8, state[idx], true);
  }
  return out;
}

export function verifyClusterAuthToken(secret: string, received: Uint8Array): boolean {
  if (received.length < 32) return false;
  const expected = computeClusterAuthToken(secret, HANDSHAKE_CHALLENGE);
  let diff = 0;
  for (let i = 0; i < 32; i++) {
    diff |= expected[i] ^ received[i];
  }
  return diff === 0;
}

export interface DecodedGgufHandshake {
  rpcVersion: number;
  versionTag: string;
  safeCapMb: number;
  bigCores: number;
  flags: number;
  status: 'READY' | 'THROTTLED' | 'SHED_LAYERS';
}

export function decodeGgufHandshakeFrame(buf: Uint8Array): DecodedGgufHandshake | null {
  if (buf.length < 32) return null;
  if (buf[0] !== 0x47 || buf[1] !== 0x47 || buf[2] !== 0x55 || buf[3] !== 0x46) return null;
  const view = new DataView(buf.buffer, buf.byteOffset, buf.byteLength);
  const rpcVersion = view.getUint32(4, true);
  if (rpcVersion !== RPC_PROTOCOL_VERSION) return null;
  const versionTag = new TextDecoder()
    .decode(buf.subarray(8, 16))
    .replace(/\0+$/, '');
  const safeCapMb = view.getUint32(16, true);
  const bigCores = view.getUint32(20, true);
  const flags = view.getUint32(24, true);
  const rawStatus = view.getUint32(28, true);
  const status = rawStatus === 0 ? 'READY' : rawStatus === 1 ? 'THROTTLED' : 'SHED_LAYERS';
  return { rpcVersion, versionTag, safeCapMb, bigCores, flags, status };
}

function concatBytes(...parts: Uint8Array[]): Uint8Array {
  const out = new Uint8Array(parts.reduce((n, p) => n + p.length, 0));
  let offset = 0;
  for (const part of parts) {
    out.set(part, offset);
    offset += part.length;
  }
  return out;
}

function equalBytes(a: Uint8Array, b: Uint8Array): boolean {
  if (a.length !== b.length) return false;
  let diff = 0;
  for (let i = 0; i < a.length; i += 1) diff |= a[i] ^ b[i];
  return diff === 0;
}

export function encodeV10Request(secret: string, nonce: Uint8Array): Uint8Array {
  const token = computeClusterAuthToken(secret, concatBytes(HANDSHAKE_CHALLENGE, nonce));
  return concatBytes(HANDSHAKE_MAGIC_V10, nonce, token.subarray(0, 20));
}

export function decodeV10Request(secret: string, frame: Uint8Array): Uint8Array | null {
  if (frame.length < 32 || !equalBytes(frame.subarray(0, 4), HANDSHAKE_MAGIC_V10)) return null;
  const nonce = frame.slice(4, 12);
  const expected = encodeV10Request(secret, nonce);
  return equalBytes(frame.subarray(12, 32), expected.subarray(12, 32)) ? nonce : null;
}

function replyMac(secret: string, nonce: Uint8Array, cap: number, cores: number, flags: number, status: number): Uint8Array {
  const data = concatBytes(
    REPLY_CHALLENGE,
    nonce,
    new Uint8Array(new Uint32Array([cap]).buffer),
    new Uint8Array([cores]),
    new Uint8Array(new Uint16Array([flags]).buffer),
    new Uint8Array([status])
  );
  const token = computeClusterAuthToken(secret, data);
  return new Uint8Array([
    token[8] ^ token[24], token[9] ^ token[25], token[10] ^ token[26], token[11] ^ token[27],
    token[12] ^ token[28], token[13] ^ token[29], token[14] ^ token[30], token[15] ^ token[31],
  ]);
}

export function encodeV10Reply(
  secret: string,
  nonce: Uint8Array,
  safeCapMb: number,
  bigCores: number,
  flags: number,
  status: number
): Uint8Array {
  const out = new Uint8Array(32);
  out.set(new TextEncoder().encode('GGUF'), 0);
  new DataView(out.buffer).setUint32(4, 10, true);
  out.set(nonce, 8);
  out.set(replyMac(secret, nonce, safeCapMb, bigCores, flags, status), 16);
  new DataView(out.buffer).setUint32(24, safeCapMb, true);
  const packed = (status & 0xff) | ((flags & 0xffff) << 8) | ((bigCores & 0xff) << 24);
  new DataView(out.buffer).setUint32(28, packed >>> 0, true);
  return out;
}

export function verifyV10Reply(secret: string, frame: Uint8Array, nonce: Uint8Array): boolean {
  if (frame.length < 32 || new TextDecoder().decode(frame.subarray(0, 4)) !== 'GGUF') return false;
  const view = new DataView(frame.buffer, frame.byteOffset, frame.byteLength);
  if (view.getUint32(4, true) !== 10) return false;
  if (!equalBytes(frame.subarray(8, 16), nonce)) return false;
  const cap = view.getUint32(24, true);
  const packed = view.getUint32(28, true);
  const expected = replyMac(secret, nonce, cap, (packed >>> 24) & 0xff, (packed >>> 8) & 0xffff, packed & 0xff);
  return equalBytes(frame.subarray(16, 24), expected);
}
