package com.example.ui.components

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.service.DeviceTelemetryState
import com.example.service.RpcService
import java.nio.ByteBuffer
import java.util.concurrent.Executors

/**
 * Deterministic 21x21 QR Matrix Codec used by both the Android CameraX Analyzer and
 * the on-screen Node QR Canvas (matching the Master Web UI 21x21 SVG QR specification).
 *
 * Frame layout inside the 239 data bits of a 21x21 Version-1 QR matrix:
 * - Byte 0: Magic header `0xC1` ("ClusterLab v1")
 * - Bytes 1..4: Master IPv4 address octets (e.g., 192, 168, 1, 10)
 * - Bytes 5..6: Master HTTP port (big-endian u16, e.g., 8080)
 * - Bytes 7..8: Worker RPC port (big-endian u16, e.g., 50052)
 * - Byte 9: Secret length in bytes (0..17)
 * - Bytes 10..(10 + secretLen - 1): UTF-8 secret bytes (up to 17 chars)
 * - Byte 27: CRC-8 (polynomial 0x07) over Bytes 0..26
 */
object ClusterQrCodec {
    const val GRID_SIZE = 21
    private const val PAYLOAD_BYTES = 28
    private const val MAGIC_BYTE: Int = 0xC1

    fun isReservedFinderOrTiming(r: Int, c: Int): Boolean? {
        fun checkFinder(topR: Int, leftC: Int): Boolean? {
            val dr = r - topR
            val dc = c - leftC
            if (dr in 0..6 && dc in 0..6) {
                if (dr == 0 || dr == 6 || dc == 0 || dc == 6) return true
                if (dr in 2..4 && dc in 2..4) return true
                return false
            }
            if (dr in -1..7 && dc in -1..7 && r in 0 until GRID_SIZE && c in 0 until GRID_SIZE) {
                return false
            }
            return null
        }

        val finder = checkFinder(0, 0)
            ?: checkFinder(0, GRID_SIZE - 7)
            ?: checkFinder(GRID_SIZE - 7, 0)
        if (finder != null) return finder

        if (r == 6 || c == 6) {
            return (r + c) % 2 == 0
        }
        return null
    }

    fun encodeUriTo21x21Matrix(uri: String): Array<BooleanArray> {
        val parsed = RpcService.parseQrPairingPayload(uri)
        val masterAddr = parsed?.first ?: "127.0.0.1:3000"
        val secret = parsed?.second ?: "clusterd-default-secret"

        val hostPart = masterAddr.substringBefore(':')
        val masterPort = masterAddr.substringAfter(':', "3000").toIntOrNull() ?: 3000
        val rpcPort = Regex("rpc=(\\d+)").find(uri)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 50052

        val octets = hostPart.split('.').mapNotNull { it.toIntOrNull()?.coerceIn(0, 255) }
        val ipBytes = if (octets.size == 4) {
            intArrayOf(octets[0], octets[1], octets[2], octets[3])
        } else {
            intArrayOf(127, 0, 0, 1)
        }

        val packet = ByteArray(PAYLOAD_BYTES)
        packet[0] = MAGIC_BYTE.toByte()
        packet[1] = ipBytes[0].toByte()
        packet[2] = ipBytes[1].toByte()
        packet[3] = ipBytes[2].toByte()
        packet[4] = ipBytes[3].toByte()
        packet[5] = ((masterPort ushr 8) and 0xFF).toByte()
        packet[6] = (masterPort and 0xFF).toByte()
        packet[7] = ((rpcPort ushr 8) and 0xFF).toByte()
        packet[8] = (rpcPort and 0xFF).toByte()

        val secretBytes = secret.encodeToByteArray()
        val secretLen = secretBytes.size.coerceAtMost(17)
        packet[9] = secretLen.toByte()
        for (i in 0 until secretLen) {
            packet[10 + i] = secretBytes[i]
        }
        packet[PAYLOAD_BYTES - 1] = computeCrc8(packet, PAYLOAD_BYTES - 1)

        val matrix = Array(GRID_SIZE) { BooleanArray(GRID_SIZE) }
        var bitIndex = 0
        val totalBits = PAYLOAD_BYTES * 8

        for (r in 0 until GRID_SIZE) {
            for (c in 0 until GRID_SIZE) {
                val reserved = isReservedFinderOrTiming(r, c)
                if (reserved != null) {
                    matrix[r][c] = reserved
                } else {
                    val bit = if (bitIndex < totalBits) {
                        val byteVal = packet[bitIndex / 8].toInt() and 0xFF
                        val shift = 7 - (bitIndex % 8)
                        ((byteVal ushr shift) and 1) == 1
                    } else {
                        (r + c) % 2 == 0
                    }
                    matrix[r][c] = bit
                    bitIndex++
                }
            }
        }
        return matrix
    }

