package com.ares.analytics.viewmodel.sysid

import com.ares.analytics.viewmodel.SysIdState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.*

class SysIdCalibrationAuditTest {
    private fun state() = MutableStateFlow(SysIdState())

    @Test fun `pinpoint fit recovers the rotating offset independently of the field origin`() {
        for (direction in listOf(-1, 1)) {
            val state = state()
            val rows = List(80) { i ->
                val angle = direction * i * 2 * PI / 80
                doubleArrayOf(i.toDouble(), 20 + 0.12*cos(angle) + 0.08*sin(angle),
                    -30 + 0.12*sin(angle) - 0.08*cos(angle), angle)
            }
            SysIdRegressionSolver(state).runCalibrationAnalysis("PINPOINT_SPIN", rows)
            assertNull(state.value.errorMessage)
            assertEquals(120.0, state.value.recommendedPinpointXOffsetMm!!, 1e-8)
            assertEquals(-80.0, state.value.recommendedPinpointYOffsetMm!!, 1e-8)
        }
    }

    @Test fun `pinpoint fitting rejects stationary and unidentifiable headings`() {
        for (spread in listOf(0.0, 1e-9)) {
            val state = state()
            val rows = List(20) { doubleArrayOf(it.toDouble(), 1.0, 2.0, it * spread) }
            SysIdRegressionSolver(state).runCalibrationAnalysis("PINPOINT_SPIN", rows)
            assertNotNull(state.value.errorMessage)
            assertNull(state.value.recommendedPinpointXOffsetMm)
            assertNull(state.value.recommendedPinpointYOffsetMm)
        }
    }

    @Test fun `failed and unknown analyses clear prior recommendations`() {
        for (type in listOf("PINPOINT_SPIN", "TRACK_WIDTH_SPIN", "VISION_CALIBRATION", "LINEAR_DRIVE", "UNKNOWN")) {
            for (count in listOf(0, 20)) {
                val state = MutableStateFlow(SysIdState(recommendedPinpointXOffsetMm=1.0,
                    recommendedPinpointYOffsetMm=2.0, recommendedTrackWidthMeters=0.4,
                    recommendedVisionStdDevsX=0.1, recommendedVisionStdDevsY=0.2,
                    recommendedVisionStdDevsHeading=0.3, recommendedTicksPerMeter=3000.0))
                SysIdRegressionSolver(state).runCalibrationAnalysis(type, List(count) { doubleArrayOf(Double.NaN) })
                assertNotNull(state.value.errorMessage)
                assertNull(state.value.recommendedPinpointXOffsetMm)
                assertNull(state.value.recommendedPinpointYOffsetMm)
                assertNull(state.value.recommendedTrackWidthMeters)
                assertNull(state.value.recommendedVisionStdDevsX)
                assertNull(state.value.recommendedVisionStdDevsY)
                assertNull(state.value.recommendedVisionStdDevsHeading)
                assertNull(state.value.recommendedTicksPerMeter)
            }
        }
    }

    @Test fun `overflowing linear and pinpoint results cannot become recommendations`() {
        val linear = state()
        SysIdRegressionSolver(linear).runCalibrationAnalysis("LINEAR_DRIVE",
            List(20) { doubleArrayOf(it.toDouble(), it.toDouble(), Double.MAX_VALUE) })
        assertNotNull(linear.value.errorMessage)
        assertNull(linear.value.recommendedTicksPerMeter)
        val pinpoint = state()
        SysIdRegressionSolver(pinpoint).runCalibrationAnalysis("PINPOINT_SPIN", List(20) {
            val angle = it * 2 * PI / 20
            doubleArrayOf(it.toDouble(), 1e307*cos(angle), 1e307*sin(angle), angle)
        })
        assertNotNull(pinpoint.value.errorMessage)
        assertNull(pinpoint.value.recommendedPinpointXOffsetMm)
    }

    @Test fun `vision variance avoids overflow when the standard deviation is representable`() {
        val state = state()
        val rows = List(20) { doubleArrayOf(it.toDouble(), if (it % 2 == 0) 1e200 else -1e200, 0.0, 0.0) }
        SysIdRegressionSolver(state).runCalibrationAnalysis("VISION_CALIBRATION", rows)
        assertNull(state.value.errorMessage)
        val actual = state.value.recommendedVisionStdDevsX!!
        assertTrue(actual.isFinite())
        assertEquals(sqrt(20.0/19), actual/1e200, 1e-12)
    }

    @Test fun `calibration rejects nonmonotonic or negative sample times`() {
        for (times in listOf(List(20) { 0.0 }, List(20) { -20.0+it }, List(20) { 20.0-it })) {
            val state = state()
            SysIdRegressionSolver(state).runCalibrationAnalysis("LINEAR_DRIVE",
                times.mapIndexed { i, t -> doubleArrayOf(t, i.toDouble(), 3000.0) })
            assertNotNull(state.value.errorMessage)
            assertNull(state.value.recommendedTicksPerMeter)
        }
    }

    @Test fun `track fit unwraps both directions from nonzero encoder origins`() {
        for (direction in listOf(-1, 1)) {
            val state = state()
            val rows = List(100) { i ->
                val heading = direction*i*0.1
                val travel = 0.5*heading
                doubleArrayOf(i.toDouble(), 10-travel, 20+travel, 30-travel, 40+travel,
                    (heading+PI) % (2*PI), 0.6)
            }
            SysIdRegressionSolver(state).runCalibrationAnalysis("TRACK_WIDTH_SPIN", rows)
            assertNull(state.value.errorMessage)
            assertEquals(0.4, state.value.recommendedTrackWidthMeters!!, 1e-10)
        }
    }

