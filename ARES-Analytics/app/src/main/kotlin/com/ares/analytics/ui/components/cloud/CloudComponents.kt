package com.ares.analytics.ui.components.cloud

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ares.analytics.shared.models.SessionSummary
import com.ares.analytics.ui.components.core.AresDialog
import com.ares.analytics.ui.components.core.AresDialogVariant
import com.ares.analytics.ui.components.forms.AresTextField
import com.ares.analytics.ui.theme.*
import com.ares.analytics.viewmodel.RobotRun
import com.ares.analytics.viewmodel.SessionSyncInfo

sealed interface PendingCloudDeletion {
    data class RobotRuns(val runs: List<RobotRun>) : PendingCloudDeletion
    data class LocalSessions(val sessions: List<SessionSyncInfo>) : PendingCloudDeletion
    data class CloudSessions(val sessions: List<SessionSyncInfo>) : PendingCloudDeletion
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
fun CloudDeletionConfirmationDialog(
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
                title = if (request.runs.size == 1) "Delete robot log run?" else "Delete  robot log runs?",
                location = "Connected robot storage",
                itemNames = request.runs.map { "Run " },
                details = " raw , ",
                retainedCopyNote = "Any copies already imported into this computer or uploaded to cloud storage are kept.",
                confirmLabel = "Delete from robot"
            )
        }
        is PendingCloudDeletion.LocalSessions -> DeletionDialogCopy(
            title = if (request.sessions.size == 1) "Delete local session?" else "Delete  local sessions?",
            location = "Local DuckDB on this computer",
            itemNames = request.sessions.map { sessionDisplayName(it.summary) },
            details = " ",
            retainedCopyNote = "Cloud copies are kept. Sessions without a cloud copy will no longer be available on this computer.",
            confirmLabel = "Delete local copy"
        )
        is PendingCloudDeletion.CloudSessions -> DeletionDialogCopy(
            title = if (request.sessions.size == 1) "Delete cloud session?" else "Delete  cloud sessions?",
            location = "Google Drive cloud storage",
            itemNames = request.sessions.map { sessionDisplayName(it.summary) },
            details = " ",
            retainedCopyNote = "Local DuckDB copies are kept. Cloud-only sessions will no longer be available on another computer.",
            confirmLabel = "Delete cloud copy"
        )
    }

    AresDialog(
        title = copy.title,
        icon = Icons.Default.Warning,
        variant = AresDialogVariant.DESTRUCTIVE,
        onDismiss = onDismiss,
        confirmText = copy.confirmLabel,
        isConfirmEnabled = request !is PendingCloudDeletion.RobotRuns || deleteToken.length >= 16,
        onConfirm = { onConfirm(deleteToken.takeIf { request is PendingCloudDeletion.RobotRuns }) }
    ) {
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
                    Text("+  more", color = AresTextSecondary, fontSize = 12.sp)
                }
                Text(copy.details, color = AresTextSecondary, fontSize = 11.sp)
            }

            Text(copy.retainedCopyNote, color = AresTextSecondary, fontSize = 12.sp)
            if (request is PendingCloudDeletion.RobotRuns) {
                AresTextField(
                    value = deleteToken,
                    onValueChange = { deleteToken = it },
                    label = "Robot log-delete token",
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    supportingText = { Text("Must match the token configured on the robot log server.") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
            Text("This deletion cannot be undone from ARES.", color = AresAmber, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
    }
}

private fun sessionDisplayName(summary: SessionSummary): String {
    val runName = runCatching {
        java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(java.util.Date(summary.createdAt))
    }.getOrDefault("Unknown date")
    val match = summary.matchNumber?.let { " • Match " }.orEmpty()
    return " • "
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024L -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    bytes >= 1024L -> "%.1f KB".format(bytes / 1024.0)
    else -> " B"
}

@Composable
fun RobotRunRow(
    run: RobotRun,
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
                Text("Run: ", color = AresTextPrimary, fontWeight = FontWeight.Bold)
                val statusText = if (run.isActive) " | ACTIVE RECORDING..." else ""
                Text(
                    "Files:  | Size:  KB | ",
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
    info: SessionSyncInfo,
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
                Text("Session: ", color = AresTextPrimary, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                val sizeStr = if (summary.fileSizeBytes > 0) " | Size:  KB" else ""
                val dateStr = try {
                    java.text.SimpleDateFormat("MMM dd, HH:mm").format(java.util.Date(summary.createdAt))
                } catch (e: Exception) {
                    "unknown"
                }

                Text(
                    "Match:  | ",
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
