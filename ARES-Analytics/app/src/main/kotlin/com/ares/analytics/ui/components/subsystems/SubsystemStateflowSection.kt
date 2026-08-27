package com.ares.analytics.ui.components.subsystems

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Memory
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
import com.ares.analytics.ui.screens.FeedforwardConceptLab
import com.ares.analytics.ui.screens.HomingConceptLab
import com.ares.analytics.ui.screens.SubsystemCommissioningLab
import com.ares.analytics.viewmodel.SubsystemGeneratorState
import com.ares.analytics.viewmodel.SubsystemGeneratorViewModel
import com.areslib.subsystem.*

@Composable
fun SubsystemStateflowSection(
    state: SubsystemGeneratorState,
    viewModel: SubsystemGeneratorViewModel,
    modifier: Modifier = Modifier,
) {
    val document = state.draft?.document ?: return

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // State Fields Card
        EditorCard("Immutable State Values (${document.stateFields.size})", Icons.Default.Memory) {
            Text(
                "Status values describe what sensors observed. Target values describe what driver or autonomous code wants. Select any value to edit in the slide-out inspector.",
                color = AresTextSecondary,
                fontSize = 11.sp,
            )
            document.stateFields.forEach { field ->
                SelectableRow(
                    title = field.displayName,
                    subtitle = "${field.role.name.lowercase()} · ${field.type.name.lowercase()}${field.unit?.let { " ($it)" }.orEmpty()}",
                    selected = state.selectedFieldUid == field.uid,
                    onClick = { viewModel.selectField(field.uid) },
                )
            }
            OutlinedButton(onClick = viewModel::addStateField, modifier = Modifier.fillMaxWidth()) {
                Text("+ Add State Value", fontSize = 11.sp)
            }
        }

        // Controller Rules Card
        EditorCard("Closed-Loop Controllers & Output Rules (${document.controlLoops.size})", Icons.Default.Build) {
            Text(
                "A controller converts immutable state into bounded motor/servo outputs. Select any controller rule to edit gains, feedforward, and sandboxes.",
                color = AresTextSecondary,
                fontSize = 11.sp,
            )
            document.controlLoops.forEach { loop ->
                SelectableRow(
                    title = loop.displayName,
                    subtitle = "${loop.strategy.name.replace('_', ' ').lowercase()} → ${loop.actuatorId}",
                    selected = state.selectedLoopUid == loop.uid,
                    onClick = { viewModel.selectLoop(loop.uid) },
                )
            }
            val controlledActuators = document.controlLoops.mapTo(mutableSetOf()) { it.actuatorId }
            val hasUncontrolledActuator = document.hardware.any {
                it.kind.isActuator() && it.following == null && it.hardwareId !in controlledActuators
            }
            val hasNumericTarget = document.stateFields.any {
                it.role == SubsystemFieldRole.TARGET &&
                    (it.type == SubsystemValueType.DOUBLE || it.type == SubsystemValueType.INT)
            }
            val canAddControl = hasUncontrolledActuator && hasNumericTarget
            OutlinedButton(
                onClick = viewModel::addControlLoop,
                enabled = canAddControl,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("+ Add Controller Rule", fontSize = 11.sp)
            }
            if (!canAddControl) {
                Text(
                    when {
                        !hasNumericTarget -> "Add a numeric target state value first."
                        !hasUncontrolledActuator -> "Every independent actuator already has a controller. Select a rule above to edit it."
                        else -> "Add an independent actuator first."
                    },
                    color = AresTextSecondary,
                    fontSize = 10.sp,
                )
            }
        }

        // 2-DOF Linkage Geometry Editor Canvas
        com.ares.analytics.ui.components.linkage.LinkageEditorCanvas(
            linkage = document.linkage,
            actuatorIds = document.hardware
                .filter { it.kind == SubsystemHardwareKind.MOTOR && it.following == null }
                .map { it.hardwareId },
            angleMeasurementFieldIds = document.stateFields
                .filter { it.role == SubsystemFieldRole.MEASUREMENT && it.type == SubsystemValueType.DOUBLE }
                .map { it.fieldId },
            onLinkageChanged = { newLinkage -> viewModel.edit { it.copy(linkage = newLinkage) } },
        )

        // Safety Contract & Fault Recovery
        SafetyInspector(state, viewModel)
        FaultRecoveryCard(document, viewModel)
        InterlockMatrixCard(document, state, viewModel)
    }
}

@Composable
fun StateFieldInspectorBody(field: SubsystemStateFieldDocument, viewModel: SubsystemGeneratorViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Configure state field role, value type, and default initial value.", color = AresTextSecondary, fontSize = 11.sp)
        TextInput("State value name", field.displayName) { value ->
            viewModel.updateStateField(field.fieldId) { it.copy(displayName = value) }
        }
        TextInput("Description", field.description) { value ->
            viewModel.updateStateField(field.fieldId) { it.copy(description = value) }
        }
        StableIdLabel("Code ID", field.fieldId, "Used by cached inputs, controller rules, and actions.")
        TextInput("Rename code ID (advanced)", field.fieldId) { value ->
            viewModel.renameStateFieldId(field.fieldId, value)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                EnumSelector("Role", field.role, SubsystemFieldRole.entries) { role ->
                    viewModel.updateStateField(field.fieldId) { it.copy(role = role) }
                }
            }
            ConceptHelp(
                "state roles",
                "Targets describe what the robot should do; measurements describe cached hardware observations; status reports derived conditions; configuration stores reviewed constants.",
                "state-roles",
                compact = true,
            )
        }
        EnumSelector("Value Type", field.type, SubsystemValueType.entries) { type ->
            viewModel.changeStateFieldType(field.fieldId, type)
        }
        when (field.type) {
            SubsystemValueType.DOUBLE -> DoubleInput("Default number", field.defaultNumber ?: 0.0) { value ->
                viewModel.updateStateField(field.fieldId) { it.copy(defaultNumber = value) }
            }
            SubsystemValueType.BOOLEAN -> ToggleRow("Default boolean", field.defaultBoolean ?: false) { value ->
                viewModel.updateStateField(field.fieldId) { it.copy(defaultBoolean = value) }
            }
            SubsystemValueType.INT -> IntInput("Default integer", field.defaultInt ?: 0) { value ->
                viewModel.updateStateField(field.fieldId) { it.copy(defaultInt = value) }
            }
            SubsystemValueType.STRING -> TextInput("Default text", field.defaultText.orEmpty()) { value ->
                viewModel.updateStateField(field.fieldId) { it.copy(defaultText = value) }
            }
        }
        if (field.type == SubsystemValueType.DOUBLE || field.type == SubsystemValueType.INT) {
            TextInput("Physical unit (optional)", field.unit.orEmpty()) { value ->
                viewModel.updateStateField(field.fieldId) { it.copy(unit = value.ifBlank { null }) }
            }
            NullableDoubleInput("Minimum bound", field.minimum) { value ->
                viewModel.updateStateField(field.fieldId) { it.copy(minimum = value) }
            }
            NullableDoubleInput("Maximum bound", field.maximum) { value ->
                viewModel.updateStateField(field.fieldId) { it.copy(maximum = value) }
            }
        }
    }
}

