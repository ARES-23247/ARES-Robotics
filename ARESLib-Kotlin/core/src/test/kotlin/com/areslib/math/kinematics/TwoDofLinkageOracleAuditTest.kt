package com.areslib.math.kinematics

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TwoDofLinkageOracleAuditTest {
    @Test
    fun `both inverse branches reproduce seeded targets across length scales`() {
        val random = Random(4201)
        repeat(5_000) {
            val scale = Math.scalb(1.0, random.nextInt(-600, 601))
            val p = TwoDofLinkageParameters(scale, scale * random.nextDouble(0.05, 1.0), 1.0, 1.0)
            val arm = TwoDofLinkageKinematics(p)
            val target = arm.forwardKinematics(random.nextDouble(-PI, PI), random.nextDouble(-PI, PI))
            assertTrue(arm.isReachable(target.x, target.y))
            for (branch in ElbowConfiguration.entries) {
                val q = assertNotNull(arm.inverseKinematics(target.x, target.y, branch))
                assertTrue(q.theta1Rad.isFinite() && q.theta2Rad.isFinite())
                val recovered = arm.forwardKinematics(q.theta1Rad, q.theta2Rad)
                assertEquals(target.x / scale, recovered.x / scale, 3e-14)
                assertEquals(target.y / scale, recovered.y / scale, 3e-14)
            }
        }
    }

    @Test
    fun `jacobian agrees with numerical Cartesian derivatives`() {
        val random = Random(4202)
        repeat(500) {
            val p = TwoDofLinkageParameters(random.nextDouble(0.01, 2.0), random.nextDouble(0.01, 2.0), 1.0, 1.0)
            val arm = TwoDofLinkageKinematics(p)
            val q1 = random.nextDouble(-PI, PI)
            val q2 = random.nextDouble(-PI, PI)
            val output = DoubleArray(4)
            arm.jacobian(q1, q2, output)
            val nested = arm.jacobian(q1, q2)
            val h = 1e-5
            for (joint in 0..1) {
                val plus = arm.forwardKinematics(q1 + if (joint == 0) h else 0.0, q2 + if (joint == 1) h else 0.0)
                val minus = arm.forwardKinematics(q1 - if (joint == 0) h else 0.0, q2 - if (joint == 1) h else 0.0)
                assertEquals((plus.x - minus.x) / (2 * h), output[joint], 2e-9)
                assertEquals((plus.y - minus.y) / (2 * h), output[joint + 2], 2e-9)
                assertEquals(nested[0][joint], output[joint])
                assertEquals(nested[1][joint], output[joint + 2])
            }
            assertEquals(output[0] * output[3] - output[1] * output[2], arm.jacobianDeterminant(q2), 2e-14)
        }
    }

    @Test
    fun `gravity compensation agrees with numerical potential energy gradient`() {
        val random = Random(4203)
        repeat(500) {
            val p = parameters(random)
            val q1 = random.nextDouble(-PI, PI)
            val q2 = random.nextDouble(-PI, PI)
            val arm = TwoDofLinkageKinematics(p)
            val torque = DoubleArray(2)
            arm.gravityTorque(q1, q2, torque)
            val allocated = arm.gravityTorque(q1, q2)
            val h = 1e-5
            val d1 = (potential(p, q1 + h, q2) - potential(p, q1 - h, q2)) / (2 * h)
            val d2 = (potential(p, q1, q2 + h) - potential(p, q1, q2 - h)) / (2 * h)
            assertEquals(d1, torque[0], 1e-7)
            assertEquals(d2, torque[1], 1e-7)
            assertEquals(allocated[0], torque[0])
            assertEquals(allocated[1], torque[1])
        }
    }

    @Test
    fun `plant acceleration agrees with energy derived mass matrix and derivatives`() {
        // Independent numerical Lagrange oracle: build M from COM Cartesian velocity
        // Jacobians and rod angular energy, differentiate M and U, then solve the 2x2
        // matrix directly. Production uses cached physical terms and a Schur solve.
        val random = Random(4204)
        repeat(500) {
            val p = parameters(random)
            val q = doubleArrayOf(random.nextDouble(-2.0, 2.0), random.nextDouble(-2.0, 2.0))
            val v = doubleArrayOf(random.nextDouble(-2.0, 2.0), random.nextDouble(-2.0, 2.0))
            val volts = doubleArrayOf(random.nextDouble(-10.0, 10.0), random.nextDouble(-10.0, 10.0))
            val matrix = massMatrix(p, q[0], q[1])
            val derivative = Array(2) { DoubleArray(4) }
            val gravity = DoubleArray(2)
            val h = 1e-5
            for (joint in 0..1) {
                val plus = q.copyOf().also { it[joint] += h }
                val minus = q.copyOf().also { it[joint] -= h }
                val mp = massMatrix(p, plus[0], plus[1])
                val mm = massMatrix(p, minus[0], minus[1])
                for (entry in 0..3) derivative[joint][entry] = (mp[entry] - mm[entry]) / (2 * h)
                gravity[joint] = (potential(p, plus[0], plus[1]) - potential(p, minus[0], minus[1])) / (2 * h)
            }
            val rhs = DoubleArray(2)
            for (i in 0..1) {
                var coriolis = 0.0
                for (j in 0..1) for (k in 0..1) {
                    coriolis += (derivative[k][2 * i + j] - 0.5 * derivative[i][2 * j + k]) * v[j] * v[k]
                }
                rhs[i] = volts[i] - coriolis - gravity[i]
            }
            val determinant = matrix[0] * matrix[3] - matrix[1] * matrix[2]
            val expected = doubleArrayOf(
                (matrix[3] * rhs[0] - matrix[1] * rhs[1]) / determinant,
                (matrix[0] * rhs[1] - matrix[2] * rhs[0]) / determinant,
            )
            val plant = TwoDofLinkagePlant(TwoDofLinkagePlantParameters(
                p, 1.0, 1.0, 0.0, 0.0, -10.0, 10.0, -10.0, 10.0,
            ))
            plant.reset(q[0], q[1], v[0], v[1])
            val dt = 1e-7
            plant.step(volts[0], volts[1], dt)
            val actual = doubleArrayOf((plant.joint1VelocityRadPerSec - v[0]) / dt, (plant.joint2VelocityRadPerSec - v[1]) / dt)
            for (joint in 0..1) assertEquals(expected[joint], actual[joint], 2e-6 * maxOf(1.0, abs(expected[joint])))
        }
    }

    private fun parameters(random: Random): TwoDofLinkageParameters {
        val l1 = random.nextDouble(0.1, 2.0)
        val l2 = random.nextDouble(0.1, 2.0)
        return TwoDofLinkageParameters(l1, l2, random.nextDouble(0.1, 5.0), random.nextDouble(0.1, 5.0),
            random.nextDouble() * l1, random.nextDouble() * l2)
    }

    private fun potential(p: TwoDofLinkageParameters, q1: Double, q2: Double): Double =
        p.g * (p.m1 * p.rc1 * sin(q1) + p.m2 * (p.l1 * sin(q1) + p.rc2 * sin(q1 + q2)))

    private fun massMatrix(p: TwoDofLinkageParameters, q1: Double, q2: Double): DoubleArray {
        val j11 = -p.l1 * sin(q1) - p.rc2 * sin(q1 + q2)
        val j21 = p.l1 * cos(q1) + p.rc2 * cos(q1 + q2)
        val j12 = -p.rc2 * sin(q1 + q2)
        val j22 = p.rc2 * cos(q1 + q2)
        val i1 = p.m1 * p.l1 * p.l1 / 12
        val i2 = p.m2 * p.l2 * p.l2 / 12
        val offDiagonal = p.m2 * (j11 * j12 + j21 * j22) + i2
        return doubleArrayOf(p.m1 * p.rc1 * p.rc1 + i1 + p.m2 * (j11 * j11 + j21 * j21) + i2,
            offDiagonal, offDiagonal, p.m2 * (j12 * j12 + j22 * j22) + i2)
    }
}
