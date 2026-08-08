package com.areslib.frc

import com.areslib.frc.drivetrain.SwerveCtreDrivetrainReader
import com.areslib.frc.drivetrain.SwerveCtreSpeedRequestWriter
import com.areslib.hardware.drive.SwerveHardwareIO
import com.areslib.state.DriveState
import com.ctre.phoenix6.swerve.SwerveDrivetrain

/**
 * Hardware IO bridge for FRC CTRE Phoenix 6 Swerve Drivetrains.
 *
 * Integrates CTRE [SwerveDrivetrain] (operating natively on 250Hz CANivore CAN-FD loops) into the pure mathematical
 * ARESLib Redux architecture. Handles CANcoder absolute position signals, TalonFX motor current draws, Pigeon2 IMU readings,
 * and AdvantageScope signal logging via [SwerveCtreDrivetrainReader] and [SwerveCtreSpeedRequestWriter].
 *
 * ### Physical Units & Coordinates:
 * - Position: Meters ($m$)
 * - Velocity: Meters per second ($m/s$)
 * - Heading: Radians ($rad$), counter-clockwise positive ($0 = +X$, $\pi/2 = +Y$)
 * - Angular Velocity: Radians per second ($rad/s$)
 * - Motor Current: Amperes ($A$)
 * - Inclination: Pitch and Roll in Degrees ($^\circ$)
 *
 * ### Zero-GC Guarantee:
 * Reads and writes pass through pre-allocated primitive arrays (`scratchSpeeds`, `scratchCurrents`, etc.) to prevent allocation stalls in 50Hz/250Hz loops.
 *
 * @param drivetrain CTRE Phoenix 6 [SwerveDrivetrain] instance.
 *
 * @see SwerveHardwareIO
 * @see SwerveCtreDrivetrainReader
 * @see SwerveCtreSpeedRequestWriter
 */
class FRCSwerveHardwareIO(private val drivetrain: SwerveDrivetrain<*, *, *>) : SwerveHardwareIO {


    private val reader = SwerveCtreDrivetrainReader(drivetrain)
    private val writer = SwerveCtreSpeedRequestWriter(drivetrain)

    /** Synchronously refreshes cached CAN signals across motor currents, encoders, and IMU status signals. */
    override fun refresh() = reader.refresh()

    /** Safely halts all drivetrain motion by commanding zero velocity. */
    override fun safe() = writer.safe()

    /**
     * Reads current supply draw in Amperes for all 4 drive motors into [out].
     * @param out 4-element output array.
     */
    override fun getCurrents(out: DoubleArray) = reader.getCurrents(out)

    /**
     * Reads absolute CANcoder module positions in rotations into [out].
     * @param out 4-element output array.
     */
    override fun getEncoderPositions(out: DoubleArray) = reader.getEncoderPositions(out)

    /** Robot pitch inclination angle in degrees. */
    override val pitchDegrees: Double
        get() = reader.pitchDegrees

    /** Robot roll inclination angle in degrees. */
    override val rollDegrees: Double
        get() = reader.rollDegrees

    /**
     * Reads individual module drive surface speeds in m/s into [out].
     * @param out 4-element output array.
     */
    override fun getModuleSpeeds(out: DoubleArray) = reader.getModuleSpeeds(out)

    /**
     * Reads the 250Hz synchronized pose from the CTRE drivetrain and maps it into a new [DriveState].
     *
     * @return Updated immutable [DriveState].
     */
    override fun read(): DriveState = reader.read()

    /**
     * Writes target chassis speed commands to the CTRE SwerveDrivetrain.
     *
     * @param state Immutable [DriveState] containing target velocities and field-centric flags.
     */
    override fun write(state: DriveState) = writer.write(state)
    
    override fun seedPose(pose: com.areslib.math.geometry.Pose2d) {
        // drivetrain.seedFieldRelative(edu.wpi.first.math.geometry.Pose2d(pose.x, pose.y, edu.wpi.first.math.geometry.Rotation2d(pose.heading.radians)))
    }
}
