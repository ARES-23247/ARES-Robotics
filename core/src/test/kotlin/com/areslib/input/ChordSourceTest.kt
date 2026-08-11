package com.areslib.input

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ChordSourceTest {
    @Test
    fun `buttons pressed inside window activate chord`() {
        val source = ChordSource.rawButtons(0, 1, simultaneityWindowNanos = 75L)
        val frame = InputFrame(1, 2)

        setButtons(frame, first = true, second = false)
        assertFalse(source.sample(frame, 0L))
        setButtons(frame, first = true, second = true)
        assertTrue(source.sample(frame, 75L))
        setButtons(frame, first = false, second = true)
        assertFalse(source.sample(frame, 76L))
    }

    @Test
    fun `button held before window does not form a late chord`() {
        val source = ChordSource.rawButtons(0, 1, simultaneityWindowNanos = 75L)
        val frame = InputFrame(1, 2)

        setButtons(frame, first = true, second = false)
        assertFalse(source.sample(frame, 10L))
        setButtons(frame, first = true, second = true)
        assertFalse(source.sample(frame, 86L))
        assertFalse(source.sample(frame, 100L), "a failed chord does not become valid merely by staying held")
    }

    private fun setButtons(frame: InputFrame, first: Boolean, second: Boolean) {
        frame.beginSample(true, 0, 2, 0L)
        frame.setButton(0, first)
        frame.setButton(1, second)
    }
}
