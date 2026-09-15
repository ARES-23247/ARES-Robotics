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
 * Integrates CTRE [SwerveDrivetrain] into the pure mathematical
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
 * ### Allocation boundary:
 * Cached array getters reuse caller storage and the writer reuses request objects. Refresh takes
 * an owning vendor state copy, which allocates, and replaces immutable pose/motion snapshots when
 * their values change. This bridge does not promise a zero-GC acquisition loop.
 *
 * All bridge calls serialize with close. Close revokes cached feedback before attempting brake
 * and native destruction once, even if cleanup fails. Later cached reads are unavailable;
 * safe/close do nothing, history reads return false, and other native operations reject the call.
 * Native calls and lock waits are not deadline-bounded. The caller transfers close ownership after
 * successful construction and must not close or mutate the drivetrain concurrently outside this bridge.
 *
 * Motion requires fresh signals, pose/motion and clear drive/steer fault flags. Denied commands
 * request X-brake. Enable/arm, configuration correctness and fault recovery belong to the robot
 * owner. Neither a returned brake nor close proves physical stopping or replaces a hardware watchdog.
 *
 * Vision timestamps are finite seconds in Phoenix's current-time epoch; latency and clock conversion
 * belong upstream. Explicit covariance requires finite positive standard deviations, and pose inputs
 * must be finite. Historical outputs are published only for complete finite poses.
 *
 * @param drivetrain CTRE Phoenix 6 [SwerveDrivetrain] instance.
 *
 * @see SwerveHardwareIO
 * @see SwerveCtreDrivetrainReader
 * @see SwerveCtreSpeedRequestWriter
 */
class FRCSwerveHardwareIO(private val drivetrain: SwerveDrivetrain<*, *, *>) : SwerveHardwareIO, AutoCloseable {


    private val lifecycleLock = Any()
    private var closed = false
    private val reader = SwerveCtreDrivetrainReader(drivetrain)
    private val writer = SwerveCtreSpeedRequestWriter(drivetrain)
    private val visionStdDevs = Matrix<N3, N1>(Nat.N3(), Nat.N1())

    /** Synchronously refreshes cached CAN signals across motor currents, encoders, and IMU status signals. */
    override fun refresh() = synchronized(lifecycleLock) {
        checkOpen()
        try {
            reader.refresh()
        } catch (failure: Throwable) {
            brakeAndRethrow(failure)
        }
        // Revoke an active command immediately on observed invalid feedback. Keep brake outside
        // the acquisition catch so a failing brake is not retried within this call.
        if (!reader.motionAllowed) writer.safe()
    }

    /** Requests zero drive velocity with active X-brake steering; no native access after close. */
    override fun safe() = synchronized(lifecycleLock) {
        if (!closed) writer.safe()
    }

    /**
     * Reads current supply draw in Amperes for all 4 drive motors into [out].
     * @param out 4-element output array.
     */
    override fun getCurrents(out: DoubleArray) = synchronized(lifecycleLock) { reader.getCurrents(out) }

    override val currentMeasurementsValid: Boolean
        get() = synchronized(lifecycleLock) { reader.currentMeasurementsValid }

    /**
     * Reads absolute CANcoder module positions in rotations into [out].
     * @param out 4-element output array.
     */
    override fun getEncoderPositions(out: DoubleArray) = synchronized(lifecycleLock) { reader.getEncoderPositions(out) }

    override val encoderPositionsValid: Boolean
        get() = synchronized(lifecycleLock) { reader.encoderPositionsValid }

    override val signalLatencyMs: Double
        get() = synchronized(lifecycleLock) { reader.signalLatencyMs }

    override fun getFaults(out: IntArray) = synchronized(lifecycleLock) { reader.getFaults(out) }

    /** Robot pitch inclination angle in degrees. */
    override val pitchDegrees: Double
        get() = synchronized(lifecycleLock) { reader.pitchDegrees }

    /** Robot roll inclination angle in degrees. */
    override val rollDegrees: Double
        get() = synchronized(lifecycleLock) { reader.rollDegrees }

    override val rawGyroYawDegrees: Double
        get() = synchronized(lifecycleLock) { reader.rawGyroYawDegrees }

    override val yawRateDegreesPerSecond: Double
        get() = synchronized(lifecycleLock) { reader.yawRateDegreesPerSecond }

