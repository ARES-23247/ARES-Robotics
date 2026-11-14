@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.ares.analytics.ui.components.strategy

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ares.analytics.domain.strategy.*
import com.ares.analytics.shared.models.WorkspaceConfig
import com.ares.analytics.ui.theme.*
import java.util.Locale

/**
 * Tab 1 inside Competition Strategy Hub: OPR / NP-OPR / DPR / ccWM leaderboard,
 * multi-scout JSON/CSV merge tools, live event score sync, and team Pit/Stand entry triggers.
 */
@Composable
internal fun StrategyOprAndScoutingTab(
    workspace: WorkspaceConfig?,
    bundle: EventScoutingBundle,
    oprSnapshot: EventOprSnapshot,
    standSummaries: Map<String, TeamStandScoutingSummary>,
    rankedRows: List<RankedPicklistTeamRow>,
    highlightedTeamNumber: String?,
    syncInProgress: Boolean,
    onSyncEventScores: () -> Unit,
    onImportAndMergeBundle: () -> Unit,
    onExportBundle: () -> Unit,
    onSavePitProfile: (PitTeamProfile) -> Unit,
    onSaveStandObservation: (StandMatchObservation) -> Unit,
    onSaveManualMatchResult: (EventMatchResult) -> Unit,
    onJumpToPicklistTeam: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var searchQuery by remember(highlightedTeamNumber) { mutableStateOf("") }
    var pitDialogTeam by remember { mutableStateOf<String?>(null) }
    var standDialogTeam by remember { mutableStateOf<String?>(null) }
    var showMatchDialog by remember { mutableStateOf(false) }

    val cleanOurTeam = workspace?.teamId?.let(::normalizeTeamNumber)?.takeIf(String::isNotBlank)
    val pitByTeam = remember(bundle.pitProfiles) {
        bundle.pitProfiles.associateBy { normalizeTeamNumber(it.teamNumber) }
    }
    val allTeamNumbers = remember(oprSnapshot, pitByTeam, standSummaries, rankedRows, cleanOurTeam) {
        buildSet {
            cleanOurTeam?.let(::add)
            addAll(oprSnapshot.metricsByTeam.keys)
            addAll(pitByTeam.keys)
            addAll(standSummaries.keys)
            rankedRows.forEach { add(it.teamNumber) }
        }.sortedWith(
            compareByDescending<String> { it == highlightedTeamNumber }
                .thenByDescending { oprSnapshot.metricsByTeam[it]?.npOpr ?: Double.NEGATIVE_INFINITY }
                .thenBy { it.toIntOrNull() ?: Int.MAX_VALUE }
                .thenBy { it },
        )
    }

    val filteredTeams = remember(allTeamNumbers, searchQuery, pitByTeam) {
        val q = searchQuery.trim().lowercase(Locale.US)
        if (q.isEmpty()) allTeamNumbers
        else allTeamNumbers.filter { team ->
            val pit = pitByTeam[team]
            team.lowercase(Locale.US).contains(q) ||
                (pit?.drivetrain?.label?.lowercase(Locale.US)?.contains(q) == true) ||
                (pit?.primaryRole?.label?.lowercase(Locale.US)?.contains(q) == true) ||
                (pit?.preferredAutoZone?.label?.lowercase(Locale.US)?.contains(q) == true)
        }
    }

    val distinctScouts = remember(bundle.pitProfiles, bundle.standObservations) {
        (bundle.pitProfiles.map { it.scoutAuthor } + bundle.standObservations.map { it.scoutAuthor })
            .map(String::trim)
            .filter(String::isNotBlank)
            .toSet()
    }

    Column(modifier = modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = AresSurface),
            shape = RoundedCornerShape(10.dp),
            border = BorderStroke(1.dp, AresBorder),
        ) {
            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            "OPR / NP-OPR / ccWM & Multi-Scout Analytics",
                            color = AresTextPrimary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                        )
                        Text(
                            "Ridge-regularized offense/defense ratings paired with offline-mergeable pit profiles and stand scouting logs.",
                            color = AresTextSecondary,
                            fontSize = 11.sp,
                        )
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedButton(onClick = onSyncEventScores, enabled = !syncInProgress) {
                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(15.dp), tint = AresCyan)
                            Spacer(Modifier.width(4.dp))
                            Text(if (syncInProgress) "Syncing..." else "Sync Event Scores", fontSize = 11.sp)
                        }
                        OutlinedButton(onClick = onImportAndMergeBundle) {
                            Icon(Icons.Default.UploadFile, contentDescription = null, modifier = Modifier.size(15.dp), tint = AresCyan)
                            Spacer(Modifier.width(4.dp))
                            Text("Import / Merge Bundle", fontSize = 11.sp)
                        }
                        OutlinedButton(onClick = onExportBundle) {
                            Icon(Icons.Default.FileDownload, contentDescription = null, modifier = Modifier.size(15.dp), tint = AresGreen)
                            Spacer(Modifier.width(4.dp))
                            Text("Export Bundle", fontSize = 11.sp)
                        }
                        OutlinedButton(onClick = { showMatchDialog = true }) {
                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(15.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Log Match Score", fontSize = 11.sp)
                        }
                        Button(
                            onClick = { pitDialogTeam = highlightedTeamNumber ?: cleanOurTeam ?: "" },
                            colors = ButtonDefaults.buttonColors(containerColor = AresCyan, contentColor = AresOnAccent),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                        ) {
                            Text("+ Pit Scout", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                        Button(
                            onClick = { standDialogTeam = highlightedTeamNumber ?: allTeamNumbers.firstOrNull() ?: "" },
                            colors = ButtonDefaults.buttonColors(containerColor = AresSurfaceElevated, contentColor = AresCyan),
                            border = BorderStroke(1.dp, AresCyan.copy(alpha = 0.6f)),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                        ) {
                            Text("+ Stand Log", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                val confColor = when (oprSnapshot.confidence) {
                    OprSampleConfidence.FULL_RANK -> AresGreen
                    OprSampleConfidence.EARLY_SAMPLE -> AresAmber
                    OprSampleConfidence.NONE -> AresTextSecondary
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    StatBadgeChip("Confidence", oprSnapshot.confidence.label, confColor)
                    StatBadgeChip("Scored Matches", "${oprSnapshot.playedMatchCount}", AresCyan)
                    StatBadgeChip("Teams Indexed", "${allTeamNumbers.size}", AresCyan)
                    StatBadgeChip("Pit Profiles", "${bundle.pitProfiles.size}", AresGreen)
                    StatBadgeChip("Stand Logs", "${bundle.standObservations.size} (${distinctScouts.size} scouts)", AresGold)
                }

                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text("Filter by team number, drivetrain, role, or auto zone...") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = AresTextSecondary) },
                )
            }
        }

        if (filteredTeams.isEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth().weight(1f),
                colors = CardDefaults.cardColors(containerColor = AresSurface),
                border = BorderStroke(1.dp, AresBorder),
                shape = RoundedCornerShape(10.dp),
            ) {
                Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                    Text(
                        "No scored matches or scouted teams yet. Sync official event scores, import a scouting bundle (.json/.csv), or record manual Pit/Stand observations.",
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
                items(filteredTeams, key = { it }) { team ->
                    val opr = oprSnapshot.metricsByTeam[team]
                    val pit = pitByTeam[team]
                    val stand = standSummaries[team]
                    val isFocused = highlightedTeamNumber != null && team.equals(highlightedTeamNumber, ignoreCase = true)
                    val isOurs = cleanOurTeam != null && team.equals(cleanOurTeam, ignoreCase = true)
                    TeamScoutingRowCard(
                        teamNumber = team,
                        isFocused = isFocused,
                        isOurTeam = isOurs,
                        opr = opr,
                        pitProfile = pit,
                        standSummary = stand,
                        onEditPit = { pitDialogTeam = team },
                        onAddStand = { standDialogTeam = team },
                        onOpenPicklist = { onJumpToPicklistTeam(team) },
                    )
                }
            }
        }
    }

    pitDialogTeam?.let { team ->
        PitTeamProfileDialog(
            initialTeamNumber = team,
            existing = pitByTeam[normalizeTeamNumber(team)],
            onDismiss = { pitDialogTeam = null },
            onSave = { profile ->
                onSavePitProfile(profile)
                pitDialogTeam = null
            },
        )
    }

    standDialogTeam?.let { team ->
        StandObservationDialog(
            initialTeamNumber = team,
            defaultMatchNumber = (bundle.matchResults.maxOfOrNull { it.matchNumber } ?: 1),
            onDismiss = { standDialogTeam = null },
            onSave = { obs ->
                onSaveStandObservation(obs)
                standDialogTeam = null
            },
        )
    }

    if (showMatchDialog) {
        ManualMatchResultDialog(
            suggestedMatchNumber = (bundle.matchResults.maxOfOrNull { it.matchNumber } ?: 0) + 1,
            onDismiss = { showMatchDialog = false },
            onSave = { result ->
                onSaveManualMatchResult(result)
                showMatchDialog = false
            },
        )
    }
}

