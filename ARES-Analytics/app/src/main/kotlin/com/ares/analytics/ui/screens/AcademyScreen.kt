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
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.runtime.DisposableEffect
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
import com.ares.analytics.di.ServiceRegistry
import com.ares.analytics.domain.learning.AcademyClassroomStore
import com.ares.analytics.domain.learning.AcademyClassroomToolkit
import com.ares.analytics.domain.learning.AcademyLearningAssignment
import com.ares.analytics.domain.learning.AcademyRuntimeSnapshot
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
import com.ares.analytics.domain.learning.LearningProgress
import com.ares.analytics.domain.learning.LearningRubricRating
import com.ares.analytics.domain.navigation.NavigationTarget
import com.ares.analytics.service.AcademyPracticeImportResult
import com.ares.analytics.service.ControlsDesignAssistant
import com.ares.analytics.service.DrivebaseDesignAssistant
import com.ares.analytics.service.LearningProgressService
import com.ares.analytics.service.SubsystemDesignAssistant
import com.ares.analytics.shared.models.League
import com.ares.analytics.shared.models.WorkspaceConfig
import com.ares.analytics.ui.components.academy.StudentCookbookPane
import com.ares.analytics.ui.components.dashboard.DashboardWidgetRegistry
import com.ares.analytics.ui.components.dashboard.EkfSensorFusionLabCard
import com.ares.analytics.ui.components.icon
import com.ares.analytics.ui.components.pathplanner.MotionProfileLabCard
import com.ares.analytics.ui.help.DeveloperReferenceCatalog
import com.ares.analytics.ui.help.GlossaryCatalog
import com.ares.analytics.ui.help.GlossaryTerm
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
import com.ares.analytics.viewmodel.CloudViewModel
import com.ares.analytics.viewmodel.DashboardViewModel
import com.ares.analytics.viewmodel.FieldEditorViewModel
import com.ares.analytics.viewmodel.ImportCenterIntent
import com.ares.analytics.viewmodel.ImportCenterViewModel
import com.ares.analytics.viewmodel.MainIntent
import com.ares.analytics.viewmodel.MainViewModel
import com.ares.analytics.viewmodel.PathPlannerViewModel
import com.ares.analytics.viewmodel.ProfileViewModel
import com.ares.analytics.viewmodel.ProjectBackupViewModel
import com.ares.analytics.viewmodel.SubsystemGeneratorViewModel
import com.ares.analytics.viewmodel.SysIdIntent
import com.ares.analytics.viewmodel.SysIdViewModel
import com.ares.analytics.viewmodel.TuningIntent
import com.ares.analytics.viewmodel.TuningViewModel
import com.ares.analytics.viewmodel.controls.ControlsEditorViewModel
import com.ares.analytics.viewmodel.drivebase.DrivebaseBuilderViewModel
import com.ares.analytics.viewmodel.field.SimulatorFieldApplyFailure
import com.ares.analytics.viewmodel.field.SimulatorFieldApplyReceipt
import com.ares.analytics.viewmodel.hardware.HardwareSetupViewModel
import com.ares.analytics.viewmodel.integrationcenter.IntegrationCenterViewModel
import com.ares.analytics.viewmodel.project.ProjectIdentityViewModel
import com.ares.analytics.viewmodel.robotstudio.RobotStudioViewModel
import com.ares.analytics.viewmodel.runanalysis.GuidedRunAnalysisViewModel
import com.ares.analytics.viewmodel.superstructure.SuperstructureStudioViewModel
import com.ares.analytics.viewmodel.tuning.GuidedExperimentProposal
import com.ares.analytics.viewmodel.tuning.GuidedTuningExperimentViewModel
import com.areslib.controls.ControllerInputPlatform
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
            ?: LearningCatalog.paths.firstOrNull()?.lessonIds?.firstOrNull()?.let(LearningCatalog::lesson)
            ?: LearningCatalog.lessons.first()
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
    var studentCookbookOpen by remember { mutableStateOf(false) }
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

    if (studentCookbookOpen) {
        StudentCookbookPane(
            onBack = { studentCookbookOpen = false },
            onOpenScreen = onOpenScreen,
            onStartSimulator = onStartSimulator,
        )
        return
    }

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
                onOpenCookbook = { studentCookbookOpen = true },
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
    onOpenCookbook: () -> Unit,
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
            onOpenCookbook = onOpenCookbook,
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
    onOpenCookbook: () -> Unit,
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
            Button(
                onClick = onOpenCookbook,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = AresCyan, contentColor = AresOnAccent),
            ) {
                Icon(Icons.AutoMirrored.Filled.MenuBook, contentDescription = null, modifier = Modifier.size(17.dp))
                Spacer(Modifier.width(6.dp))
                Text("Student Cookbook & Quick-Start", fontWeight = FontWeight.Bold)
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

