package com.areslib.frc.input

import com.areslib.input.*
import org.junit.jupiter.api.assertThrows
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
        assertTrue(frame.isButtonAvailable(17))
        assertFalse(frame.isButtonAvailable(18), "reserved POV slots must not imply missing raw buttons exist")
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
        assertFalse(frame.isAxisAvailable(0))
        assertFalse(frame.isAxisAvailable(1))
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

    @Test
    fun `missing or invalid POV bypasses debounce and requires real neutral feedback`() {
        val source = FakeHidSource(povValue = 0)
        val frame = InputFrame()
        val adapter = FrcInputFrameAdapter(source)
        val binding = DigitalBinding(RawButtonSource(FrcButtonIndex.POV_UP), DigitalBindingTiming(releaseDebounceNanos = 100L), object : DigitalBindingListener {})
        adapter.sampleInto(frame, 0L); binding.update(frame, 0L)
        assertTrue(binding.isActive)
        source.reportedPovCount = 0
        adapter.sampleInto(frame, 1L); binding.update(frame, 1L)
        assertFalse(frame.isButtonAvailable(FrcButtonIndex.POV_UP)); assertFalse(binding.isActive)
        source.reportedPovCount = 1
        adapter.sampleInto(frame, 2L); binding.update(frame, 2L)
        assertFalse(binding.isActive)
        source.povValue = 999
        adapter.sampleInto(frame, 3L); binding.update(frame, 3L)
        assertFalse(frame.isButtonAvailable(FrcButtonIndex.POV_UP))
        source.povValue = -1
        adapter.sampleInto(frame, 4L); binding.update(frame, 4L)
        assertTrue(frame.isButtonAvailable(FrcButtonIndex.POV_UP))
        source.povValue = 0
        adapter.sampleInto(frame, 5L); binding.update(frame, 5L)
        assertTrue(binding.isActive)
    }

    @Test
    fun `undersized adapter storage invalidates the old frame before rejecting configuration`() {
        val frame = InputFrame(1, 1).apply { beginSample(true, 1, 1, 0L); setAxis(0, 1.0); setButton(0, true) }
        assertThrows<IllegalArgumentException> { FrcInputFrameAdapter(FakeHidSource()).sampleInto(frame, 1L) }
        assertFalse(frame.isConnected); assertFalse(frame.button(0))
    }

    private class FakeHidSource(
        var connected: Boolean = true,
        private val axisValues: DoubleArray = DoubleArray(0),
        rawButtonCount: Int = 0,
        var povValue: Int = -1,
    ) : FrcHidSource {
        val buttons: BooleanArray = BooleanArray(maxOf(rawButtonCount, 1))
        var reportedButtonCount: Int = rawButtonCount
        var throwOnRead: Boolean = false
        var reportedPovCount: Int = if (povValue == -1) 0 else 1

        override fun isConnected(): Boolean = connected
        override fun axisCount(): Int = axisValues.size
        override fun buttonCount(): Int = reportedButtonCount
        override fun povCount(): Int = reportedPovCount

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
