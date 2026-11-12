package com.ares.analytics.service.analysis

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class RunOverlayAlignmentEngineTest {

    @Test
    fun `first motion alignment compensates delayed driver start between two autonomous runs`() {
        val primarySpeed = listOf(
            TimedScalarSample(1000L, 0.0),
            TimedScalarSample(1100L, 0.01),
            TimedScalarSample(1200L, 0.35),
            TimedScalarSample(1500L, 1.40),
        )
        val compareSpeed = listOf(
            TimedScalarSample(3000L, 0.0),
            TimedScalarSample(4000L, 0.0),
            TimedScalarSample(5400L, 0.02),
            TimedScalarSample(5500L, 0.38),
            TimedScalarSample(5800L, 1.42),
        )

        val alignment = RunOverlayAlignmentEngine.computeAlignment(
            mode = OverlayAlignmentMode.FIRST_MOTION_OR_ENABLE,
            primarySessionStartMs = 1000L,
            compareSessionStartMs = 3000L,
            primaryMotionSamples = primarySpeed,
            compareMotionSamples = compareSpeed,
            manualOffsetMs = 0L,
        )
        val mappedCompareTs = alignment.mapPrimaryToCompareTimestamp(1500L)
        assertEquals(5800L, mappedCompareTs)
    }

    @Test
    fun `pose interpolation computes smooth ghost robot pose and heading wrap across +-pi`() {
        val trace = listOf(
            TimedPoseSample(timestampMs = 1000L, x = 1.0, y = 2.0, headingRadians = 3.10),
            TimedPoseSample(timestampMs = 2000L, x = 3.0, y = 4.0, headingRadians = -3.10),
        )
        val midpoint = RunOverlayAlignmentEngine.interpolatePoseAt(trace, 1500L)
        assertNotNull(midpoint)
        assertEquals(2.0, midpoint.x, 1e-6)
        assertEquals(3.0, midpoint.y, 1e-6)
        assertEquals(kotlin.math.PI, kotlin.math.abs(midpoint.headingRadians), 0.05)
    }
}
