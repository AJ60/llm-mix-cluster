package com.example.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.Process as AndroidProcess
import android.os.StatFs
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.max
import kotlin.math.min

enum class ComputeBackend(val label: String) {
    VULKAN_FP16("Vulkan 1.2+ Compute (VK_KHR_16bit_storage)"),
    ARM_V8_2_DOTPROD("ARMv8.2+ NEON DotProd / i8mm"),
    ARM_V8_0_NEON("ARMv8.0-A NEON (Multi-Threaded CPU)")
}

enum class WorkerRunState(val badge: String) {
    STOPPED("STOPPED"),
    PROBING("PROBING HARDWARE"),
    RUNNING_USB("ONLINE (USB :50055)"),
    RUNNING_WIFI("ONLINE (Wi-Fi :50055)"),
    THROTTLED_THERMAL("PAUSED (THERMAL / BATTERY)"),
    SHEDDING_MEMORY("EMERGENCY SHED_LAYERS (LMKD GUARD)")
}

enum class ThermalScalingTier(val label: String, val policyCode: String) {
    BOOST_COOL("BOOST (<40°C)", "performance"),
    BALANCED_WARM("BALANCED (40–48°C)", "balanced"),
    THROTTLE_HOT("THROTTLED (>48°C)", "throttle"),
    SHED_LAYERS_CRITICAL("SHED LAYERS (>=52°C)", "shed_layers")
}

data class ThermalScalingDecision(
    val tier: ThermalScalingTier,
    val targetThreads: Int,
    val contextWindowTokens: Int,
    val loadAllocationScale: Float,
    val safeRamCapPercent: Int,
    val threadNicePriority: Int,
    val shouldShedLayers: Boolean,
    val allowGpuOffload: Boolean,
    val reason: String
)

data class LayerSheddingPlan(
    val activeLayersAssigned: Int,
    val assignedLayerStart: Int,
    val assignedLayerEnd: Int,
    val assignedRamMb: Long,
    val layersShedCount: Int,
    val didShed: Boolean
)

/**
 * Dynamic thermal-aware scaling policy for the Android RPC worker:
 * - Below 40.0°C: elevates thread priority (nice -8), boosts performance thread budget,
 *   full 4096-token context window, and 100% safe load allocation.
 * - 40.0°C .. 48.0°C: balanced thermal sustain stepping threads, context window (3072 -> 2048),
 *   and load allocation (0.85 -> 0.70) to avoid overheating.
 * - Above 48.0°C internal heat: progressively throttles threads (2 -> 1), drops priority (nice +8/+12),
 *   shrinks context window to 1024 tokens, disables GPU offload, and sheds layers (0.50 -> 0.25).
 * - >= 52.0°C internal heat (or >= 45.0°C battery / <15% unplugged): full layer shed (0.0 load, 0L)
 *   and thermal pause until cooled.
 */
object ThermalScalingPolicy {
    const val COOL_BOOST_THRESHOLD_C = 40.0f
    const val COOL_RESUME_HYSTERESIS_C = 38.5f
    const val INTERNAL_THROTTLE_THRESHOLD_C = 48.0f
    const val CRITICAL_SHED_THRESHOLD_C = 52.0f
    const val BATTERY_CRITICAL_C = 45.0f

    fun evaluate(
        internalHeatC: Float,
        batteryTempC: Float,
        thermalHeadroom: Float = 0.25f,
        batteryPercent: Int = 100,
        isPluggedIn: Boolean = true,
        totalCores: Int = 8,
        bigCores: Int = 4,
        totalRamMb: Long = 8192L,
        legacyHardwareMode: Boolean = false,
        threadOverride: Int = 0,
        previousTier: ThermalScalingTier = ThermalScalingTier.BOOST_COOL
    ): ThermalScalingDecision {
        val safeTotalCores = totalCores.coerceAtLeast(1)
        val safeBigCores = bigCores.coerceIn(1, safeTotalCores)
        val effectiveHeatC = max(internalHeatC, batteryTempC)

        val maxPerfCores = when {
            legacyHardwareMode -> min(safeBigCores, 2)
            safeTotalCores >= 6 -> max(4, min(safeTotalCores - 2, 6))
            else -> max(2, safeBigCores).coerceAtMost(safeTotalCores)
        }
        val boostedThreads = if (threadOverride > 0) {
            threadOverride.coerceIn(1, safeTotalCores)
        } else {
            max(safeBigCores, maxPerfCores).coerceIn(1, max(maxPerfCores, safeBigCores))
        }
        val maxRamPct = if (totalRamMb <= 2048L || legacyHardwareMode) 40 else 50
        val fullContext = if (legacyHardwareMode || totalRamMb <= 3072L) 2048 else 4096

        val lowBatteryUnplugged = batteryPercent < 15 && !isPluggedIn
        val criticalAdpf = !thermalHeadroom.isNaN() && thermalHeadroom >= 1.0f && effectiveHeatC >= INTERNAL_THROTTLE_THRESHOLD_C
        val criticalHeat = effectiveHeatC >= CRITICAL_SHED_THRESHOLD_C || batteryTempC >= BATTERY_CRITICAL_C

        // 1. Critical heat / battery -> emergency full layer shed (0L)
        if (lowBatteryUnplugged || criticalHeat || criticalAdpf) {
            val why = when {
                lowBatteryUnplugged -> "Unplugged battery ${batteryPercent}% (<15%) — shedding all layers"
                batteryTempC >= BATTERY_CRITICAL_C -> "Battery ${"%.1f".format(batteryTempC)}°C (>=45°C) — emergency layer shed"
                criticalHeat -> "Internal heat ${"%.1f".format(effectiveHeatC)}°C (>=52°C) — emergency layer shed"
                else -> "ADPF thermal saturation (${"%.2f".format(thermalHeadroom)}) at ${"%.1f".format(effectiveHeatC)}°C — shedding all layers"
            }
            return ThermalScalingDecision(
                tier = ThermalScalingTier.SHED_LAYERS_CRITICAL,
                targetThreads = 1,
                contextWindowTokens = 512,
                loadAllocationScale = 0.0f,
                safeRamCapPercent = 25,
                threadNicePriority = AndroidProcess.THREAD_PRIORITY_LOWEST,
                shouldShedLayers = true,
                allowGpuOffload = false,
                reason = why
            )
        }

        // 2. Internal heat exceeds 48°C -> progressive throttle & partial layer shedding
        if (effectiveHeatC > INTERNAL_THROTTLE_THRESHOLD_C) {
            val severeBand = effectiveHeatC >= 50.0f
            val throttledThreads = if (severeBand) {
                1
            } else {
                min(2, boostedThreads).coerceAtLeast(1)
            }
            val loadScale = if (severeBand) 0.25f else 0.50f
            val ramPct = min(maxRamPct, if (severeBand) 30 else 35)
            val nice = if (severeBand) 12 else 8
            return ThermalScalingDecision(
                tier = ThermalScalingTier.THROTTLE_HOT,
                targetThreads = throttledThreads,
                contextWindowTokens = 1024,
                loadAllocationScale = loadScale,
                safeRamCapPercent = ramPct,
                threadNicePriority = nice,
                shouldShedLayers = true,
                allowGpuOffload = false,
                reason = "Internal heat ${"%.1f".format(effectiveHeatC)}°C (>48°C) — throttling to $throttledThreads thread(s), 1024 ctx, ${(loadScale * 100).toInt()}% load"
            )
        }

        // 3. Below 40°C (with hysteresis when stepping back up from hot tiers) -> boost priority, threads, context & load
        val coolResumeCeiling = when (previousTier) {
            ThermalScalingTier.THROTTLE_HOT, ThermalScalingTier.SHED_LAYERS_CRITICAL -> COOL_RESUME_HYSTERESIS_C
            ThermalScalingTier.BALANCED_WARM -> 39.0f
            ThermalScalingTier.BOOST_COOL -> COOL_BOOST_THRESHOLD_C
        }
        if (effectiveHeatC < coolResumeCeiling) {
            return ThermalScalingDecision(
                tier = ThermalScalingTier.BOOST_COOL,
                targetThreads = boostedThreads,
                contextWindowTokens = fullContext,
                loadAllocationScale = 1.0f,
                safeRamCapPercent = maxRamPct,
                threadNicePriority = -8,
                shouldShedLayers = false,
                allowGpuOffload = !legacyHardwareMode,
                reason = "Cool (${"%.1f".format(effectiveHeatC)}°C < 40°C) — boosted priority (nice -8), $boostedThreads threads, $fullContext ctx, 100% load"
            )
        }

        // 4. Warm balanced band (40°C .. 48°C) -> progressive step-down before 48°C threshold
        val upperWarmBand = effectiveHeatC >= 44.0f
        val balancedThreads = if (upperWarmBand) {
            max(2, boostedThreads - 2).coerceAtMost(boostedThreads)
        } else {
            max(2, boostedThreads - 1).coerceAtMost(boostedThreads)
        }
        val balancedCtx = if (upperWarmBand || legacyHardwareMode) 2048 else min(fullContext, 3072)
        val balancedScale = if (upperWarmBand) 0.70f else 0.85f
        val balancedRamPct = min(maxRamPct, if (upperWarmBand) 40 else 45)
        val balancedNice = if (upperWarmBand) 4 else 0
        return ThermalScalingDecision(
            tier = ThermalScalingTier.BALANCED_WARM,
            targetThreads = balancedThreads,
            contextWindowTokens = balancedCtx,
            loadAllocationScale = balancedScale,
            safeRamCapPercent = balancedRamPct,
            threadNicePriority = balancedNice,
            shouldShedLayers = false,
            allowGpuOffload = !legacyHardwareMode && !upperWarmBand,
            reason = "Warm (${"%.1f".format(effectiveHeatC)}°C) — balanced $balancedThreads threads, $balancedCtx ctx, ${(balancedScale * 100).toInt()}% load"
        )
    }

    /**
     * Computes deterministic automatic layer-shedding when temperature exceeds 48.0°C (THROTTLE_HOT)
     * or hits emergency/RAM critical ceilings (SHED_LAYERS_CRITICAL):
     * - Normal (<40°C) and balanced (40..48°C): preserves all assigned layers (didShed = false).
     * - Hot (>48°C, scale 0.50..0.25): immediately sheds >=50%..75% of assigned layers (at least 1 layer).
     * - Critical (>=52°C or scale <= 0.0): sheds 100% of assigned layers (0L).
     */
    fun computeAutomaticLayerShedding(
        currentLayers: Int,
        layerStart: Int,
        layerEnd: Int,
        assignedRamMb: Long,
        decision: ThermalScalingDecision
    ): LayerSheddingPlan {
        val safeLayers = currentLayers.coerceAtLeast(0)
        if (!decision.shouldShedLayers || safeLayers <= 0) {
            return LayerSheddingPlan(
                activeLayersAssigned = safeLayers,
                assignedLayerStart = if (safeLayers > 0 && layerStart >= 0) layerStart else -1,
                assignedLayerEnd = if (safeLayers > 0 && layerEnd >= layerStart) layerEnd else -1,
                assignedRamMb = if (safeLayers > 0) assignedRamMb.coerceAtLeast(0L) else 0L,
                layersShedCount = 0,
                didShed = false
            )
        }
        if (decision.tier == ThermalScalingTier.SHED_LAYERS_CRITICAL || decision.loadAllocationScale <= 0.0f) {
            return LayerSheddingPlan(
                activeLayersAssigned = 0,
                assignedLayerStart = -1,
                assignedLayerEnd = -1,
                assignedRamMb = 0L,
                layersShedCount = safeLayers,
                didShed = true
            )
        }
        val scaledKeep = (safeLayers * decision.loadAllocationScale).toInt()
        val retainedLayers = min(safeLayers - 1, scaledKeep).coerceAtLeast(0)
        val shedCount = (safeLayers - retainedLayers).coerceAtLeast(0)
        if (retainedLayers <= 0) {
            return LayerSheddingPlan(
                activeLayersAssigned = 0,
                assignedLayerStart = -1,
                assignedLayerEnd = -1,
                assignedRamMb = 0L,
                layersShedCount = safeLayers,
                didShed = shedCount > 0
            )
        }
        val safeStart = if (layerStart >= 0) layerStart else 0
        val newEnd = safeStart + retainedLayers - 1
        val newRamMb = max(0L, (assignedRamMb.coerceAtLeast(0L) * retainedLayers.toLong()) / safeLayers.toLong())
        return LayerSheddingPlan(
            activeLayersAssigned = retainedLayers,
            assignedLayerStart = safeStart,
            assignedLayerEnd = newEnd,
            assignedRamMb = newRamMb,
            layersShedCount = shedCount,
            didShed = shedCount > 0
        )
    }
}

data class DeviceTelemetryState(
    val apiLevel: Int = Build.VERSION.SDK_INT,
    val releaseName: String = Build.VERSION.RELEASE ?: "Android",
    val deviceModel: String = "${Build.MANUFACTURER} ${Build.MODEL}",
    val socHardware: String = Build.HARDWARE ?: "arm64-v8a",
    val totalRamMb: Long = 0L,
    val availableRamMb: Long = 0L,
    val safeCapMb: Long = 0L,
    val bigCoreCount: Int = 4,
    val totalCoreCount: Int = Runtime.getRuntime().availableProcessors(),
    val threadOverride: Int = 0,
    val safeRamPercentOverride: Int = 0,
    val cpuFeatures: String = "asimd",
    val hasDotProd: Boolean = false,
    val hasI8mm: Boolean = false,
    val vulkanProbePassed: Boolean = false,
    val hasVulkan16BitStorage: Boolean = false,
    val vulkanApiVersion: String = "None",
    val vulkanDeviceName: String = "Not Probed",
    val activeBackend: ComputeBackend = ComputeBackend.ARM_V8_0_NEON,
    val batteryPercent: Int = 100,
    val isPluggedIn: Boolean = true,
    val temperatureCelsius: Float = 31.0f,
    val internalHeatCelsius: Float = 31.0f,
    val thermalSensorSource: String = "battery",
    val thermalHeadroom: Float = 0.25f,
    val thermalStatusLabel: String = "THERMAL_STATUS_NONE",
    val thermalScalingTier: ThermalScalingTier = ThermalScalingTier.BOOST_COOL,
    val targetContextTokens: Int = 4096,
    val loadAllocationScale: Float = 1.0f,
    val threadNicePriority: Int = -8,
    val shouldShedLayers: Boolean = false,
    val layersShedCount: Int = 0,
    val workerState: WorkerRunState = WorkerRunState.STOPPED,
    val usbLoopbackOnly: Boolean = true,
    val bindAddress: String = "0.0.0.0:50055",
    val clusterSecret: String = "clusterd-default-hmac-key",
    val nativeBinaryPath: String = "",
    val nativeBinaryPresent: Boolean = false,
    val pageAlignment16KbReady: Boolean = true,
    val activeLayersAssigned: Int = 0,
    val assignedLayerStart: Int = -1,
    val assignedLayerEnd: Int = -1,
    val assignedRamMb: Long = 0L,
    val vulkanFallbackActive: Boolean = false,
    val thermalThrottleThreads: Int = 0,
    val handshakesCompleted: Int = 0,
    val lastTrimLevel: Int = 0,
    val pairedMasterAddress: String = "Not Paired (:50055 Ready)",
    val isScanningMaster: Boolean = false,
    val pairingQrPayload: String = "clusterd://pair?master=127.0.0.1:3000&rpc=50055&secret=clusterd-default-hmac-key",
    val calibration: CalibrationProfile = CalibrationProfile(),
    val diagnosticReport: DiagnosticReport? = null,
    val isDiagnosing: Boolean = false,
    val developerModeEnabled: Boolean = false,
    val legacyHardwareMode: Boolean = false,
    val legacyGraphicsMode: Boolean = false,
    val graphicsTierLabel: String = "Auto",
    val tempSlopeCPerMin: Float = 0.0f,
    val thermalPressureScore: Float = 0.0f,
    val ramPressureScore: Float = 0.0f,
    val masterAdaptiveLoadFactor: Float = 1.0f,
    val masterRecommendedContextTokens: Int = 0,
    val masterThreadCap: Int = 0,
    val loadBalancerReason: String = "Nominal",
    val eventLogs: List<String> = emptyList()
)

class RpcService : Service(), ComponentCallbacks2 {