    @Test fun `pinpoint offset is identifiable over a partial turn`() {
        val state = state()
        val rows = List(30) { i ->
            val angle = 0.3 + i*0.02
            doubleArrayOf(i.toDouble(), 4 + 0.1*cos(angle) - 0.2*sin(angle),
                7 + 0.1*sin(angle) + 0.2*cos(angle), angle)
        }
        SysIdRegressionSolver(state).runCalibrationAnalysis("PINPOINT_SPIN", rows)
        assertNull(state.value.errorMessage)
        assertEquals(100.0, state.value.recommendedPinpointXOffsetMm!!, 1e-8)
        assertEquals(200.0, state.value.recommendedPinpointYOffsetMm!!, 1e-8)
    }

    @Test fun `extreme constant vision positions retain zero deviation`() {
        val state = state()
        SysIdRegressionSolver(state).runCalibrationAnalysis("VISION_CALIBRATION",
            List(20) { doubleArrayOf(it.toDouble(), Double.MAX_VALUE, -Double.MAX_VALUE, 0.0) })
        assertNull(state.value.errorMessage)
        assertEquals(0.0, state.value.recommendedVisionStdDevsX)
        assertEquals(0.0, state.value.recommendedVisionStdDevsY)
    }

    @Test fun `scaled residual preserves a representable deviation across overflowing differences`() {
        val state = state()
        SysIdRegressionSolver(state).runCalibrationAnalysis("VISION_CALIBRATION", List(20) {
            doubleArrayOf(it.toDouble(), if (it == 0) -Double.MAX_VALUE else Double.MAX_VALUE, 0.0, 0.0)
        })
        assertNull(state.value.errorMessage)
        assertEquals(sqrt(0.2), state.value.recommendedVisionStdDevsX!! / Double.MAX_VALUE, 1e-12)
    }

    @Test fun `unrepresentable vision deviations and ambiguous headings fail closed`() {
        for (ambiguous in listOf(false, true)) {
            val state = state()
            val rows = List(20) {
                doubleArrayOf(it.toDouble(), if (ambiguous) 0.0 else if (it % 2 == 0) Double.MAX_VALUE else -Double.MAX_VALUE,
                    0.0, if (ambiguous && it % 2 == 0) PI else 0.0)
            }
            SysIdRegressionSolver(state).runCalibrationAnalysis("VISION_CALIBRATION", rows)
            assertNotNull(state.value.errorMessage)
            assertNull(state.value.recommendedVisionStdDevsX)
        }
    }

    @Test fun `changing geometry or encoder scale invalidates calibration`() {
        val track = state()
        SysIdRegressionSolver(track).runCalibrationAnalysis("TRACK_WIDTH_SPIN", List(20) {
            doubleArrayOf(it.toDouble(), -it*0.05, it*0.05, -it*0.05, it*0.05, it*0.1, if (it == 0) 0.6 else 0.7)
        })
        assertNotNull(track.value.errorMessage)
        assertNull(track.value.recommendedTrackWidthMeters)
        val linear = state()
        SysIdRegressionSolver(linear).runCalibrationAnalysis("LINEAR_DRIVE",
            List(20) { doubleArrayOf(it.toDouble(), it*0.1, if (it == 0) 3000.0 else 4000.0) })
        assertNotNull(linear.value.errorMessage)
        assertNull(linear.value.recommendedTicksPerMeter)
    }

    @Test fun `a valid analysis recovers after failure without retaining another calibration result`() {
        val state = state()
        val solver = SysIdRegressionSolver(state)
        solver.runCalibrationAnalysis("UNKNOWN", List(20) { doubleArrayOf(it.toDouble(), 0.0, 0.0, 0.0) })
        assertNotNull(state.value.errorMessage)
        solver.runCalibrationAnalysis("LINEAR_DRIVE", List(20) { doubleArrayOf(it.toDouble(), it*0.1, 3000.0) })
        assertNull(state.value.errorMessage)
        assertEquals(2850.0, state.value.recommendedTicksPerMeter!!, 1e-9)
        solver.runCalibrationAnalysis("VISION_CALIBRATION", List(20) { doubleArrayOf(it.toDouble(), 0.0, 0.0, 0.0) })
        assertNull(state.value.errorMessage)
        assertNull(state.value.recommendedTicksPerMeter)
        assertEquals(0.0, state.value.recommendedVisionStdDevsX)
    }

    @Test fun `overflowing heading difference is rejected before wrap can substitute zero`() {
        val state = state()
        val rows = List(20) {
            doubleArrayOf(it.toDouble(), -it.toDouble(), it.toDouble(), -it.toDouble(), it.toDouble(),
                if (it == 0) Double.MAX_VALUE else -Double.MAX_VALUE, 0.6)
        }
        SysIdRegressionSolver(state).runCalibrationAnalysis("TRACK_WIDTH_SPIN", rows)
        assertNotNull(state.value.errorMessage)
        assertTrue(state.value.errorMessage!!.contains("heading difference"))
        assertNull(state.value.recommendedTrackWidthMeters)
    }
}
