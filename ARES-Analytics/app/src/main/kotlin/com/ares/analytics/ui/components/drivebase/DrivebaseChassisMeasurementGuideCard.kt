package com.ares.analytics.ui.components.drivebase

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ares.analytics.service.drivebase.DriveHardwareRole
import com.ares.analytics.ui.theme.AresBackground
import com.ares.analytics.ui.theme.AresBorder
import com.ares.analytics.ui.theme.AresCyan
import com.ares.analytics.ui.theme.AresGold
import com.ares.analytics.ui.theme.AresGreen
import com.ares.analytics.ui.theme.AresSurface
import com.ares.analytics.ui.theme.AresSurfaceElevated
import com.ares.analytics.ui.theme.AresTextPrimary
import com.ares.analytics.ui.theme.AresTextSecondary
import com.ares.analytics.viewmodel.drivebase.DrivebaseBuilderIntent
import com.ares.analytics.viewmodel.drivebase.DrivebaseBuilderState
import com.ares.analytics.viewmodel.drivebase.DrivebaseBuilderViewModel
import com.ares.analytics.viewmodel.drivebase.availableChassisPresets
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun DrivebaseChassisPresetBar(
    state: DrivebaseBuilderState,
    viewModel: DrivebaseBuilderViewModel,
    modifier: Modifier = Modifier,
) {
    val presets = availableChassisPresets(state.league)
    if (presets.isEmpty()) return

    Surface(
        color = AresSurface,
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, AresCyan.copy(alpha = 0.45f)),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    "1-CLICK REAL-WORLD COTS CHASSIS PRESETS (${state.league.name})",
                    color = AresCyan,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    "Pick a popular kit chassis to auto-fill wheel radius, center-to-center trackwidth/wheelbase, and safe speed limits.",
                    color = AresTextSecondary,
                    fontSize = 11.sp,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                presets.forEach { preset ->
                    val matchesGeometry = abs(state.draft.geometry.wheelRadiusMeters - preset.wheelRadiusMeters) < 1e-4 &&
                        abs(state.draft.geometry.trackWidthMeters - preset.trackWidthMeters) < 1e-3 &&
                        abs(state.draft.geometry.wheelBaseMeters - preset.wheelBaseMeters) < 1e-3 &&
                        state.draft.kind == preset.kind
                    Surface(
                        color = if (matchesGeometry) AresCyan.copy(alpha = 0.12f) else AresSurfaceElevated,
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(if (matchesGeometry) 1.5.dp else 1.dp, if (matchesGeometry) AresCyan else AresBorder),
                        modifier = Modifier
                            .weight(1f)
                            .clickable { viewModel.onIntent(DrivebaseBuilderIntent.ApplyChassisPreset(preset)) },
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    preset.title,
                                    color = if (matchesGeometry) AresCyan else AresTextPrimary,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp,
                                )
                                Text(
                                    if (matchesGeometry) "APPLIED" else "1-CLICK APPLY",
                                    color = if (matchesGeometry) AresGreen else AresCyan,
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                            Text(
                                preset.subtitle,
                                color = AresTextSecondary,
                                fontSize = 10.sp,
                            )
                            Text(
                                preset.measurementSummary,
                                color = AresGold,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 9.sp,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun DrivebaseChassisMeasurementGuideCard(
    state: DrivebaseBuilderState,
    viewModel: DrivebaseBuilderViewModel,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = AresSurface,
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, AresBorder),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                "HOW TO MEASURE TRACKWIDTH & WHEELBASE (STUDENT GUIDE)",
                color = AresGold,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                MeasurementTipTile(
                    modifier = Modifier.weight(1f),
                    title = "1. Wheel Radius (Not Diameter!)",
                    badge = String.format(Locale.US, "%.1f mm (%.3f m)", state.draft.geometry.wheelRadiusMeters * 1000.0, state.draft.geometry.wheelRadiusMeters),
                    detail = "Divide your wheel diameter by 2 (e.g., 104 mm goBILDA wheel → 52 mm = 0.052 m; 4 in SDS wheel → 2 in = 0.0508 m).",
                )
                MeasurementTipTile(
                    modifier = Modifier.weight(1f),
                    title = "2. Trackwidth (Left ↔ Right)",
                    badge = String.format(Locale.US, "%.0f mm (%.2f m)", state.draft.geometry.trackWidthMeters * 1000.0, state.draft.geometry.trackWidthMeters),
                    detail = "Measure from the CENTER of the left tire contact patch to the CENTER of the right tire contact patch—NOT outer bumper to outer bumper.",
                )
                MeasurementTipTile(
                    modifier = Modifier.weight(1f),
                    title = "3. Wheelbase (Front ↕ Rear)",
                    badge = String.format(Locale.US, "%.0f mm (%.2f m)", state.draft.geometry.wheelBaseMeters * 1000.0, state.draft.geometry.wheelBaseMeters),
                    detail = "Measure from the front axle centerline to the rear axle centerline. Accurate wheelbase + trackwidth prevents autonomous turn overshoot.",
                )
            }
            DrivebaseChassisPresetBar(state = state, viewModel = viewModel)
        }
    }
}

