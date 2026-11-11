package com.ares.analytics.ui.components.hardware

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ares.analytics.service.hardware.commissioningPlan
import com.ares.analytics.service.hardware.HardwareInventoryItem
import com.ares.analytics.service.hardware.HardwareInventoryOwner
import com.ares.analytics.service.hardware.HardwareSetupSnapshot
import com.ares.analytics.ui.components.core.AresSpecRow
import com.ares.analytics.ui.components.core.AresSpecSection
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
import com.ares.analytics.ui.theme.AresTextTertiary

enum class HardwareOwnerFilter(val label: String) {
    ALL("All Devices"),
    DRIVEBASE("Drivetrain"),
    SUBSYSTEM("Subsystems"),
    ISSUES_ONLY("Issues Only"),
}

data class HardwareBusSummary(
    val busName: String,
    val totalDevices: Int,
    val unconfiguredCount: Int,
    val issueCount: Int,
)

fun normalizedHardwareBusName(item: HardwareInventoryItem): String =
    item.bus?.trim()?.takeIf { it.isNotEmpty() } ?: item.addressKind.label

fun summarizeHardwareBuses(snapshot: HardwareSetupSnapshot): List<HardwareBusSummary> {
    val issueUids = snapshot.issues.mapNotNullTo(linkedSetOf()) { it.itemUid }
    return snapshot.items
        .groupBy(::normalizedHardwareBusName)
        .map { (busName, items) ->
            val unconfigured = items.count { it.address.isBlank() }
            val withIssues = items.count { it.uid in issueUids || it.address.isBlank() }
            HardwareBusSummary(
                busName = busName,
                totalDevices = items.size,
                unconfiguredCount = unconfigured,
                issueCount = withIssues,
            )
        }
        .sortedBy { it.busName.lowercase() }
}

fun filterHardwareInventory(
    snapshot: HardwareSetupSnapshot,
    searchQuery: String,
    ownerFilter: HardwareOwnerFilter,
    selectedBus: String? = null,
): List<HardwareInventoryItem> {
    val issueUids = snapshot.issues.mapNotNullTo(linkedSetOf()) { it.itemUid }
    val normalizedQuery = searchQuery.trim().lowercase()
    return snapshot.items.filter { item ->
        val matchesOwner = when (ownerFilter) {
            HardwareOwnerFilter.ALL -> true
            HardwareOwnerFilter.DRIVEBASE -> item.owner == HardwareInventoryOwner.DRIVEBASE
            HardwareOwnerFilter.SUBSYSTEM -> item.owner == HardwareInventoryOwner.SUBSYSTEM
            HardwareOwnerFilter.ISSUES_ONLY -> item.address.isBlank() || item.uid in issueUids
        }
        val matchesBus = selectedBus == null || normalizedHardwareBusName(item) == selectedBus
        val matchesSearch = normalizedQuery.isEmpty() ||
            item.displayName.lowercase().contains(normalizedQuery) ||
            item.address.lowercase().contains(normalizedQuery) ||
            item.ownerDisplayName.lowercase().contains(normalizedQuery) ||
            item.role.lowercase().contains(normalizedQuery) ||
            (item.bus?.lowercase()?.contains(normalizedQuery) == true) ||
            item.addressKind.label.lowercase().contains(normalizedQuery)
        matchesOwner && matchesBus && matchesSearch
    }
}

