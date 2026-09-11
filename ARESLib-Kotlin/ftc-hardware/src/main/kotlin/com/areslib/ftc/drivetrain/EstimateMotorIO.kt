package com.areslib.ftc.drivetrain

import com.qualcomm.robotcore.hardware.DcMotorEx
import com.areslib.hardware.actuator.MotorIO
import com.areslib.hardware.SyncPolledDevice
import com.areslib.util.RobotClock
import org.firstinspires.ftc.robotcore.external.navigation.CurrentUnit

/**
 * Non-blocking, cached motor IO wrapper for REV Expansion and Control Hub `DcMotorEx` actuators.
 *
 * Caches cumulative encoder position ($ticks$), calculated velocity ($ticks/s$), and electrical current draw ($A$) locally
 * to prevent blocking I2C/USB reads on high-frequency 50Hz–100Hz robot control loops.
 *
 * ### Physical Units & Metrics:
 * - Motor Position: Cumulative encoder ticks ($ticks$).
 * - Motor Velocity: Encoder ticks per second ($ticks/s$).
 * - Electrical Current: Amperes ($A$).
 * - Duty Cycle Output Power: Normalized voltage ratio $[-1.0, 1.0]$.
 *
 * ### Zero-GC Guarantee:
 * Updates internal primitive registers in [updateInputs] and [pollSync] without allocating temporary objects during loop execution.
 *
 * @param motor FTC SDK `DcMotorEx` hardware map instance.
 *
 * @see com.areslib.hardware.actuator.MotorIO
 * @see com.areslib.hardware.SyncPolledDevice
 */
class EstimateMotorIO(private val motor: DcMotorEx) : MotorIO, AutoCloseable, SyncPolledDevice {
    override var power: Double = 0.0
    override var powerScale: Double = 1.0
    private var cachedPosition = 0.0
    private var cachedVelocity = 0.0
    @Volatile private var cachedAmps = Double.NaN
    @Volatile private var lastCurrentSampleMs = 0L
    @Volatile private var hasCurrentSample = false

    private var lastPosition = 0.0
    private var lastTime = 0L
    private var hasPositionSample = false
    private var hasVelocitySample = false
    private var lastRawPosition = 0
    private var hasRawPosition = false
    @Volatile private var closed = false

    /**
     * Synchronously polls physical electrical current draw ($A$) from REV Lynx Hub hardware registers.
     */
    override fun pollSync() {
        if (closed) return
        try {
            val amps = motor.getCurrent(CurrentUnit.AMPS)
            if (amps.isFinite() && amps >= 0.0) {
                cachedAmps = amps
                lastCurrentSampleMs = RobotClock.currentTimeMillis()
                hasCurrentSample = true
            } else {
                invalidateCurrentSample()
            }
        } catch (_: Exception) {
            invalidateCurrentSample()
        }
    }

    /**
     * Updates local position and velocity estimates from REV bulk-read cache registers.
     *
     * Calculates velocity via backward finite difference:
     * $$v = \frac{x_k - x_{k-1}}{\Delta t}$$
     * Zero-GC compliance: zero dynamic heap allocations.
     */
    fun updateInputs() {
        if (closed) return
        try {
            val rawPosition = motor.currentPosition
            val now = RobotClock.currentTimeMillis()
            if (now < 0L) {
                hasPositionSample = false
                return
            }
            // Subtract in the counter's signed 32-bit domain before widening. This unwraps
            // rollover provided fewer than 2^31 ticks elapsed between successful reads.
            cachedPosition = if (hasRawPosition) {
                cachedPosition + (rawPosition - lastRawPosition).toDouble()
            } else {
                rawPosition.toDouble()
            }
            lastRawPosition = rawPosition
            hasRawPosition = true
            val elapsed = now - lastTime
            if (hasPositionSample && now >= lastTime && elapsed in 0L..MAX_POSITION_SAMPLE_AGE_MS) {
                val dt = (now - lastTime) / 1000.0
                if (dt > 0.0) {
                    cachedVelocity = (cachedPosition - lastPosition) / dt
                    hasVelocitySample = true
                } else {
                    // Publish the latest position, but retain the finite-difference baseline
                    // until time advances. Repeated reads must not discard displacement.
                    return
                }
            } else {
                // First/recovered samples, gaps and replay rewinds start a new baseline.
                hasVelocitySample = false
            }
            lastPosition = cachedPosition
            lastTime = now
            hasPositionSample = true
        } catch (_: Exception) {
            hasPositionSample = false
        }
    }

    /** Measured ticks/s; NaN when missing, failed, older than 100 ms, future-dated or closed. */
    override val velocity: Double
        get() = if (positionSampleFresh() && hasVelocitySample) cachedVelocity else Double.NaN

    /** Unwrapped cumulative ticks; NaN under the same observation-freshness rules as [velocity]. */
    override val position: Double
        get() = if (positionSampleFresh()) cachedPosition else Double.NaN

    private fun positionSampleFresh(): Boolean {
        val now = RobotClock.currentTimeMillis()
        return !closed && hasPositionSample && now >= lastTime &&
            now - lastTime in 0L..MAX_POSITION_SAMPLE_AGE_MS
    }

    /** Measured electrical current draw in Amperes ($A$). */
    override val currentAmps: Double
        get() {
            val ageMs = RobotClock.currentTimeMillis() - lastCurrentSampleMs
            return if (!closed && hasCurrentSample && ageMs in 0..MAX_CURRENT_SAMPLE_AGE_MS) cachedAmps else Double.NaN
        }

    private fun invalidateCurrentSample() {
        cachedAmps = Double.NaN
        hasCurrentSample = false
    }

    /**
     * Intentionally leaves the encoder reference unchanged; this read-only wrapper cannot reset hardware.
     * External SDK counter resets require a new wrapper to establish a new cumulative reference.
     */
    override fun resetEncoder() {
        // No-op to avoid side-effects in estimation wrapper
    }

    /** Releases hardware resources upon OpMode termination. */
    override fun close() {
        closed = true
        hasPositionSample = false
        invalidateCurrentSample()
    }

    private companion object {
        const val MAX_CURRENT_SAMPLE_AGE_MS = 1_000L
        private const val MAX_POSITION_SAMPLE_AGE_MS = 100L
    }
}

