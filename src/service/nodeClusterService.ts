import {
  CalibrationProfile,
  ComputeBackend,
  DeviceTelemetryState,
  DiagnosticCheck,
  DiagnosticReport,
  DiagnosticSeverity,
} from '../types/clustering';

const HANDSHAKE_CHALLENGE = new TextEncoder().encode('GGUF_PROBE_V9');
const MASK_64 = (1n << 64n) - 1n;

function rotl64(val: bigint, bits: number): bigint {
  const shift = BigInt(bits);
  return ((val << shift) | (val >> (64n - shift))) & MASK_64;
}

function rotr64(val: bigint, bits: number): bigint {
  const shift = BigInt(bits);
  return ((val >> shift) | (val << (64n - shift))) & MASK_64;
}

/**
 * Bit-exact TypeScript mirror of Kotlin/Rust `computeClusterAuthToken`
 * producing the 32-byte keyed auth token (64 hex chars).
 */
export function computeClusterAuthTokenHex(secret: string): string {
  const state = [
    0x6a09e667f3bcc908n,
    0xbb67ae8584caa73bn,
    0x3c6ef372fe94f82bn,
    0xa54ff53a5f1d36f1n,
  ];
  const secretBytes = new TextEncoder().encode(secret);
  const totalLen = secretBytes.length + HANDSHAKE_CHALLENGE.length;

  for (let i = 0; i < totalLen; i++) {
    const b =
      i < secretBytes.length
        ? secretBytes[i]
        : HANDSHAKE_CHALLENGE[i - secretBytes.length];
    const uByte = BigInt(b & 0xff);
    const lane = i & 3;
    const nextLane = (lane + 1) & 3;
    const mixed = rotl64((state[lane] + uByte * 0x9e3779b97f4a7c15n) & MASK_64, 13);
    state[lane] = mixed ^ state[nextLane];
  }

  for (let round = 0; round < 8; round++) {
    for (let lane = 0; lane < 4; lane++) {
      state[lane] = rotr64(
        ((state[lane] * 0xbf58476d1ce4e5b9n) & MASK_64) + BigInt(round),
        17
      );
    }
  }

  const out = new Uint8Array(32);
  const view = new DataView(out.buffer);
  state.forEach((word, idx) => {
    view.setBigUint64(idx * 8, word, true);
  });

  return Array.from(out)
    .map((b) => b.toString(16).padStart(2, '0'))
    .join('');
}

export function parseQrPairingPayload(raw: string): {
  masterAddr: string;
  secret: string;
  isUsb: boolean;
} | null {
  const trimmed = raw.trim();
  if (!trimmed) return null;

  if (trimmed.toLowerCase().startsWith('clusterd://')) {
    const qIdx = trimmed.indexOf('?');
    const query = qIdx >= 0 ? trimmed.slice(qIdx + 1) : '';
    let master = '127.0.0.1:8080';
    let secret = 'clusterd-default-hmac-key';
    for (const part of query.split('&')) {
      const eq = part.indexOf('=');
      if (eq < 0) continue;
      const key = part.slice(0, eq).toLowerCase();
      const val = part.slice(eq + 1);
      if (key === 'master' || key === 'host') master = val;
      if (key === 'secret' || key === 'token') secret = val;
    }
    const isUsb = master.startsWith('127.0.0.1') || master.startsWith('localhost');
    return { masterAddr: master, secret, isUsb };
  }

  const hostPort = trimmed.includes(':') ? trimmed : `${trimmed}:8080`;
  const isUsb = hostPort.startsWith('127.0.0.1') || hostPort.startsWith('localhost');
  return {
    masterAddr: hostPort,
    secret: 'clusterd-default-hmac-key',
    isUsb,
  };
}

/**
 * Deterministic 21x21 CRC-8 QR Matrix Codec matching Android CameraX & Master Web UI.
 */
