@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.ares.analytics.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.School
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ares.analytics.service.LearningProgressService
import com.ares.analytics.ui.help.LearningCheckpoint
import com.ares.analytics.ui.help.LearningCheckpointAction
import com.ares.analytics.ui.help.LearningCatalog
import com.ares.analytics.ui.help.LearningJourneyEvaluator
import com.ares.analytics.ui.theme.AresBorder
import com.ares.analytics.ui.theme.AresCyan
import com.ares.analytics.ui.theme.AresGreen
import com.ares.analytics.ui.theme.AresOnAccent
import com.ares.analytics.ui.theme.AresSurface
import com.ares.analytics.ui.theme.AresTextPrimary
import com.ares.analytics.ui.theme.AresTextSecondary
import kotlinx.coroutines.launch

/**
 * Slide-out lesson context for MainScreen. It overlays the current workspace instead of reserving
 * vertical space on every screen. The host owns navigation and process actions; this component
 * never enables, deploys to, or sends motion to a physical robot.
 */
@Composable
fun LearningCoachDrawer(
    progressService: LearningProgressService,
    onOpenAcademy: (lessonId: String) -> Unit,
    onOpenScreen: (NavigationTarget) -> Unit,
    onSelectLocalSimulator: () -> Unit,
    onStartSimulator: () -> Unit,
    onOpenDashboard: () -> Unit,
    onStopSimulator: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val progress by progressService.progress.collectAsState()
    val scope = rememberCoroutineScope()
    val lesson = progress.activeLessonId?.let(LearningCatalog::lesson) ?: return
    val journey = LearningJourneyEvaluator.lessonState(lesson, progress)
    val checkpoint = journey.currentCheckpoint

    Surface(
        modifier = modifier.fillMaxHeight().widthIn(min = 340.dp, max = 440.dp).semantics {
            contentDescription = "Robot Academy coach for ${lesson.title}"
            stateDescription = "${journey.status.label}. ${journey.completedCheckpointCount} of ${lesson.checkpoints.size} checkpoints recorded."
        },
        color = AresSurface,
        border = BorderStroke(1.dp, AresBorder),
        shape = RoundedCornerShape(topStart = 14.dp, bottomStart = 14.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("Robot Academy coach", color = AresTextPrimary, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, "Close coach", tint = AresTextSecondary)
                }
            }
            HorizontalDivider(color = AresBorder)
            CoachSummary(lesson.title, journey.status.label, journey.completedCheckpointCount, lesson.checkpoints.size, checkpoint)
            CoachActions(checkpoint?.action, lesson.id, onOpenAcademy, onOpenScreen, onSelectLocalSimulator, onStartSimulator, onOpenDashboard, onStopSimulator) {
                scope.launch {
                    progressService.clearActiveLesson()
                    onDismiss()
                }
            }
        }
    }
}

@Composable
private fun CoachSummary(
    title: String,
    status: String,
    completed: Int,
    total: Int,
    checkpoint: LearningCheckpoint?,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Default.School, contentDescription = null, tint = AresCyan, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(9.dp))
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("Robot Academy · $title", color = AresTextPrimary, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            Text(
                "$status · $completed of $total checkpoints${checkpoint?.title?.let { " · Next: $it" }.orEmpty()}",
                color = AresTextSecondary,
                fontSize = 12.sp,
            )
            Text(
                if (checkpoint == null) "All checkpoints are recorded; reflection is not a safety certification."
                else checkpoint.instruction,
                color = AresTextSecondary,
                fontSize = 11.sp,
                lineHeight = 16.sp,
            )
            if (checkpoint != null) {
                Text(
                    "Done when: ${checkpoint.successText}",
                    color = AresGreen,
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                )
            }
        }
    }
}

