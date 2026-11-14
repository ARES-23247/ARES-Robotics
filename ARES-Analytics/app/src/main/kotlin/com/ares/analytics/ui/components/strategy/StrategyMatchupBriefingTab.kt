@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.ares.analytics.ui.components.strategy

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Warning
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
import com.ares.analytics.service.ExportService
import com.ares.analytics.service.MatchInfo
import com.ares.analytics.service.integration.EngineeringNotebookCaptureService
import com.ares.analytics.shared.models.WorkspaceConfig
import com.ares.analytics.ui.theme.*
import com.ares.analytics.ui.util.DesktopFileChoosers
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Tab 2 inside Competition Strategy Hub: Pre-Match Alliance Matchup Briefing comparing projected
 * NP-OPR scores, partner auto-zone conflicts, phase advantages, and coaching talking points.
 */
@Composable
internal fun StrategyMatchupBriefingTab(
    workspace: WorkspaceConfig?,
    matches: List<MatchInfo>,
    bundle: EventScoutingBundle,
    oprSnapshot: EventOprSnapshot,
    standSummaries: Map<String, TeamStandScoutingSummary>,
    selectedMatchNumber: Int?,
    onSelectMatchNumber: (Int) -> Unit,
    exportService: ExportService?,
    notebookCaptureService: EngineeringNotebookCaptureService?,
    onScoutTeam: (String) -> Unit,
    onStatusMessage: (String) -> Unit,
    onLoggedNotebookEntry: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val eventLabel = workspace?.eventCode?.takeIf(String::isNotBlank) ?: "Local Event"
    val cleanOur = workspace?.teamId?.let(::normalizeTeamNumber)?.takeIf(String::isNotBlank)

    val combinedMatches = remember(matches, bundle.matchResults) {
        val byKey = LinkedHashMap<String, MatchInfo>()
        matches.forEach { m ->
            byKey["${m.compLevel.lowercase(Locale.US)}:${m.matchNumber}"] = m
        }
        bundle.matchResults.forEach { res ->
            val key = res.normalizedKey()
            if (key !in byKey) {
                byKey[key] = MatchInfo(
                    matchNumber = res.matchNumber,
                    compLevel = res.compLevel,
                    redAlliance = res.redTeams,
                    blueAlliance = res.blueTeams,
                    scheduledTime = res.scheduledTimeMs,
                )
            }
        }
        byKey.values.sortedWith(compareBy<MatchInfo> { it.compLevel }.thenBy { it.matchNumber })
    }

    val activeMatch = remember(combinedMatches, selectedMatchNumber, cleanOur) {
        combinedMatches.firstOrNull { it.matchNumber == selectedMatchNumber }
            ?: cleanOur?.let { ours ->
                combinedMatches.firstOrNull { m ->
                    m.redAlliance.any { normalizeTeamNumber(it) == ours } ||
                        m.blueAlliance.any { normalizeTeamNumber(it) == ours }
                }
            }
            ?: combinedMatches.firstOrNull()
    }

    val pitByTeam = remember(bundle.pitProfiles) {
        bundle.pitProfiles.associateBy { normalizeTeamNumber(it.teamNumber) }
    }
    val resultByKey = remember(bundle.matchResults) {
        bundle.matchResults.associateBy { it.normalizedKey() }
    }

    Column(modifier = modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (combinedMatches.isEmpty() || activeMatch == null) {
            Card(
                modifier = Modifier.fillMaxWidth().weight(1f),
                colors = CardDefaults.cardColors(containerColor = AresSurface),
                border = BorderStroke(1.dp, AresBorder),
                shape = RoundedCornerShape(10.dp),
            ) {
                Box(Modifier.fillMaxSize().padding(28.dp), contentAlignment = Alignment.Center) {
                    Text(
                        "No scheduled matches cached yet. Sync the schedule in Pit Match Queue or import/sync event scores in OPR & Scouting to generate pre-match briefings.",
                        color = AresTextSecondary,
                        fontSize = 12.sp,
                    )
                }
            }
            return
        }

        val matchKey = "${activeMatch.compLevel.trim().lowercase(Locale.US).ifBlank { "quals" }}:${activeMatch.matchNumber}"
        val report = remember(activeMatch, cleanOur, oprSnapshot, pitByTeam, standSummaries, resultByKey[matchKey]) {
            OprAndSynergyAnalyzer.buildMatchupBriefing(
                matchNumber = activeMatch.matchNumber,
                compLevel = activeMatch.compLevel,
                redAlliance = activeMatch.redAlliance,
                blueAlliance = activeMatch.blueAlliance,
                ourTeamNumber = cleanOur,
                oprSnapshot = oprSnapshot,
                pitProfiles = pitByTeam,
                standSummaries = standSummaries,
                recordedResult = resultByKey[matchKey],
            )
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
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
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            "Pre-Match Strategy Briefing — ${report.compLevel.uppercase(Locale.US)} #${report.matchNumber}",
                            color = AresTextPrimary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                        )
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            combinedMatches.take(18).forEach { candidate ->
                                val isOurs = cleanOur != null && (
                                    candidate.redAlliance.any { normalizeTeamNumber(it) == cleanOur } ||
                                        candidate.blueAlliance.any { normalizeTeamNumber(it) == cleanOur }
                                    )
                                FilterChip(
                                    selected = candidate.matchNumber == activeMatch.matchNumber &&
                                        candidate.compLevel.equals(activeMatch.compLevel, ignoreCase = true),
                                    onClick = { onSelectMatchNumber(candidate.matchNumber) },
                                    label = {
                                        Text(
                                            if (isOurs) "*M#${candidate.matchNumber}" else "M#${candidate.matchNumber}",
                                            fontSize = 11.sp,
                                            fontFamily = FontFamily.Monospace,
                                        )
                                    },
                                )
                            }
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(
                            onClick = {
                                val md = EventScoutingCodecAndExporters.buildMatchupBriefingMarkdown(report, eventLabel)
                                runCatching {
                                    Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(md), null)
                                }
                                onStatusMessage("Copied Match #${report.matchNumber} strategy briefing.")
                            },
                        ) {
                            Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Copy", fontSize = 11.sp)
                        }
                        if (exportService != null) {
                            OutlinedButton(
                                onClick = {
                                    scope.launch {
                                        val md = EventScoutingCodecAndExporters.buildMatchupBriefingMarkdown(report, eventLabel)
                                        val dest = withContext(Dispatchers.IO) {
                                            DesktopFileChoosers.chooseSaveFile(
                                                dialogTitle = "Export Pre-Match Briefing (.md)",
                                                defaultFileName = "matchup_${report.compLevel}_${report.matchNumber}.md",
                                                filterDescription = "Markdown (*.md)",
                                                extensions = listOf("md"),
                                            )
                                        } ?: return@launch
                                        runCatching { exportService.writeTextReportAtomically(dest, md) }
                                            .onSuccess { onStatusMessage("Saved briefing: ${dest.name}") }
                                            .onFailure { onStatusMessage("Export failed: ${it.message}") }
                                    }
                                },
                            ) {
                                Icon(Icons.Default.Description, contentDescription = null, modifier = Modifier.size(14.dp), tint = AresCyan)
                                Spacer(Modifier.width(4.dp))
                                Text("Export .md", fontSize = 11.sp)
                            }
                        }
                        if (workspace != null && notebookCaptureService != null) {
                            Button(
                                onClick = {
                                    scope.launch {
                                        runCatching {
                                            notebookCaptureService.capturePreMatchStrategyPlan(workspace, report)
                                        }.onSuccess { saved ->
                                            onLoggedNotebookEntry(saved.entryId)
                                            onStatusMessage("Logged ${saved.entryId} r${saved.revision} to Engineering Notebook.")
                                        }.onFailure { err ->
                                            onStatusMessage("Notebook capture failed: ${err.message}")
                                        }
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = AresCyan, contentColor = AresOnAccent),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                            ) {
                                Icon(Icons.Default.Book, contentDescription = null, modifier = Modifier.size(14.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Log Pre-Match Plan to Notebook", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    StatBadgeChip(
                        "Projected Red NP",
                        String.format(Locale.US, "%.1f", report.projectedRedNpScore),
                        AresRed,
                    )
                    StatBadgeChip(
                        "Projected Blue NP",
                        String.format(Locale.US, "%.1f", report.projectedBlueNpScore),
                        Color(0xFF448AFF),
                    )
                    report.projectedMarginForOurAlliance?.let { margin ->
                        StatBadgeChip(
                            "Our Projected Margin",
                            String.format(Locale.US, "%+.1f", margin),
                            if (margin >= 0.0) AresGreen else AresAmber,
                        )
                    }
                    report.recordedResult?.takeIf { it.isPlayed }?.let { res ->
                        StatBadgeChip("Final Score", "${res.redTotalScore} - ${res.blueTotalScore}", AresGold)
                    }
                }

                report.partnerAutoZoneWarning?.let { warning ->
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = AresAmber.copy(alpha = 0.14f),
                        border = BorderStroke(1.dp, AresAmber.copy(alpha = 0.65f)),
                        shape = RoundedCornerShape(8.dp),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Default.Warning, contentDescription = null, tint = AresAmber, modifier = Modifier.size(16.dp))
                            Text(warning, color = AresAmber, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    AllianceBriefingColumn(
                        title = "RED ALLIANCE (${report.redTeams.size})",
                        accent = AresRed,
                        teams = report.redTeams,
                        onScoutTeam = onScoutTeam,
                        modifier = Modifier.weight(1f),
                    )
                    AllianceBriefingColumn(
                        title = "BLUE ALLIANCE (${report.blueTeams.size})",
                        accent = Color(0xFF448AFF),
                        teams = report.blueTeams,
                        onScoutTeam = onScoutTeam,
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = AresSurface),
                    border = BorderStroke(1.dp, AresBorder),
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            "PRE-MATCH TALKING POINTS",
                            color = AresGold,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                        )
                        report.talkingPoints.forEach { point ->
                            Text("• $point", color = AresTextPrimary, fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AllianceBriefingColumn(
    title: String,
    accent: Color,
    teams: List<MatchupTeamBrief>,
    onScoutTeam: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = AresSurface),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.55f)),
        shape = RoundedCornerShape(10.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, color = accent, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            teams.forEach { t ->
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = AresSurfaceElevated,
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, if (t.isOurTeam) AresGold else AresBorder),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(
                                "Team ${t.teamNumber}" + if (t.isOurTeam) " (US)" else "",
                                color = if (t.isOurTeam) AresGold else AresTextPrimary,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                                fontFamily = FontFamily.Monospace,
                            )
                            val np = t.opr?.npOpr?.let { String.format(Locale.US, "%.1f NP-OPR", it) } ?: "No OPR"
                            val ccwm = t.opr?.ccwm?.let { String.format(Locale.US, "%+.1f ccWM", it) } ?: ""
                            Text("$np $ccwm", color = AresCyan, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                            Text(
                                "Auto: ${t.pitProfile?.preferredAutoZone?.label ?: "Unverified"} · Endgame: ${t.pitProfile?.endgameCapability?.label ?: "Unknown"}",
                                color = AresTextSecondary,
                                fontSize = 11.sp,
                            )
                        }
                        OutlinedButton(onClick = { onScoutTeam(t.teamNumber) }, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
                            Text("Scout Team", fontSize = 10.sp)
                        }
                    }
                }
            }
        }
    }
}
