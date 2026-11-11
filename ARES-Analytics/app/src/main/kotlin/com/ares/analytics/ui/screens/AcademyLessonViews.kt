@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.ares.analytics.ui.screens

import com.ares.analytics.ui.components.icon

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Launch
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Science
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ares.analytics.domain.learning.LearningProgress
import com.ares.analytics.service.LearningProgressService
import com.ares.analytics.domain.learning.AcademyClassroomStore
import com.ares.analytics.domain.learning.AcademyLearningAssignment
import com.ares.analytics.service.AcademyPracticeImportResult
import com.ares.analytics.domain.navigation.NavigationTarget
import com.ares.analytics.ui.components.dashboard.EkfSensorFusionLabCard
import com.ares.analytics.ui.components.pathplanner.MotionProfileLabCard
import com.ares.analytics.domain.learning.AcademyRuntimeSnapshot
import com.ares.analytics.domain.learning.AcademyClassroomToolkit
import com.ares.analytics.ui.help.DeveloperReferenceCatalog
import com.ares.analytics.ui.help.GlossaryCatalog
import com.ares.analytics.ui.help.GlossaryTerm
import com.ares.analytics.domain.learning.LearningAction
import com.ares.analytics.domain.learning.LearningCatalog
import com.ares.analytics.domain.learning.LearningCheckpoint
import com.ares.analytics.domain.learning.LearningCheckpointEvidence
import com.ares.analytics.domain.learning.LearningJourneyEvaluator
import com.ares.analytics.domain.learning.LearningLab
import com.ares.analytics.domain.learning.LearningLabGuide
import com.ares.analytics.domain.learning.LearningLesson
import com.ares.analytics.domain.learning.LearningLessonJourneyState
import com.ares.analytics.domain.learning.LearningLessonStatus
import com.ares.analytics.domain.learning.LearningLevel
import com.ares.analytics.domain.learning.LearningPath
import com.ares.analytics.domain.learning.LearningRubricRating
import com.ares.analytics.ui.theme.AresAmber
import com.ares.analytics.ui.theme.AresBackground
import com.ares.analytics.ui.theme.AresBorder
import com.ares.analytics.ui.theme.AresBrandDestination
import com.ares.analytics.ui.theme.AresCyan
import com.ares.analytics.ui.theme.AresCyanGlow
import com.ares.analytics.ui.theme.AresGreen
import com.ares.analytics.ui.theme.AresOnAccent
import com.ares.analytics.ui.theme.AresSurface
import com.ares.analytics.ui.theme.AresSurfaceElevated
import com.ares.analytics.ui.theme.AresTextPrimary
import com.ares.analytics.ui.theme.AresTextSecondary
import com.ares.analytics.ui.theme.AresTextTertiary
import com.ares.analytics.ui.theme.AresThemeSettings
import com.ares.analytics.ui.theme.openAresBrandDestination
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

