package com.areslib.reducer

import com.areslib.action.RobotAction
import com.areslib.state.RobotState
import com.areslib.state.VisionMeasurement
import com.areslib.math.geometry.Vector3
import com.areslib.math.estimation.PoseEstimator
import com.areslib.hardware.vision.VisionOutlierFilter
import com.areslib.reducer.controller.VisionMeasurementController

/**
 * A pure function that transitions the robot state based on the dispatched action.
 * Delegates domain state slices to specialized domain-focused sub-reducers to
 * prevent a single monolithic file and improve readability/extensibility.
 */
fun rootReducer(state: RobotState, action: RobotAction): RobotState {
    return when (action) {
        is RobotAction.VisionMeasurementsReceived -> {
            VisionMeasurementController.handle(state, action)
        }
        is RobotAction.UpdateTuningState -> {
            val filterConfig = state.vision.filterConfig.copy(
                maxDistanceMeters = action.tuning.vision.maxDistanceMeters,
                maxAmbiguity = action.tuning.vision.maxAmbiguity,
                mahalanobisThreshold = action.tuning.vision.mahalanobisThreshold
            )
            val updatedVision = state.vision.copy(filterConfig = filterConfig)
            
            state.copy(
                tuning = action.tuning,
                vision = updatedVision,
                timestampMs = action.timestampMs
            )
        }
        else -> {
            // Standard action propagation: Compose all independent domain slice reducers
            state.copy(
                drive = DriveReducer.reduce(state.drive, action),
                vision = VisionReducer.reduce(state.vision, action),
                superstructure = SuperstructureReducer.reduce(state.superstructure, action),
                pathState = PathReducer.reduce(state.pathState, action),
                routineState = RoutineReducer.reduce(state.routineState, action),
                timestampMs = action.timestampMs
            )
        }
    }
}
