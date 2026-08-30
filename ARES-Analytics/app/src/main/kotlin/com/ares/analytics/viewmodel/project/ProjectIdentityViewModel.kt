package com.ares.analytics.viewmodel.project

import com.ares.analytics.shared.League
import com.ares.analytics.shared.WorkspaceConfig
import com.ares.analytics.util.ProjectLayout
import com.ares.analytics.service.project.persistence.ProjectMetadataRepository
import com.ares.analytics.service.project.ProjectSession
import com.ares.analytics.service.project.ProjectSessionMutationResult
import com.ares.analytics.service.project.ProjectSessionRevision
import com.areslib.controls.ControllerInputPlatform
import com.areslib.project.AresCoordinateConvention
import com.areslib.project.AresFtcHubCommandTransport
import com.areslib.project.AresFtcRuntimeOptionsDocument
import com.areslib.project.AresLeague
import com.areslib.project.AresProjectIdentityDocument
import com.areslib.project.AresProjectMetadataCodec
import com.areslib.project.AresProjectMetadataDocument
import com.areslib.project.AresRuntimeOptionsDocument
import com.areslib.project.resolvedFtcRuntimeOptions
import com.areslib.project.validateAresProjectMetadata
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

enum class ProjectIdentityField {
    PROJECT_ID,
    TEAM_ID,
    SEASON_ID,
    ROBOT_ID,
    DISPLAY_NAME,
    ROBOT_LENGTH,
    ROBOT_WIDTH,
    FIELD_LENGTH,
    FIELD_WIDTH,
}

data class ProjectIdentityDraft(
    val projectId: String = "",
    val teamId: String = "",
    val seasonId: String = "",
    val robotId: String = "",
    val displayName: String = "",
    val robotLengthMeters: String = "",
    val robotWidthMeters: String = "",
    val fieldLengthMeters: String = "",
    val fieldWidthMeters: String = "",
    val ftcHubCommandTransport: AresFtcHubCommandTransport = AresFtcHubCommandTransport.STANDARD_SDK,
    val ftcLimelightProxyEnabled: Boolean = false,
)

data class ProjectIdentityChange(
    val label: String,
    val before: String,
    val after: String,
)

data class ProjectIdentityProposal(
    val expectedContentHash: String?,
    val expectedInvalidRawContentHash: String? = null,
    val proposedContentHash: String,
    val document: AresProjectMetadataDocument,
    val changes: List<ProjectIdentityChange>,
)

data class ProjectIdentityEditorState(
    val loading: Boolean = true,
    val saving: Boolean = false,
    val projectPath: String = "",
    val workspaceLeague: League = League.FTC,
    val currentDocument: AresProjectMetadataDocument? = null,
    val currentContentHash: String? = null,
    val projectRevision: ProjectSessionRevision? = null,
    val draft: ProjectIdentityDraft = ProjectIdentityDraft(),
    val fieldErrors: Map<ProjectIdentityField, String> = emptyMap(),
    val generalErrors: List<String> = emptyList(),
    val proposal: ProjectIdentityProposal? = null,
    /** Blocks canonical writes when the selected folder is not a real robot source repository. */
    val projectSourceError: String? = null,
    val protectedError: String? = null,
    /** Hash of an invalid existing file that may be replaced only through reviewed repair. */
    val protectedContentHash: String? = null,
    val message: String? = null,
    val messageIsError: Boolean = false,
) {
    val canReview: Boolean
        get() = !loading && !saving &&
            projectSourceError == null &&
            (protectedError == null || protectedContentHash != null) &&
            fieldErrors.isEmpty() && generalErrors.isEmpty()
}

