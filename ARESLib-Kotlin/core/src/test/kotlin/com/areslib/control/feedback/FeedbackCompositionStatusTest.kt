package com.areslib.control.feedback

import org.junit.jupiter.api.Test
import kotlin.test.*

class FeedbackCompositionStatusTest {
    @Test fun `PID zero deadzone rejection reset and repair have distinct status`() {
        val pid = PIDController(0.0, 0.0, 0.0)
        assertFalse(pid.lastCalculationValid)
        assertEquals(0.0, pid.calculate(0.0, 1.0, 0.02))
        assertTrue(pid.lastCalculationValid)
        pid.deadzone = 2.0
        assertEquals(0.0, pid.calculate(0.0, 1.0, 0.02))
        assertTrue(pid.lastCalculationValid)
        pid.p = Double.NaN
        assertEquals(0.0, pid.calculate(0.0, 1.0, 0.02))
        assertFalse(pid.lastCalculationValid)
        pid.p = 1.0
        pid.deadzone = 0.0
        assertEquals(1.0, pid.calculate(0.0, 1.0, 0.02))
        assertTrue(pid.lastCalculationValid)
        pid.reset()
        assertFalse(pid.lastCalculationValid)
    }

    @Test fun `ADRC rejects observer overflow without retaining partial state and recovers`() {
        val adrc = LinearADRC(1.0, 1.0, 1e154)
        assertEquals(0.0, adrc.calculate(2.0, 2.0, 1.0))
        assertFalse(adrc.lastCalculationValid)
        assertEquals(2.0, adrc.xHat1)
        assertEquals(0.0, adrc.xHat2)
        adrc.omegaO = 1.0
        assertEquals(1.0, adrc.calculate(3.0, 2.0, 0.02))
        assertTrue(adrc.lastCalculationValid)
    }

    @Test fun `ADRC rejects corrupt state and invalid bounds including outward infinities`() {
        val faults: List<(LinearADRC) -> Unit> = listOf(
            { it.xHat1 = Double.NaN }, { it.xHat2 = Double.POSITIVE_INFINITY },
            { it.omegaC = -1.0 }, { it.omegaO = -1.0 },
            { it.setOutputLimits(Double.POSITIVE_INFINITY, Double.NaN) },
            { it.setOutputLimits(Double.NaN, Double.NEGATIVE_INFINITY) },
            { it.enableContinuousInput(-Double.MAX_VALUE, Double.MAX_VALUE) })
        for (fault in faults) {
            val adrc = LinearADRC(1.0, 1.0, 1.0).also(fault)
            assertEquals(0.0, adrc.calculate(4.0, 3.0, 0.02))
            assertFalse(adrc.lastCalculationValid)
            assertEquals(3.0, adrc.xHat1)
            assertEquals(0.0, adrc.xHat2)
        }
    }

    @Test fun `negative plant gain unbounded output and zero bandwidth remain supported`() {
        val adrc = LinearADRC(-2.0, 2.0, 0.0)
        adrc.setOutputLimits(Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY)
        assertEquals(-1.0, adrc.calculate(1.0, 0.0, 0.02))
        assertTrue(adrc.lastCalculationValid)
        adrc.reset(1.0)
        assertFalse(adrc.lastCalculationValid)
        adrc.omegaC = 0.0
        assertEquals(0.0, adrc.calculate(1.0, 1.0, 0.02), 0.0)
        assertTrue(adrc.lastCalculationValid)
    }
}
