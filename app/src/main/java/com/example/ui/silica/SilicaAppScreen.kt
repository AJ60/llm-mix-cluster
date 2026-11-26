package com.example.ui.silica

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.service.WorkerRunState
import com.example.ui.components.CameraQrScannerDialog
import com.example.ui.theme.SilicaObsidianGlow

@Composable
fun SilicaAppScreen(
    viewModel: SilicaViewModel
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val cs = MaterialTheme.colorScheme
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(uiState.statusBannerMessage) {
        val msg = uiState.statusBannerMessage
        if (!msg.isNullOrBlank()) {
            snackbarHostState.showSnackbar(msg)
            viewModel.dismissBanner()
        }
    }

    // System back on secondary tabs returns cleanly to Command tab
    BackHandler(enabled = uiState.activeTab != SilicaTab.COMMAND) {
        viewModel.selectTab(SilicaTab.COMMAND)
    }

    val isOnline = uiState.telemetry.workerState == WorkerRunState.RUNNING_USB ||
        uiState.telemetry.workerState == WorkerRunState.RUNNING_WIFI
    val isDark = uiState.config.darkTheme

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(cs.background)
            .drawBehind {
                drawRect(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            if (isDark) SilicaObsidianGlow.copy(alpha = 0.7f) else cs.primaryContainer.copy(alpha = 0.25f),
                            cs.background
                        ),
                        center = Offset(size.width * 0.5f, 0f),
                        radius = size.maxDimension * 0.72f
                    )
                )
            }
    ) {
        val useRail = maxWidth >= 600.dp

        if (useRail) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing)
            ) {
                NavigationRail(
                    containerColor = cs.surface,
                    contentColor = cs.onSurface,
                    modifier = Modifier.fillMaxHeight()
                ) {
                    Spacer(Modifier.weight(0.1f))
                    SilicaTab.entries.forEach { tab ->
                        val selected = uiState.activeTab == tab
                        NavigationRailItem(
                            selected = selected,
                            onClick = { viewModel.selectTab(tab) },
                            icon = { TabIcon(tab, selected) },
                            label = { Text(tabLabel(tab)) },
                            colors = NavigationRailItemDefaults.colors(
                                selectedIconColor = cs.primary,
                                selectedTextColor = cs.primary,
                                indicatorColor = cs.primaryContainer,
                                unselectedIconColor = cs.onSurfaceVariant,
                                unselectedTextColor = cs.onSurfaceVariant
                            ),
                            modifier = Modifier.testTag("nav_rail_${tab.route}")
                        )
                    }
                    Spacer(Modifier.weight(1f))
                }

                Scaffold(
                    containerColor = cs.background.copy(alpha = 0f),
                    snackbarHost = { SnackbarHost(snackbarHostState) },
                    topBar = {
                        SilicaTopBar(
                            isOnline = isOnline,
                            activeNodes = uiState.activeNodeCount,
                            isDarkTheme = isDark,
                            onToggleTheme = { viewModel.setDarkTheme(!isDark) },
                            onOpenQr = { viewModel.openQrDialog(0) },
                            applyStatusBarPadding = false
                        )
                    },
                    modifier = Modifier.weight(1f)
                ) { innerPadding ->
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding),
                        contentAlignment = Alignment.TopCenter
                    ) {
                        Box(modifier = Modifier.widthIn(max = 760.dp).fillMaxSize()) {
                            SilicaTabBody(uiState = uiState, viewModel = viewModel)
                        }
                    }
                }
            }
        } else {
            Scaffold(
                containerColor = cs.background.copy(alpha = 0f),
                snackbarHost = { SnackbarHost(snackbarHostState) },
                topBar = {
                    SilicaTopBar(
                        isOnline = isOnline,
                        activeNodes = uiState.activeNodeCount,
                        isDarkTheme = isDark,
                        onToggleTheme = { viewModel.setDarkTheme(!isDark) },
                        onOpenQr = { viewModel.openQrDialog(0) },
                        applyStatusBarPadding = true
                    )
                },
                bottomBar = {
                    NavigationBar(
                        containerColor = cs.surface,
                        tonalElevation = 0.dp,
                        windowInsets = WindowInsets.navigationBars
                    ) {
                        SilicaTab.entries.forEach { tab ->
                            val selected = uiState.activeTab == tab
                            NavigationBarItem(
                                selected = selected,
                                onClick = { viewModel.selectTab(tab) },
                                icon = { TabIcon(tab, selected) },
                                label = { Text(tabLabel(tab), style = MaterialTheme.typography.labelLarge) },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = cs.primary,
                                    selectedTextColor = cs.primary,
                                    indicatorColor = cs.primaryContainer,
                                    unselectedIconColor = cs.onSurfaceVariant,
                                    unselectedTextColor = cs.onSurfaceVariant
                                ),
                                modifier = Modifier.testTag("nav_bar_${tab.route}")
                            )
                        }
                    }
                }
            ) { innerPadding ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                ) {
                    SilicaTabBody(uiState = uiState, viewModel = viewModel)
                }
            }
        }
    }

    // Modal Overlays
    if (uiState.showQrDialog) {
        CameraQrScannerDialog(
            telemetry = uiState.telemetry,
            initialTab = uiState.qrInitialTab,
            onDismiss = { viewModel.closeQrDialog() },
            onSubmitQrPayload = { payload -> viewModel.pairWithQrPayload(payload) }
        )
    }

    if (uiState.showModelEndpointSheet) {
        ModelAndEndpointDialog(
            uiState = uiState,
            onDismiss = { viewModel.closeModelEndpointSheet() },
            onSelectModel = { modelId -> viewModel.selectModel(modelId) },
            onSaveEndpoint = { url, secret, temp, sysPrompt ->
                viewModel.saveMasterEndpointSettings(url, secret, temp, sysPrompt)
            }
        )
    }

    if (uiState.showAddPeerDialog) {
        AddManualPeerDialog(
            onDismiss = { viewModel.closeAddPeerDialog() },
            onAddPeer = { label, addr, isMaster ->
                viewModel.addManualPeer(label, addr, isMaster)
            }
        )
    }

    if (uiState.showDiagnosticsDialog) {
        DiagnosticsReportDialog(
            report = uiState.telemetry.diagnosticReport,
            isDiagnosing = uiState.telemetry.isDiagnosing,
            onRunFix = { action -> viewModel.runAutoFixAction(action) },
            onDismiss = { viewModel.closeDiagnosticsDialog() }
        )
    }

    if (uiState.showSessionsDialog) {
        ChatSessionsDialog(
            sessions = uiState.sessions,
            activeSessionId = uiState.activeSessionId,
            onSelect = { id -> viewModel.selectChatSession(id) },
            onDelete = { id -> viewModel.deleteChatSession(id) },
            onNewChat = { viewModel.createNewChatSession() },
            onDismiss = { viewModel.closeSessionsDialog() }
        )
    }
}

