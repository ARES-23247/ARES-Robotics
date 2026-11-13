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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
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
import com.ares.analytics.domain.pit.EventScheduleCodec
import com.ares.analytics.domain.pit.ScheduledMatchItem
import com.ares.analytics.domain.pit.TeamMatchQueueRow
import com.ares.analytics.shared.models.WorkspaceConfig
import com.ares.analytics.ui.theme.AresAmber
import com.ares.analytics.ui.theme.AresBorder
import com.ares.analytics.ui.theme.AresCyan
import com.ares.analytics.ui.theme.AresError
import com.ares.analytics.ui.theme.AresGreen
import com.ares.analytics.ui.theme.AresOnAccent
import com.ares.analytics.ui.theme.AresRed
import com.ares.analytics.ui.theme.AresSurface
import com.ares.analytics.ui.theme.AresSurfaceElevated
import com.ares.analytics.ui.theme.AresTextPrimary
import com.ares.analytics.ui.theme.AresTextSecondary
import com.ares.analytics.ui.theme.AresTextTertiary
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val queueTimeFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("HH:mm", Locale.US).withZone(ZoneId.systemDefault())

@Composable
fun PitMatchQueueTab(
    workspace: WorkspaceConfig?,
    rows: List<TeamMatchQueueRow>,
    statusMessage: String?,
    onSyncApi: () -> Unit,
    onImportScheduleFile: () -> Unit,
    onUpsertManualMatch: (ScheduledMatchItem) -> Unit,
    onRemoveMatch: (compLevel: String, matchNumber: Int) -> Unit,
    onStageInReadiness: (TeamMatchQueueRow) -> Unit,
    onOpenMatchStrategy: () -> Unit,
    highlightedMatchNumber: Int? = null,
) {
    var showAddMatchDialog by remember { mutableStateOf(false) }

    val playedCount = rows.count { it.linkedSessionIds.isNotEmpty() }
    val nextRow = rows.firstOrNull { it.isNextUnplayedForTeam }
    val eventLabel = workspace?.eventCode?.takeIf(String::isNotBlank) ?: "Offline / Local Schedule"
    val teamLabel = workspace?.teamId?.takeIf(String::isNotBlank) ?: "Unassigned"
    val orderedRows = remember(rows, highlightedMatchNumber) {
        if (highlightedMatchNumber == null) rows
        else {
            val match = rows.filter { it.item.matchNumber == highlightedMatchNumber }
            val rest = rows.filterNot { it.item.matchNumber == highlightedMatchNumber }
            match + rest
        }
    }

    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
                            "Competition Match Queue — $eventLabel (Team $teamLabel)",
                            color = AresTextPrimary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                        )
                        Text(
                            "Offline-first local schedule cache supporting TBA, TOA, FIRST FTC Events v2, CSV/JSON import & manual entry.",
                            color = AresTextSecondary,
                            fontSize = 11.sp,
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = onSyncApi) {
                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp), tint = AresCyan)
                            Spacer(Modifier.width(4.dp))
                            Text("Sync API", fontSize = 11.sp)
                        }
                        OutlinedButton(onClick = onImportScheduleFile) {
                            Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(16.dp), tint = AresCyan)
                            Spacer(Modifier.width(4.dp))
                            Text("Import CSV / JSON", fontSize = 11.sp)
                        }
                        Button(
                            onClick = { showAddMatchDialog = true },
                            colors = ButtonDefaults.buttonColors(containerColor = AresCyan, contentColor = AresOnAccent),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Add Match", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    QueuePill("Matches Cached", "${rows.size}", AresCyan)
                    QueuePill("Runs Logged", "$playedCount", AresGreen)
                    nextRow?.let { next ->
                        val allianceText = next.ourAlliance?.uppercase(Locale.US) ?: "TBD"
                        QueuePill("Next Up", "${next.item.compLevel.uppercase(Locale.US)} #${next.item.matchNumber} ($allianceText)", AresAmber)
                    }
                }

                statusMessage?.let { msg ->
                    Text(msg, color = AresGreen, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }

        if (rows.isEmpty()) {
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
                    Icon(Icons.Default.Schedule, contentDescription = null, tint = AresCyan, modifier = Modifier.size(34.dp))
                    Spacer(Modifier.size(8.dp))
                    Text("No match schedule cached for this workspace", color = AresTextPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    Spacer(Modifier.size(4.dp))
                    Text(
                        "Import a CSV/JSON schedule for pit-offline turnaround, enter matches manually, or configure Event Code + API Key in Settings to sync.",
                        color = AresTextSecondary,
                        fontSize = 12.sp,
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(orderedRows, key = { "${it.item.compLevel}:${it.item.matchNumber}" }) { row ->
                    val isHighlighted = highlightedMatchNumber != null && row.item.matchNumber == highlightedMatchNumber
                    MatchQueueRowCard(
                        row = row,
                        isHighlighted = isHighlighted,
                        onStageInReadiness = { onStageInReadiness(row) },
                        onOpenMatchStrategy = onOpenMatchStrategy,
                        onRemove = { onRemoveMatch(row.item.compLevel, row.item.matchNumber) },
                    )
                }
            }
        }
    }

    if (showAddMatchDialog) {
        AddManualMatchDialog(
            nextSuggestedNumber = (rows.maxOfOrNull { it.item.matchNumber } ?: 0) + 1,
            onDismiss = { showAddMatchDialog = false },
            onSave = { item ->
                onUpsertManualMatch(item)
                showAddMatchDialog = false
            },
        )
    }
}