fun generateHardwareWiringSpecSections(snapshot: HardwareSetupSnapshot): List<AresSpecSection> {
    val issueUids = snapshot.issues.mapNotNullTo(linkedSetOf()) { it.itemUid }
    fun itemToRow(item: HardwareInventoryItem): AresSpecRow = AresSpecRow(
        id = item.uid,
        primaryLabel = "${item.ownerDisplayName} · ${item.displayName}",
        secondaryLabel = item.role,
        badge = when {
            item.address.isBlank() -> "UNCONFIGURED"
            item.uid in issueUids -> "ISSUE"
            item.inverted -> "INVERTED"
            else -> "NORMAL"
        },
        columns = listOf(
            "Address / Port" to item.address.ifBlank { "NOT CONFIGURED" },
            "Bus / Kind" to normalizedHardwareBusName(item),
            "Safe Neutral" to (item.safeOutput?.toString() ?: "0.0"),
        ),
    )

    val driveRows = snapshot.items.filter { it.owner == HardwareInventoryOwner.DRIVEBASE }.map(::itemToRow)
    val subRows = snapshot.items.filter { it.owner == HardwareInventoryOwner.SUBSYSTEM }.map(::itemToRow)
    val busRows = summarizeHardwareBuses(snapshot).map { summary ->
        AresSpecRow(
            id = "bus-${summary.busName}",
            primaryLabel = summary.busName,
            badge = if (summary.issueCount > 0) "${summary.issueCount} ISSUE(S)" else "OK",
            columns = listOf(
                "Devices" to "${summary.totalDevices} mapped",
                "Unconfigured" to "${summary.unconfiguredCount}",
            ),
        )
    }

    return listOf(
        AresSpecSection(title = "Bus & Port Allocation (${busRows.size})", rows = busRows),
        AresSpecSection(title = "Drivetrain Wiring (${driveRows.size})", rows = driveRows),
        AresSpecSection(title = "Subsystem Wiring (${subRows.size})", rows = subRows),
    )
}

fun formatHardwareWiringMarkdown(snapshot: HardwareSetupSnapshot): String = buildString {
    appendLine("# ARES Pit Wiring & Port Map Checklist")
    appendLine("- **League**: ${snapshot.league.name}")
    appendLine("- **Review Status**: ${snapshot.reviewStatus.name}${snapshot.reviewedBy?.let { " (by $it)" }.orEmpty()}")
    appendLine("- **Inventory Hash**: `${snapshot.inventoryHash.take(16)}`")
    appendLine("- **Total Devices**: ${snapshot.items.size}")
    appendLine()
    appendLine("| Check | Owner | Device | Role | Bus / Kind | Address / Port | Direction | Safe Neutral |")
    appendLine("|---|---|---|---|---|---|---|---|")
    snapshot.items.forEach { item ->
        val address = item.address.ifBlank { "NOT CONFIGURED" }
        val bus = normalizedHardwareBusName(item)
        val direction = if (item.inverted) "INVERTED" else "FORWARD"
        val neutral = item.safeOutput?.toString() ?: "0.0"
        appendLine("| [ ] | ${item.ownerDisplayName} | ${item.displayName} | ${item.role} | $bus | `$address` | $direction | `$neutral` |")
    }
}.trimEnd()