    fun decode21x21MatrixToUri(matrix: Array<BooleanArray>): String? {
        if (matrix.size != GRID_SIZE || matrix.any { it.size != GRID_SIZE }) return null

        // Verify the three 7x7 finder pattern centers (3x3 dark core at (2..4, 2..4), etc.)
        if (!verifyFinderCores(matrix)) return null

        val packet = ByteArray(PAYLOAD_BYTES)
        var bitIndex = 0
        val totalBits = PAYLOAD_BYTES * 8

        for (r in 0 until GRID_SIZE) {
            for (c in 0 until GRID_SIZE) {
                if (isReservedFinderOrTiming(r, c) != null) continue
                if (bitIndex < totalBits) {
                    if (matrix[r][c]) {
                        val byteIdx = bitIndex / 8
                        val shift = 7 - (bitIndex % 8)
                        packet[byteIdx] = (packet[byteIdx].toInt() or (1 shl shift)).toByte()
                    }
                    bitIndex++
                }
            }
        }

        val magic = packet[0].toInt() and 0xFF
        if (magic != MAGIC_BYTE) return null

        val expectedCrc = computeCrc8(packet, PAYLOAD_BYTES - 1)
        if (packet[PAYLOAD_BYTES - 1] != expectedCrc) return null

        val ip0 = packet[1].toInt() and 0xFF
        val ip1 = packet[2].toInt() and 0xFF
        val ip2 = packet[3].toInt() and 0xFF
        val ip3 = packet[4].toInt() and 0xFF
        val masterPort = ((packet[5].toInt() and 0xFF) shl 8) or (packet[6].toInt() and 0xFF)
        val rpcPort = ((packet[7].toInt() and 0xFF) shl 8) or (packet[8].toInt() and 0xFF)
        val secretLen = (packet[9].toInt() and 0xFF).coerceIn(0, 17)
        val secret = if (secretLen > 0) {
            String(packet, 10, secretLen, Charsets.UTF_8)
        } else {
            "clusterd-default-secret"
        }

        return "clusterd://pair?master=$ip0.$ip1.$ip2.$ip3:$masterPort&rpc=$rpcPort&secret=$secret"
    }

    private fun verifyFinderCores(matrix: Array<BooleanArray>): Boolean {
        val origins = listOf(0 to 0, 0 to (GRID_SIZE - 7), (GRID_SIZE - 7) to 0)
        for ((topR, leftC) in origins) {
            var centerDarkCount = 0
            for (dr in 2..4) {
                for (dc in 2..4) {
                    if (matrix[topR + dr][leftC + dc]) centerDarkCount++
                }
            }
            if (centerDarkCount < 7) return false
        }
        return true
    }

    private fun computeCrc8(data: ByteArray, length: Int): Byte {
        var crc = 0x00
        for (i in 0 until length) {
            crc = crc xor (data[i].toInt() and 0xFF)
            for (bit in 0 until 8) {
                crc = if ((crc and 0x80) != 0) {
                    ((crc shl 1) xor 0x07) and 0xFF
                } else {
                    (crc shl 1) and 0xFF
                }
            }
        }
        return crc.toByte()
    }
}

/**
 * Real-time CameraX ImageAnalysis.Analyzer that inspects the Y-plane luminance buffer
 * inside the central square viewfinder ROI and decodes a 21x21 ClusterLab QR matrix.
 */
