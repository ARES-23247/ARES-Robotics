package com.ares.analytics.ui.help

import com.ares.analytics.ui.components.NavigationTarget

enum class LearningLevel(val label: String, val explanation: String) {
    STARTER("Foundations", "Core kinematics, simulation, and basic controls"),
    BUILDER("Architecture", "Subsystems, Redux stateflow, and mechanism coordination"),
    ADVANCED("Mastery", "Control theory, live tuning, and autonomous forensics"),
}

enum class LearningTrack(val label: String) {
    DRIVETRAIN("Drivetrains & Odometry"),
    SUBSYSTEMS("Subsystems & Architecture"),
    SUPERSTRUCTURE("Superstructure & Stateflow"),
    CONTROL("Control Theory & Live Tuning"),
    AUTONOMOUS("Autonomous & Telemetry"),
}

enum class LearningAction { OPEN_SCREEN, START_SIMULATOR, OPEN_LAB }

enum class LearningLab(val label: String) {
    CONTROL("Control response"),
    TUNING_MISSIONS("Control challenges"),
    SENSOR_FUSION("Sensor fusion"),
    KINEMATICS_VECTORS("Drive kinematics"),
    MOTION_PROFILE("Motion profile"),
    MECHANISM_SIZING("Mechanism sizing"),
    HOMING_SAFETY("Homing & safe recovery"),
    STATE_FLOW("Input, state & telemetry"),
    AUTONOMOUS_SAFETY("Autonomous planning"),
}

data class LearningLabGuide(
    val lab: LearningLab,
    val title: String,
    val outcome: String,
    val beforeYouStart: List<String>,
    val tryThis: List<String>,
    val reflectionQuestions: List<String>,
    val successLooksLike: String,
)

data class LearningPath(
    val id: String,
    val title: String,
    val summary: String,
    val level: LearningLevel,
    val lessonIds: List<String>,
)

data class LearningLesson(
    val id: String,
    val level: LearningLevel,
    val track: LearningTrack,
    val title: String,
    val outcome: String,
    val durationMinutes: Int,
    val destination: NavigationTarget,
    val action: LearningAction = LearningAction.OPEN_SCREEN,
    val requiresRobot: Boolean = false,
    val beforeYouStart: List<String>,
    val steps: List<String>,
    val successLooksLike: String,
    val safetyNote: String? = null,
    val keywords: Set<String> = emptySet(),
    val prerequisiteLessonIds: Set<String> = emptySet(),
    val checkpoints: List<LearningCheckpoint> = emptyList(),
    val lab: LearningLab? = null,
)

/** Query facade over the single validated Academy resource bundled with Studio. */
object LearningCatalog {
    private val document: AcademyCatalogDocument = AcademyCatalogCodec.loadBundled()
    private val lessonsById: Map<String, LearningLesson> = document.lessons.associateBy(LearningLesson::id)
    private val pathsById: Map<String, LearningPath> = document.paths.associateBy(LearningPath::id)
    private val labGuidesByLab: Map<LearningLab, LearningLabGuide> =
        document.labGuides.associateBy(LearningLabGuide::lab)
    private val contextualLessonIds: Map<NavigationTarget, String> =
        document.contextualLessonIds.mapKeys { (targetName, _) -> NavigationTarget.valueOf(targetName) }

    val labGuides: List<LearningLabGuide> = document.labGuides
    val lessons: List<LearningLesson> = document.lessons
    val paths: List<LearningPath> = document.paths

    fun lessonFor(target: NavigationTarget): LearningLesson? = contextualLessonIds[target]?.let(lessonsById::get)

    fun lesson(id: String): LearningLesson? = lessonsById[id]

    fun path(id: String): LearningPath? = pathsById[id]

    fun labGuide(lab: LearningLab): LearningLabGuide = checkNotNull(labGuidesByLab[lab]) {
        "Validated Academy catalog is missing guide for $lab"
    }

    fun lessonsForPath(pathId: String): List<LearningLesson> =
        path(pathId)?.lessonIds.orEmpty().mapNotNull(lessonsById::get)

    fun search(query: String, level: LearningLevel? = null, pathId: String? = null): List<LearningLesson> {
        val normalized = query.trim().lowercase()
        val pathLessonIds = pathId?.let(::path)?.lessonIds?.toSet()
        return lessons.filter { lesson ->
            (level == null || lesson.level == level) &&
                (pathLessonIds == null || lesson.id in pathLessonIds) &&
                (normalized.isBlank() || listOf(
                    lesson.title,
                    lesson.outcome,
                    lesson.track.label,
                    lesson.level.label,
                    lesson.keywords.joinToString(" "),
                    lesson.steps.joinToString(" "),
                    lesson.beforeYouStart.joinToString(" "),
                ).any { normalized in it.lowercase() })
        }
    }
}
