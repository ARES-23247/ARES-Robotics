package com.ares.analytics.ui.components.terminal

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
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
import com.ares.analytics.service.ProcessManagerService
import com.ares.analytics.service.AdbService
import com.ares.analytics.service.SimulatorProcessService
import com.ares.analytics.shared.models.League
import com.ares.analytics.ui.theme.*
import kotlinx.coroutines.launch

@Composable
fun TerminalDrawer(
    processManagerService: ProcessManagerService,
    adbService: AdbService,
    simulatorProcessService: SimulatorProcessService,
    projectPath: String,
    league: League,
    isOpen: Boolean,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    var activeTab by remember { mutableStateOf(0) } // 0 = Build, 1 = Logcat
    val processState by processManagerService.processState.collectAsState()
    val isBuildRunning = processState.buildRunning
    val buildListState = rememberLazyListState()
    val logcatListState = rememberLazyListState()
    // Store lines in memory to render in LazyColumn
    val buildLog = remember { mutableStateListOf<String>() }
    val logcatLog = remember { mutableStateListOf<String>() }

    // Collect flows
    LaunchedEffect(Unit) {
        launch {
            processManagerService.buildOutput.collect { line ->
                buildLog.add(line)
                if (buildLog.size > 1000) buildLog.removeAt(0)
            }
        }
        launch {
            simulatorProcessService.output.collect { line ->
                buildLog.add(line)
                if (buildLog.size > 1000) buildLog.removeAt(0)
            }
        }
        launch {
            adbService.logcatOutput.collect { line ->
                logcatLog.add(line)
                if (logcatLog.size > 1000) logcatLog.removeAt(0)
            }
        }
    }

    // Auto-scroll is a presentation concern, not part of log collection. Snap only while the
    // relevant terminal tab is visible so a burst of build output cannot queue animations.
    LaunchedEffect(buildLog.size, isOpen, activeTab) {
        if (isOpen && activeTab == 0 && buildLog.isNotEmpty()) {
            buildListState.scrollToItem(buildLog.lastIndex)
        }
    }
    LaunchedEffect(logcatLog.size, isOpen, activeTab) {
        if (isOpen && activeTab == 1 && logcatLog.isNotEmpty()) {
            logcatListState.scrollToItem(logcatLog.lastIndex)
        }
    }

    AnimatedVisibility(
        visible = isOpen,
        enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .height(350.dp)
                .background(AresSurface)
                .border(1.dp, AresBorder, RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
        ) {
            // Header Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Tabs
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TabItem(label = "Build Output", isActive = activeTab == 0, onClick = { activeTab = 0 })
                    TabItem(label = "Logcat Stream", isActive = activeTab == 1, onClick = { activeTab = 1 })
                }

                // Control Buttons
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (activeTab == 0) {
                        Button(
                            onClick = {
                                buildLog.clear()
                                processManagerService.runBuild(projectPath, league)
                            },
                            enabled = !isBuildRunning,
                            colors = ButtonDefaults.buttonColors(containerColor = AresCyan, contentColor = AresOnAccent),
                            shape = RoundedCornerShape(6.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            if (isBuildRunning) {
                                CircularProgressIndicator(modifier = Modifier.size(15.dp), strokeWidth = 2.dp)
                            } else {
                                Icon(imageVector = Icons.Default.Build, contentDescription = null, modifier = Modifier.size(16.dp))
                            }
                            Spacer(Modifier.width(4.dp))
                            Text(if (isBuildRunning) "Verifying" else "Verify & build (Ctrl+B)", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    } else {
                        Button(
                            onClick = {
                                logcatLog.clear()
                                adbService.startLogcat()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = AresCyan, contentColor = AresOnAccent),
                            shape = RoundedCornerShape(6.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Icon(imageVector = Icons.Default.PlayArrow, contentDescription = null, tint = AresBackground, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Stream Logcat", color = AresBackground, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    IconButton(
                        onClick = {
                            if (activeTab == 0) processManagerService.killActiveBuild() else adbService.stopLogcat()
                        }
                    ) {
                        Icon(imageVector = Icons.Default.Cancel, contentDescription = "Kill Process", tint = AresError)
                    }

                    // Copy All Logs button
                    IconButton(
                        onClick = {
                            val logLines = if (activeTab == 0) buildLog else logcatLog
                            val textToCopy = logLines.joinToString("\n")
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

                    IconButton(onClick = onClose) {
                        Icon(imageVector = Icons.Default.Close, contentDescription = "Close Terminal", tint = AresTextSecondary)
                    }
                }
            }

            HorizontalDivider(color = AresBorder, thickness = 1.dp)

            // Terminal Screen Output
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(AresBackground)
                    .padding(12.dp)
            ) {
                if (activeTab == 0) {
                    LazyColumn(
                        state = buildListState,
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(buildLog) { line ->
                            SelectionContainer {
                                Text(
                                    text = parseAnsi(line),
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 12.sp,
                                    color = AresTextPrimary
                                )
                            }
                        }
                    }
                } else {
                    LazyColumn(
                        state = logcatListState,
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(logcatLog) { line ->
                            SelectionContainer {
                                Text(
                                    text = parseAnsi(line),
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 12.sp,
                                    color = AresTextPrimary
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

internal fun parseAnsi(input: String): androidx.compose.ui.text.AnnotatedString {
    return androidx.compose.ui.text.buildAnnotatedString {
        var currentIndex = 0
        var activeStyle = androidx.compose.ui.text.SpanStyle(color = AresTextPrimary)

        fun appendReadable(text: String) {
            if (text.isEmpty()) return
            pushStyle(activeStyle)
            append(text)
            pop()
        }

        while (currentIndex < input.length) {
            val escIndex = input.indexOf("\u001B[", currentIndex)
            if (escIndex == -1) {
                appendReadable(input.substring(currentIndex))
                break
            }
            if (escIndex > currentIndex) {
                appendReadable(input.substring(currentIndex, escIndex))
            }
            val mIndex = input.indexOf('m', escIndex)
            if (mIndex == -1) {
                appendReadable(input.substring(escIndex))
                break
            }
            val codeStr = input.substring(escIndex + 2, mIndex)
            val codes = codeStr.split(';').mapNotNull { it.toIntOrNull() }
            var idx = 0
            while (idx < codes.size) {
                val code = codes[idx]
                when {
                    code == 0 -> {
                        activeStyle = androidx.compose.ui.text.SpanStyle(color = AresTextPrimary)
                    }
                    code == 1 -> {
                        activeStyle = activeStyle.copy(fontWeight = FontWeight.Bold)
                    }
                    code in 30..37 -> {
                        activeStyle = activeStyle.copy(color = getAnsiColor(code - 30, bright = false))
                    }
                    code in 90..97 -> {
                        activeStyle = activeStyle.copy(color = getAnsiColor(code - 90, bright = true))
                    }
                    code == 38 && idx + 2 < codes.size && codes[idx + 1] == 5 -> {
                        val colorIndex = codes[idx + 2]
                        activeStyle = activeStyle.copy(color = get256Color(colorIndex))
                        idx += 2
                    }
                    code == 39 -> {
                        activeStyle = activeStyle.copy(color = AresTextPrimary)
                    }
                }
                idx++
            }
            val nextEsc = input.indexOf("\u001B[", mIndex + 1)
            val textSegment = if (nextEsc == -1) {
                input.substring(mIndex + 1)
            } else {
                input.substring(mIndex + 1, nextEsc)
            }

            appendReadable(textSegment)

            currentIndex = mIndex + 1 + textSegment.length
        }
    }
}

internal fun getAnsiColor(code: Int, bright: Boolean): Color {
    return when (code) {
        // ANSI black is designed for light terminals. Adapt it to a readable neutral on ARES dark
        // surfaces while retaining the normal/bright distinction.
        0 -> if (bright) AresTextPrimary else AresTextSecondary
        1 -> if (bright) AresRed else AresRedDark
        2 -> if (bright) AresGreen else AresGreen.copy(alpha = 0.8f)
        3 -> if (bright) AresGold else AresAmber
        4 -> if (bright) AresCyan else AresCyanGlow
        5 -> Color(0xFFE066FF)
        6 -> AresCyan
        7 -> if (bright) Color(0xFFFFFFFF) else Color(0xFFCCCCCC)
        else -> AresTextPrimary
    }
}

private fun get256Color(index: Int): Color {
    if (index in 0..7) return getAnsiColor(index, false)
    if (index in 8..15) return getAnsiColor(index - 8, true)

    if (index in 16..231) {
        val r = ((index - 16) / 36) * 51
        val g = (((index - 16) % 36) / 6) * 51
        val b = ((index - 16) % 6) * 51
        return Color(r, g, b)
    }

    if (index in 232..255) {
        val gray = (index - 232) * 10 + 8
        return Color(gray, gray, gray)
    }

    return AresTextPrimary
}

@Composable
private fun TabItem(
    label: String,
    isActive: Boolean,
    onClick: () -> Unit
) {
    val bgCol = if (isActive) AresBorder else Color.Transparent
    val textCol = if (isActive) AresCyan else AresTextSecondary

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(bgCol)
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = textCol, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}
