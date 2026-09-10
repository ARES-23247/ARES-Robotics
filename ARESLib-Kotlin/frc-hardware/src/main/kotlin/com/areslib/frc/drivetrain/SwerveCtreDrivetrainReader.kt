package com.areslib.frc.drivetrain

import com.areslib.state.DriveState
import com.areslib.util.RobotClock
import com.ctre.phoenix6.swerve.SwerveDrivetrain

/**
 * Single-loop-owned CTRE measurement cache. Only [refresh] acquires vendor data; all getters
 * consume an owned snapshot. Module order is front-left, front-right, rear-left, rear-right.
 * Currents are amperes, absolute encoders rotations, IMU angles degrees, wheel/chassis speeds
 * meters/second and pose meters with CCW-positive heading radians.
 *
 * Status, finite values and valid vendor timestamps are required. Fast feedback expires after
 * 100 ms; 4 Hz diagnostic signals expire after 750 ms. Cached age includes RobotClock time since
 * acquisition started, conservatively rejecting slow refreshes and clock rewind/overflow.
 * These are measurement-validity checks, not an actuator-enable gate or independent watchdog.
 *
 * Cached getters allocate nothing. Refresh uses one Phoenix owning state copy (which allocates)
 * and creates a new immutable DriveState when its six pose/motion values change. It does not
 * promise zero-GC native acquisition. Vendor callbacks/other clients cannot mutate returned data.
 */
class SwerveCtreDrivetrainReader internal constructor(private val source: SwerveCtreReaderSource) {
    constructor(drivetrain: SwerveDrivetrain<*, *, *>) : this(PhoenixSwerveReaderSource(drivetrain))

    private val values = DoubleArray(36)
    private val pendingValues = DoubleArray(36)
    private val moduleSpeeds = DoubleArray(4)
    private var signalsReady = false
    private var stateReady = false
    private var startedMs = 0L
    private var fastAgeMs = Double.POSITIVE_INFINITY
    private var faultAgeMs = Double.POSITIVE_INFINITY
    private var encoderAgeMs = Double.POSITIVE_INFINITY
    private var stateAgeMs = Double.POSITIVE_INFINITY
    private var cachedDrive = unavailableDrive

    init {
        check(source.configure()) { "CTRE swerve signal update configuration failed" }
    }

    /** All configured signals were successfully refreshed, finite and within their age limits. */
    val encoderPositionsValid: Boolean get() = signalsFresh()
    val currentMeasurementsValid: Boolean get() = signalsFresh()
    /** Conservative age of the oldest cached absolute encoder, or infinity when unavailable. */
    val signalLatencyMs: Double
        get() = if (signalsFresh()) encoderAgeMs + elapsedMs() else Double.POSITIVE_INFINITY

    /** Revokes prior validity before IO; a failed acquisition cannot publish a partial snapshot. */
    fun refresh() {
        signalsReady = false
        stateReady = false
        startedMs = RobotClock.currentTimeMillis()
        for (index in 0 until 36) source.refresh(index)
        // Phoenix's owning copy avoids aliasing its mutable state updated by native telemetry.
        val state = source.state()
        // Use one vendor timebase reading for the whole acquired frame, after all IO completes.
        source.captureTime()
        var valid = true
        var fastAge = 0.0
        var faultAge = 0.0
        var encoderAge = 0.0
        for (index in 0 until 36) {
            val value = source.value(index)
            val ageSeconds = source.latencySeconds(index)
            val limitSeconds = if (index < 12) FAST_AGE_MS / 1000.0 else FAULT_AGE_MS / 1000.0
            if (!source.statusOk(index) || !source.timestampValid(index) ||
                !value.isFinite() || !ageSeconds.isFinite() || ageSeconds < 0.0 || ageSeconds > limitSeconds ||
                (index >= 12 && value != 0.0 && value != 1.0)) {
                valid = false
            }
            pendingValues[index] = value
            val ageMs = ageSeconds * 1000.0
            if (index < 12) fastAge = maxOf(fastAge, ageMs) else faultAge = maxOf(faultAge, ageMs)
            if (index in 4..7) encoderAge = maxOf(encoderAge, ageMs)
        }

        val ageSeconds = source.stateAgeSeconds(state)
        val x = state.Pose.x
        val y = state.Pose.y
        val heading = state.Pose.rotation.radians
        val vx = state.Speeds.vxMetersPerSecond
        val vy = state.Speeds.vyMetersPerSecond
        val omega = state.Speeds.omegaRadiansPerSecond
        var motionValid = x.isFinite() && y.isFinite() && heading.isFinite() && vx.isFinite() &&
            vy.isFinite() && omega.isFinite() && ageSeconds.isFinite() && ageSeconds >= 0.0 &&
            ageSeconds <= FAST_AGE_MS / 1000.0 && state.ModuleStates.size == 4
        if (state.ModuleStates.size == 4) {
            for (index in 0 until 4) {
                val speed = state.ModuleStates[index].speedMetersPerSecond
                moduleSpeeds[index] = speed
                if (!speed.isFinite()) motionValid = false
            }
        }
        if (motionValid && (cachedDrive.odometryX != x || cachedDrive.odometryY != y ||
            cachedDrive.odometryHeading != heading || cachedDrive.xVelocityMetersPerSecond != vx ||
            cachedDrive.yVelocityMetersPerSecond != vy || cachedDrive.angularVelocityRadiansPerSecond != omega)) {
            cachedDrive = cachedDrive.copy(
                xVelocityMetersPerSecond = vx, yVelocityMetersPerSecond = vy,
                angularVelocityRadiansPerSecond = omega, odometryX = x, odometryY = y,
                odometryHeading = heading
            )
        }
        pendingValues.copyInto(values)
        fastAgeMs = fastAge
        faultAgeMs = faultAge
        encoderAgeMs = encoderAge
        stateAgeMs = ageSeconds * 1000.0
        stateReady = motionValid
        signalsReady = valid
    }

