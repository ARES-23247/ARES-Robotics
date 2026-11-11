package com.ares.analytics.ui.components.routine

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ares.analytics.shared.models.League
import com.ares.analytics.shared.models.Trajectory
import com.ares.analytics.ui.components.catalog.AresActionCatalogPicker
import com.ares.analytics.ui.theme.*
import com.ares.analytics.ui.theme.AresBackground
import com.ares.analytics.ui.theme.AresBorder
import com.ares.analytics.ui.theme.AresCyan
import com.ares.analytics.ui.theme.AresGreen
import com.ares.analytics.ui.theme.AresSurface
import com.ares.analytics.ui.theme.AresTextPrimary
import com.ares.analytics.ui.theme.AresTextSecondary
import com.ares.analytics.viewmodel.PathPlannerIntent
import com.ares.analytics.viewmodel.PathPlannerState
import com.ares.analytics.viewmodel.pathing.RobotDimensions
import com.ares.analytics.viewmodel.routine.GuidedFirstRoutinePlan
import com.ares.analytics.viewmodel.routine.RoutinePreviewAction
import com.ares.analytics.viewmodel.routine.defaultGuidedFirstRoutinePlan
import com.ares.analytics.viewmodel.routine.validateGuidedFirstRoutinePlan
import com.areslib.catalog.*
import com.areslib.routine.*
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs

private const val METERS_TO_INCHES = 39.37007874015748
private const val METERS_TO_FEET = 3.280839895013123

data class RoutineKinematicSample(
    val timeSeconds: Double,
    val xMeters: Double,
    val yMeters: Double,
    val headingDegrees: Double,
    val velocityMps: Double,
    val accelerationMps2: Double,
    val angularVelocityDegPerSec: Double,
) {
    val xInches: Double get() = xMeters * METERS_TO_INCHES
    val yInches: Double get() = yMeters * METERS_TO_INCHES
    val velocityFps: Double get() = velocityMps * METERS_TO_FEET
    val accelerationFps2: Double get() = accelerationMps2 * METERS_TO_FEET
}

internal fun wrapAngleDifferenceRadians(deltaRad: Double): Double {
    var wrapped = (deltaRad + PI) % (2.0 * PI)
    if (wrapped < 0.0) wrapped += 2.0 * PI
    return wrapped - PI
}

