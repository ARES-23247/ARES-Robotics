package com.ares.analytics.service

import com.ares.analytics.ui.help.AcademyRuntimeSnapshot
import com.ares.analytics.ui.help.LearningCatalog
import com.ares.analytics.ui.help.LearningCheckpointEvidence
import com.ares.analytics.ui.help.LearningJourneyEvaluator
import com.ares.analytics.ui.help.LearningProgressView
import com.ares.analytics.ui.help.LearningRubricRating
import com.ares.analytics.ui.help.AcademyClassroomToolkit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/** Durable, local-only progress for the in-app Help & Learn lessons. */
@Serializable
data class LearningProgress(
    val contentVersion: Int = CURRENT_LEARNING_CONTENT_VERSION,
    override val practicedLessonIds: Set<String> = emptySet(),
    override val startedLessonIds: Set<String> = emptySet(),
    override val completedCheckpointIds: Set<String> = emptySet(),
    override val activeLessonId: String? = null,
    val selectedPathId: String? = null,
    val studentDisplayName: String = "",
    val checkpointReflections: Map<String, String> = emptyMap(),
    val mentorNotes: Map<String, String> = emptyMap(),
    val rubricRatings: Map<String, LearningRubricRating> = emptyMap(),
) : LearningProgressView

/** A mentor-authored, local assignment. It is guidance, not proof that a checkpoint was completed. */
@Serializable
data class AcademyLearningAssignment(
    val assignmentId: String,
    val title: String,
    val pathId: String,
    val lessonIds: List<String>,
    val instructions: String = "",
    val dueLabel: String = "",
    val completed: Boolean = false,
    val createdAtEpochMs: Long,
)

/** One isolated learner record. Records are switched explicitly and never merged automatically. */
@Serializable
data class AcademyLearnerRecord(
    val learnerId: String,
    val progress: LearningProgress = LearningProgress(),
    val assignments: List<AcademyLearningAssignment> = emptyList(),
)

@Serializable
data class AcademyClassroomStore(
    val schemaVersion: Int = ACADEMY_CLASSROOM_SCHEMA_VERSION,
    val activeLearnerId: String,
    val learners: List<AcademyLearnerRecord>,
)

data class AcademyProgressSnapshot(
    val file: File,
    val learnerId: String,
    val pathId: String,
)

/**
 * Stores self-reported lesson practice without claiming certification or hardware verification.
 * Content-version changes retain known lesson IDs and allow the UI to identify updated material.
 */