@Composable
fun ControlInspectorBody(
    state: SubsystemGeneratorState,
    loop: SubsystemControlLoopDocument,
    viewModel: SubsystemGeneratorViewModel,
) {
    val document = state.draft?.document ?: return
    val actuator = document.hardware.firstOrNull { it.hardwareId == loop.actuatorId }
    val claimedActuators = document.controlLoops.filterNot { it.loopId == loop.loopId }.mapTo(mutableSetOf()) { it.actuatorId }
    val actuators = document.hardware.filter {
        it.kind == actuator?.kind && it.kind.isActuator() && it.following == null && it.hardwareId !in claimedActuators
    }
    val numericFields = document.stateFields.filter { it.type == SubsystemValueType.DOUBLE || it.type == SubsystemValueType.INT }
    val targetFields = numericFields.filter { it.role == SubsystemFieldRole.TARGET || it.role == SubsystemFieldRole.CONFIGURATION }
    val selectedTarget = targetFields.firstOrNull { it.fieldId == loop.targetFieldId }
    val selectedMeasurement = numericFields.firstOrNull { it.fieldId == loop.measurementFieldId }
    val allowedStrategies = when (actuator?.kind) {
        SubsystemHardwareKind.POSITIONAL_SERVO -> listOf(SubsystemControlStrategy.SERVO_POSITION)
        SubsystemHardwareKind.MOTOR -> listOf(
            SubsystemControlStrategy.DIRECT,
            SubsystemControlStrategy.POSITION_PID,
            SubsystemControlStrategy.PROFILED_POSITION_PID,
            SubsystemControlStrategy.VELOCITY_PID,
            SubsystemControlStrategy.BANG_BANG,
        )
        else -> listOf(SubsystemControlStrategy.DIRECT, SubsystemControlStrategy.BANG_BANG)
    }
    val supportsPid = loop.strategy in setOf(
        SubsystemControlStrategy.POSITION_PID,
        SubsystemControlStrategy.PROFILED_POSITION_PID,
        SubsystemControlStrategy.VELOCITY_PID,
    )
    val supportsContinuousInput = loop.strategy in setOf(
        SubsystemControlStrategy.POSITION_PID,
        SubsystemControlStrategy.PROFILED_POSITION_PID,
    ) && subsystemUnitIsCanonicalAngle(selectedTarget?.unit) &&
        subsystemUnitIsCanonicalAngle(selectedMeasurement?.unit)
    val outputUnit = when (actuator?.kind) {
        SubsystemHardwareKind.MOTOR -> "V"
        SubsystemHardwareKind.PRISM_DRIVER -> "µs"
        else -> "normalized"
    }
    var showControlLab by remember(loop.uid) { mutableStateOf(false) }
    var showFeedforwardLab by remember(loop.uid) { mutableStateOf(false) }
    var editCustomAngleRange by remember(loop.uid) { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Configure control strategy, PID feedback gains, and feedforward dynamics.", color = AresTextSecondary, fontSize = 11.sp)
        TextInput("Controller rule name", loop.displayName) { value ->
            viewModel.updateControlLoop(loop.loopId) { it.copy(displayName = value) }
        }
        TextInput("What this controller does", loop.description) { value ->
            viewModel.updateControlLoop(loop.loopId) { it.copy(description = value) }
        }
        StableIdLabel("Code ID", loop.loopId, "Used by generated controller code.")
        TextInput("Rename code ID (advanced)", loop.loopId) { value ->
            viewModel.renameControlLoopId(loop.loopId, value)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                val strategyLabels = allowedStrategies.associateBy { it.controlStrategyLabel() }
                DropdownSelector("Strategy", loop.strategy.controlStrategyLabel(), strategyLabels.keys.toList()) { label ->
                    viewModel.changeControlLoopStrategy(loop.loopId, requireNotNull(strategyLabels[label]))
                }
            }
            ConceptHelp(
                "Control strategy",
                "Direct output is simplest. PID corrects measured error. Profiled PID also limits requested velocity and acceleration before feedback.",
                "control-strategies",
                compact = true,
            )
        }
        FieldGuidance(controlStrategyGuidance(loop.strategy))
        if (actuators.isNotEmpty()) {
            val actuatorOptions = actuators.associateBy { device ->
                "${device.displayName} · ${device.kind.name.replace('_', ' ').lowercase()} (${device.hardwareId})"
            }
            val selectedActuatorLabel = actuatorOptions.entries.firstOrNull { it.value.hardwareId == loop.actuatorId }?.key
                ?: loop.actuatorId
            DropdownSelector("Actuator", selectedActuatorLabel, actuatorOptions.keys.toList()) { label ->
                viewModel.changeControlLoopActuator(loop.loopId, requireNotNull(actuatorOptions[label]).hardwareId)
            }
        }
        if (targetFields.isNotEmpty()) {
            val targetOptions = targetFields.associateBy(::stateFieldOptionLabel)
            val selectedTargetLabel = targetOptions.entries.firstOrNull { it.value.fieldId == loop.targetFieldId }?.key
                ?: loop.targetFieldId
            DropdownSelector("Target state", selectedTargetLabel, targetOptions.keys.toList()) { label ->
                viewModel.changeControlLoopTarget(loop.loopId, requireNotNull(targetOptions[label]).fieldId)
            }
        }
        if (loop.strategy.requiresMeasurement()) {
            val measurements = numericFields.filter {
                it.role == SubsystemFieldRole.MEASUREMENT &&
                    (selectedTarget == null || subsystemControlUnitsCompatible(selectedTarget.unit, it.unit))
            }
            if (measurements.isNotEmpty()) {
                val measurementOptions = measurements.associateBy(::stateFieldOptionLabel)
                val selectedMeasurementLabel = measurementOptions.entries
                    .firstOrNull { it.value.fieldId == loop.measurementFieldId }
                    ?.key ?: measurementOptions.keys.first()
                DropdownSelector("Measurement feedback", selectedMeasurementLabel, measurementOptions.keys.toList()) { label ->
                    val measurement = requireNotNull(measurementOptions[label])
                    viewModel.updateControlLoop(loop.loopId) {
                        it.copy(
                            measurementFieldId = measurement.fieldId,
                            continuousInput = it.continuousInput.copy(
                                enabled = it.continuousInput.enabled && subsystemUnitIsCanonicalAngle(selectedTarget?.unit) &&
                                    subsystemUnitIsCanonicalAngle(measurement.unit),
                            ),
                        )
                    }
                }
            } else {
                FieldGuidance("Add a numeric measurement in the same unit as the selected target. ARES will not subtract incompatible units.")
            }
        }
        if (loop.strategy in setOf(SubsystemControlStrategy.POSITION_PID, SubsystemControlStrategy.PROFILED_POSITION_PID)) {
            if (supportsContinuousInput) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) {
                        ToggleRow("Use shortest path across the angle boundary", loop.continuousInput.enabled) { enabled ->
                            viewModel.updateControlLoop(loop.loopId) {
                                it.copy(continuousInput = it.continuousInput.copy(enabled = enabled))
                            }
                        }
                    }
                    ConceptHelp(
                        "Continuous angle input",
                        "For a rotating mechanism, +179° and -179° are only 2° apart. Wrapping makes PID and its derivative use that shortest signed angular error.",
                        "continuous-angle-control",
                        compact = true,
                    )
                }
                if (loop.continuousInput.enabled) {
                    val minusPiPreset = "-π to +π (common signed angle)"
                    val zeroToTwoPiPreset = "0 to 2π (common absolute encoder)"
                    val customPreset = "Custom one-turn range"
                    val selectedPreset = when {
                        editCustomAngleRange -> customPreset
                        kotlin.math.abs(loop.continuousInput.minimumInput + Math.PI) < 1e-9 &&
                            kotlin.math.abs(loop.continuousInput.maximumInput - Math.PI) < 1e-9 -> minusPiPreset
                        kotlin.math.abs(loop.continuousInput.minimumInput) < 1e-9 &&
                            kotlin.math.abs(loop.continuousInput.maximumInput - 2.0 * Math.PI) < 1e-9 -> zeroToTwoPiPreset
                        else -> customPreset
                    }
                    DropdownSelector(
                        "Sensor angle range",
                        selectedPreset,
                        listOf(minusPiPreset, zeroToTwoPiPreset, customPreset),
                    ) { preset ->
                        when (preset) {
                            minusPiPreset -> {
                                editCustomAngleRange = false
                                viewModel.updateControlLoop(loop.loopId) {
                                    it.copy(continuousInput = it.continuousInput.copy(minimumInput = -Math.PI, maximumInput = Math.PI))
                                }
                            }
                            zeroToTwoPiPreset -> {
                                editCustomAngleRange = false
                                viewModel.updateControlLoop(loop.loopId) {
                                    it.copy(continuousInput = it.continuousInput.copy(minimumInput = 0.0, maximumInput = 2.0 * Math.PI))
                                }
                            }
                            customPreset -> editCustomAngleRange = true
                        }
                    }
                    if (selectedPreset == customPreset) {
                        DoubleInput("Angle range minimum (rad)", loop.continuousInput.minimumInput) { value ->
                            viewModel.updateControlLoop(loop.loopId) {
                                it.copy(continuousInput = it.continuousInput.copy(minimumInput = value))
                            }
                        }
                        DoubleInput("Angle range maximum (rad)", loop.continuousInput.maximumInput) { value ->
                            viewModel.updateControlLoop(loop.loopId) {
                                it.copy(continuousInput = it.continuousInput.copy(maximumInput = value))
                            }
                        }
                    }
                    FieldGuidance("The range must describe one complete turn (2π rad), such as -π to +π or 0 to 2π. Hard-limited arms should leave wrapping off.")
                }
            } else {
                FieldGuidance("Shortest-path angle wrapping becomes available when both target and feedback use canonical radians (rad).")
            }
        }
        if (supportsPid) {
            Text("PID FEEDBACK GAINS", color = AresTextTertiary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            DoubleInput("kP (Proportional)", loop.kP) { value -> viewModel.updateControlLoop(loop.loopId) { it.copy(kP = value) } }
            DoubleInput("kI (Integral)", loop.kI) { value -> viewModel.updateControlLoop(loop.loopId) { it.copy(kI = value) } }
            DoubleInput("kD (Derivative)", loop.kD) { value -> viewModel.updateControlLoop(loop.loopId) { it.copy(kD = value) } }
            DoubleInput("Derivative filter (seconds)", loop.derivativeFilterTimeConstantSeconds) { value ->
                viewModel.updateControlLoop(loop.loopId) { it.copy(derivativeFilterTimeConstantSeconds = value) }
            }
            DoubleInput("Ready tolerance (${numericFields.firstOrNull { it.fieldId == loop.targetFieldId }?.unit ?: "state units"})", loop.tolerance) { value ->
                viewModel.updateControlLoop(loop.loopId) { it.copy(tolerance = value) }
            }
        } else if (loop.strategy == SubsystemControlStrategy.BANG_BANG) {
            val targetUnit = selectedTarget?.unit ?: "state units"
            DoubleInput("Stop band / tolerance ($targetUnit)", loop.tolerance) { value ->
                viewModel.updateControlLoop(loop.loopId) { it.copy(tolerance = value) }
            }
            DoubleInput("Restart hysteresis ($targetUnit)", loop.hysteresis) { value ->
                viewModel.updateControlLoop(loop.loopId) { it.copy(hysteresis = value) }
            }
            FieldGuidance(
                "Output stops inside ±${loop.tolerance}. Once stopped, it does not restart until error exceeds ±${loop.tolerance + loop.hysteresis}. Direction reversals pass through neutral for one control tick.",
            )
        }
        if (loop.strategy == SubsystemControlStrategy.PROFILED_POSITION_PID) {
            Text("MOTION PROFILE", color = AresTextTertiary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            DoubleInput("Maximum velocity (target units/s)", loop.motionProfile.maximumVelocity) { value ->
                viewModel.updateControlLoop(loop.loopId) { it.copy(motionProfile = it.motionProfile.copy(maximumVelocity = value)) }
            }
            DoubleInput("Maximum acceleration (target units/s²)", loop.motionProfile.maximumAcceleration) { value ->
                viewModel.updateControlLoop(loop.loopId) { it.copy(motionProfile = it.motionProfile.copy(maximumAcceleration = value)) }
            }
            FieldGuidance("The generated controller moves an internal setpoint toward the goal without exceeding these constraints.")
        }
        if (supportsPid) {
            Text("FEEDFORWARD", color = AresTextTertiary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            val feedforwardKinds = SubsystemFeedforwardKind.entries.filterNot { it == SubsystemFeedforwardKind.FOUR_BAR_LINKAGE }
            val feedforwardLabels = feedforwardKinds.associateBy { it.feedforwardLabel() }
            DropdownSelector(
                "Model",
                loop.feedforward.kind.feedforwardLabel(),
                feedforwardLabels.keys.toList(),
            ) { label ->
                val kind = requireNotNull(feedforwardLabels[label])
                viewModel.updateControlLoop(loop.loopId) { current ->
                    val angle = if (kind == SubsystemFeedforwardKind.ARM) {
                        current.feedforward.gravityAngleFieldId
                            ?: document.stateFields.firstOrNull { it.role == SubsystemFieldRole.MEASUREMENT && it.unit == "rad" }?.fieldId
                    } else null
                    current.copy(feedforward = current.feedforward.copy(kind = kind, gravityAngleFieldId = angle, linkageJoint = if (kind == SubsystemFeedforwardKind.TWO_DOF_ARM) current.feedforward.linkageJoint ?: 1 else null))
                }
            }
            FieldGuidance(feedforwardGuidance(loop.feedforward.kind))
            if (loop.feedforward.kind != SubsystemFeedforwardKind.NONE) {
                DoubleInput("kS (static volts)", loop.feedforward.kS) { value ->
                    viewModel.updateControlLoop(loop.loopId) { it.copy(feedforward = it.feedforward.copy(kS = value)) }
                }
                DoubleInput("kV (volts per velocity unit/s)", loop.feedforward.kV) { value ->
                    viewModel.updateControlLoop(loop.loopId) { it.copy(feedforward = it.feedforward.copy(kV = value)) }
                }
                DoubleInput("kA (volts per acceleration unit/s²)", loop.feedforward.kA) { value ->
                    viewModel.updateControlLoop(loop.loopId) { it.copy(feedforward = it.feedforward.copy(kA = value)) }
                }
                if (loop.feedforward.kind in setOf(SubsystemFeedforwardKind.ELEVATOR, SubsystemFeedforwardKind.ARM, SubsystemFeedforwardKind.TWO_DOF_ARM)) {
                    DoubleInput("kG (gravity compensation)", loop.feedforward.kG) { value ->
                        viewModel.updateControlLoop(loop.loopId) { it.copy(feedforward = it.feedforward.copy(kG = value)) }
                    }
                }
                val velocityDefault = when (loop.strategy) {
                    SubsystemControlStrategy.VELOCITY_PID -> "Use the target velocity"
                    SubsystemControlStrategy.PROFILED_POSITION_PID -> "Use the motion-profile velocity"
                    else -> "Use zero planned velocity"
                }
                val velocityOptions = numericFields.filter { subsystemUnitCanRepresentVelocity(it.unit) }.associateBy(::stateFieldOptionLabel)
                val selectedVelocity = velocityOptions.entries.firstOrNull { it.value.fieldId == loop.feedforward.velocityFieldId }?.key
                    ?: velocityDefault
                DropdownSelector("Desired velocity source", selectedVelocity, listOf(velocityDefault) + velocityOptions.keys) { selected ->
                    viewModel.updateControlLoop(loop.loopId) {
                        it.copy(feedforward = it.feedforward.copy(velocityFieldId = velocityOptions[selected]?.fieldId))
                    }
                }
                val accelerationDefault = if (loop.strategy == SubsystemControlStrategy.PROFILED_POSITION_PID) {
                    "Use the motion-profile acceleration"
                } else {
                    "Use zero planned acceleration"
                }
                val accelerationOptions = numericFields.filter { subsystemUnitCanRepresentAcceleration(it.unit) }.associateBy(::stateFieldOptionLabel)
                val selectedAcceleration = accelerationOptions.entries.firstOrNull { it.value.fieldId == loop.feedforward.accelerationFieldId }?.key
                    ?: accelerationDefault
                DropdownSelector("Desired acceleration source", selectedAcceleration, listOf(accelerationDefault) + accelerationOptions.keys) { selected ->
                    viewModel.updateControlLoop(loop.loopId) {
                        it.copy(feedforward = it.feedforward.copy(accelerationFieldId = accelerationOptions[selected]?.fieldId))
                    }
                }
                val angleOptions = numericFields.filter { subsystemUnitIsCanonicalAngle(it.unit) }.associateBy(::stateFieldOptionLabel)
                if (loop.feedforward.kind == SubsystemFeedforwardKind.ARM && angleOptions.isNotEmpty()) {
                    val selectedAngle = angleOptions.entries.firstOrNull { it.value.fieldId == loop.feedforward.gravityAngleFieldId }?.key
                        ?: angleOptions.keys.first()
                    DropdownSelector("Arm angle measurement (rad)", selectedAngle, angleOptions.keys.toList()) { selected ->
                        viewModel.updateControlLoop(loop.loopId) {
                            it.copy(feedforward = it.feedforward.copy(gravityAngleFieldId = requireNotNull(angleOptions[selected]).fieldId))
                        }
                    }
                } else if (loop.feedforward.kind == SubsystemFeedforwardKind.ARM) {
                    FieldGuidance("Add a numeric arm-angle measurement whose unit is rad before enabling arm gravity feedforward.")
                }
                if (loop.feedforward.kind == SubsystemFeedforwardKind.TWO_DOF_ARM) {
                    DropdownSelector("Linkage joint", (loop.feedforward.linkageJoint ?: 1).toString(), listOf("1", "2")) { selected ->
                        viewModel.updateControlLoop(loop.loopId) { it.copy(feedforward = it.feedforward.copy(linkageJoint = selected.toInt())) }
                    }
                }
                OutlinedButton(onClick = { showFeedforwardLab = !showFeedforwardLab }, modifier = Modifier.fillMaxWidth()) {
                    Text(if (showFeedforwardLab) "Hide feedforward lab" else "Try feedforward without hardware")
                }
                if (showFeedforwardLab) FeedforwardConceptLab(loop)
            }
        }
        DoubleInput("Minimum output ($outputUnit)", loop.minimumOutput) { value ->
            viewModel.updateControlLoop(loop.loopId) { it.copy(minimumOutput = value) }
        }
        DoubleInput("Maximum output ($outputUnit)", loop.maximumOutput) { value ->
            viewModel.updateControlLoop(loop.loopId) { it.copy(maximumOutput = value) }
        }
        OutlinedButton(onClick = { showControlLab = !showControlLab }, modifier = Modifier.fillMaxWidth()) {
            Text(if (showControlLab) "Hide commissioning sandbox" else "Commission this controller safely")
        }
        if (showControlLab) {
            SubsystemCommissioningLab(document, loop) { reviewedLoop ->
                viewModel.updateControlLoop(loop.loopId) { reviewedLoop }
            }
        }
    }
}

private fun SubsystemControlStrategy.controlStrategyLabel(): String = when (this) {
    SubsystemControlStrategy.DIRECT -> "Direct bounded output"
    SubsystemControlStrategy.POSITION_PID -> "Position PID"
    SubsystemControlStrategy.PROFILED_POSITION_PID -> "Profiled position PID"
    SubsystemControlStrategy.VELOCITY_PID -> "Velocity PID"
    SubsystemControlStrategy.BANG_BANG -> "Hysteretic on/off (bang-bang)"
    SubsystemControlStrategy.SERVO_POSITION -> "Positional servo"
}

private fun stateFieldOptionLabel(field: SubsystemStateFieldDocument): String {
    val role = field.role.name.replace('_', ' ').lowercase()
    val unit = field.unit?.takeIf { it.isNotBlank() } ?: "unitless"
    return "${field.displayName} · $role · $unit (${field.fieldId})"
}

private fun SubsystemFeedforwardKind.feedforwardLabel(): String = when (this) {
    SubsystemFeedforwardKind.NONE -> "No feedforward"
    SubsystemFeedforwardKind.SIMPLE_MOTOR -> "Simple motor (kS + kV + kA)"
    SubsystemFeedforwardKind.ELEVATOR -> "Elevator / lift gravity"
    SubsystemFeedforwardKind.ARM -> "Single-joint arm gravity"
    SubsystemFeedforwardKind.TWO_DOF_ARM -> "Two-joint arm coupling"
    SubsystemFeedforwardKind.FOUR_BAR_LINKAGE -> "Four-bar linkage (not generated)"
}

private fun feedforwardGuidance(kind: SubsystemFeedforwardKind): String = when (kind) {
    SubsystemFeedforwardKind.NONE -> "Start here when feedback alone is enough for a teaching draft. Add a model only when its units and physical meaning are understood."
    SubsystemFeedforwardKind.SIMPLE_MOTOR -> "Predicts voltage for static friction, desired velocity, and desired acceleration. Useful for flywheels and linear mechanisms without gravity loading."
    SubsystemFeedforwardKind.ELEVATOR -> "Adds constant gravity compensation to the simple motor model. Use for vertical linear lifts."
    SubsystemFeedforwardKind.ARM -> "Adds cosine gravity compensation from a cached arm angle in radians. Use for one pivoting link."
    SubsystemFeedforwardKind.TWO_DOF_ARM -> "Uses both joint angles and the declared linkage model to compensate coupled gravity. Configure each joint controller deliberately."
    SubsystemFeedforwardKind.FOUR_BAR_LINKAGE -> "Generation remains blocked until a constrained four-bar model and hardware contract are available."
}

@Composable
fun SafetyInspector(state: SubsystemGeneratorState, viewModel: SubsystemGeneratorViewModel) {
    val document = state.draft?.document ?: return
    val safety = document.safety
    var showAdvanced by remember { mutableStateOf(false) }
    var showHomingLab by remember { mutableStateOf(false) }
    val motorIds = document.hardware.filter { it.kind == SubsystemHardwareKind.MOTOR && it.following == null }.map { it.hardwareId }
    val cachedFields = document.hardware.flatMap { device -> device.measurements.map { it.fieldId } }.distinct()

    EditorCard("Safety & Health Protections", Icons.Default.Warning) {
        Text("Outputs are held in safe neutral if feedback is stale, current is unsafe, or writes fail.", color = AresTextSecondary, fontSize = 11.sp)
        NullableDoubleInput("Feedback timeout (ms)", safety.feedbackTimeoutMs?.toDouble()) { value ->
            viewModel.edit { it.copy(safety = it.safety.copy(feedbackTimeoutMs = value?.toLong())) }
        }
        FieldGuidance("Closed-loop output stops when the newest complete cached snapshot is older than this lease.")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                EnumSelector("Homing method", safety.homing.method, SubsystemHomingMethod.entries) { method ->
                    viewModel.setHomingMethod(method)
                }
            }
            ConceptHelp(
                "Homing",
                "Homing establishes a physical zero using a switch, current stall, velocity stall, combined evidence, or another cached signal.",
                "homing",
                compact = true,
            )
        }
        if (safety.homing.method != SubsystemHomingMethod.NONE) {
            if (motorIds.isNotEmpty()) {
                DropdownSelector("Homing motor", safety.homing.actuatorId ?: motorIds.first(), motorIds) { selected ->
                    viewModel.edit { it.copy(safety = it.safety.copy(homing = it.safety.homing.copy(actuatorId = selected))) }
                }
            }
            DoubleInput("Search output (V, limited to ±4)", safety.homing.searchOutput ?: -2.0) { value ->
                viewModel.edit { it.copy(safety = it.safety.copy(homing = it.safety.homing.copy(searchOutput = value))) }
            }
            LongInput("Evidence dwell (ms)", safety.homing.dwellMs) { value ->
                viewModel.edit { it.copy(safety = it.safety.copy(homing = it.safety.homing.copy(dwellMs = value))) }
            }
            LongInput("Hard timeout (ms)", safety.homing.timeoutMs) { value ->
                viewModel.edit { it.copy(safety = it.safety.copy(homing = it.safety.homing.copy(timeoutMs = value))) }
            }
            DoubleInput("Position assigned after neutral succeeds", safety.homing.zeroPosition) { value ->
                viewModel.edit { it.copy(safety = it.safety.copy(homing = it.safety.homing.copy(zeroPosition = value))) }
            }
            Text("HOMING EVIDENCE", color = AresTextTertiary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            safety.homing.evidence.forEachIndexed { index, evidence ->
                Surface(color = AresSurface, border = BorderStroke(1.dp, AresBorder), shape = RoundedCornerShape(6.dp)) {
                    Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (cachedFields.isNotEmpty()) {
                            DropdownSelector("Cached signal", evidence.fieldId, cachedFields) { selected ->
                                viewModel.edit { doc ->
                                    doc.copy(safety = doc.safety.copy(homing = doc.safety.homing.copy(
                                        evidence = doc.safety.homing.evidence.mapIndexed { i, item -> if (i == index) item.copy(fieldId = selected) else item }
                                    )))
                                }
                            }
                        }
                        EnumSelector("Condition", evidence.comparison, SubsystemHomingComparison.entries) { comparison ->
                            viewModel.edit { doc ->
                                doc.copy(safety = doc.safety.copy(homing = doc.safety.homing.copy(
                                    evidence = doc.safety.homing.evidence.mapIndexed { i, item ->
                                        if (i == index) item.copy(
                                            comparison = comparison,
                                            threshold = item.threshold.takeUnless { comparison in setOf(SubsystemHomingComparison.TRUE, SubsystemHomingComparison.FALSE) },
                                        ) else item
                                    }
                                )))
                            }
                        }
                        if (evidence.comparison !in setOf(SubsystemHomingComparison.TRUE, SubsystemHomingComparison.FALSE)) {
                            DoubleInput("Threshold", evidence.threshold ?: 0.0) { value ->
                                viewModel.edit { doc ->
                                    doc.copy(safety = doc.safety.copy(homing = doc.safety.homing.copy(
                                        evidence = doc.safety.homing.evidence.mapIndexed { i, item -> if (i == index) item.copy(threshold = value) else item }
                                    )))
                                }
                            }
                        }
                        if (safety.homing.method == SubsystemHomingMethod.CUSTOM_MEASUREMENT) {
                            TextButton(onClick = {
                                viewModel.edit { doc -> doc.copy(safety = doc.safety.copy(homing = doc.safety.homing.copy(evidence = doc.safety.homing.evidence.filterIndexed { i, _ -> i != index }))) }
                            }) { Text("Remove evidence") }
                        }
                    }
                }
            }
            if (safety.homing.method == SubsystemHomingMethod.CUSTOM_MEASUREMENT && cachedFields.isNotEmpty()) {
                OutlinedButton(onClick = {
                    viewModel.edit { doc -> doc.copy(safety = doc.safety.copy(homing = doc.safety.homing.copy(
                        evidence = doc.safety.homing.evidence + SubsystemHomingEvidenceDocument(cachedFields.first(), SubsystemHomingComparison.AT_OR_ABOVE, 0.0)
                    ))) }
                }, modifier = Modifier.fillMaxWidth()) { Text("+ Add cached evidence") }
            }
            FieldGuidance("ARES requires fresh evidence for the full dwell, then commands neutral before assigning zero. A timeout latches a homing fault.")
            if (safety.homing.evidence.isNotEmpty()) {
                OutlinedButton(onClick = { showHomingLab = !showHomingLab }, modifier = Modifier.fillMaxWidth()) {
                    Text(if (showHomingLab) "Hide homing lab" else "Try the homing evidence without hardware")
                }
                if (showHomingLab) HomingConceptLab(safety.homing)
            }
        }
        ToggleRow("Latch failed output writes", safety.latchOutputFaults) { value ->
            viewModel.edit { it.copy(safety = it.safety.copy(latchOutputFaults = value)) }
        }
        ToggleRow("Zero-allocation hot loop path", safety.zeroAllocationPeriodic) { value ->
            viewModel.edit { it.copy(safety = it.safety.copy(zeroAllocationPeriodic = value)) }
        }
        OutlinedButton(onClick = { showAdvanced = !showAdvanced }, modifier = Modifier.fillMaxWidth()) {
            Text(if (showAdvanced) "Hide advanced safety flags" else "Show advanced safety flags", fontSize = 11.sp)
        }
        if (showAdvanced) {
            ToggleRow("Require calibration", safety.requiresCalibration) { value ->
                viewModel.edit { it.copy(safety = it.safety.copy(requiresCalibration = value)) }
            }
            ToggleRow("Require configuration health", safety.requiresConfigurationHealth) { value ->
                viewModel.edit { it.copy(safety = it.safety.copy(requiresConfigurationHealth = value)) }
            }
            ToggleRow("Require valid current monitoring", safety.requiresCurrentMonitoring) { value ->
                viewModel.edit { it.copy(safety = it.safety.copy(requiresCurrentMonitoring = value)) }
            }
            ToggleRow("Require explicit successful neutral recovery", safety.requiresExplicitNeutralRecovery) { value ->
                viewModel.edit { it.copy(safety = it.safety.copy(requiresExplicitNeutralRecovery = value)) }
            }
            ToggleRow("Publish subsystem telemetry", safety.telemetryEnabled) { value ->
                viewModel.edit { it.copy(safety = it.safety.copy(telemetryEnabled = value)) }
            }
        }
    }
}

