package com.example.ui.silica

import com.example.data.ChatMessageEntity
import com.example.data.ChatSessionEntity
import com.example.data.MasterClusterSnapshot
import com.example.data.MasterHealthResult
import com.example.data.SilicaEngineConfig
import com.example.service.DeviceTelemetryState
import com.example.service.TensorCacheStats

enum class SilicaTab(val route: String) {
    COMMAND("silica_command"),
    CLUSTER("silica_cluster"),
    CHAT("silica_chat")
}

enum class TerminalLogFilter(val label: String) {
    ALL("All"),
    NETWORK("Net"),
    GUARD("Guard")
}

data class GgufModelPreset(
    val id: String,
    val displayName: String,
    val quant: String,
    val paramsLabel: String,
    val minRamGb: Float,
    val totalLayers: Int
)

val SILICA_MODEL_PRESETS = listOf(
    GgufModelPreset(
        id = "small-1.5b",
        displayName = "Qwen2.5-1.5B-Instruct",
        quant = "Q4_K_M",
        paramsLabel = "1.5B",
        minRamGb = 1.4f,
        totalLayers = 28
    ),
    GgufModelPreset(
        id = "qwen2.5-3b-q4",
        displayName = "Qwen2.5-3B-Instruct",
        quant = "Q4_K_M",
        paramsLabel = "3B",
        minRamGb = 2.4f,
        totalLayers = 36
    ),
    GgufModelPreset(
        id = "llama-3.2-3b-q4",
        displayName = "Llama-3.2-3B-Instruct",
        quant = "Q4_K_M",
        paramsLabel = "3.2B",
        minRamGb = 2.5f,
        totalLayers = 28
    ),
    GgufModelPreset(
        id = "phi-3.5-mini-q4",
        displayName = "Phi-3.5-Mini-Instruct",
        quant = "Q4_K_M",
        paramsLabel = "3.8B",
        minRamGb = 2.9f,
        totalLayers = 32
    ),
    GgufModelPreset(
        id = "qwen2.5-7b-q4",
        displayName = "Qwen2.5-7B-Instruct",
        quant = "Q4_K_M",
        paramsLabel = "7B",
        minRamGb = 5.2f,
        totalLayers = 28
    ),
    GgufModelPreset(
        id = "llama-3.1-8b-q4",
        displayName = "Llama-3.1-8B-Instruct",
        quant = "Q4_K_M",
        paramsLabel = "8B",
        minRamGb = 5.8f,
        totalLayers = 32
    )
)

data class ClusterPeerUi(
    val id: String,
    val label: String,
    val address: String,
    val roleLabel: String, // "MASTER HUB" | "LOCAL NODE" | "REMOTE WORKER"
    val backendDriver: String,
    val layerRangeLabel: String,
    val allocatedLayers: Int,
    val allocatedMb: Long,
    val estimatedTokPerSec: Float,
    val latencyMs: Long,
    val isOnline: Boolean,
    val isLocalDevice: Boolean = false,
    val isMaster: Boolean = false,
    val isManualSavedPeer: Boolean = false
)

data class SilicaUiState(
    val activeTab: SilicaTab = SilicaTab.COMMAND,
    val telemetry: DeviceTelemetryState = DeviceTelemetryState(),
    val config: SilicaEngineConfig = SilicaEngineConfig(),
    val cacheStats: TensorCacheStats = TensorCacheStats(),
    val masterHealth: MasterHealthResult = MasterHealthResult(reachable = false, detail = "Not probed"),
    val isProbingMaster: Boolean = false,
    val liveModels: List<String> = emptyList(),
    val clusterSnapshot: MasterClusterSnapshot? = null,
    val peers: List<ClusterPeerUi> = emptyList(),
    val sessions: List<ChatSessionEntity> = emptyList(),
    val activeSessionId: Long = -1L,
    val messages: List<ChatMessageEntity> = emptyList(),
    val isStreamingChat: Boolean = false,
    val liveStreamingTokPerSec: Float = 0f,
    val logFilter: TerminalLogFilter = TerminalLogFilter.ALL,
    val showQrDialog: Boolean = false,
    val qrInitialTab: Int = 0,
    val showModelEndpointSheet: Boolean = false,
    val showAddPeerDialog: Boolean = false,
    val showDiagnosticsDialog: Boolean = false,
    val showSessionsDialog: Boolean = false,
    val statusBannerMessage: String? = null
) {
    val activeModelDisplayName: String
        get() {
            val masterModel = clusterSnapshot?.activeModelName?.trim()
            if (!masterModel.isNullOrBlank()) {
                return masterModel
            }
            val healthModel = masterHealth.activeModel?.trim()
            if (!healthModel.isNullOrBlank()) {
                return healthModel
            }
            if (config.localGgufName.isNotBlank() && config.selectedModelId == "local-gguf") {
                return config.localGgufName
            }
            return SILICA_MODEL_PRESETS.firstOrNull { it.id == config.selectedModelId }?.displayName
                ?: config.selectedModelId
        }

    val totalPoolRamGb: Float
        get() {
            val fromMaster = clusterSnapshot?.totalSafePoolMb ?: 0L
            if (fromMaster > 0L) return fromMaster / 1024f
            val localMb = telemetry.safeCapMb
            val peerMb = peers.filter { !it.isLocalDevice && it.isOnline }.sumOf { it.allocatedMb }
            return (localMb + peerMb) / 1024f
        }

    val aggregateTokPerSec: Float
        get() {
            val masterTps = clusterSnapshot?.estimatedClusterTokPerSec ?: 0f
            if (masterTps > 0f) return masterTps
            return peers.filter { it.isOnline }.sumOf { it.estimatedTokPerSec.toDouble() }.toFloat()
        }

    val activeNodeCount: Int
        get() = peers.count { it.isOnline }
}
