package com.areslib.frc.drivetrain

import com.areslib.state.DriveState
import com.ctre.phoenix6.swerve.SwerveDrivetrain
import com.ctre.phoenix6.swerve.SwerveRequest
import edu.wpi.first.math.kinematics.ChassisSpeeds

/**
 * Actuation writer for CTRE Phoenix 6 [SwerveDrivetrain] hardware platforms.
 *
 * Translates immutable Redux [DriveState] target velocities into CTRE Phoenix 6 [SwerveRequest.FieldCentric]
 * or [SwerveRequest.ApplyRobotSpeeds] commands.
 *
 * ### Physical Units & Conventions:
 * - Translation Velocities ($V_x, V_y$): Meters per second ($m/s$).
 * - Rotational Rate ($\omega$): Radians per second ($rad/s$).
 * - Angular Convention: **CCW-positive** standard.
 *
 * ### Zero-GC Guarantee:
 * Reuses internal request objects (`fieldCentricRequest`, `robotSpeedsRequest`, `scratchSpeeds`) to ensure zero-GC heap allocations during 50Hz control cycles.
 *
 * @param drivetrain Physical CTRE [SwerveDrivetrain] instance.
 *
 * @see SwerveDrivetrain
 * @see SwerveRequest
 * @see DriveState
 */
class SwerveCtreSpeedRequestWriter(private val drivetrain: SwerveDrivetrain<*, *, *>) {

    private val fieldCentricRequest = SwerveRequest.FieldCentric()
    private val robotSpeedsRequest = SwerveRequest.ApplyRobotSpeeds()
    private val scratchSpeeds = ChassisSpeeds()

    /**
     * Safes the drivetrain by commanding zero velocity.
     * Zero-GC allocation.
     */
    fun safe() {
        write(DriveState())
    }

    /**
     * Dispatches the target chassis speeds to the CTRE drivetrain.
     * Switches transparently between field-centric and robot-centric requests.
     * 
     * @param state The target [DriveState] containing $m/s$ and $rad/s$ requests.
     */
    fun write(state: DriveState) {
        if (state.isFieldCentric) {
            fieldCentricRequest.VelocityX = state.xVelocityMetersPerSecond
            fieldCentricRequest.VelocityY = state.yVelocityMetersPerSecond
            fieldCentricRequest.RotationalRate = state.angularVelocityRadiansPerSecond
            drivetrain.setControl(fieldCentricRequest)
        } else {
            scratchSpeeds.vxMetersPerSecond = state.xVelocityMetersPerSecond
            scratchSpeeds.vyMetersPerSecond = state.yVelocityMetersPerSecond
            scratchSpeeds.omegaRadiansPerSecond = state.angularVelocityRadiansPerSecond
            robotSpeedsRequest.Speeds = scratchSpeeds
            drivetrain.setControl(robotSpeedsRequest)
        }
    }
}
