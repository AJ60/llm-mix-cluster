package com.example.service

import android.content.ComponentCallbacks2
import java.io.ByteArrayInputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Local JVM unit tests for the Android Silica Worker RPC service telemetry,
 * 32-byte auth token verification, strict <=50% Zero-OOM RAM caps, and
 * Master shard residency synchronization.
 */
class RpcServiceWorkerTest {

    @Test
    fun `v10 request MAC matches cross-language golden bytes`() {
        val secret = "clusterd-default-hmac-key"
        val request = java.util.HexFormat.of().parseHex(
            "4e4f4e430102030405060708553eeee87b27826b508f437839f305883caf3a19"
        )
        val nonce = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        assertTrue(RpcService.decodeV10Request(secret, request).contentEquals(nonce))
        assertNull(RpcService.decodeV10Request("wrong-secret", request))
    }

    @Test
    fun `thermal zone parser rejects bogus clamped soc sensor`() {
        assertEquals(27.0f, HardwareProbe.parseThermalZoneCelsius(27000)!!, 0.001f)
        assertEquals(37.0f, HardwareProbe.parseThermalZoneCelsius(37000)!!, 0.001f)
        assertEquals(45.0f, HardwareProbe.parseThermalZoneCelsius(45)!!, 0.001f)
        assertNull(HardwareProbe.parseThermalZoneCelsius(100))
        assertNull(HardwareProbe.parseThermalZoneCelsius(95000))
    }

    @Test
    fun `readFully assembles fragmented bandwidth probe frames`() {
        val data = byteArrayOf(0x42, 0x57, 0x50, 0x52, 0x00, 0x04, 0x00, 0x00)
        val target = ByteArray(8)
        assertTrue(RpcService.readFully(ByteArrayInputStream(data), target))
        assertArrayEquals(data, target)
        assertFalse(RpcService.readFully(ByteArrayInputStream(byteArrayOf(1, 2)), ByteArray(4)))
    }

    @Test
    fun `computeClusterAuthToken is deterministic and verifies valid secrets`() {
        val secret = "silica-test-cluster-secret-2026"
        val tokenA = RpcService.computeClusterAuthToken(secret)
        val tokenB = RpcService.computeClusterAuthToken(secret)

        assertEquals(32, tokenA.size)
        assertArrayEquals(tokenA, tokenB)
        assertTrue(RpcService.verifyClusterAuthToken(secret, tokenA))
        assertFalse(RpcService.verifyClusterAuthToken("wrong-secret", tokenA))
        assertFalse(RpcService.verifyClusterAuthToken(secret, ByteArray(16)))
    }

    @Test
    fun `syncAssignedLayersFromMaster updates active RAM residency and clears cleanly at zero`() {
        RpcService.syncAssignedLayersFromMaster(
            layers = 12,
            layerStart = 50,
            layerEnd = 61,
            allocatedMb = 1640L
        )

        val synced = RpcService.telemetry.value
        assertEquals(12, synced.activeLayersAssigned)
        assertEquals(50, synced.assignedLayerStart)
        assertEquals(61, synced.assignedLayerEnd)
        assertEquals(1640L, synced.assignedRamMb)

        RpcService.syncAssignedLayersFromMaster(
            layers = 0,
            layerStart = -1,
            layerEnd = -1,
            allocatedMb = 0L
        )
        val cleared = RpcService.telemetry.value
        assertEquals(0, cleared.activeLayersAssigned)
        assertEquals(-1, cleared.assignedLayerStart)
        assertEquals(-1, cleared.assignedLayerEnd)
        assertEquals(0L, cleared.assignedRamMb)
    }

    @Test
    fun `applyEngineOverrides enforces strict 50 percent physical RAM ceiling`() {
        RpcService.applyEngineOverrides(
            threads = 6,
            safeRamPercent = 90,
            restartWorkerIfRunning = false
        )

        val state = RpcService.telemetry.value
        assertEquals(50, state.safeRamPercentOverride)
        val effectiveTotal = if (state.totalRamMb > 0L) state.totalRamMb else 4096L
        assertTrue(
            "safeCapMb (${state.safeCapMb}) must never exceed 50% physical RAM (${effectiveTotal / 2L})",
            state.safeCapMb <= effectiveTotal / 2L
        )
    }

    @Test
    fun `evaluateVulkanComputeSupport enables Vulkan FP16 only when 16-bit storage is verified`() {
        val modernCpu = HardwareProbe.CpuProbeResult(
            bigCoreCount = 4,
            totalCoreCount = 8,
            hasDotProd = true,
            hasI8mm = true,
            featuresSummary = "ARMv8-A NEON + ASIMDDP (DotProd) + I8MM"
        )

        val okVk = HardwareProbe.evaluateVulkanComputeSupport(
            sdkInt = 34,
            hardwareSignature = "qcom kalama sm8550",
            vulkanLibExists = true,
            vulkanVersionCode = 0x00403000, // Vulkan 1.3
            hasVulkanComputeFeature = true,
            hasHardwareIcd = true,
            nativeProbeOutput = "ggml_vulkan: 0 = Adreno (TM) 740 | fp16: 1 | 16bit_storage: 1",
            nativeProbeExitCode = 0,
            elapsedMs = 12L
        )
        assertTrue(okVk.supported)
        assertTrue(okVk.hasVulkanCompute)
        assertTrue(okVk.has16BitStorage)
        assertEquals("1.3", okVk.vulkanApiVersion)
        assertEquals(
            ComputeBackend.VULKAN_FP16,
            HardwareProbe.selectOptimalComputeBackend(okVk, modernCpu, legacyHardwareMode = false)
        )
    }

    @Test
    fun `missing Vulkan 16-bit storage forces hard switch to ARMv8_0_A NEON CPU backend`() {
        val modernCpu = HardwareProbe.CpuProbeResult(
            bigCoreCount = 4,
            totalCoreCount = 8,
            hasDotProd = true,
            hasI8mm = false,
            featuresSummary = "ARMv8-A NEON + ASIMDDP (DotProd)"
        )
        val baselineCpu = modernCpu.copy(hasDotProd = false, hasI8mm = false)

        // 1. Vulkan 1.0 (< 1.1) lacks core VK_KHR_16bit_storage -> hard ARMv8.0-A NEON switch
        val vk10 = HardwareProbe.evaluateVulkanComputeSupport(
            sdkInt = 33,
            hardwareSignature = "mt6895 dimensity",
            vulkanLibExists = true,
            vulkanVersionCode = 0x00400000, // Vulkan 1.0
            hasVulkanComputeFeature = true,
            hasHardwareIcd = true
        )
        assertFalse(vk10.supported)
        assertFalse(vk10.has16BitStorage)
        assertTrue(vk10.fallbackToArmv80Neon)
        assertEquals(
            ComputeBackend.ARM_V8_0_NEON,
            HardwareProbe.selectOptimalComputeBackend(vk10, modernCpu, legacyHardwareMode = false)
        )
        assertEquals(
            ComputeBackend.ARM_V8_0_NEON,
            HardwareProbe.selectOptimalComputeBackend(vk10, baselineCpu, legacyHardwareMode = false)
        )

        // 2. Native startup probe reports missing VK_KHR_16bit_storage / storageBuffer16BitAccess
        val probeRejected = HardwareProbe.evaluateVulkanComputeSupport(
            sdkInt = 34,
            hardwareSignature = "exynos s5e9925",
            vulkanLibExists = true,
            vulkanVersionCode = 0x00403000,
            hasVulkanComputeFeature = true,
            hasHardwareIcd = true,
            nativeProbeOutput = "ggml_vulkan: device lacks VK_KHR_16bit_storage (storageBuffer16BitAccess=0)",
            nativeProbeExitCode = 0
        )
        assertFalse(probeRejected.supported)
        assertFalse(probeRejected.has16BitStorage)
        assertTrue(probeRejected.fallbackToArmv80Neon)
        assertEquals(
            ComputeBackend.ARM_V8_0_NEON,
            HardwareProbe.selectOptimalComputeBackend(probeRejected, modernCpu, legacyHardwareMode = false)
        )

        // 3. Software renderer (SwiftShader) rejected cleanly -> ARMv8.0-A NEON CPU
        val swRenderer = HardwareProbe.evaluateVulkanComputeSupport(
            sdkInt = 34,
            hardwareSignature = "ranchu swiftshader",
            vulkanLibExists = true,
            vulkanVersionCode = 0x00403000,
            hasVulkanComputeFeature = true,
            hasHardwareIcd = false
        )
        assertFalse(swRenderer.supported)
        assertFalse(swRenderer.hasVulkanCompute)
        assertTrue(swRenderer.fallbackToArmv80Neon)
        assertEquals(
            ComputeBackend.ARM_V8_0_NEON,
            HardwareProbe.selectOptimalComputeBackend(swRenderer, modernCpu, legacyHardwareMode = false)
        )

        // 4. Runtime stderr detector catches 16-bit storage errors
        assertTrue(HardwareProbe.isVulkan16BitStorageFailureLine("ggml_vulkan: error missing VK_KHR_16bit_storage"))
        assertTrue(HardwareProbe.isVulkan16BitStorageFailureLine("Vulkan device lost: VK_ERROR_DEVICE_LOST"))
        assertFalse(HardwareProbe.isVulkan16BitStorageFailureLine("ggml_vulkan: 0 = Adreno 740 (uma: 1, fp16: 1)"))
    }

