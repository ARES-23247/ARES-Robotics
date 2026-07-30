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

    private const val POOL_SIZE = 128
    private val listPool = Array(POOL_SIZE) { ArrayList<com.areslib.state.VisionMeasurement>(MAX_VISION_BUFFER_SIZE) }
    private var poolIndex = 0
    private val validBuf = ArrayList<com.areslib.state.VisionMeasurement>(MAX_VISION_BUFFER_SIZE)

    /**
     * Reduces the VisionState slice based on general vision updates and tag observations.
     */
    fun reduce(state: VisionState, action: RobotAction): VisionState {
        return when (action) {
            is RobotAction.VisionUpdate -> {
                state.copy(
                    hasTarget = action.hasTarget,
                    targetX = action.targetX,
                    targetY = action.targetY,
                    lastTargetTimestampMs = action.timestampMs
                )
            }
            is RobotAction.VisionMeasurementsReceived -> {
                // Filter out highly ambiguous measurements
                val measurements = action.measurements
                validBuf.clear()
                for (i in 0 until measurements.size) {
                    val m = measurements[i]
                    if (m.ambiguity < MAX_AMBIGUITY) {
                        validBuf.add(m)
                    }
                }

                if (validBuf.isEmpty()) {
                    state.copy(hasTarget = action.measurements.isNotEmpty())
                } else {
                    // Keep last N measurements to avoid memory leak while retaining chronological history
                    val stateMeasurements = state.measurements
                    val totalSize = stateMeasurements.size + validBuf.size
                    val keepCount = kotlin.math.min(totalSize, MAX_VISION_BUFFER_SIZE)
                    
                    val newMeasurements = listPool[poolIndex]
                    poolIndex = (poolIndex + 1) % POOL_SIZE
                    newMeasurements.clear()
                    
                    val fromState = kotlin.math.max(0, keepCount - validBuf.size)
                    val stateStartIndex = stateMeasurements.size - fromState
                    for (i in stateStartIndex until stateMeasurements.size) {
                        newMeasurements.add(stateMeasurements[i])
                    }
                    
                    val fromValid = keepCount - fromState
                    val validStartIndex = validBuf.size - fromValid
                    for (i in validStartIndex until validBuf.size) {
                        newMeasurements.add(validBuf[i])
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