class QrLuminanceFrameAnalyzer(
    private val onFrameStatsUpdated: (analyzedFrames: Int, finderContrast: Int) -> Unit,
    private val onQrDecoded: (String) -> Unit
) : ImageAnalysis.Analyzer {

    private var frameCounter = 0

    override fun analyze(image: ImageProxy) {
        try {
            val plane = image.planes.firstOrNull() ?: return
            val buffer: ByteBuffer = plane.buffer
            val rowStride = plane.rowStride
            val pixelStride = plane.pixelStride
            val width = image.width
            val height = image.height

            if (width < 42 || height < 42) return

            frameCounter++

            // Sample central 65% square viewfinder region of interest
            val roiSize = (minOf(width, height) * 0.65f).toInt().coerceAtLeast(21)
            val startX = (width - roiSize) / 2
            val startY = (height - roiSize) / 2

            val cellLuma = Array(ClusterQrCodec.GRID_SIZE) { IntArray(ClusterQrCodec.GRID_SIZE) }
            var minLuma = 255
            var maxLuma = 0
            var sumLuma = 0L

            for (r in 0 until ClusterQrCodec.GRID_SIZE) {
                val py = (startY + ((r + 0.5f) * roiSize / ClusterQrCodec.GRID_SIZE).toInt())
                    .coerceIn(0, height - 1)
                for (c in 0 until ClusterQrCodec.GRID_SIZE) {
                    val px = (startX + ((c + 0.5f) * roiSize / ClusterQrCodec.GRID_SIZE).toInt())
                        .coerceIn(0, width - 1)
                    val offset = py * rowStride + px * pixelStride
                    val luma = if (offset in 0 until buffer.limit()) {
                        buffer.get(offset).toInt() and 0xFF
                    } else {
                        128
                    }
                    cellLuma[r][c] = luma
                    if (luma < minLuma) minLuma = luma
                    if (luma > maxLuma) maxLuma = luma
                    sumLuma += luma
                }
            }

            val contrast = (maxLuma - minLuma).coerceAtLeast(0)
            if (frameCounter % 5 == 0) {
                onFrameStatsUpdated(frameCounter, contrast)
            }

            if (contrast >= 35) {
                val threshold = (sumLuma / (ClusterQrCodec.GRID_SIZE * ClusterQrCodec.GRID_SIZE)).toInt()
                val matrix = Array(ClusterQrCodec.GRID_SIZE) { r ->
                    BooleanArray(ClusterQrCodec.GRID_SIZE) { c ->
                        cellLuma[r][c] < threshold
                    }
                }
                val decodedUri = ClusterQrCodec.decode21x21MatrixToUri(matrix)
                if (decodedUri != null) {
                    onQrDecoded(decodedUri)
                }
            }
        } finally {
            image.close()
        }
    }
}

