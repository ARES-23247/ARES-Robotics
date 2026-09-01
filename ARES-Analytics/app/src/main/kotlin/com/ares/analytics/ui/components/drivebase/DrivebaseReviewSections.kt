package com.ares.analytics.ui.components.drivebase

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ares.analytics.service.drivebase.*
import com.ares.analytics.shared.models.League
import com.ares.analytics.ui.theme.*
import com.ares.analytics.viewmodel.drivebase.*
import com.areslib.drivetrain.DisabledDrivePolicy
import com.areslib.drivetrain.DrivetrainControlKind
import com.areslib.drivetrain.DrivetrainNeutralMode

@Composable
fun SafetyAndReviewStep(
    state: DrivebaseBuilderState,
    viewModel: DrivebaseBuilderViewModel,
    onContinueToSubsystems: (() -> Unit)? = null,
    onBackToStudio: (() -> Unit)? = null,
) {
    SectionHeading("6 · Safety rules & save review", "Review fail-closed safety contracts, validate the draft diff, and save the canonical drivebase.")
    val safety = state.draft.safety
    val review = state.saveReview
    val noCodeRunnable = state.draft.kind.runtimeSupport(state.league) == DrivebaseRuntimeSupport.NO_CODE_RUNNABLE

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // Safety Interlocks & Limits (2 columns)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Surface(
                modifier = Modifier.weight(1f),
                color = AresSurface,
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.dp, AresBorder),
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text("FAIL-CLOSED SAFETY INTERLOCKS", color = AresTextSecondary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    SafetySwitch("Safe neutral required", safety.safeNeutralRequired, "Outputs become neutral at startup, disable, stop, and fault.") { viewModel.onIntent(DrivebaseBuilderIntent.UpdateSafety(safety.copy(safeNeutralRequired = it))) }
                    SafetySwitch("Configuration health required", safety.configurationHealthRequired, "Nonzero motion is blocked until all required devices report healthy configuration.") { viewModel.onIntent(DrivebaseBuilderIntent.UpdateSafety(safety.copy(configurationHealthRequired = it))) }
                    SafetySwitch("Explicit neutral recovery", safety.explicitNeutralRecoveryRequired, "Motion resumes only after a successful neutral write is confirmed.") { viewModel.onIntent(DrivebaseBuilderIntent.UpdateSafety(safety.copy(explicitNeutralRecoveryRequired = it))) }
                    SafetySwitch("Current monitoring required", safety.currentMonitoringRequired, "Monitoring must report validity for continuous current protection.") { viewModel.onIntent(DrivebaseBuilderIntent.UpdateSafety(safety.copy(currentMonitoringRequired = it))) }
                    SafetySwitch("Latch drive faults", safety.faultLatchingRequired, "A transient output or feedback failure stays faulted until explicit neutral recovery succeeds.") { viewModel.onIntent(DrivebaseBuilderIntent.UpdateSafety(safety.copy(faultLatchingRequired = it))) }
                    SimpleChoice(
                        label = "Enabled neutral mode",
                        selected = safety.enabledNeutralMode.name,
                        options = DrivetrainNeutralMode.entries.map { it.name },
                        onSelect = { viewModel.onIntent(DrivebaseBuilderIntent.UpdateSafety(safety.copy(enabledNeutralMode = DrivetrainNeutralMode.valueOf(it)))) },
                    )
                    SimpleChoice(
                        label = "Disabled behavior",
                        selected = safety.disabledPolicy.name,
                        options = DisabledDrivePolicy.entries.map { it.name },
                        onSelect = { viewModel.onIntent(DrivebaseBuilderIntent.UpdateSafety(safety.copy(disabledPolicy = DisabledDrivePolicy.valueOf(it)))) },
                    )
                }
            }

            Surface(
                modifier = Modifier.weight(1f),
                color = AresSurface,
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.dp, AresBorder),
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("COMMAND ENVELOPE & LIMITS", color = AresTextSecondary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    GeometryField("Feedback freshness timeout", safety.feedbackFreshnessTimeoutMs.toDouble(), "ms", "Feedback older than this is stale and blocks closed-loop output.") { viewModel.onIntent(DrivebaseBuilderIntent.UpdateSafety(safety.copy(feedbackFreshnessTimeoutMs = it.toInt()))) }
                    GeometryField("Maximum linear speed", safety.maxLinearSpeedMetersPerSecond, "m/s", "Hard command envelope used by control and simulation.") { viewModel.onIntent(DrivebaseBuilderIntent.UpdateSafety(safety.copy(maxLinearSpeedMetersPerSecond = it))) }
                    GeometryField("Maximum angular speed", safety.maxAngularSpeedRadiansPerSecond, "rad/s", "Positive rotation is counter-clockwise.") { viewModel.onIntent(DrivebaseBuilderIntent.UpdateSafety(safety.copy(maxAngularSpeedRadiansPerSecond = it))) }
                }
            }
        }

        // Structured Diff / Save Section
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = AresSurface,
            shape = RoundedCornerShape(10.dp),
            border = BorderStroke(1.dp, AresBorder),
        ) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("DOCUMENT REVIEW & DIFF", color = AresTextSecondary, fontSize = 10.sp, fontWeight = FontWeight.Bold)

                if (review == null) {
                    if (state.dirty) {
                        Text("Select Review Changes to validate the draft against ARES rules and generate a content-hash-bound structured diff.", color = AresTextSecondary, fontSize = 11.sp)
                        Button(
                            onClick = { viewModel.onIntent(DrivebaseBuilderIntent.ReviewSave) },
                            enabled = noCodeRunnable,
                            colors = ButtonDefaults.buttonColors(containerColor = AresCyan, contentColor = AresOnAccent),
                        ) {
                            Text(if (noCodeRunnable) "Create Reviewed Diff" else "Code Required Before Save", fontWeight = FontWeight.Bold)
                        }
                    } else {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = AresGreen.copy(alpha = 0.12f),
                            border = BorderStroke(1.dp, AresGreen),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                "✓ Saved · The canonical drivebase document already matches this form.",
                                color = AresGreen,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(10.dp)
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (onContinueToSubsystems != null) {
                                Button(
                                    onClick = onContinueToSubsystems,
                                    colors = ButtonDefaults.buttonColors(containerColor = AresCyan, contentColor = AresOnAccent),
                                ) {
                                    Text("Next: Mechanism Subsystems →", fontWeight = FontWeight.Bold)
                                }
                            }
                            if (onBackToStudio != null) {
                                OutlinedButton(onClick = onBackToStudio) {
                                    Text("Return to Robot Studio")
                                }
                            }
                        }
                    }
                } else {
                    Text("The following changes will be written to .ares/drivetrains:", color = AresTextPrimary, fontSize = 11.sp)
                    review.changes.forEach { change ->
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = AresSurfaceElevated,
                            border = BorderStroke(1.dp, AresBorder),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(Modifier.padding(8.dp)) {
                                Text(change.path, color = AresCyan, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                                Text("Before: ${change.before}", color = AresTextSecondary, fontSize = 10.sp)
                                Text("After:  ${change.after}", color = AresTextPrimary, fontSize = 10.sp)
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            onClick = { viewModel.onIntent(DrivebaseBuilderIntent.ConfirmSave(review.confirmationToken)) },
                            colors = ButtonDefaults.buttonColors(containerColor = AresGreen, contentColor = AresOnAccent),
                        ) {
                            Text("Confirm & Save", fontWeight = FontWeight.Bold)
                        }
                        OutlinedButton(onClick = { viewModel.onIntent(DrivebaseBuilderIntent.Reload) }) {
                            Text("Discard Changes")
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun HardwareEditor(
    device: DriveHardwareDeclaration,
    hardware: List<DriveHardwareDeclaration>,
    advanced: Boolean,
    onUpdate: (DriveHardwareDeclaration) -> Unit,
    onRemove: () -> Unit,
) {
    Text(device.displayName, color = AresCyan, fontWeight = FontWeight.Bold, fontSize = 16.sp)
    Text(device.role.name.lowercase().replace('_', ' '), color = AresTextSecondary, fontSize = 10.sp)
    HelpedTextField("Display name", device.displayName, "A student-facing label. It does not change the stable device ID.") { onUpdate(device.copy(displayName = it)) }
    HelpedTextField(
        if (device.canId != null) "Device label (optional)" else "Hardware-map name",
        device.hardwareName,
        if (device.canId != null) {
            "A readable project label. The CAN ID below is the physical device address."
        } else {
            "The exact configured name used by the FTC Robot Controller."
        },
    ) { onUpdate(device.copy(hardwareName = it)) }
    if (advanced) {
        var roleMenu by remember(device.id) { mutableStateOf(false) }
        Box {
            OutlinedButton({ roleMenu = true }, Modifier.fillMaxWidth()) { Text("Role · ${device.role.name.lowercase().replace('_', ' ')}") }
            DropdownMenu(roleMenu, { roleMenu = false }) {
                DriveHardwareRole.entries.forEach { role ->
                    DropdownMenuItem({ Text(role.name.lowercase().replace('_', ' ')) }, {
                        roleMenu = false
                        onUpdate(device.copy(role = role, leaderId = if (role in setOf(DriveHardwareRole.LEFT_FOLLOWER, DriveHardwareRole.RIGHT_FOLLOWER)) device.leaderId else null))
                    })
                }
            }
        }
    }
    if (device.role in setOf(DriveHardwareRole.LEFT_FOLLOWER, DriveHardwareRole.RIGHT_FOLLOWER)) {
        var leaderMenu by remember(device.id) { mutableStateOf(false) }
        val leaders = hardware.filter { it.id != device.id && it.role in setOf(DriveHardwareRole.LEFT_LEADER, DriveHardwareRole.RIGHT_LEADER, DriveHardwareRole.DRIVE_MOTOR) }
        Box {
            OutlinedButton({ leaderMenu = true }, Modifier.fillMaxWidth()) { Text("Leader · ${device.leaderId ?: "Choose a leader"}") }
            DropdownMenu(leaderMenu, { leaderMenu = false }) {
                leaders.forEach { leader -> DropdownMenuItem({ Text(leader.displayName) }, { leaderMenu = false; onUpdate(device.copy(leaderId = leader.id)) }) }
            }
        }
        Text("Follower inversion below is relative to the leader and remains independent from the leader's own mounting inversion.", color = AresTextSecondary, fontSize = 9.sp)
    }
    if (advanced || device.canId != null) {
        HelpedTextField("CAN ID", device.canId?.toString().orEmpty(), "The unique numeric CAN address. Valid ARES range: 0–62.") { onUpdate(device.copy(canId = it.toIntOrNull())) }
        HelpedTextField("CAN bus", device.canBus.orEmpty(), "The named CAN network, for example rio or CANivore name.") { onUpdate(device.copy(canBus = it.ifBlank { null })) }
    }
    val motorRoles = setOf(
        DriveHardwareRole.FRONT_LEFT, DriveHardwareRole.FRONT_RIGHT, DriveHardwareRole.REAR_LEFT, DriveHardwareRole.REAR_RIGHT,
        DriveHardwareRole.LEFT_LEADER, DriveHardwareRole.LEFT_FOLLOWER, DriveHardwareRole.RIGHT_LEADER, DriveHardwareRole.RIGHT_FOLLOWER,
        DriveHardwareRole.FRONT_LEFT_DRIVE, DriveHardwareRole.FRONT_RIGHT_DRIVE, DriveHardwareRole.REAR_LEFT_DRIVE, DriveHardwareRole.REAR_RIGHT_DRIVE,
        DriveHardwareRole.FRONT_LEFT_STEER, DriveHardwareRole.FRONT_RIGHT_STEER, DriveHardwareRole.REAR_LEFT_STEER, DriveHardwareRole.REAR_RIGHT_STEER,
        DriveHardwareRole.DRIVE_MOTOR,
    )
    if (device.role in motorRoles || advanced) {
        HelpedTextField("Motor controller model", device.controllerModel.orEmpty(), "Examples: goBILDA 5203 through REV Hub, TalonFX. Used for documentation and adapter validation; it does not guess gains.") { onUpdate(device.copy(controllerModel = it.ifBlank { null })) }
        if (device.role.name.endsWith("ENCODER") || advanced) {
            HelpedTextField("Encoder model", device.encoderModel.orEmpty(), "Examples: built-in quadrature encoder or CANcoder. Choose the adapter that owns cached position and velocity.") { onUpdate(device.copy(encoderModel = it.ifBlank { null })) }
        }
        SafetySwitch("Current sample required", device.currentMeasurementRequired, "Nonzero output is blocked if this motor's cached current reading is unavailable or invalid.") { onUpdate(device.copy(currentMeasurementRequired = it)) }
        SafetySwitch("Adapter provides current", device.currentMeasurementAvailable, "Only enable this when the selected hardware adapter supplies a cached, validity-checked current reading.") { onUpdate(device.copy(currentMeasurementAvailable = it)) }
        NullableNumberField("Controller-enforced current limit", device.currentLimitAmps, "A", "Leave blank if this is only a reviewed operating threshold and is not actually enforced by the motor controller.") { onUpdate(device.copy(currentLimitAmps = it)) }
    }
    val isAuxiliaryOrSensors = device.role in setOf(
        DriveHardwareRole.ODOMETRY,
        DriveHardwareRole.LIMELIGHT,
        DriveHardwareRole.DISTANCE_SENSOR,
        DriveHardwareRole.GYRO,
        DriveHardwareRole.OTHER,
        DriveHardwareRole.CUSTOM
    )
    val isVisionCamera = device.role == DriveHardwareRole.LIMELIGHT

    if (isAuxiliaryOrSensors || advanced) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("PHYSICAL MOUNTING POSITION (3D TRANSLATION)", color = AresTextSecondary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            GeometryField(
                label = "X Mounting Offset",
                value = device.xMeters ?: 0.0,
                unit = "m",
                explanation = "Longitudinal offset forward (+) or backward (-) from robot center of rotation. Required for Pinpoint turning arc compensation and 3D camera pose estimation."
            ) { onUpdate(device.copy(xMeters = it)) }
            GeometryField(
                label = "Y Mounting Offset",
                value = device.yMeters ?: 0.0,
                unit = "m",
                explanation = "Lateral offset left (+) or right (-) from robot center of rotation. Required for Pinpoint turning arc compensation and 3D camera pose estimation."
            ) { onUpdate(device.copy(yMeters = it)) }
            GeometryField(
                label = "Z Mounting Height",
                value = device.zMeters ?: 0.0,
                unit = "m",
                explanation = "Vertical height from the floor / ground plane up to the sensor or camera optical center."
            ) { onUpdate(device.copy(zMeters = it)) }
        }

        if (isVisionCamera || advanced) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("CAMERA ORIENTATION & ANGLES (3D ROTATION)", color = AresCyan, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                GeometryField(
                    label = "Camera Pitch",
                    value = device.pitchDegrees ?: 0.0,
                    unit = "°",
                    explanation = "Tilt angle above (+) or below (-) the horizontal horizon. Crucial for MegaTag2 / AprilTag vertical angle solving."
                ) { onUpdate(device.copy(pitchDegrees = it)) }
                GeometryField(
                    label = "Camera Yaw",
                    value = device.yawDegrees ?: 0.0,
                    unit = "°",
                    explanation = "Horizontal angle: facing straight ahead (0°), facing left (+90°), facing right (-90°), facing rear (180°)."
                ) { onUpdate(device.copy(yawDegrees = it)) }
                GeometryField(
                    label = "Camera Roll",
                    value = device.rollDegrees ?: 0.0,
                    unit = "°",
                    explanation = "Rotation / tilt of the camera around its own optical lens axis (clockwise/counter-clockwise)."
                ) { onUpdate(device.copy(rollDegrees = it)) }
            }
        }
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Switch(device.inverted, { onUpdate(device.copy(inverted = it)) })
        Spacer(Modifier.width(8.dp))
        Text(if (device.inverted) "INVERTED direction" else "NORMAL direction", color = AresTextPrimary, fontWeight = FontWeight.Bold, fontSize = 11.sp)
        HelpButton("Mounting inversion changes the sign at the hardware boundary.")
    }

    if (isAuxiliaryOrSensors || advanced) {
        OutlinedButton(
            onClick = onRemove,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = AresError),
            border = BorderStroke(1.dp, AresError.copy(alpha = 0.4f)),
        ) {
            Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(14.dp), tint = AresError)
            Spacer(Modifier.width(6.dp))
            Text("Remove this device", color = AresError, fontWeight = FontWeight.Bold, fontSize = 11.sp)
        }
    }
}

