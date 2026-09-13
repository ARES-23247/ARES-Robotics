package com.ares.analytics.ui.components.dashboard

import kotlin.test.Test
import kotlin.test.*

class GamepadMenuButtonsAuditTest {
    @Test fun `replay shows either physical button even when the other topic is false`() {
        for (primary in listOf(null, 0.0, 1.0)) for (alternate in listOf(null, 0.0, 1.0)) {
            assertEquals(primary == 1.0 || alternate == 1.0, gamepadMenuPressed(primary, alternate))
        }
    }
    @Test fun `live aliases retain independent states across every event order`() {
        for ((first, second) in listOf("Start" to "Options", "Options" to "Start", "Back" to "Share", "Share" to "Back")) {
            val buttons = GamepadMenuButtons("Gamepad1")
            assertFalse(buttons.accept("Gamepad2/$first", 1.0))
            assertTrue(buttons.accept("Gamepad1/$first", 1.0))
            assertTrue(buttons.accept("Gamepad1/$second", 0.0))
            val startGroup = first == "Start" || first == "Options"
            assertEquals(startGroup, buttons.startPressed)
            assertEquals(!startGroup, buttons.backPressed)
            buttons.accept("Gamepad1/$second", 1.0)
            buttons.accept("Gamepad1/$first", 0.0)
            assertEquals(startGroup, buttons.startPressed)
            assertEquals(!startGroup, buttons.backPressed)
            buttons.accept("Gamepad1/$second", 0.0)
            assertFalse(buttons.startPressed || buttons.backPressed)
        }
    }
    @Test fun `nonfinite button readings cannot masquerade as pressed`() {
        val buttons = GamepadMenuButtons("Gamepad1")
        for (value in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            assertFalse(gamepadMenuPressed(value, 0.0))
            assertTrue(gamepadMenuPressed(value, 1.0))
            buttons.accept("Gamepad1/Start", value)
            assertFalse(buttons.startPressed)
        }
    }
}
