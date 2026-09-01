package com.ares.analytics.viewmodel

import com.ares.analytics.service.EnvironmentService
import com.ares.analytics.service.GoogleDriveService
import com.ares.analytics.service.ManagedToolchainInstallState
import com.ares.analytics.service.ManagedToolchainPaths
import com.ares.analytics.service.ManagedToolchainService
import com.ares.analytics.service.SyncEngineService
import com.ares.analytics.service.project.RobotProjectCreationRequest
import com.ares.analytics.service.project.RobotProjectTemplateService
import com.ares.analytics.shared.models.DriveDestinationConfig
import com.ares.analytics.shared.models.DriveDestinationType
import com.ares.analytics.shared.models.League
import com.ares.analytics.shared.models.RobotProfile
import com.ares.analytics.shared.models.WorkspaceConfig
import com.areslib.project.AresProjectAuthoringModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.awt.Desktop
import java.net.URI

enum class OnboardingStep(val number: Int) {
    PROJECT(1),
    ROBOT(2),
    OPTIONAL(3),
    REVIEW(4),
}

enum class ProjectSetupMode(val createsProject: Boolean) {
    CREATE_NEW(true),
    EXPLORE_DEMO(true),
    OPEN_EXISTING(false),
}

data class OnboardingFieldErrors(
    val projectPath: String? = null,
    val teamId: String? = null,
    val seasonId: String? = null,
    val robotId: String? = null,
) {
    val hasRequiredFieldErrors: Boolean
        get() = projectPath != null || teamId != null || seasonId != null || robotId != null
}

data class OnboardingState(
    val currentStep: OnboardingStep = OnboardingStep.PROJECT,
    val projectSetupMode: ProjectSetupMode = ProjectSetupMode.CREATE_NEW,
    val projectPath: String = "",
    val projectParentPath: String = "",
    val projectFolderName: String = "",
    val projectDetectionMessage: String? = null,
    val projectTemplateName: String = "ARES FTC",
    val projectTemplateVersion: String = "6.1.0",
    val projectCreationMessage: String? = null,
    val authoringModel: AresProjectAuthoringModel = AresProjectAuthoringModel.GUI_OWNED,
    val teamId: String = "",
    val seasonId: String = "",
    val robotId: String = "",
    val robotName: String = "",
    val league: League = League.FTC,
    val nt4Host: String = "192.168.43.1",
    val isVerifyingJava: Boolean = false,
    val javaEnvValid: Boolean? = null,
    val javaEnvMsg: String = "Robot build tools have not been checked yet.",
    val javaMajorVersion: Int? = null,
    val toolchainInstallState: ManagedToolchainInstallState = ManagedToolchainInstallState.Idle,
    val isSaving: Boolean = false,
    val saveSuccess: Boolean = false,
    val errorMessage: String? = null,
    val fieldErrors: OnboardingFieldErrors = OnboardingFieldErrors(),
    val simulatorCommand: String = "",
    val cloudRobots: List<RobotProfile> = emptyList(),
    val isCloudLoading: Boolean = false,
    val selectedOptionText: String = "Select a saved robot...",
    val cloudSetupExpanded: Boolean = false,
    val advancedSetupExpanded: Boolean = false,
    val driveDestination: DriveDestinationConfig? = null,
    val isDriveDestinationBusy: Boolean = false,
    val driveDestinationError: String? = null,
) {
    val isProjectReady: Boolean
        get() = projectPath.isNotBlank() && fieldErrors.projectPath == null

    val isRobotReady: Boolean
        get() = teamId.isNotBlank() && seasonId.isNotBlank() && robotId.isNotBlank() &&
            fieldErrors.teamId == null && fieldErrors.seasonId == null && fieldErrors.robotId == null
}

