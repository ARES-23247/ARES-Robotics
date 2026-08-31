package com.areslib.telemetry

import com.areslib.hardware.actuator.MotorIO
import com.areslib.math.geometry.Pose2d

/**
 * Platform-neutral key/value telemetry boundary used by robot and simulator code.
 *
 * Topic keys are canonical without a leading slash. Implementations strip transport-only leading
 * slashes, but aliases are not supported. Getters return `defaultValue` when a topic is
 * absent, disconnected, or incompatible. Implementations that retain or asynchronously serialize a
 * [DoubleArray] must snapshot it before returning from [putDoubleArray], because hot-path callers
 * intentionally reuse preallocated buffers.
 */
interface ITelemetry {
    /** Publishes a double under canonical topic [key]. */
    fun putNumber(key: String, value: Double)
    /** Publishes a boolean under canonical topic [key]. */
    fun putBoolean(key: String, value: Boolean)
    /** Publishes a UTF-8 string under canonical topic [key]. */
    fun putString(key: String, value: String)
    /** Publishes the values currently in [value]; implementations must not retain caller ownership. */
    fun putDoubleArray(key: String, value: DoubleArray)
    
    /** Reads a number, returning `defaultValue` when no compatible value is available. */
    fun getNumber(key: String, defaultValue: Double): Double
    /** Reads a boolean, returning `defaultValue` when no compatible value is available. */
    fun getBoolean(key: String, defaultValue: Boolean): Boolean
    /** Reads a string, returning `defaultValue` when no compatible value is available. */
    fun getString(key: String, defaultValue: String): String
    
    /**
     * Flushes or advances the backend when required. The default backend needs no periodic work.
     */
    fun update() {}
    
    /**
     * Releases backend resources. The default implementation owns nothing.
     */
    fun close() {}
}

/**
 * Logs cached motor state beneath the canonical `Hardware/Motors/{name}` topic prefix.
 * No hardware reads should occur through [MotorIO] getters.
 */
fun ITelemetry.logDriveMotor(name: String, motor: MotorIO) {
    putNumber(TelemetryTopicConstants.motorPowerTopic(name), motor.power * motor.powerScale)
    putNumber(TelemetryTopicConstants.motorPositionTopic(name), motor.position)
    putNumber(TelemetryTopicConstants.motorVelocityTopic(name), motor.velocity)
    putNumber(TelemetryTopicConstants.motorCurrentTopic(name), motor.currentAmps)
}

/**
 * Logs a field pose in meters and CCW-positive radians as three scalar topics.
 */
fun ITelemetry.logPose2d(prefix: String, pose: Pose2d, useUnderscores: Boolean = false, lowercase: Boolean = false) {
    val sep = if (useUnderscores) "_" else "/"
    val xStr = if (lowercase) "x" else "X"
    val yStr = if (lowercase) "y" else "Y"
    val hStr = if (lowercase) "heading" else "Heading"
    putNumber("$prefix$sep$xStr", pose.x)
    putNumber("$prefix$sep$yStr", pose.y)
    putNumber("$prefix$sep$hStr", pose.heading.radians)
}

private val scratchPose2dArray = object : ThreadLocal<DoubleArray>() {
    /** Allocates one reusable pose buffer per publishing thread. */
    override fun initialValue() = DoubleArray(3)
}

private val scratchPose3dArray = object : ThreadLocal<DoubleArray>() {
    /** Allocates one reusable pose/quaternion buffer per publishing thread. */
    override fun initialValue() = DoubleArray(7)
}

/**
 * Logs a 2D field pose as `[xMeters, yMeters, headingRadians]` using a thread-local buffer.
 */
fun ITelemetry.logPoseArray2d(key: String, pose: Pose2d) {
    val arr = scratchPose2dArray.get()!!
    arr[0] = pose.x
    arr[1] = pose.y
    arr[2] = pose.heading.radians
    putDoubleArray(key, arr)
}

/**
 * Logs an AdvantageScope pose as `[x, y, z, qw, qx, qy, qz]` with `z = 0` and yaw-only rotation.
 */
fun ITelemetry.logPose3d(key: String, x: Double, y: Double, headingRad: Double) {
    val halfH = headingRad / 2.0
    val arr = scratchPose3dArray.get()!!
    arr[0] = x
    arr[1] = y
    arr[2] = 0.0
    arr[3] = Math.cos(halfH)
    arr[4] = 0.0
    arr[5] = 0.0
    arr[6] = Math.sin(halfH)
    putDoubleArray(key, arr)
}

/**
 * Extension to log a 3D pose array (for AdvantageScope) from a Pose2d.
 */
fun ITelemetry.logPose3d(key: String, pose: Pose2d) {
    logPose3d(key, pose.x, pose.y, pose.heading.radians)
}

/**
 * Extension to log gamepad state without code duplication.
 */
fun ITelemetry.logGamepad(prefix: String, gamepad: GamepadState) {
    putNumber("$prefix/LeftStick_X", gamepad.leftStickX.toDouble())
    putNumber("$prefix/LeftStick_Y", gamepad.leftStickY.toDouble())
    putNumber("$prefix/RightStick_X", gamepad.rightStickX.toDouble())
    putNumber("$prefix/RightStick_Y", gamepad.rightStickY.toDouble())
    putNumber("$prefix/LeftTrigger", gamepad.leftTrigger.toDouble())
    putNumber("$prefix/RightTrigger", gamepad.rightTrigger.toDouble())
    putBoolean("$prefix/A", gamepad.a)
    putBoolean("$prefix/B", gamepad.b)
    putBoolean("$prefix/X", gamepad.x)
    putBoolean("$prefix/Y", gamepad.y)
    putBoolean("$prefix/DpadUp", gamepad.dpadUp)
    putBoolean("$prefix/DpadDown", gamepad.dpadDown)
    putBoolean("$prefix/DpadLeft", gamepad.dpadLeft)
    putBoolean("$prefix/DpadRight", gamepad.dpadRight)
    putBoolean("$prefix/LeftBumper", gamepad.leftBumper)
    putBoolean("$prefix/RightBumper", gamepad.rightBumper)
    putBoolean("$prefix/C", gamepad.c)
    putBoolean("$prefix/Z", gamepad.z)
    putBoolean("$prefix/M1", gamepad.m1)
    putBoolean("$prefix/M2", gamepad.m2)
    putBoolean("$prefix/M3", gamepad.m3)
    putBoolean("$prefix/M4", gamepad.m4)
    putBoolean("$prefix/Touchpad", gamepad.touchpad)
    putBoolean("$prefix/Share", gamepad.share)
    putBoolean("$prefix/Options", gamepad.options)
}

/**
 * Extension to log FRC CANbus status diagnostics.
 */
fun ITelemetry.logCanBusStatus(
    busName: String,
    busUtilization: Double,
    errorCount: Int,
    txErrors: Int,
    rxErrors: Int,
    busOffs: Int,
    signalLatencyMs: Double
) {
    putNumber("Diagnostics/CANBus/$busName/Utilization", busUtilization)
    putNumber("Diagnostics/CANBus/$busName/ErrorCount", errorCount.toDouble())
    putNumber("Diagnostics/CANBus/$busName/TxErrors", txErrors.toDouble())
    putNumber("Diagnostics/CANBus/$busName/RxErrors", rxErrors.toDouble())
    putNumber("Diagnostics/CANBus/$busName/BusOffCount", busOffs.toDouble())
    putNumber("Diagnostics/CANBus/$busName/SignalLatencyMs", signalLatencyMs)
}