@Composable
internal fun CtreImportCard(state: DrivebaseBuilderState, viewModel: DrivebaseBuilderViewModel) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(AresSurface, RoundedCornerShape(8.dp))
            .border(1.dp, AresBorder, RoundedCornerShape(8.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        FieldHeading("Optional CTRE TunerConstants import", "ARES reads a snapshot of vendor-generated constants for review. It never edits, formats, or overwrites TunerConstants.java.")
        OutlinedTextField(state.importPath, { viewModel.onIntent(DrivebaseBuilderIntent.SetImportPath(it)) }, Modifier.fillMaxWidth(), label = { Text("TunerConstants.java path") }, singleLine = true)
        Button(onClick = { viewModel.onIntent(DrivebaseBuilderIntent.ImportCtre) }, enabled = state.importPath.isNotBlank()) { Text("Import read-only snapshot") }
        Text("Import support fails closed when typed units, module positions, CAN bus, IDs, or inversion cannot be recognized. Review every imported field.", color = AresGold, fontSize = 10.sp)
        state.importWarnings.forEach { Text("• $it", color = AresGold, fontSize = 10.sp) }
    }
}

@Composable
fun SectionHeading(title: String, description: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, color = AresTextPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp)
        Text(description, color = AresTextSecondary, fontSize = 11.sp)
    }
}

