package com.ares.analytics.service

import org.lwjgl.glfw.GLFW.GLFW_GAMEPAD_AXIS_LAST
import org.lwjgl.glfw.GLFW.GLFW_GAMEPAD_BUTTON_A
import org.lwjgl.glfw.GLFW.GLFW_GAMEPAD_BUTTON_LAST
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GamepadServiceTest {
    @Test
    fun `standard mapping retains vendor raw inputs`() {
        val rawButtons = List(24) { index -> index == 20 }
        val raw = RawJoystickSnapshot(
            joystickId = 2,
            name = "Flydigi Vader 5 Pro",
            guid = "03000000-test",
            axes = listOf(.1f, -.2f, .3f, -.4f, .8f, -.7f, .42f),
            buttons = rawButtons,
            hats = listOf(1)
        )
        val standard = StandardGamepadSnapshot(
            axes = List(GLFW_GAMEPAD_AXIS_LAST + 1) { index -> if (index == 0) .5f else -1f },
            buttons = List(GLFW_GAMEPAD_BUTTON_LAST + 1) { index -> index == GLFW_GAMEPAD_BUTTON_A }
        )

        val state = mapGamepadState(raw, standard)

        assertTrue(state.usesStandardMapping)
        assertEquals(raw.axes, state.rawAxes)
        assertEquals(rawButtons, state.rawButtons)
        assertEquals(24, state.buttonCount)
        assertTrue(state.rawButtons[20], "Extra raw button must not be hidden by the standard mapping")
        assertTrue(state.a)
        assertEquals(.5f, state.leftStickX)
        assertEquals("03000000-test", state.guid)
    }

    @Test
    fun `raw joystick fallback remains usable`() {
        val raw = RawJoystickSnapshot(
            joystickId = 0,
            name = "DirectInput pad",
            guid = "direct",
            axes = listOf(.25f, -.5f, 0f, 0f, -1f, 1f),
            buttons = listOf(false, true),
            hats = emptyList()
        )

        val state = mapGamepadState(raw, null)

        assertFalse(state.usesStandardMapping)
        assertTrue(state.b)
        assertEquals(.25f, state.leftStickX)
        assertEquals(.5f, state.leftStickY)
        assertEquals(0f, state.leftTrigger)
        assertEquals(1f, state.rightTrigger)
    }
}
