package com.ares.analytics.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SettingsInputComponent
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ares.analytics.service.integration.DeliveryState
import com.ares.analytics.service.integration.IntegrationCredential
import com.ares.analytics.service.integration.ProviderConnectionResult
import com.ares.analytics.shared.models.IntegrationEventType
import com.ares.analytics.shared.models.IntegrationWorkspaceIdentity
import com.ares.analytics.shared.models.NotebookPublisherConfig
import com.ares.analytics.shared.models.NotebookPublisherKind
import com.ares.analytics.shared.models.NotebookReviewState
import com.ares.analytics.shared.models.NotificationProviderConfig
import com.ares.analytics.shared.models.NotificationProviderKind
import com.ares.analytics.shared.models.WebhookNotificationTarget
import com.ares.analytics.shared.models.ZulipNotificationTarget
import com.ares.analytics.ui.components.core.AresCard
import com.ares.analytics.ui.theme.AresBorder
import com.ares.analytics.ui.theme.AresCyan
import com.ares.analytics.ui.theme.AresError
import com.ares.analytics.ui.theme.AresSurface
import com.ares.analytics.ui.theme.AresSurfaceElevated
import com.ares.analytics.ui.theme.AresTextPrimary
import com.ares.analytics.ui.theme.AresTextSecondary
import com.ares.analytics.viewmodel.integrationcenter.IntegrationCenterIntent
import com.ares.analytics.viewmodel.integrationcenter.IntegrationCenterState
import com.ares.analytics.viewmodel.integrationcenter.IntegrationCenterTab
import com.ares.analytics.viewmodel.integrationcenter.IntegrationCenterViewModel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private enum class ProviderEditorKind { ZULIP, WEBHOOK, LOCAL_MARKDOWN, GOOGLE_DRIVE, CMS }

@Composable
fun IntegrationCenterScreen(
    viewModel: IntegrationCenterViewModel,
    workspace: IntegrationWorkspaceIdentity,
) {
    val state by viewModel.state.collectAsState()
    var editorKind by remember { mutableStateOf<ProviderEditorKind?>(null) }
    var editingNotification by remember { mutableStateOf<NotificationProviderConfig?>(null) }
    var editingPublisher by remember { mutableStateOf<NotebookPublisherConfig?>(null) }
    var pendingDelete by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) { viewModel.onIntent(IntegrationCenterIntent.Load) }

    Column(
        Modifier.fillMaxSize().background(AresSurface).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Integration Center", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = AresTextPrimary)
                Text(
                    "Connect team services, inspect durable deliveries, and review notebook drafts. Robot operation remains local.",
                    color = AresTextSecondary,
                    fontSize = 12.sp,
                )
            }
            if (state.activeOperation != null) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = AresCyan)
                Spacer(Modifier.width(8.dp))
                Text(state.activeOperation.orEmpty(), color = AresTextSecondary, fontSize = 12.sp)
            }
            IconButton(onClick = { viewModel.onIntent(IntegrationCenterIntent.Load) }) {
                Icon(Icons.Default.Refresh, "Refresh integrations", tint = AresCyan)
            }
        }

        FeedbackBanner(state, onDismiss = { viewModel.onIntent(IntegrationCenterIntent.ClearFeedback) })

        PrimaryTabRow(selectedTabIndex = state.tab.ordinal, containerColor = AresSurfaceElevated) {
            IntegrationCenterTab.entries.forEach { tab ->
                Tab(
                    selected = state.tab == tab,
                    onClick = { viewModel.onIntent(IntegrationCenterIntent.SelectTab(tab)) },
                    text = { Text(tab.name.lowercase().replaceFirstChar(Char::uppercase)) },
                )
            }
        }

        when (state.tab) {
            IntegrationCenterTab.PROVIDERS -> ProvidersPanel(
                state = state,
                onAdd = { kind -> editorKind = kind },
                onEditNotification = { config -> editingNotification = config; editorKind = config.kind.toEditorKind() },
                onEditPublisher = { config -> editingPublisher = config; editorKind = config.kind.toEditorKind() },
                onSaveNotification = { config -> viewModel.onIntent(IntegrationCenterIntent.SaveNotification(config, null)) },
                onSavePublisher = { config -> viewModel.onIntent(IntegrationCenterIntent.SavePublisher(config, null)) },
                onTest = { config -> viewModel.onIntent(IntegrationCenterIntent.TestNotification(config, null)) },
                onSendTest = { providerId ->
                    viewModel.onIntent(IntegrationCenterIntent.SendTestNotification(providerId, workspace))
                },
                onDelete = { pendingDelete = it },
            )
            IntegrationCenterTab.DELIVERIES -> DeliveriesPanel(state) { eventId, providerId ->
                viewModel.onIntent(IntegrationCenterIntent.RetryDelivery(eventId, providerId))
            }
            IntegrationCenterTab.NOTEBOOK -> NotebookPanel(state, viewModel::onIntent)
        }
    }

    editorKind?.let { kind ->
        ProviderEditorDialog(
            kind = kind,
            notification = editingNotification,
            publisher = editingPublisher,
            credentialProtection = state.credentialProtectionDescription,
            onDismiss = { editorKind = null; editingNotification = null; editingPublisher = null },
            onSaveNotification = { config, credential ->
                viewModel.onIntent(IntegrationCenterIntent.SaveNotification(config, credential))
                editorKind = null; editingNotification = null
            },
            onSavePublisher = { config, credential ->
                viewModel.onIntent(IntegrationCenterIntent.SavePublisher(config, credential))
                editorKind = null; editingPublisher = null
            },
            onTest = { config, credential ->
                viewModel.onIntent(IntegrationCenterIntent.TestNotification(config, credential))
            },
        )
    }
    pendingDelete?.let { providerId ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Remove integration?") },
            text = { Text("$providerId will stop receiving new work. Historical events, deliveries, and receipts will be retained.") },
            confirmButton = {
                Button(onClick = {
                    viewModel.onIntent(IntegrationCenterIntent.DeleteProvider(providerId))
                    pendingDelete = null
                }) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun FeedbackBanner(state: IntegrationCenterState, onDismiss: () -> Unit) {
    val text = state.error ?: state.message ?: return
    val error = state.error != null || state.connectionResult is ProviderConnectionResult.Failed
    Card(
        colors = CardDefaults.cardColors(containerColor = if (error) AresError.copy(alpha = .12f) else AresCyan.copy(alpha = .10f)),
        border = BorderStroke(1.dp, if (error) AresError.copy(alpha = .5f) else AresCyan.copy(alpha = .4f)),
    ) {
        Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text, Modifier.weight(1f), color = if (error) AresError else AresTextPrimary, fontSize = 12.sp)
            TextButton(onClick = onDismiss) { Text("Dismiss") }
        }
    }
}