    /** Four cached drive currents; invalid snapshots write NaN. Trailing caller storage is preserved. */
    fun getCurrents(out: DoubleArray) = copySignals(out, 0)

    /** Four cached absolute steering positions in rotations; invalid snapshots write NaN. */
    fun getEncoderPositions(out: DoubleArray) = copySignals(out, 4)

    private fun copySignals(out: DoubleArray, offset: Int) {
        require(out.size >= 4) { "Swerve output must contain four modules" }
        if (signalsFresh()) values.copyInto(out, 0, offset, offset + 4) else out.fill(Double.NaN, 0, 4)
    }

    /** Bits 0..5: drive/steer hardware, brownout and temperature. Bit 6 means unavailable feedback. */
    fun getFaults(out: IntArray) {
        require(out.size >= 4) { "Swerve fault output must contain four modules" }
        if (!signalsFresh()) {
            out.fill(0x40, 0, 4)
            return
        }
        for (module in 0 until 4) {
            var bits = 0
            for (fault in 0 until 6) {
                if (values[12 + fault * 4 + module] == 1.0) bits = bits or (1 shl fault)
            }
            out[module] = bits
        }
    }

    val pitchDegrees: Double get() = if (signalsFresh()) values[8] else Double.NaN
    val rollDegrees: Double get() = if (signalsFresh()) values[9] else Double.NaN
    val rawGyroYawDegrees: Double get() = if (signalsFresh()) values[10] else Double.NaN
    val yawRateDegreesPerSecond: Double get() = if (signalsFresh()) values[11] else Double.NaN

    /** Four cached wheel speeds in m/s from the same state acquisition used by [read]. */
    fun getModuleSpeeds(out: DoubleArray) {
        require(out.size >= 4) { "Swerve speed output must contain four modules" }
        if (stateFresh()) moduleSpeeds.copyInto(out) else out.fill(Double.NaN, 0, 4)
    }

    /** Immutable cached vendor pose/motion. Unavailable state uses NaN, not a healthy origin. */
    fun read(): DriveState = if (stateFresh()) cachedDrive else unavailableDrive

    private fun elapsedMs(): Double {
        val now = RobotClock.currentTimeMillis()
        val elapsed = now - startedMs
        return if (now >= startedMs && elapsed >= 0L) elapsed.toDouble() else Double.POSITIVE_INFINITY
    }

    private fun signalsFresh(): Boolean {
        if (!signalsReady) return false
        val elapsed = elapsedMs()
        return fastAgeMs + elapsed <= FAST_AGE_MS && faultAgeMs + elapsed <= FAULT_AGE_MS
    }

    private fun stateFresh(): Boolean = stateReady && stateAgeMs + elapsedMs() <= FAST_AGE_MS

    private companion object {
        private const val FAST_AGE_MS = 100.0
        private const val FAULT_AGE_MS = 750.0
        val unavailableDrive = DriveState(
            xVelocityMetersPerSecond = Double.NaN, yVelocityMetersPerSecond = Double.NaN,
            angularVelocityRadiansPerSecond = Double.NaN, odometryX = Double.NaN,
            odometryY = Double.NaN, odometryHeading = Double.NaN
        )
    }
}