/** Owns the reviewed `.ares/project.json` workflow. It never mutates workspace configuration. */
class ProjectIdentityViewModel(
    private val scope: CoroutineScope,
    private val repository: ProjectMetadataRepository = ProjectMetadataRepository(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val projectSession: ProjectSession? = null,
) {
    private val _state = MutableStateFlow(ProjectIdentityEditorState())
    val state: StateFlow<ProjectIdentityEditorState> = _state.asStateFlow()

    private var workspace: WorkspaceConfig? = null
    private var loadJob: Job? = null
    private var generation = 0L

    fun load(config: WorkspaceConfig) {
        workspace = config
        val selectedGeneration = ++generation
        loadJob?.cancel()
        _state.value = ProjectIdentityEditorState(
            loading = true,
            projectPath = config.projectPath,
            workspaceLeague = config.league,
        )
        loadJob = scope.launch {
            val loaded = withContext(ioDispatcher) { runCatching { inspect(config) } }
            if (selectedGeneration != generation || workspace != config) return@launch
            _state.value = loaded.getOrElse { failure ->
                ProjectIdentityEditorState(
                    loading = false,
                    projectPath = config.projectPath,
                    workspaceLeague = config.league,
                    protectedError = failure.message
                        ?: "The selected project could not be inspected. Check the folder and reload.",
                )
            }
        }
    }

    fun update(field: ProjectIdentityField, value: String) {
        val config = workspace ?: return
        val current = _state.value
        if (field in STABLE_IDENTITY_FIELDS && current.currentDocument != null) {
            _state.value = current.copy(
                message = "The saved project ID is stable. Create a new project instead of renaming this identity.",
                messageIsError = false,
            )
            return
        }
        val nextDraft = when (field) {
            ProjectIdentityField.PROJECT_ID -> current.draft.copy(projectId = value)
            ProjectIdentityField.TEAM_ID -> current.draft.copy(teamId = value)
            ProjectIdentityField.SEASON_ID -> current.draft.copy(seasonId = value)
            ProjectIdentityField.ROBOT_ID -> current.draft.copy(robotId = value)
            ProjectIdentityField.DISPLAY_NAME -> current.draft.copy(displayName = value)
            ProjectIdentityField.ROBOT_LENGTH -> current.draft.copy(robotLengthMeters = value)
            ProjectIdentityField.ROBOT_WIDTH -> current.draft.copy(robotWidthMeters = value)
            ProjectIdentityField.FIELD_LENGTH -> current.draft.copy(fieldLengthMeters = value)
            ProjectIdentityField.FIELD_WIDTH -> current.draft.copy(fieldWidthMeters = value)
        }
        val validation = validateProjectIdentityDraft(config.league, nextDraft)
        _state.value = current.copy(
            draft = nextDraft,
            fieldErrors = validation.fieldErrors,
            generalErrors = validation.generalErrors,
            proposal = null,
            message = null,
            messageIsError = false,
        )
    }

    /** Selects the reviewed FTC hub command path; no robot process changes until generation/build. */
    fun updateFtcHubCommandTransport(value: AresFtcHubCommandTransport) = updateFtcRuntimeOptions {
        copy(ftcHubCommandTransport = value)
    }

    /** Controls whether the bounded Limelight HTTP proxy is owned by the FTC robot facade. */
    fun updateFtcLimelightProxyEnabled(enabled: Boolean) = updateFtcRuntimeOptions {
        copy(ftcLimelightProxyEnabled = enabled)
    }

    private fun updateFtcRuntimeOptions(transform: ProjectIdentityDraft.() -> ProjectIdentityDraft) {
        val config = workspace ?: return
        if (config.league != League.FTC) return
        val current = _state.value
        val nextDraft = current.draft.transform()
        val validation = validateProjectIdentityDraft(config.league, nextDraft)
        _state.value = current.copy(
            draft = nextDraft,
            fieldErrors = validation.fieldErrors,
            generalErrors = validation.generalErrors,
            proposal = null,
            message = null,
            messageIsError = false,
        )
    }

    fun resetDraft() {
        val config = workspace ?: return
        val current = _state.value
        val draft = projectIdentityDraft(config, current.currentDocument)
        val validation = validateProjectIdentityDraft(config.league, draft)
        _state.value = current.copy(
            draft = draft,
            fieldErrors = validation.fieldErrors,
            generalErrors = validation.generalErrors,
            proposal = null,
            message = "Draft reset to the last reviewed project identity.",
            messageIsError = false,
        )
    }

    fun review() {
        val config = workspace ?: return
        val current = _state.value
        if (
            current.projectSourceError != null ||
            (current.protectedError != null && current.protectedContentHash == null) ||
            current.loading ||
            current.saving
        ) {
            return
        }
        val validation = validateProjectIdentityDraft(config.league, current.draft)
        val document = validation.document
        if (document == null) {
            _state.value = current.copy(
                fieldErrors = validation.fieldErrors,
                generalErrors = validation.generalErrors,
                proposal = null,
                message = "Fix every project identity error before reviewing the diff.",
                messageIsError = true,
            )
            return
        }
        val changes = projectIdentityChanges(current.currentDocument, document)
        _state.value = if (changes.isEmpty()) {
            current.copy(
                proposal = null,
                message = "The canonical project identity already matches this draft.",
                messageIsError = false,
            )
        } else {
            current.copy(
                fieldErrors = emptyMap(),
                generalErrors = emptyList(),
                proposal = ProjectIdentityProposal(
                    expectedContentHash = current.currentContentHash,
                    expectedInvalidRawContentHash = current.protectedContentHash,
                    proposedContentHash = AresProjectMetadataCodec.contentHash(document),
                    document = document,
                    changes = changes,
                ),
                message = "Review every change below. No file has been written yet.",
                messageIsError = false,
            )
        }
    }

    fun cancelReview() {
        _state.value = _state.value.copy(
            proposal = null,
            message = "Review cancelled; the draft is still editable.",
            messageIsError = false,
        )
    }

    fun applyReviewed() {
        val config = workspace ?: return
        val current = _state.value
        val proposal = current.proposal ?: return
        val freshDocument = validateProjectIdentityDraft(config.league, current.draft).document
        if (freshDocument == null || AresProjectMetadataCodec.contentHash(freshDocument) != proposal.proposedContentHash) {
            _state.value = current.copy(
                proposal = null,
                message = "The draft changed after preview. Review the new diff before saving.",
                messageIsError = true,
            )
            return
        }
        _state.value = current.copy(
            saving = true,
            message = "Saving reviewed project identity…",
            messageIsError = false,
        )
        val applyGeneration = generation
        scope.launch {
            runCatching {
                withContext(ioDispatcher) {
                    ProjectLayout.validationError(config.projectPath, config.league)?.let { sourceError ->
                        error("The selected folder stopped being a valid robot project: $sourceError")
                    }
                    proposal.expectedInvalidRawContentHash?.let { invalidHash ->
                        when (
                            val result = projectSession?.repairProjectIdentity(
                                config.projectPath,
                                config.league.targetPlatform(),
                                invalidHash,
                                proposal.document,
                            )
                        ) {
                            is ProjectSessionMutationResult.Applied -> result.value
                            is ProjectSessionMutationResult.Stale -> error("The invalid project file changed after review. Review the repair again.")
                            is ProjectSessionMutationResult.Conflict -> error(result.message)
                            is ProjectSessionMutationResult.Failed -> error(result.message)
                            null -> repository.repairReviewed(config.projectPath, invalidHash, proposal.document)
                        }
                    } ?: current.projectRevision?.let { revision ->
                        when (val result = projectSession?.saveProjectIdentity(revision, proposal.document)) {
                            is ProjectSessionMutationResult.Applied -> result.value
                            is ProjectSessionMutationResult.Stale -> error("The project changed after this identity loaded. Reload before saving.")
                            is ProjectSessionMutationResult.Conflict -> error(result.message)
                            is ProjectSessionMutationResult.Failed -> error(result.message)
                            null -> repository.saveReviewed(config.projectPath, proposal.expectedContentHash, proposal.document)
                        }
                    } ?: repository.saveReviewed(config.projectPath, proposal.expectedContentHash, proposal.document)
                }
            }.onSuccess { saved ->
                if (applyGeneration != generation || workspace != config) return@onSuccess
                val validation = validateProjectIdentityDraft(config.league, projectIdentityDraft(config, saved.document))
                _state.value = ProjectIdentityEditorState(
                    loading = false,
                    projectPath = config.projectPath,
                    workspaceLeague = config.league,
                    currentDocument = saved.document,
                    currentContentHash = saved.contentHash,
                    projectRevision = projectSession?.state?.value?.revision,
                    draft = projectIdentityDraft(config, saved.document),
                    fieldErrors = validation.fieldErrors,
                    generalErrors = validation.generalErrors,
                    message = when {
                        saved.repaired ->
                            "Repaired .ares/project.json after explicit review. The exact invalid file is preserved in .ares/recovery/project."
                        saved.created -> "Created .ares/project.json after explicit review."
                        else -> "Saved .ares/project.json and preserved the previous version in project history."
                    },
                    messageIsError = false,
                )
            }.onFailure { failure ->
                if (applyGeneration != generation || workspace != config) return@onFailure
                _state.value = current.copy(
                    saving = false,
                    proposal = null,
                    message = failure.message ?: "Project identity could not be saved. Reload and try again.",
                    messageIsError = true,
                )
            }
        }
    }

    private fun inspect(config: WorkspaceConfig): ProjectIdentityEditorState {
        val file = repository.file(config.projectPath)
        val currentResult = repository.load(config.projectPath)
        val current = currentResult.getOrNull()
        val sessionRevision = current?.let {
            projectSession?.snapshot(config.projectPath, config.league.targetPlatform(), forceReload = true)?.revision
        }
        val corruptError = currentResult.exceptionOrNull()?.takeIf { file.isFile }
        val corruptHash = corruptError?.let { repository.rawContentHash(config.projectPath) }
        val retiredSchema = corruptError?.message.orEmpty().contains("authoringModel")
        val projectSourceError = ProjectLayout.validationError(config.projectPath, config.league)
        val mismatch = current?.takeIf { it.league != config.league.toAresLeague() }
        val draft = projectIdentityDraft(config, current)
        val validation = validateProjectIdentityDraft(config.league, draft)
        return ProjectIdentityEditorState(
            loading = false,
            projectPath = config.projectPath,
            workspaceLeague = config.league,
            currentDocument = current,
            currentContentHash = current?.let(AresProjectMetadataCodec::contentHash),
            projectRevision = sessionRevision,
            draft = draft,
            fieldErrors = validation.fieldErrors,
            generalErrors = validation.generalErrors,
            projectSourceError = projectSourceError,
            protectedError = when {
                retiredSchema ->
                    "The selected .ares/project.json uses the retired schema-3 format because it has no authoringModel. Current Studio supports schema-4 projects only and will not rewrite this project."
                corruptError != null ->
                    "The existing .ares/project.json cannot be used: ${corruptError.message}. Its exact bytes remain unchanged. Enter the measured robot dimensions, review the repair, and ARES will preserve the original under .ares/recovery/project before replacing it."
                mismatch != null ->
                    "The canonical project is ${mismatch.league}, but this workspace is ${config.league}. Select the correct workspace league; ARES will not rewrite platform identity automatically."
                else -> null
            },
            protectedContentHash = corruptHash.takeUnless { retiredSchema },
            message = when {
                corruptError != null -> null
                projectSourceError != null ->
                    "The canonical identity is valid, but this folder is not a runnable robot project. Switch projects or create an official starter."
                current == null -> "No canonical project identity exists yet. Enter measured geometry, then review the file before creating it."
                else -> "Loaded the canonical project identity. Stable project ID and platform are protected."
            },
        )
    }

    private fun League.targetPlatform(): ControllerInputPlatform = when (this) {
        League.FTC -> ControllerInputPlatform.FTC
        League.FRC -> ControllerInputPlatform.FRC
    }
}

internal data class ProjectIdentityDraftValidation(
    val document: AresProjectMetadataDocument?,
    val fieldErrors: Map<ProjectIdentityField, String>,
    val generalErrors: List<String>,
)

internal fun validateProjectIdentityDraft(
    league: League,
    draft: ProjectIdentityDraft,
): ProjectIdentityDraftValidation {
    val errors = linkedMapOf<ProjectIdentityField, String>()
    val projectId = draft.projectId.trim()
    if (!projectId.matches(Regex("[A-Za-z][A-Za-z0-9._-]{0,63}"))) {
        errors[ProjectIdentityField.PROJECT_ID] =
            "Use a stable ID that starts with a letter and contains only letters, numbers, dot, underscore, or dash."
    }
    val teamId = draft.teamId.trim()
    if (!teamId.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,31}"))) {
        errors[ProjectIdentityField.TEAM_ID] = "Use the team number or another stable team key."
    }
    val seasonId = draft.seasonId.trim()
    if (!seasonId.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,31}"))) {
        errors[ProjectIdentityField.SEASON_ID] = "Use a stable season key such as 2026."
    }
    val robotId = draft.robotId.trim()
    if (!robotId.matches(Regex("[A-Za-z][A-Za-z0-9._-]{0,63}"))) {
        errors[ProjectIdentityField.ROBOT_ID] = "Use a stable robot key that starts with a letter."
    }
    val displayName = draft.displayName.trim()
    if (displayName.isEmpty() || displayName.length > 80) {
        errors[ProjectIdentityField.DISPLAY_NAME] = "Enter a robot name using 1 to 80 characters."
    }
    fun parse(field: ProjectIdentityField, raw: String, label: String): Double? {
        val value = raw.trim().toDoubleOrNull()
        if (value == null || !value.isFinite() || value <= 0.0) {
            errors[field] = "$label must be a positive number in meters."
            return null
        }
        return value
    }
    val robotLength = parse(ProjectIdentityField.ROBOT_LENGTH, draft.robotLengthMeters, "Robot length")
    val robotWidth = parse(ProjectIdentityField.ROBOT_WIDTH, draft.robotWidthMeters, "Robot width")
    val fieldLength = parse(ProjectIdentityField.FIELD_LENGTH, draft.fieldLengthMeters, "Field length")
    val fieldWidth = parse(ProjectIdentityField.FIELD_WIDTH, draft.fieldWidthMeters, "Field width")
    if (errors.isNotEmpty()) return ProjectIdentityDraftValidation(null, errors, emptyList())

    val document = AresProjectMetadataDocument(
        projectId = projectId,
        identity = AresProjectIdentityDocument(
            teamId = teamId,
            seasonId = seasonId,
            robotId = robotId,
            displayName = displayName,
        ),
        league = league.toAresLeague(),
        coordinateConvention = league.coordinateConvention(),
        robotLengthMeters = requireNotNull(robotLength),
        robotWidthMeters = requireNotNull(robotWidth),
        fieldLengthMeters = requireNotNull(fieldLength),
        fieldWidthMeters = requireNotNull(fieldWidth),
        runtimeOptions = if (league == League.FTC) {
            AresRuntimeOptionsDocument(
                ftc = AresFtcRuntimeOptionsDocument(
                    hubCommandTransport = draft.ftcHubCommandTransport,
                    limelightProxyEnabled = draft.ftcLimelightProxyEnabled,
                ),
            )
        } else {
            AresRuntimeOptionsDocument()
        },
    )
    val generalErrors = validateAresProjectMetadata(document)
    return ProjectIdentityDraftValidation(document.takeIf { generalErrors.isEmpty() }, errors, generalErrors)
}