@Composable
fun FieldHeading(title: String, description: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, color = AresTextPrimary, fontWeight = FontWeight.Bold, fontSize = 12.sp)
        Text(description, color = AresTextSecondary, fontSize = 10.sp)
    }
}

@Composable
fun GeometryField(label: String, value: Double, unit: String, explanation: String, onValueChange: (Double) -> Unit) {
    var raw by remember(value) { mutableStateOf(value.toString()) }
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = AresTextPrimary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(4.dp))
            HelpButton(explanation)
        }
        OutlinedTextField(
            value = raw,
            onValueChange = {
                raw = it
                it.toDoubleOrNull()?.let(onValueChange)
            },
            trailingIcon = { Text(unit, color = AresCyan, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(end = 8.dp)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
    }
}

@Composable
fun SafetySwitch(label: String, checked: Boolean, explanation: String, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, color = AresTextPrimary, fontWeight = FontWeight.Bold, fontSize = 11.sp)
            Text(explanation, color = AresTextSecondary, fontSize = 10.sp)
        }
        Switch(checked, onCheckedChange)
    }
}

@Composable
private fun SimpleChoice(label: String, selected: String, options: List<String>, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
            Text("$label · ${selected.lowercase().replace('_', ' ')}", fontSize = 10.sp)
        }
        DropdownMenu(expanded, { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.lowercase().replace('_', ' ').replaceFirstChar(Char::uppercase)) },
                    onClick = { expanded = false; onSelect(option) },
                )
            }
        }
    }
}

