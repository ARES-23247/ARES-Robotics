package com.areslib.ftc.hardware

import com.qualcomm.robotcore.hardware.Gamepad
import org.junit.jupiter.api.Test
import kotlin.test.*

class FtcGamepadAdapterAuditTest {
    @Test fun `all buttons and axes map to an independently owned snapshot`() {
        val gamepad = Gamepad(); val adapter = FtcGamepadAdapter(gamepad, 0.0, 1.0)
        gamepad.a = true; gamepad.b = true; gamepad.x = true; gamepad.y = true
        gamepad.dpad_up = true; gamepad.dpad_down = true; gamepad.dpad_left = true; gamepad.dpad_right = true
        gamepad.left_bumper = true; gamepad.right_bumper = true
        gamepad.start = true; gamepad.back = true; gamepad.guide = true
        gamepad.left_stick_button = true; gamepad.right_stick_button = true
        gamepad.left_stick_x = 0.25f; gamepad.left_stick_y = -0.5f
        gamepad.right_stick_x = -0.5f; gamepad.right_stick_y = 0.25f
        gamepad.left_trigger = 0.25f; gamepad.right_trigger = 0.75f
        val first = adapter.getControllerState()
        assertTrue(first.a && first.b && first.x && first.y)
        assertTrue(first.dpadUp && first.dpadDown && first.dpadLeft && first.dpadRight)
        assertTrue(first.leftBumper && first.rightBumper && first.start && first.back && first.guide)
        assertTrue(first.leftStickButton && first.rightStickButton)
        assertEquals(0.25, first.leftStickX, 1e-15); assertEquals(0.5, first.leftStickY, 1e-15)
        assertEquals(-0.5, first.rightStickX, 1e-15); assertEquals(-0.25, first.rightStickY, 1e-15)
        assertEquals(0.25, first.leftTrigger); assertEquals(0.75, first.rightTrigger)
        gamepad.copy(Gamepad())
        val second = adapter.getControllerState()
        assertEquals(com.areslib.input.ControllerState(), second)
        assertNotSame(first, second); assertTrue(first.a); assertEquals(0.5, first.leftStickY, 1e-15)
    }

    @Test fun `default adapter applies radial deadband and quadratic curve once`() {
        val gamepad = Gamepad(); val adapter = FtcGamepadAdapter(gamepad)
        gamepad.left_stick_x = 0.6f; gamepad.left_stick_y = -0.8f
        val state = adapter.getControllerState()
        assertEquals(0.6, state.leftStickX, 1e-7); assertEquals(0.8, state.leftStickY, 1e-7)
        gamepad.left_stick_x = 0.5f; gamepad.left_stick_y = 0f
        assertEquals(Math.pow((0.5 - 0.05) / 0.95, 2.0), adapter.getControllerState().leftStickX, 1e-15)
    }

    @Test fun `invalid shaping configuration keeps sticks neutral while buttons and triggers remain readable`() {
        val gamepad = Gamepad(); gamepad.left_stick_x = 1f; gamepad.right_stick_y = 1f
        gamepad.a = true; gamepad.left_trigger = 0.5f
        for (adapter in listOf(FtcGamepadAdapter(gamepad, Double.NaN), FtcGamepadAdapter(gamepad, curveExponent = 0.0))) {
            val state = adapter.getControllerState()
            assertEquals(0.0, state.leftStickX); assertEquals(0.0, state.rightStickY)
            assertTrue(state.a); assertEquals(0.5, state.leftTrigger)
        }
    }

    @Test fun `invalid triggers are neutral and do not affect valid peer inputs`() {
        val gamepad = Gamepad(); val adapter = FtcGamepadAdapter(gamepad)
        for (invalid in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, -0.1f, 1.1f)) {
            gamepad.left_trigger = invalid; gamepad.right_trigger = 0.5f; gamepad.a = true
            val state = adapter.getControllerState()
            assertEquals(0.0, state.leftTrigger); assertEquals(0.5, state.rightTrigger); assertTrue(state.a)
            gamepad.left_trigger = 1f; gamepad.right_trigger = invalid
            assertEquals(1.0, adapter.getControllerState().leftTrigger)
            assertEquals(0.0, adapter.getControllerState().rightTrigger)
        }
    }
    @Test fun `invalid stick components neutralize their vector without contaminating the other stick`() {
        val gamepad = Gamepad(); val adapter = FtcGamepadAdapter(gamepad, 0.0, 1.0)
        gamepad.left_stick_x = 2f; gamepad.left_stick_y = -0.5f
        gamepad.right_stick_x = 0.25f; gamepad.right_stick_y = -0.5f
        val state = adapter.getControllerState()
        assertEquals(0.0, state.leftStickX); assertEquals(0.0, state.leftStickY)
        assertEquals(0.25, state.rightStickX, 1e-15); assertEquals(0.5, state.rightStickY, 1e-15)
    }
}
