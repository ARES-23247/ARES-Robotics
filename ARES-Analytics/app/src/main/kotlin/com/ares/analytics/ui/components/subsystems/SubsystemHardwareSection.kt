package com.ares.analytics.ui.components.subsystems

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ares.analytics.ui.theme.*
import com.ares.analytics.ui.screens.ConceptHelp
import com.ares.analytics.viewmodel.SubsystemGeneratorState
import com.ares.analytics.viewmodel.SubsystemGeneratorViewModel
import com.ares.analytics.viewmodel.SubsystemProblemSeverity
import com.areslib.subsystem.*

@Composable
fun SubsystemHardwareSection(
    state: SubsystemGeneratorState,
    viewModel: SubsystemGeneratorViewModel,
    modifier: Modifier = Modifier,
) {
    val document = state.draft?.document ?: return

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        val hardwareProblems = state.problems.filter { it.path.startsWith("hardware") }
        if (hardwareProblems.isNotEmpty()) {
            EditorCard("Hardware Configuration Notices", Icons.Default.Warning) {
                hardwareProblems.forEach { problem ->
                    OutlinedButton(
                        onClick = { viewModel.navigateToProblem(problem.path) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            "${if (problem.severity == SubsystemProblemSeverity.ERROR) "Error" else "Warning"}: ${problem.message}",
                            color = if (problem.severity == SubsystemProblemSeverity.ERROR) AresError else AresGold,
                            fontSize = 11.sp,
                        )
                    }
                }
            }
        }

        EditorCard("Physical Hardware Devices (${document.hardware.size})", Icons.Default.Settings) {
            Text(
                "Hardware names must match the robot controller configuration. Every sensor read is cached once per loop. Select any device to edit in the slide-out inspector.",
                color = AresTextSecondary,
                fontSize = 11.sp,
            )
            document.hardware.forEach { device ->
                SelectableRow(
                    title = device.displayName,
                    subtitle = "${device.kind.uiLabel()} · ${device.connectionLabel(document.platform)}",
                    selected = state.selectedHardwareUid == device.uid,
                    onClick = { viewModel.selectHardware(device.uid) },
                )
            }
            AddHardwareButton(viewModel, document.platform, "+ Add Hardware Device")
        }

        if (document.hardware.isEmpty()) {
            ConceptCard("Start with physical hardware", "Click + Add Hardware Device to declare motors, continuous or positional servos, analog sensors, digital limits, or encoders.")
        }
    }
}

