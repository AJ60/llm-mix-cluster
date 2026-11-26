package com.example

import android.content.ComponentCallbacks2
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.example.data.SilicaMasterApiClient
import com.example.service.ComputeBackend
import com.example.service.DiagnosticSeverity
import com.example.service.HardwareProbe
import com.example.service.RpcService
import com.example.service.TensorCacheManager
import com.example.service.WorkerRunState
import com.example.ui.components.ClusterQrCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.ByteBuffer
import java.nio.ByteOrder

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 34])
class ExampleRobolectricTest {

    @Test
    fun `verify Silica Cluster branding and 50 percent physical RAM LMKD cap`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertEquals("Silica Cluster", context.getString(R.string.app_name))

        val mem = HardwareProbe.readProcMemInfo()
        assertTrue("Total physical RAM must be positive", mem.totalMb > 0)
        assertTrue(
            "Safe cap (${mem.safeCapMb} MB) must never exceed 50% of MemTotal (${mem.totalMb / 2} MB)",
            mem.safeCapMb <= (mem.totalMb / 2)
        )
    }

    @Test
    fun `verify RpcService 32-byte GGUF v9 handshake, engine overrides, and SHED_LAYERS guard`() {
        val controller = Robolectric.buildService(RpcService::class.java).create()
        val service = controller.get()

        val frame = service.build32ByteGgufHandshake(safeCapMb = 1536, bigCores = 4)
        assertEquals(32, frame.size)
        val buf = ByteBuffer.wrap(frame).order(ByteOrder.LITTLE_ENDIAN)
        val magic = ByteArray(4).also { buf.get(it) }
        assertEquals("GGUF", String(magic, Charsets.US_ASCII))
        assertEquals(9, buf.int)
        val tag = ByteArray(8).also { buf.get(it) }
        assertTrue(String(tag, Charsets.US_ASCII).startsWith("v1.0.0"))
        assertEquals(1536, buf.int)
        assertEquals(4, buf.int)

        // Engine override clamps RAM strictly <= 50%
        RpcService.applyEngineOverrides(threads = 2, safeRamPercent = 80)
        val snap = RpcService.telemetry.value
        assertTrue(snap.safeCapMb <= (snap.totalRamMb / 2L).coerceAtLeast(256L))

        @Suppress("DEPRECATION")
        service.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL)
        assertEquals(WorkerRunState.SHEDDING_MEMORY, RpcService.telemetry.value.workerState)
        assertEquals(0, RpcService.telemetry.value.activeLayersAssigned)

        val stopIntent = Intent(ApplicationProvider.getApplicationContext(), RpcService::class.java).apply {
            action = RpcService.ACTION_STOP_FREE_RAM
        }
        service.onStartCommand(stopIntent, 0, 1)
        assertEquals(WorkerRunState.STOPPED, RpcService.telemetry.value.workerState)
    }

    @Test
    fun `verify QR code pairing URI parsing and 21x21 ClusterQrCodec roundtrip`() {
        val uri = "clusterd://pair?master=192.168.1.50:8080&rpc=50052&secret=my-cluster-key"
        val parsed = RpcService.parseQrPairingPayload(uri)
        requireNotNull(parsed)
        assertEquals("192.168.1.50:8080", parsed.first)
        assertEquals("my-cluster-key", parsed.second)
        assertFalse(parsed.third)

        val matrix = ClusterQrCodec.encodeUriTo21x21Matrix(uri)
        assertEquals(21, matrix.size)
        assertEquals(uri, ClusterQrCodec.decode21x21MatrixToUri(matrix))
    }

    @Test
    fun `verify cross-OS auth token bit-exact parity with Rust clusterd`() {
        val secret = "mixed-cluster-secret"
        val token = RpcService.computeClusterAuthToken(secret)
        assertEquals(32, token.size)
        val actualHex = token.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
        assertEquals(
            "e4b7016bcb76b994c5d266d0e7ecc9a533b8b96999acb1d762ad8f0ca631a007",
            actualHex
        )
        assertTrue(RpcService.verifyClusterAuthToken(secret, token))
        assertFalse(RpcService.verifyClusterAuthToken("wrong-secret", token))
    }

    @Test
    fun `verify auto-calibration, 6-check diagnostics, and TensorCacheManager persistence`() {
        val context = ApplicationProvider.getApplicationContext<Context>()

        val profile = RpcService.runAutoCalibration(context)
        assertTrue(profile.isCalibrated)
        assertTrue(profile.performanceScore in 15..100)
        assertTrue(profile.recommendedThreads >= 1)

        val report = RpcService.runAutoDiagnostics(context)
        assertEquals(6, report.checks.size)
        assertTrue(report.checks.any { it.id == "calibration" && it.severity == DiagnosticSeverity.OK })

        // Verify local flash TensorCacheManager write, hit tracking, and clear
        TensorCacheManager.clearCache(context)
        assertFalse(TensorCacheManager.hasLayerCached(context, "small-1.5b", 0))
        val payload = ByteArray(4096) { 0x2A }
        TensorCacheManager.putLayerData(context, "small-1.5b", 0, payload)
        assertTrue(TensorCacheManager.hasLayerCached(context, "small-1.5b", 0))
        val stats = TensorCacheManager.getStats(context, enabled = true)
        assertEquals(1, stats.layerCount)
        assertTrue(stats.hitCount >= 1)
        TensorCacheManager.clearCache(context)
        assertEquals(0, TensorCacheManager.getStats(context, enabled = true).layerCount)
    }

    @Test
    fun `verify Master API URL normalization and legacy hardware fallback`() {
        assertEquals("http://192.168.1.10:8080", SilicaMasterApiClient.normalizeBaseUrl("192.168.1.10:8080/v1/"))
        assertEquals("http://127.0.0.1:3000", SilicaMasterApiClient.normalizeBaseUrl("http://127.0.0.1:3000/"))

        val context = ApplicationProvider.getApplicationContext<Context>()
        RpcService.refreshStaticHardwareSnapshot(context)
        val snap = RpcService.telemetry.value
        assertTrue(snap.legacyGraphicsMode)
        assertEquals(ComputeBackend.ARM_V8_0_NEON, snap.activeBackend)
    }
}
