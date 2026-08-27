package com.areslib.ftc

import com.qualcomm.robotcore.hardware.HardwareMap
import com.qualcomm.robotcore.hardware.DcMotorEx
import com.qualcomm.robotcore.hardware.IMU
import com.areslib.ftc.hardware.FtcMotor
import com.areslib.ftc.hardware.FtcImu
import com.areslib.hardware.sensor.ImuInputs
import com.areslib.action.RobotAction
import com.areslib.subsystem.DriveSubsystem

/**
 * Single-motor testbed facade for hardware unit testing and empirical motor characterization.
 *
 * Extends [FtcBaseRobot] while bypassing odometry and vision sensors (`pinpointName = null`, `limelightName = null`).
 * Wraps a single REV motor (`"revMotor"`) and IMU (`"imu"`), allowing isolated testing of velocity, feedforward, and voltage responses.
 *
 * ### Physical Units & Control:
 * - Voltage target: Derived from Redux odometry state scaled to $[0.0, 12.0]$ Volts ($V$).
 * - Angular velocity: Radians per second ($rad/s$).
 * - IMU Euler angles: Pitch and roll converted to degrees ($deg$), yaw velocity in $rad/s$.
 *
 * ### Zero-GC Guarantee:
 * Uses pre-allocated [ImuInputs] buffers to read IMU roll, pitch, and yaw velocity without heap allocations in [updateHardwareInputs].
 *
 * @param hardwareMap Qualcomm FTC SDK hardware map reference.
 *
 * @see FtcBaseRobot
 * @see com.areslib.ftc.hardware.FtcMotor
 */
class FtcTestbedRobot(hardwareMap: HardwareMap) : FtcBaseRobot(hardwareMap, pinpointName = null, limelightName = null) {

    /** Subsystem facade managing drive state actions. */
    val drive = DriveSubsystem(store)
    
    /** Hardware IO wrapper for single REV Expansion/Control Hub motor (`"revMotor"`). */
    val motor = FtcMotor(hardwareMap.get(DcMotorEx::class.java, "revMotor"))

    /** Hardware IO wrapper for Control Hub internal IMU sensor (`"imu"`). */
    val imu = FtcImu(hardwareMap.get(IMU::class.java, "imu"))
    
    private val imuInputs = ImuInputs()

    init {
        com.areslib.hardware.HardwareRegistry.registerMotor("revMotor", motor)
        com.areslib.hardware.HardwareRegistry.registerDevice("IMU", imu)
    }

    /**
     * Reads IMU inputs into pre-allocated memory buffers and dispatches drive state update actions.
     */
    override fun updateHardwareInputs() {
        val timestamp = com.areslib.util.RobotClock.currentTimeMillis()
        com.areslib.hardware.HardwareRegistry.refreshAll()
        imu.updateInputs(imuInputs)

        store.dispatch(RobotAction.DriveHardwareUpdate(
            xVelocity = imuInputs.yawVelocityRadPerSec,
            yVelocity = 0.0,
            angularVelocity = imuInputs.yawVelocityRadPerSec,
            deltaX = 0.0,
            deltaY = 0.0,
            deltaHeading = 0.0,
            timestampMs = timestamp,
            pitchDegrees = Math.toDegrees(imuInputs.pitchRadians),
            rollDegrees = Math.toDegrees(imuInputs.rollRadians)
        ))
    }

    /**
     * Updates motor voltage output based on current state parameters.
     *
     * @param dtSeconds Delta time step in seconds ($s$).
     * @param batteryVoltage Bus battery voltage in Volts ($V$).
     * @param powerScale Dynamic power limit scaling factor $[0.0, 1.0]$.
     */
    override fun updateSubsystems(dtSeconds: Double, batteryVoltage: Double, powerScale: Double) {
        val targetVolts = (store.state.drive.odometryX * 0.1) * 12.0
        motor.setVoltage(targetVolts, batteryVoltage)
    }

    /**
     * Telemetry output hook for testbed diagnostics.
     *
     * @param timestamp System clock timestamp in milliseconds ($ms$).
     */
    override fun publishRobotTelemetry(timestamp: Long) {}

    /**
     * Safely cuts power to all registered testbed motor devices.
     */
    override fun safeHardware() {
        com.areslib.hardware.HardwareRegistry.safeAll()
    }
}


