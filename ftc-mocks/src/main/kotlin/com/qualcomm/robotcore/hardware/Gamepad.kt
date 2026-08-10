package com.qualcomm.robotcore.hardware

/**
 * Mock representation of an FTC [Gamepad].
 */
open class Gamepad {
    @JvmField var left_stick_x: Float = 0f
    @JvmField var left_stick_y: Float = 0f
    @JvmField var right_stick_x: Float = 0f
    @JvmField var right_stick_y: Float = 0f

    @JvmField var left_trigger: Float = 0f
    @JvmField var right_trigger: Float = 0f

    @JvmField var a: Boolean = false
    @JvmField var b: Boolean = false
    @JvmField var x: Boolean = false
    @JvmField var y: Boolean = false

    @JvmField var dpad_up: Boolean = false
    @JvmField var dpad_down: Boolean = false
    @JvmField var dpad_left: Boolean = false
    @JvmField var dpad_right: Boolean = false

    @JvmField var left_bumper: Boolean = false
    @JvmField var right_bumper: Boolean = false

    @JvmField var start: Boolean = false
    @JvmField var back: Boolean = false
    @JvmField var guide: Boolean = false

    @JvmField var left_stick_button: Boolean = false
    @JvmField var right_stick_button: Boolean = false

    @JvmField var touchpad: Boolean = false
    @JvmField var share: Boolean = false
    @JvmField var options: Boolean = false
    @JvmField var c: Boolean = false
    @JvmField var z: Boolean = false
    @JvmField var m1: Boolean = false
    @JvmField var m2: Boolean = false
    @JvmField var m3: Boolean = false
    @JvmField var m4: Boolean = false

    fun copy(other: Gamepad) {
        this.left_stick_x = other.left_stick_x
        this.left_stick_y = other.left_stick_y
        this.right_stick_x = other.right_stick_x
        this.right_stick_y = other.right_stick_y
        this.left_trigger = other.left_trigger
        this.right_trigger = other.right_trigger
        this.a = other.a
        this.b = other.b
        this.x = other.x
        this.y = other.y
        this.dpad_up = other.dpad_up
        this.dpad_down = other.dpad_down
        this.dpad_left = other.dpad_left
        this.dpad_right = other.dpad_right
        this.left_bumper = other.left_bumper
        this.right_bumper = other.right_bumper
        this.start = other.start
        this.back = other.back
        this.guide = other.guide
        this.left_stick_button = other.left_stick_button
        this.right_stick_button = other.right_stick_button
        this.touchpad = other.touchpad
        this.share = other.share
        this.options = other.options
        this.c = other.c
        this.z = other.z
        this.m1 = other.m1
        this.m2 = other.m2
        this.m3 = other.m3
        this.m4 = other.m4
    }

    open fun rumble(durationMs: Int) {}
    open fun rumble(leftPower: Double, rightPower: Double, durationMs: Int) {}
    open fun rumbleBlips(count: Int) {}
    open fun stopRumble() {}
}
