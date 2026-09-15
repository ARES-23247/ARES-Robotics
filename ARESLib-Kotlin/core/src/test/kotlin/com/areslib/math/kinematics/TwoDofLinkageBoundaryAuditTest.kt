package com.areslib.math.kinematics

import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TwoDofLinkageBoundaryAuditTest {
    private fun arm(length: Double) = TwoDofLinkageKinematics(
        TwoDofLinkageParameters(length, length, 1.0, 1.0),
    )

    @Test
    fun `workspace excludes outside targets when squared lengths overflow`() {
        val arm = arm(1e200)
        assertFalse(arm.isReachable(3e200, 0.0))
        assertNull(arm.inverseKinematics(3e200, 0.0))
        assertTrue(arm.isReachable(1e200, 0.0))
    }

    @Test
    fun `workspace excludes outside targets when squared lengths underflow`() {
        val arm = arm(1e-200)
        assertFalse(arm.isReachable(3e-200, 0.0))
        assertNull(arm.inverseKinematics(3e-200, 0.0))
        assertTrue(arm.isReachable(1e-200, 0.0))
    }

    @Test
    fun `inverse solution preserves equilateral triangle at huge scale`() {
        assertEquilateralSolutions(1e200)
    }

    @Test
    fun `inverse solution preserves equilateral triangle at tiny scale`() {
        assertEquilateralSolutions(1e-200)
    }

    private fun assertEquilateralSolutions(length: Double) {
        val arm = arm(length)
        for (branch in ElbowConfiguration.entries) {
            val solution = assertNotNull(arm.inverseKinematics(length, 0.0, branch))
            val sign = if (branch == ElbowConfiguration.ELBOW_UP) -1.0 else 1.0
            assertEquals(sign * 2.0 * PI / 3.0, solution.theta2Rad, 2e-14)
            assertEquals(-sign * PI / 3.0, solution.theta1Rad, 2e-14)
            val pose = arm.forwardKinematics(solution.theta1Rad, solution.theta2Rad)
            assertEquals(1.0, pose.x / length, 2e-14)
            assertEquals(0.0, pose.y / length, 2e-14)
        }
    }

    @Test
    fun `singularity classification does not depend on length scale`() {
        for (length in listOf(1e-200, 1.0, 1e200)) {
            val arm = arm(length)
            assertTrue(arm.isNearSingularity(0.0, 0.0), "length=$length")
            assertTrue(arm.isNearSingularity(0.0, PI), "length=$length")
            assertFalse(arm.isNearSingularity(0.0, PI / 2.0), "length=$length")
        }
    }

    @Test
    fun `nonfinite targets never report reachable for huge links`() {
        val arm = arm(1e200)
        for (bad in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            assertFalse(arm.isReachable(bad, 0.0))
            assertFalse(arm.isReachable(0.0, bad))
            assertNull(arm.inverseKinematics(bad, 0.0))
            assertNull(arm.inverseKinematics(0.0, bad))
        }
    }

    @Test
    fun `workspace inner hole remains significant for millimeter links`() {
        val arm = TwoDofLinkageKinematics(TwoDofLinkageParameters(2e-4, 1e-4, 1.0, 1.0))
        assertFalse(arm.isReachable(0.0, 0.0))
        assertNull(arm.inverseKinematics(0.0, 0.0))
        assertTrue(arm.isReachable(2e-4, 0.0))
    }

    @Test
    fun `singularity rejects invalid policy and treats unknown angles conservatively`() {
        val arm = arm(1.0)
        for (bad in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            assertTrue(arm.isNearSingularity(bad, PI / 2))
            assertTrue(arm.isNearSingularity(0.0, bad))
            assertFailsWith<IllegalArgumentException> { arm.isNearSingularity(0.0, 0.0, bad) }
        }
        assertFailsWith<IllegalArgumentException> { arm.isNearSingularity(0.0, 0.0, -0.1) }
        assertFalse(arm.isNearSingularity(0.0, 0.0, 0.0))
        assertTrue(arm.isNearSingularity(0.0, PI / 2, 2.0))
    }

    @Test
    fun `finite targets remain solvable when physical maximum reach overflows`() {
        val arm = arm(Double.MAX_VALUE)
        assertTrue(arm.isReachable(Double.MAX_VALUE, Double.MAX_VALUE))
        val q = assertNotNull(arm.inverseKinematics(Double.MAX_VALUE, Double.MAX_VALUE))
        assertEquals(-PI / 2, q.theta2Rad, 2e-14)
        assertEquals(PI / 2, q.theta1Rad, 2e-14)
    }

    @Test
    fun `unresolved short link still yields finite branch and dominant link direction`() {
        for (shortFirst in listOf(false, true)) {
            val p = if (shortFirst) TwoDofLinkageParameters(Double.MIN_VALUE, 1e200, 1.0, 1.0)
                else TwoDofLinkageParameters(1e200, Double.MIN_VALUE, 1.0, 1.0)
            val arm = TwoDofLinkageKinematics(p)
            for (branch in ElbowConfiguration.entries) {
                val q = assertNotNull(arm.inverseKinematics(0.0, 1e200, branch))
                val pose = arm.forwardKinematics(q.theta1Rad, q.theta2Rad)
                assertEquals(0.0, pose.x / 1e200, 1e-14)
                assertEquals(1.0, pose.y / 1e200, 1e-14)
            }
        }
    }
}
