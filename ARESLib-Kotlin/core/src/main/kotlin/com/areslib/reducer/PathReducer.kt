package com.areslib.reducer

import com.areslib.action.RobotAction
import com.areslib.state.PathState

/**
 * Object implementation for Path Reducer.
 *
 * Autonomous path planning, trajectory generation, and obstacle avoidance module.
 *
 * ### Coordinate System:
 * Field-centric coordinates in meters ($m$) relative to field origin.
 */
object PathReducer {
    /**
     * Reduces the PathState slice based on path events, switching, and progress updates.
     */
    fun reduce(state: PathState, action: RobotAction): PathState {
        return when (action) {
            is RobotAction.SwitchPath -> {
                val backupPath = if (action.isDetour) {
                    state.originalPathBeforeDetour ?: state.activePath
                } else {
                    null
                }
                state.copy(
                    activePath = action.path,
                    currentDistanceMeters = action.startDistanceMeters,
                    detourActive = action.isDetour,
                    originalPathBeforeDetour = backupPath
                )
            }
            is RobotAction.UpdatePathProgress -> {
                state.copy(
                    currentDistanceMeters = action.distanceProgressMeters,
                    crossTrackErrorMeters = action.crossTrackErrorMeters,
                    alongTrackErrorMeters = action.alongTrackErrorMeters,
                    headingErrorRadians = action.headingErrorRadians
                )
            }
            else -> state
        }
    }
}
