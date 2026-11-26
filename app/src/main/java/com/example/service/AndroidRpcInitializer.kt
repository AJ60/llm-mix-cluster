package com.example.service

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.GLES20
import android.os.Build
import java.io.File
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.min

/**
 * Persists Vulkan driver instability verdicts keyed by Build.FINGERPRINT so an unstable
 * mobile Vulkan driver (crash, device lost, or missing VK_KHR_16bit_storage / fp16 at runtime)
 * is bypassed immediately (0ms) on subsequent launches in favor of the ARM NEON CPU backend.
 * If the device receives an OS / GPU driver OTA update (new Build.FINGERPRINT), it re-probes cleanly.
 */
object VulkanDriverStabilityStore {
    private const val PREFS_NAME = "silica_vulkan_stability_prefs"
    private const val KEY_UNSTABLE_FINGERPRINT = "unstable_driver_fingerprint"
    private const val KEY_UNSTABLE_REASON = "unstable_driver_reason"

    fun currentDeviceFingerprint(): String {
        return runCatching { Build.FINGERPRINT }.getOrNull()?.takeIf { it.isNotBlank() } ?: "unknown-fingerprint"
    }

    fun isDriverMarkedUnstable(context: Context, fingerprint: String = currentDeviceFingerprint()): Boolean {
        return runCatching {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val saved = prefs.getString(KEY_UNSTABLE_FINGERPRINT, null)
            !saved.isNullOrBlank() && saved == fingerprint
        }.getOrDefault(false)
    }

    fun markDriverUnstable(context: Context, fingerprint: String = currentDeviceFingerprint(), reason: String) {
        VulkanComputeProber.invalidateCache()
        runCatching {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_UNSTABLE_FINGERPRINT, fingerprint)
                .putString(KEY_UNSTABLE_REASON, reason.take(160))
                .apply()
        }
    }

    fun clearDriverUnstable(context: Context) {
        VulkanComputeProber.invalidateCache()
        runCatching {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .remove(KEY_UNSTABLE_FINGERPRINT)
                .remove(KEY_UNSTABLE_REASON)
                .apply()
        }
    }
}

/**
 * Optional JNI bridge to rpc_server.cpp (`librpc_server_probe.so`) for direct native
 * Vulkan VkPhysicalDeviceProperties probing (`limits.maxPushConstantsSize` and
 * `VK_KHR_16bit_storage` / `storageBuffer16BitAccess`). Fails safely to null on JVM
 * tests or when only the standalone CLI binary is packaged.
 */
object NativeVulkanBridge {
    private val libraryLoaded: Boolean by lazy {
        runCatching {
            System.loadLibrary("rpc_server_probe")
            true
        }.getOrDefault(false)
    }

    @JvmStatic
    private external fun nativeProbePhysicalDeviceProperties(requestedThreads: Int): String?

    fun probePhysicalDevicePropertiesOrNull(requestedThreads: Int = 4): String? {
        if (!libraryLoaded) return null
        return runCatching {
            nativeProbePhysicalDeviceProperties(requestedThreads.coerceAtLeast(1))
        }.getOrNull()
    }
}

/**
 * Modular, single-pass Vulkan compute & `VK_KHR_16bit_storage` prober built on native Android APIs:
 * 1. Persistent stability latch check (0ms bypass if driver previously failed on this Build.FINGERPRINT).
 * 2. Native Android `PackageManager` (`FEATURE_VULKAN_HARDWARE_VERSION`, `LEVEL`, `COMPUTE`, `DEQP_LEVEL`),
 *    `ActivityManager` (GLES compute level), hardware Vulkan ICD check, and safe 1x1 offscreen `EGL14`/`GLES20`
 *    renderer + fp16 shader extension inspection.
 * 3. Direct native `VkPhysicalDeviceProperties` (`VK_KHR_16bit_storage`, `storageBuffer16BitAccess`,
 *    `limits.maxPushConstantsSize >= 256`) verification via `NativeVulkanBridge` JNI or bounded CLI probe.
 *
 * Includes a short-TTL memoization cache so repeated startup calls (`refreshStaticHardwareSnapshot`,
 * `NodeAutoCalibrator`, `RpcInitializer`) never redundantly recreate EGL contexts or fork probes.
 */
object VulkanComputeProber {

    // Vulkan 1.1.0 (0x00401000) promoted VK_KHR_16bit_storage; Vulkan 1.2.0 (0x00402000) is required
    // for stable mobile compute kernels alongside hardware-accelerated 16-bit storage.
    const val VULKAN_VERSION_1_1 = 0x00401000
    const val VULKAN_VERSION_1_2 = 0x00402000

    // Minimum VkPhysicalDeviceLimits.maxPushConstantsSize (bytes) required by ggml-vulkan compute kernels.
    const val MIN_VULKAN_PUSH_CONSTANTS_SIZE = 256

    // Android API 30+ FEATURE_VULKAN_DEQP_LEVEL minimum stable CTS conformance date (2020-03-01 = 0x07E40301).
    const val MIN_STABLE_DEQP_LEVEL = 0x07E40301
    private const val FEATURE_VULKAN_DEQP_LEVEL_NAME = "android.software.vulkan.deqp.level"
    private const val CACHE_TTL_MS = 30_000L

    private data class CachedProbeEntry(
        val cacheKey: String,
        val timestampMs: Long,
        val result: HardwareProbe.VulkanProbeResult
    )

    @Volatile
    private var cachedProbe: CachedProbeEntry? = null

    fun invalidateCache() {
        cachedProbe = null
    }

    /**
     * Parses VkPhysicalDeviceProperties telemetry emitted by rpc_server.cpp (`--probe-vulkan-props`
     * or JNI `NativeVulkanBridge`) or ggml-vulkan device listings for `maxPushConstantsSize` and
     * `VK_KHR_16bit_storage`.
     */
    fun parsePhysicalDevicePropertiesOutput(output: String?): HardwareProbe.VulkanPhysicalDevicePropsParsed {
        if (output.isNullOrBlank()) return HardwareProbe.VulkanPhysicalDevicePropsParsed()
        val pushMatch = Regex(
            """(?:maxPushConstantsSize|max_push_constants_size)\s*[:=]\s*(\d+)""",
            RegexOption.IGNORE_CASE
        ).find(output)
        val pushBytes = pushMatch?.groupValues?.getOrNull(1)?.toIntOrNull()

        val lower = output.lowercase()
        val has16Bit = when {
            lower.contains("vk_khr_16bit_storage=0") ||
                lower.contains("vk_khr_16bit_storage: 0") ||
                lower.contains("vk_khr_16bit_storage=false") ||
                lower.contains("rpc_backend_vk_16bit_storage=0") ||
                lower.contains("16bit_storage: 0") ||
                lower.contains("16bit_storage=0") ||
                lower.contains("storagebuffer16bitaccess=0") -> false
            lower.contains("vk_khr_16bit_storage=1") ||
                lower.contains("vk_khr_16bit_storage: 1") ||
                lower.contains("vk_khr_16bit_storage=true") ||
                lower.contains("rpc_backend_vk_16bit_storage=1") ||
                lower.contains("16bit_storage: 1") ||
                lower.contains("16bit_storage=1") ||
                lower.contains("storagebuffer16bitaccess=1") -> true
            else -> null
        }
        val gpuEnabled = when {
            lower.contains("rpc_backend_gpu_enabled=0") ||
                lower.contains("rpc_backend_device=cpu") -> false
            lower.contains("rpc_backend_gpu_enabled=1") -> true
            else -> null
        }
        val driverReliable = when {
            lower.contains("rpc_backend_driver_reliable=0") -> false
            lower.contains("rpc_backend_driver_reliable=1") -> true
            else -> null
        }
        val hasComputeQueue = when {
            lower.contains("rpc_backend_vk_compute=0") ||
                lower.contains("compute_queue=0") -> false
            lower.contains("rpc_backend_vk_compute=1") ||
                lower.contains("compute_queue=1") -> true
            else -> null
        }
        val apiMatch = Regex("""\bapi\s*=\s*(\d+)\.(\d+)""", RegexOption.IGNORE_CASE).find(output)
        val parsedApiCode = apiMatch?.let { m ->
            val major = m.groupValues[1].toIntOrNull()
            val minor = m.groupValues[2].toIntOrNull()
            if (major != null && minor != null) {
                (major shl 22) or (minor shl 12)
            } else {
                null
            }
        }
        return HardwareProbe.VulkanPhysicalDevicePropsParsed(
            maxPushConstantsSize = pushBytes,
            hasVkKhr16BitStorage = has16Bit,
            rpcBackendGpuEnabled = gpuEnabled,
            driverReliable = driverReliable,
            hasVulkanCompute = hasComputeQueue,
            vulkanApiVersionCode = parsedApiCode
        )
    }

