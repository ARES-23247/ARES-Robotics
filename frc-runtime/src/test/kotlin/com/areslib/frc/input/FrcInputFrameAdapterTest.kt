package com.areslib.frc.input

import com.areslib.input.InputFrame
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FrcInputFrameAdapterTest {
    @Test
    fun `preserves raw buttons and translates WPILib one-based numbering once`() {
        val source = FakeHidSource(axisValues = doubleArrayOf(-0.5, 0.75), rawButtonCount = 18)
        source.buttons[0] = true
        source.buttons[16] = true
        val frame = InputFrame()

        FrcInputFrameAdapter(source).sampleInto(frame, sampleTimeNanos = 100L)

        assertTrue(frame.isConnected)
        assertEquals(2, frame.axisCount)
        assertEquals(-0.5, frame.axis(0), 1e-9)
        assertEquals(0.75, frame.axis(1), 1e-9)
        assertTrue(frame.button(0), "WPILib raw button 1 maps to frame button 0")
        assertTrue(frame.button(16), "extra raw button 17 is retained")
        assertFalse(frame.button(17))
    }

    @Test
    fun `primary POV diagonals activate both neighboring directions`() {
        val source = FakeHidSource(povValue = 45)
        val frame = InputFrame()

        FrcInputFrameAdapter(source).sampleInto(frame, sampleTimeNanos = 101L)

        assertTrue(frame.button(FrcButtonIndex.POV_UP))
        assertTrue(frame.button(FrcButtonIndex.POV_RIGHT))
        assertFalse(frame.button(FrcButtonIndex.POV_DOWN))
        assertFalse(frame.button(FrcButtonIndex.POV_LEFT))
    }

    @Test
    fun `invalid values and disconnects fail neutral`() {
        val source = FakeHidSource(axisValues = doubleArrayOf(Double.NaN, 1.5), rawButtonCount = 1)
        source.buttons[0] = true
        val frame = InputFrame()
        val adapter = FrcInputFrameAdapter(source)

        adapter.sampleInto(frame, sampleTimeNanos = 102L)
        assertEquals(0.0, frame.axis(0))
        assertEquals(0.0, frame.axis(1))
        assertTrue(frame.button(0))

        source.connected = false
        adapter.sampleInto(frame, sampleTimeNanos = 103L)

        assertFalse(frame.isConnected)
        assertEquals(0, frame.axisCount)
        assertEquals(0, frame.buttonCount)
        assertFalse(frame.button(0))
    }

    @Test
    fun `impossible reported counts and read failures clear the frame`() {
        val source = FakeHidSource(rawButtonCount = FrcButtonIndex.MAX_RAW_BUTTON_COUNT + 1)
        val frame = InputFrame()
        val adapter = FrcInputFrameAdapter(source)

        adapter.sampleInto(frame, sampleTimeNanos = 104L)
        assertFalse(frame.isConnected)

        source.reportedButtonCount = 1
        source.throwOnRead = true
        adapter.sampleInto(frame, sampleTimeNanos = 105L)
        assertFalse(frame.isConnected)
        assertFalse(frame.button(0))
    }

    private class FakeHidSource(
        var connected: Boolean = true,
        private val axisValues: DoubleArray = DoubleArray(0),
        rawButtonCount: Int = 0,
        private var povValue: Int = -1,
    ) : FrcHidSource {
        val buttons: BooleanArray = BooleanArray(maxOf(rawButtonCount, 1))
        var reportedButtonCount: Int = rawButtonCount
        var throwOnRead: Boolean = false

        override fun isConnected(): Boolean = connected
        override fun axisCount(): Int = axisValues.size
        override fun buttonCount(): Int = reportedButtonCount
        override fun povCount(): Int = if (povValue == -1) 0 else 1

        override fun rawAxis(axisIndex: Int): Double {
            if (throwOnRead) throw IllegalStateException("simulated read failure")
            return axisValues[axisIndex]
        }

        override fun rawButton(buttonNumber: Int): Boolean {
            if (throwOnRead) throw IllegalStateException("simulated read failure")
            return buttons[buttonNumber - 1]
        }

        override fun pov(povIndex: Int): Int {
            if (throwOnRead) throw IllegalStateException("simulated read failure")
            return povValue
        }
    }
}