@Composable
private fun NullableNumberField(
    label: String,
    value: Double?,
    unit: String,
    explanation: String,
    onValueChange: (Double?) -> Unit,
) {
    var raw by remember(value) { mutableStateOf(value?.toString().orEmpty()) }
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = AresTextPrimary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(4.dp)); HelpButton(explanation)
        }
        OutlinedTextField(
            value = raw,
            onValueChange = { next ->
                raw = next
                if (next.isBlank()) onValueChange(null) else next.toDoubleOrNull()?.let(onValueChange)
            },
            isError = raw.isNotBlank() && raw.toDoubleOrNull() == null,
            supportingText = { Text(if (raw.isBlank()) "Not claimed as controller-enforced" else "Must be a positive finite value") },
            trailingIcon = { Text(unit, color = AresCyan, fontSize = 11.sp, modifier = Modifier.padding(end = 8.dp)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
    }
}

@Composable
fun HelpedTextField(label: String, value: String, explanation: String, onValueChange: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = AresTextPrimary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(4.dp))
            HelpButton(explanation)
        }
        OutlinedTextField(value = value, onValueChange = onValueChange, modifier = Modifier.fillMaxWidth(), singleLine = true)
    }
}

@Composable
fun HelpButton(text: String) {
    var showDialog by remember { mutableStateOf(false) }
    IconButton(onClick = { showDialog = true }, modifier = Modifier.size(20.dp)) {
        Icon(Icons.AutoMirrored.Filled.HelpOutline, contentDescription = "Help", tint = AresTextSecondary, modifier = Modifier.size(13.dp))
    }
    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            confirmButton = { TextButton(onClick = { showDialog = false }) { Text("OK") } },
            text = { Text(text, color = AresTextPrimary, fontSize = 12.sp) },
        )
    }
}