    @Test
    fun `ThermalScalingPolicy boosts priority and load below 40C and throttles or sheds layers above 48C`() {
        // 1. Cool (< 40°C): elevated priority (nice -8), full performance threads, 4096 ctx, 100% load
        val cool = ThermalScalingPolicy.evaluate(
            internalHeatC = 35.0f,
            batteryTempC = 33.5f,
            totalCores = 8,
            bigCores = 4,
            totalRamMb = 8192L
        )
        assertEquals(ThermalScalingTier.BOOST_COOL, cool.tier)
        assertEquals(-8, cool.threadNicePriority)
        assertEquals(6, cool.targetThreads)
        assertEquals(4096, cool.contextWindowTokens)
        assertEquals(1.0f, cool.loadAllocationScale, 0.001f)
        assertFalse(cool.shouldShedLayers)

        // 2. Warm balanced (40°C .. 48°C): progressive step-down of threads, context window, and load
        val warmMid = ThermalScalingPolicy.evaluate(
            internalHeatC = 42.0f,
            batteryTempC = 38.0f,
            totalCores = 8,
            bigCores = 4,
            totalRamMb = 8192L,
            previousTier = cool.tier
        )
        assertEquals(ThermalScalingTier.BALANCED_WARM, warmMid.tier)
        assertEquals(5, warmMid.targetThreads)
        assertEquals(3072, warmMid.contextWindowTokens)
        assertEquals(0.85f, warmMid.loadAllocationScale, 0.001f)
        assertEquals(0, warmMid.threadNicePriority)
        assertFalse(warmMid.shouldShedLayers)

        val warmUpper = ThermalScalingPolicy.evaluate(
            internalHeatC = 46.5f,
            batteryTempC = 40.0f,
            totalCores = 8,
            bigCores = 4,
            totalRamMb = 8192L,
            previousTier = warmMid.tier
        )
        assertEquals(ThermalScalingTier.BALANCED_WARM, warmUpper.tier)
        assertEquals(4, warmUpper.targetThreads)
        assertEquals(2048, warmUpper.contextWindowTokens)
        assertEquals(0.70f, warmUpper.loadAllocationScale, 0.001f)
        assertFalse(warmUpper.allowGpuOffload)

        // 3. Internal heat exceeds 48°C (even when battery lags at 39°C): progressive throttle + layer shedding
        val hot49 = ThermalScalingPolicy.evaluate(
            internalHeatC = 49.0f,
            batteryTempC = 39.0f,
            totalCores = 8,
            bigCores = 4,
            totalRamMb = 8192L,
            previousTier = warmUpper.tier
        )
        assertEquals(ThermalScalingTier.THROTTLE_HOT, hot49.tier)
        assertEquals(2, hot49.targetThreads)
        assertEquals(1024, hot49.contextWindowTokens)
        assertEquals(0.50f, hot49.loadAllocationScale, 0.001f)
        assertTrue(hot49.threadNicePriority > 0)
        assertTrue(hot49.shouldShedLayers)
        assertFalse(hot49.allowGpuOffload)

        val hot51 = ThermalScalingPolicy.evaluate(
            internalHeatC = 51.0f,
            batteryTempC = 41.0f,
            totalCores = 8,
            bigCores = 4,
            totalRamMb = 8192L,
            previousTier = hot49.tier
        )
        assertEquals(ThermalScalingTier.THROTTLE_HOT, hot51.tier)
        assertEquals(1, hot51.targetThreads)
        assertEquals(1024, hot51.contextWindowTokens)
        assertEquals(0.25f, hot51.loadAllocationScale, 0.001f)
        assertTrue(hot51.shouldShedLayers)

        // 4. Critical internal heat (>= 52°C): full layer shed (0% load)
        val critical = ThermalScalingPolicy.evaluate(
            internalHeatC = 52.5f,
            batteryTempC = 42.0f,
            totalCores = 8,
            bigCores = 4,
            totalRamMb = 8192L,
            previousTier = hot51.tier
        )
        assertEquals(ThermalScalingTier.SHED_LAYERS_CRITICAL, critical.tier)
        assertEquals(0.0f, critical.loadAllocationScale, 0.001f)
        assertEquals(512, critical.contextWindowTokens)
        assertTrue(critical.shouldShedLayers)

        // 5. Hysteresis prevents flapping right at 39.2°C after throttling; resumes BOOST_COOL below 38.5°C
        val hysteresisHold = ThermalScalingPolicy.evaluate(
            internalHeatC = 39.2f,
            batteryTempC = 36.0f,
            totalCores = 8,
            bigCores = 4,
            totalRamMb = 8192L,
            previousTier = ThermalScalingTier.THROTTLE_HOT
        )
        assertEquals(ThermalScalingTier.BALANCED_WARM, hysteresisHold.tier)

        val recovered = ThermalScalingPolicy.evaluate(
            internalHeatC = 38.0f,
            batteryTempC = 35.0f,
            totalCores = 8,
            bigCores = 4,
            totalRamMb = 8192L,
            previousTier = ThermalScalingTier.THROTTLE_HOT
        )
        assertEquals(ThermalScalingTier.BOOST_COOL, recovered.tier)
        assertEquals(-8, recovered.threadNicePriority)
    }

