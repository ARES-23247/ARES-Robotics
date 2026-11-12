package com.ares.analytics.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ares.analytics.service.ExportService
import com.ares.analytics.service.Nt4ClientService
import com.ares.analytics.service.hardware.HardwareSetupService
import com.ares.analytics.service.project.ProjectSession
import com.ares.analytics.shared.TelemetryMetricCatalog
import com.ares.analytics.shared.models.League
import com.ares.analytics.shared.models.TelemetryFrame
import com.ares.analytics.shared.models.WorkspaceConfig
import com.ares.analytics.ui.theme.*
import com.ares.analytics.ui.util.DesktopFileChoosers
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import kotlin.time.TimeMark
import kotlin.time.TimeSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal const val ARES_TELEMETRY_HEARTBEAT_TOPIC = "ARES/Telemetry/FrameSequence"
internal const val HARDWARE_READINESS_HEARTBEAT_TIMEOUT_MS = 1_500L

internal enum class SelfTestStatus {
    WAITING,
    OBSERVED,
    WARNING
}

internal data class SelfTestStep(
    val id: String,
    val name: String,
    val description: String,
    val status: SelfTestStatus,
    val details: String
)

/**
 * Builds an honest, read-only baseline preflight from telemetry already published by the robot.
 *
 * An observed topic proves only that the signal reached the app. It does not pulse motors,
 * infer encoder direction, or certify match readiness.
 */