    companion object {
        internal fun readFully(input: InputStream, buffer: ByteArray): Boolean {
            var total = 0
            while (total < buffer.size) {
                val read = input.read(buffer, total, buffer.size - total)
                if (read < 0) return false
                total += read
            }
            return true
        }

        const val DEFAULT_RPC_PORT = 50055
        const val CHANNEL_ID = "clusterd_worker_channel"
        const val NOTIFICATION_ID = 50055
        const val ACTION_START_WORKER = "com.example.service.action.START_WORKER"
        const val ACTION_STOP_FREE_RAM = "com.example.service.action.STOP_FREE_RAM"
        const val ACTION_SIMULATE_TRIM = "com.example.service.action.SIMULATE_TRIM"
        const val ACTION_TOGGLE_BIND_MODE = "com.example.service.action.TOGGLE_BIND_MODE"
        const val ACTION_AUTO_DETECT_MASTER = "com.example.service.action.AUTO_DETECT_MASTER"
        const val ACTION_PAIR_QR_URI = "com.example.service.action.PAIR_QR_URI"
        const val ACTION_AUTO_CALIBRATE = "com.example.service.action.AUTO_CALIBRATE"
        const val ACTION_RUN_DIAGNOSTICS = "com.example.service.action.RUN_DIAGNOSTICS"
        const val EXTRA_USB_ONLY = "extra_usb_only"
        const val EXTRA_CLUSTER_SECRET = "extra_cluster_secret"
        const val EXTRA_FORCE_EMBEDDED_RPC = "extra_force_embedded_rpc"
        const val EXTRA_RPC_PORT = "extra_rpc_port"
        const val EXTRA_QR_URI = "extra_qr_uri"

        const val FLAG_VULKAN_FP16 = 1 shl 0
        const val FLAG_ARM_DOTPROD = 1 shl 1
        const val FLAG_ELF_16KB_ALIGNED = 1 shl 2
        const val FLAG_SBC_CORTEX_A72 = 1 shl 3
        const val FLAG_PLATFORM_WINDOWS = 1 shl 4
        const val FLAG_PLATFORM_LINUX = 1 shl 5
        const val FLAG_PLATFORM_ANDROID = 1 shl 6
        const val FLAG_PLATFORM_MACOS = 1 shl 7
        const val FLAG_LEGACY_HARDWARE = 1 shl 8
        const val FLAG_LEGACY_GRAPHICS = 1 shl 9

        val HANDSHAKE_CHALLENGE: ByteArray = "GGUF_PROBE_V10".toByteArray(StandardCharsets.US_ASCII)
        val REPLY_CHALLENGE_V10: ByteArray = "GGUF_REPLY_V10".toByteArray(StandardCharsets.US_ASCII)
        val HANDSHAKE_MAGIC_V10: ByteArray = "NONC".toByteArray(StandardCharsets.US_ASCII)

        private val _telemetry = MutableStateFlow(DeviceTelemetryState())
        val telemetry: StateFlow<DeviceTelemetryState> = _telemetry.asStateFlow()

        @Volatile
        private var activeService: RpcService? = null

        /**
         * Bit-exact Kotlin mirror of Rust `clusterd::protocol::compute_cluster_auth_token`.
         * Produces identical 32-byte auth tokens across Windows, Linux, macOS, and Android.
         */
        @OptIn(ExperimentalUnsignedTypes::class)
        fun computeClusterAuthToken(
            secret: String,
            challenge: ByteArray = HANDSHAKE_CHALLENGE
        ): ByteArray {
            val state = ulongArrayOf(
                0x6a09e667f3bcc908UL,
                0xbb67ae8584caa73bUL,
                0x3c6ef372fe94f82bUL,
                0xa54ff53a5f1d36f1UL
            )
            val secretBytes = secret.toByteArray(StandardCharsets.UTF_8)
            val totalLen = secretBytes.size + challenge.size
            for (i in 0 until totalLen) {
                val b = if (i < secretBytes.size) secretBytes[i] else challenge[i - secretBytes.size]
                val uByte = (b.toInt() and 0xFF).toULong()
                val lane = i and 3
                val nextLane = (lane + 1) and 3
                val mixed = (state[lane] + (uByte * 0x9e3779b97f4a7c15UL)).rotateLeft(13)
                state[lane] = mixed xor state[nextLane]
            }
            for (round in 0 until 8) {
                for (lane in 0 until 4) {
                    state[lane] = ((state[lane] * 0xbf58476d1ce4e5b9UL) + round.toULong()).rotateRight(17)
                }
            }
            val buf = ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN)
            for (word in state) {
                buf.putLong(word.toLong())
            }
            return buf.array()
        }


        /** Decodes a v10 nonce-bound request; returns the 8-byte nonce only when the MAC verifies. */
        fun decodeV10Request(secret: String, frame: ByteArray): ByteArray? {
            if (frame.size < 32) return null
            if (!frame.copyOfRange(0, 4).contentEquals(HANDSHAKE_MAGIC_V10)) return null
            val nonce = frame.copyOfRange(4, 12)
            val expected = computeClusterAuthToken(secret, HANDSHAKE_CHALLENGE + nonce)
            var diff = 0
            for (i in 0 until 20) {
                diff = diff or ((expected[i].toInt() and 0xFF) xor (frame[12 + i].toInt() and 0xFF))
            }
            return if (diff == 0) nonce else null
        }

        private fun replyMacV10(secret: String, nonce: ByteArray, cap: Int, cores: Int, flags: Int, status: Int): ByteArray {
            val data = REPLY_CHALLENGE_V10 + nonce +
                ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(cap).array() +
                byteArrayOf(cores.toByte()) +
                ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(flags.toShort()).array() +
                byteArrayOf(status.toByte())
            val token = computeClusterAuthToken(secret, data)
            return byteArrayOf(
                (token[8].toInt() xor token[24].toInt()).toByte(),
                (token[9].toInt() xor token[25].toInt()).toByte(),
                (token[10].toInt() xor token[26].toInt()).toByte(),
                (token[11].toInt() xor token[27].toInt()).toByte(),
                (token[12].toInt() xor token[28].toInt()).toByte(),
                (token[13].toInt() xor token[29].toInt()).toByte(),
                (token[14].toInt() xor token[30].toInt()).toByte(),
                (token[15].toInt() xor token[31].toInt()).toByte()
            )
        }