    @Test
    fun `native Android hardware API probe rejects unstable drivers and non-fp16 EGL renderers`() {
        val modernCpu = HardwareProbe.CpuProbeResult(
            bigCoreCount = 4,
            totalCoreCount = 8,
            hasDotProd = true,
            hasI8mm = true,
            featuresSummary = "ARMv8-A NEON + ASIMDDP (DotProd) + I8MM"
        )

        // 1. Native EGL14 renderer identifies Adreno 619 even when Build.HARDWARE is generic "qcom"
        val adreno619 = HardwareProbe.evaluateVulkanComputeSupport(
            sdkInt = 34,
            hardwareSignature = "qcom",
            vulkanLibExists = true,
            vulkanVersionCode = 0x00403000,
            hasVulkanComputeFeature = true,
            hasHardwareIcd = true,
            vulkanHardwareLevel = 1,
            vulkanDeqpLevel = 0x07E70301,
            eglRenderer = "Adreno (TM) 619",
            eglVendor = "Qualcomm",
            hasFp16GlExtension = true
        )
        assertFalse(adreno619.supported)
        assertFalse(adreno619.has16BitStorage)
        assertFalse(adreno619.driverStable)
        assertTrue(adreno619.fallbackToArmv80Neon)
        assertEquals(
            ComputeBackend.ARM_V8_0_NEON,
            HardwareProbe.selectOptimalComputeBackend(adreno619, modernCpu, legacyHardwareMode = false)
        )

        // 2. Persisted unstable driver latch immediately blocks GPU offload even on flagship Adreno 740
        val unstableFlagship = HardwareProbe.evaluateVulkanComputeSupport(
            sdkInt = 34,
            hardwareSignature = "qcom kalama sm8550",
            vulkanLibExists = true,
            vulkanVersionCode = 0x00403000,
            hasVulkanComputeFeature = true,
            hasHardwareIcd = true,
            vulkanHardwareLevel = 1,
            vulkanDeqpLevel = 0x07E70301,
            eglRenderer = "Adreno (TM) 740",
            eglVendor = "Qualcomm",
            hasFp16GlExtension = true,
            driverMarkedUnstable = true
        )
        assertFalse(unstableFlagship.supported)
        assertFalse(unstableFlagship.driverStable)
        assertTrue(unstableFlagship.fallbackToArmv80Neon)
        assertEquals(
            ComputeBackend.ARM_V8_0_NEON,
            HardwareProbe.selectOptimalComputeBackend(unstableFlagship, modernCpu, legacyHardwareMode = false)
        )

        // 3. Low Vulkan dEQP conformance date (< 2020-03-01) marks driver unstable
        val lowDeqp = HardwareProbe.evaluateVulkanComputeSupport(
            sdkInt = 33,
            hardwareSignature = "qcom",
            vulkanLibExists = true,
            vulkanVersionCode = 0x00401000,
            hasVulkanComputeFeature = true,
            hasHardwareIcd = true,
            vulkanHardwareLevel = 1,
            vulkanDeqpLevel = 0x07E30301, // 2019-03-01 < MIN_STABLE_DEQP_LEVEL
            eglRenderer = "Mali-G78"
        )
        assertFalse(lowDeqp.supported)
        assertFalse(lowDeqp.driverStable)

        // 4. Minimal Vulkan hardware level 0 without verified native 16-bit storage forces NEON CPU
        val level0 = HardwareProbe.evaluateVulkanComputeSupport(
            sdkInt = 33,
            hardwareSignature = "mt6895",
            vulkanLibExists = true,
            vulkanVersionCode = 0x00401000,
            hasVulkanComputeFeature = true,
            hasHardwareIcd = true,
            vulkanHardwareLevel = 0,
            eglRenderer = "Mali-G610 MC6"
        )
        assertFalse(level0.supported)
        assertFalse(level0.has16BitStorage)

        // 5. Missing fp16 GL extension from native EGL14 probe rejects unverified GPU offload
        val noFp16Ext = HardwareProbe.evaluateVulkanComputeSupport(
            sdkInt = 34,
            hardwareSignature = "exynos",
            vulkanLibExists = true,
            vulkanVersionCode = 0x00403000,
            hasVulkanComputeFeature = true,
            hasHardwareIcd = true,
            vulkanHardwareLevel = 1,
            vulkanDeqpLevel = 0x07E70301,
            eglRenderer = "Xclipse 920",
            hasFp16GlExtension = false
        )
        assertFalse(noFp16Ext.supported)
        assertFalse(noFp16Ext.has16BitStorage)
    }

    @Test
    fun `RpcInitializer forces ARM NEON CPU backend with Vulkan disabled when fp16 or driver stability fails`() {
        val modernCpu = HardwareProbe.CpuProbeResult(
            bigCoreCount = 4,
            totalCoreCount = 8,
            hasDotProd = true,
            hasI8mm = true,
            featuresSummary = "ARMv8-A NEON + ASIMDDP (DotProd) + I8MM"
        )
        val coolThermal = ThermalScalingPolicy.evaluate(
            internalHeatC = 34.0f,
            batteryTempC = 32.0f,
            totalCores = 8,
            bigCores = 4,
            totalRamMb = 8192L
        )
        val rejectedVk = HardwareProbe.evaluateVulkanComputeSupport(
            sdkInt = 34,
            hardwareSignature = "qcom",
            vulkanLibExists = true,
            vulkanVersionCode = 0x00401000,
            hasVulkanComputeFeature = true,
            hasHardwareIcd = true,
            eglRenderer = "Adreno (TM) 619"
        )

        val neonPlan = RpcInitializer.buildInitializationPlan(
            vk = rejectedVk,
            cpu = modernCpu,
            thermal = coolThermal,
            legacyHardwareMode = false,
            vulkanFallbackActive = false,
            hostIp = "0.0.0.0",
            port = 50055,
            activeBinPath = "/data/app/lib/arm64/librpc_server_exec.so",
            cacheDirPath = "/data/user/0/com.example/cache/tensor_cache",
            libDir = "/data/app/lib/arm64",
            nativeDir = "/data/app/lib/arm64"
        )

        assertTrue(neonPlan.forceNeonCpu)
        assertEquals(ComputeBackend.ARM_V8_0_NEON, neonPlan.selectedBackend)
        assertTrue(neonPlan.cliFlags.contains("-d"))
        assertTrue(neonPlan.cliFlags.contains("CPU"))
        assertEquals("", neonPlan.processEnv["GGML_VK_VISIBLE_DEVICES"])
        assertEquals("1", neonPlan.processEnv["GGML_VK_DISABLE_F16"])
        assertEquals("0", neonPlan.processEnv["GGML_VK_FORCE_MAX_ALLOCATION_SIZE"])
        assertEquals("1", neonPlan.processEnv["SILICA_ZERO_FALLBACK_CPU"])
        assertNull(neonPlan.processEnv["GGML_ARM_DISABLE_DOTPROD"])
        assertNull(neonPlan.processEnv["GGML_ARM_DISABLE_I8MM"])
        assertEquals("ARMv8.0-A NEON", neonPlan.processEnv["SILICA_CPU_EXEC_PATH"])
        assertEquals("close", neonPlan.processEnv["OMP_PROC_BIND"])
        assertEquals("cores", neonPlan.processEnv["OMP_PLACES"])
        assertEquals("1", neonPlan.processEnv["SILICA_ARM_NEON_FASTPATH"])
        assertTrue(neonPlan.shellCommand.contains("-d CPU"))

        // Verified Vulkan 1.3 + fp16 16-bit storage + stable driver enables VULKAN_FP16 without -d CPU
        val verifiedVk = HardwareProbe.evaluateVulkanComputeSupport(
            sdkInt = 34,
            hardwareSignature = "qcom kalama sm8550",
            vulkanLibExists = true,
            vulkanVersionCode = 0x00403000,
            hasVulkanComputeFeature = true,
            hasHardwareIcd = true,
            vulkanHardwareLevel = 1,
            vulkanDeqpLevel = 0x07E70301,
            eglRenderer = "Adreno (TM) 740",
            eglVendor = "Qualcomm",
            hasFp16GlExtension = true,
            nativeProbeOutput = "ggml_vulkan: 0 = Adreno (TM) 740 | fp16: 1 | 16bit_storage: 1",
            nativeProbeExitCode = 0
        )
        val vulkanPlan = RpcInitializer.buildInitializationPlan(
            vk = verifiedVk,
            cpu = modernCpu,
            thermal = coolThermal,
            legacyHardwareMode = false,
            vulkanFallbackActive = false,
            hostIp = "0.0.0.0",
            port = 50055,
            activeBinPath = "/data/app/lib/arm64/librpc_server_exec.so",
            cacheDirPath = "/data/user/0/com.example/cache/tensor_cache",
            libDir = "/data/app/lib/arm64",
            nativeDir = "/data/app/lib/arm64"
        )

        assertFalse(vulkanPlan.forceNeonCpu)
        assertEquals(ComputeBackend.VULKAN_FP16, vulkanPlan.selectedBackend)
        assertEquals("VULKAN_FP16_VERIFIED", vulkanPlan.driverStabilityStatus)
        assertFalse(vulkanPlan.cliFlags.contains("-d"))
        assertFalse(vulkanPlan.processEnv.containsKey("GGML_VK_VISIBLE_DEVICES"))
    }

