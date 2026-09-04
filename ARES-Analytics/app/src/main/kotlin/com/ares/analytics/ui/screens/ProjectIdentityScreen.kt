package com.ares.analytics.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ares.analytics.shared.models.League
import com.ares.analytics.shared.models.WorkspaceConfig
import com.ares.analytics.ui.theme.*
import com.ares.analytics.viewmodel.project.ProjectIdentityEditorState
import com.ares.analytics.viewmodel.project.ProjectIdentityField
import com.ares.analytics.viewmodel.project.ProjectIdentityViewModel
import com.areslib.project.AresFtcHubCommandTransport

/** Reviewed editor for canonical `.ares/project.json`; workspace preferences remain separate. */
@Composable
@OptIn(ExperimentalLayoutApi::class)
fun ProjectIdentityScreen(
    viewModel: ProjectIdentityViewModel,
    config: WorkspaceConfig,
    onBackToStudio: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsState()
    LaunchedEffect(config) { viewModel.load(config) }

    LazyColumn(
        modifier = modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (onBackToStudio != null) {
                    OutlinedButton(onClick = onBackToStudio) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("Robot Studio")
                    }
                }
                Column {
                    Text(
                        "Project Identity & Robot Footprint",
                        modifier = Modifier.semantics { heading() },
                        color = AresTextPrimary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                    )
                    Text(
                        "Configure the robot identity and outer physical boundary shared by simulators, collision checkers, and autonomous paths.",
                        color = AresTextSecondary,
                        fontSize = 12.sp,
                    )
                }
            }
        }
        item { ProjectIdentityDestinationCard(state) }

        if (state.loading) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator(modifier = Modifier.width(22.dp), strokeWidth = 2.dp)
                    Text("Reading canonical project identity…", color = AresTextSecondary)
                }
            }
        } else {
            state.projectSourceError?.let { error ->
                item { MissingRobotSourceCard(error) }
            }
            state.protectedError?.let { error ->
                item { ProtectedProjectIdentityCard(error, repairAvailable = state.protectedContentHash != null) }
            }
            item {
                ProjectIdentityForm(
                    state = state,
                    onUpdate = viewModel::update,
                    onHubCommandTransport = viewModel::updateFtcHubCommandTransport,
                    onLimelightProxyEnabled = viewModel::updateFtcLimelightProxyEnabled,
                    onXrpWifiMode = viewModel::updateXrpWifiMode,
                )
            }
            if (state.generalErrors.isNotEmpty()) {
                item { ProjectIdentityErrors(state.generalErrors) }
            }
            state.message?.let { message ->
                item {
                    ProjectIdentityMessage(
                        message = message,
                        isError = state.messageIsError,
                        isWarning = state.projectSourceError != null,
                    )
                }
            }
            state.proposal?.let { proposal ->
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = AresSurfaceElevated),
                        border = BorderStroke(1.dp, AresGold),
                    ) {
                        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("Reviewed diff · confirmation required", color = AresTextPrimary, fontWeight = FontWeight.Bold)
                            Text(
                                if (proposal.expectedInvalidRawContentHash != null) {
                                    "The invalid file is hash-bound to this review and will be copied byte-for-byte to .ares/recovery/project before .ares/project.json is replaced."
                                } else {
                                    "Only .ares/project.json will change. Existing valid content is checkpointed under .ares/history/project before replacement."
                                },
                                color = AresTextSecondary,
                                fontSize = 12.sp,
                            )
                            proposal.changes.forEach { change ->
                                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(change.label, color = AresTextPrimary, fontWeight = FontWeight.SemiBold)
                                    Text("Before: ${change.before}", color = AresTextSecondary, fontFamily = FontFamily.Monospace)
                                    Text("After:  ${change.after}", color = AresTextPrimary, fontFamily = FontFamily.Monospace)
                                }
                            }
                            Text(
                                "Proposed SHA-256: ${proposal.proposedContentHash}",
                                color = AresTextTertiary,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp,
                            )
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Button(
                                    onClick = viewModel::applyReviewed,
                                    enabled = !state.saving,
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = AresCyan,
                                        contentColor = AresOnAccent,
                                    ),
                                ) {
                                    Text(
                                        when {
                                            proposal.expectedInvalidRawContentHash != null -> "Preserve original and repair identity"
                                            state.currentDocument == null -> "Create reviewed identity"
                                            else -> "Save reviewed changes"
                                        },
                                    )
                                }
                                OutlinedButton(onClick = viewModel::cancelReview, enabled = !state.saving) {
                                    Text("Keep editing")
                                }
                            }
                        }
                    }
                }
            }
            item {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = viewModel::review,
                        enabled = state.canReview && state.proposal == null,
                        colors = ButtonDefaults.buttonColors(containerColor = AresCyan, contentColor = AresOnAccent),
                    ) {
                        Text(if (state.protectedContentHash != null) "Review protected-file repair" else "Review structured diff")
                    }
                    OutlinedButton(onClick = viewModel::resetDraft, enabled = !state.saving) {
                        Text("Discard draft changes")
                    }
                    OutlinedButton(onClick = { viewModel.load(config) }, enabled = !state.saving) {
                        Icon(Icons.Default.Refresh, contentDescription = null)
                        Spacer(Modifier.width(5.dp))
                        Text("Reload project file")
                    }
                }
                projectIdentityReviewGuidance(state)?.let { guidance ->
                    Spacer(Modifier.height(6.dp))
                    Text(guidance, color = if (state.projectSourceError != null) AresError else AresGold, fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun MissingRobotSourceCard(error: String) {
    Card(
        colors = CardDefaults.cardColors(containerColor = AresGold.copy(alpha = 0.10f)),
        border = BorderStroke(1.dp, AresGold),
    ) {
        Row(Modifier.fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            Icon(Icons.Default.Info, contentDescription = null, tint = AresGold)
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(
                    "Robot source is missing · identity metadata alone cannot create a robot",
                    color = AresTextPrimary,
                    fontWeight = FontWeight.Bold,
                )
                Text(error, color = AresTextSecondary)
                Text(
                    "Use the robot selector at the top left to open an existing project. To start over, choose Create or open a robot… and create an official starter. Removing a workspace profile never deletes its files.",
                    color = AresTextPrimary,
                    fontSize = 12.sp,
                )
            }
        }
    }
}

internal fun projectIdentityReviewGuidance(state: ProjectIdentityEditorState): String? = when {
    state.projectSourceError != null ->
        "Saving is unavailable because the selected folder has no robot source. Switch to a real project or create an official starter first."
    state.proposal != null -> null
    state.canReview -> null
    state.fieldErrors.containsKey(ProjectIdentityField.ROBOT_LENGTH) ||
        state.fieldErrors.containsKey(ProjectIdentityField.ROBOT_WIDTH) ->
        "Enter valid measured robot length and width above to enable review."
    state.fieldErrors.isNotEmpty() || state.generalErrors.isNotEmpty() ->
        "Fix the highlighted identity fields to enable review."
    else -> null
}

internal fun protectedProjectIdentityExplanation(error: String): String? = when {
    error.contains("authoringModel") ->
        "This folder uses the retired schema-3 project format. Current Studio supports schema-4 projects only and will not rewrite this project automatically. Open the current Lightbot project, or create/export a new robot from Studio; you do not need to delete the old folder."
    else -> null
}

@Composable
private fun ProjectIdentityDestinationCard(state: ProjectIdentityEditorState) {
    Card(
        colors = CardDefaults.cardColors(containerColor = AresSurfaceElevated),
        border = BorderStroke(1.dp, AresBorder),
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text("Selected project", color = AresTextPrimary, fontWeight = FontWeight.Bold)
            Text(state.projectPath, color = AresTextSecondary, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
            HorizontalDivider(color = AresBorder)
            Text("Stored in: .ares/project.json", color = AresTextPrimary)
            Text(
                "Consumed by: Robot Studio, Drivebase Builder, Superstructure Studio, Autonomous Planner, simulators, and collision bounds.",
                color = AresTextSecondary,
                fontSize = 12.sp,
            )
        }
    }
}

@Composable
private fun ProtectedProjectIdentityCard(error: String, repairAvailable: Boolean) {
    val explanation = protectedProjectIdentityExplanation(error)
    Card(
        colors = CardDefaults.cardColors(containerColor = AresError.copy(alpha = 0.10f)),
        border = BorderStroke(1.dp, AresError),
    ) {
        Row(Modifier.fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            Icon(Icons.Default.Error, contentDescription = null, tint = AresError)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    if (explanation != null) "Retired project format · select a current project"
                    else if (repairAvailable) "Invalid project file preserved · reviewed repair available"
                    else "Protected project file · no write allowed",
                    color = AresTextPrimary,
                    fontWeight = FontWeight.Bold,
                )
                Text(error, color = AresTextSecondary)
                explanation?.let {
                    Text(it, color = AresTextPrimary, fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun ProjectIdentityForm(
    state: ProjectIdentityEditorState,
    onUpdate: (ProjectIdentityField, String) -> Unit,
    onHubCommandTransport: (AresFtcHubCommandTransport) -> Unit,
    onLimelightProxyEnabled: (Boolean) -> Unit,
    onXrpWifiMode: (String) -> Unit,
) {
    val isFtc = state.workspaceLeague == League.FTC
    val isXrp = state.workspaceLeague == League.XRP
    val lengthM = state.draft.robotLengthMeters.toDoubleOrNull() ?: 0.0
    val widthM = state.draft.robotWidthMeters.toDoubleOrNull() ?: 0.0
    val lengthIn = lengthM * 39.3701
    val widthIn = widthM * 39.3701
    val fitsSizingBox = !isFtc || (lengthM <= 0.4572 && widthM <= 0.4572 && lengthM > 0.0 && widthM > 0.0)
    val sourceAvailable = state.projectSourceError == null
    val runtimeOptionsEnabled = sourceAvailable &&
        (state.protectedError == null || state.protectedContentHash != null)

    Card(
        colors = CardDefaults.cardColors(containerColor = AresSurfaceElevated),
        border = BorderStroke(1.dp, AresBorder),
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Canonical identity", color = AresTextPrimary, fontWeight = FontWeight.Bold)
            Text(
                "This file is the one shared identity source for Studio, generated code, simulation, and verification. Stable IDs lock after creation; the display name remains editable.",
                color = AresTextSecondary,
                fontSize = 12.sp,
            )
            IdentityField(
                label = "Stable project ID",
                value = state.draft.projectId,
                onValueChange = { onUpdate(ProjectIdentityField.PROJECT_ID, it) },
                error = state.fieldErrors[ProjectIdentityField.PROJECT_ID],
                enabled = state.currentDocument == null &&
                    sourceAvailable &&
                    (state.protectedError == null || state.protectedContentHash != null),
                help = "Starts with a letter; letters, numbers, dot, underscore, and dash only.",
            )
            IdentityField(
                label = "Team ID",
                value = state.draft.teamId,
                onValueChange = { onUpdate(ProjectIdentityField.TEAM_ID, it) },
                error = state.fieldErrors[ProjectIdentityField.TEAM_ID],
                enabled = state.currentDocument == null && runtimeOptionsEnabled,
                help = "Usually your team number; stored with the project so another computer sees the same identity.",
            )
            IdentityField(
                label = "Season ID",
                value = state.draft.seasonId,
                onValueChange = { onUpdate(ProjectIdentityField.SEASON_ID, it) },
                error = state.fieldErrors[ProjectIdentityField.SEASON_ID],
                enabled = state.currentDocument == null && runtimeOptionsEnabled,
                help = "A stable season key such as 2026.",
            )
            IdentityField(
                label = "Stable robot ID",
                value = state.draft.robotId,
                onValueChange = { onUpdate(ProjectIdentityField.ROBOT_ID, it) },
                error = state.fieldErrors[ProjectIdentityField.ROBOT_ID],
                enabled = state.currentDocument == null && runtimeOptionsEnabled,
                help = "Used by generated files and evidence records; change it only through a coordinated project rename.",
            )
            IdentityField(
                label = "Robot display name",
                value = state.draft.displayName,
                onValueChange = { onUpdate(ProjectIdentityField.DISPLAY_NAME, it) },
                error = state.fieldErrors[ProjectIdentityField.DISPLAY_NAME],
                enabled = runtimeOptionsEnabled,
                help = "The friendly name students see. This can be changed without breaking references.",
            )
            ReadOnlyIdentityRow("League", state.workspaceLeague.name)
            ReadOnlyIdentityRow(
                "Coordinate convention",
                if (state.workspaceLeague == League.FRC) "BLUE_CORNER_ORIGIN_CCW (0,0 blue corner)"
                else "CENTER_ORIGIN_CCW (0,0 center)",
            )

            HorizontalDivider(color = AresBorder)

            // Robot Outer Footprint Section
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text("Robot Bumper Footprint (Outer Dimensions)", color = AresTextPrimary, fontWeight = FontWeight.Bold)
                    Text(
                        "Measure bumper-to-bumper outer length and width for collision detection and autonomous path clearance.",
                        color = AresTextSecondary,
                        fontSize = 11.sp,
                    )
                }

                if (isFtc && lengthM > 0.0 && widthM > 0.0) {
                    Surface(
                        color = if (fitsSizingBox) AresGreen.copy(alpha = 0.15f) else AresError.copy(alpha = 0.15f),
                        shape = RoundedCornerShape(4.dp),
                        border = BorderStroke(1.dp, if (fitsSizingBox) AresGreen else AresError),
                    ) {
                        Text(
                            if (fitsSizingBox) "✓ 18\" Sizing Box Compliant" else "⚠ Exceeds 18\" FTC Sizing Box",
                            color = if (fitsSizingBox) AresGreen else AresError,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                }
            }

            GeometryRow(
                firstLabel = "Robot length (meters)",
                firstValue = state.draft.robotLengthMeters,
                firstError = state.fieldErrors[ProjectIdentityField.ROBOT_LENGTH],
                onFirst = { onUpdate(ProjectIdentityField.ROBOT_LENGTH, it) },
                firstHelp = if (lengthIn > 0.0) "≈ ${"%.1f".format(lengthIn)} inches" else "Positive decimal meters.",
                secondLabel = "Robot width (meters)",
                secondValue = state.draft.robotWidthMeters,
                secondError = state.fieldErrors[ProjectIdentityField.ROBOT_WIDTH],
                onSecond = { onUpdate(ProjectIdentityField.ROBOT_WIDTH, it) },
                secondHelp = if (widthIn > 0.0) "≈ ${"%.1f".format(widthIn)} inches" else "Positive decimal meters.",
                enabled = sourceAvailable && (state.protectedError == null || state.protectedContentHash != null),
            )

            if (isFtc) {
                HorizontalDivider(color = AresBorder)
                FtcRuntimeOptionsEditor(
                    transport = state.draft.ftcHubCommandTransport,
                    limelightProxyEnabled = state.draft.ftcLimelightProxyEnabled,
                    enabled = runtimeOptionsEnabled,
                    onTransportChanged = onHubCommandTransport,
                    onLimelightProxyChanged = onLimelightProxyEnabled,
                )
            }

            if (isXrp) {
                HorizontalDivider(color = AresBorder)
                XrpRuntimeOptionsEditor(
                    state = state,
                    enabled = runtimeOptionsEnabled,
                    onUpdate = onUpdate,
                    onWifiModeChanged = onXrpWifiMode,
                )
            }

            HorizontalDivider(color = AresBorder)

            // Standard Field Environment Preset Card (Read-only automatic preset)
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = AresSurface,
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(1.dp, AresBorder),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Default.Info, contentDescription = null, tint = AresCyan, modifier = Modifier.size(20.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            "Field Environment: Official ${state.workspaceLeague.name} Standard",
                            color = AresTextPrimary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp,
                        )
                        Text(
                            when (state.workspaceLeague) {
                                League.FTC -> "Standard 12ft × 12ft (3.66m × 3.66m) competition perimeter. Game elements & AprilTags are configured in the Field Editor / Autonomous Planner."
                                League.FRC -> "Standard 54ft × 27ft (16.54m × 8.21m) competition perimeter. Game elements & AprilTags are configured in the Field Editor / Autonomous Planner."
                                League.XRP -> "Desktop practice field 100in × 56in (2.54m × 1.42m). Change the canonical dimensions when your taped practice area is different."
                            },
                            color = AresTextSecondary,
                            fontSize = 10.sp,
                        )
                    }
                }
            }
        }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun XrpRuntimeOptionsEditor(
    state: ProjectIdentityEditorState,
    enabled: Boolean,
    onUpdate: (ProjectIdentityField, String) -> Unit,
    onWifiModeChanged: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text("XRP connection & safety", color = AresTextPrimary, fontWeight = FontWeight.Bold)
            Text(
                "These settings are shared by the generated MicroPython robot, local simulator, and Studio. Use a unique Link port when running multiple projects on one computer.",
                color = AresTextSecondary,
                fontSize = 11.sp,
            )
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(
                selected = state.draft.xrpWifiMode == "AP",
                onClick = { onWifiModeChanged("AP") },
                enabled = enabled,
                label = { Text("XRP creates Wi-Fi") },
            )
            FilterChip(
                selected = state.draft.xrpWifiMode == "STATION",
                onClick = { onWifiModeChanged("STATION") },
                enabled = enabled,
                label = { Text("Join an existing network") },
            )
        }
        IdentityField(
            label = "Wi-Fi network name (SSID)",
            value = state.draft.xrpSsid,
            onValueChange = { onUpdate(ProjectIdentityField.XRP_SSID, it) },
            error = state.fieldErrors[ProjectIdentityField.XRP_SSID],
            enabled = enabled,
            help = "The network the laptop and XRP use together. Passwords remain in the untracked xrp_secrets.py file.",
        )
        GeometryRow(
            firstLabel = "XRP Link port",
            firstValue = state.draft.xrpLinkPort,
            firstError = state.fieldErrors[ProjectIdentityField.XRP_LINK_PORT],
            onFirst = { onUpdate(ProjectIdentityField.XRP_LINK_PORT, it) },
            firstHelp = "Dedicated JSONL control/telemetry port; 5810 is reserved for NT4.",
            secondLabel = "Deadman timeout (ms)",
            secondValue = state.draft.xrpDeadmanTimeoutMs,
            secondError = state.fieldErrors[ProjectIdentityField.XRP_DEADMAN_TIMEOUT],
            onSecond = { onUpdate(ProjectIdentityField.XRP_DEADMAN_TIMEOUT, it) },
            secondHelp = "Motors stop when fresh commands do not arrive within this interval.",
            enabled = enabled,
        )
        IdentityField(
            label = "Brownout threshold (volts)",
            value = state.draft.xrpBrownoutThresholdVolts,
            onValueChange = { onUpdate(ProjectIdentityField.XRP_BROWNOUT_THRESHOLD, it) },
            error = state.fieldErrors[ProjectIdentityField.XRP_BROWNOUT_THRESHOLD],
            enabled = enabled,
            help = "Commands fail closed below this project-specific XRP battery voltage.",
        )
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun FtcRuntimeOptionsEditor(
    transport: AresFtcHubCommandTransport,
    limelightProxyEnabled: Boolean,
    enabled: Boolean,
    onTransportChanged: (AresFtcHubCommandTransport) -> Unit,
    onLimelightProxyChanged: (Boolean) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text("Control Hub runtime", color = AresTextPrimary, fontWeight = FontWeight.Bold)
            Text(
                "Choose how this robot sends motor commands. The choice is saved with the project, generated into robot code, and reported back on the dashboard.",
                color = AresTextSecondary,
                fontSize = 11.sp,
            )
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(
                selected = transport == AresFtcHubCommandTransport.STANDARD_SDK,
                onClick = { onTransportChanged(AresFtcHubCommandTransport.STANDARD_SDK) },
                enabled = enabled,
                label = { Text("Standard FTC SDK · recommended") },
            )
            FilterChip(
                selected = transport == AresFtcHubCommandTransport.ARES_PHOTON,
                onClick = { onTransportChanged(AresFtcHubCommandTransport.ARES_PHOTON) },
                enabled = enabled,
                label = { Text("ARES Photon · experimental") },
            )
        }
        Text(
            if (transport == AresFtcHubCommandTransport.ARES_PHOTON) {
                "Experimental: ARES may use a lower-overhead direct REV Hub write path. Every unsupported or failed command falls back to the FTC SDK. Verify it on restrained physical hardware before competition. Local simulation shows it as selected but not hardware-active."
            } else {
                "Uses the supported FTC SDK command path with ARES cached reads and safety handling. This is the safest starting point for a new robot."
            },
            color = if (transport == AresFtcHubCommandTransport.ARES_PHOTON) AresGold else AresTextSecondary,
            fontSize = 11.sp,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Limelight camera proxy", color = AresTextPrimary, fontWeight = FontWeight.SemiBold)
                Text(
                    "Off by default. Enable only when the laptop must reach Limelight web/video ports through the Control Hub.",
                    color = AresTextSecondary,
                    fontSize = 11.sp,
                )
            }
            Switch(
                checked = limelightProxyEnabled,
                onCheckedChange = onLimelightProxyChanged,
                enabled = enabled,
            )
        }
    }
}

