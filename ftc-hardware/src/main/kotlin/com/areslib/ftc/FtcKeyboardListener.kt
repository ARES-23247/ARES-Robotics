package com.areslib.ftc

import com.areslib.telemetry.GamepadState

/**
 * Android HID Keyboard Event Listener for FTC Driver Station / Control Hub.
 * Captures function key presses (F1 through F12) emitted by custom gamepads
 * (such as Flydigi Vader 4/5 Pro) in keyboard macro mode and updates a [GamepadState].
 */
class FtcKeyboardListener {

    /**
     * Handles incoming Android [android.view.KeyEvent] keydown actions.
     * 
     * @param keyCode The Android keycode integer (e.g. 131 for KEYCODE_F1).
     * @param state The target [GamepadState] to modify.
     * @return True if the keycode was recognized and handled as a function key.
     */
    fun onKeyDown(keyCode: Int, state: GamepadState): Boolean {
        return updateKeyState(keyCode, true, state)
    }

    /**
     * Handles incoming Android [android.view.KeyEvent] keyup actions.
     * 
     * @param keyCode The Android keycode integer.
     * @param state The target [GamepadState] to modify.
     * @return True if the keycode was recognized and handled as a function key.
     */
    fun onKeyUp(keyCode: Int, state: GamepadState): Boolean {
        return updateKeyState(keyCode, false, state)
    }

    private fun updateKeyState(keyCode: Int, isPressed: Boolean, state: GamepadState): Boolean {
        // Standard Android KeyCodes for F1 (131) through F12 (142)
        return when (keyCode) {
            131 -> { state.f1 = isPressed; true }  // KEYCODE_F1
            132 -> { state.f2 = isPressed; true }  // KEYCODE_F2
            133 -> { state.f3 = isPressed; true }  // KEYCODE_F3
            134 -> { state.f4 = isPressed; true }  // KEYCODE_F4
            135 -> { state.f5 = isPressed; true }  // KEYCODE_F5
            136 -> { state.f6 = isPressed; true }  // KEYCODE_F6
            137 -> { state.f7 = isPressed; true }  // KEYCODE_F7
            138 -> { state.f8 = isPressed; true }  // KEYCODE_F8
            139 -> { state.f9 = isPressed; true }  // KEYCODE_F9
            140 -> { state.f10 = isPressed; true } // KEYCODE_F10
            141 -> { state.f11 = isPressed; true } // KEYCODE_F11
            142 -> { state.f12 = isPressed; true } // KEYCODE_F12
            else -> false
        }
    }
}