    @Test
    fun `Vulkan PhysicalDeviceProperties with low maxPushConstantsSize or missing VK_KHR_16bit_storage disables GPU and falls back to ARMv8_0_A NEON`() {
        val modernCpu = HardwareProbe.CpuProbeResult(
            bigCoreCount = 4,
            totalCoreCount = 8,
            hasDotProd = true,
            hasI8mm = true,
            featuresSummary = "ARMv8-A NEON + ASIMDDP (DotProd) + I8MM"
        )
        val coolThermal = ThermalScalingPolicy.evaluate(
            internalHeatC = 33.0f,
            batteryTempC = 31.5f,
            totalCores = 8,
            bigCores = 4,
            totalRamMb = 8192L
        )

        // 1. Parse rpc_server.cpp probe output with low maxPushConstantsSize (128 < 256)
        val lowPushOutput = "ggml_vulkan: 0 = Mali-G76 | api=1.2 | VK_KHR_16bit_storage=1 | 16bit_storage: 1 | maxPushConstantsSize=128"
        val parsed = HardwareProbe.parsePhysicalDevicePropertiesOutput(lowPushOutput)
        assertEquals(128, parsed.maxPushConstantsSize)
        assertEquals(true, parsed.hasVkKhr16BitStorage)
        assertEquals(0x00402000, parsed.vulkanApiVersionCode)

        val lowPushVk = HardwareProbe.evaluateVulkanComputeSupport(
            sdkInt = 34,
            hardwareSignature = "exynos",
            vulkanLibExists = true,
            vulkanVersionCode = 0x00402000,
            hasVulkanComputeFeature = true,
            hasHardwareIcd = true,
            nativeProbeOutput = lowPushOutput,
            nativeProbeExitCode = 0
        )
        assertFalse(lowPushVk.supported)
        assertTrue(lowPushVk.fallbackToArmv80Neon)
        assertEquals(128, lowPushVk.maxPushConstantsSize)
        assertEquals(
            ComputeBackend.ARM_V8_0_NEON,
            HardwareProbe.selectOptimalComputeBackend(lowPushVk, modernCpu, legacyHardwareMode = false)
        )

        val lowPushPlan = RpcInitializer.buildInitializationPlan(
            vk = lowPushVk,
            cpu = modernCpu,
            thermal = coolThermal,
            legacyHardwareMode = false,
            vulkanFallbackActive = false,
            hostIp = "0.0.0.0",
            port = 50055,
            activeBinPath = "/data/app/lib/arm64/librpc_server_exec.so",
            cacheDirPath = "/data/user/0/com.example/cache/tensor_cache",
            libDir = "/data/app/lib/arm64",
            nativeDir = "/data/app/lib/arm64"
        )
        assertTrue(lowPushPlan.forceNeonCpu)
        assertEquals(ComputeBackend.ARM_V8_0_NEON, lowPushPlan.selectedBackend)
        assertEquals("FORCED_ARMV80_NEON_LOW_PUSH_CONSTANTS", lowPushPlan.driverStabilityStatus)
        assertEquals("1", lowPushPlan.processEnv["SILICA_GPU_BACKEND_DISABLED"])
        assertEquals("ARMv8.0-A NEON", lowPushPlan.processEnv["SILICA_CPU_EXEC_PATH"])
        assertEquals("", lowPushPlan.processEnv["GGML_VK_VISIBLE_DEVICES"])
        assertTrue(lowPushPlan.threads >= 4)
        assertTrue(lowPushPlan.cliFlags.contains("-d"))
        assertTrue(lowPushPlan.cliFlags.contains("CPU"))

        // 2. Device lacking VK_KHR_16bit_storage even with 256B push constants disables GPU -> ARMv8.0-A NEON
        val no16BitVk = HardwareProbe.evaluateVulkanComputeSupport(
            sdkInt = 34,
            hardwareSignature = "qcom sm8450",
            vulkanLibExists = true,
            vulkanVersionCode = 0x00403000,
            hasVulkanComputeFeature = true,
            hasHardwareIcd = true,
            maxPushConstantsSize = 256,
            hasVkKhr16BitStorageExt = false
        )
        assertFalse(no16BitVk.supported)
        assertFalse(no16BitVk.has16BitStorage)
        assertTrue(no16BitVk.fallbackToArmv80Neon)
        assertEquals(
            ComputeBackend.ARM_V8_0_NEON,
            HardwareProbe.selectOptimalComputeBackend(no16BitVk, modernCpu, legacyHardwareMode = false)
        )
    }

    @Test
    fun `RpcInitializer buildImmediateNeonFallbackPlan forces seamless ARM NEON execution on runtime driver fault`() {
        val modernCpu = HardwareProbe.CpuProbeResult(
            bigCoreCount = 4,
            totalCoreCount = 8,
            hasDotProd = true,
            hasI8mm = true,
            featuresSummary = "ARMv8-A NEON + ASIMDDP (DotProd) + I8MM"
        )
        val coolThermal = ThermalScalingPolicy.evaluate(
            internalHeatC = 34.0f,
            batteryTempC = 32.0f,
            totalCores = 8,
            bigCores = 4,
            totalRamMb = 8192L
        )

        VulkanComputeProber.invalidateCache()
        val fallbackPlan = RpcInitializer.buildImmediateNeonFallbackPlan(
            context = null,
            cpu = modernCpu,
            thermal = coolThermal,
            legacyHardwareMode = false,
            failureReason = "VK_ERROR_DEVICE_LOST",
            hostIp = "0.0.0.0",
            port = 50055,
            activeBinPath = "/data/app/lib/arm64/librpc_server_exec.so",
            cacheDirPath = "/data/user/0/com.example/cache/tensor_cache",
            libDir = "/data/app/lib/arm64",
            nativeDir = "/data/app/lib/arm64"
        )

        assertTrue(fallbackPlan.forceNeonCpu)
        assertEquals(ComputeBackend.ARM_V8_0_NEON, fallbackPlan.selectedBackend)
        assertEquals("1", fallbackPlan.processEnv["SILICA_GPU_BACKEND_DISABLED"])
        assertEquals("1", fallbackPlan.processEnv["SILICA_ZERO_FALLBACK_CPU"])
        assertNull(fallbackPlan.processEnv["GGML_ARM_DISABLE_DOTPROD"])
        assertNull(fallbackPlan.processEnv["GGML_ARM_DISABLE_I8MM"])
        assertEquals("ARMv8.0-A NEON", fallbackPlan.processEnv["SILICA_CPU_EXEC_PATH"])
        assertEquals("", fallbackPlan.processEnv["GGML_VK_VISIBLE_DEVICES"])
        assertEquals("1", fallbackPlan.processEnv["GGML_VK_DISABLE_F16"])
        assertEquals("1", fallbackPlan.processEnv["SILICA_ARM_NEON_FASTPATH"])
        assertEquals("close", fallbackPlan.processEnv["OMP_PROC_BIND"])
        assertEquals("cores", fallbackPlan.processEnv["OMP_PLACES"])
        assertTrue(fallbackPlan.cliFlags.contains("-d"))
        assertTrue(fallbackPlan.cliFlags.contains("CPU"))
        assertTrue(fallbackPlan.shellCommand.contains("SILICA_GPU_BACKEND_DISABLED=1"))
        assertTrue(fallbackPlan.shellCommand.contains("SILICA_ZERO_FALLBACK_CPU=1"))
    }