    /**
     * Detects stdout/stderr markers indicating an unreliable, crashing, or non-conformant
     * mobile Vulkan driver so RpcService can persist a stability bypass and fall back immediately
     * to the NEON-optimized CPU path.
     */
    fun isVulkanDriverUnreliableLine(line: String): Boolean {
        val lower = line.lowercase()
        val faultMarkers = listOf(
            "rpc_backend_driver_reliable=0",
            "vk_error_",
            "vkcreateinstance failed",
            "vkcreatedevice failed",
            "vkenumeratephysicaldevices failed",
            "vkenumeratedeviceextensionproperties failed",
            "failed to initialize vulkan",
            "failed to create compute pipeline",
            "shader compilation failed",
            "spirv",
            "vulkan device lost",
            "incompatible driver",
            "driver crash",
            "broken vulkan",
            "timed out",
            "sigsegv",
            "sigill",
            "sigabrt",
            "sigbus"
        )
        return faultMarkers.any { lower.contains(it) }
    }

    /**
     * Detects stdout/stderr messages emitted by ggml-vulkan or mobile Vulkan drivers when
     * hardware 16-bit storage (`VK_KHR_16bit_storage` / `storageBuffer16BitAccess`) or compute
     * initialization is unavailable or unstable.
     */
    fun isVulkan16BitStorageFailureLine(line: String): Boolean {
        val lower = line.lowercase()
        val positive16BitIndicators = listOf(
            "16bit_storage: 1",
            "16bit_storage = 1",
            "16bit_storage=1",
            "16bit_storage: true",
            "16bit_storage=true",
            "storagebuffer16bitaccess: 1",
            "storagebuffer16bitaccess=1",
            "storagebuffer16bitaccess = true",
            "storagebuffer16bitaccess=true"
        )
        if (positive16BitIndicators.any { lower.contains(it) }) {
            return false
        }
        if (isVulkanDriverUnreliableLine(line)) {
            return true
        }
        val explicitFailureMarkers = listOf(
            "16bit_storage: 0",
            "16bit_storage = 0",
            "16bit_storage=0",
            "16bit_storage: false",
            "16bit_storage=false",
            "storagebuffer16bitaccess=0",
            "storagebuffer16bitaccess = 0",
            "storagebuffer16bitaccess=false",
            "storagebuffer16bitaccess = false",
            "missing vk_khr_16bit_storage",
            "lacks vk_khr_16bit_storage",
            "without vk_khr_16bit_storage",
            "vk_khr_16bit_storage not supported",
            "16-bit storage not supported",
            "16bit storage not supported",
            "fp16 not supported",
            "float16 not supported",
            "fp16: 0",
            "fp16=0",
            "no vulkan devices",
            "ggml_vulkan: error",
            "ggml_vulkan: failed",
            "unsupported vulkan"
        )
        if (explicitFailureMarkers.any { lower.contains(it) }) {
            return true
        }
        val mentions16Bit = lower.contains("16bit_storage") ||
            lower.contains("storagebuffer16bitaccess") ||
            lower.contains("16-bit storage")
        val mentionsNegation = lower.contains("error") ||
            lower.contains("fail") ||
            lower.contains("missing") ||
            lower.contains("unsupported") ||
            lower.contains("unavailable") ||
            lower.contains("not supported") ||
            lower.contains("required") ||
            lower.contains("disabled")
        return mentions16Bit && mentionsNegation
    }

    /**
     * Formats an Android PackageManager FEATURE_VULKAN_HARDWARE_VERSION integer
     * (packed Vulkan API version: major[31:22], minor[21:12], patch[11:0]) as "major.minor".
     */
    fun formatVulkanVersion(versionCode: Int): String {
        if (versionCode <= 0) return "None"
        val major = (versionCode ushr 22) and 0x3FF
        val minor = (versionCode ushr 12) and 0x3FF
        return "$major.$minor"
    }

