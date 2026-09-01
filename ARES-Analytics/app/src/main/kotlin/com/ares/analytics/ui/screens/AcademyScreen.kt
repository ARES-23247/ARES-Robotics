@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.ares.analytics.ui.screens

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
import com.ares.analytics.service.LearningProgress
import com.ares.analytics.service.LearningProgressService
import com.ares.analytics.service.AcademyClassroomStore
import com.ares.analytics.service.AcademyLearningAssignment
import com.ares.analytics.service.AcademyPracticeImportResult
import com.ares.analytics.ui.components.NavigationTarget
import com.ares.analytics.ui.components.dashboard.EkfSensorFusionLabCard
import com.ares.analytics.ui.components.pathplanner.MotionProfileLabCard
import com.ares.analytics.ui.help.AcademyRuntimeSnapshot
import com.ares.analytics.ui.help.AcademyClassroomToolkit
import com.ares.analytics.ui.help.DeveloperReferenceCatalog
import com.ares.analytics.ui.help.GlossaryCatalog
import com.ares.analytics.ui.help.GlossaryTerm
import com.ares.analytics.ui.help.LearningAction
import com.ares.analytics.ui.help.LearningCatalog
import com.ares.analytics.ui.help.LearningCheckpoint
import com.ares.analytics.ui.help.LearningCheckpointEvidence
import com.ares.analytics.ui.help.LearningJourneyEvaluator
import com.ares.analytics.ui.help.LearningLab
import com.ares.analytics.ui.help.LearningLabGuide
import com.ares.analytics.ui.help.LearningLesson
import com.ares.analytics.ui.help.LearningLessonJourneyState
import com.ares.analytics.ui.help.LearningLessonStatus
import com.ares.analytics.ui.help.LearningLevel
import com.ares.analytics.ui.help.LearningPath
import com.ares.analytics.ui.help.LearningRubricRating
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
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter

/**
 * Path-based Robot Academy backed by real ARES workflows and simplified, explicitly bounded labs.
 * Practice and checkpoint records are local reminders, never grades or physical safety evidence.
 */
