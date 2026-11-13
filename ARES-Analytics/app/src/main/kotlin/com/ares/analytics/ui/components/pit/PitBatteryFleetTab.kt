@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.ares.analytics.ui.components.pit

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ares.analytics.domain.pit.BatteryCheckinRecord
import com.ares.analytics.domain.pit.BatteryHealthGrade
import com.ares.analytics.domain.pit.BatteryHealthSummary
import com.ares.analytics.domain.pit.BatteryPoolStatus
import com.ares.analytics.domain.pit.BatterySpecRecord
import com.ares.analytics.domain.pit.formatBatterySessionTag
import com.ares.analytics.domain.pit.normalizeBatteryId
import com.ares.analytics.shared.models.League
import com.ares.analytics.shared.models.WorkspaceConfig
import com.ares.analytics.ui.theme.AresAmber
import com.ares.analytics.ui.theme.AresBorder
import com.ares.analytics.ui.theme.AresCyan
import com.ares.analytics.ui.theme.AresError
import com.ares.analytics.ui.theme.AresGreen
import com.ares.analytics.ui.theme.AresOnAccent
import com.ares.analytics.ui.theme.AresSurface
import com.ares.analytics.ui.theme.AresSurfaceElevated
import com.ares.analytics.ui.theme.AresTextPrimary
import com.ares.analytics.ui.theme.AresTextSecondary
import com.ares.analytics.ui.theme.AresTextTertiary
import java.util.Locale
import java.util.UUID

