package com.areslib.reducer

import com.areslib.action.RobotAction
import com.areslib.state.SuperstructureState

/**
 * Object implementation for Superstructure Reducer.
 *
 * Pure Redux state definition and deterministic reducer transition handler.
 */
object SuperstructureReducer {
    /**
     * Reduces the generic SuperstructureState by merging dynamic SubsystemState updates.
     */
    fun reduce(state: SuperstructureState, action: RobotAction): SuperstructureState {
        return when (action) {
            is RobotAction.UpdateSubsystemState -> {
                state.copy(custom = action.state)
            }
            is RobotAction.SetIndicatorLight -> {
                state.copy(
                    indicatorLights = state.indicatorLights + (action.name to action.position)
                )
            }
            is RobotAction.SetPrismDriver -> {
                state.copy(
                    prismDrivers = state.prismDrivers + (action.name to action.pulseWidthUs)
                )
            }
            else -> state
        }
    }
}
