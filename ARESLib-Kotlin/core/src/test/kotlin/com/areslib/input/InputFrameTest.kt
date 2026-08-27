package com.areslib.input

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class InputFrameTest {
    @Test
    fun `new samples clear stale axes and buttons without changing capacity`() {
        val frame = InputFrame(axisCapacity = 4, buttonCapacity = 70)
        frame.beginSample(true, reportedAxisCount = 3, reportedButtonCount = 70, sampleTimeNanos = 10L)
        frame.setAxis(2, 0.75)
        frame.setButton(0, true)
        frame.setButton(69, true)

        assertEquals(0.75, frame.axis(2))
        assertTrue(frame.button(0))
        assertTrue(frame.button(69))

        frame.beginSample(true, reportedAxisCount = 1, reportedButtonCount = 2, sampleTimeNanos = 20L)

        assertEquals(0.0, frame.axis(2))
        assertFalse(frame.button(0))
        assertFalse(frame.button(69))
        assertEquals(2L, frame.sequence)
        assertEquals(20L, frame.timestampNanos)
    }

    @Test
    fun `disconnect hides and clears all values`() {
        val frame = InputFrame(2, 2)
        frame.beginSample(true, 2, 2, 1L)
        frame.setAxis(0, 1.0)
        frame.setButton(1, true)

        frame.beginSample(false, sampleTimeNanos = 2L)

        assertFalse(frame.isConnected)
        assertEquals(0, frame.axisCount)
        assertEquals(0, frame.buttonCount)
        assertEquals(0.0, frame.axis(0))
        assertFalse(frame.button(1))
    }

    @Test
    fun `non-finite axes fail safe to zero and invalid writes fail clearly`() {
        val frame = InputFrame(1, 1)
        frame.beginSample(true, 1, 1, 0L)
        frame.setAxis(0, Double.NaN)
        assertEquals(0.0, frame.axis(0))

        assertThrows(IllegalArgumentException::class.java) { frame.setAxis(1, 0.0) }
        assertThrows(IllegalArgumentException::class.java) { frame.setButton(1, true) }
        assertThrows(IllegalArgumentException::class.java) {
            frame.beginSample(true, reportedAxisCount = 2)
        }
    }

    @Test
    fun `copy reuses destination storage and preserves sample contents`() {
        val source = InputFrame(2, 65)
        source.beginSample(true, 2, 65, 42L)
        source.setAxis(1, -0.3)
        source.setButton(64, true)
        val destination = InputFrame(4, 128)

        destination.copyFrom(source)

        assertTrue(destination.isConnected)
        assertEquals(42L, destination.timestampNanos)
        assertEquals(-0.3, destination.axis(1))
        assertTrue(destination.button(64))
    }
}
