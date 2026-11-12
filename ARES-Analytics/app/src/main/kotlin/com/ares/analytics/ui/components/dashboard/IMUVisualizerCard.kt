package com.ares.analytics.ui.components.dashboard

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CompassCalibration
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ares.analytics.service.Nt4ClientService
import com.ares.analytics.service.ReplayFrame
import com.ares.analytics.ui.components.core.*
import com.ares.analytics.ui.theme.*
import kotlin.math.abs

internal data class ImuAttitudeSnapshot(
    val rollRad: Double? = null,
    val pitchRad: Double? = null,
    val yawRad: Double? = null,
)

internal fun ImuAttitudeSnapshot.acceptImuKey(rawKey: String, value: Double): ImuAttitudeSnapshot {
    if (!value.isFinite()) return this
    val key = rawKey.trim().trimStart('/')
    return when {
        key.endsWith("IMU/Roll", ignoreCase = true) || key.endsWith("Robot/Roll", ignoreCase = true) ->
            copy(rollRad = value)
        key.endsWith("IMU/Pitch", ignoreCase = true) || key.endsWith("Robot/Pitch", ignoreCase = true) ->
            copy(pitchRad = value)
        key.endsWith("IMU/Yaw", ignoreCase = true) ||
            key.endsWith("Robot/Yaw", ignoreCase = true) ||
            key.endsWith("Drive/Pose_Heading", ignoreCase = true) ||
            key == "ARES/EstimatedPose/2" -> copy(yawRad = value)
        else -> this
    }
}

internal fun extractReplayImuAttitude(frame: ReplayFrame): ImuAttitudeSnapshot {
    var snapshot = ImuAttitudeSnapshot()
    for ((key, value) in frame.values) {
        if (key in frame.stringValues) continue
        snapshot = snapshot.acceptImuKey(key, value)
    }
    return snapshot
}

@Composable
fun IMUVisualizerCard(
    nt4ClientService: Nt4ClientService,
    modifier: Modifier = Modifier,
    replayFrame: ReplayFrame? = null,
) {
    var liveSnapshot by remember(nt4ClientService) { mutableStateOf(ImuAttitudeSnapshot()) }

    LaunchedEffect(nt4ClientService, replayFrame != null) {
        if (replayFrame != null) return@LaunchedEffect
        nt4ClientService.uiTelemetryFlow.collect { frame ->
            liveSnapshot = liveSnapshot.acceptImuKey(frame.key, frame.value)
        }
    }

    val attitude = remember(replayFrame?.sequence, liveSnapshot) {
        replayFrame?.let(::extractReplayImuAttitude) ?: liveSnapshot
    }
    val roll = attitude.rollRad
    val pitch = attitude.pitchRad
    val yaw = attitude.yawRad

    AresCard(
        modifier = modifier.fillMaxWidth(),
        backgroundColor = AresSurfaceElevated
    ) {
        CardHeader(
            title = if (replayFrame != null) "IMU Orientation · Replay" else "IMU Orientation",
            icon = Icons.Default.CompassCalibration,
            iconTint = AresGold
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("ROLL", color = AresTextTertiary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                val rColor = if (roll?.let { abs(Math.toDegrees(it)) > 15.0 } == true) AresError else AresTextPrimary
                Text(
                    text = roll?.let { String.format("%.1f°", Math.toDegrees(it)) } ?: "--",
                    color = rColor,
                    fontSize = 18.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
            }

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("PITCH", color = AresTextTertiary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                val pColor = if (pitch?.let { abs(Math.toDegrees(it)) > 15.0 } == true) AresError else AresTextPrimary
                Text(
                    text = pitch?.let { String.format("%.1f°", Math.toDegrees(it)) } ?: "--",
                    color = pColor,
                    fontSize = 18.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
            }

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("YAW (HEADING)", color = AresTextTertiary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                Text(
                    text = yaw?.let { String.format("%.1f°", Math.toDegrees(it)) } ?: "--",
                    color = AresCyan,
                    fontSize = 18.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(100.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(AresBackground)
                .border(1.dp, AresBorder, RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.size(80.dp)) {
                val cx = size.width / 2f
                val cy = size.height / 2f
                val radius = size.width / 2f - 4f

                drawCircle(color = AresSurface, radius = radius, center = Offset(cx, cy))
                drawCircle(color = AresBorder, radius = radius, center = Offset(cx, cy), style = Stroke(width = 2f))

                yaw?.let { yawRadians ->
                    val yawDeg = Math.toDegrees(yawRadians).toFloat()
                    rotate(degrees = -yawDeg, pivot = Offset(cx, cy)) {
                        val arrowW = 20f
                        val arrowH = 40f
                        drawRect(
                            color = AresCyan,
                            topLeft = Offset(cx - arrowW / 2, cy - arrowH / 2),
                            size = Size(arrowW, arrowH)
                        )
                        drawRect(
                            color = AresGold,
                            topLeft = Offset(cx - arrowW / 2, cy - arrowH / 2),
                            size = Size(arrowW, arrowH * 0.3f)
                        )
                    }
                } ?: run {
                    drawCircle(color = AresTextTertiary, radius = 4f, center = Offset(cx, cy))
                }
            }
        }
    }
}
