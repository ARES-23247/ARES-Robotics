@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.ares.analytics.ui.components.history

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FactCheck
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Save
import com.ares.analytics.domain.pit.extractBatteryIdFromTags
import com.ares.analytics.domain.pit.isBatterySessionTag
import com.ares.analytics.domain.pit.normalizeBatteryId
import com.ares.analytics.domain.pit.replaceBatteryTag
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ares.analytics.shared.models.Session
import com.ares.analytics.ui.theme.AresBorder
import com.ares.analytics.ui.theme.AresCyan
import com.ares.analytics.ui.theme.AresGreen
import com.ares.analytics.ui.theme.AresOnAccent
import com.ares.analytics.ui.theme.AresSurface
import com.ares.analytics.ui.theme.AresTextPrimary
import com.ares.analytics.ui.theme.AresTextSecondary
import com.ares.analytics.ui.theme.AresTextTertiary

@Composable
fun SessionMetadataDialog(
    session: Session,
    existingWorkspaceTags: List<String> = emptyList(),
    onSaveMetadata: (matchNumber: Int?, allianceColor: String?, tags: List<String>) -> Unit,
    onOpenReplay: (String) -> Unit,
    onOpenGuidedReview: (String) -> Unit,
    onOpenMatchStrategy: () -> Unit,
    onLogRunToNotebook: (() -> Unit)? = null,
    onDismiss: () -> Unit,
) {
    var matchText by remember(session.sessionId) {
        mutableStateOf(session.matchNumber?.toString().orEmpty())
    }
    var alliance by remember(session.sessionId) {
        mutableStateOf(session.allianceColor?.lowercase()?.takeIf { it == "red" || it == "blue" })
    }
    var tagsText by remember(session.sessionId) {
        mutableStateOf(session.tags.filterNot(::isBatterySessionTag).joinToString(", "))
    }
    var batteryIdText by remember(session.sessionId) {
        mutableStateOf(extractBatteryIdFromTags(session.tags).orEmpty())
    }

    fun parseCurrentTags(): List<String> = tagsText
        .split(',', ';')
        .map(String::trim)
        .filter(String::isNotBlank)
        .filterNot(::isBatterySessionTag)
        .distinct()

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = AresSurface,
        shape = RoundedCornerShape(14.dp),
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    session.matchNumber?.let { "Match #$it Metadata & Quick Actions" }
                        ?: "Run Metadata & Quick Actions",
                    color = AresTextPrimary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                )
                Text(
                    "Session ID: ${session.sessionId}",
                    color = AresTextTertiary,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
        },
        text = {
            Column(
                modifier = Modifier.width(520.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = matchText,
                        onValueChange = { raw -> matchText = raw.filter(Char::isDigit).take(4) },
                        label = { Text("Match # (optional)") },
                        singleLine = true,
                        modifier = Modifier.width(145.dp),
                    )
                    OutlinedTextField(
                        value = batteryIdText,
                        onValueChange = { raw -> batteryIdText = normalizeBatteryId(raw).take(20) },
                        label = { Text("Battery Pack ID") },
                        placeholder = { Text("BAT-01") },
                        leadingIcon = { Icon(Icons.Default.Bolt, contentDescription = null, modifier = Modifier.size(15.dp)) },
                        singleLine = true,
                        modifier = Modifier.width(160.dp),
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Alliance", color = AresTextSecondary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf(null to "Practice", "red" to "Red", "blue" to "Blue").forEach { (key, label) ->
                                FilterChip(
                                    selected = alliance == key,
                                    onClick = { alliance = key },
                                    label = { Text(label, fontSize = 11.sp) },
                                )
                            }
                        }
                    }
                }

                val batteryTagsInWorkspace = existingWorkspaceTags.filter(::isBatterySessionTag)
                if (batteryTagsInWorkspace.isNotEmpty()) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            "Quick battery pack:",
                            color = AresTextTertiary,
                            fontSize = 11.sp,
                            modifier = Modifier.align(Alignment.CenterVertically),
                        )
                        batteryTagsInWorkspace.take(8).forEach { tag ->
                            val rawId = extractBatteryIdFromTags(listOf(tag)).orEmpty()
                            val selected = rawId.isNotEmpty() && batteryIdText.equals(rawId, ignoreCase = true)
                            FilterChip(
                                selected = selected,
                                onClick = { batteryIdText = if (selected) "" else rawId },
                                label = { Text(rawId.ifBlank { tag }, fontSize = 11.sp) },
                            )
                        }
                    }
                }

                OutlinedTextField(
                    value = tagsText,
                    onValueChange = { tagsText = it },
                    label = { Text("Cohort & Experiment Tags (comma-separated)") },
                    placeholder = { Text("pid-v2, fresh-battery, comp-quals") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                val nonBatteryTags = existingWorkspaceTags.filterNot(::isBatterySessionTag)
                if (nonBatteryTags.isNotEmpty()) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            "Quick toggle tag:",
                            color = AresTextTertiary,
                            fontSize = 11.sp,
                            modifier = Modifier.align(Alignment.CenterVertically),
                        )
                        val activeTags = parseCurrentTags()
                        nonBatteryTags.take(10).forEach { tag ->
                            val present = activeTags.any { it.equals(tag, ignoreCase = true) }
                            FilterChip(
                                selected = present,
                                onClick = {
                                    val updated = if (present) {
                                        activeTags.filterNot { it.equals(tag, ignoreCase = true) }
                                    } else {
                                        activeTags + tag
                                    }
                                    tagsText = updated.joinToString(", ")
                                },
                                label = { Text(tag, fontSize = 11.sp) },
                            )
                        }
                    }
                }

                HorizontalDivider(color = AresBorder)

                Text(
                    "Jump with this run",
                    color = AresTextSecondary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = {
                            onDismiss()
                            onOpenReplay(session.sessionId)
                        },
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp), tint = AresCyan)
                        Spacer(Modifier.width(5.dp))
                        Text("Open Replay", fontSize = 12.sp)
                    }
                    OutlinedButton(
                        onClick = {
                            onDismiss()
                            onOpenGuidedReview(session.sessionId)
                        },
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    ) {
                        Icon(Icons.AutoMirrored.Filled.FactCheck, contentDescription = null, modifier = Modifier.size(16.dp), tint = AresCyan)
                        Spacer(Modifier.width(5.dp))
                        Text("Guided Run Review", fontSize = 12.sp)
                    }
                    OutlinedButton(
                        onClick = {
                            onDismiss()
                            onOpenMatchStrategy()
                        },
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    ) {
                        Icon(Icons.Default.Explore, contentDescription = null, modifier = Modifier.size(16.dp), tint = AresCyan)
                        Spacer(Modifier.width(5.dp))
                        Text("Match Strategy", fontSize = 12.sp)
                    }
                    if (onLogRunToNotebook != null) {
                        Button(
                            onClick = {
                                onDismiss()
                                onLogRunToNotebook()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = AresGreen, contentColor = AresOnAccent),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        ) {
                            Icon(Icons.Default.Book, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(5.dp))
                            Text("Log Run to Notebook", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val parsedMatch = matchText.trim().toIntOrNull()?.takeIf { it > 0 }
                    val parsedTags = replaceBatteryTag(parseCurrentTags(), batteryIdText)
                    onSaveMetadata(parsedMatch, alliance, parsedTags)
                    onDismiss()
                },
                colors = ButtonDefaults.buttonColors(containerColor = AresCyan, contentColor = AresOnAccent),
            ) {
                Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Save Metadata", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Close", color = AresTextSecondary)
            }
        },
    )
}