sealed class OnboardingIntent {
    data class SetProjectSetupMode(val mode: ProjectSetupMode) : OnboardingIntent()
    data class UpdateProjectPath(val projectPath: String) : OnboardingIntent()
    data class UpdateProjectParentPath(val path: String) : OnboardingIntent()
    data class UpdateProjectFolderName(val name: String) : OnboardingIntent()
    data class SetAuthoringModel(val model: AresProjectAuthoringModel) : OnboardingIntent()
    data class UpdateTeamId(val teamId: String) : OnboardingIntent()
    data class UpdateSeasonId(val seasonId: String) : OnboardingIntent()
    data class UpdateRobotId(val robotId: String) : OnboardingIntent()
    data class UpdateRobotName(val robotName: String) : OnboardingIntent()
    data class UpdateLeague(val league: League) : OnboardingIntent()
    data class UpdateNt4Host(val nt4Host: String) : OnboardingIntent()
    data class UpdateSimulatorCommand(val simulatorCommand: String) : OnboardingIntent()
    data class UpdateSelectedOptionText(val text: String) : OnboardingIntent()
    data class FetchCloudRobots(val token: String) : OnboardingIntent()
    data class SetCloudSetupExpanded(val expanded: Boolean) : OnboardingIntent()
    data class SetAdvancedSetupExpanded(val expanded: Boolean) : OnboardingIntent()
    data class ConfigureDriveDestination(
        val type: DriveDestinationType,
        val displayName: String,
        val existingFolderReference: String? = null,
        val sharedDriveId: String? = null,
    ) : OnboardingIntent()
    object DetectLeague : OnboardingIntent()
    object NextStep : OnboardingIntent()
    object PreviousStep : OnboardingIntent()
    object VerifyJava : OnboardingIntent()
    object InstallManagedJdk : OnboardingIntent()
    object SubmitConfig : OnboardingIntent()
}

/** Atomic staging hook used to give every app-created project a clean local baseline version. */
fun interface NewProjectHistoryInitializer {
    suspend fun initialize(stagedProjectPath: String)

    companion object {
        val NONE = NewProjectHistoryInitializer { }
    }
}