@Composable
fun AcademyScreen(
    progressService: LearningProgressService,
    onOpenScreen: (NavigationTarget) -> Unit,
    onStartSimulator: () -> Unit,
    onCreatePracticeProject: () -> Unit,
    onInstallAndImportPracticeRuns: suspend () -> AcademyPracticeImportResult,
    onOpenImports: () -> Unit,
    onOpenRunReview: () -> Unit,
    projectPath: String,
    projectLabel: String,
    initialLessonId: String? = null,
    initialCheckpointId: String? = null,
    initialGlossaryTerm: String? = null,
    runtime: AcademyRuntimeSnapshot = AcademyRuntimeSnapshot.Unavailable,
) {
    val progress by progressService.progress.collectAsState()
    val classroom by progressService.classroom.collectAsState()
    val scope = rememberCoroutineScope()
    val initialLesson = remember {
        progress.activeLessonId?.let(LearningCatalog::lesson)
            ?: LearningCatalog.lesson(LearningCatalog.paths.first().lessonIds.first())!!
    }
    var query by remember { mutableStateOf("") }
    var selectedLevel by remember { mutableStateOf<LearningLevel?>(null) }
    var selectedPathId by remember {
        mutableStateOf(
            progress.selectedPathId?.takeIf { LearningCatalog.path(it) != null }
                ?: LearningCatalog.paths.firstOrNull { initialLesson.id in it.lessonIds }?.id
                ?: LearningCatalog.paths.first().id,
        )
    }
    var selectedLessonId by remember { mutableStateOf(initialLesson.id) }
    var selectedLab by remember { mutableStateOf<LearningLab?>(null) }
    var glossaryOpen by remember { mutableStateOf(false) }
    var classroomToolkitOpen by remember { mutableStateOf(false) }
    var compactDetailOpen by remember { mutableStateOf(initialLessonId != null) }

    LaunchedEffect(runtime) {
        progressService.observeRuntime(runtime)
    }
    LaunchedEffect(initialGlossaryTerm) {
        if (!initialGlossaryTerm.isNullOrBlank() && GlossaryCatalog.term(initialGlossaryTerm) != null) {
            glossaryOpen = true
        }
    }
    LaunchedEffect(initialLessonId) {
        val requested = initialLessonId?.let(LearningCatalog::lesson)
        if (requested != null) {
            selectedLessonId = requested.id
            selectedLevel = null
            selectedPathId = LearningCatalog.paths.firstOrNull { requested.id in it.lessonIds }?.id
                ?: LearningCatalog.paths.first().id
            query = ""
            compactDetailOpen = true
        }
    }

    val matches = remember(query, selectedLevel, selectedPathId) {
        LearningCatalog.search(query, selectedLevel, selectedPathId)
    }
    val selectedLesson = LearningCatalog.lesson(selectedLessonId)?.takeIf { it in matches } ?: matches.firstOrNull()

    if (glossaryOpen) {
        GlossaryPane(
            initialTerm = initialGlossaryTerm,
            onBack = { glossaryOpen = false },
            onOpenLesson = { lessonId ->
                val lesson = LearningCatalog.lesson(lessonId)
                if (lesson != null) {
                    glossaryOpen = false
                    selectedLessonId = lesson.id
                    selectedLevel = null
                    selectedPathId = LearningCatalog.paths.firstOrNull { lesson.id in it.lessonIds }?.id
                        ?: LearningCatalog.paths.first().id
                    query = ""
                    compactDetailOpen = true
                    scope.launch { progressService.startLesson(lesson.id) }
                }
            },
            onOpenDeveloperReference = { onOpenScreen(NavigationTarget.KDOC_VIEWER) },
        )
        return
    }

    if (selectedLab != null) {
        LearningLabsPane(initialLab = selectedLab!!, onBack = { selectedLab = null })
        return
    }

    if (classroomToolkitOpen) {
        ClassroomToolkitPane(
            progress = progress,
            classroom = classroom,
            progressService = progressService,
            projectPath = projectPath,
            projectLabel = projectLabel,
            onCreatePracticeProject = onCreatePracticeProject,
            onInstallAndImportPracticeRuns = onInstallAndImportPracticeRuns,
            onOpenImports = onOpenImports,
            onOpenRunReview = onOpenRunReview,
            onBack = { classroomToolkitOpen = false },
        )
        return
    }

    BoxWithConstraints(
        modifier = Modifier.fillMaxSize().background(AresBackground).padding(16.dp),
    ) {
        val compact = academyUsesSinglePane(maxWidth.value, maxHeight.value, AresThemeSettings.largeTextMode)
        val catalog: @Composable (Modifier) -> Unit = { modifier ->
            AcademyCatalogPanel(
                modifier = modifier,
                progress = progress,
                query = query,
                onQueryChange = { query = it },
                selectedLevel = selectedLevel,
                onLevelSelected = { selectedLevel = it },
                selectedPathId = selectedPathId,
                onPathSelected = { pathId ->
                    selectedPathId = pathId
                    scope.launch { progressService.selectPath(pathId) }
                },
                lessons = matches,
                selectedLessonId = selectedLesson?.id,
                onLessonSelected = { lesson ->
                    if (lesson.id !in LearningCatalog.path(selectedPathId)?.lessonIds.orEmpty()) {
                        selectedPathId = LearningCatalog.paths.firstOrNull { lesson.id in it.lessonIds }?.id
                            ?: selectedPathId
                    }
                    selectedLessonId = lesson.id
                    compactDetailOpen = true
                    scope.launch { progressService.startLesson(lesson.id) }
                },
                onOpenLabs = { selectedLab = LearningLab.CONTROL },
                onOpenGlossary = { glossaryOpen = true },
                onOpenClassroomToolkit = { classroomToolkitOpen = true },
            )
        }
        val detail: @Composable (Modifier) -> Unit = { modifier ->
            Surface(
                modifier = modifier,
                color = AresSurface,
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, AresBorder),
            ) {
                if (selectedLesson == null) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("No lessons match those filters.", color = AresTextSecondary)
                    }
                } else {
                    LessonDetail(
                        journey = LearningJourneyEvaluator.lessonState(selectedLesson, progress),
                        highlightedCheckpointId = initialCheckpointId,
                        onLaunch = {
                            scope.launch { progressService.startLesson(selectedLesson.id) }
                            when (selectedLesson.action) {
                                LearningAction.OPEN_SCREEN -> onOpenScreen(selectedLesson.destination)
                                LearningAction.START_SIMULATOR -> onStartSimulator()
                                LearningAction.OPEN_LAB -> selectedLab = selectedLesson.lab ?: LearningLab.CONTROL
                            }
                        },
                        onCheckpointChange = { checkpoint, completed ->
                            scope.launch { progressService.setCheckpointCompleted(checkpoint.id, completed) }
                        },
                        checkpointReflections = progress.checkpointReflections,
                        onReflectionRecorded = { checkpoint, reflection ->
                            scope.launch { progressService.recordReflection(checkpoint.id, reflection) }
                        },
                        onPracticedChange = { practiced ->
                            scope.launch { progressService.setPracticed(selectedLesson.id, practiced) }
                        },
                        onRestartLesson = {
                            scope.launch {
                                progressService.resetLesson(selectedLesson.id)
                                progressService.startLesson(selectedLesson.id)
                            }
                        },
                    )
                }
            }
        }

        if (compact) {
            if (compactDetailOpen) {
                Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { compactDetailOpen = false }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("Back to lesson list")
                    }
                    detail(Modifier.fillMaxWidth().weight(1f))
                }
            } else {
                catalog(Modifier.fillMaxSize())
            }
        } else {
            Row(modifier = Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                catalog(Modifier.widthIn(min = 340.dp, max = 390.dp).fillMaxHeight())
                detail(Modifier.weight(1f).fillMaxHeight())
            }
        }
    }
}

