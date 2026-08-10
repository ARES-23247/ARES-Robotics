package com.areslib.ftc.hardware

import com.qualcomm.robotcore.hardware.DcMotorEx
import com.qualcomm.robotcore.hardware.Servo
import kotlin.math.abs

/**
 * Non-blocking caching decorator for [DcMotorEx] hardware objects to eliminate redundant REV I2C writes.
 *
 * Tracks the last commanded power and writes only when:
 * 1. The target changes to zero, guaranteeing one hard-stop command.
 * 2. The absolute command delta is at least [epsilon] ($|u-u_{last}| \ge \epsilon$).
 *
 * ### Performance & Bus Optimization:
 * This reduces REV Lynx command traffic and allocates no objects in the setter. Before the first
 * command, the getter delegates to hardware; after the first command it returns only the cached
 * value and never performs a hardware read. The decorator does not clamp power or validate
 * [epsilon], so callers retain the FTC SDK's normal range/validation responsibilities.
 *
 * @param delegate Underlying FTC SDK [DcMotorEx] hardware instance.
 * @param epsilon Power change threshold tolerance $[0.0, 1.0]$ (default 0.02).
 *
 * @see DcMotorEx
 */
class CachedDcMotorEx(
    private val delegate: DcMotorEx,
    private val epsilon: Double = 0.02
) : DcMotorEx by delegate {

    private var lastPower = -10.0 // Invalid starting power to guarantee the first write

    override var power: Double
        get() = if (lastPower != -10.0) lastPower else delegate.power
        set(value) {
            if (value == 0.0 && lastPower != 0.0) {
                delegate.power = 0.0
                lastPower = 0.0
            } else if (abs(value - lastPower) >= epsilon) {
                delegate.power = value
                lastPower = value
            }
        }
}

/**
 * Non-blocking caching decorator for [Servo] hardware objects to eliminate redundant REV I2C writes.
 *
 * Tracks the last commanded position setting and only delegates calls to the physical servo driver if the
 * position delta exceeds tolerance threshold [epsilon] ($|p - p_{last}| \ge \epsilon$).
 *
 * ### Performance & Bus Optimization:
 * Prevents redundant servo PWM updates and allocates no objects in the setter. Before the first
 * command, the getter delegates to hardware; afterward it returns only the cached command. The
 * decorator does not clamp position or validate [epsilon].
 *
 * @param delegate Underlying FTC SDK [Servo] hardware instance.
 * @param epsilon Servo position threshold tolerance $[0.0, 1.0]$ (default 0.005).
 *
 * @see Servo
 */
class CachedServo(
    private val delegate: Servo,
    private val epsilon: Double = 0.005
) : Servo by delegate {

    private var lastPosition = -10.0 // Invalid starting position to guarantee the first write

    override var position: Double
        get() = if (lastPosition != -10.0) lastPosition else delegate.position
        set(value) {
            if (abs(value - lastPosition) >= epsilon) {
                delegate.position = value
                lastPosition = value
            }
        }
}

