package com.areslib.ftc.input

import com.areslib.ftc.FtcKeyboardListener
import com.areslib.input.InputFrame
import com.areslib.telemetry.GamepadState
import com.areslib.util.RobotClock
import com.qualcomm.robotcore.hardware.Gamepad

/** Stable zero-based axis indexes used by [FtcInputFrameAdapter]. */
object FtcAxisIndex {
    const val LEFT_STICK_X: Int = 0
    const val LEFT_STICK_Y: Int = 1
    const val RIGHT_STICK_X: Int = 2
    const val RIGHT_STICK_Y: Int = 3
    const val LEFT_TRIGGER: Int = 4
    const val RIGHT_TRIGGER: Int = 5
    const val COUNT: Int = 6
}

/**
 * Stable zero-based button indexes used by [FtcInputFrameAdapter].
 *
 * The first 18 entries cover the FTC SDK's standard gamepad surface. The `c`, `z`, and `m1`–`m4`
 * entries are an optional extended overlay, and the final twelve entries are Android function-key
 * events handled by [FtcKeyboardListener]. Keeping those virtual controls at fixed indexes makes a
 * saved controls document independent of the Driver Station's HID numbering.
 */
object FtcButtonIndex {
    const val A: Int = 0
    const val B: Int = 1
    const val X: Int = 2
    const val Y: Int = 3
    const val LEFT_BUMPER: Int = 4
    const val RIGHT_BUMPER: Int = 5
    const val BACK: Int = 6
    const val START: Int = 7
    const val GUIDE: Int = 8
    const val LEFT_STICK: Int = 9
    const val RIGHT_STICK: Int = 10
    const val DPAD_UP: Int = 11
    const val DPAD_RIGHT: Int = 12
    const val DPAD_DOWN: Int = 13
    const val DPAD_LEFT: Int = 14
    const val TOUCHPAD: Int = 15
    const val SHARE: Int = 16
    const val OPTIONS: Int = 17
    const val C: Int = 18
    const val Z: Int = 19
    const val M1: Int = 20
    const val M2: Int = 21
    const val M3: Int = 22
    const val M4: Int = 23
    const val F1: Int = 24
    const val F2: Int = 25
    const val F3: Int = 26
    const val F4: Int = 27
    const val F5: Int = 28
    const val F6: Int = 29
    const val F7: Int = 30
    const val F8: Int = 31
    const val F9: Int = 32
    const val F10: Int = 33
    const val F11: Int = 34
    const val F12: Int = 35
    const val COUNT: Int = 36
}

/**
 * Copies the FTC SDK's cached [Gamepad] fields into a reusable [InputFrame].
 *
 * Stick axes retain the FTC SDK sign convention (pushing a stick forward is normally negative),
 * while triggers remain in `[0, 1]`. Values outside their documented range, NaN, and infinities
 * become zero. A gamepad whose `id` is `-1` is unassociated, so the entire frame is published as a
 * disconnected neutral sample.
 *
 * [extendedButtons] is deliberately separate from the SDK object. FTC SDK versions and controller
 * mappings do not expose arbitrary vendor buttons consistently. Existing code may update this
 * preallocated [GamepadState] with `c`, `z`, or `m1`–`m4`, and Android key events may either mutate
 * the same state through an existing [FtcKeyboardListener] or call [onKeyDown]/[onKeyUp]. No
 * reflection or allocation occurs in [sampleInto].
 *
 * @param gamepad the FTC SDK snapshot maintained by the event loop.
 * @param extendedButtons persistent optional virtual/extended button state.
 */
