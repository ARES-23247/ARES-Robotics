package com.areslib.ftc

import com.areslib.telemetry.GamepadState
import com.qualcomm.robotcore.hardware.Gamepad

/**
 * Converts an FTC SDK Gamepad into a platform-agnostic GamepadState
 * for the ARESLib logging pipeline.
 */
fun Gamepad.toState() = GamepadState().apply { update(this@toState) }

/**
 * Updates a platform-agnostic GamepadState in-place from an FTC SDK Gamepad.
 * This should be used on the hot path to avoid garbage collection allocations.
 */
fun GamepadState.update(gamepad: Gamepad) {
    leftStickX = gamepad.left_stick_x
    leftStickY = gamepad.left_stick_y
    rightStickX = gamepad.right_stick_x
    rightStickY = gamepad.right_stick_y
    leftTrigger = gamepad.left_trigger
    rightTrigger = gamepad.right_trigger
    a = gamepad.a
    b = gamepad.b
    x = gamepad.x
    y = gamepad.y
    dpadUp = gamepad.dpad_up
    dpadDown = gamepad.dpad_down
    dpadLeft = gamepad.dpad_left
    dpadRight = gamepad.dpad_right
    leftBumper = gamepad.left_bumper
    rightBumper = gamepad.right_bumper
    leftStickButton = gamepad.left_stick_button
    rightStickButton = gamepad.right_stick_button
    start = gamepad.start
    back = gamepad.back
    touchpad = gamepad.touchpad
    share = gamepad.share
    options = gamepad.options
    c = gamepad.c || gamepad.options
    z = gamepad.z || gamepad.guide
    m1 = gamepad.m1 || gamepad.touchpad
    m2 = gamepad.m2 || gamepad.share
    m3 = gamepad.m3
    m4 = gamepad.m4
}
