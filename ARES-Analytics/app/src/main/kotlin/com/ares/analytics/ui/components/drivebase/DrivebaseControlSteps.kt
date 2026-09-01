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
fun GeometryStep(state: DrivebaseBuilderState, viewModel: DrivebaseBuilderViewModel) {
    SectionHeading("3 · Measure geometry", "Use meters internally. Wheelbase and track width are center-to-center distances; robot dimensions include frame perimeter.")
    val geometry = state.draft.geometry
    val labResult = evaluateGeometryLab(
        geometry = geometry,
        linearCommand = 1.0,
        angularCommand = 0.0,
        configuredMaxLinearSpeedMps = state.draft.safety.maxLinearSpeedMetersPerSecond,
        useCornerModuleRadius = state.draft.kind == DrivebaseKind.FRC_CTRE_SWERVE
    )

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // Left Column (50%): Physical Measurements
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
                Text("PHYSICAL MEASUREMENTS", color = AresTextSecondary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                GeometryField("Wheel radius", geometry.wheelRadiusMeters, "m", "Measure from axle center to floor under normal robot weight.") { viewModel.onIntent(DrivebaseBuilderIntent.UpdateGeometry(geometry.copy(wheelRadiusMeters = it))) }
                GeometryField("Track width", geometry.trackWidthMeters, "m", "Center-to-center distance between left and right wheel contact lines.") { viewModel.onIntent(DrivebaseBuilderIntent.UpdateGeometry(geometry.copy(trackWidthMeters = it))) }
                GeometryField("Wheelbase", geometry.wheelBaseMeters, "m", "Center-to-center distance between front and rear wheel contact lines.") { viewModel.onIntent(DrivebaseBuilderIntent.UpdateGeometry(geometry.copy(wheelBaseMeters = it))) }
            }
        }

        // Right Column (50%): Derived Kinematic Limits & Aspect Ratio
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
                Text("CONFIGURED KINEMATIC LIMITS", color = AresTextSecondary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Surface(Modifier.weight(1f), color = AresSurfaceElevated, shape = RoundedCornerShape(8.dp), border = BorderStroke(1.dp, AresBorder)) {
                        Column(Modifier.padding(10.dp)) {
                            Text("Linear Limit", color = AresTextSecondary, fontSize = 10.sp)
                            Text(labResult.maxLinearSpeedMps?.let { "%.2f m/s".format(it) } ?: "Not configured", color = AresCyan, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                            Text("≈ ${"%.1f".format((labResult.maxLinearSpeedMps ?: 0.0) * 3.28084)} ft/s", color = AresTextTertiary, fontSize = 10.sp)
                        }
                    }
                    Surface(Modifier.weight(1f), color = AresSurfaceElevated, shape = RoundedCornerShape(8.dp), border = BorderStroke(1.dp, AresBorder)) {
                        Column(Modifier.padding(10.dp)) {
                            Text("Max Angular Rate", color = AresTextSecondary, fontSize = 10.sp)
                            Text("${"%.1f".format(labResult.maxAngularSpeedRadPerSec ?: 0.0)} rad/s", color = AresGreen, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                            Text("≈ ${"%.0f".format((labResult.maxAngularSpeedRadPerSec ?: 0.0) * 180.0 / Math.PI)}°/s", color = AresTextTertiary, fontSize = 10.sp)
                        }
                    }
                }
                val ratio = if (geometry.trackWidthMeters > 0.01) geometry.wheelBaseMeters / geometry.trackWidthMeters else 1.0
                Surface(Modifier.fillMaxWidth(), color = AresSurfaceElevated, shape = RoundedCornerShape(8.dp), border = BorderStroke(1.dp, AresBorder)) {
                    Row(
                        Modifier.fillMaxWidth().padding(10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column {
                            Text("Aspect Ratio (L/W)", color = AresTextSecondary, fontSize = 10.sp)
                            Text("%.2f".format(ratio), color = if (ratio in 0.7..1.4) AresTextPrimary else AresGold, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                        }
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = if (ratio in 0.7..1.4) AresGreen.copy(alpha = 0.15f) else AresGold.copy(alpha = 0.15f),
                            border = BorderStroke(1.dp, if (ratio in 0.7..1.4) AresGreen else AresGold),
                        ) {
                            Text(
                                if (ratio in 0.7..1.4) "Balanced Turning" else "High Scrub Risk",
                                color = if (ratio in 0.7..1.4) AresGreen else AresGold,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                        }
                    }
                }
                Text("Overall frame perimeter & bumper geometry belong to the robot identity contract.", color = AresTextTertiary, fontSize = 10.sp)
            }
        }
    }
}