fun sampleRoutineKinematicsAt(
    trajectory: Trajectory?,
    previewActions: List<RoutinePreviewAction>,
    timeSeconds: Double,
): Pair<RoutineKinematicSample?, List<RoutinePreviewAction>> {
    val clampedTime = timeSeconds.coerceAtLeast(0.0)
    val activeActions = previewActions.filter { action ->
        abs(clampedTime - action.timeSeconds) <= 0.15
    }

    val states = trajectory?.states.orEmpty()
    if (states.isEmpty()) {
        return null to activeActions
    }

    if (states.size == 1 || clampedTime <= states.first().timeSeconds) {
        val first = states.first()
        val next = states.getOrNull(1)
        val dt = ((next?.timeSeconds ?: first.timeSeconds) - first.timeSeconds).coerceAtLeast(1e-6)
        val accel = if (next != null && next.timeSeconds > first.timeSeconds) {
            (next.velocity - first.velocity) / dt
        } else {
            0.0
        }
        val omegaRad = if (next != null && next.timeSeconds > first.timeSeconds) {
            wrapAngleDifferenceRadians(next.headingRad - first.headingRad) / dt
        } else {
            0.0
        }
        return RoutineKinematicSample(
            timeSeconds = clampedTime,
            xMeters = first.x,
            yMeters = first.y,
            headingDegrees = Math.toDegrees(first.headingRad),
            velocityMps = first.velocity,
            accelerationMps2 = accel,
            angularVelocityDegPerSec = Math.toDegrees(omegaRad),
        ) to activeActions
    }

    val lastIndex = states.lastIndex
    if (clampedTime >= states.last().timeSeconds) {
        val last = states.last()
        val prev = states[lastIndex - 1]
        val dt = (last.timeSeconds - prev.timeSeconds).coerceAtLeast(1e-6)
        val accel = if (last.timeSeconds > prev.timeSeconds) (last.velocity - prev.velocity) / dt else 0.0
        val omegaRad = if (last.timeSeconds > prev.timeSeconds) {
            wrapAngleDifferenceRadians(last.headingRad - prev.headingRad) / dt
        } else {
            0.0
        }
        return RoutineKinematicSample(
            timeSeconds = clampedTime,
            xMeters = last.x,
            yMeters = last.y,
            headingDegrees = Math.toDegrees(last.headingRad),
            velocityMps = last.velocity,
            accelerationMps2 = accel,
            angularVelocityDegPerSec = Math.toDegrees(omegaRad),
        ) to activeActions
    }

    var upperIdx = states.indexOfFirst { it.timeSeconds >= clampedTime }
    if (upperIdx <= 0) upperIdx = 1
    val lower = states[upperIdx - 1]
    val upper = states[upperIdx]
    val span = (upper.timeSeconds - lower.timeSeconds).coerceAtLeast(1e-6)
    val alpha = ((clampedTime - lower.timeSeconds) / span).coerceIn(0.0, 1.0)

    val x = lower.x + (upper.x - lower.x) * alpha
    val y = lower.y + (upper.y - lower.y) * alpha
    val dTheta = wrapAngleDifferenceRadians(upper.headingRad - lower.headingRad)
    val headingRad = lower.headingRad + dTheta * alpha
    val velocity = lower.velocity + (upper.velocity - lower.velocity) * alpha
    val accel = if (upper.timeSeconds > lower.timeSeconds) (upper.velocity - lower.velocity) / span else 0.0
    val omegaRad = if (upper.timeSeconds > lower.timeSeconds) dTheta / span else 0.0

    return RoutineKinematicSample(
        timeSeconds = clampedTime,
        xMeters = x,
        yMeters = y,
        headingDegrees = Math.toDegrees(headingRad),
        velocityMps = velocity,
        accelerationMps2 = accel,
        angularVelocityDegPerSec = Math.toDegrees(omegaRad),
    ) to activeActions
}

fun formatDualPoseSummary(sample: RoutineKinematicSample): String =
    String.format(
        Locale.US,
        "(%.2f m, %.2f m) • (%.1f in, %.1f in)",
        sample.xMeters,
        sample.yMeters,
        sample.xInches,
        sample.yInches,
    )

fun formatDualKinematicsSummary(sample: RoutineKinematicSample): String =
    String.format(
        Locale.US,
        "%.2f m/s (%.1f ft/s) • %.2f m/s² (%.1f ft/s²)",
        sample.velocityMps,
        sample.velocityFps,
        sample.accelerationMps2,
        sample.accelerationFps2,
    )

private val PLAYBACK_SPEED_PRESETS = listOf(0.25, 0.5, 1.0, 2.0)