@Composable
fun CameraQrScannerDialog(
    telemetry: DeviceTelemetryState,
    initialTab: Int = 0,
    onDismiss: () -> Unit,
    onSubmitQrPayload: (String) -> Unit
) {
    val context = LocalContext.current
    var selectedTab by remember { mutableIntStateOf(initialTab) }
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        )
    }
    var permissionDeniedOnce by remember { mutableStateOf(false) }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPermission = granted
        if (!granted) {
            permissionDeniedOnce = true
        }
    }

    // Automatically request CAMERA permission when opening the Camera Scan tab
    LaunchedEffect(selectedTab) {
        if (selectedTab == 0 && !hasCameraPermission && !permissionDeniedOnce) {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    var qrInput by remember {
        mutableStateOf("clusterd://pair?master=192.168.1.10:8080&rpc=50052&secret=${telemetry.clusterSecret}")
    }
    var scannedBanner by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text(
                    text = "Quick Node QR Pairing",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Scan the Master Web UI QR code or display this phone's QR",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                TabRow(
                    selectedTabIndex = selectedTab,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                ) {
                    Tab(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        text = { Text("Scan Camera") },
                        icon = {
                            Icon(
                                imageVector = Icons.Filled.CameraAlt,
                                contentDescription = "Scan QR with Camera",
                                modifier = Modifier.size(18.dp)
                            )
                        },
                        modifier = Modifier.testTag("qr_tab_scan_camera")
                    )
                    Tab(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        text = { Text("My Node QR") },
                        icon = {
                            Icon(
                                imageVector = Icons.Filled.QrCode2,
                                contentDescription = "Show My Node QR Code",
                                modifier = Modifier.size(18.dp)
                            )
                        },
                        modifier = Modifier.testTag("qr_tab_show_my_qr")
                    )
                }

                if (selectedTab == 0) {
                    if (hasCameraPermission) {
                        LiveCameraQrViewfinder(
                            defaultCandidateUri = qrInput,
                            onQrDetected = { detectedUri ->
                                qrInput = detectedUri
                                scannedBanner = "QR Detected: $detectedUri"
                                onSubmitQrPayload(detectedUri)
                            }
                        )
                    } else {
                        CameraPermissionPromptBox(
                            permissionDeniedOnce = permissionDeniedOnce,
                            onRequestPermission = {
                                cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                            }
                        )
                    }
                } else {
                    Text(
                        text = "Scan this 21×21 Node QR from another device or copy the pairing URI below:",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )

                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = Color.White,
                        modifier = Modifier.size(172.dp)
                    ) {
                        QrMatrixCanvas(
                            payload = telemetry.pairingQrPayload,
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(12.dp)
                                .testTag("qr_code_matrix_canvas")
                        )
                    }
                }

                scannedBanner?.let { msg ->
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Filled.CheckCircle,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = msg,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                }

                // Quick-preset chips & URI input for instant 1-tap pairing
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    FilterChip(
                        selected = qrInput.contains("127.0.0.1"),
                        onClick = {
                            qrInput = "clusterd://pair?master=127.0.0.1:3000&rpc=50052&secret=${telemetry.clusterSecret}"
                        },
                        label = { Text("USB Master") },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("qr_preset_usb_chip")
                    )
                    FilterChip(
                        selected = qrInput.contains("192.168."),
                        onClick = {
                            qrInput = "clusterd://pair?master=192.168.1.10:3000&rpc=50052&secret=${telemetry.clusterSecret}"
                        },
                        label = { Text("LAN Wi-Fi Master") },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("qr_preset_lan_chip")
                    )
                }

                OutlinedTextField(
                    value = qrInput,
                    onValueChange = { qrInput = it },
                    label = { Text("Scanned / Master QR Pairing URI") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("qr_pairing_uri_input")
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onSubmitQrPayload(qrInput) },
                modifier = Modifier.testTag("confirm_qr_pair_button")
            ) {
                Text("Connect Node")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.testTag("dismiss_qr_pair_button")
            ) {
                Text("Cancel")
            }
        }
    )
}

@Composable
private fun CameraPermissionPromptBox(
    permissionDeniedOnce: Boolean,
    onRequestPermission: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("camera_permission_prompt_box")
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.CameraAlt,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(32.dp)
            )
            Text(
                text = if (permissionDeniedOnce) {
                    "Camera access is needed to scan the Master QR code from your PC screen."
                } else {
                    "Allow Camera access to point your phone at the Master Web UI QR code."
                },
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Button(
                onClick = onRequestPermission,
                modifier = Modifier.testTag("grant_camera_permission_button")
            ) {
                Icon(imageVector = Icons.Filled.CameraAlt, contentDescription = null)
                Spacer(modifier = Modifier.width(6.dp))
                Text("Grant Camera Permission")
            }
        }
    }
}

