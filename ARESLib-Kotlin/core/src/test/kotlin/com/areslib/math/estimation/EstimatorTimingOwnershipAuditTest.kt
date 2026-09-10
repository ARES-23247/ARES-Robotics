package com.areslib.math.estimation

import com.areslib.action.RobotAction
import com.areslib.math.geometry.Matrix3x3
import com.areslib.state.RobotState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EstimatorTimingOwnershipAuditTest {
    @Test
    fun `stationary dwell starts at zero and completes at exactly 500 milliseconds`() {
        val state = PoseEstimatorState()
        for (time in longArrayOf(0L, 250L, 499L)) {
            observe(state, time)
            assertEquals(0.0, state.gyroBiasRadPerSec)
        }
        observe(state, 500L)
        assertTrue(state.gyroBiasRadPerSec > 0.0)
    }

    @Test
    fun `rewound stationary clock requires a fresh complete dwell`() {
        val state = PoseEstimatorState()
        observe(state, 1000L)
        observe(state, 1600L)
        val learned = state.gyroBiasRadPerSec
        assertTrue(learned > 0.0)
        observe(state, 0L)
        observe(state, 499L)
        assertEquals(learned, state.gyroBiasRadPerSec)
        observe(state, 500L)
        assertTrue(state.gyroBiasRadPerSec > learned)
    }

    @Test
    fun `large forward elapsed time cannot become a negative stationary dwell`() {
        val state = PoseEstimatorState()
        observe(state, Long.MIN_VALUE)
        observe(state, Long.MAX_VALUE)
        assertTrue(state.gyroBiasRadPerSec > 0.0)
    }

    @Test
    fun `recovery beginning at zero applies its full interval and then expires`() {
        val state = PoseEstimatorState(isBeached = true)
        observe(state, 0L, deltaX = 0.1)
        assertFalse(state.isBeached)
        assertEquals(10.0, state.history.last().qScale, 1e-12)
        observe(state, 499L, deltaX = 0.1)
        assertEquals(10.0, state.history.last().qScale, 1e-12)
        observe(state, 500L, deltaX = 0.1)
        assertEquals(0.1, state.history.last().qScale, 1e-12)
    }

    @Test
    fun `recovery clock rewind restarts a bounded interval`() {
        val state = PoseEstimatorState(isBeached = true)
        observe(state, 1000L, deltaX = 0.1)
        observe(state, 1100L, deltaX = 0.1)
        observe(state, 0L, deltaX = 0.1)
        assertEquals(0L, state.lastUnbeachedTimeMs)
        observe(state, 500L, deltaX = 0.1)
        assertEquals(0.1, state.history.last().qScale, 1e-12)
    }

    @Test
    fun `runtime rejects backwards timestamps even when subtraction wraps positive`() {
        val runtime = PoseEstimatorRuntime(PoseEstimatorSnapshot(lastObservationTimestampMs = Long.MAX_VALUE))
        assertNull(drive(runtime, Long.MIN_VALUE))
    }

    @Test
    fun `runtime caps a large forward interval rather than rejecting subtraction overflow`() {
        val runtime = PoseEstimatorRuntime(PoseEstimatorSnapshot())
        reset(runtime, Long.MIN_VALUE)
        val updated = drive(runtime, Long.MAX_VALUE, deltaX = 1.0)
        assertEquals(1.0, updated?.estimatedPoseX)
        assertEquals(Long.MAX_VALUE, updated?.lastObservationTimestampMs)
    }

    @Test
    fun `snapshot hydration and deep copy preserve an active zero-time dwell`() {
        val state = PoseEstimatorState()
        observe(state, 0L)
        val copied = state.deepCopy()
        val snapshot = state.reduxSnapshot()
        observe(copied, 500L)
        assertTrue(copied.gyroBiasRadPerSec > 0.0)
        val runtime = PoseEstimatorRuntime(snapshot)
        val updated = drive(runtime, 500L)
        assertTrue(updated!!.gyroBiasRadPerSec > 0.0)
        assertEquals(0.0, snapshot.gyroBiasRadPerSec)
    }

    @Test
    fun `snapshot hydration and deep copy retain recovery beginning at zero`() {
        val state = PoseEstimatorState(isBeached = true)
        observe(state, 0L, deltaX = 0.1)
        val snapshot = state.reduxSnapshot()
        val copied = state.deepCopy()
        observe(copied, 100L, deltaX = 0.1)
        assertEquals(10.0, copied.history.last().qScale, 1e-12)
        val result = drive(PoseEstimatorRuntime(snapshot), 100L, deltaX = 0.1)!!
        assertEquals(0.1, result.covariance00 - snapshot.covariance00, 1e-12)
        assertTrue(result.recoveryActive)
    }

    @Test
    fun `movement interrupts stationary learning and invalid samples preserve timing state`() {
        val state = PoseEstimatorState()
        observe(state, 0L)
        observe(state, 499L, deltaX = 0.1)
        assertFalse(state.stationaryDwellActive)
        observe(state, 500L)
        val before = state.reduxSnapshot()
        observe(state, 700L, deltaX = Double.NaN)
        assertEquals(before, state.reduxSnapshot())
        observe(state, 999L)
        assertEquals(0.0, state.gyroBiasRadPerSec)
        observe(state, 1000L)
        assertTrue(state.gyroBiasRadPerSec > 0.0)
    }

    @Test
    fun `pose reset clears old dwell and measurement diagnostics`() {
        val runtime = PoseEstimatorRuntime(PoseEstimatorSnapshot(
            stationarySinceMs = 1000L, lastObservationTimestampMs = 1600L,
            lastInnovationX = 1.0, lastInnovationY = 2.0, lastInnovationTheta = 3.0,
            lastNormalizedInnovationSquared = 4.0, lastMeasurementAccepted = true,
            lastRejectionReason = "old", kalmanGain00 = 0.8
        ))
        val result = reset(runtime, 0L)
        assertEquals(0L, result.stationarySinceMs)
        assertEquals(0.0, result.lastInnovationX)
        assertEquals(0.0, result.lastInnovationY)
        assertEquals(0.0, result.lastInnovationTheta)
        assertEquals(0.0, result.lastNormalizedInnovationSquared)
        assertFalse(result.lastMeasurementAccepted)
        assertNull(result.lastRejectionReason)
        assertTrue(result.copyKalmanGain().all { it == 0.0 })
    }

    @Test
    fun `snapshot matrices and history timestamp remain independent of the mutable owner`() {
        val state = PoseEstimatorState()
        for (i in 0..8) {
            state.covarianceArray[i] = i + 1.0
            state.lastKalmanGain[i] = i / 10.0
        }
        state.history.addEntryDirect(42L, 1.0, 2.0, 0.3, Matrix3x3(), 1.0)
        val snapshot = state.reduxSnapshot()
        state.covarianceArray.fill(-1.0)
        state.lastKalmanGain.fill(-1.0)
        state.history.addEntryDirect(99L, 9.0, 9.0, 0.0, Matrix3x3(), 1.0)
        snapshot.copyCovariance().fill(-2.0)
        snapshot.copyKalmanGain().fill(-2.0)
        snapshot.covariance.m00 = -3.0
        for (i in 0..8) {
            assertEquals(i + 1.0, snapshot.covarianceElement(i))
            assertEquals(i / 10.0, snapshot.kalmanGainElement(i))
        }
        assertEquals(42L, snapshot.lastObservationTimestampMs)
    }

    private fun observe(state: PoseEstimatorState, time: Long, deltaX: Double = 0.0) {
        OdometryFusionController.processOdometryDirect(state, time, deltaX, 0.0, 0.0,
            0.0, 0.0, 0.0, 0.0, 0.05, 0.02,
            baseQ = Matrix3x3(0.01, 0.0, 0.0, 0.0, 0.01, 0.0, 0.0, 0.0, 0.01),
            scratchQ = Matrix3x3(), scratchCov = Matrix3x3())
    }
    private fun drive(runtime: PoseEstimatorRuntime, time: Long, deltaX: Double = 0.0) =
        runtime.prepare(RobotState(), RobotAction.DriveHardwareUpdate(0.0, 0.0, 0.05, deltaX, 0.0, 0.0, time))
            .estimatorAction?.estimatorState
    private fun reset(runtime: PoseEstimatorRuntime, time: Long) =
        runtime.prepare(RobotState(), RobotAction.PoseUpdate(0.0, 0.0, 0.0, time, isReset = true))
            .estimatorAction!!.estimatorState!!
}