@Composable
fun HardwareInspectorBody(
    state: SubsystemGeneratorState,
    device: SubsystemHardwareDocument,
    viewModel: SubsystemGeneratorViewModel,
) {
    val document = state.draft?.document ?: return
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Configure device identification, electrical connection, and cached telemetry.", color = AresTextSecondary, fontSize = 11.sp)
        TextInput("Hardware name", device.displayName) { value ->
            viewModel.updateHardware(device.hardwareId) { it.copy(displayName = value) }
        }
        TextInput("What this device does", device.description) { value ->
            viewModel.updateHardware(device.hardwareId) { it.copy(description = value) }
        }
        StableIdLabel("Code ID", device.hardwareId, "Used by controller rules and generated Kotlin.")
        TextInput("Rename code ID (advanced)", device.hardwareId) { value ->
            viewModel.renameHardwareId(device.hardwareId, value)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                EnumSelector("Device Type", device.kind, supportedHardwareKinds(document.platform)) { kind ->
                    viewModel.changeHardwareKind(device.hardwareId, kind)
                }
            }
            ConceptHelp(
                "hardware device types",
                "Choose the physical device ARES will own. The choice determines generated wiring, cached signals, safe outputs, and simulator behavior.",
                "hardware-devices",
                compact = true,
            )
        }

        if (device.kind.isActuator()) {
            val eligibleLeaders = document.hardware.filter {
                it.hardwareId != device.hardwareId && it.kind == device.kind && it.following == null
            }
            val independentLabel = "Independent (has its own controller)"
            val leaderLabels = eligibleLeaders.associateBy { "Follow ${it.displayName} (${it.hardwareId})" }
            val selectedLeader = device.following?.leaderId?.let { leaderId ->
                leaderLabels.entries.firstOrNull { it.value.hardwareId == leaderId }?.key
            } ?: independentLabel
            DropdownSelector(
                label = "Command source",
                selected = selectedLeader,
                options = listOf(independentLabel) + leaderLabels.keys,
            ) { label ->
                viewModel.setHardwareFollower(device.hardwareId, leaderLabels[label]?.hardwareId)
            }
            device.following?.let { following ->
                val transforms = if (device.kind == SubsystemHardwareKind.POSITIONAL_SERVO) {
                    listOf(SubsystemFollowerTransform.SAME_DIRECTION, SubsystemFollowerTransform.MIRRORED_POSITION)
                } else {
                    listOf(SubsystemFollowerTransform.SAME_DIRECTION, SubsystemFollowerTransform.INVERTED)
                }
                EnumSelector("Follower direction", following.transform, transforms) { transform ->
                    viewModel.setHardwareFollower(device.hardwareId, following.leaderId, transform)
                }
            }
        }

        when (document.platform) {
            SubsystemPlatform.FTC -> {
                TextInput("Hardware-map name", device.connection.hardwareMapName.orEmpty()) { value ->
                    viewModel.updateHardware(device.hardwareId) {
                        it.copy(connection = SubsystemHardwareConnection(hardwareMapName = value))
                    }
                }
                if (device.kind == SubsystemHardwareKind.IMU) {
                    EnumSelector(
                        "Control Hub logo points",
                        device.imuLogoFacingDirection ?: SubsystemHubFacingDirection.UP,
                        SubsystemHubFacingDirection.entries,
                    ) { value ->
                        viewModel.updateHardware(device.hardwareId) { it.copy(imuLogoFacingDirection = value) }
                    }
                    val logo = device.imuLogoFacingDirection ?: SubsystemHubFacingDirection.UP
                    EnumSelector(
                        "Control Hub USB port points",
                        device.imuUsbFacingDirection ?: SubsystemHubFacingDirection.FORWARD,
                        SubsystemHubFacingDirection.entries.filter { it.axisGroupForUi() != logo.axisGroupForUi() },
                    ) { value ->
                        viewModel.updateHardware(device.hardwareId) { it.copy(imuUsbFacingDirection = value) }
                    }
                    FieldGuidance("Describe the Control Hub's physical mounting. ARES uses this to make yaw CCW-positive and initialize the IMU correctly.")
                }
            }
            SubsystemPlatform.FRC -> when (device.kind) {
                SubsystemHardwareKind.MOTOR -> {
                    IntInput("CAN ID", device.connection.canId ?: 0) { value ->
                        viewModel.updateHardware(device.hardwareId) { it.copy(connection = it.connection.copy(canId = value, channel = null)) }
                    }
                    TextInput("CAN bus", device.connection.canBus) { value ->
                        viewModel.updateHardware(device.hardwareId) { it.copy(connection = it.connection.copy(canBus = value)) }
                    }
                }
                SubsystemHardwareKind.SOLENOID -> {
                    EnumSelector(
                        "Pneumatics module",
                        device.connection.pneumaticsModuleType ?: SubsystemPneumaticsModuleType.REV_PH,
                        SubsystemPneumaticsModuleType.entries,
                    ) { value ->
                        viewModel.updateHardware(device.hardwareId) {
                            it.copy(connection = it.connection.copy(pneumaticsModuleType = value))
                        }
                    }
                    IntInput("Pneumatics module CAN ID", device.connection.canId ?: 1) { value ->
                        viewModel.updateHardware(device.hardwareId) { it.copy(connection = it.connection.copy(canId = value)) }
                    }
                    IntInput("Solenoid channel", device.connection.channel ?: 0) { value ->
                        viewModel.updateHardware(device.hardwareId) { it.copy(connection = it.connection.copy(channel = value)) }
                    }
                }
                SubsystemHardwareKind.QUADRATURE_ENCODER -> {
                    IntInput("DIO channel A", device.connection.channel ?: 0) { value ->
                        viewModel.updateHardware(device.hardwareId) { it.copy(connection = it.connection.copy(channel = value, canId = null)) }
                    }
                    IntInput("DIO channel B", device.connection.secondaryChannel ?: 1) { value ->
                        viewModel.updateHardware(device.hardwareId) { it.copy(connection = it.connection.copy(secondaryChannel = value, canId = null)) }
                    }
                }
                SubsystemHardwareKind.IMU -> FieldGuidance("The generated FRC adapter uses the RoboRIO onboard SPI gyroscope. No channel is required.")
                else -> IntInput("Channel", device.connection.channel ?: 0) { value ->
                    viewModel.updateHardware(device.hardwareId) { it.copy(connection = it.connection.copy(channel = value, canId = null)) }
                }
            }
            SubsystemPlatform.XRP -> TextInput("XRP Device Name / Pin", device.connection.hardwareMapName.orEmpty()) { value ->
                viewModel.updateHardware(device.hardwareId) { it.copy(connection = SubsystemHardwareConnection(hardwareMapName = value)) }
            }
        }
        Row(verticalAlignment = Alignment.Top) {
            FieldGuidance(
                if (document.platform == SubsystemPlatform.FTC) "Use this exact name in the FTC Robot Controller configuration. Names are case-sensitive."
                else "Use the device ID, bus, or channel configured in the matching vendor hardware tools."
            )
            Spacer(Modifier.weight(1f))
            ConceptHelp(
                "hardware connections",
                "A hardware connection is the exact controller name, CAN identity, I/O channel, or pneumatics address used by the generated physical adapter.",
                "hardware-connections",
                compact = true,
            )
        }

        if (device.kind == SubsystemHardwareKind.INDICATOR_LIGHT || device.kind == SubsystemHardwareKind.PRISM_DRIVER) {
            val placement = device.visualPlacement ?: defaultVisualPlacement(device.kind)
            val allowedAnchors = if (device.kind == SubsystemHardwareKind.PRISM_DRIVER) {
                listOf(SubsystemVisualAnchor.UNDERBODY)
            } else {
                listOf(
                    SubsystemVisualAnchor.LEFT_SIDE,
                    SubsystemVisualAnchor.RIGHT_SIDE,
                    SubsystemVisualAnchor.FRONT,
                    SubsystemVisualAnchor.REAR,
                    SubsystemVisualAnchor.CENTER,
                )
            }
            EnumSelector("Where this light appears on the robot", placement.anchor, allowedAnchors) { anchor ->
                viewModel.updateHardware(device.hardwareId) {
                    it.copy(visualPlacement = placementForAnchor(anchor))
                }
            }
            FieldGuidance(
                if (device.kind == SubsystemHardwareKind.PRISM_DRIVER) {
                    "Prism programs appear as an underbody glow in live simulation and replay."
                } else {
                    "This location travels with the robot footprint, so students can distinguish independently controlled lights in simulation and replay."
                }
            )
            if (device.kind == SubsystemHardwareKind.INDICATOR_LIGHT) {
                var showPrecisePlacement by remember(device.uid) { mutableStateOf(false) }
                TextButton(onClick = { showPrecisePlacement = !showPrecisePlacement }) {
                    Text(if (showPrecisePlacement) "Hide precise placement" else "Fine-tune placement (advanced)")
                }
                if (showPrecisePlacement) {
                    DoubleInput("Forward position (-0.5 rear to +0.5 front)", placement.forwardFraction) { value ->
                        viewModel.updateHardware(device.hardwareId) {
                            it.copy(visualPlacement = placement.copy(forwardFraction = value.coerceIn(-0.5, 0.5)))
                        }
                    }
                    DoubleInput("Side position (-0.5 right to +0.5 left)", placement.leftFraction) { value ->
                        viewModel.updateHardware(device.hardwareId) {
                            it.copy(visualPlacement = placement.copy(leftFraction = value.coerceIn(-0.5, 0.5)))
                        }
                    }
                }
            }
        }

        val measurementSources = device.kind.compatibleMeasurementSources()
        if (measurementSources.isNotEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("CACHED INPUT SIGNALS", color = AresTextTertiary, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                ConceptHelp(
                    "cached input signals",
                    "ARES reads each physical signal once per loop, converts it to canonical units, validates it, and then publishes the immutable state snapshot used by control and simulation.",
                    "cached-inputs",
                    compact = true,
                )
            }
            device.measurements.forEachIndexed { index, measurement ->
                CachedMeasurementEditor(document, device, index, measurement, measurementSources, viewModel)
            }
            if (device.kind == SubsystemHardwareKind.MOTOR && device.measurements.any {
                    it.source == SubsystemMeasurementSource.MOTOR_POSITION_NATIVE ||
                        it.source == SubsystemMeasurementSource.MOTOR_VELOCITY_NATIVE_PER_SECOND
                }
            ) {
                MotorMechanismConversionAssistant(document, device, viewModel)
            }
            val available = measurementSources.firstOrNull { source ->
                document.stateFields.any { field ->
                    field.fieldId !in device.measurements.map { it.fieldId } &&
                        (field.role == SubsystemFieldRole.MEASUREMENT || field.role == SubsystemFieldRole.STATUS) &&
                        field.type == source.valueType()
                }
            }
            OutlinedButton(
                onClick = {
                    val source = available ?: return@OutlinedButton
                    val field = document.stateFields.first {
                        it.fieldId !in device.measurements.map { measurement -> measurement.fieldId } &&
                            (it.role == SubsystemFieldRole.MEASUREMENT || it.role == SubsystemFieldRole.STATUS) &&
                            it.type == source.valueType()
                    }
                    viewModel.updateHardware(device.hardwareId) {
                        it.copy(measurements = it.measurements + SubsystemMeasurementDocument(field.fieldId, source))
                    }
                },
                enabled = available != null,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("+ Cached Measurement") }
        }

        if (device.kind == SubsystemHardwareKind.QUADRATURE_ENCODER) {
            DoubleInput("Encoder counts per revolution", device.encoderCountsPerRevolution ?: 1.0) { value ->
                viewModel.updateHardware(device.hardwareId) { it.copy(encoderCountsPerRevolution = value) }
            }
            FieldGuidance("Enter the encoder's effective counts after any decoding and gearing. Generated state values remain radians and radians/second.")
        }
        if (device.kind == SubsystemHardwareKind.DISTANCE_SENSOR && document.platform == SubsystemPlatform.FRC) {
            DoubleInput("Distance scale (meters per volt)", device.distanceMetersPerVolt ?: 1.0) { value ->
                viewModel.updateHardware(device.hardwareId) { it.copy(distanceMetersPerVolt = value) }
            }
            FieldGuidance("The generated FRC adapter reads an analog voltage and converts it to meters at this boundary.")
        }

        if (device.kind == SubsystemHardwareKind.MOTOR && document.platform == SubsystemPlatform.FRC) {
            NullableDoubleInput("Current limit (A)", device.currentLimitAmps) { value ->
                viewModel.updateHardware(device.hardwareId) { it.copy(currentLimitAmps = value) }
            }
        }
        ToggleRow("Required hardware", device.required) { value ->
            viewModel.updateHardware(device.hardwareId) { it.copy(required = value) }
        }
        if (device.kind.isActuator()) {
            DoubleInput("Safe neutral output", device.safeOutput ?: 0.0) { value ->
                viewModel.updateHardware(device.hardwareId) { it.copy(safeOutput = value) }
            }
            ToggleRow("Reverse hardware direction", device.inverted) { value ->
                viewModel.updateHardware(device.hardwareId) { it.copy(inverted = value) }
            }
        }
    }
}