@Composable
private fun MatchQueueRowCard(
    row: TeamMatchQueueRow,
    isHighlighted: Boolean = false,
    onStageInReadiness: () -> Unit,
    onOpenMatchStrategy: () -> Unit,
    onRemove: () -> Unit,
) {
    val borderColor = when {
        isHighlighted -> AresCyan
        row.isNextUnplayedForTeam -> AresAmber
        row.linkedSessionIds.isNotEmpty() -> AresGreen.copy(alpha = 0.55f)
        else -> AresBorder
    }
    val allianceTint = when (row.ourAlliance) {
        "red" -> AresRed
        "blue" -> Color(0xFF448AFF)
        else -> AresTextSecondary
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = AresSurface),
        border = BorderStroke(if (isHighlighted) 2.dp else if (row.isNextUnplayedForTeam) 1.5.dp else 1.dp, borderColor),
        shape = RoundedCornerShape(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (isHighlighted) {
                        QueuePill("Focused", "Jump", AresCyan)
                    }
                    Text(
                        "${row.item.compLevel.replaceFirstChar { it.uppercaseChar() }} #${row.item.matchNumber}",
                        color = AresTextPrimary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                    )
                    row.ourAlliance?.let { side ->
                        Surface(
                            color = allianceTint.copy(alpha = 0.16f),
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, allianceTint.copy(alpha = 0.65f)),
                        ) {
                            Text(
                                "OUR SIDE: ${side.uppercase(Locale.US)}",
                                color = allianceTint,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                            )
                        }
                    }
                    if (row.isNextUnplayedForTeam) {
                        QueuePill("NEXT UP", "Queue", AresAmber)
                    }
                    if (row.linkedSessionIds.isNotEmpty()) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = AresGreen, modifier = Modifier.size(14.dp))
                            Text("${row.linkedSessionIds.size} run(s) logged", color = AresGreen, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                    row.item.scheduledTimeMs?.let { ts ->
                        Text(queueTimeFormatter.format(Instant.ofEpochMilli(ts)), color = AresTextTertiary, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                    }
                }

                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        "Red: ${row.item.redAlliance.joinToString(", ").ifBlank { "—" }}",
                        color = AresRed,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                    Text(
                        "Blue: ${row.item.blueAlliance.joinToString(", ").ifBlank { "—" }}",
                        color = Color(0xFF448AFF),
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                    if (row.partnerTeams.isNotEmpty()) {
                        Text(
                            "Partner(s): ${row.partnerTeams.joinToString(", ")}",
                            color = AresTextPrimary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = onStageInReadiness,
                    colors = ButtonDefaults.buttonColors(containerColor = AresCyan, contentColor = AresOnAccent),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                ) {
                    Text("Stage in Readiness", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
                OutlinedButton(onClick = onOpenMatchStrategy, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)) {
                    Icon(Icons.Default.Explore, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Strategy", fontSize = 11.sp)
                }
                IconButton(onClick = onRemove, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Default.Delete, contentDescription = "Remove match", tint = AresError, modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}

@Composable
private fun QueuePill(label: String, value: String, color: Color) {
    Surface(
        color = AresSurfaceElevated,
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, color.copy(alpha = 0.45f)),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, color = AresTextTertiary, fontSize = 10.sp)
            Text(value, color = color, fontWeight = FontWeight.Bold, fontSize = 11.sp)
        }
    }
}

@Composable
private fun AddManualMatchDialog(
    nextSuggestedNumber: Int,
    onDismiss: () -> Unit,
    onSave: (ScheduledMatchItem) -> Unit,
) {
    var compLevel by remember { mutableStateOf("quals") }
    var matchNumText by remember { mutableStateOf(nextSuggestedNumber.toString()) }
    var redText by remember { mutableStateOf("") }
    var blueText by remember { mutableStateOf("") }
    val matchNum = matchNumText.trim().toIntOrNull()?.takeIf { it > 0 }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = AresSurface,
        title = { Text("Add Scheduled Match", color = AresTextPrimary, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.width(380.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("quals" to "Quals", "elims" to "Playoff", "practice" to "Practice").forEach { (key, label) ->
                        FilterChip(
                            selected = compLevel == key,
                            onClick = { compLevel = key },
                            label = { Text(label, fontSize = 11.sp) },
                        )
                    }
                }
                OutlinedTextField(
                    value = matchNumText,
                    onValueChange = { matchNumText = it.filter(Char::isDigit).take(4) },
                    label = { Text("Match #") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = redText,
                    onValueChange = { redText = it },
                    label = { Text("Red Alliance Teams (comma or space separated)") },
                    placeholder = { Text("e.g. 23247, 11115") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = blueText,
                    onValueChange = { blueText = it },
                    label = { Text("Blue Alliance Teams (comma or space separated)") },
                    placeholder = { Text("e.g. 4278, 8888") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(
                enabled = matchNum != null,
                onClick = {
                    val split: (String) -> List<String> = { raw ->
                        raw.split(',', ' ', ';')
                            .map(EventScheduleCodec::normalizeTeamToken)
                            .filter(String::isNotBlank)
                    }
                    onSave(
                        ScheduledMatchItem(
                            matchNumber = matchNum!!,
                            compLevel = compLevel,
                            redAlliance = split(redText),
                            blueAlliance = split(blueText),
                            source = "manual",
                        )
                    )
                },
                colors = ButtonDefaults.buttonColors(containerColor = AresCyan, contentColor = AresOnAccent),
            ) { Text("Add to Queue", fontWeight = FontWeight.Bold) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
