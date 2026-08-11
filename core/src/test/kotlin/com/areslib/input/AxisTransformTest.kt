package com.areslib.input

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AxisTransformTest {
    @Test
    fun `deadband is removed rescaled and shaped`() {
        val transform = AxisTransform(deadband = 0.2, exponent = 2.0)

        assertEquals(0.0, transform.apply(0.1), 1e-12)
        assertEquals(0.25, transform.apply(0.6), 1e-12)
        assertEquals(-0.25, transform.apply(-0.6), 1e-12)
        assertEquals(1.0, transform.apply(2.0), 1e-12)
        assertEquals(0.0, transform.apply(Double.NaN), 1e-12)
    }

    @Test
    fun `unsigned trigger calibration and inversion are supported`() {
        assertEquals(0.5, AxisTransform.trigger().apply(0.5), 1e-12)
        assertEquals(-0.5, AxisTransform.trigger(inverted = true).apply(0.5), 1e-12)
    }

    @Test
    fun `axis threshold uses hysteresis`() {
        val frame = InputFrame(1, 1)
        val source = AxisThresholdSource(
            axisIndex = 0,
            pressThreshold = 0.7,
            releaseThreshold = 0.4,
            transform = AxisTransform.trigger(),
        )

        setAxis(frame, 0.0)
        assertFalse(source.sample(frame, 0L))
        setAxis(frame, 0.7)
        assertTrue(source.sample(frame, 1L))
        setAxis(frame, 0.5)
        assertTrue(source.sample(frame, 2L))
        setAxis(frame, 0.4)
        assertFalse(source.sample(frame, 3L))
    }

    private fun setAxis(frame: InputFrame, value: Double) {
        frame.beginSample(true, 1, 0, 0L)
        frame.setAxis(0, value)
    }
}
