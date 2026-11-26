package com.example.ui.silica

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.QrCode2
import androidx.compose.material.icons.outlined.Radar
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Smartphone
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

@Composable
fun SilicaClusterScreen(
    uiState: SilicaUiState,
    onAutoDetect: () -> Unit,
    onOpenQrScan: () -> Unit,
    onOpenQrShare: () -> Unit,
    onOpenAddPeer: () -> Unit,
    onRefreshCluster: () -> Unit,
    onRemoveManualPeer: (String) -> Unit,
    onToggleTensorCache: (Boolean) -> Unit,
    onClearTensorCache: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    val tel = uiState.telemetry
    val cache = uiState.cacheStats

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag("cluster_screen_list"),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // 1. Star Cluster Topology Visualizer Card
        item {
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
                        Column {
                            Text(
                                text = stringResource(R.string.cluster_topology_title),
                                style = MaterialTheme.typography.titleMedium,
                                color = cs.onSurface
                            )
                            Text(
                                text = if (uiState.masterHealth.reachable) {
                                    "Live 1-Hop Star Mesh • ${uiState.activeNodeCount} Active"
                                } else {
                                    "Local Node Ready • Scan LAN or QR to join Master"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = if (uiState.masterHealth.reachable) cs.primary else cs.onSurfaceVariant
                            )
                        }
                        IconButton(
                            onClick = onRefreshCluster,
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("cluster_refresh_button")
                        ) {
                            if (uiState.isProbingMaster) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                    color = cs.secondary
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Outlined.Refresh,
                                    contentDescription = stringResource(R.string.cluster_refresh),
                                    tint = cs.secondary
                                )
                            }
                        }
                    }

                    // Dynamic Canvas Topology Graph
                    StarTopologyCanvas(
                        peers = uiState.peers,
                        masterReachable = uiState.masterHealth.reachable,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(172.dp)
                            .clip(MaterialTheme.shapes.medium)
                            .background(cs.surfaceContainerLowest)
                    )

                    // Pooled Cluster KPI Strip
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        ClusterMetricPill(
                            label = "NODES",
                            value = "${uiState.activeNodeCount}/${uiState.peers.size}",
                            accent = cs.primary,
                            modifier = Modifier.weight(1f)
                        )
                        ClusterMetricPill(
                            label = "SAFE POOL",
                            value = "${"%.1f".format(uiState.totalPoolRamGb)} GB",
                            accent = cs.secondary,
                            modifier = Modifier.weight(1f)
                        )
                        ClusterMetricPill(
                            label = "EST. SPEED",
                            value = if (uiState.aggregateTokPerSec > 0f) {
                                "${"%.1f".format(uiState.aggregateTokPerSec)} tok/s"
                            } else "--",
                            accent = cs.primary,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }

        // 2. Discovery & Pairing Action Bar
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onAutoDetect,
                    enabled = !tel.isScanningMaster,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = cs.primary,
                        contentColor = cs.onPrimary
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .minimumInteractiveComponentSize()
                        .testTag("cluster_auto_detect_button")
                ) {
                    if (tel.isScanningMaster) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(15.dp),
                            strokeWidth = 2.dp,
                            color = cs.onPrimary
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.cluster_scanning))
                    } else {
                        Icon(Icons.Outlined.Radar, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.cluster_auto_detect))
                    }
                }

                OutlinedButton(
                    onClick = onOpenQrScan,
                    border = BorderStroke(1.dp, cs.secondary.copy(alpha = 0.55f)),
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("cluster_qr_pair_button")
                ) {
                    Icon(Icons.Outlined.QrCode2, contentDescription = null, modifier = Modifier.size(16.dp), tint = cs.secondary)
                    Spacer(Modifier.width(5.dp))
                    Text(stringResource(R.string.cluster_qr_pair), color = cs.secondary)
                }

                OutlinedButton(
                    onClick = onOpenAddPeer,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("cluster_add_peer_button")
                ) {
                    Icon(Icons.Outlined.Add, contentDescription = stringResource(R.string.cluster_add_peer), modifier = Modifier.size(17.dp))
                }
            }
        }

        // 2b. Live Model Sharding & Layer Cache Breakdown
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

        // 3. Active & Paired Node Shard Cards
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.cluster_nodes_section),
                    style = MaterialTheme.typography.titleSmall,
                    color = cs.onSurface
                )
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = cs.secondaryContainer.copy(alpha = 0.6f),
                    onClick = onOpenQrShare,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("cluster_share_qr_button")
                ) {
                    Text(
                        text = "Share My QR",
                        style = MaterialTheme.typography.labelMedium,
                        color = cs.secondary,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                }
            }
        }

        items(uiState.peers, key = { it.id }) { peer ->
            ClusterPeerCard(
                peer = peer,
                totalPoolLayers = uiState.peers.sumOf { it.allocatedLayers }.coerceAtLeast(28),
                onDelete = if (peer.isManualSavedPeer) {
                    { onRemoveManualPeer(peer.address) }
                } else null
            )
        }

        if (uiState.peers.size <= 1) {
            item {
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = cs.surfaceContainerLow,
                    border = BorderStroke(1.dp, cs.outlineVariant),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Hub,
                            contentDescription = null,
                            tint = cs.secondary,
                            modifier = Modifier.size(24.dp)
                        )
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text = stringResource(R.string.cluster_empty_peers),
                                style = MaterialTheme.typography.bodySmall,
                                color = cs.onSurfaceVariant
                            )
                            OutlinedButton(
                                onClick = onOpenQrShare,
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                modifier = Modifier
                                    .minimumInteractiveComponentSize()
                                    .testTag("empty_show_qr_button")
                            ) {
                                Text("Show Node QR Code", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }
        }

        // 4. First-Run Tensor Cache Card
        item {
            Surface(
                shape = MaterialTheme.shapes.large,
                color = cs.surfaceContainer,
                border = BorderStroke(1.dp, cs.outlineVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
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
                            Icon(
                                imageVector = Icons.Outlined.Storage,
                                contentDescription = stringResource(R.string.cluster_cache_title),
                                tint = cs.primary,
                                modifier = Modifier.size(20.dp)
                            )
                            Column {
                                Text(
                                    text = stringResource(R.string.cluster_cache_title),
                                    style = MaterialTheme.typography.titleSmall,
                                    color = cs.onSurface
                                )
                                Text(
                                    text = cache.statusLabel,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (cache.isEnabled) cs.primary else cs.onSurfaceVariant
                                )
                            }
                        }
                        Switch(
                            checked = uiState.config.tensorCacheEnabled,
                            onCheckedChange = onToggleTensorCache,
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = cs.onPrimary,
                                checkedTrackColor = cs.primary
                            ),
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("tensor_cache_switch")
                        )
                    }

                    Text(
                        text = stringResource(R.string.cluster_cache_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = cs.onSurfaceVariant
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        ClusterMetricPill(
                            label = "ON DISK",
                            value = "${"%.1f".format(cache.cachedMb)} MB",
                            accent = cs.secondary,
                            modifier = Modifier.weight(1f)
                        )
                        ClusterMetricPill(
                            label = "SHARDS",
                            value = "${cache.layerCount}",
                            accent = cs.primary,
                            modifier = Modifier.weight(1f)
                        )
                        ClusterMetricPill(
                            label = "SAVED",
                            value = "${"%.1f".format(cache.bandwidthSavedMb)} MB",
                            accent = cs.primary,
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        OutlinedButton(
                            onClick = onClearTensorCache,
                            border = BorderStroke(1.dp, cs.outline),
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("clear_tensor_cache_button")
                        ) {
                            Icon(Icons.Outlined.DeleteOutline, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(5.dp))
                            Text(stringResource(R.string.cluster_cache_clear))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StarTopologyCanvas(
    peers: List<ClusterPeerUi>,
    masterReachable: Boolean,
    modifier: Modifier = Modifier
) {
    val cs = MaterialTheme.colorScheme
    val hasActiveRemotePeer = peers.any { it.isOnline && !it.isMaster }
    val phase = if (hasActiveRemotePeer) {
        val transition = rememberInfiniteTransition(label = "topology_pulse")
        val animated by transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(2400, easing = LinearEasing), RepeatMode.Restart),
            label = "packet_phase"
        )
        animated
    } else {
        0f
    }

    Canvas(modifier = modifier) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val orbitRadius = min(size.width, size.height) * 0.34f

        // Subtle obsidian radial radar rings
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(cs.secondary.copy(alpha = 0.14f), cs.surfaceContainerLowest.copy(alpha = 0f)),
                center = center,
                radius = orbitRadius * 1.45f
            ),
            radius = orbitRadius * 1.45f,
            center = center
        )
        drawCircle(
            color = cs.outlineVariant.copy(alpha = 0.55f),
            radius = orbitRadius,
            center = center,
            style = Stroke(
                width = 1.5f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f))
            )
        )

        val hubColor = if (masterReachable || peers.any { it.isOnline }) cs.primary else cs.onSurfaceVariant
        val satellites = peers.ifEmpty { emptyList() }
        val count = satellites.size.coerceAtLeast(1)

        satellites.forEachIndexed { index, peer ->
            val angle = (2.0 * PI * index / count) - (PI / 2.0)
            val nodePos = Offset(
                x = center.x + (orbitRadius * cos(angle)).toFloat(),
                y = center.y + (orbitRadius * sin(angle)).toFloat()
            )
            val linkColor = when {
                peer.isOnline && peer.isMaster -> cs.primary
                peer.isOnline -> cs.secondary
                else -> cs.outline
            }

            // Connection spoke
            drawLine(
                color = linkColor.copy(alpha = if (peer.isOnline) 0.7f else 0.35f),
                start = center,
                end = nodePos,
                strokeWidth = if (peer.isOnline) 3f else 1.5f
            )

            // Animated data packet traveling along active spoke
            if (peer.isOnline) {
                val t = (phase + index * 0.23f) % 1f
                val packet = Offset(
                    x = center.x + (nodePos.x - center.x) * t,
                    y = center.y + (nodePos.y - center.y) * t
                )
                drawCircle(color = cs.secondary, radius = 4.5f, center = packet)
            }

            // Outer node halo + core
            drawCircle(color = linkColor.copy(alpha = 0.22f), radius = 18f, center = nodePos)
            drawCircle(color = cs.surfaceContainer, radius = 12f, center = nodePos)
            drawCircle(color = linkColor, radius = 12f, center = nodePos, style = Stroke(width = 2.5f))
            drawCircle(color = linkColor, radius = 4.5f, center = nodePos)
        }

        // Central Star Hub
        drawCircle(color = hubColor.copy(alpha = 0.2f), radius = 28f, center = center)
        drawCircle(color = cs.surfaceContainer, radius = 19f, center = center)
        drawCircle(color = hubColor, radius = 19f, center = center, style = Stroke(width = 3f))
        drawCircle(color = hubColor, radius = 7f, center = center)
    }
}

