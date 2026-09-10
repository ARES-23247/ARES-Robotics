package com.areslib.ftc.drivetrain

import com.qualcomm.robotcore.hardware.DcMotorEx
import com.qualcomm.robotcore.hardware.AnalogInput
import com.areslib.hardware.drive.SwerveModuleIO
import com.areslib.hardware.drive.SwerveModuleInputs
import com.areslib.util.RobotClock

/**
 * FTC drive/steer pair with a background analog sampler and cached input validity.
 * Encoder resolution and analog range default to SDK metadata, captured once at construction;
 * overrides describe the reported drive revolution and analog full scale. Physical polarity
 * and gear calibration remain the owning robot's configuration.
 *
 * Sampling reads outside locks, with a nominal 5 ms pause between reads. Age is measured from
 * acquisition start, so a blocked/slow read cannot renew freshness on return. Inputs and commands
 * belong to one robot-loop thread; close may run on another thread. Nonzero writes require fresh
 * drive and analog observations. The controller still owns enable/arm, fault recovery and periodic
 * execution: this adapter is not an independent watchdog.
 *
 * Normal updates/writes reuse primitive storage; SDK and exception/log paths may allocate or
 * block. Borrowed devices are never closed. Close attempts both neutral outputs, invalidates
 * samples, interrupts the sampler and reports a join timeout rather than claiming termination.
 * Host tests do not prove physical motor response or real-time deadlines.
 */
