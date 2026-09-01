package com.ares.analytics.ui.components.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ares.analytics.service.DatabaseService
import com.ares.analytics.service.AiDiagnosticsService
import com.ares.analytics.shared.models.AlertRecord
import com.ares.analytics.shared.models.ForensicsResponse
import com.ares.analytics.ui.components.core.AresEmptyState
import com.ares.analytics.ui.theme.*
import kotlinx.coroutines.launch

sealed class ForensicsState {

    object Idle : ForensicsState()

    object Loading : ForensicsState()

    data class Success(val response: ForensicsResponse) : ForensicsState()
    data class Error(val message: String) : ForensicsState()
}

@Composable
fun AiCoachPanel(
    databaseService: DatabaseService,
    aiDiagnosticsService: AiDiagnosticsService,
    sessionId: String?,
    onForensicsCompleted: (ForensicsResponse) -> Unit,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    var forensicsState by remember { mutableStateOf<ForensicsState>(ForensicsState.Idle) }

    LaunchedEffect(sessionId) {
        forensicsState = ForensicsState.Idle
    }

    Column(
        modifier = modifier
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
            Icon(imageVector = Icons.Default.AutoAwesome, contentDescription = null, tint = AresCyan)
            Text(
                "ARES Pit Diagnostics",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = AresTextPrimary
            )
        }

        HorizontalDivider(color = AresBorder, thickness = 1.dp)

        if (sessionId == null) {
            AresEmptyState(
                title = "Select a session to trigger AI Forensics.",
                modifier = Modifier.weight(1f).fillMaxWidth(),
                titleColor = AresTextTertiary,
                verticalArrangement = Arrangement.Center,
            )
            return
        }

        when (val state = forensicsState) {
            is ForensicsState.Idle -> {
                AresEmptyState(
                    title = "Trigger Vertex AI model to diagnose potential hardware faults and telemetry anomalies.",
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    titleFontSize = 12.sp,
                    verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
                ) {
                    Button(
                        onClick = {
                            scope.launch {
                                forensicsState = ForensicsState.Loading
                                try {
                                    // 1. Gather local data
                                    val summary = databaseService.getSessionSummary(sessionId)
                                    val alerts = databaseService.getAlerts(sessionId)
                                    val topology = databaseService.getTopology(summary?.robotId ?: "")

                                    // 2. Call gateway forensics api
                                    if (summary == null) {
                                        throw IllegalArgumentException("Session summary not found.")
                                    }
                                    val req = com.ares.analytics.shared.models.ForensicsRequest(
                                        teamId = summary.teamId,
                                        sessionId = sessionId,
                                        alerts = alerts,
                                        summary = summary,
                                        topology = topology,
                                        sysIdDrift = emptyMap() // Can be populated if drift calculation available
                                    )
                                    val response = aiDiagnosticsService.requestForensics(req)
                                    forensicsState = ForensicsState.Success(response)
                                    onForensicsCompleted(response)
                                } catch (e: Exception) {
                                    forensicsState = ForensicsState.Error(e.message ?: "Failed to process diagnostics.")
                                }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = AresCyan, contentColor = AresOnAccent),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("Analyze Telemetry", color = AresBackground, fontWeight = FontWeight.Bold)
                    }
                }
            }
            is ForensicsState.Loading -> {
                Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        CircularProgressIndicator(color = AresCyan)
                        Text("Running AI Pit Forensics...", color = AresTextSecondary, fontSize = 12.sp)
                    }
                }
            }
            is ForensicsState.Success -> {
                var activeTab by remember { mutableStateOf(0) }
                val checkedActions = remember { mutableStateMapOf<Int, Boolean>() }
                val chatHistory = remember { mutableStateListOf<Pair<String, String>>() }
                var userQuestion by remember { mutableStateOf("") }
                var isWaitingForReply by remember { mutableStateOf(false) }

                Column(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    PrimaryTabRow(
                        selectedTabIndex = activeTab,
                        containerColor = AresSurfaceElevated,
                        contentColor = AresCyan,
                                                divider = { HorizontalDivider(color = AresBorder) }
                    ) {
                        Tab(
                            selected = activeTab == 0,
                            onClick = { activeTab = 0 },
                            text = { Text("Diagnostics", fontSize = 12.sp, fontWeight = FontWeight.Bold) },
                            icon = { Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(16.dp)) }
                        )
                        Tab(
                            selected = activeTab == 1,
                            onClick = { activeTab = 1 },
                            text = { Text("Interactive Coach", fontSize = 12.sp, fontWeight = FontWeight.Bold) },
                            icon = { Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = null, modifier = Modifier.size(16.dp)) }
                        )
                    }

                    if (activeTab == 0) {
                        Column(
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text("PROBABLE ROOT CAUSE", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = AresTextSecondary)
                            Text(state.response.probableRootCause, fontSize = 12.sp, color = AresTextPrimary, fontWeight = FontWeight.SemiBold)

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("CONFIDENCE SCORE", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = AresTextSecondary)
                                val badgeColor = if (state.response.confidenceScore > 0.8) AresGreen else AresAmber
                                Text("${(state.response.confidenceScore * 100).toInt()}%", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = badgeColor)
                            }
                            LinearProgressIndicator(
                                progress = { state.response.confidenceScore.toFloat() },
                                color = AresCyan,
                                trackColor = AresBorder,
                                modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp))
                            )

                            Spacer(Modifier.height(4.dp))
                            Text("RECOMMENDED PIT ACTIONS", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = AresTextSecondary)

                            if (state.response.recommendedActions.isEmpty()) {
                                Text("No checklist recommendations generated.", fontSize = 11.sp, color = AresTextTertiary)
                            } else {
                                LazyColumn(
                                    modifier = Modifier.weight(1f).fillMaxWidth(),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    itemsIndexed(state.response.recommendedActions) { index, action ->
                                        val isChecked = checkedActions[index] ?: false
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .background(AresSurfaceElevated, RoundedCornerShape(6.dp))
                                                .border(1.dp, AresBorder, RoundedCornerShape(6.dp))
                                                .padding(8.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            Checkbox(
                                                checked = isChecked,
                                                onCheckedChange = { checkedActions[index] = it },
                                                colors = CheckboxDefaults.colors(
                                                    checkedColor = AresCyan,
                                                    uncheckedColor = AresBorder,
                                                    checkmarkColor = AresBackground
                                                ),
                                                modifier = Modifier.size(20.dp)
                                            )
                                            Text(
                                                text = action,
                                                fontSize = 11.sp,
                                                color = if (isChecked) AresTextTertiary else AresTextPrimary,
                                                textDecoration = if (isChecked) TextDecoration.LineThrough else TextDecoration.None
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    } else {
                        Column(
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            val listState = rememberLazyListState()
                            LaunchedEffect(chatHistory.size) {
                                if (chatHistory.isNotEmpty()) {
                                    listState.animateScrollToItem(chatHistory.size - 1)
                                }
                            }

                            LazyColumn(
                                state = listState,
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth()
                                    .background(AresSurfaceElevated, RoundedCornerShape(8.dp))
                                    .border(1.dp, AresBorder, RoundedCornerShape(8.dp))
                                    .padding(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                item {
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        verticalAlignment = Alignment.Top,
                                        modifier = Modifier.padding(bottom = 6.dp)
                                    ) {
                                        Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = AresCyan, modifier = Modifier.size(16.dp))
                                        Column {
                                            Text("ARES Pit Coach", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = AresCyan)
                                            Text("Ask me anything about this log run, the triggered alerts, or structural performance!", fontSize = 11.sp, color = AresTextSecondary)
                                        }
                                    }
                                }

                                items(chatHistory) { (role, message) ->
                                    val isUser = role == "user"
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
                                    ) {
                                        Column(
                                            modifier = Modifier
                                                .fillMaxWidth(0.85f)
                                                .background(
                                                    color = if (isUser) AresCyan.copy(alpha = 0.15f) else AresSurface,
                                                    shape = RoundedCornerShape(8.dp)
                                                )
                                                .border(1.dp, if (isUser) AresCyan else AresBorder, RoundedCornerShape(8.dp))
                                                .padding(8.dp)
                                        ) {
                                            Text(
                                                text = if (isUser) "You" else "Coach",
                                                fontSize = 9.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = if (isUser) AresCyan else AresTextSecondary
                                            )
                                            Text(text = message, fontSize = 11.sp, color = AresTextPrimary)
                                        }
                                    }
                                }

                                if (isWaitingForReply) {
                                    item {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.Start
                                        ) {
                                            CircularProgressIndicator(
                                                color = AresCyan,
                                                modifier = Modifier.size(16.dp),
                                                strokeWidth = 2.dp
                                            )
                                        }
                                    }
                                }
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                BasicTextField(
                                    value = userQuestion,
                                    onValueChange = { userQuestion = it },
                                    singleLine = true,
                                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = AresTextPrimary),
                                    cursorBrush = SolidColor(AresCyan),
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(36.dp)
                                        .background(AresSurface, RoundedCornerShape(6.dp))
                                        .border(1.dp, AresBorder, RoundedCornerShape(6.dp)),
                                    decorationBox = { innerTextField ->
                                        Row(
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .padding(horizontal = 8.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Box(modifier = Modifier.weight(1f)) {
                                                if (userQuestion.isEmpty()) {
                                                    Text("Ask Coach about this run...", fontSize = 11.sp, color = AresTextTertiary)
                                                }
                                                innerTextField()
                                            }
                                        }
                                    }
                                )

                                Button(
                                    onClick = {
                                        if (userQuestion.trim().isNotEmpty() && !isWaitingForReply) {
                                            val questionCopy = userQuestion.trim()
                                            chatHistory.add(Pair("user", questionCopy))
                                            userQuestion = ""
                                            isWaitingForReply = true
                                            scope.launch {
                                                try {
                                                    val summary = databaseService.getSessionSummary(sessionId) ?: throw IllegalStateException("Summary not found")
                                                    val alerts = databaseService.getAlerts(sessionId)
                                                    val topology = databaseService.getTopology(summary.robotId)
                                                    val req = com.ares.analytics.shared.models.ForensicsRequest(
                                                        teamId = summary.teamId,
                                                        sessionId = sessionId,
                                                        alerts = alerts,
                                                        summary = summary,
                                                        topology = topology
                                                    )
                                                    val reply = aiDiagnosticsService.requestChatCoach(req, questionCopy, chatHistory.toList())
                                                    chatHistory.add(Pair("coach", reply))
                                                } catch (e: Exception) {
                                                    chatHistory.add(Pair("coach", "Error: ${e.message ?: "Failed to get reply."}"))
                                                } finally {
                                                    isWaitingForReply = false
                                                }
                                            }
                                        }
                                    },
                                    enabled = userQuestion.trim().isNotEmpty() && !isWaitingForReply,
                                    modifier = Modifier.height(36.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = AresCyan, contentColor = AresOnAccent),
                                    shape = RoundedCornerShape(6.dp),
                                    contentPadding = PaddingValues(horizontal = 12.dp)
                                ) {
                                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null, tint = AresBackground, modifier = Modifier.size(16.dp))
                                }
                            }
                        }
                    }
                }
            }
            is ForensicsState.Error -> {
                Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(imageVector = Icons.Default.Info, contentDescription = null, tint = AresError)
                        Text(state.message, color = AresError, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}
