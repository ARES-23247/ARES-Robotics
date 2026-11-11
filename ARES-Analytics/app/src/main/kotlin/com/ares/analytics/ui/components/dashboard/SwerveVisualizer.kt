package com.ares.analytics.ui.components.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ares.analytics.service.Nt4ClientService
import com.ares.analytics.service.ReplayFrame
import com.ares.analytics.ui.canvas.drawVectorArrow
import com.ares.analytics.ui.theme.*
import com.ares.analytics.ui.theme.AresBackground
import com.ares.analytics.ui.theme.AresBorder
import com.ares.analytics.ui.theme.AresCyan
import com.ares.analytics.ui.theme.AresGold
import com.ares.analytics.ui.theme.AresSurfaceElevated
import com.ares.analytics.ui.theme.AresTextSecondary
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.launch

internal data class SwerveModuleVector(
    val angleDeg: Double,
    val speedMps: Double,
)

internal fun extractSwerveModuleVectors(values: Map<String, Double>): List<SwerveModuleVector> {
    val corners = listOf(0 to "FL", 1 to "FR", 2 to "BL", 3 to "BR")
    return corners.map { (idx, code) ->
        val angle = values["Swerve/ModuleAnglesActual/$idx"]
            ?: values["Swerve/Module$idx/AngleDeg"]
            ?: values["Drive/Swerve/Angle_$code"]
            ?: values["Drive/Swerve/${code}_Angle"]
            ?: values["Drive/Swerve/$code/Angle"]
            ?: 0.0
        val speed = values["Swerve/ModuleSpeedsActual/$idx"]
            ?: values["Swerve/Module$idx/SpeedMps"]
            ?: values["Drive/Swerve/Vel_$code"]
            ?: values["Drive/Swerve/${code}_Vel"]
            ?: values["Drive/Swerve/$code/Speed"]
            ?: 0.0
        SwerveModuleVector(angleDeg = angle, speedMps = speed)
    }
}

@Composable
fun SwerveVisualizer(
    currentFrame: ReplayFrame?,
    modifier: Modifier = Modifier,
) {
    val vectors = extractSwerveModuleVectors(currentFrame?.values ?: emptyMap())

    SwerveChassisVectorView(
        speedsTarget = listOf(0.0, 0.0, 0.0, 0.0),
        anglesTarget = listOf(0.0, 0.0, 0.0, 0.0),
        speedsActual = vectors.map { it.speedMps },
        anglesActual = vectors.map { it.angleDeg },
        modifier = modifier,
    )
}

@Composable
internal fun SwerveChassisVectorView(
    speedsTarget: List<Double>,
    anglesTarget: List<Double>,
    speedsActual: List<Double>,
    anglesActual: List<Double>,
    modifier: Modifier = Modifier,
) {
    val angFl = anglesActual.getOrElse(0) { 0.0 }
    val velFl = speedsActual.getOrElse(0) { 0.0 }
    val angFr = anglesActual.getOrElse(1) { 0.0 }
    val velFr = speedsActual.getOrElse(1) { 0.0 }
    val angBl = anglesActual.getOrElse(2) { 0.0 }
    val velBl = speedsActual.getOrElse(2) { 0.0 }
    val angBr = anglesActual.getOrElse(3) { 0.0 }
    val velBr = speedsActual.getOrElse(3) { 0.0 }

    Column(
        modifier = modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(AresBackground, RoundedCornerShape(8.dp))
                .border(1.dp, AresBorder, RoundedCornerShape(8.dp)),
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val cx = size.width / 2f
                val cy = size.height / 2f
                val robotSide = (minOf(size.width, size.height) * 0.54f).coerceIn(80f, 220f)
                val dashEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f), 0f)

                drawRect(
                    color = AresBorder,
                    topLeft = Offset(cx - robotSide / 2f, cy - robotSide / 2f),
                    size = Size(robotSide, robotSide),
                    style = Stroke(width = 2f, pathEffect = dashEffect),
                )

                val corners = listOf(
                    Offset(cx - robotSide / 2f, cy - robotSide / 2f), // 0: FL
                    Offset(cx + robotSide / 2f, cy - robotSide / 2f), // 1: FR
                    Offset(cx - robotSide / 2f, cy + robotSide / 2f), // 2: BL
                    Offset(cx + robotSide / 2f, cy + robotSide / 2f), // 3: BR
                )

                corners.forEachIndexed { idx, center ->
                    val actualAngle = toRadiansIfDegrees(anglesActual.getOrElse(idx) { 0.0 })
                    val actualSpeed = speedsActual.getOrElse(idx) { 0.0 }
                    val targetAngle = toRadiansIfDegrees(anglesTarget.getOrElse(idx) { 0.0 })
                    val targetSpeed = speedsTarget.getOrElse(idx) { 0.0 }

                    drawCircle(color = AresSurfaceElevated, radius = 20f, center = center)
                    drawCircle(color = AresBorder, radius = 20f, center = center, style = Stroke(width = 2f))

                    val maxArrowLen = 52f
                    val targetLen = (targetSpeed * 12f).toFloat().coerceIn(-maxArrowLen, maxArrowLen)
                    if (abs(targetLen) > 3f) {
                        val targetSteer = -targetAngle - Math.PI / 2.0
                        drawLine(
                            color = AresGold,
                            start = center,
                            end = Offset(
                                center.x + targetLen * cos(targetSteer).toFloat(),
                                center.y + targetLen * sin(targetSteer).toFloat(),
                            ),
                            strokeWidth = 2f,
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(5f, 5f), 0f),
                            cap = StrokeCap.Round,
                        )
                    }

                    val actualLen = (actualSpeed * 12f).toFloat().coerceIn(-maxArrowLen, maxArrowLen)
                    if (abs(actualLen) > 3f) {
                        val actualSteer = -actualAngle - Math.PI / 2.0
                        drawVectorArrow(
                            start = center,
                            end = Offset(
                                center.x + actualLen * cos(actualSteer).toFloat(),
                                center.y + actualLen * sin(actualSteer).toFloat(),
                            ),
                            color = AresCyan,
                            strokeWidth = 3.2f,
                            arrowHeadLength = 9f,
                        )
                    }
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(horizontalAlignment = Alignment.Start) {
                Text("FL: ${formatDeg(angFl)}° | ${"%.1f".format(velFl)} m/s", color = AresTextSecondary, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                Text("BL: ${formatDeg(angBl)}° | ${"%.1f".format(velBl)} m/s", color = AresTextSecondary, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("FR: ${formatDeg(angFr)}° | ${"%.1f".format(velFr)} m/s", color = AresTextSecondary, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                Text("BR: ${formatDeg(angBr)}° | ${"%.1f".format(velBr)} m/s", color = AresTextSecondary, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
            }
        }
    }
}

private fun toRadiansIfDegrees(angle: Double): Double =
    if (abs(angle) > 2 * Math.PI) Math.toRadians(angle) else angle

private fun formatDeg(angle: Double): String {
    val deg = if (abs(angle) > 2 * Math.PI) angle else Math.toDegrees(angle)
    return "%.0f".format(deg % 360.0)
}
