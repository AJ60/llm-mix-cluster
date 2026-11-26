// Connect Node / Worker Types
export type ComputeBackend =
  | 'WEBGL_FP16'
  | 'WASM_SIMD'
  | 'JS_BASELINE';

export type WorkerRunState =
  | 'STOPPED'
  | 'PROBING'
  | 'RUNNING_USB'
  | 'RUNNING_WIFI'
  | 'THROTTLED_THERMAL'
  | 'SHEDDING_MEMORY';

export interface CalibrationProfile {
  isCalibrated: boolean;
  isCalibrating: boolean;
  calibratedAtLabel: string;
  performanceScore: number;
  tierLabel: string;
  recommendedSafeRamMb: number;
  recommendedThreads: number;
  recommendedBackendLabel: string;
  estimatedLayerCapacity: number;
  benchmarkDurationMs: number;
  summarySentence: string;
}

export type DiagnosticSeverity = 'OK' | 'WARNING' | 'ISSUE';

export type AutoFixAction =
  | 'AUTO_DETECT_MASTER'
  | 'RECALIBRATE_DEVICE'
  | 'START_SHARING'
  | 'SWITCH_TO_USB_SAFE_MODE'
  | 'FREE_MEMORY_AND_RESTART';

export interface DiagnosticCheck {
  id: string;
  title: string;
  statusText: string;
  plainEnglishTip: string;
  severity: DiagnosticSeverity;
  fixAction?: AutoFixAction;
}

export interface DiagnosticReport {
  overallStatus: DiagnosticSeverity;
  headline: string;
  subtext: string;
  checks: DiagnosticCheck[];
  ranAtLabel: string;
}

export interface DeviceTelemetryState {
  deviceModel: string;
  platformLabel: string;
  totalRamMb: number;
  availableRamMb: number;
  safeCapMb: number;
  bigCoreCount: number;
  totalCoreCount: number;
  cpuFeatures: string;
  webglProbePassed: boolean;
  webglRendererName: string;
  activeBackend: ComputeBackend;
  batteryPercent: number;
  isPluggedIn: boolean;
  temperatureCelsius: number;
  thermalStatusLabel: string;
  workerState: WorkerRunState;
  usbLoopbackOnly: boolean;
  bindAddress: string;
  clusterSecret: string;
  activeLayersAssigned: number;
  handshakesCompleted: number;
  lastTrimLevel: number;
  pairedMasterAddress: string;
  isScanningMaster: boolean;
  pairingQrPayload: string;
  calibration: CalibrationProfile;
  diagnosticReport: DiagnosticReport | null;
  isDiagnosing: boolean;
  developerModeEnabled: boolean;
  legacyHardwareMode: boolean;
  legacyGraphicsMode: boolean;
  graphicsTierLabel: string;
  authTokenHex: string;
  eventLogs: string[];
}