@Composable
fun FaultRecoveryCard(document: SubsystemDocument, viewModel: SubsystemGeneratorViewModel) {
    val recovery = document.safety.faultRecovery
    val eligibleActuators = document.hardware.filter {
        it.following == null && it.kind in setOf(SubsystemHardwareKind.MOTOR, SubsystemHardwareKind.CONTINUOUS_SERVO)
    }

    EditorCard("Automatic Jam Recovery / Anti-Stall", Icons.Default.Build) {
        Text("Detects mechanical jams from motor current and triggers automatic recovery.", color = AresTextSecondary, fontSize = 11.sp)
        if (eligibleActuators.isNotEmpty()) {
            ToggleRow("Enable anti-jam pulse", recovery.enabled) { value ->
                viewModel.edit { doc ->
                    val actuator = eligibleActuators.firstOrNull { it.hardwareId == doc.safety.faultRecovery.actuatorId }
                        ?: eligibleActuators.first()
                    val current = actuator.measurements.firstOrNull { it.source == SubsystemMeasurementSource.MOTOR_CURRENT_AMPS }
                    doc.copy(safety = doc.safety.copy(
                        faultRecovery = doc.safety.faultRecovery.copy(
                            enabled = value,
                            actuatorId = actuator.hardwareId.takeIf { value },
                            currentFieldId = current?.fieldId.takeIf { value },
                        ),
                        requiresCurrentMonitoring = doc.safety.requiresCurrentMonitoring || value,
                    ))
                }
            }
            if (recovery.enabled) {
                DropdownSelector("Actuator to recover", recovery.actuatorId ?: eligibleActuators.first().hardwareId, eligibleActuators.map { it.hardwareId }) { selected ->
                    val current = eligibleActuators.first { it.hardwareId == selected }.measurements
                        .firstOrNull { it.source == SubsystemMeasurementSource.MOTOR_CURRENT_AMPS }?.fieldId
                    viewModel.edit { doc -> doc.copy(safety = doc.safety.copy(faultRecovery = doc.safety.faultRecovery.copy(actuatorId = selected, currentFieldId = current))) }
                }
                val currentOptions = document.hardware.firstOrNull { it.hardwareId == recovery.actuatorId }?.measurements
                    .orEmpty().filter { it.source == SubsystemMeasurementSource.MOTOR_CURRENT_AMPS }.map { it.fieldId }
                if (currentOptions.isNotEmpty()) {
                    DropdownSelector("Cached current signal", recovery.currentFieldId ?: currentOptions.first(), currentOptions) { selected ->
                        viewModel.edit { doc -> doc.copy(safety = doc.safety.copy(faultRecovery = doc.safety.faultRecovery.copy(currentFieldId = selected))) }
                    }
                } else {
                    Text("The selected actuator needs a cached motor-current signal before recovery can be saved.", color = AresGold, fontSize = 10.sp)
                }
                DoubleInput("Jam current threshold (A)", recovery.currentThresholdAmps) { value ->
                    viewModel.edit { doc -> doc.copy(safety = doc.safety.copy(faultRecovery = doc.safety.faultRecovery.copy(currentThresholdAmps = value))) }
                }
                LongInput("Jam evidence duration (ms)", recovery.currentDurationMs) { value ->
                    viewModel.edit { doc -> doc.copy(safety = doc.safety.copy(faultRecovery = doc.safety.faultRecovery.copy(currentDurationMs = value))) }
                }
                EnumSelector(
                    "Recovery action",
                    recovery.recoveryAction,
                    listOf(FaultRecoveryActionKind.REVERSE_BRIEFLY, FaultRecoveryActionKind.NEUTRAL_STOP),
                ) { action ->
                    viewModel.edit { doc -> doc.copy(safety = doc.safety.copy(faultRecovery = doc.safety.faultRecovery.copy(recoveryAction = action))) }
                }
                if (recovery.recoveryAction == FaultRecoveryActionKind.REVERSE_BRIEFLY) {
                    DoubleInput("Reverse output (normalized -1 to 1)", recovery.reverseDutyCycle) { value ->
                        viewModel.edit { doc -> doc.copy(safety = doc.safety.copy(faultRecovery = doc.safety.faultRecovery.copy(reverseDutyCycle = value))) }
                    }
                    LongInput("Reverse duration (ms)", recovery.reverseDurationMs) { value ->
                        viewModel.edit { doc -> doc.copy(safety = doc.safety.copy(faultRecovery = doc.safety.faultRecovery.copy(reverseDurationMs = value))) }
                    }
                    IntInput("Maximum automatic retries", recovery.maxRetries) { value ->
                        viewModel.edit { doc -> doc.copy(safety = doc.safety.copy(faultRecovery = doc.safety.faultRecovery.copy(maxRetries = value))) }
                    }
                }
                FieldGuidance("Recovery is bounded and uses cached current only. Exhausted retries or a failed write leave the subsystem neutral and fault-latched.")
            }
        } else {
            Text("Add an independently controlled motor before enabling anti-jam protection.", color = AresTextTertiary, fontSize = 10.sp)
        }
    }
}

