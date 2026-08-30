package com.ares.analytics.ui.screens

import com.ares.analytics.ui.components.history.RunDataDictionary

import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Chat
import androidx.compose.ui.graphics.SolidColor
import com.ares.analytics.service.DatabaseService
import com.ares.analytics.service.AiDiagnosticsService
import com.ares.analytics.shared.models.Session
import com.ares.analytics.shared.models.SessionSummary
import com.ares.analytics.shared.models.TelemetryFrame
import com.ares.analytics.shared.models.WorkspaceConfig
import com.ares.analytics.ui.components.history.RunFilterHeader
import com.ares.analytics.ui.components.history.RunDataCard
import com.ares.analytics.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.Cursor
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Defines a bottom border using drawBehind.
 */
fun Modifier.bottomBorder(width: Dp, color: Color) = drawBehind {
    val strokeWidth = width.toPx()
    val y = size.height - strokeWidth / 2
    drawLine(
        color = color,
        start = Offset(0f, y),
        end = Offset(size.width, y),
        strokeWidth = strokeWidth
    )
}

/**
 * Defines an end border using drawBehind.
 */
fun Modifier.endBorder(width: Dp, color: Color) = drawBehind {
    val strokeWidth = width.toPx()
    val x = size.width - strokeWidth / 2
    drawLine(
        color = color,
        start = Offset(x, 0f),
        end = Offset(x, size.height),
        strokeWidth = strokeWidth
    )
}

/**
 * Defines a row metric inside the Run History Spreadsheet.
 */
data class RowDefinition(
    val label: String,
    val category: String,
    val getValue: (Session, SessionSummary?, Map<String, Double>) -> String,
    val getNumericValue: (Session, SessionSummary?, Map<String, Double>) -> Double? = { _, _, _ -> null },
    val isAnomaly: (Double) -> Boolean = { false }
)

/**
 * Historical match run index, log inspector, and multi-run chart comparison screen.
 *
 * Browses historical match runs stored in DuckDB, rendering time-series telemetry plots (motor currents $A$, velocities $m/s$, headings $rad$),
 * log scrubbing replay controls, and AI log breakdown chat threads.
 *
 * @param databaseService Primary [DatabaseService] backing DuckDB telemetry queries.
 * @param aiDiagnosticsService AI interpretation boundary for read-only telemetry queries.
 * @param onOpenImports Opens the log-import workflow when no completed runs exist.
 * @param onOpenHelp Opens the beginner lesson that explains how to create and review a run.
 *
 * @see com.ares.analytics.service.db.TelemetryRepository
 * @see com.ares.analytics.service.db.RunEvidenceRepository
 * @see com.ares.analytics.service.ReplayEngineService
 */