internal fun projectIdentityDraft(
    config: WorkspaceConfig,
    current: AresProjectMetadataDocument?,
): ProjectIdentityDraft {
    val field = defaultFieldDimensions(config.league)
    val ftcRuntime = current?.resolvedFtcRuntimeOptions() ?: AresFtcRuntimeOptionsDocument()
    return ProjectIdentityDraft(
        projectId = current?.projectId ?: suggestedProjectId(config),
        teamId = current?.identity?.teamId ?: config.teamId,
        seasonId = current?.identity?.seasonId ?: config.seasonId,
        robotId = current?.identity?.robotId ?: config.robotId,
        displayName = current?.identity?.displayName ?: config.robotName.ifBlank { config.robotId },
        robotLengthMeters = current?.robotLengthMeters?.asInput()
            ?: config.robotLengthMeters?.asInput().orEmpty(),
        robotWidthMeters = current?.robotWidthMeters?.asInput()
            ?: config.robotWidthMeters?.asInput().orEmpty(),
        fieldLengthMeters = (current?.fieldLengthMeters ?: field.first).asInput(),
        fieldWidthMeters = (current?.fieldWidthMeters ?: field.second).asInput(),
        ftcHubCommandTransport = ftcRuntime.hubCommandTransport,
        ftcLimelightProxyEnabled = ftcRuntime.limelightProxyEnabled,
    )
}