@Composable
internal fun LessonListCard(journey: LearningLessonJourneyState, selected: Boolean, onClick: () -> Unit) {
    val lesson = journey.lesson
    val icon = when (journey.status) {
        LearningLessonStatus.PRACTICED -> Icons.Default.CheckCircle
        LearningLessonStatus.RECOMMENDED_LATER -> Icons.Default.Lock
        else -> Icons.Default.PlayArrow
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onClick, role = Role.RadioButton)
            .semantics {
                contentDescription = "${lesson.title}. ${lesson.outcome}"
                stateDescription = "${journey.status.label}. ${journey.completedCheckpointCount} of ${lesson.checkpoints.size} checkpoints recorded."
            },
        colors = CardDefaults.cardColors(containerColor = if (selected) AresCyanGlow else AresSurface),
        border = BorderStroke(1.dp, if (selected) AresCyan else AresBorder),
        shape = RoundedCornerShape(10.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.Top) {
            Icon(icon, contentDescription = null, tint = statusColor(journey.status), modifier = Modifier.size(19.dp))
            Column(Modifier.padding(start = 10.dp).weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(lesson.title, color = AresTextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                Text(lesson.outcome, color = AresTextSecondary, fontSize = 12.sp, lineHeight = 17.sp)
                Text(
                    "${journey.status.label} · ${lesson.durationMinutes} min · ${if (lesson.requiresRobot) "Robot required later" else "No robot needed"}",
                    color = statusColor(journey.status),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

@Composable
internal fun LessonDetail(
    journey: LearningLessonJourneyState,
    highlightedCheckpointId: String?,
    onLaunch: () -> Unit,
    onCheckpointChange: (LearningCheckpoint, Boolean) -> Unit,
    checkpointReflections: Map<String, String>,
    onReflectionRecorded: (LearningCheckpoint, String) -> Unit,
    onPracticedChange: (Boolean) -> Unit,
    onRestartLesson: () -> Unit,
) {
    val lesson = journey.lesson
    val practiced = journey.status == LearningLessonStatus.PRACTICED
    var confirmRestart by remember(lesson.id) { mutableStateOf(false) }
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(22.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Text(lesson.level.label, color = AresCyan, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            Text(lesson.title, color = AresTextPrimary, fontWeight = FontWeight.Bold, fontSize = 26.sp)
            Spacer(Modifier.height(6.dp))
            Text(lesson.outcome, color = AresTextSecondary, fontSize = 15.sp, lineHeight = 22.sp)
            Text(
                "Status: ${journey.status.label} · ${journey.completedCheckpointCount} of ${lesson.checkpoints.size} checkpoints recorded",
                color = statusColor(journey.status),
                fontWeight = FontWeight.SemiBold,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        if (!journey.prerequisitesMet) {
            item {
                val required = lesson.prerequisiteLessonIds.mapNotNull(LearningCatalog::lesson).joinToString { it.title }
                TeachingNotice(
                    title = "Recommended first",
                    body = "Practice $required before this lesson. You may still preview this material with a mentor.",
                    accent = AresAmber,
                )
            }
        }
        item { HorizontalDivider(color = AresBorder) }
        item { LessonSection("Before you start", lesson.beforeYouStart) }
        item {
            TeachingNotice(
                title = "How to learn this",
                body = "Predict first → build in the real editor → run the appropriate simulation or teaching model → observe named evidence → explain the result → inspect the generated Kotlin when you are ready.",
                accent = AresCyan,
            )
        }
        item { LessonSection("Do this", lesson.steps, numbered = true) }
        if (lesson.checkpoints.isNotEmpty()) {
            item {
                CheckpointSection(
                    lesson.checkpoints,
                    journey.completedCheckpointIds,
                    checkpointReflections,
                    onCheckpointChange,
                    onReflectionRecorded,
                    highlightedCheckpointId,
                )
            }
        }
        lesson.safetyNote?.let { note ->
            item { TeachingNotice("Safety boundary", note, AresAmber) }
        }
        item {
            TeachingNotice(
                title = "What success looks like",
                body = lesson.successLooksLike,
                accent = AresGreen,
            )
        }
        item {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = onLaunch,
                    colors = ButtonDefaults.buttonColors(containerColor = AresCyan, contentColor = AresOnAccent),
                ) {
                    Icon(
                        when (lesson.action) {
                            LearningAction.START_SIMULATOR -> Icons.Default.PlayArrow
                            LearningAction.OPEN_LAB -> Icons.Default.Science
                            LearningAction.OPEN_SCREEN -> Icons.AutoMirrored.Filled.Launch
                        },
                        contentDescription = null,
                    )
                    Spacer(Modifier.width(7.dp))
                    Text(lesson.launchLabel(), fontWeight = FontWeight.Bold)
                }
                OutlinedButton(onClick = { onPracticedChange(!practiced) }) {
                    Text(if (practiced) "Remove practiced mark" else "Mark lesson practiced")
                }
                OutlinedButton(onClick = { confirmRestart = true }) {
                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Restart lesson")
                }
            }
            Text(
                "Practice and checkpoint records are private reminders—not grades, certification, code verification, or proof of robot safety.",
                color = AresTextTertiary,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 7.dp),
            )
        }
    }
    if (confirmRestart) {
        AlertDialog(
            onDismissRequest = { confirmRestart = false },
            title = { Text("Restart this lesson?") },
            text = { Text("This removes this lesson's checkpoints, written reflections, practiced mark, and mentor note. Other lessons are unchanged.") },
            confirmButton = {
                Button(onClick = { confirmRestart = false; onRestartLesson() }) { Text("Restart lesson") }
            },
            dismissButton = { TextButton(onClick = { confirmRestart = false }) { Text("Cancel") } },
        )
    }
}

internal fun academyUsesSinglePane(widthDp: Float, heightDp: Float, largeTextMode: Boolean): Boolean =
    widthDp < 1180f || heightDp < 720f || largeTextMode


@Composable
internal fun AresResourceButton(destination: AresBrandDestination) {
    OutlinedButton(onClick = { openAresBrandDestination(destination) }) {
        Icon(Icons.AutoMirrored.Filled.Launch, contentDescription = null, modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(5.dp))
        Text(destination.buttonLabel, fontSize = 11.sp)
    }
}

@Composable
internal fun LessonSection(title: String, lines: List<String>, numbered: Boolean = false) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, color = AresTextPrimary, fontWeight = FontWeight.Bold, fontSize = 17.sp)
        lines.forEachIndexed { index, line ->
            Row(horizontalArrangement = Arrangement.spacedBy(9.dp), verticalAlignment = Alignment.Top) {
                Text(if (numbered) "${index + 1}." else "•", color = AresCyan, fontWeight = FontWeight.Bold)
                Text(line, color = AresTextSecondary, lineHeight = 20.sp)
            }
        }
    }
}

internal fun LearningLesson.launchLabel(): String = when (action) {
    LearningAction.START_SIMULATOR -> "Start local simulator"
    LearningAction.OPEN_LAB -> "Open ${lab?.label ?: "learning"} lab"
    LearningAction.OPEN_SCREEN -> "Open ${destination.label}"
}

@Composable
internal fun statusColor(status: LearningLessonStatus) = when (status) {
    LearningLessonStatus.PRACTICED -> AresGreen
    LearningLessonStatus.RECOMMENDED_LATER -> AresAmber
    LearningLessonStatus.IN_PROGRESS -> AresCyan
    LearningLessonStatus.NOT_STARTED -> AresTextTertiary
}