export const ClusterQrCodec = {
  GRID_SIZE: 21,
  PAYLOAD_BYTES: 28,
  MAGIC_BYTE: 0xc1,

  isReservedFinderOrTiming(r: number, c: number): boolean | null {
    const checkFinder = (topR: number, leftC: number): boolean | null => {
      const dr = r - topR;
      const dc = c - leftC;
      if (dr >= 0 && dr <= 6 && dc >= 0 && dc <= 6) {
        if (dr === 0 || dr === 6 || dc === 0 || dc === 6) return true;
        if (dr >= 2 && dr <= 4 && dc >= 2 && dc <= 4) return true;
        return false;
      }
      if (dr >= -1 && dr <= 7 && dc >= -1 && dc <= 7 && r >= 0 && r < 21 && c >= 0 && c < 21) {
        return false;
      }
      return null;
    };

    const finder =
      checkFinder(0, 0) ?? checkFinder(0, 21 - 7) ?? checkFinder(21 - 7, 0);
    if (finder !== null) return finder;

    if (r === 6 || c === 6) return (r + c) % 2 === 0;
    return null;
  },

  computeCrc8(data: Uint8Array, length: number): number {
    let crc = 0x00;
    for (let i = 0; i < length; i++) {
      crc ^= data[i] & 0xff;
      for (let bit = 0; bit < 8; bit++) {
        crc = (crc & 0x80) !== 0 ? ((crc << 1) ^ 0x07) & 0xff : (crc << 1) & 0xff;
      }
    }
    return crc;
  },

  encodeUriTo21x21Matrix(uri: string): boolean[][] {
    const parsed = parseQrPairingPayload(uri);
    const masterAddr = parsed?.masterAddr ?? '127.0.0.1:8080';
    const secret = parsed?.secret ?? 'clusterd-default-secret';

    const colonIdx = masterAddr.indexOf(':');
    const hostPart = colonIdx >= 0 ? masterAddr.slice(0, colonIdx) : masterAddr;
    const masterPort = colonIdx >= 0 ? parseInt(masterAddr.slice(colonIdx + 1), 10) || 8080 : 8080;
    const rpcMatch = uri.match(/rpc=(\d+)/);
    const rpcPort = rpcMatch ? parseInt(rpcMatch[1], 10) || 50052 : 50052;

    const octets = hostPart
      .split('.')
      .map((s) => parseInt(s, 10))
      .filter((n) => Number.isInteger(n) && n >= 0 && n <= 255);
    const ipBytes = octets.length === 4 ? octets : [127, 0, 0, 1];

    const packet = new Uint8Array(28);
    packet[0] = 0xc1;
    packet[1] = ipBytes[0];
    packet[2] = ipBytes[1];
    packet[3] = ipBytes[2];
    packet[4] = ipBytes[3];
    packet[5] = (masterPort >>> 8) & 0xff;
    packet[6] = masterPort & 0xff;
    packet[7] = (rpcPort >>> 8) & 0xff;
    packet[8] = rpcPort & 0xff;

    const secretBytes = new TextEncoder().encode(secret);
    const secretLen = Math.min(17, secretBytes.length);
    packet[9] = secretLen;
    for (let i = 0; i < secretLen; i++) {
      packet[10 + i] = secretBytes[i];
    }
    packet[27] = this.computeCrc8(packet, 27);

    const matrix: boolean[][] = Array.from({ length: 21 }, () => new Array<boolean>(21).fill(false));
    let bitIndex = 0;
    const totalBits = 28 * 8;

    for (let r = 0; r < 21; r++) {
      for (let c = 0; c < 21; c++) {
        const reserved = this.isReservedFinderOrTiming(r, c);
        if (reserved !== null) {
          matrix[r][c] = reserved;
        } else {
          let bit: boolean;
          if (bitIndex < totalBits) {
            const byteVal = packet[Math.floor(bitIndex / 8)] & 0xff;
            const shift = 7 - (bitIndex % 8);
            bit = ((byteVal >>> shift) & 1) === 1;
          } else {
            bit = (r + c) % 2 === 0;
          }
          matrix[r][c] = bit;
          bitIndex++;
        }
      }
    }
    return matrix;
  },

  decode21x21MatrixToUri(matrix: boolean[][]): string | null {
    if (matrix.length !== 21 || matrix.some((row) => row.length !== 21)) return null;

    const origins: Array<[number, number]> = [
      [0, 0],
      [0, 14],
      [14, 0],
    ];
    for (const [topR, leftC] of origins) {
      let darkCount = 0;
      for (let dr = 2; dr <= 4; dr++) {
        for (let dc = 2; dc <= 4; dc++) {
          if (matrix[topR + dr][leftC + dc]) darkCount++;
        }
      }
      if (darkCount < 7) return null;
    }

    const packet = new Uint8Array(28);
    let bitIndex = 0;
    const totalBits = 28 * 8;

    for (let r = 0; r < 21; r++) {
      for (let c = 0; c < 21; c++) {
        if (this.isReservedFinderOrTiming(r, c) !== null) continue;
        if (bitIndex < totalBits) {
          if (matrix[r][c]) {
            const byteIdx = Math.floor(bitIndex / 8);
            const shift = 7 - (bitIndex % 8);
            packet[byteIdx] |= 1 << shift;
          }
          bitIndex++;
        }
      }
    }

    if (packet[0] !== 0xc1) return null;
    if (packet[27] !== this.computeCrc8(packet, 27)) return null;

    const masterPort = (packet[5] << 8) | packet[6];
    const rpcPort = (packet[7] << 8) | packet[8];
    const secretLen = Math.min(17, packet[9]);
    const secret =
      secretLen > 0
        ? new TextDecoder().decode(packet.subarray(10, 10 + secretLen))
        : 'clusterd-default-secret';

    return `clusterd://pair?master=${packet[1]}.${packet[2]}.${packet[3]}.${packet[4]}:${masterPort}&rpc=${rpcPort}&secret=${secret}`;
  },
};