@Composable
fun RoutineTimelineScrubberCard(
    state: PathPlannerState,
    onIntent: (PathPlannerIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val hasPlayablePreview = state.routinePreviewWarning == null &&
        state.estimatedDuration > 0.0 &&
        (state.trajectory != null || state.previewActions.isNotEmpty())
    val (kinematicSample, activeActions) = sampleRoutineKinematicsAt(
        trajectory = state.trajectory,
        previewActions = state.previewActions,
        timeSeconds = state.playbackTime,
    )

    Surface(
        color = AresBackground,
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, AresBorder),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    IconButton(
                        onClick = { onIntent(PathPlannerIntent.TogglePlayback) },
                        enabled = hasPlayablePreview,
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            if (state.isPlaying) Icons.Default.Stop else Icons.Default.PlayArrow,
                            contentDescription = if (state.isPlaying) "Pause preview" else "Play preview",
                            tint = if (hasPlayablePreview) AresCyan else AresTextSecondary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    IconButton(
                        onClick = { onIntent(PathPlannerIntent.SeekPlayback(0.0)) },
                        enabled = hasPlayablePreview && state.playbackTime > 0.0,
                        modifier = Modifier.size(26.dp)
                    ) {
                        Icon(
                            Icons.Default.Replay,
                            contentDescription = "Reset to 0.0s",
                            tint = if (hasPlayablePreview && state.playbackTime > 0.0) AresTextPrimary else AresTextSecondary,
                            modifier = Modifier.size(15.dp)
                        )
                    }
                    SmallSeekPill(
                        label = "-0.1s",
                        enabled = hasPlayablePreview,
                        onClick = { onIntent(PathPlannerIntent.SeekPlayback((state.playbackTime - 0.1).coerceAtLeast(0.0))) }
                    )
                    SmallSeekPill(
                        label = "+0.1s",
                        enabled = hasPlayablePreview,
                        onClick = {
                            onIntent(
                                PathPlannerIntent.SeekPlayback(
                                    (state.playbackTime + 0.1).coerceAtMost(state.estimatedDuration.coerceAtLeast(0.0))
                                )
                            )
                        }
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    PLAYBACK_SPEED_PRESETS.forEach { speed ->
                        val selected = abs(state.playbackSpeed - speed) < 1e-3
                        Surface(
                            color = if (selected) AresCyan.copy(alpha = 0.18f) else AresSurface,
                            shape = RoundedCornerShape(6.dp),
                            border = BorderStroke(1.dp, if (selected) AresCyan else AresBorder),
                            modifier = Modifier.clickable {
                                onIntent(PathPlannerIntent.SetPlaybackSpeed(speed))
                            }
                        ) {
                            Text(
                                text = "${speed}x",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (selected) AresCyan else AresTextSecondary,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = String.format(Locale.US, "%.2fs", state.playbackTime),
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = AresCyan,
                )
                Slider(
                    value = state.playbackTime.toFloat(),
                    onValueChange = { value ->
                        onIntent(PathPlannerIntent.SeekPlayback(value.toDouble()))
                    },
                    valueRange = 0f..state.estimatedDuration.coerceAtLeast(0.01).toFloat(),
                    enabled = hasPlayablePreview,
                    colors = SliderDefaults.colors(
                        thumbColor = AresCyan,
                        activeTrackColor = AresCyan,
                        inactiveTrackColor = AresBorder
                    ),
                    modifier = Modifier.weight(1f).height(22.dp)
                )
                Text(
                    text = String.format(Locale.US, "%.2fs", state.estimatedDuration.coerceAtLeast(0.0)),
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = AresTextSecondary,
                )
            }

            if (kinematicSample != null && state.routinePreviewWarning == null) {
                Surface(
                    color = AresSurface,
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, AresBorder),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Pose: ${formatDualPoseSummary(kinematicSample)}",
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                color = AresTextPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                text = String.format(
                                    Locale.US,
                                    "θ %.1f° (ω %.0f°/s)",
                                    kinematicSample.headingDegrees,
                                    kinematicSample.angularVelocityDegPerSec
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                color = AresCyan
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "Kinematics: ${formatDualKinematicsSummary(kinematicSample)}",
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                color = AresTextSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }

            if (state.previewActions.isNotEmpty() && state.routinePreviewWarning == null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    state.previewActions.forEach { action ->
                        val isActive = activeActions.any {
                            it.stepId == action.stepId && abs(it.timeSeconds - action.timeSeconds) < 1e-6
                        }
                        val descriptor = state.routineActions.firstOrNull { it.key == action.actionKey }
                        val label = descriptor?.displayName ?: action.actionKey
                        Surface(
                            color = if (isActive) AresGreen.copy(alpha = 0.18f) else AresSurface,
                            shape = RoundedCornerShape(6.dp),
                            border = BorderStroke(1.dp, if (isActive) AresGreen else AresBorder),
                            modifier = Modifier.clickable {
                                onIntent(PathPlannerIntent.SeekPlayback(action.timeSeconds))
                            }
                        ) {
                            Text(
                                text = String.format(
                                    Locale.US,
                                    "%s @ %.1fs",
                                    label,
                                    action.timeSeconds
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                color = if (isActive) AresGreen else AresTextSecondary,
                                fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SmallSeekPill(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        color = AresSurface,
        shape = RoundedCornerShape(6.dp),
        border = BorderStroke(1.dp, AresBorder),
        modifier = if (enabled) Modifier.clickable(onClick = onClick) else Modifier
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = if (enabled) AresTextPrimary else AresTextSecondary.copy(alpha = 0.5f),
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}