@Composable
private fun ProvidersPanel(
    state: IntegrationCenterState,
    onAdd: (ProviderEditorKind) -> Unit,
    onEditNotification: (NotificationProviderConfig) -> Unit,
    onEditPublisher: (NotebookPublisherConfig) -> Unit,
    onSaveNotification: (NotificationProviderConfig) -> Unit,
    onSavePublisher: (NotebookPublisherConfig) -> Unit,
    onTest: (NotificationProviderConfig) -> Unit,
    onSendTest: (String) -> Unit,
    onDelete: (String) -> Unit,
) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ProviderEditorKind.entries.forEach { kind ->
                OutlinedButton(onClick = { onAdd(kind) }) {
                    Icon(Icons.Default.Add, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(5.dp))
                    Text(kind.label())
                }
            }
        }
        Text(
            "Credentials are write-only and protected by ${state.credentialProtectionDescription}.",
            color = AresTextSecondary,
            fontSize = 11.sp,
        )
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(state.settings.notificationProviders, key = { "notification:${it.providerId}" }) { config ->
                ProviderCard(
                    name = config.displayName,
                    id = config.providerId,
                    detail = when (config.kind) {
                        NotificationProviderKind.ZULIP -> "Zulip · ${config.zulip?.stream} / ${config.zulip?.topic}"
                        NotificationProviderKind.WEBHOOK -> "Signed webhook · ${config.webhook?.url}"
                    },
                    enabled = config.enabled,
                    error = state.configurationErrors[config.providerId],
                    onEnabled = { onSaveNotification(config.copy(enabled = it)) },
                    onEdit = { onEditNotification(config) },
                    onTest = { onTest(config) },
                    onSendTest = { onSendTest(config.providerId) },
                    onDelete = { onDelete(config.providerId) },
                )
            }
            items(state.settings.notebookPublishers, key = { "publisher:${it.publisherId}" }) { config ->
                ProviderCard(
                    name = config.displayName,
                    id = config.publisherId,
                    detail = "Notebook publisher · ${config.kind.name.lowercase().replace('_', ' ')}",
                    enabled = config.enabled,
                    error = state.configurationErrors[config.publisherId],
                    onEnabled = { onSavePublisher(config.copy(enabled = it)) },
                    onEdit = { onEditPublisher(config) },
                    onTest = null,
                    onSendTest = null,
                    onDelete = { onDelete(config.publisherId) },
                )
            }
            if (state.settings.notificationProviders.isEmpty() && state.settings.notebookPublishers.isEmpty()) {
                item { EmptyCard("No integrations configured", "Studio remains fully local. Add only the services this team wants to use.") }
            }
        }
    }
}

