package com.areslib.frc

import com.areslib.frc.drivetrain.SwerveCtreDrivetrainReader
import com.areslib.frc.drivetrain.SwerveCtreSpeedRequestWriter
import com.areslib.hardware.drive.SwerveHardwareIO
import com.areslib.state.DriveState
import com.ctre.phoenix6.swerve.SwerveDrivetrain
import edu.wpi.first.math.Matrix
import edu.wpi.first.math.Nat
import edu.wpi.first.math.numbers.N1
import edu.wpi.first.math.numbers.N3

/**
 * Hardware IO bridge for FRC CTRE Phoenix 6 Swerve Drivetrains.
 *
 * Integrates CTRE [SwerveDrivetrain] (operating natively on 250Hz CANivore CAN-FD loops) into the pure mathematical
 * ARESLib Redux architecture. Handles CANcoder absolute position signals, TalonFX motor current draws, Pigeon2 IMU readings,
 * and periodic signal reads via [SwerveCtreDrivetrainReader] and [SwerveCtreSpeedRequestWriter].
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
class FRCSwerveHardwareIO(private val drivetrain: SwerveDrivetrain<*, *, *>) : SwerveHardwareIO, AutoCloseable {


    private val reader = SwerveCtreDrivetrainReader(drivetrain)
    private val writer = SwerveCtreSpeedRequestWriter(drivetrain)
    private val visionStdDevs = Matrix<N3, N1>(Nat.N3(), Nat.N1())

    init {
        com.areslib.hardware.HardwareRegistry.registerCloseable(this)
    }

    /** Synchronously refreshes cached CAN signals across motor currents, encoders, and IMU status signals. */
    override fun refresh() = reader.refresh()

    /** Safely halts all drivetrain motion by commanding zero velocity. */
    override fun safe() = writer.safe()

    /**
     * Reads current supply draw in Amperes for all 4 drive motors into [out].
     * @param out 4-element output array.
     */
    override fun getCurrents(out: DoubleArray) = reader.getCurrents(out)

    override val currentMeasurementsValid: Boolean
        get() = reader.currentMeasurementsValid

    /**
     * Reads absolute CANcoder module positions in rotations into [out].
     * @param out 4-element output array.
     */
    override fun getEncoderPositions(out: DoubleArray) = reader.getEncoderPositions(out)

    override val encoderPositionsValid: Boolean
        get() = reader.encoderPositionsValid

    override val signalLatencyMs: Double
        get() = reader.signalLatencyMs

    override fun getFaults(out: IntArray) = reader.getFaults(out)

    /** Robot pitch inclination angle in degrees. */
    override val pitchDegrees: Double
        get() = reader.pitchDegrees

    /** Robot roll inclination angle in degrees. */
    override val rollDegrees: Double
        get() = reader.rollDegrees

    override val rawGyroYawDegrees: Double
        get() = reader.rawGyroYawDegrees

    override val yawRateDegreesPerSecond: Double
        get() = reader.yawRateDegreesPerSecond

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
     * @param driveState Immutable [DriveState] containing target velocities and field-centric flags.
     */
    override fun write(driveState: DriveState, powerScale: Double) = writer.write(driveState, powerScale)

    override fun addVisionMeasurement(pose: com.areslib.math.geometry.Pose2d, timestampSeconds: Double) {
        drivetrain.addVisionMeasurement(
            edu.wpi.first.math.geometry.Pose2d(
                pose.x,
                pose.y,
                edu.wpi.first.math.geometry.Rotation2d(pose.heading.radians)
            ),
            timestampSeconds
        )
    }

    override fun addVisionMeasurement(
        pose: com.areslib.math.geometry.Pose2d,
        timestampSeconds: Double,
        stdDevXMeters: Double,
        stdDevYMeters: Double,
        stdDevHeadingRadians: Double
    ) {
        if (!stdDevXMeters.isFinite() || stdDevXMeters <= 0.0 ||
            !stdDevYMeters.isFinite() || stdDevYMeters <= 0.0 ||
            !stdDevHeadingRadians.isFinite() || stdDevHeadingRadians <= 0.0) {
            addVisionMeasurement(pose, timestampSeconds)
            return
        }

        visionStdDevs.set(0, 0, stdDevXMeters)
        visionStdDevs.set(1, 0, stdDevYMeters)
        visionStdDevs.set(2, 0, stdDevHeadingRadians)
        drivetrain.addVisionMeasurement(
            edu.wpi.first.math.geometry.Pose2d(
                pose.x,
                pose.y,
                edu.wpi.first.math.geometry.Rotation2d(pose.heading.radians)
            ),
            timestampSeconds,
            visionStdDevs
        )
    }

    override fun samplePoseAt(timestampSeconds: Double, out: DoubleArray): Boolean {
        require(out.size >= 3) { "samplePoseAt output must contain X, Y, and heading" }
        if (!timestampSeconds.isFinite()) return false
        val sample = drivetrain.samplePoseAt(timestampSeconds)
        if (sample.isEmpty) return false
        val pose = sample.get()
        out[0] = pose.x
        out[1] = pose.y
        out[2] = pose.rotation.radians
        return true
    }
    
    /**
     * Resets CTRE's authoritative field pose in meters and CCW-positive radians.
     * This is a hard estimator seed and should be used only at lifecycle/relocalization boundaries.
     */
    override fun seedPose(pose: com.areslib.math.geometry.Pose2d) {
        drivetrain.resetPose(edu.wpi.first.math.geometry.Pose2d(pose.x, pose.y, edu.wpi.first.math.geometry.Rotation2d(pose.heading.radians)))
    }

    override fun close() {
        var failure: Throwable? = null
        try {
            safe()
        } catch (error: Throwable) {
            failure = error
        }
        try {
            drivetrain.close()
        } catch (error: Throwable) {
            if (failure == null) failure = error else failure.addSuppressed(error)
        }
        failure?.let { throw it }
    }
}
