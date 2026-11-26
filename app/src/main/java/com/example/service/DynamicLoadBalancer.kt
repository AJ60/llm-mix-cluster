package com.example.service

import android.content.ComponentCallbacks2
import android.os.Process as AndroidProcess
import kotlin.math.max
import kotlin.math.min

data class LoadBalanceInput(
    val internalHeatC: Float,
    val batteryTempC: Float,
    val tempSlopeCPerMin: Float = 0.0f,
    val thermalHeadroom: Float = 0.25f,
    val thermalStatusLabel: String = "NONE",
    val availableRamMb: Long = 4096L,
    val totalRamMb: Long = 8192L,
    val lastTrimLevel: Int = 0,
    val batteryPercent: Int = 100,
    val isPluggedIn: Boolean = true,
    val totalCores: Int = 8,
    val bigCores: Int = 4,
    val legacyHardwareMode: Boolean = false,
    val threadOverride: Int = 0,
    val safeRamPercentOverride: Int = 0,
    val masterAdaptiveLoadFactor: Float = 1.0f,
    val masterRecommendedContextTokens: Int = 0,
    val masterThreadCap: Int = 0,
    val previousTier: ThermalScalingTier = ThermalScalingTier.BOOST_COOL,
    val previousThreads: Int = 0,
    val previousContextTokens: Int = 4096,
    val millisSinceLastReload: Long = Long.MAX_VALUE
)

data class LoadBalanceDecision(
    val tier: ThermalScalingTier,
    val targetThreads: Int,
    val contextWindowTokens: Int,
    val safeRamCapMb: Long,
    val safeRamCapPercent: Int,
    val loadAllocationScale: Float,
    val threadNicePriority: Int,
    val shouldShedLayers: Boolean,
    val allowGpuOffload: Boolean,
    val thermalPressureScore: Float,
    val ramPressureScore: Float,
    val projectedHeatC: Float,
    val shouldReloadRpcProcess: Boolean,
    val reason: String
) {
    fun toThermalScalingDecision(): ThermalScalingDecision {
        return ThermalScalingDecision(
            tier = tier,
            targetThreads = targetThreads,
            contextWindowTokens = contextWindowTokens,
            loadAllocationScale = loadAllocationScale,
            safeRamCapPercent = safeRamCapPercent,
            threadNicePriority = threadNicePriority,
            shouldShedLayers = shouldShedLayers,
            allowGpuOffload = allowGpuOffload,
            reason = reason
        )
    }
}

/**
 * Dynamic Android worker load balancer combining:
 * 1. System thermal throttling signals (PowerManager.currentThermalStatus, ADPF thermalHeadroom,
 *    internal SoC/CPU thermal zones, battery heat, and temperature slope lookahead) with proactive
 *    pre-40°C tapering to maximize throughput while staying below the 40.0°C thermal ceiling.
 * 2. Live available RAM (/proc/meminfo MemAvailable + onTrimMemory pressure) so context window size,
 *    thread concurrency, and safe RAM cap adapt cleanly with Zero-OOM guarantees.
 * 3. Bidirectional coordination with Master Adaptive Load Factor (ALF) caps and reload cooldown
 *    hysteresis to avoid native RPC process thrashing.
 */
object DynamicLoadBalancer {
    const val THERMAL_CEILING_C = ThermalScalingPolicy.COOL_BOOST_THRESHOLD_C
    const val PRE_CEILING_SOFT_TAPER_C = 37.5f
    const val PRE_CEILING_GUARD_C = 38.8f
    const val RELOAD_COOLDOWN_MS = 15_000L

    fun parseThermalStatusSeverity(label: String): Int {
        val norm = label.trim().uppercase()
        return when {
            norm.contains("CRITICAL") || norm.contains("EMERGENCY") || norm.contains("SHUTDOWN") ||
                norm == "STATUS_4" || norm == "STATUS_5" || norm == "STATUS_6" -> 4
            norm.contains("SEVERE") || norm == "STATUS_3" -> 3
            norm.contains("MODERATE") || norm == "STATUS_2" -> 2
            norm.contains("LIGHT") || norm == "STATUS_1" -> 1
            else -> 0
        }
    }

