package com.areslib.frc

import com.ctre.phoenix6.hardware.TalonFX
import com.ctre.phoenix6.BaseStatusSignal
import com.areslib.action.RobotAction
import com.areslib.hardware.drive.SwerveModuleIO
import com.areslib.hardware.drive.SwerveModuleInputs

/**
 * Individual swerve pod hardware IO wrapper for CTRE Phoenix 6 [TalonFX] drive and steer motors.
 *
 * Synchronizes CANivore CAN-FD 250Hz status signals using `BaseStatusSignal.waitForAll(0.02, ...)` to eliminate
 * phase-lag jitter between drive velocity, drive encoder position, and CANcoder absolute steering angle signals.
 *
 * ### Physical Units:
 * - Position: Radians ($rad$)
 * - Velocity: Radians per second ($rad/s$)
 * - CANivore Timestamp: Microseconds converted to Milliseconds ($ms$)
 *
 * @param driveMotor CTRE Phoenix 6 [TalonFX] drive motor hardware instance.
 * @param steerMotor CTRE Phoenix 6 [TalonFX] steer motor hardware instance.
 * @param storeDispatch Redux action dispatch callback function.
 *
 * @see SwerveModuleIO
 * @see SwerveModuleInputs
 */
class SwerveModuleIOPhoenix6(
    private val driveMotor: TalonFX,
    private val steerMotor: TalonFX,
    private val storeDispatch: (RobotAction) -> Unit
) : SwerveModuleIO {

    private val drivePosition = driveMotor.position
    private val driveVelocity = driveMotor.velocity
    private val steerPosition = steerMotor.position

    /**
     * Synchronizes and updates drive position, drive velocity, and absolute steer position into [inputs].
     *
     * @param inputs Pre-allocated [SwerveModuleInputs] target container.
     */
    override fun updateInputs(inputs: SwerveModuleInputs) {
        // FRC CANivore "Airlock" - block until all signals are perfectly synchronized (timeout capped at 20ms)
        BaseStatusSignal.waitForAll(0.02, drivePosition, driveVelocity, steerPosition)
        
        // Populate inputs mutably locally just for transport
        inputs.drivePositionRads = drivePosition.valueAsDouble * 2.0 * Math.PI
        inputs.driveVelocityRadsPerSec = driveVelocity.valueAsDouble * 2.0 * Math.PI
        inputs.steerAbsolutePositionRads = steerPosition.valueAsDouble * 2.0 * Math.PI
        
        // This timestamp is perfectly synced across all devices on the CANivore
        inputs.timestampMs = (drivePosition.timestamp.time * 1000).toLong()
    }
    
    /**
     * Reads CANivore synchronized signals and dispatches [RobotAction.DriveHardwareUpdate] to the Redux store.
     */
    fun dispatchHardwareUpdate() {
        val inputs = SwerveModuleInputs()
        updateInputs(inputs)
        
        // Convert to immutable action and dispatch to central store
        // (Simplified action for demonstration)
        val action = RobotAction.DriveHardwareUpdate(
            xVelocity = 0.0, 
            yVelocity = 0.0,
            angularVelocity = 0.0,
            deltaX = inputs.driveVelocityRadsPerSec * 0.02, // mock delta
            deltaY = 0.0,
            deltaHeading = inputs.steerAbsolutePositionRads,
            timestampMs = inputs.timestampMs
        )
        storeDispatch(action)
    }
}