@Composable
private fun AcademyCatalogPanel(
    modifier: Modifier,
    progress: LearningProgress,
    query: String,
    onQueryChange: (String) -> Unit,
    selectedLevel: LearningLevel?,
    onLevelSelected: (LearningLevel?) -> Unit,
    selectedPathId: String,
    onPathSelected: (String) -> Unit,
    lessons: List<LearningLesson>,
    selectedLessonId: String?,
    onLessonSelected: (LearningLesson) -> Unit,
    onOpenLabs: () -> Unit,
    onOpenGlossary: () -> Unit,
    onOpenClassroomToolkit: () -> Unit,
) {
    val path = LearningCatalog.path(selectedPathId) ?: LearningCatalog.paths.first()
    val recommended = LearningJourneyEvaluator.recommendedLesson(path, progress)
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        LearningHeader(
            practiced = progress.practicedLessonIds.size,
            total = LearningCatalog.lessons.size,
            onOpenLabs = onOpenLabs,
            onOpenGlossary = onOpenGlossary,
            onOpenClassroomToolkit = onOpenClassroomToolkit,
        )
        Text("Engineering Mastery Tracks", color = AresTextPrimary, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        FlowRow(
            modifier = Modifier.fillMaxWidth().selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            LearningCatalog.paths.forEach { option ->
                FilterChip(
                    selected = selectedPathId == option.id,
                    onClick = { onPathSelected(option.id) },
                    label = { Text(option.title, fontSize = 11.sp) },
                    modifier = Modifier.semantics {
                        stateDescription = if (selectedPathId == option.id) "Selected learning path" else "Available learning path"
                    },
                )
            }
        }
        Text(path.summary, color = AresTextSecondary, fontSize = 12.sp, lineHeight = 17.sp)
        if (recommended != null) {
            Surface(
                color = AresCyanGlow,
                border = BorderStroke(1.dp, AresCyan),
                shape = RoundedCornerShape(10.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Continue this path", color = AresTextPrimary, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        Text(recommended.title, color = AresTextSecondary, fontSize = 11.sp)
                    }
                    OutlinedButton(onClick = { onLessonSelected(recommended) }) { Text("Continue") }
                }
            }
        }
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("What do you want to learn?") },
            placeholder = { Text("Try: simulator, logs, motor, feedforward…") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            LearningLevel.entries.forEach { level ->
                FilterChip(
                    selected = selectedLevel == level,
                    onClick = { onLevelSelected(if (selectedLevel == level) null else level) },
                    label = { Text(level.label, fontSize = 11.sp) },
                )
            }
        }
        Text(selectedLevel?.explanation ?: "Showing every level in this path", color = AresTextSecondary, fontSize = 11.sp)
        LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(lessons, key = LearningLesson::id) { lesson ->
                LessonListCard(
                    journey = LearningJourneyEvaluator.lessonState(lesson, progress),
                    selected = lesson.id == selectedLessonId,
                    onClick = { onLessonSelected(lesson) },
                )
            }
        }
    }
}

@Composable
private fun LearningHeader(
    practiced: Int,
    total: Int,
    onOpenLabs: () -> Unit,
    onOpenGlossary: () -> Unit,
    onOpenClassroomToolkit: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = AresSurface),
        border = BorderStroke(1.dp, AresBorder),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.School, contentDescription = null, tint = AresCyan, modifier = Modifier.size(26.dp))
                Text("Robot Academy", color = AresTextPrimary, fontWeight = FontWeight.Bold, fontSize = 22.sp)
            }
            Text(
                "Learn through real app tasks, simulator-first missions, and clearly bounded teaching models.",
                color = AresTextSecondary,
                fontSize = 13.sp,
                lineHeight = 19.sp,
            )
            Text("Practiced locally: $practiced of $total lessons", color = AresGreen, fontWeight = FontWeight.SemiBold)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                AresResourceButton(AresBrandDestination.TEAM_WEBSITE)
                AresResourceButton(AresBrandDestination.TEAM_GITHUB)
            }
            OutlinedButton(onClick = onOpenLabs, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Science, contentDescription = null, modifier = Modifier.size(17.dp))
                Spacer(Modifier.width(6.dp))
                Text("Explore guided learning labs")
            }
            OutlinedButton(onClick = onOpenGlossary, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.AutoMirrored.Filled.MenuBook, contentDescription = null, modifier = Modifier.size(17.dp))
                Spacer(Modifier.width(6.dp))
                Text("Glossary")
            }
            OutlinedButton(onClick = onOpenClassroomToolkit, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.School, contentDescription = null, modifier = Modifier.size(17.dp))
                Spacer(Modifier.width(6.dp))
                Text("Classroom & mentor toolkit")
            }
        }
    }
}