@Composable
private fun MeasurementTipTile(
    title: String,
    badge: String,
    detail: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = AresSurfaceElevated,
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, AresBorder),
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(title, color = AresTextPrimary, fontWeight = FontWeight.Bold, fontSize = 11.sp)
            Text(badge, color = AresCyan, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 11.sp)
            Text(detail, color = AresTextSecondary, fontSize = 10.sp, lineHeight = 14.sp)
        }
    }
}

@Composable
fun LocalizationSensorMountingCard(
    state: DrivebaseBuilderState,
    viewModel: DrivebaseBuilderViewModel,
    modifier: Modifier = Modifier,
) {
    val sensors = extractLocalizationSensorVisuals(state.draft.hardware)
    val geometry = state.draft.geometry

    Surface(
        color = AresSurface,
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, AresBorder),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        "SENSOR & VISION CAMERA MOUNTING MAP",
                        color = AresCyan,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "Top-down robot frame (+X Forward, +Y Left, CCW+ Yaw). Click any sensor to edit its 3D offset & camera pose.",
                        color = AresTextSecondary,
                        fontSize = 10.sp
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(
                        onClick = { viewModel.onIntent(DrivebaseBuilderIntent.AddHardware(DriveHardwareRole.LIMELIGHT)) },
                        border = BorderStroke(1.dp, AresCyan.copy(alpha = 0.5f)),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = AresCyan),
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Add Camera", fontSize = 10.sp)
                    }
                    OutlinedButton(
                        onClick = { viewModel.onIntent(DrivebaseBuilderIntent.AddHardware(DriveHardwareRole.ODOMETRY)) },
                        border = BorderStroke(1.dp, AresBorder),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = AresTextPrimary),
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Add Odometry", fontSize = 10.sp)
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    color = AresBackground,
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, AresBorder),
                    modifier = Modifier.size(width = 190.dp, height = 170.dp)
                ) {
                    Canvas(modifier = Modifier.fillMaxSize().padding(12.dp)) {
                        val cx = size.width / 2f
                        val cy = size.height / 2f
                        val maxSpanMeters = maxOf(geometry.wheelBaseMeters, geometry.trackWidthMeters, 0.40).coerceAtLeast(0.25)
                        val pxPerMeter = (minOf(size.width, size.height) * 0.62f) / maxSpanMeters.toFloat()
                        val frameW = (geometry.trackWidthMeters.toFloat() * pxPerMeter).coerceAtLeast(60f)
                        val frameH = (geometry.wheelBaseMeters.toFloat() * pxPerMeter).coerceAtLeast(60f)
                        val left = cx - frameW / 2f
                        val top = cy - frameH / 2f

                        drawRoundRect(
                            color = Color(0xFF161D26),
                            topLeft = Offset(left, top),
                            size = Size(frameW, frameH),
                            cornerRadius = CornerRadius(8f, 8f)
                        )
                        drawRoundRect(
                            color = AresBorder,
                            topLeft = Offset(left, top),
                            size = Size(frameW, frameH),
                            cornerRadius = CornerRadius(8f, 8f),
                            style = Stroke(width = 1.8f)
                        )
                        drawLine(
                            color = AresCyan,
                            start = Offset(left + 10f, top + 2f),
                            end = Offset(left + frameW - 10f, top + 2f),
                            strokeWidth = 3f
                        )
                        drawLine(
                            color = AresBorder,
                            start = Offset(cx - 8f, cy),
                            end = Offset(cx + 8f, cy),
                            strokeWidth = 1f
                        )
                        drawLine(
                            color = AresBorder,
                            start = Offset(cx, cy - 8f),
                            end = Offset(cx, cy + 8f),
                            strokeWidth = 1f
                        )

                        sensors.forEach { sensor ->
                            val sx = (cx - sensor.yMeters.toFloat() * pxPerMeter).coerceIn(8f, size.width - 8f)
                            val sy = (cy - sensor.xMeters.toFloat() * pxPerMeter).coerceIn(8f, size.height - 8f)
                            val origin = Offset(sx, sy)

                            if (sensor.isVisionCamera) {
                                val fovHalfRad = Math.toRadians(30.0)
                                val yawRad = Math.toRadians(sensor.yawDegrees)
                                val coneLen = 44f
                                val leftAngle = yawRad + fovHalfRad
                                val rightAngle = yawRad - fovHalfRad
                                val p1 = Offset(
                                    sx + (-sin(leftAngle) * coneLen).toFloat(),
                                    sy + (-cos(leftAngle) * coneLen).toFloat()
                                )
                                val p2 = Offset(
                                    sx + (-sin(rightAngle) * coneLen).toFloat(),
                                    sy + (-cos(rightAngle) * coneLen).toFloat()
                                )
                                val conePath = Path().apply {
                                    moveTo(origin.x, origin.y)
                                    lineTo(p1.x, p1.y)
                                    lineTo(p2.x, p2.y)
                                    close()
                                }
                                drawPath(path = conePath, color = AresCyan.copy(alpha = 0.22f))
                                drawLine(color = AresCyan, start = origin, end = p1, strokeWidth = 1.5f)
                                drawLine(color = AresCyan, start = origin, end = p2, strokeWidth = 1.5f)
                            }

                            val dotColor = when (sensor.role) {
                                DriveHardwareRole.LIMELIGHT -> AresCyan
                                DriveHardwareRole.GYRO -> AresGold
                                DriveHardwareRole.ODOMETRY -> AresGreen
                                else -> AresTextPrimary
                            }
                            drawCircle(color = dotColor, radius = 5f, center = origin)
                        }
                    }
                }

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (sensors.isEmpty()) {
                        Text(
                            "No gyro, odometry, or vision sensor hardware bindings declared yet. Use 'Add Camera' or 'Add Odometry' above to declare mounting offsets.",
                            color = AresTextSecondary,
                            fontSize = 11.sp
                        )
                    } else {
                        sensors.forEach { sensor ->
                            Surface(
                                color = AresSurfaceElevated,
                                shape = RoundedCornerShape(8.dp),
                                border = BorderStroke(1.dp, AresBorder),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        viewModel.onIntent(DrivebaseBuilderIntent.SelectHardware(sensor.id))
                                    }
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 10.dp, vertical = 8.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(
                                        modifier = Modifier.weight(1f),
                                        verticalArrangement = Arrangement.spacedBy(2.dp)
                                    ) {
                                        Row(
                                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                sensor.displayName,
                                                color = AresTextPrimary,
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                            Text(
                                                sensor.role.name,
                                                color = when (sensor.role) {
                                                    DriveHardwareRole.LIMELIGHT -> AresCyan
                                                    DriveHardwareRole.GYRO -> AresGold
                                                    else -> AresGreen
                                                },
                                                fontSize = 9.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                        val offsetSummary = buildString {
                                            append(
                                                String.format(
                                                    Locale.US,
                                                    "Offset: x=%+.2fm, y=%+.2fm, z=%+.2fm",
                                                    sensor.xMeters,
                                                    sensor.yMeters,
                                                    sensor.zMeters
                                                )
                                            )
                                            if (sensor.isVisionCamera || abs(sensor.yawDegrees) > 1e-3 || abs(sensor.pitchDegrees) > 1e-3) {
                                                append(
                                                    String.format(
                                                        Locale.US,
                                                        " • Pose: yaw=%+.0f°, pitch=%+.0f°",
                                                        sensor.yawDegrees,
                                                        sensor.pitchDegrees
                                                    )
                                                )
                                            }
                                        }
                                        Text(
                                            offsetSummary,
                                            color = AresTextSecondary,
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 10.sp
                                        )
                                    }
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.Edit,
                                            contentDescription = "Edit Mounting",
                                            tint = AresCyan,
                                            modifier = Modifier.size(14.dp)
                                        )
                                        Text(
                                            "Edit Mounting",
                                            color = AresCyan,
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