class FtcInputFrameAdapter(
    private val gamepad: Gamepad,
    private val extendedButtons: GamepadState = GamepadState(),
) {
    private val keyboardListener = FtcKeyboardListener()

    /** Routes an Android key-down code to the adapter's persistent F1–F12 overlay. */
    fun onKeyDown(keyCode: Int): Boolean = keyboardListener.onKeyDown(keyCode, extendedButtons)

    /** Routes an Android key-up code to the adapter's persistent F1–F12 overlay. */
    fun onKeyUp(keyCode: Int): Boolean = keyboardListener.onKeyUp(keyCode, extendedButtons)

    /**
     * Samples into [frame] without allocating. The frame must retain at least six axes and 36
     * buttons so the index contract cannot silently change between controllers.
     */
    fun sampleInto(
        frame: InputFrame,
        sampleTimeNanos: Long = RobotClock.nanoTime(),
    ) {
        require(frame.axisCapacity >= FtcAxisIndex.COUNT) {
            "FTC input frame requires at least ${FtcAxisIndex.COUNT} axes"
        }
        require(frame.buttonCapacity >= FtcButtonIndex.COUNT) {
            "FTC input frame requires at least ${FtcButtonIndex.COUNT} buttons"
        }

        if (gamepad.id == UNASSOCIATED_GAMEPAD_ID) {
            frame.beginSample(connected = false, sampleTimeNanos = sampleTimeNanos)
            return
        }

        frame.beginSample(
            connected = true,
            reportedAxisCount = FtcAxisIndex.COUNT,
            reportedButtonCount = FtcButtonIndex.COUNT,
            sampleTimeNanos = sampleTimeNanos,
        )

        frame.setAxis(FtcAxisIndex.LEFT_STICK_X, validStick(gamepad.left_stick_x))
        frame.setAxis(FtcAxisIndex.LEFT_STICK_Y, validStick(gamepad.left_stick_y))
        frame.setAxis(FtcAxisIndex.RIGHT_STICK_X, validStick(gamepad.right_stick_x))
        frame.setAxis(FtcAxisIndex.RIGHT_STICK_Y, validStick(gamepad.right_stick_y))
        frame.setAxis(FtcAxisIndex.LEFT_TRIGGER, validTrigger(gamepad.left_trigger))
        frame.setAxis(FtcAxisIndex.RIGHT_TRIGGER, validTrigger(gamepad.right_trigger))

        frame.setButton(FtcButtonIndex.A, gamepad.a)
        frame.setButton(FtcButtonIndex.B, gamepad.b)
        frame.setButton(FtcButtonIndex.X, gamepad.x)
        frame.setButton(FtcButtonIndex.Y, gamepad.y)
        frame.setButton(FtcButtonIndex.LEFT_BUMPER, gamepad.left_bumper)
        frame.setButton(FtcButtonIndex.RIGHT_BUMPER, gamepad.right_bumper)
        frame.setButton(FtcButtonIndex.BACK, gamepad.back)
        frame.setButton(FtcButtonIndex.START, gamepad.start)
        frame.setButton(FtcButtonIndex.GUIDE, gamepad.guide)
        frame.setButton(FtcButtonIndex.LEFT_STICK, gamepad.left_stick_button)
        frame.setButton(FtcButtonIndex.RIGHT_STICK, gamepad.right_stick_button)
        frame.setButton(FtcButtonIndex.DPAD_UP, gamepad.dpad_up)
        frame.setButton(FtcButtonIndex.DPAD_RIGHT, gamepad.dpad_right)
        frame.setButton(FtcButtonIndex.DPAD_DOWN, gamepad.dpad_down)
        frame.setButton(FtcButtonIndex.DPAD_LEFT, gamepad.dpad_left)
        frame.setButton(FtcButtonIndex.TOUCHPAD, gamepad.touchpad)
        frame.setButton(FtcButtonIndex.SHARE, gamepad.share)
        frame.setButton(FtcButtonIndex.OPTIONS, gamepad.options)
        frame.setButton(FtcButtonIndex.C, extendedButtons.c)
        frame.setButton(FtcButtonIndex.Z, extendedButtons.z)
        frame.setButton(FtcButtonIndex.M1, extendedButtons.m1)
        frame.setButton(FtcButtonIndex.M2, extendedButtons.m2)
        frame.setButton(FtcButtonIndex.M3, extendedButtons.m3)
        frame.setButton(FtcButtonIndex.M4, extendedButtons.m4)
        frame.setButton(FtcButtonIndex.F1, extendedButtons.f1)
        frame.setButton(FtcButtonIndex.F2, extendedButtons.f2)
        frame.setButton(FtcButtonIndex.F3, extendedButtons.f3)
        frame.setButton(FtcButtonIndex.F4, extendedButtons.f4)
        frame.setButton(FtcButtonIndex.F5, extendedButtons.f5)
        frame.setButton(FtcButtonIndex.F6, extendedButtons.f6)
        frame.setButton(FtcButtonIndex.F7, extendedButtons.f7)
        frame.setButton(FtcButtonIndex.F8, extendedButtons.f8)
        frame.setButton(FtcButtonIndex.F9, extendedButtons.f9)
        frame.setButton(FtcButtonIndex.F10, extendedButtons.f10)
        frame.setButton(FtcButtonIndex.F11, extendedButtons.f11)
        frame.setButton(FtcButtonIndex.F12, extendedButtons.f12)
    }

    private fun validStick(value: Float): Double =
        if (value.isFinite() && value >= -1.0f && value <= 1.0f) value.toDouble() else 0.0

    private fun validTrigger(value: Float): Double =
        if (value.isFinite() && value >= 0.0f && value <= 1.0f) value.toDouble() else 0.0

    private companion object {
        const val UNASSOCIATED_GAMEPAD_ID: Int = -1
    }
}
