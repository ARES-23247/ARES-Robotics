package com.ares.analytics.ui.components.dashboard

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ares.analytics.service.ReplayEngineService
import com.ares.analytics.service.ReplayState
import com.ares.analytics.service.VideoSyncService
import com.ares.analytics.ui.components.core.AresEmptyState
import com.ares.analytics.ui.theme.*
import kotlinx.coroutines.launch
import com.ares.analytics.ui.util.DesktopFileChoosers
import kotlin.math.sin

@Composable
fun VideoPlayerPanel(
    videoSyncService: VideoSyncService,
    replayEngineService: ReplayEngineService,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    val videoFile by videoSyncService.videoFile.collectAsState()
    val videoDurationMs by videoSyncService.videoDurationMs.collectAsState()
    val currentVideoTimeMs by videoSyncService.currentVideoTimeMs.collectAsState()
    val logOffsetMs by videoSyncService.logOffsetMs.collectAsState()
    val replayState by replayEngineService.state.collectAsState()
    val currentFrame by replayEngineService.currentFrame.collectAsState()

    // Calculate formatted times
    fun formatTime(ms: Long): String {
        val totalSecs = ms / 1000
        val minutes = totalSecs / 60
        val seconds = totalSecs % 60
        val millis = ms % 1000
        return String.format("%02d:%02d.%03d", minutes, seconds, millis)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(12.dp))
            .background(AresSurface)
            .border(1.dp, AresBorder, RoundedCornerShape(12.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Top Title / Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(Icons.Default.Videocam, null, tint = AresCyan)
                Text("MATCH VIDEO SYNCHRONIZATION", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = AresCyan)
            }

            // Load Video Button
            Button(
                onClick = {
                    DesktopFileChoosers.chooseOpenFile(
                        title = "Select Match Video",
                        filterDescription = "Video Files",
                        extensions = listOf("mp4", "mkv", "avi", "mov")
                    )?.let { file ->
                        videoSyncService.loadVideo(file)
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = AresBorder),
                shape = RoundedCornerShape(6.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                modifier = Modifier.height(32.dp)
            ) {
                Icon(Icons.Default.FolderOpen, null, tint = AresTextPrimary, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Load Video", color = AresTextPrimary, fontSize = 11.sp)
            }
        }

        // Main Video Feed Viewport
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(AresBackground, RoundedCornerShape(8.dp))
                .border(1.dp, AresBorder, RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center
        ) {
            if (videoFile == null) {
                AresEmptyState(
                    title = "No video loaded",
                    description = "Click 'Load Video' to synchronize local mp4/mov match footage",
                    icon = Icons.Default.CloudUpload,
                    iconSize = 48.dp,
                )
            } else {
                // Premium simulated video frame drawing telemetry overlay
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val width = size.width
                    val height = size.height

                    // Simulated Camera Static / Scanlines based on timestamp
                    val t = currentVideoTimeMs.toDouble() / 1000.0
                    val isPlaying = replayState == ReplayState.PLAYING

                    // Draw outer border / lens bounds
                    drawRect(
                        color = AresBorder,
                        topLeft = Offset.Zero,
                        size = size,
                        style = Stroke(width = 1f)
                    )

                    // Draw Camera Crosshair
                    val cx = width / 2
                    val cy = height / 2
                    drawLine(AresGlassBorder, Offset(cx - 30, cy), Offset(cx + 30, cy), 1.5f)
                    drawLine(AresGlassBorder, Offset(cx, cy - 30), Offset(cx, cy + 30), 1.5f)
                    drawCircle(
                        color = AresGlassBorder,
                        radius = 20f,
                        center = Offset(cx, cy),
                        style = Stroke(width = 1.5f)
                    )

                    // Draw Viewfinder Grid lines
                    drawLine(AresGlass, Offset(width * 0.25f, 0f), Offset(width * 0.25f, height), 0.5f)
                    drawLine(AresGlass, Offset(width * 0.75f, 0f), Offset(width * 0.75f, height), 0.5f)
                    drawLine(AresGlass, Offset(0f, height * 0.25f), Offset(width, height * 0.25f), 0.5f)
                    drawLine(AresGlass, Offset(0f, height * 0.75f), Offset(width, height * 0.75f), 0.5f)

                    // Draw simulated robot telemetry movement on canvas
                    val poseX = currentFrame?.values?.get("Drive/Pose_X") ?: 0.0
                    val poseY = currentFrame?.values?.get("Drive/Pose_Y") ?: 0.0
                    val heading = currentFrame?.values?.get("Drive/Pose_Heading")
                        ?: currentFrame?.values?.get("Drive/Pose_Heading")
                        ?: currentFrame?.values?.get("ARES/EstimatedPose/2")
                        ?: 0.0

                    // Draw a visual representation of the robot moving
                    val rx = cx + (poseX * 80).toFloat()
                    val ry = cy - (poseY * 80).toFloat()

                    if (rx in 0f..width && ry in 0f..height) {
                        drawCircle(
                            color = AresCyanGlow,
                            radius = 24f,
                            center = Offset(rx, ry)
                        )
                        drawCircle(
                            color = AresCyan,
                            radius = 4f,
                            center = Offset(rx, ry)
                        )
                        // Heading indicator line
                        val dx = (kotlin.math.cos(heading) * 24).toFloat()
                        val dy = -(sin(heading) * 24).toFloat()
                        drawLine(
                            color = AresCyan,
                            start = Offset(rx, ry),
                            end = Offset(rx + dx, ry + dy),
                            strokeWidth = 2f
                        )
                    }

                    // Flashing recording indicator
                    if (isPlaying && (currentVideoTimeMs / 500) % 2L == 0L) {
                        drawCircle(
                            color = AresError,
                            radius = 6f,
                            center = Offset(30f, 30f)
                        )
                    }
                }

                // Overlay Camera Details in HTML style using Column/Row wrappers
                Box(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                    // Top Left REC label
                    Row(
                        modifier = Modifier.align(Alignment.TopStart),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = if (replayState == ReplayState.PLAYING) "LIVE REC" else "PAUSE",
                            color = if (replayState == ReplayState.PLAYING) AresError else AresTextSecondary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }

                    // Top Right timestamp
                    Text(
                        text = "V-TIME: ${formatTime(currentVideoTimeMs)}",
                        color = AresTextPrimary,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.align(Alignment.TopEnd)
                    )

                    // Bottom HUD with Telemetry summary
                    Column(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .background(AresSurface.copy(alpha = 0.8f), RoundedCornerShape(4.dp))
                            .padding(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        val voltage = currentFrame?.values?.get("Drive/Voltage")
                        val loopTime = currentFrame?.values?.get("LoopTimeMs")
                        val drift = currentFrame?.values?.get("Drive/EkfDrift")

                        Text("HUD METRICS:", color = AresCyan, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                        Text("Battery: ${if (voltage != null) String.format("%.2f V", voltage) else "N/A"}", color = AresTextPrimary, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                        Text("Loop Time: ${if (loopTime != null) String.format("%.1f ms", loopTime) else "N/A"}", color = AresTextPrimary, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                        Text("EKF Drift: ${if (drift != null) String.format("%.3f m", drift) else "N/A"}", color = AresTextPrimary, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                    }

                    // Bottom Right file info
                    Column(
                        modifier = Modifier.align(Alignment.BottomEnd),
                        horizontalAlignment = Alignment.End
                    ) {
                        Text(videoFile?.name ?: "", color = AresTextSecondary, fontSize = 10.sp, maxLines = 1)
                        Text("Offset: ${logOffsetMs}ms", color = AresGold, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }

        // Manual Timestamp Alignment Tools
        if (videoFile != null) {
            Card(
                colors = CardDefaults.cardColors(containerColor = AresBackground),
                border = ButtonDefaults.outlinedButtonBorder(enabled = true).copy(brush = androidx.compose.ui.graphics.SolidColor(AresBorder)),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("MANUAL ALIGNMENT CONTROLS", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = AresCyan)

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Current times display
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Current Video: ${formatTime(currentVideoTimeMs)}", color = AresTextSecondary, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                            Text("Current Log: ${currentFrame?.let { formatTime(it.timestampMs) } ?: "N/A"}", color = AresTextSecondary, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                        }

                        // Align button
                        Button(
                            onClick = {
                                val currentLogTime = currentFrame?.timestampMs
                                if (currentLogTime != null) {
                                    videoSyncService.alignTimestamp(currentVideoTimeMs, currentLogTime)
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = AresCyan, contentColor = AresOnAccent),
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Icon(Icons.Default.Link, null, tint = AresBackground, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Align Current Frame", color = AresBackground, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    HorizontalDivider(color = AresBorder, thickness = 0.5.dp)

                    // Micro adjustment steps
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Micro offset adjustments:", fontSize = 11.sp, color = AresTextSecondary)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf(-1000, -100, -10, 10, 100, 1000).forEach { adjustment ->
                                val sign = if (adjustment > 0) "+" else ""
                                TextButton(
                                    onClick = { videoSyncService.adjustOffset(adjustment.toLong()) },
                                    colors = ButtonDefaults.textButtonColors(contentColor = AresCyan),
                                    contentPadding = PaddingValues(4.dp)
                                ) {
                                    Text("$sign${adjustment}ms", fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                                }
                            }
                        }
                    }
                }
            }

            // Timeline Seeking / Transport Scrubber
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                IconButton(
                    onClick = {
                        if (replayState == ReplayState.PLAYING) {
                            videoSyncService.pause()
                        } else {
                            videoSyncService.play()
                        }
                    }
                ) {
                    Icon(
                        imageVector = if (replayState == ReplayState.PLAYING) Icons.Default.PauseCircle else Icons.Default.PlayCircle,
                        contentDescription = "Playback",
                        tint = AresCyan,
                        modifier = Modifier.size(32.dp)
                    )
                }

                Slider(
                    value = if (videoDurationMs > 0) currentVideoTimeMs.toFloat() / videoDurationMs.toFloat() else 0f,
                    onValueChange = { pct ->
                        val targetTime = (pct * videoDurationMs).toLong()
                        videoSyncService.seekVideo(targetTime)
                    },
                    modifier = Modifier.weight(1f),
                    colors = SliderDefaults.colors(thumbColor = AresCyan, activeTrackColor = AresCyan, inactiveTrackColor = AresBorder)
                )

                Text(
                    text = "${formatTime(currentVideoTimeMs)} / ${formatTime(videoDurationMs)}",
                    fontSize = 11.sp,
                    color = AresTextSecondary,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }
}