export function probeWebHardware(): {
  totalRamMb: number;
  availableRamMb: number;
  safeCapMb: number;
  totalCores: number;
  bigCores: number;
  webglSupported: boolean;
  webglRenderer: string;
  activeBackend: ComputeBackend;
  deviceModel: string;
  platformLabel: string;
  legacyHardware: boolean;
  legacyGraphics: boolean;
} {
  const nav = navigator as Navigator & { deviceMemory?: number };
  const totalCores = Math.max(2, nav.hardwareConcurrency || 4);
  const bigCores = Math.max(1, Math.min(totalCores, Math.ceil(totalCores / 2)));

  const deviceMemGb = typeof nav.deviceMemory === 'number' ? nav.deviceMemory : 8;
  const totalRamMb = Math.max(1024, Math.round(deviceMemGb * 1024));
  const legacyHardware = totalRamMb <= 3072 || totalCores <= 2;

  let webglSupported = false;
  let webglRenderer = 'Software 2D Canvas';
  try {
    const canvas = document.createElement('canvas');
    const gl =
      (canvas.getContext('webgl2') as WebGLRenderingContext | null) ||
      (canvas.getContext('webgl') as WebGLRenderingContext | null);
    if (gl) {
      webglSupported = true;
      const dbg = gl.getExtension('WEBGL_debug_renderer_info');
      webglRenderer = dbg
        ? String(gl.getParameter(dbg.UNMASKED_RENDERER_WEBGL))
        : String(gl.getParameter(gl.RENDERER) || 'WebGL Hardware Context');
    }
  } catch {
    webglSupported = false;
  }

  const ratio = totalRamMb <= 2048 ? 0.4 : 0.5;
  const reserve = totalRamMb <= 2048 ? 256 : 512;
  const availableRamMb = Math.round(totalRamMb * 0.68);
  const safeCapMb = Math.min(
    Math.round(totalRamMb * ratio),
    Math.max(256, availableRamMb - reserve)
  );

  const hasWasmSimd = typeof WebAssembly === 'object';
  const activeBackend: ComputeBackend = webglSupported
    ? 'WEBGL_FP16'
    : hasWasmSimd
      ? 'WASM_SIMD'
      : 'JS_BASELINE';

  return {
    totalRamMb,
    availableRamMb,
    safeCapMb,
    totalCores,
    bigCores,
    webglSupported,
    webglRenderer,
    activeBackend,
    deviceModel: `${navigator.platform || 'Web Browser'}`,
    platformLabel: navigator.userAgent.includes('Android')
      ? 'Android Web Worker'
      : 'Browser Compute Worker',
    legacyHardware,
    legacyGraphics: !webglSupported,
  };
}

