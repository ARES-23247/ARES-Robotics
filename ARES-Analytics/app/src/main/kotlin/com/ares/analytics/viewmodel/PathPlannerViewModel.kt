package com.ares.analytics.viewmodel

import com.ares.analytics.service.Nt4ClientService
import com.ares.analytics.service.AresProjectGenerator
import com.ares.analytics.service.versioncontrol.ProjectCheckpointRecorder
import com.ares.analytics.service.project.ProjectSession
import com.ares.analytics.service.project.AresProjectDocuments
import com.ares.analytics.service.project.ProjectSessionMutationResult
import com.ares.analytics.service.project.ProjectSessionRevision
import com.ares.analytics.shared.*
import com.ares.analytics.shared.models.*
import com.ares.analytics.ui.components.pathplanner.Waypoint
import com.ares.analytics.viewmodel.pathing.RobotDimensions
import com.ares.analytics.service.project.persistence.ProjectMetadataRepository
import com.areslib.project.AresLeague
import com.areslib.project.AresProjectMetadataDocument
import com.areslib.controls.ControllerInputPlatform
import com.ares.analytics.service.project.persistence.AutonomousCatalogProjectRepository
import com.ares.analytics.service.project.persistence.RoutineProjectRepository
import com.ares.analytics.viewmodel.routine.clampRoutinePose
import com.ares.analytics.viewmodel.routine.clampDriveTargets
import com.ares.analytics.viewmodel.routine.defaultRoutineStep
import com.ares.analytics.viewmodel.routine.guidedFirstRoutineDocument
import com.ares.analytics.viewmodel.routine.guidedFirstRoutineEntry
import com.ares.analytics.viewmodel.routine.lastRoutineDriveTarget
import com.ares.analytics.viewmodel.routine.moveStepById
import com.ares.analytics.viewmodel.routine.removeStepById
import com.ares.analytics.viewmodel.routine.routineEditorValidation
import com.ares.analytics.viewmodel.routine.updateStepById
import com.ares.analytics.viewmodel.routine.validateGuidedFirstRoutinePlan
import com.ares.analytics.viewmodel.routine.withRoutineRouteWaypoints
import com.areslib.math.geometry.Pose2d
import com.areslib.math.geometry.Rotation2d
import com.areslib.pathing.DriveModel
import com.areslib.pathing.JerkLimitedTrajectoryProvider
import com.areslib.pathing.TrajectoryLimits
import com.areslib.pathing.TrajectoryPlanner
import com.areslib.pathing.TrajectoryPreset
import com.areslib.pathing.TrajectoryRequest
import com.areslib.catalog.CapabilityCatalogDocument
import com.areslib.catalog.CapabilityContext
import com.areslib.routine.AutonomousCatalogDocument
import com.areslib.routine.AutonomousCatalogEntry
import com.areslib.routine.RoutineAlliance
import com.areslib.routine.RoutineDocument
import com.areslib.routine.RoutineDriveStep
import com.areslib.routine.RoutinePose
import com.areslib.routine.RoutineStep
import com.areslib.routine.RoutineStepKind
import com.areslib.routine.RoutineValidationSeverity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

private val ROUTINE_PREVIEW_LIMITS = TrajectoryLimits(
    maxVelocityMps = 3.0,
    maxAccelerationMps2 = 3.0,
    maxJerkMps3 = 12.0,
    maxCentripetalAccelerationMps2 = 3.0,
    maxAngularVelocityRps = Math.toRadians(540.0),
    maxAngularAccelerationRps2 = Math.toRadians(720.0)
)
private const val MAX_ROUTINE_PREVIEW_STEPS = 4_096
private const val MAX_ROUTINE_PREVIEW_DEPTH = 64

/**
 * State owner for canonical routine editing and deterministic drive previews.
 * Geometry uses meters and CCW-positive radians internally.
 */
