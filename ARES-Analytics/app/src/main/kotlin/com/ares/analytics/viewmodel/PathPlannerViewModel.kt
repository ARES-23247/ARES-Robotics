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
import com.ares.analytics.viewmodel.routine.RoutinePersistenceService
import com.ares.analytics.viewmodel.routine.RoutinePlaybackController
import com.ares.analytics.viewmodel.routine.toAnalyticsLeague
import com.ares.analytics.viewmodel.routine.toRoutinePose
import com.ares.analytics.viewmodel.routine.clampRoutinePose
import com.ares.analytics.viewmodel.routine.clampDriveTargets
import com.ares.analytics.viewmodel.routine.analyzeRoutinePreview
import com.ares.analytics.viewmodel.routine.defaultRoutineStep
import com.ares.analytics.viewmodel.routine.defaultAutonomousEntry
import com.ares.analytics.viewmodel.routine.lastRoutineDriveTarget
import com.ares.analytics.viewmodel.routine.moveStepById
import com.ares.analytics.viewmodel.routine.removeStepById
import com.ares.analytics.viewmodel.routine.routineEditorValidation
import com.ares.analytics.viewmodel.routine.RoutineTrajectoryPreviewCompiler
import com.ares.analytics.viewmodel.routine.updateStepById
import com.ares.analytics.viewmodel.routine.validateGuidedFirstRoutinePlan
import com.ares.analytics.viewmodel.routine.withRoutineRouteWaypoints
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
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.isActive
import kotlinx.coroutines.ensureActive
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

    private val playbackController = RoutinePlaybackController(scope, _state)
    private var routinePreviewJob: Job? = null
    private var projectRefreshJob: Job? = null
    private val projectRefreshGeneration = AtomicLong()
    @Volatile private var selectedProjectPath: String? = null
    @Volatile private var loadedProjectPath: String? = null
    @Volatile private var routineProjectPath: String? = null

    private val persistence = RoutinePersistenceService(checkpointRecorder, projectSession)
    private val routinePreviewCompiler = RoutineTrajectoryPreviewCompiler()
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

                is PathPlannerIntent.TogglePlayback -> playbackController.togglePlayback()

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
                        try {
                            val expectedHash = com.areslib.project.AresProjectMetadataCodec.contentHash(metadata)
                            val savedRevision = persistence.saveMetadata(projectPath, expectedHash, updatedMetadata, current.projectRevision)
                            _state.update { current ->
                                current.copy(
                                    projectMetadata = updatedMetadata,
                                    robotDimensions = dimensions,
                                    projectRevision = savedRevision ?: current.projectRevision,
                                    saveStatus = "Saved canonical robot footprint to .ares/project.json"
                                )
                            }
                        } catch (cancelled: kotlinx.coroutines.CancellationException) {
                            throw cancelled
                        } catch (error: Exception) {
                            _state.update { it.copy(saveStatus = "Failed to save canonical robot footprint: ${error.message}") }
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
                    val (draft, entry) = createGuidedFirstRoutineDraft(intent.plan)
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
                is PathPlannerIntent.ImportBiobuzzAuto -> importBiobuzzAuto(intent.projectPath, intent.zipPath)
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
                current.autonomousEntry ?: defaultAutonomousEntry(current.routine, league, current.robotDimensions)
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
            persistence.refreshProject(projectPath, league)
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
            persistence.loadRoutine(activeProjectPath, documentId)
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
            persistence.saveRoutine(activeProjectPath, current.routine, current.autonomousEntry, current.projectRevision)
        }.onSuccess { (saved, revisions) ->
            savedSuccessfully = true
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
        }.onFailure { error ->
            if (selectedProjectPath == activeProjectPath) {
                _state.update { it.copy(saveStatus = "Routine save failed: ${error.message}") }
            }
        }
        return savedSuccessfully
    }

    private suspend fun importBiobuzzAuto(projectPath: String?, zipPath: String) {
        val path = loadedPathFor(projectPath, "importing an auto") ?: return
        val before = _state.value
        runCatching {
            require(before.activeLeague == League.FTC) { "Open a BIOBUZZ FTC RobotBuilder project first." }
            val requiredActions = setOf("subsystem.biobuzz-intake.set.intakeVoltage",
                "subsystem.biobuzz-shooter.set.flywheelVoltage", "subsystem.biobuzz-shooter.set.transferVoltage")
            require(before.routineActions.map { it.key }.containsAll(requiredActions)) {
                "This project needs the BIOBUZZ RobotBuilder intake and shooter actions."
            }
            persistence.importBiobuzzAuto(zipPath)
        }.onSuccess { draft ->
            if (!isLoadedProject(path) || _state.value.routine != before.routine) return@onSuccess
            val validation = routineEditorValidation(draft.routine, before.capabilityCatalog,
                before.availableRoutines, before.activeLeague, before.robotDimensions, draft.entry)
            if (validation.any { it.severity == RoutineValidationSeverity.ERROR }) {
                _state.update { it.copy(saveStatus = "Auto import failed: " + validation.joinToString { issue -> issue.message }) }
                return@onSuccess
            }
            playbackController.cancel()
            routineProjectPath = path
            _state.update { it.copy(routine = draft.routine, autonomousEntry = draft.entry,
                availableInAutonomousSelector = true, routineDirty = true, routineRevisions = emptyList(),
                routineValidation = validation, isPlaying = false, playbackTime = 0.0,
                saveStatus = "Imported a new BIOBUZZ auto draft. Review it, then Save & Generate.") }
            recalculateRoutinePreview()
        }.onFailure { failure ->
            if (isLoadedProject(path)) _state.update { it.copy(saveStatus = "Auto import failed: ${failure.message}") }
        }
    }

    private suspend fun restoreRoutine(projectPath: String?, contentHash: String) {
        val activeProjectPath = loadedPathFor(projectPath, "restoring a routine") ?: return
        val current = _state.value
        val documentId = current.routine.documentId
        runCatching {
            persistence.restoreRoutine(activeProjectPath, documentId, contentHash, current.projectRevision)
        }.onSuccess { (restored, revisions, projectRevision) ->
            if (!isLoadedProject(activeProjectPath)) return@onSuccess
            _state.update { current ->
                current.copy(
                    routine = restored,
                    routineDirty = false,
                    routineRevisions = revisions,
                    projectRevision = projectRevision ?: current.projectRevision,
                    routineValidation = routineEditorValidation(
                        restored,
                        current.capabilityCatalog,
                        current.availableRoutines,
                        current.activeLeague,
                        current.robotDimensions,
                        current.autonomousEntry
                    ),
                    saveStatus = "Restored as revision ${restored.revision}"
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

    @Synchronized
    private fun recalculateRoutinePreview() {
        routinePreviewJob?.cancel()
        playbackController.cancel()
        val snapshot = _state.value
        val projectGeneration = projectRefreshGeneration.get()
        val draft = snapshot.routine
        val analysis = analyzeRoutinePreview(draft, snapshot.availableRoutines)

        fun matchesInputs(current: PathPlannerState): Boolean =
            projectRefreshGeneration.get() == projectGeneration &&
                current.routine == draft &&
                current.activeLeague == snapshot.activeLeague &&
                current.autonomousEntry == snapshot.autonomousEntry &&
                current.availableRoutines == snapshot.availableRoutines

        // Invalidate the previous timeline immediately, including while a replacement
        // is being generated. Check the inputs inside the atomic state update.
        _state.update { current ->
            if (!matchesInputs(current)) current else current.copy(
                trajectory = null,
                previewActions = emptyList(),
                estimatedDuration = 0.0,
                playbackTime = 0.0,
                isPlaying = false,
                routinePreviewWarning = analysis.warning,
            )
        }
        if (analysis.warning != null || analysis.steps.isEmpty()) return
        val previewStart = snapshot.autonomousEntry?.startingPose ?: analysis.drives.firstOrNull()?.target
            ?: return
        routinePreviewJob = scope.launch(Dispatchers.Default, start = CoroutineStart.LAZY) {
            if (!isActive) return@launch
            val preview = routinePreviewCompiler.compile(
                steps = analysis.steps,
                previewStart = previewStart,
                hasAutonomousStart = snapshot.autonomousEntry != null,
                league = snapshot.activeLeague,
                checkActive = { ensureActive() },
            )
            _state.update { current ->
                if (!isActive || !matchesInputs(current)) current else current.copy(
                    trajectory = preview.trajectory,
                    previewActions = preview.actions,
                    estimatedDuration = preview.estimatedDurationSeconds,
                    playbackTime = 0.0,
                    isPlaying = false,
                    routinePreviewWarning = preview.warning,
                )
            }
        }.also { it.start() }
    }
}
