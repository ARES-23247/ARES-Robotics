package com.ares.analytics.viewmodel.field

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.LayersClear
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.Icons
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ares.analytics.domain.project.FieldDocumentMapper
import com.ares.analytics.service.*
import com.ares.analytics.shared.AprilTagPlacement
import com.ares.analytics.shared.FieldWaypoint
import com.ares.analytics.shared.GamePiece
import com.ares.analytics.shared.models.League
import com.ares.analytics.shared.models.TelemetryFrame
import com.ares.analytics.shared.Obstacle
import com.ares.analytics.ui.theme.AresBackground
import com.ares.analytics.ui.theme.AresBorder
import com.ares.analytics.ui.theme.AresCyan
import com.ares.analytics.ui.theme.AresGold
import com.ares.analytics.ui.theme.AresTextPrimary
import com.ares.analytics.ui.theme.AresTextSecondary
import com.ares.analytics.viewmodel.FieldEditorState
import com.ares.analytics.viewmodel.FieldViewerState
import com.ares.analytics.viewmodel.LivePoseState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Built-in practice field layouts for classroom calibration and driver-practice drills.
 */
enum class StudentPracticeFieldPreset(
    val title: String,
    val badge: String,
    val description: String,
) {
    VISION_CALIBRATION_TARGET_1M(
        title = "Vision Calibration (1.0 m Ahead)",
        badge = "AprilTag #1 @ +1.0 m",
        description = "Places robot start waypoint and a single AprilTag #1 exactly 1.0 m directly ahead facing the camera.",
    ),
    THREE_CONE_SLALOM_COURSE(
        title = "3-Cone Slalom Practice Course",
        badge = "3 Cones + 4 Waypoints",
        description = "Places three circular practice cones and four weave waypoints for autonomous and driver slalom drills.",
    ),
    CLEAR_PRACTICE_FLOOR(
        title = "Clear Practice Floor",
        badge = "Open Floor + Origin",
        description = "Clears all obstacles, game pieces, and AprilTags while keeping a clean origin start waypoint.",
    ),
}

/**
 * Applies a [StudentPracticeFieldPreset] to [state] using coordinate conventions appropriate for [league].
 *
 * - FTC and XRP use centered field coordinates around `(0, 0)`.
 * - FRC uses corner-origin coordinates centered around `(width / 2, height / 2)`.
 */