    @Test
    fun `DynamicLoadBalancer maximizes cool compute while tapering proactively below 40C thermal limit`() {
        // 1. Cool & ample RAM: full 6 perf threads, 4096 ctx, 100% scale, GPU eligible, <=50% Zero-OOM cap
        val cool = DynamicLoadBalancer.evaluate(
            LoadBalanceInput(
                internalHeatC = 34.0f,
                batteryTempC = 32.5f,
                tempSlopeCPerMin = 0.0f,
                thermalHeadroom = 0.20f,
                thermalStatusLabel = "NONE",
                availableRamMb = 5200L,
                totalRamMb = 8192L,
                totalCores = 8,
                bigCores = 4
            )
        )
        assertEquals(ThermalScalingTier.BOOST_COOL, cool.tier)
        assertEquals(6, cool.targetThreads)
        assertEquals(4096, cool.contextWindowTokens)
        assertEquals(4096L, cool.safeRamCapMb)
        assertEquals(1.0f, cool.loadAllocationScale, 0.001f)
        assertEquals(-8, cool.threadNicePriority)
        assertTrue(cool.allowGpuOffload)
        assertFalse(cool.shouldShedLayers)

        // 2. Soft pre-40C taper (38.0C): drops 1 thread before breaching 40C
        val softTaper = DynamicLoadBalancer.evaluate(
            LoadBalanceInput(
                internalHeatC = 38.0f,
                batteryTempC = 35.5f,
                tempSlopeCPerMin = 0.4f,
                thermalHeadroom = 0.45f,
                thermalStatusLabel = "NONE",
                availableRamMb = 5200L,
                totalRamMb = 8192L,
                totalCores = 8,
                bigCores = 4,
                previousThreads = 6,
                previousContextTokens = 4096,
                millisSinceLastReload = 20_000L
            )
        )
        assertEquals(ThermalScalingTier.BOOST_COOL, softTaper.tier)
        assertEquals(5, softTaper.targetThreads)
        assertEquals(4096, softTaper.contextWindowTokens)
        assertEquals(0.90f, softTaper.loadAllocationScale, 0.001f)
        assertTrue(softTaper.shouldReloadRpcProcess)

        // 3. Tight pre-40C ceiling guard (39.1C or steep +4.0C/min heating slope at 37.8C): trims threads & context
        val preCeiling = DynamicLoadBalancer.evaluate(
            LoadBalanceInput(
                internalHeatC = 39.1f,
                batteryTempC = 36.5f,
                tempSlopeCPerMin = 0.2f,
                thermalHeadroom = 0.55f,
                thermalStatusLabel = "NONE",
                availableRamMb = 5200L,
                totalRamMb = 8192L,
                totalCores = 8,
                bigCores = 4
            )
        )
        assertEquals(ThermalScalingTier.BOOST_COOL, preCeiling.tier)
        assertEquals(4, preCeiling.targetThreads)
        assertEquals(3072, preCeiling.contextWindowTokens)
        assertEquals(0.78f, preCeiling.loadAllocationScale, 0.001f)
        assertFalse(preCeiling.allowGpuOffload)

        val fastHeating = DynamicLoadBalancer.evaluate(
            LoadBalanceInput(
                internalHeatC = 37.8f,
                batteryTempC = 35.0f,
                tempSlopeCPerMin = 4.5f, // projects 40.05C in 30s
                thermalHeadroom = 0.50f,
                thermalStatusLabel = "NONE",
                availableRamMb = 5200L,
                totalRamMb = 8192L,
                totalCores = 8,
                bigCores = 4
            )
        )
        assertEquals(4, fastHeating.targetThreads)
        assertEquals(2048, fastHeating.contextWindowTokens)
        assertEquals(0.78f, fastHeating.loadAllocationScale, 0.001f)
    }

    @Test
    fun `DynamicLoadBalancer enforces system thermal throttling signals even below 40C`() {
        val moderate = DynamicLoadBalancer.evaluate(
            LoadBalanceInput(
                internalHeatC = 36.5f,
                batteryTempC = 34.0f,
                thermalStatusLabel = "MODERATE",
                availableRamMb = 5200L,
                totalRamMb = 8192L,
                totalCores = 8,
                bigCores = 4
            )
        )
        assertEquals(ThermalScalingTier.BALANCED_WARM, moderate.tier)
        assertEquals(4, moderate.targetThreads)
        assertEquals(2048, moderate.contextWindowTokens)
        assertTrue(moderate.loadAllocationScale <= 0.70f)
        assertFalse(moderate.allowGpuOffload)

        val severe = DynamicLoadBalancer.evaluate(
            LoadBalanceInput(
                internalHeatC = 38.0f,
                batteryTempC = 35.0f,
                thermalStatusLabel = "SEVERE",
                availableRamMb = 5200L,
                totalRamMb = 8192L,
                totalCores = 8,
                bigCores = 4,
                previousThreads = 6,
                previousContextTokens = 4096,
                millisSinceLastReload = 1_000L
            )
        )
        assertEquals(ThermalScalingTier.THROTTLE_HOT, severe.tier)
        assertTrue(severe.targetThreads <= 2)
        assertTrue(severe.contextWindowTokens <= 1024)
        assertTrue(severe.shouldShedLayers)
        assertTrue(severe.shouldReloadRpcProcess)

        val critical = DynamicLoadBalancer.evaluate(
            LoadBalanceInput(
                internalHeatC = 39.0f,
                batteryTempC = 36.0f,
                thermalStatusLabel = "CRITICAL",
                availableRamMb = 5200L,
                totalRamMb = 8192L,
                totalCores = 8,
                bigCores = 4
            )
        )
        assertEquals(ThermalScalingTier.SHED_LAYERS_CRITICAL, critical.tier)
        assertEquals(1, critical.targetThreads)
        assertEquals(512, critical.contextWindowTokens)
        assertEquals(0.0f, critical.loadAllocationScale, 0.001f)
        assertTrue(critical.shouldShedLayers)
    }

    @Test
    fun `DynamicLoadBalancer scales context window threads and safeCapMb strictly by available RAM`() {
        // 1. Moderate RAM availability on 8GB device (1900MB free < 24%) -> 3072 ctx, 4 threads, safeCap <= 1900-1024
        val modRam = DynamicLoadBalancer.evaluate(
            LoadBalanceInput(
                internalHeatC = 33.0f,
                batteryTempC = 31.0f,
                availableRamMb = 1900L,
                totalRamMb = 8192L,
                totalCores = 8,
                bigCores = 4
            )
        )
        assertEquals(4, modRam.targetThreads)
        assertEquals(3072, modRam.contextWindowTokens)
        assertTrue("safeCapMb (${modRam.safeRamCapMb}) must leave 1024MB OS reserve", modRam.safeRamCapMb <= 876L)

        // 2. Tight RAM availability (1100MB free) -> 2 threads, 1024 ctx, shed layers
        val tightRam = DynamicLoadBalancer.evaluate(
            LoadBalanceInput(
                internalHeatC = 33.0f,
                batteryTempC = 31.0f,
                availableRamMb = 1100L,
                totalRamMb = 8192L,
                totalCores = 8,
                bigCores = 4
            )
        )
        assertEquals(ThermalScalingTier.THROTTLE_HOT, tightRam.tier)
        assertEquals(2, tightRam.targetThreads)
        assertEquals(1024, tightRam.contextWindowTokens)
        assertEquals(256L, tightRam.safeRamCapMb)
        assertTrue(tightRam.shouldShedLayers)

        // 3. Critical RAM availability (300MB free) -> 512 ctx, 1 thread, emergency shed
        val critRam = DynamicLoadBalancer.evaluate(
            LoadBalanceInput(
                internalHeatC = 33.0f,
                batteryTempC = 31.0f,
                availableRamMb = 300L,
                totalRamMb = 8192L,
                totalCores = 8,
                bigCores = 4
            )
        )
        assertEquals(ThermalScalingTier.SHED_LAYERS_CRITICAL, critRam.tier)
        assertEquals(1, critRam.targetThreads)
        assertEquals(512, critRam.contextWindowTokens)
        assertEquals(0.0f, critRam.loadAllocationScale, 0.001f)

        // 4. Reload cooldown prevents non-emergency flapping, while RpcInitializer applies dynamic context/threads
        val suppressedReload = DynamicLoadBalancer.evaluate(
            LoadBalanceInput(
                internalHeatC = 38.0f,
                batteryTempC = 35.0f,
                availableRamMb = 1900L,
                totalRamMb = 8192L,
                totalCores = 8,
                bigCores = 4,
                previousThreads = 6,
                previousContextTokens = 4096,
                millisSinceLastReload = 4_000L
            )
        )
        assertFalse(suppressedReload.shouldReloadRpcProcess)

        val modernCpu = HardwareProbe.CpuProbeResult(
            bigCoreCount = 4,
            totalCoreCount = 8,
            hasDotProd = true,
            hasI8mm = true,
            featuresSummary = "ARMv8-A NEON + ASIMDDP (DotProd) + I8MM"
        )
        val noVk = HardwareProbe.evaluateVulkanComputeSupport(
            sdkInt = 34,
            hardwareSignature = "qcom",
            vulkanLibExists = false,
            vulkanVersionCode = 0,
            hasVulkanComputeFeature = false,
            hasHardwareIcd = false
        )
        val plan = RpcInitializer.buildInitializationPlan(
            vk = noVk,
            cpu = modernCpu,
            loadBalance = modRam,
            legacyHardwareMode = false,
            vulkanFallbackActive = true,
            hostIp = "0.0.0.0",
            port = 50055,
            activeBinPath = "/data/app/lib/arm64/librpc_server_exec.so",
            cacheDirPath = "/data/user/0/com.example/cache/tensor_cache",
            libDir = "/data/app/lib/arm64",
            nativeDir = "/data/app/lib/arm64"
        )
        assertEquals(4, plan.threads)
        assertEquals(3072, plan.contextWindowTokens)
        assertEquals("3072", plan.processEnv["SILICA_CONTEXT_WINDOW"])
        assertTrue(plan.cliFlags.contains("-c"))
        assertTrue(plan.cliFlags.contains("-d"))
        assertTrue(plan.cliFlags.contains("CPU"))
    }