private fun defaultVisualPlacement(kind: SubsystemHardwareKind): SubsystemVisualPlacementDocument =
    if (kind == SubsystemHardwareKind.PRISM_DRIVER) {
        placementForAnchor(SubsystemVisualAnchor.UNDERBODY)
    } else {
        placementForAnchor(SubsystemVisualAnchor.LEFT_SIDE)
    }

private fun placementForAnchor(anchor: SubsystemVisualAnchor): SubsystemVisualPlacementDocument = when (anchor) {
    SubsystemVisualAnchor.LEFT_SIDE -> SubsystemVisualPlacementDocument(anchor, leftFraction = 0.5)
    SubsystemVisualAnchor.RIGHT_SIDE -> SubsystemVisualPlacementDocument(anchor, leftFraction = -0.5)
    SubsystemVisualAnchor.FRONT -> SubsystemVisualPlacementDocument(anchor, forwardFraction = 0.5)
    SubsystemVisualAnchor.REAR -> SubsystemVisualPlacementDocument(anchor, forwardFraction = -0.5)
    SubsystemVisualAnchor.CENTER,
    SubsystemVisualAnchor.UNDERBODY,
    SubsystemVisualAnchor.UNSPECIFIED -> SubsystemVisualPlacementDocument(anchor)
}