internal fun evaluateHardwareReadiness(
    connected: Boolean,
    frames: Map<String, TelemetryFrame>,
    heartbeatAgeMs: Long?,
): List<SelfTestStep> {
    data class Definition(val id: String, val name: String, val description: String)
    val definitions = listOf(
        Definition("battery", "Battery voltage", "Confirms a finite robot voltage signal above the pit warning threshold."),
        Definition("motor_fl", "Front-left drive signal", "Confirms telemetry exists; it does not command or certify the motor."),
        Definition("motor_fr", "Front-right drive signal", "Confirms telemetry exists; it does not command or certify the motor."),
        Definition("motor_rl", "Rear-left drive signal", "Confirms telemetry exists; it does not command or certify the motor."),
        Definition("motor_rr", "Rear-right drive signal", "Confirms telemetry exists; it does not command or certify the motor."),
        Definition("pinpoint", "Pinpoint / odometry", "Checks the published health or odometry evidence without moving the robot."),
        Definition("limelight", "Limelight / vision", "Checks published camera evidence without assuming that a target must be visible.")
    )
    if (!connected) {
        return definitions.map { definition ->
            SelfTestStep(definition.id, definition.name, definition.description, SelfTestStatus.WAITING, "Connect to a robot or simulator to observe this signal.")
        }
    }

    fun numeric(vararg keys: String): TelemetryFrame? = keys.firstNotNullOfOrNull { frames[it] }
    fun observed(definition: Definition, details: String) =
        SelfTestStep(definition.id, definition.name, definition.description, SelfTestStatus.OBSERVED, details)
    fun waiting(definition: Definition, details: String) =
        SelfTestStep(definition.id, definition.name, definition.description, SelfTestStatus.WAITING, details)
    fun warning(definition: Definition, details: String) =
        SelfTestStep(definition.id, definition.name, definition.description, SelfTestStatus.WARNING, details)

    if (heartbeatAgeMs == null || heartbeatAgeMs !in 0L..HARDWARE_READINESS_HEARTBEAT_TIMEOUT_MS) {
        val details = if (heartbeatAgeMs == null) {
            "The network socket is connected, but no ARES telemetry-loop heartbeat has arrived."
        } else {
            "The last ARES telemetry-loop heartbeat is $heartbeatAgeMs ms old. Treat displayed values as stale."
        }
        return definitions.map { definition ->
            SelfTestStep(definition.id, definition.name, definition.description, SelfTestStatus.WAITING, details)
        }
    }

    val results = mutableListOf<SelfTestStep>()
    val batteryDefinition = definitions[0]
    val battery = TelemetryMetricCatalog.BATTERY_VOLTAGE.keys.firstNotNullOfOrNull { frames[it] }?.value
    results += when {
        battery == null || !battery.isFinite() -> waiting(batteryDefinition, "No finite battery-voltage topic received.")
        battery < 11.5 -> warning(batteryDefinition, "${"%.2f".format(battery)} V observed; charge or inspect the battery before a match.")
        else -> observed(batteryDefinition, "${"%.2f".format(battery)} V observed.")
    }

    listOf("fl" to definitions[1], "fr" to definitions[2], "rl" to definitions[3], "rr" to definitions[4]).forEach { (name, definition) ->
        val aliases = if (name == "rl") listOf("rl", "bl") else if (name == "rr") listOf("rr", "br") else listOf(name)
        val current = aliases.firstNotNullOfOrNull { numeric("Hardware/Motors/$it/CurrentAmps", "Hardware/Motors/$it/Current") }
        val motion = aliases.firstNotNullOfOrNull { numeric("Hardware/Motors/$it/Velocity", "Hardware/Motors/$it/Power") }
        results += if (current != null || motion != null) {
            val evidence = listOfNotNull(
                current?.value?.takeIf(Double::isFinite)?.let { "current ${"%.2f".format(it)} A" },
                motion?.value?.takeIf(Double::isFinite)?.let { "motion signal ${"%.2f".format(it)}" }
            ).joinToString()
            observed(definition, if (evidence.isBlank()) "Motor topics observed." else "Telemetry observed: $evidence.")
        } else {
            waiting(definition, "No current, velocity, or power topic received for ${aliases.joinToString("/")}.")
        }
    }

    val pinpointDefinition = definitions[5]
    val pinpointStatus = frames["Drive/Pinpoint_Status"]?.stringValue?.trim().orEmpty()
    val odometryObserved = listOf("Drive/Odom_X", "Drive/Odom_Y", "Drive/Odom_Heading")
        .all { frames[it]?.value?.isFinite() == true }
    results += when {
        pinpointStatus.contains("FAULT", true) || pinpointStatus.contains("DISCONNECT", true) ->
            warning(pinpointDefinition, "Robot reports: $pinpointStatus")
        pinpointStatus.isNotBlank() && !pinpointStatus.equals("UNKNOWN", true) ->
            observed(pinpointDefinition, "Robot reports: $pinpointStatus")
        odometryObserved -> warning(pinpointDefinition, "Odometry values are present, but the robot did not identify Pinpoint health.")
        else -> waiting(pinpointDefinition, "No Pinpoint health or complete odometry pose received.")
    }

    val visionDefinition = definitions[6]
    val fps = frames["Vision/Limelight/FPS"]?.value
    val visionTopicObserved = frames.containsKey("Vision/HasTarget") || frames.containsKey("Vision/MeasurementCount")
    results += when {
        fps != null && fps.isFinite() && fps >= 5.0 -> observed(visionDefinition, "${"%.1f".format(fps)} FPS observed.")
        fps != null && fps.isFinite() -> warning(visionDefinition, "Only ${"%.1f".format(fps)} FPS observed.")
        visionTopicObserved -> warning(visionDefinition, "Vision topics are present, but camera frame rate is not published.")
        else -> waiting(visionDefinition, "No Limelight or vision-health topic received.")
    }
    return results
}

/**
 * Read-only pre-match Pit Readiness screen combining `.ares` project-aware passive NT4 signal
 * checks, a separate human-verified pit checklist, match/alliance tagging, and `.md` receipt export.
 * This screen never commands robot hardware.
 */