    @Test
    fun `startup sequence requires explicit VK_KHR_16bit_storage verification and forces multi-threaded NEON CPU fallback when absent`() {
        val modernCpu = HardwareProbe.CpuProbeResult(
            bigCoreCount = 4,
            totalCoreCount = 8,
            hasDotProd = true,
            hasI8mm = true,
            featuresSummary = "ARMv8-A NEON + ASIMDDP (DotProd) + I8MM"
        )
        val coolLoadBalance = DynamicLoadBalancer.evaluate(
            LoadBalanceInput(
                internalHeatC = 33.5f,
                batteryTempC = 32.0f,
                availableRamMb = 5000L,
                totalRamMb = 8192L,
                totalCores = 8,
                bigCores = 4
            )
        )

        // 1. Unverified VK_KHR_16bit_storage (probe output lacks confirmation) -> seamless multi-threaded NEON fallback
        val unverifiedVk = HardwareProbe.evaluateVulkanComputeSupport(
            sdkInt = 34,
            hardwareSignature = "qcom kalama sm8550",
            vulkanLibExists = true,
            vulkanVersionCode = 0x00403000,
            hasVulkanComputeFeature = true,
            hasHardwareIcd = true,
            vulkanHardwareLevel = 1,
            vulkanDeqpLevel = 0x07E70301,
            eglRenderer = "Adreno (TM) 740",
            eglVendor = "Qualcomm",
            hasFp16GlExtension = true,
            nativeProbeOutput = null,
            nativeProbeExitCode = null
        )
        assertFalse(unverifiedVk.supported)
        assertFalse(unverifiedVk.has16BitStorage)

        val fallbackExec = RpcInitializer.executeStartupSequence(
            cpu = modernCpu,
            vk = unverifiedVk,
            loadBalance = coolLoadBalance,
            activeBinPath = "/data/app/lib/arm64/librpc_server_exec.so",
            cacheDirPath = "/data/user/0/com.example/cache/tensor_cache",
            libDir = "/data/app/lib/arm64",
            nativeDir = "/data/app/lib/arm64"
        )
        assertFalse(fallbackExec.snapshot.vulkanReady)
        assertTrue(fallbackExec.snapshot.neonFallbackEnforced)
        assertTrue(fallbackExec.snapshot.effectiveFallback)
        assertEquals(ComputeBackend.ARM_V8_0_NEON, fallbackExec.snapshot.selectedBackend)
        assertTrue(fallbackExec.initialPlan.forceNeonCpu)
        assertEquals(6, fallbackExec.initialPlan.threads)
        assertEquals("6", fallbackExec.initialPlan.processEnv["GGML_NUM_THREADS"])
        assertEquals("6", fallbackExec.initialPlan.processEnv["SILICA_NEON_THREADS"])
        assertEquals("6", fallbackExec.initialPlan.processEnv["OMP_NUM_THREADS"])
        assertEquals("6", fallbackExec.initialPlan.processEnv["OPENBLAS_NUM_THREADS"])
        assertEquals("1", fallbackExec.initialPlan.processEnv["SILICA_GPU_BACKEND_DISABLED"])
        assertEquals("1", fallbackExec.initialPlan.processEnv["SILICA_ZERO_FALLBACK_CPU"])
        assertNull(fallbackExec.initialPlan.processEnv["GGML_ARM_DISABLE_DOTPROD"])
        assertNull(fallbackExec.initialPlan.processEnv["GGML_ARM_DISABLE_I8MM"])
        assertEquals("", fallbackExec.initialPlan.processEnv["GGML_VK_VISIBLE_DEVICES"])
        assertEquals("1", fallbackExec.initialPlan.processEnv["GGML_VK_DISABLE_F16"])
        assertEquals("1", fallbackExec.initialPlan.processEnv["SILICA_ARM_NEON_FASTPATH"])
        assertTrue(fallbackExec.initialPlan.cliFlags.contains("-c"))
        assertTrue(fallbackExec.initialPlan.cliFlags.contains("-d"))
        assertTrue(fallbackExec.initialPlan.cliFlags.contains("CPU"))

        // 2. Explicitly verified VK_KHR_16bit_storage + push constants -> enables VULKAN_FP16
        val verifiedVk = HardwareProbe.evaluateVulkanComputeSupport(
            sdkInt = 34,
            hardwareSignature = "qcom kalama sm8550",
            vulkanLibExists = true,
            vulkanVersionCode = 0x00403000,
            hasVulkanComputeFeature = true,
            hasHardwareIcd = true,
            vulkanHardwareLevel = 1,
            vulkanDeqpLevel = 0x07E70301,
            eglRenderer = "Adreno (TM) 740",
            eglVendor = "Qualcomm",
            hasFp16GlExtension = true,
            nativeProbeOutput = "ggml_vulkan: 0 = Adreno (TM) 740 | api=1.3 | VK_KHR_16bit_storage=1 | 16bit_storage: 1 | maxPushConstantsSize=256\nrpc_backend_vk_16bit_storage=1",
            nativeProbeExitCode = 0
        )
        val verifiedExec = RpcInitializer.executeStartupSequence(
            cpu = modernCpu,
            vk = verifiedVk,
            loadBalance = coolLoadBalance,
            activeBinPath = "/data/app/lib/arm64/librpc_server_exec.so",
            cacheDirPath = "/data/user/0/com.example/cache/tensor_cache",
            libDir = "/data/app/lib/arm64",
            nativeDir = "/data/app/lib/arm64"
        )
        assertTrue(verifiedExec.snapshot.vulkanReady)
        assertFalse(verifiedExec.snapshot.neonFallbackEnforced)
        assertEquals(ComputeBackend.VULKAN_FP16, verifiedExec.snapshot.selectedBackend)
        assertFalse(verifiedExec.initialPlan.forceNeonCpu)
        assertFalse(verifiedExec.initialPlan.cliFlags.contains("-d"))

        // 3. Asymmetric 1-big + 7-little SoC still launches multi-threaded NEON (>=2 threads) when unthrottled
        val singleBigCpu = modernCpu.copy(bigCoreCount = 1, totalCoreCount = 8)
        val singleBigCoolThermal = ThermalScalingPolicy.evaluate(
            internalHeatC = 33.0f,
            batteryTempC = 31.0f,
            totalCores = 8,
            bigCores = 1,
            totalRamMb = 8192L
        ).copy(targetThreads = 1)
        val resolvedNeonThreads = RpcInitializer.resolveOptimalNeonThreadCount(
            cpu = singleBigCpu,
            thermal = singleBigCoolThermal,
            legacyHardwareMode = false
        )
        assertTrue("NEON CPU fallback should stay multi-threaded on 8-core SoC", resolvedNeonThreads >= 2)
    }

