package com.areslib.ftc

import com.areslib.telemetry.GamepadState
import com.qualcomm.robotcore.hardware.Gamepad

/**
 * Converts a Qualcomm FTC SDK [Gamepad] instance into a platform-agnostic [GamepadState] data snapshot.
 *
 * Copies joysticks $[-1.0, 1.0]$, triggers $[0.0, 1.0]$, standard buttons $(A, B, X, Y)$, D-pad inputs,
 * and extended macro buttons ($C, Z, M1 \dots M4$) into a newly allocated [GamepadState].
 *
 * @receiver Qualcomm FTC SDK [Gamepad] instance.
 * @return Newly allocated platform-agnostic [GamepadState] populated with current controller values.
 *
 * @see GamepadState.update
 */
fun Gamepad.toState() = GamepadState().apply { update(this@toState) }

/**
 * Updates a platform-agnostic [GamepadState] instance in-place from a Qualcomm FTC SDK [Gamepad].
 *
 * ### Zero-GC Compliance Guarantee:
 * Mutates primitive fields in-place on the existing [GamepadState] reference. This function MUST be used on the
 * 50Hz–100Hz hot execution path instead of [Gamepad.toState] to enforce a zero-heap allocation footprint.
 *
 * @receiver Target [GamepadState] snapshot object to update in-place.
 * @param gamepad Source FTC SDK [Gamepad] instance read from Driver Station hardware.
 */
private val cField = runCatching { Gamepad::class.java.getField("c") }.getOrNull()
private val zField = runCatching { Gamepad::class.java.getField("z") }.getOrNull()
private val m1Field = runCatching { Gamepad::class.java.getField("m1") }.getOrNull()
private val m2Field = runCatching { Gamepad::class.java.getField("m2") }.getOrNull()
private val m3Field = runCatching { Gamepad::class.java.getField("m3") }.getOrNull()
private val m4Field = runCatching { Gamepad::class.java.getField("m4") }.getOrNull()

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
    val valC = cField?.getBoolean(gamepad) ?: false
    val valZ = zField?.getBoolean(gamepad) ?: false
    val valM1 = m1Field?.getBoolean(gamepad) ?: false
    val valM2 = m2Field?.getBoolean(gamepad) ?: false
    val valM3 = m3Field?.getBoolean(gamepad) ?: false
    val valM4 = m4Field?.getBoolean(gamepad) ?: false
    c = valC || gamepad.options
    z = valZ || gamepad.guide
    m1 = valM1 || gamepad.touchpad
    m2 = valM2 || gamepad.share
    m3 = valM3
    m4 = valM4
}