@Composable
private fun ProviderCard(
    name: String,
    id: String,
    detail: String,
    enabled: Boolean,
    error: String?,
    onEnabled: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onTest: (() -> Unit)?,
    onSendTest: (() -> Unit)?,
    onDelete: () -> Unit,
) {
    Card(colors = CardDefaults.cardColors(containerColor = AresSurfaceElevated), border = BorderStroke(1.dp, AresBorder)) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.SettingsInputComponent, null, tint = if (enabled) AresCyan else AresTextSecondary)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(name, fontWeight = FontWeight.SemiBold, color = AresTextPrimary)
                Text("$detail · $id", color = AresTextSecondary, fontSize = 11.sp)
                error?.let { Text(it, color = AresError, fontSize = 11.sp) }
            }
            onTest?.let { OutlinedButton(onClick = it, enabled = enabled) { Text("Connect") } }
            onSendTest?.let {
                Spacer(Modifier.width(6.dp))
                OutlinedButton(onClick = it, enabled = enabled) { Text("Send test") }
            }
            IconButton(onClick = onEdit) { Icon(Icons.Default.Edit, "Edit $name", tint = AresTextSecondary) }
            IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, "Delete $name", tint = AresError) }
            Switch(checked = enabled, onCheckedChange = onEnabled)
        }
    }
}

