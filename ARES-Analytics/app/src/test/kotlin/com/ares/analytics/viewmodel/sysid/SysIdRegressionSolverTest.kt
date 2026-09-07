package com.ares.analytics.viewmodel.sysid

import com.ares.analytics.viewmodel.SysIdState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.math.PI
import kotlin.math.sqrt
import kotlin.test.*

class SysIdRegressionSolverTest {
    @Test
    fun `vision noise is small when stationary heading straddles pi`() {
        val state = MutableStateFlow(SysIdState())
        val rows = List(20) { i ->
            doubleArrayOf(i.toDouble(), 2.0, 3.0, if (i % 2 == 0) PI - 0.01 else -PI + 0.01)
        }
        SysIdRegressionSolver(state).runCalibrationAnalysis("VISION_CALIBRATION", rows)
        assertNull(state.value.errorMessage)
        assertEquals(0.01 * sqrt(20.0 / 19.0), state.value.recommendedVisionStdDevsHeading!!, 1e-12)
        assertEquals(0.0, state.value.recommendedVisionStdDevsX!!, 1e-12)
    }

    @Test
    fun `track width uses the wheelbase recorded during the spin`() {
        val state = MutableStateFlow(SysIdState())
        val rows = List(20) { i ->
            val heading = i * 0.1
            val wheelTravel = 0.5 * heading // k = (track width 0.4 + wheelbase 0.6) / 2
            doubleArrayOf(i.toDouble(), -wheelTravel, wheelTravel, -wheelTravel, wheelTravel, heading, 0.6)
        }
        SysIdRegressionSolver(state).runCalibrationAnalysis("TRACK_WIDTH_SPIN", rows)
        assertNull(state.value.errorMessage)
        assertEquals(0.4, state.value.recommendedTrackWidthMeters!!, 1e-12)
    }

    @Test
    fun `linear calibration scales the recorded encoder setting`() {
        val state = MutableStateFlow(SysIdState(linearDriveActualDistanceMeters = 2.0))
        val rows = List(20) { i -> doubleArrayOf(i.toDouble(), i * 2.2 / 19.0, 3000.0) }
        SysIdRegressionSolver(state).runCalibrationAnalysis("LINEAR_DRIVE", rows)
        assertNull(state.value.errorMessage)
        assertEquals(3300.0, state.value.recommendedTicksPerMeter!!, 1e-9)
    }

    @Test
    fun `unidentifiable calibration produces no fabricated recommendation`() {
        val state = MutableStateFlow(SysIdState())
        val rows = List(20) { doubleArrayOf(it.toDouble(), 0.0, 0.0, 0.0, 0.0, 0.0, 0.6) }
        SysIdRegressionSolver(state).runCalibrationAnalysis("TRACK_WIDTH_SPIN", rows)
        assertNotNull(state.value.errorMessage)
        assertNull(state.value.recommendedTrackWidthMeters)
    }
}