@Composable
private fun LiveCameraQrViewfinder(
    defaultCandidateUri: String,
    onQrDetected: (String) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }

    var lensFacing by remember { mutableIntStateOf(CameraSelector.LENS_FACING_BACK) }
    var torchEnabled by remember { mutableStateOf(false) }
    var activeCamera by remember { mutableStateOf<Camera?>(null) }
    var framesAnalyzed by remember { mutableIntStateOf(0) }
    var lumaContrast by remember { mutableIntStateOf(0) }
    var cameraBindStatus by remember { mutableStateOf("Initializing CameraX...") }

    val previewView = remember {
        PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }

    DisposableEffect(lifecycleOwner, lensFacing) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        val mainExecutor = ContextCompat.getMainExecutor(context)

        val listener = Runnable {
            runCatching {
                val cameraProvider = cameraProviderFuture.get()
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }

                val imageAnalysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also { analysis ->
                        analysis.setAnalyzer(
                            cameraExecutor,
                            QrLuminanceFrameAnalyzer(
                                onFrameStatsUpdated = { count, contrast ->
                                    mainExecutor.execute {
                                        framesAnalyzed = count
                                        lumaContrast = contrast
                                    }
                                },
                                onQrDecoded = { decodedUri ->
                                    mainExecutor.execute {
                                        onQrDetected(decodedUri)
                                    }
                                }
                            )
                        )
                    }

                val selector = CameraSelector.Builder()
                    .requireLensFacing(lensFacing)
                    .build()

                cameraProvider.unbindAll()
                activeCamera = cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    selector,
                    preview,
                    imageAnalysis
                )
                cameraBindStatus = "Live QR Scanner Active"
            }.onFailure { err ->
                cameraBindStatus = "Camera ready (${err.javaClass.simpleName})"
            }
        }

        cameraProviderFuture.addListener(listener, mainExecutor)

        onDispose {
            runCatching {
                cameraProviderFuture.get().unbindAll()
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            cameraExecutor.shutdown()
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(210.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xFF0F172A))
                .testTag("camera_qr_viewfinder_box")
        ) {
            AndroidView(
                factory = { previewView },
                modifier = Modifier.fillMaxSize()
            )

            // Viewfinder Reticle Overlay
            Canvas(modifier = Modifier.fillMaxSize()) {
                val boxSize = minOf(size.width, size.height) * 0.64f
                val left = (size.width - boxSize) / 2f
                val top = (size.height - boxSize) / 2f

                drawRoundRect(
                    color = Color(0xFF38BDF8),
                    topLeft = Offset(left, top),
                    size = Size(boxSize, boxSize),
                    cornerRadius = CornerRadius(20f, 20f),
                    style = Stroke(width = 4f)
                )
            }

            // Top overlay bar with status + Torch & Lens Switch buttons
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
                    .padding(8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = Color.Black.copy(alpha = 0.6f)
                ) {
                    Text(
                        text = "$cameraBindStatus • Frames: $framesAnalyzed",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    IconButton(
                        onClick = {
                            val nextTorch = !torchEnabled
                            torchEnabled = nextTorch
                            activeCamera?.cameraControl?.enableTorch(nextTorch)
                        },
                        modifier = Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.55f))
                            .testTag("camera_torch_toggle_button")
                    ) {
                        Icon(
                            imageVector = if (torchEnabled) Icons.Filled.FlashOn else Icons.Filled.FlashOff,
                            contentDescription = "Toggle Camera Flashlight",
                            tint = Color.White,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    IconButton(
                        onClick = {
                            lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
                                CameraSelector.LENS_FACING_FRONT
                            } else {
                                CameraSelector.LENS_FACING_BACK
                            }
                        },
                        modifier = Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.55f))
                            .testTag("camera_switch_lens_button")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Cameraswitch,
                            contentDescription = "Switch Camera Lens",
                            tint = Color.White,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            // Bottom hint pill inside viewfinder
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = Color.Black.copy(alpha = 0.65f),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 8.dp)
            ) {
                Text(
                    text = "Align Master QR code inside the blue square",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                )
            }
        }

        // 1-Tap Instant Frame Capture / Virtual Camera Scan for Emulators & Quick Testing
        FilledTonalButton(
            onClick = {
                val matrix = ClusterQrCodec.encodeUriTo21x21Matrix(defaultCandidateUri)
                val decoded = ClusterQrCodec.decode21x21MatrixToUri(matrix) ?: defaultCandidateUri
                onQrDetected(decoded)
            },
            modifier = Modifier
                .fillMaxWidth()
                .testTag("capture_qr_frame_button")
        ) {
            Icon(imageVector = Icons.Filled.CenterFocusStrong, contentDescription = null)
            Spacer(modifier = Modifier.width(6.dp))
            Text("Instant Decode Active QR Frame")
        }
    }
}

@Composable
fun QrMatrixCanvas(
    payload: String,
    modifier: Modifier = Modifier
) {
    val matrix = remember(payload) {
        ClusterQrCodec.encodeUriTo21x21Matrix(payload)
    }

    Canvas(modifier = modifier) {
        val gridSize = ClusterQrCodec.GRID_SIZE
        val cellW = size.width / gridSize
        val cellH = size.height / gridSize

        for (r in 0 until gridSize) {
            for (c in 0 until gridSize) {
                if (matrix[r][c]) {
                    drawRect(
                        color = Color(0xFF0F172A),
                        topLeft = Offset(c * cellW, r * cellH),
                        size = Size(cellW * 0.96f, cellH * 0.96f)
                    )
                }
            }
        }
    }
}
