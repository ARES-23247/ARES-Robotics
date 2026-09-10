package com.areslib.math.kinematics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TwoDofLinkagePlantBoundaryAuditTest {
    @Test
    fun `zero Coriolis coefficient does not multiply an overflowing velocity square`() {
        val plant = TwoDofLinkagePlant(TwoDofLinkagePlantParameters(
            TwoDofLinkageParameters(1.0, 1.0, 1.0, 1.0), 1.0, 1.0, 0.0, 0.0,
            -Double.MAX_VALUE, Double.MAX_VALUE, -Double.MAX_VALUE, Double.MAX_VALUE,
        ))
        plant.reset(0.0, 0.0, Double.MAX_VALUE, Double.MAX_VALUE)
        plant.step(0.0, 0.0, 1e-300)
        assertTrue(plant.joint1PositionRad.isFinite())
        assertTrue(plant.joint2PositionRad.isFinite())
        assertEquals(Double.MAX_VALUE, plant.joint1VelocityRadPerSec)
    }

    private fun plant(length: Double = 1.0, mass: Double = 1.0, minimum: Double = -3.0) =
        TwoDofLinkagePlant(
            TwoDofLinkagePlantParameters(
                linkage = TwoDofLinkageParameters(length, length, mass, mass),
                joint1TorquePerVoltNm = 1.0,
                joint2TorquePerVoltNm = 1.0,
                joint1ViscousDampingNmPerRadPerSec = 0.0,
                joint2ViscousDampingNmPerRadPerSec = 0.0,
                joint1MinimumRad = minimum,
                joint1MaximumRad = 3.0,
                joint2MinimumRad = minimum,
                joint2MaximumRad = 3.0,
            ),
        )

    @Test
    fun `small positive definite inertia does not become singular by absolute threshold`() {
        val plant = plant(length = 0.001, mass = 0.001)
        plant.step(0.0, 0.0, 1e-12)
        assertTrue(plant.joint1VelocityRadPerSec.isFinite())
        assertTrue(plant.joint1VelocityRadPerSec < 0.0)
        assertTrue(plant.joint2VelocityRadPerSec.isFinite())
    }

    @Test
    fun `startup state respects joint limits excluding zero`() {
        val plant = plant(minimum = 1.0)
        assertEquals(1.0, plant.joint1PositionRad)
        assertEquals(1.0, plant.joint2PositionRad)
        assertEquals(0.0, plant.joint1VelocityRadPerSec)
        assertEquals(0.0, plant.joint2VelocityRadPerSec)
    }

    @Test
    fun `reset removes outward velocity at hard stops and preserves inward velocity`() {
        val plant = plant(minimum = 1.0)
        plant.reset(1.0, 3.0, -2.0, 2.0)
        assertEquals(0.0, plant.joint1VelocityRadPerSec)
        assertEquals(0.0, plant.joint2VelocityRadPerSec)
        plant.reset(1.0, 3.0, 2.0, -2.0)
        assertEquals(2.0, plant.joint1VelocityRadPerSec)
        assertEquals(-2.0, plant.joint2VelocityRadPerSec)
    }

    @Test
    fun `unrepresentable dynamics reject before corrupting a finite state`() {
        val plant = plant()
        plant.reset(0.1, 0.5, Double.MAX_VALUE, Double.MAX_VALUE)
        assertFailsWith<IllegalStateException> { plant.step(0.0, 0.0, 0.001) }
        assertEquals(0.1, plant.joint1PositionRad)
        assertEquals(0.5, plant.joint2PositionRad)
        assertEquals(Double.MAX_VALUE, plant.joint1VelocityRadPerSec)
        assertEquals(Double.MAX_VALUE, plant.joint2VelocityRadPerSec)
        plant.reset()
        plant.step(0.0, 0.0, 0.001)
        assertTrue(plant.joint1PositionRad.isFinite())
        assertTrue(plant.joint2PositionRad.isFinite())
    }

    @Test
    fun `later substep failure rolls back earlier substeps in the external call`() {
        val params = TwoDofLinkagePlantParameters(TwoDofLinkageParameters(1.0, 1.0, 1.0, 1.0),
            1.0, 1.0, 0.0, 0.0, -Double.MAX_VALUE, Double.MAX_VALUE, -Double.MAX_VALUE, Double.MAX_VALUE)
        val firstSubstep = TwoDofLinkagePlant(params)
        firstSubstep.reset(0.1, 0.5, 1e100, 1e100)
        firstSubstep.step(0.0, 0.0, 0.002)
        assertTrue(firstSubstep.joint1PositionRad.isFinite())
        assertTrue(firstSubstep.joint1PositionRad != 0.1)
        val atomic = TwoDofLinkagePlant(params)
        atomic.reset(0.1, 0.5, 1e100, 1e100)
        assertFailsWith<IllegalStateException> { atomic.step(0.0, 0.0, 0.004) }
        assertEquals(0.1, atomic.joint1PositionRad)
        assertEquals(0.5, atomic.joint2PositionRad)
        assertEquals(1e100, atomic.joint1VelocityRadPerSec)
        assertEquals(1e100, atomic.joint2VelocityRadPerSec)
    }

    @Test
    fun `invalid timestep and reset reject without changing state`() {
        val plant = plant()
        plant.reset(0.1, 0.2, 0.3, 0.4)
        for (dt in listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY, Math.nextUp(0.1))) {
            assertFailsWith<IllegalArgumentException> { plant.step(1.0, 1.0, dt) }
        }
        for (bad in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            assertFailsWith<IllegalArgumentException> { plant.reset(bad, 0.0) }
            assertFailsWith<IllegalArgumentException> { plant.reset(0.0, bad) }
            assertFailsWith<IllegalArgumentException> { plant.reset(0.0, 0.0, bad, 0.0) }
            assertFailsWith<IllegalArgumentException> { plant.reset(0.0, 0.0, 0.0, bad) }
        }
        assertEquals(0.1, plant.joint1PositionRad)
        assertEquals(0.2, plant.joint2PositionRad)
        assertEquals(0.3, plant.joint1VelocityRadPerSec)
        assertEquals(0.4, plant.joint2VelocityRadPerSec)
    }

    @Test
    fun `invalid voltages neutralize individually and finite commands clamp at twelve volts`() {
        for (bad in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            val actual = plant()
            val expected = plant()
            actual.step(bad, 1e200, 0.1)
            expected.step(0.0, 12.0, 0.1)
            assertEquals(expected.joint1PositionRad, actual.joint1PositionRad)
            assertEquals(expected.joint2PositionRad, actual.joint2PositionRad)
            actual.step(-1e200, bad, 0.1)
            expected.step(-12.0, 0.0, 0.1)
            assertEquals(expected.joint1PositionRad, actual.joint1PositionRad)
            assertEquals(expected.joint2PositionRad, actual.joint2PositionRad)
        }
    }
}