fun applyStudentPracticeFieldPreset(
    state: FieldEditorState,
    preset: StudentPracticeFieldPreset,
    league: League,
): FieldEditorState {
    val defaultImage = FieldDocumentMapper.defaultImageConfig(league)
    val widthM = state.fieldImageConfig.widthMeters.takeIf { it > 0.0 } ?: defaultImage.widthMeters
    val heightM = state.fieldImageConfig.heightMeters.takeIf { it > 0.0 } ?: defaultImage.heightMeters
    val centered = league == League.FTC || league == League.XRP
    val originX = if (centered) 0.0 else widthM * 0.25
    val originY = if (centered) 0.0 else heightM * 0.50

    return when (preset) {
        StudentPracticeFieldPreset.VISION_CALIBRATION_TARGET_1M -> {
            val startX = if (centered) -0.5 else originX
            val startY = originY
            val tagSize = when (league) {
                League.FRC -> 0.1651
                League.FTC -> 0.127
                League.XRP -> 0.08
            }
            state.copy(
                obstacles = emptyList(),
                gamePieces = emptyList(),
                aprilTags = listOf(
                    AprilTagPlacement(
                        id = "preset-vision-tag-1",
                        tagId = 1,
                        name = "Calibration Target #1 (1.0 m)",
                        sizeMeters = tagSize,
                        x = startX + 1.0,
                        y = startY,
                        z = if (league == League.XRP) 0.08 else 0.30,
                        yawDegrees = 180.0,
                    ),
                ),
                fieldWaypoints = listOf(
                    FieldWaypoint(
                        id = "preset-vision-start",
                        name = "Robot Camera Start",
                        x = startX,
                        y = startY,
                        headingDegrees = 0.0,
                    ),
                    FieldWaypoint(
                        id = "preset-vision-standoff",
                        name = "0.5 m Standoff Check",
                        x = startX + 0.5,
                        y = startY,
                        headingDegrees = 0.0,
                    ),
                ),
                selectedElementIds = emptySet(),
            )
        }

        StudentPracticeFieldPreset.THREE_CONE_SLALOM_COURSE -> {
            val spacing = when (league) {
                League.FRC -> 1.4
                League.FTC -> 0.75
                League.XRP -> 0.40
            }
            val lateralOffset = when (league) {
                League.FRC -> 0.65
                League.FTC -> 0.38
                League.XRP -> 0.20
            }
            val coneRadius = when (league) {
                League.FRC -> 0.16
                League.FTC -> 0.11
                League.XRP -> 0.06
            }
            val baseX = if (centered) -spacing else widthM * 0.30
            val centerY = originY

            val cones = listOf(
                Obstacle.Circle(
                    id = "preset-slalom-cone-1",
                    name = "Slalom Cone 1",
                    centerX = baseX,
                    centerY = centerY,
                    radius = coneRadius,
                    colorHex = "#FF7043",
                ),
                Obstacle.Circle(
                    id = "preset-slalom-cone-2",
                    name = "Slalom Cone 2",
                    centerX = baseX + spacing,
                    centerY = centerY,
                    radius = coneRadius,
                    colorHex = "#FF7043",
                ),
                Obstacle.Circle(
                    id = "preset-slalom-cone-3",
                    name = "Slalom Cone 3",
                    centerX = baseX + spacing * 2.0,
                    centerY = centerY,
                    radius = coneRadius,
                    colorHex = "#FF7043",
                ),
            )
            val waypoints = listOf(
                FieldWaypoint(
                    id = "preset-slalom-wp-start",
                    name = "Slalom Start",
                    x = baseX - spacing * 0.6,
                    y = centerY - lateralOffset,
                    headingDegrees = 0.0,
                ),
                FieldWaypoint(
                    id = "preset-slalom-wp-1",
                    name = "Gate 1 (Left)",
                    x = baseX + spacing * 0.5,
                    y = centerY + lateralOffset,
                    headingDegrees = 0.0,
                ),
                FieldWaypoint(
                    id = "preset-slalom-wp-2",
                    name = "Gate 2 (Right)",
                    x = baseX + spacing * 1.5,
                    y = centerY - lateralOffset,
                    headingDegrees = 0.0,
                ),
                FieldWaypoint(
                    id = "preset-slalom-wp-finish",
                    name = "Slalom Finish",
                    x = baseX + spacing * 2.6,
                    y = centerY + lateralOffset,
                    headingDegrees = 0.0,
                ),
            )
            state.copy(
                obstacles = cones,
                gamePieces = emptyList(),
                aprilTags = emptyList(),
                fieldWaypoints = waypoints,
                selectedElementIds = emptySet(),
            )
        }

        StudentPracticeFieldPreset.CLEAR_PRACTICE_FLOOR -> {
            state.copy(
                obstacles = emptyList(),
                gamePieces = emptyList(),
                aprilTags = emptyList(),
                fieldWaypoints = listOf(
                    FieldWaypoint(
                        id = "preset-open-origin",
                        name = "Practice Start Origin",
                        x = originX,
                        y = originY,
                        headingDegrees = 0.0,
                    ),
                ),
                selectedElementIds = emptySet(),
            )
        }
    }
}


@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StudentPracticeFieldPresetCard(
    league: League,
    enabled: Boolean,
    onApplyPreset: (StudentPracticeFieldPreset) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = AresBackground.copy(alpha = 0.6f),
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, AresBorder),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = "Student Practice & Vision Presets",
                    style = MaterialTheme.typography.labelLarge,
                    color = AresGold,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = league.name,
                    style = MaterialTheme.typography.labelSmall,
                    color = AresTextSecondary,
                )
            }
            Text(
                text = "Set up a 1-click practice floor or AprilTag calibration target, then click Push to Sim.",
                fontSize = 11.sp,
                color = AresTextSecondary,
                lineHeight = 15.sp,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                StudentPracticeFieldPreset.entries.forEach { preset ->
                    OutlinedButton(
                        onClick = { onApplyPreset(preset) },
                        enabled = enabled,
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, AresCyan.copy(alpha = 0.45f)),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = AresTextPrimary),
                    ) {
                        Icon(
                            imageVector = preset.icon(),
                            contentDescription = null,
                            tint = AresCyan,
                            modifier = Modifier.size(14.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Column {
                            Text(
                                text = preset.title,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = AresTextPrimary,
                            )
                            Text(
                                text = preset.badge,
                                fontSize = 9.sp,
                                color = AresCyan,
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun StudentPracticeFieldPreset.icon(): ImageVector = when (this) {
    StudentPracticeFieldPreset.VISION_CALIBRATION_TARGET_1M -> Icons.Default.CenterFocusStrong
    StudentPracticeFieldPreset.THREE_CONE_SLALOM_COURSE -> Icons.Default.Route
    StudentPracticeFieldPreset.CLEAR_PRACTICE_FLOOR -> Icons.Default.LayersClear
}
