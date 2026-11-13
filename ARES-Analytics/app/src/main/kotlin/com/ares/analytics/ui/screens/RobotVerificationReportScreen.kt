package com.ares.analytics.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ares.analytics.service.verification.RobotVerificationReport
import com.ares.analytics.service.verification.VerificationLayer
import com.ares.analytics.service.verification.VerificationReportItem
import com.ares.analytics.service.verification.VerificationResultStatus
import com.ares.analytics.ui.components.robotstudio.RobotStudioSelection
import com.ares.analytics.ui.theme.AresAmber
import com.ares.analytics.ui.theme.AresBorder
import com.ares.analytics.ui.theme.AresCyan
import com.ares.analytics.ui.theme.AresGreen
import com.ares.analytics.ui.theme.AresOnAccent
import com.ares.analytics.ui.theme.AresRed
import com.ares.analytics.ui.theme.AresSurface
import com.ares.analytics.ui.theme.AresSurfaceElevated
import com.ares.analytics.ui.theme.AresTextPrimary
import com.ares.analytics.ui.theme.AresTextSecondary

enum class VerificationStatusFilter(val label: String) {
    ALL("All"),
    FAILED("Failed"),
    BLOCKED("Blocked"),
    NOT_RUN("Not measured"),
    PASSED("Passed"),
}

data class VerificationQuickJumpTarget(
    val label: String,
    val selection: RobotStudioSelection,
)

fun filterVerificationItems(
    items: List<VerificationReportItem>,
    statusFilter: VerificationStatusFilter,
    searchQuery: String,
): List<VerificationReportItem> {
    val query = searchQuery.trim().lowercase()
    return items.filter { item ->
        val matchesStatus = when (statusFilter) {
            VerificationStatusFilter.ALL -> true
            VerificationStatusFilter.FAILED -> item.status == VerificationResultStatus.FAILED
            VerificationStatusFilter.BLOCKED -> item.status == VerificationResultStatus.BLOCKED
            VerificationStatusFilter.NOT_RUN -> item.status == VerificationResultStatus.NOT_RUN
            VerificationStatusFilter.PASSED -> item.status == VerificationResultStatus.PASSED
        }
        val matchesSearch = query.isEmpty() ||
            item.title.lowercase().contains(query) ||
            item.explanation.lowercase().contains(query) ||
            item.id.lowercase().contains(query) ||
            item.source.lowercase().contains(query) ||
            item.advancedDetails.lowercase().contains(query)
        matchesStatus && matchesSearch
    }
}

fun resolveVerificationQuickJump(item: VerificationReportItem): VerificationQuickJumpTarget? {
    val id = item.id.lowercase()
    val source = item.source.replace('\\', '/')
    return when {
        id == "project.identity.configuration" || source.endsWith(".ares/project.json") ->
            VerificationQuickJumpTarget("Open Project Identity", RobotStudioSelection.Identity)
        id.startsWith("drivetrain.") || source.contains(".ares/drivetrains/") ->
            VerificationQuickJumpTarget("Open Drivetrain", RobotStudioSelection.Drivetrain)
        id.startsWith("superstructure.") || source.contains(".ares/superstructures/") ->
            VerificationQuickJumpTarget("Open Superstructure", RobotStudioSelection.Superstructure)
        id == "autonomous.configuration" || source.contains(".ares/routines") || source.contains("autonomous-catalog.json") ->
            VerificationQuickJumpTarget("Open Autonomous", RobotStudioSelection.Autonomous)
        source.contains(".ares/subsystems/") -> {
            val docId = source.substringAfterLast('/').removeSuffix(".aressubsystem")
            VerificationQuickJumpTarget("Open Subsystem", RobotStudioSelection.Subsystem(docId))
        }
        id.startsWith("subsystem.") -> {
            val docId = item.id.removePrefix("subsystem.").substringBefore('.')
            VerificationQuickJumpTarget("Open Subsystem", RobotStudioSelection.Subsystem(docId))
        }
        item.layer == VerificationLayer.PHYSICAL_VALIDATION || id.startsWith("project.physical-") ->
            VerificationQuickJumpTarget("Open Port Map & Review", RobotStudioSelection.PortMap)
        item.layer == VerificationLayer.GENERATED_BEHAVIOR && !id.startsWith("project.") && id.contains('.') -> {
            val docId = item.id.substringBefore('.')
            VerificationQuickJumpTarget("Open Subsystem", RobotStudioSelection.Subsystem(docId))
        }
        else -> null
    }
}