@Composable
fun InterlockMatrixCard(document: SubsystemDocument, state: SubsystemGeneratorState, viewModel: SubsystemGeneratorViewModel) {
    val targets = state.documents.filter {
        it.uid != document.uid && it.implementation.kind.isAresGenerated() && it.stateFields.isNotEmpty()
    }.sortedBy { it.displayName.lowercase() }
    EditorCard("Positional Interlocks (${document.interlocks.size})", Icons.Default.Lock) {
        Text("Interlocks read another generated subsystem's immutable state and force this mechanism to a declared safe fallback when a rule is not satisfied.", color = AresTextSecondary, fontSize = 11.sp)
        if (document.interlocks.isEmpty()) {
            Text("No positional interlocks configured.", color = AresTextTertiary, fontSize = 10.sp)
        }
        document.interlocks.forEach { interlock ->
            val target = targets.firstOrNull { it.uid == interlock.targetSubsystemUid }
            val targetOptions = targets.map { it.displayName }
            Surface(color = AresSurface, border = BorderStroke(1.dp, AresBorder), shape = RoundedCornerShape(6.dp)) {
                Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(interlock.interlockId, color = AresCyan, fontFamily = FontFamily.Monospace, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    if (targetOptions.isNotEmpty()) {
                        DropdownSelector("Other subsystem", target?.displayName ?: targetOptions.first(), targetOptions) { selectedName ->
                            val selected = targets.first { it.displayName == selectedName }
                            val field = selected.stateFields.first()
                            viewModel.updateInterlock(interlock.interlockId) {
                                it.copy(
                                    targetSubsystemUid = selected.uid,
                                    targetFieldId = field.fieldId,
                                    comparison = if (field.type in setOf(SubsystemValueType.DOUBLE, SubsystemValueType.INT)) InterlockComparison.LESS_THAN else InterlockComparison.EQUALS_STATE,
                                    targetStateName = if (field.type == SubsystemValueType.BOOLEAN) "false" else null,
                                )
                            }
                        }
                    }
                    val fields = target?.stateFields.orEmpty()
                    if (fields.isNotEmpty()) {
                        DropdownSelector("Observed state value", interlock.targetFieldId, fields.map { it.fieldId }) { selected ->
                            val field = fields.first { it.fieldId == selected }
                            viewModel.updateInterlock(interlock.interlockId) {
                                it.copy(
                                    targetFieldId = selected,
                                    comparison = if (field.type in setOf(SubsystemValueType.DOUBLE, SubsystemValueType.INT)) InterlockComparison.LESS_THAN else InterlockComparison.EQUALS_STATE,
                                    targetStateName = if (field.type == SubsystemValueType.BOOLEAN) "false" else null,
                                )
                            }
                        }
                        val field = fields.firstOrNull { it.fieldId == interlock.targetFieldId }
                        val comparisons = if (field?.type in setOf(SubsystemValueType.DOUBLE, SubsystemValueType.INT)) {
                            InterlockComparison.entries
                        } else {
                            listOf(InterlockComparison.EQUALS_STATE, InterlockComparison.NOT_EQUALS_STATE)
                        }
                        EnumSelector("Permit movement when", interlock.comparison, comparisons) { comparison ->
                            viewModel.updateInterlock(interlock.interlockId) { it.copy(comparison = comparison) }
                        }
                        if (interlock.comparison in setOf(InterlockComparison.LESS_THAN, InterlockComparison.GREATER_THAN)) {
                            DoubleInput("Threshold (${field?.unit ?: "state units"})", interlock.thresholdValue) { value ->
                                viewModel.updateInterlock(interlock.interlockId) { it.copy(thresholdValue = value) }
                            }
                        } else {
                            TextInput("Expected state", interlock.targetStateName.orEmpty()) { value ->
                                viewModel.updateInterlock(interlock.interlockId) { it.copy(targetStateName = value) }
                            }
                        }
                    }
                    TextInput("Student-facing reason", interlock.forbiddenZoneDescription) { value ->
                        viewModel.updateInterlock(interlock.interlockId) { it.copy(forbiddenZoneDescription = value) }
                    }
                    NullableDoubleInput("Safe fallback output (optional)", interlock.safeFallbackValue) { value ->
                        viewModel.updateInterlock(interlock.interlockId) { it.copy(safeFallbackValue = value) }
                    }
                    TextButton(onClick = { viewModel.removeInterlock(interlock.interlockId) }) {
                        Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Remove interlock")
                    }
                }
            }
        }
        OutlinedButton(onClick = viewModel::addInterlock, enabled = targets.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
            Text("+ Add cross-mechanism interlock")
        }
        if (targets.isEmpty()) {
            FieldGuidance("Add another generated subsystem with state values before creating a cross-mechanism interlock.")
        }
    }
}

private fun controlStrategyGuidance(strategy: SubsystemControlStrategy): String = when (strategy) {
    SubsystemControlStrategy.DIRECT ->
        "Direct output sends the bounded target to the actuator. Use it for percent/voltage commands that do not require sensor feedback."
    SubsystemControlStrategy.POSITION_PID ->
        "Position PID holds a measured position. Add a position measurement, physical units, output limits, and appropriate feedforward before hardware testing."
    SubsystemControlStrategy.PROFILED_POSITION_PID ->
        "Profiled position PID limits the internal setpoint's velocity and acceleration, then applies position feedback and optional feedforward."
    SubsystemControlStrategy.VELOCITY_PID ->
        "Velocity PID regulates measured speed. A simple-motor feedforward (kS/kV/kA) usually supplies most of the required voltage."
    SubsystemControlStrategy.BANG_BANG ->
        "Hysteretic on/off control stops inside a tolerance and requires extra error before restarting. This reduces chatter near the threshold, while direction changes pass through neutral."
    SubsystemControlStrategy.SERVO_POSITION ->
        "Servo position maps a normalized target to the positional-servo command and applies the declared safe neutral when motion is not permitted."
}