    @Test
    fun `unreliable Vulkan drivers and partial VK_KHR_16bit_storage reports force NEON CPU path at startup`() {
        val modernCpu = HardwareProbe.CpuProbeResult(
            bigCoreCount = 4,
            totalCoreCount = 8,
            hasDotProd = true,
            hasI8mm = true,
            featuresSummary = "ARMv8-A NEON + ASIMDDP (DotProd) + I8MM"
        )
        val coolLoadBalance = DynamicLoadBalancer.evaluate(
            LoadBalanceInput(
                internalHeatC = 33.0f,
                batteryTempC = 31.5f,
                availableRamMb = 5200L,
                totalRamMb = 8192L,
                totalCores = 8,
                bigCores = 4
            )
        )

        // 1. Native probe reports rpc_backend_driver_reliable=0 even with 16-bit storage flags -> forced NEON CPU
        val unreliableOutput = "ggml_vulkan: 0 = Adreno (TM) 740 | api=1.3 | VK_KHR_16bit_storage=1 | 16bit_storage: 1 | maxPushConstantsSize=256\nrpc_backend_driver_reliable=0\nrpc_backend_gpu_enabled=0"
        val parsedUnreliable = HardwareProbe.parsePhysicalDevicePropertiesOutput(unreliableOutput)
        assertEquals(false, parsedUnreliable.driverReliable)
        assertEquals(false, parsedUnreliable.rpcBackendGpuEnabled)

        val unreliableVk = HardwareProbe.evaluateVulkanComputeSupport(
            sdkInt = 34,
            hardwareSignature = "qcom kalama sm8550",
            vulkanLibExists = true,
            vulkanVersionCode = 0x00403000,
            hasVulkanComputeFeature = true,
            hasHardwareIcd = true,
            vulkanHardwareLevel = 1,
            vulkanDeqpLevel = 0x07E70301,
            eglRenderer = "Adreno (TM) 740",
            hasFp16GlExtension = true,
            nativeProbeOutput = unreliableOutput,
            nativeProbeExitCode = 0
        )
        assertFalse(unreliableVk.supported)
        assertFalse(unreliableVk.driverStable)

        val unreliableExec = RpcInitializer.executeStartupSequence(
            cpu = modernCpu,
            vk = unreliableVk,
            loadBalance = coolLoadBalance,
            activeBinPath = "/data/app/lib/arm64/librpc_server_exec.so",
            cacheDirPath = "/data/user/0/com.example/cache/tensor_cache",
            libDir = "/data/app/lib/arm64",
            nativeDir = "/data/app/lib/arm64"
        )
        assertFalse(unreliableExec.snapshot.vulkanReady)
        assertTrue(unreliableExec.snapshot.neonFallbackEnforced)
        assertTrue(unreliableExec.initialPlan.forceNeonCpu)
        assertEquals("1", unreliableExec.initialPlan.processEnv["SILICA_GPU_BACKEND_DISABLED"])
        assertEquals("1", unreliableExec.initialPlan.processEnv["SILICA_ARM_NEON_FASTPATH"])
        assertEquals("", unreliableExec.initialPlan.processEnv["GGML_VK_VISIBLE_DEVICES"])
        assertTrue(unreliableExec.initialPlan.cliFlags.contains("-d"))
        assertTrue(unreliableExec.initialPlan.cliFlags.contains("CPU"))

        // 2. Software Vulkan device reported in nativeProbeOutput is rejected -> forced NEON CPU
        val swiftShaderProbe = HardwareProbe.evaluateVulkanComputeSupport(
            sdkInt = 34,
            hardwareSignature = "generic arm64",
            vulkanLibExists = true,
            vulkanVersionCode = 0x00403000,
            hasVulkanComputeFeature = true,
            hasHardwareIcd = true,
            nativeProbeOutput = "ggml_vulkan: 0 = SwiftShader Device | api=1.3 | VK_KHR_16bit_storage=1 | 16bit_storage: 1 | maxPushConstantsSize=256",
            nativeProbeExitCode = 0
        )
        assertFalse(swiftShaderProbe.supported)
        assertFalse(swiftShaderProbe.driverStable)

        // 3. VK_KHR_16bit_storage extension present, but storageBuffer16BitAccess=0 -> forced NEON CPU
        val partial16Bit = HardwareProbe.evaluateVulkanComputeSupport(
            sdkInt = 34,
            hardwareSignature = "qcom sm8550",
            vulkanLibExists = true,
            vulkanVersionCode = 0x00403000,
            hasVulkanComputeFeature = true,
            hasHardwareIcd = true,
            nativeProbeOutput = "ggml_vulkan: 0 = Adreno (TM) 740 | api=1.3 | VK_KHR_16bit_storage=1 | storageBuffer16BitAccess=0 | maxPushConstantsSize=256",
            nativeProbeExitCode = 0
        )
        assertFalse(partial16Bit.supported)
        assertFalse(partial16Bit.has16BitStorage)

        // 4. Device with VK_KHR_16bit_storage but lacking VK_QUEUE_COMPUTE_BIT -> seamless ARM NEON CPU fallback
        val noComputeQueueOutput = "ggml_vulkan: 0 = Display-Only GPU | api=1.3 | compute_queue=0 | VK_KHR_16bit_storage=1 | 16bit_storage: 1 | maxPushConstantsSize=256\nrpc_backend_vk_compute=0"
        val parsedNoCompute = HardwareProbe.parsePhysicalDevicePropertiesOutput(noComputeQueueOutput)
        assertEquals(false, parsedNoCompute.hasVulkanCompute)

        val noComputeVk = HardwareProbe.evaluateVulkanComputeSupport(
            sdkInt = 34,
            hardwareSignature = "qcom sm8550",
            vulkanLibExists = true,
            vulkanVersionCode = 0x00403000,
            hasVulkanComputeFeature = true,
            hasHardwareIcd = true,
            nativeProbeOutput = noComputeQueueOutput,
            nativeProbeExitCode = 0
        )
        assertFalse(noComputeVk.supported)
        assertFalse(noComputeVk.hasVulkanCompute)
        assertFalse(noComputeVk.driverStable)

        // 5. Unreliable Vulkan driver line detectors catch driver faults
        assertTrue(HardwareProbe.isVulkanDriverUnreliableLine("VK_ERROR_INCOMPATIBLE_DRIVER during vkCreateInstance"))
        assertTrue(HardwareProbe.isVulkanDriverUnreliableLine("vkCreateDevice failed (-3)"))
        assertTrue(HardwareProbe.isVulkanDriverUnreliableLine("vkEnumeratePhysicalDevices failed (-4)"))
        assertTrue(HardwareProbe.isVulkanDriverUnreliableLine("failed to create compute pipeline (SPIRV error)"))
        assertTrue(HardwareProbe.isVulkanDriverUnreliableLine("rpc_backend_driver_reliable=0"))

        // 6. Vulkan 1.1 (< 1.2) via PackageManager or nativeProbeOutput forces seamless multi-threaded ARM NEON fallback
        val vk11PmRejected = HardwareProbe.evaluateVulkanComputeSupport(
            sdkInt = 34,
            hardwareSignature = "qcom sm8250",
            vulkanLibExists = true,
            vulkanVersionCode = 0x00401000, // Vulkan 1.1 < 1.2
            hasVulkanComputeFeature = true,
            hasHardwareIcd = true,
            vulkanHardwareLevel = 1,
            vulkanDeqpLevel = 0x07E70301,
            eglRenderer = "Adreno (TM) 650",
            hasFp16GlExtension = true,
            nativeProbeOutput = "ggml_vulkan: 0 = Adreno (TM) 650 | api=1.1 | VK_KHR_16bit_storage=1 | 16bit_storage: 1 | maxPushConstantsSize=256",
            nativeProbeExitCode = 0
        )
        assertFalse(vk11PmRejected.supported)
        assertTrue(vk11PmRejected.fallbackToArmv80Neon)
        assertEquals(
            ComputeBackend.ARM_V8_0_NEON,
            HardwareProbe.selectOptimalComputeBackend(vk11PmRejected, modernCpu, legacyHardwareMode = false)
        )

        val vk11NativeRejected = HardwareProbe.evaluateVulkanComputeSupport(
            sdkInt = 34,
            hardwareSignature = "qcom sm8250",
            vulkanLibExists = true,
            vulkanVersionCode = 0x00402000,
            hasVulkanComputeFeature = true,
            hasHardwareIcd = true,
            vulkanHardwareLevel = 1,
            vulkanDeqpLevel = 0x07E70301,
            eglRenderer = "Adreno (TM) 650",
            hasFp16GlExtension = true,
            nativeProbeOutput = "ggml_vulkan: 0 = Adreno (TM) 650 | api=1.1 | VK_KHR_16bit_storage=1 | 16bit_storage: 1 | maxPushConstantsSize=256\nrpc_backend_gpu_enabled=0",
            nativeProbeExitCode = 0
        )
        assertFalse(vk11NativeRejected.supported)
        assertTrue(vk11NativeRejected.fallbackToArmv80Neon)
        assertTrue(vk11NativeRejected.reason.contains("< 1.2"))

        val vk11Exec = RpcInitializer.executeStartupSequence(
            cpu = modernCpu,
            vk = vk11NativeRejected,
            loadBalance = coolLoadBalance,
            activeBinPath = "/data/app/lib/arm64/librpc_server_exec.so",
            cacheDirPath = "/data/user/0/com.example/cache/tensor_cache",
            libDir = "/data/app/lib/arm64",
            nativeDir = "/data/app/lib/arm64"
        )
        assertFalse(vk11Exec.snapshot.vulkanReady)
        assertTrue(vk11Exec.snapshot.neonFallbackEnforced)
        assertEquals(ComputeBackend.ARM_V8_0_NEON, vk11Exec.snapshot.selectedBackend)
        assertEquals("1", vk11Exec.initialPlan.processEnv["SILICA_ZERO_FALLBACK_CPU"])
        assertEquals("1", vk11Exec.initialPlan.processEnv["SILICA_ARM_NEON_FASTPATH"])
        assertEquals("6", vk11Exec.initialPlan.processEnv["SILICA_NEON_THREADS"])
        assertEquals("", vk11Exec.initialPlan.processEnv["GGML_VK_VISIBLE_DEVICES"])

        val unconfirmedVersionVk = HardwareProbe.evaluateVulkanComputeSupport(
            sdkInt = 34,
            hardwareSignature = "qcom sm8550",
            vulkanLibExists = true,
            vulkanVersionCode = 0, // PackageManager omitted Vulkan version
            hasVulkanComputeFeature = true,
            hasHardwareIcd = true,
            vulkanHardwareLevel = 1,
            vulkanDeqpLevel = 0x07E70301,
            eglRenderer = "Adreno (TM) 740",
            hasFp16GlExtension = true,
            nativeProbeOutput = "ggml_vulkan: 0 = Adreno (TM) 740 | VK_KHR_16bit_storage=1 | 16bit_storage: 1 | maxPushConstantsSize=256",
            nativeProbeExitCode = 0
        )
        assertFalse(unconfirmedVersionVk.supported)
        assertTrue(unconfirmedVersionVk.fallbackToArmv80Neon)
        assertEquals(
            ComputeBackend.ARM_V8_0_NEON,
            HardwareProbe.selectOptimalComputeBackend(unconfirmedVersionVk, modernCpu, legacyHardwareMode = false)
        )
    }