internal fun projectIdentityChanges(
    current: AresProjectMetadataDocument?,
    proposed: AresProjectMetadataDocument,
): List<ProjectIdentityChange> {
    fun changed(label: String, before: Any?, after: Any): ProjectIdentityChange? =
        if (before?.toString() == after.toString()) null
        else ProjectIdentityChange(label, before?.toString() ?: "missing", after.toString())
    return listOfNotNull(
        changed("Stable project ID", current?.projectId, proposed.projectId),
        changed("Team ID", current?.identity?.teamId, proposed.identity.teamId),
        changed("Season ID", current?.identity?.seasonId, proposed.identity.seasonId),
        changed("Robot ID", current?.identity?.robotId, proposed.identity.robotId),
        changed("Robot display name", current?.identity?.displayName, proposed.identity.displayName),
        changed("League", current?.league, proposed.league),
        changed("Coordinate convention", current?.coordinateConvention, proposed.coordinateConvention),
        changed("Robot length (m)", current?.robotLengthMeters, proposed.robotLengthMeters),
        changed("Robot width (m)", current?.robotWidthMeters, proposed.robotWidthMeters),
        changed("Field length (m)", current?.fieldLengthMeters, proposed.fieldLengthMeters),
        changed("Field width (m)", current?.fieldWidthMeters, proposed.fieldWidthMeters),
        changed(
            "FTC hub command transport",
            current?.takeIf { it.league == AresLeague.FTC }?.resolvedFtcRuntimeOptions()?.hubCommandTransport,
            proposed.resolvedFtcRuntimeOptions().hubCommandTransport,
        ).takeIf { proposed.league == AresLeague.FTC },
        changed(
            "Limelight camera proxy",
            current?.takeIf { it.league == AresLeague.FTC }?.resolvedFtcRuntimeOptions()?.limelightProxyEnabled,
            proposed.resolvedFtcRuntimeOptions().limelightProxyEnabled,
        ).takeIf { proposed.league == AresLeague.FTC },
    )
}

