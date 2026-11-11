package com.ares.analytics.viewmodel.routine

import com.ares.analytics.service.project.AresProjectDocuments
import com.ares.analytics.service.project.persistence.AutonomousCatalogProjectRepository
import com.ares.analytics.service.project.persistence.ProjectMetadataRepository
import com.ares.analytics.service.project.persistence.ProjectRevisionSummary
import com.ares.analytics.service.project.persistence.RoutineProjectRepository
import com.ares.analytics.service.project.ProjectSession
import com.ares.analytics.service.project.ProjectSessionMutationResult
import com.ares.analytics.service.project.ProjectSessionRevision
import com.ares.analytics.service.versioncontrol.ProjectCheckpointRecorder
import com.ares.analytics.shared.models.League
import com.ares.analytics.shared.models.Trajectory
import com.ares.analytics.shared.models.TrajectoryState
import com.ares.analytics.ui.components.pathplanner.Waypoint
import com.ares.analytics.viewmodel.pathing.legalCenterBounds
import com.ares.analytics.viewmodel.pathing.RobotDimensions
import com.ares.analytics.viewmodel.PathPlannerIntent
import com.ares.analytics.viewmodel.PathPlannerState
import com.ares.analytics.viewmodel.safeProjectDocumentId
import com.ares.analytics.viewmodel.safeRoutineDocumentId
import com.areslib.catalog.CapabilityCatalogDocument
import com.areslib.controls.ControllerInputPlatform
import com.areslib.math.geometry.Pose2d
import com.areslib.math.geometry.Rotation2d
import com.areslib.pathing.DriveModel
import com.areslib.pathing.JerkLimitedTrajectoryProvider
import com.areslib.pathing.TrajectoryLimits
import com.areslib.pathing.TrajectoryPlanner
import com.areslib.pathing.TrajectoryPreset
import com.areslib.pathing.TrajectoryRequest
import com.areslib.project.AresLeague
import com.areslib.project.AresProjectMetadataDocument
import com.areslib.routine.AutonomousCatalogDocument
import com.areslib.routine.AutonomousCatalogEntry
import com.areslib.routine.RoutineAlliance
import com.areslib.routine.RoutineDocument
import com.areslib.routine.RoutineDriveStep
import com.areslib.routine.RoutinePose
import com.areslib.routine.RoutineStep
import com.areslib.routine.RoutineStepKind
import java.io.File
import java.util.concurrent.CancellationException
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Curated 1-click starter autonomous recipes for students learning the Routine Builder.
 *
 * Each recipe creates an unsaved, field-valid canonical draft with an autonomous starting pose
 * so students immediately see a working trajectory on the canvas without filling out a blank form.
 */
enum class StudentAutonomousRecipe(
    val title: String,
    val subtitle: String,
    val summary: String,
) {
    LEAVE_STARTING_LINE(
        title = "Leave Starting Line",
        subtitle = "1-step safe forward drive",
        summary = "Starts near the field center and drives 0.75 m forward using the safe motion preset.",
    ),
    DRIVE_AND_TRIGGER_ACTION(
        title = "Drive + Mechanism Action",
        subtitle = "Drive forward & run action",
        summary = "Drives 0.85 m forward, triggers your first project action (or waits 0.75 s if no actions exist yet), and holds.",
    ),
    OUT_AND_BACK(
        title = "Out-and-Back Check",
        subtitle = "Drive out, pause, and return",
        summary = "Drives 0.90 m forward, pauses 0.5 s, and drives back to the starting pose to verify wheel odometry.",
    ),
}

