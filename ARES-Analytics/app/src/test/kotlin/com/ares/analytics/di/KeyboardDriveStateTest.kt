package com.ares.analytics.di

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class KeyboardDriveStateTest {
    @Test
    fun `local robot control starts disarmed`() {
        val state = KeyboardDriveState()
        assertFalse(state.enabled)
        assertFalse(state.useGamepad)
    }

    @Test
    fun `release and disarm neutralize every latched input`() {
        val state = KeyboardDriveState().apply {
            enabled = true
            useGamepad = true
            isWPressed = true
            isQPressed = true
            isJPressed = true
            isShiftPressed = true
        }
        state.releaseAll()
        assertTrue(state.enabled)
        assertFalse(state.isWPressed)
        assertFalse(state.isQPressed)
        assertFalse(state.isJPressed)
        assertFalse(state.isShiftPressed)

        state.disarm()
        assertFalse(state.enabled)
        assertFalse(state.useGamepad)
    }
}
