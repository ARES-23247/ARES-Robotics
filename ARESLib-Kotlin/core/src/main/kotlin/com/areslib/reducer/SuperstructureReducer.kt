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
                if (state.custom === action.state) state else state.copy(custom = action.state)
            }
            is RobotAction.UpdateNamedSubsystemState -> {
                if (state.subsystems[action.subsystemId] === action.state) state
                else state.copy(subsystems = state.subsystems + (action.subsystemId to action.state))
            }
            is RobotAction.SetIndicatorLight -> {
                val current = state.indicatorLights[action.name]
                if (current != null && java.lang.Double.compare(current, action.position) == 0) return state
                state.copy(
                    indicatorLights = state.indicatorLights + (action.name to action.position)
                )
            }
            is RobotAction.SetPrismDriver -> {
                if (state.prismDrivers[action.name] == action.pulseWidthUs) return state
                state.copy(
                    prismDrivers = state.prismDrivers + (action.name to action.pulseWidthUs)
                )
            }
            else -> state
        }
    }
}