fun formatVerificationReportMarkdown(report: RobotVerificationReport): String = buildString {
    appendLine("# ARES Robot Verification Report")
    appendLine("- **League**: ${report.league.name}")
    appendLine("- **Summary**: ${report.passedCount} passed · ${report.failedCount} failed · ${report.blockedCount} blocked · ${report.notRunCount} not measured")
    appendLine("- **Ready for Physical Validation**: ${if (report.readyForPhysicalValidation) "Yes" else "No"}")
    appendLine("- **Run ID**: `${report.provenance.runId}` (exit `${report.provenance.buildExitCode}`)")
    appendLine("- **Content Hash**: `${report.provenance.canonicalContentHash}`")
    appendLine()
    appendLine("| Status | Layer | Check | Evidence Level | Explanation |")
    appendLine("|---|---|---|---|---|")
    report.items.forEach { item ->
        val cleanExplanation = item.explanation.replace("|", "\\|").replace("\n", " ")
        appendLine("| ${item.status.symbol()} ${item.status.label()} | ${item.layer.studentLabel()} | ${item.title} | ${item.evidenceLevel.label} | $cleanExplanation |")
    }
}.trimEnd()

/** One student-facing view over generated, platform, simulator, build, and physical evidence. */
@Composable
fun RobotVerificationReportScreen(
    report: RobotVerificationReport?,
    isRunning: Boolean,
    onRunVerification: () -> Unit,
    onNavigateToSelection: ((RobotStudioSelection) -> Unit)? = null,
    onLogToNotebook: ((RobotVerificationReport) -> Unit)? = null,
) {
    var advanced by remember { mutableStateOf(false) }
    var statusFilter by remember { mutableStateOf(VerificationStatusFilter.ALL) }
    var searchQuery by remember { mutableStateOf("") }
    @Suppress("DEPRECATION") val clipboard = LocalClipboardManager.current

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Verification", color = AresTextPrimary, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    Text(
                        "One report for Robot Builder checks, independent platform tests, simulation, build, and physical readiness.",
                        color = AresTextSecondary,
                        fontSize = 12.sp,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (report != null) {
                        if (onLogToNotebook != null) {
                            OutlinedButton(
                                onClick = { onLogToNotebook(report) },
                            ) {
                                Icon(Icons.Default.Book, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Log to Notebook")
                            }
                        }
                        OutlinedButton(
                            onClick = { clipboard.setText(AnnotatedString(formatVerificationReportMarkdown(report))) },
                        ) {
                            Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Copy Markdown Report")
                        }
                    }
                    Button(
                        onClick = onRunVerification,
                        enabled = !isRunning,
                        colors = ButtonDefaults.buttonColors(containerColor = AresCyan, contentColor = AresOnAccent),
                    ) {
                        Text(if (isRunning) "Verification running…" else "Run Verify & build", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = AresSurface),
                border = BorderStroke(1.dp, AresBorder),
                shape = RoundedCornerShape(10.dp),
            ) {
                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("How to read this report", color = AresTextPrimary, fontWeight = FontWeight.Bold)
                    Text(
                        "Generated checks test the robot definition you built. Platform checks test ARES itself. " +
                            "Simulation evidence never claims that wiring or physical hardware was tested.",
                        color = AresTextSecondary,
                        fontSize = 12.sp,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Switch(checked = advanced, onCheckedChange = { advanced = it })
                        Column {
                            Text("Advanced details", color = AresTextPrimary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            Text("Show test identities, result files, and process evidence.", color = AresTextSecondary, fontSize = 10.sp)
                        }
                    }
                }
            }
        }

        if (report == null) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = AresSurfaceElevated),
                    border = BorderStroke(1.dp, AresBorder),
                ) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("No verification run yet", color = AresTextPrimary, fontWeight = FontWeight.Bold)
                        Text(
                            "Run Verify & build. ARES will generate disposable tests from the robot documents, run independent project tests, and compile the package without deploying.",
                            color = AresTextSecondary,
                            fontSize = 12.sp,
                        )
                    }
                }
            }
        } else {
            val filteredItems = filterVerificationItems(report.items, statusFilter, searchQuery)
            item {
                VerificationSummary(report)
            }
            item {
                VerificationFilterBar(
                    report = report,
                    statusFilter = statusFilter,
                    onStatusFilterChange = { statusFilter = it },
                    searchQuery = searchQuery,
                    onSearchQueryChange = { searchQuery = it },
                    filteredCount = filteredItems.size,
                )
            }
            if (advanced) {
                item { VerificationProvenance(report) }
            }
            if (filteredItems.isEmpty()) {
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = AresSurfaceElevated),
                        border = BorderStroke(1.dp, AresBorder),
                    ) {
                        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("No verification items match the current filter", color = AresTextPrimary, fontWeight = FontWeight.Bold)
                            Text("Select 'All' or clear the search query to see all ${report.items.size} check(s).", color = AresTextSecondary, fontSize = 12.sp)
                        }
                    }
                }
            }
            VerificationLayer.entries.forEach { layer ->
                val layerItems = filteredItems.filter { it.layer == layer }
                if (layerItems.isNotEmpty()) {
                    item {
                        Text("${layer.studentLabel()} (${layerItems.size})", color = AresTextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    }
                    items(layerItems, key = { it.id }) { result ->
                        VerificationResultCard(
                            item = result,
                            advanced = advanced,
                            onNavigateToSelection = onNavigateToSelection,
                        )
                    }
                }
            }
        }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun VerificationFilterBar(
    report: RobotVerificationReport,
    statusFilter: VerificationStatusFilter,
    onStatusFilterChange: (VerificationStatusFilter) -> Unit,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    filteredCount: Int,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = AresSurface),
        border = BorderStroke(1.dp, AresBorder),
        shape = RoundedCornerShape(10.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = onSearchQueryChange,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text("Search checks by title, explanation, source file, or test ID…", fontSize = 12.sp) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(16.dp)) },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { onSearchQueryChange("") }) {
                            Icon(Icons.Default.Clear, contentDescription = "Clear search", modifier = Modifier.size(16.dp))
                        }
                    }
                },
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                VerificationStatusFilter.entries.forEach { filter ->
                    val count = when (filter) {
                        VerificationStatusFilter.ALL -> report.items.size
                        VerificationStatusFilter.FAILED -> report.failedCount
                        VerificationStatusFilter.BLOCKED -> report.blockedCount
                        VerificationStatusFilter.NOT_RUN -> report.notRunCount
                        VerificationStatusFilter.PASSED -> report.passedCount
                    }
                    val selected = statusFilter == filter
                    FilterChip(
                        selected = selected,
                        onClick = { onStatusFilterChange(filter) },
                        modifier = Modifier.height(30.dp),
                        label = {
                            Text(
                                "${filter.label} ($count)",
                                fontSize = 11.sp,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                            )
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = AresCyan,
                            selectedLabelColor = AresOnAccent,
                            containerColor = AresSurfaceElevated,
                            labelColor = AresTextPrimary,
                        ),
                    )
                }
                Text(
                    "Showing $filteredCount of ${report.items.size}",
                    color = AresTextSecondary,
                    fontSize = 11.sp,
                    modifier = Modifier.align(Alignment.CenterVertically).padding(start = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun VerificationProvenance(report: RobotVerificationReport) {
    val run = report.provenance
    Card(
        colors = CardDefaults.cardColors(containerColor = AresSurface),
        border = BorderStroke(1.dp, AresBorder),
        shape = RoundedCornerShape(8.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Run evidence", color = AresTextPrimary, fontWeight = FontWeight.Bold)
            Text("Run: ${run.runId}", color = AresTextSecondary, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
            Text(
                "Canonical content: ${run.canonicalContentHash}",
                color = AresTextSecondary,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
            )
            Text(
                "ARES ${run.aresVersion} • generator ${run.generatorVersion} • Studio ${run.studioVersion}",
                color = AresTextSecondary,
                fontSize = 10.sp,
            )
            run.gitRevision?.let {
                Text("Git revision: $it", color = AresTextSecondary, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
            }
            Text(
                "${run.startedAt} → ${run.finishedAt} • exit ${run.buildExitCode}",
                color = AresTextSecondary,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
            )
            Text(run.command.joinToString(" "), color = AresTextSecondary, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
        }
    }
}

@Composable
private fun VerificationSummary(report: RobotVerificationReport) {
    Card(
        colors = CardDefaults.cardColors(containerColor = AresSurfaceElevated),
        border = BorderStroke(1.dp, AresBorder),
        shape = RoundedCornerShape(10.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                if (report.failedCount == 0 && report.blockedCount == 0) "Verification completed" else "Verification needs attention",
                color = AresTextPrimary,
                fontWeight = FontWeight.Bold,
            )
            Text(
                "${report.passedCount} passed • ${report.failedCount} failed • ${report.blockedCount} blocked • ${report.notRunCount} not measured",
                color = AresTextSecondary,
                fontSize = 12.sp,
            )
            Text(
                if (report.readyForPhysicalValidation) {
                    "Ready for a supervised physical-validation checklist; no physical validation is claimed."
                } else {
                    "Resolve failed, blocked, or unmeasured requirements before physical testing."
                },
                color = if (report.readyForPhysicalValidation) AresGreen else AresAmber,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun VerificationResultCard(
    item: VerificationReportItem,
    advanced: Boolean,
    onNavigateToSelection: ((RobotStudioSelection) -> Unit)? = null,
) {
    val statusColor = item.status.color()
    val quickJump = remember(item) { resolveVerificationQuickJump(item) }
    Card(
        colors = CardDefaults.cardColors(containerColor = AresSurface),
        border = BorderStroke(1.dp, statusColor.copy(alpha = 0.65f)),
        shape = RoundedCornerShape(8.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Text(item.title, color = AresTextPrimary, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Text(
                    "${item.status.symbol()} ${item.status.label()}",
                    color = statusColor,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
            Text(item.explanation, color = AresTextSecondary, fontSize = 11.sp)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(item.evidenceLevel.label, color = AresCyan, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                if (onNavigateToSelection != null && quickJump != null) {
                    OutlinedButton(
                        onClick = { onNavigateToSelection(quickJump.selection) },
                        modifier = Modifier.height(28.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                    ) {
                        Text("${quickJump.label} →", fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
            if (advanced) {
                HorizontalDivider(color = AresBorder)
                Text("Source: ${item.source}", color = AresTextSecondary, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                if (item.advancedDetails.isNotBlank()) {
                    Text(item.advancedDetails, color = AresTextSecondary, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                }
            }
        }
    }
}

private fun VerificationLayer.studentLabel(): String = when (this) {
    VerificationLayer.CONFIGURATION -> "Configuration checks"
    VerificationLayer.GENERATED_BEHAVIOR -> "Robot Builder behavior tests"
    VerificationLayer.SIMULATOR -> "Simulator checks"
    VerificationLayer.PLATFORM_INTEGRATION -> "ARES platform integration"
    VerificationLayer.BUILD -> "Build result"
    VerificationLayer.PHYSICAL_VALIDATION -> "Physical validation boundary"
}

private fun VerificationResultStatus.label(): String = when (this) {
    VerificationResultStatus.PASSED -> "Passed"
    VerificationResultStatus.FAILED -> "Failed"
    VerificationResultStatus.BLOCKED -> "Blocked"
    VerificationResultStatus.NOT_RUN -> "Not measured"
}

private fun VerificationResultStatus.symbol(): String = when (this) {
    VerificationResultStatus.PASSED -> "✓"
    VerificationResultStatus.FAILED -> "×"
    VerificationResultStatus.BLOCKED -> "!"
    VerificationResultStatus.NOT_RUN -> "—"
}

private fun VerificationResultStatus.color(): Color = when (this) {
    VerificationResultStatus.PASSED -> AresGreen
    VerificationResultStatus.FAILED -> AresRed
    VerificationResultStatus.BLOCKED -> AresAmber
    VerificationResultStatus.NOT_RUN -> AresTextSecondary
}