@Composable
private fun ClusterPeerCard(
    peer: ClusterPeerUi,
    totalPoolLayers: Int,
    onDelete: (() -> Unit)?
) {
    val cs = MaterialTheme.colorScheme
    val accent = when {
        !peer.isOnline -> cs.onSurfaceVariant
        peer.isMaster -> cs.primary
        else -> cs.secondary
    }
    val shardFraction = (peer.allocatedLayers.toFloat() / totalPoolLayers.coerceAtLeast(1).toFloat())
        .coerceIn(0f, 1f)

    Surface(
        shape = MaterialTheme.shapes.medium,
        color = cs.surfaceContainer,
        border = BorderStroke(1.dp, if (peer.isOnline) accent.copy(alpha = 0.4f) else cs.outlineVariant),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("peer_card_${peer.id}")
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
                            .size(36.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(accent.copy(alpha = 0.14f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (peer.isLocalDevice) Icons.Outlined.Smartphone else Icons.Outlined.Computer,
                            contentDescription = peer.label,
                            tint = accent,
                            modifier = Modifier.size(19.dp)
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = peer.label,
                            style = MaterialTheme.typography.titleSmall,
                            color = cs.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "${peer.roleLabel} • ${peer.address}",
                            style = MaterialTheme.typography.labelSmall,
                            color = cs.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = accent.copy(alpha = 0.14f)
                    ) {
                        Text(
                            text = if (peer.isOnline) {
                                if (peer.latencyMs > 0L) "${peer.latencyMs}ms" else "ONLINE"
                            } else "OFFLINE",
                            style = MaterialTheme.typography.labelSmall,
                            color = accent,
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                        )
                    }
                    if (onDelete != null) {
                        IconButton(
                            onClick = onDelete,
                            modifier = Modifier.testTag("delete_peer_${peer.address}")
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.DeleteOutline,
                                contentDescription = stringResource(R.string.btn_delete),
                                tint = cs.onSurfaceVariant,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "${peer.backendDriver} • ${peer.layerRangeLabel}",
                    style = MaterialTheme.typography.labelSmall,
                    color = cs.onSurfaceVariant
                )
                Text(
                    text = "${peer.allocatedMb} MB" + if (peer.estimatedTokPerSec > 0f) {
                        " • ~${"%.1f".format(peer.estimatedTokPerSec)} tok/s"
                    } else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = accent
                )
            }

            LinearProgressIndicator(
                progress = { if (peer.isOnline) shardFraction.coerceAtLeast(0.08f) else 0f },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(5.dp)
                    .clip(RoundedCornerShape(3.dp)),
                color = accent,
                trackColor = cs.surfaceContainerHigh
            )
        }
    }
}

@Composable
private fun ClusterMetricPill(
    label: String,
    value: String,
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
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Text(text = label, style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
            Spacer(Modifier.height(2.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = accent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