@Composable
private fun DeliveriesPanel(state: IntegrationCenterState, onRetry: (String, String) -> Unit) {
    if (state.deliveries.isEmpty()) {
        EmptyCard("No delivery history", "Events will appear here after a configured integration receives work.")
        return
    }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(state.deliveries, key = { "${it.delivery.eventId}:${it.delivery.providerId}" }) { summary ->
            val delivery = summary.delivery
            Card(colors = CardDefaults.cardColors(containerColor = AresSurfaceElevated), border = BorderStroke(1.dp, AresBorder)) {
                Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(summary.event.payload::class.simpleName.orEmpty(), color = AresTextPrimary, fontWeight = FontWeight.SemiBold)
                        Text("${delivery.providerId} · ${delivery.state.name.lowercase()} · attempt ${delivery.attemptCount}", color = AresTextSecondary, fontSize = 11.sp)
                        delivery.lastErrorMessage?.let { Text(it, color = AresError, fontSize = 11.sp) }
                        Text(formatTime(delivery.updatedAtMs), color = AresTextSecondary, fontSize = 10.sp)
                    }
                    if (delivery.state in setOf(DeliveryState.DEAD, DeliveryState.RETRY)) {
                        OutlinedButton(onClick = { onRetry(delivery.eventId, delivery.providerId) }) {
                            Icon(Icons.Default.Refresh, null, Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Retry")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NotebookPanel(state: IntegrationCenterState, onIntent: (IntegrationCenterIntent) -> Unit) {
    var reviewer by remember { mutableStateOf("") }
    var revision by remember(state.selectedEntryId, state.selectedRevisions) {
        mutableStateOf(state.selectedRevisions.lastOrNull()?.revision)
    }
    val selected = state.selectedRevisions.firstOrNull { it.revision == revision } ?: state.selectedRevisions.lastOrNull()
    var selectedPublishers by remember(state.selectedEntryId, selected?.revision, state.settings.notebookPublishers) {
        mutableStateOf(emptySet<String>())
    }
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Card(Modifier.width(300.dp).fillMaxHeight(), colors = CardDefaults.cardColors(containerColor = AresSurfaceElevated), border = BorderStroke(1.dp, AresBorder)) {
            Column(Modifier.fillMaxSize().padding(12.dp)) {
                Text("Notebook entries", fontWeight = FontWeight.Bold, color = AresTextPrimary)
                Spacer(Modifier.height(8.dp))
                LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(state.notebooks, key = { it.entryId }) { entry ->
                        FilterChip(
                            selected = state.selectedEntryId == entry.entryId,
                            onClick = { onIntent(IntegrationCenterIntent.SelectNotebook(entry.entryId)) },
                            label = {
                                Column {
                                    Text(entry.entryId, maxLines = 1)
                                    Text("r${entry.revision} · ${entry.reviewState.name.lowercase()}", fontSize = 10.sp)
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
        Card(Modifier.weight(1f).fillMaxHeight(), colors = CardDefaults.cardColors(containerColor = AresSurfaceElevated), border = BorderStroke(1.dp, AresBorder)) {
            if (selected == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Select a notebook entry to review its evidence and revisions.", color = AresTextSecondary)
                }
            } else {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(selected.entryId, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = AresTextPrimary)
                        state.selectedRevisions.forEach { candidate ->
                            FilterChip(
                                selected = selected.revision == candidate.revision,
                                onClick = { revision = candidate.revision },
                                label = { Text("r${candidate.revision}") },
                            )
                            Spacer(Modifier.width(4.dp))
                        }
                    }
                    Text("${selected.reviewState.name.lowercase()} · SHA-256 ${selected.contentHash.take(16)}…", color = AresTextSecondary, fontSize = 11.sp)
                    selected.aiProvenance?.let { ai ->
                        Text("AI draft: ${ai.provider} / ${ai.model}; prompt schema ${ai.promptSchemaVersion}", color = AresTextSecondary, fontSize = 11.sp)
                    }
                    HorizontalDivider(color = AresBorder)
                    Text(selected.markdownBody, color = AresTextPrimary, fontSize = 13.sp)
                    if (selected.evidence.isNotEmpty()) {
                        HorizontalDivider(color = AresBorder)
                        Text("Evidence", fontWeight = FontWeight.Bold, color = AresTextPrimary)
                        selected.evidence.forEach { evidence ->
                            Text("• ${evidence.label ?: evidence.kind} — ${evidence.referenceId}", color = AresTextSecondary, fontSize = 11.sp)
                        }
                    }
                    HorizontalDivider(color = AresBorder)
                    if (selected.reviewState in setOf(NotebookReviewState.DRAFT, NotebookReviewState.REVIEWED)) {
                        OutlinedTextField(
                            value = reviewer,
                            onValueChange = { reviewer = it.take(256) },
                            label = { Text("Reviewer identity") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Button(
                            onClick = { onIntent(IntegrationCenterIntent.Approve(selected.entryId, selected.revision, reviewer)) },
                            enabled = reviewer.isNotBlank(),
                        ) {
                            Icon(Icons.Default.CheckCircle, null, Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Approve this exact revision")
                        }
                    }
                    if (selected.reviewState == NotebookReviewState.APPROVED) {
                        Text("Submit to", fontWeight = FontWeight.Bold, color = AresTextPrimary)
                        state.settings.notebookPublishers.filter { it.enabled }.forEach { publisher ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(
                                    checked = publisher.publisherId in selectedPublishers,
                                    onCheckedChange = { checked ->
                                        selectedPublishers = if (checked) selectedPublishers + publisher.publisherId
                                        else selectedPublishers - publisher.publisherId
                                    },
                                )
                                Text(publisher.displayName, color = AresTextPrimary)
                            }
                        }
                        Button(
                            onClick = { onIntent(IntegrationCenterIntent.Submit(selected.entryId, selected.revision, selectedPublishers)) },
                            enabled = selectedPublishers.isNotEmpty(),
                        ) {
                            Icon(Icons.AutoMirrored.Filled.Send, null, Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Submit approved revision")
                        }
                    }
                    if (state.selectedReceipts.isNotEmpty()) {
                        Text("Publication receipts", fontWeight = FontWeight.Bold, color = AresTextPrimary)
                        state.selectedReceipts.forEach { receipt ->
                            Text("${receipt.publisherId}: ${receipt.remoteId} · r${receipt.submittedRevision}", color = AresTextSecondary, fontSize = 11.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProviderEditorDialog(
    kind: ProviderEditorKind,
    notification: NotificationProviderConfig?,
    publisher: NotebookPublisherConfig?,
    credentialProtection: String,
    onDismiss: () -> Unit,
    onSaveNotification: (NotificationProviderConfig, IntegrationCredential?) -> Unit,
    onSavePublisher: (NotebookPublisherConfig, IntegrationCredential?) -> Unit,
    onTest: (NotificationProviderConfig, IntegrationCredential?) -> Unit,
) {
    var id by remember { mutableStateOf(notification?.providerId ?: publisher?.publisherId ?: kind.defaultId()) }
    var name by remember { mutableStateOf(notification?.displayName ?: publisher?.displayName ?: kind.label()) }
    var url by remember { mutableStateOf(notification?.zulip?.siteUrl ?: notification?.webhook?.url ?: publisher?.cmsEndpoint.orEmpty()) }
    var stream by remember { mutableStateOf(notification?.zulip?.stream.orEmpty()) }
    var topic by remember { mutableStateOf(notification?.zulip?.topic ?: "ARES Studio") }
    var principal by remember { mutableStateOf("") }
    var secret by remember { mutableStateOf("") }
    var path by remember { mutableStateOf(publisher?.localDirectory ?: publisher?.driveFolderName ?: "engineering-notebook") }
    var selectedEvents by remember { mutableStateOf(notification?.eventTypes ?: IntegrationEventType.entries.toSet()) }
    var minimumSeverity by remember {
        mutableStateOf(notification?.minimumIssueSeverity ?: com.ares.analytics.shared.models.IntegrationIssueSeverity.WARNING)
    }

    fun credential(): IntegrationCredential? = secret.takeIf(String::isNotBlank)?.let { IntegrationCredential(principal.trim().ifBlank { null }, it) }
    fun notificationConfig(): NotificationProviderConfig = NotificationProviderConfig(
        providerId = id.trim(), displayName = name.trim(), kind = if (kind == ProviderEditorKind.ZULIP) NotificationProviderKind.ZULIP else NotificationProviderKind.WEBHOOK,
        enabled = notification?.enabled ?: true,
        eventTypes = selectedEvents,
        minimumIssueSeverity = minimumSeverity,
        zulip = if (kind == ProviderEditorKind.ZULIP) ZulipNotificationTarget(url.trim(), stream.trim(), topic.trim()) else null,
        webhook = if (kind == ProviderEditorKind.WEBHOOK) WebhookNotificationTarget(url.trim()) else null,
    )
    fun publisherConfig(): NotebookPublisherConfig = NotebookPublisherConfig(
        publisherId = id.trim(), displayName = name.trim(), kind = kind.publisherKind(),
        enabled = publisher?.enabled ?: true,
        localDirectory = path.takeIf { kind == ProviderEditorKind.LOCAL_MARKDOWN }?.trim(),
        driveFolderName = if (kind == ProviderEditorKind.GOOGLE_DRIVE) path.trim() else "engineering-notebook",
        cmsEndpoint = url.takeIf { kind == ProviderEditorKind.CMS }?.trim(),
        requireApproval = kind == ProviderEditorKind.CMS,
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (notification != null || publisher != null) "Configure ${kind.label()}" else "Add ${kind.label()}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(id, { id = it.lowercase().take(128) }, label = { Text("Stable provider ID") }, singleLine = true, enabled = notification == null && publisher == null)
                OutlinedTextField(name, { name = it.take(128) }, label = { Text("Display name") }, singleLine = true)
                if (kind in setOf(ProviderEditorKind.ZULIP, ProviderEditorKind.WEBHOOK, ProviderEditorKind.CMS)) {
                    OutlinedTextField(url, { url = it.take(1_024) }, label = { Text(if (kind == ProviderEditorKind.ZULIP) "Zulip site URL" else "HTTPS endpoint") }, singleLine = true)
                }
                if (kind == ProviderEditorKind.ZULIP) {
                    OutlinedTextField(stream, { stream = it.take(256) }, label = { Text("Stream") }, singleLine = true)
                    OutlinedTextField(topic, { topic = it.take(256) }, label = { Text("Topic") }, singleLine = true)
                    OutlinedTextField(principal, { principal = it.take(1_024) }, label = { Text("Bot email") }, singleLine = true)
                }
                if (kind in setOf(ProviderEditorKind.LOCAL_MARKDOWN, ProviderEditorKind.GOOGLE_DRIVE)) {
                    OutlinedTextField(path, { path = it.take(1_024) }, label = { Text(if (kind == ProviderEditorKind.LOCAL_MARKDOWN) "Export directory" else "Drive folder") }, singleLine = true)
                }
                if (kind in setOf(ProviderEditorKind.ZULIP, ProviderEditorKind.WEBHOOK, ProviderEditorKind.CMS)) {
                    OutlinedTextField(
                        secret,
                        { secret = it.take(8_192) },
                        label = { Text(if (notification != null || publisher != null) "New secret (blank keeps saved value)" else "Secret") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                    )
                    Text("Saved with $credentialProtection and never displayed again.", color = AresTextSecondary, fontSize = 10.sp)
                }
                if (kind in setOf(ProviderEditorKind.ZULIP, ProviderEditorKind.WEBHOOK)) {
                    Text("Events", fontWeight = FontWeight.SemiBold)
                    IntegrationEventType.entries.forEach { type ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = type in selectedEvents,
                                onCheckedChange = { checked ->
                                    selectedEvents = if (checked) selectedEvents + type else selectedEvents - type
                                },
                            )
                            Text(type.name.lowercase().replace('_', ' '), fontSize = 11.sp)
                        }
                    }
                    Text("Minimum issue severity", fontWeight = FontWeight.SemiBold)
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        com.ares.analytics.shared.models.IntegrationIssueSeverity.entries.forEach { severity ->
                            FilterChip(
                                selected = minimumSeverity == severity,
                                onClick = { minimumSeverity = severity },
                                label = { Text(severity.name.lowercase(), fontSize = 10.sp) },
                            )
                        }
                    }
                    OutlinedButton(onClick = { onTest(notificationConfig(), credential()) }) { Text("Test connection") }
                }
            }
        },
        confirmButton = {
            Button(
                enabled = id.isNotBlank() && name.isNotBlank() &&
                    (kind !in setOf(ProviderEditorKind.ZULIP, ProviderEditorKind.WEBHOOK) || selectedEvents.isNotEmpty()),
                onClick = {
                if (kind in setOf(ProviderEditorKind.ZULIP, ProviderEditorKind.WEBHOOK)) onSaveNotification(notificationConfig(), credential())
                else onSavePublisher(publisherConfig(), credential())
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun EmptyCard(title: String, detail: String) {
    AresCard(Modifier.fillMaxWidth(), contentPadding = 24.dp, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, color = AresTextPrimary, fontWeight = FontWeight.Bold)
        Text(detail, color = AresTextSecondary, fontSize = 12.sp)
    }
}

private fun NotificationProviderKind.toEditorKind() = when (this) {
    NotificationProviderKind.ZULIP -> ProviderEditorKind.ZULIP
    NotificationProviderKind.WEBHOOK -> ProviderEditorKind.WEBHOOK
}

private fun NotebookPublisherKind.toEditorKind() = when (this) {
    NotebookPublisherKind.LOCAL_MARKDOWN -> ProviderEditorKind.LOCAL_MARKDOWN
    NotebookPublisherKind.GOOGLE_DRIVE -> ProviderEditorKind.GOOGLE_DRIVE
    NotebookPublisherKind.CMS -> ProviderEditorKind.CMS
}

private fun ProviderEditorKind.publisherKind() = when (this) {
    ProviderEditorKind.LOCAL_MARKDOWN -> NotebookPublisherKind.LOCAL_MARKDOWN
    ProviderEditorKind.GOOGLE_DRIVE -> NotebookPublisherKind.GOOGLE_DRIVE
    ProviderEditorKind.CMS -> NotebookPublisherKind.CMS
    else -> error("Notification provider has no notebook publisher kind")
}

private fun ProviderEditorKind.label() = name.lowercase().replace('_', ' ').replaceFirstChar(Char::uppercase)
private fun ProviderEditorKind.defaultId() = name.lowercase().replace('_', '.')
private val integrationTimeFormatter = DateTimeFormatter.ofPattern("MMM d, HH:mm").withZone(ZoneId.systemDefault())
private fun formatTime(epochMs: Long): String = integrationTimeFormatter.format(Instant.ofEpochMilli(epochMs))