@Composable
fun ControlStep(state: DrivebaseBuilderState, viewModel: DrivebaseBuilderViewModel) {
    SectionHeading(
        "4 · Choose drive control systems",
        "ARES recommends chassis-velocity control for normal driving. Open-loop remains available for first-motion diagnosis; every closed-loop mode requires fresh encoder feedback.",
    )
    val modes = DrivetrainControlKind.entries
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        modes.forEach { mode ->
            val enabled = mode in state.draft.supportedControlModes
            val isDefault = state.draft.defaultControlMode == mode
            val explanation = when (mode) {
                DrivetrainControlKind.OPEN_LOOP -> "Driver demand is converted directly to bounded motor output. Useful for first motion and hardware verification."
                DrivetrainControlKind.WHEEL_VELOCITY -> "Each wheel or module drive motor closes a velocity loop using cached encoder feedback, PID, and feedforward."
                DrivetrainControlKind.CHASSIS_VELOCITY -> "The robot tracks forward, strafe, and turn velocity commands through drivetrain kinematics and wheel feedback."
                DrivetrainControlKind.TRAJECTORY -> "A path follower generates chassis targets; localization, velocity feedback, and tuned translation/heading loops are required."
            }
            Surface(
                color = if (enabled) AresCyan.copy(alpha = 0.08f) else AresSurface,
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(if (enabled) 1.5.dp else 1.dp, if (enabled) AresCyan else AresBorder),
            ) {
                Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = enabled,
                        onCheckedChange = { checked ->
                            val next = if (checked) (state.draft.supportedControlModes + mode).distinct() else state.draft.supportedControlModes - mode
                            val nextDefault = if (state.draft.defaultControlMode in next) state.draft.defaultControlMode else next.firstOrNull() ?: mode
                            viewModel.onIntent(DrivebaseBuilderIntent.UpdateControl(next, nextDefault, state.draft.fieldRelativeEnabled))
                        },
                    )
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(mode.name.lowercase().replace('_', ' ').replaceFirstChar(Char::uppercase), color = AresTextPrimary, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            if (mode == DrivetrainControlKind.CHASSIS_VELOCITY) {
                                Text("RECOMMENDED", color = AresGreen, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                        Text(explanation, color = AresTextSecondary, fontSize = 10.sp, lineHeight = 14.sp)
                    }
                    RadioButton(
                        selected = isDefault,
                        enabled = enabled,
                        onClick = { viewModel.onIntent(DrivebaseBuilderIntent.UpdateControl(state.draft.supportedControlModes, mode, state.draft.fieldRelativeEnabled)) },
                    )
                    Text(if (isDefault) "Default" else "Make default", color = if (isDefault) AresCyan else AresTextTertiary, fontSize = 10.sp)
                }
            }
        }
        Surface(color = AresSurface, shape = RoundedCornerShape(8.dp), border = BorderStroke(1.dp, AresBorder)) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(
                        checked = state.draft.fieldRelativeEnabled,
                        onCheckedChange = { viewModel.onIntent(DrivebaseBuilderIntent.UpdateControl(state.draft.supportedControlModes, state.draft.defaultControlMode, it)) },
                    )
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text("Field-relative driving", color = AresTextPrimary, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                        Text("Forward follows the field instead of the robot. Requires a fresh, valid CCW-positive heading.", color = AresTextSecondary, fontSize = 10.sp)
                    }
                }
                Text(
                    "Closed-loop wheel velocity uses the FTC motor controller's encoder loop. ARES keeps the controller's built-in PIDF when every custom motor PIDF value is zero. Calibrated overrides and feedforward belong in Tuning Studio.",
                    color = AresGold,
                    fontSize = 10.sp,
                )
                HorizontalDivider(color = AresBorder)
                Text("DRIVE ASSISTS", color = AresCyan, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                Text(
                    "Rotation lock starts enabled and holds heading when the turn stick is released. Anti-push position hold starts off and engages only with fresh, valid pose feedback.",
                    color = AresTextSecondary,
                    fontSize = 10.sp,
                    lineHeight = 14.sp,
                )
                Text(
                    "In TeleOp Controls, bind Enable, Disable, or Toggle actions under Drive assists. Tune heading and anti-push gains, deadzones, and output limits in Tuning Studio.",
                    color = AresTextSecondary,
                    fontSize = 10.sp,
                    lineHeight = 14.sp,
                )
            }
        }
        DriveControlLab(state, viewModel)
    }
}