@Composable
@OptIn(ExperimentalLayoutApi::class)
fun HardwareWiringFilterStrip(
    snapshot: HardwareSetupSnapshot,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    ownerFilter: HardwareOwnerFilter,
    onOwnerFilterChange: (HardwareOwnerFilter) -> Unit,
    selectedBus: String?,
    onSelectedBusChange: (String?) -> Unit,
    filteredCount: Int,
    modifier: Modifier = Modifier,
) {
    val busSummaries = summarizeHardwareBuses(snapshot)
    val hasActiveFilter = searchQuery.isNotBlank() || ownerFilter != HardwareOwnerFilter.ALL || selectedBus != null

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = AresSurface),
        border = BorderStroke(1.dp, AresBorder),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(
                        "INTERACTIVE PORT MAP & BUS ALLOCATION",
                        color = AresTextSecondary,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        "Showing $filteredCount of ${snapshot.items.size} mapped hardware device(s). Select a bus card or filter chip below to isolate ports.",
                        color = AresTextTertiary,
                        fontSize = 11.sp,
                    )
                }
                if (hasActiveFilter) {
                    TextButton(
                        onClick = {
                            onSearchQueryChange("")
                            onOwnerFilterChange(HardwareOwnerFilter.ALL)
                            onSelectedBusChange(null)
                        },
                    ) {
                        Icon(Icons.Default.Clear, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Reset filters", fontSize = 11.sp)
                    }
                }
            }

            if (busSummaries.isNotEmpty()) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    busSummaries.forEach { summary ->
                        val isSelected = selectedBus == summary.busName
                        val badgeTint = when {
                            summary.unconfiguredCount > 0 -> AresRed
                            summary.issueCount > 0 -> AresAmber
                            else -> AresGreen
                        }
                        Surface(
                            modifier = Modifier.clickable {
                                onSelectedBusChange(if (isSelected) null else summary.busName)
                            },
                            color = if (isSelected) AresCyan.copy(alpha = 0.14f) else AresSurfaceElevated,
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(1.dp, if (isSelected) AresCyan else AresBorder),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Column {
                                    Text(
                                        summary.busName,
                                        color = AresTextPrimary,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                    )
                                    Text(
                                        "${summary.totalDevices} device(s)",
                                        color = AresTextSecondary,
                                        fontSize = 10.sp,
                                        fontFamily = FontFamily.Monospace,
                                    )
                                }
                                Surface(
                                    color = badgeTint.copy(alpha = 0.14f),
                                    shape = RoundedCornerShape(4.dp),
                                    border = BorderStroke(1.dp, badgeTint),
                                ) {
                                    Text(
                                        if (summary.issueCount > 0) "${summary.issueCount} ISSUE" else "OK",
                                        color = badgeTint,
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = onSearchQueryChange,
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    placeholder = { Text("Filter by device name, CAN ID, channel, bus, or subsystem…", fontSize = 12.sp) },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(16.dp)) },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { onSearchQueryChange("") }) {
                                Icon(Icons.Default.Clear, contentDescription = "Clear search", modifier = Modifier.size(16.dp))
                            }
                        }
                    },
                )
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                HardwareOwnerFilter.entries.forEach { filter ->
                    val selected = ownerFilter == filter
                    FilterChip(
                        selected = selected,
                        onClick = { onOwnerFilterChange(filter) },
                        modifier = Modifier.height(32.dp),
                        label = {
                            Text(
                                filter.label,
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
            }
        }
    }
}

data class StudentPortChecklistSummary(
    val totalDevices: Int,
    val readyCount: Int,
    val missingPortCount: Int,
    val duplicateConflictCount: Int,
) {
    val allClear: Boolean
        get() = totalDevices > 0 && missingPortCount == 0 && duplicateConflictCount == 0
}

fun summarizeStudentPortChecklist(snapshot: HardwareSetupSnapshot): StudentPortChecklistSummary {
    val issueUids = snapshot.issues.mapNotNullTo(linkedSetOf()) { it.itemUid }
    val missingCount = snapshot.items.count { it.address.isBlank() }
    val duplicateConflicts = snapshot.issues.count { issue ->
        issue.message.contains("duplicate", ignoreCase = true) ||
            issue.message.contains("conflict", ignoreCase = true) ||
            issue.message.contains("already", ignoreCase = true) ||
            issue.message.contains("shared", ignoreCase = true)
    }
    val ready = snapshot.items.count { it.address.isNotBlank() && it.uid !in issueUids }
    return StudentPortChecklistSummary(
        totalDevices = snapshot.items.size,
        readyCount = ready,
        missingPortCount = missingCount,
        duplicateConflictCount = duplicateConflicts,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StudentPortChecklistBanner(
    snapshot: HardwareSetupSnapshot,
    onFilterIssues: () -> Unit,
    onShowAll: () -> Unit,
    onOpenDrivebase: () -> Unit,
    onOpenSubsystems: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val summary = remember(snapshot) { summarizeStudentPortChecklist(snapshot) }
    val borderColor = when {
        summary.duplicateConflictCount > 0 -> AresRed
        summary.missingPortCount > 0 -> AresAmber
        else -> AresGreen
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = AresSurface),
        border = BorderStroke(1.dp, borderColor),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        "Student Port & Wiring Checklist",
                        color = AresTextPrimary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                    )
                    Text(
                        if (summary.allClear) {
                            "Every declared motor, servo, and sensor has a unique port/name assigned."
                        } else {
                            "Check missing port numbers or duplicate address conflicts below before deploying to the robot."
                        },
                        color = AresTextSecondary,
                        fontSize = 12.sp,
                    )
                }
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ChecklistBadgePill(
                    label = "Ready Ports",
                    count = summary.readyCount,
                    tint = AresGreen,
                    icon = Icons.Default.CheckCircle,
                )
                ChecklistBadgePill(
                    label = "Missing Port / Name",
                    count = summary.missingPortCount,
                    tint = if (summary.missingPortCount > 0) AresAmber else AresTextTertiary,
                    icon = Icons.Default.Warning,
                )
                ChecklistBadgePill(
                    label = "Duplicate Port Conflicts",
                    count = summary.duplicateConflictCount,
                    tint = if (summary.duplicateConflictCount > 0) AresRed else AresTextTertiary,
                    icon = Icons.Default.Error,
                )
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (summary.missingPortCount > 0 || summary.duplicateConflictCount > 0) {
                    OutlinedButton(onClick = onFilterIssues) {
                        Text("Filter to Port Issues (${summary.missingPortCount + summary.duplicateConflictCount})")
                    }
                } else {
                    OutlinedButton(onClick = onShowAll) {
                        Text("Show All ${summary.totalDevices} Devices")
                    }
                }
                OutlinedButton(onClick = onOpenDrivebase) {
                    Text("Fix in Drivetrain Builder")
                }
                OutlinedButton(onClick = onOpenSubsystems) {
                    Text("Fix in Subsystem Builder")
                }
            }
        }
    }
}

