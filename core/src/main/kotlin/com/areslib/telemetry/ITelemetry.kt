package com.areslib.telemetry

import com.areslib.control.safety.BrownoutGuard
import com.areslib.hardware.actuator.MotorIO
import com.areslib.math.geometry.Pose2d

/**
 * Platform-agnostic telemetry interface used by the functional core to publish
 * variables, tuning parameters, and robot state.
 */
interface ITelemetry {
    /**
     * putNumber declaration.
     *
     * @param args Standard arguments (if applicable).
     * @return Corresponding output value or Unit.
     */
    fun putNumber(key: String, value: Double)
    /**
     * putBoolean declaration.
     *
     * @param args Standard arguments (if applicable).
     * @return Corresponding output value or Unit.
     */
    fun putBoolean(key: String, value: Boolean)
    /**
     * putString declaration.
     *
     * @param args Standard arguments (if applicable).
     * @return Corresponding output value or Unit.
     */
    fun putString(key: String, value: String)
    /**
     * putDoubleArray declaration.
     *
     * @param args Standard arguments (if applicable).
     * @return Corresponding output value or Unit.
     */
    fun putDoubleArray(key: String, value: DoubleArray)
    
    /**
     * getNumber declaration.
     *
     * @param args Standard arguments (if applicable).
     * @return Corresponding output value or Unit.
     */
    fun getNumber(key: String, defaultValue: Double): Double
    /**
     * getBoolean declaration.
     *
     * @param args Standard arguments (if applicable).
     * @return Corresponding output value or Unit.
     */
    fun getBoolean(key: String, defaultValue: Boolean): Boolean
    /**
     * getString declaration.
     *
     * @param args Standard arguments (if applicable).
     * @return Corresponding output value or Unit.
     */
    fun getString(key: String, defaultValue: String): String
    
    /**
     * Optional method to process periodic updates.
     */
    fun update() {}
    
    /**
     * Optional method to clean up resources.
     */
    fun close() {}
}

/**
 * Extension to log drive motor telemetry using the exact ARES drive key conventions.
 */
fun ITelemetry.logDriveMotor(name: String, motor: MotorIO) {
    putNumber("Drive/MotorPower_$name", motor.power * motor.powerScale)
    putNumber("Drive/MotorEncoder_$name", motor.position)
    putNumber("Drive/MotorVelocity_$name", motor.velocity)
    putNumber("Drive/MotorCurrent_$name", motor.currentAmps)
}

/**
 * Extension to log a 2D pose with format formatting.
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
    /**
     * initialValue declaration.
     *
     * @param args Standard arguments (if applicable).
     * @return Corresponding output value or Unit.
     */
    override fun initialValue() = DoubleArray(3)
}

private val scratchPose3dArray = object : ThreadLocal<DoubleArray>() {
    /**
     * initialValue declaration.
     *
     * @param args Standard arguments (if applicable).
     * @return Corresponding output value or Unit.
     */
    override fun initialValue() = DoubleArray(7)
}

/**
 * Extension to log a 2D pose as a flat double array of [x, y, headingRad].
 */
fun ITelemetry.logPoseArray2d(key: String, pose: Pose2d) {
    val arr = scratchPose2dArray.get()!!
    arr[0] = pose.x
    arr[1] = pose.y
    arr[2] = pose.heading.radians
    putDoubleArray(key, arr)
}

/**
 * Extension to log a 3D pose array (for AdvantageScope) from x, y, and heading.
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
 * Extension to log brownout guard state and diagnostics.
 */
fun ITelemetry.logBrownout(brownoutGuard: com.areslib.control.safety.BrownoutGuard, batteryVoltage: Double) {
    putNumber("Robot/BatteryVoltage", batteryVoltage)
    putNumber("Robot/BrownoutPowerScale", brownoutGuard.powerScale)
    putString("Robot/BrownoutState", brownoutGuard.state.name)
    putNumber("Robot/BatteryPercent", brownoutGuard.batteryPercent)
    putNumber("Diagnostics/Power/BrownoutCount", brownoutGuard.tripCount.toDouble())
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