/** Builds and validates a workspace configuration before making it the active workspace. */
class OnboardingViewModel(
    private val environmentService: EnvironmentService,
    private val syncEngineService: SyncEngineService,
    private val googleDriveService: GoogleDriveService,
    private val projectTemplateService: RobotProjectTemplateService,
    private val managedToolchainService: ManagedToolchainService,
    private val projectHistoryInitializer: NewProjectHistoryInitializer = NewProjectHistoryInitializer.NONE,
    private val scope: CoroutineScope,
    private val onConfigured: (WorkspaceConfig) -> Unit,
) {
    private val initialTemplate = projectTemplateService.templateFor(League.FTC)
    private val _state = MutableStateFlow(
        OnboardingState(
            projectTemplateName = initialTemplate.displayName,
            projectTemplateVersion = initialTemplate.aresVersion,
        ),
    )
    val state: StateFlow<OnboardingState> = _state.asStateFlow()

    init {
        scope.launch {
            managedToolchainService.installState.collect { installState ->
                _state.update { it.copy(toolchainInstallState = installState) }
            }
        }
        handleIntent(OnboardingIntent.VerifyJava)
    }

    fun handleIntent(intent: OnboardingIntent) {
        scope.launch {
            when (intent) {
                is OnboardingIntent.SetProjectSetupMode -> {
                    _state.update { current ->
                        val selected = current.copy(
                            projectSetupMode = intent.mode,
                            projectDetectionMessage = null,
                            projectCreationMessage = null,
                            errorMessage = null,
                            fieldErrors = current.fieldErrors.copy(projectPath = null),
                        )
                        when (intent.mode) {
                            ProjectSetupMode.CREATE_NEW -> selected.copy(projectPath = plannedProjectPath(selected))
                            ProjectSetupMode.EXPLORE_DEMO -> {
                                val template = projectTemplateService.templateFor(League.FTC)
                                val demo = selected.copy(
                                    projectFolderName = DEMO_PROJECT_FOLDER,
                                    teamId = DEMO_TEAM_ID,
                                    seasonId = DEMO_SEASON_ID,
                                    robotId = DEMO_ROBOT_ID,
                                    robotName = DEMO_ROBOT_NAME,
                                    league = League.FTC,
                                    nt4Host = "127.0.0.1",
                                    simulatorCommand = "",
                                    projectTemplateName = template.displayName,
                                    projectTemplateVersion = template.aresVersion,
                                )
                                demo.copy(projectPath = plannedProjectPath(demo))
                            }
                            ProjectSetupMode.OPEN_EXISTING -> selected.copy(projectPath = "")
                        }
                    }
                }
                is OnboardingIntent.UpdateProjectPath -> {
                    _state.update {
                        it.copy(
                            projectPath = intent.projectPath,
                            projectDetectionMessage = null,
                            fieldErrors = it.fieldErrors.copy(projectPath = null),
                            errorMessage = null,
                        )
                    }
                    if (File(intent.projectPath).isDirectory) detectProject()
                }
                is OnboardingIntent.UpdateProjectParentPath -> _state.update { current ->
                    val next = current.copy(
                        projectParentPath = intent.path,
                        projectCreationMessage = null,
                        errorMessage = null,
                        fieldErrors = current.fieldErrors.copy(projectPath = null),
                    )
                    next.copy(projectPath = plannedProjectPath(next))
                }
                is OnboardingIntent.UpdateProjectFolderName -> _state.update { current ->
                    val next = current.copy(
                        projectFolderName = intent.name,
                        projectCreationMessage = null,
                        errorMessage = null,
                        fieldErrors = current.fieldErrors.copy(projectPath = null),
                    )
                    next.copy(projectPath = plannedProjectPath(next))
                }
                is OnboardingIntent.SetAuthoringModel -> _state.update {
                    it.copy(authoringModel = intent.model, errorMessage = null)
                }
                is OnboardingIntent.UpdateTeamId -> updateRequiredField {
                    it.copy(teamId = intent.teamId, selectedOptionText = "Select a saved robot...")
                }
                is OnboardingIntent.UpdateSeasonId -> updateRequiredField { it.copy(seasonId = intent.seasonId) }
                is OnboardingIntent.UpdateRobotId -> updateRequiredField { it.copy(robotId = intent.robotId) }
                is OnboardingIntent.UpdateRobotName -> _state.update { it.copy(robotName = intent.robotName) }
                is OnboardingIntent.UpdateLeague -> _state.update { current ->
                    val template = projectTemplateService.templateFor(intent.league)
                    val next = current.copy(
                        league = intent.league,
                        nt4Host = environmentService.getDefaultNt4Host(intent.league, current.teamId),
                        projectTemplateName = template.displayName,
                        projectTemplateVersion = template.aresVersion,
                    )
                    if (next.projectSetupMode.createsProject) {
                        next.copy(projectPath = plannedProjectPath(next))
                    } else next
                }
                is OnboardingIntent.UpdateNt4Host -> _state.update { it.copy(nt4Host = intent.nt4Host) }
                is OnboardingIntent.UpdateSimulatorCommand -> _state.update { it.copy(simulatorCommand = intent.simulatorCommand) }
                is OnboardingIntent.UpdateSelectedOptionText -> _state.update { it.copy(selectedOptionText = intent.text) }
                is OnboardingIntent.SetCloudSetupExpanded -> _state.update { it.copy(cloudSetupExpanded = intent.expanded) }
                is OnboardingIntent.SetAdvancedSetupExpanded -> _state.update { it.copy(advancedSetupExpanded = intent.expanded) }
                is OnboardingIntent.ConfigureDriveDestination -> configureDriveDestination(intent)
                is OnboardingIntent.FetchCloudRobots -> fetchCloudRobots()
                OnboardingIntent.DetectLeague -> detectProject()
                OnboardingIntent.NextStep -> moveNext()
                OnboardingIntent.PreviousStep -> _state.update {
                    val previous = if (
                        it.projectSetupMode == ProjectSetupMode.EXPLORE_DEMO &&
                        it.currentStep == OnboardingStep.REVIEW
                    ) {
                        OnboardingStep.PROJECT
                    } else {
                        OnboardingStep.entries[(it.currentStep.ordinal - 1).coerceAtLeast(0)]
                    }
                    it.copy(currentStep = previous, errorMessage = null)
                }
                OnboardingIntent.VerifyJava -> verifyJavaBuildTools()
                OnboardingIntent.InstallManagedJdk -> installManagedJdk()
                OnboardingIntent.SubmitConfig -> submitConfig()
            }
        }
    }

    private fun updateRequiredField(transform: (OnboardingState) -> OnboardingState) {
        _state.update { transform(it).copy(fieldErrors = OnboardingFieldErrors(), errorMessage = null) }
    }

    private suspend fun fetchCloudRobots() {
        if (_state.value.teamId.isBlank()) {
            _state.update { it.copy(cloudRobots = emptyList()) }
            return
        }
        _state.update { it.copy(isCloudLoading = true) }
        try {
            val robots = syncEngineService.getRemoteRobotProfiles()
            _state.update { it.copy(cloudRobots = robots, isCloudLoading = false) }
        } catch (_: Exception) {
            _state.update { it.copy(cloudRobots = emptyList(), isCloudLoading = false) }
        }
    }

    private suspend fun detectProject() {
        val path = _state.value.projectPath.trim()
        val directory = File(path)
        if (path.isEmpty() || !directory.isDirectory) {
            _state.update {
                it.copy(
                    projectDetectionMessage = null,
                    fieldErrors = it.fieldErrors.copy(projectPath = "Choose a folder that contains your robot project."),
                )
            }
            return
        }

        val robotConfig = environmentService.readProjectIdentity(path)
        if (robotConfig != null) {
            val detectedLeague = if (robotConfig.league.equals("FRC", ignoreCase = true)) League.FRC else League.FTC
            _state.update {
                it.copy(
                    teamId = robotConfig.teamId,
                    seasonId = robotConfig.seasonId,
                    robotId = robotConfig.robotId,
                    robotName = robotConfig.name,
                    league = detectedLeague,
                    nt4Host = environmentService.getDefaultNt4Host(detectedLeague, robotConfig.teamId),
                    projectDetectionMessage =
                        "Project found. We filled in the ${detectedLeague.name} robot details from canonical .ares/project.json.",
                    fieldErrors = OnboardingFieldErrors(),
                    currentStep = advanceAfterDetection(it.currentStep, recognizedProject = true),
                )
            }
        } else {
            val detectedLeague = environmentService.detectLeague(path)
            // Only an identified ARES project advances the wizard. A directory that merely exists
            // may be an intermediate path while the student is still typing.
            val recognizedProject = detectedLeague == League.FRC || File(path, ".ares").isDirectory
            _state.update {
                it.copy(
                    league = detectedLeague,
                    nt4Host = environmentService.getDefaultNt4Host(detectedLeague, it.teamId),
                    projectDetectionMessage = "Project found. We detected ${detectedLeague.name}; add the robot details on the next step.",
                    fieldErrors = it.fieldErrors.copy(projectPath = null),
                    currentStep = advanceAfterDetection(it.currentStep, recognizedProject),
                )
            }
        }
    }

    private fun moveNext() {
        val current = _state.value
        val errors = validateOnboardingFields(current, current.currentStep)
        if (errors.hasRequiredFieldErrors) {
            _state.update { it.copy(fieldErrors = errors, errorMessage = "Check the highlighted field before continuing.") }
            return
        }
        val next = if (
            current.projectSetupMode == ProjectSetupMode.EXPLORE_DEMO &&
            current.currentStep == OnboardingStep.PROJECT
        ) {
            OnboardingStep.REVIEW
        } else {
            OnboardingStep.entries[(current.currentStep.ordinal + 1).coerceAtMost(OnboardingStep.entries.lastIndex)]
        }
        _state.update { it.copy(currentStep = next, fieldErrors = errors, errorMessage = null) }
    }

    private suspend fun verifyJavaBuildTools() {
        _state.update { it.copy(isVerifyingJava = true) }
        val result = environmentService.verifyJavaEnvironment()
        val javaReadiness = evaluateJavaBuildTools(result.isValid, result.message)
        _state.update {
            it.copy(
                isVerifyingJava = false,
                javaEnvValid = javaReadiness.isValid,
                javaEnvMsg = javaReadiness.message,
                javaMajorVersion = javaReadiness.majorVersion,
            )
        }
    }

    private suspend fun installManagedJdk() {
        if (ManagedToolchainPaths.managedJdkInstallationSupported()) {
            runCatching { managedToolchainService.installManagedJdk21(_state.value.league) }
            verifyJavaBuildTools()
        } else {
            runCatching { Desktop.getDesktop().browse(URI(ManagedToolchainPaths.JDK_21_DOWNLOAD_URL)) }
                .onFailure { failure ->
                    _state.update {
                        it.copy(errorMessage = "Could not open the JDK download page: ${failure.message ?: "unknown desktop error"}")
                    }
                }
        }
    }

    private suspend fun submitConfig() {
        var current = _state.value
        val errors = validateOnboardingCompletion(current)
        if (errors.hasRequiredFieldErrors) {
            _state.update {
                it.copy(
                    currentStep = if (errors.hasRequiredFieldErrors) {
                        if (errors.projectPath != null) OnboardingStep.PROJECT else OnboardingStep.ROBOT
                    } else {
                        OnboardingStep.REVIEW
                    },
                    fieldErrors = errors,
                    errorMessage = "Finish the required setup before creating this workspace.",
                )
            }
            return
        }

        _state.update { it.copy(isSaving = true, errorMessage = null) }
        try {
            if (current.projectSetupMode.createsProject) {
                val result = projectTemplateService.create(
                    request = RobotProjectCreationRequest(
                        parentDirectory = File(current.projectParentPath.trim()),
                        folderName = current.projectFolderName.trim(),
                        league = current.league,
                        teamId = current.teamId,
                        seasonId = current.seasonId,
                        robotId = current.robotId,
                        robotName = current.robotName,
                        authoringModel = current.authoringModel,
                        initialFieldPresetResourcePath = if (current.projectSetupMode == ProjectSetupMode.EXPLORE_DEMO) {
                            DEMO_FIELD_PRESET_RESOURCE
                        } else {
                            null
                        },
                    ),
                    onProgress = { message -> _state.update { it.copy(projectCreationMessage = message) } },
                    prepareStagedProject = { staged ->
                        _state.update { it.copy(projectCreationMessage = "Creating the first local project version…") }
                        projectHistoryInitializer.initialize(staged.path)
                    },
                )
                _state.update {
                    it.copy(
                        projectSetupMode = ProjectSetupMode.OPEN_EXISTING,
                        projectPath = result.destination.path,
                        projectDetectionMessage =
                            "Created ${result.template.displayName} ${result.template.aresVersion} from a verified ${result.source.name.lowercase().replace('_', ' ')}.",
                        projectCreationMessage = "Project files and local version history are ready.",
                    )
                }
                current = _state.value
            }
            val config = WorkspaceConfig(
                teamId = current.teamId.trim(),
                seasonId = current.seasonId.trim(),
                robotId = current.robotId.trim(),
                robotName = current.robotName.trim(),
                projectPath = current.projectPath.trim(),
                league = current.league,
                nt4Host = current.nt4Host.trim().takeIf(String::isNotEmpty),
                simulatorCommand = current.simulatorCommand.trim().takeIf(String::isNotEmpty),
                driveDestination = current.driveDestination,
            )
            environmentService.saveConfig(config)

            // Cloud registration is best effort. A local workspace is complete without sign-in.
            try {
                val profile = RobotProfile(
                    robotId = current.robotId.trim(),
                    league = current.league,
                    seasonId = current.seasonId.trim(),
                    name = current.robotName.trim().ifEmpty { "${current.robotId.trim()} Local Config" },
                )
                syncEngineService.mutateRemoteRobotProfiles { existing ->
                    if (existing.none { it.robotId == profile.robotId }) existing + profile else existing
                }
            } catch (_: Exception) {
                // Offline setup is intentionally sufficient.
            }

            _state.update { it.copy(isSaving = false, saveSuccess = true) }
            onConfigured(config)
        } catch (e: Exception) {
            _state.update {
                it.copy(
                    isSaving = false,
                    errorMessage = "We couldn't save this workspace. ${e.message ?: "Please try again."}",
                )
            }
        }
    }

    private suspend fun configureDriveDestination(intent: OnboardingIntent.ConfigureDriveDestination) {
        _state.update { it.copy(isDriveDestinationBusy = true, driveDestinationError = null) }
        try {
            val destination = googleDriveService.configureDestination(
                type = intent.type,
                displayName = intent.displayName,
                existingFolderReference = intent.existingFolderReference,
                sharedDriveId = intent.sharedDriveId,
            )
            _state.update {
                it.copy(
                    driveDestination = destination,
                    isDriveDestinationBusy = false,
                    driveDestinationError = null,
                )
            }
        } catch (e: Exception) {
            _state.update {
                it.copy(
                    isDriveDestinationBusy = false,
                    driveDestinationError = e.message ?: "The Drive destination could not be configured.",
                )
            }
        }
    }

}

