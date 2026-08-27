package com.ares.analytics.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ares.analytics.ui.theme.AresBackground
import com.ares.analytics.ui.theme.AresBorder
import com.ares.analytics.ui.theme.AresCyan
import com.ares.analytics.ui.theme.AresGold
import com.ares.analytics.ui.theme.AresGreen
import com.ares.analytics.ui.theme.AresSurfaceElevated
import com.ares.analytics.ui.theme.AresTextPrimary
import com.ares.analytics.ui.theme.AresTextSecondary
import com.areslib.kinematics.MecanumKinematics
import com.areslib.kinematics.SwerveKinematics
import com.areslib.math.geometry.ChassisSpeeds
import com.areslib.math.geometry.Translation2d
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

internal enum class AcademyDriveKinematics(val label: String) {
    MECANUM("Mecanum wheel speeds"),
    SWERVE("Swerve module targets"),
}

internal data class AcademyWheelTarget(
    val label: String,
    val xMeters: Double,
    val yMeters: Double,
    val speedMetersPerSecond: Double,
    val angleRadians: Double,
)

internal data class AcademyKinematicsPreview(
    val targets: List<AcademyWheelTarget>,
    val scaleApplied: Double,
)

/**
 * Calculates ideal robot-frame wheel/module targets for the Academy view using production ARES
 * kinematics. It models neither traction nor motor dynamics and cannot validate a physical drive.
 */
internal fun computeAcademyKinematicsPreview(
    kind: AcademyDriveKinematics,
    vxMetersPerSecond: Double,
    vyMetersPerSecond: Double,
    omegaRadiansPerSecond: Double,
    trackWidthMeters: Double,
    wheelBaseMeters: Double,
    maximumWheelSpeedMetersPerSecond: Double,
): AcademyKinematicsPreview {
    val values = doubleArrayOf(
        vxMetersPerSecond,
        vyMetersPerSecond,
        omegaRadiansPerSecond,
        trackWidthMeters,
        wheelBaseMeters,
        maximumWheelSpeedMetersPerSecond,
    )
    require(values.all(Double::isFinite)) { "Kinematics inputs must be finite." }
    require(trackWidthMeters > 0.0 && wheelBaseMeters > 0.0) { "Drivebase dimensions must be positive." }
    require(maximumWheelSpeedMetersPerSecond > 0.0) { "Maximum wheel speed must be positive." }

    val halfLength = wheelBaseMeters / 2.0
    val halfWidth = trackWidthMeters / 2.0
    val positions = listOf(
        Triple("FL", halfLength, halfWidth),
        Triple("FR", halfLength, -halfWidth),
        Triple("RL", -halfLength, halfWidth),
        Triple("RR", -halfLength, -halfWidth),
    )
    val rawTargets = when (kind) {
        AcademyDriveKinematics.MECANUM -> {
            val raw = MecanumKinematics(trackWidthMeters, wheelBaseMeters).toWheelSpeeds(
                ChassisSpeeds(vxMetersPerSecond, vyMetersPerSecond, omegaRadiansPerSecond),
            )
            val speeds = listOf(
                raw.frontLeftMetersPerSecond,
                raw.frontRightMetersPerSecond,
                raw.backLeftMetersPerSecond,
                raw.backRightMetersPerSecond,
            )
            positions.mapIndexed { index, (label, x, y) ->
                AcademyWheelTarget(label, x, y, speeds[index], 0.0)
            }
        }
        AcademyDriveKinematics.SWERVE -> {
            val kinematics = SwerveKinematics(positions.map { (_, x, y) -> Translation2d(x, y) })
            val states = kinematics.toSwerveModuleStates(
                ChassisSpeeds(vxMetersPerSecond, vyMetersPerSecond, omegaRadiansPerSecond),
            )
            positions.mapIndexed { index, (label, x, y) ->
                AcademyWheelTarget(label, x, y, states[index].speedMetersPerSecond, states[index].angle.radians)
            }
        }
    }
    val peak = rawTargets.maxOfOrNull { abs(it.speedMetersPerSecond) } ?: 0.0
    val scale = if (peak > maximumWheelSpeedMetersPerSecond) maximumWheelSpeedMetersPerSecond / peak else 1.0
    return AcademyKinematicsPreview(
        targets = rawTargets.map { it.copy(speedMetersPerSecond = it.speedMetersPerSecond * scale) },
        scaleApplied = scale,
    )
}

