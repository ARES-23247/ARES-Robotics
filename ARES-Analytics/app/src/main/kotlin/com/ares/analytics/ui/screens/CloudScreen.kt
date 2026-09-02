package com.ares.analytics.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ares.analytics.shared.models.SessionSummary
import com.ares.analytics.ui.components.core.AresEmptyState
import com.ares.analytics.ui.theme.*
import com.ares.analytics.viewmodel.CloudIntent
import com.ares.analytics.viewmodel.CloudViewModel
import com.ares.analytics.viewmodel.RobotRun
import com.ares.analytics.viewmodel.SessionSyncInfo

private sealed interface PendingCloudDeletion {
    data class RobotRuns(val runs: List<RobotRun>) : PendingCloudDeletion
    data class LocalSessions(val sessions: List<SessionSyncInfo>) : PendingCloudDeletion
    data class CloudSessions(val sessions: List<SessionSyncInfo>) : PendingCloudDeletion
}

/**
 * Cloud management screen for pulling raw robot logs over local Wi-Fi, converting to Parquet, and syncing with Google Cloud Run gateway.
 *
 * Facilitates Desktop Pull Architecture: downloads `.jsonl` files from Control Hub `LogManagerServer` (port 5002),
 * parses telemetry into DuckDB, exports Snappy-compressed Parquet files, and executes delta-sync to Google Cloud Storage.
 *
 * @param viewModel [CloudViewModel] managing cloud intent dispatching and state updates.
 * @param teamId Unique identifier for the team.
 * @param seasonId Unique identifier for the season.
 * @param robotId Active workspace robot identifier applied to imported robot logs.
 *
 * @see com.ares.analytics.viewmodel.CloudViewModel
 * @see com.ares.analytics.service.SyncEngineService
 */
