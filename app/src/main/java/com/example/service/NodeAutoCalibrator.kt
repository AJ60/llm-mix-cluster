package com.example.service

import android.content.Context
import java.net.InetSocketAddress
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Result of automatic on-device calibration so non-technical users never have to
 * choose thread counts, RAM caps, or GPU/CPU compute backends manually.
 */
data class CalibrationProfile(
    val isCalibrated: Boolean = false,
    val isCalibrating: Boolean = false,
    val calibratedAtLabel: String = "Not calibrated yet",
    val performanceScore: Int = 0,
    val tierLabel: String = "Auto-Tuning...",
    val recommendedSafeRamMb: Long = 1024L,
    val recommendedThreads: Int = 2,
    val recommendedBackendLabel: String = "Smart CPU Mode",
    val estimatedLayerCapacity: Int = 4,
    val benchmarkDurationMs: Long = 0L,
    val summarySentence: String = "Tap Auto-Tune to optimize memory and speed for this phone."
)

enum class DiagnosticSeverity {
    OK,
    WARNING,
    ISSUE
}

enum class AutoFixAction(val buttonLabel: String) {
    AUTO_DETECT_MASTER("Find Computer Again"),
    RECALIBRATE_DEVICE("Auto-Tune Phone"),
    START_SHARING("Start Sharing Now"),
    SWITCH_TO_USB_SAFE_MODE("Use USB Cable Mode"),
    FREE_MEMORY_AND_RESTART("Free Memory & Retry")
}

data class DiagnosticCheck(
    val id: String,
    val title: String,
    val statusText: String,
    val plainEnglishTip: String,
    val severity: DiagnosticSeverity,
    val fixAction: AutoFixAction? = null
)

data class DiagnosticReport(
    val overallStatus: DiagnosticSeverity,
    val headline: String,
    val subtext: String,
    val checks: List<DiagnosticCheck>,
    val ranAtLabel: String
)

/**
 * Automatically calibrates an Android worker node using a fast (<150ms) deterministic
 * vector dot-product micro-benchmark combined with physical RAM, thermal, battery,
 * and big-core topology probes.
 */
object NodeAutoCalibrator {