    /**
     * Queries native Android hardware APIs to inspect Vulkan compute features, dEQP conformance
     * stability level, OpenGL ES compute level, and the real GPU renderer/extensions via a bounded
     * 1x1 offscreen EGL14 pbuffer context. Never throws and always releases native EGL resources.
     */
    fun probeNativeAndroidGpuApis(context: Context?): HardwareProbe.NativeAndroidGpuApis {
        var vulkanVersionCode = 0
        var vulkanHardwareLevel = -1
        var vulkanDeqpLevel = 0
        var hasVulkanComputeFeature = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
        var reqGlEsVersion = 0x30001

        if (context != null) {
            runCatching {
                val pm = context.packageManager
                val features = pm.systemAvailableFeatures ?: emptyArray()
                val vkVerFeature = features.firstOrNull { it.name == PackageManager.FEATURE_VULKAN_HARDWARE_VERSION }
                if (vkVerFeature != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    vulkanVersionCode = vkVerFeature.version
                }
                val vkLevelFeature = features.firstOrNull { it.name == PackageManager.FEATURE_VULKAN_HARDWARE_LEVEL }
                if (vkLevelFeature != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    vulkanHardwareLevel = vkLevelFeature.version
                }
                val deqpFeature = features.firstOrNull { it.name == FEATURE_VULKAN_DEQP_LEVEL_NAME }
                if (deqpFeature != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    vulkanDeqpLevel = deqpFeature.version
                }
                hasVulkanComputeFeature = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    pm.hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_COMPUTE) ||
                        vulkanVersionCode >= VULKAN_VERSION_1_2
                } else {
                    false
                }
            }
            runCatching {
                val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                val glVer = am?.deviceConfigurationInfo?.reqGlEsVersion
                if (glVer != null && glVer > 0) {
                    reqGlEsVersion = glVer
                }
            }
        }

        // Bounded native EGL14 + GLES20 offscreen 1x1 pbuffer query to read exact GPU driver strings & fp16 extensions
        var eglRenderer = ""
        var eglVendor = ""
        var eglGlVersion = ""
        var hasFp16GlExt: Boolean? = null

        runCatching {
            val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            if (display != null && display != EGL14.EGL_NO_DISPLAY) {
                val version = IntArray(2)
                if (EGL14.eglInitialize(display, version, 0, version, 1)) {
                    var surface = EGL14.EGL_NO_SURFACE
                    var eglContext = EGL14.EGL_NO_CONTEXT
                    try {
                        val configAttribs = intArrayOf(
                            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                            EGL14.EGL_RED_SIZE, 8,
                            EGL14.EGL_GREEN_SIZE, 8,
                            EGL14.EGL_BLUE_SIZE, 8,
                            EGL14.EGL_NONE
                        )
                        val configs = arrayOfNulls<EGLConfig>(1)
                        val numConfigs = IntArray(1)
                        if (EGL14.eglChooseConfig(display, configAttribs, 0, configs, 0, 1, numConfigs, 0) &&
                            numConfigs[0] > 0 && configs[0] != null
                        ) {
                            val pbufferAttribs = intArrayOf(
                                EGL14.EGL_WIDTH, 1,
                                EGL14.EGL_HEIGHT, 1,
                                EGL14.EGL_NONE
                            )
                            surface = EGL14.eglCreatePbufferSurface(display, configs[0], pbufferAttribs, 0)
                            val ctxAttribs = intArrayOf(
                                EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
                                EGL14.EGL_NONE
                            )
                            eglContext = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT, ctxAttribs, 0)
                            if (surface != EGL14.EGL_NO_SURFACE && eglContext != EGL14.EGL_NO_CONTEXT &&
                                EGL14.eglMakeCurrent(display, surface, surface, eglContext)
                            ) {
                                eglRenderer = GLES20.glGetString(GLES20.GL_RENDERER)?.trim().orEmpty()
                                eglVendor = GLES20.glGetString(GLES20.GL_VENDOR)?.trim().orEmpty()
                                eglGlVersion = GLES20.glGetString(GLES20.GL_VERSION)?.trim().orEmpty()
                                val extensions = GLES20.glGetString(GLES20.GL_EXTENSIONS)?.lowercase().orEmpty()
                                if (extensions.isNotBlank()) {
                                    hasFp16GlExt = extensions.contains("gl_ext_shader_16bit_storage") ||
                                        extensions.contains("gl_oes_texture_half_float") ||
                                        extensions.contains("gl_ext_color_buffer_half_float") ||
                                        extensions.contains("gl_nv_gpu_shader5")
                                }
                            }
                        }
                    } finally {
                        runCatching {
                            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                        }
                        if (surface != EGL14.EGL_NO_SURFACE) {
                            runCatching { EGL14.eglDestroySurface(display, surface) }
                        }
                        if (eglContext != EGL14.EGL_NO_CONTEXT) {
                            runCatching { EGL14.eglDestroyContext(display, eglContext) }
                        }
                        runCatching { EGL14.eglTerminate(display) }
                    }
                }
            }
        }

        return HardwareProbe.NativeAndroidGpuApis(
            vulkanVersionCode = vulkanVersionCode,
            vulkanHardwareLevel = vulkanHardwareLevel,
            vulkanDeqpLevel = vulkanDeqpLevel,
            hasVulkanComputeFeature = hasVulkanComputeFeature,
            reqGlEsVersion = reqGlEsVersion,
            eglRenderer = eglRenderer,
            eglVendor = eglVendor,
            eglGlVersion = eglGlVersion,
            hasFp16GlExtension = hasFp16GlExt
        )
    }

    /**
     * Pure, deterministic capability & driver-stability evaluator for Vulkan compute + hardware
     * `VK_KHR_16bit_storage` (`storageBuffer16BitAccess`) + `limits.maxPushConstantsSize`.
     */
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
    ): HardwareProbe.VulkanProbeResult {
        val combinedSignature = "$hardwareSignature $eglRenderer $eglVendor".lowercase()
        val versionLabel = if (vulkanVersionCode > 0) formatVulkanVersion(vulkanVersionCode) else "Unknown"
        val resolvedEglName = eglRenderer.trim()
        val parsedProps = parsePhysicalDevicePropertiesOutput(nativeProbeOutput)
        val resolvedPushConstants = maxPushConstantsSize ?: parsedProps.maxPushConstantsSize
        val resolvedVkKhr16Bit = hasVkKhr16BitStorageExt ?: parsedProps.hasVkKhr16BitStorage

        fun reject(
            reason: String,
            hasCompute: Boolean,
            deviceName: String,
            apiVersion: String = versionLabel,
            hwLevel: Int = vulkanHardwareLevel,
            deqpLevel: Int = vulkanDeqpLevel,
            pushBytes: Int = resolvedPushConstants ?: 0,
            armv80Fallback: Boolean = true,
            has16Bit: Boolean = false
        ): HardwareProbe.VulkanProbeResult {
            return HardwareProbe.VulkanProbeResult(
                supported = false,
                reason = reason,
                hasVulkanCompute = hasCompute,
                has16BitStorage = has16Bit,
                vulkanApiVersion = apiVersion,
                deviceName = deviceName,
                probeDurationMs = elapsedMs,
                driverStable = false,
                vulkanHardwareLevel = hwLevel,
                vulkanDeqpLevel = deqpLevel,
                eglRenderer = resolvedEglName,
                maxPushConstantsSize = pushBytes,
                fallbackToArmv80Neon = armv80Fallback
            )
        }

        if (driverMarkedUnstable) {
            return reject(
                reason = "Vulkan driver previously crashed/unstable on this device -> forced ARM NEON CPU path (${elapsedMs}ms)",
                hasCompute = false,
                deviceName = "Unstable Vulkan Driver (Blocked)"
            )
        }

        val softwareRenderers = listOf("swiftshader", "llvmpipe", "ranchu", "goldfish", "pastel", "softpipe", "angle (google")
        if (softwareRenderers.any { combinedSignature.contains(it) }) {
            return reject(
                reason = "Software GPU renderer detected (${resolvedEglName.ifBlank { "software" }}) -> forced ARM NEON CPU path (${elapsedMs}ms)",
                hasCompute = false,
                deviceName = "Software Renderer (Skipped)"
            )
        }

        if (!vulkanLibExists) {
            return reject(
                reason = "libvulkan.so missing -> forced ARM NEON CPU path (${elapsedMs}ms)",
                hasCompute = false,
                deviceName = "ARM NEON CPU",
                apiVersion = "None",
                hwLevel = 0,
                deqpLevel = 0
            )
        }

        if (sdkInt < Build.VERSION_CODES.Q) {
            return reject(
                reason = "Android API $sdkInt < 29 (lacks stable Vulkan 1.2+ fp16 storage) -> forced ARM NEON CPU path (${elapsedMs}ms)",
                hasCompute = false,
                deviceName = "ARM NEON CPU"
            )
        }

        if (reqGlEsVersion in 1 until 0x30001) {
            return reject(
                reason = "Legacy GPU driver (GLES 0x${reqGlEsVersion.toString(16)} < 3.1 compute) -> forced ARM NEON CPU path (${elapsedMs}ms)",
                hasCompute = false,
                deviceName = resolvedEglName.ifBlank { "Legacy GPU (No Compute)" }
            )
        }

        if (!hasHardwareIcd) {
            return reject(
                reason = "No hardware Vulkan ICD found -> forced ARM NEON CPU path (${elapsedMs}ms)",
                hasCompute = false,
                deviceName = "ARM NEON CPU"
            )
        }

        if (!hasVulkanComputeFeature) {
            return reject(
                reason = "PackageManager FEATURE_VULKAN_HARDWARE_COMPUTE unavailable -> forced ARM NEON CPU path (${elapsedMs}ms)",
                hasCompute = false,
                deviceName = "ARM NEON CPU"
            )
        }

        if (vulkanVersionCode in 1 until VULKAN_VERSION_1_2) {
            return reject(
                reason = "Vulkan $versionLabel < 1.2 (Vulkan 1.2+ & VK_KHR_16bit_storage required) -> forced ARM NEON CPU path (${elapsedMs}ms)",
                hasCompute = true,
                deviceName = "Vulkan $versionLabel (< 1.2)"
            )
        }

        if (vulkanDeqpLevel in 1 until MIN_STABLE_DEQP_LEVEL) {
            return reject(
                reason = "Vulkan dEQP conformance level 0x${vulkanDeqpLevel.toString(16)} below stable compute baseline -> forced ARM NEON CPU path (${elapsedMs}ms)",
                hasCompute = true,
                deviceName = resolvedEglName.ifBlank { "Unvalidated Vulkan Driver" }
            )
        }

        var detectedDeviceName = resolvedEglName.ifBlank { "Vulkan $versionLabel Compute GPU" }
        if (!nativeProbeOutput.isNullOrBlank()) {
            val lines = nativeProbeOutput.lines()
            val unreliableLine = lines.firstOrNull { isVulkanDriverUnreliableLine(it) }
            if (unreliableLine != null || parsedProps.driverReliable == false) {
                return reject(
                    reason = "Native Vulkan startup probe detected unreliable driver (${(unreliableLine ?: "driver_reliable=0").trim().take(72)}) -> forced NEON-optimized CPU path (${elapsedMs}ms)",
                    hasCompute = false,
                    deviceName = "Unreliable Vulkan Driver",
                    armv80Fallback = true
                )
            }
            val failureLine = lines.firstOrNull { isVulkan16BitStorageFailureLine(it) }
            if (failureLine != null) {
                return reject(
                    reason = "Native Vulkan probe detected missing VK_KHR_16bit_storage/driver fault (${failureLine.trim().take(72)}) -> GPU disabled, fallback to ARMv8.0-A NEON CPU (${elapsedMs}ms)",
                    hasCompute = true,
                    deviceName = "Vulkan Probe Rejected",
                    armv80Fallback = true
                )
            }
            val lowerOut = nativeProbeOutput.lowercase()
            if (softwareRenderers.any { lowerOut.contains(it) }) {
                return reject(
                    reason = "Native Vulkan probe enumerated software renderer -> forced NEON-optimized CPU path (${elapsedMs}ms)",
                    hasCompute = false,
                    deviceName = "Software Vulkan Renderer (Skipped)"
                )
            }
            if (lowerOut.contains("available devices:") && !lowerOut.contains("vulkan")) {
                return reject(
                    reason = "Native probe reported no usable Vulkan compute device -> forced ARM NEON CPU path (${elapsedMs}ms)",
                    hasCompute = false,
                    deviceName = "ARM NEON CPU"
                )
            }
            val deviceLine = lines.firstOrNull {
                val l = it.lowercase()
                l.contains("ggml_vulkan:") || l.contains("vulkan0:") || l.contains("device 0:")
            }
            if (deviceLine != null) {
                detectedDeviceName = deviceLine.substringAfter(":").trim().take(64).ifBlank { detectedDeviceName }
            }
        }

        // PhysicalDeviceProperties gate 0: explicit Vulkan 1.2+ and compute queue support (VK_QUEUE_COMPUTE_BIT)
        val nativeApiCode = parsedProps.vulkanApiVersionCode
        if (nativeApiCode != null && nativeApiCode in 1 until VULKAN_VERSION_1_2) {
            val nativeApiLabel = formatVulkanVersion(nativeApiCode)
            return reject(
                reason = "Native Vulkan probe reported API $nativeApiLabel < 1.2 on $detectedDeviceName -> hard switch to ARMv8.0-A NEON CPU (${elapsedMs}ms)",
                hasCompute = true,
                deviceName = "$detectedDeviceName (Vulkan $nativeApiLabel < 1.2)",
                apiVersion = nativeApiLabel,
                armv80Fallback = true
            )
        }
        val effectiveApiCode = when {
            nativeApiCode != null && nativeApiCode > 0 -> nativeApiCode
            vulkanVersionCode > 0 -> vulkanVersionCode
            else -> 0
        }
        if (requireExplicit16BitVerification && effectiveApiCode < VULKAN_VERSION_1_2) {
            return reject(
                reason = "Vulkan 1.2+ API version not explicitly confirmed on $detectedDeviceName -> hard switch to ARMv8.0-A NEON CPU (${elapsedMs}ms)",
                hasCompute = true,
                deviceName = "$detectedDeviceName (Vulkan Version Unverified)",
                apiVersion = if (effectiveApiCode > 0) formatVulkanVersion(effectiveApiCode) else "Unknown",
                armv80Fallback = true
            )
        }
        if (parsedProps.hasVulkanCompute == false) {
            return reject(
                reason = "Vulkan PhysicalDevice ($detectedDeviceName) lacks VK_QUEUE_COMPUTE_BIT compute support -> hard switch to ARMv8.0-A NEON CPU (${elapsedMs}ms)",
                hasCompute = false,
                deviceName = "$detectedDeviceName (No Compute Queue)",
                armv80Fallback = true
            )
        }

        // PhysicalDeviceProperties gate 1: explicit VK_KHR_16bit_storage check
        if (resolvedVkKhr16Bit == false) {
            return reject(
                reason = "Vulkan PhysicalDevice ($detectedDeviceName) lacks VK_KHR_16bit_storage -> GPU disabled, hard switch to ARMv8.0-A NEON CPU (${elapsedMs}ms)",
                hasCompute = true,
                deviceName = "$detectedDeviceName (No VK_KHR_16bit_storage)",
                armv80Fallback = true
            )
        }
        if (requireExplicit16BitVerification && resolvedVkKhr16Bit != true) {
            return reject(
                reason = "VK_KHR_16bit_storage not explicitly confirmed on Vulkan PhysicalDevice ($detectedDeviceName) -> hard switch to ARMv8.0-A NEON CPU (${elapsedMs}ms)",
                hasCompute = true,
                deviceName = "$detectedDeviceName (VK_KHR_16bit_storage Unverified)",
                armv80Fallback = true
            )
        }

        // PhysicalDeviceProperties gate 2: limits.maxPushConstantsSize check (>= 256 bytes required)
        if (resolvedPushConstants != null && resolvedPushConstants < MIN_VULKAN_PUSH_CONSTANTS_SIZE) {
            return reject(
                reason = "Vulkan PhysicalDevice ($detectedDeviceName) reports low maxPushConstantsSize=${resolvedPushConstants}B (< ${MIN_VULKAN_PUSH_CONSTANTS_SIZE}B required) -> GPU disabled, fallback to ARMv8.0-A NEON CPU (${elapsedMs}ms)",
                hasCompute = true,
                deviceName = "$detectedDeviceName (Low maxPushConstantsSize=${resolvedPushConstants}B)",
                pushBytes = resolvedPushConstants,
                armv80Fallback = true,
                has16Bit = resolvedVkKhr16Bit ?: true
            )
        }

        // PhysicalDeviceProperties gate 3: native probe disabled GPU backend for another driver reason
        if (parsedProps.rpcBackendGpuEnabled == false) {
            return reject(
                reason = "Native Vulkan probe disabled GPU backend ($detectedDeviceName) -> forced NEON-optimized CPU path (${elapsedMs}ms)",
                hasCompute = false,
                deviceName = "$detectedDeviceName (GPU Disabled)",
                armv80Fallback = true
            )
        }

        // Crash signal from native startup probe (e.g., SIGILL=132, SIGABRT=134, SIGSEGV=139) or timeout (124)
        if (nativeProbeExitCode != null && (nativeProbeExitCode > 128 || nativeProbeExitCode == 124)) {
            val detail = if (nativeProbeExitCode == 124) "timed out" else "signal ${nativeProbeExitCode - 128}"
            return reject(
                reason = "Native Vulkan startup probe unstable ($detail) -> GPU disabled, fallback to ARMv8.0-A NEON CPU (${elapsedMs}ms)",
                hasCompute = false,
                deviceName = "Vulkan Driver Unstable",
                armv80Fallback = true
            )
        }

        val resolvedVersion = when {
            nativeApiCode != null && nativeApiCode > 0 -> formatVulkanVersion(nativeApiCode)
            vulkanVersionCode > 0 -> versionLabel
            else -> "1.2+"
        }
        val verifiedPushBytes = resolvedPushConstants ?: MIN_VULKAN_PUSH_CONSTANTS_SIZE
        return HardwareProbe.VulkanProbeResult(
            supported = true,
            reason = "Vulkan $resolvedVersion Compute + VK_KHR_16bit_storage verified ($detectedDeviceName, maxPushConstantsSize=${verifiedPushBytes}B, ${elapsedMs}ms)",
            hasVulkanCompute = true,
            has16BitStorage = true,
            vulkanApiVersion = resolvedVersion,
            deviceName = detectedDeviceName,
            probeDurationMs = elapsedMs,
            driverStable = true,
            vulkanHardwareLevel = vulkanHardwareLevel,
            vulkanDeqpLevel = vulkanDeqpLevel,
            eglRenderer = resolvedEglName,
            maxPushConstantsSize = verifiedPushBytes,
            fallbackToArmv80Neon = false
        )
    }

    /**
     * Selects the optimal compute backend. Whenever Vulkan `VK_KHR_16bit_storage`,
     * Vulkan 1.2+, `maxPushConstantsSize >= 256B`, or driver stability is unavailable or
     * fails, forces a hard, zero-fallback switch to the deterministic `ARMv8.0-A NEON`
     * multi-threaded CPU execution path.
     */
    @Suppress("UNUSED_PARAMETER")
    fun selectOptimalComputeBackend(
        vk: HardwareProbe.VulkanProbeResult,
        cpu: HardwareProbe.CpuProbeResult,
        legacyHardwareMode: Boolean,
        vulkanFallbackActive: Boolean = false
    ): ComputeBackend {
        return if (
            vk.supported &&
            vk.hasVulkanCompute &&
            vk.has16BitStorage &&
            vk.driverStable &&
            vk.maxPushConstantsSize >= MIN_VULKAN_PUSH_CONSTANTS_SIZE &&
            !legacyHardwareMode &&
            !vulkanFallbackActive
        ) {
            ComputeBackend.VULKAN_FP16
        } else {
            ComputeBackend.ARM_V8_0_NEON
        }
    }

    /**
     * Executes the single-pass native Android API Vulkan capability probe with short-TTL memoization.
     */
    fun probe(
        context: Context? = null,
        nativeBinary: File? = null,
        libDir: String? = null,
        forceRefresh: Boolean = false
    ): HardwareProbe.VulkanProbeResult {
        val driverFingerprint = VulkanDriverStabilityStore.currentDeviceFingerprint()
        val cacheKey = "$driverFingerprint|${nativeBinary?.absolutePath.orEmpty()}"
        val nowMs = System.currentTimeMillis()
        if (!forceRefresh) {
            val cached = cachedProbe
            if (cached != null && cached.cacheKey == cacheKey && (nowMs - cached.timestampMs) < CACHE_TTL_MS) {
                return cached.result
            }
        }

        val startNs = System.nanoTime()
        val result = try {
            // Stage 1: 0ms persistent driver instability bypass (skip EGL/Vulkan probing completely)
            val driverMarkedUnstable = context != null &&
                VulkanDriverStabilityStore.isDriverMarkedUnstable(context, driverFingerprint)
            if (driverMarkedUnstable) {
                return@probe cacheAndReturn(
                    cacheKey,
                    nowMs,
                    HardwareProbe.VulkanProbeResult(
                        supported = false,
                        reason = "Vulkan driver previously crashed/unstable on this device -> hard switch to ARMv8.0-A NEON CPU (0ms)",
                        hasVulkanCompute = false,
                        has16BitStorage = false,
                        vulkanApiVersion = "Blocked",
                        deviceName = "Unstable Vulkan Driver (Blocked)",
                        probeDurationMs = 0L,
                        driverStable = false,
                        maxPushConstantsSize = 0,
                        fallbackToArmv80Neon = true
                    )
                )
            }

            // Stage 2: Native Android hardware APIs (PackageManager + ActivityManager + EGL14/GLES20 + ICDs)
            val hw = (Build.HARDWARE + " " + Build.BOARD + " " + Build.MODEL + " " + Build.PRODUCT + " " + getSocModelIfAvailable()).lowercase()
            val vulkanLibExists = File("/system/lib64/libvulkan.so").exists() ||
                File("/system/lib/libvulkan.so").exists()

            val icdDirs = listOf(File("/vendor/lib64/hw"), File("/vendor/lib/hw"), File("/odm/lib64/hw"))
            val icdFiles = icdDirs.flatMap { dir ->
                runCatching { dir.listFiles()?.toList() ?: emptyList() }.getOrDefault(emptyList())
            }.filter { it.name.startsWith("vulkan.") && it.name.endsWith(".so") }
            val hasHardwareIcd = if (icdFiles.isEmpty()) {
                vulkanLibExists
            } else {
                icdFiles.any { f ->
                    val n = f.name.lowercase()
                    !n.contains("ranchu") && !n.contains("pastel") && !n.contains("swiftshader") && !n.contains("goldfish")
                }
            }

            val nativeApis = probeNativeAndroidGpuApis(context)
            val softwareRenderers = listOf("swiftshader", "llvmpipe", "ranchu", "goldfish", "pastel", "softpipe", "angle (google")
            val combinedSig = "$hw ${nativeApis.eglRenderer} ${nativeApis.eglVendor}".lowercase()
            val isSoftwareGpu = softwareRenderers.any { combinedSig.contains(it) }
            if (!vulkanLibExists || !hasHardwareIcd || isSoftwareGpu || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                val fastReject = evaluateVulkanComputeSupport(
                    sdkInt = Build.VERSION.SDK_INT,
                    hardwareSignature = hw,
                    vulkanLibExists = vulkanLibExists,
                    vulkanVersionCode = nativeApis.vulkanVersionCode,
                    hasVulkanComputeFeature = nativeApis.hasVulkanComputeFeature,
                    hasHardwareIcd = hasHardwareIcd,
                    elapsedMs = (System.nanoTime() - startNs) / 1_000_000,
                    vulkanHardwareLevel = nativeApis.vulkanHardwareLevel,
                    vulkanDeqpLevel = nativeApis.vulkanDeqpLevel,
                    eglRenderer = nativeApis.eglRenderer,
                    eglVendor = nativeApis.eglVendor,
                    hasFp16GlExtension = nativeApis.hasFp16GlExtension,
                    reqGlEsVersion = nativeApis.reqGlEsVersion,
                    driverMarkedUnstable = false,
                    requireExplicit16BitVerification = true
                )
                return@probe cacheAndReturn(cacheKey, nowMs, fastReject)
            }

            // Stage 3: Mandatory runtime Vulkan VK_KHR_16bit_storage + VkPhysicalDeviceProperties probe
            var probeOutput: String? = NativeVulkanBridge.probePhysicalDevicePropertiesOrNull()
            var probeExit: Int? = if (!probeOutput.isNullOrBlank()) 0 else null

            if (probeOutput.isNullOrBlank() && nativeBinary != null && nativeBinary.exists()) {
                val probeArgsList = listOf("--probe-vulkan-props", "--list-devices")
                for (probeArg in probeArgsList) {
                    var currentOut: String? = null
                    var currentExit: Int? = null
                    runCatching {
                        val pb = ProcessBuilder(nativeBinary.absolutePath, probeArg)
                        pb.redirectErrorStream(true)
                        val env = pb.environment()
                        if (!libDir.isNullOrBlank()) {
                            env["LD_LIBRARY_PATH"] = libDir
                        }
                        env.remove("GGML_VK_VISIBLE_DEVICES")
                        val p = pb.start()
                        val completed = p.waitFor(1200L, TimeUnit.MILLISECONDS)
                        if (!completed) {
                            p.destroyForcibly()
                            currentOut = "ggml_vulkan: failed (startup probe timed out >1200ms)"
                            currentExit = 124
                        } else {
                            currentExit = p.exitValue()
                            currentOut = runCatching {
                                p.inputStream.bufferedReader().readText().take(2048)
                            }.getOrNull()
                        }
                    }
                    probeOutput = currentOut
                    probeExit = currentExit
                    val unrecognizedFlag = currentExit != 0 &&
                        currentExit != 124 &&
                        (currentExit ?: 0) <= 128 &&
                        (currentOut?.lowercase()?.contains("invalid") == true ||
                            currentOut?.lowercase()?.contains("unknown") == true ||
                            currentOut?.lowercase()?.contains("usage:") == true)
                    if (!unrecognizedFlag) {
                        break
                    }
                }
            }

            val elapsedMs = (System.nanoTime() - startNs) / 1_000_000
            val finalResult = evaluateVulkanComputeSupport(
                sdkInt = Build.VERSION.SDK_INT,
                hardwareSignature = hw,
                vulkanLibExists = vulkanLibExists,
                vulkanVersionCode = nativeApis.vulkanVersionCode,
                hasVulkanComputeFeature = nativeApis.hasVulkanComputeFeature,
                hasHardwareIcd = hasHardwareIcd,
                nativeProbeOutput = probeOutput,
                nativeProbeExitCode = probeExit,
                elapsedMs = elapsedMs,
                vulkanHardwareLevel = nativeApis.vulkanHardwareLevel,
                vulkanDeqpLevel = nativeApis.vulkanDeqpLevel,
                eglRenderer = nativeApis.eglRenderer,
                eglVendor = nativeApis.eglVendor,
                hasFp16GlExtension = nativeApis.hasFp16GlExtension,
                reqGlEsVersion = nativeApis.reqGlEsVersion,
                driverMarkedUnstable = false,
                requireExplicit16BitVerification = true
            )

            val exitVal = probeExit
            val outputUnreliable = !probeOutput.isNullOrBlank() &&
                probeOutput.lines().any { isVulkanDriverUnreliableLine(it) }
            if (context != null && !finalResult.driverStable &&
                ((exitVal != null && (exitVal > 128 || exitVal == 124)) || outputUnreliable)
            ) {
                VulkanDriverStabilityStore.markDriverUnstable(
                    context = context,
                    fingerprint = driverFingerprint,
                    reason = if (exitVal != null && (exitVal > 128 || exitVal == 124)) {
                        "startup-probe-exit=$exitVal"
                    } else {
                        "startup-probe-unreliable-driver"
                    }
                )
            }
            finalResult
        } catch (e: Exception) {
            HardwareProbe.VulkanProbeResult(
                supported = false,
                reason = "Vulkan probe exception (${e.message}) -> hard switch to ARMv8.0-A NEON CPU",
                hasVulkanCompute = false,
                has16BitStorage = false,
                vulkanApiVersion = "None",
                deviceName = "ARMv8.0-A NEON CPU",
                probeDurationMs = (System.nanoTime() - startNs) / 1_000_000,
                driverStable = false,
                fallbackToArmv80Neon = true
            )
        }
        return cacheAndReturn(cacheKey, nowMs, result)
    }

    private fun cacheAndReturn(
        cacheKey: String,
        timestampMs: Long,
        result: HardwareProbe.VulkanProbeResult
    ): HardwareProbe.VulkanProbeResult {
        cachedProbe = CachedProbeEntry(cacheKey, timestampMs, result)
        return result
    }

    private fun getSocModelIfAvailable(): String {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Build.SOC_MODEL
        } else {
            ""
        }
    }
}

