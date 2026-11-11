package com.ares.analytics.ui.components.dashboard

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
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

@Composable
fun SwerveModuleVisualizer(
    nt4ClientService: Nt4ClientService,
    currentFrame: ReplayFrame? = null,
    modifier: Modifier = Modifier
) {
    // 0: FL, 1: FR, 2: BL, 3: BR
    val speedsTarget = remember { mutableStateListOf(0.0, 0.0, 0.0, 0.0) }
    val anglesTarget = remember { mutableStateListOf(0.0, 0.0, 0.0, 0.0) }
    val speedsActual = remember { mutableStateListOf(0.0, 0.0, 0.0, 0.0) }
    val anglesActual = remember { mutableStateListOf(0.0, 0.0, 0.0, 0.0) }
    var showChassis2dView by remember { mutableStateOf(false) }
    val replayHasSwerveTelemetry = currentFrame?.values?.keys?.any {
        it.startsWith("Swerve/Module") || it.startsWith("Drive/Swerve/")
    } ?: false

    SideEffect {
        if (currentFrame != null) {
            val cornerCodes = listOf("FL", "FR", "BL", "BR")
            for (index in 0..3) {
                val corner = cornerCodes[index]
                speedsTarget[index] = currentFrame.values["Swerve/ModuleSpeedsTarget/$index"] ?: 0.0
                anglesTarget[index] = currentFrame.values["Swerve/ModuleAnglesTarget/$index"] ?: 0.0
                speedsActual[index] = currentFrame.values["Swerve/ModuleSpeedsActual/$index"]
                    ?: currentFrame.values["Drive/Swerve/Vel_$corner"]
                    ?: currentFrame.values["Drive/Swerve/${corner}_Vel"]
                    ?: 0.0
                anglesActual[index] = currentFrame.values["Swerve/ModuleAnglesActual/$index"]
                    ?: currentFrame.values["Drive/Swerve/Angle_$corner"]
                    ?: currentFrame.values["Drive/Swerve/${corner}_Angle"]
                    ?: 0.0
            }
        }
    }

    LaunchedEffect(nt4ClientService, currentFrame == null) {
        if (currentFrame != null) return@LaunchedEffect
        nt4ClientService.uiTelemetryFlow.collect { frame ->
            val key = frame.key.trimStart('/')
            val value = frame.value
            when {
                key.startsWith("Swerve/ModuleSpeedsTarget/") -> {
                    val idx = key.substringAfterLast("/").toIntOrNull()
                    if (idx != null && idx in 0..3) speedsTarget[idx] = value
                }
                key.startsWith("Swerve/ModuleAnglesTarget/") -> {
                    val idx = key.substringAfterLast("/").toIntOrNull()
                    if (idx != null && idx in 0..3) anglesTarget[idx] = value
                }
                key.startsWith("Swerve/ModuleSpeedsActual/") -> {
                    val idx = key.substringAfterLast("/").toIntOrNull()
                    if (idx != null && idx in 0..3) speedsActual[idx] = value
                }
                key.startsWith("Swerve/ModuleAnglesActual/") -> {
                    val idx = key.substringAfterLast("/").toIntOrNull()
                    if (idx != null && idx in 0..3) anglesActual[idx] = value
                }
                key == "Drive/Swerve/Vel_FL" || key == "Drive/Swerve/FL_Vel" -> speedsActual[0] = value
                key == "Drive/Swerve/Vel_FR" || key == "Drive/Swerve/FR_Vel" -> speedsActual[1] = value
                key == "Drive/Swerve/Vel_BL" || key == "Drive/Swerve/BL_Vel" -> speedsActual[2] = value
                key == "Drive/Swerve/Vel_BR" || key == "Drive/Swerve/BR_Vel" -> speedsActual[3] = value
                key == "Drive/Swerve/Angle_FL" || key == "Drive/Swerve/FL_Angle" -> anglesActual[0] = value
                key == "Drive/Swerve/Angle_FR" || key == "Drive/Swerve/FR_Angle" -> anglesActual[1] = value
                key == "Drive/Swerve/Angle_BL" || key == "Drive/Swerve/BL_Angle" -> anglesActual[2] = value
                key == "Drive/Swerve/Angle_BR" || key == "Drive/Swerve/BR_Angle" -> anglesActual[3] = value
            }
        }
    }

    Column(
        modifier = modifier
            .background(AresSurface, RoundedCornerShape(12.dp))
            .border(1.dp, AresBorder, RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Swerve Module Vectors",
                color = AresTextPrimary,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                androidx.compose.material3.TextButton(
                    onClick = { showChassis2dView = !showChassis2dView },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    modifier = Modifier.height(24.dp),
                ) {
                    Text(
                        if (showChassis2dView) "View: 4-Corner Cards" else "View: Chassis 2D",
                        color = AresCyan,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                val isConnected by nt4ClientService.isConnected.collectAsState()
                Text(
                    when {
                        currentFrame != null -> "Replay"
                        isConnected -> "Live Connected"
                        else -> "Live Disconnected"
                    },
                    color = if (currentFrame != null || isConnected) AresGreen else AresTextTertiary,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        HorizontalDivider(color = AresBorder, thickness = 1.dp)

        if (currentFrame != null && !replayHasSwerveTelemetry) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(
                    "No swerve-module topics in this recording — values are missing, not zero.",
                    color = AresTextTertiary,
                    fontSize = 11.sp,
                )
            }
        } else if (showChassis2dView) {
            SwerveChassisVectorView(
                speedsTarget = speedsTarget,
                anglesTarget = anglesTarget,
                speedsActual = speedsActual,
                anglesActual = anglesActual,
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )
        } else {
            Row(modifier = Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(modifier = Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ModuleCard("Front Left (FL)", speedsTarget[0], anglesTarget[0], speedsActual[0], anglesActual[0], Modifier.weight(1f))
                    ModuleCard("Back Left (BL)", speedsTarget[2], anglesTarget[2], speedsActual[2], anglesActual[2], Modifier.weight(1f))
                }
                Column(modifier = Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ModuleCard("Front Right (FR)", speedsTarget[1], anglesTarget[1], speedsActual[1], anglesActual[1], Modifier.weight(1f))
                    ModuleCard("Back Right (BR)", speedsTarget[3], anglesTarget[3], speedsActual[3], anglesActual[3], Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun ModuleCard(
    name: String,
    speedTarget: Double,
    angleTarget: Double,
    speedActual: Double,
    angleActual: Double,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .background(AresSurfaceElevated, RoundedCornerShape(8.dp))
            .border(1.dp, AresBorder, RoundedCornerShape(8.dp))
            .padding(8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Dial Canvas
        Canvas(modifier = Modifier.size(56.dp)) {
            val cx = size.width / 2f
            val cy = size.height / 2f
            val r = size.width / 2f - 4f

            // Draw outer dial boundary
            drawCircle(
                color = AresBorder,
                radius = r,
                center = Offset(cx, cy),
                style = Stroke(width = 1.5f)
            )

            // Draw target vector line (dashed gold)
            val targetLength = r * (abs(speedTarget) / 4.0).coerceIn(0.0, 1.0).toFloat()
            val targetSteer = -angleTarget - Math.PI / 2.0
            val tx = cx + targetLength * cos(targetSteer).toFloat()
            val ty = cy + targetLength * sin(targetSteer).toFloat()
            val dashEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 4f), 0f)
            drawLine(
                color = AresGold,
                start = Offset(cx, cy),
                end = Offset(tx, ty),
                strokeWidth = 2f,
                pathEffect = dashEffect,
                cap = StrokeCap.Round
            )

            // Draw actual vector line (solid cyan)
            val actualLength = r * (abs(speedActual) / 4.0).coerceIn(0.0, 1.0).toFloat()
            val actualSteer = -angleActual - Math.PI / 2.0
            val ax = cx + actualLength * cos(actualSteer).toFloat()
            val ay = cy + actualLength * sin(actualSteer).toFloat()
            drawLine(
                color = AresCyan,
                start = Offset(cx, cy),
                end = Offset(ax, ay),
                strokeWidth = 3f,
                cap = StrokeCap.Round
            )
        }

        // Details column
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(name, color = AresTextPrimary, fontSize = 11.sp, fontWeight = FontWeight.Bold)

            // Speed visualization progress bar
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .background(AresBorder, RoundedCornerShape(3.dp))
            ) {
                // Target speed outline bar
                Box(
                    modifier = Modifier
                        .fillMaxWidth((abs(speedTarget) / 4.0).coerceIn(0.0, 1.0).toFloat())
                        .fillMaxHeight()
                        .border(0.5.dp, AresGold, RoundedCornerShape(3.dp))
                )
                // Actual speed filled bar
                Box(
                    modifier = Modifier
                        .fillMaxWidth((abs(speedActual) / 4.0).coerceIn(0.0, 1.0).toFloat())
                        .fillMaxHeight()
                        .background(AresCyan, RoundedCornerShape(3.dp))
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    "Tgt: ${"%.2f".format(speedTarget)} m/s @ ${"%.0f".format(Math.toDegrees(angleTarget) % 360)}°",
                    color = AresGold,
                    fontSize = 8.sp,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                )
                Text(
                    "Act: ${"%.2f".format(speedActual)} m/s @ ${"%.0f".format(Math.toDegrees(angleActual) % 360)}°",
                    color = AresCyan,
                    fontSize = 8.sp,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                )
            }
        }
    }
}