    fun runCalibration(
        context: Context,
        preProbedVk: HardwareProbe.VulkanProbeResult? = null
    ): CalibrationProfile {
        val mem = HardwareProbe.readProcMemInfo()
        val cpu = HardwareProbe.probeCpuAndCores()
        val nativeDir = context.applicationInfo.nativeLibraryDir ?: ""
        val candidateExec = listOf(
            java.io.File(nativeDir, "librpc_server_exec.so"),
            java.io.File(context.filesDir, "librpc_server_exec.so")
        ).firstOrNull { it.exists() && (it.canExecute() || it.name.endsWith(".so")) }
        val vk = preProbedVk ?: HardwareProbe.probeVulkan50ms(
            context = context,
            nativeBinary = candidateExec,
            libDir = if (candidateExec != null) "${candidateExec.parentFile?.absolutePath ?: nativeDir}:$nativeDir" else nativeDir
        )
        val legacy = HardwareProbe.detectLegacyHardwareProfile(context, mem, vk)
        val bat = HardwareProbe.readBatteryAndThermal(context)

        // Lightweight deterministic float dot-product micro-benchmark (~15-80ms)
        val benchStartNs = System.nanoTime()
        val vectorSize = 1024
        val a = FloatArray(vectorSize) { ((it % 17) - 8) * 0.125f }
        val b = FloatArray(vectorSize) { ((it % 13) - 6) * 0.15f }
        var acc = 0f
        val iterations = 120
        for (iter in 0 until iterations) {
            var sum = 0f
            for (j in 0 until vectorSize) {
                sum += a[j] * b[j]
            }
            acc += sum * 0.001f
        }
        val elapsedMs = max(1L, (System.nanoTime() - benchStartNs) / 1_000_000L)
        // Prevent dead-code elimination by folding acc into tiny tie-breaker
        val checksumAdjust = if (acc.isFinite()) 0 else -1

        // Derive normalized 15..100 score combining throughput, cores, RAM, and ISA/Vulkan
        val throughputPts = (600.0 / elapsedMs.toDouble()).coerceIn(10.0, 45.0)
        val ramPts = (mem.safeCapMb.toDouble() / 100.0).coerceIn(8.0, 30.0)
        val corePts = (cpu.bigCoreCount * 3.5).coerceIn(4.0, 15.0)
        val vk16BitReady = vk.supported &&
            vk.hasVulkanCompute &&
            vk.has16BitStorage &&
            vk.driverStable &&
            !legacy.legacyHardwareMode
        val accelBonus = when {
            vk16BitReady -> 10.0
            cpu.hasDotProd || cpu.hasI8mm -> 7.0
            else -> 2.0
        }
        val score = (throughputPts + ramPts + corePts + accelBonus + checksumAdjust)
            .roundToInt()
            .coerceIn(15, 100)

        // Thermal, battery & legacy-hardware aware automatic thread & RAM tuning
        val isWarmOrLowBattery = bat.tempCelsius >= 39.0f || (!bat.isPlugged && bat.levelPercent < 30)
        val tunedThreads = when {
            legacy.legacyHardwareMode -> min(max(1, cpu.bigCoreCount), 2)
            isWarmOrLowBattery -> max(2, min(cpu.bigCoreCount, 3))
            else -> max(cpu.bigCoreCount, min(6, max(2, cpu.totalCoreCount - 2))).coerceIn(2, cpu.totalCoreCount)
        }

        // <= 40% physical RAM on <= 2 GB / low-RAM legacy devices; strictly <= 50% on all devices
        // reserving at least 1.0 GB for Android OS and background services (512MB on <= 2GB)
        val physicalCapRatio = if (mem.totalMb <= 2048L || legacy.isLowRamDevice) 0.40 else 0.50
        val minOsReserveMb = if (mem.totalMb <= 2048L) 512L else 1024L
        val maxPhysicalCeiling = max(256L, mem.totalMb - minOsReserveMb)
        val physicalCapMb = (mem.totalMb * physicalCapRatio).toLong()
        val zeroOomCap = min(maxPhysicalCeiling, physicalCapMb)
        val tunedSafeRamMb = if (isWarmOrLowBattery) {
            min(zeroOomCap, max(256L, (zeroOomCap * 0.80).toLong()))
        } else {
            zeroOomCap
        }

        val tierLabel = when {
            legacy.legacyHardwareMode -> "Legacy-Safe Helper"
            score >= 78 && tunedSafeRamMb >= 2500L -> "Turbo Helper"
            score >= 52 && tunedSafeRamMb >= 1200L -> "Balanced Helper"
            else -> "Light Helper"
        }

        val selectedBackend = HardwareProbe.selectOptimalComputeBackend(
            vk = vk,
            cpu = cpu,
            legacyHardwareMode = legacy.legacyHardwareMode,
            vulkanFallbackActive = !vk16BitReady
        )
        val backendLabel = when (selectedBackend) {
            ComputeBackend.VULKAN_FP16 -> "Vulkan Compute (16-bit Storage)"
            ComputeBackend.ARM_V8_2_DOTPROD -> "Optimized ARMv8.2 NEON CPU"
            ComputeBackend.ARM_V8_0_NEON -> "Safe ARMv8.0 NEON CPU"
        }

        val layerCapacity = (tunedSafeRamMb / 110L).toInt().coerceIn(2, 24)
        val summary = when {
            legacy.legacyHardwareMode ->
                "Legacy-safe tuning active (${tunedSafeRamMb} MB cap, $tunedThreads thread(s), Safe ARMv8.0-A NEON CPU mode) so older hardware stays smooth."
            !vk16BitReady ->
                "Vulkan VK_KHR_16bit_storage unavailable — switched cleanly to ARMv8.0-A NEON CPU ($tunedThreads threads, ${tunedSafeRamMb} MB cap)."
            isWarmOrLowBattery ->
                "Auto-tuned in Eco Mode (${tunedSafeRamMb} MB) to keep your phone cool and save battery."
            tierLabel == "Turbo Helper" ->
                "Fast phone detected! Auto-tuned to share up to ${tunedSafeRamMb} MB smoothly in the background."
            tierLabel == "Balanced Helper" ->
                "Auto-tuned for steady background sharing (${tunedSafeRamMb} MB) without slowing your phone."
            else ->
                "Auto-tuned in Light Mode (${tunedSafeRamMb} MB) to keep everyday apps snappy and responsive."
        }

        val timestamp = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        return CalibrationProfile(
            isCalibrated = true,
            isCalibrating = false,
            calibratedAtLabel = timestamp,
            performanceScore = score,
            tierLabel = tierLabel,
            recommendedSafeRamMb = tunedSafeRamMb,
            recommendedThreads = tunedThreads,
            recommendedBackendLabel = backendLabel,
            estimatedLayerCapacity = layerCapacity,
            benchmarkDurationMs = elapsedMs,
            summarySentence = summary
        )
    }
}

