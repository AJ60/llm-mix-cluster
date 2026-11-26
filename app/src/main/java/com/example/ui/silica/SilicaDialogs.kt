package com.example.ui.silica

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.RadioButtonChecked
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.ChatSessionEntity
import com.example.service.AutoFixAction
import com.example.service.DiagnosticReport
import com.example.service.DiagnosticSeverity

@Composable
fun ModelAndEndpointDialog(
    uiState: SilicaUiState,
    onDismiss: () -> Unit,
    onSelectModel: (String) -> Unit,
    onSaveEndpoint: (url: String, secret: String, temperature: Float, systemPrompt: String) -> Unit
) {
    val cs = MaterialTheme.colorScheme
    val cfg = uiState.config
    var urlInput by remember(cfg.masterBaseUrl) { mutableStateOf(cfg.masterBaseUrl) }
    var secretInput by remember(cfg.clusterSecret) { mutableStateOf(cfg.clusterSecret) }
    var tempSlider by remember(cfg.temperature) { mutableFloatStateOf(cfg.temperature) }
    var sysPromptInput by remember(cfg.systemPrompt) { mutableStateOf(cfg.systemPrompt) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = cs.surfaceContainer,
        title = {
            Text(
                text = stringResource(R.string.dialog_model_endpoint_title),
                style = MaterialTheme.typography.titleLarge,
                color = cs.onSurface
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (uiState.liveModels.isNotEmpty()) {
                    Text(
                        text = "LIVE MASTER MODELS (/v1/models)",
                        style = MaterialTheme.typography.labelSmall,
                        color = cs.primary
                    )
                    uiState.liveModels.forEach { modelId ->
                        ModelChoiceRow(
                            title = modelId,
                            subtitle = "Reported live by Master endpoint",
                            selected = cfg.selectedModelId == modelId,
                            onClick = { onSelectModel(modelId) }
                        )
                    }
                    HorizontalDivider(color = cs.outlineVariant)
                }

                Text(
                    text = "GGUF CLUSTER PRESETS",
                    style = MaterialTheme.typography.labelSmall,
                    color = cs.secondary
                )
                SILICA_MODEL_PRESETS.forEach { preset ->
                    ModelChoiceRow(
                        title = "${preset.displayName} (${preset.paramsLabel})",
                        subtitle = "${preset.quant} • ${preset.totalLayers} layers • min ${preset.minRamGb} GB pool",
                        selected = cfg.selectedModelId == preset.id,
                        onClick = { onSelectModel(preset.id) }
                    )
                }

                HorizontalDivider(color = cs.outlineVariant)

                Text(
                    text = "MASTER OPENAI ENDPOINT",
                    style = MaterialTheme.typography.labelSmall,
                    color = cs.secondary
                )
                OutlinedTextField(
                    value = urlInput,
                    onValueChange = { urlInput = it },
                    label = { Text(stringResource(R.string.cmd_master_url_label)) },
                    placeholder = { Text("http://192.168.1.10:8080") },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.labelMedium,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("dialog_master_url_input")
                )
                OutlinedTextField(
                    value = secretInput,
                    onValueChange = { secretInput = it },
                    label = { Text(stringResource(R.string.cmd_cluster_secret_label)) },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.labelMedium,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("dialog_secret_input")
                )

                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Sampling Temperature", style = MaterialTheme.typography.bodySmall)
                        Text(
                            text = "%.2f".format(tempSlider),
                            style = MaterialTheme.typography.labelSmall,
                            color = cs.primary
                        )
                    }
                    Slider(
                        value = tempSlider,
                        onValueChange = { tempSlider = it },
                        valueRange = 0f..1.5f
                    )
                }

                OutlinedTextField(
                    value = sysPromptInput,
                    onValueChange = { sysPromptInput = it },
                    label = { Text("System Prompt") },
                    maxLines = 3,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSaveEndpoint(urlInput, secretInput, tempSlider, sysPromptInput)
                    onDismiss()
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = cs.primary,
                    contentColor = cs.onPrimary
                ),
                modifier = Modifier.testTag("save_endpoint_dialog_button")
            ) {
                Text(stringResource(R.string.btn_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.btn_close))
            }
        }
    )
}