@Composable
private fun DriveControlLab(state: DrivebaseBuilderState, viewModel: DrivebaseBuilderViewModel) {
    val lab = state.lab.copy(fieldRelative = state.draft.fieldRelativeEnabled)
    val result = evaluateDriveLab(state.draft.kind, lab, state.draft.geometry, state.draft.hardware)
    Surface(
        color = AresSurface,
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, AresBorder),
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("SAFE DRIVE MIXING LAB", color = AresCyan, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            Text(
                "Move the sliders to see the command each declared motor would receive. This model never connects to or pulses physical hardware.",
                color = AresTextSecondary,
                fontSize = 11.sp,
            )
            LabSlider("Forward", lab.forward) { viewModel.onIntent(DrivebaseBuilderIntent.UpdateLab(lab.copy(forward = it))) }
            if (state.draft.kind != DrivebaseKind.DIFFERENTIAL) {
                LabSlider("Strafe", lab.strafe) { viewModel.onIntent(DrivebaseBuilderIntent.UpdateLab(lab.copy(strafe = it))) }
            }
            LabSlider("Turn", lab.rotate) { viewModel.onIntent(DrivebaseBuilderIntent.UpdateLab(lab.copy(rotate = it))) }
            if (state.draft.fieldRelativeEnabled) {
                Text("Robot heading: ${"%.0f".format(lab.headingDegrees)}°", color = AresTextPrimary, fontSize = 11.sp)
                Slider(
                    value = lab.headingDegrees.toFloat(),
                    onValueChange = { viewModel.onIntent(DrivebaseBuilderIntent.UpdateLab(lab.copy(headingDegrees = it.toDouble()))) },
                    valueRange = -180f..180f,
                    modifier = Modifier.semantics { contentDescription = "Simulated robot heading in degrees" },
                )
            }
            Text(result.explanation, color = AresTextTertiary, fontSize = 10.sp)
            result.wheelOutputs.entries.chunked(2).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { (id, output) ->
                        Surface(
                            modifier = Modifier.weight(1f),
                            color = AresSurfaceElevated,
                            border = BorderStroke(1.dp, AresBorder),
                            shape = RoundedCornerShape(7.dp),
                        ) {
                            Column(Modifier.padding(9.dp)) {
                                Text(id.replaceFirstChar(Char::uppercase), color = AresTextSecondary, fontSize = 10.sp)
                                Text("%+.2f".format(output), color = if (kotlin.math.abs(output) > 1e-9) AresCyan else AresTextPrimary, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun LabSlider(label: String, value: Double, onValueChange: (Double) -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, color = AresTextPrimary, fontSize = 11.sp)
            Text("%+.2f".format(value), color = AresCyan, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, fontSize = 11.sp)
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { onValueChange(it.toDouble()) },
            valueRange = -1f..1f,
            modifier = Modifier.semantics { contentDescription = "$label command preview" },
        )
    }
}

@Composable
fun LocalizationStep(state: DrivebaseBuilderState, viewModel: DrivebaseBuilderViewModel) {
    SectionHeading("5 · Choose localization", "Localization estimates robot pose on the field. Choose one primary source and optionally add vision correction.")
    val kinds = LocalizationKind.entries
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        kinds.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { kind ->
                    val isChecked = kind in state.draft.localization
                    val description = when (kind) {
                        LocalizationKind.FTC_PINPOINT -> "goBILDA Pinpoint odometry computer; CCW-positive normalized."
                        LocalizationKind.WHEEL_ODOMETRY_GYRO -> "Wheel deadwheel encoders plus internal IMU gyro."
                        LocalizationKind.CTRE_POSE_ESTIMATOR -> "CTRE swerve module and Pigeon observations."
                        LocalizationKind.VISION_FUSION -> "AprilTag vision corrections with Mahalanobis gating."
                        LocalizationKind.CUSTOM -> "Team-maintained custom estimator adapter."
                    }
                    Surface(
                        modifier = Modifier
                            .weight(1f)
                            .border(
                                width = if (isChecked) 1.5.dp else 1.dp,
                                color = if (isChecked) AresCyan else AresBorder,
                                shape = RoundedCornerShape(8.dp)
                            )
                            .clickable { viewModel.onIntent(DrivebaseBuilderIntent.SetLocalization(kind, !isChecked)) },
                        color = if (isChecked) AresCyan.copy(alpha = 0.08f) else AresSurface,
                        shape = RoundedCornerShape(8.dp),
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    kind.name.lowercase().replace('_', ' ').replaceFirstChar(Char::uppercase),
                                    color = AresTextPrimary,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp,
                                )
                                Text(description, color = AresTextSecondary, fontSize = 10.sp, lineHeight = 14.sp)
                            }
                            Spacer(Modifier.width(8.dp))
                            Switch(
                                checked = isChecked,
                                onCheckedChange = { viewModel.onIntent(DrivebaseBuilderIntent.SetLocalization(kind, it)) },
                            )
                        }
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}


