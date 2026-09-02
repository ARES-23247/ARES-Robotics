package com.ares.analytics.viewmodel

import com.ares.analytics.service.AresGenerationPhase
import com.ares.analytics.service.project.ProjectSessionRevision
import com.ares.analytics.service.project.persistence.ProjectRevisionSummary
import com.ares.analytics.shared.models.League
import com.ares.analytics.shared.models.Trajectory
import com.ares.analytics.ui.components.pathplanner.Waypoint
import com.ares.analytics.viewmodel.pathing.RobotDimensions
import com.ares.analytics.viewmodel.routine.GuidedFirstRoutinePlan
import com.areslib.catalog.ActionDescriptor
import com.areslib.catalog.CapabilityCatalogDocument
import com.areslib.catalog.ConditionDescriptor
import com.areslib.project.AresProjectMetadataDocument
import com.areslib.routine.AutonomousCatalogEntry
import com.ares.analytics.viewmodel.routine.RoutinePreviewAction
import com.areslib.routine.RoutineDocument
import com.areslib.routine.RoutineStep
import com.areslib.routine.RoutineStepKind
import com.areslib.routine.RoutineValidationIssue
import java.util.UUID

internal fun safeRoutineDocumentId(name: String): String = name.trim().lowercase()
    .replace(Regex("[^a-z0-9._-]+"), "-")
    .trim('-', '.', '_')
    .take(64)
    .ifEmpty { "routine" }

internal fun safeProjectDocumentId(name: String): String = name.trim().lowercase()
    .replace(Regex("[^a-z0-9._-]+"), "-")
    .trim('-', '.', '_')
    .take(64)
    .ifEmpty { "project" }

internal fun newRoutine(name: String = "New Routine"): RoutineDocument = RoutineDocument(
    documentId = "${safeRoutineDocumentId(name).take(55)}-${UUID.randomUUID().toString().take(8)}",
    name = name,
    steps = emptyList(),
)

enum class AutonomousTourTarget { EDITOR, CANVAS }

enum class AutonomousTourStep(
    val title: String,
    val description: String,
    val target: AutonomousTourTarget,
) {
    START_POSE(
        title = "1. Set the Match Starting Pose",
        description = "For a match autonomous routine, enable Match auto and place the starting pose in the highlighted field preview. Reusable routines can stay relative and do not need a match start pose.",
        target = AutonomousTourTarget.CANVAS,
    ),
    ADD_WAYPOINT(
        title = "2. Add a Drive Target",
        description = "Add a Drive step, then edit its target or click the highlighted field preview. Set the heading to describe which way the robot should face at that point.",
        target = AutonomousTourTarget.CANVAS,
    ),
    ACTION_MARKER(
        title = "3. Attach an Action Marker",
        description = "Open the Drive step in the highlighted editor and add a named action marker. A marker requests a Redux action at a chosen point in the drive; it does not bypass subsystem safety.",
        target = AutonomousTourTarget.EDITOR,
    ),
    PREVIEW_PLAYBACK(
        title = "4. Review the Kinematic Preview",
        description = "Use Play or scrub the timeline in the highlighted field preview to inspect the planned trajectory. This checks path geometry and motion limits; it is not a physics simulation or hardware validation.",
        target = AutonomousTourTarget.CANVAS,
    ),
    MATCH_SELECTOR(
        title = "5. Choose Match or Reusable Behavior",
        description = "In the highlighted editor, enable Match auto only when this routine should appear in the generated autonomous chooser. Leave it off for a reusable routine that another routine or control can call.",
        target = AutonomousTourTarget.EDITOR,
    );

    fun next(hasProjectActions: Boolean = true): AutonomousTourStep? {
        val visibleSteps = entries.filter { hasProjectActions || it != ACTION_MARKER }
        val index = visibleSteps.indexOf(this)
        return if (index in 0 until visibleSteps.lastIndex) visibleSteps[index + 1] else null
    }

    fun previous(hasProjectActions: Boolean = true): AutonomousTourStep? {
        val visibleSteps = entries.filter { hasProjectActions || it != ACTION_MARKER }
        val index = visibleSteps.indexOf(this)
        return if (index > 0) visibleSteps[index - 1] else null
    }
}

