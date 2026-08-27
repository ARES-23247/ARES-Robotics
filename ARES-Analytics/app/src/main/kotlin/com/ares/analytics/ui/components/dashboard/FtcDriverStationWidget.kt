package com.ares.analytics.ui.components.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
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
import com.ares.analytics.service.Nt4ClientService
import com.ares.analytics.service.isLoopbackDriveControlHost
import com.ares.analytics.ui.theme.*
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

enum class DsState {
    INIT, START, STOP
}

enum class MatchState {
    IDLE, AUTO_INIT, AUTO_RUNNING, TRANSITION, TELEOP_INIT, TELEOP_RUNNING
}

internal fun canIssueDashboardDriverStationCommands(isConnected: Boolean, host: String): Boolean =
    isConnected && isLoopbackDriveControlHost(host)

@Composable
fun FtcDriverStationWidget(
    nt4Client: Nt4ClientService,
    modifier: Modifier = Modifier
) {
    val isConnected by nt4Client.isConnected.collectAsState()
    val canControlSimulator = canIssueDashboardDriverStationCommands(isConnected, nt4Client.serverIp)
    var selectedOpMode by remember { mutableStateOf<String?>(null) } // For manual control
    var selectedAutoOpMode by remember { mutableStateOf<String?>(null) }
    var selectedTeleOpMode by remember { mutableStateOf<String?>(null) }
    var dsState by remember { mutableStateOf(DsState.STOP) }
    var matchState by remember { mutableStateOf(MatchState.IDLE) }
    var matchTimeRemaining by remember { mutableIntStateOf(0) }
    var teleOps by remember {
        mutableStateOf(
            nt4Client.latestValues["ARES/DriverStation/TeleOpList"]?.stringValue?.let {
                try { Json.decodeFromString<List<String>>(it) } catch(e: Exception) { emptyList() }
            } ?: emptyList()
        )
    }
    var autos by remember {
        mutableStateOf(
            nt4Client.latestValues["ARES/DriverStation/AutonomousList"]?.stringValue?.let {
                try { Json.decodeFromString<List<String>>(it) } catch(e: Exception) { emptyList() }
            } ?: emptyList()
        )
    }
    val telemetryLines = remember { mutableStateListOf<String>() }
    var isAutoExpanded by remember { mutableStateOf(false) }
    var isTeleOpExpanded by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // Match Orchestrator
    LaunchedEffect(matchTimeRemaining, canControlSimulator) {
        if (canControlSimulator) {
            nt4Client.publishDouble("ARES/DriverStation/MatchTimeRemaining", matchTimeRemaining.toDouble())
        }
    }

    LaunchedEffect(matchState, canControlSimulator) {
        if (!canControlSimulator) return@LaunchedEffect
        nt4Client.publishString("ARES/DriverStation/MatchState", matchState.name)
        when (matchState) {
            MatchState.AUTO_INIT -> {
                matchTimeRemaining = 30
                selectedAutoOpMode?.let {
                    nt4Client.publishString("ARES/DriverStation/SelectedOpMode", it)
                    nt4Client.publishString("ARES/DriverStation/Command", "INIT")
                    dsState = DsState.INIT
                }
                kotlinx.coroutines.delay(2000) // Wait 2s for init
                matchState = MatchState.AUTO_RUNNING
            }
            MatchState.AUTO_RUNNING -> {
                nt4Client.publishString("ARES/DriverStation/Command", "START")
                dsState = DsState.START
                while (matchTimeRemaining > 0 && matchState == MatchState.AUTO_RUNNING) {
                    kotlinx.coroutines.delay(1000)
                    matchTimeRemaining--
                }
                if (matchState == MatchState.AUTO_RUNNING) {
                    nt4Client.publishString("ARES/DriverStation/Command", "STOP")
                    dsState = DsState.STOP
                    matchState = MatchState.TRANSITION
                }
            }
            MatchState.TRANSITION -> {
                matchTimeRemaining = 8
                while (matchTimeRemaining > 0 && matchState == MatchState.TRANSITION) {
                    kotlinx.coroutines.delay(1000)
                    matchTimeRemaining--
                }
                if (matchState == MatchState.TRANSITION) {
                    matchState = MatchState.TELEOP_INIT
                }
            }
            MatchState.TELEOP_INIT -> {
                matchTimeRemaining = 120
                selectedTeleOpMode?.let {
                    nt4Client.publishString("ARES/DriverStation/SelectedOpMode", it)
                    nt4Client.publishString("ARES/DriverStation/Command", "INIT")
                    dsState = DsState.INIT
                }
                kotlinx.coroutines.delay(2000) // Wait 2s for init
                matchState = MatchState.TELEOP_RUNNING
            }
            MatchState.TELEOP_RUNNING -> {
                nt4Client.publishString("ARES/DriverStation/Command", "START")
                dsState = DsState.START
                while (matchTimeRemaining > 0 && matchState == MatchState.TELEOP_RUNNING) {
                    kotlinx.coroutines.delay(1000)
                    matchTimeRemaining--
                }
                if (matchState == MatchState.TELEOP_RUNNING) {
                    nt4Client.publishString("ARES/DriverStation/Command", "STOP")
                    dsState = DsState.STOP
                    matchState = MatchState.IDLE
                }
            }
            MatchState.IDLE -> {
                // Do nothing
            }
        }
    }

    // A disconnect or target switch cancels the orchestrator and clears every latched command.
    LaunchedEffect(isConnected, nt4Client.serverIp) {
        if (!canControlSimulator) {
            selectedOpMode = null
            selectedAutoOpMode = null
            selectedTeleOpMode = null
            dsState = DsState.STOP
            matchState = MatchState.IDLE
            matchTimeRemaining = 0
            telemetryLines.clear()
            teleOps = emptyList()
            autos = emptyList()
        }
    }

    // Listen to NT4 topics
    LaunchedEffect(nt4Client) {
        nt4Client.uiTelemetryFlow.collect { frame ->
            val cleanKey = frame.key.trimStart('/')
            when {
                cleanKey == "ARES/DriverStation/TeleOpList" || cleanKey.endsWith("TeleOpList") -> {
                    frame.stringValue?.let {
                        try {
                            teleOps = Json.decodeFromString<List<String>>(it)
                        } catch (e: Exception) {
                            println("Failed to parse TeleOpList: ${e.message}")
                        }
                    }
                }
                cleanKey == "ARES/DriverStation/AutonomousList" || cleanKey.endsWith("AutonomousList") -> {
                    frame.stringValue?.let {
                        try {
                            autos = Json.decodeFromString<List<String>>(it)
                        } catch (e: Exception) {
                            println("Failed to parse AutonomousList: ${e.message}")
                        }
                    }
                }
                cleanKey.startsWith("ARES/DriverStation/Telemetry/") -> frame.stringValue?.let { line ->
                    val indexPart = frame.key.substringAfterLast("/")
                    val idx = indexPart.toIntOrNull()
                    if (idx != null) {
                        while (telemetryLines.size <= idx) {
                            telemetryLines.add("")
                        }
                        telemetryLines[idx] = line
                    }
                }
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(AresSurface, RoundedCornerShape(12.dp))
            .border(1.dp, AresBorder, RoundedCornerShape(12.dp))
            .padding(16.dp)
    ) {
        // Header and Timer
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Driver Station",
                color = AresTextPrimary,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
            if (matchState != MatchState.IDLE) {
                val isEndGame = (matchState == MatchState.TELEOP_RUNNING && matchTimeRemaining <= 30)
                val phaseText = when {
                    isEndGame -> "END GAME"
                    matchState == MatchState.AUTO_INIT || matchState == MatchState.AUTO_RUNNING -> "AUTO"
                    matchState == MatchState.TRANSITION -> "TRANSITION"
                    matchState == MatchState.TELEOP_INIT || matchState == MatchState.TELEOP_RUNNING -> "TELEOP"
                    else -> ""
                }
                val phaseColor = when {
                    isEndGame -> AresError
                    matchState == MatchState.AUTO_INIT || matchState == MatchState.AUTO_RUNNING -> AresGreen
                    matchState == MatchState.TRANSITION -> AresTextSecondary
                    matchState == MatchState.TELEOP_INIT || matchState == MatchState.TELEOP_RUNNING -> AresCyan
                    else -> AresTextPrimary
                }
                val minutes = matchTimeRemaining / 60
                val seconds = matchTimeRemaining % 60
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = phaseText,
                        color = phaseColor,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(end = 8.dp)
                    )
                    Text(
                        text = String.format("%02d:%02d", minutes, seconds),
                        color = AresTextPrimary,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }

        if (!canControlSimulator) {
            Text(
                text = if (isConnected) {
                    "Driver Station commands are disabled for physical robot connections. Use Local Sim to run OpModes here."
                } else {
                    "Connect to Local Sim to run OpModes from this widget."
                },
                color = AresAmber,
                fontSize = 12.sp,
                modifier = Modifier.padding(bottom = 12.dp),
            )
        }

        // Dropdown Selectors
        val displayAutos = autos
        val displayTeleOps = teleOps

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Autonomous Dropdown
            Box(modifier = Modifier.weight(1f)) {
                Surface(
                    onClick = { isAutoExpanded = !isAutoExpanded },
                    shape = RoundedCornerShape(4.dp),
                    color = AresSurfaceElevated,
                    border = androidx.compose.foundation.BorderStroke(1.dp, AresBorder),
                    modifier = Modifier.fillMaxWidth().height(56.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = selectedAutoOpMode?.substringAfterLast(".") ?: "Select Auto",
                            color = if (selectedAutoOpMode != null) AresTextPrimary else AresTextSecondary,
                            fontSize = 16.sp
                        )
                        Icon(
                            imageVector = Icons.Default.ArrowDropDown,
                            contentDescription = null,
                            tint = AresTextSecondary
                        )
                    }
                }
                DropdownMenu(
                    expanded = isAutoExpanded,
                    onDismissRequest = { isAutoExpanded = false },
                    modifier = Modifier.background(AresSurfaceElevated)
                ) {
                    if (displayAutos.isEmpty()) {
                        DropdownMenuItem(
                            text = { Text("No autonomous OpModes published", color = AresTextSecondary) },
                            onClick = {},
                            enabled = false,
                        )
                    }
                    displayAutos.forEach { opMode ->
                        DropdownMenuItem(
                            text = { Text(opMode.substringAfterLast("."), color = AresTextPrimary) },
                            onClick = {
                                selectedAutoOpMode = opMode
                                selectedOpMode = opMode
                                isAutoExpanded = false
                            }
                        )
                    }
                }
            }

            // TeleOp Dropdown
            Box(modifier = Modifier.weight(1f)) {
                Surface(
                    onClick = { isTeleOpExpanded = !isTeleOpExpanded },
                    shape = RoundedCornerShape(4.dp),
                    color = AresSurfaceElevated,
                    border = androidx.compose.foundation.BorderStroke(1.dp, AresBorder),
                    modifier = Modifier.fillMaxWidth().height(56.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = selectedTeleOpMode?.substringAfterLast(".") ?: "Select TeleOp",
                            color = if (selectedTeleOpMode != null) AresTextPrimary else AresTextSecondary,
                            fontSize = 16.sp
                        )
                        Icon(
                            imageVector = Icons.Default.ArrowDropDown,
                            contentDescription = null,
                            tint = AresTextSecondary
                        )
                    }
                }
                DropdownMenu(
                    expanded = isTeleOpExpanded,
                    onDismissRequest = { isTeleOpExpanded = false },
                    modifier = Modifier.background(AresSurfaceElevated)
                ) {
                    if (displayTeleOps.isEmpty()) {
                        DropdownMenuItem(
                            text = { Text("No TeleOp OpModes published", color = AresTextSecondary) },
                            onClick = {},
                            enabled = false,
                        )
                    }
                    displayTeleOps.forEach { opMode ->
                        DropdownMenuItem(
                            text = { Text(opMode.substringAfterLast("."), color = AresTextPrimary) },
                            onClick = {
                                selectedTeleOpMode = opMode
                                selectedOpMode = opMode
                                isTeleOpExpanded = false
                            }
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Match Start Button
        Button(
            onClick = {
                if (canControlSimulator && matchState == MatchState.IDLE && selectedAutoOpMode != null && selectedTeleOpMode != null) {
                    telemetryLines.clear()
                    matchState = MatchState.AUTO_INIT
                } else if (matchState != MatchState.IDLE) {
                    matchState = MatchState.IDLE
                    dsState = DsState.STOP
                    scope.launch {
                        nt4Client.publishString("ARES/DriverStation/Command", "STOP")
                    }
                }
            },
            enabled = canControlSimulator && ((matchState == MatchState.IDLE && selectedAutoOpMode != null && selectedTeleOpMode != null) || matchState != MatchState.IDLE),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (matchState == MatchState.IDLE) AresCyan else AresError,
                contentColor = AresOnAccent,
                disabledContainerColor = AresSurfaceElevated
            ),
            modifier = Modifier.fillMaxWidth().height(48.dp)
        ) {
            Text(if (matchState == MatchState.IDLE) "START MATCH" else "ABORT MATCH", fontWeight = FontWeight.Bold)
        }

        Spacer(modifier = Modifier.height(12.dp))

        // State Machine Buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            Button(
                onClick = {
                    selectedOpMode?.let { opMode ->
                        dsState = DsState.INIT
                        telemetryLines.clear()
                        scope.launch {
                            nt4Client.publishString("ARES/DriverStation/SelectedOpMode", opMode)
                            nt4Client.publishString("ARES/DriverStation/Command", "INIT")
                        }
                    }
                },
                enabled = canControlSimulator && dsState == DsState.STOP && selectedOpMode != null,
                colors = ButtonDefaults.buttonColors(
                    containerColor = AresCyan,
                    contentColor = AresOnAccent,
                    disabledContainerColor = AresSurfaceElevated
                ),
                modifier = Modifier.weight(1f).height(48.dp)
            ) {
                Text("INIT", fontWeight = FontWeight.Bold)
            }

            Spacer(modifier = Modifier.width(8.dp))

            Button(
                onClick = {
                    dsState = DsState.START
                    scope.launch {
                        nt4Client.publishString("ARES/DriverStation/Command", "START")
                    }
                },
                enabled = canControlSimulator && dsState == DsState.INIT,
                colors = ButtonDefaults.buttonColors(
                    containerColor = AresGreen,
                    contentColor = AresOnAccent,
                    disabledContainerColor = AresSurfaceElevated
                ),
                modifier = Modifier.weight(1f).height(48.dp)
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = null)
            }

            Spacer(modifier = Modifier.width(8.dp))

            Button(
                onClick = {
                    dsState = DsState.STOP
                    matchState = MatchState.IDLE
                    scope.launch {
                        nt4Client.publishString("ARES/DriverStation/Command", "STOP")
                    }
                },
                enabled = canControlSimulator,
                colors = ButtonDefaults.buttonColors(
                    containerColor = AresError,
                    contentColor = AresOnAccent,
                    disabledContainerColor = AresSurfaceElevated
                ),
                modifier = Modifier.weight(1f).height(48.dp)
            ) {
                Icon(Icons.Default.Stop, contentDescription = null)
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Telemetry View
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xFF1E1E1E))
                .padding(8.dp)
        ) {
            val listState = rememberLazyListState()

            LaunchedEffect(telemetryLines.size) {
                if (telemetryLines.isNotEmpty()) {
                    listState.animateScrollToItem(telemetryLines.size - 1)
                }
            }

            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                items(telemetryLines) { line ->
                    Text(
                        text = line,
                        color = Color(0xFF00FF00), // Terminal green
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }
    }
}