class LearningProgressService(
    private val progressFile: File = AppDataPaths.file("learning-progress.json"),
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val learnerIdFactory: () -> String = { "learner-${UUID.randomUUID()}" },
) {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private val writeMutex = Mutex()
    private val _classroom = MutableStateFlow(loadClassroomStore())
    val classroom: StateFlow<AcademyClassroomStore> = _classroom.asStateFlow()
    private val _progress = MutableStateFlow(activeRecord(_classroom.value).progress)
    val progress: StateFlow<LearningProgress> = _progress.asStateFlow()

    suspend fun setPracticed(lessonId: String, practiced: Boolean) = withContext(Dispatchers.IO) {
        require(lessonId.isNotBlank()) { "Lesson ID must not be blank" }
        updateProgress { current ->
            current.copy(
                contentVersion = CURRENT_LEARNING_CONTENT_VERSION,
                practicedLessonIds = if (practiced) {
                    current.practicedLessonIds + lessonId
                } else {
                    current.practicedLessonIds - lessonId
                },
                startedLessonIds = current.startedLessonIds + lessonId,
                activeLessonId = lessonId,
            )
        }
    }

    suspend fun startLesson(lessonId: String) = withContext(Dispatchers.IO) {
        require(lessonId.isNotBlank()) { "Lesson ID must not be blank" }
        updateProgress { current ->
            current.copy(
                contentVersion = CURRENT_LEARNING_CONTENT_VERSION,
                startedLessonIds = current.startedLessonIds + lessonId,
                activeLessonId = lessonId,
            )
        }
    }

    suspend fun selectPath(pathId: String) = withContext(Dispatchers.IO) {
        requireNotNull(LearningCatalog.path(pathId)) { "Unknown learning path '$pathId'" }
        updateProgress { current ->
            current.copy(contentVersion = CURRENT_LEARNING_CONTENT_VERSION, selectedPathId = pathId)
        }
    }

    suspend fun updateStudentDisplayName(name: String) = withContext(Dispatchers.IO) {
        val normalized = name.trim().take(80)
        updateProgress { current ->
            current.copy(contentVersion = CURRENT_LEARNING_CONTENT_VERSION, studentDisplayName = normalized)
        }
    }

    /** Creates and selects a separate local learner record; prior learners remain available. */
    suspend fun startNewStudent(name: String) = withContext(Dispatchers.IO) {
        val normalized = name.trim().take(80)
        require(normalized.isNotEmpty()) { "Enter a student display name" }
        writeMutex.withLock {
            val record = AcademyLearnerRecord(
                learnerId = learnerIdFactory(),
                progress = LearningProgress(
                    contentVersion = CURRENT_LEARNING_CONTENT_VERSION,
                    studentDisplayName = normalized,
                    selectedPathId = LearningCatalog.paths.first().id,
                ),
            )
            val updated = _classroom.value.copy(
                activeLearnerId = record.learnerId,
                learners = _classroom.value.learners + record,
            )
            persistStore(updated)
            publishStore(updated)
        }
    }

    suspend fun switchStudent(learnerId: String) = withContext(Dispatchers.IO) {
        writeMutex.withLock {
            val current = _classroom.value
            require(current.learners.any { it.learnerId == learnerId }) { "Unknown learner record '$learnerId'" }
            if (current.activeLearnerId == learnerId) return@withLock
            val updated = current.copy(activeLearnerId = learnerId)
            persistStore(updated)
            publishStore(updated)
        }
    }

    suspend fun createAssignment(
        title: String,
        pathId: String,
        lessonIds: List<String>,
        instructions: String,
        dueLabel: String,
    ) = withContext(Dispatchers.IO) {
        val normalizedTitle = title.trim().take(120)
        val normalizedInstructions = instructions.trim().take(MAX_LEARNING_NOTE_LENGTH)
        val normalizedDue = dueLabel.trim().take(120)
        require(normalizedTitle.isNotEmpty()) { "Enter an assignment title" }
        val path = requireNotNull(LearningCatalog.path(pathId)) { "Unknown learning path '$pathId'" }
        val normalizedLessons = lessonIds.distinct()
        require(normalizedLessons.isNotEmpty()) { "Choose at least one lesson" }
        require(normalizedLessons.all { it in path.lessonIds && LearningCatalog.lesson(it) != null }) {
            "Assignments may contain only lessons from the selected learning path"
        }
        updateActiveRecord { record ->
            record.copy(
                assignments = record.assignments + AcademyLearningAssignment(
                    assignmentId = "assignment-${UUID.randomUUID()}",
                    title = normalizedTitle,
                    pathId = pathId,
                    lessonIds = normalizedLessons,
                    instructions = normalizedInstructions,
                    dueLabel = normalizedDue,
                    createdAtEpochMs = nowMillis(),
                ),
            )
        }
    }

    suspend fun setAssignmentCompleted(assignmentId: String, completed: Boolean) = withContext(Dispatchers.IO) {
        updateActiveRecord { record ->
            require(record.assignments.any { it.assignmentId == assignmentId }) { "Unknown assignment '$assignmentId'" }
            record.copy(
                assignments = record.assignments.map { assignment ->
                    if (assignment.assignmentId == assignmentId) assignment.copy(completed = completed) else assignment
                },
            )
        }
    }

    suspend fun removeAssignment(assignmentId: String) = withContext(Dispatchers.IO) {
        updateActiveRecord { record ->
            require(record.assignments.any { it.assignmentId == assignmentId }) { "Unknown assignment '$assignmentId'" }
            record.copy(assignments = record.assignments.filterNot { it.assignmentId == assignmentId })
        }
    }

    suspend fun saveLocalSnapshot(pathId: String, mentorName: String): AcademyProgressSnapshot =
        withContext(Dispatchers.IO) {
            val record = activeRecord(_classroom.value)
            val report = AcademyClassroomToolkit.markdownReport(record.progress, pathId, mentorName)
            val root = File(progressFile.parentFile ?: File("."), "academy-snapshots").canonicalFile
            root.mkdirs()
            check(root.isDirectory) { "ARES could not create the Academy snapshot directory" }
            val safeLearnerId = record.learnerId.replace(Regex("[^a-zA-Z0-9._-]"), "-")
            val safePathId = pathId.replace(Regex("[^a-zA-Z0-9._-]"), "-")
            val target = uniqueSnapshotFile(root, "$safeLearnerId-$safePathId-${nowMillis()}")
            writeFileAtomically(target) { temporary -> temporary.writeText(report) }
            AcademyProgressSnapshot(target, record.learnerId, pathId)
        }

    fun snapshotsFor(learnerId: String): List<File> {
        require(_classroom.value.learners.any { it.learnerId == learnerId }) { "Unknown learner record '$learnerId'" }
        val root = File(progressFile.parentFile ?: File("."), "academy-snapshots")
        if (!root.isDirectory) return emptyList()
        val prefix = learnerId.replace(Regex("[^a-zA-Z0-9._-]"), "-") + "-"
        return root.listFiles { file -> file.isFile && file.name.startsWith(prefix) && file.extension == "md" }
            ?.sortedByDescending(File::getName)
            .orEmpty()
    }

    /** Records a student's own explanation; it never converts that reflection into observed runtime evidence. */
    suspend fun recordReflection(checkpointId: String, reflection: String) = withContext(Dispatchers.IO) {
        val checkpoint = requireNotNull(
            LearningCatalog.lessons.asSequence()
                .flatMap { it.checkpoints.asSequence() }
                .firstOrNull { it.id == checkpointId },
        ) { "Unknown learning checkpoint '$checkpointId'" }
        require(checkpoint.evidence == LearningCheckpointEvidence.SELF_REPORTED) {
            "Only a student-reflection checkpoint accepts written reflection"
        }
        val normalized = reflection.trim()
        require(normalized.isNotEmpty()) { "Write a short reflection before recording this checkpoint" }
        require(normalized.length <= MAX_LEARNING_NOTE_LENGTH) { "Reflection is too long" }
        updateProgress { current ->
            current.copy(
                contentVersion = CURRENT_LEARNING_CONTENT_VERSION,
                completedCheckpointIds = current.completedCheckpointIds + checkpointId,
                checkpointReflections = current.checkpointReflections + (checkpointId to normalized),
            )
        }
    }

    suspend fun updateMentorNote(lessonId: String, note: String) = withContext(Dispatchers.IO) {
        requireNotNull(LearningCatalog.lesson(lessonId)) { "Unknown lesson '$lessonId'" }
        val normalized = note.trim()
        require(normalized.length <= MAX_LEARNING_NOTE_LENGTH) { "Mentor note is too long" }
        updateProgress { current ->
            current.copy(
                contentVersion = CURRENT_LEARNING_CONTENT_VERSION,
                mentorNotes = if (normalized.isEmpty()) current.mentorNotes - lessonId
                else current.mentorNotes + (lessonId to normalized),
            )
        }
    }

    suspend fun setRubricRating(criterionId: String, rating: LearningRubricRating) = withContext(Dispatchers.IO) {
        require(AcademyClassroomToolkit.rubricCriteria.any { it.id == criterionId }) {
            "Unknown learning rubric criterion '$criterionId'"
        }
        updateProgress { current ->
            current.copy(
                contentVersion = CURRENT_LEARNING_CONTENT_VERSION,
                rubricRatings = if (rating == LearningRubricRating.NOT_REVIEWED) {
                    current.rubricRatings - criterionId
                } else {
                    current.rubricRatings + (criterionId to rating)
                },
            )
        }
    }

    suspend fun resetLesson(lessonId: String) = withContext(Dispatchers.IO) {
        val lesson = requireNotNull(LearningCatalog.lesson(lessonId)) { "Unknown lesson '$lessonId'" }
        val checkpointIds = lesson.checkpoints.mapTo(mutableSetOf()) { it.id }
        updateProgress { current ->
            current.copy(
                contentVersion = CURRENT_LEARNING_CONTENT_VERSION,
                practicedLessonIds = current.practicedLessonIds - lessonId,
                startedLessonIds = current.startedLessonIds - lessonId,
                completedCheckpointIds = current.completedCheckpointIds - checkpointIds,
                activeLessonId = current.activeLessonId?.takeUnless { it == lessonId },
                checkpointReflections = current.checkpointReflections - checkpointIds,
                mentorNotes = current.mentorNotes - lessonId,
            )
        }
    }

    suspend fun resetPath(pathId: String) = withContext(Dispatchers.IO) {
        val path = requireNotNull(LearningCatalog.path(pathId)) { "Unknown learning path '$pathId'" }
        val lessonIds = path.lessonIds.toSet()
        val checkpointIds = lessonIds.asSequence()
            .mapNotNull(LearningCatalog::lesson)
            .flatMap { it.checkpoints.asSequence() }
            .mapTo(mutableSetOf()) { it.id }
        updateProgress { current ->
            current.copy(
                contentVersion = CURRENT_LEARNING_CONTENT_VERSION,
                practicedLessonIds = current.practicedLessonIds - lessonIds,
                startedLessonIds = current.startedLessonIds - lessonIds,
                completedCheckpointIds = current.completedCheckpointIds - checkpointIds,
                activeLessonId = current.activeLessonId?.takeUnless { it in lessonIds },
                checkpointReflections = current.checkpointReflections - checkpointIds,
                mentorNotes = current.mentorNotes - lessonIds,
            )
        }
    }

    suspend fun exportMentorReport(
        destination: File,
        pathId: String,
        mentorName: String,
    ) = withContext(Dispatchers.IO) {
        val report = AcademyClassroomToolkit.markdownReport(
            progress = _progress.value,
            pathId = pathId,
            mentorName = mentorName,
        )
        writeFileAtomically(destination) { temporary -> temporary.writeText(report) }
    }

    suspend fun setCheckpointCompleted(checkpointId: String, completed: Boolean) = withContext(Dispatchers.IO) {
        require(checkpointId.isNotBlank()) { "Checkpoint ID must not be blank" }
        val checkpoint = LearningCatalog.lessons.asSequence()
            .flatMap { it.checkpoints.asSequence() }
            .firstOrNull { it.id == checkpointId }
        require(checkpoint?.evidence == LearningCheckpointEvidence.SELF_REPORTED) {
            "Only a known student-reflection checkpoint can be changed manually"
        }
        require(!completed) { "Use recordReflection to complete a student checkpoint with written evidence" }
        updateProgress { current ->
            current.copy(
                contentVersion = CURRENT_LEARNING_CONTENT_VERSION,
                completedCheckpointIds = current.completedCheckpointIds - checkpointId,
                checkpointReflections = current.checkpointReflections - checkpointId,
            )
        }
    }

    suspend fun clearActiveLesson() = withContext(Dispatchers.IO) {
        updateProgress { current -> current.copy(activeLessonId = null) }
    }

    /** Records only process/connection facts. It never marks reflection, safety, or hardware checks. */
    suspend fun observeRuntime(runtime: AcademyRuntimeSnapshot) = withContext(Dispatchers.IO) {
        updateProgress { current ->
            val observed = LearningJourneyEvaluator.observableCheckpointIds(
                runtime = runtime,
                previouslyCompleted = current.completedCheckpointIds,
            )
            if (observed.isEmpty() || current.completedCheckpointIds.containsAll(observed)) {
                current
            } else {
                val observedLessonIds = LearningCatalog.lessons.asSequence()
                    .filter { lesson -> lesson.checkpoints.any { it.id in observed } }
                    .map { it.id }
                    .toSet()
                current.copy(
                    contentVersion = CURRENT_LEARNING_CONTENT_VERSION,
                    completedCheckpointIds = current.completedCheckpointIds + observed,
                    startedLessonIds = current.startedLessonIds + observedLessonIds,
                    activeLessonId = current.activeLessonId ?: observedLessonIds.singleOrNull(),
                )
            }
        }
    }

    private suspend fun updateProgress(transform: (LearningProgress) -> LearningProgress) {
        writeMutex.withLock {
            val classroom = _classroom.value
            val record = activeRecord(classroom)
            val updatedProgress = transform(record.progress)
            if (updatedProgress == record.progress) return@withLock
            val updated = classroom.replaceRecord(record.copy(progress = updatedProgress))
            persistStore(updated)
            publishStore(updated)
        }
    }

    private suspend fun updateActiveRecord(transform: (AcademyLearnerRecord) -> AcademyLearnerRecord) {
        writeMutex.withLock {
            val classroom = _classroom.value
            val record = activeRecord(classroom)
            val updatedRecord = transform(record)
            if (updatedRecord == record) return@withLock
            val updated = classroom.replaceRecord(updatedRecord)
            persistStore(updated)
            publishStore(updated)
        }
    }

    private fun loadClassroomStore(): AcademyClassroomStore {
        if (!progressFile.isFile) return emptyClassroomStore()
        return runCatching {
            normalizeStore(json.decodeFromString<AcademyClassroomStore>(progressFile.readText()))
        }.getOrElse { failure ->
            // A partially-written or unreadable store must not silently reset every learner's
            // records: the next persist would overwrite the file. Preserve the unreadable
            // bytes in a quarantine copy first so a roster can still be recovered by hand.
            quarantineUnreadableStore(failure)
            emptyClassroomStore()
        }
    }

    private fun quarantineUnreadableStore(failure: Throwable) {
        runCatching {
            val stamp = java.time.LocalDateTime.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
            val quarantine = File(progressFile.parentFile ?: File("."), "learning-progress.corrupt-$stamp.json")
            progressFile.copyTo(quarantine, overwrite = true)
            System.err.println(
                "LearningProgressService: classroom store '${progressFile.name}' could not be read " +
                    "(${failure.message}); quarantined a copy as '${quarantine.name}' before starting empty"
            )
        }.onFailure { copyFailure ->
            System.err.println(
                "LearningProgressService: classroom store '${progressFile.name}' is unreadable " +
                    "(${failure.message}) and the quarantine copy also failed ($copyFailure); " +
                    "the next persist will overwrite the unreadable file"
            )
        }
    }

    private fun normalizeStore(store: AcademyClassroomStore): AcademyClassroomStore {
        require(store.schemaVersion == ACADEMY_CLASSROOM_SCHEMA_VERSION) {
            "Unsupported Academy classroom schema ${store.schemaVersion}"
        }
        require(store.learners.all { it.progress.contentVersion == CURRENT_LEARNING_CONTENT_VERSION }) {
            "Academy learner content must use version $CURRENT_LEARNING_CONTENT_VERSION"
        }
        val validRecords = store.learners
            .distinctBy(AcademyLearnerRecord::learnerId)
        if (validRecords.isEmpty()) return emptyClassroomStore()
        val active = store.activeLearnerId.takeIf { id -> validRecords.any { it.learnerId == id } }
            ?: validRecords.first().learnerId
        return store.copy(
            schemaVersion = ACADEMY_CLASSROOM_SCHEMA_VERSION,
            activeLearnerId = active,
            learners = validRecords,
        )
    }

    private fun emptyClassroomStore(): AcademyClassroomStore = AcademyClassroomStore(
        activeLearnerId = DEFAULT_LEARNER_ID,
        learners = listOf(AcademyLearnerRecord(DEFAULT_LEARNER_ID)),
    )

    private fun activeRecord(store: AcademyClassroomStore): AcademyLearnerRecord =
        requireNotNull(store.learners.firstOrNull { it.learnerId == store.activeLearnerId }) {
            "Academy classroom store has no active learner"
        }

    private fun AcademyClassroomStore.replaceRecord(record: AcademyLearnerRecord): AcademyClassroomStore =
        copy(learners = learners.map { if (it.learnerId == record.learnerId) record else it })

    private fun persistStore(store: AcademyClassroomStore) {
        writeFileAtomically(progressFile) { temporary ->
            temporary.writeText(json.encodeToString(store))
        }
    }

    private fun publishStore(store: AcademyClassroomStore) {
        _classroom.value = store
        _progress.value = activeRecord(store).progress
    }

    private fun uniqueSnapshotFile(directory: File, baseName: String): File {
        var suffix = 0
        while (true) {
            val name = if (suffix == 0) "$baseName.md" else "$baseName-$suffix.md"
            val candidate = File(directory, name).canonicalFile
            check(candidate.parentFile == directory) { "Academy snapshot destination escaped its directory" }
            if (!candidate.exists()) return candidate
            suffix++
        }
    }

}

const val CURRENT_LEARNING_CONTENT_VERSION = 5
const val ACADEMY_CLASSROOM_SCHEMA_VERSION = 1
private const val MAX_LEARNING_NOTE_LENGTH = 4_000
private const val DEFAULT_LEARNER_ID = "learner-default"