class SwerveModuleIOFtc @JvmOverloads constructor(
    private val driveMotor: DcMotorEx,
    private val steerMotor: DcMotorEx,
    private val analogEncoder: AnalogInput,
    val driveTicksPerRevolution: Double = driveMotor.motorType.ticksPerRev,
    val analogRangeVolts: Double = analogEncoder.maxVoltage,
    val sampleTimeoutMs: Long = 100L
) : SwerveModuleIO, AutoCloseable {
    private val radiansPerTick = 2.0 * Math.PI / driveTicksPerRevolution
    init {
        require(driveMotor !== steerMotor) { "Drive and steer motors must be distinct devices" }
        require(driveTicksPerRevolution.isFinite() && driveTicksPerRevolution > 0.0 && radiansPerTick.isFinite()) {
            "Drive encoder resolution must be finite, positive and convertible to radians"
        }
        require(analogRangeVolts.isFinite() && analogRangeVolts > 0.0) { "Analog range must be finite and positive" }
        require(sampleTimeoutMs > 0L) { "Sample timeout must be positive" }
    }

    private val sampleLock = Any()
    private val outputLock = Any()
    @Volatile private var running = true
    @Volatile private var closed = false
    @Volatile private var outputsMayBeActive = false
    private var latestVoltage = 0.0
    private var latestVoltageValid = false
    private var analogStartedAtMs = 0L
    private var driveSnapshotValid = false
    private var driveStartedAtMs = 0L
    private var lastDrivePosition = 0.0
    private var lastDriveVelocity = 0.0
    private var lastSteerAbsolute = 0.0
    private var lastWarningAtMs = 0L
    private var hasWarned = false

    private val samplingThread = Thread {
        try {
            while (running) {
                val started = RobotClock.currentTimeMillis()
                try {
                    val voltage = analogEncoder.voltage
                    synchronized(sampleLock) {
                        if (running && !closed) {
                            analogStartedAtMs = started
                            latestVoltageValid = voltage.isFinite() && voltage in 0.0..analogRangeVolts
                            if (latestVoltageValid) latestVoltage = voltage
                        }
                    }
                } catch (_: Exception) {
                    synchronized(sampleLock) { latestVoltageValid = false }
                }
                try { Thread.sleep(5L) }
                catch (_: InterruptedException) { Thread.currentThread().interrupt(); break }
            }
        } finally {
            running = false
            synchronized(sampleLock) { latestVoltageValid = false }
        }
    }.apply {
        isDaemon = true
        name = "ARES-SwerveModuleIOFtc-Analog-Thread"
    }

    init {
        neutralizeOutputs()
        samplingThread.start()
    }

    /** Reads drive signals once each; invalid channels retain values with false validity flags. */
    override fun updateInputs(inputs: SwerveModuleInputs) {
        try { refreshInputs(inputs) }
        catch (failure: Throwable) {
            synchronized(sampleLock) { driveSnapshotValid = false }
            inputs.drivePositionValid = false
            inputs.driveVelocityValid = false
            inputs.steerAbsoluteValid = false
            try { synchronized(outputLock) { neutralizeOutputs() } }
            catch (cleanup: Throwable) { if (cleanup !== failure) failure.addSuppressed(cleanup) }
            throw failure
        }
    }

    private fun refreshInputs(inputs: SwerveModuleInputs) {
        val started = RobotClock.currentTimeMillis()
        var positionValid = false
        var velocityValid = false
        if (!closed) {
            try {
                val position = driveMotor.currentPosition * radiansPerTick
                if (position.isFinite()) { lastDrivePosition = position; positionValid = true }
            } catch (failure: Exception) { logWarning("Drive position read", failure) }
            try {
                val velocity = driveMotor.velocity * radiansPerTick
                if (velocity.isFinite()) { lastDriveVelocity = velocity; velocityValid = true }
            } catch (failure: Exception) { logWarning("Drive velocity read", failure) }
        }
        val now = RobotClock.currentTimeMillis()
        val steerValid: Boolean
        val observationTime: Long
        synchronized(sampleLock) {
            val driveFresh = !closed && isFresh(started, now)
            positionValid = positionValid && driveFresh
            velocityValid = velocityValid && driveFresh
            driveStartedAtMs = started
            driveSnapshotValid = positionValid && velocityValid
            steerValid = !closed && running && latestVoltageValid && isFresh(analogStartedAtMs, now)
            if (steerValid) lastSteerAbsolute = latestVoltage / analogRangeVolts * (2.0 * Math.PI)
            observationTime = if (steerValid) minOf(started, analogStartedAtMs) else started
        }
        inputs.drivePositionRads = lastDrivePosition
        inputs.driveVelocityRadsPerSec = lastDriveVelocity
        inputs.steerAbsolutePositionRads = lastSteerAbsolute
        inputs.drivePositionValid = positionValid
        inputs.driveVelocityValid = velocityValid
        inputs.steerAbsoluteValid = steerValid
        // Conservative acquisition-start time, not a new timestamp for a retained analog value.
        inputs.timestampMs = observationTime
        if ((!positionValid || !velocityValid || !steerValid) && outputsMayBeActive) {
            synchronized(outputLock) { neutralizeOutputs() }
        }
    }

    /** Commands a coupled normalized pair; invalid/stale/closed commands neutralize both motors. */
    override fun setDesiredPower(drivePower: Double, steerPower: Double) = synchronized(outputLock) {
        val now = RobotClock.currentTimeMillis()
        val ready = synchronized(sampleLock) {
            !closed && running && driveSnapshotValid && latestVoltageValid &&
                isFresh(driveStartedAtMs, now) && isFresh(analogStartedAtMs, now)
        }
        if (!ready || !drivePower.isFinite() || !steerPower.isFinite() || (drivePower == 0.0 && steerPower == 0.0)) {
            neutralizeOutputs()
        } else {
            outputsMayBeActive = true
            try {
                driveMotor.power = drivePower.coerceIn(-1.0, 1.0)
                steerMotor.power = steerPower.coerceIn(-1.0, 1.0)
            } catch (failure: Throwable) {
                try { neutralizeOutputs() }
                catch (cleanup: Throwable) { if (cleanup !== failure) failure.addSuppressed(cleanup) }
                throw failure
            }
        }
    }

    private fun isFresh(started: Long, now: Long): Boolean =
        now >= started && now - started in 0..sampleTimeoutMs

    private fun neutralizeOutputs() {
        outputsMayBeActive = true
        var failure: Throwable? = null
        try { driveMotor.power = 0.0 } catch (caught: Throwable) { failure = caught }
        try { steerMotor.power = 0.0 } catch (caught: Throwable) {
            val first = failure
            if (first == null) failure = caught else if (caught !== first) first.addSuppressed(caught)
        }
        failure?.let { throw it }
        outputsMayBeActive = false
    }

    private fun logWarning(operation: String, failure: Exception) {
        val now = RobotClock.currentTimeMillis()
        if (hasWarned && now >= lastWarningAtMs && now - lastWarningAtMs in 0..2000L) return
        hasWarned = true
        lastWarningAtMs = now
        System.err.println("SwerveModuleIOFtc Warning: $operation failed: $failure")
    }

    /** Attempts both stops and joins only this instance's sampler; repeated calls retry failed stops. */
    override fun close() {
        closed = true
        running = false
        synchronized(sampleLock) { latestVoltageValid = false; driveSnapshotValid = false }
        samplingThread.interrupt()
        var failure: Throwable? = null
        try { synchronized(outputLock) { neutralizeOutputs() } } catch (caught: Throwable) { failure = caught }
        if (Thread.currentThread() !== samplingThread) {
            try {
                samplingThread.join(100L)
                check(!samplingThread.isAlive) { "Analog sampling thread did not stop within 100 ms" }
            } catch (caught: Throwable) {
                if (caught is InterruptedException) Thread.currentThread().interrupt()
                val first = failure
                if (first == null) failure = caught else if (caught !== first) first.addSuppressed(caught)
            }
        }
        failure?.let { throw it }
    }
}