data class RpcStartupProbeSnapshot(
    val cpu: HardwareProbe.CpuProbeResult,
    val vulkan: HardwareProbe.VulkanProbeResult,
    val driverFingerprint: String,
    val effectiveFallback: Boolean,
    val vulkanReady: Boolean,
    val selectedBackend: ComputeBackend,
    val neonFallbackEnforced: Boolean = !vulkanReady
)

data class RpcStartupExecutionResult(
    val snapshot: RpcStartupProbeSnapshot,
    val initialPlan: RpcInitializationPlan
)

data class RpcInitializationPlan(
    val selectedBackend: ComputeBackend,
    val forceNeonCpu: Boolean,
    val threads: Int,
    val threadNicePriority: Int,
    val driverStabilityStatus: String,
    val cliFlags: List<String>,
    val processEnv: Map<String, String>,
    val shellCommand: String,
    val contextWindowTokens: Int = 4096
)

/**
 * Deterministic Android RPC initialization & seamless NEON fallback orchestrator.
 * Guarantees that whenever native Android hardware APIs or `VkPhysicalDeviceProperties`
 * indicate missing/unverified `VK_KHR_16bit_storage` / fp16, low `maxPushConstantsSize` (< 256B),
 * an unstable driver, thermal GPU suppression, or legacy hardware, the GPU backend is
 * explicitly disabled and the RPC server initializes seamlessly on the optimized ARM NEON
 * multi-threaded CPU execution path.
 */
