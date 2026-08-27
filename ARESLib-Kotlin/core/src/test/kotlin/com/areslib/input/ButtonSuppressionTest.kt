package com.areslib.input

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ButtonSuppressionTest {
    @Test
    fun `higher priority chord consumes its buttons until every member returns neutral`() {
        val frame = InputFrame(axisCapacity = 1, buttonCapacity = 8)
        val suppression = ButtonSuppressionState(8)
        val chord = SuppressingButtonChordSource(intArrayOf(1, 2), 75_000_000L, suppression)
        val first = SuppressibleButtonSource(1, suppression)

        frame.beginSample(true, reportedAxisCount = 0, reportedButtonCount = 8, sampleTimeNanos = 0L)
        frame.setButton(1, true)
        frame.setButton(2, true)

        assertTrue(chord.sample(frame, 0L))
        assertFalse(first.sample(frame, 0L))

        frame.beginSample(true, reportedAxisCount = 0, reportedButtonCount = 8, sampleTimeNanos = 20_000_000L)
        frame.setButton(1, true)
        assertFalse(chord.sample(frame, 20_000_000L))
        assertFalse(first.sample(frame, 20_000_000L))

        frame.beginSample(true, reportedAxisCount = 0, reportedButtonCount = 8, sampleTimeNanos = 40_000_000L)
        assertFalse(chord.sample(frame, 40_000_000L))
        assertFalse(first.sample(frame, 40_000_000L))

        frame.beginSample(true, reportedAxisCount = 0, reportedButtonCount = 8, sampleTimeNanos = 60_000_000L)
        frame.setButton(1, true)
        assertFalse(chord.sample(frame, 60_000_000L))
        assertTrue(first.sample(frame, 60_000_000L))
    }
}