export function runCalibrationBenchmark(
  batteryPercent: number,
  isPluggedIn: boolean,
  temperatureCelsius: number
): { profile: CalibrationProfile; hardware: ReturnType<typeof probeWebHardware> } {
  const hardware = probeWebHardware();

  // Deterministic float dot-product micro-benchmark
  const t0 = performance.now();
  const vectorSize = 1024;
  const a = new Float32Array(vectorSize);
  const b = new Float32Array(vectorSize);
  for (let i = 0; i < vectorSize; i++) {
    a[i] = ((i % 17) - 8) * 0.125;
    b[i] = ((i % 13) - 6) * 0.15;
  }
  let acc = 0;
  for (let iter = 0; iter < 180; iter++) {
    let sum = 0;
    for (let j = 0; j < vectorSize; j++) {
      sum += a[j] * b[j];
    }
    acc += sum * 0.001;
  }
  const elapsedMs = Math.max(1, Math.round(performance.now() - t0));
  const checksumAdj = Number.isFinite(acc) ? 0 : -1;

  const throughputPts = Math.max(10, Math.min(45, 220 / elapsedMs));
  const ramPts = Math.max(8, Math.min(30, hardware.safeCapMb / 100));
  const corePts = Math.max(4, Math.min(15, hardware.bigCores * 3.5));
  const accelBonus = hardware.webglSupported ? 10 : 5;
  const score = Math.max(
    15,
    Math.min(100, Math.round(throughputPts + ramPts + corePts + accelBonus + checksumAdj))
  );

  const isWarmOrLowBattery = temperatureCelsius >= 39 || (!isPluggedIn && batteryPercent < 30);
  const tunedThreads = hardware.legacyHardware
    ? Math.min(Math.max(1, hardware.bigCores), 2)
    : isWarmOrLowBattery
      ? Math.max(1, Math.floor(hardware.bigCores / 2))
      : hardware.bigCores;

  const maxPhysicalCeiling = Math.max(256, Math.round(hardware.totalRamMb * (hardware.totalRamMb <= 2048 ? 0.4 : 0.5)));
  const tunedSafeRamMb = isWarmOrLowBattery
    ? Math.min(maxPhysicalCeiling, Math.max(256, Math.round(hardware.safeCapMb * 0.8)))
    : Math.min(maxPhysicalCeiling, hardware.safeCapMb);

  const tierLabel = hardware.legacyHardware
    ? 'Legacy-Safe Helper'
    : score >= 78 && tunedSafeRamMb >= 2500
      ? 'Turbo Helper'
      : score >= 52 && tunedSafeRamMb >= 1200
        ? 'Balanced Helper'
        : 'Light Helper';

  const backendLabel = hardware.webglSupported
    ? 'Graphics Boost (WebGL)'
    : 'Fast CPU Engine (WASM/JS)';

  const layerCapacity = Math.max(2, Math.min(24, Math.floor(tunedSafeRamMb / 110)));
  const summarySentence = hardware.legacyHardware
    ? `Legacy-safe tuning active (${tunedSafeRamMb} MB cap, ${tunedThreads} thread(s)) so older hardware stays smooth.`
    : isWarmOrLowBattery
      ? `Auto-tuned in Eco Mode (${tunedSafeRamMb} MB) to keep your device cool and save battery.`
      : tierLabel === 'Turbo Helper'
        ? `Fast device detected! Auto-tuned to share up to ${tunedSafeRamMb} MB smoothly in the background.`
        : tierLabel === 'Balanced Helper'
          ? `Auto-tuned for steady background sharing (${tunedSafeRamMb} MB) without slowing your device.`
          : `Auto-tuned in Light Mode (${tunedSafeRamMb} MB) to keep everyday apps snappy and responsive.`;

  const now = new Date();
  const calibratedAtLabel = now.toTimeString().slice(0, 8);

  return {
    hardware,
    profile: {
      isCalibrated: true,
      isCalibrating: false,
      calibratedAtLabel,
      performanceScore: score,
      tierLabel,
      recommendedSafeRamMb: tunedSafeRamMb,
      recommendedThreads: tunedThreads,
      recommendedBackendLabel: backendLabel,
      estimatedLayerCapacity: layerCapacity,
      benchmarkDurationMs: elapsedMs,
      summarySentence,
    },
  };
}

