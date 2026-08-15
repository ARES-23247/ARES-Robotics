package com.areslib.math.kinematics

import kotlin.math.cos
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TwoDofLinkagePlantTest {
    private val linkage = TwoDofLinkageParameters(
        l1 = 0.45,
        l2 = 0.30,
        m1 = 1.2,
        m2 = 0.7,
        rc1 = 0.20,
        rc2 = 0.13,
    )

    @Test
    fun `gravity compensation holds a stationary linkage`() {
        val torquePerVolt1 = 2.0
        val torquePerVolt2 = 1.0
        val plant = TwoDofLinkagePlant(
            TwoDofLinkagePlantParameters(
                linkage = linkage,
                joint1TorquePerVoltNm = torquePerVolt1,
                joint2TorquePerVoltNm = torquePerVolt2,
            ),
        )
        val q1 = 0.45
        val q2 = -0.70
        plant.reset(q1, q2)
        val gravity1 = (linkage.m1 * linkage.rc1 + linkage.m2 * linkage.l1) * linkage.g * cos(q1) +
            linkage.m2 * linkage.rc2 * linkage.g * cos(q1 + q2)
        val gravity2 = linkage.m2 * linkage.rc2 * linkage.g * cos(q1 + q2)

        repeat(100) { plant.step(gravity1 / torquePerVolt1, gravity2 / torquePerVolt2, 0.01) }

        assertEquals(q1, plant.joint1PositionRad, 1e-9)
        assertEquals(q2, plant.joint2PositionRad, 1e-9)
        assertEquals(0.0, plant.joint1VelocityRadPerSec, 1e-9)
        assertEquals(0.0, plant.joint2VelocityRadPerSec, 1e-9)
    }

    @Test
    fun `accepted voltage moves joints while hard limits remain fail safe`() {
        val plant = TwoDofLinkagePlant(
            TwoDofLinkagePlantParameters(
                linkage = linkage,
                joint1TorquePerVoltNm = 1.0,
                joint2TorquePerVoltNm = 0.8,
                joint1MinimumRad = -0.25,
                joint1MaximumRad = 0.25,
                joint2MinimumRad = -0.5,
                joint2MaximumRad = 0.5,
            ),
        )
        repeat(500) { plant.step(12.0, -12.0, 0.01) }

        assertTrue(plant.joint1PositionRad in -0.25..0.25)
        assertTrue(plant.joint2PositionRad in -0.5..0.5)
        assertEquals(0.25, plant.joint1PositionRad, 1e-12)
        assertEquals(-0.5, plant.joint2PositionRad, 1e-12)
        assertEquals(0.0, plant.joint1VelocityRadPerSec, 1e-12)
        assertEquals(0.0, plant.joint2VelocityRadPerSec, 1e-12)
    }

    @Test
    fun `identical command streams are deterministic`() {
        val params = TwoDofLinkagePlantParameters(linkage, 1.2, 0.9)
        val first = TwoDofLinkagePlant(params)
        val second = TwoDofLinkagePlant(params)
        repeat(300) { step ->
            val voltage1 = if (step < 150) 4.0 else -2.0
            val voltage2 = if (step % 40 < 20) 1.5 else -1.5
            first.step(voltage1, voltage2, 0.005)
            second.step(voltage1, voltage2, 0.005)
        }

        assertEquals(first.joint1PositionRad, second.joint1PositionRad, 0.0)
        assertEquals(first.joint2PositionRad, second.joint2PositionRad, 0.0)
        assertEquals(first.joint1VelocityRadPerSec, second.joint1VelocityRadPerSec, 0.0)
        assertEquals(first.joint2VelocityRadPerSec, second.joint2VelocityRadPerSec, 0.0)
    }
}