object RpcInitializer {

    /**
     * Resolves multi-threaded CPU concurrency for ARM NEON execution while honoring
     * thermal scaling tiers and explicit thread caps.
     */
    fun resolveOptimalNeonThreadCount(
        cpu: HardwareProbe.CpuProbeResult,
        thermal: ThermalScalingDecision,
        legacyHardwareMode: Boolean,
        thermalThrottleThreadCap: Int = 0
    ): Int {
        if (thermalThrottleThreadCap > 0) {
            return min(thermal.targetThreads, thermalThrottleThreadCap).coerceAtLeast(1)
        }
        val baseThreads = thermal.targetThreads.coerceAtLeast(1)
        if (thermal.tier == ThermalScalingTier.BOOST_COOL &&
            thermal.loadAllocationScale >= 0.99f &&
            !legacyHardwareMode &&
            cpu.totalCoreCount >= 2 &&
            baseThreads < 2
        ) {
            return min(cpu.totalCoreCount, maxOf(2, cpu.bigCoreCount))
        }
        return baseThreads
    }

    /**
     * Pure startup capability evaluator that takes an already-probed Vulkan + CPU pair and
     * computes a unified snapshot + ready-to-launch initialization plan.
     */
    fun executeStartupSequence(
        cpu: HardwareProbe.CpuProbeResult,
        vk: HardwareProbe.VulkanProbeResult,
        loadBalance: LoadBalanceDecision,
        driverFingerprint: String = "jvm-test-fingerprint",
        persistedDriverUnstable: Boolean = false,
        legacyHardwareMode: Boolean = false,
        vulkanFallbackActive: Boolean = false,
        hostIp: String = "0.0.0.0",
        port: Int = RpcService.DEFAULT_RPC_PORT,
        activeBinPath: String,
        cacheDirPath: String,
        libDir: String,
        nativeDir: String,
        thermalThrottleThreadCap: Int = 0
    ): RpcStartupExecutionResult {
        val effectiveFallback = vulkanFallbackActive ||
            persistedDriverUnstable ||
            !vk.driverStable ||
            !vk.hasVulkanCompute ||
            !vk.has16BitStorage ||
            !vk.supported
        val vkReady = vk.supported &&
            vk.hasVulkanCompute &&
            vk.has16BitStorage &&
            vk.driverStable &&
            vk.maxPushConstantsSize >= VulkanComputeProber.MIN_VULKAN_PUSH_CONSTANTS_SIZE &&
            !legacyHardwareMode &&
            !effectiveFallback
        val selectedBackend = VulkanComputeProber.selectOptimalComputeBackend(
            vk = vk,
            cpu = cpu,
            legacyHardwareMode = legacyHardwareMode,
            vulkanFallbackActive = effectiveFallback
        )
        val snapshot = RpcStartupProbeSnapshot(
            cpu = cpu,
            vulkan = vk,
            driverFingerprint = driverFingerprint,
            effectiveFallback = effectiveFallback,
            vulkanReady = vkReady,
            selectedBackend = selectedBackend,
            neonFallbackEnforced = !vkReady
        )
        val initialPlan = buildInitializationPlan(
            vk = vk,
            cpu = cpu,
            loadBalance = loadBalance,
            legacyHardwareMode = legacyHardwareMode,
            vulkanFallbackActive = effectiveFallback,
            hostIp = hostIp,
            port = port,
            activeBinPath = activeBinPath,
            cacheDirPath = cacheDirPath,
            libDir = libDir,
            nativeDir = nativeDir,
            thermalThrottleThreadCap = thermalThrottleThreadCap
        )
        return RpcStartupExecutionResult(
            snapshot = snapshot,
            initialPlan = initialPlan
        )
    }