@Composable
fun PitBatteryFleetTab(
    workspace: WorkspaceConfig?,
    summaries: List<BatteryHealthSummary>,
    recommendedPack: BatteryHealthSummary?,
    onSaveBatterySpec: (BatterySpecRecord) -> Unit,
    onUpdatePoolStatus: (batteryId: String, status: BatteryPoolStatus) -> Unit,
    onRecordCheckin: (BatteryCheckinRecord) -> Unit,
    onDeleteBattery: (batteryId: String) -> Unit,
    onAssignToReadiness: (batteryId: String) -> Unit,
    onFilterRunHistoryByBattery: (tag: String) -> Unit,
    onLogFleetToNotebook: (() -> Unit)?,
) {
    val league = workspace?.league ?: League.FTC
    var showAddBatteryDialog by remember { mutableStateOf(false) }
    var checkinTargetId by remember { mutableStateOf<String?>(null) }

    val primeCount = summaries.count { it.grade == BatteryHealthGrade.COMPETITION_PRIME }
    val healthyCount = summaries.count { it.grade == BatteryHealthGrade.HEALTHY }
    val inspectCount = summaries.count {
        it.grade == BatteryHealthGrade.PRACTICE_ONLY || it.grade == BatteryHealthGrade.RETIRE_OR_INSPECT
    }

    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // Recommended pack & fleet toolbar
        Card(
            colors = CardDefaults.cardColors(containerColor = AresSurface),
            border = BorderStroke(1.dp, AresBorder),
            shape = RoundedCornerShape(10.dp),
        ) {
            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            "Battery Fleet & Internal Resistance Tracker",
                            color = AresTextPrimary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                        )
                        Text(
                            "Tracks DuckDB Battery Beak check-ins + hardware session R_int / V_min linked via bat:<ID> tags (excludes simulation runs).",
                            color = AresTextSecondary,
                            fontSize = 11.sp,
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { showAddBatteryDialog = true }) {
                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp), tint = AresCyan)
                            Spacer(Modifier.width(4.dp))
                            Text("Add Pack", fontSize = 11.sp)
                        }
                        if (onLogFleetToNotebook != null) {
                            Button(
                                onClick = onLogFleetToNotebook,
                                colors = ButtonDefaults.buttonColors(containerColor = AresGreen, contentColor = AresOnAccent),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            ) {
                                Icon(Icons.Default.Book, contentDescription = null, modifier = Modifier.size(15.dp))
                                Spacer(Modifier.width(5.dp))
                                Text("Log Fleet to Notebook", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    FleetKpiChip("Tracked Packs", "${summaries.size}", AresCyan)
                    FleetKpiChip("Competition Prime", "$primeCount", AresGreen)
                    FleetKpiChip("Healthy", "$healthyCount", AresCyan)
                    FleetKpiChip("Practice / Retire", "$inspectCount", if (inspectCount > 0) AresAmber else AresTextSecondary)
                }

                recommendedPack?.let { best ->
                    Surface(
                        color = AresGreen.copy(alpha = 0.10f),
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, AresGreen.copy(alpha = 0.55f)),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Row(
                                modifier = Modifier.weight(1f),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Icon(Icons.Default.BatteryChargingFull, contentDescription = null, tint = AresGreen, modifier = Modifier.size(18.dp))
                                Column {
                                    Text(
                                        "Recommended Next-Match Pack: ${best.batteryId} (${best.grade.label})",
                                        color = AresTextPrimary,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 12.sp,
                                    )
                                    Text(best.recommendation, color = AresTextSecondary, fontSize = 11.sp)
                                }
                            }
                            Button(
                                onClick = { onAssignToReadiness(best.batteryId) },
                                colors = ButtonDefaults.buttonColors(containerColor = AresCyan, contentColor = AresOnAccent),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                            ) {
                                Text("Assign to Readiness", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }

        if (summaries.isEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth().weight(1f),
                colors = CardDefaults.cardColors(containerColor = AresSurface),
                border = BorderStroke(1.dp, AresBorder),
                shape = RoundedCornerShape(10.dp),
            ) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(Icons.Default.Bolt, contentDescription = null, tint = AresCyan, modifier = Modifier.size(34.dp))
                    Spacer(Modifier.size(8.dp))
                    Text("No competition batteries tracked yet", color = AresTextPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    Spacer(Modifier.size(4.dp))
                    Text(
                        "Register packs (e.g. BAT-01..BAT-06) or tag imported runs with bat:BAT-01 to track internal resistance trends and brownout margin.",
                        color = AresTextSecondary,
                        fontSize = 12.sp,
                    )
                    Spacer(Modifier.size(12.dp))
                    Button(
                        onClick = { showAddBatteryDialog = true },
                        colors = ButtonDefaults.buttonColors(containerColor = AresCyan, contentColor = AresOnAccent),
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(5.dp))
                        Text("Register First Battery Pack", fontWeight = FontWeight.Bold)
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(summaries, key = BatteryHealthSummary::batteryId) { pack ->
                    BatteryPackCard(
                        pack = pack,
                        onUpdateStatus = { newStatus -> onUpdatePoolStatus(pack.batteryId, newStatus) },
                        onOpenCheckin = { checkinTargetId = pack.batteryId },
                        onAssign = { onAssignToReadiness(pack.batteryId) },
                        onFilterRuns = {
                            formatBatterySessionTag(pack.batteryId)?.let(onFilterRunHistoryByBattery)
                        },
                        onDelete = { onDeleteBattery(pack.batteryId) },
                    )
                }
            }
        }
    }

    if (showAddBatteryDialog) {
        AddBatteryDialog(
            workspace = workspace,
            league = league,
            onDismiss = { showAddBatteryDialog = false },
            onSave = { spec ->
                onSaveBatterySpec(spec)
                showAddBatteryDialog = false
            },
        )
    }

    checkinTargetId?.let { targetId ->
        BatteryCheckinDialog(
            batteryId = targetId,
            workspace = workspace,
            onDismiss = { checkinTargetId = null },
            onSave = { checkin ->
                onRecordCheckin(checkin)
                checkinTargetId = null
            },
        )
    }
}

@Composable
private fun BatteryPackCard(
    pack: BatteryHealthSummary,
    onUpdateStatus: (BatteryPoolStatus) -> Unit,
    onOpenCheckin: () -> Unit,
    onAssign: () -> Unit,
    onFilterRuns: () -> Unit,
    onDelete: () -> Unit,
) {
    val gradeColor = when (pack.grade) {
        BatteryHealthGrade.COMPETITION_PRIME -> AresGreen
        BatteryHealthGrade.HEALTHY -> AresCyan
        BatteryHealthGrade.UNTESTED -> AresTextSecondary
        BatteryHealthGrade.PRACTICE_ONLY -> AresAmber
        BatteryHealthGrade.RETIRE_OR_INSPECT -> AresError
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = AresSurface),
        border = BorderStroke(1.dp, gradeColor.copy(alpha = 0.45f)),
        shape = RoundedCornerShape(10.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Surface(
                        color = AresSurfaceElevated,
                        shape = RoundedCornerShape(6.dp),
                        border = BorderStroke(1.dp, AresBorder),
                    ) {
                        Text(
                            pack.batteryId,
                            color = AresTextPrimary,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        )
                    }
                    Surface(
                        color = gradeColor.copy(alpha = 0.14f),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, gradeColor.copy(alpha = 0.6f)),
                    ) {
                        Text(
                            pack.grade.label,
                            color = gradeColor,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        )
                    }
                    Text(
                        "${pack.hardwareCycleCount} hw cycle(s) · ${"%.0f".format(Locale.US, pack.nominalVoltage)}V ${"%.1f".format(Locale.US, pack.capacityAh)}Ah",
                        color = AresTextSecondary,
                        fontSize = 11.sp,
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = onOpenCheckin, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
                        Text("Log Beak / V_oc", fontSize = 11.sp)
                    }
                    OutlinedButton(onClick = onAssign, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
                        Text("Assign", fontSize = 11.sp, color = AresCyan)
                    }
                    OutlinedButton(onClick = onFilterRuns, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
                        Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(13.dp))
                        Spacer(Modifier.width(3.dp))
                        Text("Runs", fontSize = 11.sp)
                    }
                    if (!pack.isDiscoveredFromTagsOnly) {
                        IconButton(onClick = onDelete, modifier = Modifier.size(28.dp)) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete battery", tint = AresError, modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }

            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                MetricValueText("Resting V", pack.latestRestingVoltage?.let { "${"%.2f".format(Locale.US, it)} V" } ?: "—")
                MetricValueText("Beak R_int", pack.latestBeakMilliohms?.let { "${"%.1f".format(Locale.US, it)} mΩ" } ?: "—")
                MetricValueText("Telemetry Median", pack.medianTelemetryMilliohms?.let { "${"%.1f".format(Locale.US, it)} mΩ" } ?: "—")
                MetricValueText(
                    "R Slope",
                    pack.resistanceSlopeMilliohmsPerRun?.let { "${"%+.2f".format(Locale.US, it)} mΩ/run" } ?: "—",
                )
                MetricValueText("Worst V_min", pack.worstMinVoltage?.let { "${"%.2f".format(Locale.US, it)} V" } ?: "—")
            }

            Text(pack.recommendation, color = AresTextSecondary, fontSize = 11.sp)

            HorizontalDivider(color = AresBorder.copy(alpha = 0.55f))

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    "Pool state:",
                    color = AresTextTertiary,
                    fontSize = 11.sp,
                    modifier = Modifier.align(Alignment.CenterVertically),
                )
                BatteryPoolStatus.entries.forEach { option ->
                    FilterChip(
                        selected = pack.status == option,
                        onClick = { onUpdateStatus(option) },
                        label = { Text(option.label, fontSize = 10.sp) },
                    )
                }
            }
        }
    }
}

