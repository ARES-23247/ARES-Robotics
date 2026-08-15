package com.areslib.math.kinematics

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

class TwoDofLinkageKinematicsTest {

    private val params = TwoDofLinkageParameters(
        l1 = 0.40,
        l2 = 0.30,
        m1 = 1.0,
        m2 = 0.5,
        rc1 = 0.20,
        rc2 = 0.15,
        g = 9.81,
    )
    private val kinematics = TwoDofLinkageKinematics(params)

    @Test
    fun `forward kinematics at horizontal extension computes correct coordinates`() {
        val pose = kinematics.forwardKinematics(0.0, 0.0)
        assertEquals(0.70, pose.x, 1e-4)
        assertEquals(0.0, pose.y, 1e-4)
    }

    @Test
    fun `forward kinematics at 90 deg elbow computes correct right triangle position`() {
        val pose = kinematics.forwardKinematics(0.0, PI / 2.0)
        assertEquals(0.40, pose.x, 1e-4)
        assertEquals(0.30, pose.y, 1e-4)
    }

    @Test
    fun `reachability bounds check returns false outside maximum radius`() {
        assertTrue(kinematics.isReachable(0.50, 0.20))
        assertFalse(kinematics.isReachable(0.80, 0.0)) // > 0.70m max reach
        assertFalse(kinematics.isReachable(0.05, 0.0)) // < 0.10m min reach
    }

    @Test
    fun `inverse kinematics accurately recovers forward kinematics target`() {
        val targetTheta1 = 0.35
        val targetTheta2 = -0.65
        val fk = kinematics.forwardKinematics(targetTheta1, targetTheta2)

        val ik = kinematics.inverseKinematics(fk.x, fk.y, ElbowConfiguration.ELBOW_UP)
        assertNotNull(ik)
        assertEquals(targetTheta1, ik!!.theta1Rad, 1e-4)
        assertEquals(targetTheta2, ik.theta2Rad, 1e-4)
    }

    @Test
    fun `inverse kinematics returns null for unreachable points`() {
        assertNull(kinematics.inverseKinematics(1.5, 2.0))
    }

    @Test
    fun `singularity detection identifies full extension and folding`() {
        assertTrue(kinematics.isNearSingularity(0.0, 0.0)) // theta2 = 0 (full extension)
        assertTrue(kinematics.isNearSingularity(0.0, PI)) // theta2 = PI (folded back)
        assertFalse(kinematics.isNearSingularity(0.0, PI / 2.0))
    }

    @Test
    fun `lagrangian gravity torque matches analytical static equilibrium equations`() {
        // At full horizontal extension:
        // G1 = (m1 * rc1 + m2 * L1) * g + (m2 * rc2) * g
        //    = (1.0 * 0.20 + 0.5 * 0.40) * 9.81 + (0.5 * 0.15) * 9.81
        //    = (0.40 + 0.075) * 9.81 = 0.475 * 9.81 = 4.65975 N*m
        // G2 = m2 * rc2 * g = 0.5 * 0.15 * 9.81 = 0.73575 N*m
        val torques = kinematics.gravityTorque(0.0, 0.0)
        assertEquals(4.65975, torques[0], 1e-4)
        assertEquals(0.73575, torques[1], 1e-4)

        // At vertical position (theta1 = PI/2, theta2 = 0), cos(PI/2) = 0 -> zero gravity torque on horizontal axis
        val verticalTorques = kinematics.gravityTorque(PI / 2.0, 0.0)
        assertEquals(0.0, verticalTorques[0], 1e-4)
        assertEquals(0.0, verticalTorques[1], 1e-4)
    }
}
