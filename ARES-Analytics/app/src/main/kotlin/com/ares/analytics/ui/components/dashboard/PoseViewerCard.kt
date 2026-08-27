package com.ares.analytics.ui.components.dashboard

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ares.analytics.service.Nt4ClientService
import com.ares.analytics.service.ReplayFrame
import com.ares.analytics.ui.components.core.CardHeader
import com.ares.analytics.ui.components.core.GlassCard
import com.ares.analytics.ui.components.core.MetricValueBadge
import com.ares.analytics.ui.theme.*
import com.ares.analytics.viewmodel.LivePoseState
import com.ares.analytics.viewmodel.field.FieldPoseFrameAccumulator
import com.ares.analytics.viewmodel.field.isFieldPoseTopic
import com.ares.analytics.viewmodel.field.toReplayPoseState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun PoseViewerCard(
    nt4ClientService: Nt4ClientService,
    currentFrame: ReplayFrame? = null,
    modifier: Modifier = Modifier
) {
    val poseAccumulator = remember(nt4ClientService) { FieldPoseFrameAccumulator() }
    var pose by remember(nt4ClientService) { mutableStateOf(LivePoseState()) }
    var visionX by remember(nt4ClientService) { mutableStateOf<Double?>(null) }
    var visionY by remember(nt4ClientService) { mutableStateOf<Double?>(null) }
    var visionHeading by remember(nt4ClientService) { mutableStateOf<Double?>(null) }
    var visionHasTarget by remember(nt4ClientService) { mutableStateOf<Boolean?>(null) }
    var connected by remember(nt4ClientService) { mutableStateOf(nt4ClientService.isConnected.value) }
    var replayActive by remember(nt4ClientService) { mutableStateOf(nt4ClientService.isReplayActive.value) }
    var lastUpdateMs by remember(nt4ClientService) { mutableStateOf<Long?>(null) }
    var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }

    LaunchedEffect(nt4ClientService) {
        launch {
            nt4ClientService.isConnected.collect { isConnected ->
                connected = isConnected
                if (!isConnected) {
                    poseAccumulator.reset()
                    pose = LivePoseState()
                    visionX = null
                    visionY = null
                    visionHeading = null
                    visionHasTarget = null
                    lastUpdateMs = null
                }
            }
        }
        launch {
            nt4ClientService.isReplayActive.collect { isActive ->
                replayActive = isActive
                poseAccumulator.reset()
                pose = LivePoseState(isConnected = connected)
                visionX = null
                visionY = null
                visionHeading = null
                visionHasTarget = null
                lastUpdateMs = null
            }
        }
        launch {
            nt4ClientService.simulatorPoseFrame.collect { frame ->
                if (frame != null && connected && !replayActive) {
                    poseAccumulator.accept(frame)
                    pose = poseAccumulator.snapshot(pose)
                    lastUpdateMs = System.currentTimeMillis()
                }
            }
        }
        launch {
            nt4ClientService.uiTelemetryFlow.collect { frame ->
                when {
                    isFieldPoseTopic(frame.key) -> {
                        if (poseAccumulator.accept(frame.key, frame.value)) {
                            pose = poseAccumulator.snapshot(pose)
                        }
                    }
                    frame.key == "Vision/HasTarget" -> {
                        visionHasTarget = frame.value > 0.5
                        if (visionHasTarget == false) {
                            visionX = null
                            visionY = null
                            visionHeading = null
                        }
                    }
                    frame.key == "Vision/Pose_X" && visionHasTarget != false -> visionX = frame.value
                    frame.key == "Vision/Pose_Y" && visionHasTarget != false -> visionY = frame.value
                    frame.key == "Vision/Pose_Heading" && visionHasTarget != false -> visionHeading = frame.value
                }

                // Refresh on every received sample, even when a stationary robot repeatedly
                // publishes the same numeric value and Compose suppresses equal state writes.
                if (isPoseViewerTelemetryTopic(frame.key)) {
                    lastUpdateMs = System.currentTimeMillis()
                }
            }
        }
    }
    LaunchedEffect(Unit) {
        while (true) {
            nowMs = System.currentTimeMillis()
            delay(250)
        }
    }

    val displayedPose = remember(currentFrame?.sequence, pose) {
        currentFrame?.toReplayPoseState() ?: pose
    }
    val displayedVisionX = if (currentFrame != null) displayedPose.visionX else visionX
    val displayedVisionY = if (currentFrame != null) displayedPose.visionY else visionY
    val displayedVisionHeading = if (currentFrame != null) displayedPose.visionHeading else visionHeading
    val elapsed = lastUpdateMs?.let { nowMs - it }
    val (statusText, statusColor) = when {
        currentFrame != null -> "Replay" to AresCyan
        !connected && !replayActive -> "Offline" to AresError
        elapsed == null -> "No Data" to AresTextTertiary
        elapsed < 500 -> "Active" to AresGreen
        elapsed < 2000 -> "Stale" to AresAmber
        else -> "Offline" to AresError
    }

    GlassCard(
        modifier = modifier
    ) {
        CardHeader(
            title = "Robot Pose Telemetry",
            icon = Icons.Default.MyLocation,
            iconTint = AresCyan,
            statusText = statusText,
            statusColor = statusColor
        )

        Column(
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.weight(1f)
        ) {
            PoseRow(
                "True (Actual)",
                displayedPose.trueX.takeIf { displayedPose.hasTruePoseData },
                displayedPose.trueY.takeIf { displayedPose.hasTruePoseData },
                displayedPose.trueHeading.takeIf { displayedPose.hasTruePoseData },
                AresCyan
            )
            HorizontalDivider(color = AresBorder.copy(alpha = 0.5f), thickness = 0.5.dp)
            PoseRow("Estimated (EKF)", displayedPose.ekfX, displayedPose.ekfY, displayedPose.ekfHeading, AresAmber)
            HorizontalDivider(color = AresBorder.copy(alpha = 0.5f), thickness = 0.5.dp)
            PoseRow("Pinpoint (Odom)", displayedPose.odomX, displayedPose.odomY, displayedPose.odomHeading, AresGreen)
            HorizontalDivider(color = AresBorder.copy(alpha = 0.5f), thickness = 0.5.dp)
            PoseRow("Vision (Limelight)", displayedVisionX, displayedVisionY, displayedVisionHeading, AresGold)
        }
    }
}