@Composable
private fun FleetKpiChip(label: String, value: String, color: Color) {
    Surface(
        color = AresSurfaceElevated,
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, color.copy(alpha = 0.45f)),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(value, color = color, fontWeight = FontWeight.Bold, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
            Text(label, color = AresTextSecondary, fontSize = 11.sp)
        }
    }
}

@Composable
private fun MetricValueText(label: String, value: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("$label:", color = AresTextTertiary, fontSize = 11.sp)
        Text(value, color = AresTextPrimary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun AddBatteryDialog(
    workspace: WorkspaceConfig?,
    league: League,
    onDismiss: () -> Unit,
    onSave: (BatterySpecRecord) -> Unit,
) {
    var batteryId by remember { mutableStateOf("BAT-01") }
    var nominalText by remember(league) { mutableStateOf(if (league == League.XRP) "6.0" else "12.0") }
    var capacityText by remember(league) { mutableStateOf(when (league) { League.FRC -> "18.0"; League.FTC -> "3.0"; League.XRP -> "2.0" }) }
    var notes by remember { mutableStateOf("") }
    val normalized = normalizeBatteryId(batteryId)

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = AresSurface,
        title = { Text("Register Battery Pack", color = AresTextPrimary, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.width(380.dp)) {
                OutlinedTextField(
                    value = batteryId,
                    onValueChange = { batteryId = it },
                    label = { Text("Battery ID (e.g. BAT-01)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = nominalText,
                        onValueChange = { nominalText = it },
                        label = { Text("Nominal V") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = capacityText,
                        onValueChange = { capacityText = it },
                        label = { Text("Capacity Ah") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }
                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    label = { Text("Purchase / cell notes (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (normalized.isNotEmpty()) {
                    Text(
                        "Sessions tagged with ${formatBatterySessionTag(normalized)} will link automatically.",
                        color = AresTextSecondary,
                        fontSize = 11.sp,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                enabled = normalized.isNotEmpty(),
                onClick = {
                    val now = System.currentTimeMillis()
                    onSave(
                        BatterySpecRecord(
                            batteryId = normalized,
                            teamId = workspace?.teamId?.trim().orEmpty(),
                            seasonId = workspace?.seasonId?.trim().orEmpty(),
                            robotId = workspace?.robotId?.trim().orEmpty(),
                            label = normalized,
                            nominalVoltage = nominalText.toDoubleOrNull()?.takeIf { it > 0.0 } ?: 12.0,
                            capacityAh = capacityText.toDoubleOrNull()?.takeIf { it > 0.0 } ?: 3.0,
                            status = BatteryPoolStatus.READY,
                            notes = notes.trim(),
                            createdAtMs = now,
                            updatedAtMs = now,
                        )
                    )
                },
                colors = ButtonDefaults.buttonColors(containerColor = AresCyan, contentColor = AresOnAccent),
            ) { Text("Save Pack", fontWeight = FontWeight.Bold) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun BatteryCheckinDialog(
    batteryId: String,
    workspace: WorkspaceConfig?,
    onDismiss: () -> Unit,
    onSave: (BatteryCheckinRecord) -> Unit,
) {
    var restingText by remember { mutableStateOf("") }
    var beakMohmText by remember { mutableStateOf("") }
    var initials by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }
    val valid = restingText.toDoubleOrNull()?.let { it > 0.0 } == true ||
        beakMohmText.toDoubleOrNull()?.let { it > 0.0 } == true

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = AresSurface,
        title = { Text("Log Check-In — $batteryId", color = AresTextPrimary, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.width(380.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = restingText,
                        onValueChange = { restingText = it },
                        label = { Text("Resting V_oc") },
                        placeholder = { Text("13.05") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = beakMohmText,
                        onValueChange = { beakMohmText = it },
                        label = { Text("Beak R_int (mΩ)") },
                        placeholder = { Text("18.5") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }
                OutlinedTextField(
                    value = initials,
                    onValueChange = { initials = it.take(16) },
                    label = { Text("Crew initials (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    label = { Text("Condition notes (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(
                enabled = valid,
                onClick = {
                    onSave(
                        BatteryCheckinRecord(
                            checkinId = "chk-${UUID.randomUUID().toString().replace("-", "").take(12)}",
                            batteryId = batteryId,
                            teamId = workspace?.teamId?.trim().orEmpty(),
                            seasonId = workspace?.seasonId?.trim().orEmpty(),
                            robotId = workspace?.robotId?.trim().orEmpty(),
                            restingVoltage = restingText.toDoubleOrNull()?.takeIf { it > 0.0 },
                            beakResistanceMilliohms = beakMohmText.toDoubleOrNull()?.takeIf { it > 0.0 },
                            inspectorInitials = initials.trim(),
                            notes = notes.trim(),
                            recordedAtMs = System.currentTimeMillis(),
                        )
                    )
                },
                colors = ButtonDefaults.buttonColors(containerColor = AresCyan, contentColor = AresOnAccent),
            ) { Text("Record Check-In", fontWeight = FontWeight.Bold) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
