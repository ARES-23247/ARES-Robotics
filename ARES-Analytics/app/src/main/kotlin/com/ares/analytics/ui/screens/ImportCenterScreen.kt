package com.ares.analytics.ui.screens

import com.ares.analytics.ui.theme.AresOnAccent

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ares.analytics.service.ImportArchiveEntry
import com.ares.analytics.ui.theme.AresAmber
import com.ares.analytics.ui.theme.AresBackground
import com.ares.analytics.ui.theme.AresBorder
import com.ares.analytics.ui.theme.AresCyan
import com.ares.analytics.ui.theme.AresError
import com.ares.analytics.ui.theme.AresGreen
import com.ares.analytics.ui.theme.AresSurface
import com.ares.analytics.ui.theme.AresSurfaceElevated
import com.ares.analytics.ui.theme.AresTextPrimary
import com.ares.analytics.ui.theme.AresTextSecondary
import com.ares.analytics.ui.theme.AresTextTertiary
import com.ares.analytics.ui.util.AresFormatters
import com.ares.analytics.ui.util.DesktopFileChoosers
import com.ares.analytics.viewmodel.ImportCenterIntent
import com.ares.analytics.viewmodel.ImportCenterViewModel
import java.io.File

@Composable
fun ImportCenterScreen(
    viewModel: ImportCenterViewModel,
    projectPath: String,
    onOpenRunHistory: () -> Unit = {},
    onOpenGuidedAnalysis: () -> Unit = {},
    onOpenHelp: () -> Unit = {}
) {
    val state by viewModel.state.collectAsState()

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("Log Import Center", color = AresTextPrimary, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text(
                    "Evidence for every automatic import, with recoverable quarantine failures",
                    color = AresTextSecondary,
                    fontSize = 12.sp
                )
            }
            IconButton(
                onClick = { viewModel.onIntent(ImportCenterIntent.Refresh) },
                enabled = !state.isLoading
            ) {
                Icon(Icons.Default.Refresh, contentDescription = "Refresh import reports", tint = AresCyan)
            }
        }

        Surface(
            modifier = Modifier.fillMaxWidth().border(1.dp, AresCyan.copy(alpha = 0.65f), RoundedCornerShape(10.dp)),
            color = AresCyan.copy(alpha = 0.08f),
            shape = RoundedCornerShape(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(14.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Default.FolderOpen, contentDescription = null, tint = AresCyan, modifier = Modifier.size(28.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Import a completed run", color = AresTextPrimary, fontWeight = FontWeight.Bold)
                    Text(
                        "Choose robot or simulator logs. ARES verifies and archives a copy inside this workspace, then adds one run to local history. Your originals are never changed.",
                        color = AresTextSecondary,
                        fontSize = 12.sp,
                    )
                    Text(
                        "CSV/CSV.GZ · JSONL · Parquet · WPILOG/WPILOGXZ · DSLOG · RLOG · REVLOG · Hoot · Road Runner LOG",
                        color = AresTextTertiary,
                        fontSize = 10.sp,
                    )
                }
                Button(
                    onClick = {
                        val selected = chooseCompletedRobotLogs(projectPath)
                        if (selected.isNotEmpty()) viewModel.onIntent(ImportCenterIntent.ImportFiles(selected))
                    },
                    enabled = !state.isImporting && state.retryingId == null,
                    colors = ButtonDefaults.buttonColors(containerColor = AresCyan, contentColor = AresOnAccent),
                ) {
                    Icon(Icons.Default.FolderOpen, contentDescription = null)
                    Spacer(Modifier.width(7.dp))
                    Text("Choose log files", fontWeight = FontWeight.Bold)
                }
            }
        }

        if (state.isImporting) {
            Surface(
                modifier = Modifier.fillMaxWidth().border(1.dp, AresCyan.copy(alpha = 0.55f), RoundedCornerShape(8.dp)),
                color = AresSurface,
                shape = RoundedCornerShape(8.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    CircularProgressIndicator(color = AresCyan, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Import in progress", color = AresTextPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        Text(state.importPhase.orEmpty(), color = AresTextSecondary, fontSize = 11.sp)
                    }
                    TextButton(onClick = { viewModel.onIntent(ImportCenterIntent.CancelImport) }) {
                        Icon(Icons.Default.Cancel, contentDescription = null, tint = AresAmber, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(5.dp))
                        Text("Cancel safely", color = AresAmber)
                    }
                }
            }
        }

        state.lastImport?.let { outcome ->
            Surface(
                modifier = Modifier.fillMaxWidth().border(1.dp, AresGreen.copy(alpha = 0.65f), RoundedCornerShape(10.dp)),
                color = AresGreen.copy(alpha = 0.09f),
                shape = RoundedCornerShape(10.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(Icons.Default.CheckCircle, contentDescription = null, tint = AresGreen)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            if (outcome.wasAlreadyImported) "Existing run found—no duplicate created" else "Run ready to review",
                            color = AresTextPrimary,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            "${outcome.acceptedRecords} records · ${outcome.detectedTopicCount} topics · session ${outcome.session.sessionId.take(8)}",
                            color = AresTextSecondary,
                            fontSize = 11.sp,
                        )
                    }
                    TextButton(onClick = onOpenRunHistory) {
                        Icon(Icons.Default.FolderOpen, contentDescription = null, tint = AresCyan, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(5.dp))
                        Text("Run History", color = AresCyan)
                    }
                    Button(
                        onClick = onOpenGuidedAnalysis,
                        colors = ButtonDefaults.buttonColors(containerColor = AresCyan, contentColor = AresOnAccent),
                    ) {
                        Icon(Icons.Default.Analytics, contentDescription = null)
                        Spacer(Modifier.width(5.dp))
                        Text("Review this run", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        if (!state.isLoading && state.snapshot.imported.isEmpty() && state.snapshot.quarantined.isEmpty()) {
            Surface(
                modifier = Modifier.fillMaxWidth().border(1.dp, AresCyan.copy(alpha = 0.55f), RoundedCornerShape(10.dp)),
                color = AresCyan.copy(alpha = 0.08f),
                shape = RoundedCornerShape(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(14.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.School, contentDescription = null, tint = AresCyan, modifier = Modifier.size(26.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Bring in your first run", color = AresTextPrimary, fontWeight = FontWeight.Bold)
                        Text(
                            "Use Choose log files above for a guided import. You can also place a completed log in ${File(projectPath, "logs").path}; " +
                                "ARES waits until it stops changing and then imports it automatically. Connected robots are checked when available.",
                            color = AresTextSecondary,
                            fontSize = 12.sp
                        )
                        Text(
                            "Successful files move to ${File("logs", "imported").path}; failures stay recoverable in ${File("logs", "quarantine").path}.",
                            color = AresTextTertiary,
                            fontSize = 11.sp
                        )
                    }
                    TextButton(onClick = onOpenHelp) { Text("Show steps", color = AresCyan) }
                }
            }
        }

        state.message?.let {
            ImportNotice(it, AresGreen) { viewModel.onIntent(ImportCenterIntent.ClearNotice) }
        }
        state.error?.let {
            ImportNotice(it, AresError) { viewModel.onIntent(ImportCenterIntent.ClearNotice) }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            ImportSummaryCard(
                label = "Imported",
                value = state.snapshot.imported.size,
                color = AresGreen,
                modifier = Modifier.weight(1f)
            )
            ImportSummaryCard(
                label = "Quarantined",
                value = state.snapshot.quarantined.size,
                color = if (state.snapshot.quarantined.isEmpty()) AresTextTertiary else AresAmber,
                modifier = Modifier.weight(1f)
            )
            ImportSummaryCard(
                label = "Unreadable reports",
                value = state.snapshot.unreadableCount,
                color = if (state.snapshot.unreadableCount == 0) AresTextTertiary else AresError,
                modifier = Modifier.weight(1f)
            )
        }

        if (state.isLoading && state.snapshot.imported.isEmpty() && state.snapshot.quarantined.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = AresCyan)
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth().weight(1f),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                ImportEvidencePane(
                    title = "Successful imports",
                    emptyMessage = "No automatic imports have completed yet.",
                    entries = state.snapshot.imported,
                    accent = AresGreen,
                    retryingId = state.retryingId,
                    onRetry = null,
                    modifier = Modifier.weight(1f).fillMaxHeight()
                )
                ImportEvidencePane(
                    title = "Quarantine",
                    emptyMessage = "No logs are quarantined.",
                    entries = state.snapshot.quarantined,
                    accent = AresAmber,
                    retryingId = state.retryingId,
                    onRetry = { viewModel.onIntent(ImportCenterIntent.Retry(it)) },
                    modifier = Modifier.weight(1f).fillMaxHeight()
                )
            }
        }
    }
}

private fun chooseCompletedRobotLogs(projectPath: String): List<File> {
    // A modal Swing chooser lives on the same AWT event thread as Compose and is not reachable
    // from the loopback Skia test surface. Keep deterministic desktop journeys in the real app by
    // accepting an explicit selection only when the opt-in test-control server is also enabled.
    val controlledSelection = controlledLogSelection(
        testControlPort = System.getenv("ARES_ANALYTICS_TEST_CONTROL_PORT"),
        encodedSelection = System.getenv("ARES_ANALYTICS_TEST_LOG_SELECTION"),
    )
    if (controlledSelection.isNotEmpty()) return controlledSelection

    val initialDirectory = File(projectPath).takeIf(File::isDirectory)
    return DesktopFileChoosers.chooseOpenFiles(
        dialogTitle = "Choose completed robot or simulator logs",
        initialDirectory = initialDirectory,
        filterDescription = "ARES-supported logs",
        "csv", "gz", "jsonl", "parquet", "wpilog", "wpilogxz", "dslog", "dsevents",
        "rlog", "revlog", "hoot", "log",
    )
}

/** Test-only selection is impossible unless the opt-in loopback UI control is also active. */
internal fun controlledLogSelection(
    testControlPort: String?,
    encodedSelection: String?,
): List<File> = encodedSelection
        ?.takeIf { testControlPort.orEmpty().isNotBlank() }
        ?.split(File.pathSeparatorChar)
        ?.map(String::trim)
        ?.filter(String::isNotEmpty)
        ?.map(::File)
        .orEmpty()

@Composable
private fun ImportNotice(message: String, color: Color, onDismiss: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(color.copy(alpha = 0.12f), RoundedCornerShape(8.dp))
            .border(1.dp, color.copy(alpha = 0.55f), RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(message, color = AresTextPrimary, fontSize = 12.sp)
        TextButton(onClick = onDismiss) { Text("Dismiss", color = color) }
    }
}

@Composable
private fun ImportSummaryCard(label: String, value: Int, color: Color, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.border(1.dp, AresBorder, RoundedCornerShape(10.dp)),
        color = AresSurface,
        shape = RoundedCornerShape(10.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(value.toString(), color = color, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Text(label, color = AresTextSecondary, fontSize = 11.sp)
        }
    }
}

@Composable
private fun ImportEvidencePane(
    title: String,
    emptyMessage: String,
    entries: List<ImportArchiveEntry>,
    accent: Color,
    retryingId: String?,
    onRetry: ((ImportArchiveEntry) -> Unit)?,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.border(1.dp, AresBorder, RoundedCornerShape(10.dp)),
        color = AresSurface,
        shape = RoundedCornerShape(10.dp)
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (onRetry == null) Icons.Default.CheckCircle else Icons.Default.WarningAmber,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(title, color = AresTextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(6.dp))
                Text("${entries.size}", color = accent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(8.dp))
            HorizontalDivider(color = AresBorder)
            Spacer(Modifier.height(8.dp))

            if (entries.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(emptyMessage, color = AresTextTertiary, fontSize = 12.sp)
                }
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(entries, key = ImportArchiveEntry::id) { entry ->
                        ImportEvidenceRow(
                            entry = entry,
                            accent = accent,
                            isRetrying = retryingId == entry.id,
                            retryEnabled = retryingId == null,
                            onRetry = onRetry
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ImportEvidenceRow(
    entry: ImportArchiveEntry,
    accent: Color,
    isRetrying: Boolean,
    retryEnabled: Boolean,
    onRetry: ((ImportArchiveEntry) -> Unit)?
) {
    val report = entry.report
    Surface(
        color = AresSurfaceElevated,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth().border(1.dp, AresBorder, RoundedCornerShape(8.dp))
    ) {
        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (report == null) Icons.Default.ErrorOutline else Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = if (report == null) AresError else accent,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        report?.sourceName ?: entry.logPath.substringAfterLast(FileSeparator),
                        color = AresTextPrimary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                onRetry?.let { retry ->
                    Button(
                        onClick = { retry(entry) },
                        enabled = retryEnabled,
                        colors = ButtonDefaults.buttonColors(containerColor = AresCyan, contentColor = AresOnAccent),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                        modifier = Modifier.height(30.dp)
                    ) {
                        if (isRetrying) {
                            CircularProgressIndicator(
                                color = AresBackground,
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(14.dp)
                            )
                        } else {
                            Icon(Icons.Default.Replay, contentDescription = null, tint = AresBackground, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Retry", color = AresBackground, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            if (report == null) {
                Text(entry.readError ?: "Unreadable import report", color = AresError, fontSize = 11.sp)
            } else {
                Text(
                    "${report.decoder.uppercase()}  •  ${report.acceptedRecords} records  •  ${report.detectedTopics.size} topics",
                    color = AresTextSecondary,
                    fontSize = 10.sp
                )
                report.error?.let {
                    Text(friendlyImportError(it), color = AresError, fontSize = 11.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
                report.warnings.firstOrNull()?.let {
                    Text(it, color = AresAmber, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        report.sourceSha256.take(12),
                        color = AresTextTertiary,
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(formatReportTime(entry.lastModifiedMs), color = AresTextTertiary, fontSize = 9.sp)
                }
            }
        }
    }
}

private fun formatReportTime(timestampMs: Long): String = runCatching {
    AresFormatters.formatDateTimeShort(timestampMs)
}.getOrDefault("Unknown time")

private val FileSeparator: Char = java.io.File.separatorChar

private fun friendlyImportError(error: String): String = when {
    error.contains("out of memory", ignoreCase = true) || error.contains("could not allocate", ignoreCase = true) ->
        "This log exceeded the local database memory budget. Close memory-heavy programs, then retry; if it repeats, import a shorter recording."
    else -> error
}
