package com.areslib.ftc.hardware

import com.qualcomm.robotcore.hardware.DcMotorEx
import com.qualcomm.robotcore.hardware.DcMotor
import com.qualcomm.robotcore.hardware.DcMotorSimple
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
 * value and never performs a hardware read until configuration invalidates the cache.
 * Power is clamped to [-1, 1]; non-finite commands neutralize. Configuration changes
 * invalidate the cache so the next explicit command reaches the device.
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

    init { require(epsilon in 0.0..1.0) { "Power cache epsilon must be within [0, 1]" } }

    private var hasPowerCommand = false
    private var lastPower = 0.0

    override var power: Double
        get() = if (hasPowerCommand) lastPower else delegate.power
        set(value) {
            val command = if (value.isFinite()) value.coerceIn(-1.0, 1.0) else 0.0
            if (!hasPowerCommand || (command != lastPower &&
                (command == 0.0 || abs(command - lastPower) >= epsilon))) {
                delegate.power = command
                lastPower = command
                hasPowerCommand = true
            }
        }

    override var mode: DcMotor.RunMode
        get() = delegate.mode
        set(value) {
            hasPowerCommand = false
            delegate.mode = value
        }

    override var direction: DcMotorSimple.Direction
        get() = delegate.direction
        set(value) {
            hasPowerCommand = false
            delegate.direction = value
        }

    override fun resetDeviceConfigurationForOpMode() {
        hasPowerCommand = false
        delegate.resetDeviceConfigurationForOpMode()
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
 * position is clamped to [0, 1]. Non-finite positions are rejected because a servo has
 * no universal neutral position. Device resets invalidate the command cache.
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

    init { require(epsilon in 0.0..1.0) { "Position cache epsilon must be within [0, 1]" } }

    private var hasPositionCommand = false
    private var lastPosition = 0.0

    override var position: Double
        get() = if (hasPositionCommand) lastPosition else delegate.position
        set(value) {
            require(value.isFinite()) { "Servo position must be finite" }
            val command = value.coerceIn(0.0, 1.0)
            if (!hasPositionCommand || (command != lastPosition && abs(command - lastPosition) >= epsilon)) {
                delegate.position = command
                lastPosition = command
                hasPositionCommand = true
            }
        }

    override fun resetDeviceConfigurationForOpMode() {
        hasPositionCommand = false
        delegate.resetDeviceConfigurationForOpMode()
    }
}