@Composable
private fun CoachActions(
    action: LearningCheckpointAction?,
    lessonId: String,
    onOpenAcademy: (String) -> Unit,
    onOpenScreen: (NavigationTarget) -> Unit,
    onSelectLocalSimulator: () -> Unit,
    onStartSimulator: () -> Unit,
    onOpenDashboard: () -> Unit,
    onStopSimulator: () -> Unit,
    onHide: () -> Unit,
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val observed = action == null
        Icon(
            if (observed) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
            contentDescription = null,
            tint = if (observed) AresGreen else AresCyan,
            modifier = Modifier.size(18.dp),
        )
        Text(if (observed) "Recorded" else "Next step", color = AresTextPrimary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        if (action != null && action != LearningCheckpointAction.OPEN_LESSON) {
            Button(
                onClick = when (action) {
                    LearningCheckpointAction.SELECT_LOCAL_SIMULATOR -> onSelectLocalSimulator
                    LearningCheckpointAction.START_SIMULATOR -> onStartSimulator
                    LearningCheckpointAction.OPEN_DASHBOARD -> onOpenDashboard
                    LearningCheckpointAction.STOP_SIMULATOR -> onStopSimulator
                    LearningCheckpointAction.OPEN_SUBSYSTEM_BUILDER -> {
                        { onOpenScreen(NavigationTarget.SUBSYSTEM_GEN) }
                    }
                    LearningCheckpointAction.OPEN_HOMING_LAB -> {
                        { onOpenAcademy("homing-safety-lab") }
                    }
                    LearningCheckpointAction.OPEN_STATE_FLOW_LAB -> {
                        { onOpenAcademy("state-flow-lab") }
                    }
                    LearningCheckpointAction.OPEN_CONTROLS -> {
                        { onOpenScreen(NavigationTarget.CONTROLS) }
                    }
                    LearningCheckpointAction.OPEN_TUNING -> {
                        { onOpenScreen(NavigationTarget.TUNING) }
                    }
                    LearningCheckpointAction.OPEN_SUPERSTRUCTURE_STUDIO -> {
                        { onOpenScreen(NavigationTarget.SUPERSTRUCTURE_STUDIO) }
                    }
                    LearningCheckpointAction.OPEN_AUTONOMOUS -> {
                        { onOpenScreen(NavigationTarget.PATH_PLANNER) }
                    }
                    LearningCheckpointAction.OPEN_IMPORTS -> {
                        { onOpenScreen(NavigationTarget.IMPORT_CENTER) }
                    }
                    LearningCheckpointAction.OPEN_GUIDED_ANALYSIS -> {
                        { onOpenScreen(NavigationTarget.GUIDED_RUN_ANALYSIS) }
                    }
                    LearningCheckpointAction.OPEN_ROBOT_STUDIO -> {
                        { onOpenScreen(NavigationTarget.ROBOT_STUDIO) }
                    }
                    LearningCheckpointAction.OPEN_DEVELOPER_REFERENCE -> {
                        { onOpenScreen(NavigationTarget.KDOC_VIEWER) }
                    }
                    LearningCheckpointAction.OPEN_LESSON -> error("Handled by the lesson button")
                },
                colors = ButtonDefaults.buttonColors(containerColor = AresCyan, contentColor = AresOnAccent),
            ) {
                Text(action.buttonLabel(), fontWeight = FontWeight.Bold)
            }
        }
        OutlinedButton(onClick = { onOpenAcademy(lessonId) }) {
            Text(if (action == LearningCheckpointAction.OPEN_LESSON) "View instructions" else "Lesson")
        }
        OutlinedButton(onClick = onHide) { Text("End lesson") }
    }
}

private fun LearningCheckpointAction.buttonLabel(): String = when (this) {
    LearningCheckpointAction.SELECT_LOCAL_SIMULATOR -> "Select Local Sim"
    LearningCheckpointAction.START_SIMULATOR -> "Start simulator"
    LearningCheckpointAction.OPEN_DASHBOARD -> "Open Dashboard"
    LearningCheckpointAction.STOP_SIMULATOR -> "Stop simulator"
    LearningCheckpointAction.OPEN_SUBSYSTEM_BUILDER -> "Open Subsystem Builder"
    LearningCheckpointAction.OPEN_HOMING_LAB -> "Open homing lab"
    LearningCheckpointAction.OPEN_STATE_FLOW_LAB -> "Open state-flow lab"
    LearningCheckpointAction.OPEN_CONTROLS -> "Open Controller Bindings"
    LearningCheckpointAction.OPEN_TUNING -> "Open Tuning"
    LearningCheckpointAction.OPEN_SUPERSTRUCTURE_STUDIO -> "Open Superstructure Studio"
    LearningCheckpointAction.OPEN_AUTONOMOUS -> "Open Autonomous"
    LearningCheckpointAction.OPEN_IMPORTS -> "Open Log Imports"
    LearningCheckpointAction.OPEN_GUIDED_ANALYSIS -> "Open Guided Run Review"
    LearningCheckpointAction.OPEN_ROBOT_STUDIO -> "Open Robot Studio"
    LearningCheckpointAction.OPEN_DEVELOPER_REFERENCE -> "Open Developer Reference"
    LearningCheckpointAction.OPEN_LESSON -> "View instructions"
}
