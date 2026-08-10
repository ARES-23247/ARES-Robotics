package com.areslib.reducer

import com.areslib.action.RobotAction
import com.areslib.state.VisionState

/**
 * Object implementation for Vision Reducer.
 *
 * Hardware IO abstraction layer bridging physical robot sensors and actuators into immutable Redux state representations.
 */
object VisionReducer {
    private const val MAX_VISION_BUFFER_SIZE = 50
    private const val MAX_AMBIGUITY = 0.2

    /**
     * Reduces the VisionState slice based on general vision updates and tag observations.
     */
    fun reduce(state: VisionState, action: RobotAction): VisionState {
        return when (action) {
            is RobotAction.VisionMeasurementsReceived -> {
                // Filter out highly ambiguous measurements
                val measurements = action.measurements
                val validMeasurements = ArrayList<com.areslib.state.VisionMeasurement>(measurements.size)
                for (i in 0 until measurements.size) {
                    val m = measurements[i]
                    if (m.ambiguity < MAX_AMBIGUITY) {
                        validMeasurements.add(m)
                    }
                }

                if (validMeasurements.isEmpty()) {
                    state.copy(hasTarget = action.measurements.isNotEmpty())
                } else {
                    // Retained Redux state must own every mutable measurement/pose. Hardware adapters
                    // may recycle their objects immediately after this dispatch returns.
                    val stateMeasurements = state.measurements
                    val totalSize = stateMeasurements.size + validMeasurements.size
                    val keepCount = kotlin.math.min(totalSize, MAX_VISION_BUFFER_SIZE)
                    val newMeasurements = ArrayList<com.areslib.state.VisionMeasurement>(keepCount)
                    
                    val fromState = kotlin.math.max(0, keepCount - validMeasurements.size)
                    val stateStartIndex = stateMeasurements.size - fromState
                    for (i in stateStartIndex until stateMeasurements.size) {
                        newMeasurements.add(stateMeasurements[i])
                    }
                    
                    val fromValid = keepCount - fromState
                    val validStartIndex = validMeasurements.size - fromValid
                    for (i in validStartIndex until validMeasurements.size) {
                        newMeasurements.add(validMeasurements[i].ownedCopy())
                    }

                    state.copy(
                        lastTargetTimestampMs = action.timestampMs,
                        hasTarget = true,
                        measurements = newMeasurements
                    )
                }
            }
            else -> state
        }
    }
}
