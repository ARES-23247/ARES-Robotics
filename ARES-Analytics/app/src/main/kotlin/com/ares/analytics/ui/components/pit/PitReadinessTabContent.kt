@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.ares.analytics.ui.components.pit

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ares.analytics.domain.pit.normalizeBatteryId
import com.ares.analytics.ui.screens.PitAllianceColor
import com.ares.analytics.ui.screens.PitManualCheckItem
import com.ares.analytics.ui.screens.PitMatchTag
import com.ares.analytics.ui.screens.PitMatchType
import com.ares.analytics.ui.screens.SelfTestStatus
import com.ares.analytics.ui.screens.SelfTestStep
import com.ares.analytics.ui.theme.AresBorder
import com.ares.analytics.ui.theme.AresCyan
import com.ares.analytics.ui.theme.AresGold
import com.ares.analytics.ui.theme.AresGreen
import com.ares.analytics.ui.theme.AresOnAccent
import com.ares.analytics.ui.theme.AresRed
import com.ares.analytics.ui.theme.AresSurface
import com.ares.analytics.ui.theme.AresSurfaceElevated
import com.ares.analytics.ui.theme.AresTextPrimary
import com.ares.analytics.ui.theme.AresTextSecondary

@Composable
internal fun PitReadinessTabContent(
    projectLabel: String,
    connected: Boolean,
    streamLive: Boolean,
    steps: List<SelfTestStep>,
    manualChecklist: List<PitManualCheckItem>,
    matchTag: PitMatchTag,
    recommendedBatteryId: String?,
    hasNextScheduledMatch: Boolean,
    receiptStatusMessage: String?,
    lastLoggedEntryId: String?,
    onMatchTagChange: (PitMatchTag) -> Unit,
    onFillNextMatch: (() -> Unit)?,
    onRefreshSignals: () -> Unit,
    onToggleManualCheck: (String) -> Unit,
    onResetChecklist: () -> Unit,
    onExportMarkdown: () -> Unit,
    onCopyMarkdown: () -> Unit,
    onLogReceiptToNotebook: (() -> Unit)?,
    onOpenNotebookEntry: ((String) -> Unit)?,
) {
    val observedCount = steps.count { it.status == SelfTestStatus.OBSERVED }
    val manualCheckedCount = manualChecklist.count { it.checked }

    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Card(
            colors = CardDefaults.cardColors(containerColor = AresSurface),
            border = BorderStroke(1.dp, AresBorder),
            shape = RoundedCornerShape(10.dp),
        ) {
            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                    Text(
                        "$projectLabel · Passive NT4 signals + human pit sign-off",
                        color = AresTextSecondary,
                        fontSize = 12.sp,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PitReadinessStatusPill(
                            when {
                                !connected -> "OFFLINE"
                                streamLive -> "STREAM LIVE"
                                else -> "STREAM STALE"
                            },
                            if (streamLive) AresGreen else AresRed,
                        )
                        PitReadinessStatusPill("$observedCount/${steps.size} SIGNALS", AresCyan)
                        PitReadinessStatusPill(
                            "$manualCheckedCount/${manualChecklist.size} CREW CHECKS",
                            if (manualCheckedCount == manualChecklist.size) AresGreen else AresGold,
                        )
                        IconButton(onClick = onRefreshSignals) {
                            Icon(Icons.Default.Refresh, "Refresh readiness signals", tint = AresTextSecondary)
                        }
                    }
                }

                PitMatchTagAndExportBar(
                    matchTag = matchTag,
                    recommendedBatteryId = recommendedBatteryId,
                    hasNextScheduledMatch = hasNextScheduledMatch,
                    onMatchTagChange = onMatchTagChange,
                    onFillNextMatch = onFillNextMatch,
                    onExportMarkdown = onExportMarkdown,
                    onCopyMarkdown = onCopyMarkdown,
                    onLogReceiptToNotebook = onLogReceiptToNotebook,
                )

                Surface(
                    color = AresGold.copy(alpha = 0.10f),
                    shape = RoundedCornerShape(7.dp),
                    border = BorderStroke(1.dp, AresGold.copy(alpha = 0.45f)),
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(10.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Default.Warning, null, tint = AresGold, modifier = Modifier.size(18.dp))
                        Text(
                            "Signal presence is not a hardware certification. This page never pulses motors or declares the robot match-ready.",
                            color = AresTextPrimary,
                            fontSize = 12.sp,
                        )
                    }
                }

                receiptStatusMessage?.let { msg ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(msg, fontSize = 11.sp, color = AresGreen, fontWeight = FontWeight.SemiBold)
                        if (lastLoggedEntryId != null && onOpenNotebookEntry != null) {
                            OutlinedButton(
                                onClick = { onOpenNotebookEntry(lastLoggedEntryId) },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 3.dp),
                            ) {
                                Text("Open in Notebook", fontSize = 10.sp, color = AresCyan)
                            }
                        }
                    }
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().weight(1f),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Card(
                modifier = Modifier.weight(1.15f).fillMaxHeight(),
                colors = CardDefaults.cardColors(containerColor = AresSurface),
                border = BorderStroke(1.dp, AresBorder),
                shape = RoundedCornerShape(10.dp),
            ) {
                Column(Modifier.fillMaxSize().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "PASSIVE NT4 SIGNAL OBSERVATIONS (READ-ONLY)",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = AresCyan,
                    )
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(steps, key = SelfTestStep::id) { step -> ReadinessSignalCard(step) }
                    }
                }
            }

            Card(
                modifier = Modifier.weight(1f).fillMaxHeight(),
                colors = CardDefaults.cardColors(containerColor = AresSurface),
                border = BorderStroke(1.dp, AresBorder),
                shape = RoundedCornerShape(10.dp),
            ) {
                Column(Modifier.fillMaxSize().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "MANUAL PIT CREW PHYSICAL SIGN-OFFS",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = AresGold,
                        )
                        TextButton(
                            onClick = onResetChecklist,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        ) {
                            Text("Reset For Next Match", fontSize = 11.sp, color = AresTextSecondary)
                        }
                    }

                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(manualChecklist, key = PitManualCheckItem::id) { item ->
                            ManualPitCheckRow(
                                item = item,
                                onToggle = { onToggleManualCheck(item.id) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PitMatchTagAndExportBar(
    matchTag: PitMatchTag,
    recommendedBatteryId: String?,
    hasNextScheduledMatch: Boolean,
    onMatchTagChange: (PitMatchTag) -> Unit,
    onFillNextMatch: (() -> Unit)?,
    onExportMarkdown: () -> Unit,
    onCopyMarkdown: () -> Unit,
    onLogReceiptToNotebook: (() -> Unit)?,
) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PitMatchType.entries.forEach { type ->
                val selected = matchTag.matchType == type
                OutlinedButton(
                    onClick = { onMatchTagChange(matchTag.copy(matchType = type)) },
                    shape = RoundedCornerShape(6.dp),
                    border = BorderStroke(1.dp, if (selected) AresCyan else AresBorder),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    modifier = Modifier.height(28.dp),
                ) {
                    Text(type.label, fontSize = 11.sp, color = if (selected) AresCyan else AresTextSecondary, fontWeight = FontWeight.SemiBold)
                }
            }

            OutlinedTextField(
                value = matchTag.matchNumber,
                onValueChange = { onMatchTagChange(matchTag.copy(matchNumber = it.take(6))) },
                modifier = Modifier.width(82.dp),
                singleLine = true,
                placeholder = { Text("M#", fontSize = 11.sp) },
            )

            listOf(PitAllianceColor.BLUE, PitAllianceColor.RED).forEach { alliance ->
                val selected = matchTag.alliance == alliance
                val tint = if (alliance == PitAllianceColor.BLUE) Color(0xFF448AFF) else AresRed
                OutlinedButton(
                    onClick = {
                        onMatchTagChange(
                            matchTag.copy(alliance = if (selected) PitAllianceColor.UNASSIGNED else alliance)
                        )
                    },
                    shape = RoundedCornerShape(6.dp),
                    border = BorderStroke(1.dp, if (selected) tint else AresBorder),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = if (selected) tint.copy(alpha = 0.16f) else Color.Transparent,
                    ),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    modifier = Modifier.height(28.dp),
                ) {
                    Text(alliance.label, fontSize = 11.sp, color = if (selected) tint else AresTextSecondary, fontWeight = FontWeight.Bold)
                }
            }

            OutlinedTextField(
                value = matchTag.batteryId,
                onValueChange = { raw -> onMatchTagChange(matchTag.copy(batteryId = normalizeBatteryId(raw).take(20))) },
                modifier = Modifier.width(106.dp),
                singleLine = true,
                placeholder = { Text(recommendedBatteryId ?: "Pack ID", fontSize = 11.sp) },
                leadingIcon = { Icon(Icons.Default.Bolt, contentDescription = null, modifier = Modifier.size(14.dp), tint = AresGold) },
            )

            OutlinedTextField(
                value = matchTag.inspectorInitials,
                onValueChange = { onMatchTagChange(matchTag.copy(inspectorInitials = it.take(16))) },
                modifier = Modifier.width(106.dp),
                singleLine = true,
                placeholder = { Text("Crew initials", fontSize = 11.sp) },
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (hasNextScheduledMatch && onFillNextMatch != null) {
                OutlinedButton(
                    onClick = onFillNextMatch,
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, AresGold.copy(alpha = 0.65f)),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                ) {
                    Icon(Icons.Default.Schedule, contentDescription = null, tint = AresGold, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Fill Next Match", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = AresGold)
                }
            }
            OutlinedButton(
                onClick = onExportMarkdown,
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(1.dp, AresGreen.copy(alpha = 0.65f)),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
            ) {
                Icon(Icons.Default.Description, contentDescription = null, tint = AresGreen, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(4.dp))
                Text("Export Receipt (.md)", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = AresGreen)
            }
            OutlinedButton(
                onClick = onCopyMarkdown,
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(1.dp, AresBorder),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
            ) {
                Icon(Icons.Default.ContentCopy, contentDescription = null, tint = AresTextSecondary, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(4.dp))
                Text("Copy", fontSize = 11.sp, color = AresTextPrimary)
            }
            if (onLogReceiptToNotebook != null) {
                Button(
                    onClick = onLogReceiptToNotebook,
                    colors = ButtonDefaults.buttonColors(containerColor = AresCyan, contentColor = AresOnAccent),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                ) {
                    Icon(Icons.Default.Book, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Log to Notebook", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun ManualPitCheckRow(
    item: PitManualCheckItem,
    onToggle: () -> Unit,
) {
    val borderColor = if (item.checked) AresGreen.copy(alpha = 0.65f) else AresBorder
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onToggle),
        color = AresSurfaceElevated,
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, borderColor),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Checkbox(
                checked = item.checked,
                onCheckedChange = { onToggle() },
                colors = CheckboxDefaults.colors(checkedColor = AresGreen),
            )
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(item.title, color = AresTextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                Text(item.detail, color = AresTextSecondary, fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun ReadinessSignalCard(step: SelfTestStep) {
    val color = when (step.status) {
        SelfTestStatus.OBSERVED -> AresGreen
        SelfTestStatus.WARNING -> AresGold
        SelfTestStatus.WAITING -> AresTextSecondary
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = AresSurfaceElevated),
        border = BorderStroke(1.dp, if (step.status == SelfTestStatus.WAITING) AresBorder else color.copy(alpha = 0.65f)),
        shape = RoundedCornerShape(8.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                Text(step.name, color = AresTextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                PitReadinessStatusPill(step.status.name, color)
            }
            Text(step.description, color = AresTextSecondary, fontSize = 11.sp)
            Text(step.details, color = color, fontSize = 11.sp)
        }
    }
}

@Composable
private fun PitReadinessStatusPill(label: String, color: Color) {
    Surface(color = color.copy(alpha = 0.12f), shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, color.copy(alpha = 0.6f))) {
        Text(label, color = color, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp))
    }
}
