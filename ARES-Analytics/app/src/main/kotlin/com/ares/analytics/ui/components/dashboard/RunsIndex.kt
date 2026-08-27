package com.ares.analytics.ui.components.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ares.analytics.service.DatabaseService
import com.ares.analytics.shared.Session
import com.ares.analytics.shared.SessionAnnotation
import com.ares.analytics.shared.WorkspaceConfig
import com.ares.analytics.ui.theme.*
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RunsIndex(
    databaseService: DatabaseService,
    workspace: WorkspaceConfig,
    primarySessionId: String?,
    compareSessionId: String?,
    onSelectPrimary: (String) -> Unit,
    onSelectCompare: (String) -> Unit,
    modifier: Modifier = Modifier,
    reloadTrigger: Int = 0
) {
    val scope = rememberCoroutineScope()
    var sessions by remember { mutableStateOf<List<Session>>(emptyList()) }

    // Edit Notes and Tags state
    var editingSession by remember { mutableStateOf<Session?>(null) }
    var annotationText by remember { mutableStateOf("") }
    var tagsText by remember { mutableStateOf("") }
    var batteryLabel by remember { mutableStateOf("A") }

    // Helper function to reload sessions list
    fun reloadSessions() {
        scope.launch {
            sessions = databaseService.getSessionsForWorkspace(
                workspace.teamId,
                workspace.seasonId,
                workspace.robotId,
            )
        }
    }

    LaunchedEffect(reloadTrigger, workspace.teamId, workspace.seasonId, workspace.robotId) {
        reloadSessions()
    }

    LaunchedEffect(editingSession) {
        val session = editingSession ?: return@LaunchedEffect
        val annotations = databaseService.getAnnotations(session.sessionId)
        annotationText = annotations.firstOrNull()?.text ?: ""
        tagsText = session.tags.filter { !it.startsWith("battery-") }.joinToString(", ")
        batteryLabel = session.tags.firstOrNull { it.startsWith("battery-") }?.removePrefix("battery-") ?: "A"
    }
    val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }

    Column(
        modifier = modifier
            .fillMaxHeight()
            .clip(RoundedCornerShape(12.dp))
            .background(AresSurface)
            .border(1.dp, AresBorder, RoundedCornerShape(12.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(imageVector = Icons.Default.History, contentDescription = null, tint = AresCyan)
            Text(
                "Recorded Sessions",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = AresTextPrimary
            )
        }

        HorizontalDivider(color = AresBorder, thickness = 1.dp)

        if (sessions.isEmpty()) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text("No sessions recorded yet.", color = AresTextTertiary)
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(sessions) { session ->
                    val isPrimary = session.sessionId == primarySessionId
                    val isCompare = session.sessionId == compareSessionId
                    val borderCol = when {
                        isPrimary -> AresCyan
                        isCompare -> AresGold
                        else -> AresBorder
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(AresSurfaceElevated)
                            .border(1.dp, borderCol, RoundedCornerShape(8.dp))
                            .clickable { onSelectPrimary(session.sessionId) }
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    session.matchNumber?.let { "Match #$it" } ?: "Practice Run",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp,
                                    color = AresTextPrimary
                                )
                                session.allianceColor?.let { alliance ->
                                    val badgeColor = if (alliance.lowercase() == "red") AresRed else AresCyan
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(4.dp))
                                            .background(badgeColor.copy(alpha = 0.2f))
                                            .padding(horizontal = 6.dp, vertical = 2.dp)
                                    ) {
                                        Text(alliance.uppercase(), color = badgeColor, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                            Spacer(Modifier.height(4.dp))
                            Text(
                                dateFormat.format(Date(session.createdAt)),
                                fontSize = 11.sp,
                                color = AresTextSecondary
                            )
                            if (session.tags.isNotEmpty()) {
                                Spacer(Modifier.height(6.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    session.tags.forEach { tag ->
                                        val displayTag = if (tag.startsWith("battery-")) {
                                            "🔋 ${tag.removePrefix("battery-")}"
                                        } else tag
                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(4.dp))
                                                .background(AresBorder)
                                                .padding(horizontal = 6.dp, vertical = 2.dp)
                                        ) {
                                            Text(displayTag, color = AresTextSecondary, fontSize = 9.sp)
                                        }
                                    }
                                }
                            }
                        }

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            // Edit Notes and Tags button
                            IconButton(
                                onClick = { editingSession = session }
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Edit,
                                    contentDescription = "Edit Notes and Tags",
                                    tint = AresTextSecondary
                                )
                            }

                            // Select as Compare button
                            IconButton(
                                onClick = { onSelectCompare(session.sessionId) }
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Star,
                                    contentDescription = "Select for Comparison",
                                    tint = if (isCompare) AresGold else AresTextTertiary
                                )
                            }

                            // Delete button
                            IconButton(
                                onClick = {
                                    scope.launch {
                                        databaseService.deleteSession(session.sessionId)
                                        reloadSessions()
                                    }
                                }
                            ) {
                                Icon(imageVector = Icons.Default.Delete, contentDescription = "Delete", tint = AresError)
                            }
                        }
                    }
                }
            }
        }
    }

    // Annotation & Tag Edit Dialog
    editingSession?.let { session ->
        AlertDialog(
            onDismissRequest = { editingSession = null },
            title = { Text("Edit Run Metadata", color = AresTextPrimary, fontWeight = FontWeight.Bold) },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Session ID: ${session.sessionId.take(8)}...", fontSize = 11.sp, color = AresTextTertiary)

                    OutlinedTextField(
                        value = annotationText,
                        onValueChange = { annotationText = it },
                        label = { Text("Session Notes") },
                        modifier = Modifier.fillMaxWidth().height(100.dp),
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = AresCyan, unfocusedBorderColor = AresBorder)
                    )

                    OutlinedTextField(
                        value = tagsText,
                        onValueChange = { tagsText = it },
                        label = { Text("Custom Tags (comma separated)") },
                        placeholder = { Text("quals, autonomous, intake-jam") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = AresCyan, unfocusedBorderColor = AresBorder)
                    )

                    // Battery Label selector
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Battery Label:", fontSize = 13.sp, color = AresTextSecondary)
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            listOf("A", "B", "C", "D").forEach { label ->
                                FilterChip(
                                    selected = batteryLabel == label,
                                    onClick = { batteryLabel = label },
                                    label = { Text("Battery $label") },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = AresCyan,
                                        selectedLabelColor = AresOnAccent,
                                        containerColor = AresSurfaceElevated,
                                        labelColor = AresTextSecondary
                                    )
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val cleanTags = tagsText.split(",")
                            .map { it.trim() }
                            .filter { it.isNotEmpty() }
                            .toMutableList()
                        if (batteryLabel.isNotEmpty()) {
                            cleanTags.add("battery-$batteryLabel")
                        }

                        scope.launch {
                            val annotation = SessionAnnotation(
                                annotationId = java.util.UUID.randomUUID().toString(),
                                sessionId = session.sessionId,
                                text = annotationText,
                                createdAt = System.currentTimeMillis(),
                                authorId = "Pit Leader"
                            )
                            databaseService.insertAnnotation(annotation)
                            databaseService.updateSessionTags(session.sessionId, cleanTags)

                            editingSession = null
                            reloadSessions()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = AresCyan, contentColor = AresOnAccent)
                ) {
                    Text("Save", color = AresOnAccent, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { editingSession = null }) {
                    Text("Cancel", color = AresTextSecondary)
                }
            },
            containerColor = AresSurfaceElevated,
            shape = RoundedCornerShape(12.dp)
        )
    }
}