export async function probeMasterEndpointReachable(masterAddr: string): Promise<boolean> {
  const cleaned = masterAddr.split(' ')[0].trim();
  if (!cleaned.includes(':')) return false;
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), 750);
  try {
    await fetch(`http://${cleaned}/health`, {
      method: 'GET',
      mode: 'no-cors',
      signal: controller.signal,
    });
    return true;
  } catch {
    return false;
  } finally {
    clearTimeout(timeout);
  }
}

export async function runAutoDiagnosticsSuite(
  telemetry: DeviceTelemetryState
): Promise<DiagnosticReport> {
  const checks: DiagnosticCheck[] = [];

  // 1. Calibration
  if (telemetry.calibration.isCalibrated) {
    checks.push({
      id: 'calibration',
      title: 'Device Speed & Memory Tuning',
      statusText: `${telemetry.calibration.tierLabel} (${telemetry.calibration.recommendedSafeRamMb} MB cap)`,
      plainEnglishTip: 'Your device is auto-calibrated and ready for safe background sharing.',
      severity: 'OK',
    });
  } else {
    checks.push({
      id: 'calibration',
      title: 'Device Speed & Memory Tuning',
      statusText: 'Not calibrated yet',
      plainEnglishTip: 'Run a quick 1-second auto-tune so this device picks safe memory and speed limits.',
      severity: 'WARNING',
      fixAction: 'RECALIBRATE_DEVICE',
    });
  }

  // 2. Network / Cable
  const isOnline = typeof navigator !== 'undefined' ? navigator.onLine : true;
  if (telemetry.usbLoopbackOnly) {
    checks.push({
      id: 'network',
      title: 'Connection Type',
      statusText: 'USB Cable / Localhost Mode Ready',
      plainEnglishTip:
        'Local loopback mode is active. Plug in via USB/ADB or scan a LAN QR code to switch to Wi-Fi.',
      severity: 'OK',
    });
  } else if (isOnline) {
    checks.push({
      id: 'network',
      title: 'Wi-Fi / LAN Connection',
      statusText: 'Network online & HMAC ready',
      plainEnglishTip:
        'Make sure your Windows or Linux cluster master is on the same Wi-Fi LAN.',
      severity: 'OK',
    });
  } else {
    checks.push({
      id: 'network',
      title: 'Wi-Fi / LAN Connection',
      statusText: 'Device appears offline',
      plainEnglishTip:
        'Connect to Wi-Fi or switch to USB Loopback Mode to connect directly over a cable.',
      severity: 'ISSUE',
      fixAction: 'SWITCH_TO_USB_SAFE_MODE',
    });
  }

  // 3. Computer (Master) Reachability
  const cleanedMaster = telemetry.pairedMasterAddress.split(' ')[0].trim();
  const hasMasterAddr = cleanedMaster.includes(':') && !telemetry.pairedMasterAddress.startsWith('Not Paired');
  if (!hasMasterAddr) {
    checks.push({
      id: 'master_reachability',
      title: 'Computer Connection',
      statusText: 'Not paired with a computer yet',
      plainEnglishTip:
        "Open ClusterLab (`clusterd`) on your Windows or Linux PC, then click 'Auto-Connect' or scan the QR code.",
      severity: 'WARNING',
      fixAction: 'AUTO_DETECT_MASTER',
    });
  } else {
    const reachable = await probeMasterEndpointReachable(cleanedMaster);
    if (reachable) {
      checks.push({
        id: 'master_reachability',
        title: 'Computer Connection',
        statusText: `Reachable at ${cleanedMaster}`,
        plainEnglishTip: 'Your node can reach your cluster master cleanly with no firewall block.',
        severity: 'OK',
      });
    } else {
      checks.push({
        id: 'master_reachability',
        title: 'Computer Connection',
        statusText: `Master not responding at ${cleanedMaster}`,
        plainEnglishTip:
          'Check that `clusterd` is running on your PC, both devices share the network, or run `adb forward tcp:50052 tcp:50052` for USB.',
        severity: 'ISSUE',
        fixAction: 'AUTO_DETECT_MASTER',
      });
    }
  }

  // 4. Sharing State
  if (telemetry.workerState === 'RUNNING_USB' || telemetry.workerState === 'RUNNING_WIFI') {
    checks.push({
      id: 'worker_state',
      title: 'Sharing Service',
      statusText: 'Active & listening for tasks',
      plainEnglishTip: 'Your device is online and ready whenever your computer loads a workload.',
      severity: 'OK',
    });
  } else if (telemetry.workerState === 'THROTTLED_THERMAL') {
    checks.push({
      id: 'worker_state',
      title: 'Sharing Service',
      statusText: 'Paused automatically to cool down',
      plainEnglishTip:
        'Safety guard paused sharing due to thermal or low battery limits. It resumes after cooling.',
      severity: 'WARNING',
    });
  } else if (telemetry.workerState === 'SHEDDING_MEMORY') {
    checks.push({
      id: 'worker_state',
      title: 'Sharing Service',
      statusText: 'Paused to protect system memory',
      plainEnglishTip:
        'Emergency SHED_LAYERS freed memory safely. Click below to re-tune and resume.',
      severity: 'ISSUE',
      fixAction: 'FREE_MEMORY_AND_RESTART',
    });
  } else {
    checks.push({
      id: 'worker_state',
      title: 'Sharing Service',
      statusText: 'Currently turned off',
      plainEnglishTip: "Click 'Start Sharing Now' so your computer can register this node.",
      severity: 'WARNING',
      fixAction: 'START_SHARING',
    });
  }

  // 5. Battery & Thermal
  const lowUnplugged = !telemetry.isPluggedIn && telemetry.batteryPercent < 20;
  const tooHot = telemetry.temperatureCelsius >= 41;
  if (lowUnplugged || tooHot) {
    checks.push({
      id: 'battery_thermal',
      title: 'Battery & Temperature',
      statusText: `${telemetry.batteryPercent}% • ${telemetry.temperatureCelsius.toFixed(1)}°C`,
      plainEnglishTip: 'Plug in a charger or let the device cool slightly for maximum speed.',
      severity: 'WARNING',
    });
  } else {
    checks.push({
      id: 'battery_thermal',
      title: 'Battery & Temperature',
      statusText: `${telemetry.batteryPercent}%${telemetry.isPluggedIn ? ' (Charging/AC)' : ''} • ${telemetry.temperatureCelsius.toFixed(1)}°C`,
      plainEnglishTip: 'Battery and temperature are in the ideal comfort zone.',
      severity: 'OK',
    });
  }

  // 6. Memory Headroom
  if (telemetry.availableRamMb < 768) {
    checks.push({
      id: 'memory_headroom',
      title: 'Free Device Memory',
      statusText: `Low free RAM (${telemetry.availableRamMb} MB)`,
      plainEnglishTip: 'Close unused browser tabs or apps so this node can share memory smoothly.',
      severity: 'WARNING',
      fixAction: 'RECALIBRATE_DEVICE',
    });
  } else {
    checks.push({
      id: 'memory_headroom',
      title: 'Free Device Memory',
      statusText: `${telemetry.safeCapMb} MB safely reserved (${telemetry.availableRamMb} MB free)`,
      plainEnglishTip: 'Plenty of free memory available. ClusterLab always stays <= 50% physical RAM.',
      severity: 'OK',
    });
  }

  const issueCount = checks.filter((c) => c.severity === 'ISSUE').length;
  const warnCount = checks.filter((c) => c.severity === 'WARNING').length;
  const overallStatus: DiagnosticSeverity =
    issueCount > 0 ? 'ISSUE' : warnCount > 0 ? 'WARNING' : 'OK';

  const headline =
    overallStatus === 'OK'
      ? 'Everything Looks Great!'
      : overallStatus === 'WARNING'
        ? `Almost Ready (${warnCount} Quick Tip${warnCount === 1 ? '' : 's'})`
        : `Found ${issueCount} Issue${issueCount === 1 ? '' : 's'} to Fix`;

  const subtext =
    overallStatus === 'OK'
      ? 'Your device is auto-calibrated, healthy, and ready for your cluster.'
      : overallStatus === 'WARNING'
        ? 'Follow the highlighted tip below or click a 1-click fix button.'
        : 'Click the 1-click fix button on the red item below to resolve it automatically.';

  return {
    overallStatus,
    headline,
    subtext,
    checks,
    ranAtLabel: new Date().toTimeString().slice(0, 8),
  };
}

