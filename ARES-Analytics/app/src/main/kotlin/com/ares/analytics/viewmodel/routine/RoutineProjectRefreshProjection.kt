package com.ares.analytics.viewmodel.routine

import com.ares.analytics.shared.models.League
import com.ares.analytics.viewmodel.PathPlannerState
import com.ares.analytics.viewmodel.newRoutine
import com.ares.analytics.viewmodel.pathing.RobotDimensions
import com.areslib.catalog.CapabilityContext

/**
 * Captures the routine selection for a completed read, then projects it onto the latest editor state.
 * The caller binds project ownership before applying the returned update. Dirty/revision fields and
 * validation remain inside that update; a StateFlow retry must use its current state.
 */
internal fun routineProjectRefreshProjection(
    beforeRefresh: PathPlannerState,
    refresh: RoutineRefresh,
    isBoundRoutineProject: Boolean,
    fallbackLeague: League,
    currentDimensions: () -> RobotDimensions,
): (PathPlannerState) -> PathPlannerState {
    val keepCurrentRoutine = isBoundRoutineProject &&
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
    val effectiveLeague = refresh.metadata?.league?.toAnalyticsLeague() ?: fallbackLeague
    val effectiveDimensions = refresh.metadata?.let {
        RobotDimensions(it.robotLengthMeters, it.robotWidthMeters)
    } ?: currentDimensions()

    return { current ->
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
}