private val POSE_STATUS_TOPICS = setOf(
    "ARES/TruePose/0",
    "ARES/TruePose/1",
    "ARES/TruePose/2",
    "ARES/EstimatedPose/0",
    "ARES/EstimatedPose/1",
    "ARES/EstimatedPose/2",
    "Drive/Pose_X",
    "Drive/Pose_Y",
    "Drive/Pose_Heading",
    "Drive/Drive_Heading",
    "Drive/Odom_X",
    "Drive/Odom_Y",
    "Drive/Odom_Heading",
    "Vision/HasTarget",
    "Vision/Pose_X",
    "Vision/Pose_Y",
    "Vision/Pose_Heading"
)

internal fun isPoseViewerTelemetryTopic(key: String): Boolean =
    key in POSE_STATUS_TOPICS || key.startsWith("ARES/SimulatorPoseFrame/")

@Composable
private fun PoseRow(
    title: String,
    x: Double?,
    y: Double?,
    heading: Double?,
    color: Color
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text = title,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = AresTextSecondary
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            MetricValueBadge(
                label = "X",
                value = x?.let { String.format("%.3f", it) } ?: "---",
                unit = "m",
                statusColor = if (x != null) color else AresTextTertiary,
                modifier = Modifier.weight(1f)
            )
            MetricValueBadge(
                label = "Y",
                value = y?.let { String.format("%.3f", it) } ?: "---",
                unit = "m",
                statusColor = if (y != null) color else AresTextTertiary,
                modifier = Modifier.weight(1f)
            )
            MetricValueBadge(
                label = "Heading",
                value = heading?.let { String.format("%.3f", Math.toDegrees(it)) } ?: "---",
                unit = "°",
                statusColor = if (heading != null) color else AresTextTertiary,
                modifier = Modifier.weight(1f)
            )
        }
    }
}