@Composable
private fun SilicaTopBar(
    isOnline: Boolean,
    activeNodes: Int,
    isDarkTheme: Boolean,
    onToggleTheme: () -> Unit,
    onOpenQr: () -> Unit,
    applyStatusBarPadding: Boolean
) {
    val cs = MaterialTheme.colorScheme
    Surface(
        color = cs.surface.copy(alpha = 0.92f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .let { if (applyStatusBarPadding) it.windowInsetsPadding(WindowInsets.statusBars) else it }
                .padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(cs.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.Hub,
                        contentDescription = stringResource(R.string.app_name),
                        tint = cs.primary,
                        modifier = Modifier.size(19.dp)
                    )
                }
                Column {
                    Text(
                        text = stringResource(R.string.app_name),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = cs.onSurface
                    )
                    Text(
                        text = stringResource(R.string.silica_tagline),
                        style = MaterialTheme.typography.labelSmall,
                        color = cs.onSurfaceVariant
                    )
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = if (isOnline) cs.primaryContainer else cs.surfaceContainer
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .clip(CircleShape)
                                .background(if (isOnline) cs.primary else cs.onSurfaceVariant)
                        )
                        Text(
                            text = if (isOnline) "$activeNodes ACTIVE" else "STANDBY",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isOnline) cs.onPrimaryContainer else cs.onSurfaceVariant
                        )
                    }
                }
                IconButton(
                    onClick = onToggleTheme,
                    modifier = Modifier.testTag("topbar_theme_button")
                ) {
                    Icon(
                        imageVector = if (isDarkTheme) Icons.Outlined.LightMode else Icons.Outlined.DarkMode,
                        contentDescription = if (isDarkTheme) "Switch to Light Mode" else "Switch to Dark Mode",
                        tint = cs.onSurfaceVariant
                    )
                }
                IconButton(
                    onClick = onOpenQr,
                    modifier = Modifier.testTag("topbar_qr_button")
                ) {
                    Icon(
                        imageVector = Icons.Outlined.QrCodeScanner,
                        contentDescription = stringResource(R.string.cluster_qr_pair),
                        tint = cs.secondary
                    )
                }
            }
        }
    }
}