    /**
     * Computes an EMA-smoothed temperature rate of change in °C/minute.
     */
    fun updateThermalSlopeCPerMin(
        previousTempC: Float,
        currentTempC: Float,
        elapsedMs: Long,
        previousSlopeCPerMin: Float = 0.0f
    ): Float {
        if (elapsedMs < 500L || !previousTempC.isFinite() || !currentTempC.isFinite()) {
            return previousSlopeCPerMin.coerceIn(-15.0f, 15.0f)
        }
        val instantSlope = ((currentTempC - previousTempC) * 60_000.0f) / elapsedMs.toFloat()
        val clampedInstant = instantSlope.coerceIn(-15.0f, 15.0f)
        return (previousSlopeCPerMin * 0.65f + clampedInstant * 0.35f).coerceIn(-15.0f, 15.0f)
    }

    fun evaluate(input: LoadBalanceInput): LoadBalanceDecision {
        val safeTotalCores = input.totalCores.coerceAtLeast(1)
        val safeBigCores = input.bigCores.coerceIn(1, safeTotalCores)
        val safeTotalRamMb = input.totalRamMb.coerceAtLeast(1024L)
        val safeAvailRamMb = input.availableRamMb.coerceIn(256L, safeTotalRamMb)
        val effectiveHeatC = max(input.internalHeatC, input.batteryTempC)
        val positiveSlope = max(0.0f, input.tempSlopeCPerMin)
        val projectedHeatC = effectiveHeatC + positiveSlope * 0.5f

        val baseThermal = ThermalScalingPolicy.evaluate(
            internalHeatC = input.internalHeatC,
            batteryTempC = input.batteryTempC,
            thermalHeadroom = input.thermalHeadroom,
            batteryPercent = input.batteryPercent,
            isPluggedIn = input.isPluggedIn,
            totalCores = safeTotalCores,
            bigCores = safeBigCores,
            totalRamMb = safeTotalRamMb,
            legacyHardwareMode = input.legacyHardwareMode,
            threadOverride = input.threadOverride,
            previousTier = input.previousTier
        )

        val maxPerfCores = when {
            input.legacyHardwareMode -> min(safeBigCores, 2)
            safeTotalCores >= 6 -> max(4, min(safeTotalCores - 2, 6))
            else -> max(2, safeBigCores).coerceAtMost(safeTotalCores)
        }
        val boostedThreads = if (input.threadOverride > 0) {
            input.threadOverride.coerceIn(1, safeTotalCores)
        } else {
            max(safeBigCores, maxPerfCores).coerceIn(1, max(maxPerfCores, safeBigCores))
        }
        val fullContext = if (input.legacyHardwareMode || safeTotalRamMb <= 3072L) 2048 else 4096

        val statusSeverity = parseThermalStatusSeverity(input.thermalStatusLabel)
        val headroom = if (input.thermalHeadroom.isNaN()) 0.25f else input.thermalHeadroom.coerceIn(0.0f, 1.5f)

        var thermalTier = baseThermal.tier
        var thermalThreads = baseThermal.targetThreads
        var thermalCtx = baseThermal.contextWindowTokens
        var thermalLoadScale = baseThermal.loadAllocationScale
        var thermalNice = baseThermal.threadNicePriority
        var thermalShed = baseThermal.shouldShedLayers
        var thermalAllowGpu = baseThermal.allowGpuOffload
        var thermalRamPct = baseThermal.safeRamCapPercent
        var thermalReason = baseThermal.reason

        // Apply Android PowerManager thermal status, ADPF headroom, and proactive pre-40°C ceiling control
        when {
            statusSeverity >= 4 || (headroom >= 1.0f && effectiveHeatC >= 40.0f) -> {
                thermalTier = ThermalScalingTier.SHED_LAYERS_CRITICAL
                thermalThreads = 1
                thermalCtx = 512
                thermalLoadScale = 0.0f
                thermalNice = AndroidProcess.THREAD_PRIORITY_LOWEST
                thermalShed = true
                thermalAllowGpu = false
                thermalRamPct = 25
                thermalReason = "System thermal status ${input.thermalStatusLabel} (${"%.1f".format(effectiveHeatC)}°C) -> critical layer shed"
            }
            statusSeverity == 3 || headroom >= 0.92f -> {
                if (thermalTier.ordinal < ThermalScalingTier.THROTTLE_HOT.ordinal) {
                    thermalTier = ThermalScalingTier.THROTTLE_HOT
                }
                thermalThreads = min(thermalThreads, min(2, boostedThreads).coerceAtLeast(1))
                thermalCtx = min(thermalCtx, 1024)
                thermalLoadScale = min(thermalLoadScale, 0.45f)
                thermalNice = max(thermalNice, 8)
                thermalShed = true
                thermalAllowGpu = false
                thermalRamPct = min(thermalRamPct, 35)
                thermalReason = "System thermal throttling ${input.thermalStatusLabel} (headroom=${"%.2f".format(headroom)}, ${"%.1f".format(effectiveHeatC)}°C) -> $thermalThreads thread(s), ${thermalCtx} ctx"
            }
            statusSeverity == 2 || headroom >= 0.82f -> {
                if (thermalTier == ThermalScalingTier.BOOST_COOL) {
                    thermalTier = ThermalScalingTier.BALANCED_WARM
                }
                thermalThreads = min(thermalThreads, max(2, boostedThreads - 2))
                thermalCtx = min(thermalCtx, 2048)
                thermalLoadScale = min(thermalLoadScale, 0.70f)
                thermalNice = max(thermalNice, 2)
                thermalAllowGpu = false
                thermalRamPct = min(thermalRamPct, 40)
                thermalReason = "Moderate system thermal throttling (${input.thermalStatusLabel}, ${"%.1f".format(effectiveHeatC)}°C) -> capping to $thermalThreads threads, ${thermalCtx} ctx"
            }
            effectiveHeatC < THERMAL_CEILING_C && thermalTier == ThermalScalingTier.BOOST_COOL -> {
                val guardActive = statusSeverity == 1 ||
                    headroom >= 0.70f ||
                    effectiveHeatC >= PRE_CEILING_GUARD_C ||
                    projectedHeatC >= 39.6f
                val softTaperActive = !guardActive && (
                    effectiveHeatC >= PRE_CEILING_SOFT_TAPER_C ||
                        projectedHeatC >= 38.8f
                    )
                if (guardActive) {
                    val tightGuard = effectiveHeatC >= 39.3f || projectedHeatC >= 40.0f
                    thermalThreads = min(thermalThreads, max(2, boostedThreads - 2))
                    thermalCtx = min(thermalCtx, if (tightGuard) 2048 else min(fullContext, 3072))
                    thermalLoadScale = min(thermalLoadScale, 0.78f)
                    thermalNice = max(thermalNice, 0)
                    thermalAllowGpu = thermalAllowGpu && effectiveHeatC < 39.0f && statusSeverity == 0
                    thermalRamPct = min(thermalRamPct, 45)
                    thermalReason = "Pre-40°C thermal ceiling guard (${"%.1f".format(effectiveHeatC)}°C, proj=${"%.1f".format(projectedHeatC)}°C, ${input.thermalStatusLabel}) -> tapering to $thermalThreads threads, $thermalCtx ctx"
                } else if (softTaperActive) {
                    thermalThreads = min(thermalThreads, max(2, boostedThreads - 1))
                    thermalCtx = min(thermalCtx, if (projectedHeatC >= 39.2f) min(fullContext, 3072) else fullContext)
                    thermalLoadScale = min(thermalLoadScale, 0.90f)
                    thermalNice = max(thermalNice, -4)
                    thermalReason = "Pre-40°C soft taper (${"%.1f".format(effectiveHeatC)}°C, slope=+${"%.1f".format(positiveSlope)}°C/m) -> $thermalThreads threads, $thermalCtx ctx"
                }
            }
        }

        // Live Available RAM & onTrimMemory pressure evaluation
        val osReserveMb = if (safeTotalRamMb <= 2048L) 512L else 1024L
        val availHeadroomMb = max(256L, safeAvailRamMb - osReserveMb)
        val availRatio = safeAvailRamMb.toFloat() / safeTotalRamMb.toFloat()

        var ramMaxThreads = safeTotalCores
        var ramMaxCtx = fullContext
        var ramLoadScale = 1.0f
        var ramShed = false
        var ramCritical = false
        var ramNiceFloor = -8
        var ramRamPct = 50
        var ramReason = ""

        when {
            safeAvailRamMb < 400L || availRatio < 0.08f || input.lastTrimLevel >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> {
                ramMaxThreads = 1
                ramMaxCtx = 512
                ramLoadScale = 0.0f
                ramShed = true
                ramCritical = true
                ramNiceFloor = AndroidProcess.THREAD_PRIORITY_LOWEST
                ramRamPct = 25
                ramReason = "Critical RAM pressure (avail=${safeAvailRamMb}MB, trim=${input.lastTrimLevel}) -> 512 ctx, shed layers"
            }
            safeAvailRamMb < 900L || availRatio < 0.14f || input.lastTrimLevel >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> {
                ramMaxThreads = 2
                ramMaxCtx = 1024
                ramLoadScale = 0.50f
                ramShed = true
                ramNiceFloor = 6
                ramRamPct = 35
                ramReason = "Tight available RAM (${safeAvailRamMb}MB / ${safeTotalRamMb}MB) -> capping to 2 threads, 1024 ctx"
            }
            (safeAvailRamMb < 1800L && availRatio < 0.35f) || availRatio < 0.24f || input.lastTrimLevel >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE -> {
                ramMaxThreads = min(boostedThreads, 4)
                ramMaxCtx = if (safeAvailRamMb >= 1650L && availRatio >= 0.22f) min(fullContext, 3072) else 2048
                ramLoadScale = 0.80f
                ramNiceFloor = -2
                ramRamPct = 42
                ramReason = "Moderate RAM pressure (${safeAvailRamMb}MB avail) -> capping to $ramMaxThreads threads, $ramMaxCtx ctx"
            }
        }

        // Master Adaptive Load Factor / context / thread caps
        val masterFactor = input.masterAdaptiveLoadFactor.coerceIn(0.0f, 1.0f)
        val masterThreadBound = if (input.masterThreadCap > 0) {
            input.masterThreadCap.coerceIn(1, safeTotalCores)
        } else {
            safeTotalCores
        }
        val masterCtxBound = if (input.masterRecommendedContextTokens in 512..8192) {
            input.masterRecommendedContextTokens
        } else {
            4096
        }

        val finalThreads = minOf(thermalThreads, ramMaxThreads, masterThreadBound).coerceIn(1, safeTotalCores)
        val finalCtx = minOf(thermalCtx, ramMaxCtx, masterCtxBound).coerceIn(512, 4096)
        val finalLoadScale = minOf(thermalLoadScale, ramLoadScale, masterFactor).coerceIn(0.0f, 1.0f)
        val rawFinalNice = max(thermalNice, ramNiceFloor)
        // Enforce strict < 40.0°C ceiling for boosted (negative nice) thread priority
        val finalNice = if (effectiveHeatC >= THERMAL_CEILING_C) max(rawFinalNice, 0) else rawFinalNice
        val finalShed = thermalShed || ramShed || finalLoadScale <= 0.0f
        val finalAllowGpu = thermalAllowGpu && !ramShed && effectiveHeatC < 39.2f && statusSeverity < 2 && finalLoadScale >= 0.70f

        val finalTier = when {
            ramCritical || finalLoadScale <= 0.0f || thermalTier == ThermalScalingTier.SHED_LAYERS_CRITICAL ->
                ThermalScalingTier.SHED_LAYERS_CRITICAL
            ramShed && thermalTier.ordinal < ThermalScalingTier.THROTTLE_HOT.ordinal ->
                ThermalScalingTier.THROTTLE_HOT
            else -> thermalTier
        }

        var boundedRamPct = min(thermalRamPct, ramRamPct)
        if (input.legacyHardwareMode || safeTotalRamMb <= 2048L) {
            boundedRamPct = min(boundedRamPct, 40)
        }
        if (input.safeRamPercentOverride in 25..50) {
            boundedRamPct = min(boundedRamPct, input.safeRamPercentOverride)
        }
        boundedRamPct = boundedRamPct.coerceIn(25, 50)

        val hardHalfMb = max(256L, safeTotalRamMb / 2L)
        val rawPhysicalCapMb = min(hardHalfMb, max(256L, (safeTotalRamMb * boundedRamPct) / 100L))
        val scaledPhysicalMb = if (finalLoadScale <= 0.0f) {
            256L
        } else {
            max(256L, (rawPhysicalCapMb * finalLoadScale).toLong())
        }
        val finalSafeCapMb = min(hardHalfMb, min(scaledPhysicalMb, availHeadroomMb)).coerceAtLeast(256L)

        val thermalPressureScore = max(
            ((effectiveHeatC - 30.0f) / 22.0f).coerceIn(0.0f, 1.0f),
            max(statusSeverity / 4.0f, (headroom - 0.35f).coerceIn(0.0f, 1.0f))
        ).coerceIn(0.0f, 1.0f)
        val ramPressureScore = max(
            (1.0f - (availRatio / 0.35f)).coerceIn(0.0f, 1.0f),
            (input.lastTrimLevel / 15.0f).coerceIn(0.0f, 1.0f)
        ).coerceIn(0.0f, 1.0f)

        val threadsChanged = input.previousThreads > 0 && finalThreads != input.previousThreads
        val ctxChanged = input.previousContextTokens > 0 && finalCtx != input.previousContextTokens
        val enteredSheddingTier = finalShed && input.previousTier != finalTier
        val emergencyStepDown = finalTier == ThermalScalingTier.THROTTLE_HOT || ramShed
        val shouldReloadRpcProcess = finalTier != ThermalScalingTier.SHED_LAYERS_CRITICAL &&
            (threadsChanged || ctxChanged || enteredSheddingTier) &&
            (emergencyStepDown || input.millisSinceLastReload >= RELOAD_COOLDOWN_MS)

        val combinedReason = buildList {
            add(thermalReason)
            if (ramReason.isNotEmpty()) add(ramReason)
            if (masterFactor < 0.99f || input.masterThreadCap > 0) {
                add("Master ALF=${"%.2f".format(masterFactor)}")
            }
        }.joinToString(" | ")

        return LoadBalanceDecision(
            tier = finalTier,
            targetThreads = finalThreads,
            contextWindowTokens = finalCtx,
            safeRamCapMb = finalSafeCapMb,
            safeRamCapPercent = boundedRamPct,
            loadAllocationScale = finalLoadScale,
            threadNicePriority = finalNice,
            shouldShedLayers = finalShed,
            allowGpuOffload = finalAllowGpu,
            thermalPressureScore = thermalPressureScore,
            ramPressureScore = ramPressureScore,
            projectedHeatC = projectedHeatC,
            shouldReloadRpcProcess = shouldReloadRpcProcess,
            reason = combinedReason
        )
    }
}