@Composable
private fun GeometryRow(
    firstLabel: String,
    firstValue: String,
    firstError: String?,
    onFirst: (String) -> Unit,
    firstHelp: String = "Positive decimal meters.",
    secondLabel: String,
    secondValue: String,
    secondError: String?,
    onSecond: (String) -> Unit,
    secondHelp: String = "Positive decimal meters.",
    enabled: Boolean,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        IdentityField(firstLabel, firstValue, onFirst, firstError, enabled, firstHelp, Modifier.weight(1f))
        IdentityField(secondLabel, secondValue, onSecond, secondError, enabled, secondHelp, Modifier.weight(1f))
    }
}

@Composable
private fun IdentityField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    error: String?,
    enabled: Boolean,
    help: String,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        supportingText = { Text(error ?: help) },
        isError = error != null,
        enabled = enabled,
        singleLine = true,
        modifier = modifier,
    )
}

@Composable
private fun ReadOnlyIdentityRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, modifier = Modifier.weight(0.35f), color = AresTextSecondary)
        Text(value, modifier = Modifier.weight(0.65f), color = AresTextPrimary, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun ProjectIdentityErrors(errors: List<String>) {
    Card(
        colors = CardDefaults.cardColors(containerColor = AresError.copy(alpha = 0.10f)),
        border = BorderStroke(1.dp, AresError),
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Cannot review yet", color = AresTextPrimary, fontWeight = FontWeight.Bold)
            errors.forEach { Text("• $it", color = AresTextSecondary) }
        }
    }
}

@Composable
private fun ProjectIdentityMessage(message: String, isError: Boolean, isWarning: Boolean = false) {
    val icon = when {
        isError -> Icons.Default.Error
        isWarning -> Icons.Default.Info
        else -> Icons.Default.CheckCircle
    }
    val color = when {
        isError -> AresError
        isWarning -> AresGold
        else -> AresGreen
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = color,
        )
        Text(
            text = when {
                isError -> "Error: $message"
                isWarning -> "Action needed: $message"
                else -> "Status: $message"
            },
            color = AresTextPrimary,
        )
    }
}
