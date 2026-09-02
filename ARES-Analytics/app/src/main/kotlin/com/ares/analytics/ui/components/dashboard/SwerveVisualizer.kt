package com.ares.analytics.ui.components.dashboard

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ares.analytics.service.ReplayFrame
import com.ares.analytics.ui.canvas.drawVectorArrow
import com.ares.analytics.ui.theme.*
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun SwerveVisualizer(
    currentFrame: ReplayFrame?,
    modifier: Modifier = Modifier
) {
    // Front Left Module
    val angFl = currentFrame?.values?.get("Drive/Swerve/Angle_FL")
        ?: currentFrame?.values?.get("Drive/Swerve/FL_Angle") ?: 0.0
    val velFl = currentFrame?.values?.get("Drive/Swerve/Vel_FL")
        ?: currentFrame?.values?.get("Drive/Swerve/FL_Vel") ?: 0.0

    // Front Right Module
    val angFr = currentFrame?.values?.get("Drive/Swerve/Angle_FR")
        ?: currentFrame?.values?.get("Drive/Swerve/FR_Angle") ?: 0.0
    val velFr = currentFrame?.values?.get("Drive/Swerve/Vel_FR")
        ?: currentFrame?.values?.get("Drive/Swerve/FR_Vel") ?: 0.0

    // Back Left Module
    val angBl = currentFrame?.values?.get("Drive/Swerve/Angle_BL")
        ?: currentFrame?.values?.get("Drive/Swerve/BL_Angle") ?: 0.0
    val velBl = currentFrame?.values?.get("Drive/Swerve/Vel_BL")
        ?: currentFrame?.values?.get("Drive/Swerve/BL_Vel") ?: 0.0

    // Back Right Module
    val angBr = currentFrame?.values?.get("Drive/Swerve/Angle_BR")
        ?: currentFrame?.values?.get("Drive/Swerve/BR_Angle") ?: 0.0
    val velBr = currentFrame?.values?.get("Drive/Swerve/Vel_BR")
        ?: currentFrame?.values?.get("Drive/Swerve/BR_Vel") ?: 0.0

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(AresSurface)
            .border(1.dp, AresBorder, RoundedCornerShape(12.dp))
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            "Swerve Module Vector Dashboard",
            color = AresTextPrimary,
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.titleMedium
        )

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(AresBackground, RoundedCornerShape(8.dp))
                .border(1.dp, AresBorder, RoundedCornerShape(8.dp))
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val cx = size.width / 2f
                val cy = size.height / 2f

                // Draw robot boundary outline (dashed)
                val robotW = 200f
                val robotH = 200f
                val dashEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f), 0f)
                drawRect(
                    color = AresBorder,
                    topLeft = Offset(cx - robotW / 2f, cy - robotH / 2f),
                    size = Size(robotW, robotH),
                    style = Stroke(width = 2f, pathEffect = dashEffect)
                )

                // Module Offsets
                val moduleOffsets = listOf(
                    Triple("FL", Offset(cx - robotW / 2f, cy - robotH / 2f), Pair(angFl, velFl)),
                    Triple("FR", Offset(cx + robotW / 2f, cy - robotH / 2f), Pair(angFr, velFr)),
                    Triple("BL", Offset(cx - robotW / 2f, cy + robotH / 2f), Pair(angBl, velBl)),
                    Triple("BR", Offset(cx + robotW / 2f, cy + robotH / 2f), Pair(angBr, velBr))
                )

                for ((name, center, state) in moduleOffsets) {
                    val angle = state.first
                    val velocity = state.second

                    // Angle translation: support radians vs degrees
                    val angleRad = if (Math.abs(angle) > 2 * Math.PI) Math.toRadians(angle) else angle

                    // Draw module circle
                    drawCircle(
                        color = AresSurfaceElevated,
                        radius = 24f,
                        center = center
                    )
                    drawCircle(
                        color = AresBorder,
                        radius = 24f,
                        center = center,
                        style = Stroke(width = 2f)
                    )

                    // Draw velocity vector arrow
                    val maxArrowLen = 60f
                    val arrowLen = (velocity * 12f).toFloat().coerceIn(-maxArrowLen, maxArrowLen)

                    // Steer vector line (adjusted by -90 degrees to align with CCW field coordinates)
                    val steerAngle = -angleRad - Math.PI / 2.0
                    val endX = center.x + arrowLen * cos(steerAngle).toFloat()
                    val endY = center.y + arrowLen * sin(steerAngle).toFloat()

                    if (Math.abs(arrowLen) > 5f) {
                        drawVectorArrow(
                            start = center,
                            end = Offset(endX, endY),
                            color = AresCyan,
                            strokeWidth = 3.5f,
                            arrowHeadLength = 10f
                        )
                    }
                }
            }
        }

        // Details Panel
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(horizontalAlignment = Alignment.Start) {
                Text("FL: ${"%.1f".format(angFl)}° | ${"%.1f".format(velFl)}m/s", color = AresTextSecondary, fontSize = 11.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                Text("BL: ${"%.1f".format(angBl)}° | ${"%.1f".format(velBl)}m/s", color = AresTextSecondary, fontSize = 11.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("FR: ${"%.1f".format(angFr)}° | ${"%.1f".format(velFr)}m/s", color = AresTextSecondary, fontSize = 11.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                Text("BR: ${"%.1f".format(angBr)}° | ${"%.1f".format(velBr)}m/s", color = AresTextSecondary, fontSize = 11.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
            }
        }
    }
}
