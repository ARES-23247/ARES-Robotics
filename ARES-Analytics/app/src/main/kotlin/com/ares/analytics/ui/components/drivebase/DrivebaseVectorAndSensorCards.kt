package com.ares.analytics.ui.components.drivebase

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ares.analytics.service.drivebase.*
import com.ares.analytics.service.drivebase.DriveHardwareDeclaration
import com.ares.analytics.service.drivebase.DriveHardwareRole
import com.ares.analytics.service.drivebase.DrivebaseKind
import com.ares.analytics.shared.models.League
import com.ares.analytics.ui.theme.*
import com.ares.analytics.ui.theme.AresBackground
import com.ares.analytics.ui.theme.AresBorder
import com.ares.analytics.ui.theme.AresCyan
import com.ares.analytics.ui.theme.AresGold
import com.ares.analytics.ui.theme.AresGreen
import com.ares.analytics.ui.theme.AresSurface
import com.ares.analytics.ui.theme.AresSurfaceElevated
import com.ares.analytics.ui.theme.AresTextPrimary
import com.ares.analytics.ui.theme.AresTextSecondary
import com.ares.analytics.ui.theme.AresTextTertiary
import com.ares.analytics.viewmodel.drivebase.*
import com.ares.analytics.viewmodel.drivebase.DriveLabResult
import com.ares.analytics.viewmodel.drivebase.DriveLabState
import com.ares.analytics.viewmodel.drivebase.DrivebaseBuilderIntent
import com.ares.analytics.viewmodel.drivebase.DrivebaseBuilderState
import com.ares.analytics.viewmodel.drivebase.DrivebaseBuilderViewModel
import com.areslib.drivetrain.DisabledDrivePolicy
import com.areslib.drivetrain.DrivetrainControlKind
import com.areslib.drivetrain.DrivetrainNeutralMode
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

data class DriveWheelVectorVisual(
    val id: String,
    val label: String,
    /** Forward normalized position in [-1, +1] (+1 is front). */
    val xNorm: Double,
    /** Left normalized position in [-1, +1] (+1 is left). */
    val yNorm: Double,
    val output: Double,
    /** Steer/vector direction in robot frame degrees (0° = front/+X, +90° = left/+Y). */
    val angleDegrees: Double,
)

data class DriveCommandPreset(
    val label: String,
    val forward: Double,
    val strafe: Double,
    val rotate: Double,
)

fun driveCommandPresetsForKind(kind: DrivebaseKind): List<DriveCommandPreset> = buildList {
    add(DriveCommandPreset("Forward", forward = 0.8, strafe = 0.0, rotate = 0.0))
    if (kind != DrivebaseKind.DIFFERENTIAL) {
        add(DriveCommandPreset("Strafe Left", forward = 0.0, strafe = 0.8, rotate = 0.0))
        add(DriveCommandPreset("Diag + Turn", forward = 0.6, strafe = 0.5, rotate = 0.4))
    }
    add(DriveCommandPreset("Rotate CCW", forward = 0.0, strafe = 0.0, rotate = 0.75))
    add(DriveCommandPreset("Zero (Stop)", forward = 0.0, strafe = 0.0, rotate = 0.0))
}

