@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.ares.analytics.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ares.analytics.service.*
import com.ares.analytics.ui.help.*
import com.ares.analytics.ui.theme.*
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun ClassroomToolkitPane(
    progress: LearningProgress,
    classroom: AcademyClassroomStore,
    progressService: LearningProgressService,
    projectPath: String,
    projectLabel: String,
    onCreatePracticeProject: () -> Unit,
    onInstallAndImportPracticeRuns: suspend () -> AcademyPracticeImportResult,
    onOpenImports: () -> Unit,
    onOpenRunReview: () -> Unit,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var selectedPathId by remember(progress.selectedPathId) {
        mutableStateOf(progress.selectedPathId?.takeIf { LearningCatalog.path(it) != null } ?: LearningCatalog.paths.first().id)
    }
    var studentDraft by remember(progress.studentDisplayName) { mutableStateOf(progress.studentDisplayName) }
    var mentorName by remember { mutableStateOf("") }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var confirmResetPath by remember { mutableStateOf(false) }
    var confirmPracticePack by remember { mutableStateOf(false) }
    var confirmNewStudent by remember { mutableStateOf(false) }
    var assignmentTitle by remember { mutableStateOf("") }
    var assignmentInstructions by remember { mutableStateOf("") }
    var assignmentDue by remember { mutableStateOf("") }
    var assignmentLessonIds by remember(selectedPathId) { mutableStateOf(emptySet<String>()) }
    val summary = AcademyClassroomToolkit.pathSummary(selectedPathId, progress)
    // The active-learner invariant is enforced by the store, but composition must not
    // crash if a corrupt current document breaks it — fall back to the first roster
    // entry instead of throwing NoSuchElementException mid-frame.
    val activeRecord = classroom.learners.firstOrNull { it.learnerId == classroom.activeLearnerId }
        ?: classroom.learners.firstOrNull()
        ?: return

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(AresBackground).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Back to lessons")
                }
                Column(Modifier.weight(1f)) {
                    Text("Classroom & mentor toolkit", color = AresTextPrimary, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Text(
                        "Local progress, written reflections, review prompts, and exportable evidence. Nothing here is a grade or physical safety approval.",
                        color = AresTextSecondary,
                        fontSize = 12.sp,
                    )
                }
            }
        }
        item {
            ClassroomPracticeSetupCard(
                projectPath = projectPath,
                projectLabel = projectLabel,
                statusMessage = statusMessage,
                onCreatePracticeProject = onCreatePracticeProject,
                onInstallPracticePack = { confirmPracticePack = true },
                onOpenImports = onOpenImports,
                onOpenRunReview = onOpenRunReview,
            )
        }
        item {
            ClassroomSection("Student and learning path") {
                Text(
                    "Learner records stay separate on this computer. Switching students never merges checkpoints, reflections, notes, or assignments.",
                    color = AresTextSecondary,
                    fontSize = 12.sp,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    classroom.learners.forEach { record ->
                        val label = record.progress.studentDisplayName.ifBlank { "Unnamed learner" }
                        FilterChip(
                            selected = record.learnerId == classroom.activeLearnerId,
                            onClick = { scope.launch { progressService.switchStudent(record.learnerId) } },
                            label = { Text(label) },
                            modifier = Modifier.semantics {
                                stateDescription = if (record.learnerId == classroom.activeLearnerId) {
                                    "Active learner record"
                                } else {
                                    "Saved learner record"
                                }
                            },
                        )
                    }
                }
                OutlinedTextField(
                    value = studentDraft,
                    onValueChange = { studentDraft = it.take(80) },
                    label = { Text("Student display name (stored only on this computer)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    onClick = { scope.launch { progressService.updateStudentDisplayName(studentDraft) } },
                    enabled = studentDraft.trim() != progress.studentDisplayName,
                    colors = ButtonDefaults.buttonColors(containerColor = AresCyan, contentColor = AresOnAccent),
                ) { Text("Save student name") }
                OutlinedButton(
                    onClick = { confirmNewStudent = true },
                    enabled = studentDraft.isNotBlank(),
                ) { Text("Add separate student") }
                Text(
                    "${classroom.learners.size} local learner record${if (classroom.learners.size == 1) "" else "s"}. Select a name above to resume that student's work.",
                    color = AresTextSecondary,
                    fontSize = 11.sp,
                )
                Text("Choose a path to review or export.", color = AresTextSecondary, fontSize = 12.sp)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    LearningCatalog.paths.forEach { path ->
                        FilterChip(
                            selected = selectedPathId == path.id,
                            onClick = {
                                selectedPathId = path.id
                                scope.launch { progressService.selectPath(path.id) }
                            },
                            label = { Text(path.title) },
                            modifier = Modifier.semantics {
                                stateDescription = if (selectedPathId == path.id) "Selected learning path" else "Available learning path"
                            },
                        )
                    }
                }
                Text(
                    "${summary.practicedLessons} of ${summary.path.lessonIds.size} lessons practiced · " +
                        "${summary.completedCheckpoints} of ${summary.totalCheckpoints} checkpoints recorded",
                    color = AresGreen,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "Suggested next: ${summary.recommendedLesson?.title ?: "Choose another path or revisit a lesson with new evidence."}",
                    color = AresTextPrimary,
                )
            }
        }
        item {
            ClassroomSection("Assignments and worksheets") {
                Text(
                    "Create a bounded lesson assignment for the active learner. Assignment completion is a mentor/student checklist; lesson evidence remains separate.",
                    color = AresTextSecondary,
                    fontSize = 12.sp,
                )
                OutlinedTextField(
                    value = assignmentTitle,
                    onValueChange = { assignmentTitle = it.take(120) },
                    label = { Text("Assignment title") },
                    placeholder = { Text("Example: Trace one safe mechanism") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = assignmentDue,
                    onValueChange = { assignmentDue = it.take(120) },
                    label = { Text("Due date or class period (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = assignmentInstructions,
                    onValueChange = { assignmentInstructions = it.take(4_000) },
                    label = { Text("Mentor instructions (optional)") },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("Choose lessons", color = AresTextPrimary, fontWeight = FontWeight.SemiBold)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    summary.path.lessonIds.mapNotNull(LearningCatalog::lesson).forEach { lesson ->
                        FilterChip(
                            selected = lesson.id in assignmentLessonIds,
                            onClick = {
                                assignmentLessonIds = if (lesson.id in assignmentLessonIds) {
                                    assignmentLessonIds - lesson.id
                                } else {
                                    assignmentLessonIds + lesson.id
                                }
                            },
                            label = { Text(lesson.title, fontSize = 11.sp) },
                        )
                    }
                }
                Button(
                    onClick = {
                        scope.launch {
                            runCatching {
                                progressService.createAssignment(
                                    title = assignmentTitle,
                                    pathId = summary.path.id,
                                    lessonIds = assignmentLessonIds.toList(),
                                    instructions = assignmentInstructions,
                                    dueLabel = assignmentDue,
                                )
                            }.onSuccess {
                                assignmentTitle = ""
                                assignmentInstructions = ""
                                assignmentDue = ""
                                assignmentLessonIds = emptySet()
                                statusMessage = "Assignment added for ${progress.studentDisplayName.ifBlank { "the active learner" }}."
                            }.onFailure { statusMessage = "Assignment was not added: ${it.message ?: "unknown error"}" }
                        }
                    },
                    enabled = assignmentTitle.isNotBlank() && assignmentLessonIds.isNotEmpty(),
                    colors = ButtonDefaults.buttonColors(containerColor = AresCyan, contentColor = AresOnAccent),
                ) { Text("Add assignment") }
                if (activeRecord.assignments.isEmpty()) {
                    Text("No assignments for this learner yet.", color = AresTextTertiary, fontSize = 11.sp)
                } else {
                    activeRecord.assignments.forEach { assignment ->
                        AssignmentCard(
                            assignment = assignment,
                            studentName = progress.studentDisplayName,
                            onCompletedChange = { completed ->
                                scope.launch { progressService.setAssignmentCompleted(assignment.assignmentId, completed) }
                            },
                            onExportWorksheet = {
                                chooseAssignmentWorksheetFile(progress.studentDisplayName, assignment.title)?.let { target ->
                                    scope.launch {
                                        runCatching {
                                            withContext(Dispatchers.IO) {
                                                val markdown = AcademyClassroomToolkit.assignmentWorksheet(assignment, progress.studentDisplayName)
                                                com.ares.analytics.service.writeFileAtomically(target) { temporary -> temporary.writeText(markdown) }
                                            }
                                        }.onSuccess { statusMessage = "Worksheet exported to ${target.path}." }
                                            .onFailure { statusMessage = "Worksheet export failed: ${it.message ?: "unknown error"}" }
                                    }
                                }
                            },
                            onRemove = { scope.launch { progressService.removeAssignment(assignment.assignmentId) } },
                        )
                    }
                }
            }
        }
        item {
            ClassroomSection("Mentor rubric") {
                Text(
                    "Ratings are local coaching notes. Review the student's explanation and named evidence; do not infer competence from completed buttons alone.",
                    color = AresTextSecondary,
                    fontSize = 12.sp,
                )
                AcademyClassroomToolkit.rubricCriteria.forEach { criterion ->
                    val selected = progress.rubricRatings[criterion.id] ?: LearningRubricRating.NOT_REVIEWED
                    Surface(
                        color = AresSurfaceElevated,
                        border = BorderStroke(1.dp, AresBorder),
                        shape = RoundedCornerShape(9.dp),
                    ) {
                        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(criterion.title, color = AresTextPrimary, fontWeight = FontWeight.Bold)
                            Text(criterion.prompt, color = AresTextSecondary, fontSize = 12.sp)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                LearningRubricRating.entries.forEach { rating ->
                                    FilterChip(
                                        selected = selected == rating,
                                        onClick = { scope.launch { progressService.setRubricRating(criterion.id, rating) } },
                                        label = { Text(rating.label, fontSize = 11.sp) },
                                        modifier = Modifier.semantics {
                                            stateDescription = if (selected == rating) "Selected rating" else "Available rating"
                                        },
                                    )
                                }
                            }
                            Text("Boundary: ${criterion.boundary}", color = AresAmber, fontSize = 11.sp)
                        }
                    }
                }
            }
        }
        item {
            ClassroomSection("Lesson evidence") {
                summary.path.lessonIds.mapNotNull(LearningCatalog::lesson).forEach { lesson ->
                    val journey = LearningJourneyEvaluator.lessonState(lesson, progress)
                    var noteDraft by remember(lesson.id, progress.mentorNotes[lesson.id]) {
                        mutableStateOf(progress.mentorNotes[lesson.id].orEmpty())
                    }
                    Surface(
                        color = AresSurfaceElevated,
                        border = BorderStroke(1.dp, AresBorder),
                        shape = RoundedCornerShape(9.dp),
                    ) {
                        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Column(Modifier.weight(1f)) {
                                    Text(lesson.title, color = AresTextPrimary, fontWeight = FontWeight.Bold)
                                    Text(
                                        "${journey.status.label} · ${journey.completedCheckpointCount} / ${lesson.checkpoints.size} checkpoints",
                                        color = statusColor(journey.status),
                                        fontSize = 11.sp,
                                    )
                                }
                            }
                            val reflections = lesson.checkpoints.mapNotNull { checkpoint ->
                                progress.checkpointReflections[checkpoint.id]?.let { checkpoint.title to it }
                            }
                            if (reflections.isEmpty()) {
                                Text("No written student reflection recorded for this lesson.", color = AresTextTertiary, fontSize = 11.sp)
                            } else {
                                reflections.forEach { (title, reflection) ->
                                    Text("Student · $title: $reflection", color = AresTextSecondary, fontSize = 11.sp)
                                }
                            }
                            OutlinedTextField(
                                value = noteDraft,
                                onValueChange = { noteDraft = it.take(4_000) },
                                label = { Text("Mentor note") },
                                placeholder = { Text("Prompt used, evidence discussed, or suggested next practice") },
                                minLines = 2,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            OutlinedButton(
                                onClick = { scope.launch { progressService.updateMentorNote(lesson.id, noteDraft) } },
                                enabled = noteDraft.trim() != progress.mentorNotes[lesson.id].orEmpty(),
                            ) { Text("Save mentor note") }
                        }
                    }
                }
            }
        }
        item {
            ClassroomSection("Export or restart") {
                OutlinedTextField(
                    value = mentorName,
                    onValueChange = { mentorName = it.take(80) },
                    label = { Text("Mentor name for the exported record") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            chooseAcademyReportFile(progress.studentDisplayName, summary.path.id)?.let { target ->
                                scope.launch {
                                    runCatching { progressService.exportMentorReport(target, summary.path.id, mentorName) }
                                        .onSuccess { statusMessage = "Learning record exported to ${target.path}." }
                                        .onFailure { statusMessage = "Export failed: ${it.message ?: "unknown error"}" }
                                }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = AresCyan, contentColor = AresOnAccent),
                    ) { Text("Export learning record") }
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                runCatching { progressService.saveLocalSnapshot(summary.path.id, mentorName) }
                                    .onSuccess { snapshot ->
                                        statusMessage = "Immutable local snapshot saved to ${snapshot.file.path}."
                                    }
                                    .onFailure { statusMessage = "Snapshot failed: ${it.message ?: "unknown error"}" }
                            }
                        },
                    ) { Text("Save local snapshot") }
                    OutlinedButton(onClick = { confirmResetPath = true }) {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Restart this path")
                    }
                }
                Text(
                    "Exports contain local progress, written reflections, mentor notes, and rubric ratings. They contain no OAuth tokens, robot credentials, or telemetry database rows.",
                    color = AresTextSecondary,
                    fontSize = 11.sp,
                )
            }
        }
    }

    if (confirmResetPath) {
        AlertDialog(
            onDismissRequest = { confirmResetPath = false },
            title = { Text("Restart ${summary.path.title}?") },
            text = { Text("This removes progress, reflections, and mentor notes for lessons in this path. A lesson shared with another path restarts there too. Rubric ratings remain. Export first if you need a record.") },
            confirmButton = {
                Button(onClick = {
                    confirmResetPath = false
                    scope.launch { progressService.resetPath(summary.path.id) }
                }) { Text("Restart path") }
            },
            dismissButton = { TextButton(onClick = { confirmResetPath = false }) { Text("Cancel") } },
        )
    }

    if (confirmNewStudent) {
        AlertDialog(
            onDismissRequest = { confirmNewStudent = false },
            title = { Text("Add a separate student record?") },
            text = { Text("ARES will preserve the current learner and switch to a new, empty record for ${studentDraft.trim()}. You can switch back from the learner list at any time.") },
            confirmButton = {
                Button(onClick = {
                    confirmNewStudent = false
                    scope.launch {
                        progressService.startNewStudent(studentDraft)
                        selectedPathId = LearningCatalog.paths.first().id
                    }
                }) { Text("Add and switch") }
            },
            dismissButton = { TextButton(onClick = { confirmNewStudent = false }) { Text("Cancel") } },
        )
    }

    if (confirmPracticePack) {
        AlertDialog(
            onDismissRequest = { confirmPracticePack = false },
            title = { Text("Install and import synthetic practice runs?") },
            text = {
                Text("ARES will add two small CSV teaching datasets under .ares/academy/practice-runs and import them into Run History. Existing files are never replaced, and repeated clicks reuse prior imports. The data is synthetic—not robot or simulator evidence.")
            },
            confirmButton = {
                Button(onClick = {
                    confirmPracticePack = false
                    scope.launch {
                        runCatching { onInstallAndImportPracticeRuns() }
                            .onSuccess { result ->
                                statusMessage = "Practice runs ready: ${result.importedCount} imported, ${result.reusedCount} already present. Open Guided Run Review to compare them."
                            }
                            .onFailure { statusMessage = "Practice runs were not ready: ${it.message ?: "unknown error"}" }
                    }
                }) { Text("Install and import") }
            },
            dismissButton = { TextButton(onClick = { confirmPracticePack = false }) { Text("Cancel") } },
        )
    }
}