fun buildStudentStarterRoutineDraft(
    recipe: StudentAutonomousRecipe,
    league: League,
    dimensions: RobotDimensions,
    firstActionKey: String? = null,
): Pair<RoutineDocument, AutonomousCatalogEntry> {
    val bounds = legalCenterBounds(league, dimensions, headingRadians = 0.0)
    val startX = (bounds.minX + bounds.maxX) / 2.0
    val startY = (bounds.minY + bounds.maxY) / 2.0
    val forwardRoom = bounds.maxX - startX
    val backwardRoom = startX - bounds.minX
    val direction = if (forwardRoom >= 0.60 || forwardRoom >= backwardRoom) 1.0 else -1.0
    val available = (if (direction > 0.0) forwardRoom else backwardRoom).coerceAtLeast(0.25)

    val startPose = RoutinePose(startX, startY, 0.0)
    val documentId = "${safeRoutineDocumentId(recipe.title).take(55)}-${UUID.randomUUID().toString().take(8)}"

    val steps: List<RoutineStep> = when (recipe) {
        StudentAutonomousRecipe.LEAVE_STARTING_LINE -> {
            val dist = available.coerceAtMost(0.75)
            val target = clampRoutinePose(RoutinePose(startX + direction * dist, startY, 0.0), league, dimensions)
            listOf(
                RoutineStep.driveTo(RoutineDriveStep(target = target, motionPresetKey = "safe")),
            )
        }
        StudentAutonomousRecipe.DRIVE_AND_TRIGGER_ACTION -> {
            val dist = available.coerceAtMost(0.85)
            val target = clampRoutinePose(RoutinePose(startX + direction * dist, startY, 0.0), league, dimensions)
            val followUpStep = if (!firstActionKey.isNullOrBlank()) {
                RoutineStep.action(firstActionKey)
            } else {
                RoutineStep.wait(0.75)
            }
            listOf(
                RoutineStep.driveTo(RoutineDriveStep(target = target, motionPresetKey = "safe")),
                followUpStep,
            )
        }
        StudentAutonomousRecipe.OUT_AND_BACK -> {
            val dist = available.coerceAtMost(0.90)
            val outTarget = clampRoutinePose(RoutinePose(startX + direction * dist, startY, 0.0), league, dimensions)
            val returnTarget = clampRoutinePose(startPose, league, dimensions)
            listOf(
                RoutineStep.driveTo(RoutineDriveStep(target = outTarget, motionPresetKey = "safe")),
                RoutineStep.wait(0.5),
                RoutineStep.driveTo(RoutineDriveStep(target = returnTarget, motionPresetKey = "safe")),
            )
        }
    }

    val draft = RoutineDocument(
        documentId = documentId,
        name = recipe.title,
        description = recipe.summary,
        steps = steps,
    )
    val entry = AutonomousCatalogEntry(
        entryId = documentId,
        displayName = recipe.title,
        routineId = documentId,
        startingPose = clampRoutinePose(startPose, league, dimensions),
        authoredAlliance = RoutineAlliance.RED,
        mirrorForOppositeAlliance = true,
    )
    return draft to entry
}

internal fun applyConfigureFieldState(
    current: PathPlannerState,
    intent: PathPlannerIntent.ConfigureField,
): PathPlannerState {
    val metadata = current.projectMetadata
    val dimensions = metadata?.let { RobotDimensions(it.robotLengthMeters, it.robotWidthMeters) }
        ?: intent.robotDimensions.normalized()
    val league = metadata?.league?.toAnalyticsLeague() ?: intent.league
    return current.copy(
        activeLeague = league,
        robotDimensions = dimensions,
        routineValidation = routineEditorValidation(
            current.routine,
            current.capabilityCatalog,
            current.availableRoutines,
            league,
            dimensions,
            current.autonomousEntry,
        ),
    )
}

internal fun applyNewRoutineDraftState(
    current: PathPlannerState,
    draft: RoutineDocument,
): PathPlannerState = current.copy(
    routine = draft,
    routineValidation = routineEditorValidation(
        draft,
        current.capabilityCatalog,
        current.availableRoutines,
        current.activeLeague,
        current.robotDimensions,
        null,
    ),
    routineRevisions = emptyList(),
    autonomousEntry = null,
    availableInAutonomousSelector = false,
    routineDirty = true,
    saveStatus = "New reusable routine initialized",
)

internal fun applyStarterRecipeDraftState(
    current: PathPlannerState,
    draft: RoutineDocument,
    entry: AutonomousCatalogEntry,
    recipe: StudentAutonomousRecipe,
): PathPlannerState = current.copy(
    routine = draft,
    routineValidation = routineEditorValidation(
        draft,
        current.capabilityCatalog,
        current.availableRoutines,
        current.activeLeague,
        current.robotDimensions,
        entry,
    ),
    routineRevisions = emptyList(),
    autonomousEntry = entry,
    availableInAutonomousSelector = true,
    routineDirty = true,
    saveStatus = "Loaded '${recipe.title}' starter recipe. Press Play on the field preview or customize the steps, then Save.",
)

internal fun applyLoadedRoutineState(
    current: PathPlannerState,
    routine: RoutineDocument,
    revisions: List<ProjectRevisionSummary>,
    autonomous: AutonomousCatalogDocument?,
): PathPlannerState {
    val entry = autonomous?.entries?.firstOrNull { it.routineId == routine.documentId }
    return current.copy(
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
            entry,
        ),
        saveStatus = "Loaded ${routine.name} revision ${routine.revision}",
    )
}

internal fun applySavedRoutineState(
    state: PathPlannerState,
    saved: RoutineSave,
    revisions: List<ProjectRevisionSummary>,
): PathPlannerState {
    val entry = saved.autonomous.entries.firstOrNull { it.routineId == saved.routine.documentId }
    return state.copy(
        routine = saved.routine,
        routineDirty = false,
        routineRevisions = revisions,
        autonomousEntry = entry,
        availableInAutonomousSelector = entry != null,
        saveStatus = if (saved.createdRevision) {
            "Saved routine revision ${saved.routine.revision}"
        } else {
            "Already up to date at revision ${saved.routine.revision}"
        },
    )
}

internal fun applyRestoredRoutineState(
    current: PathPlannerState,
    restored: RoutineDocument,
    revisions: List<ProjectRevisionSummary>,
    projectRevision: ProjectSessionRevision?,
): PathPlannerState = current.copy(
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
        current.autonomousEntry,
    ),
    saveStatus = "Restored as revision ${restored.revision}",
)
