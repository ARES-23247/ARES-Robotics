package com.ares.analytics.viewmodel

import com.ares.analytics.service.project.RobotProjectTemplateService
import java.io.File

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
            state.projectSetupMode.createsProject && state.projectParentPath.isBlank() ->
                "Choose where ARES should create the robot project."
            state.projectSetupMode.createsProject && !File(state.projectParentPath.trim()).isDirectory ->
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
            state.teamId.any { !it.isDigit() } -> "Use numbers only for the team number."
            else -> null
        },
        seasonId = when {
            !validateRobot -> null
            state.seasonId.isBlank() -> "Enter the season, for example 2026."
            else -> null
        },
        robotId = when {
            !validateRobot -> null
            state.robotId.isBlank() -> "Enter a short robot ID."
            else -> null
        },
    )
}

/** Build-tool readiness is advisory; local authoring remains available without a supported JDK. */
internal fun validateOnboardingCompletion(state: OnboardingState): OnboardingFieldErrors =
    validateOnboardingFields(state, OnboardingStep.REVIEW)
