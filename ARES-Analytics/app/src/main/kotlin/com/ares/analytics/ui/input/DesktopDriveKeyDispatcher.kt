package com.ares.analytics.ui.input

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState
import com.ares.analytics.di.KeyboardDriveState
import java.awt.AWTEvent
import java.awt.KeyEventDispatcher
import java.awt.KeyboardFocusManager
import java.awt.Toolkit
import java.awt.event.AWTEventListener
import java.awt.event.KeyEvent

/**
 * Applies one native desktop key transition to the armed drive-control state.
 *
 * Compose controls such as buttons, menus, and text fields can become the focused AWT child and
 * consume a key before a root `Modifier.onPreviewKeyEvent` observes it. The desktop dispatcher is
 * intentionally installed at the window event-queue boundary; it remains inert until the Dashboard
 * drive surface is active (loopback simulator for FTC/FRC/XRP, or an explicitly operator-authorized
 * XRP Driver Station) and drive control has been explicitly armed.
 */
internal fun applyDesktopDriveKey(
    state: KeyboardDriveState,
    controlSurfaceActive: Boolean,
    keyCode: Int,
    isPressed: Boolean,
    controlDown: Boolean = false,
    textInputFocused: Boolean = false,
): Boolean {
    if (!controlSurfaceActive || !state.enabled || state.useGamepad) return false
    if (textInputFocused) {
        if (isPressed) state.releaseAll()
        return false
    }

    // Track physical key state only while an authorized control surface is armed. Publishers
    // independently enforce loopback/peer-authorization boundaries, and a Ctrl-modified key
    // release still clears a movement key instead of leaving it latched.
    return when (keyCode) {
        KeyEvent.VK_W -> updateDriveKey(isPressed, controlDown) { state.isWPressed = it }
        KeyEvent.VK_S -> updateDriveKey(isPressed, controlDown) { state.isSPressed = it }
        KeyEvent.VK_A -> updateDriveKey(isPressed, controlDown) { state.isAPressed = it }
        KeyEvent.VK_D -> updateDriveKey(isPressed, controlDown) { state.isDPressed = it }
        KeyEvent.VK_UP -> updateDriveKey(isPressed, controlDown) { state.isUpPressed = it }
        KeyEvent.VK_DOWN -> updateDriveKey(isPressed, controlDown) { state.isDownPressed = it }
        KeyEvent.VK_LEFT -> updateDriveKey(isPressed, controlDown) { state.isLeftPressed = it }
        KeyEvent.VK_RIGHT -> updateDriveKey(isPressed, controlDown) { state.isRightPressed = it }
        KeyEvent.VK_Q -> updateDriveKey(isPressed, controlDown) { state.isQPressed = it }
        KeyEvent.VK_E -> updateDriveKey(isPressed, controlDown) { state.isEPressed = it }
        KeyEvent.VK_J -> updateDriveKey(isPressed, controlDown) { state.isJPressed = it }
        KeyEvent.VK_L -> updateDriveKey(isPressed, controlDown) { state.isLPressed = it }
        KeyEvent.VK_U -> updateDriveKey(isPressed, controlDown) { state.isUPressed = it }
        KeyEvent.VK_SPACE -> updateDriveKey(isPressed, controlDown) { state.isSpacePressed = it }
        KeyEvent.VK_SHIFT -> updateDriveKey(isPressed, controlDown) { state.isShiftPressed = it }
        else -> false
    }
}

private inline fun updateDriveKey(
    isPressed: Boolean,
    modifierDown: Boolean,
    update: (Boolean) -> Unit,
): Boolean {
    if (isPressed && modifierDown) return false
    update(isPressed)
    return true
}

@Composable
fun DesktopDriveKeyDispatcher(
    state: KeyboardDriveState,
    controlSurfaceActive: Boolean,
) {
    val activeNow = rememberUpdatedState(controlSurfaceActive)

    DisposableEffect(state) {
        val focusManager = KeyboardFocusManager.getCurrentKeyboardFocusManager()
        val applyEvent: (KeyEvent) -> Boolean = { event ->
            if (event.id != KeyEvent.KEY_PRESSED && event.id != KeyEvent.KEY_RELEASED) {
                false
            } else {
                val focusOwner = focusManager.focusOwner
                val textFocused = focusOwner is javax.swing.text.JTextComponent ||
                    focusOwner?.inputContext?.let {
                        focusOwner.inputMethodRequests != null
                    } == true
                applyDesktopDriveKey(
                    state = state,
                    controlSurfaceActive = activeNow.value,
                    keyCode = event.keyCode,
                    isPressed = event.id == KeyEvent.KEY_PRESSED,
                    controlDown = event.isControlDown || event.isAltDown || event.isMetaDown,
                    textInputFocused = textFocused,
                )
            }
        }
        val dispatcher = KeyEventDispatcher(applyEvent)
        // Compose popups and focus-owner transitions can bypass or replace the initially observed
        // KeyboardFocusManager dispatch route. The toolkit listener is an independent, JVM-local
        // fallback; duplicate press/release assignments are idempotent.
        val toolkitListener = AWTEventListener { event ->
            if (event is KeyEvent) applyEvent(event)
        }
        focusManager.addKeyEventDispatcher(dispatcher)
        Toolkit.getDefaultToolkit().addAWTEventListener(toolkitListener, AWTEvent.KEY_EVENT_MASK)
        onDispose {
            Toolkit.getDefaultToolkit().removeAWTEventListener(toolkitListener)
            focusManager.removeKeyEventDispatcher(dispatcher)
            state.releaseAll()
        }
    }
}