@Composable
private fun LessonListCard(journey: LearningLessonJourneyState, selected: Boolean, onClick: () -> Unit) {
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
private fun LessonDetail(
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
private fun CheckpointSection(
    checkpoints: List<LearningCheckpoint>,
    completedIds: Set<String>,
    checkpointReflections: Map<String, String>,
    onCheckpointChange: (LearningCheckpoint, Boolean) -> Unit,
    onReflectionRecorded: (LearningCheckpoint, String) -> Unit,
    highlightedCheckpointId: String?,
) {
    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
        Text("Learning checkpoints", color = AresTextPrimary, fontWeight = FontWeight.Bold, fontSize = 17.sp)
        Text(
            "ARES records only narrow app facts automatically, such as a running simulator or a valid saved descriptor. Understanding, code quality, and physical safety decisions stay with people.",
            color = AresTextSecondary,
            fontSize = 12.sp,
        )
        checkpoints.forEachIndexed { index, checkpoint ->
            val completed = checkpoint.id in completedIds
            val automatic = checkpoint.evidence != LearningCheckpointEvidence.SELF_REPORTED
            val highlighted = checkpoint.id == highlightedCheckpointId
            var reflectionDraft by remember(checkpoint.id, checkpointReflections[checkpoint.id]) {
                mutableStateOf(checkpointReflections[checkpoint.id].orEmpty())
            }
            Surface(
                color = if (highlighted) AresCyan.copy(alpha = .12f) else AresSurfaceElevated,
                border = BorderStroke(2.dp.takeIf { highlighted } ?: 1.dp, when {
                    highlighted -> AresCyan
                    completed -> AresGreen
                    else -> AresBorder
                }),
                shape = RoundedCornerShape(9.dp),
                modifier = Modifier.fillMaxWidth().semantics {
                    stateDescription = if (completed) "Recorded" else if (automatic) "Waiting for observable app evidence" else "Waiting for your reflection"
                },
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Icon(
                            if (completed) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                            contentDescription = null,
                            tint = if (completed) AresGreen else AresTextTertiary,
                        )
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            if (highlighted) Text("Linked from the validation error", color = AresCyan, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            Text("${index + 1}. ${checkpoint.title}", color = AresTextPrimary, fontWeight = FontWeight.Bold)
                            Text(checkpoint.instruction, color = AresTextSecondary, fontSize = 12.sp, lineHeight = 18.sp)
                            Text(
                                when {
                                    completed && automatic -> "Observed by ARES: ${checkpoint.successText}"
                                    completed -> "Your reflection is recorded: ${checkpoint.successText}"
                                    automatic -> "Waiting for observable app evidence"
                                    else -> "Your reflection is not recorded yet"
                                },
                                color = if (completed) AresGreen else AresTextTertiary,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                    if (!automatic) {
                        Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp), horizontalAlignment = Alignment.End) {
                            OutlinedTextField(
                                value = reflectionDraft,
                                onValueChange = { reflectionDraft = it.take(4_000) },
                                label = { Text("Your evidence or explanation") },
                                placeholder = { Text("What did you observe, and what does it not prove?") },
                                minLines = 2,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            OutlinedButton(
                                onClick = {
                                    if (completed) onCheckpointChange(checkpoint, false)
                                    else onReflectionRecorded(checkpoint, reflectionDraft)
                                },
                                enabled = completed || reflectionDraft.isNotBlank(),
                            ) {
                                Text(if (completed) "Remove reflection" else "Record reflection")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun TeachingNotice(title: String, body: String, accent: androidx.compose.ui.graphics.Color) {
    Surface(
        color = accent.copy(alpha = 0.12f),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.7f)),
        shape = RoundedCornerShape(10.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, color = accent, fontWeight = FontWeight.Bold)
            Text(body, color = AresTextPrimary, lineHeight = 20.sp)
        }
    }
}

@Composable
private fun AresResourceButton(destination: AresBrandDestination) {
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

private fun LearningLesson.launchLabel(): String = when (action) {
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