@Composable
fun HardwareSelfTestWizard(
    nt4ClientService: Nt4ClientService,
    workspace: WorkspaceConfig? = null,
    projectSession: ProjectSession? = null,
    hardwareSetupService: HardwareSetupService? = null,
    exportService: ExportService? = null,
) {
    val scope = rememberCoroutineScope()
    val league = workspace?.league ?: League.FTC
    val connected by nt4ClientService.isConnected.collectAsState()

    var reloadProfileNonce by remember { mutableStateOf(0) }
    val projectProfile by produceState(
        initialValue = PitProjectSignalProfile.leagueFallback(league),
        workspace?.id,
        workspace?.projectPath,
        league,
        reloadProfileNonce,
    ) {
        value = PitReadinessInspector.loadProjectProfile(
            workspace = workspace,
            projectSession = projectSession,
            hardwareSetupService = hardwareSetupService,
        )
    }

    var lastHeartbeatReceipt by remember { mutableStateOf<TimeMark?>(null) }
    var streamLive by remember { mutableStateOf(false) }
    var steps by remember(projectProfile) {
        mutableStateOf(
            PitReadinessInspector.evaluateProjectAwarePitReadiness(
                connected = connected,
                frames = nt4ClientService.latestValues,
                heartbeatAgeMs = null,
                profile = projectProfile,
            )
        )
    }

    var manualChecklist by remember(workspace?.id, league) {
        mutableStateOf(PitReadinessInspector.defaultManualChecklist(league))
    }
    var matchTag by remember(workspace?.id) { mutableStateOf(PitMatchTag()) }
    var receiptStatusMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(nt4ClientService) {
        nt4ClientService.uiTelemetryFlow.collect { frame ->
            if (frame.key.removePrefix("/") == ARES_TELEMETRY_HEARTBEAT_TOPIC) {
                lastHeartbeatReceipt = TimeSource.Monotonic.markNow()
            }
        }
    }

    LaunchedEffect(connected, nt4ClientService, projectProfile) {
        if (!connected) {
            lastHeartbeatReceipt = null
            streamLive = false
        }
        while (isActive) {
            val heartbeatAgeMs = lastHeartbeatReceipt?.elapsedNow()?.inWholeMilliseconds
            streamLive = connected && heartbeatAgeMs != null && heartbeatAgeMs <= HARDWARE_READINESS_HEARTBEAT_TIMEOUT_MS
            steps = PitReadinessInspector.evaluateProjectAwarePitReadiness(
                connected = connected,
                frames = nt4ClientService.latestValues,
                heartbeatAgeMs = heartbeatAgeMs,
                profile = projectProfile,
            )
            delay(250)
        }
    }

    val observedCount = steps.count { it.status == SelfTestStatus.OBSERVED }
    val manualCheckedCount = manualChecklist.count { it.checked }

    Column(
        Modifier.fillMaxSize().background(AresBackground).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Card(
            colors = CardDefaults.cardColors(containerColor = AresSurface),
            border = BorderStroke(1.dp, AresBorder),
            shape = RoundedCornerShape(10.dp),
        ) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Default.HealthAndSafety, null, tint = AresCyan, modifier = Modifier.size(24.dp))
                        Column {
                            Text("Pre-Match Pit Readiness", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = AresTextPrimary)
                            Text("${projectProfile.projectLabel} · Passive NT4 signals + human pit sign-off", color = AresTextSecondary, fontSize = 12.sp)
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StatusPill(
                            when {
                                !connected -> "OFFLINE"
                                streamLive -> "STREAM LIVE"
                                else -> "STREAM STALE"
                            },
                            if (streamLive) AresGreen else AresRed,
                        )
                        StatusPill("$observedCount/${steps.size} SIGNALS", AresCyan)
                        StatusPill("$manualCheckedCount/${manualChecklist.size} CREW CHECKS", if (manualCheckedCount == manualChecklist.size) AresGreen else AresGold)
                        IconButton(onClick = {
                            reloadProfileNonce += 1
                            val heartbeatAgeMs = lastHeartbeatReceipt?.elapsedNow()?.inWholeMilliseconds
                            streamLive = connected && heartbeatAgeMs != null && heartbeatAgeMs <= HARDWARE_READINESS_HEARTBEAT_TIMEOUT_MS
                            steps = PitReadinessInspector.evaluateProjectAwarePitReadiness(
                                connected = connected,
                                frames = nt4ClientService.latestValues,
                                heartbeatAgeMs = heartbeatAgeMs,
                                profile = projectProfile,
                            )
                        }) {
                            Icon(Icons.Default.Refresh, "Refresh readiness signals", tint = AresTextSecondary)
                        }
                    }
                }

                // Match Tagging & Receipt Export Row
                PitMatchTagAndExportBar(
                    matchTag = matchTag,
                    onMatchTagChange = { matchTag = it },
                    onExportMarkdown = {
                        val receipt = PitReadinessInspector.buildPitReadinessMarkdownReceipt(
                            workspace = workspace,
                            profile = projectProfile,
                            matchTag = matchTag,
                            connected = connected,
                            streamLive = streamLive,
                            signalSteps = steps,
                            manualChecklist = manualChecklist,
                        )
                        scope.launch {
                            val safeMatch = matchTag.summaryBadge().lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_')
                            val dest = withContext(Dispatchers.IO) {
                                DesktopFileChoosers.chooseSaveFile(
                                    dialogTitle = "Export Pit Readiness Receipt (.md)",
                                    defaultFileName = "pit_receipt_${safeMatch.ifBlank { "check" }}.md",
                                    filterDescription = "Markdown Receipt (*.md)",
                                    extensions = listOf("md"),
                                )
                            } ?: return@launch
                            runCatching {
                                if (exportService != null) {
                                    exportService.writeTextReportAtomically(dest, receipt)
                                } else {
                                    withContext(Dispatchers.IO) { dest.writeText(receipt, Charsets.UTF_8) }
                                }
                            }.onSuccess {
                                receiptStatusMessage = "Saved pit receipt: ${dest.name}"
                            }.onFailure {
                                receiptStatusMessage = "Export failed: ${it.message}"
                            }
                        }
                    },
                    onCopyMarkdown = {
                        val receipt = PitReadinessInspector.buildPitReadinessMarkdownReceipt(
                            workspace = workspace,
                            profile = projectProfile,
                            matchTag = matchTag,
                            connected = connected,
                            streamLive = streamLive,
                            signalSteps = steps,
                            manualChecklist = manualChecklist,
                        )
                        runCatching {
                            Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(receipt), null)
                        }
                        receiptStatusMessage = "Copied pit receipt (${matchTag.summaryBadge()}) to clipboard."
                    },
                )

                Surface(color = AresGold.copy(alpha = .10f), shape = RoundedCornerShape(7.dp), border = BorderStroke(1.dp, AresGold.copy(alpha = .45f))) {
                    Row(Modifier.fillMaxWidth().padding(10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Warning, null, tint = AresGold, modifier = Modifier.size(18.dp))
                        Text(
                            "Signal presence is not a hardware certification. This page never pulses motors or declares the robot match-ready.",
                            color = AresTextPrimary,
                            fontSize = 12.sp,
                        )
                    }
                }

                receiptStatusMessage?.let { msg ->
                    Text(msg, fontSize = 11.sp, color = AresGreen, fontWeight = FontWeight.SemiBold)
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().weight(1f),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // Left Column: Automated Passive NT4 Signal Matrix
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
                        items(steps, key = SelfTestStep::id) { step -> ReadinessCard(step) }
                    }
                }
            }

            // Right Column: Manual Pit Crew Physical Inspection Checklist
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
                            onClick = { manualChecklist = manualChecklist.map { it.copy(checked = false) } },
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
                                onToggle = {
                                    manualChecklist = manualChecklist.map { cur ->
                                        if (cur.id == item.id) cur.copy(checked = !cur.checked) else cur
                                    }
                                },
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
    onMatchTagChange: (PitMatchTag) -> Unit,
    onExportMarkdown: () -> Unit,
    onCopyMarkdown: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
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
                modifier = Modifier.width(86.dp),
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
                value = matchTag.inspectorInitials,
                onValueChange = { onMatchTagChange(matchTag.copy(inspectorInitials = it.take(16))) },
                modifier = Modifier.width(110.dp),
                singleLine = true,
                placeholder = { Text("Crew initials", fontSize = 11.sp) },
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
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
private fun ReadinessCard(step: SelfTestStep) {
    val color = when (step.status) {
        SelfTestStatus.OBSERVED -> AresGreen
        SelfTestStatus.WARNING -> AresGold
        SelfTestStatus.WAITING -> AresTextSecondary
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = AresSurfaceElevated),
        border = BorderStroke(1.dp, if (step.status == SelfTestStatus.WAITING) AresBorder else color.copy(alpha = .65f)),
        shape = RoundedCornerShape(8.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                Text(step.name, color = AresTextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                StatusPill(step.status.name, color)
            }
            Text(step.description, color = AresTextSecondary, fontSize = 11.sp)
            Text(step.details, color = color, fontSize = 11.sp)
        }
    }
}

@Composable
private fun StatusPill(label: String, color: Color) {
    Surface(color = color.copy(alpha = .12f), shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, color.copy(alpha = .6f))) {
        Text(label, color = color, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp))
    }
}
