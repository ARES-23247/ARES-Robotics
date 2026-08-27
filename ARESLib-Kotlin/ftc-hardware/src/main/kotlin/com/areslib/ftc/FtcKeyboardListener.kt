package com.areslib.ftc

import com.areslib.telemetry.GamepadState

/**
 * Android Human Interface Device (HID) Keyboard Event Listener for FTC Driver Station & Control Hub.
 *
 * Captures external function key events ($F1$ through $F12$) emitted by custom controllers
 * (e.g., Flydigi Vader 4/5 Pro in keyboard macro mapping mode) and propagates key states
 * into a platform-agnostic [GamepadState] for telemetry logging and robot control actions.
 *
 * ### Android HID Key Mapping Table:
 * | Function Key | Android KeyCode | KeyCode Constant |
 * | :--- | :--- | :--- |
 * | $F1 \dots F12$ | $131 \dots 142$ | `KEYCODE_F1` $\dots$ `KEYCODE_F12` |
 *
 * ### Zero-GC Guarantee:
 * Mutates primitive boolean fields directly inside the passed [GamepadState] reference without allocating
 * heap memory or creating temporary event wrappers during high-frequency gamepad sampling.
 *
 * @see com.areslib.telemetry.GamepadState
 */
class FtcKeyboardListener {

    /**
     * Processes an incoming Android keydown event for function keys ($F1 \dots F12$).
     *
     * Sets the corresponding function key boolean field in [state] to `true`.
     *
     * @param keyCode Android keycode integer (e.g., 131 for `KEYCODE_F1` through 142 for `KEYCODE_F12`).
     * @param state Platform-agnostic [GamepadState] instance to update in-place.
     * @return `true` if [keyCode] was a valid function key ($131 \dots 142$); `false` otherwise.
     */
    fun onKeyDown(keyCode: Int, state: GamepadState): Boolean {
        return updateKeyState(keyCode, true, state)
    }

    /**
     * Processes an incoming Android keyup event for function keys ($F1 \dots F12$).
     *
     * Sets the corresponding function key boolean field in [state] to `false`.
     *
     * @param keyCode Android keycode integer (e.g., 131 for `KEYCODE_F1` through 142 for `KEYCODE_F12`).
     * @param state Platform-agnostic [GamepadState] instance to update in-place.
     * @return `true` if [keyCode] was a valid function key ($131 \dots 142$); `false` otherwise.
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