    @Test
    fun `DynamicLoadBalancer grants elevated priority and full concurrency strictly below 40C and sheds layers automatically above 48C`() {
        // 1. Strictly below 40.0C (36.0C): elevated priority (nice -8), full 6 perf threads, no layer shedding
        val cool = DynamicLoadBalancer.evaluate(
            LoadBalanceInput(
                internalHeatC = 36.0f,
                batteryTempC = 33.0f,
                availableRamMb = 5400L,
                totalRamMb = 8192L,
                totalCores = 8,
                bigCores = 4
            )
        )
        assertEquals(ThermalScalingTier.BOOST_COOL, cool.tier)
        assertEquals(-8, cool.threadNicePriority)
        assertEquals(6, cool.targetThreads)
        assertFalse(cool.shouldShedLayers)

        val noShed = ThermalScalingPolicy.computeAutomaticLayerShedding(
            currentLayers = 12,
            layerStart = 20,
            layerEnd = 31,
            assignedRamMb = 1320L,
            decision = cool.toThermalScalingDecision()
        )
        assertFalse(noShed.didShed)
        assertEquals(12, noShed.activeLayersAssigned)
        assertEquals(0, noShed.layersShedCount)

        // 2. At exact 40.0C: must NOT elevate priority (< 0) or keep full 6 threads
        val at40 = DynamicLoadBalancer.evaluate(
            LoadBalanceInput(
                internalHeatC = 40.0f,
                batteryTempC = 36.0f,
                availableRamMb = 5400L,
                totalRamMb = 8192L,
                totalCores = 8,
                bigCores = 4,
                previousTier = ThermalScalingTier.BOOST_COOL
            )
        )
        assertEquals(ThermalScalingTier.BALANCED_WARM, at40.tier)
        assertTrue("Priority must not be boosted at >=40C (was ${at40.threadNicePriority})", at40.threadNicePriority >= 0)
        assertTrue("Concurrency must drop below cool 6 threads at 40C (was ${at40.targetThreads})", at40.targetThreads < 6)
        assertFalse(at40.shouldShedLayers)

        // 3. Above 48.0C (48.5C): automatic partial layer-shedding + immediate reload transition
        val hot48_5 = DynamicLoadBalancer.evaluate(
            LoadBalanceInput(
                internalHeatC = 48.5f,
                batteryTempC = 39.0f,
                availableRamMb = 5400L,
                totalRamMb = 8192L,
                totalCores = 8,
                bigCores = 4,
                previousTier = ThermalScalingTier.BALANCED_WARM,
                previousThreads = 2,
                previousContextTokens = 1024,
                millisSinceLastReload = 1_000L
            )
        )
        assertEquals(ThermalScalingTier.THROTTLE_HOT, hot48_5.tier)
        assertTrue(hot48_5.shouldShedLayers)
        assertTrue(hot48_5.threadNicePriority > 0)
        assertTrue(hot48_5.shouldReloadRpcProcess)

        val shed48_5 = ThermalScalingPolicy.computeAutomaticLayerShedding(
            currentLayers = 12,
            layerStart = 20,
            layerEnd = 31,
            assignedRamMb = 1320L,
            decision = hot48_5.toThermalScalingDecision()
        )
        assertTrue(shed48_5.didShed)
        assertEquals(6, shed48_5.activeLayersAssigned)
        assertEquals(6, shed48_5.layersShedCount)
        assertEquals(20, shed48_5.assignedLayerStart)
        assertEquals(25, shed48_5.assignedLayerEnd)
        assertEquals(660L, shed48_5.assignedRamMb)

        // 4. Severe (>50C) & Critical (>=52C): escalates layer shedding from 75% to 100% (0 layers)
        val hot50_5 = DynamicLoadBalancer.evaluate(
            LoadBalanceInput(
                internalHeatC = 50.5f,
                batteryTempC = 41.0f,
                availableRamMb = 5400L,
                totalRamMb = 8192L,
                totalCores = 8,
                bigCores = 4,
                previousTier = ThermalScalingTier.THROTTLE_HOT
            )
        )
        val shed50_5 = ThermalScalingPolicy.computeAutomaticLayerShedding(
            currentLayers = 12,
            layerStart = 20,
            layerEnd = 31,
            assignedRamMb = 1320L,
            decision = hot50_5.toThermalScalingDecision()
        )
        assertTrue(shed50_5.didShed)
        assertEquals(3, shed50_5.activeLayersAssigned)
        assertEquals(9, shed50_5.layersShedCount)

        val crit52_5 = DynamicLoadBalancer.evaluate(
            LoadBalanceInput(
                internalHeatC = 52.5f,
                batteryTempC = 42.0f,
                availableRamMb = 5400L,
                totalRamMb = 8192L,
                totalCores = 8,
                bigCores = 4,
                previousTier = ThermalScalingTier.THROTTLE_HOT
            )
        )
        val shed52_5 = ThermalScalingPolicy.computeAutomaticLayerShedding(
            currentLayers = 12,
            layerStart = 20,
            layerEnd = 31,
            assignedRamMb = 1320L,
            decision = crit52_5.toThermalScalingDecision()
        )
        assertTrue(shed52_5.didShed)
        assertEquals(0, shed52_5.activeLayersAssigned)
        assertEquals(12, shed52_5.layersShedCount)
        assertEquals(-1, shed52_5.assignedLayerStart)
        assertEquals(-1, shed52_5.assignedLayerEnd)
        assertEquals(0L, shed52_5.assignedRamMb)
    }
}