@Composable
private fun MotorMechanismConversionAssistant(
    document: SubsystemDocument,
    device: SubsystemHardwareDocument,
    viewModel: SubsystemGeneratorViewModel,
) {
    val positionMeasurement = device.measurements.firstOrNull {
        it.source == SubsystemMeasurementSource.MOTOR_POSITION_NATIVE
    }
    val velocityMeasurement = device.measurements.firstOrNull {
        it.source == SubsystemMeasurementSource.MOTOR_VELOCITY_NATIVE_PER_SECOND
    }
    val stateField = document.stateFields.firstOrNull {
        it.fieldId == positionMeasurement?.fieldId || it.fieldId == velocityMeasurement?.fieldId
    }
    val suggestedTravel = when (stateField?.unit?.trim()?.lowercase()) {
        "rad", "rad/s", "radian", "radians", "radian/second", "radians/second" -> Math.PI * 2.0
        "rot", "rot/s", "turn", "turns", "rotation", "rotations" -> 1.0
        else -> 0.0
    }
    var nativeUnitsPerMotorRevolution by remember(device.uid) {
        mutableStateOf(if (document.platform == SubsystemPlatform.FRC) 1.0 else 0.0)
    }
    var motorRevolutionsPerMechanismRevolution by remember(device.uid) { mutableStateOf(1.0) }
    var stateUnitsPerMechanismRevolution by remember(device.uid, stateField?.unit) { mutableStateOf(suggestedTravel) }
    val valid = nativeUnitsPerMotorRevolution.isFinite() && nativeUnitsPerMotorRevolution > 0.0 &&
        motorRevolutionsPerMechanismRevolution.isFinite() && motorRevolutionsPerMechanismRevolution > 0.0 &&
        stateUnitsPerMechanismRevolution.isFinite() && stateUnitsPerMechanismRevolution > 0.0
    val scale = if (valid) SubsystemUnits.motorMeasurementScale(
        nativeUnitsPerMotorRevolution,
        motorRevolutionsPerMechanismRevolution,
        stateUnitsPerMechanismRevolution,
    ) else Double.NaN

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = AresSurface,
        shape = RoundedCornerShape(6.dp),
        border = BorderStroke(1.dp, AresBorder),
    ) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text("MECHANISM CONVERSION", color = AresTextTertiary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            Text(
                "Convert the motor sensor into the mechanism unit before PID or feedforward uses it. This updates both cached position and velocity scales.",
                color = AresTextSecondary,
                fontSize = 10.sp,
            )
            DoubleInput(
                if (document.platform == SubsystemPlatform.FTC) {
                    "Encoder counts per motor revolution (datasheet)"
                } else {
                    "Native sensor turns per motor revolution"
                },
                nativeUnitsPerMotorRevolution,
            ) { nativeUnitsPerMotorRevolution = it }
            DoubleInput("Motor revolutions per mechanism revolution", motorRevolutionsPerMechanismRevolution) {
                motorRevolutionsPerMechanismRevolution = it
            }
            DoubleInput(
                "${stateField?.unit ?: "State units"} per mechanism revolution",
                stateUnitsPerMechanismRevolution,
            ) { stateUnitsPerMechanismRevolution = it }
            FieldGuidance(
                when (stateField?.unit?.trim()?.lowercase()) {
                    "rad", "rad/s" -> "For a rotating output, one mechanism revolution is 2π radians. For an elevator, enter spool travel per revolution in meters instead."
                    "m", "m/s" -> "Enter the measured belt, lead-screw, or spool travel produced by one mechanism revolution in meters."
                    else -> "Use the unit declared on the cached state field. Check the motor/encoder datasheet and the actual gear ratio."
                }
            )
            Text(
                if (valid) "Result: state = native sensor × ${"%.9g".format(scale)} + offset" else "Enter positive finite conversion values.",
                color = if (valid) AresTextPrimary else AresError,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
            )
            Button(
                onClick = {
                    viewModel.updateHardware(device.hardwareId) { hardware ->
                        hardware.copy(measurements = hardware.measurements.map { measurement ->
                            if (measurement.source == SubsystemMeasurementSource.MOTOR_POSITION_NATIVE ||
                                measurement.source == SubsystemMeasurementSource.MOTOR_VELOCITY_NATIVE_PER_SECOND
                            ) measurement.copy(scale = scale) else measurement
                        })
                    }
                },
                enabled = valid,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Apply conversion to position and velocity") }
        }
    }
}