        fun buildV10Reply(secret: String, nonce: ByteArray, cap: Int, cores: Int, flags: Int, status: Int): ByteArray {
            val out = ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN)
            out.put("GGUF".toByteArray(StandardCharsets.US_ASCII))
            out.putInt(10)
            out.put(nonce)
            out.put(replyMacV10(secret, nonce, cap, cores, flags, status))
            out.putInt(cap)
            val packed = (status and 0xFF) or ((flags and 0xFFFF) shl 8) or ((cores and 0xFF) shl 24)
            out.putInt(packed)
            return out.array()
        }

        /** Constant-time 32-byte comparison matching Rust `verify_cluster_auth_token`. */
        fun verifyClusterAuthToken(secret: String, received: ByteArray): Boolean {
            if (received.size < 32) return false
            val expected = computeClusterAuthToken(secret, HANDSHAKE_CHALLENGE)
            var diff = 0
            for (i in 0 until 32) {
                diff = diff or ((expected[i].toInt() and 0xFF) xor (received[i].toInt() and 0xFF))
            }
            return diff == 0
        }

        fun setDeveloperMode(enabled: Boolean) {
            _telemetry.update { it.copy(developerModeEnabled = enabled) }
            appendLog(if (enabled) "Developer Mode enabled" else "Switched back to Simple Mode")
        }

        fun applyEngineOverrides(threads: Int, safeRamPercent: Int, restartWorkerIfRunning: Boolean = true) {
            val pct = safeRamPercent.coerceIn(25, 50)
            _telemetry.update { state ->
                val boundedThreads = if (threads > 0) {
                    threads.coerceIn(1, max(1, state.totalCoreCount))
                } else {
                    state.calibration.recommendedThreads.coerceIn(1, max(1, state.totalCoreCount))
                }
                val total = if (state.totalRamMb > 0L) state.totalRamMb else 4096L
                // Strict Zero-OOM guarantee: strictly <= 50% physical RAM (leaving remaining 50% / >1.8GB for Android OS)
                val physicalCeiling = min(total / 2L, max(256L, (total * pct) / 100L))
                val boundedRam = physicalCeiling
                val layers = (boundedRam / 110L).toInt().coerceIn(2, 28)
                state.copy(
                    threadOverride = if (threads > 0) boundedThreads else 0,
                    safeRamPercentOverride = pct,
                    bigCoreCount = boundedThreads,
                    safeCapMb = boundedRam,
                    activeLayersAssigned = if (state.workerState == WorkerRunState.RUNNING_USB || state.workerState == WorkerRunState.RUNNING_WIFI) layers else state.activeLayersAssigned
                )
            }

            activeService?.let { service ->
                val curState = _telemetry.value.workerState
                if (restartWorkerIfRunning && (curState == WorkerRunState.RUNNING_USB || curState == WorkerRunState.RUNNING_WIFI)) {
                    val usbOnly = _telemetry.value.usbLoopbackOnly
                    val secret = _telemetry.value.clusterSecret
                    appendLog("Applying updated thread budget (${_telemetry.value.bigCoreCount} cores) — reloading RPC server process")
                    service.launchNativeOrEmbeddedRpcServer(usbOnly, secret)
                    val master = service.lastPairedMasterHostPort ?: "127.0.0.1:3000"
                    service.notifyMasterViaUdpOrHttp(master, usbOnly)
                }
            }
        }

        fun syncAssignedLayersFromMaster(
            layers: Int,
            layerStart: Int,
            layerEnd: Int,
            allocatedMb: Long
        ) {
            _telemetry.update { state ->
                val clampedLayers = layers.coerceAtLeast(0)
                if (clampedLayers > 0 && state.shouldShedLayers) {
                    val syntheticDecision = ThermalScalingDecision(
                        tier = state.thermalScalingTier,
                        targetThreads = if (state.thermalThrottleThreads > 0) state.thermalThrottleThreads else state.bigCoreCount,
                        contextWindowTokens = state.targetContextTokens,
                        loadAllocationScale = state.loadAllocationScale,
                        safeRamCapPercent = state.safeRamPercentOverride.takeIf { it in 25..50 } ?: 35,
                        threadNicePriority = state.threadNicePriority,
                        shouldShedLayers = true,
                        allowGpuOffload = false,
                        reason = state.loadBalancerReason
                    )
                    val shedPlan = ThermalScalingPolicy.computeAutomaticLayerShedding(
                        currentLayers = clampedLayers,
                        layerStart = layerStart,
                        layerEnd = layerEnd,
                        assignedRamMb = allocatedMb,
                        decision = syntheticDecision
                    )
                    state.copy(
                        activeLayersAssigned = shedPlan.activeLayersAssigned,
                        assignedLayerStart = shedPlan.assignedLayerStart,
                        assignedLayerEnd = shedPlan.assignedLayerEnd,
                        assignedRamMb = shedPlan.assignedRamMb,
                        layersShedCount = shedPlan.layersShedCount
                    )
                } else {
                    state.copy(
                        activeLayersAssigned = clampedLayers,
                        assignedLayerStart = if (clampedLayers > 0 && layerStart >= 0) layerStart else -1,
                        assignedLayerEnd = if (clampedLayers > 0 && layerEnd >= layerStart) layerEnd else -1,
                        assignedRamMb = if (clampedLayers > 0) allocatedMb.coerceAtLeast(0L) else 0L,
                        layersShedCount = if (clampedLayers == 0 && state.shouldShedLayers) state.layersShedCount else 0
                    )
                }
            }
        }

        fun logEngineEvent(message: String) {
            appendLog(message)
        }

        fun clearEventLogs() {
            _telemetry.update { it.copy(eventLogs = emptyList()) }
        }

        /**
         * Runs the fast on-device auto-calibration benchmark and locks safe RAM cap &
         * optimal performance thread count automatically for the user.
         */
        fun runAutoCalibration(context: Context): CalibrationProfile {
            _telemetry.update {
                it.copy(calibration = it.calibration.copy(isCalibrating = true))
            }
            val startupSnap = initializeStartupBackend(context = context, forceRefresh = false)
            val profile = NodeAutoCalibrator.runCalibration(
                context = context,
                preProbedVk = startupSnap.vulkan
            )
            _telemetry.update { state ->
                val override = state.threadOverride
                val cores = if (override > 0) {
                    override.coerceIn(1, max(1, state.totalCoreCount))
                } else {
                    profile.recommendedThreads
                }
                val ramCap = if (state.safeRamPercentOverride in 25..50 && state.totalRamMb > 0L) {
                    max(256L, (state.totalRamMb * state.safeRamPercentOverride) / 100L)
                } else {
                    profile.recommendedSafeRamMb
                }
                state.copy(
                    calibration = profile,
                    safeCapMb = ramCap,
                    bigCoreCount = cores
                )
            }
            appendLog(
                "Auto-Calibrated: ${profile.tierLabel} (Score ${profile.performanceScore}/100, " +
                    "SafeRAM=${profile.recommendedSafeRamMb}MB, Threads=${profile.recommendedThreads}, ${profile.benchmarkDurationMs}ms)"
            )
            return profile
        }

        /**
         * Runs the live zero-jargon troubleshooting diagnostics suite (network, PC reachability,
         * sharing status, memory, thermal/battery, and calibration) and publishes a fixable report.
         */
        fun runAutoDiagnostics(context: Context): DiagnosticReport {
            _telemetry.update { it.copy(isDiagnosing = true) }
            refreshStaticHardwareSnapshot(context)
            val report = NodeAutoDiagnoser.runDiagnostics(context, _telemetry.value)
            _telemetry.update {
                it.copy(
                    isDiagnosing = false,
                    diagnosticReport = report
                )
            }
            appendLog("Auto-Diagnosis: ${report.headline} (${report.checks.size} checks)")
            return report
        }

        /** Detects the Android device's active Wi-Fi LAN IPv4 address via zero-packet UDP route lookup. */
        fun detectLocalLanIpv4(targetHost: String = "192.168.1.1"): String {
            return runCatching {
                java.net.DatagramSocket().use { sock ->
                    sock.connect(java.net.InetAddress.getByName(targetHost), 80)
                    val ip = sock.localAddress?.hostAddress
                    if (!ip.isNullOrBlank() && ip != "0.0.0.0" && !ip.contains(":")) ip else "127.0.0.1"
                }
            }.getOrDefault("127.0.0.1")
        }

        /**
         * Parses a QR pairing string (`clusterd://pair?master=192.168.1.10:8080&rpc=50052&secret=xyz`
         * or a raw `IP:Port`) into `(masterAddress, secret, usbLoopbackOnly)`.
         */
        fun parseQrPairingPayload(raw: String): Triple<String, String, Boolean>? {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) return null
            if (trimmed.startsWith("clusterd://", ignoreCase = true)) {
                val query = trimmed.substringAfter("?", "")
                var master = "127.0.0.1:3000"
                var secret = "clusterd-default-hmac-key"
                for (part in query.split("&")) {
                    val kv = part.split("=", limit = 2)
                    if (kv.size == 2) {
                        when (kv[0].lowercase()) {
                            "master", "host" -> master = kv[1]
                            "secret", "token" -> secret = kv[1]
                        }
                    }
                }
                val isUsb = master.startsWith("127.0.0.1") || master.startsWith("localhost")
                return Triple(master, secret, isUsb)
            }
            // Fallback for plain IP or IP:Port entered or scanned
            val hostPort = if (trimmed.contains(":")) trimmed else "$trimmed:3000"
            val isUsb = hostPort.startsWith("127.0.0.1") || hostPort.startsWith("localhost")
            return Triple(hostPort, "clusterd-default-hmac-key", isUsb)
        }

        /**
         * Explicit RpcService startup probe & backend selector:
         * - Probes `VK_KHR_16bit_storage`, `storageBuffer16BitAccess`, `maxPushConstantsSize`,
         *   dEQP conformance, and persistent driver stability verdicts via `RpcInitializer`.
         * - When `VK_KHR_16bit_storage` is unavailable or drivers are unreliable, immediately
         *   locks `vulkanFallbackActive = true` and forces the NEON-optimized CPU backend.
         */
        fun initializeStartupBackend(
            context: Context,
            nativeBinary: File? = null,
            libDir: String? = null,
            forceRefresh: Boolean = false
        ): RpcStartupProbeSnapshot {
            val mem = HardwareProbe.readProcMemInfo()
            val nativeDir = context.applicationInfo.nativeLibraryDir ?: ""
            val candidateExecs = listOf(
                File(nativeDir, "librpc_server_exec.so"),
                File(context.filesDir, "librpc_server_exec.so")
            )
            val execFile = nativeBinary
                ?: candidateExecs.firstOrNull { it.exists() && (it.canExecute() || it.name.endsWith(".so")) }
            val probeFile = execFile ?: File(nativeDir, "librpc_server_exec.so")
            val resolvedLibDir = libDir ?: "${probeFile.parentFile?.absolutePath ?: nativeDir}:$nativeDir"

            val priorState = _telemetry.value
            val provisionalLegacy = HardwareProbe.detectLegacyHardwareProfile(
                context = context,
                memInfo = mem,
                vkInfo = priorState.let {
                    HardwareProbe.VulkanProbeResult(
                        supported = it.vulkanProbePassed,
                        reason = it.vulkanDeviceName,
                        has16BitStorage = it.hasVulkan16BitStorage
                    )
                }
            )
            val startupProbe = RpcInitializer.prepareStartupInitialization(
                context = context,
                activeBin = execFile,
                nativeDir = nativeDir,
                legacyHardwareMode = provisionalLegacy.legacyHardwareMode,
                vulkanFallbackActive = priorState.vulkanFallbackActive,
                forceRefresh = forceRefresh
            )
            val cpu = startupProbe.cpu
            val vk = startupProbe.vulkan
            val legacy = HardwareProbe.detectLegacyHardwareProfile(context, mem, vk)
            val battery = HardwareProbe.readBatteryAndThermal(context)

            _telemetry.update { current ->
                val lbDecision = DynamicLoadBalancer.evaluate(
                    LoadBalanceInput(
                        internalHeatC = battery.internalHeatCelsius,
                        batteryTempC = battery.tempCelsius,
                        tempSlopeCPerMin = current.tempSlopeCPerMin,
                        thermalHeadroom = battery.thermalHeadroom,
                        thermalStatusLabel = battery.thermalStatusLabel,
                        availableRamMb = mem.availableMb,
                        totalRamMb = mem.totalMb,
                        lastTrimLevel = current.lastTrimLevel,
                        batteryPercent = battery.levelPercent,
                        isPluggedIn = battery.isPlugged,
                        totalCores = cpu.totalCoreCount,
                        bigCores = cpu.bigCoreCount,
                        legacyHardwareMode = legacy.legacyHardwareMode,
                        threadOverride = current.threadOverride,
                        safeRamPercentOverride = current.safeRamPercentOverride,
                        masterAdaptiveLoadFactor = current.masterAdaptiveLoadFactor,
                        masterRecommendedContextTokens = current.masterRecommendedContextTokens,
                        masterThreadCap = current.masterThreadCap,
                        previousTier = current.thermalScalingTier
                    )
                )
                val effectiveFallback = current.vulkanFallbackActive ||
                    startupProbe.effectiveFallback ||
                    legacy.legacyHardwareMode ||
                    !vk.supported ||
                    !vk.hasVulkanCompute ||
                    !vk.has16BitStorage ||
                    !vk.driverStable
                val vkReady = startupProbe.vulkanReady &&
                    !effectiveFallback &&
                    lbDecision.allowGpuOffload
                val backend = VulkanComputeProber.selectOptimalComputeBackend(
                    vk = vk,
                    cpu = cpu,
                    legacyHardwareMode = legacy.legacyHardwareMode,
                    vulkanFallbackActive = effectiveFallback || !lbDecision.allowGpuOffload
                )
                val override = current.threadOverride
                val cores = if (override > 0) {
                    override.coerceIn(1, max(1, cpu.totalCoreCount))
                } else if (legacy.legacyHardwareMode) {
                    min(cpu.bigCoreCount, 2)
                } else {
                    cpu.bigCoreCount
                }
                current.copy(
                    totalRamMb = mem.totalMb,
                    availableRamMb = mem.availableMb,
                    safeCapMb = lbDecision.safeRamCapMb,
                    bigCoreCount = cores,
                    totalCoreCount = cpu.totalCoreCount,
                    cpuFeatures = cpu.featuresSummary,
                    hasDotProd = cpu.hasDotProd,
                    hasI8mm = cpu.hasI8mm,
                    vulkanFallbackActive = effectiveFallback,
                    vulkanProbePassed = vkReady,
                    hasVulkan16BitStorage = vk.has16BitStorage && vk.driverStable && !legacy.legacyHardwareMode && !effectiveFallback,
                    vulkanApiVersion = vk.vulkanApiVersion,
                    vulkanDeviceName = vk.reason,
                    activeBackend = backend,
                    batteryPercent = battery.levelPercent,
                    isPluggedIn = battery.isPlugged,
                    temperatureCelsius = battery.tempCelsius,
                    internalHeatCelsius = battery.internalHeatCelsius,
                    thermalSensorSource = battery.sensorSource,
                    thermalHeadroom = battery.thermalHeadroom,
                    thermalStatusLabel = battery.thermalStatusLabel,
                    thermalScalingTier = lbDecision.tier,
                    targetContextTokens = lbDecision.contextWindowTokens,
                    loadAllocationScale = lbDecision.loadAllocationScale,
                    threadNicePriority = lbDecision.threadNicePriority,
                    shouldShedLayers = lbDecision.shouldShedLayers,
                    thermalThrottleThreads = lbDecision.targetThreads,
                    thermalPressureScore = lbDecision.thermalPressureScore,
                    ramPressureScore = lbDecision.ramPressureScore,
                    loadBalancerReason = lbDecision.reason,
                    nativeBinaryPath = probeFile.absolutePath,
                    nativeBinaryPresent = probeFile.exists() && probeFile.canExecute(),
                    legacyHardwareMode = legacy.legacyHardwareMode,
                    legacyGraphicsMode = legacy.legacyGraphicsMode,
                    graphicsTierLabel = legacy.graphicsTierLabel
                )
            }
            return startupProbe
        }

        fun refreshStaticHardwareSnapshot(
            context: Context,
            nativeBinary: File? = null,
            libDir: String? = null
        ) {
            initializeStartupBackend(
                context = context,
                nativeBinary = nativeBinary,
                libDir = libDir,
                forceRefresh = false
            )
        }

        private fun appendLog(message: String) {
            android.util.Log.i("SilicaRpcService", message)
            val timestamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
                .format(java.util.Date())
            _telemetry.update { state ->
                val updated = (listOf("[$timestamp] $message") + state.eventLogs).take(30)
                state.copy(eventLogs = updated)
            }
        }
    }

    inner class LocalBinder : Binder() {
        fun getService(): RpcService = this@RpcService
    }

    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var monitorJob: Job? = null
    private var rpcServerJob: Job? = null
    private var heartbeatJob: Job? = null
    @Volatile private var forceEmbeddedRpc: Boolean = false
    @Volatile private var rpcPortOverride: Int = 0

    private fun effectiveRpcPort(): Int =
        if (rpcPortOverride in 1..65535) rpcPortOverride else DEFAULT_RPC_PORT
    private var nativeProcess: Process? = null
    private var serverSocket: ServerSocket? = null
    private var lastPairedMasterHostPort: String? = null
    private var peakPausedTempCelsius: Float = 0f
    private var cooldownStartedEpochMs: Long = 0L
    @Volatile private var lastRpcReloadEpochMs: Long = 0L
    @Volatile private var lastSampledTempC: Float = Float.NaN
    @Volatile private var lastSampledTempEpochMs: Long = 0L

    private fun buildDeviceWorkerId(): String {
        val raw = "${Build.MANUFACTURER}-${Build.MODEL}-${Build.DEVICE}"
            .lowercase()
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
            .take(28)
        return "android-${raw.ifBlank { "worker" }}"
    }

    override fun onCreate() {
        super.onCreate()
        activeService = this
        createNotificationChannel()
        val (activeBin, nativeDir) = resolveNativeRpcBinary()
        val startupLibDir = activeBin?.parentFile?.absolutePath ?: nativeDir
        val startupProbe = initializeStartupBackend(
            context = this,
            nativeBinary = activeBin,
            libDir = "$startupLibDir:$nativeDir",
            forceRefresh = true
        )
        val snap = _telemetry.value
        appendLog(
            if (startupProbe.vulkanReady && snap.hasVulkan16BitStorage) {
                "RpcService initialized on API ${Build.VERSION.SDK_INT} (${Build.MODEL}) — Vulkan 1.2+ & VK_KHR_16bit_storage verified (${snap.activeBackend.label})"
            } else {
                "RpcService initialized on API ${Build.VERSION.SDK_INT} (${Build.MODEL}) — Vulkan 1.2+ / VK_KHR_16bit_storage unavailable or unstable; forced ${snap.activeBackend.label}"
            }
        )
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.hasExtra(EXTRA_FORCE_EMBEDDED_RPC) == true) {
            forceEmbeddedRpc = intent.getBooleanExtra(EXTRA_FORCE_EMBEDDED_RPC, false)
        }
        if (intent?.hasExtra(EXTRA_RPC_PORT) == true) {
            rpcPortOverride = intent.getIntExtra(EXTRA_RPC_PORT, 0)
        }
        when (intent?.action) {
            ACTION_STOP_FREE_RAM -> {
                appendLog("1-Tap 'Stop & Free RAM' triggered — shedding layers & halting worker.")
                stopWorkerAndFreeRam("User requested Stop & Free RAM")
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_SIMULATE_TRIM -> {
                @Suppress("DEPRECATION")
                onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL)
                return START_STICKY
            }
            ACTION_TOGGLE_BIND_MODE -> {
                val usbOnly = intent.getBooleanExtra(EXTRA_USB_ONLY, true)
                val secret = intent.getStringExtra(EXTRA_CLUSTER_SECRET)
                    ?: _telemetry.value.clusterSecret
                val wasRunning = _telemetry.value.workerState == WorkerRunState.RUNNING_USB ||
                    _telemetry.value.workerState == WorkerRunState.RUNNING_WIFI
                _telemetry.update {
                    it.copy(
                        usbLoopbackOnly = usbOnly,
                        bindAddress = "0.0.0.0:${effectiveRpcPort()}",
                        clusterSecret = secret
                    )
                }
                appendLog(
                    if (usbOnly) {
                        "Switched bind policy to USB ADB Loopback (:50055)"
                    } else {
                        "Switched bind policy to Wi-Fi Proxy with HMAC cluster_secret enforcement (:50055)"
                    }
                )
                if (wasRunning) {
                    launchNativeOrEmbeddedRpcServer(usbOnly, secret)
                    lastPairedMasterHostPort?.let { master ->
                        startMasterHeartbeatLoop(master, usbOnly)
                    }
                }
                return START_STICKY
            }
            ACTION_PAIR_QR_URI -> {
                val rawQr = intent.getStringExtra(EXTRA_QR_URI).orEmpty()
                val parsed = parseQrPairingPayload(rawQr)
                if (parsed != null) {
                    val (masterAddr, secret, isUsb) = parsed
                    _telemetry.update {
                        it.copy(
                            pairedMasterAddress = masterAddr,
                            clusterSecret = secret,
                            usbLoopbackOnly = isUsb,
                            bindAddress = "0.0.0.0:${effectiveRpcPort()}",
                            pairingQrPayload = "clusterd://pair?master=$masterAddr&rpc=${effectiveRpcPort()}&secret=$secret"
                        )
                    }
                    appendLog("Paired via QR with Master $masterAddr (${if (isUsb) "USB Loopback" else "Wi-Fi LAN"})")
                    startForegroundSafely()
                    startWorkerPipeline(usbOnly = isUsb, clusterSecret = secret)
                    serviceScope.launch {
                        // Allow TCP listener to bind before notifying Master so Master's
                        // immediate handshake probe succeeds without ECONNREFUSED.
                        delay(200L)
                        lastPairedMasterHostPort = masterAddr
                        notifyMasterViaUdpOrHttp(masterAddr, isUsb)
                        startMasterHeartbeatLoop(masterAddr, isUsb)
                    }
                } else {
                    appendLog("Invalid QR pairing code: $rawQr")
                }
                return START_STICKY
            }
            ACTION_AUTO_DETECT_MASTER -> {
                startForegroundSafely()
                autoDetectMasterNode()
                return START_STICKY
            }
            else -> {
                val usbOnly = intent?.getBooleanExtra(
                    EXTRA_USB_ONLY,
                    _telemetry.value.usbLoopbackOnly
                ) ?: _telemetry.value.usbLoopbackOnly
                val secret = intent?.getStringExtra(EXTRA_CLUSTER_SECRET)
                    ?: _telemetry.value.clusterSecret

                startForegroundSafely()
                startWorkerPipeline(usbOnly = usbOnly, clusterSecret = secret)
                return START_STICKY
            }
        }
    }

    /**
     * Multi-generation Foreground Service promotion:
     * API 29-33 uses FOREGROUND_SERVICE_TYPE_DATA_SYNC, API 34+ uses DATA_SYNC safely declared
     * in AndroidManifest.xml, and API 28 falls back to 2-arg startForeground().
     */
    private fun startForegroundSafely() {
        val notification = buildForegroundNotification(
            statusText = "Safe Cap: ${_telemetry.value.safeCapMb} MB • ${_telemetry.value.activeBackend.label}"
        )
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (se: SecurityException) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification)
            } else {
                appendLog("Foreground start rejected on API ${Build.VERSION.SDK_INT}: ${se.message}")
            }
        } catch (e: IllegalStateException) {
            appendLog("Foreground start not allowed in current state: ${e.message}")
        }
    }

    private fun resolveNativeRpcBinary(): Pair<File?, String> {
        if (forceEmbeddedRpc) return null to (applicationContext.applicationInfo.nativeLibraryDir ?: "")
        val snap = _telemetry.value
        val nativeDir = applicationContext.applicationInfo.nativeLibraryDir ?: ""
        val preferDotProd = snap.hasDotProd && snap.hasI8mm
        val fastName = if (preferDotProd) "librpc_server_exec_v82.so" else "librpc_server_exec.so"
        val fallbackName = if (preferDotProd) "librpc_server_exec.so" else "librpc_server_exec_v82.so"
        val candidateFiles = listOf(
            File(snap.nativeBinaryPath),
            File(nativeDir, fastName),
            File(applicationContext.filesDir, fastName),
            File(nativeDir, fallbackName),
            File(applicationContext.filesDir, fallbackName)
        )
        val activeBin = candidateFiles.firstOrNull { it.exists() && (it.canExecute() || it.name.endsWith(".so")) }
        return activeBin to nativeDir
    }

    private fun startWorkerPipeline(usbOnly: Boolean, clusterSecret: String) {
        _telemetry.update {
            it.copy(
                workerState = WorkerRunState.PROBING,
                usbLoopbackOnly = usbOnly,
                bindAddress = "0.0.0.0:${effectiveRpcPort()}",
                clusterSecret = clusterSecret
            )
        }

        // Stage 1: Explicit startup probe for VK_KHR_16bit_storage, driver stability, RAM, and thermal state.
        val (activeBin, nativeDir) = resolveNativeRpcBinary()
        val startupLibDir = activeBin?.parentFile?.absolutePath ?: nativeDir
        val startupProbe = initializeStartupBackend(
            context = this,
            nativeBinary = activeBin,
            libDir = "$startupLibDir:$nativeDir",
            forceRefresh = true
        )
        if (!_telemetry.value.calibration.isCalibrated) {
            val profile = NodeAutoCalibrator.runCalibration(
                context = this,
                preProbedVk = startupProbe.vulkan
            )
            _telemetry.update { state ->
                val override = state.threadOverride
                val cores = if (override > 0) {
                    override.coerceIn(1, max(1, state.totalCoreCount))
                } else {
                    profile.recommendedThreads
                }
                val ramCap = if (state.safeRamPercentOverride in 25..50 && state.totalRamMb > 0L) {
                    max(256L, (state.totalRamMb * state.safeRamPercentOverride) / 100L)
                } else {
                    profile.recommendedSafeRamMb
                }
                state.copy(
                    calibration = profile,
                    safeCapMb = ramCap,
                    bigCoreCount = cores
                )
            }
            appendLog(
                "Auto-Calibrated: ${profile.tierLabel} (Score ${profile.performanceScore}/100, " +
                    "SafeRAM=${profile.recommendedSafeRamMb}MB, Threads=${profile.recommendedThreads}, ${profile.benchmarkDurationMs}ms)"
            )
        }
        val snap = _telemetry.value
        appendLog(
            "RAM Guard: MemTotal=${snap.totalRamMb}MB, MemAvail=${snap.availableRamMb}MB -> Calibrated SafeCap=${snap.safeCapMb}MB (<=50%)"
        )

        // Stage 2: Launch native RPC server with verified Vulkan 1.2+ / FP16 or immediate multi-threaded ARM NEON fallback.
        launchNativeOrEmbeddedRpcServer(usbOnly, clusterSecret, preProbedStartup = startupProbe)
        startSafetyTelemetryMonitor()
    }

    private fun isPortFree(port: Int): Boolean {
        return try {
            ServerSocket(port).use { true }
        } catch (_: Exception) {
            false
        }
    }

    internal fun launchNativeOrEmbeddedRpcServer(
        usbOnly: Boolean,
        clusterSecret: String,
        preProbedStartup: RpcStartupProbeSnapshot? = null
    ) {
        rpcServerJob?.cancel()
        runCatching { serverSocket?.close() }
        runCatching { nativeProcess?.destroyForcibly() }

        val hostIp = if (forceEmbeddedRpc) "127.0.0.1" else "0.0.0.0"
        val (activeBin, nativeDir) = resolveNativeRpcBinary()

        rpcServerJob = serviceScope.launch(Dispatchers.IO) {
            val cacheDir = TensorCacheManager.getCacheDir(applicationContext)
            val chosenPort = effectiveRpcPort()

            // If port is already bound (e.g. orphan process or background daemon), check if it's alive
            if (!isPortFree(chosenPort)) {
                val isRpcAlive = try {
                    Socket().use { s ->
                        s.connect(InetSocketAddress("127.0.0.1", chosenPort), 400)
                        val hello = ByteArray(33)
                        hello[0] = 14
                        hello[1] = 24
                        s.outputStream.write(hello)
                        s.outputStream.flush()
                        // Read handshake reply before closing to prevent Expected HELLO log spam
                        val resp = ByteArray(8)
                        s.inputStream.read(resp)
                        true
                    }
                } catch (_: Exception) { false }

                if (isRpcAlive) {
                    appendLog("Port $chosenPort is already actively bound and listening — adopting running RPC server.")
                    _telemetry.update {
                        it.copy(
                            bindAddress = "$hostIp:$chosenPort",
                            workerState = if (usbOnly) WorkerRunState.RUNNING_USB else WorkerRunState.RUNNING_WIFI,
                            pairingQrPayload = "clusterd://pair?master=${it.pairedMasterAddress.substringAfter("http://").substringBefore(" ")}&rpc=$chosenPort&secret=${it.clusterSecret}"
                        )
                    }
                    updateNotification()
                    while (isActive && !isPortFree(chosenPort)) {
                        delay(2500L)
                    }
                    if (!isActive) return@launch
                }
            }

            // Explicit Vulkan 1.2+ & VK_KHR_16bit_storage startup evaluation shared across
            // both native librpc_server_exec.so and embedded GGUF handshake execution paths.
            val initialSnap = _telemetry.value
            val startupProbe = preProbedStartup ?: RpcInitializer.prepareStartupInitialization(
                context = applicationContext,
                activeBin = activeBin,
                nativeDir = nativeDir,
                legacyHardwareMode = initialSnap.legacyHardwareMode,
                vulkanFallbackActive = initialSnap.vulkanFallbackActive
            )
            val cpuInfo = startupProbe.cpu
            val startupVk = startupProbe.vulkan

            _telemetry.update { cur ->
                val effectiveFallback = cur.vulkanFallbackActive || startupProbe.effectiveFallback
                val vkReady = startupProbe.vulkanReady && !cur.legacyHardwareMode && !effectiveFallback
                val selectedBackend = VulkanComputeProber.selectOptimalComputeBackend(
                    vk = startupVk,
                    cpu = cpuInfo,
                    legacyHardwareMode = cur.legacyHardwareMode,
                    vulkanFallbackActive = effectiveFallback
                )
                cur.copy(
                    vulkanFallbackActive = effectiveFallback,
                    vulkanProbePassed = vkReady,
                    hasVulkan16BitStorage = vkReady,
                    vulkanApiVersion = startupVk.vulkanApiVersion,
                    vulkanDeviceName = startupVk.reason,
                    activeBackend = selectedBackend,
                    hasDotProd = cpuInfo.hasDotProd,
                    hasI8mm = cpuInfo.hasI8mm,
                    cpuFeatures = cpuInfo.featuresSummary
                )
            }

            val probedSnap = _telemetry.value
            if (probedSnap.vulkanProbePassed && probedSnap.hasVulkan16BitStorage) {
                appendLog(
                    "[rpc-init] Verified Vulkan ${probedSnap.vulkanApiVersion} (1.2+) compute + VK_KHR_16bit_storage " +
                        "(${startupVk.deviceName}, push=${startupVk.maxPushConstantsSize}B) -> ${probedSnap.activeBackend.label}"
                )
            } else {
                appendLog(
                    "[rpc-init] Vulkan 1.2+ / VK_KHR_16bit_storage unavailable or driver unstable -> forcing NEON-optimized CPU backend " +
                        "(${probedSnap.activeBackend.label}; reason: ${startupVk.reason})"
                )
            }

            if (activeBin != null) {

                var restartAttempt = 0
                var pendingImmediateFallbackPlan: RpcInitializationPlan? = null
                while (isActive) {
                    var proc: Process? = null
                    try {
                        val currentSnap = _telemetry.value
                        if (!forceEmbeddedRpc && (
                                currentSnap.workerState == WorkerRunState.THROTTLED_THERMAL ||
                                currentSnap.workerState == WorkerRunState.SHEDDING_MEMORY ||
                                currentSnap.workerState == WorkerRunState.STOPPED
                            )
                        ) {
                            appendLog("Native RPC watchdog paused while in state ${currentSnap.workerState.name}.")
                            while (isActive && (_telemetry.value.workerState == WorkerRunState.THROTTLED_THERMAL || _telemetry.value.workerState == WorkerRunState.SHEDDING_MEMORY)) {
                                delay(1000L)
                            }
                            if (!isActive || _telemetry.value.workerState == WorkerRunState.STOPPED) break
                        }

                        val liveMem = HardwareProbe.readProcMemInfo()
                        val liveThermal = HardwareProbe.readBatteryAndThermal(applicationContext)
                        val loadDecision = DynamicLoadBalancer.evaluate(
                            LoadBalanceInput(
                                internalHeatC = liveThermal.internalHeatCelsius,
                                batteryTempC = liveThermal.tempCelsius,
                                tempSlopeCPerMin = currentSnap.tempSlopeCPerMin,
                                thermalHeadroom = liveThermal.thermalHeadroom,
                                thermalStatusLabel = liveThermal.thermalStatusLabel,
                                availableRamMb = liveMem.availableMb,
                                totalRamMb = liveMem.totalMb,
                                lastTrimLevel = currentSnap.lastTrimLevel,
                                batteryPercent = liveThermal.levelPercent,
                                isPluggedIn = liveThermal.isPlugged,
                                totalCores = currentSnap.totalCoreCount,
                                bigCores = currentSnap.bigCoreCount,
                                legacyHardwareMode = currentSnap.legacyHardwareMode,
                                threadOverride = currentSnap.threadOverride,
                                safeRamPercentOverride = currentSnap.safeRamPercentOverride,
                                masterAdaptiveLoadFactor = currentSnap.masterAdaptiveLoadFactor,
                                masterRecommendedContextTokens = currentSnap.masterRecommendedContextTokens,
                                masterThreadCap = currentSnap.masterThreadCap,
                                previousTier = currentSnap.thermalScalingTier
                            )
                        )
                        if (loadDecision.tier == ThermalScalingTier.SHED_LAYERS_CRITICAL && pendingImmediateFallbackPlan == null && !forceEmbeddedRpc) {
                            val ramTriggeredShed = liveMem.availableMb < 400L || currentSnap.lastTrimLevel >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL
                            val pauseState = if (ramTriggeredShed) WorkerRunState.SHEDDING_MEMORY else WorkerRunState.THROTTLED_THERMAL
                            cooldownStartedEpochMs = System.currentTimeMillis()
                            appendLog("[load-balancer] Deferring RPC launch (${pauseState.name}): ${loadDecision.reason}")
                            _telemetry.update {
                                it.copy(
                                    workerState = pauseState,
                                    totalRamMb = liveMem.totalMb,
                                    availableRamMb = liveMem.availableMb,
                                    safeCapMb = loadDecision.safeRamCapMb,
                                    thermalScalingTier = loadDecision.tier,
                                    targetContextTokens = loadDecision.contextWindowTokens,
                                    loadAllocationScale = 0.0f,
                                    threadNicePriority = loadDecision.threadNicePriority,
                                    thermalPressureScore = loadDecision.thermalPressureScore,
                                    ramPressureScore = loadDecision.ramPressureScore,
                                    loadBalancerReason = loadDecision.reason
                                )
                            }
                            delay(2000L)
                            continue
                        }
                        val thermalDecision = loadDecision.toThermalScalingDecision()
                        val effectiveVk = if (currentSnap.vulkanFallbackActive || !currentSnap.vulkanProbePassed || !currentSnap.hasVulkan16BitStorage) {
                            startupVk.copy(
                                supported = false,
                                has16BitStorage = false,
                                driverStable = false
                            )
                        } else {
                            startupVk
                        }
                        val explicitThrottleCap = if (currentSnap.thermalThrottleThreads > 0 && currentSnap.thermalThrottleThreads < loadDecision.targetThreads) {
                            currentSnap.thermalThrottleThreads
                        } else {
                            0
                        }
                        val initPlan = pendingImmediateFallbackPlan ?: RpcInitializer.buildInitializationPlan(
                            vk = effectiveVk,
                            cpu = cpuInfo,
                            loadBalance = loadDecision,
                            legacyHardwareMode = currentSnap.legacyHardwareMode,
                            vulkanFallbackActive = currentSnap.vulkanFallbackActive,
                            hostIp = hostIp,
                            port = chosenPort,
                            activeBinPath = activeBin.absolutePath,
                            cacheDirPath = cacheDir.absolutePath,
                            libDir = activeBin.parentFile?.absolutePath ?: nativeDir,
                            nativeDir = nativeDir,
                            thermalThrottleThreadCap = explicitThrottleCap
                        )
                        pendingImmediateFallbackPlan = null

                        runCatching { AndroidProcess.setThreadPriority(initPlan.threadNicePriority) }

                        val pb = ProcessBuilder("/system/bin/sh", "-c", initPlan.shellCommand)
                        pb.directory(cacheDir)
                        val env = pb.environment()
                        env.remove("GGML_VK_VISIBLE_DEVICES")
                        env.remove("GGML_VK_DISABLE_F16")
                        env.remove("GGML_VK_FORCE_MAX_ALLOCATION_SIZE")
                        env.remove("SILICA_GPU_BACKEND_DISABLED")
                        env.remove("SILICA_ZERO_FALLBACK_CPU")
                        env.remove("GGML_ARM_DISABLE_DOTPROD")
                        env.remove("GGML_ARM_DISABLE_I8MM")
                        env.remove("SILICA_CPU_EXEC_PATH")
                        env.remove("OMP_PROC_BIND")
                        env.remove("OMP_PLACES")
                        env.remove("SILICA_ARM_NEON_FASTPATH")
                        env.remove("SILICA_NEON_THREADS")
                        env.putAll(initPlan.processEnv)

                        val useGpu = !initPlan.forceNeonCpu
                        val spawnStartMs = System.currentTimeMillis()
                        lastRpcReloadEpochMs = spawnStartMs
                        val vulkanRuntimeFailureDetected = AtomicBoolean(false)
                        proc = pb.start()
                        nativeProcess = proc

                        _telemetry.update {
                            it.copy(
                                bindAddress = "$hostIp:$chosenPort",
                                workerState = if (usbOnly) WorkerRunState.RUNNING_USB else WorkerRunState.RUNNING_WIFI,
                                activeBackend = initPlan.selectedBackend,
                                totalRamMb = liveMem.totalMb,
                                availableRamMb = liveMem.availableMb,
                                safeCapMb = loadDecision.safeRamCapMb,
                                thermalScalingTier = loadDecision.tier,
                                targetContextTokens = loadDecision.contextWindowTokens,
                                loadAllocationScale = loadDecision.loadAllocationScale,
                                threadNicePriority = initPlan.threadNicePriority,
                                thermalPressureScore = loadDecision.thermalPressureScore,
                                ramPressureScore = loadDecision.ramPressureScore,
                                loadBalancerReason = loadDecision.reason,
                                pairingQrPayload = "clusterd://pair?master=${it.pairedMasterAddress.substringAfter("http://").substringBefore(" ")}&rpc=$chosenPort&secret=${it.clusterSecret}"
                            )
                        }
                        updateNotification()
                        appendLog(
                            if (useGpu) {
                                "[rpc-init] Spawned ${activeBin.name} on $hostIp:$chosenPort (-t ${initPlan.threads}, Vulkan fp16 Compute, ctx=${loadDecision.contextWindowTokens}, safeCap=${loadDecision.safeRamCapMb}MB, avail=${liveMem.availableMb}MB, tier=${loadDecision.tier.label}, nice=${initPlan.threadNicePriority}, cache: ${cacheDir.name})"
                            } else {
                                "[rpc-init] Spawned ${activeBin.name} on $hostIp:$chosenPort (-d CPU -t ${initPlan.threads}, ${initPlan.selectedBackend.label}, NEON fastpath, ctx=${loadDecision.contextWindowTokens}, safeCap=${loadDecision.safeRamCapMb}MB, avail=${liveMem.availableMb}MB, tier=${loadDecision.tier.label}, nice=${initPlan.threadNicePriority}, cache: ${cacheDir.name})"
                            }
                        )

                        val outJob = launch {
                            runCatching {
                                proc.inputStream.bufferedReader().useLines { lines ->
                                    lines.forEach { line ->
                                        appendLog("[rpc] $line")
                                        if (useGpu && VulkanComputeProber.isVulkan16BitStorageFailureLine(line)) {
                                            if (vulkanRuntimeFailureDetected.compareAndSet(false, true)) {
                                                appendLog("[vulkan-guard] Runtime Vulkan VK_KHR_16bit_storage/driver fault -> triggering immediate ARM NEON fallback")
                                                runCatching { proc.destroyForcibly() }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        val errJob = launch {
                            runCatching {
                                proc.errorStream.bufferedReader().useLines { lines ->
                                    lines.forEach { line ->
                                        appendLog("[rpc] $line")
                                        if (useGpu && VulkanComputeProber.isVulkan16BitStorageFailureLine(line)) {
                                            if (vulkanRuntimeFailureDetected.compareAndSet(false, true)) {
                                                appendLog("[vulkan-guard] Runtime Vulkan error detected -> triggering immediate ARM NEON fallback")
                                                runCatching { proc.destroyForcibly() }
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        val exitCode = proc.waitFor()
                        val runtimeMs = System.currentTimeMillis() - spawnStartMs
                        outJob.cancel()
                        errJob.cancel()
                        nativeProcess = null

                        // Seamless, zero-delay fallback to the optimized ARM NEON CPU execution path
                        val fatalCrashSignal = useGpu && exitCode > 128 && exitCode != 143
                        val fastVulkanCrash = useGpu && exitCode != 0 && exitCode != 143 && runtimeMs < 4000L
                        if (useGpu && (vulkanRuntimeFailureDetected.get() || fatalCrashSignal || fastVulkanCrash) && isActive) {
                            val failReason = "runtime=${runtimeMs}ms exit=$exitCode"
                            val fallbackPlan = RpcInitializer.buildImmediateNeonFallbackPlan(
                                context = applicationContext,
                                cpu = cpuInfo,
                                thermal = thermalDecision,
                                legacyHardwareMode = currentSnap.legacyHardwareMode,
                                failureReason = failReason,
                                hostIp = hostIp,
                                port = chosenPort,
                                activeBinPath = activeBin.absolutePath,
                                cacheDirPath = cacheDir.absolutePath,
                                libDir = activeBin.parentFile?.absolutePath ?: nativeDir,
                                nativeDir = nativeDir,
                                thermalThrottleThreadCap = currentSnap.thermalThrottleThreads
                            )
                            pendingImmediateFallbackPlan = fallbackPlan
                            appendLog("[vulkan-guard] Vulkan driver unstable ($failReason) — seamlessly switching (0ms) to ${fallbackPlan.selectedBackend.label}.")
                            _telemetry.update {
                                it.copy(
                                    vulkanFallbackActive = true,
                                    vulkanProbePassed = false,
                                    hasVulkan16BitStorage = false,
                                    vulkanDeviceName = "Vulkan unstable/no fp16 ($failReason) -> ${fallbackPlan.selectedBackend.label}",
                                    activeBackend = fallbackPlan.selectedBackend,
                                    loadBalancerReason = "Vulkan fault ($failReason) -> ${fallbackPlan.selectedBackend.label}"
                                )
                            }
                            restartAttempt = 0
                            continue
                        }

                        val postExitState = _telemetry.value.workerState
                        if (!isActive ||
                            postExitState == WorkerRunState.THROTTLED_THERMAL ||
                            postExitState == WorkerRunState.SHEDDING_MEMORY ||
                            postExitState == WorkerRunState.STOPPED ||
                            exitCode == 143
                        ) {
                            appendLog("Native RPC server halted cleanly (state=${postExitState.name}, code $exitCode); watchdog paused.")
                            while (isActive && (_telemetry.value.workerState == WorkerRunState.THROTTLED_THERMAL || _telemetry.value.workerState == WorkerRunState.SHEDDING_MEMORY)) {
                                delay(1000L)
                            }
                            if (!isActive || _telemetry.value.workerState == WorkerRunState.STOPPED) break
                            restartAttempt = 0
                            continue
                        }
                        restartAttempt += 1
                        val backoffMs = min(8000L, 1200L * restartAttempt)
                        appendLog("[watchdog] Native RPC server exited (code $exitCode) — restarting in ${backoffMs}ms...")
                        delay(backoffMs)
                    } catch (e: Exception) {
                        nativeProcess = null
                        if (!isActive) break
                        appendLog("[watchdog] Native exec error: ${e.message} — retrying in 2000ms")
                        delay(2000L)
                    } finally {
                        runCatching {
                            if (proc?.isAlive == true) proc.destroyForcibly()
                        }
                    }
                }
            } else {
                appendLog(
                    "Native librpc_server_exec.so not packaged in APK — running embedded GGUF/v1.0.0 handshake responder on " +
                        "$hostIp:$chosenPort (${probedSnap.activeBackend.label})"
                )
                _telemetry.update {
                    it.copy(
                        bindAddress = "$hostIp:$chosenPort",
                        workerState = if (usbOnly) WorkerRunState.RUNNING_USB else WorkerRunState.RUNNING_WIFI
                    )
                }
                updateNotification()
                try {
                    val sock = ServerSocket()
                    sock.reuseAddress = true
                    sock.bind(InetSocketAddress(hostIp, chosenPort))
                    serverSocket = sock
                    while (isActive) {
                        val client = sock.accept()
                        handleIncomingMasterConnection(client, usbOnly, clusterSecret)
                    }
                } catch (_: Exception) {
                    // Socket closed on service stop
                }
            }
        }
    }

    private fun handleIncomingMasterConnection(
        client: Socket,
        usbOnly: Boolean,
        clusterSecret: String
    ) {
        serviceScope.launch {
            client.use { socket ->
                socket.soTimeout = 4000
                val input: InputStream = socket.getInputStream()
                val output: OutputStream = socket.getOutputStream()

                val incoming = ByteArray(32)
                var totalRead = 0
                while (totalRead < 32) {
                    val n = runCatching {
                        input.read(incoming, totalRead, 32 - totalRead)
                    }.getOrDefault(-1)
                    if (n <= 0) break
                    totalRead += n
                }

                val nonce = if (totalRead >= 32) decodeV10Request(clusterSecret, incoming) else null
                if (nonce == null) {
                    appendLog("Rejected unauthorized v10 probe from ${socket.inetAddress.hostAddress} (nonce MAC mismatch)")
                    return@use
                }

                val snap = _telemetry.value
                val response = buildV10Reply(
                    secret = clusterSecret,
                    nonce = nonce,
                    cap = snap.safeCapMb.toInt(),
                    cores = snap.bigCoreCount,
                    flags = if (snap.activeBackend.label.contains("Vulkan", ignoreCase = true)) FLAG_VULKAN_FP16 else 0,
                    status = if (snap.shouldShedLayers) 2 else if (snap.thermalThrottleThreads > 0) 1 else 0
                )
                output.write(response)
                output.flush()

                _telemetry.update {
                    it.copy(handshakesCompleted = it.handshakesCompleted + 1)
                }
                if (totalRead > 0) {
                    appendLog("Verified nonce-bound GGUF RPC v10 handshake with Master (${socket.inetAddress.hostAddress})")
                }

                val bwHeader = ByteArray(8)
                if (readFully(input, bwHeader) &&
                    bwHeader.copyOfRange(0, 4).contentEquals("BWPR".toByteArray(StandardCharsets.US_ASCII))
                ) {
                    val length = ByteBuffer.wrap(bwHeader, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
                    if (length in 1..(4 * 1024 * 1024)) {
                        val payload = ByteArray(length)
                        if (readFully(input, payload)) {
                            output.write("BWOK".toByteArray(StandardCharsets.US_ASCII))
                            output.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(length).array())
                            output.write(payload)
                            output.flush()
                        }
                    }
                }
            }
        }
    }

    /**
     * Builds the deterministic 32-byte GGUF / RPC v9 / v1.0.0 capability frame:
     * Bytes 0..3   : "GGUF" magic
     * Bytes 4..7   : uint32 LE RPC protocol version = 9
     * Bytes 8..15  : "v1.0.0\0\0" stock llama.cpp tag
     * Bytes 16..19 : uint32 LE safe_cap_mb (strictly <= 50% physical RAM)
     * Bytes 20..23 : uint32 LE big_core_count
     * Bytes 24..27 : uint32 LE flags (bit 0 = Vulkan, bit 1 = DotProd, bit 2 = 16KB aligned, bit 6 = Android)
     * Bytes 28..31 : uint32 LE status (0 = READY, 1 = THROTTLED, 2 = SHED_LAYERS)
     */
    fun build32ByteGgufHandshake(safeCapMb: Int, bigCores: Int): ByteArray {
        val snap = _telemetry.value
        val buf = ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN)
        buf.put("GGUF".toByteArray(StandardCharsets.US_ASCII))
        buf.putInt(9) // RPC protocol v9
        val versionBytes = "v1.0.0\u0000\u0000".toByteArray(StandardCharsets.US_ASCII)
        buf.put(versionBytes, 0, 8)
        buf.putInt(safeCapMb)
        buf.putInt(bigCores)
        var flags = FLAG_PLATFORM_ANDROID
        if (snap.vulkanProbePassed && snap.hasVulkan16BitStorage && !snap.legacyHardwareMode && !snap.vulkanFallbackActive) {
            flags = flags or FLAG_VULKAN_FP16
        }
        if (snap.hasDotProd) flags = flags or FLAG_ARM_DOTPROD
        if (snap.pageAlignment16KbReady) flags = flags or FLAG_ELF_16KB_ALIGNED
        if (snap.legacyHardwareMode) flags = flags or FLAG_LEGACY_HARDWARE
        if (snap.legacyGraphicsMode) flags = flags or FLAG_LEGACY_GRAPHICS
        buf.putInt(flags)
        val status = when (snap.workerState) {
            WorkerRunState.THROTTLED_THERMAL -> 1
            WorkerRunState.SHEDDING_MEMORY -> 2
            else -> 0
        }
        buf.putInt(status)
        return buf.array()
    }

    /**
     * Polls internal thermal zones (/sys/class/thermal/thermal_zone*), battery temperature,
     * ADPF thermal headroom, battery level, and /proc/meminfo every 2 seconds.
     * Applies ThermalScalingPolicy dynamically:
     * - < 40°C: elevates thread priority (nice -8), boosts performance threads, full context window, 100% load.
     * - 40..48°C: balanced scaling stepping down threads & context window smoothly.
     * - > 48°C internal heat: progressively throttles threads/priority, shrinks context window to 1024, and sheds layers.
     * - >= 52°C internal heat (or >= 45°C battery / < 15% unplugged): full layer shed & pause until cooled.
     */
    private fun startSafetyTelemetryMonitor() {
        monitorJob?.cancel()
        monitorJob = serviceScope.launch {
            while (isActive) {
                val nowMs = System.currentTimeMillis()
                val mem = HardwareProbe.readProcMemInfo()
                val bat = HardwareProbe.readBatteryAndThermal(this@RpcService)
                val current = _telemetry.value
                val effectiveHeatC = max(bat.internalHeatCelsius, bat.tempCelsius)

                val nextSlopeCPerMin = if (!lastSampledTempC.isNaN() && lastSampledTempEpochMs > 0L) {
                    DynamicLoadBalancer.updateThermalSlopeCPerMin(
                        previousTempC = lastSampledTempC,
                        currentTempC = effectiveHeatC,
                        elapsedMs = nowMs - lastSampledTempEpochMs,
                        previousSlopeCPerMin = current.tempSlopeCPerMin
                    )
                } else {
                    0.0f
                }
                lastSampledTempC = effectiveHeatC
                lastSampledTempEpochMs = nowMs

                val activePrevThreads = if (current.thermalThrottleThreads > 0) {
                    current.thermalThrottleThreads
                } else {
                    current.bigCoreCount
                }
                val millisSinceReload = if (lastRpcReloadEpochMs > 0L) {
                    nowMs - lastRpcReloadEpochMs
                } else {
                    Long.MAX_VALUE
                }

                val decision = DynamicLoadBalancer.evaluate(
                    LoadBalanceInput(
                        internalHeatC = bat.internalHeatCelsius,
                        batteryTempC = bat.tempCelsius,
                        tempSlopeCPerMin = nextSlopeCPerMin,
                        thermalHeadroom = bat.thermalHeadroom,
                        thermalStatusLabel = bat.thermalStatusLabel,
                        availableRamMb = mem.availableMb,
                        totalRamMb = mem.totalMb,
                        lastTrimLevel = current.lastTrimLevel,
                        batteryPercent = bat.levelPercent,
                        isPluggedIn = bat.isPlugged,
                        totalCores = current.totalCoreCount,
                        bigCores = current.bigCoreCount,
                        legacyHardwareMode = current.legacyHardwareMode,
                        threadOverride = current.threadOverride,
                        safeRamPercentOverride = current.safeRamPercentOverride,
                        masterAdaptiveLoadFactor = current.masterAdaptiveLoadFactor,
                        masterRecommendedContextTokens = current.masterRecommendedContextTokens,
                        masterThreadCap = current.masterThreadCap,
                        previousTier = current.thermalScalingTier,
                        previousThreads = activePrevThreads,
                        previousContextTokens = current.targetContextTokens,
                        millisSinceLastReload = millisSinceReload
                    )
                )

                var nextState = current.workerState
                var nextThermalThrottleThreads = current.thermalThrottleThreads
                var nextActiveLayers = current.activeLayersAssigned
                var nextLayerStart = current.assignedLayerStart
                var nextLayerEnd = current.assignedLayerEnd
                var nextAssignedRamMb = current.assignedRamMb
                var nextLayersShedCount = current.layersShedCount
                var triggerThermalThreadReload = false
                var triggerImmediateMasterSync = false

                if (decision.threadNicePriority != current.threadNicePriority) {
                    runCatching { AndroidProcess.setThreadPriority(decision.threadNicePriority) }
                }

                if (current.workerState == WorkerRunState.RUNNING_USB ||
                    current.workerState == WorkerRunState.RUNNING_WIFI
                ) {
                    if (decision.tier == ThermalScalingTier.SHED_LAYERS_CRITICAL) {
                        val ramTriggeredShed = mem.availableMb < 400L || current.lastTrimLevel >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL
                        nextState = if (ramTriggeredShed) WorkerRunState.SHEDDING_MEMORY else WorkerRunState.THROTTLED_THERMAL
                        nextThermalThrottleThreads = 1
                        peakPausedTempCelsius = max(effectiveHeatC, ThermalScalingPolicy.INTERNAL_THROTTLE_THRESHOLD_C)
                        cooldownStartedEpochMs = nowMs
                        appendLog("[load-balancer] CRITICAL SHED LAYERS: ${decision.reason}. Pausing compute until recovered.")
                        _telemetry.update {
                            it.copy(
                                workerState = nextState,
                                totalRamMb = mem.totalMb,
                                availableRamMb = mem.availableMb,
                                safeCapMb = decision.safeRamCapMb,
                                thermalScalingTier = decision.tier,
                                targetContextTokens = decision.contextWindowTokens,
                                loadAllocationScale = 0.0f,
                                threadNicePriority = decision.threadNicePriority,
                                shouldShedLayers = true,
                                layersShedCount = current.activeLayersAssigned,
                                tempSlopeCPerMin = nextSlopeCPerMin,
                                thermalPressureScore = decision.thermalPressureScore,
                                ramPressureScore = decision.ramPressureScore,
                                loadBalancerReason = decision.reason,
                                activeLayersAssigned = 0,
                                assignedLayerStart = -1,
                                assignedLayerEnd = -1,
                                assignedRamMb = 0L
                            )
                        }
                        pauseNativeCompute()
                        triggerImmediateMasterSync = true
                    } else {
                        val desiredThrottle = if (decision.loadAllocationScale < 0.99f ||
                            decision.tier != ThermalScalingTier.BOOST_COOL ||
                            decision.targetThreads != current.bigCoreCount
                        ) {
                            decision.targetThreads
                        } else {
                            0
                        }
                        val shedPlan = ThermalScalingPolicy.computeAutomaticLayerShedding(
                            currentLayers = current.activeLayersAssigned,
                            layerStart = current.assignedLayerStart,
                            layerEnd = current.assignedLayerEnd,
                            assignedRamMb = current.assignedRamMb,
                            decision = decision.toThermalScalingDecision()
                        )
                        if (shedPlan.didShed) {
                            nextActiveLayers = shedPlan.activeLayersAssigned
                            nextLayerStart = shedPlan.assignedLayerStart
                            nextLayerEnd = shedPlan.assignedLayerEnd
                            nextAssignedRamMb = shedPlan.assignedRamMb
                            nextLayersShedCount = shedPlan.layersShedCount
                            triggerImmediateMasterSync = true
                            appendLog(
                                "[load-balancer] AUTO LAYER-SHED (${"%.1f".format(effectiveHeatC)}°C > 48°C): " +
                                    "shedding ${shedPlan.layersShedCount}L (${current.activeLayersAssigned}L -> ${shedPlan.activeLayersAssigned}L)"
                            )
                        } else if (!decision.shouldShedLayers) {
                            nextLayersShedCount = 0
                        }

                        val tierChanged = decision.tier != current.thermalScalingTier
                        val ctxChanged = decision.contextWindowTokens != current.targetContextTokens
                        val loadChanged = kotlin.math.abs(decision.loadAllocationScale - current.loadAllocationScale) >= 0.05f
                        val threadsChanged = desiredThrottle != current.thermalThrottleThreads
                        val shedFlagChanged = decision.shouldShedLayers != current.shouldShedLayers

                        if (threadsChanged || ctxChanged || tierChanged || shedFlagChanged) {
                            nextThermalThrottleThreads = desiredThrottle
                            if (decision.shouldReloadRpcProcess) {
                                triggerThermalThreadReload = true
                            }
                            appendLog("[load-balancer] ${decision.tier.label}: ${decision.reason} (availRam=${mem.availableMb}MB, status=${bat.thermalStatusLabel}, reload=$triggerThermalThreadReload)")
                        }
                        if (tierChanged || ctxChanged || threadsChanged || loadChanged || shedFlagChanged) {
                            triggerImmediateMasterSync = true
                        }
                    }
                } else if (current.workerState == WorkerRunState.THROTTLED_THERMAL ||
                    current.workerState == WorkerRunState.SHEDDING_MEMORY
                ) {
                    val cooledSufficiently = effectiveHeatC < DynamicLoadBalancer.THERMAL_CEILING_C ||
                        effectiveHeatC <= (peakPausedTempCelsius - 4.0f)
                    val elapsed20s = (nowMs - cooldownStartedEpochMs) >= 20_000L
                    val batterySafe = (bat.levelPercent >= 15 || bat.isPlugged) && bat.tempCelsius < 43.0f
                    val headroomSafe = bat.thermalHeadroom.isNaN() || bat.thermalHeadroom < 0.85f || effectiveHeatC < DynamicLoadBalancer.THERMAL_CEILING_C
                    val ramRecovered = mem.availableMb >= 1200L && !decision.shouldShedLayers
                    var resumeAfterRecover = false

                    if (cooledSufficiently && elapsed20s && batterySafe && headroomSafe && ramRecovered) {
                        nextState = if (current.usbLoopbackOnly) {
                            WorkerRunState.RUNNING_USB
                        } else {
                            WorkerRunState.RUNNING_WIFI
                        }
                        nextThermalThrottleThreads = decision.targetThreads
                        appendLog("[load-balancer] Recovery complete (heat=${"%.1f".format(effectiveHeatC)}°C, avail=${mem.availableMb}MB, ctx=${decision.contextWindowTokens}). Resuming RPC worker.")
                        resumeAfterRecover = true
                        triggerImmediateMasterSync = true
                    }

                    val clearedTrim = if (ramRecovered && mem.availableMb >= max(1800L, (mem.totalMb * 30L) / 100L)) 0 else current.lastTrimLevel
                    _telemetry.update {
                        it.copy(
                            totalRamMb = mem.totalMb,
                            availableRamMb = mem.availableMb,
                            safeCapMb = decision.safeRamCapMb,
                            batteryPercent = bat.levelPercent,
                            isPluggedIn = bat.isPlugged,
                            temperatureCelsius = bat.tempCelsius,
                            internalHeatCelsius = bat.internalHeatCelsius,
                            thermalSensorSource = bat.sensorSource,
                            thermalHeadroom = bat.thermalHeadroom,
                            thermalStatusLabel = bat.thermalStatusLabel,
                            thermalScalingTier = decision.tier,
                            targetContextTokens = decision.contextWindowTokens,
                            loadAllocationScale = decision.loadAllocationScale,
                            threadNicePriority = decision.threadNicePriority,
                            shouldShedLayers = decision.shouldShedLayers,
                            layersShedCount = if (resumeAfterRecover) 0 else it.layersShedCount,
                            thermalThrottleThreads = nextThermalThrottleThreads,
                            tempSlopeCPerMin = nextSlopeCPerMin,
                            thermalPressureScore = decision.thermalPressureScore,
                            ramPressureScore = decision.ramPressureScore,
                            loadBalancerReason = decision.reason,
                            lastTrimLevel = clearedTrim,
                            workerState = nextState
                        )
                    }
                    if (resumeAfterRecover) {
                        lastRpcReloadEpochMs = nowMs
                        launchNativeOrEmbeddedRpcServer(current.usbLoopbackOnly, current.clusterSecret)
                    }
                    if (triggerImmediateMasterSync) {
                        val masterAddr = lastPairedMasterHostPort
                        if (!masterAddr.isNullOrBlank()) {
                            notifyMasterViaUdpOrHttp(masterAddr, current.usbLoopbackOnly)
                        }
                    }
                    updateNotification()
                    delay(2000L)
                    continue
                }

                val clearedTrimRunning = if (current.lastTrimLevel in 1..14 && mem.availableMb >= max(1800L, (mem.totalMb * 30L) / 100L)) 0 else current.lastTrimLevel
                _telemetry.update {
                    it.copy(
                        totalRamMb = mem.totalMb,
                        availableRamMb = mem.availableMb,
                        safeCapMb = decision.safeRamCapMb,
                        batteryPercent = bat.levelPercent,
                        isPluggedIn = bat.isPlugged,
                        temperatureCelsius = bat.tempCelsius,
                        internalHeatCelsius = bat.internalHeatCelsius,
                        thermalSensorSource = bat.sensorSource,
                        thermalHeadroom = bat.thermalHeadroom,
                        thermalStatusLabel = bat.thermalStatusLabel,
                        thermalScalingTier = decision.tier,
                        targetContextTokens = decision.contextWindowTokens,
                        loadAllocationScale = decision.loadAllocationScale,
                        threadNicePriority = decision.threadNicePriority,
                        shouldShedLayers = decision.shouldShedLayers,
                        layersShedCount = nextLayersShedCount,
                        activeLayersAssigned = nextActiveLayers,
                        assignedLayerStart = nextLayerStart,
                        assignedLayerEnd = nextLayerEnd,
                        assignedRamMb = nextAssignedRamMb,
                        thermalThrottleThreads = nextThermalThrottleThreads,
                        tempSlopeCPerMin = nextSlopeCPerMin,
                        thermalPressureScore = decision.thermalPressureScore,
                        ramPressureScore = decision.ramPressureScore,
                        loadBalancerReason = decision.reason,
                        lastTrimLevel = clearedTrimRunning,
                        workerState = nextState
                    )
                }
                if (triggerThermalThreadReload) {
                    lastRpcReloadEpochMs = nowMs
                    launchNativeOrEmbeddedRpcServer(current.usbLoopbackOnly, current.clusterSecret)
                }
                if (triggerImmediateMasterSync) {
                    val masterAddr = lastPairedMasterHostPort
                    if (!masterAddr.isNullOrBlank()) {
                        notifyMasterViaUdpOrHttp(masterAddr, current.usbLoopbackOnly)
                    }
                }
                updateNotification()
                delay(2000L)
            }
        }
    }

    /**
     * ComponentCallbacks2.onTrimMemory hook:
     * When Android signals TRIM_MEMORY_RUNNING_CRITICAL (15) or TRIM_MEMORY_COMPLETE (80),
     * immediately emit an emergency SHED_LAYERS frame to the Master and halt native execution
     * before Android's low-memory killer daemon (lmkd) terminates the process.
     */
    @Suppress("DEPRECATION")
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level != ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) {
            _telemetry.update { it.copy(lastTrimLevel = level) }
        }
        if (level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL ||
            level >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE
        ) {
            appendLog("CRITICAL onTrimMemory(level=$level) -> Sending emergency SHED_LAYERS frame & stopping librpc_server_exec.so before lmkd kill!")
            sendEmergencyShedLayersFrame()
            stopWorkerAndFreeRam("onTrimMemory(level=$level) SHED_LAYERS triggered")
            _telemetry.update {
                it.copy(
                    workerState = WorkerRunState.SHEDDING_MEMORY,
                    activeLayersAssigned = 0
                )
            }
            updateNotification()
        }
    }

    private fun sendEmergencyShedLayersFrame() {
        // Updates active state so any Master health poll receives status=2 (SHED_LAYERS)
        _telemetry.update {
            it.copy(
                workerState = WorkerRunState.SHEDDING_MEMORY,
                activeLayersAssigned = 0
            )
        }
    }

    private fun pauseNativeCompute() {
        runCatching { nativeProcess?.destroy() }
        nativeProcess = null
    }

    /**
     * Step 1: Broadcasts `CLUSTERD_DISCOVER` on UDP :50050 across LAN (`255.255.255.255`),
     *         Android Emulator host (`10.0.2.2`), and USB loopback (`127.0.0.1`) to find a
     *         Windows or Linux Master without triggering an early TCP probe race condition.
     * Step 2: Binds this Android worker's TCP :50055 listener with the discovered secret.
     * Step 3: Registers with the Master via both UDP `CLUSTERD_REGISTER:50055` and HTTP
     *         `POST /api/nodes/add` fallback so the Master's handshake succeeds immediately.
     */
    private fun autoDetectMasterNode() {
        _telemetry.update { it.copy(isScanningMaster = true) }
        appendLog("Auto-Detecting Master Node on Wi-Fi LAN (UDP :50050 + HTTP /24 beacon) & USB ADB...")

        serviceScope.launch {
            var discoveredMaster: String? = null
            var discoveredSecret = _telemetry.value.clusterSecret
            var useUsb = true

            // Acquire WifiManager.MulticastLock so OEM Wi-Fi power savers do not drop UDP broadcast replies
            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val multicastLock = runCatching {
                wifiManager?.createMulticastLock("silica_cluster_udp_discover")?.apply {
                    setReferenceCounted(false)
                    acquire()
                }
            }.getOrNull()

            try {
                java.net.DatagramSocket().use { udp ->
                    udp.broadcast = true
                    udp.soTimeout = 900
                    val payload = "CLUSTERD_DISCOVER".toByteArray(StandardCharsets.UTF_8)

                    val targets = listOf("255.255.255.255", "10.0.2.2", "127.0.0.1")
                    for (host in targets) {
                        runCatching {
                            val packet = java.net.DatagramPacket(
                                payload,
                                payload.size,
                                java.net.InetAddress.getByName(host),
                                50050
                            )
                            udp.send(packet)
                        }
                    }

                    val recvBuf = ByteArray(256)
                    val recvPacket = java.net.DatagramPacket(recvBuf, recvBuf.size)
                    if (runCatching { udp.receive(recvPacket) }.isSuccess) {
                        val reply = String(recvPacket.data, 0, recvPacket.length, StandardCharsets.UTF_8)
                        val senderIp = recvPacket.address.hostAddress ?: "127.0.0.1"
                        var port = "3000"
                        for (token in reply.substringAfter(":").split(";")) {
                            val kv = token.split("=", limit = 2)
                            if (kv.size == 2) {
                                when (kv[0]) {
                                    "master_port", "port" -> port = kv[1]
                                    "secret" -> discoveredSecret = kv[1]
                                }
                            }
                        }
                        discoveredMaster = "$senderIp:$port"
                        useUsb = senderIp == "127.0.0.1"
                    }
                }
            } catch (_: Exception) {
            } finally {
                runCatching {
                    if (multicastLock?.isHeld == true) multicastLock.release()
                }
            }

            // Fallback: parallel /24 HTTP /api/cluster/beacon sweep if UDP broadcast was blocked by AP isolation
            if (discoveredMaster == null) {
                val beaconHit = probeSubnetHttpBeaconFallback()
                if (beaconHit != null) {
                    discoveredMaster = beaconHit.first
                    if (beaconHit.second.isNotBlank()) discoveredSecret = beaconHit.second
                    useUsb = beaconHit.first.startsWith("127.0.0.1")
                    appendLog("Found Master via HTTP /api/cluster/beacon fallback at ${beaconHit.first}")
                }
            }

            val loopbackMaster = probeLoopbackMaster()
            if (loopbackMaster != null) {
                discoveredMaster = loopbackMaster.first
                if (loopbackMaster.second.isNotBlank()) discoveredSecret = loopbackMaster.second
                useUsb = true
                appendLog("Preferring ADB loopback master at ${loopbackMaster.first}")
            }

            if (discoveredMaster != null) {
                val master = discoveredMaster!!
                lastPairedMasterHostPort = master
                _telemetry.update {
                    it.copy(
                        isScanningMaster = false,
                        pairedMasterAddress = "$master (Auto-Detected)",
                        clusterSecret = discoveredSecret,
                        usbLoopbackOnly = useUsb,
                        bindAddress = "0.0.0.0:${effectiveRpcPort()}",
                        pairingQrPayload = "clusterd://pair?master=$master&rpc=${effectiveRpcPort()}&secret=$discoveredSecret"
                    )
                }
                appendLog("Auto-Detected Master at $master! Binding TCP :50055 before registering.")
                startWorkerPipeline(usbOnly = useUsb, clusterSecret = discoveredSecret)
                delay(200L)
                notifyMasterViaUdpOrHttp(master, useUsb)
                startMasterHeartbeatLoop(master, useUsb)
            } else {
                // Default to plug-and-play USB ADB loopback & Wi-Fi LAN (:50055) so both work out of the box
                lastPairedMasterHostPort = "127.0.0.1:3000"
                _telemetry.update {
                    it.copy(
                        isScanningMaster = false,
                        pairedMasterAddress = "USB ADB Tunnel (:50055 Ready)",
                        usbLoopbackOnly = true,
                        bindAddress = "0.0.0.0:${effectiveRpcPort()}"
                    )
                }
                appendLog("Ready on USB ADB Tunnel & Wi-Fi LAN (:50055) & QR Pairing.")
                startWorkerPipeline(usbOnly = true, clusterSecret = discoveredSecret)
                delay(200L)
                notifyMasterViaUdpOrHttp("127.0.0.1:3000", true)
                startMasterHeartbeatLoop("127.0.0.1:3000", true)
            }
        }
    }

    private suspend fun probeLoopbackMaster(): Pair<String, String>? {
        return runCatching {
            val conn = (java.net.URL("http://127.0.0.1:3000/api/cluster/beacon").openConnection() as java.net.HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 400
                readTimeout = 600
            }
            try {
                if (conn.responseCode in 200..299) {
                    val json = org.json.JSONObject(conn.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() })
                    "127.0.0.1:${json.optInt("port", 3000)}" to json.optString("secret", "")
                } else null
            } finally {
                conn.disconnect()
            }
        }.getOrNull()
    }

    private suspend fun probeSubnetHttpBeaconFallback(): Pair<String, String>? {
        val localIp = detectLocalLanIpv4()
        val candidates = mutableListOf("127.0.0.1", "10.0.2.2")
        val parts = localIp.split(".")
        if (parts.size == 4 && localIp != "127.0.0.1") {
            val prefix = "${parts[0]}.${parts[1]}.${parts[2]}"
            val selfOctet = parts[3].toIntOrNull() ?: -1
            // Probe common gateway & DHCP host octets quickly in parallel batches
            val priorityOctets = (listOf(1, 2, 10, 20, 50, 100, 101, 102, 103, 104, 105, 110, 120, 150, 200, 254) +
                (1..64).toList()).distinct().filter { it != selfOctet }
            priorityOctets.forEach { oct -> candidates.add("$prefix.$oct") }
        }

        for (batch in candidates.chunked(16)) {
            val hits = batch.map { host ->
                serviceScope.async {
                    runCatching {
                        val conn = (java.net.URL("http://$host:3000/api/cluster/beacon").openConnection() as java.net.HttpURLConnection).apply {
                            requestMethod = "GET"
                            connectTimeout = 280
                            readTimeout = 350
                        }
                        try {
                            if (conn.responseCode in 200..299) {
                                val text = conn.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
                                val json = org.json.JSONObject(text)
                                val port = json.optInt("port", 3000)
                                val secret = json.optString("secret", "")
                                "$host:$port" to secret
                            } else null
                        } finally {
                            conn.disconnect()
                        }
                    }.getOrNull()
                }
            }.awaitAll().filterNotNull()
            if (hits.isNotEmpty()) return hits.first()
        }
        return null
    }

    private fun startMasterHeartbeatLoop(masterHostPort: String, isUsb: Boolean) {
        heartbeatJob?.cancel()
        heartbeatJob = serviceScope.launch {
            while (isActive) {
                delay(8000L)
                val st = _telemetry.value.workerState
                if (st == WorkerRunState.RUNNING_USB || st == WorkerRunState.RUNNING_WIFI) {
                    notifyMasterViaUdpOrHttp(masterHostPort, isUsb)
                }
            }
        }
    }

    private fun unregisterFromMasterAsync() {
        val master = lastPairedMasterHostPort ?: "127.0.0.1:3000"
        val host = master.substringBefore(":")
        val httpPort = master.substringAfter(":", "3000").toIntOrNull() ?: 3000
        val workerId = buildDeviceWorkerId()
        val payload = org.json.JSONObject().apply {
            put("id", workerId)
        }.toString().toByteArray(StandardCharsets.UTF_8)

        Thread {
            val urls = listOf(
                "http://$host:$httpPort/api/cluster/nodes/unregister",
                "http://127.0.0.1:3000/api/cluster/nodes/unregister"
            ).distinct()
            for (u in urls) {
                val ok = runCatching {
                    val conn = (java.net.URL(u).openConnection() as java.net.HttpURLConnection).apply {
                        requestMethod = "POST"
                        connectTimeout = 800
                        readTimeout = 800
                        doOutput = true
                        setRequestProperty("Content-Type", "application/json")
                    }
                    try {
                        conn.outputStream.use { it.write(payload) }
                        conn.responseCode in 200..299
                    } finally {
                        conn.disconnect()
                    }
                }.getOrDefault(false)
                if (ok) break
            }
        }.start()
    }

    internal fun notifyMasterViaUdpOrHttp(masterHostPort: String, isUsb: Boolean = false) {
        serviceScope.launch {
            val host = masterHostPort.substringBefore(":")
            val httpPort = masterHostPort.substringAfter(":", "3000").toIntOrNull() ?: 3000

            val activePort = _telemetry.value.bindAddress.substringAfter(":", "50055").toIntOrNull() ?: 50055

            // 1. Send UDP registration beacon to Master :50050 with exact chosen port
            runCatching {
                java.net.DatagramSocket().use { udp ->
                    val msg = "CLUSTERD_REGISTER:$activePort".toByteArray(StandardCharsets.UTF_8)
                    val pkt = java.net.DatagramPacket(
                        msg,
                        msg.size,
                        java.net.InetAddress.getByName(host),
                        50050
                    )
                    udp.send(pkt)
                }
            }

            // 2. HTTP POST registration/heartbeat with real hardware telemetry, exact rpcPort, and flash tensor cache stats
            runCatching {
                val lanAdvertiseIp = if (isUsb || host == "127.0.0.1") {
                    "127.0.0.1"
                } else {
                    detectLocalLanIpv4(host)
                }
                val curTel = _telemetry.value
                val isNativeRpcRunning = nativeProcess != null && runCatching {
                    nativeProcess?.isAlive == true
                }.getOrDefault(false)
                val cacheStats = TensorCacheManager.getStats(applicationContext, enabled = true)

                val jsonPayload = org.json.JSONObject().apply {
                    put("id", buildDeviceWorkerId())
                    put("label", "${curTel.deviceModel.trim()} (Android)")
                    put("platform", "Android")
                    put("osType", "android")
                    put("addr", "$lanAdvertiseIp:$activePort")
                    put("ip", lanAdvertiseIp)
                    put("rpcPort", activePort)
                    put("totalRamMb", curTel.totalRamMb)
                    put("availableRamMb", curTel.availableRamMb)
                    put("safeCapMb", curTel.safeCapMb)
                    val reportedThreads = if (curTel.thermalThrottleThreads > 0) curTel.thermalThrottleThreads else curTel.bigCoreCount
                    put("bigCores", curTel.bigCoreCount)
                    put("targetThreads", reportedThreads)
                    val reportedTempC = max(curTel.temperatureCelsius, curTel.internalHeatCelsius).toDouble()
                    put("tempC", reportedTempC)
                    put("batteryTempC", curTel.temperatureCelsius.toDouble())
                    put("internalTempC", curTel.internalHeatCelsius.toDouble())
                    put("tempSlopeCPerMin", curTel.tempSlopeCPerMin.toDouble())
                    put("thermalHeadroom", if (curTel.thermalHeadroom.isNaN()) 0.25 else curTel.thermalHeadroom.toDouble())
                    put("thermalStatusLabel", curTel.thermalStatusLabel)
                    put("thermalPressureScore", curTel.thermalPressureScore.toDouble())
                    put("ramPressureScore", curTel.ramPressureScore.toDouble())
                    put("thermalSensorSource", curTel.thermalSensorSource)
                    put("thermalTier", curTel.thermalScalingTier.policyCode)
                    put("loadAllocationScale", curTel.loadAllocationScale.toDouble())
                    put("recommendedContextTokens", curTel.targetContextTokens)
                    put("threadNicePriority", curTel.threadNicePriority)
                    put("shedLayers", curTel.shouldShedLayers || curTel.thermalScalingTier == ThermalScalingTier.THROTTLE_HOT || curTel.thermalScalingTier == ThermalScalingTier.SHED_LAYERS_CRITICAL)
                    put("layersShedCount", curTel.layersShedCount)
                    put("computeTflops", if (curTel.hasDotProd) 2.2 else 1.4)
                    put("memBwGbps", 25.6)
                    val storageStats = StatFs(applicationContext.filesDir.path)
                    put("storageTotalMb", storageStats.totalBytes / (1024L * 1024L))
                    put("storageFreeMb", storageStats.availableBytes / (1024L * 1024L))
                    put("cpuUsagePct", 0.0)
                    put("gpuUsagePct", 0.0)
                    put("linkType", if (isUsb || lanAdvertiseIp == "127.0.0.1") "USB-C Tunnel" else "Wi-Fi LAN")
                    val vkReady = curTel.vulkanProbePassed &&
                        curTel.hasVulkan16BitStorage &&
                        !curTel.legacyHardwareMode &&
                        !curTel.vulkanFallbackActive &&
                        curTel.thermalScalingTier != ThermalScalingTier.THROTTLE_HOT &&
                        curTel.thermalScalingTier != ThermalScalingTier.SHED_LAYERS_CRITICAL
                    put("nativeRpcReady", isNativeRpcRunning)
                    put("vulkanSupported", vkReady)
                    put("vulkan16BitStorage", vkReady)
                    put("vulkanApiVersion", curTel.vulkanApiVersion)
                    put("activeBackend", curTel.activeBackend.label)
                    put("allowHttpRegistration", true)
                    put("cachedBytes", cacheStats.cachedBytes)
                    put("cachedShards", cacheStats.layerCount)
                    put("cacheHits", cacheStats.hitCount)
                    put("cacheStatus", cacheStats.statusLabel)
                }

                val targetUrls = if (isUsb || host == "127.0.0.1") {
                    listOf("http://127.0.0.1:3000/api/cluster/nodes/register")
                } else {
                    listOf(
                        "http://$host:$httpPort/api/cluster/nodes/register",
                        "http://127.0.0.1:3000/api/cluster/nodes/register"
                    ).distinct()
                }

                for (u in targetUrls) {
                    val loopbackTarget = u.startsWith("http://127.0.0.1")
                    val payloadIp = if (loopbackTarget) "127.0.0.1" else lanAdvertiseIp
                    jsonPayload.put("ip", payloadIp)
                    jsonPayload.put("addr", "$payloadIp:$activePort")
                    jsonPayload.put("linkType", if (loopbackTarget || isUsb) "USB-C Tunnel" else "Wi-Fi LAN")
                    val ok = runCatching {
                        val url = java.net.URL(u)
                        val conn = (url.openConnection() as java.net.HttpURLConnection).apply {
                            requestMethod = "POST"
                            connectTimeout = 3000
                            readTimeout = 8000
                            doOutput = true
                            setRequestProperty("Content-Type", "application/json")
                        }
                        try {
                            conn.outputStream.use { os ->
                                    os.write(jsonPayload.toString().toByteArray(StandardCharsets.UTF_8))
                            }
                            if (conn.responseCode in 200..299) {
                                val respBody = runCatching {
                                    conn.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
                                }.getOrDefault("")
                                if (respBody.isNotBlank()) {
                                    runCatching {
                                        val json = org.json.JSONObject(respBody)
                                        val assignedLayers = json.optInt("assignedLayers", -1)
                                        val layerStart = json.optInt("layerStart", -1)
                                        val layerEnd = json.optInt("layerEnd", -1)
                                        val allocatedMb = json.optLong("allocatedMb", 0L)
                                        if (assignedLayers >= 0) {
                                            syncAssignedLayersFromMaster(
                                                layers = assignedLayers,
                                                layerStart = layerStart,
                                                layerEnd = layerEnd,
                                                allocatedMb = allocatedMb
                                            )
                                        }
                                        val recCfg = json.optJSONObject("recommendedConfig")
                                        if (recCfg != null) {
                                            val recThreads = recCfg.optInt("recommendedThreads", 0)
                                            val recRamPct = recCfg.optInt("recommendedSafeRamPct", 0)
                                            val recCtx = recCfg.optInt("recommendedContextTokens", 0)
                                            val concurrencyCapped = recCfg.optBoolean("concurrencyCapped", false)
                                            val concurrencyCap = recCfg.optInt("concurrencyCap", 0)
                                            val adaptiveLoadFactor = recCfg.optDouble("adaptiveLoadFactor", 1.0).toFloat().coerceIn(0.0f, 1.0f)
                                            val thermalPolicy = recCfg.optString("thermalPolicy", "performance")
                                            val curState = _telemetry.value
                                            val safePct = if (recRamPct in 25..50) recRamPct else (curState.safeRamPercentOverride.takeIf { it in 25..50 } ?: 50)
                                            val masterCapThreads = when {
                                                concurrencyCapped && concurrencyCap in 1..max(1, curState.totalCoreCount) -> concurrencyCap
                                                thermalPolicy == "cooldown" -> 2
                                                recThreads in 1..max(1, curState.totalCoreCount) -> recThreads
                                                else -> 0
                                            }
                                            val masterCtx = if (recCtx in 512..8192) recCtx else 0
                                            val activePrevThreads = if (curState.thermalThrottleThreads > 0) curState.thermalThrottleThreads else curState.bigCoreCount
                                            val millisSinceReload = if (lastRpcReloadEpochMs > 0L) System.currentTimeMillis() - lastRpcReloadEpochMs else Long.MAX_VALUE

                                            val reconciled = DynamicLoadBalancer.evaluate(
                                                LoadBalanceInput(
                                                    internalHeatC = curState.internalHeatCelsius,
                                                    batteryTempC = curState.temperatureCelsius,
                                                    tempSlopeCPerMin = curState.tempSlopeCPerMin,
                                                    thermalHeadroom = curState.thermalHeadroom,
                                                    thermalStatusLabel = curState.thermalStatusLabel,
                                                    availableRamMb = curState.availableRamMb,
                                                    totalRamMb = curState.totalRamMb,
                                                    lastTrimLevel = curState.lastTrimLevel,
                                                    batteryPercent = curState.batteryPercent,
                                                    isPluggedIn = curState.isPluggedIn,
                                                    totalCores = curState.totalCoreCount,
                                                    bigCores = curState.bigCoreCount,
                                                    legacyHardwareMode = curState.legacyHardwareMode,
                                                    threadOverride = curState.threadOverride,
                                                    safeRamPercentOverride = safePct,
                                                    masterAdaptiveLoadFactor = adaptiveLoadFactor,
                                                    masterRecommendedContextTokens = masterCtx,
                                                    masterThreadCap = masterCapThreads,
                                                    previousTier = curState.thermalScalingTier,
                                                    previousThreads = activePrevThreads,
                                                    previousContextTokens = curState.targetContextTokens,
                                                    millisSinceLastReload = millisSinceReload
                                                )
                                            )

                                            val changedMasterBounds = kotlin.math.abs(adaptiveLoadFactor - curState.masterAdaptiveLoadFactor) >= 0.05f ||
                                                masterCapThreads != curState.masterThreadCap ||
                                                masterCtx != curState.masterRecommendedContextTokens ||
                                                safePct != curState.safeRamPercentOverride

                                            if (changedMasterBounds || reconciled.shouldReloadRpcProcess) {
                                                if (reconciled.threadNicePriority != curState.threadNicePriority) {
                                                    runCatching { AndroidProcess.setThreadPriority(reconciled.threadNicePriority) }
                                                }
                                                _telemetry.update { st ->
                                                    val shedPlan = ThermalScalingPolicy.computeAutomaticLayerShedding(
                                                        currentLayers = st.activeLayersAssigned,
                                                        layerStart = st.assignedLayerStart,
                                                        layerEnd = st.assignedLayerEnd,
                                                        assignedRamMb = st.assignedRamMb,
                                                        decision = reconciled.toThermalScalingDecision()
                                                    )
                                                    st.copy(
                                                        safeRamPercentOverride = safePct,
                                                        masterAdaptiveLoadFactor = adaptiveLoadFactor,
                                                        masterRecommendedContextTokens = masterCtx,
                                                        masterThreadCap = masterCapThreads,
                                                        thermalScalingTier = reconciled.tier,
                                                        thermalThrottleThreads = reconciled.targetThreads,
                                                        targetContextTokens = reconciled.contextWindowTokens,
                                                        safeCapMb = reconciled.safeRamCapMb,
                                                        loadAllocationScale = reconciled.loadAllocationScale,
                                                        threadNicePriority = reconciled.threadNicePriority,
                                                        shouldShedLayers = reconciled.shouldShedLayers,
                                                        layersShedCount = if (shedPlan.didShed) shedPlan.layersShedCount else st.layersShedCount,
                                                        activeLayersAssigned = if (shedPlan.didShed) shedPlan.activeLayersAssigned else st.activeLayersAssigned,
                                                        assignedLayerStart = if (shedPlan.didShed) shedPlan.assignedLayerStart else st.assignedLayerStart,
                                                        assignedLayerEnd = if (shedPlan.didShed) shedPlan.assignedLayerEnd else st.assignedLayerEnd,
                                                        assignedRamMb = if (shedPlan.didShed) shedPlan.assignedRamMb else st.assignedRamMb,
                                                        thermalPressureScore = reconciled.thermalPressureScore,
                                                        ramPressureScore = reconciled.ramPressureScore,
                                                        loadBalancerReason = reconciled.reason
                                                    )
                                                }
                                                if (reconciled.shouldReloadRpcProcess &&
                                                    (curState.workerState == WorkerRunState.RUNNING_USB || curState.workerState == WorkerRunState.RUNNING_WIFI)
                                                ) {
                                                    appendLog("[load-balancer] Master sync (ALF=${"%.2f".format(adaptiveLoadFactor)}, cap=${masterCapThreads}, ctx=${reconciled.contextWindowTokens}, threads=${reconciled.targetThreads}) -> reloading RPC worker.")
                                                    lastRpcReloadEpochMs = System.currentTimeMillis()
                                                    launchNativeOrEmbeddedRpcServer(curState.usbLoopbackOnly, curState.clusterSecret)
                                                }
                                            }
                                        }
                                    }
                                }
                                true
                            } else {
                                false
                            }
                        } finally {
                            conn.disconnect()
                        }
                    }.getOrDefault(false)
                    if (ok) break
                }
            }
        }
    }

    fun stopWorkerAndFreeRam(reason: String = "Manual Stop") {
        heartbeatJob?.cancel()
        heartbeatJob = null
        monitorJob?.cancel()
        rpcServerJob?.cancel()
        runCatching { serverSocket?.close() }
        serverSocket = null
        runCatching { nativeProcess?.destroyForcibly() }
        nativeProcess = null
        unregisterFromMasterAsync()
        System.gc()

        val freshMem = HardwareProbe.readProcMemInfo()
        _telemetry.update {
            it.copy(
                workerState = WorkerRunState.STOPPED,
                activeLayersAssigned = 0,
                availableRamMb = freshMem.availableMb,
                safeCapMb = freshMem.safeCapMb
            )
        }
        appendLog("Worker stopped & RAM freed ($reason). Available RAM: ${freshMem.availableMb} MB")
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Clusterd Non-Root RPC Worker",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Persistent guard notification for clusterd RPC worker with 1-Tap RAM release"
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildForegroundNotification(statusText: String): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, RpcService::class.java).apply {
            action = ACTION_STOP_FREE_RAM
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val snap = _telemetry.value
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Silica Cluster • ${snap.workerState.badge}")
            .setContentText(statusText)
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    "$statusText\nBind: ${snap.bindAddress} • Temp: ${"%.1f".format(snap.temperatureCelsius)}°C • Battery: ${snap.batteryPercent}%"
                )
            )
            .setOngoing(true)
            .setContentIntent(contentPendingIntent)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Stop & Free RAM",
                stopPendingIntent
            )
            .build()
    }

    private fun updateNotification() {
        val snap = _telemetry.value
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val text = "SafeCap: ${snap.safeCapMb}MB (<=50%) • ${snap.bigCoreCount} Big Cores • ${"%.1f".format(snap.temperatureCelsius)}°C"
        nm.notify(NOTIFICATION_ID, buildForegroundNotification(text))
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        stopWorkerAndFreeRam("App swiped from recents")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        if (activeService === this) {
            activeService = null
        }
        stopWorkerAndFreeRam("Service destroyed")
        serviceScope.cancel()
        super.onDestroy()
    }
}

/**
 * Multi-generation Android 9 (API 28) through Android 16 (API 36) non-root hardware probe.
 */
object HardwareProbe {

    data class MemInfoResult(
        val totalMb: Long,
        val availableMb: Long,
        val safeCapMb: Long
    )

    data class CpuProbeResult(
        val bigCoreCount: Int,
        val totalCoreCount: Int,
        val hasDotProd: Boolean,
        val hasI8mm: Boolean,
        val featuresSummary: String
    )

    data class VulkanProbeResult(
        val supported: Boolean,
        val reason: String,
        val hasVulkanCompute: Boolean = supported,
        val has16BitStorage: Boolean = supported,
        val vulkanApiVersion: String = if (supported) "1.3" else "None",
        val deviceName: String = if (supported) "Vulkan Compute GPU" else "ARMv8.0-A NEON CPU",
        val probeDurationMs: Long = 0L,
        val driverStable: Boolean = supported,
        val vulkanHardwareLevel: Int = if (supported) 1 else 0,
        val vulkanDeqpLevel: Int = 0,
        val eglRenderer: String = "",
        val maxPushConstantsSize: Int = if (supported) MIN_VULKAN_PUSH_CONSTANTS_SIZE else 0,
        val fallbackToArmv80Neon: Boolean = false
    )

    data class VulkanPhysicalDevicePropsParsed(
        val maxPushConstantsSize: Int? = null,
        val hasVkKhr16BitStorage: Boolean? = null,
        val rpcBackendGpuEnabled: Boolean? = null,
        val driverReliable: Boolean? = null,
        val hasVulkanCompute: Boolean? = null,
        val vulkanApiVersionCode: Int? = null
    )

    data class NativeAndroidGpuApis(
        val vulkanVersionCode: Int,
        val vulkanHardwareLevel: Int,
        val vulkanDeqpLevel: Int,
        val hasVulkanComputeFeature: Boolean,
        val reqGlEsVersion: Int,
        val eglRenderer: String,
        val eglVendor: String,
        val eglGlVersion: String,
        val hasFp16GlExtension: Boolean?
    )

    data class BatteryThermalResult(
        val levelPercent: Int,
        val isPlugged: Boolean,
        val tempCelsius: Float,
        val thermalHeadroom: Float,
        val thermalStatusLabel: String,
        val internalHeatCelsius: Float = tempCelsius,
        val sensorSource: String = "battery"
    )

    /**
     * Strictly reads MemTotal and MemAvailable from /proc/meminfo (ignoring SwapFree / zram).
     * Locks safe_cap_mb = min(MemTotal * 0.50, MemAvailable - 512).
     */
    fun readProcMemInfo(): MemInfoResult {
        var memTotalKb = 4_194_304L // 4 GB fallback
        var memAvailKb = 2_097_152L // 2 GB fallback
        try {
            File("/proc/meminfo").forEachLine { line ->
                when {
                    line.startsWith("MemTotal:") -> {
                        memTotalKb = line.substringAfter("MemTotal:")
                            .trim()
                            .substringBefore(" ")
                            .toLongOrNull() ?: memTotalKb
                    }
                    line.startsWith("MemAvailable:") -> {
                        memAvailKb = line.substringAfter("MemAvailable:")
                            .trim()
                            .substringBefore(" ")
                            .toLongOrNull() ?: memAvailKb
                    }
                    // SwapFree and zram lines are intentionally ignored
                }
            }
        } catch (_: Exception) {
        }

        val totalMb = (memTotalKb / 1024L).coerceAtLeast(1024L)
        val availMb = (memAvailKb / 1024L).coerceAtLeast(256L)
        // Reserve minimum 1.0 GB for Android OS and system services (512MB on <= 2GB)
        val minOsReserveMb = if (totalMb <= 2048L) 512L else 1024L
        val maxPhysicalCeiling = max(256L, totalMb - minOsReserveMb)
        val availCeiling = max(256L, availMb - minOsReserveMb)
        val physicalCapRatio = if (totalMb <= 2048L) 0.40 else 0.50
        val physicalLimitMb = (totalMb * physicalCapRatio).toLong()
        val safeCapMb = min(physicalLimitMb, min(maxPhysicalCeiling, availCeiling))

        return MemInfoResult(
            totalMb = totalMb,
            availableMb = availMb,
            safeCapMb = safeCapMb
        )
    }

    data class LegacyHardwareProfile(
        val legacyHardwareMode: Boolean,
        val legacyGraphicsMode: Boolean,
        val isLowRamDevice: Boolean,
        val glEsVersionHex: Int,
        val graphicsTierLabel: String,
        val reason: String
    )

    /**
     * Detects old hardware (<= 3 GB RAM, Android 9-10, low-RAM Go devices, <= 2 cores)
     * and legacy graphics drivers (OpenGL ES < 3.1, Mali-400/T-series/G51/G52/G71/G72,
     * Adreno 3xx/4xx/5xx/61x, PowerVR, Vivante, Tegra, software GL) so UI and worker
     * subsystems automatically degrade gracefully with zero crashes or visual artifacts.
     */
    fun detectLegacyHardwareProfile(
        context: Context,
        memInfo: MemInfoResult = readProcMemInfo(),
        vkInfo: VulkanProbeResult = probeVulkan50ms(context)
    ): LegacyHardwareProfile {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
        val isLowRam = runCatching { am?.isLowRamDevice == true }.getOrDefault(false)
        val glEsVersion = runCatching {
            am?.deviceConfigurationInfo?.reqGlEsVersion ?: 0x30000
        }.getOrDefault(0x30000)

        val totalCores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        val is32BitOnly = Build.SUPPORTED_64_BIT_ABIS.isEmpty()
        val isLegacyHw = isLowRam ||
            memInfo.totalMb <= 3072L ||
            Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q ||
            totalCores <= 2 ||
            is32BitOnly

        val isOldGlEs = glEsVersion < 0x30001 // Older than OpenGL ES 3.1
        val isLegacyGfx = isOldGlEs || !vkInfo.supported || isLegacyHw

        val tierLabel = when {
            isLegacyGfx && isLegacyHw -> "Legacy-Safe (Low-RAM + Safe 2D)"
            isLegacyGfx -> "Legacy-Safe 2D Graphics (CPU Fallback)"
            else -> "Standard Hardware Accelerated"
        }

        val reasons = buildList {
            if (isLowRam) add("Android Low-RAM device")
            if (memInfo.totalMb <= 3072L) add("${memInfo.totalMb}MB RAM")
            if (is32BitOnly) add("32-bit ABI")
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q) add("Android API ${Build.VERSION.SDK_INT}")
            if (isOldGlEs) add("GLES 0x${glEsVersion.toString(16)}")
            if (!vkInfo.supported) add("CPU NEON fallback")
        }

        return LegacyHardwareProfile(
            legacyHardwareMode = isLegacyHw,
            legacyGraphicsMode = isLegacyGfx,
            isLowRamDevice = isLowRam,
            glEsVersionHex = glEsVersion,
            graphicsTierLabel = tierLabel,
            reason = if (reasons.isEmpty()) "Modern hardware & graphics" else reasons.joinToString(" • ")
        )
    }

    /**
     * Parses /proc/cpuinfo for ARMv8.0 vs ARMv8.2+ features (asimddp / dotprod, i8mm)
     * and inspects /sys/devices/system/cpu/cpuN/cpufreq/cpuinfo_max_freq for Big-Core pinning.
     */
    fun probeCpuAndCores(): CpuProbeResult {
        var featuresLine = ""
        try {
            File("/proc/cpuinfo").forEachLine { line ->
                if (line.startsWith("Features", ignoreCase = true)) {
                    featuresLine = line.substringAfter(":").trim().lowercase()
                }
            }
        } catch (_: Exception) {
        }

        val hasDotProd = featuresLine.contains("asimddp") || featuresLine.contains("dotprod")
        val hasI8mm = featuresLine.contains("i8mm")

        val totalCores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        val maxFreqs = mutableListOf<Long>()
        for (coreIdx in 0 until totalCores) {
            val freqFile = File("/sys/devices/system/cpu/cpu$coreIdx/cpufreq/cpuinfo_max_freq")
            val freq = runCatching {
                if (freqFile.canRead()) freqFile.readText().trim().toLongOrNull() else null
            }.getOrNull()
            if (freq != null && freq > 0L) {
                maxFreqs.add(freq)
            }
        }

        val bigCores = if (maxFreqs.size >= 2) {
            val peakFreq = maxFreqs.maxOrNull() ?: 0L
            val threshold = (peakFreq * 0.80).toLong()
            maxFreqs.count { it >= threshold }.coerceIn(1, totalCores)
        } else {
            max(1, totalCores / 2)
        }

        val summary = buildList {
            add("ARMv8-A NEON")
            if (hasDotProd) add("ASIMDDP (DotProd)")
            if (hasI8mm) add("I8MM")
        }.joinToString(" + ")

        return CpuProbeResult(
            bigCoreCount = bigCores,
            totalCoreCount = totalCores,
            hasDotProd = hasDotProd,
            hasI8mm = hasI8mm,
            featuresSummary = summary
        )
    }

    const val VULKAN_VERSION_1_1 = VulkanComputeProber.VULKAN_VERSION_1_1
    const val VULKAN_VERSION_1_2 = VulkanComputeProber.VULKAN_VERSION_1_2
    const val MIN_VULKAN_PUSH_CONSTANTS_SIZE = VulkanComputeProber.MIN_VULKAN_PUSH_CONSTANTS_SIZE
    const val MIN_STABLE_DEQP_LEVEL = VulkanComputeProber.MIN_STABLE_DEQP_LEVEL

    fun parsePhysicalDevicePropertiesOutput(output: String?): VulkanPhysicalDevicePropsParsed =
        VulkanComputeProber.parsePhysicalDevicePropertiesOutput(output)

    fun isVulkanDriverUnreliableLine(line: String): Boolean =
        VulkanComputeProber.isVulkanDriverUnreliableLine(line)

    fun isVulkan16BitStorageFailureLine(line: String): Boolean =
        VulkanComputeProber.isVulkan16BitStorageFailureLine(line)

    fun formatVulkanVersion(versionCode: Int): String =
        VulkanComputeProber.formatVulkanVersion(versionCode)

    fun probeNativeAndroidGpuApis(context: Context?): NativeAndroidGpuApis =
        VulkanComputeProber.probeNativeAndroidGpuApis(context)

    fun evaluateVulkanComputeSupport(
        sdkInt: Int,
        hardwareSignature: String,
        vulkanLibExists: Boolean,
        vulkanVersionCode: Int,
        hasVulkanComputeFeature: Boolean,
        hasHardwareIcd: Boolean,
        nativeProbeOutput: String? = null,
        nativeProbeExitCode: Int? = null,
        elapsedMs: Long = 0L,
        vulkanHardwareLevel: Int = 1,
        vulkanDeqpLevel: Int = 0,
        eglRenderer: String = "",
        eglVendor: String = "",
        hasFp16GlExtension: Boolean? = null,
        reqGlEsVersion: Int = 0x30001,
        driverMarkedUnstable: Boolean = false,
        maxPushConstantsSize: Int? = null,
        hasVkKhr16BitStorageExt: Boolean? = null,
        requireExplicit16BitVerification: Boolean = true
    ): VulkanProbeResult = VulkanComputeProber.evaluateVulkanComputeSupport(
        sdkInt = sdkInt,
        hardwareSignature = hardwareSignature,
        vulkanLibExists = vulkanLibExists,
        vulkanVersionCode = vulkanVersionCode,
        hasVulkanComputeFeature = hasVulkanComputeFeature,
        hasHardwareIcd = hasHardwareIcd,
        nativeProbeOutput = nativeProbeOutput,
        nativeProbeExitCode = nativeProbeExitCode,
        elapsedMs = elapsedMs,
        vulkanHardwareLevel = vulkanHardwareLevel,
        vulkanDeqpLevel = vulkanDeqpLevel,
        eglRenderer = eglRenderer,
        eglVendor = eglVendor,
        hasFp16GlExtension = hasFp16GlExtension,
        reqGlEsVersion = reqGlEsVersion,
        driverMarkedUnstable = driverMarkedUnstable,
        maxPushConstantsSize = maxPushConstantsSize,
        hasVkKhr16BitStorageExt = hasVkKhr16BitStorageExt,
        requireExplicit16BitVerification = requireExplicit16BitVerification
    )

    fun selectOptimalComputeBackend(
        vk: VulkanProbeResult,
        cpu: CpuProbeResult,
        legacyHardwareMode: Boolean,
        vulkanFallbackActive: Boolean = false
    ): ComputeBackend = VulkanComputeProber.selectOptimalComputeBackend(
        vk = vk,
        cpu = cpu,
        legacyHardwareMode = legacyHardwareMode,
        vulkanFallbackActive = vulkanFallbackActive
    )

    fun probeVulkan50ms(
        context: Context? = null,
        nativeBinary: File? = null,
        libDir: String? = null
    ): VulkanProbeResult = VulkanComputeProber.probe(
        context = context,
        nativeBinary = nativeBinary,
        libDir = libDir
    )

    /**
     * Multi-generation thermal & battery sensor reader:
     * - Reads BatteryManager.EXTRA_TEMPERATURE AND internal SoC/CPU/skin zones under /sys/class/thermal/thermal_zoneN
     * - API >= 30: PowerManager.getThermalHeadroom(5)
     * - API >= 29: PowerManager.getCurrentThermalStatus()
     */
    fun readBatteryAndThermal(context: Context): BatteryThermalResult {
        val intent: Intent? = runCatching {
            context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }.getOrNull()

        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, 100) ?: 100
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100)?.coerceAtLeast(1) ?: 100
        val percent = ((level * 100f) / scale.toFloat()).toInt().coerceIn(0, 100)
        val plugged = (intent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
        val batteryTenthsC = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 310) ?: 310
        var batteryTempC = batteryTenthsC / 10.0f

        val (sysfsInternalC, sysfsZoneName) = readInternalThermalSensors()
        if (batteryTempC <= 5.0f) {
            batteryTempC = sysfsInternalC ?: 31.0f
        }
        val internalHeatC = max(batteryTempC, sysfsInternalC ?: batteryTempC)
        val sensorSource = if (sysfsInternalC != null && !sysfsZoneName.isNullOrBlank()) {
            "battery + $sysfsZoneName"
        } else {
            "battery"
        }

        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        var headroom = Float.NaN
        var statusLabel = "API28_BATTERY_SENSOR"

        if (pm != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                headroom = runCatching { pm.getThermalHeadroom(5) }.getOrDefault(Float.NaN)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val status = runCatching { pm.currentThermalStatus }.getOrDefault(0)
                statusLabel = when (status) {
                    PowerManager.THERMAL_STATUS_NONE -> "NONE"
                    PowerManager.THERMAL_STATUS_LIGHT -> "LIGHT"
                    PowerManager.THERMAL_STATUS_MODERATE -> "MODERATE"
                    PowerManager.THERMAL_STATUS_SEVERE -> "SEVERE"
                    PowerManager.THERMAL_STATUS_CRITICAL -> "CRITICAL"
                    PowerManager.THERMAL_STATUS_EMERGENCY -> "EMERGENCY"
                    PowerManager.THERMAL_STATUS_SHUTDOWN -> "SHUTDOWN"
                    else -> "STATUS_$status"
                }
            }
        }

        if (headroom.isNaN()) {
            // Derive synthetic normalized headroom from internal heat relative to 48°C throttle threshold
            headroom = ((internalHeatC - 25.0f) / (ThermalScalingPolicy.INTERNAL_THROTTLE_THRESHOLD_C - 25.0f)).coerceIn(0.05f, 1.20f)
        }

        return BatteryThermalResult(
            levelPercent = percent,
            isPlugged = plugged,
            tempCelsius = batteryTempC,
            thermalHeadroom = headroom,
            thermalStatusLabel = statusLabel,
            internalHeatCelsius = internalHeatC,
            sensorSource = sensorSource
        )
    }

    /**
     * Scans readable non-root Linux/Android thermal zones (/sys/class/thermal/thermal_zoneN)
     * prioritizing internal SoC/CPU/skin/GPU sensors to capture real internal heat before battery lags.
     */
    internal fun parseThermalZoneCelsius(raw: Long): Float? {
        val celsius = when {
            raw > 1000L -> raw / 1000.0f
            raw > 150L -> raw / 10.0f
            else -> raw.toFloat()
        }
        return if (celsius in 5.0f..85.0f) celsius else null
    }

    fun readInternalThermalSensors(): Pair<Float?, String?> {
        return try {
            val zones = File("/sys/class/thermal")
                .listFiles { file -> file.name.startsWith("thermal_zone") }
                ?: return null to null
            val preferredKeywords = listOf("cpu", "soc", "tsens", "skin", "ap_ntc", "gpu", "mtktscpu", "xo_therm", "quiet_therm", "exynos")
            val preferred = mutableListOf<Pair<Float, String>>()
            val fallback = mutableListOf<Pair<Float, String>>()

            for (zone in zones) {
                val raw = runCatching { File(zone, "temp").readText().trim().toLongOrNull() }.getOrNull() ?: continue
                val celsius = parseThermalZoneCelsius(raw) ?: continue
                val zoneType = runCatching {
                    val typeFile = File(zone, "type")
                    if (typeFile.canRead()) typeFile.readText().trim() else zone.name
                }.getOrDefault(zone.name)
                val entry = celsius to zoneType
                if (preferredKeywords.any { zoneType.lowercase().contains(it) }) {
                    preferred.add(entry)
                } else {
                    fallback.add(entry)
                }
            }

            fun median(entries: List<Pair<Float, String>>): Pair<Float?, String?> {
                if (entries.isEmpty()) return null to null
                val sorted = entries.sortedBy { it.first }
                val chosen = sorted[sorted.size / 2]
                return chosen.first to chosen.second
            }

            val preferredResult = median(preferred)
            if (preferredResult.first != null) preferredResult else median(fallback)
        } catch (_: Exception) {
            null to null
        }
    }

    fun computeHmacSha256(secret: String, payload: ByteArray): String {
        return try {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(secret.toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
            mac.doFinal(payload).joinToString("") { "%02x".format(it) }
        } catch (_: Exception) {
            ""
        }
    }
}
