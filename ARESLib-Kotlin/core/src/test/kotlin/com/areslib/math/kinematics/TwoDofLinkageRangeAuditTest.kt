package com.areslib.math.kinematics

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TwoDofLinkageRangeAuditTest {
    @Test
    fun `finite angle sum overflow does not destroy forward geometry`() {
        val q = Double.MAX_VALUE
        val c = cos(q)
        val s = sin(q)
        val arm = TwoDofLinkageKinematics(TwoDofLinkageParameters(1.0, 1.0, 1.0, 1.0))
        val x = c + c * c - s * s
        val y = s + 2 * c * s
        val pose = arm.forwardKinematics(q, q)
        assertEquals(x, pose.x, 1e-15)
        assertEquals(y, pose.y, 1e-15)
        val buffer = DoubleArray(4)
        arm.forwardKinematics(q, q, buffer)
        assertEquals(pose.x, buffer[0])
        assertEquals(pose.y, buffer[1])
        arm.jacobian(q, q, buffer)
        assertEquals(-y, buffer[0], 1e-15)
        assertEquals(x, buffer[2], 1e-15)
        val nested = arm.jacobian(q, q)
        assertEquals(nested[0][1], buffer[1])
        assertEquals(nested[1][1], buffer[3])
        assertTrue(arm.gravityTorque(q, q).all(Double::isFinite))
    }

    @Test
    fun `small sine recovers a representable determinant after length product overflow`() {
        val arm = TwoDofLinkageKinematics(TwoDofLinkageParameters(1e200, 1e200, 1.0, 1.0))
        assertEquals(1.0, arm.jacobianDeterminant(1e-300) / 1e100, 5e-15)
        assertEquals(0.0, arm.jacobianDeterminant(0.0))
    }

    @Test
    fun `gravity product overflow does not hide finite torque`() {
        val arm = TwoDofLinkageKinematics(TwoDofLinkageParameters(1e200, 1.0, 1e200, 0.0, 1e200, 0.0, 1e-200))
        assertEquals(1.0, arm.gravityTorque(0.0, 0.0)[0] / 1e200, 5e-15)
    }

    @Test
    fun `gravity product underflow does not discard recoverable torque`() {
        val arm = TwoDofLinkageKinematics(TwoDofLinkageParameters(1e-200, 1.0, 1e-200, 0.0, 1e-200, 0.0, 1e200))
        assertEquals(1.0, arm.gravityTorque(0.0, 0.0)[0] / 1e-200, 5e-15)
    }

    @Test
    fun `opposing unrepresentable gravity components cancel before final conversion`() {
        val arm = TwoDofLinkageKinematics(TwoDofLinkageParameters(1.0, 1.0, 0.0, Double.MAX_VALUE, 0.0, 1.0, 2.0))
        val torques = arm.gravityTorque(0.0, PI)
        assertEquals(0.0, torques[0])
        assertEquals(Double.NEGATIVE_INFINITY, torques[1]) // This component truly exceeds Double range.
        val buffer = DoubleArray(2)
        arm.gravityTorque(0.0, PI, buffer)
        assertEquals(torques[0], buffer[0])
        assertEquals(torques[1], buffer[1])
    }
}
