@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.ares.analytics.ui.components.strategy

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ares.analytics.domain.strategy.*
import com.ares.analytics.service.ExportService
import com.ares.analytics.service.integration.EngineeringNotebookCaptureService
import com.ares.analytics.shared.models.WorkspaceConfig
import com.ares.analytics.ui.theme.*
import com.ares.analytics.ui.util.DesktopFileChoosers
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Tab 3 inside Competition Strategy Hub: Alliance Selection Picklist with coach preset weights,
 * auto-lane synergy alerts, manual tier & pin controls, live draft struck-through filtering,
 * and printable 1-page competition floor sheet exports.
 */
@Composable
internal fun StrategyAlliancePicklistTab(
    workspace: WorkspaceConfig?,
    bundle: EventScoutingBundle,
    oprSnapshot: EventOprSnapshot,
    rankedRows: List<RankedPicklistTeamRow>,
    highlightedTeamNumber: String?,
    exportService: ExportService?,
    notebookCaptureService: EngineeringNotebookCaptureService?,
    onChangePreset: (PicklistWeightPreset) -> Unit,
    onSaveOverride: (TeamPicklistOverride) -> Unit,
    onStatusMessage: (String) -> Unit,
    onLoggedNotebookEntry: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var hideTaken by remember { mutableStateOf(false) }

    val visibleRows = remember(rankedRows, hideTaken) {
        if (!hideTaken) rankedRows
        else rankedRows.filter { it.override.draftStatus == AllianceDraftStatus.AVAILABLE }
    }
    val availableCount = rankedRows.count { it.override.draftStatus == AllianceDraftStatus.AVAILABLE }
    val takenCount = rankedRows.size - availableCount
    val eventLabel = workspace?.eventCode?.takeIf(String::isNotBlank) ?: "Local Event"
    val ourTeam = workspace?.teamId?.ifBlank { "Team" } ?: "Team"

    Column(modifier = modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            "Live Alliance Selection Picklist — $eventLabel",
                            color = AresTextPrimary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                        )
                        Text(
                            "Composite score blends NP-OPR, Auto/Endgame OPR, stand reliability penalties, and Auto-Zone compatibility.",
                            color = AresTextSecondary,
                            fontSize = 11.sp,
                        )
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(
                            selected = hideTaken,
                            onClick = { hideTaken = !hideTaken },
                            label = { Text("Hide Taken ($takenCount)", fontSize = 11.sp) },
                        )
                        if (exportService != null) {
                            OutlinedButton(
                                onClick = {
                                    scope.launch {
                                        val html = EventScoutingCodecAndExporters.buildPrintablePicklistHtml(
                                            eventLabel = eventLabel,
                                            ourTeam = ourTeam,
                                            preset = bundle.selectedPreset,
                                            confidence = oprSnapshot.confidence,
                                            rows = rankedRows,
                                        )
                                        val dest = withContext(Dispatchers.IO) {
                                            DesktopFileChoosers.chooseSaveFile(
                                                dialogTitle = "Export Floor Picklist (.html)",
                                                defaultFileName = "alliance_picklist_${eventLabel.lowercase(Locale.US)}.html",
                                                filterDescription = "Printable HTML (*.html)",
                                                extensions = listOf("html"),
                                            )
                                        } ?: return@launch
                                        runCatching { exportService.writeTextReportAtomically(dest, html) }
                                            .onSuccess { onStatusMessage("Saved printable floor picklist: ${dest.name}") }
                                            .onFailure { onStatusMessage("Export failed: ${it.message}") }
                                    }
                                },
                            ) {
                                Icon(Icons.Default.Code, contentDescription = null, modifier = Modifier.size(14.dp), tint = AresGreen)
                                Spacer(Modifier.width(4.dp))
                                Text("Export Floor Sheet (.html)", fontSize = 11.sp)
                            }
                            OutlinedButton(
                                onClick = {
                                    scope.launch {
                                        val csv = EventScoutingCodecAndExporters.exportPicklistAndOprCsv(rankedRows, oprSnapshot)
                                        val dest = withContext(Dispatchers.IO) {
                                            DesktopFileChoosers.chooseSaveFile(
                                                dialogTitle = "Export Picklist & OPR (.csv)",
                                                defaultFileName = "picklist_opr_${eventLabel.lowercase(Locale.US)}.csv",
                                                filterDescription = "CSV (*.csv)",
                                                extensions = listOf("csv"),
                                            )
                                        } ?: return@launch
                                        runCatching { exportService.writeTextReportAtomically(dest, csv) }
                                            .onSuccess { onStatusMessage("Saved CSV: ${dest.name}") }
                                            .onFailure { onStatusMessage("Export failed: ${it.message}") }
                                    }
                                },
                            ) {
                                Icon(Icons.Default.FileDownload, contentDescription = null, modifier = Modifier.size(14.dp), tint = AresCyan)
                                Spacer(Modifier.width(4.dp))
                                Text("Export .csv", fontSize = 11.sp)
                            }
                        }
                        if (workspace != null && notebookCaptureService != null) {
                            Button(
                                onClick = {
                                    scope.launch {
                                        runCatching {
                                            notebookCaptureService.captureAlliancePicklistRationale(
                                                workspace = workspace,
                                                preset = bundle.selectedPreset,
                                                confidence = oprSnapshot.confidence,
                                                rows = rankedRows,
                                            )
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
                                Text("Log Picklist to Notebook", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }

                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    PicklistWeightPreset.entries.forEach { preset ->
                        FilterChip(
                            selected = bundle.selectedPreset == preset,
                            onClick = { onChangePreset(preset) },
                            label = { Text(preset.label, fontSize = 11.sp) },
                        )
                    }
                    StatBadgeChip("Available", "$availableCount / ${rankedRows.size}", AresGreen)
                }
            }
        }

        if (visibleRows.isEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth().weight(1f),
                colors = CardDefaults.cardColors(containerColor = AresSurface),
                border = BorderStroke(1.dp, AresBorder),
                shape = RoundedCornerShape(10.dp),
            ) {
                Box(Modifier.fillMaxSize().padding(28.dp), contentAlignment = Alignment.Center) {
                    Text(
                        if (rankedRows.isEmpty()) "No candidate teams scored or scouted yet."
                        else "All candidate teams are marked as Captain/Picked. Uncheck 'Hide Taken' to view drafted teams.",
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
                items(visibleRows, key = { it.teamNumber }) { row ->
                    val tierRows = remember(rankedRows, row.override.tier) {
                        rankedRows.filter { it.override.tier == row.override.tier }
                    }
                    val idxInTier = tierRows.indexOfFirst { it.teamNumber == row.teamNumber }
                    PicklistCandidateCard(
                        row = row,
                        canMoveUp = idxInTier > 0,
                        canMoveDown = idxInTier in 0 until tierRows.lastIndex,
                        isHighlighted = highlightedTeamNumber != null && row.teamNumber.equals(highlightedTeamNumber, ignoreCase = true),
                        onUpdateTier = { newTier ->
                            onSaveOverride(
                                row.override.copy(
                                    teamNumber = row.teamNumber,
                                    tier = newTier,
                                    pinnedRank = null,
                                    updatedAtMs = System.currentTimeMillis(),
                                ),
                            )
                        },
                        onMovePin = { delta ->
                            if (idxInTier >= 0) {
                                val targetIdx = (idxInTier + delta).coerceIn(0, tierRows.lastIndex)
                                if (targetIdx != idxInTier) {
                                    val neighbor = tierRows[targetIdx]
                                    val neighborRank = (targetIdx + 1) * 10
                                    val movedRank = if (delta < 0) neighborRank - 5 else neighborRank + 5
                                    val nowMs = System.currentTimeMillis()
                                    if (neighbor.override.pinnedRank == null) {
                                        onSaveOverride(
                                            neighbor.override.copy(
                                                teamNumber = neighbor.teamNumber,
                                                pinnedRank = neighborRank,
                                                updatedAtMs = nowMs,
                                            ),
                                        )
                                    }
                                    onSaveOverride(
                                        row.override.copy(
                                            teamNumber = row.teamNumber,
                                            pinnedRank = movedRank,
                                            updatedAtMs = nowMs,
                                        ),
                                    )
                                }
                            }
                        },
                        onUpdateDraftStatus = { status ->
                            onSaveOverride(
                                row.override.copy(
                                    teamNumber = row.teamNumber,
                                    draftStatus = status,
                                    updatedAtMs = System.currentTimeMillis(),
                                ),
                            )
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun PicklistCandidateCard(
    row: RankedPicklistTeamRow,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    isHighlighted: Boolean,
    onUpdateTier: (PicklistTier) -> Unit,
    onMovePin: (Int) -> Unit,
    onUpdateDraftStatus: (AllianceDraftStatus) -> Unit,
) {
    val taken = row.override.draftStatus != AllianceDraftStatus.AVAILABLE
    val border = when {
        isHighlighted -> BorderStroke(2.dp, AresCyan)
        row.override.tier == PicklistTier.DO_NOT_PICK -> BorderStroke(1.dp, AresError.copy(alpha = 0.5f))
        taken -> BorderStroke(1.dp, AresBorder.copy(alpha = 0.5f))
        else -> BorderStroke(1.dp, AresBorder)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = if (taken) AresSurfaceElevated else AresSurface),
        border = border,
        shape = RoundedCornerShape(10.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FlowRow(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        "#${row.displayRank} · Team ${row.teamNumber}",
                        color = if (taken) AresTextTertiary else AresTextPrimary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                    StatBadgeChip("Score", String.format(Locale.US, "%.1f", row.compositePickScore), AresCyan)
                    row.opr?.let { opr ->
                        StatBadgeChip("NP-OPR", String.format(Locale.US, "%.1f", opr.npOpr), AresGreen)
                        StatBadgeChip("ccWM", String.format(Locale.US, "%+.1f", opr.ccwm), AresGold)
                    }
                    StatBadgeChip(
                        "Auto Fit",
                        row.autoZoneSynergyLabel,
                        if (row.hasAutoZoneCollision) AresAmber else AresGreen,
                    )
                    if (row.reliabilityPenaltyPoints > 0.0) {
                        StatBadgeChip(
                            "Penalty",
                            "-${String.format(Locale.US, "%.1f", row.reliabilityPenaltyPoints)} pts",
                            AresError,
                        )
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { onMovePin(-1) }, enabled = canMoveUp, modifier = Modifier.size(28.dp)) {
                        Icon(
                            Icons.Default.ArrowUpward,
                            contentDescription = "Move up",
                            tint = if (canMoveUp) AresCyan else AresTextTertiary,
                            modifier = Modifier.size(15.dp),
                        )
                    }
                    IconButton(onClick = { onMovePin(1) }, enabled = canMoveDown, modifier = Modifier.size(28.dp)) {
                        Icon(
                            Icons.Default.ArrowDownward,
                            contentDescription = "Move down",
                            tint = if (canMoveDown) AresTextSecondary else AresTextTertiary,
                            modifier = Modifier.size(15.dp),
                        )
                    }
                }
            }

            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                PicklistTier.entries.forEach { tier ->
                    FilterChip(
                        selected = row.override.tier == tier,
                        onClick = { onUpdateTier(tier) },
                        label = { Text(tier.shortBadge, fontSize = 10.sp) },
                    )
                }
                Spacer(Modifier.width(8.dp))
                listOf(
                    AllianceDraftStatus.AVAILABLE,
                    AllianceDraftStatus.ALLIANCE_CAPTAIN,
                    AllianceDraftStatus.PICKED,
                ).forEach { draft ->
                    FilterChip(
                        selected = row.override.draftStatus == draft,
                        onClick = { onUpdateDraftStatus(draft) },
                        label = { Text(draft.label, fontSize = 10.sp) },
                    )
                }
            }
        }
    }
}
