package com.areslib.ftc.drivetrain

import com.areslib.control.feedback.PIDController
import com.areslib.math.filter.SlewRateLimiter
import kotlin.math.abs
import kotlin.math.sign

/**
 * Feedforward and feedback power controller for 4-wheel Mecanum drivetrains.
 *
 * Manages static friction feedforward ($k_S$), battery voltage compensation, wheel velocity PID feedback loops,
 * and voltage-scaled slew rate acceleration limits.
 *
 * ### Mathematical Formulation:
 * Feedforward calculation:
 * $$u_{FF} = \left(\frac{v_{desired}}{v_{max}} + k_S \cdot \text{sign}(v_{desired})\right) \cdot \frac{12.0}{V_{battery}}$$
 * Raw requested effort with PID feedback and voltage compensation:
 * $$u_{raw} = \text{coerceIn}\left((u_{FF} + u_{PID}) \cdot \frac{12}{V_{battery}}, -1.0, 1.0\right)$$
 *
 * The drivetrain's safety scale is deliberately not applied here. [MecanumMotorCluster] owns that
 * final hardware-boundary multiplication so [com.areslib.hardware.actuator.MotorIO.power] remains
 * the unscaled request and current estimation does not apply the same scale twice.
 *
 * ### Physical Units & Range Boundaries:
 * - Wheel Surface Velocities: Meters per second ($m/s$).
 * - Measured Voltage: Volts ($V$).
 * - Static Friction $k_S$: Dimensionless normalized voltage feedforward offset $[0.0, 1.0]$.
 * - Output Power: Duty cycle percent $[-1.0, 1.0]$.
 *
 * ### Zero-GC Compliance:
 * Computes all 4 motor power outputs in [calculateMotorPowers] using pre-allocated arrays and primitive variables with zero dynamic heap allocations.
 *
 * @param initialKs Static friction feedforward voltage offset $k_S$.
 * @param motorKp Proportional gain $K_p$ for wheel velocity PID feedback.
 * @param motorKi Integral gain $K_i$ for wheel velocity PID feedback.
 * @param motorKd Derivative gain $K_d$ for wheel velocity PID feedback.
 * @param initialSlewRateLimit Acceleration slew rate limit ($1/s$).
 */
