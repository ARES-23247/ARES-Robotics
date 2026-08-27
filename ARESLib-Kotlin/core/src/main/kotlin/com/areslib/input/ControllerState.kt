package com.areslib.input

/**
 * A pure, platform-agnostic data representation of a standard game controller's state.
 * This can be constructed by reading an FTC Gamepad or an FRC GenericHID/XboxController.
 */
data class ControllerState(
    // Face buttons
    val a: Boolean = false,
    val b: Boolean = false,
    val x: Boolean = false,
    val y: Boolean = false,
    
    // D-Pad
    val dpadUp: Boolean = false,
    val dpadDown: Boolean = false,
    val dpadLeft: Boolean = false,
    val dpadRight: Boolean = false,
    
    // Bumpers
    val leftBumper: Boolean = false,
    val rightBumper: Boolean = false,
    
    // Center buttons
    val start: Boolean = false,
    val back: Boolean = false,
    val guide: Boolean = false,
    
    // Stick buttons (L3 / R3)
    val leftStickButton: Boolean = false,
    val rightStickButton: Boolean = false,
    
    // Axes (Sticks typically range -1.0 to 1.0, Triggers 0.0 to 1.0)
    val leftStickX: Double = 0.0,
    val leftStickY: Double = 0.0,
    val rightStickX: Double = 0.0,
    val rightStickY: Double = 0.0,
    
    val leftTrigger: Double = 0.0,
    val rightTrigger: Double = 0.0
)
