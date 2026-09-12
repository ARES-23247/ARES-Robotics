package com.areslib.reducer

import com.areslib.action.RobotAction
import com.areslib.math.estimation.ApplyPoseEstimatorRuntimeResult
import com.areslib.state.Matrix3x3Snapshot
import com.areslib.state.VisionMeasurementSnapshot
import com.areslib.state.VisionState
import com.areslib.state.snapshot

/**
 * Object implementation for Vision Reducer.
 *
 * Hardware IO abstraction layer bridging physical robot sensors and actuators into immutable Redux state representations.
 */
object VisionReducer {
    private const val MAX_VISION_BUFFER_SIZE = 50

    /**
     * Reduces the VisionState slice based on general vision updates and tag observations.
     */
    fun reduce(state: VisionState, action: RobotAction): VisionState {
        return when (action) {
            is RobotAction.VisionMeasurementsReceived -> {
                // Preserve the configured inclusive/availability policy used by the runtime.
                // Direct pure-reducer callers still need this guard before snapshot publication.
                val measurements = action.measurements
                val filterConfig = state.filterConfig
                val validMeasurements = ArrayList<com.areslib.state.VisionMeasurement>(measurements.size)
                for (i in 0 until measurements.size) {
                    val m = measurements[i]
                    if (filterConfig.isValidConfiguration && (!m.ambiguityAvailable ||
                        (m.ambiguity.isFinite() && m.ambiguity >= 0.0 && m.ambiguity <= filterConfig.maxAmbiguity))) {
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
                    val newMeasurements = ArrayList<VisionMeasurementSnapshot>(keepCount)
                    
                    val fromState = kotlin.math.max(0, keepCount - validMeasurements.size)
                    val stateStartIndex = stateMeasurements.size - fromState
                    for (i in stateStartIndex until stateMeasurements.size) {
                        newMeasurements.add(stateMeasurements[i])
                    }
                    
                    val fromValid = keepCount - fromState
                    val validStartIndex = validMeasurements.size - fromValid
                    for (i in validStartIndex until validMeasurements.size) {
                        newMeasurements.add(validMeasurements[i].snapshot())
                    }

                    state.copy(
                        lastTargetTimestampMs = action.timestampMs,
                        hasTarget = true,
                        measurements = newMeasurements
                    )
                }
            }
            is RobotAction.PoseUpdate -> if (action.isReset) state.copy(lastNisDegreesOfFreedom = 0,
                diagnosticMeasurementIndex = -1, diagnosticMeasurementAccepted = false,
                diagnosticMeasurementRejectionReason = null) else state
            is ApplyPoseEstimatorRuntimeResult -> {
                val diagnostics = action.visionDiagnostics ?: return state
                state.copy(
                    lastMeasurementAccepted = diagnostics.lastMeasurementAccepted,
                    lastRejectionReason = diagnostics.lastRejectionReason,
                    covarianceBeforeUpdate = diagnostics.covarianceBeforeUpdate?.let(Matrix3x3Snapshot::from)
                        ?: state.covarianceBeforeUpdate,
                    covarianceAfterUpdate = diagnostics.covarianceAfterUpdate?.let(Matrix3x3Snapshot::from)
                        ?: state.covarianceAfterUpdate,
                    measurementCount = state.measurementCount + diagnostics.acceptedCountDelta,
                    rejectionCount = state.rejectionCount + diagnostics.rejectedCountDelta,
                    lastNis = diagnostics.lastNis,
                    lastNisDegreesOfFreedom = diagnostics.lastNisDegreesOfFreedom,
                    lastNisTimestampMs = diagnostics.lastNisTimestampMs,
                    lastNisSourceId = diagnostics.lastNisSourceId,
                    lastNisFrameId = diagnostics.lastNisFrameId,
                    lastNisTagId = diagnostics.lastNisTagId,
                    lastNisSolverType = diagnostics.lastNisSolverType,
                    lastNisAccepted = diagnostics.lastNisAccepted,
                    diagnosticMeasurementIndex = diagnostics.diagnosticMeasurementIndex,
                    diagnosticMeasurementAccepted = diagnostics.diagnosticMeasurementAccepted,
                    diagnosticMeasurementRejectionReason = diagnostics.diagnosticMeasurementRejectionReason
                )
            }
            else -> state
        }
    }
}
