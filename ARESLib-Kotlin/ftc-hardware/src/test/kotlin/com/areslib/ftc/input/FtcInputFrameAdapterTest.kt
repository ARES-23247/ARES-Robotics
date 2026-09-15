package com.areslib.ftc.input

import com.areslib.input.*
import kotlin.test.assertFailsWith
import com.areslib.telemetry.GamepadState
import com.qualcomm.robotcore.hardware.Gamepad
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FtcInputFrameAdapterTest {
    @Test
    fun `samples standard and virtual controls at stable indexes`() {
        val gamepad = Gamepad().apply {
            left_stick_x = 0.25f
            right_trigger = 0.75f
            a = true
            dpad_left = true
            touchpad = true
        }
        val extended = GamepadState(m3 = true)
        val adapter = FtcInputFrameAdapter(gamepad, extended)
        val frame = InputFrame()

        assertTrue(adapter.onKeyDown(131))
        adapter.sampleInto(frame, sampleTimeNanos = 42L)

        assertTrue(frame.isConnected)
        assertEquals(42L, frame.timestampNanos)
        assertEquals(0.25, frame.axis(FtcAxisIndex.LEFT_STICK_X), 1e-9)
        assertEquals(0.75, frame.axis(FtcAxisIndex.RIGHT_TRIGGER), 1e-9)
        assertTrue(frame.button(FtcButtonIndex.A))
        assertTrue(frame.button(FtcButtonIndex.DPAD_LEFT))
        assertTrue(frame.button(FtcButtonIndex.TOUCHPAD))
        assertTrue(frame.button(FtcButtonIndex.M3))
        assertTrue(frame.button(FtcButtonIndex.F1))
        assertFalse(frame.button(FtcButtonIndex.B))

        assertTrue(adapter.onKeyUp(131))
        adapter.sampleInto(frame, sampleTimeNanos = 43L)
        assertFalse(frame.button(FtcButtonIndex.F1))
    }

    @Test
    fun `invalid axes become neutral and disconnect clears stale input`() {
        val gamepad = Gamepad().apply {
            left_stick_x = Float.NaN
            left_stick_y = 1.25f
            left_trigger = Float.POSITIVE_INFINITY
            a = true
        }
        val adapter = FtcInputFrameAdapter(gamepad)
        val frame = InputFrame()

        adapter.sampleInto(frame, sampleTimeNanos = 10L)

        assertEquals(0.0, frame.axis(FtcAxisIndex.LEFT_STICK_X))
        assertEquals(0.0, frame.axis(FtcAxisIndex.LEFT_STICK_Y))
        assertEquals(0.0, frame.axis(FtcAxisIndex.LEFT_TRIGGER))
        assertFalse(frame.isAxisAvailable(FtcAxisIndex.LEFT_STICK_X))
        assertFalse(frame.isAxisAvailable(FtcAxisIndex.LEFT_STICK_Y))
        assertFalse(frame.isAxisAvailable(FtcAxisIndex.LEFT_TRIGGER))
        assertTrue(frame.isAxisAvailable(FtcAxisIndex.RIGHT_TRIGGER))
        assertTrue(frame.button(FtcButtonIndex.A))

        gamepad.id = Gamepad.ID_UNASSOCIATED
        adapter.sampleInto(frame, sampleTimeNanos = 11L)

        assertFalse(frame.isConnected)
        assertEquals(0, frame.axisCount)
        assertEquals(0, frame.buttonCount)
        assertEquals(0.0, frame.axis(FtcAxisIndex.LEFT_STICK_X))
        assertFalse(frame.button(FtcButtonIndex.A))
    }

    @Test
    fun `invalid calibrated feedback cannot command a deflected axis`() {
        val gamepad = Gamepad().apply { left_stick_x = 1.25f }
        val frame = InputFrame()
        val adapter = FtcInputFrameAdapter(gamepad)
        val binding = AnalogBinding(FtcAxisIndex.LEFT_STICK_X, AxisTransform(0.0, 0.5, 1.0), object : AnalogBindingListener {})
        adapter.sampleInto(frame, 0L); binding.update(frame, 0L)
        assertEquals(0.0, binding.value)
        gamepad.left_stick_x = 1.0f
        adapter.sampleInto(frame, 1L); binding.update(frame, 1L)
        assertEquals(0.0, binding.value)
        gamepad.left_stick_x = 0.5f
        adapter.sampleInto(frame, 2L); binding.update(frame, 2L)
        gamepad.left_stick_x = 1.0f
        adapter.sampleInto(frame, 3L); binding.update(frame, 3L)
        assertEquals(1.0, binding.value)
    }

    @Test
    fun `undersized adapter storage invalidates old feedback`() {
        val frame = InputFrame(1, 1).apply { beginSample(true, 1, 1, 0L); setAxis(0, 1.0); setButton(0, true) }
        assertFailsWith<IllegalArgumentException> { FtcInputFrameAdapter(Gamepad()).sampleInto(frame, 1L) }
        assertFalse(frame.isConnected); assertFalse(frame.button(0))
    }
}
