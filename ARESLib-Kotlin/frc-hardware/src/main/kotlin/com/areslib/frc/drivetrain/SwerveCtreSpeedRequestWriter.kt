package com.areslib.frc.drivetrain

import com.areslib.state.DriveState
import com.ctre.phoenix6.swerve.SwerveDrivetrain
import com.ctre.phoenix6.swerve.SwerveRequest
import edu.wpi.first.math.kinematics.ChassisSpeeds
import java.util.function.Consumer

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
class SwerveCtreSpeedRequestWriter internal constructor(
    private val setControl: Consumer<SwerveRequest>
) {

    constructor(drivetrain: SwerveDrivetrain<*, *, *>) : this(Consumer { request ->
        drivetrain.setControl(request)
    })

    private val fieldCentricRequest = SwerveRequest.FieldCentric()
        .withDriveRequestType(com.ctre.phoenix6.swerve.SwerveModule.DriveRequestType.Velocity)
        .withSteerRequestType(com.ctre.phoenix6.swerve.SwerveModule.SteerRequestType.Position)
        .withDeadband(0.02)
        .withRotationalDeadband(0.02)
        .withDesaturateWheelSpeeds(true)
    private val robotSpeedsRequest = SwerveRequest.ApplyRobotSpeeds()
        .withDesaturateWheelSpeeds(true)
    private val brakeRequest = SwerveRequest.SwerveDriveBrake()
        .withDriveRequestType(com.ctre.phoenix6.swerve.SwerveModule.DriveRequestType.Velocity)
        .withSteerRequestType(com.ctre.phoenix6.swerve.SwerveModule.SteerRequestType.Position)
    private val scratchSpeeds = ChassisSpeeds()

    /**
     * Safes the drivetrain with CTRE's physical X-brake request.
     *
     * A zero-speed request leaves the modules at their prior steering targets and can overwrite an
     * already-commanded X lock when [com.areslib.hardware.HardwareRegistry.safeAll] runs. The brake
     * request is therefore the fail-safe primitive as well as the operator-facing X-brake command.
     * Zero-GC allocation.
     */
    fun safe() {
        setControl.accept(brakeRequest)
    }

    /**
     * Dispatches the target chassis speeds to the CTRE drivetrain.
     * Switches transparently between field-centric and robot-centric requests.
     * 
     * @param state The target [DriveState] containing $m/s$ and $rad/s$ requests.
     */
    fun write(state: DriveState, powerScale: Double) {
        if (!powerScale.isFinite()) {
            throw IllegalArgumentException("Swerve power scale must be finite")
        }
        val appliedScale = powerScale.coerceIn(0.0, 1.0)
        if (state.isXLock || state.driveMode == com.areslib.state.DriveMode.X_BRAKE) {
            setControl.accept(brakeRequest)
        } else if (state.isFieldCentric) {
            fieldCentricRequest.VelocityX = state.xVelocityMetersPerSecond * appliedScale
            fieldCentricRequest.VelocityY = state.yVelocityMetersPerSecond * appliedScale
            fieldCentricRequest.RotationalRate = state.angularVelocityRadiansPerSecond * appliedScale
            setControl.accept(fieldCentricRequest)
        } else {
            scratchSpeeds.vxMetersPerSecond = state.xVelocityMetersPerSecond * appliedScale
            scratchSpeeds.vyMetersPerSecond = state.yVelocityMetersPerSecond * appliedScale
            scratchSpeeds.omegaRadiansPerSecond = state.angularVelocityRadiansPerSecond * appliedScale
            robotSpeedsRequest.Speeds = scratchSpeeds
            setControl.accept(robotSpeedsRequest)
        }
    }
}