    /**
     * Single-pass startup probe executed once before spawning `librpc_server_exec.so`.
     */
    fun prepareStartupInitialization(
        context: Context,
        activeBin: File?,
        nativeDir: String,
        legacyHardwareMode: Boolean,
        vulkanFallbackActive: Boolean,
        forceRefresh: Boolean = false
    ): RpcStartupProbeSnapshot {
        val startupLibDir = activeBin?.parentFile?.absolutePath ?: nativeDir
        val cpuInfo = HardwareProbe.probeCpuAndCores()
        val startupVk = VulkanComputeProber.probe(
            context = context,
            nativeBinary = activeBin,
            libDir = "$startupLibDir:$nativeDir",
            forceRefresh = forceRefresh
        )
        val driverFingerprint = VulkanDriverStabilityStore.currentDeviceFingerprint()
        val persistedUnstable = VulkanDriverStabilityStore.isDriverMarkedUnstable(context, driverFingerprint)
        val effectiveFallback = vulkanFallbackActive ||
            persistedUnstable ||
            !startupVk.driverStable ||
            !startupVk.hasVulkanCompute ||
            !startupVk.has16BitStorage ||
            !startupVk.supported
        val vkReady = startupVk.supported &&
            startupVk.hasVulkanCompute &&
            startupVk.has16BitStorage &&
            startupVk.driverStable &&
            startupVk.maxPushConstantsSize >= VulkanComputeProber.MIN_VULKAN_PUSH_CONSTANTS_SIZE &&
            !legacyHardwareMode &&
            !effectiveFallback
        val selectedBackend = VulkanComputeProber.selectOptimalComputeBackend(
            vk = startupVk,
            cpu = cpuInfo,
            legacyHardwareMode = legacyHardwareMode,
            vulkanFallbackActive = effectiveFallback
        )
        return RpcStartupProbeSnapshot(
            cpu = cpuInfo,
            vulkan = startupVk,
            driverFingerprint = driverFingerprint,
            effectiveFallback = effectiveFallback,
            vulkanReady = vkReady,
            selectedBackend = selectedBackend,
            neonFallbackEnforced = !vkReady
        )
    }

