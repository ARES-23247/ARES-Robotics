package com.areslib.telemetry

/**
 * Platform-agnostic snapshot of a single gamepad's state.
 * Lives in :core so the logging/replay pipeline can serialize it
 * without depending on any FTC or FRC SDK types.
 */
class GamepadState(
    var leftStickX: Float = 0f,
    var leftStickY: Float = 0f,
    var rightStickX: Float = 0f,
    var rightStickY: Float = 0f,
    var leftTrigger: Float = 0f,
    var rightTrigger: Float = 0f,
    var a: Boolean = false,
    var b: Boolean = false,
    var x: Boolean = false,
    var y: Boolean = false,
    var dpadUp: Boolean = false,
    var dpadDown: Boolean = false,
    var dpadLeft: Boolean = false,
    var dpadRight: Boolean = false,
    var leftBumper: Boolean = false,
    var rightBumper: Boolean = false,
    var leftStickButton: Boolean = false,
    var rightStickButton: Boolean = false,
    var start: Boolean = false,
    var back: Boolean = false,
    var touchpad: Boolean = false,
    var share: Boolean = false,
    var options: Boolean = false,
    var c: Boolean = false,
    var z: Boolean = false,
    var m1: Boolean = false,
    var m2: Boolean = false,
    var m3: Boolean = false,
    var m4: Boolean = false
) {
    /**
     * copyFrom declaration.
     *
     * @param args Standard arguments (if applicable).
     * @return Corresponding output value or Unit.
     */
    fun copyFrom(other: GamepadState) {
        this.leftStickX = other.leftStickX
        this.leftStickY = other.leftStickY
        this.rightStickX = other.rightStickX
        this.rightStickY = other.rightStickY
        this.leftTrigger = other.leftTrigger
        this.rightTrigger = other.rightTrigger
        this.a = other.a
        this.b = other.b
        this.x = other.x
        this.y = other.y
        this.dpadUp = other.dpadUp
        this.dpadDown = other.dpadDown
        this.dpadLeft = other.dpadLeft
        this.dpadRight = other.dpadRight
        this.leftBumper = other.leftBumper
        this.rightBumper = other.rightBumper
        this.leftStickButton = other.leftStickButton
        this.rightStickButton = other.rightStickButton
        this.start = other.start
        this.back = other.back
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

    /**
     * reset declaration.
     *
     * @param args Standard arguments (if applicable).
     * @return Corresponding output value or Unit.
     */
    fun reset() {
        this.leftStickX = 0f
        this.leftStickY = 0f
        this.rightStickX = 0f
        this.rightStickY = 0f
        this.leftTrigger = 0f
        this.rightTrigger = 0f
        this.a = false
        this.b = false
        this.x = false
        this.y = false
        this.dpadUp = false
        this.dpadDown = false
        this.dpadLeft = false
        this.dpadRight = false
        this.leftBumper = false
        this.rightBumper = false
        this.leftStickButton = false
        this.rightStickButton = false
        this.start = false
        this.back = false
        this.touchpad = false
        this.share = false
        this.options = false
        this.c = false
        this.z = false
        this.m1 = false
        this.m2 = false
        this.m3 = false
        this.m4 = false
    }
}