class MecanumDriveFeedforward(
    var initialKs: Double = 0.0,
    var motorKp: Double? = null,
    var motorKi: Double? = null,
    var motorKd: Double? = null,
    var initialSlewRateLimit: Double? = null
) {

    /** Static friction feedforward coefficient $k_S$. */
    var kS: Double = initialKs
    var kV: Double = 0.0
    var kA: Double = 0.0

    private val previousSpeeds = DoubleArray(4)

    private var flController = if (motorKp != null) PIDController(motorKp!!, motorKi ?: 0.0, motorKd ?: 0.0) else null
    private var frController = if (motorKp != null) PIDController(motorKp!!, motorKi ?: 0.0, motorKd ?: 0.0) else null
    private var rlController = if (motorKp != null) PIDController(motorKp!!, motorKi ?: 0.0, motorKd ?: 0.0) else null
    private var rrController = if (motorKp != null) PIDController(motorKp!!, motorKi ?: 0.0, motorKd ?: 0.0) else null

    private var flLimiter: SlewRateLimiter? = null
    private var frLimiter: SlewRateLimiter? = null
    private var rlLimiter: SlewRateLimiter? = null
    private var rrLimiter: SlewRateLimiter? = null

    /** Maximum acceleration slew rate limit. */
    var slewRateLimit: Double? = initialSlewRateLimit
        set(value) {
            val validLimit = value?.takeIf { it.isFinite() && it > 0.0 }
            field = validLimit
            if (validLimit != null) {
                flLimiter = SlewRateLimiter(validLimit)
                frLimiter = SlewRateLimiter(validLimit)
                rlLimiter = SlewRateLimiter(validLimit)
                rrLimiter = SlewRateLimiter(validLimit)
            } else {
                flLimiter = null
                frLimiter = null
                rlLimiter = null
                rrLimiter = null
            }
        }

    /** Enables automatic reduction of slew acceleration limits when battery voltage drops below 12.0V. */
    var enableVoltageCompensatedSlew: Boolean = false

    init {
        flController?.setOutputLimits(-1.0, 1.0)
        frController?.setOutputLimits(-1.0, 1.0)
        rlController?.setOutputLimits(-1.0, 1.0)
        rrController?.setOutputLimits(-1.0, 1.0)

        if (initialSlewRateLimit != null) {
            slewRateLimit = initialSlewRateLimit
        }
    }

    /**
     * Dynamically updates PID gains across all 4 wheel velocity controllers.
     *
     * @param kp Proportional gain $K_p$.
     * @param ki Integral gain $K_i$.
     * @param kd Derivative gain $K_d$.
     */
    fun updateMotorGains(kp: Double, ki: Double, kd: Double) {
        val fl = flController
        if (fl == null) {
            flController = PIDController(kp, ki, kd).apply { setOutputLimits(-1.0, 1.0) }
            frController = PIDController(kp, ki, kd).apply { setOutputLimits(-1.0, 1.0) }
            rlController = PIDController(kp, ki, kd).apply { setOutputLimits(-1.0, 1.0) }
            rrController = PIDController(kp, ki, kd).apply { setOutputLimits(-1.0, 1.0) }
        } else {
            fl.p = kp; fl.i = ki; fl.d = kd
            frController?.let { it.p = kp; it.i = ki; it.d = kd }
            rlController?.let { it.p = kp; it.i = ki; it.d = kd }
            rrController?.let { it.p = kp; it.i = ki; it.d = kd }
        }
    }

    /**
     * Zero-GC calculation cycle for 4 motor duty-cycle powers with feedforward, PID feedback, and slew rate limiting.
     *
     * @param speeds Target 4 wheel surface speeds $[v_{FL}, v_{FR}, v_{RL}, v_{RR}]$ (m/s).
     * @param maxWheelSpeedMps Maximum physical wheel speed capability (m/s).
     * @param batteryVolts Current battery voltage level in Volts ($V$).
     * @param dtSeconds Time step in seconds ($s$).
     * @param useClosedLoopVelocity True if REV Control Hub internal closed-loop velocity PID is enabled.
     * @param ticksPerMeter Encoder resolution in ticks per meter.
     * @param flVel Front-left wheel velocity encoder reading (ticks/s).
     * @param frVel Front-right wheel velocity encoder reading (ticks/s).
     * @param rlVel Rear-left wheel velocity encoder reading (ticks/s).
     * @param rrVel Rear-right wheel velocity encoder reading (ticks/s).
     * @param outputPowers 4-element output array receiving computed motor power duty cycles (-1.0 to 1.0).
     */
    fun calculateMotorPowers(
        speeds: DoubleArray,
        maxWheelSpeedMps: Double,
        batteryVolts: Double,
        dtSeconds: Double,
        useClosedLoopVelocity: Boolean,
        ticksPerMeter: Double,
        flVel: Double,
        frVel: Double,
        rlVel: Double,
        rrVel: Double,
        outputPowers: DoubleArray
    ) {
        if (outputPowers.size < 4) return
        outputPowers[0] = 0.0
        outputPowers[1] = 0.0
        outputPowers[2] = 0.0
        outputPowers[3] = 0.0
        if (speeds.size < 4) return

        val validMaxSpeed = maxWheelSpeedMps.takeIf { it.isFinite() && it > 0.0 } ?: return
        val actualVolts = batteryVolts.takeIf { it.isFinite() && it > 0.1 } ?: return
        val controlDt = dtSeconds.takeIf { it.isFinite() && it > 1e-4 } ?: 0.02
        val maxVolts = 12.0
        val voltageCompensationFactor = maxVolts / actualVolts

        val flTarget = finiteClampedSpeed(speeds[0], validMaxSpeed)
        val frTarget = finiteClampedSpeed(speeds[1], validMaxSpeed)
        val rlTarget = finiteClampedSpeed(speeds[2], validMaxSpeed)
        val rrTarget = finiteClampedSpeed(speeds[3], validMaxSpeed)

        fun applyFeedforward(speedMetersPerSecond: Double, prevSpeed: Double): Double {
            if (abs(speedMetersPerSecond) < 1e-4) return 0.0
            val sign = sign(speedMetersPerSecond)
            val acceleration = (speedMetersPerSecond - prevSpeed) / controlDt
            val velocityFF = speedMetersPerSecond * kV.finiteOrZero()
            val accelFF = acceleration * kA.finiteOrZero()
            val staticFF = sign * kS.finiteOrZero()
            return (velocityFF + accelFF + staticFF)
        }

        if (abs(flTarget) < 1e-4) flController?.reset()
        if (abs(frTarget) < 1e-4) frController?.reset()
        if (abs(rlTarget) < 1e-4) rlController?.reset()
        if (abs(rrTarget) < 1e-4) rrController?.reset()

        val fl = flController
        val fr = frController
        val rl = rlController
        val rr = rrController

        val validTicksPerMeter = ticksPerMeter.takeIf { it.isFinite() && abs(it) > 1e-9 }
        val flFeedback = calculateFeedback(fl, flVel, validTicksPerMeter, flTarget, controlDt, useClosedLoopVelocity)
        val frFeedback = calculateFeedback(fr, frVel, validTicksPerMeter, frTarget, controlDt, useClosedLoopVelocity)
        val rlFeedback = calculateFeedback(rl, rlVel, validTicksPerMeter, rlTarget, controlDt, useClosedLoopVelocity)
        val rrFeedback = calculateFeedback(rr, rrVel, validTicksPerMeter, rrTarget, controlDt, useClosedLoopVelocity)

        var flPower = applyFeedforward(flTarget, previousSpeeds[0]) + flFeedback
        var frPower = applyFeedforward(frTarget, previousSpeeds[1]) + frFeedback
        var rlPower = applyFeedforward(rlTarget, previousSpeeds[2]) + rlFeedback
        var rrPower = applyFeedforward(rrTarget, previousSpeeds[3]) + rrFeedback
        
        previousSpeeds[0] = flTarget
        previousSpeeds[1] = frTarget
        previousSpeeds[2] = rlTarget
        previousSpeeds[3] = rrTarget

        val baseLimit = slewRateLimit
        if (baseLimit != null) {
            val posLimit = if (enableVoltageCompensatedSlew) {
                val scale = ((actualVolts - 7.5) / (12.0 - 7.5)).coerceIn(0.2, 1.0)
                baseLimit * scale
            } else {
                baseLimit
            }
            flLimiter?.setRateLimits(posLimit, -baseLimit)
            frLimiter?.setRateLimits(posLimit, -baseLimit)
            rlLimiter?.setRateLimits(posLimit, -baseLimit)
            rrLimiter?.setRateLimits(posLimit, -baseLimit)
        }

        flLimiter?.let { flPower = it.calculate(flPower, controlDt) }
        frLimiter?.let { frPower = it.calculate(frPower, controlDt) }
        rlLimiter?.let { rlPower = it.calculate(rlPower, controlDt) }
        rrLimiter?.let { rrPower = it.calculate(rrPower, controlDt) }

        outputPowers[0] = finiteClampedPower(flPower * voltageCompensationFactor)
        outputPowers[1] = finiteClampedPower(frPower * voltageCompensationFactor)
        outputPowers[2] = finiteClampedPower(rlPower * voltageCompensationFactor)
        outputPowers[3] = finiteClampedPower(rrPower * voltageCompensationFactor)
    }

    private fun calculateFeedback(
        controller: PIDController?,
        measuredTicksPerSecond: Double,
        ticksPerMeter: Double?,
        targetMetersPerSecond: Double,
        dtSeconds: Double,
        useClosedLoopVelocity: Boolean
    ): Double {
        if (useClosedLoopVelocity || controller == null || ticksPerMeter == null) return 0.0
        val measuredMetersPerSecond = measuredTicksPerSecond.finiteOrZero() / ticksPerMeter
        return controller.calculate(measuredMetersPerSecond, targetMetersPerSecond, dtSeconds).finiteOrZero()
    }

    private fun finiteClampedSpeed(value: Double, maxMagnitude: Double): Double =
        if (value.isFinite()) value.coerceIn(-maxMagnitude, maxMagnitude) else 0.0

    private fun finiteClampedPower(value: Double): Double =
        if (value.isFinite()) value.coerceIn(-1.0, 1.0) else 0.0

    private fun Double.finiteOrZero(): Double = if (isFinite()) this else 0.0
}