@Composable
private fun SilicaTabBody(
    uiState: SilicaUiState,
    viewModel: SilicaViewModel
) {
    AnimatedContent(
        targetState = uiState.activeTab,
        transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(150)) },
        label = "silica_tab_content"
    ) { tab ->
        when (tab) {
            SilicaTab.COMMAND -> SilicaCommandScreen(
                uiState = uiState,
                onToggleEngine = viewModel::toggleNodeEngine,
                onSelectRole = viewModel::setNodeRole,
                onOpenModelSheet = viewModel::openModelEndpointSheet,
                onPickLocalGguf = viewModel::attachLocalGgufUri,
                onSetThreads = viewModel::setThreadCount,
                onSetSafeRamPercent = viewModel::setSafeRamPercent,
                onSetContextLength = viewModel::setContextLength,
                onSetKvQuant = viewModel::setKvCacheQuant,
                onRunAutoTune = viewModel::runAutoTune,
                onSetBridgeMode = viewModel::setBridgeMode,
                onSaveMasterUrlAndSecret = { url, secret ->
                    viewModel.saveMasterEndpointSettings(
                        baseUrl = url,
                        secret = secret,
                        temperature = uiState.config.temperature,
                        systemPrompt = uiState.config.systemPrompt
                    )
                },
                onPingSyncMaster = { viewModel.syncWithMaster() },
                onAutoDetect = viewModel::autoDetectCluster,
                onOpenQr = { viewModel.openQrDialog(0) },
                onSelectLogFilter = viewModel::setLogFilter,
                onClearLogs = viewModel::clearTerminalLogs,
                onRunDiagnostics = viewModel::runDiagnostics,
                onDismissOnboarding = viewModel::dismissOnboarding
            )
            SilicaTab.CLUSTER -> SilicaClusterScreen(
                uiState = uiState,
                onAutoDetect = viewModel::autoDetectCluster,
                onOpenQrScan = { viewModel.openQrDialog(0) },
                onOpenQrShare = { viewModel.openQrDialog(1) },
                onOpenAddPeer = viewModel::openAddPeerDialog,
                onRefreshCluster = { viewModel.syncWithMaster() },
                onRemoveManualPeer = viewModel::removeManualPeer,
                onToggleTensorCache = viewModel::toggleTensorCache,
                onClearTensorCache = viewModel::clearTensorCache
            )
            SilicaTab.CHAT -> SilicaChatScreen(
                uiState = uiState,
                onSendPrompt = { prompt, imageDataUrl -> viewModel.sendChatMessage(prompt, imageDataUrl) },
                onStopStreaming = viewModel::stopChatStream,
                onToggleWebTools = viewModel::setWebToolsEnabled,
                onOpenModelEndpointSheet = viewModel::openModelEndpointSheet,
                onOpenSessionsDialog = viewModel::openSessionsDialog,
                onNewSession = viewModel::createNewChatSession,
                onAutoDetect = viewModel::autoDetectCluster
            )
        }
    }
}

@Composable
private fun TabIcon(tab: SilicaTab, selected: Boolean) {
    val icon = when (tab) {
        SilicaTab.COMMAND -> if (selected) Icons.Filled.Terminal else Icons.Outlined.Terminal
        SilicaTab.CLUSTER -> if (selected) Icons.Filled.Hub else Icons.Outlined.Hub
        SilicaTab.CHAT -> if (selected) Icons.Filled.ChatBubble else Icons.Outlined.ChatBubbleOutline
    }
    Icon(imageVector = icon, contentDescription = tabLabel(tab))
}

@Composable
private fun tabLabel(tab: SilicaTab): String = when (tab) {
    SilicaTab.COMMAND -> stringResource(R.string.tab_command)
    SilicaTab.CLUSTER -> stringResource(R.string.tab_cluster)
    SilicaTab.CHAT -> stringResource(R.string.tab_chat)
}