/** Interactive ideal-kinematics lab backed by the same ARES math used by robot runtimes. */
@Composable
internal fun KinematicsVectorLabCard(modifier: Modifier = Modifier) {
    var kind by remember { mutableStateOf(AcademyDriveKinematics.MECANUM) }
    var vx by remember { mutableFloatStateOf(1.0f) }
    var vy by remember { mutableFloatStateOf(0.0f) }
    var omega by remember { mutableFloatStateOf(0.0f) }
    val preview = computeAcademyKinematicsPreview(
        kind = kind,
        vxMetersPerSecond = vx.toDouble(),
        vyMetersPerSecond = vy.toDouble(),
        omegaRadiansPerSecond = omega.toDouble(),
        trackWidthMeters = 0.38,
        wheelBaseMeters = 0.38,
        maximumWheelSpeedMetersPerSecond = 3.5,
    )

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = AresSurfaceElevated),
        border = BorderStroke(1.dp, AresBorder),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Robot-frame inverse kinematics", color = AresTextPrimary, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Text(
                "Production ARES kinematics · ideal wheel/module targets only. No traction, voltage, current, steering delay, or mechanism dynamics are modeled.",
                color = AresTextSecondary,
                fontSize = 12.sp,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AcademyDriveKinematics.entries.forEach { option ->
                    FilterChip(selected = kind == option, onClick = { kind = option }, label = { Text(option.label) })
                }
            }
            KinematicsSlider("Forward vx", vx, -3f..3f, "m/s") { vx = it }
            KinematicsSlider("Left vy", vy, -3f..3f, "m/s") { vy = it }
            KinematicsSlider("CCW omega", omega, -5f..5f, "rad/s") { omega = it }
            Surface(
                color = AresBackground,
                border = BorderStroke(1.dp, AresBorder),
                shape = RoundedCornerShape(9.dp),
                modifier = Modifier.fillMaxWidth().height(230.dp),
            ) {
                Canvas(Modifier.fillMaxSize()) {
                    val center = Offset(size.width / 2f, size.height / 2f)
                    val chassisWidth = size.width.coerceAtMost(size.height) * 0.55f
                    drawRect(
                        color = AresBorder,
                        topLeft = Offset(center.x - chassisWidth / 2f, center.y - chassisWidth / 2f),
                        size = Size(chassisWidth, chassisWidth),
                        style = Stroke(width = 2f),
                    )
                    preview.targets.forEach { target ->
                        val x = center.x + (target.yMeters / 0.38 * chassisWidth).toFloat()
                        val y = center.y - (target.xMeters / 0.38 * chassisWidth).toFloat()
                        val direction = if (kind == AcademyDriveKinematics.MECANUM) 0.0 else target.angleRadians
                        val signedLength = (target.speedMetersPerSecond * 22.0).toFloat()
                        drawCircle(AresCyan, radius = 6f, center = Offset(x, y))
                        drawLine(
                            color = if (preview.scaleApplied < 1.0) AresGold else AresGreen,
                            start = Offset(x, y),
                            end = Offset(
                                x + (sin(direction) * signedLength).toFloat(),
                                y - (cos(direction) * signedLength).toFloat(),
                            ),
                            strokeWidth = 3f,
                        )
                    }
                }
            }
            if (preview.scaleApplied < 1.0) {
                Text(
                    "Wheel targets were uniformly scaled to ${(preview.scaleApplied * 100).toInt()}% so none exceeds 3.5 m/s; ratios and intended chassis direction are preserved.",
                    color = AresGold,
                    fontSize = 12.sp,
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                preview.targets.forEach { target ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(target.label, color = AresTextPrimary, fontWeight = FontWeight.Bold)
                        Text(format(target.speedMetersPerSecond, "m/s"), color = AresCyan, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                        if (kind == AcademyDriveKinematics.SWERVE) {
                            Text(format(Math.toDegrees(target.angleRadians), "°"), color = AresTextSecondary, fontSize = 11.sp)
                        }
                    }
                }
            }
            Text(
                if (kind == AcademyDriveKinematics.MECANUM)
                    "Arrows show commanded wheel surface velocity along the wheel rolling direction—not the 45° roller contact-force vectors."
                else
                    "Arrows show ideal module wheel velocity and azimuth before steering-rate and drive-acceleration effects.",
                color = AresTextSecondary,
                fontSize = 11.sp,
            )
        }
    }
}

@Composable
private fun KinematicsSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    unit: String,
    onValueChange: (Float) -> Unit,
) {
    val valueText = format(value.toDouble(), unit)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, color = AresTextSecondary, fontSize = 12.sp)
            Text(valueText, color = AresCyan, fontFamily = FontFamily.Monospace)
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = range,
            modifier = Modifier.semantics {
                contentDescription = label
                stateDescription = valueText
            },
        )
    }
}

private fun format(value: Double, unit: String): String =
    String.format(Locale.ROOT, "%.2f %s", value, unit)
