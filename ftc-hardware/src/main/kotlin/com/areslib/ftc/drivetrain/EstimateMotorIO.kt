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

    /**
     * Synchronously polls physical electrical current draw ($A$) from REV Lynx Hub hardware registers.
     */
    override fun pollSync() {
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
        try {
            cachedPosition = motor.currentPosition.toDouble()
            val now = RobotClock.currentTimeMillis()
            if (lastTime != 0L) {
                val dt = (now - lastTime) / 1000.0
                if (dt > 0.0) {
                    cachedVelocity = (cachedPosition - lastPosition) / dt
                }
            }
            lastPosition = cachedPosition
            lastTime = now
        } catch (_: Exception) {}
    }

    /** Measured motor velocity in encoder ticks per second ($ticks/s$). */
    override val velocity: Double
        get() = cachedVelocity

    /** Measured motor position in total cumulative encoder ticks ($ticks$). */
    override val position: Double
        get() = cachedPosition

    /** Measured electrical current draw in Amperes ($A$). */
    override val currentAmps: Double
        get() {
            val ageMs = RobotClock.currentTimeMillis() - lastCurrentSampleMs
            return if (hasCurrentSample && ageMs in 0..MAX_CURRENT_SAMPLE_AGE_MS) cachedAmps else Double.NaN
        }

    private fun invalidateCurrentSample() {
        cachedAmps = Double.NaN
        hasCurrentSample = false
    }

    /** Resets the local motor encoder zero reference (no-op to preserve cached estimates). */
    override fun resetEncoder() {
        // No-op to avoid side-effects in estimation wrapper
    }

    /** Releases hardware resources upon OpMode termination. */
    override fun close() {
        invalidateCurrentSample()
    }

    private companion object {
        const val MAX_CURRENT_SAMPLE_AGE_MS = 1_000L
    }
}