class PathPlannerViewModel(
    private val scope: CoroutineScope,
    @Suppress("UNUSED_PARAMETER") nt4ClientService: Nt4ClientService? = null,
    private val projectGenerator: AresProjectGenerator? = null,
    private val checkpointRecorder: ProjectCheckpointRecorder = ProjectCheckpointRecorder.NONE,
    private val projectSession: ProjectSession? = null,
) {
    private val _state = MutableStateFlow(PathPlannerState())
    val state: StateFlow<PathPlannerState> = _state.asStateFlow()

    private var playbackJob: kotlinx.coroutines.Job? = null
    private var projectRefreshJob: Job? = null
    private val projectRefreshGeneration = AtomicLong()
    @Volatile private var selectedProjectPath: String? = null
    @Volatile private var loadedProjectPath: String? = null
    @Volatile private var routineProjectPath: String? = null

    private val routineRepository = RoutineProjectRepository()
    private val autonomousRepository = AutonomousCatalogProjectRepository(routineRepository)
    private val metadataRepository = ProjectMetadataRepository()
    private val projectDocuments = AresProjectDocuments(
        routines = routineRepository,
        metadata = metadataRepository,
        autonomous = autonomousRepository,
    )
    private val trajectoryPlanner = TrajectoryPlanner(listOf(JerkLimitedTrajectoryProvider))
    init {
        projectGenerator?.let { generator ->
            scope.launch {
                generator.aresGenerationState.collect { generation ->
                    _state.update {
                        it.copy(
                            generationPhase = generation.phase,
                            generationMessage = generation.message.ifBlank { null }
                        )
                    }
                }
            }
        }
    }

    fun onIntent(intent: PathPlannerIntent) {
        if (intent is PathPlannerIntent.RefreshProject) {
            scheduleProjectRefresh(intent.projectPath, intent.league)
            return
        }
        scope.launch {
            when (intent) {
                is PathPlannerIntent.RefreshProject -> Unit // Dispatched synchronously above.
                is PathPlannerIntent.UpdateViewRotation -> _state.update { it.copy(viewRotation = intent.viewRotation) }

                is PathPlannerIntent.TogglePlayback -> {
                    val preview = _state.value
                    if (preview.routinePreviewWarning != null || preview.trajectory == null || preview.estimatedDuration <= 0.0) {
                        _state.update { it.copy(isPlaying = false, playbackTime = 0.0) }
                        return@launch
                    }
                    val currentlyPlaying = _state.value.isPlaying
                    if (currentlyPlaying) {
                        _state.update { it.copy(isPlaying = false) }
                        playbackJob?.cancel()
                    } else {
                        if (_state.value.playbackTime >= _state.value.estimatedDuration) {
                            _state.update { it.copy(playbackTime = 0.0) }
                        }
                        _state.update { it.copy(isPlaying = true) }
                        playbackJob = scope.launch {
                            var lastTime = System.currentTimeMillis()
                            while (_state.value.isPlaying) {
                                kotlinx.coroutines.delay(16)
                                val now = System.currentTimeMillis()
                                val dt = (now - lastTime) / 1000.0
                                lastTime = now
                                val nextTime = _state.value.playbackTime + dt
                                if (nextTime >= _state.value.estimatedDuration) {
                                    _state.update { it.copy(playbackTime = _state.value.estimatedDuration, isPlaying = false) }
                                    break
                                } else {
                                    _state.update { it.copy(playbackTime = nextTime) }
                                }
                            }
                        }
                    }
                }

                is PathPlannerIntent.ConfigureField -> {
                    _state.update { current ->
                        val metadata = current.projectMetadata
                        val dimensions = metadata?.let { RobotDimensions(it.robotLengthMeters, it.robotWidthMeters) }
                            ?: intent.robotDimensions.normalized()
                        val league = metadata?.league?.toAnalyticsLeague() ?: intent.league
                        current.copy(
                            activeLeague = league,
                            robotDimensions = dimensions,
                            routineValidation = routineEditorValidation(
                                current.routine,
                                current.capabilityCatalog,
                                current.availableRoutines,
                                league,
                                dimensions,
                                current.autonomousEntry
                            )
                        )
                    }
                    recalculateRoutinePreview()
                }
                is PathPlannerIntent.UpdateCanonicalRobotDimensions -> {
                    val current = _state.value
                    val metadata = current.projectMetadata
                    val projectPath = loadedPathFor(intent.projectPath, "updating robot dimensions")
                    if (metadata != null && projectPath != null) {
                        val dimensions = intent.robotDimensions.normalized()
                        val updatedMetadata = metadata.copy(
                            robotLengthMeters = dimensions.lengthMeters,
                            robotWidthMeters = dimensions.widthMeters,
                        )
                        val savedRevision = withContext(Dispatchers.IO) {
                            val session = projectSession
                            val revision = current.projectRevision
                            if (session != null && revision != null) {
                                when (val result = session.saveProjectIdentity(revision, updatedMetadata)) {
                                    is ProjectSessionMutationResult.Applied -> result.snapshot.revision
                                    is ProjectSessionMutationResult.Stale -> error(
                                        "The project changed after the autonomous editor loaded. Reload before changing the robot footprint.",
                                    )
                                    is ProjectSessionMutationResult.Conflict -> error(result.message)
                                    is ProjectSessionMutationResult.Failed -> error(result.message)
                                }
                            } else {
                                metadataRepository.save(projectPath, updatedMetadata)
                                null
                            }
                        }
                        _state.update { current ->
                            current.copy(
                                projectMetadata = updatedMetadata,
                                robotDimensions = dimensions,
                                projectRevision = savedRevision ?: current.projectRevision,
                                saveStatus = "Saved canonical robot footprint to .ares/project.json"
                            )
                        }
                    }
                }

                is PathPlannerIntent.CreateRoutine -> {
                    val draft = newRoutine(intent.name)
                    routineProjectPath = selectedProjectPath ?: loadedProjectPath
                    _state.update { current ->
                        current.copy(
                            routine = draft,
                            routineValidation = routineEditorValidation(
                                draft,
                                current.capabilityCatalog,
                                current.availableRoutines,
                                current.activeLeague,
                                current.robotDimensions,
                                null
                            ),
                            routineRevisions = emptyList(),
                            autonomousEntry = null,
                            availableInAutonomousSelector = false,
                            routineDirty = true,
                            saveStatus = "New reusable routine initialized"
                        )
                    }
                    recalculateRoutinePreview()
                }
                is PathPlannerIntent.CreateGuidedFirstRoutine -> {
                    val current = _state.value
                    val errors = validateGuidedFirstRoutinePlan(
                        intent.plan,
                        current.activeLeague,
                        current.robotDimensions,
                    )
                    if (errors.isNotEmpty()) {
                        _state.update {
                            it.copy(saveStatus = "First routine draft was not created: ${errors.first()}")
                        }
                        return@launch
                    }
                    val documentId = "${safeRoutineDocumentId(intent.plan.name).take(55)}-${UUID.randomUUID().toString().take(8)}"
                    val draft = guidedFirstRoutineDocument(documentId, intent.plan)
                    val entry = guidedFirstRoutineEntry(documentId, intent.plan)
                    routineProjectPath = selectedProjectPath ?: loadedProjectPath
                    _state.update {
                        it.copy(
                            routine = draft,
                            routineValidation = routineEditorValidation(
                                draft,
                                it.capabilityCatalog,
                                it.availableRoutines,
                                it.activeLeague,
                                it.robotDimensions,
                                entry,
                            ),
                            routineRevisions = emptyList(),
                            autonomousEntry = entry,
                            availableInAutonomousSelector = true,
                            routineDirty = true,
                            saveStatus = "Guided first routine is an unsaved draft. Review the field preview before saving.",
                        )
                    }
                    recalculateRoutinePreview()
                }
                is PathPlannerIntent.LoadRoutine -> loadRoutine(intent.projectPath, intent.documentId)
                is PathPlannerIntent.SaveRoutine -> saveRoutine(intent.projectPath)
                is PathPlannerIntent.SaveAndGenerateRoutine -> {
                    if (saveRoutine(intent.projectPath)) {
                        val path = intent.projectPath
                        val generator = projectGenerator
                        if (!path.isNullOrBlank() && generator != null) {
                            generator.generateAresProject(path, intent.league)
                        } else {
                            _state.update { it.copy(saveStatus = "Saved, but project generation is unavailable") }
                        }
                    }
                }
                is PathPlannerIntent.RestoreRoutine -> restoreRoutine(intent.projectPath, intent.contentHash)
                is PathPlannerIntent.UpdateRoutineName -> updateRoutine { it.copy(name = intent.name) }
                is PathPlannerIntent.UpdateRoutineDescription -> updateRoutine {
                    it.copy(description = intent.description.trim().ifEmpty { null })
                }
                is PathPlannerIntent.AddRoutineStep -> {
                    val current = _state.value
                    val pose = current.routine.steps.lastRoutineDriveTarget()
                        ?: current.autonomousEntry?.startingPose
                        ?: RoutinePose(0.0, 0.0, 0.0)
                    val step = defaultRoutineStep(
                        intent.kind,
                        clampRoutinePose(pose, current.activeLeague, current.robotDimensions),
                        current.routineActions.firstOrNull()?.key,
                        current.routineConditions.firstOrNull()?.key,
                        current.availableRoutines.firstOrNull { it.documentId != current.routine.documentId }?.documentId
                    )
                    updateRoutine { it.copy(steps = it.steps + step) }
                    recalculateRoutinePreview()
                }
                is PathPlannerIntent.UpdateRoutineStep -> {
                    updateRoutine { routine ->
                        routine.copy(steps = routine.steps.updateStepById(intent.stepId) {
                            intent.step.copy(stepId = intent.stepId).clampDriveTargets(
                                _state.value.activeLeague,
                                _state.value.robotDimensions
                            )
                        })
                    }
                    recalculateRoutinePreview()
                }
                is PathPlannerIntent.RemoveRoutineStep -> {
                    updateRoutine { routine -> routine.copy(steps = routine.steps.removeStepById(intent.stepId)) }
                    recalculateRoutinePreview()
                }
                is PathPlannerIntent.MoveRoutineStep -> {
                    updateRoutine { routine ->
                        routine.copy(steps = routine.steps.moveStepById(intent.stepId, intent.direction))
                    }
                    recalculateRoutinePreview()
                }
                is PathPlannerIntent.AddRoutineChild -> updateRoutineChildList(
                    intent.parentStepId,
                    intent.toElseBranch
                ) { children ->
                    val current = _state.value
                    children + defaultRoutineStep(
                        intent.kind,
                        RoutinePose(0.0, 0.0, 0.0),
                        current.routineActions.firstOrNull()?.key,
                        current.routineConditions.firstOrNull()?.key,
                        current.availableRoutines.firstOrNull { it.documentId != current.routine.documentId }?.documentId
                    )
                }
                is PathPlannerIntent.UpdateRoutineChild -> {
                    updateRoutine { routine -> routine.copy(steps = routine.steps.updateStepById(intent.childStepId) {
                        intent.step.copy(stepId = intent.childStepId).clampDriveTargets(
                            _state.value.activeLeague,
                            _state.value.robotDimensions
                        )
                    }) }
                    recalculateRoutinePreview()
                }
                is PathPlannerIntent.RemoveRoutineChild -> {
                    updateRoutine { routine -> routine.copy(steps = routine.steps.removeStepById(intent.childStepId)) }
                    recalculateRoutinePreview()
                }
                is PathPlannerIntent.SetAutonomousAvailability -> setAutonomousAvailability(intent.enabled, intent.league)
                is PathPlannerIntent.UpdateAutonomousEntry -> {
                    val clamped = intent.entry.copy(
                        routineId = _state.value.routine.documentId,
                        startingPose = clampRoutinePose(
                            intent.entry.startingPose,
                            intent.league,
                            _state.value.robotDimensions
                        )
                    )
                    _state.update { current -> current.copy(autonomousEntry = clamped, availableInAutonomousSelector = true) }
                    updateRoutine { it }
                    recalculateRoutinePreview()
                }
                is PathPlannerIntent.UpdateRoutineFieldWaypoints -> {
                    updateRoutineFieldWaypoints(intent.waypoints, intent.league)
                    recalculateRoutinePreview()
                }
                is PathPlannerIntent.StartGuidedTour -> _state.update { it.copy(tourStep = AutonomousTourStep.START_POSE) }
                is PathPlannerIntent.NextTourStep -> _state.update {
                    val next = it.tourStep?.next(hasProjectActions = it.routineActions.isNotEmpty())
                    it.copy(tourStep = next)
                }
                is PathPlannerIntent.PreviousTourStep -> _state.update {
                    val prev = it.tourStep?.previous(hasProjectActions = it.routineActions.isNotEmpty())
                    it.copy(tourStep = prev ?: it.tourStep)
                }
                is PathPlannerIntent.DismissTour -> _state.update { it.copy(tourStep = null) }
            }
        }
    }

    private fun updateRoutine(transform: (RoutineDocument) -> RoutineDocument) {
        if (routineProjectPath == null) routineProjectPath = selectedProjectPath
        _state.update { current ->
            val updated = transform(current.routine)
            val existingEntry = current.autonomousEntry
            val entry = existingEntry?.copy(
                routineId = updated.documentId,
                displayName = if (existingEntry.displayName == current.routine.name) {
                    updated.name
                } else {
                    existingEntry.displayName
                }
            )
            current.copy(
                routine = updated,
                autonomousEntry = entry,
                routineValidation = routineEditorValidation(
                    updated,
                    current.capabilityCatalog,
                    current.availableRoutines,
                    current.activeLeague,
                    current.robotDimensions,
                    entry
                ),
                routineDirty = true,
                saveStatus = if (current.saveStatus.startsWith("Saved")) {
                    "Unsaved changes"
                } else {
                    current.saveStatus
                }
            )
        }
    }

    private fun updateRoutineChildList(
        parentStepId: String,
        elseBranch: Boolean,
        transform: (List<RoutineStep>) -> List<RoutineStep>
    ) {
        updateRoutine { routine ->
            routine.copy(steps = routine.steps.updateStepById(parentStepId) { parent ->
                if (elseBranch) parent.copy(elseChildren = transform(parent.elseChildren))
                else parent.copy(children = transform(parent.children))
            })
        }
        recalculateRoutinePreview()
    }

    private fun setAutonomousAvailability(enabled: Boolean, league: League) {
        _state.update { current ->
            val entry = if (enabled) {
                current.autonomousEntry ?: AutonomousCatalogEntry(
                    entryId = current.routine.documentId,
                    displayName = current.routine.name,
                    routineId = current.routine.documentId,
                    startingPose = clampRoutinePose(
                        current.routine.steps.firstOrNull()?.drive?.target ?: RoutinePose(0.0, 0.0, 0.0),
                        league,
                        current.robotDimensions
                    ),
                    authoredAlliance = RoutineAlliance.RED,
                    mirrorForOppositeAlliance = true
                )
            } else {
                null
            }
            current.copy(
                availableInAutonomousSelector = enabled,
                autonomousEntry = entry,
                routineValidation = routineEditorValidation(
                    current.routine,
                    current.capabilityCatalog,
                    current.availableRoutines,
                    league,
                    current.robotDimensions,
                    entry
                ),
                routineDirty = true,
                saveStatus = "Unsaved autonomous selector change"
            )
        }
        recalculateRoutinePreview()
    }

    private fun scheduleProjectRefresh(projectPath: String?, league: League) {
        val canonicalPath = canonicalProjectPath(projectPath)
        val generation = projectRefreshGeneration.incrementAndGet()
        if (canonicalPath != selectedProjectPath) routineProjectPath = null
        selectedProjectPath = canonicalPath
        loadedProjectPath = null
        projectRefreshJob?.cancel()
        if (canonicalPath == null) {
            _state.update {
                it.copy(
                    capabilityStatus = "Select a project to load offline robot actions and routines",
                    projectLoading = false,
                    availableRoutines = emptyList(),
                    routineActions = emptyList(),
                    routineConditions = emptyList()
                )
            }
            recalculateRoutinePreview()
            return
        }
        _state.update {
            it.copy(
                capabilityStatus = "Loading project documents…",
                projectLoading = true,
            )
        }
        projectRefreshJob = scope.launch {
            refreshRoutineProject(canonicalPath, league, generation)
        }
    }

    private suspend fun refreshRoutineProject(projectPath: String, league: League, generation: Long) {
        runCatching {
            withContext(Dispatchers.IO) {
                val target = when (league) {
                    League.FTC -> ControllerInputPlatform.FTC
                    League.FRC -> ControllerInputPlatform.FRC
                }
                val sessionSnapshot = projectSession?.snapshot(projectPath, target, forceReload = true)
                val snapshot = sessionSnapshot?.documents ?: projectDocuments.load(projectPath, target)
                val project = snapshot.query
                RoutineRefresh(
                    project.routines,
                    snapshot.diagnostics.map { it.message },
                    project.capabilityCatalog,
                    project.autonomousCatalog,
                    project.metadata,
                    sessionSnapshot?.revision,
                )
            }
        }.onSuccess { refresh ->
            if (!isCurrentProjectRequest(projectPath, generation)) return@onSuccess
            val beforeRefresh = _state.value
            val keepCurrentRoutine = routineProjectPath == projectPath &&
                (beforeRefresh.routineDirty || refresh.routines.any { it.documentId == beforeRefresh.routine.documentId })
            val activeRoutine = if (keepCurrentRoutine) {
                beforeRefresh.routine
            } else {
                refresh.routines.firstOrNull() ?: newRoutine()
            }
            val persistedEntry = refresh.autonomous?.entries?.firstOrNull {
                it.routineId == activeRoutine.documentId
            }
            val currentEntry = if (keepCurrentRoutine && beforeRefresh.routineDirty) {
                beforeRefresh.autonomousEntry
            } else {
                persistedEntry
            }
            val catalog = refresh.catalog
            val effectiveLeague = refresh.metadata?.league?.toAnalyticsLeague() ?: league
            val effectiveDimensions = refresh.metadata?.let {
                RobotDimensions(it.robotLengthMeters, it.robotWidthMeters)
            } ?: _state.value.robotDimensions
            // Publish "loading complete" only after save/load operations are bound to this exact
            // canonical project. Otherwise a fast click can observe an enabled editor while the
            // private ownership path still points at no project.
            loadedProjectPath = projectPath
            routineProjectPath = projectPath
            _state.update { current ->
                current.copy(
                    routine = activeRoutine,
                    routineDirty = if (keepCurrentRoutine) current.routineDirty else false,
                    routineRevisions = if (keepCurrentRoutine) current.routineRevisions else emptyList(),
                    availableRoutines = refresh.routines,
                    capabilityCatalog = catalog,
                    routineActions = catalog?.actions
                        ?.filter { CapabilityContext.AUTONOMOUS in it.allowedContexts }
                        .orEmpty(),
                    routineConditions = catalog?.conditions.orEmpty(),
                    autonomousEntry = currentEntry,
                    availableInAutonomousSelector = currentEntry != null,
                    projectMetadata = refresh.metadata,
                    projectRevision = refresh.projectRevision,
                    projectLoading = false,
                    activeLeague = effectiveLeague,
                    robotDimensions = effectiveDimensions,
                    capabilityStatus = when {
                        refresh.diagnostics.isNotEmpty() -> refresh.diagnostics.first()
                        catalog == null -> "No generated action catalog yet. Save and generate Robot Studio changes before adding mechanism actions; drive, wait, call, and group steps remain available."
                        catalog.actions.isEmpty() -> "No mechanism actions yet. Add a subsystem in Robot Studio, then Save & generate; drive-only routines work now."
                        else -> "${catalog.actions.size} actions and ${catalog.conditions.size} conditions loaded from the project"
                    },
                    routineValidation = routineEditorValidation(
                        activeRoutine,
                        catalog,
                        refresh.routines,
                        effectiveLeague,
                        effectiveDimensions,
                        currentEntry
                    )
                )
            }
            recalculateRoutinePreview()
        }.onFailure { error ->
            if (isCurrentProjectRequest(projectPath, generation)) {
                _state.update {
                    it.copy(
                        capabilityStatus = "Could not read project documents: ${error.message}",
                        projectLoading = false,
                    )
                }
            }
        }
    }

    private fun canonicalProjectPath(projectPath: String?): String? = projectPath
        ?.trim()
        ?.takeIf(String::isNotEmpty)
        ?.let { File(it).canonicalFile.path }

    private fun isCurrentProjectRequest(projectPath: String, generation: Long): Boolean =
        selectedProjectPath == projectPath && projectRefreshGeneration.get() == generation

    private fun loadedPathFor(projectPath: String?, operation: String): String? {
        val canonicalPath = canonicalProjectPath(projectPath)
        if (
            canonicalPath == null || canonicalPath != selectedProjectPath ||
            canonicalPath != loadedProjectPath || canonicalPath != routineProjectPath
        ) {
            _state.update {
                it.copy(saveStatus = "Wait for the selected project to finish loading before $operation")
            }
            return null
        }
        return canonicalPath
    }

    private fun isLoadedProject(projectPath: String): Boolean =
        selectedProjectPath == projectPath && loadedProjectPath == projectPath && routineProjectPath == projectPath

    private suspend fun loadRoutine(projectPath: String?, documentId: String) {
        val activeProjectPath = loadedPathFor(projectPath, "opening a routine") ?: return
        runCatching {
            withContext(Dispatchers.IO) {
                val routine = routineRepository.load(activeProjectPath, documentId)
                val revisions = routineRepository.listRevisions(activeProjectPath, documentId)
                val autonomous = autonomousRepository.load(activeProjectPath).getOrNull()
                Triple(routine, revisions, autonomous)
            }
        }.onSuccess { (routine, revisions, autonomous) ->
            if (!isLoadedProject(activeProjectPath)) return@onSuccess
            val entry = autonomous?.entries?.firstOrNull { it.routineId == routine.documentId }
            _state.update { current ->
                current.copy(
                    routine = routine,
                    routineDirty = false,
                    routineRevisions = revisions,
                    autonomousEntry = entry,
                    availableInAutonomousSelector = entry != null,
                    routineValidation = routineEditorValidation(
                        routine,
                        current.capabilityCatalog,
                        current.availableRoutines,
                        current.activeLeague,
                        current.robotDimensions,
                        entry
                    ),
                    saveStatus = "Loaded ${routine.name} revision ${routine.revision}"
                )
            }
            routineProjectPath = activeProjectPath
            recalculateRoutinePreview()
        }.onFailure { error ->
            if (selectedProjectPath == activeProjectPath) {
                _state.update { it.copy(saveStatus = "Could not load routine: ${error.message}") }
            }
        }
    }

    private suspend fun saveRoutine(projectPath: String?): Boolean {
        val activeProjectPath = loadedPathFor(projectPath, "saving") ?: return false
        val current = _state.value
        if (current.routineValidation.any { it.severity == RoutineValidationSeverity.ERROR }) {
            _state.update { it.copy(saveStatus = "Fix the highlighted routine issues before saving") }
            return false
        }
        var savedSuccessfully = false
        runCatching {
            withContext(Dispatchers.IO) {
                val session = projectSession
                val revision = current.projectRevision
                if (session != null && revision != null) {
                    when (val result = session.saveRoutine(revision, current.routine, current.autonomousEntry)) {
                        is ProjectSessionMutationResult.Applied -> RoutineSave(
                            result.value.routine.document,
                            result.value.routine.createdRevision,
                            result.value.autonomousCatalog.document,
                        )
                        is ProjectSessionMutationResult.Stale -> error("The project changed after this routine loaded. Reload before saving.")
                        is ProjectSessionMutationResult.Conflict -> error(result.message)
                        is ProjectSessionMutationResult.Failed -> error(result.message)
                    }
                } else {
                    val saved = routineRepository.save(activeProjectPath, current.routine)
                    val oldCatalog = autonomousRepository.load(activeProjectPath).getOrNull()
                    val entry = current.autonomousEntry?.copy(routineId = saved.document.documentId)
                    val entries = oldCatalog?.entries.orEmpty()
                        .filterNot { it.routineId == saved.document.documentId || it.entryId == entry?.entryId }
                        .let { remaining -> if (entry == null) remaining else remaining + entry }
                    val projectId = oldCatalog?.projectId ?: safeProjectDocumentId(File(activeProjectPath).name)
                    val defaultEntryId = oldCatalog?.defaultEntryId?.takeIf { id -> entries.any { it.entryId == id && it.enabled } }
                        ?: entries.firstOrNull { it.enabled }?.entryId
                    val catalogDraft = AutonomousCatalogDocument(
                        projectId = projectId,
                        revision = oldCatalog?.revision ?: 1,
                        defaultEntryId = defaultEntryId,
                        entries = entries
                    )
                    val savedCatalog = autonomousRepository.save(activeProjectPath, catalogDraft)
                    RoutineSave(saved.document, saved.createdRevision, savedCatalog.document)
                }
            }
        }.onSuccess { saved ->
            savedSuccessfully = true
            val revisions = withContext(Dispatchers.IO) {
                routineRepository.listRevisions(activeProjectPath, saved.routine.documentId)
            }
            if (!isLoadedProject(activeProjectPath)) return@onSuccess
            val entry = saved.autonomous.entries.firstOrNull { it.routineId == saved.routine.documentId }
            _state.update { state ->
                state.copy(
                    routine = saved.routine,
                    routineDirty = false,
                    routineRevisions = revisions,
                    autonomousEntry = entry,
                    availableInAutonomousSelector = entry != null,
                    saveStatus = if (saved.createdRevision) {
                        "Saved routine revision ${saved.routine.revision}"
                    } else {
                        "Already up to date at revision ${saved.routine.revision}"
                    }
                )
            }
            scheduleProjectRefresh(activeProjectPath, _state.value.activeLeague)
            runCatching {
                checkpointRecorder.checkpoint(
                    activeProjectPath,
                    "Saved ${saved.routine.name} autonomous routine",
                    setOf(".ares/routines", ".ares/history/routines", ".ares/autonomous-catalog.json", ".ares/history/autonomous"),
                )
            }.onFailure { failure ->
                _state.update { it.copy(saveStatus = "Routine saved, but automatic Project History checkpoint failed: ${failure.message}") }
            }
        }.onFailure { error ->
            if (selectedProjectPath == activeProjectPath) {
                _state.update { it.copy(saveStatus = "Routine save failed: ${error.message}") }
            }
        }
        return savedSuccessfully
    }

    private suspend fun restoreRoutine(projectPath: String?, contentHash: String) {
        val activeProjectPath = loadedPathFor(projectPath, "restoring a routine") ?: return
        val current = _state.value
        val documentId = current.routine.documentId
        runCatching {
            withContext(Dispatchers.IO) {
                val session = projectSession
                val revision = current.projectRevision
                val restored = if (session != null && revision != null) {
                    when (val result = session.restoreRoutineRevision(revision, documentId, contentHash)) {
                        is ProjectSessionMutationResult.Applied -> result.value to result.snapshot.revision
                        is ProjectSessionMutationResult.Stale -> error(
                            "The project changed after this routine loaded. Reload before restoring.",
                        )
                        is ProjectSessionMutationResult.Conflict -> error(result.message)
                        is ProjectSessionMutationResult.Failed -> error(result.message)
                    }
                } else {
                    routineRepository.restore(activeProjectPath, documentId, contentHash) to null
                }
                Triple(
                    restored.first,
                    routineRepository.listRevisions(activeProjectPath, documentId),
                    restored.second,
                )
            }
        }.onSuccess { (restored, revisions, projectRevision) ->
            if (!isLoadedProject(activeProjectPath)) return@onSuccess
            _state.update { current ->
                current.copy(
                    routine = restored.document,
                    routineDirty = false,
                    routineRevisions = revisions,
                    projectRevision = projectRevision ?: current.projectRevision,
                    routineValidation = routineEditorValidation(
                        restored.document,
                        current.capabilityCatalog,
                        current.availableRoutines,
                        current.activeLeague,
                        current.robotDimensions,
                        current.autonomousEntry
                    ),
                    saveStatus = "Restored as revision ${restored.document.revision}"
                )
            }
            routineProjectPath = activeProjectPath
            recalculateRoutinePreview()
        }.onFailure { error ->
            if (selectedProjectPath == activeProjectPath) {
                _state.update { it.copy(saveStatus = "Restore failed: ${error.message}") }
            }
        }
    }

    private fun updateRoutineFieldWaypoints(waypoints: List<Waypoint>, league: League) {
        if (waypoints.isEmpty()) return
        val hasStart = _state.value.autonomousEntry != null
        val driveWaypoints = if (hasStart) waypoints.drop(1) else waypoints
        if (hasStart) {
            val start = waypoints.first()
            val pose = clampRoutinePose(start.toRoutinePose(), league, _state.value.robotDimensions)
            _state.update { current -> current.copy(autonomousEntry = current.autonomousEntry?.copy(startingPose = pose)) }
        }
        updateRoutine { routine ->
            routine.copy(
                steps = routine.steps.withRoutineRouteWaypoints(
                    driveWaypoints.iterator(),
                    league,
                    _state.value.robotDimensions
                )
            )
        }
    }

    private fun recalculateRoutinePreview() {
        val snapshot = _state.value
        val draft = snapshot.routine
        val analysis = analyzeRoutinePreview(draft, snapshot.availableRoutines)
        if (analysis.warning != null) {
            playbackJob?.cancel()
            if (_state.value.routine == draft) {
                _state.update {
                    it.copy(
                        trajectory = null,
                        estimatedDuration = 0.0,
                        playbackTime = 0.0,
                        isPlaying = false,
                        routinePreviewWarning = analysis.warning
                    )
                }
            }
            return
        }
        val drives = analysis.drives
        val previewStart = snapshot.autonomousEntry?.startingPose ?: drives.firstOrNull()?.target
        scope.launch(Dispatchers.Default) {
            if (previewStart == null || drives.isEmpty()) {
                if (_state.value.routine == draft) {
                    _state.update {
                        it.copy(
                            trajectory = null,
                            estimatedDuration = 0.0,
                            playbackTime = 0.0,
                            isPlaying = false,
                            routinePreviewWarning = null
                        )
                    }
                }
                return@launch
            }
            val driveModel = if (snapshot.activeLeague == League.FRC) DriveModel.SWERVE else DriveModel.MECANUM
            var current = previewStart.toPose2d()
            var timeOffset = 0.0
            val previewStates = mutableListOf<TrajectoryState>()
            drives.forEachIndexed { driveIndex, drive ->
                // A neutral routine has no start pose. Treat its first drive target as the preview
                // anchor, rather than inventing match metadata that would change runtime behavior.
                if (snapshot.autonomousEntry == null && driveIndex == 0) return@forEachIndexed
                val target = drive.target.toPose2d()
                val preset = runCatching {
                    TrajectoryPreset.valueOf(drive.motionPresetKey.uppercase())
                }.getOrDefault(TrajectoryPreset.BALANCED)
                val generated = trajectoryPlanner.generate(
                    TrajectoryRequest(
                        waypoints = listOf(current, target),
                        driveModel = driveModel,
                        preset = preset,
                        limits = ROUTINE_PREVIEW_LIMITS,
                        preferredEngine = null
                    )
                ).trajectory ?: return@forEachIndexed
                generated.states.forEachIndexed { index, sample ->
                    if (previewStates.isNotEmpty() && index == 0) return@forEachIndexed
                    previewStates += TrajectoryState(
                        timeSeconds = sample.timeSeconds + timeOffset,
                        x = sample.pose.x,
                        y = sample.pose.y,
                        headingRad = sample.pose.heading.radians,
                        velocity = kotlin.math.hypot(sample.velocityXMps, sample.velocityYMps)
                    )
                }
                timeOffset += generated.durationSeconds
                current = target
            }
            val latest = _state.value
            if (latest.routine == draft &&
                latest.activeLeague == snapshot.activeLeague &&
                latest.autonomousEntry == snapshot.autonomousEntry &&
                latest.availableRoutines == snapshot.availableRoutines
            ) {
                _state.update {
                    it.copy(
                        trajectory = previewStates.takeIf { states -> states.isNotEmpty() }
                            ?.let { states -> Trajectory(timeOffset, states) },
                        estimatedDuration = timeOffset,
                        playbackTime = 0.0,
                        isPlaying = false,
                        routinePreviewWarning = null
                    )
                }
            }
        }
    }

    /**
     * Expands only deterministic control flow. Runtime-selected/parallel timelines are never
     * converted into an invented serial route; callers suppress both geometry and duration.
     */
    private fun analyzeRoutinePreview(
        root: RoutineDocument,
        availableRoutines: List<RoutineDocument>
    ): RoutinePreviewAnalysis {
        val routinesById = (availableRoutines + root).associateBy(RoutineDocument::documentId)
        val drives = mutableListOf<RoutineDriveStep>()
        val activeCalls = linkedSetOf(root.documentId)
        var expandedSteps = 0

        fun visit(steps: List<RoutineStep>, depth: Int): String? {
            if (depth > MAX_ROUTINE_PREVIEW_DEPTH) {
                return "Preview unavailable: routine nesting exceeds $MAX_ROUTINE_PREVIEW_DEPTH levels."
            }
            for (step in steps) {
                expandedSteps++
                if (expandedSteps > MAX_ROUTINE_PREVIEW_STEPS) {
                    return "Preview unavailable: expanded routine exceeds $MAX_ROUTINE_PREVIEW_STEPS steps."
                }
                when (step.kind) {
                    RoutineStepKind.DRIVE_TO -> step.drive?.let(drives::add)
                    RoutineStepKind.REPEAT -> {
                        val count = step.repeatCount
                            ?: return "Preview unavailable: repeat count is missing."
                        if (count < 0) return "Preview unavailable: repeat count must be non-negative."
                        if (count > MAX_ROUTINE_PREVIEW_STEPS) {
                            return "Preview unavailable: repeat count exceeds $MAX_ROUTINE_PREVIEW_STEPS."
                        }
                        repeat(count) {
                            visit(step.children, depth + 1)?.let { return it }
                        }
                    }
                    RoutineStepKind.CALL -> {
                        val routineId = step.routineId
                            ?: return "Preview unavailable: called routine ID is missing."
                        val called = routinesById[routineId]
                            ?: return "Preview unavailable: called routine '$routineId' is not loaded."
                        if (!activeCalls.add(routineId)) {
                            return "Preview unavailable: routine call cycle includes '$routineId'."
                        }
                        val warning = visit(called.steps, depth + 1)
                        activeCalls.remove(routineId)
                        if (warning != null) return warning
                    }
                    RoutineStepKind.BRANCH,
                    RoutineStepKind.TOGETHER,
                    RoutineStepKind.FIRST_TO_FINISH,
                    RoutineStepKind.DEADLINE -> return compositePreviewWarning(step.kind)
                    RoutineStepKind.ACTION,
                    RoutineStepKind.WAIT,
                    RoutineStepKind.WAIT_UNTIL -> Unit
                }
            }
            return null
        }

        return RoutinePreviewAnalysis(drives = drives, warning = visit(root.steps, depth = 0))
    }

    private fun compositePreviewWarning(kind: RoutineStepKind): String {
        val label = when (kind) {
            RoutineStepKind.BRANCH -> "Branch"
            RoutineStepKind.TOGETHER -> "Parallel group"
            RoutineStepKind.FIRST_TO_FINISH -> "First-to-finish group"
            RoutineStepKind.DEADLINE -> "Deadline group"
            else -> kind.name
        }
        return "Preview unavailable: $label has multiple possible timelines. " +
            "Select a runtime scenario before showing its route or duration."
    }

    private fun Waypoint.toRoutinePose(): RoutinePose = RoutinePose(
        xMeters = x,
        yMeters = y,
        headingRadians = rotationDeg?.let(Math::toRadians) ?: headingRad ?: 0.0
    )

    private fun RoutinePose.toPose2d(): Pose2d = Pose2d(xMeters, yMeters, Rotation2d(headingRadians))

    private data class RoutineRefresh(
        val routines: List<RoutineDocument>,
        val diagnostics: List<String>,
        val catalog: CapabilityCatalogDocument?,
        val autonomous: AutonomousCatalogDocument?,
        val metadata: AresProjectMetadataDocument?,
        val projectRevision: ProjectSessionRevision?,
    )

    private fun AresLeague.toAnalyticsLeague(): League = when (this) {
        AresLeague.FTC -> League.FTC
        AresLeague.FRC -> League.FRC
    }

    private data class RoutineSave(
        val routine: RoutineDocument,
        val createdRevision: Boolean,
        val autonomous: AutonomousCatalogDocument
    )

    private data class RoutinePreviewAnalysis(
        val drives: List<RoutineDriveStep>,
        val warning: String?
    )
}