data class PathPlannerState(
    val saveStatus: String = "",
    val estimatedDuration: Double = 0.0,
    val viewRotation: Float = 0f,
    val trajectory: Trajectory? = null,
    val previewActions: List<RoutinePreviewAction> = emptyList(),
    val isPlaying: Boolean = false,
    val playbackTime: Double = 0.0,
    /** Non-null when one routine has multiple possible timelines and preview is suppressed. */
    val routinePreviewWarning: String? = null,
    val capabilityStatus: String = "Select a project to discover robot actions",
    val activeLeague: League = League.FTC,
    val robotDimensions: RobotDimensions = RobotDimensions.defaultFor(League.FTC),
    val projectMetadata: AresProjectMetadataDocument? = null,
    val projectRevision: ProjectSessionRevision? = null,
    val generationPhase: AresGenerationPhase = AresGenerationPhase.IDLE,
    val generationMessage: String? = null,
    val projectLoading: Boolean = false,
    val routine: RoutineDocument = newRoutine(),
    val routineValidation: List<RoutineValidationIssue> = emptyList(),
    val availableRoutines: List<RoutineDocument> = emptyList(),
    val routineRevisions: List<ProjectRevisionSummary> = emptyList(),
    val capabilityCatalog: CapabilityCatalogDocument? = null,
    val routineActions: List<ActionDescriptor> = emptyList(),
    val routineConditions: List<ConditionDescriptor> = emptyList(),
    val autonomousEntry: AutonomousCatalogEntry? = null,
    val availableInAutonomousSelector: Boolean = false,
    val tourStep: AutonomousTourStep? = null,
    /** True only when the visible routine/autonomous draft differs from its last persisted form. */
    val routineDirty: Boolean = false,
)

sealed class PathPlannerIntent {
    data class RefreshProject(val projectPath: String?, val league: League) : PathPlannerIntent()
    data class UpdateViewRotation(val viewRotation: Float) : PathPlannerIntent()
    object TogglePlayback : PathPlannerIntent()
    data class ConfigureField(val league: League, val robotDimensions: RobotDimensions) : PathPlannerIntent()
    data class UpdateCanonicalRobotDimensions(
        val projectPath: String?,
        val robotDimensions: RobotDimensions,
    ) : PathPlannerIntent()

    data object StartGuidedTour : PathPlannerIntent()
    data object NextTourStep : PathPlannerIntent()
    data object PreviousTourStep : PathPlannerIntent()
    data object DismissTour : PathPlannerIntent()

    data class LoadRoutine(val projectPath: String?, val documentId: String) : PathPlannerIntent()
    data class SaveRoutine(val projectPath: String?) : PathPlannerIntent()
    data class SaveAndGenerateRoutine(val projectPath: String?, val league: League) : PathPlannerIntent()
    data class RestoreRoutine(val projectPath: String?, val contentHash: String) : PathPlannerIntent()
    data class CreateRoutine(val name: String = "New Routine") : PathPlannerIntent()
    data class CreateGuidedFirstRoutine(val plan: GuidedFirstRoutinePlan) : PathPlannerIntent()
    data class UpdateRoutineName(val name: String) : PathPlannerIntent()
    data class UpdateRoutineDescription(val description: String) : PathPlannerIntent()
    data class AddRoutineStep(val kind: RoutineStepKind) : PathPlannerIntent()
    data class UpdateRoutineStep(val stepId: String, val step: RoutineStep) : PathPlannerIntent()
    data class RemoveRoutineStep(val stepId: String) : PathPlannerIntent()
    data class MoveRoutineStep(val stepId: String, val direction: Int) : PathPlannerIntent()
    data class AddRoutineChild(
        val parentStepId: String,
        val toElseBranch: Boolean,
        val kind: RoutineStepKind,
    ) : PathPlannerIntent()
    data class UpdateRoutineChild(val childStepId: String, val step: RoutineStep) : PathPlannerIntent()
    data class RemoveRoutineChild(val childStepId: String) : PathPlannerIntent()
    data class SetAutonomousAvailability(val enabled: Boolean, val league: League) : PathPlannerIntent()
    data class UpdateAutonomousEntry(val entry: AutonomousCatalogEntry, val league: League) : PathPlannerIntent()
    data class UpdateRoutineFieldWaypoints(val waypoints: List<Waypoint>, val league: League) : PathPlannerIntent()
}