private fun SubsystemHubFacingDirection.axisGroupForUi(): Int = when (this) {
    SubsystemHubFacingDirection.UP, SubsystemHubFacingDirection.DOWN -> 0
    SubsystemHubFacingDirection.FORWARD, SubsystemHubFacingDirection.BACKWARD -> 1
    SubsystemHubFacingDirection.LEFT, SubsystemHubFacingDirection.RIGHT -> 2
}

@Composable
private fun CachedMeasurementEditor(
    document: SubsystemDocument,
    device: SubsystemHardwareDocument,
    index: Int,
    measurement: SubsystemMeasurementDocument,
    sources: List<SubsystemMeasurementSource>,
    viewModel: SubsystemGeneratorViewModel,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = AresSurface,
        shape = RoundedCornerShape(6.dp),
        border = BorderStroke(1.dp, AresBorder),
    ) {
        Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            EnumSelector("Hardware signal", measurement.source, sources) { source ->
                val compatible = document.stateFields.firstOrNull {
                    (it.role == SubsystemFieldRole.MEASUREMENT || it.role == SubsystemFieldRole.STATUS) &&
                        it.type == source.valueType() && it.fieldId !in device.measurements.filterIndexed { i, _ -> i != index }.map { it.fieldId }
                }
                viewModel.updateHardware(device.hardwareId) { hardware ->
                    hardware.copy(measurements = hardware.measurements.mapIndexed { i, current ->
                        if (i == index) current.copy(
                            source = source,
                            fieldId = compatible?.fieldId ?: current.fieldId,
                            scale = if (source.valueType() == SubsystemValueType.DOUBLE) current.scale else 1.0,
                            offset = if (source.valueType() == SubsystemValueType.DOUBLE) current.offset else 0.0,
                        ) else current
                    })
                }
            }
            val fieldOptions = document.stateFields.filter {
                (it.role == SubsystemFieldRole.MEASUREMENT || it.role == SubsystemFieldRole.STATUS) &&
                    it.type == measurement.source.valueType() &&
                    it.fieldId !in device.measurements.filterIndexed { i, _ -> i != index }.map { existing -> existing.fieldId }
            }.map { it.fieldId }

            if (fieldOptions.isNotEmpty()) {
                DropdownSelector(
                    label = "Target state field",
                    selected = measurement.fieldId,
                    options = fieldOptions,
                ) { newFieldId ->
                    viewModel.updateHardware(device.hardwareId) { hardware ->
                        hardware.copy(measurements = hardware.measurements.mapIndexed { i, current ->
                            if (i == index) current.copy(fieldId = newFieldId) else current
                        })
                    }
                }
            }
            if (measurement.source.valueType() == SubsystemValueType.DOUBLE) {
                DoubleInput("Scale (state units per hardware unit)", measurement.scale) { value ->
                    viewModel.updateHardware(device.hardwareId) { hardware ->
                        hardware.copy(measurements = hardware.measurements.mapIndexed { i, current ->
                            if (i == index) current.copy(scale = value) else current
                        })
                    }
                }
                DoubleInput("Offset (state units)", measurement.offset) { value ->
                    viewModel.updateHardware(device.hardwareId) { hardware ->
                        hardware.copy(measurements = hardware.measurements.mapIndexed { i, current ->
                            if (i == index) current.copy(offset = value) else current
                        })
                    }
                }
                NullableDoubleInput("Valid minimum (optional)", measurement.validMinimum) { value ->
                    viewModel.updateHardware(device.hardwareId) { hardware ->
                        hardware.copy(measurements = hardware.measurements.mapIndexed { i, current ->
                            if (i == index) current.copy(validMinimum = value) else current
                        })
                    }
                }
                NullableDoubleInput("Valid maximum (optional)", measurement.validMaximum) { value ->
                    viewModel.updateHardware(device.hardwareId) { hardware ->
                        hardware.copy(measurements = hardware.measurements.mapIndexed { i, current ->
                            if (i == index) current.copy(validMaximum = value) else current
                        })
                    }
                }
            }
            NullableLongInput("Freshness timeout (ms; blank inherits subsystem)", measurement.maxAgeMs) { value ->
                viewModel.updateHardware(device.hardwareId) { hardware ->
                    hardware.copy(measurements = hardware.measurements.mapIndexed { i, current ->
                        if (i == index) current.copy(maxAgeMs = value) else current
                    })
                }
            }
            FieldGuidance("ARES reads this signal once per robot loop. Scale and offset convert it into the state field's documented unit before validity checks.")
            IconButton(
                onClick = {
                    viewModel.updateHardware(device.hardwareId) { hardware ->
                        hardware.copy(measurements = hardware.measurements.filterIndexed { i, _ -> i != index })
                    }
                },
                modifier = Modifier.size(22.dp).align(Alignment.End),
            ) {
                Icon(Icons.Default.Delete, null, tint = AresError, modifier = Modifier.size(14.dp))
            }
        }
    }
}
