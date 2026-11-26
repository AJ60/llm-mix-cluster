package com.example.ui.silica

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.ShowChart
import androidx.compose.material.icons.outlined.Smartphone
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.MasterClusterSnapshot
import com.example.service.ComputeBackend
import com.example.service.DeviceTelemetryState

@Composable
fun SilicaLayerShardingCard(
    clusterSnapshot: MasterClusterSnapshot?,
    telemetry: DeviceTelemetryState,
    activeModelDisplayName: String,
    isStreamingChat: Boolean,
    liveStreamingTokPerSec: Float,
    masterReachable: Boolean,
    modifier: Modifier = Modifier
) {
    val cs = MaterialTheme.colorScheme

    // Determine nodes and layer splits
    val nodes = clusterSnapshot?.nodes.orEmpty()
    val masterNode = nodes.firstOrNull { it.isMaster }
    val modelToken = telemetry.deviceModel.trim().substringAfterLast(" ")
    val phoneNode = nodes.firstOrNull {
        !it.isMaster && (
            it.osType.contains("android", ignoreCase = true) ||
            it.id.contains("android", ignoreCase = true) ||
            (modelToken.length >= 3 && it.label.contains(modelToken, ignoreCase = true)) ||
            it.label.contains("Note", ignoreCase = true) ||
            it.label.contains("Phone", ignoreCase = true)
        )
    } ?: nodes.firstOrNull { !it.isMaster }

    val masterLayers = masterNode?.allocatedLayers ?: if (masterReachable) 25 else 0
    val masterRange = if (masterNode != null && masterNode.allocatedLayers > 0) {
        "L${masterNode.layerStart}–L${masterNode.layerEnd}"
    } else if (masterReachable) "L0–L24" else "Offline"

    val snapshotLayers = phoneNode?.allocatedLayers ?: 0
    val phoneLayers = if (snapshotLayers > 0) snapshotLayers else telemetry.activeLayersAssigned
    val phoneRange = if (phoneNode != null && snapshotLayers > 0) {
        "L${phoneNode.layerStart}–L${phoneNode.layerEnd}"
    } else if (phoneLayers > 0 && telemetry.assignedLayerStart >= 0 && telemetry.assignedLayerEnd >= telemetry.assignedLayerStart) {
        "L${telemetry.assignedLayerStart}–L${telemetry.assignedLayerEnd}"
    } else if (phoneLayers > 0) {
        val start = masterLayers
        "L$start–L${start + phoneLayers - 1}"
    } else {
        "Standby"
    }

    val totalLayers = (masterLayers + phoneLayers).coerceAtLeast(28)
    val masterRatio = (masterLayers.toFloat() / totalLayers.toFloat()).coerceIn(0.05f, 0.95f)
    val phoneRatio = if (phoneLayers > 0) (phoneLayers.toFloat() / totalLayers.toFloat()).coerceIn(0.05f, 0.95f) else 0f
    val masterSharePct = (masterRatio * 100).toInt()
    val phoneSharePct = (phoneRatio * 100).toInt()

    // Cached RAM vs Safe Cap (honest 0 MB when 0L assigned)
    val phoneCachedMb = phoneNode?.allocatedMb?.takeIf { it > 0L }
        ?: telemetry.assignedRamMb.takeIf { it > 0L }
        ?: if (phoneLayers > 0) phoneLayers * 180L else 0L
    val phoneSafeCapMb = telemetry.safeCapMb.coerceAtLeast(800L)
    val cacheRatio = if (phoneCachedMb > 0L) (phoneCachedMb.toFloat() / phoneSafeCapMb.toFloat()).coerceIn(0.02f, 1f) else 0f

    val pulseAlpha = if (isStreamingChat) {
        val pulse = rememberInfiniteTransition(label = "compute_pulse")
        val animated by pulse.animateFloat(
            initialValue = 0.35f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(650),
                repeatMode = RepeatMode.Reverse
            ),
            label = "pulse_alpha"
        )
        animated
    } else {
        1f
    }

    Surface(
        shape = MaterialTheme.shapes.large,
        color = cs.surfaceContainer,
        border = BorderStroke(1.dp, cs.outlineVariant),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Header: Model Name & Live Workload Status
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(cs.primary.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Layers,
                            contentDescription = null,
                            tint = cs.primary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Model Sharding & Layer Split",
                            style = MaterialTheme.typography.titleSmall,
                            color = cs.onSurface
                        )
                        Text(
                            text = "$activeModelDisplayName • $totalLayers Layers Total",
                            style = MaterialTheme.typography.labelSmall,
                            color = cs.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                // Compute Working Status Pill
                val isWorking = isStreamingChat
                val statusBg = if (isWorking) Color(0xFF0284C7).copy(alpha = 0.2f) else cs.primary.copy(alpha = 0.15f)
                val statusText = if (isWorking) Color(0xFF38BDF8) else cs.primary
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(statusBg)
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .alpha(if (isWorking) pulseAlpha else 1f)
                            .clip(CircleShape)
                            .background(statusText)
                    )
                    Text(
                        text = if (isWorking) "COMPUTING" else "STANDBY",
                        style = MaterialTheme.typography.labelSmall,
                        color = statusText,
                        fontWeight = FontWeight.Bold,
                        fontSize = 10.sp
                    )
                }
            }

            // 1. Multi-Segment Contiguous Layer Progress Bar with Node Labels
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(Color(0xFF0284C7)))
                        Text(
                            text = "Master: $masterRange (${masterLayers}L · $masterSharePct%)",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFF38BDF8),
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(Color(0xFF10B981)))
                        Text(
                            text = "Phone: $phoneRange (${phoneLayers}L · $phoneSharePct%)",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFF34D399),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1
                        )
                    }
                }

                // Dual-Segment Contiguous Progress Bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(12.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(cs.surfaceContainerHigh)
                ) {
                    Box(
                        modifier = Modifier
                            .weight(masterRatio.coerceAtLeast(0.05f))
                            .fillMaxHeight()
                            .background(Color(0xFF0284C7))
                    )
                    if (phoneRatio > 0f) {
                        Spacer(modifier = Modifier.width(2.dp).fillMaxHeight().background(cs.surface))
                        Box(
                            modifier = Modifier
                                .weight(phoneRatio)
                                .fillMaxHeight()
                                .background(Color(0xFF10B981))
                        )
                    }
                }

                // Mini layer badge pill strip
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Host: ${(masterNode?.allocatedMb ?: 0L)} MB",
                        style = MaterialTheme.typography.labelSmall,
                        color = cs.onSurfaceVariant,
                        fontSize = 10.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = "Worker: $phoneCachedMb MB ($phoneRange)",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFF34D399),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        textAlign = TextAlign.End
                    )
                }
            }

            // 2. Local RAM Cache Residency & Workload Stats
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(cs.surfaceContainerHigh.copy(alpha = 0.5f))
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "RAM Residency (${phoneLayers}L Active)",
                        style = MaterialTheme.typography.bodySmall,
                        color = cs.onSurfaceVariant,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = "$phoneCachedMb / $phoneSafeCapMb MB (${(cacheRatio * 100).toInt()}%)",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (cacheRatio < 0.7f) Color(0xFF34D399) else cs.tertiary,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1
                    )
                }
                LinearProgressIndicator(
                    progress = { cacheRatio },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(5.dp)
                        .clip(RoundedCornerShape(3.dp)),
                    color = Color(0xFF10B981),
                    trackColor = cs.surfaceContainerHighest
                )
            }

            // 3. Real-time Node Activity & Compute Workload Graph
            ComputeActivityGraph(
                isStreaming = isStreamingChat,
                tokPerSec = liveStreamingTokPerSec,
                phoneSharePct = phoneSharePct
            )

            // 4. Telemetry Tiles: Live Speed, Link Transport & Worker Workload
            val isUsbLink = telemetry.workerState.name.contains("USB", ignoreCase = true)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                LayerStatTile(
                    title = "SPEED",
                    value = if (liveStreamingTokPerSec > 0f) "${"%.1f".format(liveStreamingTokPerSec)} tok/s" else "Ready",
                    sub = when {
                        telemetry.activeBackend == ComputeBackend.VULKAN_FP16 -> "Vulkan 1.3 GPU"
                        telemetry.hasDotProd -> "ARMv8.2 DotProd"
                        else -> "ARM NEON"
                    },
                    icon = Icons.Outlined.Speed,
                    tint = Color(0xFF34D399),
                    modifier = Modifier.weight(1f)
                )
                LayerStatTile(
                    title = "TRANSPORT",
                    value = if (isUsbLink) "USB Tunnel" else "Wi-Fi LAN",
                    sub = if (masterReachable) "Synced" else "Local Only",
                    icon = Icons.Outlined.Hub,
                    tint = Color(0xFF38BDF8),
                    modifier = Modifier.weight(1f)
                )
                LayerStatTile(
                    title = "WORKLOAD",
                    value = "${phoneLayers}L Sharded",
                    sub = "$phoneCachedMb MB active",
                    icon = Icons.Outlined.Memory,
                    tint = cs.primary,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun ComputeActivityGraph(
    isStreaming: Boolean,
    tokPerSec: Float,
    phoneSharePct: Int
) {
    val cs = MaterialTheme.colorScheme
    val waveOffset = if (isStreaming) {
        val pulse = rememberInfiniteTransition(label = "graph_pulse")
        val animated by pulse.animateFloat(
            initialValue = 0.2f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(450),
                repeatMode = RepeatMode.Reverse
            ),
            label = "wave_offset"
        )
        animated
    } else {
        0.2f
    }

    Surface(
        shape = RoundedCornerShape(8.dp),
        color = cs.surfaceContainerHigh.copy(alpha = 0.4f),
        border = BorderStroke(1.dp, cs.outlineVariant.copy(alpha = 0.4f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.ShowChart,
                        contentDescription = null,
                        tint = if (isStreaming) Color(0xFF38BDF8) else cs.onSurfaceVariant,
                        modifier = Modifier.size(13.dp)
                    )
                    Text(
                        text = "Live Compute Activity & Workload",
                        style = MaterialTheme.typography.labelSmall,
                        color = cs.onSurfaceVariant,
                        fontSize = 10.sp
                    )
                }
                Text(
                    text = if (isStreaming) "${"%.1f".format(tokPerSec)} tok/s · Active Step" else "Standby · Ready for inference",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isStreaming) Color(0xFF38BDF8) else cs.onSurfaceVariant,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 10.sp
                )
            }

            // Real-time Activity Waveform / Equalizer bars
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(24.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom
            ) {
                val barCount = 18
                for (i in 0 until barCount) {
                    val baseHeight = if (isStreaming) {
                        val factor = ((i + 1) * 7 % 10) / 10f
                        (0.25f + factor * 0.75f * waveOffset).coerceIn(0.15f, 1f)
                    } else {
                        0.12f
                    }
                    val barColor = if (isStreaming) {
                        if (i >= barCount - 3) Color(0xFF10B981) else Color(0xFF0284C7)
                    } else {
                        cs.outlineVariant
                    }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 1.dp)
                            .height((24 * baseHeight).dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(barColor)
                    )
                }
            }
        }
    }
}

@Composable
private fun LayerStatTile(
    title: String,
    value: String,
    sub: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tint: Color,
    modifier: Modifier = Modifier
) {
    val cs = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = cs.surfaceContainerHigh.copy(alpha = 0.5f),
        border = BorderStroke(1.dp, cs.outlineVariant.copy(alpha = 0.4f)),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Icon(imageVector = icon, contentDescription = null, tint = tint, modifier = Modifier.size(12.dp))
                Text(text = title, style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant, fontSize = 9.sp)
            }
            Text(text = value, style = MaterialTheme.typography.labelMedium, color = cs.onSurface, fontWeight = FontWeight.Bold)
            Text(text = sub, style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant, fontSize = 9.sp, maxLines = 1)
        }
    }
}
