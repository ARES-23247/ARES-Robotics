package com.ares.analytics.ui.input

import com.ares.analytics.di.KeyboardDriveState
import java.awt.event.KeyEvent
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopDriveKeyDispatcherTest {
    @Test
    fun `drive keys are ignored until loopback simulator control is armed`() {
        val state = KeyboardDriveState()

        assertFalse(applyDesktopDriveKey(state, true, KeyEvent.VK_SPACE, true))
        state.enabled = true
        assertTrue(applyDesktopDriveKey(state, true, KeyEvent.VK_W, true))
        assertTrue(state.isWPressed)
    }

    @Test
    fun `space is not consumed or used as a drive authorization switch`() {
        val state = KeyboardDriveState().apply { enabled = true }
        applyDesktopDriveKey(state, true, KeyEvent.VK_W, true)
        applyDesktopDriveKey(state, true, KeyEvent.VK_LEFT, true)

        assertFalse(applyDesktopDriveKey(state, true, KeyEvent.VK_SPACE, true))
        assertFalse(applyDesktopDriveKey(state, true, KeyEvent.VK_SPACE, false))
        assertTrue(state.enabled)
        assertTrue(state.isWPressed)
        assertTrue(state.isLeftPressed)
    }

    @Test
    fun `dispatcher does not steal shortcuts or gamepad mode`() {
        val state = KeyboardDriveState().apply { enabled = true }

        assertFalse(applyDesktopDriveKey(state, true, KeyEvent.VK_D, true, controlDown = true))
        state.useGamepad = true
        assertFalse(applyDesktopDriveKey(state, true, KeyEvent.VK_SPACE, true))
    }

    @Test
    fun `control modified release still clears a previously held drive key`() {
        val state = KeyboardDriveState().apply { enabled = true }

        assertTrue(applyDesktopDriveKey(state, true, KeyEvent.VK_W, true))
        assertTrue(state.isWPressed)
        assertTrue(applyDesktopDriveKey(state, true, KeyEvent.VK_W, false, controlDown = true))
        assertFalse(state.isWPressed)
    }
}