    /**
     * Builds an immediate, seamless ARM NEON CPU fallback plan (zero retry delay) and persists
     * the driver instability verdict when a runtime Vulkan fault or fp16 failure occurs.
     */
    fun buildImmediateNeonFallbackPlan(
        context: Context?,
        cpu: HardwareProbe.CpuProbeResult,
        thermal: ThermalScalingDecision,
        legacyHardwareMode: Boolean,
        failureReason: String,
        hostIp: String,
        port: Int,
        activeBinPath: String,
        cacheDirPath: String,
        libDir: String,
        nativeDir: String,
        thermalThrottleThreadCap: Int = 0
    ): RpcInitializationPlan {
        if (context != null) {
            VulkanDriverStabilityStore.markDriverUnstable(
                context = context,
                fingerprint = VulkanDriverStabilityStore.currentDeviceFingerprint(),
                reason = failureReason
            )
        }
        val failedVk = HardwareProbe.VulkanProbeResult(
            supported = false,
            reason = "Runtime Vulkan fallback ($failureReason) -> hard zero-fallback switch to ARMv8.0-A NEON CPU",
            hasVulkanCompute = false,
            has16BitStorage = false,
            vulkanApiVersion = "Fallback",
            deviceName = "ARMv8.0-A NEON CPU",
            driverStable = false,
            maxPushConstantsSize = 0,
            fallbackToArmv80Neon = true
        )
        return buildInitializationPlan(
            vk = failedVk,
            cpu = cpu,
            thermal = thermal,
            legacyHardwareMode = legacyHardwareMode,
            vulkanFallbackActive = true,
            hostIp = hostIp,
            port = port,
            activeBinPath = activeBinPath,
            cacheDirPath = cacheDirPath,
            libDir = libDir,
            nativeDir = nativeDir,
            thermalThrottleThreadCap = thermalThrottleThreadCap
        )
    }

    fun buildInitializationPlan(
        vk: HardwareProbe.VulkanProbeResult,
        cpu: HardwareProbe.CpuProbeResult,
        loadBalance: LoadBalanceDecision,
        legacyHardwareMode: Boolean,
        vulkanFallbackActive: Boolean,
        hostIp: String,
        port: Int,
        activeBinPath: String,
        cacheDirPath: String,
        libDir: String,
        nativeDir: String,
        thermalThrottleThreadCap: Int = 0
    ): RpcInitializationPlan = buildInitializationPlan(
        vk = vk,
        cpu = cpu,
        thermal = loadBalance.toThermalScalingDecision(),
        legacyHardwareMode = legacyHardwareMode,
        vulkanFallbackActive = vulkanFallbackActive,
        hostIp = hostIp,
        port = port,
        activeBinPath = activeBinPath,
        cacheDirPath = cacheDirPath,
        libDir = libDir,
        nativeDir = nativeDir,
        thermalThrottleThreadCap = thermalThrottleThreadCap
    )