@Composable
fun RunHistoryScreen(
    databaseService: DatabaseService,
    aiDiagnosticsService: AiDiagnosticsService,
    workspace: WorkspaceConfig,
    reloadTrigger: Int = 0,
    onOpenImports: () -> Unit = {},
    onOpenHelp: () -> Unit = {}
) {
    val scope = rememberCoroutineScope()
    var sessions by remember { mutableStateOf<List<Session>>(emptyList()) }
    var summaries by remember { mutableStateOf<Map<String, SessionSummary>>(emptyMap()) }
    var diagnosticsMap by remember { mutableStateOf<Map<String, Map<String, Double>>>(emptyMap()) }
    var isLoading by remember { mutableStateOf(true) }
    var isAiAnalystOpen by remember { mutableStateOf(false) }
    val analystChatHistory = remember { mutableStateListOf<Pair<String, String>>() }
    var userQueryText by remember { mutableStateOf("") }
    var isAnalystLoading by remember { mutableStateOf(false) }
    var selectedRowForGraph by remember { mutableStateOf<RowDefinition?>(null) }
    val dateFormatter = remember {
        DateTimeFormatter.ofPattern("MMM dd, HH:mm", Locale.US).withZone(ZoneId.systemDefault())
    }

    // Load data from DuckDB
    LaunchedEffect(workspace.teamId, workspace.seasonId, workspace.robotId, reloadTrigger) {
        isLoading = true
        withContext(Dispatchers.IO) {
            val list = databaseService.getSessionsForWorkspace(
                workspace.teamId,
                workspace.seasonId,
                workspace.robotId,
            ).sortedBy { it.createdAt }
            val sums = list.mapNotNull { session ->
                databaseService.getSessionSummary(session.sessionId)
                    ?.let { summary -> session.sessionId to summary }
            }.toMap()
            val diags = list.associate { session ->
                session.sessionId to databaseService.getDiagnosticsTelemetry(session.sessionId).associate { it.key to it.value }
            }
            sessions = list
            summaries = sums
            diagnosticsMap = diags
            isLoading = false
        }
    }

    if (isLoading) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = AresCyan)
        }
        return
    }

    if (sessions.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Card(
                modifier = Modifier.widthIn(max = 540.dp).padding(24.dp),
                colors = CardDefaults.cardColors(containerColor = AresSurfaceElevated),
                border = BorderStroke(1.dp, AresBorder)
            ) {
                Column(
                    modifier = Modifier.padding(28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ShowChart,
                        contentDescription = null,
                        tint = AresCyan,
                        modifier = Modifier.size(40.dp)
                    )
                    Text(
                        "No completed runs yet",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = AresTextPrimary
                    )
                    Text(
                        "Bring in a robot or simulator log first. ARES will then show the run here " +
                            "for replay, charts, and comparison.",
                        color = AresTextSecondary,
                        textAlign = TextAlign.Center
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Button(onClick = onOpenImports) {
                            Icon(Icons.Default.FolderOpen, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Open Log Imports")
                        }
                        OutlinedButton(onClick = onOpenHelp) {
                            Icon(Icons.Default.School, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Show me how")
                        }
                    }
                    Text(
                        "You can learn this workflow entirely with the simulator—no physical robot is required.",
                        style = MaterialTheme.typography.bodySmall,
                        color = AresTextSecondary,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
        return
    }

    // Dynamic Motor Subsystem rows discovery
    val motorNames = remember(diagnosticsMap) {
        diagnosticsMap.values.flatMap { it.keys }
            .filter { it.startsWith("Diagnostics/SysId/Motors/") }
            .map { it.split("/")[3] }
            .map { RunDataDictionary.canonicalizeMotorName(it) }
            .distinct()
            .sorted()
    }
    val allMotorNames = remember(motorNames, summaries) {
        val currentMotors = summaries.values.flatMap { it.motorCurrentAverages.keys }
            .map { RunDataDictionary.canonicalizeMotorName(it) }
        (motorNames + currentMotors).distinct().sorted()
    }
    val allRows = remember(allMotorNames) {
        val baseRowDefinitions = RunDataDictionary.buildBaseRowDefinitions()
        val currentDrawRowDefinitions = RunDataDictionary.buildMotorCurrentRows(allMotorNames)
        val motorRowDefinitions = RunDataDictionary.buildMotorSysIdRows(allMotorNames)
        baseRowDefinitions + currentDrawRowDefinitions + motorRowDefinitions
    }
    val groupedRows = remember(allRows) {
        allRows.groupBy { it.category }
    }

    Row(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.weight(1f).fillMaxHeight().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Title Row
            RunFilterHeader(
                isAiAnalystOpen = isAiAnalystOpen,
                onToggleAiAnalyst = { isAiAnalystOpen = !isAiAnalystOpen }
            )

        // Spreadsheet Container
        Surface(
            modifier = Modifier.fillMaxWidth().weight(1f).border(1.dp, AresBorder, RoundedCornerShape(12.dp)),
            shape = RoundedCornerShape(12.dp),
            color = AresSurface
        ) {
            val horizontalScrollState = rememberScrollState()
            val verticalScrollState = rememberScrollState()

            Row(modifier = Modifier.fillMaxSize()) {
                // Left Column: Row Headers (Sticky)
                Column(
                    modifier = Modifier
                        .width(220.dp)
                        .fillMaxHeight()
                        .background(AresSurfaceElevated)
                        .endBorder(1.dp, AresBorder)
                        .verticalScroll(verticalScrollState)
                ) {
                    // Header corner block
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp)
                            .bottomBorder(1.dp, AresBorder)
                            .padding(12.dp),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        Text("Calculated Metrics", fontWeight = FontWeight.Bold, color = AresTextPrimary, fontSize = 13.sp)
                    }

                    // Group Category Labels and Row Labels
                    groupedRows.forEach { (category, rows) ->
                        // Category Header Row
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(32.dp)
                                .background(Color(0xFF252C3D))
                                .bottomBorder(1.dp, AresBorder)
                                .padding(horizontal = 12.dp),
                            contentAlignment = Alignment.CenterStart
                        ) {
                            Text(category, fontWeight = FontWeight.Bold, color = AresCyan, fontSize = 11.sp)
                        }

                        rows.forEach { rowDef ->
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(44.dp)
                                    .bottomBorder(1.dp, AresBorder)
                                    .pointerHoverIcon(PointerIcon(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)))
                                    .clickable { selectedRowForGraph = rowDef }
                                    .padding(horizontal = 12.dp),
                                contentAlignment = Alignment.CenterStart
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(rowDef.label, color = AresTextPrimary, fontSize = 12.sp, maxLines = 1)
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.ShowChart,
                                        contentDescription = "Graph row",
                                        tint = AresTextTertiary,
                                        modifier = Modifier.size(14.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                // Right Section: Scrollable Columns (Runs)
                Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .horizontalScroll(horizontalScrollState)
                    ) {
                        sessions.forEachIndexed { sessionIdx, session ->
                            val summary = summaries[session.sessionId]
                            val diags = diagnosticsMap[session.sessionId] ?: emptyMap()

                            Column(
                                modifier = Modifier
                                    .width(180.dp)
                                    .fillMaxHeight()
                                    .endBorder(1.dp, AresBorder)
                                    .verticalScroll(verticalScrollState)
                            ) {
                                // Run Header block
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(56.dp)
                                        .background(if (sessionIdx % 2 == 0) AresSurface else AresSurfaceElevated)
                                        .bottomBorder(1.dp, AresBorder)
                                        .padding(8.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text(
                                            text = session.matchNumber?.let { "Match #$it" } ?: "Run #${sessionIdx + 1}",
                                            fontWeight = FontWeight.Bold,
                                            color = AresTextPrimary,
                                            fontSize = 12.sp
                                        )
                                        Text(
                                            text = dateFormatter.format(Instant.ofEpochMilli(session.createdAt)),
                                            color = AresTextSecondary,
                                            fontSize = 10.sp
                                        )
                                    }
                                }

                                // Cells grouped by categories
                                groupedRows.forEach { (_, rows) ->
                                    // Empty category cell placeholder for spacing
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(32.dp)
                                            .background(Color(0xFF252C3D))
                                            .bottomBorder(1.dp, AresBorder)
                                    )

                                    rows.forEach { rowDef ->
                                        val displayVal = rowDef.getValue(session, summary, diags)
                                        val numVal = rowDef.getNumericValue(session, summary, diags)
                                        val isAnomaly = numVal?.let { rowDef.isAnomaly(it) } ?: false

                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(44.dp)
                                                .background(
                                                    when {
                                                        isAnomaly -> Color(0x35FF5252)
                                                        sessionIdx % 2 == 0 -> AresSurface
                                                        else -> AresSurfaceElevated
                                                    }
                                                )
                                                .bottomBorder(1.dp, AresBorder)
                                                .padding(8.dp),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                text = displayVal,
                                                color = if (isAnomaly) AresError else AresTextPrimary,
                                                fontSize = 12.sp,
                                                fontWeight = if (isAnomaly) FontWeight.Bold else FontWeight.Normal,
                                                textAlign = TextAlign.Center
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Collapsible AI Analyst side panel!
    AnimatedVisibility(
        visible = isAiAnalystOpen,
        enter = slideInHorizontally(initialOffsetX = { it }) + fadeIn(),
        exit = slideOutHorizontally(targetOffsetX = { it }) + fadeOut()
    ) {
        Column(
            modifier = Modifier
                .width(360.dp)
                .fillMaxHeight()
                .background(AresSurfaceElevated)
                .border(1.dp, AresBorder)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = AresCyan)
                    Text("AI Telemetry SQL Analyst", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = AresTextPrimary)
                }
                IconButton(
                    onClick = { isAiAnalystOpen = false },
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(Icons.Default.Close, contentDescription = "Close", tint = AresTextSecondary, modifier = Modifier.size(16.dp))
                }
            }

            HorizontalDivider(color = AresBorder)
            val listState = rememberLazyListState()
            LaunchedEffect(analystChatHistory.size) {
                if (analystChatHistory.isNotEmpty()) {
                    listState.animateScrollToItem(analystChatHistory.size - 1)
                }
            }

            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(AresSurface, RoundedCornerShape(8.dp))
                    .border(1.dp, AresBorder, RoundedCornerShape(8.dp))
                    .padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item {
                    Text(
                        text = "Ask me to generate SQL queries and summarize cross-session telemetry trends (e.g., 'What is our average EKF drift?' or 'List matches where battery fell below 9.5V').",
                        fontSize = 11.sp,
                        color = AresTextSecondary
                    )
                }

                items(analystChatHistory) { (role, msg) ->
                    val isUser = role == "user"
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth(0.85f)
                                .background(
                                    color = if (isUser) AresCyan.copy(alpha = 0.15f) else AresSurfaceElevated,
                                    shape = RoundedCornerShape(8.dp)
                                )
                                .border(1.dp, if (isUser) AresCyan else AresBorder, RoundedCornerShape(8.dp))
                                .padding(8.dp)
                        ) {
                            Text(
                                text = if (isUser) "You" else "SQL Analyst",
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isUser) AresCyan else AresTextSecondary
                            )
                            Text(text = msg, fontSize = 11.sp, color = AresTextPrimary)
                        }
                    }
                }

                if (isAnalystLoading) {
                    item {
                        CircularProgressIndicator(
                            color = AresCyan,
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                BasicTextField(
                    value = userQueryText,
                    onValueChange = { userQueryText = it },
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
                                if (userQueryText.isEmpty()) {
                                    Text("Ask database analyst...", fontSize = 11.sp, color = AresTextTertiary)
                                }
                                innerTextField()
                            }
                        }
                    }
                )

                Button(
                    onClick = {
                        if (userQueryText.trim().isNotEmpty() && !isAnalystLoading) {
                            val queryCopy = userQueryText.trim()
                            analystChatHistory.add(Pair("user", queryCopy))
                            userQueryText = ""
                            isAnalystLoading = true
                            scope.launch {
                                try {
                                    val reply = aiDiagnosticsService.requestSqlAnalysis(queryCopy)
                                    analystChatHistory.add(Pair("analyst", reply))
                                } catch (e: Exception) {
                                    analystChatHistory.add(Pair("analyst", "Error: ${e.message ?: "Failed to analyze query."}"))
                                } finally {
                                    isAnalystLoading = false
                                }
                            }
                        }
                    },
                    enabled = userQueryText.trim().isNotEmpty() && !isAnalystLoading,
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

    // Row Graphing overlay Modal
    selectedRowForGraph?.let { rowDef ->
        RunDataCard(
            rowDefinition = rowDef,
            sessions = sessions,
            summaries = summaries,
            diagnosticsMap = diagnosticsMap,
            dateFormatter = dateFormatter,
            onDismiss = { selectedRowForGraph = null }
        )
    }
}
