package com.example.ui.silica

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.ChatCompletionMessage
import com.example.data.ChatMessageEntity
import com.example.data.MasterClusterSnapshot
import com.example.data.MasterHealthResult
import com.example.data.SavedPeerEntity
import com.example.data.SilicaEngineConfig
import com.example.data.SilicaMasterApiClient
import com.example.data.SilicaRepository
import com.example.service.AutoFixAction
import com.example.service.DeviceTelemetryState
import com.example.service.RpcService
import com.example.service.TensorCacheManager
import com.example.service.WorkerRunState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalCoroutinesApi::class)
class SilicaViewModel(
    private val app: Application,
    private val repository: SilicaRepository
) : AndroidViewModel(app) {

    private val activeTab = MutableStateFlow(SilicaTab.COMMAND)
    private val logFilter = MutableStateFlow(TerminalLogFilter.ALL)
    private val masterHealth = MutableStateFlow(MasterHealthResult(reachable = false, detail = "Not probed"))
    private val isProbingMaster = MutableStateFlow(false)
    private val liveModels = MutableStateFlow<List<String>>(emptyList())
    private val clusterSnapshot = MutableStateFlow<MasterClusterSnapshot?>(null)
    private val activeSessionId = MutableStateFlow(-1L)
    private val isStreamingChat = MutableStateFlow(false)
    private val liveStreamingTokPerSec = MutableStateFlow(0f)
    private val cacheRefreshNonce = MutableStateFlow(0)
    private val statusBanner = MutableStateFlow<String?>(null)

    private val showQrDialog = MutableStateFlow(false)
    private val qrInitialTab = MutableStateFlow(0)
    private val showModelEndpointSheet = MutableStateFlow(false)
    private val showAddPeerDialog = MutableStateFlow(false)
    private val showDiagnosticsDialog = MutableStateFlow(false)
    private val showSessionsDialog = MutableStateFlow(false)

    private var activeStreamJob: Job? = null
    private var lastSyncedPairedAddr: String = ""

    private val activeMessagesFlow = activeSessionId.flatMapLatest { id ->
        if (id > 0L) repository.observeMessages(id) else flowOf(emptyList())
    }

    private data class CoreBundle(
        val telemetry: DeviceTelemetryState,
        val config: SilicaEngineConfig,
        val savedPeers: List<SavedPeerEntity>,
        val masterHealth: MasterHealthResult,
        val clusterSnapshot: MasterClusterSnapshot?
    )

    private data class ChatBundle(
        val sessions: List<com.example.data.ChatSessionEntity>,
        val sessionId: Long,
        val messages: List<ChatMessageEntity>,
        val isStreaming: Boolean,
        val liveTps: Float
    )

    private data class ModalBundle(
        val showQr: Boolean,
        val qrTab: Int,
        val showModelSheet: Boolean,
        val showAddPeer: Boolean,
        val showDiag: Boolean,
        val showSessions: Boolean
    )

    private val coreBundleFlow = combine(
        RpcService.telemetry,
        repository.engineConfig,
        repository.savedPeers,
        masterHealth,
        clusterSnapshot
    ) { telemetry, config, savedPeers, health, snapshot ->
        CoreBundle(telemetry, config, savedPeers, health, snapshot)
    }

    private val chatBundleFlow = combine(
        repository.sessions,
        activeSessionId,
        activeMessagesFlow,
        isStreamingChat,
        liveStreamingTokPerSec
    ) { sessions, sessionId, messages, streaming, liveTps ->
        ChatBundle(sessions, sessionId, messages, streaming, liveTps)
    }

    private val modalBundleFlow = combine(
        combine(showQrDialog, qrInitialTab) { showQr, qrTab -> showQr to qrTab },
        showModelEndpointSheet,
        showAddPeerDialog,
        showDiagnosticsDialog,
        showSessionsDialog
    ) { qrPair, showModelSheet, showAddPeer, showDiag, showSessions ->
        ModalBundle(
            showQr = qrPair.first,
            qrTab = qrPair.second,
            showModelSheet = showModelSheet,
            showAddPeer = showAddPeer,
            showDiag = showDiag,
            showSessions = showSessions
        )
    }

    val uiState: StateFlow<SilicaUiState> = combine(
        coreBundleFlow,
        chatBundleFlow,
        modalBundleFlow,
        combine(activeTab, logFilter, isProbingMaster, liveModels, combine(cacheRefreshNonce, statusBanner) { n, b -> n to b }) { tab, filter, probing, models, extra ->
            UiMisc(tab, filter, probing, models, extra.first, extra.second)
        }
    ) { core, chat, modals, misc ->
        val cacheStats = TensorCacheManager.getStats(app, enabled = core.config.tensorCacheEnabled)
        val mergedPeers = buildMergedPeerList(
            telemetry = core.telemetry,
            config = core.config,
            savedPeers = core.savedPeers,
            snapshot = core.clusterSnapshot,
            masterHealth = core.masterHealth
        )
        SilicaUiState(
            activeTab = misc.tab,
            telemetry = core.telemetry,
            config = core.config,
            cacheStats = cacheStats,
            masterHealth = core.masterHealth,
            isProbingMaster = misc.probing,
            liveModels = misc.models,
            clusterSnapshot = core.clusterSnapshot,
            peers = mergedPeers,
            sessions = chat.sessions,
            activeSessionId = chat.sessionId,
            messages = chat.messages,
            isStreamingChat = chat.isStreaming,
            liveStreamingTokPerSec = chat.liveTps,
            logFilter = misc.filter,
            showQrDialog = modals.showQr,
            qrInitialTab = modals.qrTab,
            showModelEndpointSheet = modals.showModelSheet,
            showAddPeerDialog = modals.showAddPeer,
            showDiagnosticsDialog = modals.showDiag,
            showSessionsDialog = modals.showSessions,
            statusBannerMessage = misc.banner
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000L),
        initialValue = SilicaUiState(telemetry = RpcService.telemetry.value)
    )

    private data class UiMisc(
        val tab: SilicaTab,
        val filter: TerminalLogFilter,
        val probing: Boolean,
        val models: List<String>,
        val cacheNonce: Int,
        val banner: String?
    )

    init {
        // Initial non-root hardware snapshot + auto-calibration on IO
        viewModelScope.launch(Dispatchers.IO) {
            RpcService.refreshStaticHardwareSnapshot(app)
            if (!RpcService.telemetry.value.calibration.isCalibrated) {
                RpcService.runAutoCalibration(app)
            }
            cacheRefreshNonce.update { it + 1 }
        }

        // Ensure at least one active chat session exists
        viewModelScope.launch {
            repository.sessions.collect { list ->
                if (activeSessionId.value <= 0L) {
                    if (list.isNotEmpty()) {
                        activeSessionId.value = list.first().id
                    } else {
                        val id = repository.createSession(
                            title = "Cluster Chat",
                            modelId = uiState.value.config.selectedModelId
                        )
                        activeSessionId.value = id
                    }
                } else if (list.none { it.id == activeSessionId.value }) {
                    activeSessionId.value = list.firstOrNull()?.id ?: -1L
                }
            }
        }

        // React when UDP Auto-Detect or QR Pairing discovers a real LAN Master address
        viewModelScope.launch {
            RpcService.telemetry.collect { tel ->
                val rawAddr = tel.pairedMasterAddress.substringBefore(" ").trim()
                val looksLikeHostPort = rawAddr.contains(":") &&
                    !rawAddr.startsWith("Not", ignoreCase = true) &&
                    !rawAddr.startsWith("USB", ignoreCase = true)
                if (looksLikeHostPort && rawAddr != lastSyncedPairedAddr) {
                    lastSyncedPairedAddr = rawAddr
                    val url = SilicaMasterApiClient.normalizeBaseUrl("http://$rawAddr")
                    repository.updateEngineConfig { cfg ->
                        cfg.copy(masterBaseUrl = url, clusterSecret = tel.clusterSecret)
                    }
                    syncWithMaster(url, tel.clusterSecret)
                }
            }
        }

        // Initial Master probe + periodic 6s live shard/RAM residency sync
        viewModelScope.launch {
            delay(450L)
            while (true) {
                val cfg = uiState.value.config
                val tel = RpcService.telemetry.value
                val workerActive = tel.workerState == WorkerRunState.RUNNING_USB ||
                    tel.workerState == WorkerRunState.RUNNING_WIFI
                if (workerActive || masterHealth.value.reachable || clusterSnapshot.value == null) {
                    syncWithMaster(cfg.masterBaseUrl, cfg.clusterSecret)
                }
                delay(6000L)
            }
        }

        // Synchronize persisted thread override & safe RAM preferences into RpcService on startup
        viewModelScope.launch {
            repository.engineConfig.collect { cfg ->
                RpcService.applyEngineOverrides(
                    threads = cfg.threadCountOverride,
                    safeRamPercent = cfg.safeRamPercent,
                    restartWorkerIfRunning = false
                )
            }
        }
    }

    fun selectTab(tab: SilicaTab) {
        activeTab.value = tab
    }

    fun setLogFilter(filter: TerminalLogFilter) {
        logFilter.value = filter
    }

    fun dismissBanner() {
        statusBanner.value = null
    }

    fun setDarkTheme(dark: Boolean) {
        viewModelScope.launch {
            repository.updateEngineConfig { it.copy(darkTheme = dark) }
        }
    }

    fun dismissOnboarding() {
        viewModelScope.launch {
            repository.updateEngineConfig { it.copy(onboardingCompleted = true) }
        }
    }

    fun setWebToolsEnabled(enabled: Boolean) {
        viewModelScope.launch {
            repository.updateEngineConfig { it.copy(webToolsEnabled = enabled) }
        }
    }

    fun openQrDialog(initialTab: Int = 0) {
        qrInitialTab.value = initialTab
        showQrDialog.value = true
    }

    fun closeQrDialog() {
        showQrDialog.value = false
    }

    fun openModelEndpointSheet() {
        showModelEndpointSheet.value = true
    }

    fun closeModelEndpointSheet() {
        showModelEndpointSheet.value = false
    }

    fun openAddPeerDialog() {
        showAddPeerDialog.value = true
    }

    fun closeAddPeerDialog() {
        showAddPeerDialog.value = false
    }

    fun closeDiagnosticsDialog() {
        showDiagnosticsDialog.value = false
    }

    fun openSessionsDialog() {
        showSessionsDialog.value = true
    }

    fun closeSessionsDialog() {
        showSessionsDialog.value = false
    }

    fun toggleNodeEngine(turnOn: Boolean) {
        val cfg = uiState.value.config
        if (turnOn) {
            RpcService.applyEngineOverrides(cfg.threadCountOverride, cfg.safeRamPercent)
            val intent = Intent(app, RpcService::class.java).apply {
                action = RpcService.ACTION_START_WORKER
                putExtra(RpcService.EXTRA_USB_ONLY, cfg.usbBridgeOnly)
                putExtra(RpcService.EXTRA_CLUSTER_SECRET, cfg.clusterSecret)
            }
            ContextCompat.startForegroundService(app, intent)
            if (cfg.tensorCacheEnabled) {
                viewModelScope.launch(Dispatchers.IO) {
                    val layers = RpcService.telemetry.value.activeLayersAssigned.coerceAtLeast(4)
                    TensorCacheManager.writeShardIndexEntry(
                        context = app,
                        modelId = cfg.selectedModelId,
                        layerStart = 0,
                        layerEnd = (layers - 1).coerceAtLeast(0),
                        safeCapMb = RpcService.telemetry.value.safeCapMb
                    )
                    cacheRefreshNonce.update { it + 1 }
                }
            }
        } else {
            val intent = Intent(app, RpcService::class.java).apply {
                action = RpcService.ACTION_STOP_FREE_RAM
            }
            app.startService(intent)
        }
    }

    fun setNodeRole(role: String) {
        viewModelScope.launch {
            repository.updateEngineConfig { it.copy(nodeRole = role) }
            RpcService.logEngineEvent("Node role switched to ${if (role == "MASTER_HUB") "Master Bridge Hub" else "Worker Compute Node"}")
        }
    }

    fun selectModel(modelId: String) {
        viewModelScope.launch {
            repository.updateEngineConfig { it.copy(selectedModelId = modelId) }
            RpcService.logEngineEvent("Active inference model set to $modelId")
            if (uiState.value.config.tensorCacheEnabled) {
                withContext(Dispatchers.IO) {
                    val layers = RpcService.telemetry.value.activeLayersAssigned.coerceAtLeast(4)
                    TensorCacheManager.writeShardIndexEntry(
                        context = app,
                        modelId = modelId,
                        layerStart = 0,
                        layerEnd = (layers - 1).coerceAtLeast(0),
                        safeCapMb = RpcService.telemetry.value.safeCapMb
                    )
                }
                cacheRefreshNonce.update { it + 1 }
            }
            // Proactively notify Master and resync cluster state
            withContext(Dispatchers.IO) {
                SilicaMasterApiClient.activateModelOnMaster(
                    baseUrl = uiState.value.config.masterBaseUrl,
                    modelId = modelId,
                    secret = uiState.value.config.clusterSecret
                )
            }
            syncWithMaster()
        }
    }

    fun attachLocalGgufUri(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                app.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            val displayName = resolveDisplayName(uri) ?: "local-model.gguf"
            repository.updateEngineConfig {
                it.copy(
                    selectedModelId = "local-gguf",
                    localGgufUri = uri.toString(),
                    localGgufName = displayName
                )
            }
            RpcService.logEngineEvent("Mounted local GGUF via SAF: $displayName")
            statusBanner.value = "Loaded GGUF file: $displayName"
        }
    }

    private fun resolveDisplayName(uri: Uri): String? {
        return runCatching {
            app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                }
        }.getOrNull()
    }

    fun setThreadCount(threads: Int) {
        viewModelScope.launch {
            val pct = uiState.value.config.safeRamPercent
            repository.updateEngineConfig { it.copy(threadCountOverride = threads) }
            RpcService.applyEngineOverrides(threads, pct, restartWorkerIfRunning = true)
            RpcService.logEngineEvent("CPU thread budget updated to $threads threads")
        }
    }

    fun setSafeRamPercent(percent: Int) {
        val clamped = percent.coerceIn(25, 70)
        viewModelScope.launch {
            val threads = uiState.value.config.threadCountOverride
            repository.updateEngineConfig { it.copy(safeRamPercent = clamped) }
            RpcService.applyEngineOverrides(threads, clamped, restartWorkerIfRunning = true)
            RpcService.logEngineEvent("LMKD RAM guard set to $clamped% physical RAM ceiling")
        }
    }

    fun setContextLength(ctx: Int) {
        viewModelScope.launch {
            repository.updateEngineConfig { it.copy(contextLength = ctx) }
            RpcService.logEngineEvent("Context window configured to $ctx tokens")
        }
    }

    fun setKvCacheQuant(quant: String) {
        viewModelScope.launch {
            repository.updateEngineConfig { it.copy(kvCacheQuant = quant) }
            RpcService.logEngineEvent("KV cache quantization set to $quant")
        }
    }

    fun setBridgeMode(usbOnly: Boolean) {
        val secret = uiState.value.config.clusterSecret
        viewModelScope.launch {
            repository.updateEngineConfig { it.copy(usbBridgeOnly = usbOnly) }
        }
        val intent = Intent(app, RpcService::class.java).apply {
            action = RpcService.ACTION_TOGGLE_BIND_MODE
            putExtra(RpcService.EXTRA_USB_ONLY, usbOnly)
            putExtra(RpcService.EXTRA_CLUSTER_SECRET, secret)
        }
        app.startService(intent)
    }

    fun saveMasterEndpointSettings(
        baseUrl: String,
        secret: String,
        temperature: Float,
        systemPrompt: String
    ) {
        val normalized = SilicaMasterApiClient.normalizeBaseUrl(baseUrl)
        viewModelScope.launch {
            repository.updateEngineConfig {
                it.copy(
                    masterBaseUrl = normalized,
                    clusterSecret = secret.ifBlank { it.clusterSecret },
                    temperature = temperature.coerceIn(0f, 1.5f),
                    systemPrompt = systemPrompt.trim()
                )
            }
            RpcService.logEngineEvent("Master endpoint configured -> $normalized")
            syncWithMaster(normalized, secret)
        }
    }

    fun runAutoTune() {
        viewModelScope.launch(Dispatchers.IO) {
            val profile = RpcService.runAutoCalibration(app)
            repository.updateEngineConfig {
                it.copy(threadCountOverride = profile.recommendedThreads)
            }
            statusBanner.value = "Auto-tuned: ${profile.tierLabel} (${profile.recommendedThreads} threads, ${profile.recommendedSafeRamMb} MB cap)"
        }
    }

    fun autoDetectCluster() {
        val intent = Intent(app, RpcService::class.java).apply {
            action = RpcService.ACTION_AUTO_DETECT_MASTER
        }
        ContextCompat.startForegroundService(app, intent)
        viewModelScope.launch {
            delay(1300L)
            val cfg = uiState.value.config
            syncWithMaster(cfg.masterBaseUrl, cfg.clusterSecret)
        }
    }

    fun pairWithQrPayload(rawQr: String) {
        val parsed = RpcService.parseQrPairingPayload(rawQr)
        val intent = Intent(app, RpcService::class.java).apply {
            action = RpcService.ACTION_PAIR_QR_URI
            putExtra(RpcService.EXTRA_QR_URI, rawQr)
        }
        ContextCompat.startForegroundService(app, intent)
        if (parsed != null) {
            val (masterAddr, secret, isUsb) = parsed
            val url = SilicaMasterApiClient.normalizeBaseUrl("http://$masterAddr")
            viewModelScope.launch {
                repository.updateEngineConfig {
                    it.copy(
                        masterBaseUrl = url,
                        clusterSecret = secret,
                        usbBridgeOnly = isUsb
                    )
                }
                delay(400L)
                syncWithMaster(url, secret)
            }
        }
        showQrDialog.value = false
    }

    fun syncWithMaster(
        baseUrl: String = uiState.value.config.masterBaseUrl,
        secret: String = uiState.value.config.clusterSecret
    ) {
        viewModelScope.launch {
            isProbingMaster.value = true
            val wasReachable = masterHealth.value.reachable
            val normalized = SilicaMasterApiClient.normalizeBaseUrl(baseUrl)
            val health = SilicaMasterApiClient.probeHealth(normalized, secret)
            masterHealth.value = health
            if (health.reachable) {
                val models = SilicaMasterApiClient.fetchModels(normalized, secret)
                if (models.isNotEmpty()) {
                    liveModels.value = models
                }
                val snap = SilicaMasterApiClient.fetchClusterState(normalized, secret)
                clusterSnapshot.value = snap
                if (snap != null && snap.nodes.isNotEmpty()) {
                    val tel = RpcService.telemetry.value
                    val modelToken = tel.deviceModel.trim().substringAfterLast(" ")
                    val phoneNode = snap.nodes.firstOrNull { remote ->
                        !remote.isMaster && (
                            remote.osType.contains("android", ignoreCase = true) ||
                            remote.id.contains("android", ignoreCase = true) ||
                            (modelToken.length >= 3 && remote.label.contains(modelToken, ignoreCase = true))
                        )
                    } ?: snap.nodes.firstOrNull { !it.isMaster }

                    if (phoneNode != null) {
                        RpcService.syncAssignedLayersFromMaster(
                            layers = phoneNode.allocatedLayers,
                            layerStart = phoneNode.layerStart,
                            layerEnd = phoneNode.layerEnd,
                            allocatedMb = phoneNode.allocatedMb
                        )
                    }
                    if (!wasReachable) {
                        val summary = snap.nodes.joinToString(" + ") { n ->
                            "${n.label} (L${n.layerStart}-L${n.layerEnd}, ${n.allocatedLayers}L, ${n.allocatedMb}MB)"
                        }
                        RpcService.logEngineEvent("Master synced ($normalized • ${health.latencyMs}ms)")
                        RpcService.logEngineEvent("[SHARD] Model ${snap.activeModelName}: $summary")
                    }
                } else if (!wasReachable) {
                    RpcService.logEngineEvent(
                        "Master synced ($normalized • ${health.latencyMs}ms • ${models.size} model(s))"
                    )
                }
            } else {
                clusterSnapshot.value = null
                RpcService.logEngineEvent("Master probe unreachable: $normalized")
            }

            // Refresh TCP ping on manually saved peers
            val savedPeers = uiState.value.peers.filter { it.isManualSavedPeer }
            for (peer in savedPeers) {
                val ping = SilicaMasterApiClient.probePeerTcp(peer.address)
                repository.updatePeerLatency(peer.address, ping ?: -1L)
            }
            isProbingMaster.value = false
        }
    }

    fun addManualPeer(label: String, rawAddress: String, isMasterRole: Boolean) {
        val cleanAddr = rawAddress.trim()
            .removePrefix("http://")
            .removePrefix("https://")
            .substringBefore("/")
        val hostPort = if (cleanAddr.contains(":")) cleanAddr else "$cleanAddr:50052"
        if (hostPort.substringBefore(":").isBlank()) return

        viewModelScope.launch {
            val ping = SilicaMasterApiClient.probePeerTcp(hostPort)
            repository.upsertPeer(
                SavedPeerEntity(
                    address = hostPort,
                    label = label.trim().ifBlank { "LAN Node $hostPort" },
                    role = if (isMasterRole) "MASTER" else "WORKER",
                    lastLatencyMs = ping ?: -1L
                )
            )
            RpcService.logEngineEvent(
                "Added peer $hostPort (${if (ping != null) "${ping}ms" else "offline"})"
            )
            showAddPeerDialog.value = false
        }
    }

    fun removeManualPeer(address: String) {
        viewModelScope.launch {
            repository.deletePeer(address)
            RpcService.logEngineEvent("Removed peer $address")
        }
    }

    fun toggleTensorCache(enabled: Boolean) {
        viewModelScope.launch {
            repository.updateEngineConfig { it.copy(tensorCacheEnabled = enabled) }
            RpcService.logEngineEvent(
                if (enabled) "First-run tensor flash cache enabled" else "Tensor flash cache paused"
            )
            cacheRefreshNonce.update { it + 1 }
        }
    }

    fun clearTensorCache() {
        viewModelScope.launch(Dispatchers.IO) {
            TensorCacheManager.clearCache(app)
            RpcService.logEngineEvent("Cleared local tensor layer cache")
            cacheRefreshNonce.update { it + 1 }
            statusBanner.value = "Tensor cache cleared"
        }
    }

    fun clearTerminalLogs() {
        RpcService.clearEventLogs()
    }

    fun runDiagnostics() {
        showDiagnosticsDialog.value = true
        viewModelScope.launch(Dispatchers.IO) {
            RpcService.runAutoDiagnostics(app)
        }
    }

    fun runAutoFixAction(action: AutoFixAction) {
        when (action) {
            AutoFixAction.AUTO_DETECT_MASTER -> autoDetectCluster()
            AutoFixAction.RECALIBRATE_DEVICE -> runAutoTune()
            AutoFixAction.START_SHARING -> toggleNodeEngine(true)
            AutoFixAction.SWITCH_TO_USB_SAFE_MODE -> setBridgeMode(true)
            AutoFixAction.FREE_MEMORY_AND_RESTART -> {
                toggleNodeEngine(false)
                viewModelScope.launch {
                    delay(400L)
                    runAutoTune()
                    toggleNodeEngine(true)
                }
            }
        }
        viewModelScope.launch(Dispatchers.IO) {
            delay(800L)
            RpcService.runAutoDiagnostics(app)
        }
    }

    fun createNewChatSession() {
        stopChatStream()
        viewModelScope.launch {
            val id = repository.createSession(
                title = "Cluster Chat",
                modelId = uiState.value.config.selectedModelId
            )
            activeSessionId.value = id
            showSessionsDialog.value = false
        }
    }

    fun selectChatSession(sessionId: Long) {
        stopChatStream()
        activeSessionId.value = sessionId
        showSessionsDialog.value = false
    }

    fun deleteChatSession(sessionId: Long) {
        viewModelScope.launch {
            repository.deleteSession(sessionId)
        }
    }

    fun stopChatStream() {
        activeStreamJob?.cancel()
        activeStreamJob = null
        isStreamingChat.value = false
        liveStreamingTokPerSec.value = 0f
    }

    fun sendChatMessage(rawPrompt: String, imageDataUrl: String? = null) {
        val prompt = rawPrompt.trim()
        if ((prompt.isEmpty() && imageDataUrl.isNullOrBlank()) || isStreamingChat.value) return

        val state = uiState.value
        val cfg = state.config
        // Always send exact model ID (never human-readable display name) to OpenAI-compatible endpoints
        val modelId = clusterSnapshot.value?.activeModelId?.trim()?.ifBlank { null }
            ?: masterHealth.value.activeModelId?.trim()?.ifBlank { null }
            ?: cfg.selectedModelId

        activeStreamJob = viewModelScope.launch {
            var sessionId = activeSessionId.value
            if (sessionId <= 0L) {
                sessionId = repository.createSession(
                    title = prompt.ifBlank { "Image query" }.take(36),
                    modelId = modelId
                )
                activeSessionId.value = sessionId
            }

            val existing = repository.getMessagesOnce(sessionId)
            if (existing.isEmpty()) {
                repository.updateSessionMeta(
                    sessionId = sessionId,
                    title = prompt.ifBlank { "Image query" }.take(40),
                    modelId = modelId
                )
            }

            val storedUserContent = if (!imageDataUrl.isNullOrBlank()) {
                "[Image attached] ${prompt.ifBlank { "Describe this image." }}"
            } else {
                prompt
            }

            repository.insertMessage(
                ChatMessageEntity(
                    sessionId = sessionId,
                    role = "user",
                    content = storedUserContent,
                    modelId = modelId
                )
            )

            val assistantMsgId = repository.insertMessage(
                ChatMessageEntity(
                    sessionId = sessionId,
                    role = "assistant",
                    content = "",
                    modelId = modelId,
                    activeNodes = state.activeNodeCount.coerceAtLeast(1)
                )
            )

            val historyPayload = (existing.takeLast(12).filter { !it.isError }
                .map { ChatCompletionMessage(role = it.role, content = it.content) }) +
                ChatCompletionMessage(
                    role = "user",
                    content = prompt.ifBlank { "Describe this image." },
                    imageDataUrl = imageDataUrl
                )

            isStreamingChat.value = true
            liveStreamingTokPerSec.value = 0f

            val phoneLayers = clusterSnapshot.value?.nodes?.firstOrNull { !it.isMaster }?.allocatedLayers ?: 3
            RpcService.logEngineEvent("[FORWARD] Step started: model=$modelId (${if (imageDataUrl != null) "vision" else "text"})")
            RpcService.logEngineEvent("[COMPUTE] Offloading $phoneLayers layer(s) to local ARM NEON pipeline")

            var lastLoggedTokenCount = 0
            val result = SilicaMasterApiClient.streamChatCompletion(
                baseUrl = cfg.masterBaseUrl,
                modelId = modelId,
                messages = historyPayload,
                temperature = cfg.temperature,
                systemPrompt = cfg.systemPrompt,
                secret = cfg.clusterSecret,
                webToolsEnabled = cfg.webToolsEnabled,
                onDelta = { text, count, tps ->
                    liveStreamingTokPerSec.value = tps
                    if (count - lastLoggedTokenCount >= 30) {
                        lastLoggedTokenCount = count
                        RpcService.logEngineEvent("[COMPUTE] Live step: ${"%.1f".format(tps)} tok/s ($count tokens)")
                    }
                    repository.updateMessage(
                        messageId = assistantMsgId,
                        content = text,
                        tokPerSec = tps,
                        latencyMs = 0L,
                        activeNodes = uiState.value.activeNodeCount.coerceAtLeast(1),
                        isError = false
                    )
                }
            )

            result.onSuccess { (finalText, finalTps, latencyMs) ->
                val text = finalText.ifBlank { "(Empty completion returned by Master)" }
                RpcService.logEngineEvent("[FORWARD] Done: ${"%.1f".format(finalTps)} tok/s (${latencyMs}ms) · all layers synchronized")
                repository.updateMessage(
                    messageId = assistantMsgId,
                    content = text,
                    tokPerSec = finalTps,
                    latencyMs = latencyMs,
                    activeNodes = uiState.value.activeNodeCount.coerceAtLeast(1),
                    isError = false
                )
                masterHealth.value = MasterHealthResult(
                    reachable = true,
                    latencyMs = latencyMs,
                    activeModel = clusterSnapshot.value?.activeModelName ?: modelId,
                    activeModelId = modelId,
                    engineRunning = true,
                    activeNodes = uiState.value.activeNodeCount.coerceAtLeast(1),
                    detail = "Engine Online"
                )
            }.onFailure { err ->
                val msg = err.message ?: "Connection error"
                RpcService.logEngineEvent("[ERROR] Forward pass failed: $msg")
                val isEngineOffline = msg.contains("ENGINE_OFFLINE", ignoreCase = true) || msg.contains("503")
                val cleanError = if (isEngineOffline) {
                    "Engine Offline (HTTP 503)\n\nNo live LLM engine is loaded on your Master yet. Open Silica Cluster on Desktop to load a .gguf model or connect llama-server / Ollama."
                } else {
                    "Could not reach Master OpenAI endpoint (${cfg.masterBaseUrl}/v1/chat/completions).\n\n$msg"
                }
                repository.updateMessage(
                    messageId = assistantMsgId,
                    content = cleanError,
                    tokPerSec = 0f,
                    latencyMs = 0L,
                    activeNodes = 0,
                    isError = true
                )
                masterHealth.value = masterHealth.value.copy(
                    reachable = isEngineOffline,
                    engineRunning = false,
                    detail = if (isEngineOffline) "Engine Offline (HTTP 503)" else msg
                )
            }

            isStreamingChat.value = false
            liveStreamingTokPerSec.value = 0f
        }
    }

    private fun buildMergedPeerList(
        telemetry: DeviceTelemetryState,
        config: SilicaEngineConfig,
        savedPeers: List<SavedPeerEntity>,
        snapshot: MasterClusterSnapshot?,
        masterHealth: MasterHealthResult
    ): List<ClusterPeerUi> {
        val result = mutableListOf<ClusterPeerUi>()

        val localOnline = telemetry.workerState == WorkerRunState.RUNNING_USB ||
            telemetry.workerState == WorkerRunState.RUNNING_WIFI
        val localLayers = if (localOnline) telemetry.activeLayersAssigned.coerceAtLeast(2) else 0
        val localIsMaster = config.nodeRole == "MASTER_HUB"
        val localTps = if (localOnline) {
            (telemetry.calibration.performanceScore.coerceAtLeast(20) / 8.5f)
        } else 0f

        // When Master cluster snapshot is available, reconcile topology to avoid duplicate self-nodes
        if (snapshot != null && snapshot.nodes.isNotEmpty()) {
            val masterNode = snapshot.nodes.firstOrNull { it.isMaster }
            val modelToken = telemetry.deviceModel.trim().substringAfterLast(" ")
            val phoneNode = snapshot.nodes.firstOrNull { remote ->
                !remote.isMaster && (
                    remote.osType.contains("android", ignoreCase = true) ||
                    remote.id.contains("android", ignoreCase = true) ||
                    (modelToken.length >= 3 && remote.label.contains(modelToken, ignoreCase = true)) ||
                    remote.label.contains("Redmi", ignoreCase = true) ||
                    remote.label.contains("Note", ignoreCase = true) ||
                    remote.label.contains("Phone", ignoreCase = true) ||
                    remote.id.contains("dev-", ignoreCase = true)
                )
            } ?: snapshot.nodes.firstOrNull { !it.isMaster }
            val otherWorkers = snapshot.nodes.filter { it != masterNode && it != phoneNode }

            // 1. Master Hub Node
            if (masterNode != null) {
                result += ClusterPeerUi(
                    id = "master_${masterNode.id}",
                    label = masterNode.label,
                    address = config.masterBaseUrl.removePrefix("http://").removePrefix("https://"),
                    roleLabel = "MASTER HUB",
                    backendDriver = masterNode.backendDriver,
                    layerRangeLabel = if (masterNode.allocatedLayers > 0) {
                        "L${masterNode.layerStart}–L${masterNode.layerEnd} (${masterNode.allocatedLayers} L)"
                    } else "Standby",
                    allocatedLayers = masterNode.allocatedLayers,
                    allocatedMb = masterNode.allocatedMb,
                    estimatedTokPerSec = masterNode.estimatedTokPerSec,
                    latencyMs = masterHealth.latencyMs.coerceAtLeast(1L),
                    isOnline = true,
                    isMaster = true
                )
            }

            // 2. This Local Device (accurately unified with Master's shard assignment)
            val phoneLayers = if (phoneNode != null && phoneNode.allocatedLayers > 0) {
                phoneNode.allocatedLayers
            } else if (localOnline) {
                telemetry.activeLayersAssigned
            } else {
                0
            }
            val phoneRange = if (phoneNode != null && phoneNode.allocatedLayers > 0) {
                "L${phoneNode.layerStart}–L${phoneNode.layerEnd} (${phoneNode.allocatedLayers} L)"
            } else if (phoneLayers > 0 && telemetry.assignedLayerStart >= 0 && telemetry.assignedLayerEnd >= telemetry.assignedLayerStart) {
                "L${telemetry.assignedLayerStart}–L${telemetry.assignedLayerEnd} ($phoneLayers L)"
            } else if (phoneLayers > 0) {
                "L0–L${phoneLayers - 1} ($phoneLayers L)"
            } else "Standby"

            result += ClusterPeerUi(
                id = "local_android",
                label = "${telemetry.deviceModel.trim()} (This Phone)",
                address = telemetry.bindAddress,
                roleLabel = if (localIsMaster) "MASTER HUB" else "LOCAL WORKER",
                backendDriver = phoneNode?.backendDriver ?: telemetry.activeBackend.label.substringBefore(" ("),
                layerRangeLabel = phoneRange,
                allocatedLayers = phoneLayers,
                allocatedMb = phoneNode?.allocatedMb?.takeIf { it > 0L } ?: telemetry.assignedRamMb,
                estimatedTokPerSec = phoneNode?.estimatedTokPerSec ?: localTps,
                latencyMs = 0L,
                isOnline = localOnline,
                isLocalDevice = true,
                isMaster = localIsMaster
            )

            // 3. Any additional remote workers (if there are other physical nodes in cluster)
            otherWorkers.forEach { remote ->
                result += ClusterPeerUi(
                    id = "master_${remote.id}",
                    label = remote.label,
                    address = remote.osType,
                    roleLabel = "REMOTE WORKER",
                    backendDriver = remote.backendDriver,
                    layerRangeLabel = if (remote.allocatedLayers > 0) {
                        "L${remote.layerStart}–L${remote.layerEnd} (${remote.allocatedLayers} L)"
                    } else "Standby",
                    allocatedLayers = remote.allocatedLayers,
                    allocatedMb = remote.allocatedMb,
                    estimatedTokPerSec = remote.estimatedTokPerSec,
                    latencyMs = masterHealth.latencyMs.coerceAtLeast(1L),
                    isOnline = true,
                    isMaster = false
                )
            }
        } else {
            // Standalone or pre-connection mode
            result += ClusterPeerUi(
                id = "local_android",
                label = "${telemetry.deviceModel.trim()} (This Phone)",
                address = telemetry.bindAddress,
                roleLabel = if (localIsMaster) "MASTER HUB" else "LOCAL NODE",
                backendDriver = telemetry.activeBackend.label.substringBefore(" ("),
                layerRangeLabel = if (localLayers > 0) "L0–L${localLayers - 1} ($localLayers L)" else "Standby",
                allocatedLayers = localLayers,
                allocatedMb = telemetry.safeCapMb,
                estimatedTokPerSec = localTps,
                latencyMs = 0L,
                isOnline = localOnline,
                isLocalDevice = true,
                isMaster = localIsMaster
            )

            if (masterHealth.reachable) {
                result += ClusterPeerUi(
                    id = "live_master_http",
                    label = "OpenAI Master Endpoint",
                    address = config.masterBaseUrl.removePrefix("http://").removePrefix("https://"),
                    roleLabel = "MASTER HUB",
                    backendDriver = "OpenAI HTTP/SSE",
                    layerRangeLabel = masterHealth.activeModel ?: config.selectedModelId,
                    allocatedLayers = 16,
                    allocatedMb = 4096L,
                    estimatedTokPerSec = 18.0f,
                    latencyMs = masterHealth.latencyMs.coerceAtLeast(1L),
                    isOnline = true,
                    isMaster = true
                )
            }
        }

        // Add manually saved peers from Room
        savedPeers.forEach { saved ->
            if (result.none { it.address == saved.address }) {
                result += ClusterPeerUi(
                    id = "saved_${saved.address}",
                    label = saved.label,
                    address = saved.address,
                    roleLabel = if (saved.role == "MASTER") "MASTER HUB" else "REMOTE WORKER",
                    backendDriver = "TCP RPC v9",
                    layerRangeLabel = if (saved.lastLatencyMs >= 0L) "Ready for shards" else "Unreachable",
                    allocatedLayers = if (saved.lastLatencyMs >= 0L) 8 else 0,
                    allocatedMb = if (saved.lastLatencyMs >= 0L) 2048L else 0L,
                    estimatedTokPerSec = if (saved.lastLatencyMs >= 0L) 8.0f else 0f,
                    latencyMs = saved.lastLatencyMs,
                    isOnline = saved.lastLatencyMs >= 0L,
                    isMaster = saved.role == "MASTER",
                    isManualSavedPeer = true
                )
            }
        }

        return result
    }

    class Factory(
        private val app: Application,
        private val repository: SilicaRepository
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return SilicaViewModel(app, repository) as T
        }
    }
}