    /**
     * Reads individual module drive surface speeds in m/s into [out].
     * @param out 4-element output array.
     */
    override fun getModuleSpeeds(out: DoubleArray) = synchronized(lifecycleLock) { reader.getModuleSpeeds(out) }

    /**
     * Returns the owned pose/motion snapshot captured by the latest refresh.
     *
     * @return Cached immutable [DriveState], or unavailable NaN measurements when stale/invalid.
     */
    override fun read(): DriveState = synchronized(lifecycleLock) { reader.read() }

    /**
     * Writes target chassis speed commands to the CTRE SwerveDrivetrain.
     *
     * @param driveState Immutable [DriveState] containing target velocities and field-centric flags.
     */
    override fun write(driveState: DriveState, powerScale: Double) = synchronized(lifecycleLock) {
        checkOpen()
        // Denial takes precedence over unused motion data, just as an explicit X-brake does.
        if (!reader.motionAllowed) writer.safe() else writer.write(driveState, powerScale)
    }

    override fun addVisionMeasurement(pose: com.areslib.math.geometry.Pose2d, timestampSeconds: Double) = synchronized(lifecycleLock) {
        checkOpen()
        requireFinitePose(pose)
        require(timestampSeconds.isFinite()) { "Vision timestamp must be finite in the vendor timebase" }
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
    ) = synchronized(lifecycleLock) {
        checkOpen()
        requireFinitePose(pose)
        require(timestampSeconds.isFinite()) { "Vision timestamp must be finite in the vendor timebase" }
        require(stdDevXMeters.isFinite() && stdDevXMeters > 0.0 &&
            stdDevYMeters.isFinite() && stdDevYMeters > 0.0 &&
            stdDevHeadingRadians.isFinite() && stdDevHeadingRadians > 0.0) {
            "Vision standard deviations must be finite and positive"
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

    override fun samplePoseAt(timestampSeconds: Double, out: DoubleArray): Boolean = synchronized(lifecycleLock) {
        require(out.size >= 3) { "samplePoseAt output must contain X, Y, and heading" }
        if (closed || !timestampSeconds.isFinite()) return false
        val sample = drivetrain.samplePoseAt(timestampSeconds)
        if (sample.isEmpty) return false
        val pose = sample.get()
        val x = pose.x
        val y = pose.y
        val heading = pose.rotation.radians
        if (!x.isFinite() || !y.isFinite() || !heading.isFinite()) return false
        out[0] = x
        out[1] = y
        out[2] = heading
        return true
    }
    
    /**
     * Resets CTRE's authoritative field pose in meters and CCW-positive radians.
     * This is a hard estimator seed for lifecycle/relocalization boundaries. Brake and revoke the
     * old cache first; motion remains denied until a subsequent valid refresh.
     */
    override fun seedPose(pose: com.areslib.math.geometry.Pose2d) = synchronized(lifecycleLock) {
        checkOpen()
        requireFinitePose(pose)
        reader.invalidate()
        writer.safe()
        drivetrain.resetPose(edu.wpi.first.math.geometry.Pose2d(pose.x, pose.y, edu.wpi.first.math.geometry.Rotation2d(pose.heading.radians)))
    }

    override fun close(): Unit = synchronized(lifecycleLock) {
        if (closed) return
        closed = true
        reader.invalidate()
        var failure: Throwable? = null
        try {
            writer.safe()
        } catch (error: Throwable) {
            failure = error
        }
        try {
            drivetrain.close()
        } catch (error: Throwable) {
            if (failure == null) failure = error else if (failure !== error) failure.addSuppressed(error)
        }
        failure?.let { throw it }
    }

    private fun checkOpen() { check(!closed) { "CTRE swerve hardware is closed" } }

    private fun requireFinitePose(pose: com.areslib.math.geometry.Pose2d) {
        // Rotation2d.radians wraps nonfinite input to zero; validate the original measurement.
        require(pose.x.isFinite() && pose.y.isFinite() && pose.heading.rawRadians.isFinite()) {
            "Pose components must be finite"
        }
    }

    private fun brakeAndRethrow(failure: Throwable): Nothing {
        try {
            writer.safe()
        } catch (cleanup: Throwable) {
            if (cleanup !== failure) failure.addSuppressed(cleanup)
        }
        throw failure
    }
}