/**
 * Automatic zero-jargon troubleshooting engine that checks Wi-Fi/USB connectivity,
 * computer (Master) reachability, worker sharing status, memory safety, battery/heat,
 * and calibration health, returning plain-English fixes and 1-tap auto-repair actions.
 */
object NodeAutoDiagnoser {

    fun runDiagnostics(
        context: Context,
        telemetry: DeviceTelemetryState
    ): DiagnosticReport {
        val checks = mutableListOf<DiagnosticCheck>()
        val mem = HardwareProbe.readProcMemInfo()
        val bat = HardwareProbe.readBatteryAndThermal(context)
        val lanIp = RpcService.detectLocalLanIpv4()

        // 1. Calibration Check
        if (telemetry.calibration.isCalibrated) {
            checks += DiagnosticCheck(
                id = "calibration",
                title = "Phone Speed & Memory Tuning",
                statusText = "${telemetry.calibration.tierLabel} (${telemetry.calibration.recommendedSafeRamMb} MB cap)",
                plainEnglishTip = "Your phone is auto-calibrated and ready for safe background sharing.",
                severity = DiagnosticSeverity.OK
            )
        } else {
            checks += DiagnosticCheck(
                id = "calibration",
                title = "Phone Speed & Memory Tuning",
                statusText = "Not calibrated yet",
                plainEnglishTip = "Run a quick 1-second auto-tune so this phone picks safe memory and speed limits.",
                severity = DiagnosticSeverity.WARNING,
                fixAction = AutoFixAction.RECALIBRATE_DEVICE
            )
        }

        // 2. Network / Cable Check
        val hasWifiLan = lanIp != "127.0.0.1"
        if (telemetry.usbLoopbackOnly) {
            checks += DiagnosticCheck(
                id = "network",
                title = "Connection Type",
                statusText = "USB Cable Mode Ready",
                plainEnglishTip = if (hasWifiLan) {
                    "USB mode is active. You can also scan the QR code on your computer to switch to Wi-Fi ($lanIp)."
                } else {
                    "Plug your phone into your computer with a USB cable to connect automatically."
                },
                severity = DiagnosticSeverity.OK
            )
        } else if (hasWifiLan) {
            checks += DiagnosticCheck(
                id = "network",
                title = "Wi-Fi Connection",
                statusText = "Connected to Wi-Fi ($lanIp)",
                plainEnglishTip = "Make sure your Windows or Linux computer is on this exact same Wi-Fi network.",
                severity = DiagnosticSeverity.OK
            )
        } else {
            checks += DiagnosticCheck(
                id = "network",
                title = "Wi-Fi Connection",
                statusText = "No Wi-Fi network detected",
                plainEnglishTip = "Turn on Wi-Fi on your phone, or switch to USB Cable Mode to connect over a cable.",
                severity = DiagnosticSeverity.ISSUE,
                fixAction = AutoFixAction.SWITCH_TO_USB_SAFE_MODE
            )
        }

        // 3. Computer (Master) Reachability Check
        val masterTarget = extractMasterHostPort(telemetry.pairedMasterAddress)
        if (masterTarget == null) {
            checks += DiagnosticCheck(
                id = "master_reachability",
                title = "Computer Connection",
                statusText = "Not paired with a computer yet",
                plainEnglishTip = "Open ClusterLab on your Windows or Linux PC, then tap 'Auto-Connect' or scan the QR code on screen.",
                severity = DiagnosticSeverity.WARNING,
                fixAction = AutoFixAction.AUTO_DETECT_MASTER
            )
        } else {
            val (host, port) = masterTarget
            val reachable = canReachTcpHost(host, port, timeoutMs = 650)
            if (reachable) {
                checks += DiagnosticCheck(
                    id = "master_reachability",
                    title = "Computer Connection",
                    statusText = "Reachable at $host:$port",
                    plainEnglishTip = "Your phone can talk to your computer cleanly with zero network blocks.",
                    severity = DiagnosticSeverity.OK
                )
            } else {
                val hint = if (host == "127.0.0.1") {
                    "Check that your USB cable is plugged in and ClusterLab is open on your computer."
                } else {
                    "Could not reach $host:$port. Check that ClusterLab is open on your PC, both devices share Wi-Fi, and your PC firewall allows port $port."
                }
                checks += DiagnosticCheck(
                    id = "master_reachability",
                    title = "Computer Connection",
                    statusText = "Computer not responding ($host:$port)",
                    plainEnglishTip = hint,
                    severity = DiagnosticSeverity.ISSUE,
                    fixAction = AutoFixAction.AUTO_DETECT_MASTER
                )
            }
        }

        // 4. Worker Active Status Check
        when (telemetry.workerState) {
            WorkerRunState.RUNNING_USB, WorkerRunState.RUNNING_WIFI -> {
                checks += DiagnosticCheck(
                    id = "worker_state",
                    title = "Sharing Service",
                    statusText = "Active & listening for tasks",
                    plainEnglishTip = "Your phone is online and ready whenever your computer loads an AI model.",
                    severity = DiagnosticSeverity.OK
                )
            }
            WorkerRunState.THROTTLED_THERMAL -> {
                checks += DiagnosticCheck(
                    id = "worker_state",
                    title = "Sharing Service",
                    statusText = "Paused automatically to cool down",
                    plainEnglishTip = "Safety guard paused sharing because the phone got warm or battery dropped below 15%. It will resume automatically once cool.",
                    severity = DiagnosticSeverity.WARNING
                )
            }
            WorkerRunState.SHEDDING_MEMORY -> {
                checks += DiagnosticCheck(
                    id = "worker_state",
                    title = "Sharing Service",
                    statusText = "Paused to protect phone memory",
                    plainEnglishTip = "Android needed RAM for your other apps so ClusterLab stepped aside safely. Tap below to re-tune and resume.",
                    severity = DiagnosticSeverity.ISSUE,
                    fixAction = AutoFixAction.FREE_MEMORY_AND_RESTART
                )
            }
            else -> {
                checks += DiagnosticCheck(
                    id = "worker_state",
                    title = "Sharing Service",
                    statusText = "Currently turned off",
                    plainEnglishTip = "Tap 'Start Sharing Now' so your computer can see and use this phone.",
                    severity = DiagnosticSeverity.WARNING,
                    fixAction = AutoFixAction.START_SHARING
                )
            }
        }

        // 5. Battery & Thermal Comfort Check
        val lowUnplugged = !bat.isPlugged && bat.levelPercent < 20
        val tooHot = bat.tempCelsius >= 41.0f
        if (tooHot || lowUnplugged) {
            val detail = buildString {
                if (tooHot) append("Phone temperature is ${"%.1f".format(bat.tempCelsius)}°C. ")
                if (lowUnplugged) append("Battery is ${bat.levelPercent}% and unplugged. ")
                append("Plug in a charger or let the phone rest for a minute for best speed.")
            }
            checks += DiagnosticCheck(
                id = "battery_thermal",
                title = "Battery & Temperature",
                statusText = "${bat.levelPercent}% • ${"%.1f".format(bat.tempCelsius)}°C",
                plainEnglishTip = detail,
                severity = DiagnosticSeverity.WARNING
            )
        } else {
            checks += DiagnosticCheck(
                id = "battery_thermal",
                title = "Battery & Temperature",
                statusText = "${bat.levelPercent}%${if (bat.isPlugged) " (Charging)" else ""} • ${"%.1f".format(bat.tempCelsius)}°C",
                plainEnglishTip = "Battery and temperature are in the ideal comfort zone.",
                severity = DiagnosticSeverity.OK
            )
        }

        // 6. Memory Headroom Check
        if (mem.availableMb < 768L) {
            checks += DiagnosticCheck(
                id = "memory_headroom",
                title = "Free Phone Memory",
                statusText = "Low free RAM (${mem.availableMb} MB)",
                plainEnglishTip = "Close a few background apps (like games or browsers) so your phone can share more memory smoothly.",
                severity = DiagnosticSeverity.WARNING,
                fixAction = AutoFixAction.RECALIBRATE_DEVICE
            )
        } else {
            checks += DiagnosticCheck(
                id = "memory_headroom",
                title = "Free Phone Memory",
                statusText = "${mem.safeCapMb} MB safely reserved (${mem.availableMb} MB free)",
                plainEnglishTip = "Plenty of free memory available. ClusterLab never uses more than half your phone's RAM.",
                severity = DiagnosticSeverity.OK
            )
        }

        val issueCount = checks.count { it.severity == DiagnosticSeverity.ISSUE }
        val warnCount = checks.count { it.severity == DiagnosticSeverity.WARNING }
        val overall = when {
            issueCount > 0 -> DiagnosticSeverity.ISSUE
            warnCount > 0 -> DiagnosticSeverity.WARNING
            else -> DiagnosticSeverity.OK
        }
        val headline = when (overall) {
            DiagnosticSeverity.OK -> "Everything Looks Great!"
            DiagnosticSeverity.WARNING -> "Almost Ready ($warnCount Quick Tip${if (warnCount == 1) "" else "s"})"
            DiagnosticSeverity.ISSUE -> "Found $issueCount Issue${if (issueCount == 1) "" else "s"} to Fix"
        }
        val subtext = when (overall) {
            DiagnosticSeverity.OK -> "Your phone is auto-calibrated, healthy, and ready for your cluster."
            DiagnosticSeverity.WARNING -> "Follow the highlighted tip below or tap a 1-click fix button."
            DiagnosticSeverity.ISSUE -> "Tap the 1-click fix button on the red item below to resolve it automatically."
        }
        val timestamp = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())

        return DiagnosticReport(
            overallStatus = overall,
            headline = headline,
            subtext = subtext,
            checks = checks,
            ranAtLabel = timestamp
        )
    }

    private fun extractMasterHostPort(pairedLabel: String): Pair<String, Int>? {
        val cleaned = pairedLabel.substringBefore(" ").trim()
        if (!cleaned.contains(":")) return null
        val host = cleaned.substringBefore(":")
        val port = cleaned.substringAfter(":").toIntOrNull() ?: return null
        if (host.isBlank() || port !in 1..65535) return null
        return host to port
    }

    private fun canReachTcpHost(host: String, port: Int, timeoutMs: Int): Boolean {
        return runCatching {
            Socket().use { sock ->
                sock.connect(InetSocketAddress(host, port), timeoutMs)
                true
            }
        }.getOrDefault(false)
    }
}
