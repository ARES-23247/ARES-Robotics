package com.ares.analytics.ui.components.pathplanner

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class MotionProfileLabTest {

    @Test
    fun testQuinticSplineCurvatureAndVelocityLimits() {
        val samples = SplineMath.sampleQuinticSpline(
            x0 = 0.0, y0 = 0.0, th0 = 0.0,
            x1 = 2.0, y1 = 2.0, th1 = Math.toRadians(90.0),
            scale = 1.5,
            maxCentripetalAccel = 2.0,
            maxVelocity = 4.0,
            samples = 50
        )

        assertEquals(51, samples.size)
        // Verify start and end points match boundary
        assertEquals(0.0, samples.first().x, 1e-4)
        assertEquals(0.0, samples.first().y, 1e-4)
        assertEquals(2.0, samples.last().x, 1e-4)
        assertEquals(2.0, samples.last().y, 1e-4)

        // Verify curvature velocity clamping
        for (sample in samples) {
            assertTrue(sample.maxVelocity <= 4.0, "Velocity should never exceed maxVelocity")
            if (kotlin.math.abs(sample.curvature) > 1.0) {
                // High curvature should limit velocity below max cruise
                assertTrue(sample.maxVelocity < 4.0, "High curvature must clamp max velocity")
            }
        }
    }

    @Test
    fun testSCurveMotionProfileGeneration() {
        val profile = SplineMath.generateSCurveProfile(
            distance = 4.0,
            maxVel = 3.0,
            maxAccel = 2.0,
            maxJerk = 10.0,
            dt = 0.01
        )

        assertTrue(profile.isNotEmpty(), "Profile points should be populated")
        val finalPoint = profile.last()
        assertTrue(finalPoint.pos >= 3.8, "Profile should reach near target distance")
        assertTrue(finalPoint.vel <= 0.1, "Final velocity should be near zero (settled)")

        for (pt in profile) {
            assertTrue(pt.vel <= 3.01, "Velocity must respect maxVel")
            assertTrue(kotlin.math.abs(pt.accel) <= 2.01, "Acceleration must respect maxAccel")
        }
    }

    @Test
    fun rejectsInvalidOrUnboundedTeachingInputs() {
        assertFailsWith<IllegalArgumentException> {
            SplineMath.sampleQuinticSpline(0.0, 0.0, 0.0, 1.0, 1.0, 0.0, samples = 5_001)
        }
        assertFailsWith<IllegalArgumentException> {
            SplineMath.generateSCurveProfile(1.0, 1.0, 1.0, 1.0, dt = 0.5)
        }
        assertFailsWith<IllegalArgumentException> {
            SplineMath.generateSCurveProfile(Double.NaN, 1.0, 1.0, 1.0)
        }
    }
}
