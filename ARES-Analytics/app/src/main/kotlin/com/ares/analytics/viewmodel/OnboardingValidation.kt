package com.ares.analytics.viewmodel

import com.ares.analytics.service.project.RobotProjectTemplateService
import java.io.File

internal fun defaultProjectsParentDirectory(
    userHome: String = System.getProperty("user.home").orEmpty(),
): String {
    val trimmed = userHome.trim()
    return if (trimmed.isBlank()) "" else File(trimmed, "ARESStudioProjects").path
}

internal fun isCreatableProjectsParentDirectory(path: String): Boolean {
    val trimmed = path.trim()
    if (trimmed.isBlank()) return false
    val dir = File(trimmed)
    return dir.isDirectory || (!dir.exists() && dir.parentFile?.isDirectory == true)
}

internal fun canSelectOnboardingStep(
    state: OnboardingState,
    target: OnboardingStep,
): Boolean {
    if (state.isSaving || state.isDetectingProject) return false
    if (target.ordinal <= state.currentStep.ordinal) {
        if (state.projectSetupMode.isExample && (target == OnboardingStep.ROBOT || target == OnboardingStep.OPTIONAL)) {
            return false
        }
        return true
    }
    return when (target) {
        OnboardingStep.PROJECT -> true
        OnboardingStep.ROBOT -> !state.projectSetupMode.isExample && state.isProjectReady
        OnboardingStep.OPTIONAL -> !state.projectSetupMode.isExample && state.isProjectReady && state.isRobotReady
        OnboardingStep.REVIEW -> if (state.projectSetupMode.isExample) state.isProjectReady else state.isProjectReady && state.isRobotReady
    }
}

internal fun plannedProjectPath(state: OnboardingState): String {
    val parent = state.projectParentPath.trim()
    val name = state.projectFolderName.trim()
    return if (parent.isBlank() || name.isBlank()) "" else File(parent, name).path
}

/** Advances only when detection recognizes a project; partial paths never pull the student back. */
internal fun advanceAfterDetection(currentStep: OnboardingStep, recognizedProject: Boolean): OnboardingStep =
    if (currentStep == OnboardingStep.PROJECT && recognizedProject) OnboardingStep.ROBOT else currentStep

internal data class JavaBuildToolsReadiness(
    val isValid: Boolean,
    val majorVersion: Int?,
    val message: String,
)

internal fun evaluateJavaBuildTools(commandSucceeded: Boolean, rawMessage: String): JavaBuildToolsReadiness {
    if (!commandSucceeded) {
        return JavaBuildToolsReadiness(
            isValid = false,
            majorVersion = null,
            message = "ARES Robotics Studio is ready. Robot builds and simulation need JDK 17 or 21, but a supported JDK was not found.",
        )
    }
    val major = parseJavaMajorVersion(rawMessage)
    return when (major) {
        17, 21 -> JavaBuildToolsReadiness(true, major, "JDK $major is ready for robot builds and simulation.")
        null -> JavaBuildToolsReadiness(
            false,
            null,
            "ARES Robotics Studio is ready, but the Java version could not be identified. Install JDK 17 or 21 before building or simulating a robot.",
        )
        else -> JavaBuildToolsReadiness(
            false,
            major,
            "ARES Robotics Studio is ready, but robot builds and simulation need JDK 17 or 21. We found Java $major; install a supported JDK and ARES will discover it automatically.",
        )
    }
}

internal fun parseJavaMajorVersion(message: String): Int? {
    val version = Regex("(?:java|openjdk) version \"([^\"]+)\"", RegexOption.IGNORE_CASE)
        .find(message)?.groupValues?.get(1)
        ?: Regex("version[=: ]+([0-9]+(?:\\.[0-9]+)*)", RegexOption.IGNORE_CASE)
            .find(message)?.groupValues?.get(1)
        ?: return null
    val parts = version.split('.')
    val first = parts.firstOrNull()?.takeWhile(Char::isDigit)?.toIntOrNull() ?: return null
    return if (first == 1) parts.getOrNull(1)?.takeWhile(Char::isDigit)?.toIntOrNull() else first
}

internal fun validateOnboardingFields(
    state: OnboardingState,
    throughStep: OnboardingStep,
): OnboardingFieldErrors {
    val validateProject = throughStep.ordinal >= OnboardingStep.PROJECT.ordinal
    val validateRobot = throughStep.ordinal >= OnboardingStep.ROBOT.ordinal
    return OnboardingFieldErrors(
        projectPath = when {
            !validateProject -> null
            !state.projectSetupMode.createsProject && state.fieldErrors.projectPath != null -> state.fieldErrors.projectPath
            state.projectSetupMode.createsProject && state.projectParentPath.isBlank() ->
                "Choose where ARES should create the robot project."
            state.projectSetupMode.createsProject && !isCreatableProjectsParentDirectory(state.projectParentPath) ->
                "The parent folder does not exist or cannot be opened."
            state.projectSetupMode.createsProject ->
                RobotProjectTemplateService.projectFolderNameError(state.projectFolderName.trim())
                    ?: File(state.projectParentPath.trim(), state.projectFolderName.trim())
                        .takeIf(File::exists)?.let { "A file or folder already exists at ${it.path}." }
            state.projectPath.isBlank() -> "Choose your robot project folder."
            !File(state.projectPath.trim()).isDirectory -> "This folder does not exist or cannot be opened."
            else -> null
        },
        teamId = when {
            !validateRobot -> null
            state.teamId.isBlank() -> "Enter your FIRST team number."
            state.teamId.trim().any { !it.isDigit() } -> "Use numbers only for the team number."
            state.projectSetupMode.createsProject && state.teamId.trim().length > 32 -> "Use 32 or fewer digits for the team number."
            else -> null
        },
        seasonId = when {
            !validateRobot -> null
            state.seasonId.isBlank() -> "Enter the season, for example 2026."
            state.projectSetupMode.createsProject && !state.seasonId.trim().matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,31}")) ->
                "Use 1–32 letters, numbers, dots, underscores, or dashes for the season."
            else -> null
        },
        robotId = when {
            !validateRobot -> null
            state.robotId.isBlank() -> "Enter a short robot ID."
            state.projectSetupMode.createsProject && !state.robotId.trim().matches(Regex("[A-Za-z][A-Za-z0-9._-]{0,63}")) ->
                "Start with a letter; use up to 64 letters, numbers, dots, underscores, or dashes."
            else -> null
        },
        robotName = if (validateRobot && state.projectSetupMode.createsProject && state.robotName.trim().length > 80) {
            "Use 80 or fewer characters for the friendly name."
        } else null,
    )
}

/** Build-tool readiness is advisory; local authoring remains available without a supported JDK. */
internal fun validateOnboardingCompletion(state: OnboardingState): OnboardingFieldErrors =
    validateOnboardingFields(state, OnboardingStep.REVIEW)

internal const val LIGHTBOT_PROJECT_FOLDER = "Lightbot-Example"
internal const val LIGHTBOT_TEAM_ID = "23247"
internal const val LIGHTBOT_SEASON_ID = "2026"
internal const val LIGHTBOT_ROBOT_ID = "Lightbot"
internal const val LIGHTBOT_ROBOT_NAME = "Lightbot"