@Composable
private fun TeamScoutingRowCard(
    teamNumber: String,
    isFocused: Boolean,
    isOurTeam: Boolean,
    opr: TeamOprMetrics?,
    pitProfile: PitTeamProfile?,
    standSummary: TeamStandScoutingSummary?,
    onEditPit: () -> Unit,
    onAddStand: () -> Unit,
    onOpenPicklist: () -> Unit,
) {
    val border = when {
        isFocused -> BorderStroke(2.dp, AresCyan)
        isOurTeam -> BorderStroke(1.5.dp, AresGold.copy(alpha = 0.7f))
        else -> BorderStroke(1.dp, AresBorder)
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = AresSurface),
        border = border,
        shape = RoundedCornerShape(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        "Team #$teamNumber",
                        color = AresTextPrimary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                    if (isFocused) StatBadgeChip("Focus", "Selected", AresCyan)
                    if (isOurTeam) StatBadgeChip("Us", "Own Robot", AresGold)
                    pitProfile?.let { pit ->
                        StatBadgeChip("Drive", pit.drivetrain.label, AresTextSecondary)
                        StatBadgeChip("Auto", pit.preferredAutoZone.label, AresCyan)
                        StatBadgeChip("Endgame", pit.endgameCapability.label, AresGreen)
                    }
                    if ((standSummary?.disconnectCount ?: 0) > 0) {
                        StatBadgeChip("Reliability", "${standSummary?.disconnectCount} drops", AresError)
                    }
                }

                FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    if (opr != null) {
                        Text(
                            "NP-OPR: ${String.format(Locale.US, "%.1f", opr.npOpr)}",
                            color = AresCyan,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                        )
                        Text(
                            "Total OPR: ${String.format(Locale.US, "%.1f", opr.totalOpr)}",
                            color = AresTextSecondary,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                        )
                        Text(
                            "DPR: ${String.format(Locale.US, "%.1f", opr.dpr)}",
                            color = AresTextSecondary,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                        )
                        Text(
                            "ccWM: ${String.format(Locale.US, "%+.1f", opr.ccwm)}",
                            color = if (opr.ccwm >= 0) AresGreen else AresAmber,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                        )
                        opr.autoOpr?.let {
                            Text("Auto OPR: ${String.format(Locale.US, "%.1f", it)}", color = AresGold, fontSize = 11.sp)
                        }
                        opr.endgameOpr?.let {
                            Text("End OPR: ${String.format(Locale.US, "%.1f", it)}", color = AresGreen, fontSize = 11.sp)
                        }
                    } else {
                        Text("No official match OPR computed yet.", color = AresTextTertiary, fontSize = 11.sp)
                    }
                }

                if (standSummary != null) {
                    Text(
                        "Stand (${standSummary.observationsCount} logs · ${standSummary.distinctScouts.size} scout(s)): " +
                            "Auto ${(standSummary.autoSuccessRate * 100).toInt()}% · " +
                            "Cycles ${String.format(Locale.US, "%.1f", standSummary.meanCycles)} · " +
                            "Driver ${String.format(Locale.US, "%.1f", standSummary.avgDriverRating)}/5",
                        color = AresTextSecondary,
                        fontSize = 11.sp,
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = onEditPit, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
                    Text("Pit", fontSize = 11.sp, color = AresCyan)
                }
                OutlinedButton(onClick = onAddStand, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
                    Text("Stand", fontSize = 11.sp, color = AresGreen)
                }
                if (!isOurTeam) {
                    OutlinedButton(onClick = onOpenPicklist, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
                        Text("Picklist", fontSize = 11.sp, color = AresGold)
                    }
                }
            }
        }
    }
}

@Composable
internal fun StatBadgeChip(label: String, value: String, tint: Color) {
    Surface(
        color = AresSurfaceElevated,
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, tint.copy(alpha = 0.45f)),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, color = AresTextTertiary, fontSize = 10.sp)
            Text(value, color = tint, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        }
    }
}
