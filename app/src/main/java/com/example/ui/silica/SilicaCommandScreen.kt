package com.example.ui.silica

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoFixHigh
import androidx.compose.material.icons.outlined.BatteryChargingFull
import androidx.compose.material.icons.outlined.BatteryStd
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.DeviceThermostat
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.HealthAndSafety
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.Radar
import androidx.compose.material.icons.outlined.Router
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Usb
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.R
import com.example.service.WorkerRunState
import com.example.ui.theme.JetBrainsMonoFontFamily
import kotlin.math.roundToInt

@Composable
fun SilicaCommandScreen(
    uiState: SilicaUiState,
    onToggleEngine: (Boolean) -> Unit,
    onSelectRole: (String) -> Unit,
    onOpenModelSheet: () -> Unit,
    onPickLocalGguf: (Uri) -> Unit,
    onSetThreads: (Int) -> Unit,
    onSetSafeRamPercent: (Int) -> Unit,
    onSetContextLength: (Int) -> Unit,
    onSetKvQuant: (String) -> Unit,
    onRunAutoTune: () -> Unit,
    onSetBridgeMode: (Boolean) -> Unit,
    onSaveMasterUrlAndSecret: (String, String) -> Unit,
    onPingSyncMaster: () -> Unit,
    onAutoDetect: () -> Unit,
    onOpenQr: () -> Unit,
    onSelectLogFilter: (TerminalLogFilter) -> Unit,
    onClearLogs: () -> Unit,
    onRunDiagnostics: () -> Unit,
    onDismissOnboarding: () -> Unit
) {
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val tel = uiState.telemetry
    val cfg = uiState.config

    val isOnline = tel.workerState == WorkerRunState.RUNNING_USB ||
        tel.workerState == WorkerRunState.RUNNING_WIFI
    val isGuarded = tel.workerState == WorkerRunState.THROTTLED_THERMAL ||
        tel.workerState == WorkerRunState.SHEDDING_MEMORY

    // Runtime notification permission for Android 13+ foreground service
    val notifLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { _ ->
        onToggleEngine(true)
    }

    // Zero-permission SAF document picker for local .gguf files
    val ggufPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) onPickLocalGguf(uri)
    }

    val handleEngineSwitch: (Boolean) -> Unit = { turnOn ->
        if (turnOn && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                onToggleEngine(true)
            }
        } else {
            onToggleEngine(turnOn)
        }
    }

    val isProbing = tel.workerState == WorkerRunState.PROBING
    val pulseAlpha = if (isProbing) {
        val pulseTransition = rememberInfiniteTransition(label = "node_pulse")
        val animated by pulseTransition.animateFloat(
            initialValue = 0.45f,
            targetValue = 1.0f,
            animationSpec = infiniteRepeatable(tween(950), RepeatMode.Reverse),
            label = "led_alpha"
        )
        animated
    } else {
        1.0f
    }

    val statusColor by animateColorAsState(
        targetValue = when {
            isOnline -> cs.primary
            isGuarded -> cs.tertiary
            tel.workerState == WorkerRunState.PROBING -> cs.secondary
            else -> cs.onSurfaceVariant
        },
        animationSpec = spring(),
        label = "status_color"
    )

    var masterUrlDraft by remember(cfg.masterBaseUrl) { mutableStateOf(cfg.masterBaseUrl) }
    var secretDraft by remember(cfg.clusterSecret) { mutableStateOf(cfg.clusterSecret) }
    val maxCores = tel.totalCoreCount.coerceAtLeast(2)
    val effectiveThreads = if (cfg.threadCountOverride > 0) {
        cfg.threadCountOverride.coerceIn(1, maxCores)
    } else {
        tel.bigCoreCount.coerceIn(1, maxCores)
    }
    var threadsSlider by remember(effectiveThreads) { mutableFloatStateOf(effectiveThreads.toFloat()) }
    var ramPctSlider by remember(cfg.safeRamPercent) { mutableFloatStateOf(cfg.safeRamPercent.toFloat()) }
    var showAdvancedTuning by remember { mutableStateOf(false) }
    var showDiagnosticsTerminal by remember { mutableStateOf(false) }

    LaunchedEffect(cfg.masterBaseUrl) { masterUrlDraft = cfg.masterBaseUrl }
    LaunchedEffect(cfg.clusterSecret) { secretDraft = cfg.clusterSecret }

    val filteredLogs = remember(tel.eventLogs, uiState.logFilter) {
        when (uiState.logFilter) {
            TerminalLogFilter.ALL -> tel.eventLogs
            TerminalLogFilter.NETWORK -> tel.eventLogs.filter {
                it.contains("Master", ignoreCase = true) ||
                    it.contains("UDP", ignoreCase = true) ||
                    it.contains("QR", ignoreCase = true) ||
                    it.contains("Wi-Fi", ignoreCase = true) ||
                    it.contains("USB", ignoreCase = true) ||
                    it.contains("handshake", ignoreCase = true) ||
                    it.contains("peer", ignoreCase = true)
            }
            TerminalLogFilter.GUARD -> tel.eventLogs.filter {
                it.contains("RAM", ignoreCase = true) ||
                    it.contains("LMKD", ignoreCase = true) ||
                    it.contains("Thermal", ignoreCase = true) ||
                    it.contains("Calibrated", ignoreCase = true) ||
                    it.contains("Vulkan", ignoreCase = true) ||
                    it.contains("PAUSED", ignoreCase = true)
            }
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag("command_screen_list"),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // 0. Guided 3-Step Onboarding Card (dismissible)
        if (!cfg.onboardingCompleted) {
            item {
                Surface(
                    shape = MaterialTheme.shapes.large,
                    color = cs.primaryContainer.copy(alpha = 0.45f),
                    border = BorderStroke(1.dp, cs.primary.copy(alpha = 0.45f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Quick Start · Join Silica Cluster",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = cs.onSurface
                            )
                            OutlinedButton(
                                onClick = onDismissOnboarding,
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                                modifier = Modifier.minimumInteractiveComponentSize()
                            ) {
                                Text("Dismiss", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                        Text(
                            text = "1. Connect via USB-C or 5 GHz Wi-Fi on the same network as your PC.\n" +
                                "2. Tap Auto-Detect below to link with your Master (<=50% safe RAM cap).\n" +
                                "3. Open Chat to send text or vision prompts to your loaded GGUF model.",
                            style = MaterialTheme.typography.bodySmall,
                            color = cs.onSurfaceVariant
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = {
                                    onAutoDetect()
                                    onDismissOnboarding()
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = cs.primary,
                                    contentColor = cs.onPrimary
                                ),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                Icon(Icons.Outlined.Radar, contentDescription = null, modifier = Modifier.size(15.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Auto-Detect Master", style = MaterialTheme.typography.labelSmall)
                            }
                            OutlinedButton(
                                onClick = onOpenQr,
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                Icon(Icons.Outlined.QrCodeScanner, contentDescription = null, modifier = Modifier.size(15.dp))
                                Spacer(Modifier.width(5.dp))
                                Text("Scan QR", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }
        }

        // 1. Hero Engine Control Card
        item {
            Surface(
                shape = MaterialTheme.shapes.large,
                color = cs.surfaceContainer,
                border = BorderStroke(1.dp, if (isOnline) cs.primary.copy(alpha = 0.55f) else cs.outlineVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(42.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(statusColor.copy(alpha = 0.14f))
                                    .border(1.dp, statusColor.copy(alpha = 0.4f), RoundedCornerShape(12.dp)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.PowerSettingsNew,
                                    contentDescription = stringResource(R.string.cmd_engine_title),
                                    tint = statusColor,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                            Column {
                                Text(
                                    text = stringResource(R.string.cmd_engine_title),
                                    style = MaterialTheme.typography.titleMedium,
                                    color = cs.onSurface
                                )
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(8.dp)
                                            .alpha(if (isOnline) pulseAlpha else 1f)
                                            .clip(CircleShape)
                                            .background(statusColor)
                                    )
                                    Text(
                                        text = tel.workerState.badge,
                                        style = MaterialTheme.typography.labelMedium,
                                        color = statusColor,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }

                        Switch(
                            checked = isOnline || tel.workerState == WorkerRunState.PROBING,
                            onCheckedChange = handleEngineSwitch,
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("command_engine_switch"),
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = cs.onPrimary,
                                checkedTrackColor = cs.primary,
                                uncheckedThumbColor = cs.onSurfaceVariant,
                                uncheckedTrackColor = cs.surfaceContainerHigh
                            )
                        )
                    }

                    // Role Selector: Worker Node vs Master Bridge
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        val isWorker = cfg.nodeRole != "MASTER_HUB"
                        FilterChip(
                            selected = isWorker,
                            onClick = { onSelectRole("WORKER") },
                            label = { Text(stringResource(R.string.cmd_role_worker)) },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Outlined.Memory,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = cs.primaryContainer,
                                selectedLabelColor = cs.onPrimaryContainer,
                                selectedLeadingIconColor = cs.primary
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .minimumInteractiveComponentSize()
                                .testTag("role_worker_chip")
                        )
                        FilterChip(
                            selected = !isWorker,
                            onClick = { onSelectRole("MASTER_HUB") },
                            label = { Text(stringResource(R.string.cmd_role_master)) },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Outlined.Hub,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = cs.secondaryContainer,
                                selectedLabelColor = cs.onSecondaryContainer,
                                selectedLeadingIconColor = cs.secondary
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .minimumInteractiveComponentSize()
                                .testTag("role_master_chip")
                        )
                    }

                    // Quick status strip + 1-Tap Stop & Free RAM when active
                    AnimatedVisibility(visible = isOnline || isGuarded) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "RPC ${tel.bindAddress} • ${tel.activeLayersAssigned} layers • ${tel.handshakesCompleted} handshakes",
                                style = MaterialTheme.typography.labelSmall,
                                color = cs.onSurfaceVariant,
                                modifier = Modifier.weight(1f)
                            )
                            OutlinedButton(
                                onClick = { onToggleEngine(false) },
                                border = BorderStroke(1.dp, cs.error.copy(alpha = 0.6f)),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = cs.error),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                modifier = Modifier
                                    .minimumInteractiveComponentSize()
                                    .testTag("stop_free_ram_button")
                            ) {
                                Text(
                                    text = stringResource(R.string.cmd_stop_free_ram),
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        }
                    }
                }
            }
        }

        // 1b. Live Model Sharding & Layer Cache Residency Card
        item {
            SilicaLayerShardingCard(
                clusterSnapshot = uiState.clusterSnapshot,
                telemetry = tel,
                activeModelDisplayName = uiState.activeModelDisplayName,
                isStreamingChat = uiState.isStreamingChat,
                liveStreamingTokPerSec = uiState.liveStreamingTokPerSec,
                masterReachable = uiState.masterHealth.reachable
            )
        }

        // 2. Live Hardware Telemetry Card
        item {
            SectionSurfaceCard(
                title = stringResource(R.string.cmd_section_telemetry),
                icon = Icons.Outlined.Speed,
                accentColor = cs.secondary
            ) {
                val ramRatio = if (tel.totalRamMb > 0L) {
                    (tel.safeCapMb.toFloat() / tel.totalRamMb.toFloat()).coerceIn(0f, 1f)
                } else 0.4f

                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "LMKD Safe Pool",
                            style = MaterialTheme.typography.bodySmall,
                            color = cs.onSurfaceVariant
                        )
                        Text(
                            text = "${tel.safeCapMb} MB cap / ${tel.availableRamMb} MB free (${tel.totalRamMb} MB)",
                            style = MaterialTheme.typography.labelSmall,
                            color = cs.primary
                        )
                    }
                    LinearProgressIndicator(
                        progress = { ramRatio },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(7.dp)
                            .clip(RoundedCornerShape(4.dp)),
                        color = cs.primary,
                        trackColor = cs.surfaceContainerHigh
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        TelemetryTile(
                            label = "CPU / BACKEND",
                            value = "${tel.bigCoreCount}/${tel.totalCoreCount} Cores",
                            sub = tel.activeBackend.label.substringBefore(" ("),
                            icon = Icons.Outlined.Memory,
                            accent = cs.secondary,
                            modifier = Modifier.weight(1f)
                        )
                        val hot = tel.temperatureCelsius >= 41f
                        TelemetryTile(
                            label = "THERMAL",
                            value = "${"%.1f".format(tel.temperatureCelsius)}°C",
                            sub = "Headroom ${"%.0f".format((1f - tel.thermalHeadroom.coerceIn(0f, 1f)) * 100)}%",
                            icon = Icons.Outlined.DeviceThermostat,
                            accent = if (hot) cs.tertiary else cs.primary,
                            modifier = Modifier.weight(1f)
                        )
                        TelemetryTile(
                            label = "POWER",
                            value = "${tel.batteryPercent}%",
                            sub = if (tel.isPluggedIn) "Charging" else "Battery",
                            icon = if (tel.isPluggedIn) Icons.Outlined.BatteryChargingFull else Icons.Outlined.BatteryStd,
                            accent = if (!tel.isPluggedIn && tel.batteryPercent < 20) cs.error else cs.primary,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }

        // 3. Collapsible Advanced Engine & Network Disclosure Row
        item {
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = cs.surfaceContainer,
                border = BorderStroke(1.dp, cs.outlineVariant),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showAdvancedTuning = !showAdvancedTuning }
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Outlined.Tune, contentDescription = null, tint = cs.primary, modifier = Modifier.size(18.dp))
                        Column {
                            Text("Engine & Network Tuning", style = MaterialTheme.typography.titleSmall, color = cs.onSurface)
                            Text(
                                "${if (cfg.usbBridgeOnly) "USB-C ADB" else "Wi-Fi LAN"} · ${effectiveThreads}T · ${cfg.safeRamPercent}% cap",
                                style = MaterialTheme.typography.labelSmall,
                                color = cs.onSurfaceVariant
                            )
                        }
                    }
                    Icon(
                        imageVector = if (showAdvancedTuning) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                        contentDescription = null,
                        tint = cs.onSurfaceVariant
                    )
                }
            }
        }

        if (showAdvancedTuning) {
        // 3. Full Silica Model & Engine Tuning Card
        item {
            SectionSurfaceCard(
                title = stringResource(R.string.cmd_section_engine),
                icon = Icons.Outlined.Tune,
                accentColor = cs.primary,
                trailing = {
                    OutlinedButton(
                        onClick = onRunAutoTune,
                        enabled = !tel.calibration.isCalibrating,
                        border = BorderStroke(1.dp, cs.primary.copy(alpha = 0.5f)),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("auto_tune_button")
                    ) {
                        if (tel.calibration.isCalibrating) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(14.dp),
                                strokeWidth = 2.dp,
                                color = cs.primary
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.cmd_tuning_busy), style = MaterialTheme.typography.labelSmall)
                        } else {
                            Icon(Icons.Outlined.AutoFixHigh, contentDescription = null, modifier = Modifier.size(15.dp))
                            Spacer(Modifier.width(5.dp))
                            Text(stringResource(R.string.cmd_auto_tune), style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    // Active Model Picker Row + Local GGUF SAF Picker
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = cs.surfaceContainerLow,
                        border = BorderStroke(1.dp, cs.outlineVariant),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(onClick = onOpenModelSheet)
                            .minimumInteractiveComponentSize()
                            .testTag("command_model_selector_card")
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "ACTIVE MODEL",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = cs.secondary
                                )
                                Text(
                                    text = uiState.activeModelDisplayName,
                                    style = MaterialTheme.typography.titleSmall,
                                    color = cs.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = if (uiState.liveModels.isNotEmpty()) {
                                        "${uiState.liveModels.size} live Master model(s) synced"
                                    } else {
                                        "Tap to switch GGUF preset or sync Master models"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = cs.onSurfaceVariant
                                )
                            }
                            OutlinedButton(
                                onClick = { ggufPickerLauncher.launch(arrayOf("*/*")) },
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                                modifier = Modifier
                                    .minimumInteractiveComponentSize()
                                    .testTag("pick_local_gguf_button")
                            ) {
                                Icon(Icons.Outlined.FolderOpen, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(5.dp))
                                Text("GGUF", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }

                    // CPU Threads Slider
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(stringResource(R.string.cmd_threads_label), style = MaterialTheme.typography.bodyMedium)
                            Text(
                                text = "${threadsSlider.roundToInt()} / $maxCores cores",
                                style = MaterialTheme.typography.labelMedium,
                                color = cs.primary
                            )
                        }
                        Slider(
                            value = threadsSlider,
                            onValueChange = { threadsSlider = it },
                            onValueChangeFinished = { onSetThreads(threadsSlider.roundToInt()) },
                            valueRange = 1f..maxCores.toFloat(),
                            steps = (maxCores - 2).coerceAtLeast(0),
                            colors = SliderDefaults.colors(
                                thumbColor = cs.primary,
                                activeTrackColor = cs.primary
                            ),
                            modifier = Modifier.testTag("threads_slider")
                        )
                    }

                    // LMKD Safe RAM Cap Slider (strictly 25%..50%)
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(stringResource(R.string.cmd_ram_cap_label), style = MaterialTheme.typography.bodyMedium)
                            Text(
                                text = "${ramPctSlider.roundToInt()}% (${tel.safeCapMb} MB)",
                                style = MaterialTheme.typography.labelMedium,
                                color = cs.secondary
                            )
                        }
                        Slider(
                            value = ramPctSlider,
                            onValueChange = { ramPctSlider = it },
                            onValueChangeFinished = { onSetSafeRamPercent(ramPctSlider.roundToInt()) },
                            valueRange = 25f..50f,
                            steps = 4,
                            colors = SliderDefaults.colors(
                                thumbColor = cs.secondary,
                                activeTrackColor = cs.secondary
                            ),
                            modifier = Modifier.testTag("ram_cap_slider")
                        )
                    }

                    // Context Length & KV Quant Chips
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = "${stringResource(R.string.cmd_context_label)} & ${stringResource(R.string.cmd_kv_quant_label)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = cs.onSurfaceVariant
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            listOf(2048, 4096, 8192, 16384).forEach { ctx ->
                                FilterChip(
                                    selected = cfg.contextLength == ctx,
                                    onClick = { onSetContextLength(ctx) },
                                    label = { Text("${ctx / 1024}K ctx", style = MaterialTheme.typography.labelSmall) },
                                    modifier = Modifier.testTag("ctx_chip_$ctx")
                                )
                            }
                            listOf("F16", "Q8_0", "Q4_0").forEach { quant ->
                                FilterChip(
                                    selected = cfg.kvCacheQuant == quant,
                                    onClick = { onSetKvQuant(quant) },
                                    label = { Text("KV $quant", style = MaterialTheme.typography.labelSmall) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = cs.secondaryContainer,
                                        selectedLabelColor = cs.onSecondaryContainer
                                    ),
                                    modifier = Modifier.testTag("kv_chip_$quant")
                                )
                            }
                        }
                    }

                    if (tel.calibration.isCalibrated) {
                        Text(
                            text = "Calibration: ${tel.calibration.tierLabel} • Score ${tel.calibration.performanceScore}/100 • ${tel.calibration.benchmarkDurationMs}ms",
                            style = MaterialTheme.typography.labelSmall,
                            color = cs.onSurfaceVariant
                        )
                    }
                }
            }
        }

        // 4. Network Bridge & Master Endpoint Card
        item {
            SectionSurfaceCard(
                title = stringResource(R.string.cmd_section_bridge),
                icon = Icons.Outlined.Router,
                accentColor = cs.secondary
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilterChip(
                            selected = cfg.usbBridgeOnly,
                            onClick = { onSetBridgeMode(true) },
                            label = { Text(stringResource(R.string.cmd_bridge_usb), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            leadingIcon = { Icon(Icons.Outlined.Usb, contentDescription = null, modifier = Modifier.size(16.dp)) },
                            modifier = Modifier
                                .weight(1f)
                                .minimumInteractiveComponentSize()
                                .testTag("bridge_usb_chip")
                        )
                        FilterChip(
                            selected = !cfg.usbBridgeOnly,
                            onClick = { onSetBridgeMode(false) },
                            label = { Text(stringResource(R.string.cmd_bridge_wifi), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            leadingIcon = { Icon(Icons.Outlined.Wifi, contentDescription = null, modifier = Modifier.size(16.dp)) },
                            modifier = Modifier
                                .weight(1f)
                                .minimumInteractiveComponentSize()
                                .testTag("bridge_wifi_chip")
                        )
                    }

                    OutlinedTextField(
                        value = masterUrlDraft,
                        onValueChange = { masterUrlDraft = it },
                        label = { Text(stringResource(R.string.cmd_master_url_label)) },
                        placeholder = { Text("http://192.168.1.10:8080") },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.labelMedium,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("master_url_input")
                    )

                    OutlinedTextField(
                        value = secretDraft,
                        onValueChange = { secretDraft = it },
                        label = { Text(stringResource(R.string.cmd_cluster_secret_label)) },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.labelMedium,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("cluster_secret_input")
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = { onSaveMasterUrlAndSecret(masterUrlDraft, secretDraft) },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = cs.primary,
                                contentColor = cs.onPrimary
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .minimumInteractiveComponentSize()
                                .testTag("ping_sync_master_button")
                        ) {
                            if (uiState.isProbingMaster) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(15.dp),
                                    strokeWidth = 2.dp,
                                    color = cs.onPrimary
                                )
                            } else {
                                Text(stringResource(R.string.cmd_ping_sync))
                            }
                        }
                        OutlinedButton(
                            onClick = onAutoDetect,
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("command_auto_detect_button")
                        ) {
                            Icon(Icons.Outlined.Radar, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("LAN")
                        }
                        OutlinedButton(
                            onClick = onOpenQr,
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("command_qr_button")
                        ) {
                            Icon(Icons.Outlined.QrCodeScanner, contentDescription = "Scan QR", modifier = Modifier.size(16.dp))
                        }
                    }

                    val reachColor = if (uiState.masterHealth.reachable) cs.primary else cs.onSurfaceVariant
                    Text(
                        text = "Master Status: ${uiState.masterHealth.detail} • Paired: ${tel.pairedMasterAddress}",
                        style = MaterialTheme.typography.labelSmall,
                        color = reachColor
                    )
                }
            }
        }
        } // end if (showAdvancedTuning)

        // 5. Collapsible Diagnostics & Live Terminal Toggle
        item {
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = cs.surfaceContainer,
                border = BorderStroke(1.dp, cs.outlineVariant),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showDiagnosticsTerminal = !showDiagnosticsTerminal }
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Outlined.Terminal, contentDescription = null, tint = cs.secondary, modifier = Modifier.size(18.dp))
                        Column {
                            Text("Diagnostics & Live Terminal", style = MaterialTheme.typography.titleSmall, color = cs.onSurface)
                            Text(
                                "${tel.eventLogs.size} events · 6-check health probe",
                                style = MaterialTheme.typography.labelSmall,
                                color = cs.onSurfaceVariant
                            )
                        }
                    }
                    Icon(
                        imageVector = if (showDiagnosticsTerminal) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                        contentDescription = null,
                        tint = cs.onSurfaceVariant
                    )
                }
            }
        }

        if (showDiagnosticsTerminal) {
        // 5. Dark Monospace Live Terminal & 6-Check Diagnostics
        item {
            Surface(
                shape = MaterialTheme.shapes.large,
                color = cs.surfaceContainerLowest,
                border = BorderStroke(1.dp, cs.outlineVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Terminal,
                                contentDescription = stringResource(R.string.cmd_section_terminal),
                                tint = cs.primary,
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                text = stringResource(R.string.cmd_section_terminal),
                                style = MaterialTheme.typography.titleSmall,
                                color = cs.onSurface
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                            IconButton(
                                onClick = {
                                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    cm.setPrimaryClip(
                                        ClipData.newPlainText("Silica Logs", filteredLogs.joinToString("\n"))
                                    )
                                },
                                modifier = Modifier.testTag("copy_logs_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.ContentCopy,
                                    contentDescription = stringResource(R.string.cmd_copy_logs),
                                    tint = cs.onSurfaceVariant,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            IconButton(
                                onClick = onClearLogs,
                                modifier = Modifier.testTag("clear_logs_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.DeleteOutline,
                                    contentDescription = stringResource(R.string.cmd_clear_logs),
                                    tint = cs.onSurfaceVariant,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            TerminalLogFilter.entries.forEach { filter ->
                                FilterChip(
                                    selected = uiState.logFilter == filter,
                                    onClick = { onSelectLogFilter(filter) },
                                    label = { Text(filter.label, style = MaterialTheme.typography.labelSmall) },
                                    modifier = Modifier.testTag("log_filter_${filter.name.lowercase()}")
                                )
                            }
                        }
                        OutlinedButton(
                            onClick = onRunDiagnostics,
                            border = BorderStroke(1.dp, cs.secondary.copy(alpha = 0.55f)),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("run_diagnostics_button")
                        ) {
                            Icon(Icons.Outlined.HealthAndSafety, contentDescription = null, modifier = Modifier.size(15.dp), tint = cs.secondary)
                            Spacer(Modifier.width(4.dp))
                            Text("Diagnose", style = MaterialTheme.typography.labelSmall, color = cs.secondary)
                        }
                    }

                    HorizontalDivider(color = cs.outlineVariant.copy(alpha = 0.6f))

                    val logScrollState = rememberScrollState()
                    LaunchedEffect(filteredLogs.size) {
                        if (filteredLogs.isNotEmpty()) {
                            logScrollState.animateScrollTo(logScrollState.maxValue)
                        }
                    }

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 120.dp, max = 240.dp)
                            .verticalScroll(logScrollState),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        if (filteredLogs.isEmpty()) {
                            Text(
                                text = stringResource(R.string.cmd_terminal_empty),
                                fontFamily = JetBrainsMonoFontFamily,
                                fontSize = 11.sp,
                                lineHeight = 16.sp,
                                color = cs.onSurfaceVariant
                            )
                        } else {
                            filteredLogs.forEach { line ->
                                val lineColor = when {
                                    line.contains("CRITICAL", ignoreCase = true) ||
                                        line.contains("Rejected", ignoreCase = true) ||
                                        line.contains("unreachable", ignoreCase = true) -> cs.error
                                    line.contains("AUTO-PAUSED", ignoreCase = true) ||
                                        line.contains("fallback", ignoreCase = true) -> cs.tertiary
                                    line.contains("OK", ignoreCase = true) ||
                                        line.contains("Auto-Detected", ignoreCase = true) ||
                                        line.contains("synced", ignoreCase = true) ||
                                        line.contains("Verified", ignoreCase = true) -> cs.primary
                                    else -> cs.onSurface.copy(alpha = 0.85f)
                                }
                                Text(
                                    text = line,
                                    fontFamily = JetBrainsMonoFontFamily,
                                    fontSize = 11.sp,
                                    lineHeight = 15.sp,
                                    color = lineColor
                                )
                            }
                        }
                    }
                }
            }
        }
        } // end if (showDiagnosticsTerminal)
    }
}

@Composable
private fun SectionSurfaceCard(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    accentColor: androidx.compose.ui.graphics.Color,
    trailing: @Composable (() -> Unit)? = null,
    content: @Composable () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    Surface(
        shape = MaterialTheme.shapes.large,
        color = cs.surfaceContainer,
        border = BorderStroke(1.dp, cs.outlineVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = title,
                        tint = accentColor,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall,
                        color = cs.onSurface
                    )
                }
                if (trailing != null) trailing()
            }
            content()
        }
    }
}

@Composable
private fun TelemetryTile(
    label: String,
    value: String,
    sub: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    accent: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier
) {
    val cs = MaterialTheme.colorScheme
    Surface(
        shape = MaterialTheme.shapes.small,
        color = cs.surfaceContainerLow,
        border = BorderStroke(1.dp, cs.outlineVariant),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Icon(icon, contentDescription = label, tint = accent, modifier = Modifier.size(13.dp))
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = cs.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Text(
                text = value,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = cs.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = sub,
                style = MaterialTheme.typography.labelSmall,
                color = accent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