fun computeDriveWheelVectors(
    kind: DrivebaseKind,
    result: DriveLabResult,
): List<DriveWheelVectorVisual> = when (kind) {
    DrivebaseKind.DIFFERENTIAL -> {
        val left = result.wheelOutputs["left"] ?: 0.0
        val right = result.wheelOutputs["right"] ?: 0.0
        listOf(
            DriveWheelVectorVisual(
                id = "left",
                label = "Left",
                xNorm = 0.0,
                yNorm = 1.0,
                output = left,
                angleDegrees = if (left >= 0.0) 0.0 else 180.0,
            ),
            DriveWheelVectorVisual(
                id = "right",
                label = "Right",
                xNorm = 0.0,
                yNorm = -1.0,
                output = right,
                angleDegrees = if (right >= 0.0) 0.0 else 180.0,
            ),
        )
    }
    DrivebaseKind.FRC_CTRE_SWERVE -> {
        val corners = listOf(
            Triple("frontLeft", "FL", 1.0 to 1.0),
            Triple("frontRight", "FR", 1.0 to -1.0),
            Triple("rearLeft", "RL", -1.0 to 1.0),
            Triple("rearRight", "RR", -1.0 to -1.0),
        )
        corners.map { (key, shortLabel, pos) ->
            val out = result.wheelOutputs[key] ?: 0.0
            val rawAngle = result.moduleAnglesDegrees[key] ?: 0.0
            val effectiveAngle = if (out < 0.0) rawAngle + 180.0 else rawAngle
            DriveWheelVectorVisual(
                id = key,
                label = shortLabel,
                xNorm = pos.first,
                yNorm = pos.second,
                output = out,
                angleDegrees = effectiveAngle,
            )
        }
    }
    DrivebaseKind.FTC_MECANUM, DrivebaseKind.CUSTOM -> {
        val corners = listOf(
            Triple("frontLeft", "FL", 1.0 to 1.0),
            Triple("frontRight", "FR", 1.0 to -1.0),
            Triple("rearLeft", "RL", -1.0 to 1.0),
            Triple("rearRight", "RR", -1.0 to -1.0),
        )
        corners.map { (key, shortLabel, pos) ->
            val out = result.wheelOutputs[key] ?: 0.0
            DriveWheelVectorVisual(
                id = key,
                label = shortLabel,
                xNorm = pos.first,
                yNorm = pos.second,
                output = out,
                angleDegrees = if (out >= 0.0) 0.0 else 180.0,
            )
        }
    }
}

data class LocalizationSensorVisual(
    val id: String,
    val displayName: String,
    val role: DriveHardwareRole,
    val xMeters: Double,
    val yMeters: Double,
    val zMeters: Double,
    val yawDegrees: Double,
    val pitchDegrees: Double,
    val isVisionCamera: Boolean,
)

private val LOCALIZATION_ROLES = setOf(
    DriveHardwareRole.GYRO,
    DriveHardwareRole.ODOMETRY,
    DriveHardwareRole.LIMELIGHT,
    DriveHardwareRole.DISTANCE_SENSOR,
)

fun extractLocalizationSensorVisuals(
    hardware: List<DriveHardwareDeclaration>,
): List<LocalizationSensorVisual> =
    hardware
        .filter { it.role in LOCALIZATION_ROLES }
        .map { device ->
            LocalizationSensorVisual(
                id = device.id,
                displayName = device.displayName,
                role = device.role,
                xMeters = device.xMeters ?: 0.0,
                yMeters = device.yMeters ?: 0.0,
                zMeters = device.zMeters ?: 0.0,
                yawDegrees = device.yawDegrees ?: 0.0,
                pitchDegrees = device.pitchDegrees ?: 0.0,
                isVisionCamera = device.role == DriveHardwareRole.LIMELIGHT,
            )
        }