private fun suggestedProjectId(config: WorkspaceConfig): String {
    val raw = "team${config.teamId}-${config.robotId}-${config.seasonId}"
    val normalized = raw.replace(Regex("[^A-Za-z0-9._-]+"), "-").trim('-', '.', '_').take(64)
    return normalized.takeIf { it.firstOrNull()?.isLetter() == true } ?: "project-${normalized.take(56)}"
}

private val STABLE_IDENTITY_FIELDS = setOf(
    ProjectIdentityField.PROJECT_ID,
    ProjectIdentityField.TEAM_ID,
    ProjectIdentityField.SEASON_ID,
    ProjectIdentityField.ROBOT_ID,
)

private fun defaultFieldDimensions(league: League): Pair<Double, Double> = when (league) {
    League.FTC -> 3.6576 to 3.6576
    League.FRC -> 16.541 to 8.211
}

private fun League.toAresLeague(): AresLeague = when (this) {
    League.FTC -> AresLeague.FTC
    League.FRC -> AresLeague.FRC
}

private fun League.coordinateConvention(): AresCoordinateConvention = when (this) {
    League.FTC -> AresCoordinateConvention.CENTER_ORIGIN_CCW
    League.FRC -> AresCoordinateConvention.BLUE_CORNER_ORIGIN_CCW
}

private fun Double.asInput(): String = String.format(Locale.ROOT, "%.6f", this).trimEnd('0').trimEnd('.')