@Composable
private fun ModelChoiceRow(
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    Surface(
        shape = MaterialTheme.shapes.small,
        color = if (selected) cs.primaryContainer.copy(alpha = 0.55f) else cs.surfaceContainerLow,
        border = BorderStroke(1.dp, if (selected) cs.primary else cs.outlineVariant),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .minimumInteractiveComponentSize()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = if (selected) Icons.Outlined.RadioButtonChecked else Icons.Outlined.RadioButtonUnchecked,
                contentDescription = null,
                tint = if (selected) cs.primary else cs.onSurfaceVariant,
                modifier = Modifier.size(18.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = cs.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = cs.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
fun AddManualPeerDialog(
    onDismiss: () -> Unit,
    onAddPeer: (label: String, address: String, isMaster: Boolean) -> Unit
) {
    val cs = MaterialTheme.colorScheme
    var label by remember { mutableStateOf("") }
    var address by remember { mutableStateOf("192.168.1.") }
    var isMaster by remember { mutableStateOf(false) }

    val hostPart = address.trim().substringBefore(":")
    val isValid = hostPart.length >= 3 && !hostPart.endsWith(".")

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = cs.surfaceContainer,
        title = {
            Text(
                text = stringResource(R.string.dialog_add_peer_title),
                style = MaterialTheme.typography.titleLarge,
                color = cs.onSurface
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text("Node Name") },
                    placeholder = { Text("e.g., Office Laptop or Pixel 7") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("add_peer_label_input")
                )
                OutlinedTextField(
                    value = address,
                    onValueChange = { address = it },
                    label = { Text("LAN IP:Port") },
                    placeholder = { Text("192.168.1.24:50052") },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.labelMedium,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("add_peer_address_input")
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = !isMaster,
                        onClick = { isMaster = false },
                        label = { Text("Worker Node") }
                    )
                    FilterChip(
                        selected = isMaster,
                        onClick = { isMaster = true },
                        label = { Text("Master Hub") }
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onAddPeer(label, address, isMaster) },
                enabled = isValid,
                colors = ButtonDefaults.buttonColors(
                    containerColor = cs.primary,
                    contentColor = cs.onPrimary
                ),
                modifier = Modifier.testTag("confirm_add_peer_button")
            ) {
                Text(stringResource(R.string.btn_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.btn_cancel))
            }
        }
    )
}

@Composable
fun DiagnosticsReportDialog(
    report: DiagnosticReport?,
    isDiagnosing: Boolean,
    onRunFix: (AutoFixAction) -> Unit,
    onDismiss: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = cs.surfaceContainer,
        title = {
            Text(
                text = stringResource(R.string.dialog_diagnostics_title),
                style = MaterialTheme.typography.titleLarge,
                color = cs.onSurface
            )
        },
        text = {
            if (isDiagnosing || report == null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 20.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(22.dp), color = cs.secondary)
                    Spacer(Modifier.width(12.dp))
                    Text("Running 6-check hardware & LAN audit…", style = MaterialTheme.typography.bodyMedium)
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 430.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = report.headline,
                        style = MaterialTheme.typography.titleSmall,
                        color = when (report.overallStatus) {
                            DiagnosticSeverity.OK -> cs.primary
                            DiagnosticSeverity.WARNING -> cs.tertiary
                            DiagnosticSeverity.ISSUE -> cs.error
                        }
                    )
                    Text(
                        text = "${report.subtext} (${report.ranAtLabel})",
                        style = MaterialTheme.typography.bodySmall,
                        color = cs.onSurfaceVariant
                    )
                    HorizontalDivider(color = cs.outlineVariant)
                    report.checks.forEach { check ->
                        val iconColor = when (check.severity) {
                            DiagnosticSeverity.OK -> cs.primary
                            DiagnosticSeverity.WARNING -> cs.tertiary
                            DiagnosticSeverity.ISSUE -> cs.error
                        }
                        Surface(
                            shape = MaterialTheme.shapes.small,
                            color = cs.surfaceContainerLow,
                            border = BorderStroke(1.dp, iconColor.copy(alpha = 0.35f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(10.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Icon(
                                        imageVector = when (check.severity) {
                                            DiagnosticSeverity.OK -> Icons.Outlined.CheckCircle
                                            DiagnosticSeverity.WARNING -> Icons.Outlined.WarningAmber
                                            DiagnosticSeverity.ISSUE -> Icons.Outlined.ErrorOutline
                                        },
                                        contentDescription = check.title,
                                        tint = iconColor,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Text(check.title, style = MaterialTheme.typography.titleSmall, color = cs.onSurface)
                                }
                                Text(check.statusText, style = MaterialTheme.typography.labelSmall, color = iconColor)
                                Text(check.plainEnglishTip, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                                if (check.fixAction != null) {
                                    OutlinedButton(
                                        onClick = { onRunFix(check.fixAction) },
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                        modifier = Modifier.minimumInteractiveComponentSize()
                                    ) {
                                        Text(check.fixAction.buttonLabel, style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.btn_close))
            }
        }
    )
}

@Composable
fun ChatSessionsDialog(
    sessions: List<ChatSessionEntity>,
    activeSessionId: Long,
    onSelect: (Long) -> Unit,
    onDelete: (Long) -> Unit,
    onNewChat: () -> Unit,
    onDismiss: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = cs.surfaceContainer,
        title = {
            Text(
                text = stringResource(R.string.dialog_sessions_title),
                style = MaterialTheme.typography.titleLarge,
                color = cs.onSurface
            )
        },
        text = {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 360.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(sessions, key = { it.id }) { session ->
                    val isActive = session.id == activeSessionId
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = if (isActive) cs.primaryContainer.copy(alpha = 0.5f) else cs.surfaceContainerLow,
                        border = BorderStroke(1.dp, if (isActive) cs.primary else cs.outlineVariant),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(session.id) }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = session.title,
                                    style = MaterialTheme.typography.titleSmall,
                                    color = cs.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = session.modelId,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = cs.onSurfaceVariant
                                )
                            }
                            if (sessions.size > 1) {
                                IconButton(onClick = { onDelete(session.id) }) {
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
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onNewChat,
                colors = ButtonDefaults.buttonColors(
                    containerColor = cs.primary,
                    contentColor = cs.onPrimary
                )
            ) {
                Text(stringResource(R.string.chat_new_session))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.btn_close))
            }
        }
    )
}
