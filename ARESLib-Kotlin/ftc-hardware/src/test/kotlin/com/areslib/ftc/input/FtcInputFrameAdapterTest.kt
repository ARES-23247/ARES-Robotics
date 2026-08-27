package com.areslib.ftc.input

import com.areslib.input.InputFrame
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
        assertTrue(frame.button(FtcButtonIndex.A))

        gamepad.id = Gamepad.ID_UNASSOCIATED
        adapter.sampleInto(frame, sampleTimeNanos = 11L)

        assertFalse(frame.isConnected)
        assertEquals(0, frame.axisCount)
        assertEquals(0, frame.buttonCount)
        assertEquals(0.0, frame.axis(FtcAxisIndex.LEFT_STICK_X))
        assertFalse(frame.button(FtcButtonIndex.A))
    }
}
