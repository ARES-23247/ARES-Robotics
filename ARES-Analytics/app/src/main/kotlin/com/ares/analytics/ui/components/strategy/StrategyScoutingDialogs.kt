@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.ares.analytics.ui.components.strategy

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ares.analytics.domain.strategy.*
import com.ares.analytics.ui.theme.*

@Composable
internal fun PitTeamProfileDialog(
    initialTeamNumber: String,
    existing: PitTeamProfile?,
    onDismiss: () -> Unit,
    onSave: (PitTeamProfile) -> Unit,
) {
    var teamNumber by remember(initialTeamNumber, existing) {
        mutableStateOf(existing?.teamNumber ?: initialTeamNumber)
    }
    var drivetrain by remember(existing) { mutableStateOf(existing?.drivetrain ?: ScoutedDrivetrain.UNKNOWN) }
    var autoZone by remember(existing) { mutableStateOf(existing?.preferredAutoZone ?: AutoStartZone.UNKNOWN) }
    var endgame by remember(existing) { mutableStateOf(existing?.endgameCapability ?: EndgameTier.UNKNOWN) }
    var role by remember(existing) { mutableStateOf(existing?.primaryRole ?: RobotRole.PRIMARY_SCORER) }
    var autoSummary by remember(existing) { mutableStateOf(existing?.autoSummary.orEmpty()) }
    var notes by remember(existing) { mutableStateOf(existing?.notes.orEmpty()) }
    var scoutAuthor by remember(existing) { mutableStateOf(existing?.scoutAuthor?.ifBlank { "Pit Crew" } ?: "Pit Crew") }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = AresSurface,
        title = {
            Text(
                if (existing == null) "Log Pit Scouting Profile" else "Edit Pit Profile — Team ${existing.teamNumber}",
                color = AresTextPrimary,
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            Column(
                modifier = Modifier.widthIn(min = 420.dp, max = 560.dp).heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = teamNumber,
                        onValueChange = { teamNumber = it },
                        label = { Text("Team #") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = scoutAuthor,
                        onValueChange = { scoutAuthor = it },
                        label = { Text("Scout Author") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }

                Text("Drivetrain", color = AresTextSecondary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ScoutedDrivetrain.entries.forEach { d ->
                        FilterChip(selected = drivetrain == d, onClick = { drivetrain = d }, label = { Text(d.label, fontSize = 11.sp) })
                    }
                }

                Text("Preferred Auto Start Zone", color = AresTextSecondary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    AutoStartZone.entries.forEach { z ->
                        FilterChip(selected = autoZone == z, onClick = { autoZone = z }, label = { Text(z.label, fontSize = 11.sp) })
                    }
                }

                Text("Endgame Capability", color = AresTextSecondary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    EndgameTier.entries.forEach { e ->
                        FilterChip(selected = endgame == e, onClick = { endgame = e }, label = { Text(e.label, fontSize = 11.sp) })
                    }
                }

                Text("Primary Alliance Role", color = AresTextSecondary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    RobotRole.entries.forEach { r ->
                        FilterChip(selected = role == r, onClick = { role = r }, label = { Text(r.label, fontSize = 11.sp) })
                    }
                }

                OutlinedTextField(
                    value = autoSummary,
                    onValueChange = { autoSummary = it },
                    label = { Text("Auto Routine Summary (e.g., 4-sample left + park)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    label = { Text("Pit Observations / Bumper Clearance / Mechanical Notes") },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val clean = normalizeTeamNumber(teamNumber)
                    if (clean.isNotBlank()) {
                        onSave(
                            PitTeamProfile(
                                teamNumber = clean,
                                drivetrain = drivetrain,
                                preferredAutoZone = autoZone,
                                endgameCapability = endgame,
                                primaryRole = role,
                                autoSummary = autoSummary.trim(),
                                notes = notes.trim(),
                                scoutAuthor = scoutAuthor.trim().ifBlank { "Pit Crew" },
                                updatedAtMs = System.currentTimeMillis(),
                            ),
                        )
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = AresCyan, contentColor = AresOnAccent),
            ) {
                Text("Save Pit Profile", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
internal fun StandObservationDialog(
    initialTeamNumber: String,
    defaultMatchNumber: Int,
    onDismiss: () -> Unit,
    onSave: (StandMatchObservation) -> Unit,
) {
    var teamNumber by remember(initialTeamNumber) { mutableStateOf(initialTeamNumber) }
    var matchNumberText by remember(defaultMatchNumber) { mutableStateOf(defaultMatchNumber.coerceAtLeast(1).toString()) }
    var scoutAuthor by remember { mutableStateOf("Stand Scout") }
    var autoAttempted by remember { mutableStateOf(true) }
    var autoSucceeded by remember { mutableStateOf(true) }
    var cyclesText by remember { mutableStateOf("5") }
    var endgame by remember { mutableStateOf(EndgameTier.PARK) }
    var defenseRating by remember { mutableStateOf(0) }
    var driverRating by remember { mutableStateOf(4) }
    var diedOrCommsLost by remember { mutableStateOf(false) }
    var selectedTags by remember { mutableStateOf(emptySet<ScoutingQuickTag>()) }
    var notes by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = AresSurface,
        title = { Text("Quick Stand Match Observation", color = AresTextPrimary, fontWeight = FontWeight.Bold) },
        text = {
            Column(
                modifier = Modifier.widthIn(min = 420.dp, max = 560.dp).heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = teamNumber,
                        onValueChange = { teamNumber = it },
                        label = { Text("Team #") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = matchNumberText,
                        onValueChange = { matchNumberText = it.filter(Char::isDigit) },
                        label = { Text("Match #") },
                        singleLine = true,
                        modifier = Modifier.weight(0.8f),
                    )
                    OutlinedTextField(
                        value = cyclesText,
                        onValueChange = { cyclesText = it.filter(Char::isDigit) },
                        label = { Text("Cycles") },
                        singleLine = true,
                        modifier = Modifier.weight(0.8f),
                    )
                }
                OutlinedTextField(
                    value = scoutAuthor,
                    onValueChange = { scoutAuthor = it },
                    label = { Text("Scout Name (kept distinct on bundle merge)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    FilterChip(selected = autoAttempted, onClick = { autoAttempted = !autoAttempted }, label = { Text("Auto Tried", fontSize = 11.sp) })
                    FilterChip(selected = autoSucceeded, onClick = { autoSucceeded = !autoSucceeded }, label = { Text("Auto Worked", fontSize = 11.sp) })
                    FilterChip(selected = diedOrCommsLost, onClick = { diedOrCommsLost = !diedOrCommsLost }, label = { Text("Died / Comms Drop", fontSize = 11.sp) })
                }
                Text("Endgame Achieved", color = AresTextSecondary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(EndgameTier.NONE, EndgameTier.PARK, EndgameTier.LOW_CLIMB, EndgameTier.HIGH_CLIMB).forEach { tier ->
                        FilterChip(selected = endgame == tier, onClick = { endgame = tier }, label = { Text(tier.label, fontSize = 11.sp) })
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text("Driver Rating: $driverRating / 5", color = AresTextSecondary, fontSize = 11.sp)
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            (1..5).forEach { r ->
                                FilterChip(selected = driverRating == r, onClick = { driverRating = r }, label = { Text("$r", fontSize = 11.sp) })
                            }
                        }
                    }
                    Column(Modifier.weight(1f)) {
                        Text("Defense Rating: $defenseRating / 5", color = AresTextSecondary, fontSize = 11.sp)
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            (0..5 step 1).forEach { d ->
                                FilterChip(selected = defenseRating == d, onClick = { defenseRating = d }, label = { Text("$d", fontSize = 11.sp) })
                            }
                        }
                    }
                }
                Text("Quick Tags", color = AresTextSecondary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ScoutingQuickTag.entries.forEach { tag ->
                        FilterChip(
                            selected = tag in selectedTags,
                            onClick = { selectedTags = if (tag in selectedTags) selectedTags - tag else selectedTags + tag },
                            label = { Text(tag.label, fontSize = 11.sp) },
                        )
                    }
                }
                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    label = { Text("Stand Notes") },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val clean = normalizeTeamNumber(teamNumber)
                    val matchNum = matchNumberText.toIntOrNull()?.coerceAtLeast(1) ?: 1
                    if (clean.isNotBlank()) {
                        onSave(
                            StandMatchObservation(
                                teamNumber = clean,
                                matchNumber = matchNum,
                                autoAttempted = autoAttempted,
                                autoSucceeded = autoSucceeded,
                                estimatedCycles = cyclesText.toIntOrNull()?.coerceAtLeast(0) ?: 0,
                                endgameAchieved = endgame,
                                defenseRating = defenseRating,
                                driverRating = driverRating,
                                diedOrCommsLost = diedOrCommsLost,
                                quickTags = selectedTags.toList(),
                                notes = notes.trim(),
                                scoutAuthor = scoutAuthor.trim().ifBlank { "Stand Scout" },
                                updatedAtMs = System.currentTimeMillis(),
                            ),
                        )
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = AresCyan, contentColor = AresOnAccent),
            ) {
                Text("Save Stand Log", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
internal fun ManualMatchResultDialog(
    suggestedMatchNumber: Int,
    onDismiss: () -> Unit,
    onSave: (EventMatchResult) -> Unit,
) {
    var matchText by remember(suggestedMatchNumber) { mutableStateOf(suggestedMatchNumber.coerceAtLeast(1).toString()) }
    var redTeamsText by remember { mutableStateOf("") }
    var blueTeamsText by remember { mutableStateOf("") }
    var redScoreText by remember { mutableStateOf("") }
    var blueScoreText by remember { mutableStateOf("") }
    var redFoulText by remember { mutableStateOf("0") }
    var blueFoulText by remember { mutableStateOf("0") }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = AresSurface,
        title = { Text("Record Offline Match Score (OPR Input)", color = AresTextPrimary, fontWeight = FontWeight.Bold) },
        text = {
            Column(modifier = Modifier.widthIn(min = 400.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = matchText,
                    onValueChange = { matchText = it.filter(Char::isDigit) },
                    label = { Text("Qualification Match #") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = redTeamsText,
                    onValueChange = { redTeamsText = it },
                    label = { Text("Red Teams (comma-separated)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = blueTeamsText,
                    onValueChange = { blueTeamsText = it },
                    label = { Text("Blue Teams (comma-separated)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = redScoreText,
                        onValueChange = { redScoreText = it.filter(Char::isDigit) },
                        label = { Text("Red Total") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = blueScoreText,
                        onValueChange = { blueScoreText = it.filter(Char::isDigit) },
                        label = { Text("Blue Total") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = redFoulText,
                        onValueChange = { redFoulText = it.filter(Char::isDigit) },
                        label = { Text("Red Foul Pts Awarded") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = blueFoulText,
                        onValueChange = { blueFoulText = it.filter(Char::isDigit) },
                        label = { Text("Blue Foul Pts Awarded") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val mNum = matchText.toIntOrNull()?.coerceAtLeast(1) ?: return@Button
                    val red = redTeamsText.split(',', ';', ' ').map(::normalizeTeamNumber).filter(String::isNotBlank)
                    val blue = blueTeamsText.split(',', ';', ' ').map(::normalizeTeamNumber).filter(String::isNotBlank)
                    val rScore = redScoreText.toIntOrNull()
                    val bScore = blueScoreText.toIntOrNull()
                    if (red.isNotEmpty() && blue.isNotEmpty() && rScore != null && bScore != null) {
                        onSave(
                            EventMatchResult(
                                matchNumber = mNum,
                                compLevel = "quals",
                                redTeams = red,
                                blueTeams = blue,
                                redTotalScore = rScore,
                                blueTotalScore = bScore,
                                redFoulPointsAwarded = redFoulText.toIntOrNull() ?: 0,
                                blueFoulPointsAwarded = blueFoulText.toIntOrNull() ?: 0,
                                source = "manual",
                                updatedAtMs = System.currentTimeMillis(),
                            ),
                        )
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = AresCyan, contentColor = AresOnAccent),
            ) {
                Text("Save Match Result", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
