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
 * - Field commands use the fixed blue-alliance field frame; the input owner applies alliance
 *   perspective once before dispatch. Both frames request velocity drive and position steering.
 *
 * ### Zero-GC Guarantee:
 * Normal writes reuse request objects and primitive speed storage. Invalid arguments and exception
 * paths may allocate. The writer is single-loop-owned; the synchronous consumer must consume or
 * snapshot mutable request values before returning. Native execution and physical timing are
 * separate from host allocation checks. Enable/arm and vehicle speed limits belong to the owner.
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
        .withForwardPerspective(SwerveRequest.ForwardPerspectiveValue.BlueAlliance)
        .withDriveRequestType(com.ctre.phoenix6.swerve.SwerveModule.DriveRequestType.Velocity)
        .withSteerRequestType(com.ctre.phoenix6.swerve.SwerveModule.SteerRequestType.Position)
        .withDeadband(0.02)
        .withRotationalDeadband(0.02)
        .withDesaturateWheelSpeeds(true)
    private val robotSpeedsRequest = SwerveRequest.ApplyRobotSpeeds()
        .withDriveRequestType(com.ctre.phoenix6.swerve.SwerveModule.DriveRequestType.Velocity)
        .withSteerRequestType(com.ctre.phoenix6.swerve.SwerveModule.SteerRequestType.Position)
        .withDesaturateWheelSpeeds(true)
    private val brakeRequest = SwerveRequest.SwerveDriveBrake()
        .withDriveRequestType(com.ctre.phoenix6.swerve.SwerveModule.DriveRequestType.Velocity)
        .withSteerRequestType(com.ctre.phoenix6.swerve.SwerveModule.SteerRequestType.Position)
    private val scratchSpeeds = ChassisSpeeds()

    /**
     * Requests CTRE's X-brake: zero drive speed with active steering position control.
     *
     * A zero-speed request leaves the modules at their prior steering targets and can overwrite an
     * already-commanded X lock when [com.areslib.hardware.HardwareRegistry.safeAll] runs. The brake
     * request is therefore the fail-safe primitive as well as the operator-facing X-brake command.
     * Reuses the brake object; consumer/native execution is outside the allocation claim.
     */
    fun safe() {
        setControl.accept(brakeRequest)
    }

    /**
     * Dispatches finite target chassis speeds with finite scale clamped to [0, 1]. Explicit X-brake
     * ignores unused motion/scale fields. Invalid motion requests attempt the same brake before
     * throwing; failed motion writes attempt brake and retain their original exception, with a
     * distinct cleanup failure suppressed. A successful request is not proof of physical stopping.
     * 
     * @param state The target [DriveState] containing $m/s$ and $rad/s$ requests.
     */
    fun write(state: DriveState, powerScale: Double) {
        if (state.isXLock || state.driveMode == com.areslib.state.DriveMode.X_BRAKE) {
            safe()
            return
        }
        if (!powerScale.isFinite() || !state.xVelocityMetersPerSecond.isFinite() ||
            !state.yVelocityMetersPerSecond.isFinite() || !state.angularVelocityRadiansPerSecond.isFinite()) {
            failSafe(IllegalArgumentException("Swerve motion components and power scale must be finite"))
        }
        val appliedScale = powerScale.coerceIn(0.0, 1.0)
        try {
            if (state.isFieldCentric) {
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
        } catch (failure: Throwable) {
            failSafe(failure)
        }
    }

    private fun failSafe(failure: Throwable): Nothing {
        try {
            safe()
        } catch (cleanup: Throwable) {
            if (cleanup !== failure) failure.addSuppressed(cleanup)
        }
        throw failure
    }
}