    fun buildInitializationPlan(
        vk: HardwareProbe.VulkanProbeResult,
        cpu: HardwareProbe.CpuProbeResult,
        thermal: ThermalScalingDecision,
        legacyHardwareMode: Boolean,
        vulkanFallbackActive: Boolean,
        hostIp: String,
        port: Int,
        activeBinPath: String,
        cacheDirPath: String,
        libDir: String,
        nativeDir: String,
        thermalThrottleThreadCap: Int = 0
    ): RpcInitializationPlan {
        val useVulkanGpu = vk.supported &&
            vk.hasVulkanCompute &&
            vk.has16BitStorage &&
            vk.driverStable &&
            vk.maxPushConstantsSize >= VulkanComputeProber.MIN_VULKAN_PUSH_CONSTANTS_SIZE &&
            thermal.allowGpuOffload &&
            !legacyHardwareMode &&
            !vulkanFallbackActive

        val forceNeonCpu = !useVulkanGpu
        val threads = if (forceNeonCpu) {
            resolveOptimalNeonThreadCount(cpu, thermal, legacyHardwareMode, thermalThrottleThreadCap)
        } else if (thermalThrottleThreadCap > 0) {
            min(thermal.targetThreads, thermalThrottleThreadCap).coerceAtLeast(1)
        } else {
            thermal.targetThreads.coerceAtLeast(1)
        }
        val nicePriority = thermal.threadNicePriority
        val selectedBackend = VulkanComputeProber.selectOptimalComputeBackend(
            vk = vk,
            cpu = cpu,
            legacyHardwareMode = legacyHardwareMode,
            vulkanFallbackActive = vulkanFallbackActive || !thermal.allowGpuOffload
        )

        val stabilityStatus = when {
            useVulkanGpu -> "VULKAN_FP16_VERIFIED"
            vk.maxPushConstantsSize in 1 until VulkanComputeProber.MIN_VULKAN_PUSH_CONSTANTS_SIZE -> "FORCED_ARMV80_NEON_LOW_PUSH_CONSTANTS"
            !vk.has16BitStorage -> "FORCED_ARMV80_NEON_NO_16BIT_STORAGE"
            !vk.driverStable || vulkanFallbackActive -> "FORCED_NEON_UNSTABLE_DRIVER"
            !thermal.allowGpuOffload -> "FORCED_NEON_THERMAL_GUARD"
            legacyHardwareMode -> "FORCED_NEON_LEGACY_HARDWARE"
            else -> "FORCED_NEON_CPU_DEFAULT"
        }

        val cliFlags = buildList {
            add("-H"); add(hostIp)
            add("-p"); add(port.toString())
            add("-t"); add(threads.toString())
            add("-c")
            if (forceNeonCpu) {
                add("-d"); add("CPU")
            }
        }

        val loadScaleFormatted = "%.2f".format(Locale.US, thermal.loadAllocationScale)
        val combinedLibPath = if (nativeDir.isNotBlank() && nativeDir != libDir) "$libDir:$nativeDir" else libDir
        val cpuExecProfile = if (forceNeonCpu || selectedBackend == ComputeBackend.ARM_V8_0_NEON) {
            "ARMv8.0-A NEON"
        } else {
            selectedBackend.label
        }
        val processEnv = linkedMapOf(
            "HOME" to cacheDirPath,
            "XDG_CACHE_HOME" to cacheDirPath,
            "CACHE_DIR" to cacheDirPath,
            "LD_LIBRARY_PATH" to combinedLibPath,
            "OMP_NUM_THREADS" to threads.toString(),
            "OPENBLAS_NUM_THREADS" to threads.toString(),
            "GGML_NUM_THREADS" to threads.toString(),
            "OMP_WAIT_POLICY" to "PASSIVE",
            "SILICA_CONTEXT_WINDOW" to thermal.contextWindowTokens.toString(),
            "SILICA_LOAD_SCALE" to loadScaleFormatted,
            "SILICA_THERMAL_TIER" to thermal.tier.policyCode,
            "SILICA_THREAD_PRIORITY" to nicePriority.toString()
        )
        if (forceNeonCpu) {
            processEnv["GGML_VK_VISIBLE_DEVICES"] = ""
            processEnv["GGML_VK_DISABLE_F16"] = "1"
            processEnv["GGML_VK_FORCE_MAX_ALLOCATION_SIZE"] = "0"
            processEnv["SILICA_GPU_BACKEND_DISABLED"] = "1"
            processEnv["SILICA_ZERO_FALLBACK_CPU"] = "1"
            if (!cpu.hasDotProd && !cpu.hasI8mm) {
                processEnv["GGML_ARM_DISABLE_DOTPROD"] = "1"
                processEnv["GGML_ARM_DISABLE_I8MM"] = "1"
            }
            processEnv["SILICA_CPU_EXEC_PATH"] = cpuExecProfile
            processEnv["OMP_PROC_BIND"] = "close"
            processEnv["OMP_PLACES"] = "cores"
            processEnv["SILICA_ARM_NEON_FASTPATH"] = "1"
            processEnv["SILICA_NEON_THREADS"] = threads.toString()
        }

        val argsStr = cliFlags.joinToString(" ")
        val vkExports = if (forceNeonCpu) {
            "export GGML_VK_VISIBLE_DEVICES=; export GGML_VK_DISABLE_F16=1; export GGML_VK_FORCE_MAX_ALLOCATION_SIZE=0; export SILICA_GPU_BACKEND_DISABLED=1; export SILICA_ZERO_FALLBACK_CPU=1; ${if (!cpu.hasDotProd && !cpu.hasI8mm) "export GGML_ARM_DISABLE_DOTPROD=1; export GGML_ARM_DISABLE_I8MM=1; " else ""}export SILICA_CPU_EXEC_PATH=\"$cpuExecProfile\"; export OMP_PROC_BIND=close; export OMP_PLACES=cores; export SILICA_ARM_NEON_FASTPATH=1; export SILICA_NEON_THREADS=$threads; "
        } else {
            ""
        }
        val commonExports = "export LD_LIBRARY_PATH=\"$combinedLibPath\"; export XDG_CACHE_HOME=\"$cacheDirPath\"; export HOME=\"$cacheDirPath\"; " +
            "export OMP_NUM_THREADS=$threads; export OPENBLAS_NUM_THREADS=$threads; export GGML_NUM_THREADS=$threads; export OMP_WAIT_POLICY=PASSIVE; " +
            "export SILICA_CONTEXT_WINDOW=${thermal.contextWindowTokens}; export SILICA_LOAD_SCALE=$loadScaleFormatted; " +
            "export SILICA_THERMAL_TIER=${thermal.tier.policyCode}; "
        val execCmd = if (nicePriority > 0) {
            "(command -v nice >/dev/null 2>&1 && exec nice -n $nicePriority \"$activeBinPath\" $argsStr) || exec \"$activeBinPath\" $argsStr"
        } else {
            "exec \"$activeBinPath\" $argsStr"
        }

        return RpcInitializationPlan(
            selectedBackend = selectedBackend,
            forceNeonCpu = forceNeonCpu,
            threads = threads,
            threadNicePriority = nicePriority,
            driverStabilityStatus = stabilityStatus,
            cliFlags = cliFlags,
            processEnv = processEnv,
            shellCommand = "$commonExports$vkExports$execCmd",
            contextWindowTokens = thermal.contextWindowTokens
        )
    }
}