@Composable
fun DriveMixingVectorCanvas(
    kind: DrivebaseKind,
    lab: DriveLabState,
    result: DriveLabResult,
    onPresetSelected: (DriveCommandPreset) -> Unit,
    modifier: Modifier = Modifier,
) {
    val presets = driveCommandPresetsForKind(kind)
    val wheelVectors = computeDriveWheelVectors(kind, result)

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Quick Test:",
                color = AresTextSecondary,
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold
            )
            presets.forEach { preset ->
                val isCurrent = abs(lab.forward - preset.forward) < 0.02 &&
                    abs(lab.strafe - preset.strafe) < 0.02 &&
                    abs(lab.rotate - preset.rotate) < 0.02
                Surface(
                    color = if (isCurrent) AresCyan.copy(alpha = 0.16f) else AresSurfaceElevated,
                    shape = RoundedCornerShape(6.dp),
                    border = BorderStroke(1.dp, if (isCurrent) AresCyan else AresBorder),
                    modifier = Modifier.clickable { onPresetSelected(preset) }
                ) {
                    Text(
                        preset.label,
                        color = if (isCurrent) AresCyan else AresTextPrimary,
                        fontSize = 10.sp,
                        fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }
        }

        Surface(
            color = AresBackground,
            shape = RoundedCornerShape(8.dp),
            border = BorderStroke(1.dp, AresBorder),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(width = 180.dp, height = 160.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val cx = size.width / 2f
                        val cy = size.height / 2f
                        val bodyW = size.width * 0.48f
                        val bodyH = size.height * 0.62f
                        val left = cx - bodyW / 2f
                        val top = cy - bodyH / 2f

                        // Robot chassis rectangle (+X Forward is UP on screen, +Y Left is LEFT on screen)
                        drawRoundRect(
                            color = Color(0xFF161D26),
                            topLeft = Offset(left, top),
                            size = Size(bodyW, bodyH),
                            cornerRadius = CornerRadius(10f, 10f)
                        )
                        drawRoundRect(
                            color = AresBorder,
                            topLeft = Offset(left, top),
                            size = Size(bodyW, bodyH),
                            cornerRadius = CornerRadius(10f, 10f),
                            style = Stroke(width = 2f)
                        )

                        // Front indicator bar at top edge
                        drawLine(
                            color = AresCyan,
                            start = Offset(left + 12f, top + 3f),
                            end = Offset(left + bodyW - 12f, top + 3f),
                            strokeWidth = 3f
                        )

                        // Center chassis resultant velocity vector
                        val chassisMag = hypot(result.robotForward, result.robotStrafe).coerceAtMost(1.4)
                        if (chassisMag > 0.02) {
                            val chassisAngleRad = atan2(result.robotStrafe, result.robotForward)
                            // Screen coordinates: Up (-Y_screen) is +X_robot, Left (-X_screen) is +Y_robot
                            val vxScreen = (-sin(chassisAngleRad) * chassisMag * 32.0).toFloat()
                            val vyScreen = (-cos(chassisAngleRad) * chassisMag * 32.0).toFloat()
                            val end = Offset(cx + vxScreen, cy + vyScreen)
                            drawLine(
                                color = AresCyan,
                                start = Offset(cx, cy),
                                end = end,
                                strokeWidth = 3f
                            )
                            drawCircle(color = AresCyan, radius = 4f, center = end)
                        } else {
                            drawCircle(color = AresTextTertiary, radius = 3f, center = Offset(cx, cy))
                        }

                        // Each wheel/module vector
                        val maxArrowPx = 28f
                        wheelVectors.forEach { wheel ->
                            val wx = cx - (wheel.yNorm.toFloat() * (bodyW / 2f))
                            val wy = cy - (wheel.xNorm.toFloat() * (bodyH / 2f))
                            val wheelOrigin = Offset(wx, wy)

                            drawRoundRect(
                                color = Color(0xFF263240),
                                topLeft = Offset(wx - 7f, wy - 11f),
                                size = Size(14f, 22f),
                                cornerRadius = CornerRadius(3f, 3f)
                            )

                            val mag = abs(wheel.output).coerceIn(0.0, 1.0).toFloat()
                            if (mag > 0.02f) {
                                val rad = Math.toRadians(wheel.angleDegrees)
                                val dx = (-sin(rad) * mag * maxArrowPx).toFloat()
                                val dy = (-cos(rad) * mag * maxArrowPx).toFloat()
                                val tip = Offset(wx + dx, wy + dy)
                                val vectorColor = if (wheel.output >= 0.0) AresGreen else AresGold
                                drawLine(
                                    color = vectorColor,
                                    start = wheelOrigin,
                                    end = tip,
                                    strokeWidth = 3f
                                )
                                drawCircle(
                                    color = vectorColor,
                                    radius = 3.5f,
                                    center = tip
                                )
                            }
                        }
                    }
                }

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Text(
                        "2D WHEEL / MODULE VECTOR MAP",
                        color = AresCyan,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        String.format(
                            Locale.US,
                            "Chassis demand: vx=%+.2f, vy=%+.2f, ω=%+.2f",
                            result.robotForward,
                            result.robotStrafe,
                            lab.rotate
                        ),
                        color = AresTextPrimary,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp
                    )
                    wheelVectors.forEach { wheel ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                wheel.label,
                                color = AresTextSecondary,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            val detail = if (kind == DrivebaseKind.FRC_CTRE_SWERVE) {
                                String.format(
                                    Locale.US,
                                    "%+.2f @ %+.0f°",
                                    wheel.output,
                                    result.moduleAnglesDegrees[wheel.id] ?: 0.0
                                )
                            } else {
                                String.format(Locale.US, "%+.2f", wheel.output)
                            }
                            Text(
                                detail,
                                color = when {
                                    wheel.output > 0.02 -> AresGreen
                                    wheel.output < -0.02 -> AresGold
                                    else -> AresTextTertiary
                                },
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }
    }
}