export function createInitialTelemetryState(developerModeEnabled: boolean): DeviceTelemetryState {
  const hw = probeWebHardware();
  const secret = 'clusterd-default-hmac-key';
  return {
    deviceModel: hw.deviceModel,
    platformLabel: hw.platformLabel,
    totalRamMb: hw.totalRamMb,
    availableRamMb: hw.availableRamMb,
    safeCapMb: hw.safeCapMb,
    bigCoreCount: hw.bigCores,
    totalCoreCount: hw.totalCores,
    cpuFeatures: hw.webglSupported ? 'webgl-fp16, wasm-simd' : 'wasm-simd, js-f32',
    webglProbePassed: hw.webglSupported,
    webglRendererName: hw.webglRenderer,
    activeBackend: hw.activeBackend,
    batteryPercent: 100,
    isPluggedIn: true,
    temperatureCelsius: 31.0,
    thermalStatusLabel: 'THERMAL_STATUS_NONE',
    workerState: 'STOPPED',
    usbLoopbackOnly: true,
    bindAddress: '127.0.0.1:50052',
    clusterSecret: secret,
    activeLayersAssigned: 0,
    handshakesCompleted: 0,
    lastTrimLevel: 0,
    pairedMasterAddress: 'Not Paired (USB 127.0.0.1:50052 Ready)',
    isScanningMaster: false,
    pairingQrPayload: `clusterd://pair?master=127.0.0.1:8080&rpc=50052&secret=${secret}`,
    calibration: {
      isCalibrated: false,
      isCalibrating: false,
      calibratedAtLabel: 'Not calibrated yet',
      performanceScore: 0,
      tierLabel: 'Auto-Tuning...',
      recommendedSafeRamMb: hw.safeCapMb,
      recommendedThreads: hw.bigCores,
      recommendedBackendLabel: hw.webglSupported ? 'Graphics Boost (WebGL)' : 'Smart CPU Mode',
      estimatedLayerCapacity: Math.max(2, Math.min(24, Math.floor(hw.safeCapMb / 110))),
      benchmarkDurationMs: 0,
      summarySentence: 'Click Re-Tune to optimize memory and speed for this device.',
    },
    diagnosticReport: null,
    isDiagnosing: false,
    developerModeEnabled,
    legacyHardwareMode: hw.legacyHardware,
    legacyGraphicsMode: hw.legacyGraphics,
    graphicsTierLabel: hw.webglSupported ? 'WebGL Hardware' : '2D Safe Canvas',
    authTokenHex: computeClusterAuthTokenHex(secret),
    eventLogs: [`[${new Date().toTimeString().slice(0, 8)}] Node worker ready (${hw.platformLabel})`],
  };
}