private fun plannedProjectPath(state: OnboardingState): String {
    val parent = state.projectParentPath.trim()
    val name = state.projectFolderName.trim()
    return if (parent.isBlank() || name.isBlank()) "" else File(parent, name).path
}

/**
 * League detection filled the project page in, so a recognized project moves the student straight
 * to the robot-details page. An unrecognized directory (possibly a partial path while typing)
 * leaves the wizard where it is, and a student who already advanced is never pulled back.
 */
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
        17, 21 -> JavaBuildToolsReadiness(
            true,
            major,
            "JDK $major is ready for robot builds and simulation.",
        )
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
        .find(message)
        ?.groupValues
        ?.get(1)
        ?: Regex("version[=: ]+([0-9]+(?:\\.[0-9]+)*)", RegexOption.IGNORE_CASE)
            .find(message)
            ?.groupValues
            ?.get(1)
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
            state.projectSetupMode.createsProject -> {
                RobotProjectTemplateService.projectFolderNameError(state.projectFolderName.trim())
                    ?: File(state.projectParentPath.trim(), state.projectFolderName.trim())
                        .takeIf(File::exists)
                        ?.let { "A file or folder already exists at ${it.path}." }
            }
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

/** Build-tool readiness is advisory; local analysis and workspace authoring remain available without a supported JDK. */
internal fun validateOnboardingCompletion(state: OnboardingState): OnboardingFieldErrors =
    validateOnboardingFields(state, OnboardingStep.REVIEW)