@Composable
private fun ChecklistBadgePill(
    label: String,
    count: Int,
    tint: Color,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
) {
    Surface(
        color = tint.copy(alpha = 0.10f),
        border = BorderStroke(1.dp, tint),
        shape = RoundedCornerShape(8.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(15.dp))
            Text(
                "$label: $count",
                color = AresTextPrimary,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 11.sp,
            )
        }
    }
}

/** Inventory diagnostics can change without changing any successfully decoded source fingerprint. */
@Composable
internal fun rememberCommissioningPlan(snapshot: HardwareSetupSnapshot) =
    remember(snapshot) { snapshot.commissioningPlan() }

@Composable
internal fun CommissioningGuideCard(
    snapshot: HardwareSetupSnapshot,
    onOpenPitDiagnostics: (() -> Unit)?,
) {
    val plan = rememberCommissioningPlan(snapshot)
    @Suppress("DEPRECATION") val clipboard = LocalClipboardManager.current
    Card(
        colors = CardDefaults.cardColors(containerColor = AresSurface),
        border = BorderStroke(1.dp, AresBorder),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Configure and identify the real robot", color = AresTextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text(
                "These names come from the canonical robot description. Enter them exactly in Driver Station → Configure Robot → Hardware; capitalization matters.",
                color = AresTextSecondary,
                lineHeight = 19.sp,
            )
            if (plan.hardwareMapEntries.isNotEmpty()) {
                Surface(color = AresSurfaceElevated, shape = RoundedCornerShape(9.dp), border = BorderStroke(1.dp, AresBorder)) {
                    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        plan.hardwareMapEntries.forEach { item ->
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text(item.displayName, color = AresTextPrimary, modifier = Modifier.weight(1f), fontSize = 12.sp)
                                Text(item.address.ifBlank { "NOT CONFIGURED" }, color = if (item.address.isBlank()) AresRed else AresCyan, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
                OutlinedButton(onClick = { clipboard.setText(AnnotatedString(plan.clipboardText)) }) {
                    Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Copy exact hardware names")
                }
            }

            HorizontalDivider(color = AresBorder)
            Text("Safely check motor position and direction", color = AresTextPrimary, fontWeight = FontWeight.Bold)
            Text(
                "ARES Robotics Studio never pulses a physical motor from this screen. On the Driver Station, select the TeleOp named ARES Drivetrain Diagnostic. It is hold-to-run and uses the generated names and directions below.",
                color = AresTextSecondary,
                fontSize = 12.sp,
                lineHeight = 18.sp,
            )
            Surface(color = AresAmber.copy(alpha = 0.10f), border = BorderStroke(1.dp, AresAmber), shape = RoundedCornerShape(9.dp)) {
                Text(
                    "Before Play: put the robot on secure blocks so every wheel is clear of the floor, remove game pieces, keep hands and clothing away, and have one person ready to press Stop. Release the button immediately if the wrong wheel or direction moves.",
                    color = AresTextPrimary,
                    modifier = Modifier.fillMaxWidth().padding(11.dp),
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                )
            }
            if (plan.ftcDiagnosticAvailable) {
                plan.ftcMotorChecks.forEach { check ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(check.gamepadControl, color = AresCyan, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, modifier = Modifier.width(100.dp))
                        Text("${check.displayName}  [${check.hardwareMapName}]  · ${check.configuredDirection}", color = AresTextPrimary, fontSize = 12.sp)
                    }
                }
            } else {
                Text(plan.ftcDiagnosticBlockReason.orEmpty(), color = AresAmber, fontSize = 12.sp, lineHeight = 18.sp)
            }
            Text(
                "The diagnostic proves only motor identity and configured direction. It does not validate odometry, closed-loop tuning, current limits, mechanisms, or match readiness.",
                color = AresTextTertiary,
                fontSize = 11.sp,
                lineHeight = 16.sp,
            )
            if (plan.subsystemChecks.isNotEmpty()) {
                HorizontalDivider(color = AresBorder)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    Icon(Icons.Default.Science, contentDescription = null, tint = AresCyan, modifier = Modifier.size(18.dp))
                    Text("Commission subsystem devices", color = AresTextPrimary, fontWeight = FontWeight.Bold)
                }
                Text(
                    "Start with read-only signals in the simulator or live self-test. Motion proposals below are intentionally unarmed: this app does not send them to a robot.",
                    color = AresTextSecondary,
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                )
                plan.subsystemChecks.forEach { check ->
                    Surface(color = AresSurfaceElevated, shape = RoundedCornerShape(9.dp), border = BorderStroke(1.dp, AresBorder)) {
                        Column(Modifier.fillMaxWidth().padding(11.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("${check.subsystemName} · ${check.deviceName}", color = AresTextPrimary, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                Text(check.hardwareAddress, color = AresCyan, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                            }
                            if (check.readOnlySignals.isNotEmpty()) {
                                Text("Observe first: ${check.readOnlySignals.joinToString()}", color = AresTextSecondary, fontSize = 11.sp)
                            }
                            if (check.controlStrategies.isNotEmpty()) {
                                Text("Control: ${check.controlStrategies.joinToString { it.lowercase().replace('_', ' ') }}", color = AresTextSecondary, fontSize = 11.sp)
                            }
                            check.homingMethod?.let { method ->
                                Text(
                                    "Homing: ${method.lowercase().replace('_', ' ')}${check.homingEvidence.takeIf { it.isNotEmpty() }?.joinToString(prefix = " · evidence: ").orEmpty()}",
                                    color = AresTextSecondary,
                                    fontSize = 11.sp,
                                )
                            }
                            if (check.requiresCalibration) Text("Calibration must be established before motion.", color = AresAmber, fontSize = 11.sp)
                            if (check.requiresCurrentMonitoring) Text("A fresh, valid current reading is required.", color = AresAmber, fontSize = 11.sp)
                            when {
                                check.followerOnly -> Text("Follower device: verify it with its leader; do not command it independently.", color = AresAmber, fontSize = 11.sp)
                                check.pulseProposal != null -> Text(
                                    "UNARMED PULSE PROPOSAL · safety checklist required · ≤${check.pulseProposal.maximumDurationMs} ms · ≤${(check.pulseProposal.maximumTravelFromNeutralFraction * 100).toInt()}% from neutral ${check.pulseProposal.safeNeutralOutput}",
                                    color = AresAmber,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                        }
                    }
                }
                Text(
                    "After identity, direction, sensors, and homing pass, transfer only reviewed gains from the simulation/tuning tools. Re-run neutral recovery before enabling normal commands.",
                    color = AresTextTertiary,
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                )
            }
            if (onOpenPitDiagnostics != null) {
                OutlinedButton(onClick = onOpenPitDiagnostics) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Open live read-only self-test")
                }
            }
        }
    }
}
