package com.ares.analytics.ui.components.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ares.analytics.service.DatabaseService
import com.ares.analytics.service.Nt4ClientService
import com.ares.analytics.service.ReplayEngineService
import com.ares.analytics.service.ReplayState
import com.ares.analytics.service.WidgetConfig
import com.ares.analytics.shared.models.ConsoleMessage
import com.ares.analytics.ui.theme.*
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConsoleViewer(
    nt4ClientService: Nt4ClientService,
    databaseService: DatabaseService,
    replayEngineService: ReplayEngineService,
    widgetConfig: WidgetConfig,
    modifier: Modifier = Modifier
) {
    val currentSessionId = widgetConfig.properties["sessionId"]
    val primarySessionId = currentSessionId // Or dynamically bind to live/replay
    val liveLogs = remember { mutableStateListOf<ConsoleMessage>() }
    val sessionLogs = remember { mutableStateListOf<ConsoleMessage>() }
    val scope = rememberCoroutineScope()

    // 1. Subscribe to Live Streams
    LaunchedEffect(Unit) {
        nt4ClientService.consoleFlow.collect { msg ->
            liveLogs.add(msg)
            if (liveLogs.size > 1000) {
                liveLogs.removeAt(0)
            }
        }
    }

    // 2. Fetch DB Logs if we have a primary/replay session
    LaunchedEffect(primarySessionId) {
        sessionLogs.clear()
        if (primarySessionId != null) {
            try {
                val dbLogs = databaseService.getConsoleMessages(primarySessionId)
                sessionLogs.addAll(dbLogs)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
    val isReplayMode = primarySessionId != null
    val allMessages = if (isReplayMode) sessionLogs else liveLogs

    // Replay synchronisation
    val replayFrame by replayEngineService.currentFrame.collectAsState()
    val replayState by replayEngineService.state.collectAsState()
    val displayMessages = remember(allMessages, replayFrame, replayState, isReplayMode) {
        val playheadMs = replayFrame?.timestampMs
        if (isReplayMode && replayState != ReplayState.STOPPED && playheadMs != null) {
            allMessages.filter { it.timestampMs <= playheadMs }
        } else {
            allMessages
        }
    }

    // Filters & Search
    var searchText by remember { mutableStateOf("") }
    var showInfo by remember { mutableStateOf(true) }
    var showWarn by remember { mutableStateOf(true) }
    var showError by remember { mutableStateOf(true) }
    var autoScroll by remember { mutableStateOf(true) }
    val filteredMessages = remember(displayMessages, searchText, showInfo, showWarn, showError) {
        val regex = try {
            if (searchText.isNotEmpty()) Regex(searchText, RegexOption.IGNORE_CASE) else null
        } catch (e: Exception) {
            null
        }

        displayMessages.filter { msg ->
            val matchesSeverity = when (msg.severity) {
                "INFO" -> showInfo
                "WARN" -> showWarn
                "ERROR" -> showError
                else -> true
            }
            val matchesSearch = when {
                searchText.isEmpty() -> true
                regex != null -> regex.containsMatchIn(msg.text)
                else -> msg.text.contains(searchText, ignoreCase = true)
            }
            matchesSeverity && matchesSearch
        }
    }
    val listState = rememberLazyListState()
    LaunchedEffect(filteredMessages.size) {
        if (autoScroll && filteredMessages.isNotEmpty()) {
            listState.animateScrollToItem(filteredMessages.size - 1)
        }
    }
    val timeFormatter = remember { SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(12.dp))
            .background(AresSurface)
            .border(1.dp, AresBorder, RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Toolbar / Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Terminal,
                    contentDescription = null,
                    tint = AresCyan,
                    modifier = Modifier.size(20.dp)
                )
                Text(
                    text = if (isReplayMode) "Console Message Viewer (Replay)" else "Console Message Viewer (Live)",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = AresTextPrimary
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // Clear Live Logs Button
                if (!isReplayMode) {
                    IconButton(
                        onClick = { liveLogs.clear() },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.DeleteSweep,
                            contentDescription = "Clear Logs",
                            tint = AresTextSecondary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                // Auto scroll toggle
                IconButton(
                    onClick = { autoScroll = !autoScroll },
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        imageVector = if (autoScroll) Icons.Default.VerticalAlignBottom else Icons.Default.VerticalAlignTop,
                        contentDescription = "Toggle Auto Scroll",
                        tint = if (autoScroll) AresCyan else AresTextSecondary,
                        modifier = Modifier.size(18.dp)
                    )
                }

                // Export Logs button
                IconButton(
                    onClick = {
                        scope.launch(Dispatchers.IO) {
                            try {
                                val frame = java.awt.Frame()
                                val fileDialog = java.awt.FileDialog(frame, "Export Console Logs", java.awt.FileDialog.SAVE)
                                fileDialog.file = "ares_console_logs.txt"
                                fileDialog.isVisible = true
                                val directory = fileDialog.directory
                                val file = fileDialog.file
                                if (directory != null && file != null) {
                                    val targetFile = java.io.File(directory, file)
                                    targetFile.writeText(filteredMessages.joinToString("\n") { msg ->
                                        val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date(msg.timestampMs))
                                        "[$time] [${msg.severity}] ${msg.text}"
                                    })
                                }
                                frame.dispose()
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                        }
                    },
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Download,
                        contentDescription = "Export Logs",
                        tint = AresTextSecondary,
                        modifier = Modifier.size(18.dp)
                    )
                }

                // Copy All Logs button
                IconButton(
                    onClick = {
                        val textToCopy = filteredMessages.joinToString("\n") { msg ->
                            val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date(msg.timestampMs))
                            "[$time] [${msg.severity}] ${msg.text}"
                        }
                        try {
                            val selection = java.awt.datatransfer.StringSelection(textToCopy)
                            java.awt.Toolkit.getDefaultToolkit().systemClipboard.setContents(selection, selection)
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    },
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.ContentCopy,
                        contentDescription = "Copy All Logs",
                        tint = AresTextSecondary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }

        // Search Bar & Severity Filters
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = searchText,
                onValueChange = { searchText = it },
                placeholder = { Text("Filter logs (regex supported)...", fontSize = 11.sp, color = AresTextTertiary) },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodySmall.copy(color = AresTextPrimary),
                modifier = Modifier.weight(1f).height(38.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = AresCyan,
                    unfocusedBorderColor = AresBorder,
                    focusedContainerColor = AresSurfaceElevated,
                    unfocusedContainerColor = AresSurfaceElevated
                ),
                trailingIcon = if (searchText.isNotEmpty()) {
                    {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Clear Search",
                            modifier = Modifier.size(16.dp).clickable { searchText = "" },
                            tint = AresTextSecondary
                        )
                    }
                } else null
            )

            // Severity Chips
            FilterChip(
                selected = showInfo,
                onClick = { showInfo = !showInfo },
                label = { Text("INFO", fontSize = 10.sp) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = AresGreen.copy(alpha = 0.2f),
                    selectedLabelColor = AresGreen,
                    containerColor = AresSurfaceElevated,
                    labelColor = AresTextSecondary
                ),
                border = FilterChipDefaults.filterChipBorder(
                    enabled = true,
                    selected = showInfo,
                    borderColor = AresBorder,
                    selectedBorderColor = AresGreen,
                    borderWidth = 1.dp,
                    selectedBorderWidth = 1.dp
                ),
                modifier = Modifier.height(32.dp)
            )

            FilterChip(
                selected = showWarn,
                onClick = { showWarn = !showWarn },
                label = { Text("WARN", fontSize = 10.sp) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = AresAmber.copy(alpha = 0.2f),
                    selectedLabelColor = AresAmber,
                    containerColor = AresSurfaceElevated,
                    labelColor = AresTextSecondary
                ),
                border = FilterChipDefaults.filterChipBorder(
                    enabled = true,
                    selected = showWarn,
                    borderColor = AresBorder,
                    selectedBorderColor = AresAmber,
                    borderWidth = 1.dp,
                    selectedBorderWidth = 1.dp
                ),
                modifier = Modifier.height(32.dp)
            )

            FilterChip(
                selected = showError,
                onClick = { showError = !showError },
                label = { Text("ERROR", fontSize = 10.sp) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = AresError.copy(alpha = 0.2f),
                    selectedLabelColor = AresError,
                    containerColor = AresSurfaceElevated,
                    labelColor = AresTextSecondary
                ),
                border = FilterChipDefaults.filterChipBorder(
                    enabled = true,
                    selected = showError,
                    borderColor = AresBorder,
                    selectedBorderColor = AresError,
                    borderWidth = 1.dp,
                    selectedBorderWidth = 1.dp
                ),
                modifier = Modifier.height(32.dp)
            )
        }

        // Message List
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(AresBackground, RoundedCornerShape(6.dp))
                .border(1.dp, AresBorder, RoundedCornerShape(6.dp))
                .padding(6.dp)
        ) {
            if (filteredMessages.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No log messages found.",
                        color = AresTextTertiary,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(filteredMessages) { msg ->
                        SelectionContainer {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 2.dp),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.Top
                            ) {
                                // Timestamp
                                val dateStr = try {
                                    timeFormatter.format(Date(msg.timestampMs))
                                } catch (e: Exception) {
                                    msg.timestampMs.toString()
                                }
                                Text(
                                    text = dateStr,
                                    color = AresTextTertiary,
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    modifier = Modifier.padding(top = 1.dp)
                                )

                                // Severity Badge
                                val severityColor = when (msg.severity) {
                                    "INFO" -> AresGreen
                                    "WARN" -> AresAmber
                                    "ERROR" -> AresError
                                    else -> AresTextSecondary
                                }
                                Text(
                                    text = "[${msg.severity}]",
                                    color = severityColor,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace,
                                    modifier = Modifier.padding(top = 1.dp)
                                )

                                // Message Content
                                Text(
                                    text = msg.text,
                                    color = when (msg.severity) {
                                        "INFO" -> AresTextPrimary
                                        "WARN" -> AresAmber
                                        "ERROR" -> AresError
                                        else -> AresTextPrimary
                                    },
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