@Composable
fun CloudScreen(
    viewModel: CloudViewModel,
    teamId: String,
    seasonId: String,
    robotId: String
) {
    val state by viewModel.state.collectAsState()
    val checkedRobotRuns = remember { mutableStateListOf<String>() }
    val checkedSessions = remember { mutableStateListOf<String>() }
    var pendingDeletion by remember { mutableStateOf<PendingCloudDeletion?>(null) }

    LaunchedEffect(viewModel) { viewModel.onIntent(CloudIntent.RefreshCloudLogs) }
    pendingDeletion?.let { request ->
        CloudDeletionConfirmationDialog(
            request = request,
            onDismiss = { pendingDeletion = null },
            onConfirm = { deleteToken ->
                when (request) {
                    is PendingCloudDeletion.RobotRuns -> {
                        viewModel.onIntent(
                            CloudIntent.DeleteMultipleRobotRuns(
                                request.runs.map { it.runId },
                                requireNotNull(deleteToken)
                            )
                        )
                        checkedRobotRuns.removeAll(request.runs.map { it.runId }.toSet())
                    }
                    is PendingCloudDeletion.LocalSessions -> {
                        viewModel.onIntent(
                            CloudIntent.DeleteMultipleLocalSessions(
                                request.sessions.map { it.summary.sessionId }
                            )
                        )
                        checkedSessions.removeAll(request.sessions.map { it.summary.sessionId }.toSet())
                    }
                    is PendingCloudDeletion.CloudSessions -> {
                        viewModel.onIntent(
                            CloudIntent.DeleteMultipleRemoteSessions(
                                request.sessions.map { it.summary.sessionId to it.summary.teamId }
                            )
                        )
                        checkedSessions.removeAll(request.sessions.map { it.summary.sessionId }.toSet())
                    }
                }
                pendingDeletion = null
            }
        )
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Header / Toolbar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(AresSurface)
                .border(1.dp, AresBorder, RoundedCornerShape(8.dp))
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("Cloud Data Management", color = AresTextPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(4.dp))
                if (state.isAuthenticated) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.CheckCircle, contentDescription = null, tint = AresGreen, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Authenticated with Google", color = AresTextSecondary, fontSize = 12.sp)
                    }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Warning, contentDescription = null, tint = AresAmber, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Not Authenticated - Syncing may fail", color = AresTextSecondary, fontSize = 12.sp)
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { viewModel.onIntent(CloudIntent.RefreshRobotLogs) },
                    colors = ButtonDefaults.buttonColors(containerColor = AresSurfaceElevated)
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = null, tint = AresCyan)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Refresh Robot", color = AresCyan)
                }

                Button(
                    onClick = {
                        viewModel.onIntent(CloudIntent.RefreshCloudLogs)
                        viewModel.onIntent(CloudIntent.PerformDeltaSync(teamId, seasonId))
                    },
                    enabled = !state.isSyncing,
                    colors = ButtonDefaults.buttonColors(
                    containerColor = AresCyan,
                    contentColor = AresOnAccent, disabledContainerColor = AresSurfaceElevated)
                ) {
                    if (state.isSyncing) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), color = AresCyan, strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Default.Sync, contentDescription = null, tint = AresBackground)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Sync Cloud", color = if (state.isSyncing) AresTextTertiary else AresBackground, fontWeight = FontWeight.Bold)
                }
            }
        }

        // Error message if any
        state.errorMessage?.let { errorMessage ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(AresRedDark)
                    .border(1.dp, AresRed, RoundedCornerShape(8.dp))
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(errorMessage, color = AresTextPrimary, fontSize = 14.sp)
                TextButton(onClick = { viewModel.onIntent(CloudIntent.ClearError) }) {
                    Text("Dismiss", color = AresTextPrimary)
                }
            }
        }

        // Dual Lists
        Row(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Left Pane: Robot Logs
            Column(
                modifier = Modifier.weight(1f).fillMaxHeight()
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Robot Logs (Local)", color = AresTextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)

                    if (state.robotRuns.isNotEmpty()) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val selectableRuns = state.robotRuns.filterNot { it.isActive }
                            val allChecked = selectableRuns.isNotEmpty() && selectableRuns.all { it.runId in checkedRobotRuns }
                            Checkbox(
                                checked = allChecked,
                                onCheckedChange = { check ->
                                    if (check) {
                                        checkedRobotRuns.clear()
                                        checkedRobotRuns.addAll(selectableRuns.map { it.runId })
                                    } else {
                                        checkedRobotRuns.clear()
                                    }
                                },
                                colors = CheckboxDefaults.colors(checkedColor = AresCyan)
                            )
                            Text("Select All", color = AresTextSecondary, fontSize = 12.sp)
                        }
                    }
                }

                // Batch Actions
                if (checkedRobotRuns.isNotEmpty()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                viewModel.onIntent(
                                    CloudIntent.UploadMultipleRobotRuns(
                                        checkedRobotRuns.toList(),
                                        teamId,
                                        seasonId,
                                        robotId
                                    )
                                )
                                checkedRobotRuns.clear()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = AresCyan, contentColor = AresOnAccent),
                            modifier = Modifier.height(32.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
                        ) {
                            Text("Import Selected (${checkedRobotRuns.size})", color = AresBackground, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }

                        Button(
                            onClick = {
                                val runs = state.robotRuns.filter { it.runId in checkedRobotRuns && !it.isActive }
                                if (runs.isNotEmpty()) {
                                    pendingDeletion = PendingCloudDeletion.RobotRuns(runs)
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = AresRed, contentColor = AresOnAccent),
                            modifier = Modifier.height(32.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
                        ) {
                            Text("Delete Selected (${checkedRobotRuns.size})", color = AresOnAccent, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(AresSurface)
                        .border(1.dp, AresBorder, RoundedCornerShape(8.dp))
                        .padding(8.dp)
                ) {
                    when {
                        state.isFetchingRobotLogs && state.robotRuns.isEmpty() -> {
                            CircularProgressIndicator(color = AresCyan, modifier = Modifier.align(Alignment.Center))
                        }
                        state.robotRuns.isEmpty() -> {
                            AresEmptyState("No logs found on connected robot.", modifier = Modifier.align(Alignment.Center))
                        }
                        else -> {
                            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                items(state.robotRuns, key = { it.runId }) { run ->
                                    RobotRunRow(
                                        run = run,
                                        isChecked = run.runId in checkedRobotRuns,
                                        onCheckedChange = { check ->
                                            if (check) checkedRobotRuns.add(run.runId) else checkedRobotRuns.remove(run.runId)
                                        },
                                        isUploading = state.isUploadingRobotLog == run.runId || state.isUploadingRobotLog == "BATCH",
                                        onUpload = {
                                            viewModel.onIntent(
                                                CloudIntent.UploadRobotRun(run.runId, teamId, seasonId, robotId)
                                            )
                                        },
                                        onDelete = {
                                            pendingDeletion = PendingCloudDeletion.RobotRuns(listOf(run))
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Right Pane: Database & Google Drive Sync
            Column(modifier = Modifier.weight(1.2f).fillMaxHeight()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Database & Google Drive Sync", color = AresTextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)

                    if (state.sessions.isNotEmpty()) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val allChecked = state.sessions.isNotEmpty() && state.sessions.all { it.summary.sessionId in checkedSessions }
                            Checkbox(
                                checked = allChecked,
                                onCheckedChange = { check ->
                                    if (check) {
                                        checkedSessions.clear()
                                        checkedSessions.addAll(state.sessions.map { it.summary.sessionId })
                                    } else {
                                        checkedSessions.clear()
                                    }
                                },
                                colors = CheckboxDefaults.colors(checkedColor = AresCyan)
                            )
                            Text("Select All", color = AresTextSecondary, fontSize = 12.sp)
                        }
                    }
                }

                // Batch Actions for Sessions
                if (checkedSessions.isNotEmpty()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        val selectedSessionInfos = state.sessions.filter { it.summary.sessionId in checkedSessions }
                        val remoteOnlySummaries = selectedSessionInfos.filter { !it.isLocal && it.isRemote }.map { it.summary }
                        val localSessions = selectedSessionInfos.filter { it.isLocal }
                        val remoteSessions = selectedSessionInfos.filter { it.isRemote }

                        if (remoteOnlySummaries.isNotEmpty()) {
                            Button(
                                onClick = {
                                    viewModel.onIntent(CloudIntent.DownloadMultipleSessions(remoteOnlySummaries))
                                    checkedSessions.clear()
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = AresCyan, contentColor = AresOnAccent),
                                modifier = Modifier.height(32.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
                            ) {
                                Text("Import Selected (${remoteOnlySummaries.size})", color = AresBackground, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }

                        if (localSessions.isNotEmpty()) {
                            Button(
                                onClick = {
                                    pendingDeletion = PendingCloudDeletion.LocalSessions(localSessions)
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = AresRed, contentColor = AresOnAccent),
                                modifier = Modifier.height(32.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
                            ) {
                                Text("Delete Local (${localSessions.size})", color = AresOnAccent, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }

                        if (remoteSessions.isNotEmpty()) {
                            Button(
                                onClick = {
                                    pendingDeletion = PendingCloudDeletion.CloudSessions(remoteSessions)
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = AresRed, contentColor = AresOnAccent),
                                modifier = Modifier.height(32.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
                            ) {
                                Text("Delete Cloud (${remoteSessions.size})", color = AresOnAccent, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(AresSurface)
                        .border(1.dp, AresBorder, RoundedCornerShape(8.dp))
                        .padding(8.dp)
                ) {
                    if (state.sessions.isEmpty()) {
                        AresEmptyState("No sessions found in local DuckDB or Google Drive.", modifier = Modifier.align(Alignment.Center))
                    } else {
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(state.sessions, key = { it.summary.sessionId }) { sessionInfo ->
                                SessionSyncRow(
                                    info = sessionInfo,
                                    isChecked = sessionInfo.summary.sessionId in checkedSessions,
                                    onCheckedChange = { check ->
                                        if (check) checkedSessions.add(sessionInfo.summary.sessionId) else checkedSessions.remove(sessionInfo.summary.sessionId)
                                    },
                                    onUpload = { viewModel.onIntent(CloudIntent.UploadSession(sessionInfo.summary.sessionId)) },
                                    onDownload = { viewModel.onIntent(CloudIntent.DownloadSession(sessionInfo.summary)) },
                                    onDeleteLocal = {
                                        pendingDeletion = PendingCloudDeletion.LocalSessions(listOf(sessionInfo))
                                    },
                                    onDeleteRemote = {
                                        pendingDeletion = PendingCloudDeletion.CloudSessions(listOf(sessionInfo))
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }

        // Console Output
        if (state.uploadLogs.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(150.dp)
                    .background(AresBackground, RoundedCornerShape(8.dp))
                    .border(1.dp, AresBorder, RoundedCornerShape(8.dp))
                    .padding(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Upload Console", color = AresCyan, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 4.dp))

                    IconButton(
                        onClick = {
                            val textToCopy = state.uploadLogs.joinToString("\n")
                            try {
                                val selection = java.awt.datatransfer.StringSelection(textToCopy)
                                java.awt.Toolkit.getDefaultToolkit().systemClipboard.setContents(selection, selection)
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                        },
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.ContentCopy,
                            contentDescription = "Copy Upload Logs",
                            tint = AresTextSecondary,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
                val lazyListState = androidx.compose.foundation.lazy.rememberLazyListState()
                LaunchedEffect(state.uploadLogs.size) {
                    if (state.uploadLogs.isNotEmpty()) {
                        lazyListState.animateScrollToItem(state.uploadLogs.size - 1)
                    }
                }

                LazyColumn(state = lazyListState, modifier = Modifier.fillMaxSize()) {
                    items(state.uploadLogs) { log ->
                        SelectionContainer {
                            Text(log, color = AresTextSecondary, fontSize = 11.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                        }
                    }
                }
            }
        }
    }
}

private data class DeletionDialogCopy(
    val title: String,
    val location: String,
    val itemNames: List<String>,
    val details: String,
    val retainedCopyNote: String,
    val confirmLabel: String
)

@Composable
private fun CloudDeletionConfirmationDialog(
    request: PendingCloudDeletion,
    onDismiss: () -> Unit,
    onConfirm: (String?) -> Unit
) {
    var deleteToken by remember(request) { mutableStateOf("") }
    val copy = when (request) {
        is PendingCloudDeletion.RobotRuns -> {
            val fileCount = request.runs.sumOf { it.files.size }
            val totalBytes = request.runs.sumOf { it.totalSizeBytes }
            DeletionDialogCopy(
                title = if (request.runs.size == 1) "Delete robot log run?" else "Delete ${request.runs.size} robot log runs?",
                location = "Connected robot storage",
                itemNames = request.runs.map { "Run ${it.runId}" },
                details = "$fileCount raw ${if (fileCount == 1) "file" else "files"}, ${formatBytes(totalBytes)}",
                retainedCopyNote = "Any copies already imported into this computer or uploaded to cloud storage are kept.",
                confirmLabel = "Delete from robot"
            )
        }
        is PendingCloudDeletion.LocalSessions -> DeletionDialogCopy(
            title = if (request.sessions.size == 1) "Delete local session?" else "Delete ${request.sessions.size} local sessions?",
            location = "Local DuckDB on this computer",
            itemNames = request.sessions.map { sessionDisplayName(it.summary) },
            details = "${request.sessions.size} ${if (request.sessions.size == 1) "session" else "sessions"}",
            retainedCopyNote = "Cloud copies are kept. Sessions without a cloud copy will no longer be available on this computer.",
            confirmLabel = "Delete local copy"
        )
        is PendingCloudDeletion.CloudSessions -> DeletionDialogCopy(
            title = if (request.sessions.size == 1) "Delete cloud session?" else "Delete ${request.sessions.size} cloud sessions?",
            location = "Google Drive cloud storage",
            itemNames = request.sessions.map { sessionDisplayName(it.summary) },
            details = "${request.sessions.size} ${if (request.sessions.size == 1) "session" else "sessions"}",
            retainedCopyNote = "Local DuckDB copies are kept. Cloud-only sessions will no longer be available on another computer.",
            confirmLabel = "Delete cloud copy"
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Warning, contentDescription = null, tint = AresAmber) },
        title = { Text(copy.title, color = AresTextPrimary, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("DELETION LOCATION", color = AresTextTertiary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    Text(copy.location, color = AresRed, fontWeight = FontWeight.Bold)
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(AresBackground, RoundedCornerShape(6.dp))
                        .border(1.dp, AresBorder, RoundedCornerShape(6.dp))
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    copy.itemNames.take(5).forEach { name ->
                        Text(name, color = AresTextPrimary, fontSize = 12.sp)
                    }
                    if (copy.itemNames.size > 5) {
                        Text("+ ${copy.itemNames.size - 5} more", color = AresTextSecondary, fontSize = 12.sp)
                    }
                    Text(copy.details, color = AresTextSecondary, fontSize = 11.sp)
                }

                Text(copy.retainedCopyNote, color = AresTextSecondary, fontSize = 12.sp)
                if (request is PendingCloudDeletion.RobotRuns) {
                    OutlinedTextField(
                        value = deleteToken,
                        onValueChange = { deleteToken = it },
                        label = { Text("Robot log-delete token") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        supportingText = { Text("Must match the token configured on the robot log server.") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                Text("This deletion cannot be undone from ARES.", color = AresAmber, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(deleteToken.takeIf { request is PendingCloudDeletion.RobotRuns }) },
                enabled = request !is PendingCloudDeletion.RobotRuns || deleteToken.length >= 16,
                colors = ButtonDefaults.buttonColors(containerColor = AresRed, contentColor = AresOnAccent)
            ) {
                Text(copy.confirmLabel, color = AresOnAccent, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) {
                Text("Cancel", color = AresTextSecondary)
            }
        },
        containerColor = AresSurface,
        shape = RoundedCornerShape(12.dp)
    )
}

private fun sessionDisplayName(summary: SessionSummary): String {
    val runName = runCatching {
        java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(java.util.Date(summary.createdAt))
    }.getOrDefault("Unknown date")
    val match = summary.matchNumber?.let { " • Match $it" }.orEmpty()
    return "$runName$match • ${summary.sessionId.take(8)}"
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024L -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    bytes >= 1024L -> "%.1f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}

@Composable
fun RobotRunRow(
    run: com.ares.analytics.viewmodel.RobotRun,
    isChecked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    isUploading: Boolean,
    onUpload: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(AresBackground, RoundedCornerShape(6.dp))
            .border(1.dp, AresBorder, RoundedCornerShape(6.dp))
            .padding(12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.weight(1f)
        ) {
            Checkbox(
                checked = isChecked,
                onCheckedChange = onCheckedChange,
                enabled = !isUploading && !run.isActive,
                colors = CheckboxDefaults.colors(checkedColor = AresCyan)
            )
            Column {
                Text("Run: ${run.runId}", color = AresTextPrimary, fontWeight = FontWeight.Bold)
                val statusText = if (run.isActive) " | ACTIVE RECORDING..." else ""
                Text(
                    "Files: ${run.files.size} | Size: ${run.totalSizeBytes / 1024} KB | ${run.lastModifiedFmt}$statusText",
                    color = if (run.isActive) AresCyan else AresTextSecondary,
                    fontSize = 12.sp
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            IconButton(onClick = onDelete, enabled = !isUploading && !run.isActive) {
                Icon(Icons.Default.Delete, contentDescription = "Delete", tint = AresRed)
            }
            Button(
                onClick = onUpload,
                enabled = !isUploading && !run.isActive,
                colors = ButtonDefaults.buttonColors(containerColor = AresSurfaceElevated)
            ) {
                if (isUploading) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), color = AresCyan, strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Default.CloudUpload, contentDescription = null, tint = AresCyan, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Upload", color = AresCyan)
                }
            }
        }
    }
}

@Composable
fun SessionSyncRow(
    info: com.ares.analytics.viewmodel.SessionSyncInfo,
    isChecked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    onUpload: () -> Unit,
    onDownload: () -> Unit,
    onDeleteLocal: () -> Unit,
    onDeleteRemote: () -> Unit
) {
    val summary = info.summary
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(AresBackground, RoundedCornerShape(6.dp))
            .border(1.dp, AresBorder, RoundedCornerShape(6.dp))
            .padding(12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.weight(1f)
        ) {
            Checkbox(
                checked = isChecked,
                onCheckedChange = onCheckedChange,
                colors = CheckboxDefaults.colors(checkedColor = AresCyan)
            )
            Column(modifier = Modifier.weight(1f)) {
                val formatter = java.text.SimpleDateFormat("yyyyMMdd_HHmmss")
                val runName = try {
                    formatter.format(java.util.Date(summary.createdAt))
                } catch (e: Exception) {
                    "Unknown Date"
                }
                Text("Session: $runName", color = AresTextPrimary, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                val sizeStr = if (summary.fileSizeBytes > 0) " | Size: ${summary.fileSizeBytes / 1024} KB" else ""
                val dateStr = try {
                    java.text.SimpleDateFormat("MMM dd, HH:mm").format(java.util.Date(summary.createdAt))
                } catch (e: Exception) {
                    "unknown"
                }

                Text(
                    "Match: ${summary.matchNumber ?: "None"}$sizeStr | $dateStr",
                    color = AresTextSecondary,
                    fontSize = 11.sp
                )

                Spacer(modifier = Modifier.height(6.dp))
                val badgeColor = when {
                    info.isLocal && info.isRemote -> AresGreen
                    info.isLocal -> AresCyan
                    else -> AresAmber
                }
                val badgeText = when {
                    info.isLocal && info.isRemote -> "Synced"
                    info.isLocal -> "Local Only (DuckDB)"
                    else -> "Cloud Only (Drive)"
                }
                Text(
                    text = badgeText,
                    color = badgeColor,
                    fontWeight = FontWeight.Bold,
                    fontSize = 10.sp,
                    modifier = Modifier
                        .background(badgeColor.copy(alpha = 0.1f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (!info.isLocal && info.isRemote) {
                Button(
                    onClick = onDownload,
                    colors = ButtonDefaults.buttonColors(containerColor = AresSurfaceElevated),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    modifier = Modifier.height(28.dp)
                ) {
                    Text("Import", color = AresCyan, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
                IconButton(onClick = onDeleteRemote, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Default.Delete, contentDescription = "Delete Cloud", tint = AresRed, modifier = Modifier.size(16.dp))
                }
            }

            if (info.isLocal && !info.isRemote) {
                Button(
                    onClick = onUpload,
                    colors = ButtonDefaults.buttonColors(containerColor = AresSurfaceElevated),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    modifier = Modifier.height(28.dp)
                ) {
                    Text("Upload", color = AresCyan, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
                IconButton(onClick = onDeleteLocal, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Default.Delete, contentDescription = "Delete Local", tint = AresRed, modifier = Modifier.size(16.dp))
                }
            }

            if (info.isLocal && info.isRemote) {
                TextButton(
                    onClick = onDeleteLocal,
                    contentPadding = PaddingValues(horizontal = 4.dp),
                    modifier = Modifier.height(28.dp)
                ) {
                    Text("Del Local", color = AresRed, fontSize = 10.sp)
                }
                TextButton(
                    onClick = onDeleteRemote,
                    contentPadding = PaddingValues(horizontal = 4.dp),
                    modifier = Modifier.height(28.dp)
                ) {
                    Text("Del Cloud", color = AresRed, fontSize = 10.sp)
                }
            }
        }
    }
}
